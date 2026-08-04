# Claude Remarks — Working Notes

This project builds a plugin for IntelliJ that lets you mark up code with remarks while reading,
then turn them all into one prompt for a Claude Code session.

Phases 1-10 are implemented and covered by unit tests. Phase 10 (the published file and the review's
handoff file merge into one file with one eight-line header; an agent's read of it now goes through
one of two acknowledgement routes, a review's own session id or a published batch's nonce; Publish
All Pending is renamed to Publish Unread and carries every remark that is not yet `READ`; Publish
answers a waiting review directly, so the three separate Send controls are gone; and a background
watcher script, launched by the skill, is what lets both review mode and the published-file modes
wait past the ten-minute cap on a foreground shell call) is complete, including task 13's version
bump to `0.7.0` and this final documentation sweep.

**The plugin has now been seen running in a real IDE, on version `0.6.0`.** A review was started
over the endpoint, the waiting banner appeared, the file the request named opened, remarks were
written including sub-line ones with their markers, Send to Claude Code handed them over, and the
read acknowledgement came back. Separately, the remote path was proven end to end between two
machines: a tunnel carried the requests, the token was accepted, `start` was accepted, the banner
appeared in the IDE on the far side, a fetch carried the content back across the tunnel, and the
acknowledgement was accepted. The published file it carried had correct sub-line markers.
That closes the one gating check phases 6 to 9 all owed — whether any of this works outside the
tests at all — and no others. **Still unchecked by hand:** the markdown preview entry point,
dragging a remark onto a bucket, phase 7's scheduled deadline and its diff opening, and everything
phase 10 itself built. The one build ever installed on either machine when those checks ran was
`0.6.0`, which predates phase 10, so none of phase 10's own hand checks — listed in section 8 of
`docs/plans/completed/20260805-claude-remarks-phase10.md` — have been run either.

What has and has not been in front of a real IDE otherwise, per phase: **phase 6's seven security
hand checks were run in a real IDE before 0.3.0 was released**, and phase 5's commit stamp was
checked in a real IDE too. The `runIde` checks in the phase 1-2, phase 3-4 and phase 5 plans were
skipped in the autonomous sessions that did that work, so for those treat "it works" as "the tests
pass" until someone runs the hand checks at the end of the plan. **Phase 7's matter most of what is
still open**: a second delivery signal, a scheduled deadline, and a diff opened over VCS all depend
on platform behaviour no automated test in this project reaches. See `docs/claude/design.md` for
exactly which. **Phase 8 owes hand checks too, and it needs something no earlier phase did: a second
machine.** A tunnel, an `sshd`, and an agent session on the far side of it are needed to check the
remote path at all. The gating run above closed the fetch action's own remote-path answers, but not
phase 8's fuller list. Section 13 of `docs/plans/completed/20260803-claude-remarks-phase8.md` lists
all of them, split by which group needs the second machine. **Most of phase 9's hand checks have
still not been run.** Task 1 of its plan owed three of its own, in its own checkboxes: whether the
plugin loads at all, whether a sub-line remark's markers land in the right place, and whether the
grey row and faded gutter icon are visible — the gating run above answers the first two of those, for
sub-line markers specifically, but not the third. Group five owes checks nobody can automate at all:
whether the Claude Remarks item appears in a running preview's right-click menu, whether a real
browser selection reaches Kotlin as the right character range, and whether the plugin still loads
cleanly with the markdown plugin disabled. Section 12 of
`docs/plans/completed/20260803-claude-remarks-phase9.md` lists the whole set, split by which of them
also needs a second machine. Select lines, press `Ctrl+Alt+Shift+R`
(or use the "Add Claude Remark" intention through Alt+Enter), type a note, optionally pick a tag and a
severity level, and press Enter. A gutter icon appears on the marked lines and follows the code as
you keep editing. `Cmd+Ctrl+Shift+Space` in the box (`Ctrl+Alt+Shift+Space` off macOS) inserts a
class name from the project. The tool window lists every remark as a tree grouped by file, with a
General group at the top for a remark about the whole change and a bucket level above the files once
any remark is put in one; right-click a row for the severity and bucket menu. Press Add General
Remark in the toolbar to write a remark that is not about any one file; it always shows up in the
General group, whatever bucket it also carries. A remark can also be written from the rendered
markdown preview instead of from the source: select words there, right-click, and pick Add Claude
Remark, and it points at the same characters behind the selection. This needs the Markdown plugin,
which every JetBrains IDE bundles by default; with it turned off, only this one entry point is
missing and everything else works as before. Press Publish Unread in the tool window to turn every
remark that is not yet `READ` into one markdown prompt on the clipboard, and also to write the same
prompt, with an eight-line header on top, to one file under `~/.claude-remarks/` that a Claude Code
skill can read on its own, with no review ever started; a balloon says how many remarks and files.
Published remarks turn gray rather than disappearing, so Publish Selected can send them again if the
paste went to the wrong place, and publishing a remark that was already read hands it over again the
same way. Clearing (Clear Handed Over, Clear All) archives to a history file in the IDE configuration
directory before it removes anything. If a Claude Code skill has started a review, a banner reads
"Claude Code is waiting: <label>" above the tree, and pressing Publish — Publish Unread or Publish
Selected, whichever is in reach — both hands the remarks to the clipboard as always and answers the
waiting review in the same file the published remarks already go to; the banner then says the review
is waiting to read them. There is no separate Send control any more: since phase 10, a publish is
how a waiting review gets answered. See "The Shared Review Session" below for how the IDE finds the
skill and hands the remarks back.

For the design — how anchoring, the gutter, the change notification, severity and buckets, the
commit stamp, the history file, the publish pipeline, the published file, the phrase a remark points
at, a remark about no file, and the shared review session work. See `docs/claude/design.md`.

