package com.testing.aproxy.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import com.testing.aproxy.audit.AuditLogger;
import com.testing.aproxy.config.AutosysProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T10:00:00Z"));
    private final CredentialVault vault = newVault();
    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry(properties(Duration.ofHours(8), Duration.ofHours(24)), vault,
                new TokenService(), new AuditLogger(), clock);
    }

    @Test
    void issuedTokenAuthenticatesAndKeepsPasswordSealed() {
        var issued = registry.create("alice", "pw".toCharArray(), "10.0.0.1");

        assertThat(issued.token()).startsWith("asp_");
        var session = registry.authenticate(issued.token(), "10.0.0.1").orElseThrow();
        assertThat(session.username()).isEqualTo("alice");
        String password = vault.withPassword(session.credentials(), String::new);
        assertThat(password).isEqualTo("pw");
        assertThat(session.toString()).doesNotContain("pw");
        assertThat(registry.authenticate("asp_wrongwrongwrongwrongwrong", null)).isEmpty();
    }

    @Test
    void idleTimeoutExpiresAndWipesCredentials() {
        var issued = registry.create("alice", "pw".toCharArray(), null);
        var session = issued.session();

        clock.advance(Duration.ofHours(7));
        assertThat(registry.authenticate(issued.token(), null)).isPresent();
        clock.advance(Duration.ofHours(7));
        assertThat(registry.authenticate(issued.token(), null)).isPresent();
        clock.advance(Duration.ofHours(8).plusSeconds(1));
        assertThat(registry.authenticate(issued.token(), null)).isEmpty();
        registry.activeCount();
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(session.credentials().isWiped()).isTrue();
    }

    @Test
    void absoluteTimeoutWinsOverActivity() {
        var issued = registry.create("alice", "pw".toCharArray(), null);
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofHours(6));
            registry.authenticate(issued.token(), null);
        }
        clock.advance(Duration.ofMinutes(1));
        assertThat(registry.authenticate(issued.token(), null)).isEmpty();
    }

    @Test
    void newLoginReplacesPreviousSessionOfSameUser() {
        var first = registry.create("alice", "pw".toCharArray(), null);
        var second = registry.create("alice", "pw2".toCharArray(), null);

        assertThat(registry.authenticate(first.token(), null)).isEmpty();
        assertThat(first.session().credentials().isWiped()).isTrue();
        assertThat(registry.authenticate(second.token(), null)).isPresent();
    }

    @Test
    void revokeWipesCredentials() {
        var issued = registry.create("bob", "pw".toCharArray(), null);
        registry.revoke(issued.session());

        assertThat(registry.authenticate(issued.token(), null)).isEmpty();
        assertThat(issued.session().credentials().isWiped()).isTrue();
    }

    @Test
    void clientIpBindingWhenEnabled() {
        var bound = new SessionRegistry(propertiesWithIpBinding(), vault, new TokenService(), new AuditLogger(), clock);
        var issued = bound.create("alice", "pw".toCharArray(), "10.0.0.1");

        assertThat(bound.authenticate(issued.token(), "10.0.0.2")).isEmpty();
        assertThat(bound.authenticate(issued.token(), "10.0.0.1")).isPresent();
    }

    private static CredentialVault newVault() {
        try {
            return new CredentialVault();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static AutosysProperties properties(Duration idle, Duration absolute) {
        return new AutosysProperties(URI.create("https://autosys.example.internal"), Set.of("DEV"), "DEV",
                new AutosysProperties.Proxy("", false, true, true, null),
                new AutosysProperties.Session(idle, absolute, Duration.ofMinutes(5)),
                null, new AutosysProperties.Token(false), null, null, null);
    }

    static AutosysProperties propertiesWithIpBinding() {
        return new AutosysProperties(URI.create("https://autosys.example.internal"), Set.of("DEV"), "DEV",
                new AutosysProperties.Proxy("", false, true, true, null),
                new AutosysProperties.Session(Duration.ofHours(8), Duration.ofHours(24), Duration.ofMinutes(5)),
                null, new AutosysProperties.Token(true), null, null, null);
    }

    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
