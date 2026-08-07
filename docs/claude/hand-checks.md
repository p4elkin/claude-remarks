# Claude Remarks — what is still unchecked by hand

**A hand check is something `./gradlew test` cannot reach.** It needs a person, a running IDE, and in
a few cases a second machine. The suite covers storage, anchoring, resolving, the renderer, the tree's
node building and the endpoint. It cannot say that an icon reads as yellow rather than as green, that
a popup appears at the caret, that a balloon fires, that a highlight survives typing, or that a shell
script does what its own comments claim — `./gradlew test` runs no shell at all.

**An item leaves this list when it has actually been run, not when the phase that added it merged.**
Everything below is owed. A green suite is not evidence for any of it.

This file is the live state. `CHANGELOG.md` is how the project got here, `docs/claude/design.md` is
what the system is, and `CLAUDE.md` is the rules and the map. Keep this one current: when a check is
run, say so here.

## What has been seen running

**The plugin has been seen running in a real IDE exactly once, on version `0.6.0`.** What that run
still proves: the plugin loads at all, the handshake file is found from another machine, the endpoint
accepts a token and works across a tunnel, a publish renders sub-line markers correctly, and an
acknowledgement really does mark remarks read. The rest of that run exercised review mode, which no
longer exists, so it proves nothing about anything that ships today.

Two things have been in front of a real IDE otherwise: phase 6's seven security hand checks, run
before `0.3.0` was released, and phase 5's commit stamp. The `runIde` checks in the phase 1-2, phase
3-4 and phase 5 plans were skipped in the autonomous sessions that did that work, so for those read
"it works" as "the tests pass" until somebody runs the list at the end of the plan.

## What is unproven

**Everything built after `0.6.0` is unproven in a real IDE.** That is the merged published file, the
acknowledgement by nonce, the watcher script and its streaming shape, the skill's three modes, the
whole Ask Claude round trip, the answer nesting, the three question-mark icons, the Open/Done split,
the wrapped rows and the grey metadata line, the preview's two actions and its highlighting, and the
skill install with its settings row, its button and its balloon.

The markdown preview entry point has never been watched running either, in any version.

## Where each list is

Every plan keeps its own numbered list, and those numbers are how other documents point at individual
checks. The numbering below is each plan's own; nothing here renumbers anything.

| what it covers | where the list is |
| --- | --- |
| phase 5 — the commit stamp, buckets, chips | section 10 of `docs/plans/20260803-claude-remarks-phase5.md` |
| phase 7 — the diff opening | section 12 of `docs/plans/20260805-claude-remarks-phase7.md` |
| phase 8 — the remote path | section 13 of `docs/plans/completed/20260803-claude-remarks-phase8.md` |
| phase 9 — the preview, the phrase, general remarks | section 12 of `docs/plans/completed/20260803-claude-remarks-phase9.md` |
| phase 10 — the merged published file, the watcher | section 8 of `docs/plans/completed/20260805-claude-remarks-phase10.md` |
| phase 11 — the answer round trip, twenty-four checks | "Hand checks" in `docs/plans/20260805-claude-remarks-phase11.md` |
| phase 12 — the icon column, the answer nesting, twelve checks | "Hand checks" in `docs/plans/completed/20260806-claude-remarks-phase12.md` |
| phase 13 — the tool window, Open and Done, twenty-one checks | "Hand checks" in `docs/plans/completed/20260806-claude-remarks-phase13.md` |
| phase 14 — the preview highlighting and streaming, fourteen checks | "Hand checks" in `docs/plans/completed/20260806-claude-remarks-phase14.md` |
| phase 15 — the skill install | "Hand checks" in `docs/plans/completed/20260806-claude-remarks-phase15.md` |

⚠️ **Four of those plans have moved into `docs/plans/completed/` and the rest have not.** The paths
above are where each file sits today. When a plan moves, its path here moves with it.

## How the lists relate to each other

⚠️ **A later list supersedes an earlier one wherever the two overlap.** Phase 13's beats phase 12's,
which beats phase 11's — all three rewrote the same tree rows. Phase 13's is the list that matters
most right now: the wrapping, the Open/Done split, the grey metadata line and the two-group batch
summary are all in it, and none of them is reachable by `./gradlew test`.

⚠️ **Phase 12's own thirteenth check is carried forward inside phase 13's list, as its check 13** —
the three question-mark colours in a light theme and in a dark one, and an answer turning its
question green on the gutter. Both are still unrun.

⚠️ **Phase 14's list repeats three of phase 9's preview checks on purpose**: whether the Claude
Remarks entry appears in a running preview's right-click menu, whether a real browser selection
reaches Kotlin as the right character range, and whether the plugin still loads cleanly with the
markdown plugin disabled. Phase 14's preview half builds on code nobody has watched run, so if any of
it turns out not to work, those three are the first place to look.

⚠️ **Phase 7's list is mostly moot, and it is kept rather than deleted so that Sasha decides.** The
machinery it checked is deleted — a second delivery signal and a scheduled deadline are both gone.
Only its diff opening survives, reachable now through the `open` action, which is hand check 10 in the
phase 12 plan. Read it as: one live check, plus a list of checks whose subject no longer exists.

⚠️ **Phase 8's list and part of phase 9's need something no other phase does**: a second machine, a
tunnel, an `sshd`, and an agent session on the far side of it. Each of those two lists is split by
which of its checks needs the second machine, so the local half of each can be run on its own.

⚠️ **Phase 8's list is not closed by the `0.6.0` gating run.** That run answered the fetch action's
own remote-path questions and nothing else on the list.