**Phase 5 is built.** It added a severity level and named buckets to a remark, tag chips with Alt
keys in place of the old tag drop-down, a commit stamp read straight out of `.git`, a history file
that cleared remarks are archived to instead of deleted, and a keystroke that inserts a class name
into the remark text. One specific automated-dispatch idea was dropped before it was built: a
pluggable `Dispatcher` interface, a tmux pane, a file inside `.idea/`. See `docs/claude/design.md`,
section "The Publish Pipeline" (called "The Copy Pipeline" until phase 9 renamed it), for why. That
idea stays dropped. Phase 6 below does not revive it.

**Phase 6 is built.** It adds a different, simpler automated path next to the clipboard, never
instead of it: a Claude Code skill can ask a running IDE to hold a review open through the IDE's
own built-in HTTP server, the person answered by pressing Send to Claude Code in the tool window
until phase 10 replaced that button with a plain publish, and the remarks reach the skill through a
file both sides agree on. The plugin works exactly as it
did before with no skill installed and nothing listening. See `docs/claude/design.md`, section "The
Shared Review Session", for the whole design, and `docs/ideas.md` for the reasoning this carries
forward from before it was built.

**Phase 7 is built.** It closes the gap between "the IDE wrote a file" and "the agent actually read
it." Rejecting a review in the banner now writes that decision to the handoff file and clears the
review — the link is called Reject, not Cancel — instead of only closing the banner while the skill
waits out its full timeout. A review carries a phase, `Waiting` or `Sent`: sending writes the file
and records which remarks it wrote, but does not mark them read; only a `read` acknowledgement from
the skill does that, over a second endpoint action, `POST /api/claude-remarks/ack`. The skill also
declares how long it will wait, and the IDE clamps and enforces that deadline itself, so a killed
or abandoned session does not leave a stale banner and a live Send button on screen forever. A
review request that names files with a local change now opens one real diff over just those files,
through `ShowDiffAction`, instead of a plain editor per file — which also means a remark on the
revision side of a diff is now refused, with a sentence pointing at the working copy, rather than
stored with line numbers that described a different revision. See `docs/claude/design.md`, section
"The Shared Review Session", for both new subsections, and `docs/ideas.md` for the ideas this
carries forward.

**Phase 8 is built.** It lets a Claude Code session on another machine read remarks too, over an SSH
tunnel the person sets up by hand. The IDE's built-in server gains a third action,
`POST /api/claude-remarks/fetch`. It reads the waiting review's handoff file and returns the content
in the response body, instead of a path. A path on the IDE machine means nothing to an agent on a
different machine. An HTTP response body crosses the tunnel the same way any other response does.
The fetch changes nothing: no remark is marked read, no state moves. The `read`
acknowledgement is still the only thing that marks remarks read, so the fetch can be repeated as
often as the skill's poll needs, and a lost response only costs one retry. Fetching a review that
ended by rejection still works. The service now remembers the most recently ended review's output
path, one review at a time. A rejection is written into the handoff file, and then the review is
cleared. Without this, a fetch after that point would answer "nothing is waiting", and a remote
agent could not tell a rejection from a timeout. A response over one megabyte is refused
rather than truncated, because a markdown prompt cut in the middle looks complete to a model reading
it. The skill (`docs/skill/claude-remarks-review/SKILL.md`) now takes four connection values: host,
port, token and the repository path as the IDE machine sees it. It keeps one wait loop for both
the local case and the remote case, switching only on how it checks whether the remarks are ready.
Nothing about the security model changed: the built-in server only binds `127.0.0.1`, so a tunnel is
the only way in. `isHostTrusted` skips the platform's own Host check entirely, so that check was
never what protected this endpoint. The only gate is the plugin's own token check, plus the refusal
of any request carrying `Origin` or `Referer`. See `docs/claude/design.md`, section "The Shared Review Session", subsection
"Reaching an agent on another machine", for the whole design, and `docs/ideas.md` for the reasoning
this carries forward.

**Phase 9's group one is built.** A remark now has three states instead of two: `PENDING`,
`PUBLISHED` and `READ`, not `PENDING` and `SENT`. `PUBLISHED` means handed to a channel that cannot
confirm a read: the clipboard, or the published file below. `READ` means an agent said it actually
read the remarks — since phase 10, over either of two acknowledgement routes, both keyed to
something the IDE itself minted; see the phase 10 paragraph below. Only an agent's own
acknowledgement can produce `READ`; publishing, however many times, only ever produces `PUBLISHED`.
The action people
press is now called Publish, not Copy. `ClaudeRemarks.CopyAll` keeps its id, because `README.md`
promises that id will not be renamed, but the button, the menu entry and the class are all Publish
now. Publishing also writes the same rendered prompt, with a small dated header on top, to a file
under `~/.claude-remarks/<hash of the project's identity — the git top level, or the project base
path outside a git repository>.md`, overwritten on every publish, so a Claude
Code skill can read published remarks on its own schedule with no review ever started;
`docs/skill/claude-remarks-review/SKILL.md` gained a second mode that reads it. Two behaviours in the
publish pipeline have no automated test at all: a failed published-file write after the clipboard
already succeeded, and a project root that fails to resolve. Both still mark the remarks published,
correctly, and both are only checkable by hand. See `docs/claude/design.md`'s Known Issues entry "a
failed published-file write leaves the previous file in place". See `docs/claude/design.md`, sections
"The Publish Pipeline", "The three states, and why published is not read" and "The published file",
for the whole design.

**Phase 9's group two is built too.** A sub-line remark now stores the exact words between its two
columns, not only the columns themselves: `RemarkState.phrase`, filled by `anchor/Anchoring.kt`'s
`phraseAt` when the remark is written. Resolving a remark looks for that phrase before falling back
to the stored columns: `resolveWithPhrase` finds it again inside the line it resolved to, or, when
the line resolve orphaned, on a nearby line the text reflowed onto. That last case is the one thing
this group buys that a plain line resolve could not do at all. The tree row and the gutter tooltip
both show the sub-line range this way, one line as `9:12-38`, across lines as `9:12-11:5`, and the
tooltip also shows the phrase itself. The history file records both, the phrase written indented
under the heading the same way the remark text already is. See `docs/claude/design.md`, section
"The phrase a remark points at", under "The Anchoring Design", for the whole design.

