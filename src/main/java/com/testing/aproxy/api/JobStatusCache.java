package com.testing.aproxy.api;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.webui.JobPage;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Short-lived cache of job reads (the job's Quick View page, a box's job list), so repeated questions about the
 * same job do not each cost a Quick View search.
 * <p>
 * An entry goes away when its time is up ({@code autosys.job-cache.ttl}), or as soon as an action is sent through
 * the API on a job it depends on: the job itself, a job named in its condition, or the box it is in (and that box's
 * boxes). A box's job list also goes when one of its jobs gets an action. Entries are per AutoSys user and server,
 * since users may see different jobs. Changes made elsewhere (the AutoSys UI, the scheduler) are only seen after the
 * time is up.
 */
@Component
public class JobStatusCache {

    private static final Logger log = LoggerFactory.getLogger(JobStatusCache.class);
    /* job names in a condition: s(JOB), success(JOB,10.00), d(JOB), e(JOB)>1, JOB^INSTANCE, ... */
    private static final Pattern CONDITION_JOB = Pattern.compile("[A-Za-z]+\\s*\\(\\s*([A-Za-z0-9_.#-]+)");

    enum Kind { JOB, BOX_JOBS }

    private record Key(String user, String server, Kind kind, String job) {
    }

    /** @param related jobs whose actions make this entry stale; @param box the box this job is in, or null */
    private record Entry(Object value, Instant fetchedAt, Set<String> related, @Nullable String box) {
    }

    /** A read result, and whether it came from the cache. */
    public record Read<T>(T value, Instant fetchedAt, boolean cached) {
    }

    private final boolean enabled;
    private final Clock clock;
    private final Cache<Key, Entry> entries;
    /* counts evictions by actions, so a read that started before one does not store what it read */
    private final AtomicLong actions = new AtomicLong();

    public JobStatusCache(AutosysProperties properties, Clock clock) {
        AutosysProperties.JobCache config = properties.jobCache();
        this.enabled = config.enabled() && !config.ttl().isZero();
        this.clock = clock;
        this.entries = Caffeine.newBuilder()
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .expireAfterWrite(config.ttl()).maximumSize(config.maxEntries()).build();
    }

    /** The job's Quick View page; {@code fresh} reads AutoSys again (and caches that). */
    public Read<JobPage> job(String user, @Nullable String server, String job, boolean fresh, Supplier<JobPage> load) {
        return read(new Key(user, norm(server), Kind.JOB, norm(job)), fresh, load,
                page -> conditionJobs(page), page -> page.attributes().get("Box"));
    }

    /** The jobs directly in a box ({@code box} is the box the box itself is in, null if none). */
    public Read<List<JobPage.JobRow>> boxJobs(String user, @Nullable String server, String boxName,
            @Nullable String box, boolean fresh, Supplier<List<JobPage.JobRow>> load) {
        return read(new Key(user, norm(server), Kind.BOX_JOBS, norm(boxName)), fresh, load,
                rows -> {
                    Set<String> names = new HashSet<>();
                    rows.forEach(r -> {
                        if (r.name() != null) {
                            names.add(norm(r.name()));
                        }
                    });
                    return names;
                }, rows -> box);
    }

    @SuppressWarnings("unchecked")
    private <T> Read<T> read(Key key, boolean fresh, Supplier<T> load, java.util.function.Function<T, Set<String>> related,
            java.util.function.Function<T, @Nullable String> box) {
        if (enabled && !fresh) {
            Entry entry = entries.getIfPresent(key);
            if (entry != null) {
                return new Read<>((T) entry.value(), entry.fetchedAt(), true);
            }
        }
        long before = actions.get();
        Instant at = clock.instant();
        T value = load.get();
        // an action evicted entries while this read was on its way: what it read may be from before the action
        if (enabled && actions.get() == before) {
            entries.put(key, new Entry(value, at, Set.copyOf(related.apply(value)), norm(box.apply(value))));
        }
        return new Read<>(value, at, false);
    }

    /**
     * An action was sent (or may have been) on this job: drops what it makes stale, for every user and server.
     * Jobs in a box it drops go too, down the nested boxes.
     */
    public void actionOn(String job) {
        actions.incrementAndGet();
        Set<String> boxes = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(norm(job)));
        int dropped = 0;
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!boxes.add(name)) {
                continue;
            }
            for (var e : entries.asMap().entrySet()) {
                Key key = e.getKey();
                Entry entry = e.getValue();
                boolean inBox = name.equals(entry.box());
                if (key.job().equals(name) || inBox || entry.related().contains(name)) {
                    if (entries.asMap().remove(key, entry)) {
                        dropped++;
                    }
                    if (inBox && key.kind() == Kind.JOB) {
                        todo.add(key.job()); // a nested box: its jobs change with it
                    }
                }
            }
        }
        if (dropped > 0) {
            log.info("[cache] action on {}: dropped {} cached job read(s)", job, dropped);
        }
    }

    /** The jobs the job's condition names, from Quick View's condition list and the condition text. */
    static Set<String> conditionJobs(JobPage page) {
        Set<String> jobs = new HashSet<>();
        page.conditions().forEach(c -> jobs.add(norm(c.condition())));
        String text = page.attributes().get("Condition");
        if (text != null) {
            Matcher m = CONDITION_JOB.matcher(text);
            while (m.find()) {
                jobs.add(norm(m.group(1)));
            }
        }
        jobs.remove("");
        return jobs;
    }

    private static String norm(@Nullable String name) {
        return Objects.requireNonNullElse(name, "").strip().toUpperCase(Locale.ROOT);
    }
}
