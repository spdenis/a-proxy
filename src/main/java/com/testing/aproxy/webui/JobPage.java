package com.testing.aproxy.webui;

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
 * <li>search results: Name, Box Name, Start Time, End Time, Status, Run, Priority, Exit Code</li>
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

    /** One row of the search results: the job's current status. */
    public record JobRow(@Nullable String name, @Nullable String box, @Nullable String startTime,
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
                for (List<String> row : rows(table)) {
                    String status = c.get(row, "Status");
                    results.add(new JobRow(c.get(row, "Name"), c.get(row, "Box Name"), c.get(row, "Start Time"),
                            c.get(row, "End Time"), statusCode(status), statusText(status), c.get(row, "Run"),
                            c.get(row, "Priority"), c.get(row, "Exit Code")));
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
                : new JobRow(name, r.box(), r.startTime(), r.endTime(), r.status(), r.statusText(), r.run(),
                        r.priority(), r.exitCode());
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
        if (t.matches("(no|false|n|not\\b.*|un\\w*)")) {
            return false;
        }
        if (t.matches("(yes|true|y|satisfied|met|ok)")) {
            return true;
        }
        return null;
    }

    private static List<List<String>> rows(HtmlTables.Table table) {
        return table.dataRows().stream()
                .filter(r -> !(r.stream().filter(t -> !t.isBlank()).count() == 1
                        && r.stream().anyMatch(t -> NO_ROWS.matcher(t.strip()).matches())))
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