**Phase 9's group three is built too.** A remark can now be about the whole change instead of one
file. Press Add General Remark in the tool window toolbar, the only entry point for it, on purpose:
the tool window is where a person is looking at remarks rather than at code, which is where a
thought about the whole change gets written. Such a remark carries no path, no line range and no
code snippet. The prompt renderer gives it its own `## General` section at the very top, with no
code block at all, the same shape the renderer used to reserve for an orphan, the remark whose code
could not be found; splitting it out before that branch runs is what keeps a deliberate remark from
reading as a broken one. The tree groups it under its own General group at the very top too, above
the buckets, and it stays there even when it also carries a bucket: a general remark is about the
whole change, so the top of the tree is where it should be read, worth the cost of ignoring its
bucket for grouping. The resolver's `isAboutNoFile` is the one thing that changed there: such a
remark used to be refused as an orphan with no code, and now resolves as itself instead. See
`docs/claude/design.md`, section "A Remark About No File", for the whole design.

**Phase 9's group four is built too.** A file row in the tree now shows the file name in bold first,
with the shortened directory in grey after it, instead of the whole path. A deep directory is
shortened to its last two segments with a leading ellipsis rather than being cropped from the right,
which used to lose the file name itself. A remark, several selected remarks, or a whole file or
bucket group can also be dragged onto a bucket row to move them there, or onto `(no bucket)` to clear
it; nothing changed in how a bucket gets created, `Move to Bucket…` in the right-click menu still
does that by name. A `Published` group above the buckets was designed and deliberately left unbuilt;
see `docs/ideas.md` for the condition that would make it worth building.

**Phase 9's group five is built too.** A remark can now be written from IntelliJ's rendered markdown
preview: select words there, right-click, pick Claude Remarks, and the remark points at the exact
characters in the `.md` source behind the selection, not at the whole line.
`src/main/resources/dev/sasha/clauderemarks/preview/claude-remarks-preview.js` is the script injected
into the preview page, posting the browser's selection on every change.
`preview/PreviewRemarkExtension.kt` is the `MarkdownBrowserPreviewExtension` that receives it and
keeps the last one in `preview/PreviewSelectionService.kt`. `preview/PreviewSelection.kt` is the pure
arithmetic that turns a browser selection into a character range, by searching for the highlighted
text inside the coarse range the page can name. `action/AddPreviewRemarkAction.kt` is the entry point
in the preview's right-click menu, reading only what is already stored and asking the page nothing.
Two facts about this a future session should not have to re-derive by reading the platform again.
First, the pipe handler that receives the browser's message does **not** run on the EDT: it is called
inline from a native CEF callback, so it may only parse the message it was handed, and everything
that touches a `Document`, a project service or Swing has to hop to the EDT itself, with
`invokeLater`. Second, `md-src-pos` is written on every tag the markdown generator opens, not only on
the ones worth selecting, and that is also true of a Mermaid fence: inside a drawn diagram there is
no per-line span the way there is inside a paragraph, so the coarse range covers the whole diagram,
and whether a remark lands on one node's label or on the whole diagram depends only on whether
`narrowToSelection`'s one `indexOf` search finds the label's text inside that whole-fence source.
`plugin.xml` declares the markdown plugin as an optional dependency, `org.intellij.plugins.markdown`,
with its own config file, `claude-remarks-markdown.xml`, so the plugin still loads with the tool
window intact when the markdown plugin is turned off. `build.gradle.kts` subtracts
`EXPERIMENTAL_API_USAGES`, not only `INTERNAL_API_USAGES` as before, from what `verifyPlugin` treats
as a failure, because the three `MarkdownHtmlPanel` getters this group calls are the published route
to the preview's pipe and all carry `@ApiStatus.Experimental`. **None of this has been seen running
in a real IDE.** Whether the menu item actually appears in a running preview, whether a real browser
selection reaches Kotlin as the right range, and whether the plugin truly still loads cleanly with
the markdown plugin disabled, are all still owed as hand checks. See `docs/claude/design.md`, section
"A Remark on the Rendered Preview", for the whole design, and section 12 of
`docs/plans/completed/20260803-claude-remarks-phase9.md` for the full hand-check list.

**Phase 10 is built.** The published file and the review's handoff file were two files answering two
contracts; now there is one file, one eight-line header, and one of two ways to acknowledge it. The
header (`PublishedHeader` in `review/PublishedRemarks.kt`) always carries a fresh `nonce`, so an
acknowledgement can name exactly the batch it read, plus `review:` and `label:` fields filled in
whenever the batch also answers a waiting review, and a `rejected:` field, since a rejection is now
just another batch written to the same file. `review/PublishedAck.kt`'s `PublishedBatchService`
remembers the last sixteen published batches in memory and answers `ok`, `already-read` (naming who
got there first), or `unknown-batch` to a `POST /api/claude-remarks/published-read` request carrying
a nonce — the second acknowledgement route next to the review's own `ack`, and the reason guard 6
below now names two files instead of one. Publish All Pending is renamed Publish Unread and now
filters on "not yet `READ`" rather than "still `PENDING`", so publishing again after an
acknowledgement, or while nothing has ever been acknowledged, is the ordinary case rather than a
refused one. **Publishing is now how a waiting review gets answered, and the three controls that used
to do that — the banner's "Send remarks" link, the toolbar's "Send to Claude Code" button, and the
Tools menu's `ClaudeRemarks.SendToWaiting` action — are gone.** `answerWaitingReview` in
`review/ReviewLifecycle.kt` replaces `sendToWaitingReview`, called from inside the publish pipeline right
after the file write succeeds; `sendToWaitingReview`, `canSend` and `SendReviewAction` are deleted.
The banner is two lines now: "Claude Code is waiting: <label>" and "Publish to answer, or Reject."
Rejecting also writes into the merged file — a batch with `remarks: 0` and `rejected: yes` — instead
of into a directory the review owned on its own; that per-review temp directory is gone entirely,
along with `WaitingReviewState.outputPath` and `endedOutputPath`, since a fetch or a published-read
now always resolves the one predictable path under `handshakeDir()` rather than a path that had to be
handed back in a response first. The skill gained a background watcher script,
`docs/skill/claude-remarks-review/watch-remarks.sh`, launched once and read for its exit code and
its stdout, which is what lets both review mode and the two published-file modes (a one-shot read,
and an opt-in listen mode that waits for the next batch) wait past the ten-minute cap a foreground
Bash call carries — the same reason the skill's own wait loop used to have to poll inside one long
foreground call, capped at the plan's declared 1800 seconds in name only. The skill can also now
remember a remote IDE's four connection values (host, port, project path, token) across runs, through
`docs/skill/claude-remarks-review/remote-config.sh`, instead of the person retyping all four every
time. See `docs/claude/design.md`, sections "The three states, and why published is not read", "The
published file" and "The Shared Review Session", for the whole design.

