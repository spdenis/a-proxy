package com.testing.aproxy.session;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The browser's login request without its password, so the proxy can log in to AutoSys
 * the same way (same body format, extra fields and headers) when it needs its own session.
 *
 * @param jsonBody     JSON body with the password property removed; null for a form login
 * @param formFields   form fields except the password, in request order; empty for a JSON login
 * @param headers      non-secret headers the UI added, e.g. X-Requested-With
 */
public record LoginTemplate(String contentType, @Nullable String jsonBody, List<Map.Entry<String, String>> formFields,
        String passwordField, Map<String, String> headers) {

    private static final JsonMapper JSON = JsonMapper.shared();

    public LoginTemplate {
        formFields = List.copyOf(formFields);
        headers = Map.copyOf(headers);
    }

    public boolean json() {
        return jsonBody != null;
    }

    /** The request body with {@code password} filled in. */
    public String body(char[] password) {
        if (jsonBody != null) {
            JsonNode tree = JSON.readTree(jsonBody);
            String[] path = passwordField.split("\\.");
            JsonNode parent = tree;
            for (int i = 0; i < path.length - 1; i++) {
                parent = parent.get(path[i]);
            }
            ((ObjectNode) parent).put(path[path.length - 1], new String(password));
            return JSON.writeValueAsString(tree);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> field : formFields) {
            append(out, field.getKey(), field.getValue());
        }
        append(out, passwordField, new String(password));
        return out.toString();
    }

    /** Removes the property at a dotted path; false when it is not there. */
    public static boolean removeJsonField(JsonNode tree, String dottedPath) {
        String[] path = dottedPath.split("\\.");
        JsonNode parent = tree;
        for (int i = 0; i < path.length - 1 && parent != null; i++) {
            parent = parent.get(path[i]);
        }
        if (parent instanceof ObjectNode object && object.has(path[path.length - 1])) {
            object.remove(path[path.length - 1]);
            return true;
        }
        return false;
    }

    private static void append(StringBuilder out, String name, String value) {
        if (!out.isEmpty()) {
            out.append('&');
        }
        out.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "LoginTemplate[" + contentType + ", fields=" + (json() ? "json" : formFields.size()) + "]";
    }
}
