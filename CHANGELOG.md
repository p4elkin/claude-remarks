# Changelog

This project was built in ten phases over four days, each one planned in a file under
`docs/plans/` before any code was written. The entries below are the record of those phases,
newest first.

They are development history, not release announcements: nothing here was published to the
JetBrains Marketplace, and a version number only ever means "this is what `build.gradle.kts` said
when that phase finished". Every version and date below is read out of the git history of
`build.gradle.kts`, and every plan section named at the end is a file in this repository.

The design that came out of all this lives in `docs/claude/design.md`, which is kept current with
the code. These entries are how the work happened; that document is what the system now is.

---

## 0.7.0 — 2026-08-05 — phase 10: one file, two acknowledgements

The published file and the review's own handoff file were two files answering two contracts. They
merge into one file with one eight-line header, so an agent's read of it goes through one of two
acknowledgement routes — a review's own session id, or a published batch's nonce — instead of two
separate files each with their own rules.

- Publish All Pending becomes **Publish Unread**, and its filter changes from "still `PENDING`" to
  "not yet `READ`". Publishing again after an acknowledgement is now the ordinary case rather than
  a refused one.
- **Publishing is how a waiting review gets answered.** The banner's "Send remarks" link, the
  toolbar's "Send to Claude Code" button and the Tools menu's `ClaudeRemarks.SendToWaiting` action
  are all gone. `answerWaitingReview` in `review/ReviewLifecycle.kt` replaces `sendToWaitingReview`,
  called from inside the publish pipeline once the file write succeeds.
- A review can only be answered once. A second publish while the same review still waits is still a
  real publish — clipboard and file both — but the review keeps the ids of the batch that actually
  reached the agent, and the balloon says so. The agent's watcher exits on the first batch and
  nothing re-arms it, so telling somebody a second publish had been added to what the agent is
  reading would have been a lie.
- A rejection is now just another batch written to that same file, carrying `rejected: yes` and
  `remarks: 0`. The per-review temp directory the review used to own is gone entirely, along with
  `WaitingReviewState.outputPath` and `endedOutputPath`: a fetch now resolves one predictable path
  instead of a path that had to be handed back in a response first.
- The endpoint gains a fourth action, `POST /api/claude-remarks/published-read`, keyed to a batch's
  nonce. `review/PublishedAck.kt` remembers the last sixteen published batches in memory and answers
  `ok`, `already-read` (naming who got there first) or `unknown-batch`.
- The skill gains a background watcher script, `watch-remarks.sh`, launched once and waited on. A
  foreground shell call is capped at ten minutes; a launched background command is not, which is
  what lets review mode and listen mode wait out a real deadline. It also gains
  `remote-config.sh`, so a remote IDE's four connection values are stored once per repository
  instead of retyped on every run.
- The skill's three modes are settled here: a one-shot read of what is already published, an
  opt-in listen mode that waits for the next batch, and review mode.

After the phase closed, three more fixes landed on how a remark looks:

- The Publish buttons stopped wearing a Copy icon; they carry Upload now, which is what they do.
- The tree and the gutter grey a remark only once it is **read**. A published remark draws at full
  strength, because Publish Unread carries it again.
- Each of the three states gets its own icon, so pending and published are told apart at the start
  of a row. `ui/RemarkStatusLook.kt` is the one place that decides both.

## 0.6.0 — 2026-08-04 — phase 9: three states, phrases, general remarks, drag, preview

Five groups of work.

- **Three states in place of two.** `PENDING`, `PUBLISHED` and `READ` replace `PENDING` and `SENT`.
  Only an agent's own acknowledgement produces `READ`; publishing, however many times, only ever
  produces `PUBLISHED`. Copy All Pending and Copy Selected become Publish All Pending and Publish
  Selected. The `ClaudeRemarks.CopyAll` action id stays as it is, because it is a public interface.
  Publishing also starts writing the prompt to a file under `~/.claude-remarks/`, so a Claude Code
  skill can read published remarks on its own schedule with no review started at all.
