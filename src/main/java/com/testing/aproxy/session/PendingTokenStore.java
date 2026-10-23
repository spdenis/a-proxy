package com.testing.aproxy.session;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.testing.aproxy.config.AutosysProperties;

import org.springframework.stereotype.Component;

/**
 * Hands a freshly issued token to the browser that logged in. The browser gets a random,
 * HttpOnly "ticket" cookie; {@code /proxy/token} exchanges the ticket for the token while it
 * is fresh. The raw token never appears in a URL.
 */
@Component
public class PendingTokenStore {

    /** Prefix of cookies owned by the proxy; they are never forwarded to AutoSys. */
    public static final String OWN_COOKIE_PREFIX = "ASP_";

    public static final String COOKIE = OWN_COOKIE_PREFIX + "TOKEN_TICKET";

    private final SecureRandom random = new SecureRandom();
    private final Cache<String, PendingToken> pending;

    public PendingTokenStore(AutosysProperties properties) {
        this.pending = Caffeine.newBuilder()
                .expireAfterWrite(properties.session().tokenPageTtl())
                .maximumSize(10_000)
                .build();
    }

    /** Returns the ticket to put into the browser cookie. */
    public String put(String token, ProxySession session) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pending.put(ticket, new PendingToken(token, session.username(), Instant.now()));
        return ticket;
    }

    public Optional<PendingToken> get(String ticket) {
        return Optional.ofNullable(pending.getIfPresent(ticket));
    }

    public void remove(String ticket) {
        pending.invalidate(ticket);
    }

    public record PendingToken(String token, String username, Instant issuedAt) {

        @Override
        public String toString() {
            return "PendingToken[token=***, user=" + username + "]";
        }
    }
}