## Rules that must not break

1. **The anchoring module stays free of the platform.** `anchor/` is pure Kotlin, which is what
   keeps its tests running in milliseconds.

   ```bash
   grep -rn "com.intellij" src/main/kotlin/dev/sasha/clauderemarks/anchor/   # must find nothing
   ```

2. **The markdown renderer stays free of the platform too.** `render/PromptRenderer.kt` takes data
   classes and returns a string, the same shape as `anchor/`, for the same reason: no fixture,
   tests in milliseconds.

   ```bash
   grep -rn "com.intellij" src/main/kotlin/dev/sasha/clauderemarks/render/PromptRenderer.kt   # must find nothing
   ```

3. **`store/RemarkEdits.kt` holds the only eleven functions that touch a remark.**
   `RemarkStore`'s own mutators stay public, and `RemarkEdits.kt` sits in the same package, so
   nothing but this check keeps the claim true. A caller that reaches past the ten that change a
   remark mutates the store without telling the gutter or the tool window to redraw. The grep
   allows through the one read-only method by name, `all()`, rather than listing the mutator names
   by hand: a hand-picked list has to be edited every time a mutator is added, and forgetting is
   silent — the guard keeps passing while it stops covering the new function. That is exactly
   what happened here: phase 5 added `setSeverity`/`setBucket`, and the old six-name list never
   saw them. The count moved from eight to nine in phase 9's group one, when `markRemarksSent`
   split into `markRemarksPublished` and `markRemarksRead`, and `clearSentRemarks` was renamed to
   `clearHandedOverRemarks`. That is a rename, not a new function, so it did not change the count on
   its own. Group three's `addGeneralRemark` moved it from nine to ten mutators. The eleventh
   function in the file, `notifyRemarksChanged`, changes nothing itself. It is what every one of the
   ten calls to announce the change. It is counted here too, because it is public, it lives in this
   file, and this line has to match what a reader finds by opening the file and counting.

   ```bash
   grep -rn "RemarkStore\.getInstance([^)]*)\." src/main/kotlin --include='*.kt' \
     | grep -v RemarkEdits.kt | grep -v "\.all()"   # must be empty
   ```

   The glob has to be quoted. Unquoted, zsh expands `*.kt` itself before `grep` ever runs; with no
   match in the current directory it fails the whole line with "no matches found" before the
   pipeline starts, and that prints nothing. Nothing printed looks the same as an empty, passing
   result.
   Checked directly: `zsh -c` with the bare form fails that way, the quoted form runs.

   Test code is outside this one on purpose: fixture-backed test classes call
   `RemarkStore.getInstance(project).clear()` in `setUp` to clear the shared light-fixture project
   between test classes.

   **Two ways past it, named rather than patched.** The grep is a line-based text search, so it does
   not see either of these:

   ```kotlin
   val store = RemarkStore.getInstance(project)   // no dot after the call
   store.setBucket(setOf(id), "x")                // this line never says RemarkStore

   project.service<RemarkStore>().setBucket(...)  // never says getInstance either
   ```

   A wrapped chain hides the same way, and any line that also contains `.all()` is dropped whole by
   the third filter. Do not grow the pattern to chase these: the rule's own argument is that a guard
   which quietly stops covering things is the failure being fixed, and a cleverer regex is a guard
   nobody can reason about. Naming the holes is the honest version. If a bypass is ever found in a
   review, the fix is to move the call into `RemarkEdits.kt`, not to widen the grep.

4. **No code ever writes to a source file.** The whole point of the plugin is that the working tree
   stays clean.

   ```bash
   grep -rnE "WriteCommandAction|WriteAction\.|runWriteAction|insertString|replaceString|deleteString|[Dd]ocument\.setText|setBinaryContent" src/   # must find nothing
   ```

   A bare `setText(` is deliberately not in that pattern: it is also `JLabel.setText` and
   `JTextField.setText`, so a guard built on it fires on ordinary UI work and gets deleted at
   exactly the moment it would protect something. Every real write instead needs a write action
   (the first three alternatives) or one of the document and file mutators (the rest). Checked
   both ways: the pattern stays quiet on a file full of Swing `setText` calls, and it does catch
   `document.setText(...)`, `doc.insertString(...)` and `WriteCommandAction.runWriteCommandAction`.

