package com.testing.aproxy.proxy;

import java.io.IOException;

import com.testing.aproxy.config.AutosysProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One line per request on the {@code ACCESS} logger: method, path, status, duration and
 * whether it went to AutoSys or was served by the proxy. Query strings are never logged
 * (they may carry secrets). Disable with {@code autosys.proxy.access-log=false}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger ACCESS = LoggerFactory.getLogger("ACCESS");

    private final boolean enabled;
    private final ProxyPaths paths;
    private final String upstream;

    public AccessLogFilter(AutosysProperties properties, ProxyPaths paths) {
        this.enabled = properties.proxy().accessLog();
        this.paths = paths;
        this.upstream = HeaderRewriter.origin(properties.baseUrl());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !ACCESS.isInfoEnabled() || ProxyPaths.pathOf(request).startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String path = ProxyPaths.pathOf(request);
        String target = paths.isProxied(path) ? "-> " + upstream : "(proxy)";
        try {
            chain.doFilter(request, response);
            ACCESS.info("{} {} {} {} {} ms", request.getMethod(), path, target, response.getStatus(), millis(start));
        } catch (IOException | ServletException | RuntimeException e) {
            ACCESS.warn("{} {} {} failed after {} ms: {}", request.getMethod(), path, target, millis(start),
                    rootMessage(e));
            throw e;
        }
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
