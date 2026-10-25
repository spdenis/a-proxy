package com.testing.aproxy.webui;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.session.BrowserPage;
import com.testing.aproxy.session.ProxySession;
import com.testing.aproxy.session.SessionRegistry;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Optional background task ({@code autosys.keep-alive}): every interval it requests a job's status
 * for each open session, so the AutoSys session behind it does not time out between API calls.
 * A session that is busy or used AutoSys within the interval is skipped. It does not count as a
 * use of the proxy session: tokens still expire after their idle time.
 */
@Component
public class KeepAliveTask {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveTask.class);

    private final SessionRegistry sessions;
    private final QuickViewClient quickView;
    private final AutosysProperties.KeepAlive settings;
    private final String searchField;
    /* sessions whose last keep-alive failed, so a failure is logged at INFO once, not every interval */
    private final Cache<ProxySession, Boolean> failing = Caffeine.newBuilder().weakKeys().build();
    private final @Nullable ScheduledExecutorService scheduler;

    public KeepAliveTask(SessionRegistry sessions, QuickViewClient quickView, AutosysProperties properties) {
        this.sessions = sessions;
        this.quickView = quickView;
        this.settings = properties.keepAlive();
        this.searchField = properties.webui().quickview().searchField();
        if (!settings.enabled()) {
            scheduler = null;
            return;
        }
        if (settings.interval().compareTo(Duration.ofSeconds(10)) < 0) {
            throw new IllegalStateException("autosys.keep-alive.interval must be at least 10s");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("keep-alive").factory());
        long millis = settings.interval().toMillis();
        scheduler.scheduleWithFixedDelay(this::runSafely, millis, millis, TimeUnit.MILLISECONDS);
        log.info("[backend] keep-alive on: every {} a job status request per session ({})", settings.interval(),
                settings.job() != null ? "job " + settings.job() : "the job each session looked up last");
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("[backend] keep-alive run failed: {}", e.toString());
        }
    }

    /** One round over the open sessions; returns how many searched. */
    public int runOnce() {
        int sent = 0;
        for (ProxySession session : sessions.active()) {
            String job = job(session);
            if (job == null) {
                log.debug("[backend] keep-alive: no job known yet for '{}'", session.username());
                continue;
            }
            try {
                if (quickView.keepAlive(session, job, settings.interval())) {
                    sent++;
                    if (failing.asMap().remove(session) != null) {
                        log.info("[backend] keep-alive for '{}' works again", session.username());
                    }
                    log.debug("[backend] keep-alive: status of {} requested for '{}'", job, session.username());
                }
            } catch (BackendException e) {
                if (failing.asMap().put(session, Boolean.TRUE) == null) {
                    log.info("[backend] keep-alive for '{}' failed (logged once until it works again): {}",
                            session.username(), e.getMessage());
                }
            }
        }
        return sent;
    }

    /** The configured job, else the job the session looked up last, else the one its browser searched for. */
    private @Nullable String job(ProxySession session) {
        if (settings.job() != null) {
            return settings.job();
        }
        if (session.lastJob() != null) {
            return session.lastJob();
        }
        BrowserPage page = session.quickViewPage();
        String searched = page == null ? null : page.fields().get(searchField);
        return searched == null || searched.isBlank() || searched.contains("*") || searched.contains("%") ? null
                : searched.strip();
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
