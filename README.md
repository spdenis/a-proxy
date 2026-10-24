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
cp user.properties.example user.properties    # then edit it
./gradlew bootRun
# or: ./gradlew bootJar && java -jar build/libs/a-proxy-0.1.0-SNAPSHOT.jar
```

Configuration is read from **`user.properties` in the working directory** (the directory you start the
proxy from). It overrides the defaults in `src/main/resources/application.yml`; any property listed there
can be set. The file is git-ignored, so keep real hostnames there. To use another file, pass
`--aproxy.user-properties=/path/to/file.properties`.

On startup the log shows which file was loaded, where the proxy listens and where it forwards to:

```
Configuration: loaded /home/me/a-proxy/user.properties
AutoSys UI:   http://localhost:8080/  ->  https://autosys-host:8443 (environment DEV)
Token page:   http://localhost:8080/proxy/token   (after logging in on the AutoSys page)
```

Open <http://localhost:8080/>. You should see the AutoSys login page served through the proxy.

### Logs

Logs go to the console **and** to `logs/a-proxy.log` (rolls at 10 MB, kept 7 days; `logging.file.name` changes the path).

- `ACCESS`: one line per request, e.g. `GET /wcc/ui/home -> https://autosys-host:8443 200 34 ms`. Query
  strings are never logged. Turn off with `autosys.proxy.access-log=false`.
- `AUDIT`: JSON lines for `LOGIN_CAPTURED`, `LOGIN_FAILED`, `SESSION_CLOSED`.
- Login capture result: `AutoSys login captured for user 'alice'; open http://localhost:8080/proxy/token ...`
  or why a login was not accepted.
- More detail: `logging.level.com.testing.aproxy=DEBUG`.

Passwords, tokens, cookies and `Authorization` values are masked in every log line.

### Teach the proxy how your AutoSys login looks

The login request and the "success" signal differ between AutoSys versions, so they are configured,
not coded (`autosys.webui.login.*` in `user.properties`):

1. Set `autosys.webui.login.discovery-log=true` and log in once through the proxy. For every POST the
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
     `statuses` (e.g. `302`), `location-pattern` / `location-reject-pattern` (regex on the redirect target),
     `cookie` (cookie set on success).
   - `failure-marker`: text in the body of a failed-login response.
4. Restart. The startup log should say `Login capture: configured for POST <path>`.

The login **page** and the login **endpoint** are separate settings. Only the endpoint is configured above;
the page just passes through. Two optional settings cover the pages around the login:

```properties
autosys.webui.login.path=/authentication/login      # what the login form/JS POSTs to (captured)
autosys.proxy.start-page=/ui/Login.html             # http://localhost:8080/ redirects here
autosys.webui.login.landing-page=/ui/Home.html      # page AutoSys shows after login (Ant pattern allowed)
```

With `landing-page` set, a login that does not end with a redirect (typical for XHR/JSON logins) still shows
the token. When the UI next opens the landing page, the proxy first sends the browser to `/proxy/token`,
whose **Continue** link leads back to it. This happens once per login.

Until success rules are configured, logins pass through normally but no token is issued (a warning is logged at startup).

### Get the API token

1. Open <http://localhost:8080/> and log in on the normal AutoSys page.
2. The browser goes to <http://localhost:8080/proxy/token> on its own if the login ends with a redirect,
   or if `landing-page` is set. Otherwise open that page yourself. It shows the token (`asp_...`) with a **Copy token** button and a link back to AutoSys.
3. The page only shows the token for 5 minutes after login (`autosys.session.token-page-ttl`), and only in the
   browser that logged in. To get a new token, log in again. That replaces the previous token.

The token stays valid for 8 hours without use (`autosys.session.idle-ttl`) and 24 hours at most
(`autosys.session.absolute-ttl`), or until it is revoked or the proxy restarts.

### Use the token

Send it in the `Authorization` header as a Bearer token:

```bash
TOKEN=asp_...   # from /proxy/token

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/session          # who am I, expiry
curl -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/sessions/current   # revoke
```

```json
{"sessionId":"...","username":"alice","state":"ACTIVE","createdAt":"2026-10-23T17:10:45Z",
 "lastUsedAt":"2026-10-23T17:12:01Z","expiresAt":"2026-10-24T01:12:01Z","absoluteExpiresAt":"2026-10-24T17:10:45Z"}
```

