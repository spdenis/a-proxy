package com.testing.aproxy.proxy;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.stripPrefix;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import java.net.URI;
import java.util.List;

import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.session.PendingTokenStore;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The browser-facing reverse proxy route: everything that is not served by this
 * application itself is forwarded to the AutoSys web server.
 */
@Configuration(proxyBeanMethods = false)
public class ProxyRouteConfig {

    @Bean
    RouterFunction<ServerResponse> autosysRoute(AutosysProperties properties, ProxyPaths paths) {
        URI upstream = properties.baseUrl();
        String prefix = paths.prefix();

        RequestPredicate forwarded = RequestPredicates.path(prefix.isEmpty() ? "/**" : prefix + "/**");
        for (String own : ProxyPaths.OWN_PREFIXES) {
            forwarded = forwarded.and(RequestPredicates.path(own + "/**").negate());
        }

        var builder = route("autosys-ui")
                .route(forwarded, http())
                .before(uri(upstream));
        int prefixSegments = prefix.isEmpty() ? 0 : prefix.substring(1).split("/").length;
        if (prefixSegments > 0) {
            builder = builder.before(stripPrefix(prefixSegments));
        }
        return builder
                .before(request -> rewriteRequestHeaders(request, upstream, prefix))
                .after((request, response) -> rewriteResponseHeaders(request, response, upstream, prefix))
                .build();
    }

    static ServerRequest rewriteRequestHeaders(ServerRequest request, URI upstream, String prefix) {
        String proxyOrigin = ProxyPaths.proxyOrigin(request.servletRequest());
        return ServerRequest.from(request)
                .headers(headers -> {
                    replace(headers, HttpHeaders.ORIGIN,
                            HeaderRewriter.toUpstream(headers.getFirst(HttpHeaders.ORIGIN), upstream, proxyOrigin, prefix));
                    replace(headers, HttpHeaders.REFERER,
                            HeaderRewriter.toUpstream(headers.getFirst(HttpHeaders.REFERER), upstream, proxyOrigin, prefix));
                    List<String> cookies = headers.get(HttpHeaders.COOKIE);
                    if (cookies != null) {
                        replace(headers, HttpHeaders.COOKIE,
                                HeaderRewriter.stripOwnCookies(String.join("; ", cookies), PendingTokenStore.OWN_COOKIE_PREFIX));
                    }
                })
                .build();
    }

    static ServerResponse rewriteResponseHeaders(ServerRequest request, ServerResponse response, URI upstream,
            String prefix) {
        HttpServletRequest servletRequest = request.servletRequest();
        String proxyOrigin = ProxyPaths.proxyOrigin(servletRequest);
        HttpHeaders headers = response.headers();

        String location = headers.getFirst(HttpHeaders.LOCATION);
        if (location != null) {
            headers.set(HttpHeaders.LOCATION, HeaderRewriter.rewriteLocation(location, upstream, proxyOrigin, prefix));
        }
        String contentLocation = headers.getFirst(HttpHeaders.CONTENT_LOCATION);
        if (contentLocation != null) {
            headers.set(HttpHeaders.CONTENT_LOCATION,
                    HeaderRewriter.rewriteLocation(contentLocation, upstream, proxyOrigin, prefix));
        }
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies != null && !setCookies.isEmpty()) {
            boolean secure = servletRequest.isSecure();
            headers.put(HttpHeaders.SET_COOKIE, setCookies.stream()
                    .map(c -> HeaderRewriter.rewriteSetCookie(c, prefix, secure))
                    .toList());
        }
        if (!servletRequest.isSecure()) {
            // HSTS from AutoSys must not pin the proxy host to HTTPS when it is served over HTTP.
            headers.remove("Strict-Transport-Security");
        }
        return response;
    }

    private static void replace(HttpHeaders headers, String name, String value) {
        if (value == null) {
            headers.remove(name);
        } else {
            headers.set(name, value);
        }
    }
}
