package com.testing.aproxy.capture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Minimal, dependency-free extraction of HTML tables (nested tables supported) as rows of
 * cell texts. Also works on JSF/Trinidad partial-page XML responses, whose HTML sits in CDATA.
 */
public final class HtmlTables {

    private static final Pattern TABLE_TAG = Pattern.compile("(?is)<(/?)(table|tr|th|td)\\b([^>]*)>");
    private static final Pattern ANY_TAG = Pattern.compile("(?is)<script.*?</script>|<style.*?</style>|<[^>]*>");
    private static final Pattern IMG = Pattern.compile("(?is)<img\\b([^>]*)>");
    private static final Pattern ALT = Pattern.compile("(?i)\\balt\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern TITLE = Pattern.compile("(?i)\\btitle\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern ID = Pattern.compile("(?i)\\bid\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern SUMMARY = Pattern.compile("(?i)\\bsummary\\s*=\\s*[\"']([^\"']+)[\"']");

    private HtmlTables() {
    }

    /** @param html the cell's markup (without nested table tags), e.g. for link targets and titles */
    public record Cell(String text, boolean header, String html) {

        public Cell(String text, boolean header) {
            this(text, header, "");
        }
    }

    public record Table(int index, @Nullable String id, @Nullable String summary, int depth, List<List<Cell>> rows) {

        public List<String> headers() {
            for (List<Cell> row : rows) {
                if (!row.isEmpty() && row.stream().allMatch(Cell::header)) {
                    return row.stream().map(Cell::text).toList();
                }
            }
            return List.of();
        }

        /** Rows made of data cells, ignoring header rows and rows without any text. */
        public List<List<String>> dataRows() {
            return dataCells().stream().map(r -> r.stream().map(Cell::text).toList()).toList();
        }

        /** As {@link #dataRows()}, with each cell's markup. */
        public List<List<Cell>> dataCells() {
            return rows.stream()
                    .filter(r -> r.stream().anyMatch(c -> !c.header()))
                    .filter(r -> r.stream().anyMatch(c -> !c.text().isEmpty()))
                    .toList();
        }
    }

    public static List<Table> parse(String html) {
        String source = html.replace("<![CDATA[", "").replace("]]>", "");
        List<Table> done = new ArrayList<>();
        Deque<Builder> open = new ArrayDeque<>();
        int counter = 0;
        int last = 0;
        Matcher m = TABLE_TAG.matcher(source);
        while (m.find()) {
            // a cell's text includes nested tables (Trinidad wraps links and icons in layout tables)
            String chunk = source.substring(last, m.start());
            open.forEach(b -> b.text(chunk));
            last = m.end();
            boolean closing = !m.group(1).isEmpty();
            String tag = m.group(2).toLowerCase(java.util.Locale.ROOT);
            switch (tag) {
                case "table" -> {
                    if (closing) {
                        if (!open.isEmpty()) {
                            done.add(open.pop().build());
                        }
                    } else {
                        open.push(new Builder(++counter, attr(ID, m.group(3)), attr(SUMMARY, m.group(3)), open.size()));
                    }
                }
                case "tr" -> {
                    if (!open.isEmpty()) {
                        if (closing) {
                            open.peek().endRow();
                        } else {
                            open.peek().startRow();
                        }
                    }
                }
                default -> {
                    if (!open.isEmpty()) {
                        if (closing) {
                            open.peek().endCell();
                        } else {
                            open.peek().startCell(tag.equals("th"));
                        }
                    }
                }
            }
        }
        while (!open.isEmpty()) {
            done.add(open.pop().build());
        }
        done.sort((a, b) -> Integer.compare(a.index(), b.index()));
        return done;
    }

    public static String text(String html) {
        // status and "satisfied" columns are often icons: use their alt/title text
        String t = IMG.matcher(html).replaceAll(img -> {
            // alt first: WCC puts the status code there, e.g. alt="Success (SUCCESS)" title="Success"
            Matcher alt = ALT.matcher(img.group(1));
            Matcher title = TITLE.matcher(img.group(1));
            String text = alt.find() ? alt.group(1) : title.find() ? title.group(1) : "";
            return " " + Matcher.quoteReplacement(text) + " ";
        });
        t = ANY_TAG.matcher(t).replaceAll(" ");
        t = t.replace("&nbsp;", " ").replace("&#160;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
        return t.replaceAll("\\s+", " ").strip();
    }

    private static @Nullable String attr(Pattern p, String attrs) {
        Matcher m = p.matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static final class Builder {

        final int index;
        final @Nullable String id;
        final @Nullable String summary;
        final int depth;
        final List<List<Cell>> rows = new ArrayList<>();
        @Nullable List<Cell> row;
        @Nullable StringBuilder cell;
        boolean cellIsHeader;

        Builder(int index, @Nullable String id, @Nullable String summary, int depth) {
            this.index = index;
            this.id = id;
            this.summary = summary;
            this.depth = depth;
        }

        void text(String html) {
            if (cell != null) {
                cell.append(' ').append(html);
            }
        }

        void startRow() {
            endRow();
            row = new ArrayList<>();
        }

        void endRow() {
            endCell();
            if (row != null) {
                rows.add(row);
                row = null;
            }
        }

        void startCell(boolean header) {
            endCell();
            if (row == null) {
                row = new ArrayList<>();
            }
            cell = new StringBuilder();
            cellIsHeader = header;
        }

        void endCell() {
            if (cell != null && row != null) {
                String html = cell.toString();
                row.add(new Cell(HtmlTables.text(html), cellIsHeader, html.strip()));
            }
            cell = null;
        }

        Table build() {
            endRow();
            return new Table(index, id, summary, depth, List.copyOf(rows));
        }
    }
}
