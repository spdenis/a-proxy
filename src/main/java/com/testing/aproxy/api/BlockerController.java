package com.testing.aproxy.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.deps.BlockerScan;
import com.testing.aproxy.session.ProxySession;
import com.testing.aproxy.webui.BackendException;
import com.testing.aproxy.webui.JobPage;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dependencies that keep jobs from starting, followed up through their conditions and boxes
 * ({@link BlockerScan}): {@code mode=all} lists every unready one in the order to complete them,
 * {@code mode=root} only the ones at the top, which wait for nothing.
 */
@RestController
public class BlockerController {

    private static final Logger log = LoggerFactory.getLogger(BlockerController.class);
    private static final int MAX_TARGETS = 20;

    private final JobReads reads;
    private final AutosysProperties.Blockers config;
    private final java.time.ZoneId zone;
    private final Clock clock;

    public BlockerController(JobReads reads, AutosysProperties properties, Clock clock) {
        this.reads = reads;
        this.config = properties.blockers();
        this.zone = properties.zone();
        this.clock = clock;
    }

    /** @param jobs the jobs asked about; @param blockers in the order to complete them */
    public record BlockerReport(List<String> jobs, String mode, List<BlockerScan.Target> targets,
            List<BlockerScan.Blocker> blockers, boolean truncated, Instant fetchedAt) {
    }

    @GetMapping("/api/v1/blockers")
    public BlockerReport blockers(@AuthenticationPrincipal ProxySession session, @RequestParam List<String> jobs,
            @RequestParam(defaultValue = "all") String mode, @RequestParam(required = false) @Nullable String server,
            @RequestParam(defaultValue = "false") boolean fresh) {
        Set<String> names = new LinkedHashSet<>();
        jobs.forEach(j -> Arrays.stream(j.split(",")).map(String::strip).filter(n -> !n.isEmpty()).forEach(names::add));
        if (names.isEmpty()) {
            throw new BackendException(HttpStatus.BAD_REQUEST, "invalid-job-name", "Name at least one job in jobs=");
        }
        if (names.size() > MAX_TARGETS) {
            throw new BackendException(HttpStatus.BAD_REQUEST, "too-many-jobs", "At most " + MAX_TARGETS
                    + " jobs per request");
        }
        for (String name : names) {
            if (!JobController.JOB_NAME.matcher(name).matches()) {
                throw JobController.invalidName(name);
            }
        }
        return scan(session, List.copyOf(names), mode, server, fresh);
    }

    @GetMapping("/api/v1/jobs/{name}/blockers")
    public BlockerReport jobBlockers(@AuthenticationPrincipal ProxySession session, @PathVariable String name,
            @RequestParam(defaultValue = "all") String mode, @RequestParam(required = false) @Nullable String server,
            @RequestParam(defaultValue = "false") boolean fresh) {
        if (!JobController.JOB_NAME.matcher(name).matches()) {
            throw JobController.invalidName(name);
        }
        return scan(session, List.of(name), mode, server, fresh);
    }

    private BlockerReport scan(ProxySession session, List<String> jobs, String mode, @Nullable String server,
            boolean fresh) {
        BlockerScan.Mode m = mode(mode);
        Instant started = clock.instant();
        BlockerScan.Loader loader = new BlockerScan.Loader() {
            @Override
            public Optional<JobPage> load(String job) {
                return BlockerController.this.load(session, job, server, fresh);
            }

            @Override
            public List<JobPage.JobRow> jobsIn(String box, JobPage page) {
                return reads.boxJobs(session, box, page, server, fresh).value();
            }
        };
        BlockerScan scan = new BlockerScan(loader, clock, zone, config.maxJobs(), config.maxDepth());
        BlockerScan.Result result;
        try {
            result = scan.scan(jobs, m);
        } catch (BlockerScan.NoSuchJob e) {
            throw new BackendException(HttpStatus.NOT_FOUND, "job-not-found", "No job named " + e.getMessage());
        }
        log.info("[api] blockers of {} for '{}' ({}): {} found{}", jobs, session.username(),
                m.name().toLowerCase(Locale.ROOT), result.blockers().size(), result.truncated() ? ", truncated" : "");
        return new BlockerReport(jobs, m.name().toLowerCase(Locale.ROOT), result.targets(), result.blockers(),
                result.truncated(), started);
    }

    /** A job's page; empty when there is no such job (or the name cannot be one), for the scan to report. */
    private Optional<JobPage> load(ProxySession session, String job, @Nullable String server, boolean fresh) {
        if (!JobController.JOB_NAME.matcher(job).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(reads.page(session, job, server, fresh).value());
        } catch (BackendException e) {
            if (JobReads.isNotFound(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static BlockerScan.Mode mode(String mode) {
        try {
            return BlockerScan.Mode.valueOf(mode.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BackendException(HttpStatus.BAD_REQUEST, "invalid-mode", "mode must be all or root");
        }
    }

    @ExceptionHandler(BackendException.class)
    ProblemDetail backendProblem(BackendException e) {
        return Problems.of(e.status(), e.type(), e.getMessage());
    }
}