5. **The review endpoint never touches the VFS, Swing, or `invokeAndWait`.** `execute` in
   `review/ReviewRestService.kt` runs on a netty IO thread, which is neither the EDT nor a thread
   holding any IntelliJ lock. That is the most fragile invariant phase 6 adds, and a paragraph in a
   plan file does not outlive the plan, so it gets a guard here instead.

   ```bash
   grep -rnE "invokeAndWait|projectRoot\(|FileEditorManager|VfsUtil|SwingUtilities" \
     src/main/kotlin/dev/sasha/clauderemarks/review/ReviewRestService.kt   # must be empty
   ```

   `toRealPath()` is deliberately fine inside `execute`: it is a plain `java.nio` filesystem call,
   never a call into the VFS. `projectRoot(project)` is not fine there, because it hands back a
   `VirtualFile` — which is why the file opening a review request can trigger lives in its own
   file, `review/OpenReviewFiles.kt`, and calls `invokeLater` rather than `invokeAndWait`. `execute`'s
   own KDoc deliberately does not spell out any of the five names above: this grep is line-based and
   cannot tell a comment from code, so an explanatory comment naming them would trip the guard it is
   explaining.

   **Phase 7 hit the same trap and it is still live.** The `ack` action's consequences — marking a
   remark read, showing a balloon, live in `review/ReviewLifecycle.kt`, not in `ReviewRestService.kt`,
   for exactly this rule. The comment in `ReviewRestService.kt` that explains why says "the file that
   owns the editor side" and names `review/ReviewLifecycle.kt` by path, and does not spell out any of the
   five forbidden symbols, even to say they are absent.

   The fetch handler, `handleFetch`, also reads a file inside this class, through `readPublished`
   (renamed from `readHandoff` in phase 10, once the review's own handoff file and the published file
   became the same file). Plain `java.nio` calls are what make that allowed, the same reason
   `toRealPath()` is allowed above. The comment trap is still live: the grep is line-based, so a
   comment naming any of the five forbidden symbols would trip it, even to say they are absent.

6. **Only `store/RemarkEdits.kt`, `review/ReviewLifecycle.kt` and `review/PublishedAck.kt` may call
   `markRemarksRead`.** `READ` means an agent said it read the remarks. There are, since phase 10,
   two routes that can say so, and both are answers to something the IDE itself minted: a `read`
   acknowledgement over `POST /api/claude-remarks/ack`, keyed to a review session and handled by
   `reportReviewEnd` in `review/ReviewLifecycle.kt`; and a `published-read` acknowledgement over
   `POST /api/claude-remarks/published-read`, keyed to a published batch's nonce and handled by
   `reportPublishedRead` in `review/PublishedAck.kt`. The two routes are not independent of each
   other: a batch that answered a waiting review carries that review's session id on the
   `PublishedBatch` record, and `reportPublishedRead` ends that review too, through the same
   `WaitingReviewService.acknowledge` the `ack` action goes through. Without it the remarks would be
   `READ` while the review sat in its `Sent` phase, and the review's own expiry would tell the person
   the agent left without reading remarks the store already says were read. A publish is still
   neither of them, however
   many times it runs — publishing, whether through the clipboard or the published file, can only
   ever move a remark to `PUBLISHED`. Letting anything else call `markRemarksRead` would let a copy
   or a publish quietly claim an agent read remarks it never saw.

   ```bash
   grep -rn "markRemarksRead(" src/main --include='*.kt' \
     | grep -v "store/RemarkEdits.kt" | grep -v "review/ReviewLifecycle.kt" \
     | grep -v "review/PublishedAck.kt"   # must be empty
   ```

   **One way past it, named rather than patched.** The grep is a line-based text search on the
   literal substring `markRemarksRead(`. A method reference, `::markRemarksRead`, or an aliased
   import would reach the function from a third file without ever writing that substring, and the
   grep would not see it. Nothing exploits this today. Following guard 3's own argument above: the
   fix, if this is ever found in use, is to keep the two allowed callers as they are, not to grow the
   pattern chasing every way a call can be spelled.

Every command above must come back empty.

## Project structure

