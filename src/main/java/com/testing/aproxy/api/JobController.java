package com.testing.aproxy.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.testing.aproxy.session.ProxySession;
import com.testing.aproxy.webui.BackendException;
import com.testing.aproxy.webui.JobPage;
import com.testing.aproxy.webui.QuickViewClient;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only job API, answered by searching WCC Quick View with the caller's own AutoSys
 * session. One Quick View search returns status, definition, conditions and run history.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    /** AutoSys job names: letters, digits and {@code _ - . #}; searches may also use {@code * %}. */
    static final Pattern JOB_NAME = Pattern.compile("[A-Za-z0-9_.#-]{1,128}");
    private static final Pattern JOB_SEARCH = Pattern.compile("[A-Za-z0-9_.#*%-]{1,128}");

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final QuickViewClient quickView;

    public JobController(QuickViewClient quickView) {
        this.quickView = quickView;
    }

    public record JobList(String query, List<JobPage.JobRow> jobs, Instant fetchedAt) {
    }

    /**
     * @param type BOX (contains jobs) or REGULAR
     * @param jobs for a box, the jobs directly in it (a nested box shows as one job of type BOX);
     *             null for a regular job, or when Quick View did not list them
     */
    public record JobDetails(String name, @Nullable String type, @Nullable String jobType, @Nullable String box,
            JobPage.@Nullable JobRow status, @Nullable List<JobPage.JobRow> jobs, Map<String, String> attributes,
            List<JobPage.Condition> conditions, List<JobPage.Dependent> dependents,
            List<Map<String, String>> resources, List<JobPage.Run> runs, Instant fetchedAt) {
    }

    public record BoxJobs(String name, List<JobPage.JobRow> jobs, Instant fetchedAt) {
    }

    public record JobDependencies(String name, @Nullable String condition, List<JobPage.Condition> conditions,
            List<JobPage.Dependent> dependents, Instant fetchedAt) {
    }

    public record JobRuns(String name, List<JobPage.Run> runs, Instant fetchedAt) {
    }

    @GetMapping
    public JobList search(@AuthenticationPrincipal ProxySession session, @RequestParam String name,
            @RequestParam(required = false) @Nullable String server) {
        if (!JOB_SEARCH.matcher(name).matches()) {
            throw invalidName(name);
        }
        JobPage page = JobPage.parse(quickView.searchJob(session, name, server(server)));
        return new JobList(name, page.results(), Instant.now());
    }

    @GetMapping("/{name}")
    public JobDetails details(@AuthenticationPrincipal ProxySession session, @PathVariable String name,
            @RequestParam(required = false) @Nullable String server) {
        JobPage page = job(session, name, server);
        JobPage.JobRow row = page.row(name).orElse(null);
        String jobName = page.attributes().get("Name");
        List<JobPage.JobRow> jobs = null;
        if (row != null && JobPage.BOX.equals(row.type())) {
            try {
                jobs = JobPage.parse(quickView.expandBox(session, jobName, server(server))).jobsIn(jobName);
            } catch (BackendException e) {
                log.warn("[backend] jobs of box {} not listed: {}", jobName, e.getMessage());
            }
        }
        return new JobDetails(jobName, row == null ? null : row.type(), row == null ? null : row.jobType(),
                row != null && row.box() != null ? row.box() : page.attributes().get("Box"), row, jobs,
                page.attributes(), page.conditions(), page.dependents(), page.resources(), page.runs(),
                Instant.now());
    }

    @GetMapping("/{name}/jobs")
    public BoxJobs jobs(@AuthenticationPrincipal ProxySession session, @PathVariable String name,
            @RequestParam(required = false) @Nullable String server) {
        JobPage page = job(session, name, server);
        JobPage.JobRow row = page.row(name).orElse(null);
        if (row == null || !JobPage.BOX.equals(row.type())) {
            throw new BackendException(HttpStatus.CONFLICT, "not-a-box", name + " is not a box job"
                    + (row == null || row.jobType() == null ? "" : " (its type is " + row.jobType() + ")"));
        }
        String jobName = page.attributes().get("Name");
        return new BoxJobs(jobName, JobPage.parse(quickView.expandBox(session, jobName, server(server))).jobsIn(jobName),
                Instant.now());
    }

    @GetMapping("/{name}/status")
    public JobPage.JobRow status(@AuthenticationPrincipal ProxySession session, @PathVariable String name,
            @RequestParam(required = false) @Nullable String server) {
        return job(session, name, server).row(name).orElseThrow(() -> new BackendException(HttpStatus.BAD_GATEWAY,
                "unexpected-response", "Quick View showed job " + name + " but no status row"));
    }

    @GetMapping("/{name}/dependencies")
    public JobDependencies dependencies(@AuthenticationPrincipal ProxySession session, @PathVariable String name,
            @RequestParam(required = false) @Nullable String server) {
        JobPage page = job(session, name, server);
        return new JobDependencies(page.attributes().get("Name"), page.attributes().get("Condition"),
                page.conditions(), page.dependents(), Instant.now());
    }

    @GetMapping("/{name}/runs")
    public JobRuns runs(@AuthenticationPrincipal ProxySession session, @PathVariable String name,
            @RequestParam(required = false) @Nullable String server) {
        JobPage page = job(session, name, server);
        return new JobRuns(page.attributes().get("Name"), page.runs(), Instant.now());
    }

    private JobPage job(ProxySession session, String name, @Nullable String server) {
        if (!JOB_NAME.matcher(name).matches()) {
            throw invalidName(name);
        }
        JobPage page = JobPage.parse(quickView.searchJob(session, name, server(server)));
        if (!page.hasDetailsFor(name)) {
            throw new BackendException(HttpStatus.NOT_FOUND, "job-not-found", "No job named " + name
                    + (page.results().isEmpty() ? "" : " (the search matched " + page.results().size() + " other jobs)"));
        }
        session.rememberJob(page.attributes().get("Name"));
        return page;
    }

    /** The AutoSys server asked for (its label in Quick View's server dropdown), null when none. */
    static @Nullable String server(@Nullable String server) {
        if (server == null || server.isBlank()) {
            return null;
        }
        String s = server.strip();
        if (s.length() > 100) {
            throw new BackendException(HttpStatus.BAD_REQUEST, "unknown-server", "Server name too long");
        }
        return s;
    }

    static BackendException invalidName(String name) {
        return new BackendException(HttpStatus.BAD_REQUEST, "invalid-job-name",
                "Not a valid AutoSys job name: " + (name.length() > 40 ? name.substring(0, 40) + "..." : name));
    }

    @ExceptionHandler(BackendException.class)
    ProblemDetail backendProblem(BackendException e) {
        return Problems.of(e.status(), e.type(), e.getMessage());
    }
}