A missing, wrong, expired or revoked token gives `401` with `application/problem+json` (`/problems/token-invalid`).

### Job API (read-only)

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/jobs/MY_JOB                # everything below
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/jobs/MY_JOB/status         # current status row
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/jobs/MY_JOB/dependencies   # conditions + dependents
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/jobs/MY_JOB/runs           # run history
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/jobs/MY_BOX/jobs           # jobs in a box (409 if not a box)
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/jobs?name=MY_JOB*"         # search
```

```json
{"name":"MY_JOB", "type":"REGULAR", "jobType":"CMD", "box":"MY_BOX",
 "status":{"name":"MY_JOB","type":"REGULAR","jobType":"CMD","box":"MY_BOX","startTime":"Oct 23, 2026 3:44:41 PM","endTime":"Oct 23, 2026 3:45:06 PM",
           "status":"SUCCESS","statusText":"Success","run":"711037496","priority":null,"exitCode":"0"},
 "jobs":null,
 "attributes":{"Name":"MY_JOB","Description":"...","Send to machine":"host","Condition":"s(OTHER_JOB,10.00)", "...":"..."},
 "conditions":[{"condition":"OTHER_JOB","expectedValue":"SUCCESS","currentStatus":"Success","satisfied":true,"satisfiedText":"Yes"}],
 "dependents":[{"jobName":"NEXT_JOB","condition":"s(MY_JOB)","satisfied":null,"satisfiedText":null}],
 "resources":[], "runs":[{"startTime":"...","endTime":"...","status":"SUCCESS","statusText":"SUCCESS","run":"711037496/2","exitCode":"0","machine":"host"}],
 "fetchedAt":"2026-10-23T21:30:00Z"}
```

`type` is `BOX` (a job that contains jobs) or `REGULAR`; `jobType` is the AutoSys type from Quick View's icon
(`BOX`, `CMD`, `FW`, ...). For a box, `jobs` lists the jobs directly in it with their status, as Quick View shows them
when you expand the box (a second request to AutoSys); a box inside it appears as one job of type `BOX`, so call the
API for that box to see its jobs. `jobs` is null for a regular job, and when Quick View did not list them (logged as
a WARN).

How it works, in order of preference:

1. **The browser's Quick View page.** When the browser loads Quick View (`main.jsf`) or searches in it through the
   proxy, the proxy remembers the page's search form (URL with `AntiXSRFToken`, form fields with
   `javax.faces.ViewState`) and the browser's cookies (with the Quick View `JSESSIONID`), and sends API searches the
   same way, changing only the job name. So: log in, open Quick View, then use the API. The API and the browser tab share that page state (`autosys.webui.quickview.reuse-browser-page=false`
   turns this off).
2. **A Quick View page of its own** from your browser's AutoSys session, opened via `index.jsp` the way the UI's
   launcher opens it: `?randomId=…&AntiXSRFToken=…` with `Referer: /wcc/ui/Launcher.html`. AutoSys answers 403
   without the token, so the proxy takes it from the query or headers of the browser's requests
   (`autosys.webui.quickview.xsrf-param`); until it has seen one, this step fails.
3. **Its own login** with the captured credentials.

For 2: the proxy uses the AutoSys session of your browser login, so there is no second login. At login it
gives the browser an `ASP_SID` cookie (never forwarded to AutoSys) and from then on remembers the AutoSys cookies that
browser sends, including those the UI sets in JavaScript; API calls use them, except `JSESSIONID`
(`autosys.webui.quickview.exclude-browser-cookies`), so they get their own Quick View page state. Browse AutoSys through
the proxy at least once after logging in before calling the API. Only if that session does not open Quick View (or
`autosys.webui.quickview.reuse-browser-session=false`) does it log in itself with the captured credentials, replaying
the browser's login request (same format, extra fields and `X-*` headers; the password comes from the vault); a rejected
login is logged with the response status, body start and the headers sent. Then it opens WCC Quick View (`autosys.webui.quickview.entry-path`), keeps that session's cookies, `AntiXSRFToken`,
`conversationContext` and `javax.faces.ViewState`, and submits the job search as the UI does (Trinidad partial-page
request). When the answer is not a partial-page response (session expired, redirect to login), it opens Quick View again
(falling back to its own login) and retries once. Calls of one session run one at a time. Times are as Quick View shows them (`Accept-Language: en-US`).

| Problem type | Status | Meaning |
|---|---|---|
| `/problems/job-not-found` | 404 | Quick View showed no job of that name |
| `/problems/invalid-job-name` | 400 | name has characters other than letters, digits, `_ - . #` (search also `* %`) |
| `/problems/autosys-login` | 502 | AutoSys rejected the proxy's own login (reason in `detail`; the AUDIT log has `BACKEND_LOGIN_FAILED`) |
| `/problems/autosys-quickview` | 502 | Quick View did not open after login (check `entry-path`) |
| `/problems/autosys-session` | 502 | Quick View kept refusing the search after logging in again |
| `/problems/autosys-unreachable` | 502 | connection error or timeout |
| `/problems/login-not-recorded` | 409 | session created before this feature; log in through the proxy again |

