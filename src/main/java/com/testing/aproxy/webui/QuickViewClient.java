package com.testing.aproxy.webui;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.testing.aproxy.audit.AuditLogger;
import com.testing.aproxy.capture.Redactor;
import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.proxy.LoginClassifier;
import com.testing.aproxy.session.BrowserPage;
import com.testing.aproxy.session.CredentialVault;
import com.testing.aproxy.session.LoginTemplate;
import com.testing.aproxy.session.PendingTokenStore;
import com.testing.aproxy.session.ProxySession;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Drives WCC Quick View (JSF / Apache MyFaces Trinidad) like a browser, with its own AutoSys
 * session per proxy session: logs in with the captured credentials (replaying the browser's
 * login format), opens Quick View to get the {@code AntiXSRFToken}, {@code conversationContext}
 * and {@code javax.faces.ViewState}, and submits the job search as a partial-page request.
 * When the AutoSys session has expired it logs in again and retries once.
 */
@Component
public class QuickViewClient {

    private static final Logger log = LoggerFactory.getLogger(QuickViewClient.class);
    private static final String VIEW_STATE = "javax.faces.ViewState";
    private static final String TRINIDAD_FORM = "org.apache.myfaces.trinidad.faces.FORM";
    private static final int MAX_REDIRECTS = 6;
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final Pattern QUERY_PARAM = Pattern.compile("[?&]([^=&#]+)=([^&#]*)");
    private static final Pattern FRAGMENT = Pattern.compile("(?is)<fragment>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</fragment>");
    /* parameters of one Trinidad partial-page event, not kept from an earlier request */
    private static final List<String> EVENT_PARAMS = List.of("source", "event", "partial", "partialTargets", "state",
            "value", "path");
    private static final String UNREACHABLE = "autosys-unreachable";
    private static final Redactor MASK = new Redactor(List.of());

    private final CloseableHttpClient http;
    private final CredentialVault vault;
    private final LoginClassifier classifier;
    private final AuditLogger audit;
    /* the browser's AutoSys (its cookies and pages are valid there), and the URLs for the proxy's own logins */
    private final URI browserBase;
    private final List<URI> bases;
    private final AutosysProperties.Login login;
    private final AutosysProperties.Quickview quickview;
    private final @Nullable String startPage;
    private final Cache<ProxySession, State> states;

    public QuickViewClient(CloseableHttpClient autosysHttpClient, CredentialVault vault, LoginClassifier classifier,
            AuditLogger audit, AutosysProperties properties) {
        this.http = autosysHttpClient;
        this.vault = vault;
        this.classifier = classifier;
        this.audit = audit;
        this.browserBase = properties.baseUrl();
        this.bases = properties.backendUrls();
        this.login = properties.webui().login();
        this.quickview = properties.webui().quickview();
        this.startPage = properties.proxy().startPage();
        Duration idle = properties.session().idleTtl();
        // weak keys: the state goes away with the proxy session
        this.states = idle.isZero() ? Caffeine.newBuilder().weakKeys().build()
                : Caffeine.newBuilder().weakKeys().expireAfterAccess(idle).build();
    }

    /** Backend state of one proxy session; guarded by {@link ProxySession#lock()}. */
    static final class State {

        final CookieJar cookies = new CookieJar();
        boolean loggedIn;
        /* the browser's Quick View page in use, and the last one that stopped working */
        @Nullable BrowserPage usedPage;
        @Nullable BrowserPage failedPage;
        HtmlForms.@Nullable Form form;
        @Nullable String pageUrl;
        @Nullable String userAgent;
        /* AutoSys URL in use: the browser's while its session is borrowed, else the own login's (bases[baseIndex]) */
        URI base;
        /* the own logins' URL, the next one after AutoSys dropped the proxy's own session */
        int baseIndex;
        /* the session is the browser's (borrowed cookies or page), not the proxy's own login */
        boolean borrowed;
        /* when this session last sent a request to AutoSys */
        volatile long lastSentNanos;

        State(URI base) {
            this.base = base;
        }

        void reset() {
            cookies.clear();
            loggedIn = false;
            borrowed = false;
            form = null;
            pageUrl = null;
            usedPage = null;
        }
    }

    /** Searches Quick View for a job name (wildcards as the UI allows) and returns the partial-page response. */
    public String searchJob(ProxySession session, String jobName) {
        session.lock().lock();
        try {
            return search(session, state(session), jobName);
        } finally {
            session.lock().unlock();
        }
    }

