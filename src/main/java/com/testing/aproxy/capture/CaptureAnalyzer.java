package com.testing.aproxy.capture;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a capture run directory into a compact text summary of the AutoSys UI's internal
 * endpoints, small enough to be retyped or photographed from an isolated network.
 * <p>
 * Usage: {@code ./gradlew analyzeCaptures --args="[runDir] [--only=regex] [--values=N] [--no-values] [--brief]"},
 * or for one request in full detail: {@code --args="--dump=<seq> [--rows=N] [--all-tables] [--width=N]"},
 * or to find a text in all requests with the raw markup around it: {@code --args="--grep=<regex> [--context=N] [--rows=N] [--responses]"}.
 * Without a directory the newest run under {@code ./captures} is used. The summary is printed
 * and also written to {@code SUMMARY.txt} in the run directory.
 */
public final class CaptureAnalyzer {

    private static final JsonMapper JSON = JsonMapper.shared();

    /** Request/response headers every browser or server sends; everything else is shown. */
    private static final Set<String> COMMON_HEADERS = Set.of("host", "user-agent", "accept", "accept-language",
            "accept-encoding", "connection", "content-length", "content-type", "cookie", "origin", "referer",
            "cache-control", "pragma", "upgrade-insecure-requests", "priority", "dnt", "http2-settings", "upgrade",
            "te", "date", "server", "transfer-encoding", "keep-alive", "expires", "last-modified", "etag", "vary",
            "x-content-type-options", "x-frame-options", "strict-transport-security", "x-xss-protection",
            "content-security-policy", "set-cookie", "location", "content-language", "accept-ranges", "age",
            "if-modified-since", "if-none-match", "matched-stub-id", "content-encoding", "referrer-policy",
            "permissions-policy", "x-ua-compatible");

    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("\\d+|[0-9a-fA-F-]{32,36}");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern FORM = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form>");
    private static final Pattern INPUT = Pattern.compile("(?is)<(input|select|textarea)\\b([^>]*)>");
    private static final Pattern TH = Pattern.compile("(?is)<th\\b[^>]*>(.*?)</th>");
    private static final Pattern TR = Pattern.compile("(?i)<tr\\b");
    private static final Pattern TABLE = Pattern.compile("(?i)<table\\b");
    private static final Pattern URL_IN_TEXT = Pattern.compile(
            "[\"'](/[A-Za-z0-9_\\-./{}$]+(?:\\?[^\"'\\s<>]*)?)[\"']");
    private static final Pattern PASSWORD_INPUT = Pattern.compile("(?i)type\\s*=\\s*[\"']?password");
    private static final Pattern TAGS = Pattern.compile("(?is)<script.*?</script>|<style.*?</style>|<[^>]+>");
    private static final Pattern STATIC_URL = Pattern.compile(
            "(?i).*\\.(js|css|map|png|jpe?g|gif|svg|ico|woff2?|ttf|eot|otf)(\\?.*)?$");

    private final int maxValues;
    private final boolean showValues;

    CaptureAnalyzer(int maxValues, boolean showValues) {
        this.maxValues = maxValues;
        this.showValues = showValues;
    }

