package com.testing.aproxy.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * One structured JSON line per security-relevant event on the dedicated {@code AUDIT}
 * logger, so it can be routed separately. Never receives secrets.
 */
@Component
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final JsonMapper JSON = JsonMapper.shared();

    public void loginCaptured(String username, String sessionId, @Nullable String clientIp) {
        write("LOGIN_CAPTURED", username, clientIp, Map.of("sessionId", sessionId));
    }

    public void loginFailed(@Nullable String username, @Nullable String clientIp, String reason) {
        write("LOGIN_FAILED", username, clientIp, Map.of("reason", reason));
    }

    /** The proxy logged in to AutoSys itself (for API calls) with the credentials of a session. */
    public void backendLogin(String username, String sessionId, boolean success, String reason) {
        write(success ? "BACKEND_LOGIN" : "BACKEND_LOGIN_FAILED", username, null,
                Map.of("sessionId", sessionId, "reason", reason));
    }

    public void sessionClosed(String username, String sessionId, String reason) {
        write("SESSION_CLOSED", username, null, Map.of("sessionId", sessionId, "reason", reason));
    }

    private void write(String event, @Nullable String username, @Nullable String clientIp, Map<String, ?> extra) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("event", event);
        line.put("user", username);
        line.put("clientIp", clientIp);
        line.putAll(extra);
        AUDIT.info(JSON.writeValueAsString(line));
    }
}
