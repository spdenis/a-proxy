package com.testing.aproxy.proxy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.testing.aproxy.config.AutosysProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Optional ({@code autosys.proxy.rewrite-body-urls=true}): replaces absolute AutoSys URLs in
 * text responses (HTML, JS, CSS, JSON, XML) with the proxy URL, for UIs that embed the
 * server's own origin. Off by default; enabling it buffers text responses in memory.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BodyUrlRewriteFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final URI upstream;
    private final ProxyPaths paths;

    public BodyUrlRewriteFilter(AutosysProperties properties, ProxyPaths paths) {
        this.enabled = properties.proxy().rewriteBodyUrls();
        this.upstream = properties.baseUrl();
        this.paths = paths;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !paths.isProxied(ProxyPaths.pathOf(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper caching = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new LoginRequestWrapper(request, null), caching);
            if (isText(caching.getContentType()) && caching.getHeader("Content-Encoding") == null) {
                Charset charset = charset(caching);
                String body = new String(caching.getContentAsByteArray(), charset);
                String proxyBase = ProxyPaths.proxyOrigin(request) + paths.prefix();
                String rewritten = rewrite(body, HeaderRewriter.origin(upstream), proxyBase);
                String proxyOrigin = ProxyPaths.proxyOrigin(request);
                if (!proxyOrigin.startsWith(upstream.getScheme() + "://")) {
                    // With preserve-host AutoSys writes the proxy host with its own scheme, e.g. https://localhost:8080
                    String misScheme = upstream.getScheme() + proxyOrigin.substring(proxyOrigin.indexOf("://"));
                    rewritten = rewrite(rewritten, misScheme, proxyBase);
                }
                if (!rewritten.equals(body)) {
                    caching.resetBuffer();
                    caching.getOutputStream().write(rewritten.getBytes(charset));
                }
            }
        } finally {
            caching.copyBodyToResponse();
        }
    }

    static String rewrite(String body, String upstreamOrigin, String proxyBase) {
        String result = body.replace(upstreamOrigin, proxyBase);
        // JSON-escaped form, e.g. https:\/\/host:8443
        return result.replace(upstreamOrigin.replace("/", "\\/"), proxyBase.replace("/", "\\/"));
    }

    private static boolean isText(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.startsWith("text/") || ct.contains("javascript") || ct.contains("json") || ct.contains("xml");
    }

    private static Charset charset(HttpServletResponse response) {
        try {
            return response.getCharacterEncoding() == null ? StandardCharsets.UTF_8
                    : Charset.forName(response.getCharacterEncoding());
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
