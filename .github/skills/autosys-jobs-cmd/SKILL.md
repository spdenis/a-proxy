---
name: autosys-jobs-cmd
description: Windows cmd version of autosys-jobs (no PowerShell needed, only curl.exe). Look up AutoSys jobs through the AutoSys proxy API - job status, definition, run history, conditions (what a job depends on), dependents (which jobs depend on it), jobs inside a box, and multi-level dependency chains or box trees; and force-starting, killing or changing the status of a job when the user explicitly asks for it. Use when the user asks about an AutoSys job, box, its status, dependencies, dependents, runs or failures, or asks to force start, kill or change the status of a job.
---

# AutoSys jobs via the AutoSys proxy (cmd)

The AutoSys proxy answers a REST API by driving WCC Quick View with the user's own AutoSys session. There can be
several proxies; the script always talks to the one the user selected (see [Choosing the proxy](#choosing-the-proxy)).
Use it for every question about AutoSys jobs; never guess job data. Everything is read-only except `force-start`,
`kill` and `change-status` (see [Force start](#force-start), [Kill](#kill) and [Change status](#change-status)).

## How to call it

Use the helper script `autosys.cmd` in this skill's directory (plain cmd and `curl.exe`, no PowerShell). It prints
the API's JSON:

```
cmd /c "<skill-dir>\autosys.cmd" <command> <JOB_NAME> [-Comment "text"] [-Status STATUS]
```

Exit code 0 = success, 1 = the API returned a problem (printed as JSON), 2 = setup problem (message on stderr).
Pass job names and comments in double quotes when they contain spaces; comments must not contain `"`.

| User asks | Command |
|---|---|
| everything about a job, its definition, attributes, "show me JOB" | `job JOB` |
| status, is it running/failed, last start/end, exit code | `status JOB` |
| dependencies, conditions, "what does JOB wait for / depend on" (direct) | `deps JOB` (read `condition` and `conditions`) |
| dependents, "which jobs depend on JOB", "what runs after JOB" (direct) | `deps JOB` (read `dependents`) |
| run history, previous runs, when did it last fail | `runs JOB` |
| jobs in a box (direct children) | `jobs BOX` |
| find jobs by name pattern, "jobs starting with X" | `search "X*"` (wildcard `*`) |
| who am I / token expiry | `session` |
| "force start JOB" (only on an explicit request, after confirming; see below) | `force-start JOB -Comment "<reason>"` |
| "kill JOB", "stop JOB" (only on an explicit request, after confirming) | `kill JOB -Comment "<reason>"` |
| "set JOB to INACTIVE", "mark JOB as SUCCESS", "change the status of JOB" (only on an explicit request, after confirming) | `change-status JOB -Status <STATUS> -Comment "<reason>"` |

### Chains and box trees

The cmd script makes one call at a time; follow chains yourself, one call per job (each takes a moment, because
AutoSys Quick View is behind it). Remember which jobs you have already read and read each only once (cycles happen).
Stop at 5 levels or 100 jobs, whichever comes first, and tell the user the result is cut off there; offer to go
deeper instead of silently going on.

- **Downstream** ("everything that depends on JOB, directly or indirectly", impact of a failure): `deps JOB`, then
  `deps` for each job in `dependents`, and so on.
- **Upstream** ("everything JOB needs before it can start", root cause of a job not starting): `deps JOB`, then
  `deps` for each job named in its conditions (`s(A)`, `d(B,10.00)`, `e(C)=1` ...; `v(NAME)` is a global variable,
  not a job), and so on.
- **Box tree** ("everything inside BOX", "which jobs in BOX failed"): `jobs BOX`, then `jobs` for each job with
  `type` `BOX`, and so on.
- A job that answers 404 or another error (e.g. it is in another AutoSys instance): note it and go on with the rest.

## Setup

- `curl.exe` must be there (Windows 10 1803 and later have it). The script finds the API token (`asp_...`) of the
  selected proxy by itself: in `AUTOSYS_PROXY_TOKEN` if set, else in the proxy's own file in
  `%USERPROFILE%\.autosys-proxy`. Do not look for, read or print the token yourself, and never ask the user to
  paste it into the chat.
- If the script says there is no token or it got 401 (exit code 2), stop and pass on its message: log in to AutoSys
  through that proxy, press **Copy token** on `<proxy URL>/proxy/token`, and run in their own terminal
  `"<skill-dir>\autosys.cmd" set-token` (it asks for the token; paste it with right-click or Ctrl+V). Then retry.
  Do not run `set-token` yourself: it waits for input.

## Choosing the proxy

The user saves their proxies under names and selects one; every call then goes to it. Never pick another proxy on
your own.

| User says | Command |
|---|---|
| "which proxies are there", "which proxy am I using" | `proxy-list` (the proxy in use, the saved ones, which have a token) |
| "add the UAT proxy at https://host:8443" | `proxy-add uat https://host:8443` |
| "switch to UAT", "use the local proxy" | `proxy-use uat` (or a URL) |

- Switch only when the user asks, then say which proxy is now in use and whether it has a token.
- For actions (force start, kill, change status), name the proxy in the confirmation question ("Force start
  TEST_JOB on uat now?"); `proxy-list` tells you which one is in use.
- `-Proxy NAME_OR_URL` on a single call overrides the selection; use it only when the user asks for one call on
  another proxy.

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
- **Box jobs** (`jobs`): the box's direct `jobs` (name, type, status, times, exit code).

## Answering

- Answer the question first, in plain words, then the supporting details. For lists of jobs use a short table
  (job, status, condition / satisfied, times). For chains, show the levels (e.g. an indented tree or
  "A -> B -> D").
- Mention when the data was read (`fetchedAt`) if timing matters, and that times are AutoSys server times.
- "Which jobs failed in BOX" -> walk the box tree (see [Chains and box trees](#chains-and-box-trees)), then filter jobs with `status` FAILURE or TERMINATED (and say
  which boxes they are in). "Why hasn't JOB started" -> `deps JOB`, list the conditions that are not
  satisfied, then follow them upstream if needed.
- Report jobs that could not be read and where you stopped a chain instead of leaving them out.

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
| 409 `not-a-box` | `jobs` on a regular job: answer with `job` instead. |
| 409 `autosys-action-rejected`, 502 `autosys-action-unknown` | See [Force start](#force-start). |
| 400 `invalid-status` | `change-status` without a valid `-Status` (or `-Status` given to another action). |
| 429 `rate-limited` | Too many actions in a minute: wait, do not loop. |
| 403 `actions-disabled` | Actions are switched off on this proxy. |
| 409 `autosys-login-rejected` | AutoSys rejected the stored password (e.g. it was changed): tell the user to log in to AutoSys through the proxy again and set the new token. Do not retry. |
| 502 `autosys-quickview` | The proxy could not open Quick View: open Quick View in the browser through the proxy (its URL), then retry. |
| 502 `autosys-session`, `autosys-unreachable`, `autosys-unavailable`, `autosys-login` | AutoSys did not answer as expected: retry once; if it fails again, show the `detail` to the user. |

If the proxy is not reachable at all, say which URL was tried; for a local one tell the user to start it
(`./gradlew bootRun` in the proxy project), for another, check the URL with `proxy-list`.

## Limits

- The only changes the API makes are force start, kill and change status. It cannot hold or ice jobs or change
  definitions: if asked to, say so and suggest doing it in the AutoSys UI.
- Only jobs the user's AutoSys account can see in Quick View are returned.