Set `logging.level.com.testing.aproxy.webui=DEBUG` to see each request the proxy makes to AutoSys (paths only).

## Copilot skill

`.github/skills/autosys-jobs/` is an agent skill for GitHub Copilot: ask Copilot things like "give me the
dependencies of MY_JOB", "which jobs depend on MY_JOB, also indirectly" or "which jobs in MY_BOX failed", and it
calls the job API through `autosys.ps1` (PowerShell) and answers from the result.

- Copilot finds it in this repository; to use it from any workspace, copy the folder to `~/.copilot/skills/`.
- Set the token in the terminal Copilot uses: `$env:AUTOSYS_PROXY_TOKEN = 'asp_...'` (from `/proxy/token`);
  `AUTOSYS_PROXY_URL` if the proxy is not on `http://localhost:8080`.
- The script works on its own too: `powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 downstream MY_JOB -Depth 3`.
  Commands: `job`, `status`, `deps`, `runs`, `jobs`, `search`, `downstream`, `upstream`, `tree`, `session`.

## Capture mode: record the AutoSys UI's internal requests

The job API has to make the same internal requests the AutoSys UI makes. To find out what those are,
switch on capture mode and click through the UI once:

```properties
autosys.capture.enabled=true
```

Each proxied request is then written to `captures/<yyyyMMdd-HHmmss>/NNNN-<METHOD>-<path>.json`, numbered in
order. A `[capture] GET /... -> 200 saved 0012-GET-....json` log line marks each one, so a click can be matched
to its files. Static assets (`.js`, `.css`, images, fonts) are skipped; `autosys.capture.exclude-path-pattern` changes that.

Each file holds the method, path, query parameters, request headers and body, response status, headers and body.
JSON bodies are stored as JSON, text as text, binary as a size placeholder. Responses are recorded uncompressed, as
AutoSys sent them, before any rewriting.

**What is masked:** cookie and `Set-Cookie` values, `Authorization`, any header, parameter or JSON field whose name
looks secret (password, token, CSRF, session id, credential), the configured login password field, CSRF tokens in
HTML hidden inputs and JavaScript, and API tokens. **What is not masked:** job names, machines, owners, commands
and everything else. `captures/` is git-ignored; review files before sharing them.

Flows to record, ideally with a **test job** outside production:

| # | In the UI | Learn |
|---|---|---|
| 1 | Search jobs (with `*` wildcard; by status if possible) | search endpoint, parameters, paging, result format |
| 2 | Open a job: details and current status | details endpoint, status codes |
| 3 | Show dependencies / conditions; a box job with children | dependency format |
| 4 | Force start the test job | action endpoint, payload, how the CSRF token is sent |
| 5 | Start (if separate) and kill | same |
| 6 | Optional: hold/off hold, ice/off ice, run history | same |
| 7 | Delete the AutoSys session cookie in dev tools (or wait for the timeout), then click something | how an expired session looks |
| 8 | Log out | logout endpoint |

Also check by hand: log in from two different browsers. Does the first one get logged out?

Switch capture mode off afterwards; it buffers every response in memory.

### Summarise a capture run

When the capture files cannot leave the network, summarise them as compact text:

```bash
./gradlew -q analyzeCaptures                                   # newest run under ./captures
./gradlew -q analyzeCaptures --args="captures/20261023-191740"  # a specific run
./gradlew -q analyzeCaptures --args="--only=job --values=5"     # only paths matching a regex, more sample values
./gradlew -q analyzeCaptures --args="--brief"                   # endpoint list only
./gradlew -q analyzeCaptures --args="--no-values"               # field names and types, no sample values
```

