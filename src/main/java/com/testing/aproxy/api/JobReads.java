package com.testing.aproxy.api;

import java.util.List;

import com.testing.aproxy.session.ProxySession;
import com.testing.aproxy.webui.BackendException;
import com.testing.aproxy.webui.JobPage;
import com.testing.aproxy.webui.QuickViewClient;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Job reads through Quick View, cached by {@link JobStatusCache}; shared by the read-only job endpoints. */
@Component
public class JobReads {

    private final QuickViewClient quickView;
    private final JobStatusCache cache;

    public JobReads(QuickViewClient quickView, JobStatusCache cache) {
        this.quickView = quickView;
        this.cache = cache;
    }

    /** The job's Quick View page, from the cache unless {@code fresh}; 404 {@code job-not-found} (not cached). */
    public JobStatusCache.Read<JobPage> page(ProxySession session, String name, @Nullable String server,
            boolean fresh) {
        if (!JobController.JOB_NAME.matcher(name).matches()) {
            throw JobController.invalidName(name);
        }
        String label = JobController.server(server);
        JobStatusCache.Read<JobPage> read = cache.job(session.username(), quickView.serverFor(session, label), name,
                fresh, () -> {
                    JobPage page = JobPage.parse(quickView.searchJob(session, name, label));
                    if (!page.hasDetailsFor(name)) {
                        throw notFound(name, page);
                    }
                    return page;
                });
        session.rememberJob(read.value().attributes().get("Name"));
        return read;
    }

    /** The jobs directly in a box, whose page is {@code page}. */
    public JobStatusCache.Read<List<JobPage.JobRow>> boxJobs(ProxySession session, String box, JobPage page,
            @Nullable String server, boolean fresh) {
        String label = JobController.server(server);
        return cache.boxJobs(session.username(), quickView.serverFor(session, label), box,
                page.attributes().get("Box"), fresh,
                () -> JobPage.parse(quickView.expandBox(session, box, label)).jobsIn(box));
    }

    static boolean isNotFound(BackendException e) {
        return "job-not-found".equals(e.type());
    }

    private static BackendException notFound(String name, JobPage page) {
        return new BackendException(HttpStatus.NOT_FOUND, "job-not-found", "No job named " + name
                + (page.results().isEmpty() ? "" : " (the search matched " + page.results().size() + " other jobs)"));
    }
}