    public static void main(String[] args) throws IOException {
        Path dir = null;
        String only = null;
        int values = 3;
        boolean showValues = true;
        boolean brief = false;
        Integer dumpSeq = null;
        String grep = null;
        int context = 150;
        boolean responsesOnly = false;
        int rows = 10;
        int width = 60;
        boolean allTables = false;
        for (String arg : args) {
            if (arg.startsWith("--dump=")) {
                dumpSeq = Integer.parseInt(arg.substring("--dump=".length()));
            } else if (arg.startsWith("--grep=")) {
                grep = arg.substring("--grep=".length());
            } else if (arg.equals("--responses")) {
                responsesOnly = true;
            } else if (arg.startsWith("--context=")) {
                context = Integer.parseInt(arg.substring("--context=".length()));
            } else if (arg.startsWith("--rows=")) {
                rows = Integer.parseInt(arg.substring("--rows=".length()));
            } else if (arg.startsWith("--width=")) {
                width = Integer.parseInt(arg.substring("--width=".length()));
            } else if (arg.equals("--all-tables")) {
                allTables = true;
            } else if (arg.startsWith("--only=")) {
                only = arg.substring("--only=".length());
            } else if (arg.startsWith("--values=")) {
                values = Integer.parseInt(arg.substring("--values=".length()));
            } else if (arg.equals("--no-values")) {
                showValues = false;
            } else if (arg.equals("--brief")) {
                brief = true;
            } else if (!arg.isBlank()) {
                dir = Path.of(arg);
            }
        }
        if (dir == null) {
            dir = newestRun(Path.of("captures"));
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (grep != null) {
            String result = new CaptureGrep(Pattern.compile(grep), context, rows, responsesOnly).grep(dir);
            out.print(result);
            Path file = dir.resolve("GREP.txt");
            Files.writeString(file, result);
            out.println("(written to " + file.toAbsolutePath() + ")");
            return;
        }
        if (dumpSeq != null) {
            String dump = new CaptureDump(rows, allTables, width).dump(JSON.readTree(findSeq(dir, dumpSeq).toFile()));
            out.print(dump);
            Path file = dir.resolve("DUMP-%04d.txt".formatted(dumpSeq));
            Files.writeString(file, dump);
            out.println("(written to " + file.toAbsolutePath() + ")");
            return;
        }
        String summary = new CaptureAnalyzer(values, showValues).analyze(dir, only == null ? null : Pattern.compile(only),
                brief);
        out.print(summary);
        Path file = dir.resolve(brief ? "SUMMARY-brief.txt" : "SUMMARY.txt");
        Files.writeString(file, summary);
        out.println("(written to " + file.toAbsolutePath() + ")");
    }

    static Path findSeq(Path dir, int seq) throws IOException {
        String prefix = "%04d-".formatted(seq);
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith(prefix)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No capture with seq " + seq + " in " + dir));
        }
    }