The summary is printed and saved as `SUMMARY.txt` in the run directory. It has three parts:
- **TIMELINE:** the order of requests. `!` marks a response that looks like a login page or an expired session.
- **ENDPOINTS:** one line per method + path, with numeric ids grouped as `{n}`.
- **DETAILS:** for each endpoint:
  - where it is called from, query parameters, non-standard headers (e.g. CSRF), cookie names, the request body shape;
  - redirects and the response shape: JSON field paths with types and up to 3 sample values;
    for HTML the title, forms, table headers and URLs referenced in the page.

Sample values are cut to 40 characters; check them for job names you do not want to share, or use `--no-values`.

For one request in full detail (the `seq` numbers are in the TIMELINE):

```bash
./gradlew -q analyzeCaptures --args="--dump=84"                  # saved as DUMP-0084.txt
./gradlew -q analyzeCaptures --args="--dump=84 --rows=30 --all-tables --width=100"
```

The dump has every query parameter and form field with its value, the request headers that matter (CSRF, `X-*`,
JSF/Trinidad), the response's partial-page fragment ids and hidden inputs (e.g. `javax.faces.ViewState`), and each
data table: id, header cells and the first rows.

To find where a text occurs in the whole run, with the raw markup around each match:

```bash
./gradlew -q analyzeCaptures --args="--grep=AntiXSRFToken"         # saved as GREP.txt
./gradlew -q analyzeCaptures --args="--grep=711037496 --context=300 --rows=2"
```

`--context` is the number of characters shown on each side of a match, `--rows` the matches shown per part of a request,
and `--responses` searches only response headers and bodies (where a token is issued, not where it is sent back).

## How it works

| Path | Served by |
|---|---|
| `/api/**` | REST API, bearer token required |
| `/proxy/token` | token page |
| `/actuator/health` | health (other actuator endpoints are closed) |
| everything else (or only `autosys.proxy.prefix/**`) | forwarded to `autosys.base-url` |

- The proxy uses Spring Cloud Gateway Server Web MVC. The outbound client never follows redirects, stores cookies
  or decompresses. `Location`/`Content-Location` are rewritten to the proxy origin. `Set-Cookie` loses `Domain`
  and gets its `Path` prefixed; `Secure` is dropped when the proxy runs on plain HTTP. The proxy's own `ASP_*`
  cookies are never forwarded.
- `autosys.proxy.preserve-host: true` (default) forwards the browser's `Host`, `Origin` and `Referer` unchanged,
  so AutoSys believes it runs under the proxy's name. This is required by OWASP CSRFGuard, which AutoSys uses:
  without it the UI fails with *"OWASP CSRFGuard JavaScript was included from within an unauthorized domain!"*.
  Redirects AutoSys then builds with its own scheme (e.g. `https://localhost:8080/...`) are corrected to the
  proxy's scheme. Set it to `false` only if AutoSys (or a load balancer in front of it) rejects unknown host
  names; `Origin`/`Referer` are then mapped back to the AutoSys host instead.
- Serving AutoSys at the proxy root (`prefix: ""`, the default) is recommended. With a prefix, absolute paths
  in AutoSys HTML/JS (e.g. `/wcc/...`) would bypass it.
- If AutoSys pages embed their own absolute URL (`https://autosys-host:port/...`), enable
  `autosys.proxy.rewrite-body-urls` (buffers text responses).
- Credentials: AES-GCM in memory with a key generated at startup, wiped on revoke, expiry, replacement
  (one session per user) and shutdown. Tokens: `asp_` + 256 random bits, only the SHA-256 hash is stored.
  This protects against accidental logging/serialisation, not against someone dumping the JVM memory.
- `autosys.environment` must be in `autosys.allowed-environments` (default `DEV, UAT`), or the app refuses to start.

## Known limitations

- WebSockets are not proxied (Spring Cloud Gateway MVC does not support them).
- SSO logins (SAML/OIDC/Kerberos) are out of scope.
- Multipart logins are not captured.
- The job API is read-only; start/force start/kill come next.
- The proxy's own AutoSys session is not logged out when the proxy session ends; it times out on the AutoSys side.
- Only the first page of search results is returned.

## Build & test

```bash
./gradlew build      # compiles and runs unit + WireMock integration tests
```