    /**
     * Searches for a box and expands it in the results tree, as clicking its expand icon does;
     * returns that response, which lists the box and the jobs directly in it.
     */
    public String expandBox(ProxySession session, String boxName) {
        session.lock().lock();
        try {
            State state = state(session);
            String found = search(session, state, boxName);
            Exchange response = postExpand(state, boxName, found);
            String problem = partialResponseProblem(response);
            if (problem != null) {
                state.reset();
                throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-session",
                        "AutoSys Quick View did not expand box " + boxName + ": " + problem);
            }
            remember(state, response.body());
            return response.body();
        } finally {
            session.lock().unlock();
        }
    }

    public enum Outcome {
        /** AutoSys took the event: the confirmation dialog closed normally */
        ACCEPTED,
        /** AutoSys refused it, or Quick View does not offer it for this job; nothing was sent */
        REJECTED,
        /** the event was confirmed but the answer is unclear; it may or may not have been executed */
        UNKNOWN
    }

    /**
     * @param job          the job name as AutoSys shows it
     * @param event        the dropdown option sent, e.g. "Force Start Job"
     * @param confirmation the text of the confirmation dialog
     * @param message      what AutoSys answered (its page messages or the dialog's answer)
     * @param statusAfter  the job's status when searched again afterwards
     */
    public record EventResult(String job, Outcome outcome, @Nullable String event, @Nullable String confirmation,
            @Nullable String message, @Nullable String statusAfter) {
    }

    private record EventChoice(String select, String button, String value, String label) {
    }

    /**
     * Sends a job event as the user does in Quick View: searches the job, chooses the event in the
     * dropdown of the results, presses Go, and answers Yes in the confirmation dialog that opens.
     * Until Yes is sent nothing has happened, and a failure is thrown as a {@link BackendException};
     * once it is sent nothing is retried, and an unclear answer is {@link Outcome#UNKNOWN}.
     */
    public EventResult sendEvent(ProxySession session, String jobName, JobAction action, boolean readStatusAfter) {
        return sendEvent(session, jobName, action, null, null, readStatusAfter);
    }

    /**
     * As {@link #sendEvent(ProxySession, String, JobAction, boolean)}; an action sent through "Send Event"
     * ({@link JobAction#dialogEvent()}) is chosen in the Send Event dialog instead, with {@code status}
     * (for Change Status) and {@code comment} in its fields, and sent with its OK button.
     */
    public EventResult sendEvent(ProxySession session, String jobName, JobAction action, @Nullable String status,
            @Nullable String comment, boolean readStatusAfter) {
        session.lock().lock();
        try {
            State state = state(session);
            String found = search(session, state, jobName);
            JobPage page = JobPage.parse(found);
            // the event goes to the job in the results: it must be the only one there
            if (!page.hasDetailsFor(jobName) || page.results().size() != 1 || page.row(jobName).isEmpty()) {
                throw new BackendException(HttpStatus.NOT_FOUND, "job-not-found", "No job named " + jobName
                        + (page.results().isEmpty() ? "" : " (the search shows " + page.results().size() + " jobs)"));
            }
            String job = page.attributes().get("Name");
            EventChoice choice = choose(found, action);
            if (choice == null) {
                Map<String, String> offered = HtmlForms.selects(found).getOrDefault(quickview.eventSelect(), Map.of());
                return new EventResult(job, Outcome.REJECTED, null, null, "Quick View offers no " + action.label()
                        + " for job " + job + " (it offers " + String.join(", ", offered.values()) + ")", null);
            }
            String what = choice.label() + " of job " + job;

            Exchange chosen = postEvent(state, found, job, choice, true);
            requirePartial(state, chosen, "choosing " + what);
            remember(state, chosen.body());
            Exchange go = postEvent(state, found, job, choice, false);
            requirePartial(state, go, "pressing Go for " + what);
            remember(state, go.body());

            String dialogPath = action.dialogEvent() != null ? quickview.sendEvent().path()
                    : quickview.confirmationPath();
            URI dialog = dialogUri(state, go.body(), dialogPath);
            if (dialog == null) {
                String messages = join(messages(chosen.body()), messages(go.body()));
                if (messages != null) {
                    log.info("[backend] AutoSys refused {} for '{}': {}", what, session.username(), messages);
                    return new EventResult(job, Outcome.REJECTED, choice.label(), null, messages, null);
                }
                log.warn("[backend] Quick View did not open {} after Go ({} for '{}'): {}",
                        dialogPath, what, session.username(), snippet(MASK.text(go.body())));
                throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-unexpected-response",
                        "Quick View did not open its dialog for " + what + "; the event was not sent");
            }
            if (action.dialogEvent() != null) {
                return sendInDialog(session, state, found, job, choice, action, status, comment, dialog,
                        readStatusAfter);
            }
            Landing opened = get(state, dialog, state.pageUrl);
            String dialogBody = opened.exchange().body();
            HtmlForms.Form form = opened.exchange().status() == 200
                    ? HtmlForms.find(dialogBody, quickview.confirmationForm()) : null;
            if (form == null || !dialogBody.contains(quickview.confirmButton())) {
                log.warn("[backend] the confirmation dialog for {} did not open for '{}': {} {} -> {}, body: {}", what,
                        session.username(), "GET", opened.uri().getPath(), opened.exchange().status(),
                        snippet(MASK.text(dialogBody)));
                throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-unexpected-response",
                        "The confirmation dialog for " + what + " did not open (" + opened.uri().getPath()
                                + " answered status " + opened.exchange().status() + "); the event was not sent");
            }
            String question = cut(HtmlForms.text(dialogBody), 500);

            // from here on the event may have been executed: no retry, an unclear answer is UNKNOWN
            Exchange answer;
            try {
                answer = postConfirm(state, opened.uri(), form);
            } catch (BackendException e) {
                state.reset();
                return new EventResult(job, Outcome.UNKNOWN, choice.label(), question,
                        "No answer from AutoSys after confirming: " + e.getMessage(), null);
            }
            boolean closed = answer.status() == 200 && !answer.body().contains("<form")
                    && !answer.body().contains("ViewExpired");
            String message = HtmlForms.text(answer.body());
            log.info("[backend] '{}' confirmed {}: {} {}, {} bytes, answer: {}", session.username(), what,
                    answer.status(), answer.contentType(), answer.body().length(), snippet(MASK.text(answer.body())));
            if (!closed) {
                state.reset();
            }
            return finish(session, state, job, choice.label(), closed, question, message, readStatusAfter);
        } finally {
            session.lock().unlock();
        }
    }

    /** The result once the event was sent, with the job's status searched again when asked for. */
    private EventResult finish(ProxySession session, State state, String job, String event, boolean closed,
            @Nullable String question, @Nullable String message, boolean readStatusAfter) {
        String statusAfter = null;
        if (readStatusAfter) {
            try {
                String after = search(session, state, job);
                statusAfter = JobPage.parse(after).row(job).map(JobPage.JobRow::status).orElse(null);
                message = join(message == null || message.isEmpty() ? null : message, messages(after));
            } catch (BackendException e) {
                log.info("[backend] status of {} after {} not read: {}", job, event, e.getMessage());
            }
        }
        return new EventResult(job, closed ? Outcome.ACCEPTED : Outcome.UNKNOWN, event, question,
                message == null || message.isEmpty() ? null : cut(message, 1000), statusAfter);
    }

    /**
     * The Send Event dialog, as the user fills it: the event (e.g. Change Status) in its dropdown, which
     * adds that event's fields (the status dropdown) with a partial-page event; the status and comment;
     * OK. Then the postback to the page that the browser sends once the dialog has closed, whose page
     * messages say what AutoSys did. Nothing is sent before OK.
     */
    private EventResult sendInDialog(ProxySession session, State state, String found, String job,
            EventChoice choice, JobAction action, @Nullable String status, @Nullable String comment, URI dialog,
            boolean readStatusAfter) {
        AutosysProperties.SendEvent dialogConfig = quickview.sendEvent();
        String event = action.dialogEvent();
        String what = event + (status == null ? "" : " to " + status) + " of job " + job;

        Landing opened = get(state, dialog, state.pageUrl);
        String body = opened.exchange().body();
        HtmlForms.Form form = opened.exchange().status() == 200 ? HtmlForms.find(body, dialogConfig.form()) : null;
        if (form == null) {
            log.warn("[backend] the Send Event dialog for {} did not open for '{}': GET {} -> {}, body: {}", what,
                    session.username(), opened.uri().getPath(), opened.exchange().status(), snippet(MASK.text(body)));
            throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-unexpected-response",
                    "The Send Event dialog for " + what + " did not open (" + opened.uri().getPath()
                            + " answered status " + opened.exchange().status() + "); the event was not sent");
        }
        URI target = form.action().isEmpty() ? opened.uri()
                : withParams(opened.uri().resolve(form.action()), opened.uri().toString());

        Map<String, String> events = HtmlForms.selects(body).getOrDefault(dialogConfig.eventSelect(), Map.of());
        String eventValue = option(events, event, dialogConfig.eventIds().get(action.name()));
        if (eventValue == null) {
            return new EventResult(job, Outcome.REJECTED, choice.label(), null, "The Send Event dialog offers no "
                    + event + " for job " + job + " (it offers " + String.join(", ", events.values()) + ")", null);
        }

        // the dialog's page posts its URL's parameters as fields too
        Map<String, String> fields = new LinkedHashMap<>(queryParams(target.toString()));
        fields.putAll(form.fields());
        EVENT_PARAMS.forEach(fields::remove);
        fields.put(TRINIDAD_FORM, dialogConfig.form());
        fields.put(dialogConfig.eventSelect(), eventValue);
        fields.put("event", quickview.eventEvent());
        fields.put("source", dialogConfig.eventSelect());
        fields.put("partial", "true");
        Exchange chosen = postDialog(state, target, opened.uri(), fields, true);
        requirePartial(state, chosen, "choosing " + event + " in the Send Event dialog");
        String chosenBody = chosen.body();
        String rejected = messages(chosenBody);
        if (rejected != null && !chosenBody.contains(dialogConfig.statusSelect())) {
            return new EventResult(job, Outcome.REJECTED, choice.label(), null, rejected, null);
        }
        // the fields the event added (the status dropdown) and the dialog's new ViewState
        HtmlForms.inputs(chosenBody).forEach((name, value) -> {
            if (!name.equals(TRINIDAD_FORM) && !EVENT_PARAMS.contains(name)) {
                fields.put(name, value);
            }
        });
        String viewState = postscriptViewState(chosenBody, dialogConfig.form());
        if (viewState != null) {
            fields.put(VIEW_STATE, viewState);
        }

        if (action.needsStatus()) {
            Map<String, String> statuses = HtmlForms.selects(chosenBody).getOrDefault(dialogConfig.statusSelect(),
                    Map.of());
            if (statuses.isEmpty()) {
                log.warn("[backend] the Send Event dialog shows no {} dropdown after choosing {} ('{}'): {}",
                        dialogConfig.statusSelect(), event, session.username(), snippet(MASK.text(chosenBody)));
                throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-unexpected-response", "The Send Event "
                        + "dialog showed no statuses for " + event + "; the event was not sent");
            }
            String statusValue = status == null ? null : option(statuses, status, null);
            if (statusValue == null) {
                return new EventResult(job, Outcome.REJECTED, choice.label(), null, "The Send Event dialog offers "
                        + "no status " + status + " (it offers " + String.join(", ", statuses.values()) + ")", null);
            }
            fields.put(dialogConfig.statusSelect(), statusValue);
        }
        String commentField = HtmlForms.labelledField(body, dialogConfig.commentLabel());
        if (comment != null && commentField != null && fields.containsKey(commentField)) {
            fields.put(commentField, comment);
        }
        String ok = HtmlForms.buttonSource(body + chosenBody, dialogConfig.form(), dialogConfig.okButton());
        if (ok == null) {
            throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-unexpected-response", "The Send Event "
                    + "dialog has no " + dialogConfig.okButton() + " button; the event was not sent");
        }
        fields.remove("partial");
        fields.put("event", "");
        fields.put("source", ok);
        String question = "Send Event: " + event + (status == null ? "" : ", status " + status) + ", job " + job;

        // from here on the event may have been executed: no retry, an unclear answer is UNKNOWN
        Exchange answer;
        try {
            answer = postDialog(state, target, opened.uri(), fields, false);
        } catch (BackendException e) {
            state.reset();
            return new EventResult(job, Outcome.UNKNOWN, choice.label(), question,
                    "No answer from AutoSys after OK: " + e.getMessage(), null);
        }
        boolean closed = answer.status() == 200 && !answer.body().contains("<form")
                && !answer.body().contains("ViewExpired");
        log.info("[backend] '{}' sent {}: {} {}, {} bytes, answer: {}", session.username(), what, answer.status(),
                answer.contentType(), answer.body().length(), snippet(MASK.text(answer.body())));
        String message = HtmlForms.text(answer.body());
        if (closed) {
            message = join(message.isEmpty() ? null : message, returnFromDialog(session, state, found, job, choice));
        } else {
            state.reset();
        }
        return finish(session, state, job, choice.label() + ": " + event + (status == null ? "" : " " + status),
                closed, question, message, readStatusAfter);
    }

    /**
     * The postback the page sends when its dialog has closed ({@code rtrn=<Go button>}); returns its page
     * messages. A failure is only logged: the event was sent already.
     */
    private @Nullable String returnFromDialog(ProxySession session, State state, String found, String job,
            EventChoice choice) {
        try {
            Map<String, String> fields = pageFields(state, found);
            fields.put(quickview.searchField(), job);
            fields.put(choice.select(), choice.value());
            fields.putIfAbsent(choice.button(), "");
            fields.put("source", "");
            fields.put("partialTargets", "");
            fields.put("partial", "true");
            fields.put(quickview.sendEvent().returnParam(), choice.button());
            Exchange back = postPartial(state, fields);
            if (partialResponseProblem(back) != null) {
                log.info("[backend] the page did not take the return from the dialog for '{}': {}",
                        session.username(), partialResponseProblem(back));
                state.reset();
                return null;
            }
            remember(state, back.body());
            return messages(back.body());
        } catch (BackendException e) {
            log.info("[backend] no return from the dialog for '{}': {}", session.username(), e.getMessage());
            return null;
        }
    }

    /** The value of the option labelled {@code wanted}, else {@code configured} when no labels are listed; null if not offered. */
    private static @Nullable String option(Map<String, String> options, String wanted, @Nullable String configured) {
        if (options.isEmpty()) {
            return configured;
        }
        return options.entrySet().stream().filter(e -> JobAction.same(e.getValue(), wanted)).map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    /** A POST to a dialog's form: partial-page (Tr-XHR) or the full submit of its button. */
    private Exchange postDialog(State state, URI target, URI dialog, Map<String, String> fields, boolean partial) {
        HttpPost post = new HttpPost(target);
        if (partial) {
            post.setHeader("Tr-XHR-Message", "true");
            post.setHeader("Accept", "*/*");
        } else {
            post.setHeader("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
        }
        post.setHeader("Origin", origin(state));
        post.setHeader("Referer", dialog.toString());
        post.setEntity(new StringEntity(formBody(fields),
                ContentType.APPLICATION_FORM_URLENCODED.withCharset(StandardCharsets.UTF_8)));
        return send(state, post);
    }

    /**
     * The dropdown option of an action: found by its label when the response lists the options, else the
     * configured value. Null when the dropdown does not offer the action.
     */
    private @Nullable EventChoice choose(String found, JobAction action) {
        Map<String, Map<String, String>> selects = HtmlForms.selects(found);
        String select = quickview.eventSelect();
        String button = quickview.eventButton();
        if (!selects.containsKey(select) && select.contains(":")) {
            // the same dropdown in another results table (e.g. of a box)
            String suffix = select.substring(select.lastIndexOf(':'));
            List<String> others = selects.keySet().stream().filter(n -> n.endsWith(suffix)).toList();
            if (others.size() == 1) {
                select = others.getFirst();
                String table = select.substring(0, select.length() - suffix.length());
                button = table + (button.contains(":") ? button.substring(button.lastIndexOf(':')) : ":" + button);
            }
        }
        String configured = quickview.eventIds().get(action.name());
        Map<String, String> options = selects.getOrDefault(select, Map.of());
        if (!options.isEmpty()) {
            Map.Entry<String, String> option = options.entrySet().stream().filter(e -> action.matches(e.getValue()))
                    .findFirst().orElse(null);
            if (option == null) {
                return null;
            }
            if (configured != null && !configured.equals(option.getKey())) {
                log.warn("[backend] the Quick View option '{}' has value {}, not {} as configured in "
                        + "autosys.webui.quickview.event-ids; sending {}", option.getValue(), option.getKey(),
                        configured, option.getKey());
            }
            return new EventChoice(select, button, option.getKey(), option.getValue());
        }
        if (configured == null) {
            throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-unexpected-response", "Quick View did not "
                    + "list its job events and autosys.webui.quickview.event-ids has no value for " + action
                    + "; the event was not sent");
        }
        return new EventChoice(select, button, configured, action.label());
    }

    private void requirePartial(State state, Exchange response, String step) {
        String problem = partialResponseProblem(response);
        if (problem != null) {
            state.reset();
            throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-session",
                    "AutoSys Quick View did not accept " + step + ": " + problem + "; the event was not sent");
        }
    }

    /** Chooses the event in the dropdown ({@code choosing}), or presses Go with it chosen. */
    private Exchange postEvent(State state, String searchResponse, String job, EventChoice choice, boolean choosing) {
        Map<String, String> fields = pageFields(state, searchResponse);
        fields.put(quickview.searchField(), job);
        fields.put(choice.select(), choice.value());
        fields.putIfAbsent(choice.button(), "");
        fields.put("source", choosing ? choice.select() : choice.button());
        fields.put("partialTargets", "");
        fields.put("partial", "true");
        if (choosing) {
            fields.put("state", "");
            fields.put("value", "");
        }
        fields.put("event", quickview.eventEvent());
        return postPartial(state, fields);
    }

    /**
     * The fields the Quick View page submits now: those of its form, the ones the search response added
     * (results table state, buttons) and the latest ViewState.
     */
    private Map<String, String> pageFields(State state, String searchResponse) {
        HtmlForms.Form form = state.form;
        Map<String, String> fields = new LinkedHashMap<>(form.fields());
        EVENT_PARAMS.forEach(fields::remove);
        queryParams(form.action()).forEach(fields::putIfAbsent);
        HtmlForms.Form updated = HtmlForms.find(searchResponse, quickview.form());
        Map<String, String> added = updated != null ? updated.fields() : HtmlForms.inputs(searchResponse);
        added.forEach((name, value) -> {
            if (!name.equals(TRINIDAD_FORM) && !name.equals(VIEW_STATE) && !EVENT_PARAMS.contains(name)) {
                fields.put(name, value);
            }
        });
        fields.put(TRINIDAD_FORM, quickview.form());
        String viewState = form.fields().get(VIEW_STATE);
        if (viewState != null) {
            fields.put(VIEW_STATE, viewState);
        }
        return fields;
    }

    private Exchange postPartial(State state, Map<String, String> fields) {
        HttpPost post = new HttpPost(state.base.resolve(state.form.action()));
        post.setHeader("Tr-XHR-Message", "true");
        post.setHeader("Accept", "*/*");
        post.setHeader("Origin", origin(state));
        if (state.pageUrl != null) {
            post.setHeader("Referer", state.pageUrl);
        }
        post.setEntity(new StringEntity(formBody(fields),
                ContentType.APPLICATION_FORM_URLENCODED.withCharset(StandardCharsets.UTF_8)));
        return send(state, post);
    }

    /**
     * The confirmation dialog that the response to Go opens (its URL is in a script), with the page's
     * tokens added that the browser's scripts add; null when the response opens none.
     */
    private @Nullable URI dialogUri(State state, String goResponse, String path) {
        String text = goResponse.replace("<![CDATA[", "").replace("]]>", "").replace("\\/", "/")
                .replaceAll("(?i)\\\\(?:x26|u0026)", "&").replaceAll("(?i)\\\\(?:x3d|u003d)", "=")
                .replace("&amp;", "&");
        String file = path.substring(path.lastIndexOf('/') + 1);
        Matcher m = Pattern.compile("[^\"'\\s<>()]*" + Pattern.quote(file) + "[^\"'\\s<>()]*").matcher(text);
        if (!m.find()) {
            return null;
        }
        URI page = state.pageUrl != null ? URI.create(state.pageUrl) : state.base.resolve(state.form.action());
        URI dialog;
        try {
            dialog = page.resolve(m.group());
        } catch (IllegalArgumentException e) {
            log.warn("[backend] the confirmation dialog URL in the Quick View response is not usable: {}",
                    e.getMessage());
            return null;
        }
        if (dialog.getHost() != null && !dialog.getRawAuthority().equalsIgnoreCase(state.base.getRawAuthority())) {
            return null;
        }
        return withParams(dialog, page.toString(), state.form.action());
    }

    /** {@code uri} with the configured dialog parameters that it lacks, taken from the first of {@code sources} that has them. */
    private URI withParams(URI uri, String... sources) {
        Map<String, String> present = queryParams(uri.toString());
        StringBuilder url = new StringBuilder(uri.toString());
        for (String name : quickview.dialogParams()) {
            if (present.containsKey(name)) {
                continue;
            }
            for (String source : sources) {
                String value = queryParams(source).get(name);
                if (value != null) {
                    url.append(url.indexOf("?") < 0 ? '?' : '&').append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                            .append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                    break;
                }
            }
        }
        return URI.create(url.toString());
    }

    /** Answers Yes in the confirmation dialog, as its button submits the dialog's form. */
    private Exchange postConfirm(State state, URI dialog, HtmlForms.Form form) {
        Map<String, String> fields = new LinkedHashMap<>(form.fields());
        EVENT_PARAMS.forEach(fields::remove);
        fields.putIfAbsent(TRINIDAD_FORM, quickview.confirmationForm());
        fields.put("source", quickview.confirmButton());
        URI action = form.action().isEmpty() ? dialog : withParams(dialog.resolve(form.action()), dialog.toString());
        HttpPost post = new HttpPost(action);
        post.setHeader("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
        post.setHeader("Origin", origin(state));
        post.setHeader("Referer", dialog.toString());
        post.setEntity(new StringEntity(formBody(fields),
                ContentType.APPLICATION_FORM_URLENCODED.withCharset(StandardCharsets.UTF_8)));
        return send(state, post);
    }

    /** Text of the page messages in a partial-page response (errors such as missing permission); null if none. */
    private @Nullable String messages(String response) {
        Pattern id = Pattern.compile("(?i)\\bid\\s*=\\s*[\"']" + Pattern.quote(quickview.messagesId()) + "[\"']");
        Matcher m = FRAGMENT.matcher(response);
        while (m.find()) {
            if (id.matcher(m.group(1)).find()) {
                String text = HtmlForms.text(m.group(1));
                return text.isEmpty() ? null : text;
            }
        }
        return null;
    }

    private static @Nullable String join(@Nullable String a, @Nullable String b) {
        return a == null ? b : b == null || a.contains(b) ? a : a + " " + b;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    /**
     * Searches a job to keep the session's AutoSys session alive, unless the session is busy (another call
     * holds its lock) or sent a request to AutoSys within {@code quietFor}. Returns whether it searched.
     */
    public boolean keepAlive(ProxySession session, String jobName, Duration quietFor) {
        if (!session.lock().tryLock()) {
            return false;
        }
        try {
            State existing = states.getIfPresent(session);
            if (existing != null && existing.lastSentNanos != 0
                    && System.nanoTime() - existing.lastSentNanos < quietFor.toNanos()) {
                return false;
            }
            search(session, state(session), jobName);
            return true;
        } finally {
            session.lock().unlock();
        }
    }

    private State state(ProxySession session) {
        State state = states.get(session, s -> new State(bases.getFirst()));
        state.userAgent = session.browserUserAgent();
        return state;
    }

    private String search(ProxySession session, State state, String jobName) {
        String lastProblem = "no attempt";
        int attempts = Math.max(1, quickview.maxAttempts()) + bases.size() - 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (state.form == null && !useBrowserPage(session, state)) {
                open(session, state);
            }
            String problem;
            Exchange response = null;
            try {
                response = postSearch(state, jobName);
                problem = partialResponseProblem(response);
            } catch (BackendException e) {
                if (!e.type().equals(UNREACHABLE)) {
                    throw e;
                }
                problem = e.getMessage();
            }
            if (problem == null) {
                remember(state, response.body());
                return response.body();
            }
            lastProblem = problem;
            if (state.usedPage != null) {
                state.failedPage = state.usedPage;
            }
            boolean own = !state.borrowed;
            state.reset();
            // the proxy's own AutoSys session was dropped: the next login goes to the next URL
            String next = own ? nextBase(state) : null;
            log.info("[backend] Quick View session of '{}' is no longer valid ({}); logging in again{}",
                    session.username(), problem, next == null ? "" : " on " + next);
        }
        throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-session",
                "AutoSys Quick View did not accept the request: " + lastProblem);
    }

    /** Moves the session to the next configured AutoSys URL; returns it, or null when there is only one. */
    private @Nullable String nextBase(State state) {
        if (bases.size() < 2) {
            return null;
        }
        state.baseIndex = (state.baseIndex + 1) % bases.size();
        state.base = bases.get(state.baseIndex);
        return state.base.toString();
    }

    /**
     * Takes over the Quick View page the browser has open: its URL tokens, form fields, ViewState
     * and cookies (with its Quick View JSESSIONID), as sent on its latest search.
     */
    private boolean useBrowserPage(ProxySession session, State state) {
        BrowserPage page = session.quickViewPage();
        if (!quickview.reuseBrowserPage()) {
            return false;
        }
        if (page == null || page == state.failedPage) {
            log.info("[backend] {} for '{}'; opening Quick View via {}", page == null
                    ? "no Quick View page seen from the browser yet (open Quick View through the proxy)"
                    : "the browser's Quick View page no longer works (reload Quick View to refresh it)",
                    session.username(), quickview.entryPath());
            return false;
        }
        state.reset();
        Map<String, List<String>> cookies = new LinkedHashMap<>();
        for (String cookie : page.cookieHeader().split(";")) {
            String c = cookie.strip();
            int eq = c.indexOf('=');
            if (eq > 0) {
                cookies.computeIfAbsent(c.substring(0, eq), n -> new ArrayList<>()).add(c.substring(eq + 1));
            }
        }
        cookies.forEach(state.cookies::putBrowser);
        // the browser's page and cookies are valid only on the browser's AutoSys
        state.base = browserBase;
        state.form = new HtmlForms.Form(page.action(), page.fields());
        state.pageUrl = state.base.resolve(page.pageUrl() != null ? page.pageUrl() : page.action()).toString();
        state.loggedIn = true;
        state.borrowed = true;
        state.usedPage = page;
        log.info("[backend] using the browser's Quick View page for '{}' (seen at {}, cookies {})",
                session.username(), page.seenAt(), state.cookies.names());
        return true;
    }

    /**
     * Loads Quick View with the browser's AutoSys session, or after the proxy's own login;
     * leaves {@code state.form} set.
     */
    private void open(ProxySession session, State state) {
        boolean borrowed = false;
        // tried on every opening: the browser's cookies change as the user moves around the UI
        if (!state.loggedIn && quickview.reuseBrowserSession()) {
            state.base = browserBase;
            state.cookies.store(login.path(), session.autosysCookies());
            session.browserCookies().forEach((name, values) -> {
                if (quickview.excludeBrowserCookies().stream().noneMatch(name::equalsIgnoreCase)) {
                    state.cookies.putBrowser(name, values);
                }
            });
            if (state.cookies.names().isEmpty()) {
                log.info("[backend] no AutoSys cookies seen from the browser of '{}' yet (it must load an AutoSys "
                        + "page through the proxy after logging in, and send the {} cookie); logging in as the proxy",
                        session.username(), PendingTokenStore.BROWSER_COOKIE);
            } else {
                state.loggedIn = true;
                state.borrowed = borrowed = true;
            }
        }
        if (!state.loggedIn) {
            loginOnAnyBase(session, state);
        }
        Landing page = get(state, entryUri(session, state), state.base.resolve(quickview.entryReferer()).toString());
        HtmlForms.Form form = quickViewForm(page);
        if (form == null && borrowed) {
            log.info("[backend] the browser's AutoSys session of '{}' did not open Quick View (ended at {} with "
                    + "status {}, sent cookies {}, body: {}); logging in as the proxy", session.username(),
                    page.uri().getPath(), page.exchange().status(), state.cookies.names(),
                    snippet(page.exchange().body()));
            state.reset();
            borrowed = false;
            loginOnAnyBase(session, state);
            page = get(state, entryUri(session, state), state.base.resolve(quickview.entryReferer()).toString());
            form = quickViewForm(page);
        }
        if (form == null) {
            log.warn("[backend] Quick View did not open for '{}': {} {} -> {} {}, sent cookies {}, body: {}",
                    session.username(), "GET", page.uri().getPath(), page.exchange().status(),
                    page.exchange().contentType(), state.cookies.names(), snippet(page.exchange().body()));
            state.reset();
            throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-quickview",
                    (quickview.reuseBrowserPage()
                            ? (session.quickViewPage() == null
                                    ? "No Quick View page seen from your browser: open Quick View through the proxy, "
                                            + "then retry. "
                                    : "Your browser's Quick View page no longer works: reload Quick View, then "
                                            + "retry. ")
                            : "")
                            + "(Opening Quick View separately ended at " + page.uri().getPath() + " with status "
                            + page.exchange().status() + ")");
        }
        state.form = form;
        state.pageUrl = page.uri().toString();
        log.info("[backend] Quick View opened for '{}' with {} ({} form fields, cookies {})", session.username(),
                borrowed ? "the browser's AutoSys session" : "the proxy's own login on " + state.base,
                form.fields().size(), state.cookies.names());
    }

    private HtmlForms.@Nullable Form quickViewForm(Landing page) {
        return page.exchange().status() == 200 ? HtmlForms.find(page.exchange().body(), quickview.form()) : null;
    }

    /** The proxy's own login on the session's URL; on the next ones in turn while AutoSys is unreachable. */
    private void loginOnAnyBase(ProxySession session, State state) {
        state.base = bases.get(state.baseIndex);
        for (int tried = 1; ; tried++) {
            try {
                login(session, state);
                return;
            } catch (BackendException e) {
                if (!e.type().equals(UNREACHABLE) || tried >= bases.size()) {
                    throw e;
                }
                state.reset();
                log.info("[backend] AutoSys at {} unreachable for '{}' ({}); logging in on {}", state.base,
                        session.username(), e.getMessage(), nextBase(state));
            }
        }
    }

    private void login(ProxySession session, State state) {
        if (session.loginRejected() != null) {
            throw new BackendException(HttpStatus.CONFLICT, "autosys-login-rejected", "AutoSys rejected the stored "
                    + "password of '" + session.username() + "' (" + session.loginRejected() + "); the proxy does "
                    + "not try it again, so the account is not locked. Log in to AutoSys through the proxy again");
        }
        LoginTemplate template = session.loginTemplate();
        if (template == null) {
            throw new BackendException(HttpStatus.CONFLICT, "login-not-recorded",
                    "The browser login of this session was not recorded; log in through the proxy again");
        }
        if (login.path().contains("*") || login.path().contains("?")) {
            throw new BackendException(HttpStatus.INTERNAL_SERVER_ERROR, "config",
                    "autosys.webui.login.path must be a literal path for the proxy's own login, not a pattern");
        }
        if (quickview.loginPage() != null) {
            get(state, state.base.resolve(quickview.loginPage()));
        }
        String referer = state.base.resolve(quickview.loginPage() != null ? quickview.loginPage()
                : startPage != null ? startPage : "/").toString();
        URI uri = state.base.resolve(login.path());
        HttpPost post = new HttpPost(uri);
        template.headers().forEach(post::setHeader);
        post.setHeader("Origin", origin(state));
        post.setHeader("Referer", referer);
        String body = vault.withPassword(session.credentials(), template::body);
        post.setEntity(new StringEntity(body, ContentType.parse(template.contentType())));
        Exchange response = send(state, post);

        LoginClassifier.Result result = classifier.classify(response.status(), response.location(),
                response.setCookies(), response.body());
        audit.backendLogin(session.username(), session.sessionId(), result.success(),
                result.reason() + " on " + state.base);
        if (!result.success()) {
            session.loginRejected(result.reason() + " on " + state.base);
            log.warn("[backend] AutoSys rejected the proxy's login for '{}': {}; response {} {}, cookies {}, "
                    + "body: {}; sent headers {}", session.username(), result.reason(), response.status(),
                    response.contentType(), response.setCookies().stream().map(c -> c.split("=", 2)[0]).toList(),
                    snippet(response.body()), Arrays.stream(post.getHeaders()).map(Header::getName).toList());
            throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-login",
                    "AutoSys rejected the proxy's login for '" + session.username() + "': " + result.reason());
        }
        if (response.location() != null) {
            get(state, uri.resolve(response.location()));
        }
        state.loggedIn = true;
        log.info("[backend] logged in to AutoSys at {} as '{}' (cookies {})", state.base, session.username(),
                state.cookies.names());
        if (state.cookies.names().isEmpty()) {
            log.warn("[backend] the login response set no cookies: the AutoSys UI probably sets its session cookie "
                    + "in JavaScript, which the proxy's own login cannot do. Use the API after browsing AutoSys "
                    + "through the proxy, so the proxy sees the browser's cookies.");
        }
    }

    private Exchange postSearch(State state, String jobName) {
        HtmlForms.Form form = state.form;
        Map<String, String> fields = new LinkedHashMap<>(form.fields());
        // tokens the action carries (AntiXSRFToken, conversationContext) are also sent as fields
        Map<String, String> actionParams = queryParams(form.action());
        actionParams.forEach(fields::putIfAbsent);
        fields.putIfAbsent(TRINIDAD_FORM, quickview.form());
        fields.put(quickview.searchField(), jobName);
        fields.put("source", quickview.searchButton());
        fields.put("state", "");
        fields.put("value", "");
        fields.put("event", quickview.searchEvent());
        fields.put("partial", "true");
        return postPartial(state, fields);
    }

    /**
     * Expands the first row (the box) of the results tree. The form now holds the fields the search
     * response added (tree table state and buttons), as the browser's page does.
     */
    private Exchange postExpand(State state, String boxName, String searchResponse) {
        HtmlForms.Form form = state.form;
        Map<String, String> fields = new LinkedHashMap<>(form.fields());
        queryParams(form.action()).forEach(fields::putIfAbsent);
        HtmlForms.Form updated = HtmlForms.find(searchResponse, quickview.form());
        Map<String, String> added = updated != null ? updated.fields() : HtmlForms.inputs(searchResponse);
        added.forEach((name, value) -> {
            if (!name.equals(TRINIDAD_FORM)) {
                fields.put(name, value);
            }
        });
        fields.put(TRINIDAD_FORM, quickview.form());
        fields.put(quickview.searchField(), boxName);
        fields.putIfAbsent(quickview.boxTable() + ":_sm", "");
        fields.put("source", quickview.boxTable());
        fields.put("partialTargets", quickview.boxTable());
        fields.put("partial", "true");
        fields.put("state", "");
        fields.put("value", "");
        fields.put("event", quickview.expandEvent());
        fields.put("path", "0");
        return postPartial(state, fields);
    }

    /** Null when the response is a usable Trinidad partial-page response, else why not. */
    static @Nullable String partialResponseProblem(Exchange response) {
        if (response.status() >= 300 && response.status() < 400) {
            return "redirect to " + URI.create(response.location() == null ? "" : response.location()).getPath();
        }
        if (response.status() != 200) {
            return "status " + response.status();
        }
        String body = response.body();
        if (body.contains("<redirect")) {
            return "partial-page redirect";
        }
        if (body.contains("ViewExpired")) {
            return "view expired";
        }
        if (!body.contains("<content") && !body.contains("<partial-response")) {
            return "not a partial-page response (" + response.contentType() + ")";
        }
        return null;
    }

    /** The next request must carry the ViewState (and form action) of the latest response. */
    private void remember(State state, String body) {
        if (state.form == null) {
            return;
        }
        HtmlForms.Form updated = HtmlForms.find(body, quickview.form());
        String viewState = updated != null ? updated.fields().get(VIEW_STATE) : postscriptViewState(body, quickview.form());
        if (updated == null && viewState == null) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>(state.form.fields());
        if (viewState != null) {
            fields.put(VIEW_STATE, viewState);
        }
        String action = updated == null || updated.action().isEmpty() ? state.form.action() : updated.action();
        state.form = new HtmlForms.Form(action, fields);
    }

    /** The ViewState in the form's {@code tr_<form>_Postscript} fragment, sent when only parts of the page change. */
    private @Nullable String postscriptViewState(String body, String form) {
        Matcher m = FRAGMENT.matcher(body);
        String id = "id=\"tr_" + form + "_Postscript\"";
        while (m.find()) {
            if (m.group(1).contains(id)) {
                return HtmlForms.inputs(m.group(1)).get(VIEW_STATE);
            }
        }
        return null;
    }

    private record Landing(URI uri, Exchange exchange) {
    }

    /** GET that follows same-host redirects, as the browser would. */
    /**
     * The entry page as the UI's launcher opens it: {@code index.jsp?randomId=..&AntiXSRFToken=..}
     * with the token the browser sent; without one AutoSys answers 403.
     */
    private URI entryUri(ProxySession session, State state) {
        String token = session.xsrfToken();
        if (token == null) {
            log.info("[backend] no {} seen from the browser of '{}' yet; opening Quick View without it",
                    quickview.xsrfParam(), session.username());
            return state.base.resolve(quickview.entryPath());
        }
        return state.base.resolve(quickview.entryPath() + "?randomId=" + ThreadLocalRandom.current().nextLong(1L << 52)
                + "&" + URLEncoder.encode(quickview.xsrfParam(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    private Landing get(State state, URI uri) {
        return get(state, uri, null);
    }

    private Landing get(State state, URI uri, @Nullable String referer) {
        URI current = uri;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            HttpGet get = new HttpGet(current);
            get.setHeader("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
            if (referer != null) {
                get.setHeader("Referer", referer);
            }
            Exchange response = send(state, get);
            if (response.location() == null || response.status() < 300 || response.status() >= 400) {
                return new Landing(current, response);
            }
            URI next = current.resolve(response.location());
            if (next.getHost() != null && !next.getAuthority().equalsIgnoreCase(state.base.getAuthority())) {
                throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-redirect",
                        "AutoSys redirected to another host (" + next.getHost() + "); not supported");
            }
            current = next;
        }
        throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-redirect", "Too many redirects from AutoSys");
    }

    record Exchange(int status, @Nullable String location, List<String> setCookies, @Nullable String contentType,
            String body) {
    }

    private Exchange send(State state, HttpUriRequestBase request) {
        String path = request.getPath().split("\\?", 2)[0];
        String cookie = state.cookies.header(path);
        if (cookie != null) {
            request.setHeader("Cookie", cookie);
        }
        // as the user's browser: a filter in front of AutoSys may refuse unknown clients
        request.setHeader("User-Agent", state.userAgent == null ? "Mozilla/5.0 (compatible; a-proxy)" : state.userAgent);
        // dates in the tables are parsed in this locale; bodies are read uncompressed
        request.setHeader("Accept-Language", "en-US,en;q=0.8");
        request.setHeader("Accept-Encoding", "identity");
        long started = System.nanoTime();
        state.lastSentNanos = started;
        try {
            Exchange exchange = http.execute(request, response -> {
                List<String> setCookies = Arrays.stream(response.getHeaders("Set-Cookie")).map(Header::getValue)
                        .toList();
                Header location = response.getFirstHeader("Location");
                Header type = response.getFirstHeader("Content-Type");
                String body = response.getEntity() == null ? ""
                        : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8, MAX_BODY);
                return new Exchange(response.getCode(), location == null ? null : location.getValue(), setCookies,
                        type == null ? null : type.getValue(), body);
            });
            state.cookies.store(path, exchange.setCookies());
            log.debug("[backend] {} {} -> {} {} bytes in {} ms", request.getMethod(), path, exchange.status(),
                    exchange.body().length(), (System.nanoTime() - started) / 1_000_000);
            return exchange;
        } catch (IOException e) {
            throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-unreachable",
                    "AutoSys request failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String snippet(String body) {
        String s = body.replaceAll("\\s+", " ").strip();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static String origin(State state) {
        return state.base.getScheme() + "://" + state.base.getRawAuthority();
    }

    static Map<String, String> queryParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = QUERY_PARAM.matcher(url);
        while (m.find()) {
            params.putIfAbsent(URLDecoder.decode(m.group(1), StandardCharsets.UTF_8),
                    URLDecoder.decode(m.group(2), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String formBody(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        fields.forEach((name, value) -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return out.toString();
    }
}