- **A sub-line remark keeps the words it points at.** The selected text is stored beside the line
  range, and the anchor searches for it once the plain line resolve orphans — which is the one case
  a line-only anchor could never handle, a paragraph that reflowed. The tree row and the gutter
  tooltip show the sub-line range, and the tooltip shows the phrase.
- **A remark about the whole change, not one file.** Written with the Add General Remark toolbar
  button, rendered first in the published prompt under its own `## General` heading with no code
  block, and grouped at the very top of the tree above the buckets.
- **A file row shows its name first**, with the directory shortened in grey after it. A remark,
  several selected remarks, or a whole file or bucket group can be dragged onto a bucket row to move
  them, or onto `(no bucket)` to clear it.
- **A remark written from the rendered markdown preview.** Select words there, right-click, and the
  remark points at the same characters in the `.md` source. The markdown plugin is an optional
  dependency, so the plugin still loads with it disabled — that one entry point is simply absent.

## 0.5.0 — 2026-08-03 — phase 8: an agent on another machine

A Claude Code session on another machine can read remarks over an SSH tunnel the person sets up by
hand.

- The endpoint gains `POST /api/claude-remarks/fetch`, returning the file's content in the response
  body instead of a path — a path on the IDE machine means nothing to an agent on a different one.
- Fetching marks nothing read. The `read` acknowledgement is still the only thing that does, so a
  fetch is safe to repeat as often as a poll needs, and a lost response costs one retry.
- A response over one megabyte is refused rather than truncated, because a markdown prompt cut in
  the middle looks complete to a model reading it.
- Nothing about the security model changed: the built-in server only binds `127.0.0.1`, so the
  tunnel is the only way in, and the plugin's own token check plus the refusal of any request
  carrying `Origin` or `Referer` are the whole gate.

## 0.4.1 — 2026-08-03 — a remark can point at part of a line

A remark records the columns of the selection, not only its lines, and the published prompt draws
`⟦`/`⟧` markers around the selected characters inside the quoted code.

## 0.4.0 — 2026-08-03 — phase 7: the review tells the truth about itself

- Rejecting a review in the banner writes that decision to the handoff file instead of only closing
  the banner, and the link is called Reject rather than Cancel.
- A remark is marked read only once the skill acknowledges it read the file, not the moment the
  file is written. A review that never gets a reply goes stale on its own deadline, declared by the
  skill and clamped and enforced by the IDE.
- A review request that names files with a local change opens one real diff over just those files,
  through `ShowDiffAction`, instead of a plain editor each. Which also means a remark written on the
  revision side of a diff is now refused, with a sentence pointing at the working copy: its line
  numbers describe the revision, not the file on disk.

## 0.3.0 — 2026-08-03 — phase 6: the shared review session

A Claude Code skill can ask a running IDE to hold a review open for a repository, through the IDE's
own built-in HTTP server. The plugin writes a small handshake file under `~/.claude-remarks/` when a
project opens; the skill reads it to find the IDE, and posts one request. A banner appears above the
tree, and the remarks reach the skill through a file both sides agree on.

The plugin works exactly as it did before with no skill installed and nothing listening. This is an
addition next to the clipboard path, never a replacement for it.

## 0.2.0 — 2026-08-03 — phase 5: severity, buckets, chips, commits, history

- **Severity** — `vibe` / `suggestion` / `should` / `must`, defaulting to `should`. A second axis
  next to the tag: the tag says what kind of remark it is, severity says how strongly to act on it.
  The renderer appends a note explaining the scale below the prompt header on every publish, so
  rewriting the header cannot silently drop what the levels mean.
- **Named buckets**, assigned to a selection rather than to one remark at a time, so a whole reading
  pass moves in one step. The tree grows a bucket level only once any remark actually has a bucket.
