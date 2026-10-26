package com.testing.aproxy.api;

import java.io.IOException;
import java.util.List;

import com.testing.aproxy.session.SessionRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer asp_...} against the session registry. The
 * principal is the {@link com.testing.aproxy.session.ProxySession}. Not a bean on purpose,
 * so it only runs inside the API security chain.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    /** Request attribute with the reason a presented token was rejected, for the 401 answer. */
    public static final String REJECTED = BearerTokenFilter.class.getName() + ".rejected";

    private final SessionRegistry sessions;

    public BearerTokenFilter(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String token = header.substring(BEARER.length()).strip();
            sessions.authenticate(token, request.getRemoteAddr()).ifPresentOrElse(session -> {
                var authentication = UsernamePasswordAuthenticationToken.authenticated(session, null, List.of());
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }, () -> request.setAttribute(REJECTED, sessions.whyRejected(token)));
        }
        chain.doFilter(request, response);
    }
}
