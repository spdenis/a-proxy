package com.testing.aproxy.proxy;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.session.PendingTokenStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Browser entry points:
 * <ul>
 * <li>{@code autosys.proxy.start-page}: the proxy root redirects to this AutoSys page (e.g. the login page).</li>
 * <li>{@code autosys.webui.login.landing-page}: after a captured login that did not end with a redirect
 * (typical for XHR logins), the first visit of this page goes to {@code /proxy/token} first.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class LandingPageFilter extends OncePerRequestFilter {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final ProxyPaths paths;
    private final @Nullable String startPage;
    private final @Nullable String landingPage;

    public LandingPageFilter(AutosysProperties properties, ProxyPaths paths) {
        this.paths = paths;
        this.startPage = properties.proxy().startPage();
        this.landingPage = properties.webui().login().landingPage();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return (startPage == null && landingPage == null) || !"GET".equalsIgnoreCase(request.getMethod())
                || !paths.isProxied(ProxyPaths.pathOf(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = ProxyPaths.pathOf(request).substring(paths.prefix().length());
        String context = request.getContextPath();

        if (startPage != null && (path.isEmpty() || path.equals("/"))) {
            response.sendRedirect(context + paths.prefix() + startPage);
            return;
        }
        if (landingPage != null && MATCHER.match(landingPage, path) && hasShowTokenCookie(request) && acceptsHtml(request)) {
            String query = request.getQueryString();
            String next = paths.prefix() + path + (query == null ? "" : "?" + query);
            response.addHeader(HttpHeaders.SET_COOKIE, clearShowTokenCookie(request, paths));
            response.sendRedirect(context + ProxyPaths.TOKEN_PAGE + "?next="
                    + URLEncoder.encode(next, StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    static String showTokenCookie(HttpServletRequest request, ProxyPaths paths, Duration ttl) {
        return cookie(request, paths, "1", ttl);
    }

    private static String clearShowTokenCookie(HttpServletRequest request, ProxyPaths paths) {
        return cookie(request, paths, "", Duration.ZERO);
    }

    private static String cookie(HttpServletRequest request, ProxyPaths paths, String value, Duration maxAge) {
        return ResponseCookie.from(PendingTokenStore.SHOW_COOKIE, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path(request.getContextPath() + paths.prefix() + "/")
                .maxAge(maxAge)
                .build()
                .toString();
    }

    private static boolean hasShowTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (PendingTokenStore.SHOW_COOKIE.equals(c.getName()) && !c.getValue().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Only top-level page loads, never XHR/fetch calls the UI makes to the same URL. */
    private static boolean acceptsHtml(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains("text/html");
    }
}
