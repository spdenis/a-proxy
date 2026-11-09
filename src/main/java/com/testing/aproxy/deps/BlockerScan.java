package com.testing.aproxy.deps;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.testing.aproxy.webui.JobPage;

import org.jspecify.annotations.Nullable;

/**
 * Finds what keeps jobs from starting: walks up from the target jobs through their starting conditions (Quick View
 * shows one level; this follows each unmet dependency further) and through their boxes, and lists every dependency
 * that is not ready, the ones to complete first at the top.
 * <p>
 * A dependency is not ready when its atom of the condition does not hold now: e.g. for {@code s(JOB)} the job is not
 * in SUCCESS, or for {@code s(JOB,02.00)} its success ended more than two hours ago. Only atoms that keep the whole
 * condition false count: in {@code s(A) | s(B)} nothing blocks while one of them holds. AutoSys's own "satisfied"
 * flag (Quick View) is taken into account too: an atom holds only when neither says otherwise. A job in a box starts
 * only while its box runs, so a box that is not running blocks the jobs in it. A box that has started (or failed)
 * but is not complete is looked into: the jobs in it that keep it from completing are its blockers. One instance is
 * not thread-safe.
 */
public final class BlockerScan {

    public enum Mode { ALL, ROOT }

    /** What a dependency is: a job, a box (of a job waiting in it), or something the scan cannot look into. */
    public enum Kind { JOB, BOX, VARIABLE, EXTERNAL, UNKNOWN }

    /** Reads Quick View for the scan. */
    public interface Loader {

        /** The job's Quick View page; empty when there is no such job. */
        Optional<JobPage> load(String job);

        /** The jobs directly in a box (whose page is {@code page}), with their states. */
        List<JobPage.JobRow> jobsIn(String box, JobPage page);
    }

    /** A job asked about and its state. */
    public record Target(String name, @Nullable String status, @Nullable String statusText, @Nullable String box,
            @Nullable String condition, @Nullable String nextStartTime, boolean blocked) {
    }

    /**
     * Why a dependency keeps another job (its {@code job}) from starting.
     *
     * @param condition the atom of {@code job}'s condition, or {@code box} for the box of a job
     * @param anyOf     one of alternatives (an OR): any one of them becoming ready is enough
     */
    public record Blocking(String job, String condition, String reason, boolean anyOf) {
    }

    /**
     * A dependency that is not ready.
     *
     * @param root     nothing it depends on is unready: it is where to start
     * @param waitsFor the unready dependencies of this one (also in the list)
     * @param blocks   the jobs it keeps from starting directly, and why
     * @param targets  the target jobs it keeps from starting, directly or further down
     * @param cycle    part of a dependency loop (the order within the loop is arbitrary)
     */
    public record Blocker(String name, Kind kind, @Nullable String type, @Nullable String jobType,
            @Nullable String box, @Nullable String status, @Nullable String statusText, @Nullable String startTime,
            @Nullable String endTime, @Nullable String exitCode, boolean root, List<String> waitsFor,
            List<Blocking> blocks, List<String> targets, boolean cycle) {
    }

    /** @param truncated the scan stopped at its limits: there may be more blockers further up */
    public record Result(List<Target> targets, List<Blocker> blockers, boolean truncated) {
    }

    /** Thrown when a target job does not exist. */
    public static final class NoSuchJob extends RuntimeException {

        public NoSuchJob(String job) {
            super(job);
        }
    }