```
src/main/kotlin/dev/sasha/clauderemarks/
  anchor/Anchoring.kt              hashing, capture, the two-pass resolve, plus phraseAt/findPhrase/
                                   resolveWithPhrase for the sub-line phrase (phase 9). No platform
                                   imports.
  anchor/SubLineRange.kt           hasSubLineRange and positionLabel: the one place that decides
                                   whether a column pair is a real sub-line range, and the one place
                                   that writes a position down ("9-9", "9:12-38", "9:12-11:5").
                                   Asked by phraseAt, by markersValid in render/PromptRenderer.kt,
                                   by the tree row and by the history heading. Pure Kotlin, so the
                                   renderer can import it without breaking rule 2 below
  model/RemarkState.kt             the persisted record, RemarkTag (+ its label extension), RemarkStatus,
                                   phrase (the sub-line text between startColumn and endColumn, phase 9)
  store/RemarkStore.kt             @Service project component, state in workspace.xml
  store/RemarkEdits.kt             the ten mutation functions plus notifyRemarksChanged (eleven in
                                   all), the REMARKS_CHANGED topic
  store/RemarkResolver.kt          projectRoot, resolveAll, anchorOf, and isAboutNoFile, which
                                   resolveOne checks before treating a remark with no path as
                                   itself rather than as an orphan
  store/RemarkTarget.kt            relativePathOf, remarkTargetProblem, the diff fallback, and the
                                   refusal for a remark on the revision side of a diff.
                                   fileTargetProblem (phase 9) is the file-only half of that refusal,
                                   split out so the preview's own action can reuse it without a diff
                                   or an editor
  store/ContextFormat.kt           joinContext/splitContext, how context lines are stored
  store/GitHead.kt                 headCommit and gitTopLevel, both off one walk up the tree for
                                   .git. Reads .git directly, no platform import, no Git4Idea
  store/RemarkHistory.kt           historyFile, appendToHistory, renderHistory: the archive, with
                                   a phrase line under a sub-line heading and a plain "(general)"
                                   heading for a remark about no file
  ui/RemarkInputPanel.kt           the popup's panel, the Enter/Shift+Enter keys, the tag chips and
                                   their Alt keys, CLASS_NAME_STROKE to insert a class name
  ui/RemarkActions.kt              remarkChangeActions: the severity and bucket menu, shared by the
                                   gutter icon and the tree
  ui/ClassNameInsert.kt            projectClassNames, chooseClassName: the class-name chooser the
                                   input popup opens on Cmd+Ctrl+Shift+Space (Ctrl+Alt+Shift+Space
                                   off macOS — NOT Ctrl+Space, see CLASS_NAME_STROKE for why)
  ui/RemarksTree.kt                node building: a General group at the very top for a remark
                                   about no file, then buckets, then files, and the tree cell
                                   renderer
  ui/RemarksTreeDnd.kt             the drag wiring beside that node building: the private drag bean,
                                   installDragToBucket, and the node lookup under the pointer. The
                                   decision a drop makes is bucketDropTarget, in RemarksTree.kt
  ui/RemarksToolWindowFactory.kt   RemarksPanel: the tree, the toolbar (including Add General
                                   Remark), self-refresh on REMARKS_CHANGED
  action/AddRemarkAction.kt        the shortcut / popup-menu entry point, selectedLines(), and
                                   openGeneralRemarkInput, the tool window's entry point for a
                                   remark about no file
  action/AddRemarkIntention.kt     the Alt+Enter entry point
  action/AddPreviewRemarkAction.kt the entry point in the rendered markdown preview's right-click
                                   menu (phase 9). Reads only what PreviewSelectionService already
                                   holds, asks the page nothing, and refuses with a dialog rather
                                   than a hint, since there is no editor here to put a hint in
  action/PublishRemarks.kt         publishRemarks(project, ids), the whole publish pipeline, plus the
                                   Tools-menu action (PublishUnreadRemarksAction) that calls it without
                                   the tool window; renamed from CopyRemarks.kt/copyRemarks in phase 9
  editor/RemarkGutterIcon.kt       the placement record, the tooltip, the gutter icon renderer
  editor/RemarkGutter.kt           the project service that keeps gutter icons in step
  editor/RemarkGutterStartup.kt    the ProjectActivity that starts RemarkGutter, and
                                   ReviewHandshakeService
  settings/RemarkSettings.kt       the app-level service and the default prompt header
  settings/RemarkSettingsConfigurable.kt
  render/PromptRenderer.kt         pure Kotlin, zero platform imports. Remarks to markdown, general
                                   remarks first under their own heading and with no code block.
  render/PromptPayload.kt          collectForPrompt and clipboardPayload
  preview/PreviewSelection.kt      SourceRange, PreviewSelection, parseSelectionMessage and
                                   narrowToSelection: what a selection in the rendered markdown
                                   preview is allowed to say, and how it becomes a character range in
                                   the .md source. No platform import, so its tests need no fixture.
  preview/PreviewSelectionService.kt
                                   @Service PROJECT, in memory only: the last selection any preview
                                   reported, with the file url beside it so a reader can compare
  preview/PreviewRemarkExtension.kt
                                   the browser half: the MarkdownBrowserPreviewExtension, its
                                   Provider and its ResourceProvider. Subscribes to one pipe message,
                                   parses it on the browser's callback thread, then hops to the EDT
                                   to narrow the range against the Document and store it
  review/ReviewHandshake.kt        projectIdentity (the ONE function that decides what the plugin
                                   hashes and compares as "this project": the git top level, or the
                                   base path outside a repository), projectHash, handshakeName,
                                   renderHandshake, writeHandshake/deleteHandshake,
                                   the per-run ReviewToken, and ReviewHandshakeService (@Service
                                   PROJECT, Disposable) — the file a skill reads to find this IDE
  review/AtomicWrite.kt            atomicWriteString: temp file beside the target, then rename
  review/PublishedRemarks.kt       PublishedHeader (nonce, publishedAt, commit, remarks, reviewSession,
                                   reviewLabel, rejected) with render()/publishedHeaderOf(), the
                                   private label sanitizer, PUBLISHED_MARKER, publishedName,
                                   writePublished: the one merged file a publish, a review's answer or
                                   a rejection all write under handshakeDir(). Added in phase 9 as a
                                   three-field header; restructured into the eight-line PublishedHeader
                                   in phase 10, when the review's own handoff file merged into this one
  review/PublishedAck.kt           PublishedAckOutcome, PublishedBatch, PublishedAckAnswer,
                                   PublishedBatchService (@Service PROJECT, in memory only, the last
                                   sixteen published batches, @Synchronized record/acknowledge) and
                                   reportPublishedRead: the second acknowledgement route, added in
                                   phase 10, keyed to a published batch's nonce rather than to a review
                                   session
  review/WaitingReview.kt          WaitingReviewState (with its ReviewPhase and deadlineAt/isStale;
                                   outputPath and endedOutputPath removed in phase 10, once the review
                                   stopped owning a directory of its own), StartResult, the pure
                                   startOrConflict, and WaitingReviewService (@Service PROJECT,
                                   Disposable) — at most one waiting review per project, in memory
                                   only, plus markSent, acknowledge and the scheduled expiry
  review/ReviewRestService.kt      the RestService at
                                   POST /api/claude-remarks/{start,ack,fetch,published-read} (the
                                   fourth action added in phase 10): isHostTrusted, execute (dispatches
                                   on the sub-path), clampDeadlineSeconds, readPublished/PublishedRead
                                   (renamed from readHandoff/HandoffRead in phase 10, the merged file
                                   read back with a size cap), handlePublishedRead, and the pure
                                   requestIsAllowed/projectForPath helpers. Rule 5 above governs this
                                   file specifically. handoffFile and handleStart's own filesystem
                                   write are gone in phase 10, since start no longer creates a
                                   directory
  review/ReviewLifecycle.kt        named SendReview.kt until phase 10 renamed it, once the last
                                   thing in it that sent was deleted. answerWaitingReview (replaces
                                   sendToWaitingReview, canSend and SendReviewAction in phase 10,
                                   called from inside the publish pipeline once the file write
                                   succeeds) and waitingReviewForPublish;
                                   rejectWaitingReview (writes into the same merged file through
                                   writePublished since phase 10, no longer its own marker or its own
                                   directory); finishReview and expireStaleReview, the ack's
                                   consequences (marking read, the balloon), kept out of
                                   ReviewRestService.kt by rule 5
  review/OpenReviewFiles.kt        the only file in review/ that touches the VFS or the editor —
                                   opens a real diff over the files that have a local change,
                                   through ShowDiffAction, and a plain editor for the rest
src/main/resources/META-INF/plugin.xml           declares two hard dependencies:
                                                  com.intellij.modules.platform and, since phase 7,
                                                  com.intellij.modules.vcs, for ShowDiffAction, which
                                                  lives in a module jar
                                                  (lib/modules/intellij.platform.vcs.impl.jar), not
                                                  in app.jar. Since phase 9 it also declares one
                                                  optional dependency, org.intellij.plugins.markdown,
                                                  naming the config file below
src/main/resources/META-INF/claude-remarks-markdown.xml
                                                  everything that cannot exist without the markdown
                                                  plugin, skipped whole when it is disabled: the
                                                  browserPreviewExtensionProvider registration
src/main/resources/dev/sasha/clauderemarks/preview/claude-remarks-preview.js
                                                  the script injected into the preview page. Listens
                                                  for selectionchange, walks up to the nearest element
                                                  carrying the position attribute (whose name it reads
                                                  from the page's own meta tag), and posts four
                                                  offsets plus the highlighted text
src/main/resources/intentionDescriptions/AddRemarkIntention/description.html
src/test/kotlin/dev/sasha/clauderemarks/...   mirrors the same packages
```

