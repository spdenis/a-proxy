package com.testing.aproxy.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.testing.aproxy.audit.AuditLogger;
import com.testing.aproxy.config.AutosysProperties;
import jakarta.annotation.PreDestroy;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of proxy sessions keyed by token hash, with idle and absolute expiry
 * and at most one active session per AutoSys user. Credentials are wiped whenever a session
 * leaves the registry for any reason.
 */
@Component
public class SessionRegistry {

    private final Clock clock;
    private final CredentialVault vault;
    private final TokenService tokens;
    private final AuditLogger audit;
    private final Duration idleTtl;
    private final Duration absoluteTtl;
    private final boolean bindClientIp;
    private final boolean browserUseCounts;
    private final Cache<String, ProxySession> sessions;
    /* token hash -> why its session ended, to answer a 401 with the reason; tokens themselves are not kept */
    private final Cache<String, Closed> closed;
    private final ConcurrentMap<String, String> tokenHashByUser = new ConcurrentHashMap<>();
    /* hash of the ASP_SID browser cookie -> token hash */
    private final ConcurrentMap<String, String> tokenHashByBrowserKey = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    public SessionRegistry(AutosysProperties properties, CredentialVault vault, TokenService tokens,
            AuditLogger audit, Clock clock) {
        this.clock = clock;
        this.vault = vault;
        this.tokens = tokens;
        this.audit = audit;
        this.idleTtl = properties.session().idleTtl();
        this.absoluteTtl = properties.session().absoluteTtl();
        this.bindClientIp = properties.token().bindClientIp();
        this.browserUseCounts = properties.session().browserUseCounts();
        this.closed = Caffeine.newBuilder()
                .expireAfterWrite(absoluteTtl.isZero() ? Duration.ofDays(7) : absoluteTtl.multipliedBy(2))
                .maximumSize(10_000).build();
        this.sessions = Caffeine.newBuilder()
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .scheduler(Scheduler.systemScheduler())
                .executor(Runnable::run)
                .expireAfter(new SessionExpiry())
                .removalListener(this::onRemoval)
                .build();
    }

    /**
     * Creates a session for a freshly captured login and returns the raw token. Any previous
     * session of the same user is closed. The caller must wipe {@code password} afterwards.
     */
    public IssuedSession create(String username, char[] password, @Nullable String clientIp) {
        return create(username, password, clientIp, null, List.of());
    }

    public IssuedSession create(String username, char[] password, @Nullable String clientIp,
            @Nullable LoginTemplate loginTemplate, List<String> autosysCookies) {
        Instant now = clock.instant();
        String token = tokens.newToken();
        String tokenHash = TokenService.hash(token);
        ProxySession session = new ProxySession(username, tokenHash, vault.seal(password), clientIp, loginTemplate,
                autosysCookies, now,
                absoluteTtl.isZero() ? null : now.plus(absoluteTtl));
        String browserKey = tokens.newToken().substring(TokenService.PREFIX.length());
        session.browserKeyHash(TokenService.hash(browserKey));
        tokenHashByBrowserKey.put(session.browserKeyHash(), tokenHash);
        String previous = tokenHashByUser.put(username, tokenHash);
        sessions.put(tokenHash, session);
        if (previous != null) {
            closed.put(previous, new Closed(username, Reason.REPLACED, now));
            sessions.invalidate(previous);
        }
        return new IssuedSession(token, browserKey, session);
    }

    /** Resolves a raw bearer token to its session and refreshes the idle timer. */
    public Optional<ProxySession> authenticate(String token, @Nullable String clientIp) {
        if (!TokenService.looksLikeToken(token)) {
            return Optional.empty();
        }
        ProxySession session = sessions.getIfPresent(TokenService.hash(token));
        if (session == null || session.state() == SessionState.CLOSED) {
            return Optional.empty();
        }
        if (bindClientIp && session.clientIp() != null && !session.clientIp().equals(clientIp)) {
            return Optional.empty();
        }
        session.touch(clock.instant());
        return Optional.of(session);
    }

    /** The session whose browser holds this {@code ASP_SID} cookie; does not refresh the idle timer. */
    public Optional<ProxySession> byBrowserKey(String browserKey) {
        String tokenHash = tokenHashByBrowserKey.get(TokenService.hash(browserKey));
        return Optional.ofNullable(tokenHash == null ? null : sessions.policy().getIfPresentQuietly(tokenHash));
    }

    /**
     * The browser linked to the session used AutoSys through the proxy: counts as use of the
     * session (refreshes its idle timer) when {@code autosys.session.browser-use-counts} is on.
     */
    public void browserUsed(ProxySession session) {
        if (browserUseCounts && session.state() != SessionState.CLOSED) {
            session.touch(clock.instant());
            sessions.getIfPresent(session.tokenHash()); // a read recomputes the expiry
        }
    }

