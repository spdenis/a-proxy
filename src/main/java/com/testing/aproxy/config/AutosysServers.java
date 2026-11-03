package com.testing.aproxy.config;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The AutoSys servers ({@code autosys.backend-urls}, else {@code autosys.base-url}) and the one in use.
 * <p>
 * Failover only: everyone uses the current server until it fails, then the next one in the list (after the
 * last, the first again). There is no switching back when an earlier server recovers: each server has its own
 * AutoSys sessions, so every switch logs the users out.
 */
@Component
public class AutosysServers {

    private static final Logger log = LoggerFactory.getLogger(AutosysServers.class);

    private final List<URI> urls;
    private final AtomicInteger current = new AtomicInteger();

    public AutosysServers(AutosysProperties properties) {
        this.urls = properties.backendUrls();
    }

    public List<URI> all() {
        return urls;
    }

    public URI current() {
        return urls.get(current.get());
    }

    public int currentIndex() {
        return current.get();
    }

    /**
     * Reports that this server is not usable. When it is the one in use, the next one becomes current;
     * a server reported by several requests at once moves the choice on only once.
     *
     * @return the server in use after the report
     */
    public URI failed(URI server, String reason) {
        int index = urls.indexOf(server);
        if (index < 0 || urls.size() < 2) {
            return current();
        }
        int next = (index + 1) % urls.size();
        if (current.compareAndSet(index, next)) {
            log.warn("[failover] AutoSys at {} is not usable ({}); switching to {}", server, reason, urls.get(next));
        }
        return current();
    }
}
