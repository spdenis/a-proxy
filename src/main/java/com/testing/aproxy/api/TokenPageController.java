package com.testing.aproxy.api;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.testing.aproxy.config.AutosysProperties;
import com.testing.aproxy.proxy.ProxyPaths;
import com.testing.aproxy.session.PendingTokenStore;
import com.testing.aproxy.session.SessionRegistry;

import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** Shows the API token issued for the browser's AutoSys login. */
@RestController
public class TokenPageController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final PendingTokenStore pendingTokens;
    private final SessionRegistry sessions;
    private final ProxyPaths paths;

    private final String defaultNext;

    public TokenPageController(PendingTokenStore pendingTokens, SessionRegistry sessions, ProxyPaths paths,
            AutosysProperties properties) {
        this.pendingTokens = pendingTokens;
        this.sessions = sessions;
        this.paths = paths;
        String landing = properties.webui().login().landingPage();
        // a pattern cannot be linked to; fall back to the AutoSys root then
        this.defaultNext = paths.prefix() + (landing != null && !landing.contains("*") ? landing : "/");
    }

    @GetMapping(path = ProxyPaths.TOKEN_PAGE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> tokenPage(
            @CookieValue(name = PendingTokenStore.COOKIE, required = false) @Nullable String ticket,
            @RequestParam(required = false) @Nullable String next) {
        String home = safeNext(next);
        var pending = ticket == null ? null : pendingTokens.get(ticket)
                .filter(p -> sessions.peek(p.token()).isPresent())
                .orElse(null);
        String html = pending == null ? noTokenPage(home) : tokenPage(pending, home);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex")
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .body(html);
    }

    /** Only same-site relative paths are allowed as the link back, never an open redirect. */
    private String safeNext(@Nullable String next) {
        if (next != null && next.startsWith("/") && !next.startsWith("//") && !next.contains("\\")
                && !ProxyPaths.isOwnPath(next)) {
            return next;
        }
        return defaultNext;
    }

    private static String expiry(@Nullable Instant idle, @Nullable Instant absolute) {
        if (idle == null) {
            return "only when you log in through the proxy again or revoke it";
        }
        return absolute == null || absolute.equals(idle) ? TIME.format(idle)
                : TIME.format(idle) + " (idle), at the latest " + TIME.format(absolute);
    }

    private String tokenPage(PendingTokenStore.PendingToken pending, String home) {
        String expires = sessions.peek(pending.token())
                .map(s -> expiry(sessions.idleExpiresAt(s), s.absoluteExpiresAt()))
                .orElse("-");
        return page("API token", """
                <p>Logged in to AutoSys as <b>%s</b>. Use this token from scripts and tools:</p>
                <pre id="token">%s</pre>
                <p><button onclick="navigator.clipboard.writeText(document.getElementById('token').textContent)\
                .then(()=>this.textContent='Copied')">Copy token</button></p>
                <p class="hint">Send it as <code>Authorization: Bearer &lt;token&gt;</code>.
                Expires %s. It is shown here only for a few minutes after login; log in again to get a new one.
                Treat it like your password.</p>
                <p><a href="%s">Continue to AutoSys &rarr;</a></p>
                """.formatted(HtmlUtils.htmlEscape(pending.username()), HtmlUtils.htmlEscape(pending.token()),
                HtmlUtils.htmlEscape(expires), HtmlUtils.htmlEscape(home)));
    }

    private String noTokenPage(String home) {
        return page("No API token", """
                <p>There is no freshly issued token for this browser.</p>
                <p class="hint">Log in on the AutoSys page through this proxy; the token is shown here right after
                a successful login.</p>
                <p><a href="%s">Go to the AutoSys login &rarr;</a></p>
                """.formatted(HtmlUtils.htmlEscape(home)));
    }

    private static String page(String title, String body) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s – AutoSys proxy</title>
                <style>
                  body{font-family:system-ui,sans-serif;max-width:46rem;margin:3rem auto;padding:0 1rem;color:#1d1d1f}
                  pre{background:#f3f3f5;border:1px solid #ddd;border-radius:6px;padding:.8rem;white-space:pre-wrap;
                      word-break:break-all;font-size:.95rem}
                  button{font:inherit;padding:.4rem .9rem;cursor:pointer}
                  .hint{color:#555;font-size:.9rem}
                </style></head>
                <body><h1>%s</h1>
                %s
                </body></html>
                """.formatted(title, title, body);
    }
}
