# Phase 11: replacement task sections for tasks 23, 24 and 25

These three sections replace the `### Task 23`, `### Task 24` and `### Task 25` sections in
`docs/plans/20260805-claude-remarks-phase11.md`. They are written here, in a separate file, because
that plan is being executed right now and other agents are ticking its checkboxes.

The reason for the replacement is one decision, applied to the specification in section 17 of
`docs/plans/20260805-claude-remarks-phase11-spec.md`: **several sessions may listen to the same
repository at once, and nothing kills a watcher.** The exclusion that the killing was meant to
provide already exists one layer up, in the batch claim: `published-read` is atomic in the IDE, so
the first session to claim a batch is answered `ok` and every later one is answered `already-read`
with the winner's name. Doing the same exclusion a second time, by killing a process, is what caused
a real incident on 2026-08-05.

## What changes, checkbox by checkbox

### Task 23 — what changes

**Dropped:**

- the old checkbox that only corrected the comment above the `INT TERM HUP` trap. It is replaced by a
  wider one, because the block that comment describes is now deleted.
- the old single hand-check checkbox. It is now three checks and a gate.

**New:**

- delete the block that kills the pid already in the pid file.
- say what the pid file is for now, and rewrite the comment above `hex16_of` that explains its name
  by the one-watcher-per-project rule.
- two hand checks: two watchers on one project, and two watchers on two different projects.

**Unchanged:** dropping `session_id` from the fetch guard, the request body without a `session`
field, the reworded `timed_out_fetch` and `usage`, and the rule that the token never reaches `curl`'s
argv.

### Task 24 — what changes

**Dropped:** nothing.

**New:** one checkbox — a session that lost the batch claim does not answer the marked remarks in
that batch either.

**Unchanged:** everything else, including the shape of the POST and the two must-not rules.

### Task 25 — what changes

**Dropped:**

- the whole pid-file check on an exit code above 128: read the file, retry after two seconds, stop if
  it names a live watcher on the same identity. It was the right fix for the wrong design. There are
  no takeovers now, so there is nothing left for it to detect.
- the checkbox that asked which identity that check compares in each mode. With the check gone, there
  is nothing to compare.

**New:**

- the rules that replace the one-watcher rule: nothing kills a watcher, and the batch claim decides
  who acts.
- the stopping rules: never match on the program name, stop only by the pid in the repository's own
  pid file, and state repository isolation as a guarantee.
- the rule that a session which stops listening always says so and why.
- two hand checks and a gate.

**Unchanged:** the startup claim, the loop order, the remote branch, and the one rule for every
remote POST.

### Also in the plan file, outside these three tasks

Two hand checks in the plan's own `### Hand checks` list still describe the old rule and need the
same treatment. Hand check 11, "A real takeover stops the loser", becomes two sessions listening to
one project where exactly one acts. Hand check 24, "A stray kill does not stop the listener", becomes
a killed watcher that is reported and replaced. Section 19 of the specification already carries both
in their new form, plus a new check for two repositories on one machine.

---

### Task 23: watch-remarks.sh — fetch without a session, and nothing that kills another watcher

**Files:**
- Modify: `docs/skill/claude-remarks-review/watch-remarks.sh` (the `fetch)` branch of the mode `case`,
  which requires `session_id`; the `jq -n` body built at the top of the fetch loop; the
  `timed_out_fetch` helper; the `usage` synopsis; the block under the comment "One watcher per project
  on this machine", which kills the old pid and waits five seconds for it to die; the comment above
  `hex16_of` that explains the pid file's name by that same rule; the comment above
  `trap 'cleanup; ...; exit 143' INT TERM HUP`)

- [ ] drop `session_id` from the fetch-mode guard, so it requires the url and the project and no
      longer the session, and build the request body without a `session` field when `$session_id` is
      empty and with it when it is not
- [ ] reword `timed_out_fetch` so it does not name a session it may not have, and update the `usage`
      synopsis so `--session` reads as optional
