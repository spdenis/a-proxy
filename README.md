# a-proxy — AutoSys Web Proxy API

Spring Boot reverse proxy in front of the AutoSys Web UI. The user logs in on the normal
AutoSys page **through the proxy**; the proxy captures the credentials (kept encrypted in memory
only), and issues an API token for automated clients.

**Status — phase 1:** transparent proxy, login capture, token issuing,
`/proxy/token` page, session API, log masking, audit log. Job endpoints and the server-side
AutoSys session / re-login are not implemented yet.

## Run locally

Requirements: Java 21. Everything else comes through the Gradle wrapper.

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# edit base-url (+ truststore if AutoSys uses an internal CA)
./gradlew bootRun --args='--spring.profiles.active=local'
# or: ./gradlew bootJar && java -jar build/libs/a-proxy-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Open <http://localhost:8080/>. You should see the AutoSys login page served through the proxy.
`application-local.yml` is git-ignored; keep real hostnames there, not in the repo.

### Teach the proxy how your AutoSys login looks

The login request and the "success" signal differ between AutoSys versions, so they are configured,
not coded (`autosys.webui.login.*`):

1. Set `autosys.webui.login.discovery-log: true` and log in once through the proxy. For every POST the
   log shows a line with the path, content type, **field names** (never values), response status,
   redirect target and **cookie names**:
   ```
   [discovery] POST /some/login contentType=application/x-www-form-urlencoded fields=[user, pass] -> status=302 location=http://localhost:8080/some/home cookieNames=[JSESSIONID] matchesLoginPath=false
   ```
2. Do it once with a wrong password too, and compare the two lines (or the browser dev tools network tab).
3. Fill in:
   - `path`: the login POST path (AutoSys-relative, Ant pattern allowed).
   - `username-field` / `password-field`: form field or JSON property (dotted path such as `credentials.user` for nested JSON).
   - `success.*`: what only a good login does. Every rule you set must hold:
     `statuses` (e.g. `[302]`), `location-pattern` / `location-reject-pattern` (regex on the redirect target),
     `cookie` (cookie set on success).
   - `failure-marker`: text in the body of a failed-login response.
4. Restart and log in. On success the browser is sent to `/proxy/token` (when the login ends with a redirect),
   which shows the token for a few minutes. It has a link back to AutoSys. For XHR/JSON logins without a redirect, open
   <http://localhost:8080/proxy/token> manually.

Until success rules are configured, logins pass through normally but no token is issued (a warning is logged at startup).

### Use the token

```bash
curl -H "Authorization: Bearer asp_..." http://localhost:8080/api/v1/session
curl -X DELETE -H "Authorization: Bearer asp_..." http://localhost:8080/api/v1/sessions/current   # revoke
```

## How it works

| Path | Served by |
|---|---|
| `/api/**` | REST API, bearer token required |
| `/proxy/token` | token page |
| `/actuator/health` | health (other actuator endpoints are closed) |
| everything else (or only `autosys.proxy.prefix/**`) | forwarded to `autosys.base-url` |

- The proxy uses Spring Cloud Gateway Server Web MVC. The outbound client never follows redirects, stores cookies
  or decompresses. `Location`/`Content-Location` are rewritten to the proxy origin. `Set-Cookie` loses `Domain`
  and gets its `Path` prefixed; `Secure` is dropped when the proxy runs on plain HTTP. `Origin`/`Referer` are mapped
  back to AutoSys, and the proxy's own `ASP_*` cookies are never forwarded.
- Serving AutoSys at the proxy root (`prefix: ""`, the default) is recommended. With a prefix, absolute paths
  in AutoSys HTML/JS (e.g. `/wcc/...`) would bypass it.
- If AutoSys pages embed their own absolute URL (`https://autosys-host:port/...`), enable
  `autosys.proxy.rewrite-body-urls` (buffers text responses).
- Credentials: AES-GCM in memory with a key generated at startup, wiped on revoke, expiry, replacement
  (one session per user) and shutdown. Tokens: `asp_` + 256 random bits, only the SHA-256 hash is stored.
  This protects against accidental logging/serialisation, not against someone dumping the JVM memory.
- `autosys.environment` must be in `autosys.allowed-environments` (default `DEV, UAT`), or the app refuses to start.
- Audit events (`LOGIN_CAPTURED`, `LOGIN_FAILED`, `SESSION_CLOSED`) go to the `AUDIT` logger as JSON lines.

## Known limitations (phase 1)

- WebSockets are not proxied (Spring Cloud Gateway MVC does not support them).
- SSO logins (SAML/OIDC/Kerberos) are out of scope.
- Multipart logins are not captured.
- No server-side AutoSys login yet: the token proves a successful browser login and holds the credentials
  for the upcoming job API.

## Build & test

```bash
./gradlew build      # compiles and runs unit + WireMock integration tests
```