## Toolchain

- Kotlin 2.1.20, `jvmToolchain(21)`.
- IntelliJ Platform Gradle Plugin 2.18.1, `intellijIdeaCommunity("2025.2")`, `sinceBuild = "252"`.
- Gradle wrapper 9.1.0 (the platform plugin needs Gradle 9). The foojay resolver in
  `settings.gradle.kts` downloads a JDK 21 on the first build, so any JDK 17-25 can start it.
- `kotlin.stdlib.default.dependency = false` in `gradle.properties`: the IDE ships its own Kotlin
  stdlib, and bundling a second copy in the plugin zip is a known source of conflicts.
- `org.intellij.plugins.markdown` is an optional dependency, declared in `plugin.xml` with
  `config-file="claude-remarks-markdown.xml"`, so the plugin still loads with it disabled. Because of
  it, `build.gradle.kts`'s `pluginVerification` subtracts `EXPERIMENTAL_API_USAGES` from
  `verifyPlugin`'s failure level, alongside the pre-existing `INTERNAL_API_USAGES` subtraction: the
  three `MarkdownHtmlPanel` getters the preview extension calls all carry `@ApiStatus.Experimental`,
  and there is no non-experimental route to a preview's `BrowserPipe`.

## Reading the platform

A checkout of IntelliJ Community sits at `~/dev/oss/intellij-community`, shallow, pinned to tag
`idea/2025.2.6.3`. Use it. Grepping it is far cheaper than unzipping jars, and it answers a kind of
question the jars cannot.

Which source to trust for which question:

- **What does this actually do?** Read the checkout. `javap` gives signatures and nothing else, so
  any question about behaviour is unanswerable from the jars. A real example from this project: we
  needed to know whether a diff pane's editor carries the real project file. Guessing said yes.
  `DiffUtil.configureEditor` says no — it sets the editor's file from
  `FileDocumentManager.getFile(content.getDocument())`, the same call that already fails there. Only
  the source settled it.
- **Does this method exist, with this signature?** Check the jars, with `javap` against the exact
  build under
  `~/.gradle/caches/9.1.0/transforms/*/transformed/ideaIC-2025.2-aarch64/lib/`.
  The checkout is tag `2025.2.6.3` and we compile against `2025.2` (build 252.28539.97). Same 252
  line, so this is stable in practice, but the jars are what the code is actually compiled against.

The exact tag `idea/252.28539.97` does exist upstream. It would not fetch into the shallow clone,
and re-cloning for a patch-level difference costs another 1.9G, so the mismatch is accepted and
written down here rather than silently carried.

## Commands

```bash
./gradlew test                              # the whole suite
./gradlew build                             # compile, test, assemble
./gradlew buildPlugin                       # build/distributions/claude-remarks-<version>.zip
./gradlew verifyPluginProjectConfiguration  # after any plugin.xml or build.gradle.kts change
./gradlew verifyPlugin                      # compatibility report against the target IDE
```

Do not run `./gradlew runIde` from an agent session: it starts an interactive sandbox IDE that
never exits on its own.

## Testing

