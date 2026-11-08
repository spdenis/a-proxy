package com.testing.aproxy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.webui.JobPage;

import org.junit.jupiter.api.Test;

class JobStatusCacheTest {

    private final TestClock clock = new TestClock();
    private final JobStatusCache cache = new JobStatusCache(properties(true, Duration.ofSeconds(30)), clock);
    private final AtomicInteger loads = new AtomicInteger();

    @Test
    void aSecondReadComesFromTheCacheAndFreshReadsAgain() {
        assertThat(read("LOADER", "s(FEED_SYNC)", "BOX").cached()).isFalse();
        JobStatusCache.Read<JobPage> again = read("loader", "s(FEED_SYNC)", "BOX");
        assertThat(again.cached()).isTrue();
        assertThat(loads).hasValue(1);

        assertThat(cache.job("carol", null, "LOADER", true, load(page("LOADER", null, null))).cached()).isFalse();
        assertThat(loads).hasValue(2);
    }

    @Test
    void anEntryExpiresAfterTheTtl() {
        read("LOADER", null, null);
        clock.advance(Duration.ofSeconds(29));
        assertThat(read("LOADER", null, null).cached()).isTrue();
        clock.advance(Duration.ofSeconds(2));
        assertThat(read("LOADER", null, null).cached()).isFalse();
    }

    @Test
    void anActionOnTheJobItselfDropsIt() {
        read("LOADER", null, null);
        cache.actionOn("loader");
        assertThat(read("LOADER", null, null).cached()).isFalse();
    }

    @Test
    void anActionOnAJobInTheConditionDropsTheJobButAnUnrelatedActionDoesNot() {
        read("LOADER", "s(FEED_SYNC,10.00) & d(OTHER_JOB)", null);
        cache.actionOn("UNRELATED");
        assertThat(read("LOADER", null, null).cached()).isTrue();

        cache.actionOn("OTHER_JOB");
        assertThat(read("LOADER", "s(FEED_SYNC,10.00) & d(OTHER_JOB)", null).cached()).isFalse();
        cache.actionOn("FEED_SYNC");
        assertThat(read("LOADER", null, null).cached()).isFalse();
    }

    @Test
    void theJobsThatDependOnAJobKeepTheirEntryWhenItIsTheDependentThatGetsTheAction() {
        // LOADER's page lists CLEANUP as a dependent; CLEANUP's action does not change LOADER
        read("LOADER", null, null);
        cache.actionOn("CLEANUP");
        assertThat(read("LOADER", null, null).cached()).isTrue();
    }

    @Test
    void anActionOnABoxDropsTheJobsInItDownTheNestedBoxes() {
        read("TOP_BOX", null, null);
        read("INNER_BOX", null, "TOP_BOX");
        read("LOADER", null, "INNER_BOX");
        read("ELSEWHERE", null, "OTHER_BOX");

        cache.actionOn("TOP_BOX");

        assertThat(read("TOP_BOX", null, null).cached()).isFalse();
        assertThat(read("INNER_BOX", null, "TOP_BOX").cached()).isFalse();
        assertThat(read("LOADER", null, "INNER_BOX").cached()).isFalse();
        assertThat(read("ELSEWHERE", null, "OTHER_BOX").cached()).isTrue();
    }

    @Test
    void anActionOnAJobDropsTheJobListOfItsBox() {
        Supplier<List<JobPage.JobRow>> rows = () -> {
            loads.incrementAndGet();
            return List.of(row("LOADER"), row("CLEANUP"));
        };
        cache.boxJobs("carol", null, "BOX", null, false, rows);
        assertThat(cache.boxJobs("carol", null, "BOX", null, false, rows).cached()).isTrue();

        cache.actionOn("CLEANUP");

        assertThat(cache.boxJobs("carol", null, "BOX", null, false, rows).cached()).isFalse();
        assertThat(loads).hasValue(2);
    }

    @Test
    void entriesArePerUserAndServerButAnActionDropsThemForEveryone() {
        read("LOADER", null, null);
        assertThat(cache.job("dave", null, "LOADER", false, load(page("LOADER", null, null))).cached()).isFalse();
        assertThat(cache.job("carol", "QA5", "LOADER", false, load(page("LOADER", null, null))).cached()).isFalse();

        cache.actionOn("LOADER");

        assertThat(cache.job("dave", null, "LOADER", false, load(page("LOADER", null, null))).cached()).isFalse();
        assertThat(cache.job("carol", "qa5", "LOADER", false, load(page("LOADER", null, null))).cached()).isFalse();
    }

    @Test
    void aReadThatWasOnItsWayDuringAnActionIsNotKept() {
        cache.job("carol", null, "LOADER", false, () -> {
            cache.actionOn("LOADER"); // the action completes while AutoSys is being read
            return page("LOADER", null, null);
        });
        assertThat(read("LOADER", null, null).cached()).isFalse();
    }

    @Test
    void aFailedReadIsNotCached() {
        assertThatThrownBy(() -> cache.job("carol", null, "NOPE", false, () -> {
            throw new IllegalStateException("not found");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(cache.job("carol", null, "NOPE", false, load(page("NOPE", null, null))).cached()).isFalse();
    }

    @Test
    void turnedOffNothingIsCached() {
        JobStatusCache off = new JobStatusCache(properties(false, Duration.ofSeconds(30)), clock);
        off.job("carol", null, "LOADER", false, load(page("LOADER", null, null)));
        assertThat(off.job("carol", null, "LOADER", false, load(page("LOADER", null, null))).cached()).isFalse();
    }

    @Test
    void conditionJobsComeFromTheListAndTheConditionText() {
        JobPage page = new JobPage(List.of(), Map.of("Condition", "s(A_JOB,03.00) & (f(B.JOB) | e(C#JOB) > 1) "
                + "& n(D_JOB^PRD)"), List.of(new JobPage.Condition("e_listed", null, null, true, null)),
                List.of(), List.of(), List.of());
        assertThat(JobStatusCache.conditionJobs(page)).containsExactlyInAnyOrder("A_JOB", "B.JOB", "C#JOB", "D_JOB",
                "E_LISTED");
    }

    private JobStatusCache.Read<JobPage> read(String job, String condition, String box) {
        return cache.job("carol", null, job, false, load(page(job, condition, box)));
    }

    private Supplier<JobPage> load(JobPage page) {
        return () -> {
            loads.incrementAndGet();
            return page;
        };
    }

    private static JobPage page(String job, String condition, String box) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Name", job);
        if (condition != null) {
            attributes.put("Condition", condition);
        }
        if (box != null) {
            attributes.put("Box", box);
        }
        return new JobPage(List.of(row(job)), attributes, List.of(),
                List.of(new JobPage.Dependent("CLEANUP", "s(" + job + ")", true, "Yes")), List.of(), List.of());
    }

    private static JobPage.JobRow row(String job) {
        return new JobPage.JobRow(job, "REGULAR", "CMD", null, null, null, "SUCCESS", "Success", "1", null, "0");
    }

    private static AutosysProperties properties(boolean enabled, Duration ttl) {
        return new AutosysProperties(URI.create("https://autosys.example.internal"), null, Set.of("DEV"), "DEV",
                null, null, null, null, null, null, null, null,
                new AutosysProperties.JobCache(enabled, ttl, 1000));
    }

    static final class TestClock extends Clock {

        private Instant now = Instant.parse("2026-11-05T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
