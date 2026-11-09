package com.testing.aproxy.deps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.testing.aproxy.deps.BlockerScan.Blocker;
import com.testing.aproxy.deps.BlockerScan.Kind;
import com.testing.aproxy.deps.BlockerScan.Mode;
import com.testing.aproxy.webui.JobPage;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BlockerScanTest {

    /* "now" is 8 Nov 2026 12:00 in the AutoSys server's zone (UTC here) */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);
    private static final String TODAY_9 = "Nov 8, 2026 9:00:00 AM";
    private static final String TODAY_3 = "Nov 8, 2026 3:00:00 AM";
    private static final String YESTERDAY = "Nov 7, 2026 10:00:00 PM";

    private final Map<String, JobPage> jobs = new HashMap<>();
    private final List<String> loaded = new ArrayList<>();

    @Test
    void followsUnreadyDependenciesUpAndListsTheRootFirst() {
        job("TARGET", "ACTIVATED", null, null, "s(MIDDLE)", shown("MIDDLE", false));
        job("MIDDLE", "INACTIVE", null, null, "s(ROOT_JOB)", shown("ROOT_JOB", false));
        job("ROOT_JOB", "FAILURE", TODAY_9, null, null);

        List<Blocker> all = scan(Mode.ALL, "TARGET");

        assertThat(all).extracting(Blocker::name).containsExactly("ROOT_JOB", "MIDDLE");
        assertThat(all.get(0).root()).isTrue();
        assertThat(all.get(0).blocks().getFirst().reason()).isEqualTo("is FAILURE, needs SUCCESS");
        assertThat(all.get(0).targets()).containsExactly("TARGET");
        assertThat(all.get(1).root()).isFalse();
        assertThat(all.get(1).waitsFor()).containsExactly("ROOT_JOB");
        assertThat(all.get(1).blocks().getFirst().job()).isEqualTo("TARGET");

        assertThat(scan(Mode.ROOT, "TARGET")).extracting(Blocker::name).containsExactly("ROOT_JOB");
    }

    @Test
    void aConditionAutoSysShowsAsSatisfiedWithoutTimeFrameIsNotLookedInto() {
        job("TARGET", "ACTIVATED", null, null, "s(FEED)", shown("FEED", true));
        job("FEED", "SUCCESS", TODAY_9, null, null);

        assertThat(scan(Mode.ALL, "TARGET")).isEmpty();
        assertThat(loaded).containsExactly("TARGET");
    }

    @Test
    void aSuccessOutsideTheLookBackIsNotReadyEvenWhenShownAsSatisfied() {
        job("TARGET", "ACTIVATED", null, null, "s(FEED,02.00) & s(OTHER,10.00)",
                shown("FEED", true), shown("OTHER", true));
        job("FEED", "SUCCESS", TODAY_3, null, null);   // 9 hours ago
        job("OTHER", "SUCCESS", TODAY_9, null, null);  // 3 hours ago: within 10

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactly("FEED");
        assertThat(blockers.getFirst().blocks().getFirst().reason())
                .isEqualTo("is SUCCESS but ended " + TODAY_3 + ", outside the look-back of 02.00 hours");
        assertThat(blockers.getFirst().blocks().getFirst().condition()).isEqualTo("s(FEED,02.00)");
    }

    @Test
    void lookBackZeroNeedsAnEndAfterTheWaitingJobsLastStart() {
        job("TARGET", "INACTIVE", null, null, "s(FEED,0)").startedAt(TODAY_9);
        job("FEED", "SUCCESS", YESTERDAY, null, null);

        assertThat(scan(Mode.ALL, "TARGET").getFirst().blocks().getFirst().reason())
                .contains("before the waiting job's last start");
    }

    @Test
    void anOrIsMetByOneReadyAlternativeElseAllAlternativesAreListed() {
        job("TARGET", "ACTIVATED", null, null, "s(A) | s(B)");
        job("A", "SUCCESS", TODAY_9, null, null);
        job("B", "FAILURE", TODAY_9, null, null);
        assertThat(scan(Mode.ALL, "TARGET")).isEmpty();

        job("A", "TERMINATED", TODAY_9, null, null);
        jobsReloaded();
        List<Blocker> blockers = scan(Mode.ALL, "TARGET");
        assertThat(blockers).extracting(Blocker::name).containsExactly("A", "B");
        assertThat(blockers).allMatch(b -> b.blocks().getFirst().anyOf());
    }

    @Test
    void theConditionTypeDecidesWhatIsReady() {
        job("TARGET", "ACTIVATED", null, null, "f(A) & d(B) & t(C) & e(D) > 1 & n(E) & s(ICED)");
        job("A", "FAILURE", TODAY_9, null, null);
        job("B", "TERMINATED", TODAY_9, null, null);
        job("C", "SUCCESS", TODAY_9, null, null);
        job("D", "FAILURE", TODAY_9, null, null).exitCode("1");
        job("E", "RUNNING", TODAY_9, null, null);
        job("ICED", "ON_ICE", null, null, null); // AutoSys counts a condition on an iced job as met

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactly("C", "D", "E");
        assertThat(blockers.get(0).blocks().getFirst().reason()).isEqualTo("is SUCCESS, needs TERMINATED");
        assertThat(blockers.get(1).blocks().getFirst().reason()).isEqualTo("ended with exit code 1, needs > 1");
        assertThat(blockers.get(2).blocks().getFirst().reason()).isEqualTo("is RUNNING, needs not to be running");
    }

    @Test
    void aRunningDependencyIsListedButWhatItWaitedForIsNot() {
        job("TARGET", "ACTIVATED", null, null, "s(LOADER)");
        job("LOADER", "RUNNING", null, null, "s(NEVER_LOOKED_AT)");

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactly("LOADER");
        assertThat(blockers.getFirst().root()).isTrue();
        assertThat(loaded).doesNotContain("NEVER_LOOKED_AT");
    }

    @Test
    void aBoxThatIsNotRunningBlocksTheJobsWaitingInItAndIsFollowedUp() {
        job("TARGET", "INACTIVE", null, "BOX", null);
        job("BOX", "INACTIVE", null, "TOP_BOX", "s(FEED_SYNC)").type("BOX");
        job("TOP_BOX", "RUNNING", TODAY_9, null, null).type("BOX");
        job("FEED_SYNC", "FAILURE", TODAY_9, null, null);

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactly("FEED_SYNC", "BOX");
        Blocker box = blockers.get(1);
        assertThat(box.kind()).isEqualTo(Kind.BOX);
        assertThat(box.blocks().getFirst().condition()).isEqualTo("box");
        assertThat(box.blocks().getFirst().reason()).startsWith("its box BOX is INACTIVE, not running");
        assertThat(box.waitsFor()).containsExactly("FEED_SYNC");
    }

    @Test
    void theBoxOfAFailedJobIsNotItsBlocker() {
        job("TARGET", "ACTIVATED", null, null, "s(LOADER)");
        job("LOADER", "FAILURE", TODAY_9, "BOX", null);
        job("BOX", "FAILURE", TODAY_9, null, null).type("BOX");

        assertThat(scan(Mode.ALL, "TARGET")).extracting(Blocker::name).containsExactly("LOADER");
    }

    @Test
    void variablesOtherInstancesAndMissingJobsAreReportedButNotFollowed() {
        job("TARGET", "ACTIVATED", null, null, "v(MODE) = \"FULL\" & s(FEED^PRD) & s(GONE)",
                shown("FEED^PRD", false));

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name, Blocker::kind).containsExactly(
                org.assertj.core.groups.Tuple.tuple("MODE", Kind.VARIABLE),
                org.assertj.core.groups.Tuple.tuple("FEED^PRD", Kind.EXTERNAL),
                org.assertj.core.groups.Tuple.tuple("GONE", Kind.UNKNOWN));
        assertThat(blockers.get(0).blocks().getFirst().reason()).isEqualTo("cannot be checked through Quick View");
        assertThat(blockers.get(2).blocks().getFirst().reason()).isEqualTo("no such job in Quick View");
    }

    @Test
    void severalTargetsShareTheirBlockersInCompletionOrder() {
        job("NAM", "ACTIVATED", null, null, "s(LOAD_NAM)");
        job("EMEA", "ACTIVATED", null, null, "s(LOAD_EMEA)");
        job("LOAD_NAM", "INACTIVE", null, null, "s(SYNC)");
        job("LOAD_EMEA", "INACTIVE", null, null, "s(SYNC)");
        job("SYNC", "FAILURE", TODAY_9, null, null);

        BlockerScan.Result result = scanner().scan(List.of("NAM", "EMEA"), Mode.ALL);

        assertThat(result.blockers()).extracting(Blocker::name).containsExactly("SYNC", "LOAD_NAM", "LOAD_EMEA");
        assertThat(result.blockers().getFirst().targets()).containsExactly("NAM", "EMEA");
        assertThat(result.blockers().getFirst().blocks()).extracting(BlockerScan.Blocking::job)
                .containsExactly("LOAD_NAM", "LOAD_EMEA");
        assertThat(result.targets()).extracting(BlockerScan.Target::blocked).containsExactly(true, true);
    }

    @Test
    void aTargetThatBlocksAnotherTargetIsListedWithWhatItWaitsFor() {
        job("FIRST", "ACTIVATED", null, null, "s(SECOND)");
        job("SECOND", "ACTIVATED", null, null, "s(ROOT_JOB)");
        job("ROOT_JOB", "FAILURE", TODAY_9, null, null);

        // SECOND is scanned before it is found as FIRST's blocker
        List<Blocker> blockers = scanner().scan(List.of("SECOND", "FIRST"), Mode.ALL).blockers();

        assertThat(blockers).extracting(Blocker::name).containsExactly("ROOT_JOB", "SECOND");
        assertThat(blockers.get(1).waitsFor()).containsExactly("ROOT_JOB");
        assertThat(blockers.get(1).root()).isFalse();
    }

    @Test
    void aLoopIsListedOnceWithOneMemberAsItsRoot() {
        job("TARGET", "ACTIVATED", null, null, "s(A)");
        job("A", "INACTIVE", null, null, "s(B)");
        job("B", "INACTIVE", null, null, "s(A)");

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactlyInAnyOrder("A", "B");
        assertThat(blockers).allMatch(Blocker::cycle);
        assertThat(blockers.stream().filter(Blocker::root)).hasSize(1);
        assertThat(scan(Mode.ROOT, "TARGET")).hasSize(1);
    }

    @Test
    void aRunningTargetHasNoBlockers() {
        job("TARGET", "RUNNING", null, "BOX", "s(A)");
        assertThat(scan(Mode.ALL, "TARGET")).isEmpty();
    }

    @Test
    void stopsAtItsLimitAndSaysSo() {
        job("TARGET", "ACTIVATED", null, null, "s(A)");
        job("A", "INACTIVE", null, null, "s(B)");
        job("B", "INACTIVE", null, null, "s(C)");
        job("C", "FAILURE", TODAY_9, null, null);

        BlockerScan.Result result = new BlockerScan(fake, CLOCK, ZoneId.of("UTC"), 3, 30)
                .scan(List.of("TARGET"), Mode.ALL);

        assertThat(result.truncated()).isTrue();
        assertThat(result.blockers()).extracting(Blocker::name).containsExactly("C", "B", "A");
        assertThat(result.blockers().getFirst().kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(result.blockers().getFirst().blocks().getFirst().reason()).contains("limit");
    }

    @Test
    void anUnknownTargetIsAnError() {
        assertThatThrownBy(() -> scan(Mode.ALL, "NOPE")).isInstanceOf(BlockerScan.NoSuchJob.class);
    }

    @Test
    void aConditionItCannotReadFallsBackOnWhatQuickViewMarksAsNotSatisfied() {
        job("TARGET", "ACTIVATED", null, null, "s(A) ^^ weird", shown("A", false), shown("B", true));
        job("A", "FAILURE", TODAY_9, null, null);

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactly("A");
        assertThat(blockers.getFirst().blocks().getFirst().reason()).contains("not satisfied in Quick View");
    }

    @Test
    void aRunningBoxThatIsABlockerIsLookedIntoForTheJobsThatKeepItFromCompleting() {
        job("TARGET", "ACTIVATED", null, null, "s(BOX)");
        job("BOX", "RUNNING", null, null, null).type("BOX");
        job("DONE_JOB", "SUCCESS", TODAY_9, "BOX", null);
        job("LOADER", "RUNNING", null, "BOX", null);
        job("REPORT", "ACTIVATED", null, "BOX", "s(LOADER)");

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactly("LOADER", "REPORT", "BOX");
        assertThat(blockers.get(0).root()).isTrue();
        assertThat(blockers.get(0).blocks()).extracting(BlockerScan.Blocking::job).containsExactly("BOX", "REPORT");
        assertThat(blockers.get(0).blocks().getFirst().condition()).isEqualTo("inside box");
        assertThat(blockers.get(0).blocks().getFirst().reason())
                .isEqualTo("is RUNNING inside box BOX, still going: the box completes only when its jobs do");
        assertThat(blockers.get(1).waitsFor()).containsExactly("LOADER");
        assertThat(blockers.get(2).waitsFor()).containsExactly("LOADER", "REPORT");
        assertThat(blockers.get(2).blocks().getFirst().reason()).isEqualTo("is RUNNING, needs SUCCESS");

        assertThat(scan(Mode.ROOT, "TARGET")).extracting(Blocker::name).containsExactly("LOADER");
    }

    @Test
    void aFailedBoxListsTheJobsThatFailedNotTheOnesThatNeverRan() {
        job("TARGET", "ACTIVATED", null, null, "s(BOX)");
        job("BOX", "FAILURE", TODAY_9, null, null).type("BOX");
        job("BROKEN", "FAILURE", TODAY_9, "BOX", null);
        job("NEVER_RAN", "INACTIVE", null, "BOX", "s(BROKEN)");

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactly("BROKEN", "BOX");
        assertThat(blockers.getFirst().blocks().getFirst().reason()).isEqualTo("is FAILURE inside box BOX, which "
                + "ended FAILURE");
    }

    @Test
    void theBoxOfAWaitingJobIsLookedIntoWhenItFailed() {
        job("TARGET", "INACTIVE", null, "BOX", null);
        job("BOX", "FAILURE", TODAY_9, null, null).type("BOX");
        job("SIBLING", "FAILURE", TODAY_9, "BOX", null);

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactly("SIBLING", "BOX");
        assertThat(blockers.get(1).blocks().getFirst().reason()).startsWith("its box BOX is FAILURE, not running");
    }

    @Test
    void nestedBoxesAreLookedIntoDownToTheJob() {
        job("TARGET", "ACTIVATED", null, null, "s(OUTER)");
        job("OUTER", "RUNNING", null, null, null).type("BOX");
        job("INNER", "FAILURE", TODAY_9, "OUTER", null).type("BOX");
        job("OK_JOB", "SUCCESS", TODAY_9, "OUTER", null);
        job("BROKEN", "FAILURE", TODAY_9, "INNER", null);

        List<Blocker> blockers = scan(Mode.ALL, "TARGET");

        assertThat(blockers).extracting(Blocker::name).containsExactly("BROKEN", "INNER", "OUTER");
        assertThat(blockers.get(1).kind()).isEqualTo(Kind.BOX);
        assertThat(scan(Mode.ROOT, "TARGET")).extracting(Blocker::name).containsExactly("BROKEN");
    }

    @Test
    void aBoxThatHasNotStartedIsFollowedThroughItsConditionsNotLookedInto() {
        job("TARGET", "ACTIVATED", null, null, "s(BOX)");
        job("BOX", "INACTIVE", null, null, "s(FEED)").type("BOX");
        job("FEED", "FAILURE", TODAY_9, null, null);

        assertThat(scan(Mode.ALL, "TARGET")).extracting(Blocker::name).containsExactly("FEED", "BOX");
        assertThat(loaded).doesNotContain("jobs in BOX");
    }

    @Test
    void timesAreReadInTheAutosysTimeZone() {
        // 12:00 UTC is 07:00 in New York; the feed ended at 06:30 there, half an hour ago
        job("TARGET", "ACTIVATED", null, null, "s(FEED,01.00)", shown("FEED", true));
        job("FEED", "SUCCESS", "Nov 8, 2026 6:30:00 AM", null, null);

        assertThat(new BlockerScan(fake, CLOCK, ZoneId.of("America/New_York"), 200, 30)
                .scan(List.of("TARGET"), Mode.ALL).blockers()).isEmpty();
        // read as UTC, the same time is five and a half hours ago: outside the look-back
        assertThat(scan(Mode.ALL, "TARGET")).extracting(Blocker::name).containsExactly("FEED");
    }

    // --- fake Quick View ---

    private final class Job {
        final String name;
        String status;
        @Nullable String start;
        @Nullable String end;
        @Nullable String box;
        @Nullable String condition;
        @Nullable String exitCode;
        String type = "REGULAR";
        List<JobPage.Condition> shown = List.of();

        Job(String name) {
            this.name = name;
        }

        Job startedAt(String time) {
            start = time;
            return save();
        }

        Job exitCode(String code) {
            exitCode = code;
            return save();
        }

        Job type(String t) {
            type = t;
            return save();
        }

        Job save() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Name", name);
            if (condition != null) {
                attributes.put("Condition", condition);
            }
            if (box != null) {
                attributes.put("Box", box);
            }
            JobPage.JobRow row = new JobPage.JobRow(name, type, type.equals("BOX") ? "BOX" : "CMD", box, start, end,
                    status, status, "1", null, exitCode);
            jobs.put(name.toUpperCase(Locale.ROOT), new JobPage(List.of(row), attributes, shown, List.of(), List.of(),
                    List.of()));
            return this;
        }
    }

    private Job job(String name, String status, @Nullable String end, @Nullable String box,
            @Nullable String condition, JobPage.Condition... shown) {
        Job job = new Job(name);
        job.status = status;
        job.end = end;
        job.start = end;
        job.box = box;
        job.condition = condition;
        job.shown = List.of(shown);
        return job.save();
    }

    private static JobPage.Condition shown(String job, boolean satisfied) {
        return new JobPage.Condition(job, "SUCCESS", null, satisfied, satisfied ? "Yes" : "");
    }

    private void jobsReloaded() {
        loaded.clear();
    }

    private Optional<JobPage> load(String job) {
        loaded.add(job);
        return Optional.ofNullable(jobs.get(job.toUpperCase(Locale.ROOT)));
    }

    private final BlockerScan.Loader fake = new BlockerScan.Loader() {
        @Override
        public Optional<JobPage> load(String job) {
            return BlockerScanTest.this.load(job);
        }

        @Override
        public List<JobPage.JobRow> jobsIn(String box, JobPage page) {
            loaded.add("jobs in " + box);
            return jobs.values().stream().flatMap(p -> p.results().stream())
                    .filter(r -> box.equalsIgnoreCase(r.box())).toList();
        }
    };

    private BlockerScan scanner() {
        return new BlockerScan(fake, CLOCK, ZoneId.of("UTC"), 200, 30);
    }

    private List<Blocker> scan(Mode mode, String target) {
        return scanner().scan(List.of(target), mode).blockers();
    }
}
