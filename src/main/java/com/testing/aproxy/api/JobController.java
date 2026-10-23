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
    private static final Pattern JOB_NAME = Pattern.compile("[A-Za-z0-9_.#-]{1,128}");
    private static final Pattern JOB_SEARCH = Pattern.compile("[A-Za-z0-9_.#*%-]{1,128}");

    private final QuickViewClient quickView;

    public JobController(QuickViewClient quickView) {
        this.quickView = quickView;
    }

    public record JobList(String query, List<JobPage.JobRow> jobs, Instant fetchedAt) {
    }

    public record JobDetails(String name, JobPage.@Nullable JobRow status, Map<String, String> attributes,
            List<JobPage.Condition> conditions, List<JobPage.Dependent> dependents,
            List<Map<String, String>> resources, List<JobPage.Run> runs, Instant fetchedAt) {
    }

    public record JobDependencies(String name, @Nullable String condition, List<JobPage.Condition> conditions,
            List<JobPage.Dependent> dependents, Instant fetchedAt) {
    }

    public record JobRuns(String name, List<JobPage.Run> runs, Instant fetchedAt) {
    }

    @GetMapping
    public JobList search(@AuthenticationPrincipal ProxySession session, @RequestParam String name) {
        if (!JOB_SEARCH.matcher(name).matches()) {
            throw invalidName(name);
        }
        JobPage page = JobPage.parse(quickView.searchJob(session, name));
        return new JobList(name, page.results(), Instant.now());
    }

    @GetMapping("/{name}")
    public JobDetails details(@AuthenticationPrincipal ProxySession session, @PathVariable String name) {
        JobPage page = job(session, name);
        return new JobDetails(page.attributes().get("Name"), page.row(name).orElse(null), page.attributes(),
                page.conditions(), page.dependents(), page.resources(), page.runs(), Instant.now());
    }

    @GetMapping("/{name}/status")
    public JobPage.JobRow status(@AuthenticationPrincipal ProxySession session, @PathVariable String name) {
        return job(session, name).row(name).orElseThrow(() -> new BackendException(HttpStatus.BAD_GATEWAY,
                "unexpected-response", "Quick View showed job " + name + " but no status row"));
    }

    @GetMapping("/{name}/dependencies")
    public JobDependencies dependencies(@AuthenticationPrincipal ProxySession session, @PathVariable String name) {
        JobPage page = job(session, name);
        return new JobDependencies(page.attributes().get("Name"), page.attributes().get("Condition"),
                page.conditions(), page.dependents(), Instant.now());
    }

    @GetMapping("/{name}/runs")
    public JobRuns runs(@AuthenticationPrincipal ProxySession session, @PathVariable String name) {
        JobPage page = job(session, name);
        return new JobRuns(page.attributes().get("Name"), page.runs(), Instant.now());
    }

    private JobPage job(ProxySession session, String name) {
        if (!JOB_NAME.matcher(name).matches()) {
            throw invalidName(name);
        }
        JobPage page = JobPage.parse(quickView.searchJob(session, name));
        if (!page.hasDetailsFor(name)) {
            throw new BackendException(HttpStatus.NOT_FOUND, "job-not-found", "No job named " + name
                    + (page.results().isEmpty() ? "" : " (the search matched " + page.results().size() + " other jobs)"));
        }
        return page;
    }

    private static BackendException invalidName(String name) {
        return new BackendException(HttpStatus.BAD_REQUEST, "invalid-job-name",
                "Not a valid AutoSys job name: " + (name.length() > 40 ? name.substring(0, 40) + "..." : name));
    }

    @ExceptionHandler(BackendException.class)
    ProblemDetail backendProblem(BackendException e) {
        return Problems.of(e.status(), e.type(), e.getMessage());
    }
}
