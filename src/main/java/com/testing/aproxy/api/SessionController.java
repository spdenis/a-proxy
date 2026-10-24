package com.testing.aproxy.api;

import java.time.Instant;
import java.util.List;

import com.testing.aproxy.session.ProxySession;
import com.testing.aproxy.session.SessionRegistry;
import com.testing.aproxy.session.SessionState;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SessionController {

    private final SessionRegistry sessions;

    public SessionController(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/session")
    public SessionInfo current(@AuthenticationPrincipal ProxySession session) {
        return new SessionInfo(session.sessionId(), session.username(), session.state(), session.createdAt(),
                session.lastUsedAt(), sessions.idleExpiresAt(session), session.absoluteExpiresAt(),
                List.copyOf(session.browserCookies().keySet()),
                session.quickViewPage() == null ? null : session.quickViewPage().seenAt());
    }

    /** Revokes the token and wipes the captured credentials. */
    @DeleteMapping("/sessions/current")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal ProxySession session) {
        sessions.revoke(session);
        return ResponseEntity.noContent().build();
    }

    public record SessionInfo(String sessionId, String username, SessionState state, Instant createdAt,
            Instant lastUsedAt, Instant expiresAt, Instant absoluteExpiresAt,
            /* names only: AutoSys cookies the browser of this session sent through the proxy */
            List<String> browserCookies,
            /* when the browser last searched in Quick View through the proxy; null = never */
            @Nullable Instant quickViewSearchSeenAt) {
    }
}
