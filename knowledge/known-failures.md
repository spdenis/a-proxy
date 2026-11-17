# Known failures runbook

The agent matches evidence against these cases. Keep one case per section, same headings.
**Add a case every time someone fixes something by hand.** The examples below are generic starters — adjust or replace them with your team's real cases.

Template:

```
## KF-NNN: <short name>
- **Symptom:** what status / log line / timing you see
- **Confirm by:** which skill scripts to run and what to look for
- **Fix:** exact actions, in order (state-changing ones need user approval)
- **Don't:** common wrong reaction
- **Escalate to:** who, if the fix doesn't work
```

---

## KF-001: Job stuck in STARTING
- **Symptom:** status ST for more than ~5 minutes.
- **Confirm by:** `definition.sh <job>` to get the machine; check other jobs on the same machine — if they're also ST/PE, the agent is down.
- **Fix:** if only this job is affected: `kill.sh`, then `force_start.sh`. If the whole machine is affected: don't restart jobs — escalate.
- **Don't:** force-start repeatedly; it queues more starts on a dead agent.
- **Escalate to:** Autosys / platform support (machine agent down).

## KF-002: Upstream feed file not arrived
- **Symptom:** file watcher job RU/AC far past normal time, or loader FA with "file not found".
- **Confirm by:** `log.sh <loader>`; `deps.sh <loader>` to identify the feed/watcher job.
- **Fix:** first force-start the feed's `sync_job` from `upstream-feeds.yaml` (even if it shows SUCCESS) and wait until that new run ends in SUCCESS. Then, if the watcher / loader did not start on its own, `force_start.sh` the loader. If the sync job fails, stop and escalate. If after the new sync the file still does not come, ask the user whether to ice the watcher.
- **Don't:** force-start the watcher or loader before a new sync run has succeeded; ice the loader itself — downstream would run on stale data.
- **Escalate to:** owner of the upstream feed.

## KF-003: Database lock / deadlock / connection timeout
- **Symptom:** FA with ORA-00060, ORA-00054, deadlock, "connection reset", or pool timeout in the log.
- **Confirm by:** `log.sh <job> 100`.
- **Fix:** usually transient on staging — `force_start.sh` once. If it fails again with the same error, stop.
- **Don't:** retry more than once.
- **Escalate to:** DBA / environment owner if repeated.

## KF-004: Job running far longer than normal (hung)
- **Symptom:** RU for more than 2× its usual duration; log has no new lines for a long time.
- **Confirm by:** `status.sh` (start time), `log.sh` (last lines and their timestamps).
- **Fix:** `kill.sh`, wait for TE, then `force_start.sh`.
- **Don't:** kill jobs that are slow but still writing progress to the log.
- **Escalate to:** app owner if it hangs again at the same step.

## KF-005: Job ON_HOLD / ON_ICE unexpectedly
- **Symptom:** OH or OI on a job that should run.
- **Confirm by:** `status.sh`; ask the user — someone may have held it on purpose (testing, a release).
- **Fix:** only after the user confirms nobody needs the hold: `hold.sh <job> off` / `ice.sh <job> off`.
- **Don't:** release holds silently.
- **Escalate to:** whoever placed the hold, if known.

## KF-006: Downstream avalanche after a root failure
- **Symptom:** many jobs IN/AC past their start time in one box; one job upstream in FA/TE.
- **Confirm by:** `status.sh <box> --tree`; find the earliest FA/TE; `deps.sh` on a waiting job.
- **Fix:** fix the root job per its own case. Once it is SU, dependents start by themselves. Only force-start dependents whose start window has passed (`date_conditions`/`start_times` in `definition.sh`).
- **Don't:** force-start the whole tree.
- **Escalate to:** —

## KF-007: Box still FAILURE after its failed jobs were fixed
- **Symptom:** a box is FA/TE (it shows as a root in `blockers`), but the jobs in it that failed are SU now (they were rerun), or some jobs in it never ran (IN/AC) because the box failed first.
- **Confirm by:** `jobs <box>` (or `tree <box>` for nested boxes): every job FA/TE before is SU for today's run; note the ones still IN/AC.
- **Fix:** 1) jobs still FA/TE: fix each by its own case. 2) jobs that never ran: force-start them one by one in condition order, once their conditions are satisfied (the ended box will not start them). 3) when every job in the box is SU for today's run (or OI / ON_NOEXEC): `change-status <box> -Status SUCCESS -Comment "jobs fixed: ..."`. Its dependents then start. Nested boxes: innermost first.
- **Don't:** force-start the box — it reruns every job in it, including those already SU today, and the reruns fail (double loads, files gone, duplicate keys). Never force-start a job that is SU for today's run.
- **Escalate to:** app owner if a job in the box fails again after its fix.