    /** Why a token that does not authenticate is not (or no longer) valid, for the 401 answer. */
    public String whyRejected(String token) {
        if (!TokenService.looksLikeToken(token)) {
            return "This is not an API token of this proxy (they start with " + TokenService.PREFIX + ")";
        }
        String tokenHash = TokenService.hash(token);
        sessions.cleanUp(); // records the reason of a session that has just expired
        ProxySession open = sessions.policy().getIfPresentQuietly(tokenHash);
        if (open != null && open.state() != SessionState.CLOSED) {
            return "The API token is valid, but not from this client address (autosys.token.bind-client-ip)";
        }
        Closed why = closed.getIfPresent(tokenHash);
        if (why == null) {
            return "This proxy does not know the API token: it was restarted since the token was issued (tokens are "
                    + "kept in memory only), or the token is wrong. Log in to AutoSys through the proxy again for "
                    + "a new token";
        }
        String at = TIME.withZone(clock.getZone()).format(why.at());
        return switch (why.reason()) {
            case IDLE -> "The API token expired at " + at + " after " + idleTtl + " without use. Log in to AutoSys "
                    + "through the proxy again for a new token";
            case ABSOLUTE -> "The API token expired at " + at + ": tokens last at most " + absoluteTtl
                    + " after the login. Log in to AutoSys through the proxy again for a new token";
            case REPLACED -> "The API token was replaced at " + at + " by a newer login of '" + why.username()
                    + "' through the proxy: copy the new token from " + "/proxy/token right after that login, or "
                    + "log in again";
            case REVOKED -> "The API token was revoked at " + at + ". Log in to AutoSys through the proxy again";
            case SHUTDOWN -> "The proxy was shut down at " + at + ". Log in to AutoSys through the proxy again";
        };
    }

    /** Looks up a session by token without refreshing it (e.g. for the token page). */
    public Optional<ProxySession> peek(String token) {
        return Optional.ofNullable(sessions.policy().getIfPresentQuietly(TokenService.hash(token)));
    }

    /** When the session expires unless it is used again; null = never (no idle and no absolute expiry). */
    public @Nullable Instant idleExpiresAt(ProxySession session) {
        Instant idle = idleTtl.isZero() ? null : session.lastUsedAt().plus(idleTtl);
        Instant absolute = session.absoluteExpiresAt();
        if (idle == null || absolute == null) {
            return idle == null ? absolute : idle;
        }
        return idle.isBefore(absolute) ? idle : absolute;
    }

    public void revoke(ProxySession session) {
        sessions.invalidate(session.tokenHash());
    }

    /** The open sessions, without counting as a use of them (their idle time keeps running). */
    public List<ProxySession> active() {
        return sessions.asMap().values().stream().filter(s -> s.state() != SessionState.CLOSED).toList();
    }

    public long activeCount() {
        sessions.cleanUp();
        return sessions.estimatedSize();
    }

    @PreDestroy
    public void closeAll() {
        shuttingDown = true;
        sessions.invalidateAll();
        sessions.cleanUp();
    }

    private void onRemoval(@Nullable String tokenHash, @Nullable ProxySession session, RemovalCause cause) {
        if (session == null) {
            return;
        }
        tokenHashByUser.remove(session.username(), session.tokenHash());
        if (session.browserKeyHash() != null) {
            tokenHashByBrowserKey.remove(session.browserKeyHash());
        }
        session.close();
        Reason reason = shuttingDown ? Reason.SHUTDOWN : switch (cause) {
            case EXPIRED -> session.absoluteExpiresAt() == null || clock.instant().isBefore(session.absoluteExpiresAt())
                    ? Reason.IDLE : Reason.ABSOLUTE;
            default -> Reason.REVOKED;
        };
        Instant at = switch (reason) {
            case IDLE, ABSOLUTE -> Objects.requireNonNullElse(idleExpiresAt(session), clock.instant());
            default -> clock.instant();
        };
        // a replaced session has its reason already
        closed.asMap().putIfAbsent(session.tokenHash(), new Closed(session.username(), reason, at));
        audit.sessionClosed(session.username(), session.sessionId(), shuttingDown ? "SHUTDOWN" : switch (cause) {
            case EXPIRED -> "EXPIRED";
            case EXPLICIT -> "REVOKED_OR_REPLACED";
            default -> cause.name();
        });
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private enum Reason { IDLE, ABSOLUTE, REPLACED, REVOKED, SHUTDOWN }

    private record Closed(String username, Reason reason, Instant at) {
    }

    /** Idle TTL, capped by the absolute TTL counted from creation. */
    private final class SessionExpiry implements Expiry<String, ProxySession> {

        @Override
        public long expireAfterCreate(String key, ProxySession session, long currentTime) {
            return remaining(session);
        }

        @Override
        public long expireAfterUpdate(String key, ProxySession session, long currentTime, long currentDuration) {
            return remaining(session);
        }

        @Override
        public long expireAfterRead(String key, ProxySession session, long currentTime, long currentDuration) {
            return remaining(session);
        }

        /* counted from now: each read is a use of the session (0 = no such limit) */
        private long remaining(ProxySession session) {
            Instant absolute = session.absoluteExpiresAt();
            Duration untilAbsolute = absolute == null ? null : Duration.between(clock.instant(), absolute);
            Duration ttl = idleTtl.isZero() ? untilAbsolute
                    : untilAbsolute == null || idleTtl.compareTo(untilAbsolute) < 0 ? idleTtl : untilAbsolute;
            return ttl == null ? Long.MAX_VALUE : Math.max(0, ttl.toNanos());
        }
    }

    /** @param browserKey value of the {@code ASP_SID} cookie that links the browser to the session */
    public record IssuedSession(String token, String browserKey, ProxySession session) {

        @Override
        public String toString() {
            return "IssuedSession[token=***, browserKey=***, " + session + "]";
        }
    }
}
