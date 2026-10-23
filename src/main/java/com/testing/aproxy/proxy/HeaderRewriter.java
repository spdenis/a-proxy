package com.testing.aproxy.proxy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Pure functions that keep the browser on the proxy origin: rewriting {@code Location},
 * {@code Set-Cookie}, {@code Origin} and {@code Referer} between proxy and AutoSys.
 */
public final class HeaderRewriter {

    private HeaderRewriter() {
    }

    /**
     * Rewrites a redirect target. Absolute URLs pointing at AutoSys, or at the proxy host
     * with AutoSys' scheme (what AutoSys produces when it receives the proxy's Host header),
     * are moved to the proxy origin; root-relative paths get the proxy prefix. Anything else
     * is left alone.
     */
    public static String rewriteLocation(String location, URI upstream, String proxyOrigin, String prefix) {
        if (location.startsWith("/") && !location.startsWith("//")) {
            return prefix + location;
        }
        URI target;
        try {
            target = URI.create(location.strip());
        } catch (IllegalArgumentException e) {
            return location;
        }
        if (!target.isAbsolute() || !(sameServer(target, upstream) || isProxyHost(target, proxyOrigin))) {
            return location;
        }
        String rawPath = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();
        StringBuilder rewritten = new StringBuilder(proxyOrigin).append(prefix).append(rawPath);
        if (target.getRawQuery() != null) {
            rewritten.append('?').append(target.getRawQuery());
        }
        if (target.getRawFragment() != null) {
            rewritten.append('#').append(target.getRawFragment());
        }
        return rewritten.toString();
    }

    /**
     * Rewrites one {@code Set-Cookie} value: drops {@code Domain} (cookie becomes host-only
     * on the proxy), prefixes {@code Path}, and drops {@code Secure} when the browser talks
     * plain HTTP to the proxy (with {@code SameSite=None} relaxed to {@code Lax}, which
     * browsers require without {@code Secure}).
     */
    public static String rewriteSetCookie(String setCookie, String prefix, boolean secureRequest) {
        String[] parts = setCookie.split(";");
        List<String> out = new ArrayList<>(parts.length + 1);
        out.add(parts[0].strip());
        boolean hasPath = false;
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].strip();
            if (attribute.isEmpty()) {
                continue;
            }
            String name = attributeName(attribute);
            switch (name) {
                case "domain" -> {
                    // host-only cookie on the proxy origin
                }
                case "path" -> {
                    hasPath = true;
                    String value = attribute.substring(attribute.indexOf('=') + 1).strip();
                    out.add("Path=" + prefixedPath(prefix, value));
                }
                case "secure" -> {
                    if (secureRequest) {
                        out.add(attribute);
                    }
                }
                case "samesite" -> {
                    String value = attribute.substring(attribute.indexOf('=') + 1).strip();
                    out.add(!secureRequest && value.equalsIgnoreCase("none") ? "SameSite=Lax" : attribute);
                }
                default -> out.add(attribute);
            }
        }
        if (!hasPath && !prefix.isEmpty()) {
            // Without an explicit Path the browser would derive one from the proxied URL; pin it.
            out.add("Path=" + prefix + "/");
        }
        return String.join("; ", out);
    }

    /** Name of the cookie set by a {@code Set-Cookie} value. */
    public static String cookieName(String setCookie) {
        int eq = setCookie.indexOf('=');
        return (eq < 0 ? setCookie : setCookie.substring(0, eq)).strip();
    }

    /** Maps an Origin/Referer value seen on the proxy back to the AutoSys server. */
    public static @Nullable String toUpstream(@Nullable String value, URI upstream, String proxyOrigin, String prefix) {
        if (value == null) {
            return null;
        }
        String proxyBase = proxyOrigin + prefix;
        if (value.equals(proxyOrigin) || value.equals(proxyBase)) {
            return origin(upstream);
        }
        if (value.startsWith(proxyBase + "/")) {
            return origin(upstream) + value.substring(proxyBase.length());
        }
        return value;
    }

    /** Removes the proxy's own cookies from a {@code Cookie} request header before forwarding. */
    public static @Nullable String stripOwnCookies(@Nullable String cookieHeader, String ownPrefix) {
        if (cookieHeader == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String cookie : cookieHeader.split(";")) {
            String c = cookie.strip();
            if (!c.isEmpty() && !c.startsWith(ownPrefix)) {
                kept.add(c);
            }
        }
        return kept.isEmpty() ? null : String.join("; ", kept);
    }

    public static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private static String prefixedPath(String prefix, String path) {
        if (prefix.isEmpty()) {
            return path;
        }
        return path.equals("/") || path.isEmpty() ? prefix + "/" : prefix + path;
    }

    private static String attributeName(String attribute) {
        int eq = attribute.indexOf('=');
        return (eq < 0 ? attribute : attribute.substring(0, eq)).strip().toLowerCase(Locale.ROOT);
    }

    /** Same host and port as the proxy, whatever the scheme (AutoSys assumes its own, e.g. https). */
    private static boolean isProxyHost(URI target, String proxyOrigin) {
        URI proxy;
        try {
            proxy = URI.create(proxyOrigin);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (target.getHost() == null || !target.getHost().equalsIgnoreCase(proxy.getHost())) {
            return false;
        }
        int proxyPort = effectivePort(proxy);
        return target.getPort() == -1 ? effectivePort(target) == proxyPort || proxy.getPort() == -1
                : target.getPort() == proxyPort;
    }

    private static boolean sameServer(URI a, URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost()) && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
