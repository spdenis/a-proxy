package com.testing.aproxy.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;

/**
 * Server-side state of one logged-in user. Holds only the token hash, never the raw token,
 * and the password only in sealed form.
 */
public final class ProxySession {

    private final String sessionId = UUID.randomUUID().toString();
    private final String username;
    private final String tokenHash;
    private final CredentialVault.SealedSecret credentials;
    private final @Nullable String clientIp;
    private final @Nullable LoginTemplate loginTemplate;
    private final List<String> autosysCookies;
    private final Instant createdAt;
    private final Instant absoluteExpiresAt;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Instant lastUsedAt;
    private volatile SessionState state = SessionState.ACTIVE;

    ProxySession(String username, String tokenHash, CredentialVault.SealedSecret credentials,
            @Nullable String clientIp, @Nullable LoginTemplate loginTemplate, List<String> autosysCookies, Instant now,
            Instant absoluteExpiresAt) {
        this.username = username;
        this.tokenHash = tokenHash;
        this.credentials = credentials;
        this.clientIp = clientIp;
        this.loginTemplate = loginTemplate;
        this.autosysCookies = List.copyOf(autosysCookies);
        this.createdAt = now;
        this.lastUsedAt = now;
        this.absoluteExpiresAt = absoluteExpiresAt;
    }

    public String sessionId() {
        return sessionId;
    }

    public String username() {
        return username;
    }

    String tokenHash() {
        return tokenHash;
    }

    /** Sealed credentials, to be opened only through {@link CredentialVault#withPassword}. */
    public CredentialVault.SealedSecret credentials() {
        return credentials;
    }

    public @Nullable String clientIp() {
        return clientIp;
    }

    /** How the browser logged in, for the proxy's own AutoSys login; null if not recorded. */
    public @Nullable LoginTemplate loginTemplate() {
        return loginTemplate;
    }

    /**
     * {@code Set-Cookie} values AutoSys sent with the browser's successful login (paths as on
     * AutoSys), so the proxy can use the already authenticated AutoSys session for API calls.
     */
    public List<String> autosysCookies() {
        return autosysCookies;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant absoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public SessionState state() {
        return state;
    }

    /** Serialises backend re-login for this session and the proxy's own AutoSys requests for this session. */
    public ReentrantLock lock() {
        return lock;
    }

    void touch(Instant now) {
        lastUsedAt = now;
    }

    void close() {
        state = SessionState.CLOSED;
        credentials.wipe();
    }

    @Override
    public String toString() {
        return "ProxySession[" + sessionId + ", user=" + username + ", state=" + state + "]";
    }
}
