package com.testing.aproxy.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * All AutoSys-specific settings. Endpoint paths, field names and login markers depend on
 * the AutoSys version and are configured, never hard-coded in Java.
 */
@Validated
@ConfigurationProperties("autosys")
public record AutosysProperties(
        @NotNull URI baseUrl,
        /*
         * AutoSys URLs (origins) for the proxy's own logins, in order: a session stays on one until AutoSys
         * drops it, then logs in on the next one. Empty = base-url only. The browser always uses base-url.
         */
        @Nullable List<URI> backendUrls,
        @NotEmpty Set<String> allowedEnvironments,
        @NotBlank String environment,
        @Valid @DefaultValue Proxy proxy,
        @Valid @DefaultValue Session session,
        @Valid @DefaultValue Http http,
        @Valid @DefaultValue Token token,
        @Valid @DefaultValue Webui webui,
        @Valid @DefaultValue Actions actions,
        @Valid @DefaultValue KeepAlive keepAlive,
        @Valid @DefaultValue Capture capture) {

    public AutosysProperties {
        backendUrls = backendUrls == null || backendUrls.isEmpty() ? List.of(baseUrl) : List.copyOf(backendUrls);
    }

    /**
     * Background job status requests that keep each session's AutoSys (Quick View) session from timing
     * out while the API is not used. Off by default.
     */
    public record KeepAlive(
            @DefaultValue("false") boolean enabled,
            /* Time between two requests of a session; skipped when the session talked to AutoSys more recently. */
            @DefaultValue("3m") Duration interval,
            /* Job whose status is requested; empty = the job the session looked up last. */
            @Nullable String job) {

        public KeepAlive {
            job = blankToNull(job);
        }
    }

    /** Job actions ({@code POST /api/v1/jobs/{name}/actions}): force start and other job events. */
    public record Actions(
            @DefaultValue("true") boolean enabled,
            /* Actions per session and minute; more are answered with 429. */
            @DefaultValue("30") int rateLimitPerMinute,
            /* How long the result of a request with an Idempotency-Key is kept and replayed. */
            @DefaultValue("10m") Duration idempotencyTtl,
            /* Search the job once more after an action, to report its status. */
            @DefaultValue("true") boolean readStatusAfter) {
    }

    /**
     * Records proxied AutoSys requests/responses (secrets masked) to files, to learn the
     * UI's internal endpoints. For development only: captures contain job data.
     */
    public record Capture(
            @DefaultValue("false") boolean enabled,
            /* Each proxy run writes into its own sub-directory. */
            @DefaultValue("captures") String dir,
            /* Bodies longer than this are truncated in the capture file. */
            @DefaultValue("1048576") int maxBodyBytes,
            /* Paths matching this regex (static assets) are not recorded. */
            @DefaultValue("(?i).*\\.(js|css|map|png|jpe?g|gif|svg|ico|woff2?|ttf|eot|otf)$") String excludePathPattern) {
    }

    /** Browser-facing reverse proxy settings. */
    public record Proxy(
            /* Path prefix under which AutoSys is exposed; empty means the proxy root. */
            @DefaultValue("") String prefix,
            /* Optional replacement of absolute AutoSys URLs inside text response bodies. */
            @DefaultValue("false") boolean rewriteBodyUrls,
            /*
             * Forward the browser's Host header (and leave Origin/Referer untouched), so AutoSys
             * believes it is served under the proxy's name. Needed for OWASP CSRFGuard, which
             * embeds the request host in its JavaScript and checks it against the page domain.
             */
            @DefaultValue("true") boolean preserveHost,
            /* One INFO line per request on the ACCESS logger. */
            @DefaultValue("true") boolean accessLog,
            /* AutoSys-relative page that the proxy root redirects to, e.g. /ui/Login.html; empty = forward "/". */
            @Nullable String startPage) {

        public Proxy {
            startPage = blankToNull(startPage);
        }

        public String normalizedPrefix() {
            String p = prefix.strip();
            if (p.isEmpty() || p.equals("/")) {
                return "";
            }
            p = p.startsWith("/") ? p : "/" + p;
            return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        }
    }

    public record Session(
            /* 0 = never: the token stays valid until a new login through the proxy replaces it or it is revoked. */
            @DefaultValue("0") Duration idleTtl,
            @DefaultValue("0") Duration absoluteTtl,
            /* How long the freshly issued token can be viewed on /proxy/token. */
            @DefaultValue("5m") Duration tokenPageTtl,
            /* Whether the linked browser's AutoSys pages count as use of the session (refresh its idle time). */
            @DefaultValue("true") boolean browserUseCounts) {
    }

    public record Http(
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout,
            @DefaultValue("50") int maxConnections,
            /* PKCS12/JKS truststore with the internal CA; JVM default trust when unset. */
            @Nullable Resource truststore,
            @Nullable String truststorePassword,
            @DefaultValue("PKCS12") String truststoreType,
            /*
             * Skip the check of the AutoSys server certificate (any certificate and host name is accepted).
             * Only for test servers with self-signed certificates; prefer a truststore.
             */
            @DefaultValue("false") boolean trustAllCertificates) {
    }

    public record Token(@DefaultValue("false") boolean bindClientIp) {
    }

    public record Webui(@Valid @DefaultValue Login login, @Valid @DefaultValue Quickview quickview) {
    }

    /**
     * WCC Quick View (JSF/Trinidad) pages that the proxy drives itself to answer API calls,
     * with its own AutoSys session per proxy session. Paths are relative to the base URL.
     */
    public record Quickview(
            /* The Quick View page that the UI posts its searches to. */
            @DefaultValue("/wcc/quickview/pages/main.jsf") String pagePath,
            /*
             * Send API searches through the Quick View page the browser has open (its tokens, ViewState and
             * session), as seen on its last search; needs one search in the browser after logging in.
             */
            @DefaultValue("true") boolean reuseBrowserPage,
            /* Page that starts a Quick View session; it redirects to the page with the CSRF tokens. */
            @DefaultValue("/wcc/quickview/index.jsp") String entryPath,
            /* Optional page fetched before the backend login (when AutoSys needs its cookies first). */
            @Nullable String loginPage,
            /* id/name of the form that holds the job search. */
            @DefaultValue("jobDetailsForm") String form,
            @DefaultValue("jobNameInput") String searchField,
            @DefaultValue("goButtonID") String searchButton,
            /* Trinidad partial-page event sent with the search. */
            @DefaultValue("autosub") String searchEvent,
            /*
             * Use the AutoSys session of the browser login (its cookies) instead of logging in again;
             * the proxy logs in itself only when that session does not open Quick View.
             */
            @DefaultValue("true") boolean reuseBrowserSession,
            /*
             * Browser cookies not reused; JSESSIONID by default, so API calls get their own Quick View
             * page state and do not disturb the browser's.
             */
            @DefaultValue("JSESSIONID") List<String> excludeBrowserCookies,
            /* Login and Quick View attempts per API call before giving up. */
            @DefaultValue("2") int maxAttempts,
            /*
             * Token the UI's launcher passes to the entry page (with a randomId); the proxy takes it from
             * the browser's requests and opens Quick View with it.
             */
            @DefaultValue("AntiXSRFToken") String xsrfParam,
            /* Referer of the entry page request, as when the launcher opens Quick View. */
            @DefaultValue("/wcc/ui/Launcher.html") String entryReferer,
            /* Tree table that lists a box with its jobs, and the Trinidad event that expands a row of it. */
            @DefaultValue("boxJobResultTable") String boxTable,
            @DefaultValue("show") String expandEvent,
            /*
             * Job events (force start, ...): the dropdown of the results table, chosen with a partial-page
             * event, and the Go button next to it.
             */
            @DefaultValue("jobResultTable:eventId") String eventSelect,
            @DefaultValue("jobResultTable:sendEventButton") String eventButton,
            @DefaultValue("autosub") String eventEvent,
            /*
             * Dropdown value of each action (FORCE_STARTJOB: "1"), used when the response does not list the
             * dropdown's options; when it does, the option is found by its label.
             */
            @Nullable Map<String, String> eventIds,
            /* The dialog that Go opens, its form and its Yes button. */
            @DefaultValue("/wcc/quickview/pages/dialog/confirmationDialog.jsf") String confirmationPath,
            @DefaultValue("confirmationDialog") String confirmationForm,
            @DefaultValue("sendEvtYes") String confirmButton,
            /* Page parameters a dialog URL needs; taken from the Quick View page URL when the dialog's lacks them. */
            @DefaultValue({"AntiXSRFToken", "OWASP-CSRFTOKEN", "conversationContext"}) List<String> dialogParams,
            /* id of the element with the page's (error) messages. */
            @DefaultValue("pageMessages") String messagesId,
            /* Dropdown of the AutoSys servers, shown when the user has access to more than one. */
            @DefaultValue("serverCombo_input") String serverSelect,
            /*
             * Server searched when an API call names none (its label in the dropdown, e.g. QA5); empty = the one
             * the call before chose, at first the page's own choice.
             */
            @Nullable String server,
            @Valid @DefaultValue SendEvent sendEvent) {

        public Quickview {
            loginPage = blankToNull(loginPage);
            server = blankToNull(server);
            eventIds = eventIds == null ? Map.of() : Map.copyOf(eventIds);
        }
    }

    /**
     * The Send Event dialog that Go opens for "Send Event" (e.g. Change Status): its event dropdown (a
     * partial-page event adds the event's fields, such as the status dropdown), OK, and the postback to
     * the page that the browser sends when the dialog has closed.
     */
    public record SendEvent(
            @DefaultValue("/wcc/quickview/pages/dialog/autosysSendEvent.jsf") String path,
            @DefaultValue("sendeventDialog") String form,
            @DefaultValue("eventId") String eventSelect,
            @DefaultValue("statusId") String statusSelect,
            /* Text of the button that sends the event (its id is generated). */
            @DefaultValue("OK") String okButton,
            /* Label of the dialog's comment field; the API request's comment goes there. */
            @DefaultValue("Comment") String commentLabel,
            /* Parameter of the postback after the dialog closed; its value is the Go button. */
            @DefaultValue("rtrn") String returnParam,
            /* Dropdown value of each dialog event (CHANGE_STATUS: "11"), used when the dialog lists no labels. */
            @Nullable Map<String, String> eventIds) {

        public SendEvent {
            eventIds = eventIds == null ? Map.of() : Map.copyOf(eventIds);
        }
    }

    /** How the AutoSys login exchange looks on the wire (filled from discovery D1). */
    public record Login(
            /* Request path of the login submit, relative to the AutoSys base URL. Ant patterns allowed. */
            @DefaultValue("/CHANGE-ME/login") String path,
            @DefaultValue("POST") String method,
            @DefaultValue("username") String usernameField,
            @DefaultValue("password") String passwordField,
            @Valid @DefaultValue Success success,
            /* Text in the response body that indicates a failed login. */
            @Nullable String failureMarker,
            /* Logs the shape (never values) of every POST and its response to help fill in this section. */
            @DefaultValue("false") boolean discoveryLog,
            /* Redirect the browser to /proxy/token after a successful login that ends with a redirect. */
            @DefaultValue("true") boolean redirectToTokenPage,
            /*
             * AutoSys-relative page shown after a successful login, e.g. /ui/Home.html (Ant pattern allowed).
             * The first visit after a captured login goes to /proxy/token first; its "Continue" link leads here.
             */
            @Nullable String landingPage) {

        public Login {
            failureMarker = blankToNull(failureMarker);
            landingPage = blankToNull(landingPage);
        }
    }

    /** All configured rules must hold for the login to count as successful. */
    public record Success(
            @DefaultValue({}) List<Integer> statuses,
            /* Regex the Location header must match. */
            @Nullable String locationPattern,
            /* Regex the Location header must NOT match (e.g. back to the login page with an error flag). */
            @Nullable String locationRejectPattern,
            /* Name of a cookie AutoSys sets on success, e.g. a session cookie. */
            @Nullable String cookie) {

        public Success {
            // Keys left empty in YAML bind as "", which must mean "no rule".
            locationPattern = blankToNull(locationPattern);
            locationRejectPattern = blankToNull(locationRejectPattern);
            cookie = blankToNull(cookie);
        }

        public boolean hasAnyRule() {
            return !statuses.isEmpty() || locationPattern != null || locationRejectPattern != null || cookie != null;
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