    private static final Set<String> STARTED = Set.of("RUNNING", "STARTING", "RESTART", "WAIT_REPLY");
    /* ran and ended badly: its box not running is a consequence, not a cause */
    private static final Set<String> ENDED_BADLY = Set.of("FAILURE", "TERMINATED");
    private static final Set<String> COMPLETED = Set.of("SUCCESS", "FAILURE", "TERMINATED");
    /* AutoSys treats conditions on a job on ice (or on no-exec) as met */
    private static final Set<String> COUNTS_AS_DONE = Set.of("ON_ICE", "ON_NOEXEC");
    /* a box in these states is looked into: its jobs say why it is not complete */
    private static final Set<String> LOOK_INSIDE = Set.of("RUNNING", "STARTING", "RESTART", "FAILURE", "TERMINATED");
    private static final Pattern LOOK_BACK = Pattern.compile("(\\d{1,4})(?:[.:](\\d{1,2}))?");
    private static final List<DateTimeFormatter> TIMES = List.of(
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.US),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US));

    private final Loader loader;
    private final Clock clock;
    private final ZoneId zone;
    private final int maxJobs;
    private final int maxDepth;

    private final Map<String, Optional<JobPage>> pages = new HashMap<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    /* job -> the unready dependencies it waits for (names as found), for every job scanned */
    private final Map<String, Set<String>> waits = new HashMap<>();
    private boolean truncated;

    public BlockerScan(Loader loader, Clock clock, ZoneId zone, int maxJobs, int maxDepth) {
        this.loader = loader;
        this.clock = clock;
        this.zone = zone;
        this.maxJobs = maxJobs;
        this.maxDepth = maxDepth;
    }

    private static final class Node {
        final String name;
        final Kind kind;
        final int order;
        int depth;
        JobPage.@Nullable JobRow row;
        final List<Blocking> blocks = new ArrayList<>();

        Node(String name, Kind kind, int order, int depth, JobPage.@Nullable JobRow row) {
            this.name = name;
            this.kind = kind;
            this.order = order;
            this.depth = depth;
            this.row = row;
        }
    }

    /** One unmet dependency of a job, found by {@link #blockersOf}. */
    private record Found(String name, Kind kind, JobPage.@Nullable JobRow row, Blocking blocking) {
    }

    private record Step(String job, JobPage page, int depth) {
    }

    public Result scan(List<String> jobs, Mode mode) {
        List<Target> targets = new ArrayList<>();
        Set<String> targetNames = new LinkedHashSet<>();
        Deque<Step> todo = new ArrayDeque<>();
        for (String job : jobs) {
            JobPage page = page(job).orElseThrow(() -> new NoSuchJob(job));
            String name = Objects.requireNonNullElse(page.attributes().get("Name"), job);
            if (targetNames.add(key(name))) {
                todo.add(new Step(name, page, 0));
            }
        }
        Map<String, Boolean> blocked = new HashMap<>();
        Set<String> scanned = new LinkedHashSet<>();
        while (!todo.isEmpty()) {
            Step step = todo.poll();
            if (!scanned.add(key(step.job()))) {
                continue;
            }
            JobPage.JobRow stepRow = row(step.page(), step.job());
            List<Found> found = isBox(stepRow) && LOOK_INSIDE.contains(stepRow.status())
                    ? inside(step.job(), step.page(), stepRow)
                    : blockersOf(step.job(), step.page());
            blocked.put(key(step.job()), !found.isEmpty());
            for (Found f : found) {
                Node node = nodes.get(key(f.name()));
                if (node == null) {
                    node = new Node(f.name(), f.kind(), nodes.size(), step.depth() + 1, f.row());
                    nodes.put(key(f.name()), node);
                    if (f.kind() == Kind.JOB || f.kind() == Kind.BOX) {
                        String status = f.row() == null ? null : f.row().status();
                        if (status != null && STARTED.contains(status) && !isBox(f.row())) {
                            // started: its own conditions were met, it only has to finish
                        } else if (step.depth() + 1 >= maxDepth) {
                            truncated = true;
                        } else {
                            Optional<JobPage> page = page(f.name());
                            page.ifPresent(p -> todo.add(new Step(f.name(), p, step.depth() + 1)));
                        }
                    }
                } else {
                    node.depth = Math.max(node.depth, step.depth() + 1);
                }
                node.blocks.add(f.blocking());
                waits.computeIfAbsent(key(step.job()), k -> new LinkedHashSet<>()).add(node.name);
            }
        }
        for (String name : targetNames) {
            JobPage page = pages.get(name).orElseThrow();
            JobPage.JobRow row = row(page, page.attributes().get("Name"));
            targets.add(new Target(page.attributes().get("Name"), row == null ? null : row.status(),
                    row == null ? null : row.statusText(), page.attributes().get("Box"),
                    page.attributes().get("Condition"), page.attributes().get("Next Start Time"),
                    Boolean.TRUE.equals(blocked.get(name))));
        }
        return new Result(targets, ordered(mode, targetNames), truncated);
    }

    /** The unmet dependencies that keep this job from starting now: atoms of its condition, and its box. */
    private List<Found> blockersOf(String job, JobPage page) {
        JobPage.JobRow row = row(page, job);
        String status = row == null ? null : row.status();
        if (status != null && STARTED.contains(status)) {
            return List.of();
        }
        List<Found> found = new ArrayList<>();
        String text = page.attributes().get("Condition");
        if (text != null && !text.isBlank()) {
            StartCondition condition = null;
            try {
                condition = StartCondition.parse(text);
            } catch (IllegalArgumentException e) {
                // not understood: what Quick View marks as not satisfied blocks
                for (JobPage.Condition c : page.conditions()) {
                    if (!Boolean.TRUE.equals(c.satisfied())) {
                        Optional<JobPage> dependency = page(c.condition());
                        found.add(new Found(c.condition(), dependency.isPresent() ? Kind.JOB : Kind.UNKNOWN,
                                dependency.map(p -> row(p, c.condition())).orElse(null),
                                new Blocking(job, c.condition(), "not satisfied in Quick View (current status "
                                        + c.currentStatus() + "); " + e.getMessage(), false)));
                    }
                }
            }
            if (condition != null) {
                found.addAll(evaluate(condition, job, row, page).blocking());
            }
        }
        String box = page.attributes().get("Box");
        if (box != null && !box.isBlank() && (status == null || !ENDED_BADLY.contains(status))) {
            Optional<JobPage> boxPage = page(box);
            JobPage.JobRow boxRow = boxPage.map(p -> row(p, box)).orElse(null);
            String boxStatus = boxRow == null ? null : boxRow.status();
            if (boxPage.isEmpty()) {
                found.add(new Found(box, Kind.UNKNOWN, null, new Blocking(job, "box", "its box " + box
                        + (pages.containsKey(key(box)) && skipped.contains(key(box)) ? " was not checked (limit)"
                                : " was not found"), false)));
            } else if (boxStatus == null || !STARTED.contains(boxStatus)) {
                found.add(new Found(box, Kind.BOX, boxRow, new Blocking(job, "box", "its box " + box + " is "
                        + (boxStatus == null ? "in an unknown state" : boxStatus) + ", not running: jobs in a box "
                        + "start only while it runs", false)));
            }
        }
        return found;
    }

    /**
     * Why a box that started (or ended badly) is not complete: the jobs in it that are not done. In a box that
     * ended in FAILURE / TERMINATED, the jobs that ended so; the others did not run because of them.
     */
    private List<Found> inside(String box, JobPage page, JobPage.JobRow boxRow) {
        String boxStatus = boxRow.status();
        boolean ended = ENDED_BADLY.contains(boxStatus);
        List<Found> found = new ArrayList<>();
        for (JobPage.JobRow child : jobsIn(box, page)) {
            String name = child.name();
            String status = child.status();
            if (name == null || waitsFor(name, box)) {
                continue; // the job that waits for this box is not why the box is not complete
            }
            String reason;
            if (ended) {
                if (status == null || !ENDED_BADLY.contains(status)) {
                    continue;
                }
                reason = "is " + status + " inside box " + box + ", which ended " + boxStatus;
            } else if (status != null && (status.equals("SUCCESS") || COUNTS_AS_DONE.contains(status))) {
                continue;
            } else {
                reason = status == null ? "its state inside box " + box + " is not known"
                        : "is " + status + " inside box " + box + (STARTED.contains(status) ? ", still going"
                                : ENDED_BADLY.contains(status) ? "" : ", not run yet")
                                + ": the box completes only when its jobs do";
            }
            found.add(new Found(name, isBox(child) ? Kind.BOX : Kind.JOB, child,
                    new Blocking(box, "inside box", reason, false)));
        }
        return found;
    }

    /** Whether this job was found waiting for that one already. */
    private boolean waitsFor(String job, String other) {
        return waits.getOrDefault(key(job), Set.of()).stream().anyMatch(w -> key(w).equals(key(other)));
    }

    private static boolean isBox(JobPage.@Nullable JobRow row) {
        return row != null && (JobPage.BOX.equals(row.type()) || JobPage.BOX.equals(row.jobType()));
    }

    private final Set<String> expanded = new LinkedHashSet<>();

    /** The jobs in a box, within the scan's limit (an expansion counts as one read). */
    private List<JobPage.JobRow> jobsIn(String box, JobPage page) {
        if (pages.size() + expanded.size() >= maxJobs) {
            truncated = true;
            return List.of();
        }
        expanded.add(key(box));
        return loader.jobsIn(box, page);
    }

    private record Evaluation(Truth truth, List<Found> blocking) {
    }

    private enum Truth { YES, NO, UNKNOWN }

    private Evaluation evaluate(StartCondition condition, String job, JobPage.@Nullable JobRow jobRow, JobPage page) {
        return switch (condition) {
            case StartCondition.Atom atom -> atom(atom, job, jobRow, page);
            case StartCondition.All all -> {
                List<Found> blocking = new ArrayList<>();
                Truth truth = Truth.YES;
                for (StartCondition part : all.parts()) {
                    Evaluation e = evaluate(part, job, jobRow, page);
                    if (e.truth() != Truth.YES) {
                        blocking.addAll(e.blocking());
                        truth = truth == Truth.NO || e.truth() == Truth.NO ? Truth.NO : Truth.UNKNOWN;
                    }
                }
                yield new Evaluation(truth, blocking);
            }
            case StartCondition.Any any -> {
                List<Found> blocking = new ArrayList<>();
                boolean unknown = false;
                for (StartCondition part : any.parts()) {
                    Evaluation e = evaluate(part, job, jobRow, page);
                    if (e.truth() == Truth.YES) {
                        yield new Evaluation(Truth.YES, List.of());
                    }
                    unknown |= e.truth() == Truth.UNKNOWN;
                    for (Found f : e.blocking()) {
                        Blocking b = f.blocking();
                        blocking.add(new Found(f.name(), f.kind(), f.row(),
                                new Blocking(b.job(), b.condition(), b.reason(), true)));
                    }
                }
                yield new Evaluation(unknown ? Truth.UNKNOWN : Truth.NO, blocking);
            }
        };
    }

    /** Whether one atom holds now, from the dependency's state and Quick View's "satisfied" flag. */
    private Evaluation atom(StartCondition.Atom atom, String job, JobPage.@Nullable JobRow jobRow, JobPage page) {
        JobPage.Condition shown = shown(atom, page);
        Boolean satisfied = shown == null ? null : shown.satisfied();
        if (atom.type() == StartCondition.Type.VALUE || atom.instance() != null) {
            // a global variable, or a job of another instance: only AutoSys's own answer is known
            if (Boolean.TRUE.equals(satisfied)) {
                return new Evaluation(Truth.YES, List.of());
            }
            Kind kind = atom.type() == StartCondition.Type.VALUE ? Kind.VARIABLE : Kind.EXTERNAL;
            String name = atom.instance() == null ? atom.name() : atom.name() + "^" + atom.instance();
            String reason = satisfied == null ? "cannot be checked through Quick View"
                    : "not satisfied in Quick View" + (shown.currentStatus() == null ? ""
                            : " (current " + shown.currentStatus() + ")");
            return blocking(satisfied == null ? Truth.UNKNOWN : Truth.NO,
                    new Found(name, kind, null, new Blocking(job, atom.text(), reason, false)));
        }
        if (Boolean.TRUE.equals(satisfied) && atom.lookBack() == null) {
            return new Evaluation(Truth.YES, List.of()); // AutoSys says so and there is no time frame to check
        }
        Optional<JobPage> dependency = page(atom.name());
        if (dependency.isEmpty()) {
            boolean limit = skipped.contains(key(atom.name()));
            if (limit) {
                truncated = true;
            }
            return blocking(Truth.UNKNOWN, new Found(atom.name(), Kind.UNKNOWN, null, new Blocking(job,
                    atom.text(), limit ? "not checked: the scan reached its limit" : "no such job in Quick View",
                    false)));
        }
        JobPage.JobRow row = row(dependency.get(), atom.name());
        String reason = notReady(atom, row, jobRow);
        if (reason == null && Boolean.FALSE.equals(satisfied)) {
            reason = "AutoSys shows it as not satisfied (current status " + (row == null ? shown.currentStatus()
                    : row.status()) + ")";
        }
        if (reason == null) {
            return new Evaluation(Truth.YES, List.of());
        }
        String name = Objects.requireNonNullElse(dependency.get().attributes().get("Name"), atom.name());
        return blocking(Truth.NO, new Found(name, Kind.JOB, row, new Blocking(job, atom.text(), reason, false)));
    }

    private static Evaluation blocking(Truth truth, Found found) {
        return new Evaluation(truth, List.of(found));
    }

    /** Why the atom does not hold for this state of its job, or null when it holds. */
    @Nullable String notReady(StartCondition.Atom atom, JobPage.@Nullable JobRow row, JobPage.@Nullable JobRow waiting) {
        String status = row == null ? null : row.status();
        if (status == null) {
            return "its status is not known";
        }
        boolean iced = COUNTS_AS_DONE.contains(status);
        String needs = switch (atom.type()) {
            case SUCCESS -> status.equals("SUCCESS") || iced ? null : "SUCCESS";
            case FAILURE -> status.equals("FAILURE") ? null : "FAILURE";
            case DONE -> COMPLETED.contains(status) || iced ? null : "SUCCESS, FAILURE or TERMINATED";
            case TERMINATED -> status.equals("TERMINATED") ? null : "TERMINATED";
            case NOTRUNNING -> STARTED.contains(status) ? "not to be running" : null;
            case EXITCODE -> COMPLETED.contains(status) ? null : "to have ended";
            case VALUE -> null;
        };
        if (needs != null) {
            return "is " + status + ", needs " + needs;
        }
        if (atom.type() == StartCondition.Type.EXITCODE) {
            String compared = exitCode(atom, row.exitCode());
            if (compared != null) {
                return compared;
            }
        }
        if (atom.lookBack() == null || iced || atom.type() == StartCondition.Type.NOTRUNNING) {
            return null;
        }
        return outsideLookBack(atom, row, waiting);
    }

    private static @Nullable String exitCode(StartCondition.Atom atom, @Nullable String exitCode) {
        Integer actual = number(exitCode);
        Integer expected = number(atom.value());
        if (actual == null || expected == null) {
            return "exit code " + exitCode + " cannot be compared with " + atom.operator() + " " + atom.value();
        }
        boolean holds = switch (Objects.requireNonNull(atom.operator())) {
            case "=" -> actual.equals(expected);
            case "!=" -> !actual.equals(expected);
            case "<" -> actual < expected;
            case ">" -> actual > expected;
            case "<=" -> actual <= expected;
            case ">=" -> actual >= expected;
            default -> false;
        };
        return holds ? null : "ended with exit code " + actual + ", needs " + atom.operator() + " " + expected;
    }

    /**
     * The look-back: the dependency must have ended within the last HH.MM hours; with 0, after the waiting job's
     * last start.
     */
    private @Nullable String outsideLookBack(StartCondition.Atom atom, JobPage.JobRow row,
            JobPage.@Nullable JobRow waiting) {
        Matcher m = LOOK_BACK.matcher(Objects.requireNonNull(atom.lookBack()));
        if (!m.matches()) {
            return "the look-back " + atom.lookBack() + " cannot be read";
        }
        Instant ended = time(row.endTime());
        if (ended == null) {
            return "has no end time to check against the look-back " + atom.lookBack();
        }
        Duration window = Duration.ofHours(Long.parseLong(m.group(1)))
                .plusMinutes(m.group(2) == null ? 0 : Long.parseLong(m.group(2)));
        if (window.isZero()) {
            Instant since = waiting == null ? null : time(waiting.startTime());
            return since == null || !ended.isBefore(since) ? null
                    : "ended " + row.endTime() + ", before the waiting job's last start " + waiting.startTime()
                            + " (look-back 0)";
        }
        Instant from = clock.instant().minus(window);
        return ended.isBefore(from) ? "is " + row.status() + " but ended " + row.endTime()
                + ", outside the look-back of " + atom.lookBack() + " hours" : null;
    }

    private @Nullable Instant time(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (DateTimeFormatter format : TIMES) {
            try {
                return LocalDateTime.parse(text.strip(), format).atZone(zone).toInstant();
            } catch (DateTimeParseException e) {
                // next format
            }
        }
        return null;
    }

    private static @Nullable Integer number(@Nullable String text) {
        try {
            return text == null ? null : Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Quick View's row for this atom: the one of its job, preferring the one with the atom's expected value. */
    private static JobPage.@Nullable Condition shown(StartCondition.Atom atom, JobPage page) {
        List<JobPage.Condition> same = page.conditions().stream()
                .filter(c -> c.condition().strip().equalsIgnoreCase(atom.instance() == null ? atom.name()
                        : atom.name() + "^" + atom.instance()) || c.condition().strip().equalsIgnoreCase(atom.name()))
                .toList();
        if (same.size() <= 1) {
            return same.isEmpty() ? null : same.getFirst();
        }
        String type = atom.type().name();
        return same.stream().filter(c -> c.expectedValue() != null
                && c.expectedValue().toUpperCase(Locale.ROOT).replace(" ", "").contains(type)).findFirst()
                .orElse(null); // ambiguous: rely on the job's state alone
    }

    private final Set<String> skipped = new LinkedHashSet<>();

    private Optional<JobPage> page(String job) {
        String key = key(job);
        Optional<JobPage> page = pages.get(key);
        if (page != null) {
            return page;
        }
        if (pages.size() + expanded.size() >= maxJobs) {
            truncated = true;
            skipped.add(key);
            return Optional.empty();
        }
        page = loader.load(job);
        pages.put(key, page);
        return page;
    }

    private static JobPage.@Nullable JobRow row(JobPage page, @Nullable String job) {
        return job == null ? null : page.row(job).orElse(null);
    }

    private static String key(String job) {
        return job.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * The blockers in the order to complete them: each after everything it waits for, the furthest up first.
     * ROOT keeps only those that wait for nothing (and the first of a loop).
     */
    private List<Blocker> ordered(Mode mode, Set<String> targetNames) {
        Map<String, Integer> waiting = new HashMap<>();
        nodes.keySet().forEach(key -> waiting.put(key, waitsFor(key).size()));
        Comparator<Node> first = Comparator.comparingInt((Node n) -> -n.depth).thenComparingInt(n -> n.order);
        List<Node> ready = new ArrayList<>(nodes.values().stream().filter(n -> waiting.get(key(n.name)) == 0)
                .toList());
        Set<String> roots = new LinkedHashSet<>(ready.stream().map(n -> key(n.name)).toList());
        Set<String> inCycle = new LinkedHashSet<>();
        List<Node> order = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        while (done.size() < nodes.size()) {
            if (ready.isEmpty()) {
                // a loop: start it at its furthest-up member; a root when all it waits for is done or in the loop
                Node pick = nodes.values().stream().filter(n -> !done.contains(key(n.name))).min(first).orElseThrow();
                String p = key(pick.name);
                for (String n : nodes.keySet()) {
                    if (!done.contains(n) && reaches(n, p) && reaches(p, n)) {
                        inCycle.add(n);
                    }
                }
                if (waitsFor(p).stream().allMatch(w -> done.contains(w) || inCycle.contains(w))) {
                    roots.add(p);
                }
                ready.add(pick);
            }
            ready.sort(first);
            Node next = ready.removeFirst();
            if (!done.add(key(next.name))) {
                continue;
            }
            order.add(next);
            for (Blocking b : next.blocks) {
                String dependent = key(b.job());
                if (nodes.containsKey(dependent) && !done.contains(dependent)
                        && waiting.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(nodes.get(dependent));
                }
            }
        }
        List<Blocker> result = new ArrayList<>();
        for (Node node : order) {
            boolean root = roots.contains(key(node.name));
            if (mode == Mode.ROOT && !root) {
                continue;
            }
            JobPage.JobRow r = node.row;
            result.add(new Blocker(node.name, node.kind, r == null ? null : r.type(), r == null ? null : r.jobType(),
                    r == null ? null : r.box(), r == null ? null : r.status(), r == null ? null : r.statusText(),
                    r == null ? null : r.startTime(), r == null ? null : r.endTime(), r == null ? null : r.exitCode(),
                    root, waits.getOrDefault(key(node.name), Set.of()).stream().toList(), List.copyOf(node.blocks),
                    targetsOf(node, targetNames, new LinkedHashSet<>()), inCycle.contains(key(node.name))));
        }
        return result;
    }

    /** The unready dependencies of this job that are in the list (keys). */
    private Set<String> waitsFor(String job) {
        Set<String> keys = new LinkedHashSet<>();
        for (String w : waits.getOrDefault(job, Set.of())) {
            if (nodes.containsKey(key(w))) {
                keys.add(key(w));
            }
        }
        return keys;
    }

    /** Whether {@code from} waits for {@code to}, directly or further up. */
    private boolean reaches(String from, String to) {
        Deque<String> todo = new ArrayDeque<>(waitsFor(from));
        Set<String> seen = new LinkedHashSet<>();
        while (!todo.isEmpty()) {
            String n = todo.pop();
            if (n.equals(to)) {
                return true;
            }
            if (seen.add(n)) {
                todo.addAll(waitsFor(n));
            }
        }
        return false;
    }

    private List<String> targetsOf(Node node, Set<String> targetNames, Set<String> seen) {
        Set<String> found = new LinkedHashSet<>();
        if (!seen.add(key(node.name))) {
            return List.of();
        }
        for (Blocking b : node.blocks) {
            if (targetNames.contains(key(b.job()))) {
                found.add(pages.get(key(b.job())).map(p -> p.attributes().get("Name")).orElse(b.job()));
            }
            Node dependent = nodes.get(key(b.job()));
            if (dependent != null) {
                found.addAll(targetsOf(dependent, targetNames, seen));
            }
        }
        return List.copyOf(found);
    }
}