- **Tag chips picked from the keyboard**, `Alt+0` through `Alt+4`, replacing the tag drop-down —
  which had made Enter ambiguous, since the plugin's own Enter-submits binding beat the list's.
- **A commit stamp** read straight out of `.git`, with no platform import and no dependency on
  Git4Idea, recorded when the remark is written and never refreshed.
- **A history file.** Clearing archives the remarks about to go to a markdown file in the IDE
  configuration directory before removing anything, and removes nothing if that write fails.
- **`Cmd+Ctrl+Shift+Space`** in the remark box inserts a class name from the project. Deliberately
  not `Ctrl+Space`: Basic Completion is offered even inside a popup, and macOS takes `Ctrl+Space`
  for switching input source.

## 0.1.1 — 2026-08-02 — phases 3 and 4: the editor side and the output side

Phase 3 is creating and viewing remarks without leaving the editor: the input popup, the gutter
icon that follows the code, and the tool window as a tree grouped by file. Phase 4 is turning
remarks into one prompt: the editable prompt header in Settings, the markdown renderer, the publish
pipeline, and the toolbar.

One idea from an earlier brief was dropped here before it was built: a pluggable `Dispatcher`
interface, a tmux pane, a file inside `.idea/`. Publishing already gets a prompt into a Claude Code
session with none of that machinery. `docs/claude/design.md`, section "The Publish Pipeline", has
the reasoning. Phase 6 later added a different, simpler automated path; the dropped idea stays
dropped.

## 0.1.0 — 2026-08-02 — phases 1 and 2: storage and anchoring

The persistent store in `.idea/workspace.xml`, the remark record, and the two-pass anchoring search
that keeps a remark pointed at the right lines as the file changes around it. The Bookmarks API was
considered and rejected: `LineBookmark.line` is a single `Int` and there is no range bookmark in the
provider hierarchy.

---

## What has actually been checked by hand

Almost nothing. The automated suite is green and has been throughout, but a green suite says very
little about a plugin: there are no UI-rendering and no end-to-end tests, and the two shell scripts
the skill ships are not run by `./gradlew test` at all.

**One gating run happened, on version `0.6.0`, and it is the whole of what has been seen working.**
A review was started over the endpoint, the waiting banner appeared, the file the request named
opened, remarks were written including sub-line ones with their markers, the handover reached the
agent, and the read acknowledgement turned the rows grey. Separately, the remote path was proven end
to end between two machines: a tunnel carried the requests, the token was accepted, `start` was
accepted, the banner appeared in the IDE on the far side, a fetch carried the content back across
the tunnel, and the acknowledgement was accepted. The published file it carried had correct sub-line
markers.

Two things that closes, and no more: whether any of this works outside the tests at all, and the
fetch action's own remote-path answers. **Everything phase 10 built is unproven**, because `0.6.0`
predates it — the merged published file, the two acknowledgement routes, publishing answering a
waiting review, the watcher script and the skill's three modes are all in that set, along with the
three appearance fixes that landed after the phase closed. Phase 6's seven security hand checks were
run in a real IDE before `0.3.0`, and phase 5's commit stamp was checked in a real IDE; the `runIde`
checks in the phase 1-2, phase 3-4 and phase 5 plans were skipped in the autonomous sessions that
did that work.

Each plan keeps its own list of what it owes, and those lists are the detail:

| Phase | Hand checks |
| --- | --- |
| 5 | Section 10 of `docs/plans/20260803-claude-remarks-phase5.md` |
| 7 | Section 12 of `docs/plans/20260805-claude-remarks-phase7.md` |
| 8 | Section 13 of `docs/plans/completed/20260803-claude-remarks-phase8.md` |
| 9 | Section 12 of `docs/plans/completed/20260803-claude-remarks-phase9.md` |
| 10 | Section 8 of `docs/plans/completed/20260805-claude-remarks-phase10.md` |

Phase 8's and phase 10's lists need something no other phase does: a second machine, an `sshd`, and
an agent session on the far side of a tunnel.
