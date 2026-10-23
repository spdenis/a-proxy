package com.testing.aproxy.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.testing.aproxy.audit.AuditLogger;
import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.session.LoginTemplate;
import com.testing.aproxy.session.PendingTokenStore;
import com.testing.aproxy.session.SessionRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Watches the AutoSys login exchange passing through the proxy.
 * <p>
 * Before forwarding it reads username and password from the login request (form or JSON);
 * the request itself is forwarded unchanged. After AutoSys has answered it classifies the
 * response and, on success, creates a proxy session and issues an API token, which the
 * browser can pick up on {@code /proxy/token}. On failure nothing is kept.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LoginCaptureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginCaptureFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final int MAX_JSON_LOGIN_BODY = 64 * 1024;

    private final AutosysProperties.Login login;
    private final ProxyPaths paths;
    private final LoginClassifier classifier;
    private final SessionRegistry sessions;
    private final PendingTokenStore pendingTokens;
    private final AuditLogger audit;
    private final Duration tokenPageTtl;

    public LoginCaptureFilter(AutosysProperties properties, ProxyPaths paths, LoginClassifier classifier,
            SessionRegistry sessions, PendingTokenStore pendingTokens, AuditLogger audit) {
        this.login = properties.webui().login();
        this.paths = paths;
        this.classifier = classifier;
        this.sessions = sessions;
        this.pendingTokens = pendingTokens;
        this.audit = audit;
        this.tokenPageTtl = properties.session().tokenPageTtl();
        if (!classifier.isConfigured()) {
            log.warn("No login success rules configured (autosys.webui.login.success.* / failure-marker): "
                    + "logins will pass through but no API token will be issued. "
                    + "Set autosys.webui.login.discovery-log=true to see what the login exchange looks like.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !paths.isProxied(ProxyPaths.pathOf(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isLoginRequest(request)) {
            if (login.discoveryLog() && "POST".equalsIgnoreCase(request.getMethod())) {
                // Field names only; any body read here is replayed to the proxy.
                LoginRequestWrapper wrapped = wrapForInspection(request);
                List<String> fields = fieldNames(wrapped);
                chain.doFilter(wrapped, response);
                logDiscovery(request, fields, response.getStatus(), response.getHeader(HttpHeaders.LOCATION),
                        response.getHeaders(HttpHeaders.SET_COOKIE), false);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        LoginRequestWrapper wrapped = wrapForInspection(request);
        Credentials credentials = extractCredentials(wrapped);
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(wrapped, cachingResponse);
            if (login.discoveryLog()) {
                logDiscovery(request, fieldNames(wrapped), cachingResponse.getStatus(),
                        cachingResponse.getHeader(HttpHeaders.LOCATION),
                        cachingResponse.getHeaders(HttpHeaders.SET_COOKIE), true);
                log.info("[discovery] login response: contentType={} length={} json={}",
                        cachingResponse.getContentType(), cachingResponse.getContentSize(),
                        jsonShape(cachingResponse.getContentAsByteArray()));
            }
            if (credentials == null) {
                log.warn("Login request to {} did not contain fields '{}'/'{}'; check autosys.webui.login.*",
                        ProxyPaths.pathOf(request), login.usernameField(), login.passwordField());
            } else {
                onLoginResponse(request, cachingResponse, credentials);
            }
        } finally {
            if (credentials != null) {
                credentials.wipe();
            }
            cachingResponse.copyBodyToResponse();
        }
    }

    private void onLoginResponse(HttpServletRequest request, ContentCachingResponseWrapper response,
            Credentials credentials) {
        String clientIp = request.getRemoteAddr();
        int status = response.getStatus();
        String location = response.getHeader(HttpHeaders.LOCATION);
        Collection<String> setCookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        String body = new String(response.getContentAsByteArray(), charsetOf(response));

        LoginClassifier.Result result = classifier.classify(status, location, setCookies, body);
        if (!result.success()) {
            log.info("AutoSys login for user '{}' not accepted: {}", credentials.username(), result.reason());
            audit.loginFailed(credentials.username(), clientIp, result.reason());
            return;
        }

        var issued = sessions.create(credentials.username(), credentials.password(), clientIp,
                credentials.template(), setCookies.stream().map(c -> upstreamCookie(c, paths.prefix())).toList());
        audit.loginCaptured(credentials.username(), issued.session().sessionId(), clientIp);
        String ticket = pendingTokens.put(issued.token(), issued.session());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(PendingTokenStore.COOKIE, ticket)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path(request.getContextPath() + "/proxy")
                .maxAge(tokenPageTtl)
                .build()
                .toString());

        boolean redirect = status >= 300 && status < 400 && location != null;
        if (redirect && login.redirectToTokenPage()) {
            String next = sameOriginPath(location, request);
            response.setHeader(HttpHeaders.LOCATION, request.getContextPath() + ProxyPaths.TOKEN_PAGE
                    + (next == null ? "" : "?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8)));
        } else if (login.landingPage() != null) {
            // e.g. XHR login answered with 200: the UI navigates to the landing page itself; show the token there
            response.addHeader(HttpHeaders.SET_COOKIE, LandingPageFilter.showTokenCookie(request, paths, tokenPageTtl));
        }
        log.info("AutoSys login captured for user '{}'; open {}{} within {} to copy the API token",
                credentials.username(), ProxyPaths.proxyOrigin(request), ProxyPaths.TOKEN_PAGE, tokenPageTtl);
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        if (!login.method().equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = ProxyPaths.pathOf(request).substring(paths.prefix().length());
        return MATCHER.match(login.path(), path.isEmpty() ? "/" : path);
    }

    /** JSON bodies are read up front so they can be inspected and then replayed. */
    private static LoginRequestWrapper wrapForInspection(HttpServletRequest request) throws IOException {
        byte[] body = null;
        if (isJson(request)) {
            body = StreamUtils.copyToByteArray(request.getInputStream());
        }
        return new LoginRequestWrapper(request, body);
    }

    private @Nullable Credentials extractCredentials(LoginRequestWrapper request) throws IOException {
        String username;
        String password;
        LoginTemplate template;
        if (isForm(request)) {
            // The container parses the form; Spring Cloud Gateway's FormFilter re-encodes it for forwarding.
            username = request.getParameter(login.usernameField());
            password = request.getParameter(login.passwordField());
            List<Map.Entry<String, String>> fields = new ArrayList<>();
            request.getParameterMap().forEach((name, values) -> {
                if (!name.equals(login.passwordField())) {
                    for (String value : values) {
                        fields.add(Map.entry(name, value));
                    }
                }
            });
            template = new LoginTemplate(request.getContentType(), null, fields, login.passwordField(),
                    templateHeaders(request));
        } else if (isJson(request)) {
            byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
            if (body.length == 0 || body.length > MAX_JSON_LOGIN_BODY) {
                return null;
            }
            JsonNode json;
            try {
                json = JSON.readTree(body);
            } catch (RuntimeException e) {
                return null;
            } finally {
                Arrays.fill(body, (byte) 0);
            }
            username = jsonField(json, login.usernameField());
            password = jsonField(json, login.passwordField());
            LoginTemplate.removeJsonField(json, login.passwordField());
            template = new LoginTemplate(request.getContentType(), JSON.writeValueAsString(json), List.of(),
                    login.passwordField(), templateHeaders(request));
        } else {
            return null;
        }
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return null;
        }
        return new Credentials(username.strip(), password.toCharArray(), template);
    }

    /** The response's Set-Cookie has already been rewritten for the browser; restore the AutoSys path. */
    static String upstreamCookie(String setCookie, String prefix) {
        if (prefix.isEmpty()) {
            return setCookie;
        }
        return Pattern.compile("(?i)(;\\s*Path=)" + Pattern.quote(prefix) + "(/[^;]*)?")
                .matcher(setCookie).replaceAll(m -> Matcher.quoteReplacement(
                        m.group(1) + (m.group(2) == null ? "/" : m.group(2))));
    }

    /** {@code X-*} headers the UI adds to its login call (never cookies, auth or forwarding headers). */
    private Map<String, String> templateHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("x-") && !lower.startsWith("x-forwarded") && !lower.contains("csrf")
                    && !lower.contains("token") && !lower.contains("auth")) || lower.equals("accept")) {
                headers.put(name, request.getHeader(name));
            }
        }
        return headers;
    }

    /** Supports dotted paths such as {@code credentials.user}. */
    private static @Nullable String jsonField(JsonNode json, String field) {
        JsonNode node = json;
        for (String part : field.split("\\.")) {
            node = node == null ? null : node.get(part);
        }
        return node == null || !node.isValueNode() ? null : node.asString();
    }

    private static List<String> fieldNames(LoginRequestWrapper request) {
        try {
            if (isForm(request)) {
                return new ArrayList<>(request.getParameterMap().keySet());
            }
            if (isJson(request)) {
                JsonNode json = JSON.readTree(request.getInputStream());
                return json == null ? List.of() : new ArrayList<>(json.propertyNames());
            }
        } catch (IOException | RuntimeException e) {
            return List.of("<unreadable>");
        }
        return List.of();
    }

    /**
     * Shape of a JSON login response to help choose success/failure rules: field paths with
     * booleans, numbers and null shown as-is, but every string replaced by {@code <string>}.
     */
    static String jsonShape(byte[] body) {
        if (body.length == 0 || body.length > MAX_JSON_LOGIN_BODY) {
            return "-";
        }
        JsonNode json;
        try {
            json = JSON.readTree(body);
        } catch (RuntimeException e) {
            return "-";
        }
        if (json == null || !(json.isObject() || json.isArray())) {
            return "-";
        }
        List<String> out = new ArrayList<>();
        describe("", json, 0, out);
        return out.toString();
    }

    private static void describe(String path, JsonNode node, int depth, List<String> out) {
        if (out.size() >= 40) {
            return;
        }
        if (node.isObject() && depth < 4) {
            for (String name : node.propertyNames()) {
                describe(path.isEmpty() ? name : path + "." + name, node.get(name), depth + 1, out);
            }
        } else if (node.isArray()) {
            out.add(path + "=<array of " + node.size() + ">");
        } else if (node.isObject()) {
            out.add(path + "=<object>");
        } else if (node.isBoolean() || node.isNumber() || node.isNull()) {
            out.add(path + "=" + node);
        } else {
            out.add(path + "=<string>");
        }
    }

    private void logDiscovery(HttpServletRequest request, List<String> fields, int status, @Nullable String location,
            Collection<String> setCookies, boolean matchedLoginRule) {
        log.info("[discovery] POST {} contentType={} fields={} -> status={} location={} cookieNames={} "
                        + "matchesLoginPath={}",
                ProxyPaths.pathOf(request), request.getContentType(), fields, status, location,
                setCookies.stream().map(HeaderRewriter::cookieName).toList(), matchedLoginRule);
    }

    private static @Nullable String sameOriginPath(String location, HttpServletRequest request) {
        try {
            URI uri = URI.create(location);
            if (uri.isAbsolute() && !ProxyPaths.proxyOrigin(request).equals(HeaderRewriter.origin(uri)
                    + request.getContextPath())) {
                return null;
            }
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                return null;
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isForm(HttpServletRequest request) {
        return contentTypeIs(request, MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    }

    private static boolean isJson(HttpServletRequest request) {
        String ct = request.getContentType();
        return ct != null && ct.toLowerCase(Locale.ROOT).contains("json");
    }

    private static boolean contentTypeIs(HttpServletRequest request, String type) {
        String ct = request.getContentType();
        return ct != null && ct.toLowerCase(Locale.ROOT).startsWith(type);
    }

    private static Charset charsetOf(HttpServletResponse response) {
        try {
            return response.getCharacterEncoding() == null ? StandardCharsets.UTF_8
                    : Charset.forName(response.getCharacterEncoding());
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private record Credentials(String username, char[] password, LoginTemplate template) {

        void wipe() {
            Arrays.fill(password, '\0');
        }

        @Override
        public String toString() {
            return "Credentials[" + username + ", ***]";
        }
    }
}
