package com.testing.aproxy.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Full detail of one captured request, for building parsers: every query parameter and form
 * field with its value, non-standard headers, and the response's data tables (id, headers,
 * first rows) and hidden fields. Used by {@code analyzeCaptures --args="--dump=<seq>"}.
 */
final class CaptureDump {

    private static final Pattern FRAGMENT_ID = Pattern.compile("(?is)<fragment>\\s*(?:<!\\[CDATA\\[)?\\s*<\\w+[^>]*\\bid\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern HIDDEN_INPUT = Pattern.compile("(?is)<input\\b[^>]*type\\s*=\\s*[\"']?hidden[^>]*>");
    private static final Pattern NAME = Pattern.compile("(?i)\\bname\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern VALUE = Pattern.compile("(?i)\\bvalue\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern SELECT = Pattern.compile("(?is)<select\\b([^>]*)>(.*?)</select>");
    private static final Pattern OPTION = Pattern.compile("(?is)<option\\b([^>]*)>(.*?)(?=</option>|<option\\b|$)");
    private static final Pattern INPUT = Pattern.compile("(?is)<input\\b([^>]*)>");
    private static final Pattern TEXTAREA = Pattern.compile("(?is)<textarea\\b([^>]*)>(.*?)</textarea>");
    private static final Pattern BUTTON = Pattern.compile("(?is)<button\\b([^>]*)>(.*?)</button>");
    private static final Pattern SCRIPTED_LINK = Pattern.compile("(?is)<a\\b([^>]*\\bonclick\\s*=[^>]*)>(.*?)</a>");
    private static final Pattern LABEL = Pattern.compile("(?is)<label\\b[^>]*\\bfor\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</label>");
    private static final Pattern TYPE = Pattern.compile("(?i)\\btype\\s*=\\s*[\"']?([\\w-]+)");
    private static final Pattern ID = Pattern.compile("(?i)\\bid\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern SCRIPT = Pattern.compile("(?i)\\bon(?:change|click)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern FLAG = Pattern.compile("(?i)\\b(selected|checked|disabled|readonly)\\b");
    private static final int MAX_CONTROLS = 60;
    private static final Pattern XML_ROOT = Pattern.compile("(?s)^\\s*(?:<\\?xml[^>]*>\\s*)?<([\\w:-]+)");

    private final int maxRows;
    private final boolean allTables;
    private final int valueWidth;

    CaptureDump(int maxRows, boolean allTables, int valueWidth) {
        this.maxRows = maxRows;
        this.allTables = allTables;
        this.valueWidth = valueWidth;
    }

    String dump(JsonNode c) {
        StringBuilder out = new StringBuilder();
        JsonNode req = c.at("/request");
        JsonNode res = c.at("/response");
        out.append("DUMP seq=").append(c.at("/seq").asInt()).append(' ').append(req.at("/method").asString("?"))
                .append(' ').append(req.at("/autosysPath").asString(req.at("/path").asString("")))
                .append(" -> ").append(res.at("/status").asInt()).append(' ')
                .append(res.at("/contentType").asString("")).append(' ')
                .append(res.at("/bodyLength").asLong()).append(" bytes\n");

        section(out, "query", req.at("/query"));
        JsonNode headers = req.at("/headers");
        List<String> interesting = new ArrayList<>();
        if (headers.isObject()) {
            for (String name : headers.propertyNames()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("referer") || lower.equals("cookie") || lower.startsWith("x-") || lower.contains("csrf")
                        || lower.contains("xsrf") || lower.startsWith("tr-") || lower.contains("wcc")
                        || lower.contains("token") || lower.contains("faces")) {
                    interesting.add(name + "=" + cut(flat(headers.get(name))));
                }
            }
        }
        if (!interesting.isEmpty()) {
            out.append("-- request headers\n");
            interesting.forEach(h -> out.append("  ").append(h).append('\n'));
        }
        JsonNode body = req.at("/body");
        if (body.isObject()) {
            section(out, "request fields (" + body.size() + ")", body);
        } else if (body.isString()) {
            out.append("-- request body\n  ").append(cut(body.asString())).append('\n');
        }

        JsonNode responseHeaders = res.at("/headers");
        if (responseHeaders.isObject()) {
            List<String> rh = new ArrayList<>();
            for (String name : responseHeaders.propertyNames()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("location") || lower.equals("set-cookie") || lower.startsWith("x-") || lower.contains("wcc")
                        || lower.contains("csrf")) {
                    rh.add(name + "=" + cut(flat(responseHeaders.get(name))));
                }
            }
            if (!rh.isEmpty()) {
                out.append("-- response headers\n");
                rh.forEach(h -> out.append("  ").append(h).append('\n'));
            }
        }

        JsonNode resBody = res.at("/body");
        if (resBody.isString()) {
            describeMarkup(resBody.asString(), out);
        } else if (resBody.isObject() || resBody.isArray()) {
            out.append("-- response json\n  ").append(cut(resBody.toString())).append('\n');
        }
        return out.toString();
    }

    private void describeMarkup(String body, StringBuilder out) {
        Matcher root = XML_ROOT.matcher(body);
        if (root.find()) {
            out.append("-- response root element: <").append(root.group(1)).append(">\n");
        }
        List<String> fragments = new ArrayList<>();
        Matcher f = FRAGMENT_ID.matcher(body);
        while (f.find() && fragments.size() < 30) {
            fragments.add(f.group(1));
        }
        if (!fragments.isEmpty()) {
            out.append("-- partial-page fragments (").append(fragments.size()).append("): ")
                    .append(String.join(", ", fragments)).append('\n');
        }

        List<String> hidden = new ArrayList<>();
        Matcher h = HIDDEN_INPUT.matcher(body);
        while (h.find() && hidden.size() < 40) {
            Matcher name = NAME.matcher(h.group());
            if (name.find()) {
                Matcher value = VALUE.matcher(h.group());
                hidden.add(name.group(1) + "=" + (value.find() ? cut(value.group(1)) : ""));
            }
        }
        if (!hidden.isEmpty()) {
            out.append("-- hidden inputs in response\n");
            hidden.forEach(x -> out.append("  ").append(x).append('\n'));
        }

        describeControls(body, out);

        List<HtmlTables.Table> tables = HtmlTables.parse(body);
        int shown = 0;
        for (HtmlTables.Table t : tables) {
            List<String> headers = t.headers();
            List<List<String>> rows = t.dataRows();
            boolean dataLike = rows.size() >= 2 && rows.getFirst().size() >= 2;
            if (headers.isEmpty() && !(allTables && dataLike)) {
                continue;
            }
            shown++;
            out.append("-- table T").append(t.index());
            if (t.id() != null) {
                out.append(" id=").append(t.id());
            }
            if (t.summary() != null) {
                out.append(" summary=").append(cut(t.summary()));
            }
            out.append(" rows=").append(rows.size()).append('\n');
            if (!headers.isEmpty()) {
                out.append("  headers: ").append(join(headers)).append('\n');
            }
            for (int i = 0; i < Math.min(maxRows, rows.size()); i++) {
                out.append("  r").append(i + 1).append(": ").append(join(rows.get(i))).append('\n');
            }
            if (rows.size() > maxRows) {
                out.append("  ... ").append(rows.size() - maxRows).append(" more rows (--rows=N)\n");
            }
        }
        out.append("-- ").append(tables.size()).append(" tables in total, ").append(shown)
                .append(" shown (with header cells").append(allTables ? " or data rows" : "; --all-tables for more")
                .append(")\n");
    }

    /** Dropdowns with all their options, visible inputs (text, radio, check box), buttons and scripted links. */
    private void describeControls(String body, StringBuilder out) {
        Map<String, String> labels = new HashMap<>();
        Matcher l = LABEL.matcher(body);
        while (l.find()) {
            labels.putIfAbsent(l.group(1), HtmlTables.text(l.group(2)));
        }

        List<String> selects = new ArrayList<>();
        Matcher s = SELECT.matcher(body);
        while (s.find() && selects.size() < MAX_CONTROLS) {
            StringBuilder one = new StringBuilder(control(s.group(1), labels)).append(flags(s.group(1)));
            Matcher o = OPTION.matcher(s.group(2));
            int options = 0;
            while (o.find()) {
                options++;
                Matcher value = VALUE.matcher(o.group(1));
                String label = HtmlTables.text(o.group(2));
                one.append("\n    ").append(value.find() ? value.group(1) : "(no value)").append(" = ")
                        .append(cut(label)).append(flags(o.group(1)));
            }
            selects.add(one.append(options == 0 ? "\n    (no options)" : "").toString());
        }
        if (!selects.isEmpty()) {
            out.append("-- dropdowns (option value = label)\n");
            selects.forEach(x -> out.append("  ").append(x).append('\n'));
        }

        List<String> inputs = new ArrayList<>();
        Matcher i = INPUT.matcher(body);
        while (i.find() && inputs.size() < MAX_CONTROLS) {
            String attrs = i.group(1);
            Matcher type = TYPE.matcher(attrs);
            String t = type.find() ? type.group(1).toLowerCase(Locale.ROOT) : "text";
            if (t.equals("hidden")) {
                continue;
            }
            Matcher value = VALUE.matcher(attrs);
            inputs.add(t + " " + control(attrs, labels) + (value.find() ? " value=" + cut(value.group(1)) : "")
                    + flags(attrs));
        }
        Matcher ta = TEXTAREA.matcher(body);
        while (ta.find() && inputs.size() < MAX_CONTROLS) {
            inputs.add("textarea " + control(ta.group(1), labels) + " value=" + cut(HtmlTables.text(ta.group(2))));
        }
        if (!inputs.isEmpty()) {
            out.append("-- visible inputs (type, name/id [label], value)\n");
            inputs.forEach(x -> out.append("  ").append(x).append('\n'));
        }

        List<String> buttons = new ArrayList<>();
        Matcher b = BUTTON.matcher(body);
        while (b.find() && buttons.size() < MAX_CONTROLS) {
            buttons.add("button " + control(b.group(1), labels) + " text=" + cut(HtmlTables.text(b.group(2))));
        }
        Matcher a = SCRIPTED_LINK.matcher(body);
        while (a.find() && buttons.size() < MAX_CONTROLS) {
            buttons.add("link " + control(a.group(1), labels) + " text=" + cut(HtmlTables.text(a.group(2))));
        }
        if (!buttons.isEmpty()) {
            out.append("-- buttons and scripted links (name/id, script, text)\n");
            buttons.forEach(x -> out.append("  ").append(x).append('\n'));
        }
    }

    /** {@code name=.. id=.. [label] script=..} from a control's attributes. */
    private String control(String attrs, Map<String, String> labels) {
        StringBuilder out = new StringBuilder();
        Matcher name = NAME.matcher(attrs);
        Matcher id = ID.matcher(attrs);
        String nameValue = name.find() ? name.group(1) : null;
        String idValue = id.find() ? id.group(1) : null;
        if (nameValue != null) {
            out.append("name=").append(nameValue);
        }
        if (idValue != null && !idValue.equals(nameValue)) {
            out.append(out.isEmpty() ? "" : " ").append("id=").append(idValue);
        }
        if (idValue != null && labels.containsKey(idValue)) {
            out.append(" [").append(cut(labels.get(idValue))).append(']');
        }
        Matcher script = SCRIPT.matcher(attrs);
        if (script.find()) {
            out.append(" script=").append(cut(script.group(1) != null ? script.group(1) : script.group(2)));
        }
        return out.isEmpty() ? "(no name/id)" : out.toString();
    }

    /** selected, checked, disabled, readonly: attributes outside quoted values. */
    private static String flags(String attrs) {
        List<String> flags = new ArrayList<>();
        Matcher f = FLAG.matcher(QUOTED.matcher(attrs).replaceAll(""));
        while (f.find()) {
            flags.add(f.group(1).toLowerCase(Locale.ROOT));
        }
        return flags.isEmpty() ? "" : " [" + String.join(", ", flags) + "]";
    }

    private void section(StringBuilder out, String title, JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return;
        }
        out.append("-- ").append(title).append('\n');
        for (String name : node.propertyNames()) {
            out.append("  ").append(name).append('=').append(cut(flat(node.get(name)))).append('\n');
        }
    }

    private String join(List<String> cells) {
        return String.join(" | ", cells.stream().map(this::cut).toList());
    }

    private static String flat(JsonNode v) {
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            v.forEach(x -> parts.add(x.asString(x.toString())));
            return String.join(" || ", parts);
        }
        return v.asString(v.toString());
    }

    private String cut(String v) {
        String s = v.replaceAll("\\s+", " ").strip();
        return s.length() > valueWidth ? s.substring(0, valueWidth - 3) + "..." : s;
    }
}
