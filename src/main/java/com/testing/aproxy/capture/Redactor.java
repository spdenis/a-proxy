package com.testing.aproxy.capture;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.testing.aproxy.logging.MaskingMessageConverter;
import com.testing.aproxy.proxy.HeaderRewriter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Masks secrets in captured traffic while keeping its structure: names of headers, cookies,
 * parameters and JSON fields are kept, their values replaced by {@code ***}.
 */
public final class Redactor {

    static final String MASK = "***";

    /** Names whose values are secrets (passwords, tokens, CSRF, session ids). */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(pass(word|wd)?|pwd|secret|token|csrf|xsrf|jsessionid|sessionid|auth|credential|cookie).*");

    private static final Set<String> SECRET_HEADERS = Set.of("cookie", "set-cookie", "authorization",
            "proxy-authorization");

    /** {@code <input name="...csrf..." value="SECRET">} and the reverse attribute order. */
    private static final Pattern HTML_SECRET_INPUT = Pattern.compile(
            "(?i)(<input[^>]*name\\s*=\\s*[\"'][^\"']*(?:csrf|xsrf|token|pass)[^\"']*[\"'][^>]*value\\s*=\\s*[\"'])([^\"']*)");
    private static final Pattern HTML_SECRET_INPUT_REVERSED = Pattern.compile(
            "(?i)(<input[^>]*value\\s*=\\s*[\"'])([^\"']*)([\"'][^>]*name\\s*=\\s*[\"'][^\"']*(?:csrf|xsrf|token|pass)[^\"']*[\"'])");

    /** {@code OWASP-CSRFTOKEN:abc}, {@code csrfToken = "abc"}, {@code "token":"abc"}, {@code jsessionid=abc}. */
    private static final Pattern TEXT_SECRET = Pattern.compile(
            "(?i)((?:csrf|xsrf|token|password|passwd|pwd|jsessionid)[\\w-]*[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s&;,<>)}]{4,})");

    private final Set<String> extraSecretNames;

    /** @param extraSecretNames field names that are secrets in this installation (e.g. the login password field) */
    public Redactor(Collection<String> extraSecretNames) {
        this.extraSecretNames = Set.copyOf(extraSecretNames.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList());
    }

    public boolean isSecretName(String name) {
        return SECRET_NAME.matcher(name).matches() || extraSecretNames.contains(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, Object> headers(Map<String, List<String>> headers) {
        Map<String, Object> out = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            List<String> masked;
            if (lower.equals("cookie")) {
                masked = values.stream().map(Redactor::maskCookieHeader).toList();
            } else if (lower.equals("set-cookie")) {
                masked = values.stream().map(v -> HeaderRewriter.cookieName(v) + "=" + MASK + attributes(v)).toList();
            } else if (SECRET_HEADERS.contains(lower) || isSecretName(name)) {
                masked = values.stream().map(v -> MASK).toList();
            } else {
                masked = values.stream().map(this::text).toList();
            }
            out.put(name, masked.size() == 1 ? masked.getFirst() : masked);
        });
        return out;
    }

    public Map<String, Object> params(Map<String, String[]> params) {
        Map<String, Object> out = new LinkedHashMap<>();
        params.forEach((name, values) -> {
            List<String> masked = List.of(values).stream().map(v -> isSecretName(name) ? MASK : text(v)).toList();
            out.put(name, masked.size() == 1 ? masked.getFirst() : masked);
        });
        return out;
    }

    /** Masks string values of secret-named fields, recursively; the tree is modified in place. */
    public JsonNode json(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyNames())) {
                JsonNode child = object.get(name);
                if (isSecretName(name) && child.isValueNode() && !child.isNull() && !child.isBoolean()) {
                    object.put(name, MASK);
                } else if (child.isString()) {
                    object.put(name, text(child.asString()));
                } else {
                    json(child);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode child = node.get(i);
                if (child.isString()) {
                    ((tools.jackson.databind.node.ArrayNode) node).set(i, text(child.asString()));
                } else {
                    json(child);
                }
            }
        }
        return node;
    }

    /** Masks secret-looking assignments in free text (HTML, JS, plain text). */
    public String text(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = HTML_SECRET_INPUT.matcher(value).replaceAll("$1" + MASK);
        masked = HTML_SECRET_INPUT_REVERSED.matcher(masked).replaceAll("$1" + MASK + "$3");
        masked = TEXT_SECRET.matcher(masked).replaceAll("$1" + MASK);
        return MaskingMessageConverter.mask(masked);
    }

    private static String maskCookieHeader(String header) {
        StringBuilder out = new StringBuilder();
        for (String cookie : header.split(";")) {
            String c = cookie.strip();
            if (c.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("; ");
            }
            int eq = c.indexOf('=');
            out.append(eq < 0 ? c : c.substring(0, eq)).append('=').append(MASK);
        }
        return out.toString();
    }

    private static String attributes(String setCookie) {
        int semi = setCookie.indexOf(';');
        return semi < 0 ? "" : setCookie.substring(semi);
    }
}
