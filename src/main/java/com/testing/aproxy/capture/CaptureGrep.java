package com.testing.aproxy.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds where a text occurs in a capture run and prints the raw markup around each match, e.g.
 * where a token or ViewState is first issued, or how a status icon is rendered in a table row.
 * Used by {@code analyzeCaptures --args="--grep=<regex>"}.
 */
final class CaptureGrep {

    private final Pattern pattern;
    private final int context;
    private final int maxMatches;
    private final boolean responsesOnly;

    CaptureGrep(Pattern pattern, int context, int maxMatches, boolean responsesOnly) {
        this.pattern = pattern;
        this.responsesOnly = responsesOnly;
        this.context = context;
        this.maxMatches = maxMatches;
    }

    String grep(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(f -> f.getFileName().toString().matches("\\d{4}-.*\\.json")).sorted().toList();
        }
        StringBuilder out = new StringBuilder("GREP /").append(pattern.pattern()).append("/ in ").append(dir)
                .append('\n');
        int hits = 0;
        for (Path f : files) {
            hits += grep(JsonMapper.shared().readTree(f.toFile()), out);
        }
        out.append("-- ").append(hits).append(" matching requests of ").append(files.size()).append('\n');
        return out.toString();
    }

    /** @return 1 when the capture matched */
    int grep(JsonNode c, StringBuilder out) {
        JsonNode req = c.at("/request");
        JsonNode res = c.at("/response");
        StringBuilder found = new StringBuilder();
        if (!responsesOnly) {
            search("query", req.at("/query"), found);
            search("request headers", req.at("/headers"), found);
            search("request body", req.at("/body"), found);
        }
        search("response headers", res.at("/headers"), found);
        search("response body", res.at("/body"), found);
        if (found.isEmpty()) {
            return 0;
        }
        out.append("== seq=").append(c.at("/seq").asInt()).append(' ').append(req.at("/method").asString("?"))
                .append(' ').append(req.at("/autosysPath").asString(req.at("/path").asString(""))).append(" -> ")
                .append(res.at("/status").asInt()).append('\n').append(found);
        return 1;
    }

    private void search(String where, JsonNode node, StringBuilder out) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        String text = (node.isString() ? node.asString() : node.toString()).replaceAll("\\s+", " ");
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            if (n++ == maxMatches) {
                out.append("  [").append(where).append("] ... more matches (--rows=N)\n");
                break;
            }
            int from = Math.max(0, m.start() - context);
            int to = Math.min(text.length(), m.end() + context);
            out.append("  [").append(where).append(" @").append(m.start()).append("] ")
                    .append(from > 0 ? "..." : "").append(text, from, to).append(to < text.length() ? "..." : "")
                    .append('\n');
        }
    }
}
