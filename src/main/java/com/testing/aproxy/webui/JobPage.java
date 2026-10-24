package com.testing.aproxy.webui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.testing.aproxy.capture.HtmlTables;

import org.jspecify.annotations.Nullable;

/**
 * What a Quick View job search response contains. Tables are recognised by their header
 * cells (not position or generated ids), as seen in WCC 11.x Quick View:
 * <ul>
 * <li>search results: (Select,) Name, Box Name, Start Time, End Time, Status, Run, Priority, Exit Code; the
 * Name cell starts with the job type icon ("Box (box)", "Command (cmd)") and, for a box, an expand icon. A
 * box's results are a tree: once expanded, its direct jobs follow it, with long names shortened to "..."</li>
 * <li>job definition: Attribute Name, Attribute Value (rows without a value are section titles)</li>
 * <li>conditions of the job: Atomic Condition, Expected Value, Current Status, Condition Satisfied</li>
 * <li>jobs depending on it: Job Name, Condition, Condition Satisfied</li>
 * <li>resources: Type, Name, Machine, Required, ...</li>
 * <li>run history: Start time, End Time, Status, Run, Exit Code, Machine</li>
 * </ul>
 */
public record JobPage(List<JobRow> results, Map<String, String> attributes, List<Condition> conditions,
        List<Dependent> dependents, List<Map<String, String>> resources, List<Run> runs) {

    private static final Pattern STATUS_CODE = Pattern.compile("\\(([A-Z][A-Z_]+)\\)\\s*$");
    private static final Pattern NO_ROWS = Pattern.compile("(?i)no (results|data|rows|records)( found)?\\.?");
    /* "[Select to expand|collapse] <type label> (<type code>) <name>" */
    private static final Pattern NAME_CELL = Pattern.compile(
            "^(?:Select to (?:expand|collapse)\\s+)?(?:(.+?)\\s+\\(([a-z][a-z0-9_]*)\\)\\s+)?(\\S.*)$");
    private static final Pattern GO_TO = Pattern.compile("(?i)\\btitle\\s*=\\s*[\"']Go to ([^\"']+)[\"']");
    private static final Pattern JOB_LINK = Pattern.compile("[?&;]jobName=([^&\"'#\\s]+)");

    public static final String BOX = "BOX";
    public static final String REGULAR = "REGULAR";

    /**
     * One row of the search results: the job's current status.
     *
     * @param type    {@link #BOX} (can contain jobs) or {@link #REGULAR}; null when the row shows no type icon
     * @param jobType AutoSys job type code, e.g. BOX, CMD, FW
     * @param box     the box this job is in
     */
    public record JobRow(@Nullable String name, @Nullable String type, @Nullable String jobType,
            @Nullable String box, @Nullable String startTime,
            @Nullable String endTime, @Nullable String status, @Nullable String statusText, @Nullable String run,
            @Nullable String priority, @Nullable String exitCode) {
    }

    /** An atomic condition of the job (upstream dependency). */
    public record Condition(String condition, @Nullable String expectedValue, @Nullable String currentStatus,
            @Nullable Boolean satisfied, @Nullable String satisfiedText) {
    }

    /** A job whose condition refers to this job (downstream dependency). */
    public record Dependent(String jobName, @Nullable String condition, @Nullable Boolean satisfied,
            @Nullable String satisfiedText) {
    }

    /** One past or current run; {@code run} is AutoSys {@code run_num/ntry}. */
    public record Run(@Nullable String startTime, @Nullable String endTime, @Nullable String status,
            @Nullable String statusText, @Nullable String run, @Nullable String exitCode, @Nullable String machine) {
    }

    public static JobPage parse(String response) {
        List<JobRow> results = new ArrayList<>();
        Map<String, String> attributes = new LinkedHashMap<>();
        List<Condition> conditions = new ArrayList<>();
        List<Dependent> dependents = new ArrayList<>();
        List<Map<String, String>> resources = new ArrayList<>();
        List<Run> runs = new ArrayList<>();

        for (HtmlTables.Table table : HtmlTables.parse(response)) {
            Columns c = new Columns(table.headers());
            if (c.isEmpty()) {
                continue;
            }
            if (c.has("Attribute Name") && c.has("Attribute Value")) {
                for (List<String> row : rows(table)) {
                    String name = c.get(row, "Attribute Name");
                    String value = c.get(row, "Attribute Value");
                    if (name != null && value != null) {
                        attributes.putIfAbsent(name, value);
                    }
                }
            } else if (c.has("Atomic Condition")) {
                for (List<String> row : rows(table)) {
                    String text = c.get(row, "Condition Satisfied");
                    String condition = c.get(row, "Atomic Condition");
                    if (condition != null) {
                        conditions.add(new Condition(condition, c.get(row, "Expected Value"),
                                c.get(row, "Current Status"), satisfied(text), text));
                    }
                }
            } else if (c.has("Job Name") && c.has("Condition")) {
                for (List<String> row : rows(table)) {
                    String text = c.get(row, "Condition Satisfied");
                    String job = c.get(row, "Job Name");
                    if (job != null) {
                        dependents.add(new Dependent(job, c.get(row, "Condition"), satisfied(text), text));
                    }
                }
            } else if (c.has("Box Name") && c.has("Status") && c.has("Exit Code")) {
                for (List<HtmlTables.Cell> cells : cellRows(table)) {
                    List<String> row = cells.stream().map(HtmlTables.Cell::text).toList();
                    String status = c.get(row, "Status");
                    int nameColumn = c.index("Name");
                    String[] name = nameCell(c.get(row, "Name"),
                            nameColumn < cells.size() ? cells.get(nameColumn).html() : "");
                    results.add(new JobRow(name[0], name[1], name[2], c.get(row, "Box Name"),
                            c.get(row, "Start Time"), c.get(row, "End Time"), statusCode(status), statusText(status),
                            c.get(row, "Run"), c.get(row, "Priority"), c.get(row, "Exit Code")));
                }
            } else if (c.has("Required") && c.has("Machine") && c.has("Type")) {
                for (List<String> row : rows(table)) {
                    Map<String, String> resource = new LinkedHashMap<>();
                    for (String header : c.headers) {
                        String value = c.get(row, header);
                        if (value != null) {
                            resource.put(header, value);
                        }
                    }
                    resources.add(resource);
                }
            } else if (c.has("Start Time") && c.has("Exit Code") && c.has("Machine")) {
                for (List<String> row : rows(table)) {
                    String status = c.get(row, "Status");
                    runs.add(new Run(c.get(row, "Start Time"), c.get(row, "End Time"), statusCode(status),
                            statusText(status), c.get(row, "Run"), c.get(row, "Exit Code"), c.get(row, "Machine")));
                }
            }
        }
        return new JobPage(List.copyOf(results), attributes, List.copyOf(conditions), List.copyOf(dependents),
                List.copyOf(resources), List.copyOf(runs));
    }

    /** The job definition shown is for this job (Quick View shows details only for an exact match). */
    public boolean hasDetailsFor(String jobName) {
        String shown = attributes.get("Name");
        return shown != null && shown.equalsIgnoreCase(jobName);
    }

    /** Status row of the job: the matching search result, or the only one. */
    public Optional<JobRow> row(String jobName) {
        return results.stream().filter(r -> jobName.equalsIgnoreCase(r.name())).findFirst()
                .or(() -> results.size() == 1 && hasDetailsFor(jobName)
                        ? Optional.of(withName(results.getFirst(), attributes.get("Name")))
                        : Optional.empty());
    }

    private static JobRow withName(JobRow r, String name) {
        return r.name() != null ? r
                : new JobRow(name, r.type(), r.jobType(), r.box(), r.startTime(), r.endTime(), r.status(),
                        r.statusText(), r.run(), r.priority(), r.exitCode());
    }

    /** The jobs directly in a box, as listed once the box is expanded (the box's own row excluded). */
    public List<JobRow> jobsIn(String boxName) {
        return results.stream()
                .filter(r -> boxName.equalsIgnoreCase(r.box()) && !boxName.equalsIgnoreCase(r.name()))
                .toList();
    }

    /**
     * Name, type and type code from a Name cell: the full name from the job link (the text may be
     * shortened), the type from its icon.
     */
    static @Nullable String[] nameCell(@Nullable String text, String html) {
        String name = null;
        String code = null;
        if (text != null) {
            Matcher m = NAME_CELL.matcher(text);
            if (m.matches()) {
                code = m.group(2);
                name = m.group(3).strip();
            }
        }
        Matcher title = GO_TO.matcher(html);
        Matcher link = JOB_LINK.matcher(html);
        if (title.find()) {
            name = HtmlTables.text(title.group(1));
        } else if (link.find()) {
            name = URLDecoder.decode(HtmlTables.text(link.group(1)), StandardCharsets.UTF_8);
        }
        String jobType = code == null ? null : code.toUpperCase(Locale.ROOT);
        String type = jobType == null ? null : jobType.equals(BOX) ? BOX : REGULAR;
        return new String[] {name, type, jobType};
    }

    /** {@code "Success (SUCCESS)"} gives SUCCESS, {@code "On Ice"} gives ON_ICE. */
    static @Nullable String statusCode(@Nullable String status) {
        if (status == null) {
            return null;
        }
        Matcher m = STATUS_CODE.matcher(status);
        return m.find() ? m.group(1) : status.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    static @Nullable String statusText(@Nullable String status) {
        return status == null ? null : STATUS_CODE.matcher(status).replaceAll("").strip();
    }

    /** Icon titles such as "Yes", "true", "Satisfied", "Not satisfied"; null when unknown. */
    static @Nullable Boolean satisfied(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (t.matches("(no|false|n|not\\b.*|un\\w*)") || t.matches(".*\\bnot (satisfied|met)\\b.*")) {
            return false;
        }
        if (t.matches("(yes|true|y|satisfied|met|ok)") || t.matches(".*\\b(satisfied|met)\\b.*")) {
            return true;
        }
        return null;
    }

    private static List<List<String>> rows(HtmlTables.Table table) {
        return cellRows(table).stream().map(r -> r.stream().map(HtmlTables.Cell::text).toList()).toList();
    }

    private static List<List<HtmlTables.Cell>> cellRows(HtmlTables.Table table) {
        return table.dataCells().stream()
                .filter(r -> !(r.stream().filter(c -> !c.text().isBlank()).count() == 1
                        && r.stream().anyMatch(c -> NO_ROWS.matcher(c.text().strip()).matches())))
                .toList();
    }

    private static final class Columns {

        final List<String> headers;

        Columns(List<String> headers) {
            this.headers = headers;
        }

        boolean isEmpty() {
            return headers.isEmpty();
        }

        boolean has(String header) {
            return index(header) >= 0;
        }

        int index(String header) {
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i).equalsIgnoreCase(header)) {
                    return i;
                }
            }
            return -1;
        }

        @Nullable String get(List<String> row, String header) {
            int i = index(header);
            if (i < 0 || i >= row.size()) {
                return null;
            }
            String v = row.get(i).strip();
            return v.isEmpty() ? null : v;
        }
    }
}
