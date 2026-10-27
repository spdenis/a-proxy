package com.testing.aproxy.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.testing.aproxy.audit.AuditLogger;
import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.session.ProxySession;
import com.testing.aproxy.webui.BackendException;
import com.testing.aproxy.webui.JobAction;
import com.testing.aproxy.webui.QuickViewClient;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job actions (force start, ...), sent through WCC Quick View as the user does it: event in the
 * dropdown, Go, Yes in the confirmation dialog. Each one is written to the AUDIT log. Results of
 * requests with an {@code Idempotency-Key} are kept and replayed instead of sending the event again.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobActionController {

    private static final Logger log = LoggerFactory.getLogger(JobActionController.class);
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final int MAX_KEY = 128;
    private static final int MAX_COMMENT = 500;
    private static final Pattern STATUS = Pattern.compile("[A-Za-z_ ]{1,32}");

    private final QuickViewClient quickView;
    private final AuditLogger audit;
    private final AutosysProperties.Actions actions;
    private final Cache<String, Replay> replays;
    private final Cache<String, Deque<Instant>> recent;

    public JobActionController(QuickViewClient quickView, AuditLogger audit, AutosysProperties properties) {
        this.quickView = quickView;
        this.audit = audit;
        this.actions = properties.actions();
        this.replays = Caffeine.newBuilder().expireAfterWrite(actions.idempotencyTtl()).maximumSize(10_000).build();
        this.recent = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(2)).build();
    }

    /** @param status for CHANGE_STATUS: RUNNING, STARTING, SUCCESS, FAILURE, INACTIVE, TERMINATED (as Quick View offers) */
    public record ActionRequest(@Nullable String action, @Nullable String status, @Nullable String comment) {
    }

    /**
     * @param outcome      ACCEPTED, REJECTED (AutoSys refused it or does not offer it) or UNKNOWN
     * @param event        the Quick View dropdown option that was sent
     * @param confirmation the text of the confirmation dialog that was answered with Yes
     * @param statusAfter  the job's status right after the action, when read
     */
    public record ActionResult(String job, String action, @Nullable String status, String outcome, @Nullable String event,
            @Nullable String autosysMessage, @Nullable String confirmation, @Nullable String statusAfter,
            String requestId, Instant at) {
    }

    private record Replay(String job, String action, ResponseEntity<?> response) {
    }

    @PostMapping("/{name}/actions")
    public ResponseEntity<?> act(@AuthenticationPrincipal ProxySession session, @PathVariable String name,
            @RequestBody ActionRequest body, @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String key,
            @RequestHeader(name = "X-Request-Id", required = false) @Nullable String requestIdHeader,
            HttpServletRequest request) {
        String requestId = requestIdHeader != null && REQUEST_ID.matcher(requestIdHeader).matches() ? requestIdHeader
                : UUID.randomUUID().toString();
        if (!actions.enabled()) {
            throw new BackendException(HttpStatus.FORBIDDEN, "actions-disabled",
                    "Job actions are disabled on this proxy (autosys.actions.enabled)");
        }
        if (!JobController.JOB_NAME.matcher(name).matches()) {
            throw JobController.invalidName(name);
        }
        JobAction action = action(body.action());
        String status = status(action, body.status());
        String comment = body.comment() == null || body.comment().isBlank() ? null
                : body.comment().strip().length() > MAX_COMMENT ? body.comment().strip().substring(0, MAX_COMMENT)
                : body.comment().strip();
        if (key != null && (key.isBlank() || key.length() > MAX_KEY)) {
            throw new BackendException(HttpStatus.BAD_REQUEST, "invalid-idempotency-key",
                    "Idempotency-Key must be 1 to " + MAX_KEY + " characters");
        }

        // one action at a time per session, so a retried request finds the result of the first one
        session.lock().lock();
        try {
            String replayKey = key == null ? null : session.sessionId() + "\n" + key;
            String what = status == null ? action.name() : action.name() + " " + status;
            Replay replay = replayKey == null ? null : replays.getIfPresent(replayKey);
            if (replay != null) {
                if (!replay.job().equalsIgnoreCase(name) || !replay.action().equals(what)) {
                    throw new BackendException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
                            "This Idempotency-Key was used for " + replay.action() + " of " + replay.job());
                }
                log.info("[api] {} of {} by '{}' replayed for its Idempotency-Key (not sent again)", action, name,
                        session.username());
                return replay.response();
            }
            limitRate(session);

            ResponseEntity<?> response;
            try {
                QuickViewClient.EventResult result = quickView.sendEvent(session, name, action, status, comment,
                        actions.readStatusAfter());
                audit.jobAction(session.username(), session.sessionId(), request.getRemoteAddr(), result.job(),
                        what, result.outcome().name(), requestId, result.message(), comment);
                response = response(result, action, status, requestId);
            } catch (BackendException e) {
                audit.jobAction(session.username(), session.sessionId(), request.getRemoteAddr(), name,
                        what, "NOT_SENT", requestId, e.getMessage(), comment);
                throw e;
            }
            if (replayKey != null) {
                replays.put(replayKey, new Replay(name, what, response));
            }
            return response;
        } finally {
            session.lock().unlock();
        }
    }

    private static ResponseEntity<?> response(QuickViewClient.EventResult result, JobAction action,
            @Nullable String status, String requestId) {
        String what = status == null ? action.toString() : action + " " + status;
        ActionResult body = new ActionResult(result.job(), action.name(), status, result.outcome().name(), result.event(),
                result.message(), result.confirmation(), result.statusAfter(), requestId, Instant.now());
        return switch (result.outcome()) {
            case ACCEPTED -> ResponseEntity.ok().header("X-Request-Id", requestId).body(body);
            case REJECTED -> problem(HttpStatus.CONFLICT, "autosys-action-rejected", "AutoSys did not take "
                    + what + " of " + result.job() + (result.message() == null ? "" : ": " + result.message()),
                    body);
            case UNKNOWN -> problem(HttpStatus.BAD_GATEWAY, "autosys-action-unknown", what + " of " + result.job()
                    + " was confirmed in AutoSys, but its answer is unclear: check the job's status before trying "
                    + "again", body);
        };
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail,
            ActionResult result) {
        ProblemDetail problem = Problems.of(status, type, detail);
        problem.setProperty("job", result.job());
        problem.setProperty("action", result.action());
        problem.setProperty("status", result.status());
        problem.setProperty("outcome", result.outcome());
        problem.setProperty("event", result.event());
        problem.setProperty("autosysMessage", result.autosysMessage());
        problem.setProperty("confirmation", result.confirmation());
        problem.setProperty("statusAfter", result.statusAfter());
        problem.setProperty("requestId", result.requestId());
        return ResponseEntity.status(status).header("X-Request-Id", result.requestId())
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static JobAction action(@Nullable String value) {
        try {
            if (value != null) {
                return JobAction.valueOf(value.strip().toUpperCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException e) {
            // answered below
        }
        throw new BackendException(HttpStatus.BAD_REQUEST, "invalid-action", "\"action\" must be one of "
                + Arrays.toString(JobAction.values()));
    }

    /** The status of CHANGE_STATUS, upper case; none for the other actions. */
    private static @Nullable String status(JobAction action, @Nullable String value) {
        String status = value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
        if (action.needsStatus() && (status == null || !STATUS.matcher(status).matches())) {
            throw new BackendException(HttpStatus.BAD_REQUEST, "invalid-status", "\"status\" is required for "
                    + action + ": RUNNING, STARTING, SUCCESS, FAILURE, INACTIVE or TERMINATED");
        }
        if (!action.needsStatus() && status != null) {
            throw new BackendException(HttpStatus.BAD_REQUEST, "invalid-status", "\"status\" is only for "
                    + JobAction.CHANGE_STATUS);
        }
        return status;
    }

    private void limitRate(ProxySession session) {
        Instant now = Instant.now();
        Deque<Instant> times = recent.get(session.sessionId(), id -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(Duration.ofMinutes(1)))) {
            times.pollFirst();
        }
        if (times.size() >= actions.rateLimitPerMinute()) {
            throw new BackendException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "At most "
                    + actions.rateLimitPerMinute() + " job actions per minute; try again shortly");
        }
        times.addLast(now);
    }

    @ExceptionHandler(BackendException.class)
    ProblemDetail backendProblem(BackendException e) {
        return Problems.of(e.status(), e.type(), e.getMessage());
    }
}
