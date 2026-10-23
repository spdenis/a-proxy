package com.testing.aproxy.webui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/** Reads an HTML form (action and the fields a browser would submit) from a page or a partial-page response. */
final class HtmlForms {

    private static final Pattern FORM = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form>");
    private static final Pattern FIELD = Pattern.compile(
            "(?is)<input\\b([^>]*)>|<select\\b([^>]*)>(.*?)</select>|<textarea\\b([^>]*)>(.*?)</textarea>");
    private static final Pattern OPTION = Pattern.compile("(?is)<option\\b([^>]*)>([^<]*)");

    private HtmlForms() {
    }

    /** @param action as written in the page (entities decoded), e.g. {@code /wcc/quickview/pages/main.jsf?AntiXSRFToken=...} */
    record Form(String action, Map<String, String> fields) {
    }

    /** The form whose id or name is {@code idOrName}; null when the markup has none. */
    static @Nullable Form find(String markup, String idOrName) {
        String source = markup.replace("<![CDATA[", "").replace("]]>", "");
        Matcher m = FORM.matcher(source);
        while (m.find()) {
            String attrs = m.group(1);
            if (idOrName.equals(attr(attrs, "id")) || idOrName.equals(attr(attrs, "name"))) {
                String action = attr(attrs, "action");
                return new Form(action == null ? "" : action, fields(m.group(2)));
            }
        }
        return null;
    }

    private static Map<String, String> fields(String formBody) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(formBody);
        while (m.find()) {
            if (m.group(1) != null) {
                String attrs = m.group(1);
                String name = attr(attrs, "name");
                String type = String.valueOf(attr(attrs, "type")).toLowerCase(Locale.ROOT);
                boolean skipped = switch (type) {
                    case "submit", "button", "image", "reset", "file" -> true;
                    case "checkbox", "radio" -> !Pattern.compile("(?i)\\bchecked\\b").matcher(attrs).find();
                    default -> false;
                };
                if (name != null && !skipped) {
                    String value = attr(attrs, "value");
                    fields.putIfAbsent(name, value == null ? "" : value);
                }
            } else if (m.group(2) != null) {
                String name = attr(m.group(2), "name");
                if (name != null) {
                    fields.putIfAbsent(name, selected(m.group(3)));
                }
            } else {
                String name = attr(m.group(4), "name");
                if (name != null) {
                    fields.putIfAbsent(name, unescape(m.group(5)));
                }
            }
        }
        return fields;
    }

    private static String selected(String options) {
        Matcher m = OPTION.matcher(options);
        String first = null;
        while (m.find()) {
            String value = attr(m.group(1), "value");
            value = value == null ? unescape(m.group(2).strip()) : value;
            if (first == null) {
                first = value;
            }
            if (Pattern.compile("(?i)\\bselected\\b").matcher(m.group(1)).find()) {
                return value;
            }
        }
        return first == null ? "" : first;
    }

    static @Nullable String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?is)(?:^|\\s)" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
                .matcher(attrs);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        return unescape(v);
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&#61;", "=").replace("&amp;", "&");
    }
}
