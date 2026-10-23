package com.testing.aproxy.proxy;

import java.util.List;

import com.testing.aproxy.config.AutosysProperties;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

/** Which request paths belong to the proxy itself and which are forwarded to AutoSys. */
@Component
public class ProxyPaths {

    /** Paths served by this application; never forwarded, even when AutoSys is mapped at the root. */
    public static final List<String> OWN_PREFIXES = List.of("/api", "/proxy", "/actuator", "/error");

    public static final String TOKEN_PAGE = "/proxy/token";

    private final String prefix;

    public ProxyPaths(AutosysProperties properties) {
        this.prefix = properties.proxy().normalizedPrefix();
    }

    /** Proxy path prefix for AutoSys, {@code ""} when mapped at the root. */
    public String prefix() {
        return prefix;
    }

    public static boolean isOwnPath(String path) {
        for (String own : OWN_PREFIXES) {
            if (path.equals(own) || path.startsWith(own + "/")) {
                return true;
            }
        }
        return false;
    }

    public boolean isProxied(String path) {
        if (isOwnPath(path)) {
            return false;
        }
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
    }

    public static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** Origin (scheme://host[:port]) under which the browser reaches this proxy. */
    public static String proxyOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort || port <= 0 ? "" : ":" + port)
                + request.getContextPath();
    }
}
