package com.testing.aproxy.proxy;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.preserveHostHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.stripPrefix;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.config.AutosysServers;
import com.testing.aproxy.session.PendingTokenStore;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The browser-facing reverse proxy route: everything that is not served by this
 * application itself is forwarded to the AutoSys web server in use ({@link AutosysServers}).
 * <p>
 * Failover: when the server cannot be connected to, the next one becomes current and the same
 * request goes there (nothing reached AutoSys, so even a login POST is safe to send again). When it
 * answers 502/503/504, later requests go to the next server; the request itself is sent again
 * only when it has no side effects (GET, HEAD), else the browser gets the error once.
 */
@Configuration(proxyBeanMethods = false)
public class ProxyRouteConfig {

    private static final Logger log = LoggerFactory.getLogger(ProxyRouteConfig.class);

    /** Request attribute: the AutoSys URL the request was last sent to. */
    public static final String SERVER_ATTRIBUTE = ProxyRouteConfig.class.getName() + ".server";

    /* answers meaning the server (or the load balancer in front of it) cannot serve requests now */
    private static final Set<Integer> UNAVAILABLE = Set.of(502, 503, 504);
    private static final Set<HttpMethod> SAFE_TO_REPEAT = Set.of(HttpMethod.GET, HttpMethod.HEAD);

    @Bean
    RouterFunction<ServerResponse> autosysRoute(AutosysProperties properties, ProxyPaths paths,
            AutosysServers servers) {
        String prefix = paths.prefix();

        RequestPredicate forwarded = RequestPredicates.path(prefix.isEmpty() ? "/**" : prefix + "/**");
        for (String own : ProxyPaths.OWN_PREFIXES) {
            forwarded = forwarded.and(RequestPredicates.path(own + "/**").negate());
        }

        boolean preserveHost = properties.proxy().preserveHost();
        HandlerFunction<ServerResponse> proxy = http();
        var builder = route("autosys-ui")
                .route(forwarded, request -> forward(request, proxy, servers, prefix, preserveHost));
        int prefixSegments = prefix.isEmpty() ? 0 : prefix.substring(1).split("/").length;
        if (prefixSegments > 0) {
            builder = builder.before(stripPrefix(prefixSegments));
        }
        if (preserveHost) {
            builder = builder.before(preserveHostHeader());
        }
        return builder.build();
    }

    /** Sends the request to the server in use; on to the next ones while a server is not usable. */
    static ServerResponse forward(ServerRequest request, HandlerFunction<ServerResponse> proxy, AutosysServers servers,
            String prefix, boolean preserveHost) throws Exception {
        int triesLeft = servers.all().size();
        while (true) {
            triesLeft--;
            URI upstream = servers.current();
            request.servletRequest().setAttribute(SERVER_ATTRIBUTE, upstream);
            ServerRequest outgoing = uri(upstream).apply(rewriteRequestHeaders(request, upstream, prefix,
                    preserveHost));
            ServerResponse response;
            try {
                response = proxy.handle(outgoing);
            } catch (Exception e) {
                String failure = connectFailure(e);
                if (failure == null) {
                    throw e;
                }
                URI next = servers.failed(upstream, failure);
                if (triesLeft <= 0 || next.equals(upstream)) {
                    throw e;
                }
                log.info("[failover] {} {}: AutoSys at {} not reachable ({}); sending it to {}", request.method(),
                        request.path(), upstream, failure, next);
                continue;
            }
            int status = response.statusCode().value();
            if (UNAVAILABLE.contains(status)) {
                URI next = servers.failed(upstream, "answered " + status + " to " + request.method() + " "
                        + request.path());
                if (triesLeft > 0 && !next.equals(upstream) && SAFE_TO_REPEAT.contains(request.method())) {
                    discard(outgoing);
                    log.info("[failover] {} {}: AutoSys at {} answered {}; sending it to {}", request.method(),
                            request.path(), upstream, status, next);
                    continue;
                }
            }
            return rewriteResponseHeaders(outgoing, response, upstream, prefix);
        }
    }

    /**
     * Why the connection to AutoSys could not be made, or null when the failure came later (then the
     * request may have reached AutoSys and must not be sent again).
     */
    static String connectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException || t instanceof NoRouteToHostException
                    || t instanceof UnknownHostException || t instanceof ConnectTimeoutException) {
                return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            }
            if (t instanceof InterruptedIOException && !(t instanceof ConnectTimeoutException)) {
                return null; // read timeout: AutoSys may be working on the request
            }
        }
        return null;
    }

    /** Closes the AutoSys response that is not passed on to the browser, so its connection is freed. */
    private static void discard(ServerRequest outgoing) {
        Object response = outgoing.attributes().get(MvcUtils.CLIENT_RESPONSE_ATTR);
        if (response instanceof ClientHttpResponse r) {
            r.close();
        }
    }

    static ServerRequest rewriteRequestHeaders(ServerRequest request, URI upstream, String prefix,
            boolean preserveHost) {
        String proxyOrigin = ProxyPaths.proxyOrigin(request.servletRequest());
        return ServerRequest.from(request)
                .headers(headers -> {
                    if (!preserveHost) {
                        // AutoSys sees its own host name, so browser-side origins must match it.
                        replace(headers, HttpHeaders.ORIGIN, HeaderRewriter.toUpstream(
                                headers.getFirst(HttpHeaders.ORIGIN), upstream, proxyOrigin, prefix));
                        replace(headers, HttpHeaders.REFERER, HeaderRewriter.toUpstream(
                                headers.getFirst(HttpHeaders.REFERER), upstream, proxyOrigin, prefix));
                    }
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