- [ ] ⚠️ delete the block that kills whichever pid is already in the pid file, and the five-second
      wait for that process to die. A starting watcher never takes over from one that is already
      running: several sessions may listen to one repository, and the batch claim in the IDE is what
      stops two of them acting on the same batch
- [ ] keep the pid file, the `mkdir` lock around the write, and the cleanup trap's check that the file
      still holds this watcher's own pid. Rewrite the comment above `hex16_of`, which explains the
      file's name by the one-watcher-per-project rule: the file now identifies one specific watcher so
      that it can be stopped by pid, and that is what makes a match on the program name unnecessary
- [ ] ⚠️ correct the comment above the `INT TERM HUP` trap, which tells a reader to read any exit code
      above 128 as "another watcher took over". 143 is `128 + SIGTERM`, which any kill produces, and
      nothing takes over any more. Say that a session seeing it reports the kill and starts a new
      watcher
- [ ] ⚠️ leave the token handling exactly as it is — read from `CLAUDE_REMARKS_TOKEN` in the
      environment and handed to `curl` on stdin through `--config -`, never as an argument where `ps`
      can read it
- [ ] check by hand in the scratchpad, with `HOME` pointed at a temporary directory and **never** at
      the real `~/.claude-remarks`: fetch mode starts with no `--session`, it still refuses with no
      `--project`, and it still refuses with no `CLAUDE_REMARKS_TOKEN`
- [ ] check by hand in that same scratchpad `HOME`: start a file-mode watcher, then start a second one
      on the same file. Both processes are still alive after the second starts, and the pid file holds
      the second watcher's pid on line 1 and the watched path on line 2
- [ ] check by hand in that same scratchpad `HOME`: with a watcher running on one path, start and then
      stop a watcher on a second path. The first watcher is still alive and its own pid file is
      untouched
- [ ] run `sh -n docs/skill/claude-remarks-review/watch-remarks.sh`, then run the three hand checks
      above once each from a clean scratchpad `HOME` - must pass before task 24

### Task 24: The skill answers what is marked