    static Path newestRun(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("No capture directory " + root.toAbsolutePath()
                    + "; pass the run directory as argument");
        }
        try (Stream<Path> runs = Files.list(root)) {
            return runs.filter(Files::isDirectory).max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new IllegalArgumentException("No capture runs in " + root.toAbsolutePath()));
        }
    }

    String analyze(Path dir, Pattern only, boolean brief) throws IOException {
        List<JsonNode> captures = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int files;
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> jsonFiles = list.filter(f -> f.getFileName().toString().matches("\\d+-.*\\.json")).sorted().toList();
            files = jsonFiles.size();
            for (Path file : jsonFiles) {
                try {
                    captures.add(JSON.readTree(file.toFile()));
                } catch (RuntimeException e) {
                    String reason = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
                    skipped.add(file.getFileName() + ": " + e.getClass().getSimpleName() + " "
                            + (reason.length() > 120 ? reason.substring(0, 120) + "..." : reason));
                }
            }
        }
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        List<String> timeline = new ArrayList<>();
        for (JsonNode c : captures) {
            String path = c.at("/request/autosysPath").asString(c.at("/request/path").asString(""));
            if (only != null && !only.matcher(path).find()) {
                continue;
            }
            String key = c.at("/request/method").asString("?") + " " + template(path);
            Endpoint e = endpoints.computeIfAbsent(key, k -> new Endpoint("E" + (endpoints.size() + 1), k));
            e.add(c, this);
            timeline.add(c.at("/seq").asInt() + ":" + e.id + statusMark(c));
        }

        StringBuilder out = new StringBuilder();
        out.append("CAPTURE SUMMARY ").append(dir.getFileName()).append("  files=").append(files)
                .append("  requests=").append(timeline.size()).append("  endpoints=").append(endpoints.size())
                .append(only != null ? "  (--only=" + only.pattern() + ")" : "").append('\n');
        out.append("(an endpoint is one method + URL; its requests are listed by seq in the timeline)\n");
        if (!skipped.isEmpty()) {
            out.append("== UNREADABLE FILES (").append(skipped.size()).append(", not analyzed)\n");
            skipped.forEach(line -> out.append("  ").append(line).append('\n'));
        }
        out.append("== TIMELINE (seq:endpoint, !=login-like, x=error)\n");
        wrap(out, String.join(" ", timeline), 100);
        out.append("== ENDPOINTS\n");
        for (Endpoint e : endpoints.values()) {
            out.append(e.headline()).append('\n');
        }
        if (!brief) {
            out.append("== DETAILS\n");
            for (Endpoint e : endpoints.values()) {
                e.details(out);
            }
        }
        return out.toString();
    }

    private static String statusMark(JsonNode c) {
        if (c.has("error")) {
            return "x";
        }
        return looksLikeLogin(c) ? "!" : "";
    }

    /** Numeric ids and UUIDs become {n}, so repeated calls group together. */
    static String template(String path) {
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (NUMERIC_SEGMENT.matcher(segments[i]).matches()) {
                segments[i] = "{n}";
            }
        }
        return String.join("/", segments);
    }

    /** Response that looks like "please log in": 401/403, redirect to a login URL, or a page with a password field. */
    static boolean looksLikeLogin(JsonNode c) {
        int status = c.at("/response/status").asInt();
        if (status == 401 || status == 403) {
            return true;
        }
        String location = c.at("/response/headers/Location").asString("");
        if (status >= 300 && status < 400 && location.toLowerCase(Locale.ROOT).contains("login")) {
            return true;
        }
        JsonNode body = c.at("/response/body");
        return body.isString() && PASSWORD_INPUT.matcher(body.asString()).find();
    }

    String value(String v) {
        if (!showValues) {
            return "<" + v.length() + ">";
        }
        String oneLine = v.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 40 ? oneLine.substring(0, 37) + "..." : oneLine;
    }

    private static void wrap(StringBuilder out, String text, int width) {
        int lineStart = out.length();
        for (String word : text.split(" ")) {
            if (out.length() - lineStart + word.length() > width) {
                out.append('\n');
                lineStart = out.length();
            }
            out.append(word).append(' ');
        }
        out.append('\n');
    }

    /** Aggregated view of all captures of one method + path template. */
    private static final class Endpoint {

        final String id;
        final String key;
        final List<Integer> seqs = new ArrayList<>();
        final Set<Integer> statuses = new LinkedHashSet<>();
        final Set<String> requestTypes = new LinkedHashSet<>();
        final Set<String> responseTypes = new LinkedHashSet<>();
        final Map<String, Set<String>> query = new LinkedHashMap<>();
        final Map<String, Set<String>> headers = new LinkedHashMap<>();
        final Set<String> cookiesSent = new LinkedHashSet<>();
        final Set<String> cookiesSet = new LinkedHashSet<>();
        final Set<String> referers = new LinkedHashSet<>();
        final Set<String> locations = new LinkedHashSet<>();
        final Set<String> paths = new LinkedHashSet<>();
        final Set<String> errors = new LinkedHashSet<>();
        final Shape requestBody = new Shape();
        final Shape responseBody = new Shape();
        final Map<String, Set<String>> html = new LinkedHashMap<>();
        boolean loginLike;
        long maxLength;

        Endpoint(String id, String key) {
            this.id = id;
            this.key = key;
        }

        void add(JsonNode c, CaptureAnalyzer a) {
            seqs.add(c.at("/seq").asInt());
            paths.add(c.at("/request/autosysPath").asString(""));
            statuses.add(c.at("/response/status").asInt());
            addIfPresent(requestTypes, c.at("/request/contentType"));
            addIfPresent(responseTypes, c.at("/response/contentType"));
            maxLength = Math.max(maxLength, c.at("/response/bodyLength").asLong());
            loginLike |= looksLikeLogin(c);
            if (c.has("error")) {
                errors.add(a.value(c.get("error").asString()));
            }
            JsonNode q = c.at("/request/query");
            if (q.isObject()) {
                for (String name : q.propertyNames()) {
                    collect(query.computeIfAbsent(name, k -> new LinkedHashSet<>()), q.get(name), a);
                }
            }
            JsonNode h = c.at("/request/headers");
            if (h.isObject()) {
                for (String name : h.propertyNames()) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("cookie")) {
                        for (String cookie : h.get(name).asString("").split(";")) {
                            cookiesSent.add(cookie.split("=", 2)[0].strip());
                        }
                    } else if (lower.equals("referer")) {
                        referers.add(pathOf(h.get(name).asString("")));
                    } else if (!COMMON_HEADERS.contains(lower) && !lower.startsWith("sec-")) {
                        collect(headers.computeIfAbsent(name, k -> new LinkedHashSet<>()), h.get(name), a);
                    }
                }
            }
            JsonNode rh = c.at("/response/headers");
            if (rh.isObject()) {
                for (String name : rh.propertyNames()) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("set-cookie")) {
                        JsonNode v = rh.get(name);
                        for (JsonNode one : v.isArray() ? v : List.of(v)) {
                            cookiesSet.add(one.asString("").split("=", 2)[0].strip());
                        }
                    } else if (lower.equals("location")) {
                        locations.add(pathOf(rh.get(name).asString("")));
                    } else if (!COMMON_HEADERS.contains(lower)) {
                        collect(headers.computeIfAbsent("response " + name, k -> new LinkedHashSet<>()), rh.get(name), a);
                    }
                }
            }
            body(c.at("/request/body"), requestBody, null, a);
            body(c.at("/response/body"), responseBody, html, a);
        }

        private void body(JsonNode body, Shape shape, Map<String, Set<String>> htmlInfo, CaptureAnalyzer a) {
            if (body == null || body.isMissingNode() || body.isNull()) {
                return;
            }
            if (body.isObject() || body.isArray()) {
                shape.add("", body, a);
                return;
            }
            String text = body.asString("");
            if (text.startsWith("<binary") || text.startsWith("<multipart")) {
                shape.note(text);
                return;
            }
            if (htmlInfo == null) {
                shape.note("text: " + a.value(text));
                return;
            }
            summarizeText(text, htmlInfo, a);
        }

        private void summarizeText(String text, Map<String, Set<String>> info, CaptureAnalyzer a) {
            Matcher title = TITLE.matcher(text);
            if (title.find()) {
                put(info, "title", a.value(title.group(1)));
            }
            Matcher form = FORM.matcher(text);
            while (form.find()) {
                String attrs = form.group(1);
                List<String> inputs = new ArrayList<>();
                Matcher input = INPUT.matcher(form.group(2));
                while (input.find()) {
                    String name = attr(input.group(2), "name");
                    if (name != null) {
                        String type = attr(input.group(2), "type");
                        inputs.add(name + (type == null || type.equalsIgnoreCase("text") ? "" : ":" + type));
                    }
                }
                put(info, "form", (attr(attrs, "method") == null ? "GET" : attr(attrs, "method").toUpperCase(Locale.ROOT))
                        + " " + (attr(attrs, "action") == null ? "(same page)" : attr(attrs, "action")) + " " + inputs);
            }
            int tables = count(TABLE, text);
            if (tables > 0) {
                List<String> headerCells = new ArrayList<>();
                Matcher th = TH.matcher(text);
                while (th.find() && headerCells.size() < 20) {
                    headerCells.add(a.value(TAGS.matcher(th.group(1)).replaceAll("").strip()));
                }
                put(info, "tables", tables + " table(s), " + count(TR, text) + " rows, headers " + headerCells);
            }
            Set<String> urls = new LinkedHashSet<>();
            Matcher url = URL_IN_TEXT.matcher(text);
            while (url.find() && urls.size() < 25) {
                if (!STATIC_URL.matcher(url.group(1)).matches() && url.group(1).length() > 1) {
                    urls.add(url.group(1).length() > 80 ? url.group(1).substring(0, 77) + "..." : url.group(1));
                }
            }
            for (String u : urls) {
                put(info, "urls", u);
            }
            String visible = TAGS.matcher(text).replaceAll(" ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").strip();
            if (!visible.isEmpty()) {
                put(info, "text", visible.length() > 160 ? visible.substring(0, 157) + "..." : visible);
            }
        }

        String headline() {
            StringBuilder s = new StringBuilder();
            s.append('[').append(id).append("] ").append(key).append("  x").append(seqs.size())
                    .append("  status=").append(join(statuses));
            if (!requestTypes.isEmpty()) {
                s.append("  req=").append(shortType(requestTypes));
            }
            if (!responseTypes.isEmpty()) {
                s.append("  res=").append(shortType(responseTypes));
            }
            if (loginLike) {
                s.append("  !LOGIN-LIKE");
            }
            if (!errors.isEmpty()) {
                s.append("  ERROR");
            }
            return s.toString();
        }

        void details(StringBuilder out) {
            out.append('\n').append(headline()).append('\n');
            out.append("  seq: ").append(seqs.size() > 15 ? seqs.subList(0, 15) + "..." : seqs).append('\n');
            if (paths.size() > 1) {
                line(out, "paths", List.copyOf(paths).subList(0, Math.min(5, paths.size())).toString());
            }
            if (!referers.isEmpty()) {
                line(out, "called from", join(referers));
            }
            query.forEach((k, v) -> line(out, "query " + k, join(v)));
            headers.forEach((k, v) -> line(out, "header " + k, join(v)));
            if (!cookiesSent.isEmpty()) {
                line(out, "cookies sent", join(cookiesSent));
            }
            requestBody.print(out, "req body");
            if (!cookiesSet.isEmpty()) {
                line(out, "sets cookies", join(cookiesSet));
            }
            if (!locations.isEmpty()) {
                line(out, "redirect to", join(locations));
            }
            line(out, "response", join(responseTypes) + ", up to " + maxLength + " bytes");
            responseBody.print(out, "res");
            html.forEach((k, v) -> {
                for (String one : v) {
                    line(out, "res " + k, one);
                }
            });
            errors.forEach(e -> line(out, "error", e));
        }

        private static void line(StringBuilder out, String label, String value) {
            out.append("  ").append(label).append(": ").append(value).append('\n');
        }

        private static String shortType(Set<String> types) {
            Set<String> s = new LinkedHashSet<>();
            for (String t : types) {
                String lower = t.toLowerCase(Locale.ROOT);
                s.add(lower.contains("json") ? "json" : lower.contains("html") ? "html"
                        : lower.contains("form-urlencoded") ? "form" : lower.contains("javascript") ? "js"
                        : lower.contains("xml") ? "xml" : lower.split(";")[0]);
            }
            return join(s);
        }

        private static void collect(Set<String> into, JsonNode v, CaptureAnalyzer a) {
            for (JsonNode one : v.isArray() ? v : List.of(v)) {
                if (into.size() < a.maxValues) {
                    into.add(a.value(one.asString("")));
                } else {
                    into.add("...");
                    return;
                }
            }
        }

        private static void put(Map<String, Set<String>> map, String key, String value) {
            Set<String> set = map.computeIfAbsent(key, k -> new LinkedHashSet<>());
            if (set.size() < 25) {
                set.add(value);
            }
        }

        private static void addIfPresent(Set<String> set, JsonNode v) {
            if (v.isString() && !v.asString().isBlank()) {
                set.add(v.asString());
            }
        }

        private static String join(Set<?> values) {
            return values.stream().map(String::valueOf).reduce((x, y) -> x + " | " + y).orElse("");
        }

        private static String pathOf(String url) {
            String s = url.replaceFirst("^[a-zA-Z]+://[^/]+", "");
            return s.isEmpty() ? "/" : s;
        }

        private static String attr(String attrs, String name) {
            Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(attrs);
            if (!m.find()) {
                return null;
            }
            return m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
        }

        private static int count(Pattern p, String text) {
            Matcher m = p.matcher(text);
            int n = 0;
            while (m.find()) {
                n++;
            }
            return n;
        }
    }

    /** Field paths of JSON bodies with types and a few distinct values. */
    private static final class Shape {

        private final Map<String, Set<String>> types = new LinkedHashMap<>();
        private final Map<String, Set<String>> values = new LinkedHashMap<>();
        private final Map<String, Integer> arraySizes = new LinkedHashMap<>();
        private final Set<String> notes = new LinkedHashSet<>();

        void note(String note) {
            if (notes.size() < 3) {
                notes.add(note);
            }
        }

        void add(String path, JsonNode node, CaptureAnalyzer a) {
            if (types.size() > 80 && !types.containsKey(path)) {
                return;
            }
            if (node.isObject()) {
                if (node.isEmpty()) {
                    type(path, "{}");
                }
                for (String name : node.propertyNames()) {
                    add(path.isEmpty() ? name : path + "." + name, node.get(name), a);
                }
            } else if (node.isArray()) {
                type(path, "array");
                arraySizes.merge(path, node.size(), Math::max);
                int n = 0;
                for (JsonNode item : node) {
                    if (n++ >= 20) {
                        break;
                    }
                    add(path + "[]", item, a);
                }
            } else {
                String t = node.isNull() ? "null" : node.isBoolean() ? "bool" : node.isNumber() ? "number" : "string";
                type(path, t);
                Set<String> v = values.computeIfAbsent(path, k -> new LinkedHashSet<>());
                if (!node.isNull()) {
                    if (v.size() < a.maxValues) {
                        v.add(node.isString() ? "\"" + a.value(node.asString()) + "\"" : node.toString());
                    } else {
                        v.add("...");
                    }
                }
            }
        }

        private void type(String path, String type) {
            types.computeIfAbsent(path.isEmpty() ? "(root)" : path, k -> new LinkedHashSet<>()).add(type);
        }

        void print(StringBuilder out, String label) {
            notes.forEach(n -> out.append("  ").append(label).append(": ").append(n).append('\n'));
            if (types.isEmpty()) {
                return;
            }
            out.append("  ").append(label).append(" json:\n");
            types.forEach((path, t) -> {
                out.append("    ").append(path).append(" : ").append(String.join("|", t));
                String key = path.equals("(root)") ? "" : path;
                if (arraySizes.containsKey(key)) {
                    out.append("[max ").append(arraySizes.get(key)).append(']');
                }
                Set<String> v = values.get(key);
                if (v != null && !v.isEmpty()) {
                    out.append(" = ").append(String.join(", ", v));
                }
                out.append('\n');
            });
        }
    }
}
