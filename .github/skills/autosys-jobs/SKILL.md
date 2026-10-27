---
name: autosys-jobs
description: Look up AutoSys jobs through the local AutoSys proxy API - job status, definition, run history, conditions (what a job depends on), dependents (which jobs depend on it), jobs inside a box, and multi-level dependency chains or box trees; and force-starting, killing or changing the status of a job when the user explicitly asks for it. Use when the user asks about an AutoSys job, box, its status, dependencies, dependents, runs or failures, or asks to force start, kill or change the status of a job.
---

# AutoSys jobs via the AutoSys proxy

The AutoSys proxy (running locally, default `http://localhost:8080`) answers a REST API by driving WCC
Quick View with the user's own AutoSys session. Use it for every question about AutoSys jobs; never guess job
data. Everything is read-only except `force-start`, `kill` and `change-status` (see [Force start](#force-start),
[Kill](#kill) and [Change status](#change-status)).

## Setup

- The script finds the user's API token (`asp_...`) by itself: in `AUTOSYS_PROXY_TOKEN` if set, else in the
  file `~/.autosys-proxy/token`. Do not look for, read or print the token yourself, and never ask the user to
  paste it into the chat.
- If the script says there is no token or it got 401 (exit code 2), stop and tell the user to: log in to AutoSys
  through the proxy, press **Copy token** on `http://localhost:8080/proxy/token`, and run in their own terminal
  `powershell -NoProfile -ExecutionPolicy Bypass -File <skill-dir>/autosys.ps1 set-token` (it saves the token
  from the clipboard). Then retry. Do not run `set-token` yourself.
- `AUTOSYS_PROXY_URL` overrides the proxy address (default `http://localhost:8080`).

## How to call it

Use the helper script `autosys.ps1` in this skill's directory. It prints JSON:

```powershell
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File <skill-dir>/autosys.ps1 <command> <JOB_NAME> [-Depth N] [-MaxJobs N]
```

(`pwsh` works the same on PowerShell 7 and on macOS/Linux.) Exit code 0 = success, 1 = the API returned a
problem (printed as JSON), 2 = setup problem (message on stderr).

| User asks | Command |
|---|---|
| everything about a job, its definition, attributes, "show me JOB" | `job JOB` |
| status, is it running/failed, last start/end, exit code | `status JOB` |
| dependencies, conditions, "what does JOB wait for / depend on" (direct) | `deps JOB` (read `condition` and `conditions`) |
| dependents, "which jobs depend on JOB", "what runs after JOB" (direct) | `deps JOB` (read `dependents`) |
| run history, previous runs, when did it last fail | `runs JOB` |
| jobs in a box (direct children) | `jobs BOX` |
| find jobs by name pattern, "jobs starting with X" | `search "X*"` (wildcards `*`, `%`) |
| all jobs downstream, "everything that depends on JOB, directly or indirectly", impact of a failure | `downstream JOB [-Depth N]` |
| full upstream chain, "everything JOB needs before it can start", root cause of a job not starting | `upstream JOB [-Depth N]` |
| everything inside a box including nested boxes, "which jobs in BOX failed" | `tree BOX [-Depth N]` |
| who am I / token expiry | `session` |
| "force start JOB" (only on an explicit request, after confirming; see below) | `force-start JOB -Comment "<reason>"` |
| "kill JOB", "stop JOB" (only on an explicit request, after confirming) | `kill JOB -Comment "<reason>"` |
| "set JOB to INACTIVE", "mark JOB as SUCCESS", "change the status of JOB" (only on an explicit request, after confirming) | `change-status JOB -Status <STATUS> -Comment "<reason>"` |

Traversals make one API call per job (each takes a moment, because AutoSys Quick View is behind it). The
defaults are `-Depth 5` and `-MaxJobs 100`. Start with the defaults; if the result says `"truncated": true`,
tell the user and offer to go deeper instead of silently raising the limits a lot.

Without the script (e.g. no PowerShell), call the API directly; on Windows use `curl.exe`, not the `curl` alias:

```
curl.exe -s -H "Authorization: Bearer %AUTOSYS_PROXY_TOKEN%" http://localhost:8080/api/v1/jobs/JOB/dependencies
```

Endpoints: `GET /api/v1/jobs/{name}`, `/{name}/status`, `/{name}/dependencies`, `/{name}/runs`,
`/{name}/jobs`, `GET /api/v1/jobs?name=<pattern>`, `GET /api/v1/session`;
`POST /api/v1/jobs/{name}/actions` with `{"action":"FORCE_STARTJOB","comment":"..."}` or
`{"action":"CHANGE_STATUS","status":"INACTIVE","comment":"..."}`.

## What the results mean

- **Job** (`job`): `name`, `type` (`BOX` = can contain jobs, `REGULAR` = any other job), `jobType` (AutoSys
  type code such as `CMD`, `BOX`, `FW`), `box` (the box this job is in, or null), `status` (the status row),
  `jobs` (for a box: its direct jobs; null for a regular job), `attributes` (job definition as Quick View shows
  it, e.g. `Description`, `Owner`, `Send to machine`, `Command`, `Condition`, `Run days`), `conditions`,
  `dependents`, `runs`, `fetchedAt`.
- **Status row**: `status` is the AutoSys status code (`SUCCESS`, `FAILURE`, `RUNNING`, `ACTIVATED`,
  `INACTIVE`, `ON_ICE`, `ON_HOLD`, `TERMINATED`, ...), `statusText` the label shown in the UI, `startTime` /
  `endTime` as shown by Quick View (local time of the AutoSys server), `run` the run number, `exitCode`.
- **Dependencies** (`deps`): `condition` is the job's full AutoSys condition expression; `conditions` are its
  atomic parts (`condition`, `expectedValue`, `currentStatus`, `satisfied`); `dependents` are jobs whose
  condition refers to this job (`jobName`, `condition`, `satisfied`).
  - `satisfied`: `true` / `false`, or `null` when Quick View showed no clear answer (say "unknown").
  - Condition syntax: `s(JOB)` success, `f(JOB)` failure, `d(JOB)` done, `n(JOB)` not running,
    `t(JOB)` terminated, `e(JOB)=N` exit code, `v(NAME)=x` global variable (not a job). `s(JOB,10.00)` means
    "within the last 10 hours"; `&` is AND, `|` is OR.
- **Traversals** (`downstream`, `upstream`): `nodes` are the jobs reached with their `depth` (0 = the start
  job) and `condition`; `edges` are `from` -> `to` meaning "`to` depends on `from`", with the condition and
  whether it is satisfied; `errors` are jobs that could not be read (e.g. a job in another AutoSys instance,
  404); `truncated` = limits reached. A job reached twice is listed once (cycles are handled).
- **Box tree** (`tree`): `nodes` lists each box (`box`, `depth`) with its direct `jobs` (name, type, status,
  times, exit code). Nested boxes appear both as a job of their parent and as their own node.

## Answering

- Answer the question first, in plain words, then the supporting details. For lists of jobs use a short table
  (job, status, condition / satisfied, times). For chains, show the levels (e.g. an indented tree or
  "A -> B -> D").
- Mention when the data was read (`fetchedAt`) if timing matters, and that times are AutoSys server times.
- "Which jobs failed in BOX" -> `tree BOX`, then filter jobs with `status` FAILURE or TERMINATED (and say
  which boxes they are in). "Why hasn't JOB started" -> `deps JOB`, list the conditions that are not
  satisfied, then follow them with `upstream` if needed.
- Report `errors` and `truncated` from traversals instead of leaving them out.

## Force start

`force-start` changes AutoSys: it starts the job right away, whatever its conditions. So:

- Run it only when the user explicitly asks to force start a job, never as a step of answering another question
  (e.g. "why hasn't JOB started" is answered, not fixed), and never for several jobs in a loop.
- Before running it, show the job's current status (`status JOB`) and ask the user to confirm with the exact job
  name ("Force start TEST_JOB now? It is ACTIVATED."). Run it only after a clear yes in the user's next message.
  Pass the user's reason as `-Comment` if they gave one (it goes to the proxy's audit log).
- Run it once. The result has `outcome`:
  - `ACCEPTED`: AutoSys took the event. Report `statusAfter` (e.g. `STARTING`, `RUNNING`) and `autosysMessage`.
  - `REJECTED` (409 `autosys-action-rejected`): AutoSys refused it (e.g. no permission, or the job's menu does not
    offer it); nothing happened. Show `autosysMessage`.
  - `UNKNOWN` (502 `autosys-action-unknown`): the event was confirmed but the answer was unclear; it may have
    run. Do **not** retry: check `status JOB` and tell the user what you see.
- Other errors (`autosys-session`, `autosys-unexpected-response`, ...) say "the event was not sent" in `detail`:
  nothing happened, and it is safe to try once more if the user wants.

## Kill

`kill` stops a running job (AutoSys `KILLJOB`; AutoSys then usually sets it to `TERMINATED`, which may start jobs
whose conditions wait for that).

- The same rules as [Force start](#force-start): only on an explicit request, never as a step of answering another
  question, never in a loop; show `status JOB` first and confirm the exact job name ("Kill TEST_JOB now? It is
  RUNNING since 02:00."); run it once; never retry `UNKNOWN`.
- If the job is not `RUNNING` (or `STARTING`), say so before asking: there may be nothing to kill.
- Report `statusAfter` (often still `RUNNING` for a moment: offer to check `status JOB` again) and `autosysMessage`.

## Change status

`change-status` sets a job's status without running it (AutoSys "Send Event" → "Change Status"). `-Status` is one of
`RUNNING`, `STARTING`, `SUCCESS`, `FAILURE`, `INACTIVE`, `TERMINATED`. It is riskier than force start: `SUCCESS` (or
`FAILURE`, `TERMINATED`) releases the jobs that depend on it as if the job had really ended that way.

- The same rules as [Force start](#force-start): only on an explicit request, never as a step of answering another
  question, never in a loop; show `status JOB` first and confirm the exact job **and status** ("Change TEST_JOB from
  FAILURE to INACTIVE now?"); run it once; never retry `UNKNOWN`.
- Before setting `SUCCESS`, `FAILURE` or `TERMINATED`, also show what depends on the job (`deps JOB`, its
  `dependents`) and say that they may start.
- The result has `status`, `event` and `autosysMessage` (what AutoSys said afterwards); report `statusAfter`.
  `REJECTED` with "offers no status ..." means that status is not available for that job.

## Errors

The API returns `application/problem+json` with `type` and `detail`:

| Status / type | Meaning and what to tell the user |
|---|---|
| 401 `token-invalid` | Token missing, expired or replaced by a newer login: log in through the proxy again and set a new token. |
| 400 `invalid-job-name` | Not a valid AutoSys job name (letters, digits, `_ - . #`). |
| 404 `job-not-found` | No such job (`detail` may say the search matched other jobs: offer `search`). |
| 409 `not-a-box` | `jobs` / `tree` on a regular job: answer with `job` instead. |
| 409 `autosys-action-rejected`, 502 `autosys-action-unknown` | See [Force start](#force-start). |
| 400 `invalid-status` | `change-status` without a valid `-Status` (or `-Status` given to another action). |
| 429 `rate-limited` | Too many actions in a minute: wait, do not loop. |
| 403 `actions-disabled` | Actions are switched off on this proxy. |
| 409 `autosys-login-rejected` | AutoSys rejected the stored password (e.g. it was changed): tell the user to log in to AutoSys through the proxy again and set the new token. Do not retry. |
| 502 `autosys-quickview` | The proxy could not open Quick View: open Quick View in the browser through the proxy (`http://localhost:8080`), then retry. |
| 502 `autosys-session`, `autosys-unreachable`, `autosys-unavailable`, `autosys-login` | AutoSys did not answer as expected: retry once; if it fails again, show the `detail` to the user. |

If the proxy is not reachable at all, tell the user to start it (`./gradlew bootRun` in the proxy project).

## Limits

- The only changes the API makes are force start, kill and change status. It cannot hold or ice jobs or change
  definitions: if asked to, say so and suggest doing it in the AutoSys UI.
- Only jobs the user's AutoSys account can see in Quick View are returned.
