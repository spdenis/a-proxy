package com.testing.aproxy.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final Cache<String, ProxySession> sessions;
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
                now.plus(absoluteTtl));
        String browserKey = tokens.newToken().substring(TokenService.PREFIX.length());
        session.browserKeyHash(TokenService.hash(browserKey));
        tokenHashByBrowserKey.put(session.browserKeyHash(), tokenHash);
        String previous = tokenHashByUser.put(username, tokenHash);
        sessions.put(tokenHash, session);
        if (previous != null) {
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

    /** Looks up a session by token without refreshing it (e.g. for the token page). */
    public Optional<ProxySession> peek(String token) {
        return Optional.ofNullable(sessions.policy().getIfPresentQuietly(TokenService.hash(token)));
    }

    public Instant idleExpiresAt(ProxySession session) {
        Instant idle = session.lastUsedAt().plus(idleTtl);
        return idle.isBefore(session.absoluteExpiresAt()) ? idle : session.absoluteExpiresAt();
    }

    public void revoke(ProxySession session) {
        sessions.invalidate(session.tokenHash());
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
        audit.sessionClosed(session.username(), session.sessionId(), shuttingDown ? "SHUTDOWN" : switch (cause) {
            case EXPIRED -> "EXPIRED";
            case EXPLICIT -> "REVOKED_OR_REPLACED";
            default -> cause.name();
        });
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

        private long remaining(ProxySession session) {
            Instant now = clock.instant();
            Duration untilAbsolute = Duration.between(now, session.absoluteExpiresAt());
            Duration ttl = untilAbsolute.compareTo(idleTtl) < 0 ? untilAbsolute : idleTtl;
            return Math.max(0, ttl.toNanos());
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
