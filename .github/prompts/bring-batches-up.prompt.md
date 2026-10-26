---
description: Bring all batches up (to SUCCESS) in UAT, QA or both, working in a loop until they are done or the time limit is reached (30 min by default).
agent: batch-support
argument-hint: "[UAT|QA|all] [REGION...] [time limit, e.g. 45m]"
---

# Bring the batches up

Work in a loop until every batch in scope reaches **SUCCESS** or the time runs out. Follow everything in your
agent definition (environments, dependencies between jobs and boxes, upstream feeds, the runbook), except for the
approval rule, which this command changes as described under [What you may do](#what-you-may-do).

## Scope and time limit

Read them from what the user wrote after the command:

- **Environment:** `UAT`, `QA` or `all`. Default: `all` (both, reported separately).
- **Regions:** `TOKYO`, `EMEA`, `NAM`, or batch names from `knowledge/batch-map.yaml`. Default: all regions,
  all batches.
- **Time limit:** a duration such as `15m`, `1h`, `90 minutes`. Default: **30 minutes**.

Say the scope and the limit in one line before you start ("Bringing up UAT + QA, all regions, limit 30 min").
Write down the start time from the terminal (`Get-Date -Format "yyyy-MM-dd HH:mm:ss"` in PowerShell) and check it at
the start of every round. Do not guess the time.

## The loop

Repeat these rounds:

1. **Check the state.** Read the top-level box of every batch in scope (`tree BOX` or `status BOX`), with fresh
   data every round. A batch is *done* when its top-level box is `SUCCESS`.
2. **Stop if** all batches are done, or the time limit is reached, or the user says stop. Go to
   [Final report](#final-report).
3. **Find the root jobs.** For every batch that is not done, find the job that actually blocks it: the earliest
   failed, terminated or stuck job, including conditions on its boxes and upstream feeds. Skip batches that are
   only waiting on a root job you already found, and ones that are simply running normally.
4. **Act** on each root job (see [What you may do](#what-you-may-do)), most urgent cut-off first
   (`knowledge/regions.md`).
5. **Report progress** in a few lines: round number, time used / limit, batches done / total, what you did this
   round, and what you are waiting for.
6. **Wait** before the next round if you only have running jobs to watch: about 2 minutes
   (`Start-Sleep -Seconds 120`), less if something is about to finish, never longer than the time left. Do not
   wait when you still have something to do.

Keep a list of everything you have tried for each job (what, when, result) and use it every round: never repeat an
action that already failed in the same way.

## Be persistent and smart

Do not give up on a blocked batch after the first idea. Before you call a job *stuck*, work through these:

- **Read everything about it:** `job` (definition, command, machine, run days), `deps` of the job **and of every
  box above it**, `runs` (did it work before? how did the last good run differ?), and the status of every job in
  its conditions.
- **Follow the chain.** A blocker is often several levels up: use `upstream`, then add the box levels by hand.
  Fix the earliest one; the rest usually follow.
- **Check the feeds.** For every loader or file watcher on the path, check the sync job in
  `knowledge/upstream-feeds.yaml`.
- **Compare UAT and QA.** If a job works in one environment and not in the other, compare their definitions and
  conditions (read-only). A difference often explains it.
- **Read the runbook again** with the evidence you now have: a case may match that did not look like it at first.
- **Look at the conditions' details:** a look-back window (`s(JOB,10.00)`) that the last good run has left, an
  exit-code condition (`e(JOB)=0`), a condition on a job that does not exist in this environment, a
  `v(NAME)` global variable.

Only when all of these are done, and nothing you may do would help, mark the job as **needs a human**, with the
evidence and who to ask, and go on with the other batches. Re-check it in later rounds in case it moved.

## What you may do

Starting this command is the user's approval, for this run only, of these actions without asking again:

- **Force-start a sync job** from `knowledge/upstream-feeds.yaml` (in the environment in scope) when the feed's
  loader waits for it, as described in the agent definition.
- **Force-start a job when `known-failures.md` says so** for the case you matched.
- **Force-start a failed or terminated root job** once, when the evidence points to a one-off failure (it ran
  fine before with the same definition, its conditions are satisfied, and nothing suggests the same failure will
  happen again). Say why you think so.

Limits:

- At most **2 force-starts per job** in a run. After that it needs a human.
- Only jobs in the environment(s) and regions in scope, and only through the `autosys-jobs` skill.
- Never force-start a job whose own or box conditions are not satisfied, just to skip what it waits for, unless the
  runbook says so for that case.
- After a force-start, never retry an `UNKNOWN` outcome: check the status in the next round instead.
- Anything else (kill, hold, ice, changing a definition, a job outside the scope, a 3rd force-start) is not
  approved: put it in the final report as a proposal.

## Final report

When the loop stops, say why (all done / time limit / stopped by the user), then:

```
ENV | REGION | BATCH | STATE | ROOT JOB | CAUSE | ACTIONS TAKEN | RESULT
```

followed by:

- **Needs a human:** each blocked job with the evidence, what you tried, and who to ask.
- **Proposals:** actions you would take but were not allowed to (see above).
- **Runbook:** a draft entry for `known-failures.md` for each problem you solved that was not in it.
- Time used, and the number of rounds.
