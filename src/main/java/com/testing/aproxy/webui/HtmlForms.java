package com.testing.aproxy.webui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/** Reads an HTML form (action and the fields a browser would submit) from a page or a partial-page response. */
public final class HtmlForms {

    private static final Pattern FORM = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form>");
    private static final Pattern FIELD = Pattern.compile(
            "(?is)<input\\b([^>]*)>|<select\\b([^>]*)>(.*?)</select>|<textarea\\b([^>]*)>(.*?)</textarea>");
    private static final Pattern OPTION = Pattern.compile("(?is)<option\\b([^>]*)>([^<]*)");
    private static final Pattern BUTTON = Pattern.compile("(?is)<button\\b([^>]*)>(.*?)</button>|<input\\b([^>]*)>");
    private static final Pattern LABEL = Pattern.compile("(?is)<label\\b([^>]*)>(.*?)</label>");
    private static final Pattern SUBMIT_SOURCE = Pattern.compile("submitForm\\(\\s*'([^']+)'[^)]*source\\s*:\\s*'([^']+)'");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern DISABLED = Pattern.compile("(?i)\\bdisabled\\b");

    private HtmlForms() {
    }

    /** @param action as written in the page (entities decoded), e.g. {@code /wcc/quickview/pages/main.jsf?AntiXSRFToken=...} */
    public record Form(String action, Map<String, String> fields) {
    }

    /** The form whose id or name is {@code idOrName}; null when the markup has none. */
    public static @Nullable Form find(String markup, String idOrName) {
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

    /** All fields in some markup, e.g. a partial-page response whose fragments are parts of a form. */
    public static Map<String, String> inputs(String markup) {
        return fields(markup.replace("<![CDATA[", "").replace("]]>", ""));
    }

    /**
     * Options of every select in some markup, by select name: option value (its label when it has no value
     * attribute) to label.
     */
    public static Map<String, Map<String, String>> selects(String markup) {
        Map<String, Map<String, String>> selects = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(markup.replace("<![CDATA[", "").replace("]]>", ""));
        while (m.find()) {
            String name = m.group(2) == null ? null : attr(m.group(2), "name");
            if (name != null) {
                Map<String, String> options = new LinkedHashMap<>();
                Matcher o = OPTION.matcher(m.group(3));
                while (o.find()) {
                    String label = unescape(o.group(2).strip());
                    String value = attr(o.group(1), "value");
                    options.putIfAbsent(value == null ? label : value, label);
                }
                selects.putIfAbsent(name, options);
            }
        }
        return selects;
    }

    /**
     * The {@code source} that the button with this text submits form {@code formId} with, as in
     * {@code onclick="submitForm('form',1,{source:'j_id76'})"}; null when there is no such button.
     */
    public static @Nullable String buttonSource(String markup, String formId, String text) {
        Matcher m = BUTTON.matcher(markup.replace("<![CDATA[", "").replace("]]>", ""));
        while (m.find()) {
            String attrs = m.group(1) != null ? m.group(1) : m.group(3);
            String label = m.group(1) != null ? text(m.group(2)) : String.valueOf(attr(attrs, "value"));
            String onclick = attr(attrs, "onclick");
            if (onclick == null || !label.equalsIgnoreCase(text)) {
                continue;
            }
            Matcher submit = SUBMIT_SOURCE.matcher(onclick);
            if (submit.find() && submit.group(1).equals(formId)) {
                return submit.group(2);
            }
        }
        return null;
    }

    /** The id of the field whose label starts with {@code label} (e.g. "Comment" for "Comment:"); null if none. */
    public static @Nullable String labelledField(String markup, String label) {
        Matcher m = LABEL.matcher(markup.replace("<![CDATA[", "").replace("]]>", ""));
        while (m.find()) {
            String target = attr(m.group(1), "for");
            if (target != null && text(m.group(2)).toLowerCase(Locale.ROOT).startsWith(label.toLowerCase(Locale.ROOT))) {
                return target;
            }
        }
        return null;
    }

    /** The text a user reads in some markup: head, scripts, styles and tags removed, whitespace collapsed. */
    public static String text(String markup) {
        String s = markup.replace("<![CDATA[", "").replace("]]>", "")
                .replaceAll("(?is)<(head|script|style)\\b.*?</\\1\\s*>", " ")
                .replaceAll("(?s)<[^>]*>", " ");
        return unescape(s).replace("&nbsp;", " ").replace("&#160;", " ").replaceAll("\\s+", " ").strip();
    }

    private static Map<String, String> fields(String formBody) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(formBody);
        while (m.find()) {
            String own = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(4);
            if (DISABLED.matcher(QUOTED.matcher(own).replaceAll("")).find()) {
                continue; // a browser does not submit disabled fields
            }
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
