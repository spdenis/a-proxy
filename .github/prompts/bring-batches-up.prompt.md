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

1. **Check the state of the batches not done yet.** Read the top-level box (`status BOX -Fresh`) of each batch
   that is **not** in your *done* list; never read a done batch again. A batch is *done* when its top-level box
   is `SUCCESS` **for today's run**, or it is not due today (not a run day, or its start time is still ahead —
   that one comes back when its start time passes). A `SUCCESS` from an earlier day on a run day whose start time
   has passed is **not** done: it missed today's run and must catch up (see "judge today's run" in your agent
   definition). Move each newly done batch to the *done* list. Get the current time from the terminal every
   round.
2. **Stop if** all batches are done, or the time limit is reached, or the user says stop. Go to
   [Final report](#final-report).
3. **Find the root jobs: always start with the incomplete-dependencies call.** In one call, ask for what keeps
   every batch that is not done from completing: `blockers BOX1,BOX2,... -Mode all -Fresh`, with the top-level
   boxes, or the jobs that missed their run. The proxy follows the conditions up through every level, and the
   boxes too, including look-backs and the jobs inside a started or failed box. It lists every unready dependency
   with the **root blockers first**.
   - The **root blockers** (`root: true`) are the jobs that break the job sequence: they wait for nothing unready
     themselves, and everything after them waits for them. Fixing starts with them, and only with them.
   - Jobs that are not roots only wait, in the order the list gives (`waitsFor` / `blocks`). Do not act on them:
     they move once their roots are fixed.
   - Several batches stuck behind one root show it once, with all of them in its `targets`: fix it once.
   - Then gather the evidence for each **new** root only, once: `job`, `runs`, and the sync job when it is a
     feed (without `-Fresh`). Its conditions and why they are not met are already in the `blockers` answer: do
     not call `deps` / `upstream` on it or on the jobs it waits for. A root you already have evidence for is not
     read again unless you acted on it. Use `upstream` / `tree` only if `blockers` answers `truncated: true`.
   - Never call `status` on a job that the `blockers` answer already lists: its status is in the answer.
   - Skip batches whose only blockers are `RUNNING` and within their usual run time (compare with `runs`): they
     are progressing.
4. **Fix each root blocker with the runbook**, most urgent cut-off first (`knowledge/regions.md`), within
   [What you may do](#what-you-may-do). Each root's `blocks[].reason` and `condition` say how the dependency
   failed. Match that failure to its case in `knowledge/known-failures.md` (and `upstream-feeds.yaml` for
   loaders and file watchers), name the case, and fix it as that case says. For example:
   - `is FAILURE` / `is TERMINATED, needs SUCCESS`: the case for that job and exit code. Without one, the
     one-off failure rule.
   - `outside the look-back of ...`: the dependency's last good run is too old. Catch up its run for today,
     after checking it has no blockers of its own (it is a root).
   - `its box ... is ..., not running`: the box's own case, or catch up the box's missed run.
   - A **box in `FAILURE` / `TERMINATED`** (as a root, or after you fixed the jobs that failed in it): **never
     force-start it**, that reruns the jobs that already succeeded and they fail. Follow "Failed box: mark it,
     don't rerun it" in your agent definition: fix the failed jobs, force-start only the jobs that never ran,
     then `change-status BOX -Status SUCCESS` once every job in it is `SUCCESS` for today (KF-007).
   - A feed loader or file watcher: the sync job first, always (see the strict rule below).
   - `ON_HOLD`, a `VARIABLE`, an `EXTERNAL` or `UNKNOWN` blocker, or no runbook case: do not improvise. It is a
     proposal or **needs a human**, with the evidence.

   After fixing roots, do not fix the jobs that waited for them. The next round's `blockers` call shows whether
   they moved and which roots, if any, come next.
5. **Report progress** in a few lines: round number, time used / limit, batches done / total, what you did this
   round, and what you are waiting for.
6. **Wait** before the next round if you only have running jobs to watch: about 2 minutes
   (`Start-Sleep -Seconds 120`), less if something is about to finish, never longer than the time left. Do not
   wait when you still have something to do.

Keep a **state table** and update it every round (see "Don't read the same thing twice" in your agent
definition): the *done* list, each root with its evidence and runbook case, and everything you have tried for each
job (what, when, result). Work from it: read again only the batches not done, the round's `blockers` call, and the
jobs you acted on. Never repeat an action that already failed in the same way.

## Be persistent and smart

Do not give up on a blocked batch after the first idea. Before you call a job *stuck*, work through these:

- **Read everything about it, once:** `job` (definition, command, machine, run days) and `runs` (did it work
  before? how did the last good run differ?). Its conditions, its boxes' conditions and the status of every job in
  them are already in the `blockers` answer: use those, do not read them again one by one.
- **Follow the chain.** A blocker is often several levels up. `blockers` follows it for you, boxes and
  look-backs included, and puts the root first: fix the roots; the rest usually follow.
- **Check the feeds.** For every loader or file watcher on the path, check the sync job in
  `knowledge/upstream-feeds.yaml`.
- **Compare UAT and QA.** If a job works in one environment and not in the other, compare their definitions and
  conditions (read-only). A difference often explains it.
- **Read the runbook again** with the evidence you now have: a case may match that did not look like it at first.
- **Look at the conditions' details:** a look-back window (`s(JOB,10.00)`) that the last good run has left, an
  exit-code condition (`e(JOB)=0`), a condition on a job that does not exist in this environment, a
  `v(NAME)` global variable.

Only when all of these are done, and nothing you may do would help, mark the job as **needs a human**, with the
evidence and who to ask, and go on with the other batches. Do not read it again yourself: if it moves, the
round's `blockers` call shows it.

## What you may do

Starting this command is the user's approval, for this run only, of these actions without asking again:

- **Force-start a sync job** from `knowledge/upstream-feeds.yaml` (in the environment in scope) when the feed's
  loader waits for it, or before starting any of its upstream jobs, as described in the agent definition.
- **Force-start a job when `known-failures.md` says so** for the case you matched.
- **Catch up a missed run:** force-start a box or job that was scheduled today, whose start time has passed and
  which has not started, once its own and its boxes' conditions are satisfied (and it is not `ON_HOLD` /
  `ON_ICE`). Only today's run, never runs of earlier days.
- **Force-start a failed or terminated root job** once, when the evidence points to a one-off failure (it ran
  fine before with the same definition, its conditions are satisfied, and nothing suggests the same failure will
  happen again). Say why you think so.
- **Mark a failed box SUCCESS** (`change-status BOX -Status SUCCESS -Comment "jobs fixed: ..."`) when every job in
  it is `SUCCESS` for today's run (or `ON_ICE` / `ON_NOEXEC`), after you fixed the ones that failed (KF-007). Only
  boxes, only to `SUCCESS`, only in that case.
- **Force-start the jobs of a failed box that never ran** (`INACTIVE` / `ACTIVATED` because the box failed first),
  one by one in condition order, once their own conditions are satisfied.

Limits:

- **Never rerun what succeeded.** Never force-start a job that is `SUCCESS` for today's run, and never force-start
  a box that has any job `SUCCESS` for today's run: mark the box instead (above). Only exception: a sync job under
  the strict sync rule below.

- **Strict: sync before any upstream job.** Before you force-start any file watcher or loader listed in
  `upstream-feeds.yaml` (for whatever reason: a runbook case, a one-off failure, a missed run), force-start its
  sync job, even if it shows `SUCCESS`, and wait until that new run ends in `SUCCESS` (end time after your
  force-start). Only then start the upstream job, and only if it did not start on its own. If the sync job fails
  or cannot be started, do not start the upstream job: it **needs a human**. A sync run done for this counts
  toward the sync job's 2 force-starts.
- At most **2 force-starts per job** in a run. After that it needs a human.
- Only jobs in the environment(s) and regions in scope, and only through the `autosys-jobs` skill.
- Never force-start a job whose own or box conditions are not satisfied, just to skip what it waits for, unless the
  runbook says so for that case. In `blockers` terms: act on root blockers only, never on a job that still
  `waitsFor` something.
- After a force-start, never retry an `UNKNOWN` outcome: check the status in the next round instead.
- Anything else (kill, hold, ice, any other status change, changing a definition, a job outside the scope, a 3rd
  force-start) is not approved: put it in the final report as a proposal.

## Final report

When the loop stops, say why (all done / time limit / stopped by the user), then:

```
ENV | REGION | BATCH | SCHEDULED | LAST RUN (date, state) | ROOT JOB | CAUSE | ACTIONS TAKEN | RESULT
```

followed by:

- **Needs a human:** each blocked job with the evidence, what you tried, and who to ask.
- **Proposals:** actions you would take but were not allowed to (see above).
- **Runbook:** a draft entry for `known-failures.md` for each problem you solved that was not in it.
- Time used, and the number of rounds.
