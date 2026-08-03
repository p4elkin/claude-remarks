# Resume note: phase 7 planner

Written 2026-08-03, before a deliberate session restart. Delete this file once the plan is reviewed
and execution starts.

## Where things stand

`docs/plans/20260805-claude-remarks-phase7.md` is committed, 1218 lines, 9 tasks. It covers the
delivery acknowledgement work only — the three signals (read, abandoned, stale after a deadline).

**Two requirements arrived after that plan was written and are NOT in it yet.** A planner was told
about both and died with the session before folding either in. Both are committed to `docs/ideas.md`,
so nothing is lost; the plan simply has to grow.

## What has to be folded into the plan

Read these two sections of `docs/ideas.md` in full. They carry the reasoning and the verified API
references, and this note is only an index to them.

1. **"Rejecting a review has to reach Claude Code, and the link should say Reject"** — a subsection of
   "Tell the IDE the remarks were actually delivered". Commit `52ddf21`. This is a defect found by
   hand in a real IDE, not a wish. It should be the phase's first task: it is the one signal that
   fires today and is silently lost.

2. **"Open the real diff for just the files the skill named"** — a top-level section. Commit
   `ff0a9b7`. The user asked for it to ride in this same phase, so do not restructure it into a
   separate phase.

## Decisions already made, so a fresh planner does not reopen them

- **Phase 7 carries two subjects** — the acknowledgement signals and the diff opening. That is the
  user's explicit call.
- **Remote-over-SSH becomes phase 8.** The documentation task must fix every place that currently
  promises phase 7 is the remote work: `docs/ideas.md`, `docs/plans/20260804-claude-remarks-phase6.md`,
  and `docs/skill/claude-remarks-review/SKILL.md`.
- **The old pane of a diff gets the cheap answer in this phase**: refuse a remark there with a clear
  sentence, reusing `remarkTargetProblem`'s existing diff-specific message. Mapping the line through
  the diff's own line mapping belongs in a later phase and must not enter this plan. The user may
  still overrule this.
- **Committed ranges are out of scope** and the plan should say so. `ChangeListManager` only knows
  uncommitted work.
- **No protocol change for the diff opening.** The `files` field already carries what is needed.

## Things that must not be undone

- `WaitingReviewService`'s `@Volatile` field with `@Synchronized` methods, and `current()` left
  deliberately unsynchronized. Both are argued for in `WaitingReview.kt`'s own KDoc. A
  compare-and-set is specifically rejected there because the update lambda creates a temp directory.
- The reject write goes through the existing `atomicWriteString`, and the diff opening goes in
  `review/OpenReviewFiles.kt` — never in `ReviewRestService.kt`, which CLAUDE.md rule 5 greps.
- The zsh `status` hazard, documented in the skill's step 3. This phase adds steps to that skill,
  which is exactly where it would bite again.

## Lesson from the phase 6 planner

Write the plan file EARLY and improve it in place. A planner that holds its research in context and
writes at the end loses everything when the session restarts.