Anchoring (`AnchoringTest`, including phase 9's `phraseAt`, `findPhrase` and `resolveWithPhrase`),
`SubLineRangeTest` (the shared rule: one line needs the end column after the start, across lines the
two columns are never ordered against each other, and the three shapes `positionLabel` prints),
storage round-trips, the resolver helpers (including `isAboutNoFile`), the tree's node-building
(including the General group), the markdown renderer (including the General section, rendered
first with no code block), the settings round trip, `GitHeadTest` (reads real `.git` directories built on disk for
the test, including a worktree, a detached HEAD and packed refs, plus `gitTopLevel` for a directory
below the repository root, for a worktree, and with no repository at all), `RemarkHistoryTest` (the
archive's markdown rendering, and the write itself against a temp file; since phase 9 also the
sub-line position shape in the heading and the phrase written indented under it, and a general
remark's `(general)` heading with no line numbers), `AtomicWriteTest` (the
temp file lands beside the target, not in the system temp directory, and no temp file is left
behind), `ReviewHandshakeTest` (the name, the rendering, the escaping, the owner-only
permissions, and `projectIdentity`: the repository for a project opened below its root, the base
path with no repository, and null for a base path that is missing or unusable), `WaitingReviewTest` (the pure `startOrConflict`: accept, honest-retry reuse, a
same-session retry after the deadline, and conflict, plus `isStale`'s boundary), and
`ReviewRequestTest` (the pure `requestIsAllowed`, `projectForPath`, since phase 7
`clampDeadlineSeconds`, and since phase 8 `readPublished` — renamed from `readHandoff` in phase 10 —
and its size cap), `PreviewSelectionTest`
(since phase 9, `parseSelectionMessage`'s refusals and `narrowToSelection`'s search, including the
cross-line case and the malformed-message case), and `PreviewRemarkProblemTest` (since phase 9, the
pure `previewRemarkProblem`: no stored selection, a stored selection in another preview, and one that
matches) are plain JUnit tests with
no fixture, so they run in milliseconds. The rest
need a light IDE fixture
(`BasePlatformTestCase`, which needs `testFramework(TestFrameworkType.Platform)` in
`build.gradle.kts`) and are slower, because each goes through a real project service, a real
`Document`, or a real markup model: `RemarkStoreServiceTest`, `ResolveAllTest` (stored remarks
resolved against real files, including a path that tries to climb out of the project, and, since
phase 9, that a resolved row carries the phrase's refreshed columns),
`SelectedLinesTest` (the selection line math against a real `Document`), `RemarkEditsTest` (the
ten mutation functions publish `REMARKS_CHANGED`), the key-binding half of
`RemarkInputPanelTest`, `AddRemarkActionTest`, `ActionIdsTest` (pins the two action ids and the
tool window's derived activation id, so a rename is caught rather than silently breaking every
`.ideavimrc`), `RemarkActionsTest` (the severity and bucket menu acts on the ids it is given at
press time, not at build time), `ClassNameInsertTest` (inserting a class name at the caret, and
over a selection), `DiffRemarkTargetTest` (adding a remark from a diff pane: a real
`DiffContentFactory` content standing in for a VCS revision, since a light fixture cannot build a
diff viewer), the renderer-equality half of `RemarkGutterIconTest`, `RemarkGutterTest` (the gutter
service, including that a general remark produces no placement anywhere), `RemarksPanelTest` (the
tool window panel: every file and bucket group ends up expanded, the selection survives a rebuild,
and the Add General Remark button is offered and enabled with no selection and no editor open),
`NavigationLineBaseTest` (pins `OpenFileDescriptor`'s
0-based line argument), the collector half of `PromptPayloadTest`, `PublishRemarksTest` (renamed
from `CopyRemarksTest` in phase 9; since phase 10, that a publish with no ids takes every remark that
is not `READ`, not only `PENDING` ones), `PublishedRemarksTest` (the published file's name and write,
added in phase 9; since phase 10, `PublishedHeader`'s eight-line `render()`/`publishedHeaderOf()`
round trip, the label sanitizer, and the malformed-header cases that read back as null),
`PublishedAckTest` (added in phase 10, fixture-backed: an acknowledgement of a recorded batch marks
its remarks read and answers `ok`; a second session, or the same session twice, gets `already-read`
naming who got there first; an unknown nonce answers `unknown-batch`; only the last sixteen batches
are remembered; and an acknowledgement marks only its own batch),
`ReviewEndpointSmokeTest` (the one test that calls `ReviewRestService.execute` itself, through a
real `EmbeddedChannel`, so the response actually carries a body, plus the ack action's five answers,
the unknown-action refusal, that the deadline the request declares really reaches the review, the
fetch action's answers since phase 8 — `waiting` before a send, `ready` with the whole prompt after
one, that a fetch marks nothing read and leaves the review alone, that a fetch still carries a
rejection's body, `no-review` for a session nothing knows about, `too-large` over the size cap,
`bad-request` for a missing field, and `unknown-project` for a project nothing has open — and, since
phase 10, the `published-read` action's five answers, mirroring `PublishedAckTest` at the HTTP layer),
`OpenReviewFilesTest` (the string-only half of the path
filter: absolute paths and `..` segments are dropped, plus a fixture-backed class for the
diff-or-editor decision, since a light fixture project has no VCS root and every file takes the
plain-editor branch), `ReviewLifecycleTest` (since phase 10: `answerWaitingReview` records what was
published, and a second answer keeps the first batch's ids and says in the balloon that it did not go
to the waiting session — the review's ids must be the ids the agent really got, since the watcher
exits on the first batch and nothing re-arms it; it also says so instead of
claiming a handover once the review already ended, and one test drives the whole chain, two answers
then an `ack read`, asserting only the first batch is READ; `rejectWaitingReview` writes a rejection batch
into the published file rather than into a directory of its own, and a rejection after a publish
still writes nothing and only clears the review; nothing is marked read until the read
acknowledgement, and an abandoned acknowledgement or the deadline both leave the remarks pending),
`PreviewSelectionServiceTest` (since phase 9, fixture-backed for the same
reason: `remember`, `forget` and `current` on the project-level service that holds the preview's last
selection), and `WaitingReviewServiceTest` (fixture-backed, because a
project-level service needs a project: `markSent`, the session it names and its refusal to re-stamp a
review already `Sent`, `acknowledge`,
`expireIfStale`, and that `clear` cancels the deadline task and a stale review is not `current()`;
phase 8's `endedOutputPath` tests were removed in phase 10 along with the field itself, once a fetch
or a published-read started resolving the one predictable published-file path instead of a path the
review had to hand back).

Two more files are checked by hand, not by `./gradlew test`, because this repository's suite is
Kotlin and runs no shell: `docs/skill/claude-remarks-review/watch-remarks.sh` (added in phase 10; each
check is its own run, in the scratchpad directory, covering a deadline timeout, a nonce that has
already changed, a file that appears after the watcher starts, `--require-review`, a malformed
header, and that a second watcher on the same project kills the first) and
`docs/skill/claude-remarks-review/remote-config.sh` (added in phase 10; each check is its own run too,
with `HOME` pointed at a temporary directory, covering `save`/`show`/`forget`, that the token never
appears in any output, permission and validation refusals, and that two repository roots produce two
different stored files).

Every fixture-backed test class that asserts on the whole store clears it in `setUp`, not only in
`tearDown`: the light fixture project is shared across test classes, so remarks left behind by an
earlier class are still there when the next one starts. `ReviewLifecycleTest` and `RemarksPanelTest`
both clear `WaitingReviewService` in `setUp` and `tearDown` for the same reason: the fixture
project is shared between test classes too, and task 6's failure-path test in `ReviewLifecycleTest`
deliberately leaves a review waiting when it finishes, so the next test class to touch the tool
window must not find it still there.

There are no UI-rendering or end-to-end tests. The popup appearing at the caret, the gutter icon
painting, the tree colours, the balloon, and the settings page layout are checked by hand in a
sandbox IDE, not automated.