**Files:**
- Modify: `docs/skill/claude-remarks-review/SKILL.md` (a new answering section, referenced from all
  three modes; the two sentences that say "each with its severity, its tag and the code it points at",
  one in `## Read remarks the person already published` and one in step 7 of `## Steps`; the one-shot
  mode's closing "Then act on the remarks the same way step 7 describes" line)

- [ ] write the answering step once: find every remark whose heading carries the asks marker, answer
      each one in this turn from the conversation and from the batch payload, and POST each answer to
      `/api/claude-remarks/answer` with the batch's nonce and the remark's id
- [ ] say plainly that a subagent is the escalation and not the default, and why — it starts with an
      empty context, so making it the default pays to re-derive what the session already knows
- [ ] add the two must-not rules: answering a question is not licence to do the work the question
      implies, and a failed POST is reported rather than retried more than once
- [ ] ⚠️ add the rule that comes from several sessions listening at once: a session answered
      `already-read` for a batch does not answer the marked remarks in that batch either. The session
      answered `ok` is the one that answers them. The losing session names the winner, answers
      nothing, and goes back to listening
- [ ] ⚠️ write the POST as a third copy of the `printf 'header = ...' | curl --config -` shape, so the
      token never reaches `curl`'s argv and is never echoed. The file already argues why these stay
      copies rather than becoming a shared script, so quote that argument rather than re-deriving it
- [ ] reference the answering step from listen mode, from the read-what-is-published mode and from
      review mode's step 7, and fix the two severity-and-tag sentences
- [ ] check by hand in the scratchpad, with `HOME` overridden, that the new shell block runs and that
      `ps` shows no token in any `curl` argument line while it is in flight - must pass before task 25

### Task 25: The skill: listen mode claims, re-arms, shares the repository and reaches over the tunnel

**Files:**
- Modify: `docs/skill/claude-remarks-review/SKILL.md` (the frontmatter sentence promising that listen
  mode acts on nothing published before listening started; the same promise in the opening of
  `## Listen for the next batch`; that section's startup Bash block; its exit-code list, in particular
  the "Any exit code above 128, 143 in particular" entry; the re-arming paragraph that cites the
  one-watcher-per-project rule; the same exit-code entry in step 6 of `## Steps`; the "One watcher per
  project on the machine" paragraph in `## The watcher script`, and the "An exit code above 128 is a
  signal" paragraph beside it; the kill line at the end of step 6 that stops a watcher by pid)

- [ ] add the startup claim: read the published file and take the nonce from line 2 **out of the file
      on every run**, never from a value remembered from an earlier one, then POST `published-read`
      for it and act on each of the three answers — `ok` surfaces the batch, `already-read` skips it
      and names the session that got there first, `unknown-batch` surfaces it and says plainly that it
      may already have been done
- [ ] add the loop in this exact order — the batch arrives, acknowledge it, **re-arm immediately,
      before summarising**, then summarise and wait for go — and say why the third step is where it is
- [ ] ⚠️ write the two rules that replace the one-watcher-per-project rule: several sessions may
      listen to one repository at once and **nothing kills a watcher**, and the batch claim is what
      decides who acts. A session answered `already-read` names the winner, does not act on the batch,
      and **keeps listening**. Losing a claim is an ordinary outcome, not a reason to stop
- [ ] ⚠️ write the stopping rules, and state repository isolation as a guarantee rather than leaving
      a reader to infer it from the script: never `pkill`, `killall` or `ps | grep | kill` matched on
      `watch-remarks.sh`, because every repository's watcher on this machine runs a program with that
      name and a blunt match stops all of them at once. A watcher is stopped only by the pid on the
      first line of its own repository's `.watch` file, and only after checking that the pid is alive
      and that its command line names the same watched path. That is what the pid file is for now —
      identifying one specific watcher, which is what makes a blunt match unnecessary
- [ ] ⚠️ replace the exit-code-above-128 rule in both places that carry it, listen mode's exit list
      and step 6's: 143 is `128 + SIGTERM`, which any kill produces, so the session says in one line
      that the watcher was killed and starts a new one. **Add no pid-file check** — an earlier draft
      had one and it is deleted, because there are no takeovers left for it to detect. Listen mode
      re-arms. Review mode launches a new watcher for the same review, which is still waiting in the
      IDE, and sends no `ack` of any kind
- [ ] say that a session which stops listening always says so and why, and never goes quiet: the
      deadline passing, a refusal, or the person asking it to stop, each in one line. The incident
      began with a session that stopped silently while the person kept publishing
- [ ] add the remote branch: read the four stored values from `remote-<hash>.env` with a whitelist
      parse and **never** by sourcing the file, build `base_url` once, get the startup nonce from a
      session-less `fetch` because there is no local file to read over a tunnel, and arm the watcher
      with `--fetch "$base_url" --project "$ide_project" --seen "$nonce" --deadline 43200`
- [ ] state the one rule covering every remote POST — the startup claim, the acknowledgement and the
      answer all go to `$base_url` with the token on stdin — and delete the two promises listen mode
      makes today: that it acts on nothing published before it started, in the frontmatter and in the
      section, and that re-arming is a manual choice because one watcher owns the project
- [ ] check by hand in the scratchpad, with `HOME` overridden, that the changed startup block runs
      both ways: with no published file it prints a launch line whose `--seen` is empty, and with a
      file present it prints the nonce it read out of line 2 of that file
- [ ] check by hand that the section forbids the blunt kill and no longer describes a takeover:
      `grep -nE 'pkill|killall|took over' docs/skill/claude-remarks-review/SKILL.md` returns only the
      lines that forbid those things, and no line tells a session to stop because another watcher took
      over
- [ ] run both hand checks above once each from a clean scratchpad `HOME` - must pass before the next
      task
