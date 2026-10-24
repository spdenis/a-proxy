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

    private final CloseableHttpClient http;
    private final CredentialVault vault;
    private final LoginClassifier classifier;
    private final AuditLogger audit;
    private final URI base;
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
        this.base = properties.baseUrl();
        this.login = properties.webui().login();
        this.quickview = properties.webui().quickview();
        this.startPage = properties.proxy().startPage();
        Duration idle = properties.session().idleTtl();
        // weak keys: the state goes away with the proxy session
        this.states = Caffeine.newBuilder().weakKeys().expireAfterAccess(idle).build();
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

        void reset() {
            cookies.clear();
            loggedIn = false;
            form = null;
            pageUrl = null;
            usedPage = null;
        }
    }

    /** Searches Quick View for a job name (wildcards as the UI allows) and returns the partial-page response. */
    public String searchJob(ProxySession session, String jobName) {
        session.lock().lock();
        try {
            State state = states.get(session, s -> new State());
            state.userAgent = session.browserUserAgent();
            String lastProblem = "no attempt";
            for (int attempt = 1; attempt <= Math.max(1, quickview.maxAttempts()); attempt++) {
                if (state.form == null && !useBrowserPage(session, state)) {
                    open(session, state);
                }
                Exchange response = postSearch(state, jobName);
                String problem = partialResponseProblem(response);
                if (problem == null) {
                    remember(state, response.body());
                    return response.body();
                }
                lastProblem = problem;
                if (state.usedPage != null) {
                    state.failedPage = state.usedPage;
                }
                log.info("[backend] Quick View session of '{}' is no longer valid ({}); logging in again",
                        session.username(), problem);
                state.reset();
            }
            throw new BackendException(HttpStatus.BAD_GATEWAY, "autosys-session",
                    "AutoSys Quick View did not accept the request: " + lastProblem);
        } finally {
            session.lock().unlock();
        }
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
        state.form = new HtmlForms.Form(page.action(), page.fields());
        state.pageUrl = base.resolve(page.action()).toString();
        state.loggedIn = true;
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
                borrowed = true;
            }
        }
        if (!state.loggedIn) {
            login(session, state);
        }
        Landing page = get(state, entryUri(session), base.resolve(quickview.entryReferer()).toString());
        HtmlForms.Form form = quickViewForm(page);
        if (form == null && borrowed) {
            log.info("[backend] the browser's AutoSys session of '{}' did not open Quick View (ended at {} with "
                    + "status {}, sent cookies {}, body: {}); logging in as the proxy", session.username(),
                    page.uri().getPath(), page.exchange().status(), state.cookies.names(),
                    snippet(page.exchange().body()));
            state.reset();
            borrowed = false;
            login(session, state);
            page = get(state, entryUri(session), base.resolve(quickview.entryReferer()).toString());
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
                borrowed ? "the browser's AutoSys session" : "the proxy's own login", form.fields().size(),
                state.cookies.names());
    }

    private HtmlForms.@Nullable Form quickViewForm(Landing page) {
        return page.exchange().status() == 200 ? HtmlForms.find(page.exchange().body(), quickview.form()) : null;
    }

    private void login(ProxySession session, State state) {
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
            get(state, base.resolve(quickview.loginPage()));
        }
        String referer = base.resolve(quickview.loginPage() != null ? quickview.loginPage()
                : startPage != null ? startPage : "/").toString();
        URI uri = base.resolve(login.path());
        HttpPost post = new HttpPost(uri);
        template.headers().forEach(post::setHeader);
        post.setHeader("Origin", origin());
        post.setHeader("Referer", referer);
        String body = vault.withPassword(session.credentials(), template::body);
        post.setEntity(new StringEntity(body, ContentType.parse(template.contentType())));
        Exchange response = send(state, post);

        LoginClassifier.Result result = classifier.classify(response.status(), response.location(),
                response.setCookies(), response.body());
        audit.backendLogin(session.username(), session.sessionId(), result.success(), result.reason());
        if (!result.success()) {
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
        log.info("[backend] logged in to AutoSys as '{}' (cookies {})", session.username(), state.cookies.names());
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

        HttpPost post = new HttpPost(base.resolve(form.action()));
        post.setHeader("Tr-XHR-Message", "true");
        post.setHeader("Accept", "*/*");
        post.setHeader("Origin", origin());
        if (state.pageUrl != null) {
            post.setHeader("Referer", state.pageUrl);
        }
        post.setEntity(new StringEntity(formBody(fields),
                ContentType.APPLICATION_FORM_URLENCODED.withCharset(StandardCharsets.UTF_8)));
        return send(state, post);
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
        HtmlForms.Form updated = HtmlForms.find(body, quickview.form());
        if (updated == null || state.form == null) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>(state.form.fields());
        String viewState = updated.fields().get(VIEW_STATE);
        if (viewState != null) {
            fields.put(VIEW_STATE, viewState);
        }
        String action = updated.action().isEmpty() ? state.form.action() : updated.action();
        state.form = new HtmlForms.Form(action, fields);
    }

    private record Landing(URI uri, Exchange exchange) {
    }

    /** GET that follows same-host redirects, as the browser would. */
    /**
     * The entry page as the UI's launcher opens it: {@code index.jsp?randomId=..&AntiXSRFToken=..}
     * with the token the browser sent; without one AutoSys answers 403.
     */
    private URI entryUri(ProxySession session) {
        String token = session.xsrfToken();
        if (token == null) {
            log.info("[backend] no {} seen from the browser of '{}' yet; opening Quick View without it",
                    quickview.xsrfParam(), session.username());
            return base.resolve(quickview.entryPath());
        }
        return base.resolve(quickview.entryPath() + "?randomId=" + ThreadLocalRandom.current().nextLong(1L << 52)
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
            if (next.getHost() != null && !next.getAuthority().equalsIgnoreCase(base.getAuthority())) {
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

    private String origin() {
        return base.getScheme() + "://" + base.getRawAuthority();
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
