package com.testing.aproxy.webui;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cookies of the proxy's own AutoSys session (one host). Paths matter: WCC sets cookies with
 * the same name for different applications, e.g. JSESSIONID on {@code /wcc/quickview}.
 */
final class CookieJar {

    private record Cookie(String name, String value, String path) {
    }

    private final Map<String, Cookie> cookies = new LinkedHashMap<>();

    void store(String requestPath, List<String> setCookies) {
        for (String header : setCookies) {
            String[] parts = header.split(";");
            int eq = parts[0].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = parts[0].substring(0, eq).strip();
            String value = parts[0].substring(eq + 1).strip();
            String path = defaultPath(requestPath);
            boolean delete = false;
            for (int i = 1; i < parts.length; i++) {
                String attr = parts[i].strip();
                String lower = attr.toLowerCase(Locale.ROOT);
                if (lower.startsWith("path=")) {
                    path = attr.substring(5).strip();
                } else if (lower.startsWith("max-age=")) {
                    delete |= attr.substring(8).strip().startsWith("-") || attr.substring(8).strip().equals("0");
                } else if (lower.startsWith("expires=")) {
                    delete |= lower.contains("1970");
                }
            }
            String key = name + "|" + path;
            if (delete) {
                cookies.remove(key);
            } else {
                cookies.put(key, new Cookie(name, value, path));
            }
        }
    }

    /**
     * Cookies of one name as the browser sent them (paths unknown, so sent everywhere, in the
     * browser's order); they replace any cookie of that name the jar had.
     */
    void putBrowser(String name, List<String> values) {
        cookies.values().removeIf(c -> c.name().equals(name));
        for (int i = 0; i < values.size(); i++) {
            cookies.put(name + "|browser|" + i, new Cookie(name, values.get(i), "/"));
        }
    }

    /** Cookie header for a request path, most specific path first (as browsers do); null when none. */
    String header(String requestPath) {
        String header = cookies.values().stream()
                .filter(c -> pathMatches(c.path(), requestPath))
                .sorted(Comparator.comparingInt((Cookie c) -> c.path().length()).reversed())
                .map(c -> c.name() + "=" + c.value())
                .collect(Collectors.joining("; "));
        return header.isEmpty() ? null : header;
    }

    boolean has(String name) {
        return cookies.values().stream().anyMatch(c -> c.name().equals(name));
    }

    List<String> names() {
        return cookies.values().stream().map(c -> c.name() + "(" + c.path() + ")").toList();
    }

    void clear() {
        cookies.clear();
    }

    private static boolean pathMatches(String cookiePath, String requestPath) {
        if (cookiePath.equals("/") || requestPath.equals(cookiePath)) {
            return true;
        }
        return requestPath.startsWith(cookiePath)
                && (cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/');
    }

    private static String defaultPath(String requestPath) {
        int slash = requestPath.lastIndexOf('/');
        return slash <= 0 ? "/" : requestPath.substring(0, slash);
    }
}
