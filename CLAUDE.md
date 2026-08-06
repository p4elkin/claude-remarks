# Claude Remarks — Working Notes

This project builds a plugin for IntelliJ that lets you mark up code with remarks while reading,
then turn them all into one prompt for a Claude Code session.

Phases 1-14 are implemented and covered by unit tests. Phase 14 (the rendered markdown preview gets
Ask Claude beside Add Claude Remark and highlights the elements remarks point at, and the watcher
script learns to stream, to keep its own seen nonce and to claim a batch itself) is complete,
including the version bump to `0.11.0` and this final documentation sweep.

⚠️ **Read the phase paragraphs below as history, not as a feature list.** Phases 6, 7, 8 and 10 each
built part of a waiting review, and phase 12 deleted it. Phase 5 built buckets and phase 9 built the
drag onto them, and phase 13 deleted both. Each of those paragraphs now says what its phase built and
what became of it. What the plugin does *today* is in "What the plugin does" below and in the phase 13
paragraph.

**The plugin has been seen running in a real IDE exactly once, on version `0.6.0`.** That run
exercised review mode, which phase 12 has since deleted: a review was started over the endpoint, the
waiting banner appeared, the file the request named opened, remarks were written including sub-line
ones with their markers, Send to Claude Code handed them over, and the read acknowledgement came back.
Separately, the remote path was proven end to end between two machines: a tunnel carried the requests,
the token was accepted, a banner appeared in the IDE on the far side, a fetch carried the content back
across the tunnel, and the acknowledgement was accepted. The published file it carried had correct
sub-line markers.

**What that run still proves, now that review mode is gone:** the plugin loads at all, the handshake
file is found from another machine, the endpoint accepts a token and works across a tunnel, a publish
renders sub-line markers correctly, and an acknowledgement really does mark remarks read. What it
proved about `start`, `ack`, the banner and the deadline is history and nothing more.

**Still unchecked by hand:** the markdown preview entry point, and everything phases 10, 11, 12, 13
and 14 themselves built. The one build ever installed on either machine
predates all five. Phase 14's fourteen are under "Hand checks" in
`docs/plans/20260806-claude-remarks-phase14.md`, and ⚠️ several of them are phase 9's own preview
checks listed again on purpose, because phase 14's preview half builds on code nobody has watched
run. Phase 10's own list is section 8 of
`docs/plans/completed/20260805-claude-remarks-phase10.md`; phase 11's twenty-four are under "Hand
checks" in `docs/plans/20260805-claude-remarks-phase11.md`; phase 12's twelve are under "Hand checks"
in `docs/plans/20260806-claude-remarks-phase12.md`; phase 13's twenty-one are under "Hand checks" in
`docs/plans/20260806-claude-remarks-phase13.md`. ⚠️ Those three plans are still in `docs/plans/`, not
in `docs/plans/completed/`: each leaves the move to the harness, and when they move these paths move
with them. ⚠️ **Phase 13's list supersedes phase 12's wherever the two overlap**, and phase 12's
supersedes phase 11's the same way — every one of the three rewrote the same tree rows. Phase 13's is
the list that matters most right now, because the wrapping, the Open/Done split, the grey metadata
line and the two-group batch summary are all in it and none of them is reachable by `./gradlew test`.
⚠️ Phase 12's own thirteenth check is carried forward inside phase 13's list, as its check 13: the
three question-mark colours in a light theme and in a dark one, and an answer turning its question
green on the gutter, are still unrun.

What has and has not been in front of a real IDE otherwise, per phase: **phase 6's seven security hand
checks were run in a real IDE before 0.3.0 was released**, and phase 5's commit stamp was checked in a
real IDE too. The `runIde` checks in the phase 1-2, phase 3-4 and phase 5 plans were skipped in the
autonomous sessions that did that work, so for those treat "it works" as "the tests pass" until
someone runs the hand checks at the end of the plan. **Phase 7's list is mostly moot now**: a second
delivery signal and a scheduled deadline are both deleted, and the diff opening it built is reachable
through the `open` action instead, which is hand check 10 in the phase 12 plan. **Phase 8's fuller
list is still open, and it needs something no earlier phase did: a second machine**, plus a tunnel, an
`sshd`, and an agent session on the far side of it. The gating run above closed the fetch action's own
remote-path answers, not that list. Section 13 of
`docs/plans/completed/20260803-claude-remarks-phase8.md` lists all of them, split by which group needs
the second machine. **Most of phase 9's hand checks have still not been run.** The gating run answers
whether the plugin loads and whether a sub-line remark's markers land in the right place, but not the
grey row and its gutter icon, and group five owes checks nobody can automate at all: whether the
Claude Remarks item appears in a running preview's right-click menu, whether a real browser selection
reaches Kotlin as the right character range, and whether the plugin still loads cleanly with the
markdown plugin disabled. Section 12 of
`docs/plans/completed/20260803-claude-remarks-phase9.md` lists the whole set, split by which of them
also needs a second machine.

**What the plugin does.** Select lines, press `Ctrl+Alt+Shift+R` (or use the "Add Claude Remark"
intention through Alt+Enter), type a note, and press Enter. Press `Ctrl+Alt+Shift+A` instead to ask a
question rather than leave a note: that remark is stored marked as asking for an answer, published on
the spot, and answered back onto the line. A gutter icon appears on the marked lines and follows the
code as you keep editing. `Cmd+Ctrl+Shift+Space` in the box (`Ctrl+Alt+Shift+Space` off macOS) inserts
a class name from the project. The tool window lists every remark as a tree split into two top-level
groups, **Open** and **Done**: a row is Done once it is `READ` or once an answer has come back for it,
and Open until then. Done starts collapsed, and stays open across a refresh once a person opens it.
Inside each side the rows are grouped by file, with a General group above the files for a remark about
the whole change; inside a file, Open is oldest first and Done is newest-processed first. Right-click
a row for the shared menu — Ask for an Answer, and Publish. An answer nests under the question it
answers, as a child row, so an answered question moves to Done and takes its answer with it; a group
called "Answers with no question" sits above Open only when some answer's question is gone. A row
draws up to three lines of wrapped text, keeping any line breaks the person typed, with one grey line
under it carrying the position and any `(moved)`/`(orphaned…)` note. Nothing in the tree can be
dragged. Press Add General
Remark in the toolbar to write a remark that is not about any one file; it always shows up in the
General group of whichever side it is on. A remark can also be written from the rendered
markdown preview instead of from the source: select words there, right-click, and pick Add Claude
Remark or Ask Claude, and it points at the same characters behind the selection. The preview also
shows what is already annotated: the paragraph, list item or heading a remark points at gets a faint
background, in one of two colours, and a question still waiting for an answer looks different from a
plain remark. This needs the Markdown plugin,
which every JetBrains IDE bundles by default; with it turned off, only these two entry points and the
highlighting are missing and everything else works as before. Press Publish Unread in the tool window to turn every remark that
is not yet `READ` into one markdown prompt on the clipboard, and also to write the same prompt, with a
five-line header on top, to one file under `~/.claude-remarks/` that a Claude Code skill can read on
its own; a balloon says how many remarks and files. A published remark stays in the list rather than
disappearing, and it still draws at full strength, because it is still the work the next publish
carries — only once an agent confirms it read the remark does the row turn gray. **The icon says two
things at once**: a plain remark draws a note when written, a neutral tick once handed over and a green
tick once read, while a question draws a question mark coloured by how far it got — neutral pending,
yellow published, green once an answer has come back. So Publish Selected can send a published remark
again if the paste went to the wrong place, and publishing a remark that was already read hands it over
again the same way. Clearing (Clear Handed Over, Clear All) archives to a history file in the IDE
configuration directory before it removes anything. Nothing sits above the tree and nothing in the IDE
ever waits for an agent: a session reads the published file when it is asked to, and the IDE learns
about it only when the acknowledgement arrives.

For the design — how anchoring, the gutter, the change notification, the
commit stamp, the history file, the publish pipeline, the published file, the phrase a remark points
at, a remark about no file, the endpoint a skill talks to, the Ask Claude gesture, what an answer is,
and the Open/Done split with its wrapped rows all work. See `docs/claude/design.md`.

**Phase 5 is built.** It added a severity level and named buckets to a remark, tag chips with Alt
keys in place of the old tag drop-down, a commit stamp read straight out of `.git`, a history file
that cleared remarks are archived to instead of deleted, and a keystroke that inserts a class name
into the remark text. ⚠️ **Three of those are gone again**: phase 11 deleted the severity level and
the tag — field, chips, menu and all — and phase 13 deleted buckets the same way, the field, the
mutator, the tree level and the `Move to Bucket…` entry that was the only way to make one. The commit
stamp, the history file and the class-name keystroke all stay. One specific automated-dispatch idea was dropped before it was built: a
pluggable `Dispatcher` interface, a tmux pane, a file inside `.idea/`. See `docs/claude/design.md`,
section "The Publish Pipeline" (called "The Copy Pipeline" until phase 9 renamed it), for why. That
idea stays dropped. Phase 6 below does not revive it.

**Phase 6 is built, and phase 12 retired most of it.** It added a different, simpler automated path
next to the clipboard, never instead of it: a Claude Code skill could ask a running IDE to hold a
review open through the IDE's own built-in HTTP server, the person answered by pressing Publish, and
the remarks reached the skill through a file both sides agree on. Phase 12 deleted the review and kept
everything underneath it — the handshake file, the atomic write, the endpoint, its three-part security
rule, and the file opening, which is now an action of its own. The plugin works exactly as it did
before with no skill installed and nothing listening, which was true then and is true now. See
`docs/claude/design.md`, section "The Endpoint the Skill Talks To", for what stands and for what review
mode was, and `docs/ideas.md` for the reasoning this carries forward from before it was built.

**Phase 7 is built, and phase 12 deleted all of it but one piece.** It closed the gap between "the
IDE wrote a file" and "the agent actually read it", for a review: rejecting in the banner wrote that
decision into the handoff file — the link was called Reject, not Cancel — instead of only closing the
banner while the skill waited out its full timeout; a review carried a phase, `Waiting` or `Sent`, so
writing the file recorded which remarks it wrote without marking them read, and only a `read`
acknowledgement over `POST /api/claude-remarks/ack` did that; and the skill declared how long it would
wait, which the IDE clamped and enforced itself, so a killed or abandoned session did not leave a stale
banner and a live button on screen forever. None of that exists now: with no banner there is nothing
that can outlive an agent, so there is nothing left to keep honest. **The principle survived the
machinery** — a publish still only ever produces `PUBLISHED`, and only an agent's own acknowledgement
produces `READ`.

The one piece kept whole is the diff opening. A request naming files that have a local change opens one
real diff over just those files, through `ShowDiffAction`, rather than a plain editor each — now behind
`POST /api/claude-remarks/open`. The refusal it forced stays with it: a remark on the revision side of
a diff is refused, with a sentence pointing at the working copy, rather than stored with line numbers
that describe a different revision. See `docs/claude/design.md`, section "The Endpoint the Skill Talks
To", subsection "Opening the files the skill named".

**Phase 8 is built and still stands.** It lets a Claude Code session on another machine read remarks,
over an SSH tunnel the person sets up by hand. `POST /api/claude-remarks/fetch` reads the published
file and returns its content in the response body rather than a path, because a path on the IDE machine
means nothing to an agent on a different machine while an HTTP response body crosses the tunnel the
same way any other response does. The fetch changes nothing: no remark is marked read and no state
moves, so it can be repeated as often as a poll needs and a lost response costs one retry. A response
over one megabyte is refused rather than truncated, because a markdown prompt cut in the middle looks
complete to a model reading it.

Two things have changed since. The fetch used to be keyed to a review session, so a remote session
could only ever read a review's own answer; phase 11 made that field optional and phase 12 deleted it,
so a fetch now carries the project alone and answers with whatever batch the file holds. And the review
machinery it leaned on is gone — the one-review-at-a-time service, the remembered ended-review path,
and the rejection a fetch could still reach after the review had ended. The skill
(`docs/skill/claude-remarks/SKILL.md`) still takes four connection values: host, port, token and the
repository path as the IDE machine sees it. Nothing about the security model changed in any of this:
the built-in server only binds `127.0.0.1`, so a tunnel is the only way in; `isHostTrusted` skips the
platform's own Host check entirely, so that check was never what protected this endpoint; and the only
gate is the plugin's own token check, plus the refusal of any request carrying `Origin` or `Referer`.
See `docs/claude/design.md`, section "The Endpoint the Skill Talks To", subsection "Reaching an agent
on another machine", and `docs/ideas.md` for the reasoning this carries forward.

**Phase 9's group one is built.** A remark now has three states instead of two: `PENDING`,
`PUBLISHED` and `READ`, not `PENDING` and `SENT`. `PUBLISHED` means handed to a channel that cannot
confirm a read: the clipboard, or the published file below. `READ` means an agent said it actually
read the remarks — over one acknowledgement route, keyed to the nonce of the batch it read. There were
two of them between phase 10 and phase 12; see those two paragraphs below. Only an agent's own
acknowledgement can produce `READ`; publishing, however many times, only ever produces `PUBLISHED`.
The action people
press is now called Publish, not Copy. `ClaudeRemarks.CopyAll` keeps its id, because `README.md`
promises that id will not be renamed, but the button, the menu entry and the class are all Publish
now. Publishing also writes the same rendered prompt, with a small dated header on top, to a file
under `~/.claude-remarks/<hash of the project's identity — the git top level, or the project base
path outside a git repository>.md`, overwritten on every publish, so a Claude
Code skill can read published remarks on its own schedule with no review ever started;
`docs/skill/claude-remarks/SKILL.md` gained a second mode that reads it. Two behaviours in the
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
reading as a broken one. The tree groups it under its own General group too, above the file groups —
above the buckets as well, until phase 13 deleted those, and a general remark's own bucket was
deliberately ignored so that it stayed at the top rather than being gathered into one. Since phase 13
the General group sits inside whichever side the remark is on, Open or Done. The resolver's
`isAboutNoFile` is the one thing that changed there: such a
remark used to be refused as an orphan with no code, and now resolves as itself instead. See
`docs/claude/design.md`, section "A Remark About No File", for the whole design.

**Phase 9's group four is built too.** A file row in the tree now shows the file name in bold first,
with the shortened directory in grey after it, instead of the whole path. A deep directory is
shortened to its last two segments with a leading ellipsis rather than being cropped from the right,
which used to lose the file name itself. That half still stands. ⚠️ **The other half is gone.** Rows
could also be dragged onto a bucket row to move them there, or onto `(no bucket)` to clear it, and
phase 13 deleted that with the buckets themselves — `ui/RemarksTreeDnd.kt` whole, `bucketDropTarget`,
and the tree's `DnDAwareTree` superclass, which is a plain `Tree` again. Dragging onto a bucket was
the only drag anywhere in the plugin, so nothing drags in the tool window now. A `Published` group
above the buckets was designed in this same pass and deliberately left unbuilt; phase 13's Open/Done
split supersedes that idea and `docs/ideas.md` now says so.

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
the markdown plugin disabled, are all still owed as hand checks. ⚠️ **Phase 14 built on top of this
anyway** — an Ask Claude action beside the one above and a highlight pushed the other way down the
same pipe — so if any of it turns out not to work, this is the first place to look, and phase 14's own
hand-check list repeats these three deliberately. See `docs/claude/design.md`, section
"A Remark on the Rendered Preview", for the whole design, and section 12 of
`docs/plans/completed/20260803-claude-remarks-phase9.md` for the full hand-check list.

**Phase 10 is built, and phase 12 took the review half back out.** The published file and the
review's handoff file were two files answering two contracts; phase 10 made them one file, one
eight-line header, and one of two ways to acknowledge it. The header (`PublishedHeader` in
`review/PublishedRemarks.kt`) always carries a fresh `nonce`, so an acknowledgement can name exactly
the batch it read. `review/PublishedAck.kt`'s `PublishedBatchService` remembers the last sixteen
published batches in memory and answers `ok`, `already-read` (naming who got there first), or
`unknown-batch` to a `POST /api/claude-remarks/published-read` request carrying a nonce. **That is the
only acknowledgement route now, and the header is five lines**: phase 12 deleted the `review:`,
`label:` and `rejected:` fields together with the `ack` action, the banner, and the code that made a
publish answer a waiting review. Publish All Pending is renamed Publish Unread and filters on "not yet
`READ`" rather than "still `PENDING`", so publishing again after an acknowledgement, or while nothing
has ever been acknowledged, is the ordinary case rather than a refused one.

**Phase 10 also made publishing the way a waiting review got answered, and deleted the three controls
that used to do it** — the banner's "Send remarks" link, the toolbar's "Send to Claude Code" button,
and the Tools menu's `ClaudeRemarks.SendToWaiting` action. Phase 12 then deleted the other end:
`answerWaitingReview`, `waitingReviewForPublish`, the banner and the review itself. The per-review temp
directory phase 10 removed stays removed — a fetch or a `published-read` resolves the one predictable
path under `handshakeDir()` rather than a path handed back in a response first.

The skill gained a background watcher script, `docs/skill/claude-remarks/watch-remarks.sh`, launched
once and read for its exit code and its stdout, which is what lets listen mode wait past the ten-minute
cap a foreground Bash call carries. ⚠️ That is one of the script's two shapes since phase 14, not the
whole of it — see the phase 14 paragraph below for the streaming shape, which prints instead of
exiting. It can also remember a remote IDE's four connection values (host,
port, project path, token) across runs, through `docs/skill/claude-remarks/remote-config.sh`, instead of
the person retyping all four every time. See `docs/claude/design.md`, sections "The three states, and
why published is not read" and "The published file".

**Phase 11 is built.** It carries six changes, and the headline one is that the arrow now points
both ways: a person can ask, and an agent's answer comes back into the IDE.

*Tags and severity are gone.* `RemarkTag`, `RemarkSeverity`, both `label` extensions, both stored
fields, the input popup's chip row with its five Alt bindings, the shared menu's Severity submenu,
`setRemarkSeverity`, and the four-level scale the prompt used to explain are all deleted. The reason
is use: severity was never changed from its default and a tag was never picked, so every remark ever
published shipped as an untagged `should` while the prompt spent a paragraph teaching a scale it
then used one value of. An old element carrying `<option name="severity" value="MUST"/>` and
`<option name="tag" value="BUG"/>` still deserializes — the deserializer skips an `<option>` it has
no property for — and the two options are dropped on the next save. `RemarkStoreStateTest` pins that,
and phase 13 added the same pin for `<option name="bucket" value="x"/>` when it deleted that field.
⚠️ `BaseState` stores every property as an `<option name= value=/>` child element, never as an XML
attribute, so a migration test must be written in `<option>` form. Attribute form parses into a
`RemarkState` with every property at its default, which makes such a test pass against any
`RemarkState` at all — that is what four tests in that class did until phase 11's review rewrote them.

*Publish is in the shared menu, and the toolbar buttons say what they take.* `remarkChangeActions`
offered Ask for an Answer, Publish and Move to Bucket…, in that order, from both the gutter icon's
click menu and the tree's right-click menu — so publishing one remark is one right-click instead of
five steps. (Two entries since phase 13 took Move to Bucket… out.) `ToolbarAction` gained a `description` parameter, and all six buttons carry one that
says what the button takes rather than repeating its own name.

*The Ask Claude gesture.* `Ctrl+Alt+Shift+A`, the `AskClaudeIntention` under `Alt+Enter`, and an
editor popup-menu entry all open the same input box `Ctrl+Alt+Shift+R` opens, then store the remark
with `asksForAnswer = true` and call `publishRemarks` on every question still open — the new one plus
any earlier question that is not `READ` and has no answer yet. The wider batch is what stops a second
ask overwriting the first question's file and stranding it; see design.md, "It publishes on the spot,
and that is the point". `action/AskClaudeAction.kt` holds the action and the intention. The published prompt marks such a remark `— asks for an answer`
in its heading and prints every remark's `id:` on its own line, which is what a session needs to
answer one.

*An answer comes back.* This is the first thing an agent sends that a person reads rather than a
control signal. `POST /api/claude-remarks/answer` carries the batch's nonce, the remark's id and the
answer as markdown; `review/AnswerReceipt.kt` resolves the remark, captures a **fresh** anchor at the
position it resolves to now, and stores an `AnswerState` of its own through `recordAnswer`. An answer
is its own record with its own anchor, so it survives its question being cleared and follows the code
on its own. It gets a row in the tree — in a flat Answers group at the very top until phase 12 nested
it under its own question — a balloon icon on the gutter, and a popup rendering its markdown when
either is clicked. At most one answer per remark:
`putAnswer` upserts on `remarkId`, because a re-publish mints a fresh nonce and a watcher compares
nonces rather than content, so the same question reaching a session twice is ordinary.

*Listen mode stops needing to be babysat.* It claims the batch already sitting in the published file
at startup, by reading the nonce out of line 2 and posting `published-read` for it, and it re-arms
its watcher immediately after each batch instead of waiting to be asked. Several sessions may now
listen to one repository at once, **nothing kills a watcher**, and the batch claim in the IDE is what
decides who acts: a session answered `already-read` names the winner, acts on nothing and keeps
listening. ⚠️ A watcher is stopped only by the pid on the first line of its own repository's `.watch`
file, after checking that the pid is alive and that its command line names the same watched path —
never by `pkill`, `killall` or a `ps | grep` match on `watch-remarks.sh`, because every repository's
watcher on the machine runs a program with that name.

*Listen mode works over the tunnel.* `handleFetch`'s `session` became optional, so a session-less
fetch takes any batch in the file rather than only one whose header names the caller's own review.
That is what lets a remote session claim a plain publish, which `review: none` in the header made
impossible before. A caller that still sent a session got the previous behaviour byte for byte — and
phase 12 finished the job by deleting the field, the header line and the review behind both.

See `docs/claude/design.md`, sections "The Ask Claude Gesture" and "What an Answer Is", for the whole
design, and its Known Issues for the two limits this phase accepts.

**Phase 12 is built.** Three strands, one phase, and the headline one is a deletion.

*Review mode is retired whole.* The `start` and `ack` endpoint actions, the banner above the tree, the
deadline and its scheduled expiry, the review phases, the acknowledgement keyed to a session id and the
rejection are all gone. `review/WaitingReview.kt` and `review/ReviewLifecycle.kt` are deleted, with
three test classes. The published file's header shrinks from eight lines to five — `review:`, `label:`
and `rejected:` go — and `FetchRequest` loses its `session` field, so a readable published file is
always `ready`. In one sentence each, what went with it: `publishRemarks` no longer looks for a review
to answer, so `publishMessage` has one parameter fewer; `PublishedBatch` no longer carries a review
session and `record` takes only the ids; `sanitizeLabel` and `sanitizeControls` are both deleted, so a
control character in `commit` shifts the header and the fetch answers `failed` rather than reporting a
commit nobody has; the watcher script's `--require-review` and `--session` flags are refused with
exit 2 rather than ignored; and the skill's whole `## Steps` review flow, 531 lines, is deleted.
⚠️ **Half of that flag sentence is out of date.** Phase 14 gave `--session` a meaning again — the id a
session claims a batch under — so it is accepted beside `--claim` and still refused with exit 2 on its
own, which is what keeps a launch line written for `0.8.0` failing loudly. `--require-review` is gone
for good. It
went because it was a second protocol for something that already had one — a session id, a deadline, a
phase machine, a scheduled expiry, a banner and its own acknowledgement route, and every one of those
was a place the two sides could disagree about a single handover. Thirteen Known Issues in
`docs/claude/design.md` were struck out by the deletion, and not one of them was fixed.

*One piece is kept, as its own action.* `POST /api/claude-remarks/open` takes a project and a list of
files and opens a real diff over the ones with a local change, plus a plain editor for the rest. That
was the useful half of `start`, and `review/OpenReviewFiles.kt` needed no change at all. It always
answers HTTP 200 with a `status` — `ok` with an `opened` count, `unknown-project`, or `bad-request` —
and ⚠️ `opened` counts the paths that passed the filter, **not** editors that appeared, because the
opening hops to the EDT and the response is written before any of it happens.

*An answer nests under the question it answers.* Instead of a flat Answers group at the top of the
tree, an answer is a child row of its own question, inside the file group that question already sits in,
added expanded. The flat group narrows to hold only answers whose question is gone and is relabelled
"Answers with no question"; `ANSWERS_KEY` keeps its value, so a group collapsed before the upgrade
still matches itself afterwards. Delete on an **expanded** question now takes its answer with it, in one
action and with no dialog, because `leavesOf` recurses into a `RemarkNode` instead of stopping at it —
and `deleteSelected` asks `selectionHidesRows` whether the selection stands for a row that is off
screen, rather than comparing two counts, which was a proxy for the same thing that nesting broke. ⚠️ A
question the person has **collapsed** by hand asks first, exactly like a group row: its answer is off
screen, and `deleteAnswer` archives nothing to the history file.

*The icon column carries two facts instead of one.* A question draws a question mark coloured by how
far it got — neutral pending, yellow published, green once an answer is back — and a plain remark draws
a note, then a neutral tick, then a green tick, replacing the upload mark. The three question marks are
the plugin's own SVGs under `src/main/resources/dev/sasha/clauderemarks/icons/`, loaded by
`ui/RemarkIcons.kt`. The grey `asks` word at the end of a row is deleted, because three things now say
it. ⚠️ `RemarkGutterIconRenderer` carries `asksForAnswer` and `hasAnswer` in its `equals` and
`hashCode`; without that the gutter icon would never change when an answer arrived, and it would look
like it worked, because the tree updates through a different path.

⚠️ **Two decisions in there are the kind somebody later "fixes".** A question that is `READ` with no
answer stays yellow, not green: green is earned by an answer arriving and by nothing else. And the
neutral colour deliberately sits at a different step in the two tracks — `PUBLISHED` on the plain
track, `PENDING` on the question track — because a question's middle state is the one a person waits on
and so earns yellow. Both are argued in `docs/claude/design.md`, section "The icon column carries two
facts"; the nesting is under "Reading an answer: three places". **The skill directory is renamed** from
`docs/skill/claude-remarks-review/` to `docs/skill/claude-remarks/`, so the deployed symlink under
`~/.claude/skills/` has to be recreated by hand.

**Phase 13 is built.** Six changes to the tool window and one to the skill.

*Buckets are deleted whole.* The `bucket` field, `setRemarkBucket`, `RemarkStore.setBucket`, the
bucket level in the tree, the `Move to Bucket…` menu entry and all of the drag and drop went in one
pass — `ui/RemarksTreeDnd.kt` was deleted with the file, and the tree reverted from `DnDAwareTree` to
plain `Tree`. Dragging onto a bucket was the only drag in the plugin, so nothing drags now. An element
carrying `<option name="bucket" value="x"/>` still deserializes and drops the option on the next save,
the same migration phase 11 pinned for `tag` and `severity`. The history file's heading loses its
`— bucket <name>` part, and ⚠️ every entry archived before this phase still carries one on disk,
because that file is append-only.

*The tree splits into Open and Done.* A row is Done once it is `READ` **or** it has an answer, Open
until then. Done starts collapsed, with everything inside it already expanded, so opening it is one
click. ⚠️ An answered question moves to Done at once, even when nothing acknowledged it — decided
knowing the cost, and paid for by the answer staying nested under its question, one click away, and by
Done ordering newest-processed first **inside each file group** (the file groups themselves stay in
path order on both sides — Done is not one newest-first list). ⚠️ A row under Done can still be picked
up by Publish Unread: Done's test is "`READ`, or has an answer" and Publish Unread's is "not `READ`",
and narrowing Publish Unread to match would stop a batch nobody acknowledged being re-sent. The
`DONE_KEY` KDoc says so where somebody would go to "fix" it. "Answers with no question" stays as its
own top-level group above Open, not folded into Done: an answer with no question is a loose end, not
finished work. ⚠️ Every group *inside* a side carries its side's key as a prefix (`open/general`,
`done/file:src/Foo.kt`), because one file can hold rows on both sides and `RemarksPanel` matches
groups by key alone. ⚠️ `expandAll` walks the **model**, not the rows — a collapsed node's descendants
are not rows, so a row walk could never expand anything inside a shut Done — and shuts Done again at
the end with `Tree.collapsePaths`, never `collapsePath`, which collapses the whole visible subtree by
default. It takes a `keepDoneOpen` flag read **before** the rebuild, because `collapsedGroups` records
what is shut and "not shut" also covers "no such group yet".

*`RemarkState` gains `readAt`.* Stamped in `RemarkStore.markRead`, which `RemarkEdits.markRemarksRead`
is the only route to, and guard 6 gates that function to two callers — so it is a single-writer field
by construction. It is 0 for every remark read before this phase, and `processedAt` falls back to
`createdAt` when it is, so the old backlog orders by when it was written instead of collapsing to the
epoch. Inside a file, Open sorts by `createdAt` oldest first and Done by `processedAt` newest first.
`processedAt` is the later of `readAt` and the time the answer nested under the row came back, because
either one alone is enough to put the row in Done — a question answered by a session that never
acknowledged the batch has no `readAt` at all.

*Rows wrap, and the position moves below the text.* `RemarkTreeRenderer` is a `JPanel` on a
`GridBagLayout` stacking `SimpleColoredComponent` lines, not a `ColoredTreeCellRenderer`, which paints
one line by construction. ⚠️ `tree.setRowHeight(0)` is the entire variable-height mechanism —
`platform/todo/.../TodoPanel.java:251` does exactly that and nothing else. `ui/WrapText.kt`'s
`wrapToLines` is the word-break, and it is **ours**: the platform's `MultiLineTodoRenderer` receives
lines that are already split and never wraps anything. It takes a `widthOf: (String) -> Int` rather
than a `FontMetrics`, which is why that file has no `import` line at all and its tests run in
milliseconds. The three-line cap counts lines **of text**; a fourth grey row carries the position, the
`(moved)`/`(orphaned…)` suffix and an orphan-group answer's file name, and is hidden when there is
nothing to put in it, drawn in `GRAYED_SMALL_ATTRIBUTES` so it is still told apart from a `READ` row's
body, which is the same plain grey. The trailing `read` and `published` words are deleted — the icon,
the colour and the Done group already say it three times over. ⚠️ **Four things
`ColoredTreeCellRenderer` gave for free had to be written out by hand**, and each is a real failure
without it: the forced selection foreground (`selectionAdjusted`, or a grey fragment stays grey on the
selection band), the try/catch around the whole render (an exception from a renderer repeats on every
repaint), the accessible name (a bare `JPanel` announces nothing), and a no-op `revalidateAndRepaint`
on each line component. **Resizing the tool window re-wraps the rows**: a `ComponentListener` on the
scroll pane's viewport restarts a one-shot 150 ms `javax.swing.Timer`, which calls
`nodeStructureChanged` to drop JTree's cached row bounds and then re-applies the expand, recollapse and
reselect a rebuild does. ⚠️ Not `TreeUtil.invalidateCacheAndRepaint`, which is
`@ApiStatus.Experimental`.

*A session summarises a batch before acting on it.* `SKILL.md`'s read mode and listen mode both write
a two-group bullet list — things to change, questions to answer — after acknowledging and before any
work, with `none` written under an empty group so a dropped question group cannot be mistaken for no
questions.

See `docs/claude/design.md`, section "Open, Done, and Rows That Wrap", for the whole design, and its
"Buckets" subsection for what was deleted and why.

**Phase 14 is built.** Two halves that share nothing but the version bump. The rendered markdown
preview gets what the editor already had, and the watcher script stops needing a session to keep it
alive.

*The preview has both actions now.* `action/AskClaudePreviewAction.kt` sits beside
`action/AddPreviewRemarkAction.kt` in the preview's right-click menu, registered as
`ClaudeRemarks.AskClaudePreview` in `claude-remarks-markdown.xml` and ⚠️ nowhere else — a markdown id
in `plugin.xml` stops the whole plugin loading when the markdown plugin is off. It differs from the
action beside it in two things: the popup's title, and that the typed text goes through `askClaude`
rather than `addRemark`. Every refusal — no stored selection, a selection from another preview, a file
that does not resolve, a source that moved since the page reported the selection — is written once, in
`openPreviewRemarkInput`, and both actions call it.

*The preview highlights the elements remarks point at.* `preview/PreviewHighlights.kt` is the pure
half, with no `com.intellij` import: it takes plain values, drops an orphan, a remark about no file
and a remark about another file, turns what is left into a character offset in the `.md` source, and
writes a small JSON array of `{offset, kind}`. `preview/PreviewRemarkExtension.kt` pushes that array
down the same pipe the page already posts selections up — once when the extension is created, so a
preview opened on an annotated file highlights with no edit, and again on every `REMARKS_CHANGED`,
with that subscription disconnected in `dispose` beside the pipe's own `removeSubscription`. The page
marks the innermost element whose position range covers the offset, in one of two classes, a plain
remark or a question still waiting, styled by `claude-remarks-preview.css`. The platform serves that
file as an ordinary stylesheet because `MarkdownBrowserPreviewExtension` declares a `styles` list
beside `scripts`. ⚠️ **The highlight can only ever be a whole element, never the exact words**, and a
`MutationObserver` is what keeps it alive through a re-render. Both are argued in
`docs/claude/design.md`, section "A Remark on the Rendered Preview", subsection "Highlighting what a
remark points at, and why it is a whole element" — read it before changing either.

*The watcher can stream, it owns its seen nonce, and it can claim.* `watch-remarks.sh` has two shapes
now and `--stream` picks between them. Without it the script behaves exactly as it always has: one
batch on stdout, exit 0. That is the path every agent other than Claude Code still takes, and it stays
for that reason. With it the script keeps polling and prints **one short line per batch** — the nonce,
and the watched path in `--file` mode — never the batch body, because the harness's `Monitor` tool
turns each printed line into a notification and a monitor that emits too many events is stopped on its
own. Three things follow. The seen nonce lives in the script rather than in the calling session, which
removes the whole class of repeat that came from a session typing a stale nonce back into the next
launch line. The deadline restarts on every batch. And `--claim <base_url>`, with `--session <id>` and
`--project <path>` beside it and the token in `CLAUDE_REMARKS_TOKEN`, makes the watcher send the
`published-read` acknowledgement itself before it prints the line, putting the answer on the end of
that same line. `--claim` needs `--stream`: without it stdout carries the batch and the session claims
it, so a claim from the watcher would take that batch out from under the session reading it. ⚠️ There
are **five** outcome words, not three — `ok`, `already-read <session>`, `unknown-batch`,
`claim-failed <status>` and `claim-failed http <code>` — and the rule for reading them is that `ok`
means act, `already-read` means another session holds it, and anything else means the IDE did not
confirm, so act on the batch and send `published-read` yourself. A failed claim always prints the
nonce anyway: claiming twice is recoverable, a batch nobody hears about is not.

⚠️ **The rule for stopping a watcher did not change and must survive every later rewrite of this
paragraph.** A watcher is stopped only by the pid on the first line of its own repository's `.watch`
file, after checking that the pid is alive and that its command line names the same watched path.
Never by `pkill`, `killall` or a `ps | grep | kill` match on `watch-remarks.sh`: every repository's
watcher on this machine runs a program with that name, and a blunt match stops all of them at once.
Streaming changes nothing about this — a streaming watcher writes the same pid file the exiting one
does.

`SKILL.md`'s listen mode splits into two branches on one shell variable, `listen_monitor`, set on the
first line of a setup block that is otherwise shared. A harness with a `Monitor` tool arms **one
persistent monitor** and never re-arms; every other agent keeps the exit-per-batch loop, unchanged.
The Monitor branch passes no `--owner` and no `perl … setsid` wrapper, because the watcher is the
monitor's own child there. The summarise, answer and wait-for-go steps are written once, in a section
both branches end in, so there is one copy rather than two that can drift. See `docs/claude/design.md`,
sections "The Endpoint the Skill Talks To" — subsection "The streaming shape: the watcher keeps its own
seen nonce and claims for itself" — and "A Remark on the Rendered Preview" for the whole design.

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

3. **`store/RemarkEdits.kt` holds the only twelve functions that touch a remark or an answer.**
   `RemarkStore`'s own mutators stay public, and `RemarkEdits.kt` sits in the same package, so
   nothing but this check keeps the claim true. A caller that reaches past the eleven that change
   stored data mutates the store without telling the gutter or the tool window to redraw. The grep
   allows through the two read-only methods by name, `all()` and `allAnswers()`, rather than listing the mutator names
   by hand: a hand-picked list has to be edited every time a mutator is added, and forgetting is
   silent — the guard keeps passing while it stops covering the new function. That is exactly
   what happened here: phase 5 added `setSeverity`/`setBucket`, and the old six-name list never
   saw them. The exempted names are readers, not mutators, which is why exempting a second one in
   phase 11 costs the guard nothing: `allAnswers()` is what the tree, the gutter and the resolver
   read the answers list through, the same way they read remarks through `all()`.

   How the count got here, since the line has to match what a reader finds by opening the file and
   counting. It moved from eight to nine in phase 9's group one, when `markRemarksSent` split into
   `markRemarksPublished` and `markRemarksRead`, and `clearSentRemarks` was renamed to
   `clearHandedOverRemarks` — that is a rename, not a new function, so it did not change the count on
   its own. Group three's `addGeneralRemark` moved it from nine to ten mutators. Phase 11 deleted
   `setRemarkSeverity`, taking it back to nine, then added `setRemarkAsksForAnswer`, `recordAnswer`
   and `deleteAnswer`, giving twelve. Phase 13 deleted `setRemarkBucket` along with the field it set,
   taking it back down to eleven. They are `addRemark`, `addGeneralRemark`, `editRemark`,
   `deleteRemark`, `markRemarksPublished`, `markRemarksRead`, `setRemarkAsksForAnswer`,
   `recordAnswer`, `deleteAnswer`, `clearHandedOverRemarks` and `clearAllRemarks`. The twelfth
   function in the file, `notifyRemarksChanged`, changes nothing itself. It is what every one of the
   eleven calls to announce the change. It is counted here too, because it is public and it lives in
   this file. `RemarksListener` is a type and `archive` is private, so neither counts.

   ```bash
   grep -rn "RemarkStore\.getInstance([^)]*)\." src/main/kotlin --include='*.kt' \
     | grep -v RemarkEdits.kt | grep -v "\.all()" | grep -v "\.allAnswers()"   # must be empty
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
   val store = RemarkStore.getInstance(project)      // no dot after the call
   store.setAsksForAnswer(setOf(id), true)           // this line never says RemarkStore

   project.service<RemarkStore>().setAsksForAnswer(…) // never says getInstance either
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

   **Every action's consequences live in another file, for exactly this rule.** `published-read`'s are
   in `review/PublishedAck.kt`, `answer`'s in `review/AnswerReceipt.kt`, and `open`'s in
   `review/OpenReviewFiles.kt` — that last one is the file that really does reach the VFS and the
   editor, and it hops there with `invokeLater`. Each handler's KDoc names its file by path and spells
   out none of the five forbidden symbols, not even to say they are absent. This was first written for
   phase 7's `ack` action, whose consequences lived in `review/ReviewLifecycle.kt` until phase 12
   deleted that file.

   The fetch handler, `handleFetch`, also reads a file inside this class, through `readPublished`
   (renamed from `readHandoff` in phase 10, once the review's own handoff file and the published file
   became the same file). Plain `java.nio` calls are what make that allowed, the same reason
   `toRealPath()` is allowed above. The comment trap is still live: the grep is line-based, so a
   comment naming any of the five forbidden symbols would trip it, even to say they are absent.

   **The grep has never needed an edit for an action being added, relaxed or deleted, because it names
   the whole file.** Phase 11 added `answer` and it was covered the moment it was written.
   `handleAnswer` does four things and nothing else: it parses the body, checks the size cap, calls
   `matchProject`, calls one function in another file, and writes the status fields. Building an answer
   resolves a remark against a file, which reaches the VFS, so it could never have lived here. Phase 12
   deleted `start` and `ack`, relaxed `handleFetch` down to one field, and added `open`, and the rule
   held through all three. ⚠️ `handleOpen` is the one handler whose called function genuinely does
   touch the VFS and the editor, which is exactly why that call is one line here and every consequence
   of it is in `review/OpenReviewFiles.kt`.

6. **Only `store/RemarkEdits.kt` and `review/PublishedAck.kt` may call `markRemarksRead`.** `READ`
   means an agent said it read the remarks. There is exactly one route that can say so, and it is an
   answer to something the IDE itself minted: a `published-read` acknowledgement over
   `POST /api/claude-remarks/published-read`, keyed to a published batch's nonce and handled by
   `reportPublishedRead` in `review/PublishedAck.kt`. A publish is not that, however many times it runs
   — publishing, whether through the clipboard or the published file, can only ever move a remark to
   `PUBLISHED`. Letting anything else call `markRemarksRead` would let a copy or a publish quietly claim
   an agent read remarks it never saw.

   ```bash
   grep -rn "markRemarksRead(" src/main --include='*.kt' \
     | grep -v "store/RemarkEdits.kt" | grep -v "review/PublishedAck.kt"   # must be empty
   ```

   **There were two routes between phase 10 and phase 12, and the paragraph that tied them together is
   gone with the second one.** The other was a `read` acknowledgement over
   `POST /api/claude-remarks/ack`, keyed to a review session and handled by `reportReviewEnd` in
   `review/ReviewLifecycle.kt`. The two were not independent of each other: a batch that answered a
   waiting review carried that review's session id on its `PublishedBatch` record, and
   `reportPublishedRead` had to end that review too, through the same `WaitingReviewService.acknowledge`
   the `ack` action went through. Without it the remarks would have been `READ` while the review sat in
   its `Sent` phase, and the review's own expiry would then have told the person the agent left without
   reading remarks the store already said were read. Phase 12 deleted the review, so
   `reportPublishedRead` now marks the batch's remarks read and shows one balloon, and nothing else.

   **One way past it, named rather than patched.** The grep is a line-based text search on the
   literal substring `markRemarksRead(`. A method reference, `::markRemarksRead`, or an aliased
   import would reach the function from a third file without ever writing that substring, and the
   grep would not see it. Nothing exploits this today. Following guard 3's own argument above: the
   fix, if this is ever found in use, is to keep the two allowed callers as they are, not to grow the
   pattern chasing every way a call can be spelled.

7. **Only `store/RemarkEdits.kt` and `review/AnswerReceipt.kt` may create an answer.** An answer is a
   record that a Claude Code session answered a question. There is exactly one route that can say so:
   a `POST /api/claude-remarks/answer` request carrying the nonce of a published batch and the id of
   a remark that batch carried, handled by `reportAnswer` in `review/AnswerReceipt.kt`. Nothing else
   may mint one. Guard 6 makes the same argument about `markRemarksRead`, where there are two routes;
   here there is one.

   ```bash
   grep -rn "recordAnswer(" src/main --include='*.kt' \
     | grep -v "store/RemarkEdits.kt" | grep -v "review/AnswerReceipt.kt"   # must be empty
   ```

   The function is `recordAnswer` and not `addAnswer`, and the name is load-bearing: it upserts on
   `remarkId`, so a second answer to the same question replaces the first, and a function called
   `addAnswer` that silently replaced would be a lie in the one file this guard points at.

   **One way past it, named rather than patched.** The grep is a line-based search on the literal
   substring `recordAnswer(`. A method reference, `::recordAnswer`, or an aliased import would reach
   the function without ever writing it, and the grep would not see it. Nothing exploits this today.
   Following guard 3's own argument: the fix, if it is ever found in use, is to keep the two allowed
   callers as they are, not to grow the pattern chasing every way a call can be spelled.

   **There is deliberately no matching guard for `asksForAnswer`.** That flag is set by the person,
   from two places on purpose — the Ask Claude gesture and the toggle in `remarkChangeActions` — so
   there is nothing for a one-writer rule to protect. Guards 6 and 7 exist because `READ` and an
   answer are both claims about what an agent did, and only an agent's own message may make one. A
   person saying "I want an answer to this" is not a claim about anybody else.

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
  model/RemarkState.kt             the persisted record, RemarkStatus, phrase (the sub-line text
                                   between startColumn and endColumn, phase 9), asksForAnswer
                                   (phase 11) and readAt (phase 13, 0 for a remark never read and for
                                   every remark stored before it). RemarkTag and RemarkSeverity were
                                   deleted in phase 11, bucket in phase 13
  model/AnswerState.kt             the answer record (phase 11): remarkId, the question copied at
                                   answer time, the markdown, answeredAt, and its own nine anchor
                                   fields. Its KDoc argues why it does not share a superclass with
                                   RemarkState
  store/RemarkStore.kt             @Service project component, state in workspace.xml. Two lists
                                   since phase 11, remarks and answers, both @get:XCollection
  store/RemarkEdits.kt             the eleven mutation functions plus notifyRemarksChanged (twelve
                                   in all), the REMARKS_CHANGED topic. markRemarksRead is what
                                   reaches RemarkStore.markRead, which stamps readAt
  store/RemarkResolver.kt          projectRoot, resolveAll, anchorOf, and isAboutNoFile, which
                                   resolveOne checks before treating a remark with no path as
                                   itself rather than as an orphan. Since phase 11 also the pure
                                   StoredAnchor value type and resolveStored, which resolveOne and
                                   resolveAnswers both go through, so the file lookup, the Document
                                   lookup, the no-file case and the five refusals are written once
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
                                   heading for a remark about no file, and since phase 11 an
                                   "### answers" subsection whose markdown is indented so a heading
                                   or a fence inside an answer cannot restructure the document
  ui/RemarkInputPanel.kt           the popup's panel, the Enter/Shift+Enter keys, CLASS_NAME_STROKE
                                   to insert a class name. The tag chips and their Alt keys were
                                   deleted in phase 11, and the panel now returns a plain String
  ui/RemarkActions.kt              remarkChangeActions: the Ask for an Answer toggle and Publish,
                                   shared by the gutter icon and the tree. The Severity submenu was
                                   deleted in phase 11, Move to Bucket… in phase 13
  ui/RemarkStatusLook.kt           RemarkStatusLook: the icon and the text attributes for a row,
                                   shared by the gutter icon and the tree the same way RemarkActions.kt
                                   is, since a status's look used to be decided twice and, after phase
                                   10 changed what the three states mean, was about to be decided
                                   twice again. icon() takes three facts since phase 12 — status,
                                   asksForAnswer, hasAnswer — and picks from two tracks of three
                                   icons; its KDoc argues the two decisions nobody should "fix"
  ui/RemarkIcons.kt                the three question-mark icons the plugin ships itself (phase 12),
                                   loaded through IconLoader.getIcon(absolute path, class). A wrong
                                   path fails only at runtime, which is what RemarkIconsTest catches
  ui/ClassNameInsert.kt            projectClassNames, chooseClassName: the class-name chooser the
                                   input popup opens on Cmd+Ctrl+Shift+Space (Ctrl+Alt+Shift+Space
                                   off macOS — NOT Ctrl+Space, see CLASS_NAME_STROKE for why)
  ui/RemarksTree.kt                node building: an "Answers with no question" group at the very top
                                   when any answer's question is gone (phase 11's flat Answers group,
                                   narrowed by phase 12), then the two sides, OPEN_KEY and DONE_KEY
                                   (phase 13), each holding a General group for a remark about no file
                                   and then files, with an answer nested under its own question.
                                   RemarkNode.processedAt is what Done orders by, the later of readAt
                                   and the nested answer's answeredAt, falling back to createdAt. Every group inside a side is keyed with its side's
                                   prefix. Also RemarkTreeRenderer, the stacked-line cell renderer:
                                   MAX_TEXT_LINES text rows plus a grey metadataLine below them.
                                   leavesOf recurses into a RemarkNode, which is what makes Delete on
                                   a question take its answer too. asksLabel was deleted in phase 12,
                                   when the icon took over saying that a remark asks; the bucket
                                   level, bucketDropTarget and the trailing read/published words went
                                   in phase 13
  ui/WrapText.kt                   wrapToLines and elideToWidth (phase 13): the pure word-break the
                                   renderer wraps a row with, and the cut-short-but-never-re-flowed
                                   one the grey metadata line uses. NO imports at all — they take a
                                   widthOf measurer instead of a FontMetrics, which is what keeps the
                                   file free of java.awt and com.intellij and its tests running in
                                   milliseconds. The platform's own MultiLineTodoRenderer never wraps,
                                   so there is nothing to fall back on here
  ui/RemarksToolWindowFactory.kt   RemarksPanel: the tree, the toolbar (six buttons, each with its
                                   own description since phase 11), self-refresh on REMARKS_CHANGED.
                                   setRowHeight(0) since phase 13 — the whole variable-row-height
                                   mechanism, cited from TodoPanel.java:251 — and expandAll walks the
                                   model rather than the rows, so a node inside a shut Done is
                                   expanded too, shutting Done again at the end with collapsePaths. It
                                   takes a keepDoneOpen flag read before the rebuild, since
                                   collapsedGroups cannot tell "the person opened Done" from "Done is
                                   new". A ComponentListener on the scroll pane's viewport restarts a
                                   one-shot 150 ms Timer that re-wraps every row for the new width,
                                   through nodeStructureChanged plus the same three restores.
                                   The waiting-review banner above the tree was deleted in phase 12,
                                   and deleteSelected now asks selectionHidesRows whether the
                                   selection stands for a row that is off screen, rather than
                                   comparing two counts. A group row always does; a question row does
                                   when it has an answer under it and is itself collapsed.
                                   Since phase 11 it also resolves answers, deletes answer rows, and
                                   on an answer row navigates to the code AND then opens the popup.
                                   navigateTo is the one place that opens a file at a line, shared by
                                   the remark row and the answer row
  ui/AnswerPopup.kt                showAnswerPopup (phase 11): DocMarkdownToHtmlConverter inside a
                                   ReadAction.nonBlocking, then a JBHtmlPane in a JBScrollPane on the
                                   EDT. Disposer.register(popup, pane) is not optional — JBHtmlPane
                                   is Disposable and nothing else in this plugin is
  action/AddRemarkAction.kt        the shortcut / popup-menu entry point, selectedLines(), and
                                   openGeneralRemarkInput, the tool window's entry point for a
                                   remark about no file
  action/AskClaudeAction.kt        the Ctrl+Alt+Shift+A gesture and AskClaudeIntention beside it
                                   (phase 11): the same input popup, then addRemark with
                                   asksForAnswer = true, then publishRemarks on that one id
  action/AddRemarkIntention.kt     the Alt+Enter entry point
  action/AddPreviewRemarkAction.kt the entry point in the rendered markdown preview's right-click
                                   menu (phase 9). Reads only what PreviewSelectionService already
                                   holds, asks the page nothing, and refuses with a dialog rather
                                   than a hint, since there is no editor here to put a hint in.
                                   updatePreviewRemarkEntryPoint and openPreviewRemarkInput live
                                   here and hold every check both preview actions make
  action/AskClaudePreviewAction.kt the preview's Ask Claude, beside the action above (phase 14). Same
                                   pair one level down as AskClaudeAction is to AddRemarkAction in
                                   the editor: it supplies the popup title and calls askClaude, and
                                   nothing else differs
  action/PublishRemarks.kt         publishRemarks(project, ids), the whole publish pipeline, plus the
                                   Tools-menu action (PublishUnreadRemarksAction) that calls it without
                                   the tool window; renamed from CopyRemarks.kt/copyRemarks in phase 9
  editor/RemarkGutterIcon.kt       the placement record, the tooltip, the gutter icon renderer, and
                                   since phase 11 AnswerPlacement, answerTooltipFor and
                                   AnswerGutterIconRenderer, whose equals/hashCode include the
                                   markdown because that is what its click opens
  editor/RemarkGutter.kt           the project service that keeps gutter icons in step
  editor/RemarkGutterStartup.kt    the ProjectActivity that starts RemarkGutter, and
                                   ReviewHandshakeService
  settings/RemarkSettings.kt       the app-level service and the default prompt header
  settings/RemarkSettingsConfigurable.kt
  render/PromptRenderer.kt         pure Kotlin, zero platform imports. Remarks to markdown, general
                                   remarks first under their own heading and with no code block.
                                   PROMPT_NOTES (called SEVERITY_SCALE_NOTE until phase 11) is the
                                   text appended under the editable header: what the asks marker and
                                   the id: line mean, the commit paragraph, the ⟦/⟧ paragraph
  render/PromptPayload.kt          collectForPrompt and clipboardPayload
  preview/PreviewSelection.kt      SourceRange, PreviewSelection, parseSelectionMessage and
                                   narrowToSelection: what a selection in the rendered markdown
                                   preview is allowed to say, and how it becomes a character range in
                                   the .md source. No platform import, so its tests need no fixture.
  preview/PreviewSelectionService.kt
                                   @Service PROJECT, in memory only: the last selection any preview
                                   reported, with the file url beside it so a reader can compare
  preview/PreviewHighlights.kt     which remarks the preview should highlight and where each starts
                                   in the .md source (phase 14). PreviewHighlight, HighlightCandidate,
                                   highlightsFor and toJson. NO com.intellij import: it takes plain
                                   values, the same argument anchor/ and PreviewSelection.kt make,
                                   which is why its tests need no fixture. It drops an orphan, a
                                   remark about no file and a remark about another file
  preview/PreviewRemarkExtension.kt
                                   the browser half: the MarkdownBrowserPreviewExtension, its
                                   Provider and its ResourceProvider. Subscribes to one pipe message,
                                   parses it on the browser's callback thread, then hops to the EDT
                                   to narrow the range against the Document and store it. Since phase
                                   14 it also sends a message the other way, on creation and on every
                                   REMARKS_CHANGED, resolving the remarks inside a
                                   ReadAction.nonBlocking and disconnecting that subscription in
                                   dispose. It declares the stylesheet through styles as well as the
                                   script through scripts, and serves both from the same
                                   ResourceProvider
  review/ReviewHandshake.kt        projectIdentity (the ONE function that decides what the plugin
                                   hashes and compares as "this project": the git top level, or the
                                   base path outside a repository), projectHash, handshakeName,
                                   renderHandshake, writeHandshake/deleteHandshake,
                                   the per-run ReviewToken, and ReviewHandshakeService (@Service
                                   PROJECT, Disposable) — the file a skill reads to find this IDE
  review/AtomicWrite.kt            atomicWriteString: temp file beside the target, then rename
  review/PublishedRemarks.kt       PublishedHeader (nonce, publishedAt, commit, remarks) with
                                   render()/publishedHeaderOf(), PUBLISHED_MARKER, publishedName,
                                   writePublished: the one file a publish writes under handshakeDir().
                                   Nothing is sanitised on the way out any more: a control character
                                   in commit shifts the header, publishedHeaderOf reads back null and
                                   the fetch answers failed, which is the loud answer this file wants
                                   Added in phase 9 as a three-field header; restructured into an
                                   eight-line PublishedHeader in phase 10, when the review's own
                                   handoff file merged into this one; back to five lines in phase 12,
                                   when the review, the label and the rejection went
  review/PublishedAck.kt           PublishedAckOutcome, PublishedBatch, PublishedAckAnswer,
                                   PublishedBatchService (@Service PROJECT, in memory only, the last
                                   sixteen published batches, @Synchronized record/acknowledge) and
                                   reportPublishedRead: since phase 12 the ONLY acknowledgement route,
                                   keyed to a published batch's nonce. Added in phase 10 as the second
                                   of two, beside the review's own session-keyed ack. Since phase 11
                                   also BatchLookup and batchCarries, the non-destructive read the
                                   answer action asks "did this batch carry this remark" with — it
                                   never stamps readBy
  review/AnswerReceipt.kt          reportAnswer and buildAnswer (phase 11): everything the answer
                                   action causes, kept out of ReviewRestService.kt by rule 5 the way
                                   PublishedAck.kt keeps published-read's consequences out. It
                                   resolves the remark and captures a FRESH anchor inside a
                                   ReadAction.nonBlocking, then calls recordAnswer and the balloon on
                                   the EDT. It never asked anything about a review even while reviews
                                   existed, which is why phase 12 needed no change to it
  review/ReviewRestService.kt      the RestService at
                                   POST /api/claude-remarks/{fetch,published-read,answer,open}:
                                   isHostTrusted, execute (dispatches on the sub-path), handleAnswer
                                   with MAX_ANSWER_BYTES, readPublished/PublishedRead (the file read
                                   back with a size cap), handlePublishedRead, handleOpen, and the
                                   pure requestIsAllowed/projectForPath helpers. Rule 5 above governs
                                   this file specifically. It had five actions after phase 11; phase
                                   12 deleted start and ack with their request classes, the deadline
                                   clamp and its three constants, and FetchRequest.session, and added
                                   open. review/WaitingReview.kt and review/ReviewLifecycle.kt were
                                   deleted whole in the same phase, with the waiting review they held
  review/OpenReviewFiles.kt        the only file in review/ that touches the VFS or the editor —
                                   opens a real diff over the files that have a local change,
                                   through ShowDiffAction, and a plain editor for the rest. Written
                                   for phase 7's start action, and unchanged by phase 12, which
                                   pointed the new open action at it
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
                                                  browserPreviewExtensionProvider registration and,
                                                  in Markdown.PreviewGroup, the two preview actions
                                                  ClaudeRemarks.AddPreviewRemark and
                                                  ClaudeRemarks.AskClaudePreview. Neither id is
                                                  pinned by ActionIdsTest, because the test fixture
                                                  never loads the markdown plugin
src/main/resources/dev/sasha/clauderemarks/icons/question{Pending,Published,Answered}.svg
                                                  plus a _dark sibling for each: the three question
                                                  marks a question's row and its gutter icon draw
                                                  (phase 12). The shape is the platform's own
                                                  expui/general/questionMark.svg, recoloured; its own
                                                  colours are NOT reused, because that file's dark
                                                  variant is drawn on a chip rather than a tree row
src/main/resources/dev/sasha/clauderemarks/preview/claude-remarks-preview.js
                                                  the script injected into the preview page. Listens
                                                  for selectionchange, walks up to the nearest element
                                                  carrying the position attribute (whose name it reads
                                                  from the page's own meta tag), and posts four
                                                  offsets plus the highlighted text. Since phase 14 it
                                                  also subscribes to the highlight message, marks the
                                                  innermost element covering each offset, and
                                                  re-applies the last list on every DOM rebuild
                                                  through a MutationObserver
src/main/resources/dev/sasha/clauderemarks/preview/claude-remarks-preview.css
                                                  the two highlight classes (phase 14), served as an
                                                  ordinary stylesheet because
                                                  MarkdownBrowserPreviewExtension declares a styles
                                                  list. Both colours are alpha-blended over whatever
                                                  background is there, since this page defines no
                                                  theme colour variables a stylesheet could read
src/main/resources/intentionDescriptions/AddRemarkIntention/description.html
src/main/resources/intentionDescriptions/AskClaudeIntention/description.html
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
  `verifyPlugin`'s failure level: the three `MarkdownHtmlPanel` getters the preview extension calls
  all carry `@ApiStatus.Experimental`, and there is no non-experimental route to a preview's
  `BrowserPipe`. ⚠️ Since phase 11 that subtraction has a **second** reason, and both have to go
  before the line can: `ui/AnswerPopup.kt` builds a `JBHtmlPane`, which is experimental at class
  level. Removing the markdown preview later does not make the subtraction removable — check the
  popup too.
- `INTERNAL_API_USAGES` is no longer subtracted. It was there for `SegmentedButton.component`,
  reached from the tag chip row, and phase 11 deleted the chip row. The verifier now reports no
  internal API usage at all, so a new one fails the build rather than passing unnoticed. Keep it that
  way: if a future change needs an internal API, weigh it on its own rather than reviving the
  subtraction.
- `com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter` and `com.intellij.ui.components.JBHtmlPane`
  are both in `lib/app-client.jar`, already on the compile classpath. The answer popup needed no
  change to the `dependencies` block and no `bundledModule` line.
- **The plugin ships icons of its own since phase 12**, under
  `src/main/resources/dev/sasha/clauderemarks/icons/`, and `ui/RemarkIcons.kt` loads each with
  `IconLoader.getIcon(path, aClass)`. The path is absolute and starts with a slash. The `_dark` suffix
  goes before the extension and `IconLoader` finds the dark variant without being told, so only the
  light name appears in Kotlin. Nothing in `build.gradle.kts` or `plugin.xml` had to change for them —
  they are ordinary resources. ⚠️ A wrong path is not a compile error; it is a missing icon at runtime,
  which is what `RemarkIconsTest` exists to catch.

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
(including the General group, and since phase 12 the answer nesting: a matched answer is its
question's child, an answer naming nothing is in the top-level "Answers with no question" group, that
group is absent when every answer has a question, a nested row carries no file name and a top-level one
does, and an answer naming a remark that produced no node lands in the top-level group rather than
disappearing; and since phase 13 the Open/Done split: a `READ` remark is under Done, an answered
question is under Done with its answer still nested, `PENDING` and `PUBLISHED` are under Open, Done
orders by `readAt`, a remark with `readAt == 0` falls back to `createdAt`, two Done rows sharing a
processed time fall back to the resolved line, a question answered but never acknowledged is processed
when its answer came back, and an empty side produces
no group at all), `WrapTextTest` (phase 13's word-break, with a fixed width per character so the
arithmetic is exact: a short text staying on one line, a break on a space, a run of spaces collapsing,
a single word longer than the width breaking mid-word, a `\n` starting a new line, more lines than the
cap truncating with an ellipsis, empty text giving one empty line, the space in front of an ellipsis
being trimmed, `maxLines` below one still giving a line, a 20,000-character word being measured a
bounded number of times rather than a number that grows with it, and `elideToWidth` leaving text that
fits exactly as it was written), the markdown renderer (including the General section, rendered
first with no code block), the settings round trip, `GitHeadTest` (reads real `.git` directories built on disk for
the test, including a worktree, a detached HEAD and packed refs, plus `gitTopLevel` for a directory
below the repository root, for a worktree, and with no repository at all), `RemarkHistoryTest` (the
archive's markdown rendering, and the write itself against a temp file; since phase 9 also the
sub-line position shape in the heading and the phrase written indented under it, and a general
remark's `(general)` heading with no line numbers), `AtomicWriteTest` (the
temp file lands beside the target, not in the system temp directory, and no temp file is left
behind), `ReviewHandshakeTest` (the name, the rendering, the escaping, the owner-only
permissions, and `projectIdentity`: the repository for a project opened below its root, the base
path with no repository, and null for a base path that is missing or unusable), and
`ReviewRequestTest` (the pure `requestIsAllowed`, `projectForPath`, and since phase 8 `readPublished`
— renamed from `readHandoff` in phase 10 — and its size cap; phase 7's `clampDeadlineSeconds` tests
went with the deadline in phase 12, and `WaitingReviewTest`'s `startOrConflict` and `isStale` tests
went with the whole file), `PreviewSelectionTest`
(since phase 9, `parseSelectionMessage`'s refusals and `narrowToSelection`'s search, including the
cross-line case and the malformed-message case), `PreviewRemarkProblemTest` (since phase 9, the
pure `previewRemarkProblem`: no stored selection, a stored selection in another preview, and one that
matches), and `PreviewHighlightsTest` (phase 14, the pure `highlightsFor` and `toJson`: a plain remark
becoming an offset, an unanswered question becoming the question kind, an answered question falling
back to the plain kind, the three exclusions one test each — orphaned, about no file, about another
file — several remarks each keeping their own offset, a start line past the end of the source being
excluded rather than throwing, and the two `toJson` shapes) are plain JUnit tests with
no fixture, so they run in milliseconds. The rest
need a light IDE fixture
(`BasePlatformTestCase`, which needs `testFramework(TestFrameworkType.Platform)` in
`build.gradle.kts`) and are slower, because each goes through a real project service, a real
`Document`, or a real markup model: `RemarkStoreServiceTest`, `ResolveAllTest` (stored remarks
resolved against real files, including a path that tries to climb out of the project, and, since
phase 9, that a resolved row carries the phrase's refreshed columns),
`SelectedLinesTest` (the selection line math against a real `Document`), `RemarkEditsTest` (the
eleven mutation functions publish `REMARKS_CHANGED`; since phase 11 also that `recordAnswer` upserts
on the remark id rather than appending, that `deleteAnswer` is keyed on the answer's own id, and that
`clearAllRemarks` archives and clears both lists while `clearHandedOverRemarks` leaves answers
alone; since phase 13 that `markRemarksRead` stamps `readAt` and that a second mark leaves an
already-set stamp alone, so re-publishing and re-acknowledging cannot make a row jump to the top of
Done), the key-binding half of
`RemarkInputPanelTest`, `AddRemarkActionTest`, `ActionIdsTest` (pins the two action ids and the
tool window's derived activation id, so a rename is caught rather than silently breaking every
`.ideavimrc`), `RemarkActionsTest` (the shared menu offers Ask for an Answer and Publish in that
order, and acts on the ids it is given at press time, not at build time; the toggle
is off when there is nothing to act on and flips across several ids at once), `ClassNameInsertTest`
(inserting a class name at the caret, and over a selection; its two extension-point tests assert
that the contributed names are present, that a repeated name comes back once and that the list is
sorted, rather than comparing the whole list — the light fixture also answers the Kotlin builtin
names now, and the old exact comparison broke on that, which is fixture drift and not a plugin
change), `DiffRemarkTargetTest` (adding a remark from a diff pane: a real
`DiffContentFactory` content standing in for a VCS revision, since a light fixture cannot build a
diff viewer), the renderer-equality half of `RemarkGutterIconTest`, `RemarkGutterTest` (the gutter
service, including that a general remark produces no placement anywhere), `RemarksPanelTest` (the
tool window panel: every group ends up expanded, the selection survives a rebuild,
and the Add General Remark button is offered and enabled with no selection and no editor open; since
phase 13 also that Done starts collapsed while Open is expanded, that a Done a person opened by
hand is still open after a refresh, and ⚠️ that expanding **only** the Done row shows the file group,
the question and its nested answer with no further clicks — the one that pins `expandAll` walking the
model, since a row walk can never reach inside a collapsed node. ⚠️ Three refresh-based tests go through a `refreshAndSettle`
helper that asserts the tree root object actually changed: "the tree looks the same after a refresh"
passes just as happily when the async `ReadAction` never finished, and that vacuity would hide the
whole `keepDoneOpen` path), `RemarkTreeRendererTest` (phase 13, fixture-backed because every
assertion needs a real `SimpleColoredComponent`, a real `Tree` and `UIUtil`'s theme colours: how many
line components a row drew, what each carries, and whether the grey metadata row was drawn at all —
a general remark draws none and a positioned remark does; plus the wrap width losing one indent per
level of depth and falling back to its floor at width zero, the metadata line being cut short with an
ellipsis rather than overflowing, a row's accessible name being the text it drew, and
`selectionAdjusted` rewriting a grey fragment's colour on a focused selected row. ⚠️ That last one
sets `Tree.forceFocusedSelectionForeground` in `UIManager` and puts it back in `tearDown`: a test
fixture loads no theme, so reading the key instead would silently assert nothing),
`NavigationLineBaseTest` (pins `OpenFileDescriptor`'s
0-based line argument), the collector half of `PromptPayloadTest`, `PublishRemarksTest` (renamed
from `CopyRemarksTest` in phase 9; since phase 10, that a publish with no ids takes every remark that
is not `READ`, not only `PENDING` ones), `PublishedRemarksTest` (the published file's name and write,
added in phase 9; since phase 12, `PublishedHeader`'s **five**-line `render()`/`publishedHeaderOf()`
round trip, that a four-line text reads back null, that a missing prefix on any of lines 2 to 5 or a
non-integer `remarks:` reads back null, and that a `commit` carrying a newline shifts the header so
`publishedHeaderOf` reads back null rather than reporting a commit nobody has),
`PublishedAckTest` (added in phase 10, fixture-backed: an acknowledgement of a recorded batch marks
its remarks read and answers `ok`; a second session, or the same session twice, gets `already-read`
naming who got there first; an unknown nonce answers `unknown-batch`; only the last sixteen batches
are remembered; and an acknowledgement marks only its own batch),
`ReviewEndpointSmokeTest` (the one test that calls `ReviewRestService.execute` itself, through a
real `EmbeddedChannel`, so the response actually carries a body. It covers the four surviving actions
and every status each of them can answer: `fetch`'s six — `ready` with the whole prompt, `no-review`
with nothing published, `too-large`, `unknown-project`, `bad-request`, and `failed` with a test each for
its two causes — plus that a fetch marks nothing read; `published-read`'s five — `ok`, `already-read`
naming who got there first, `unknown-batch`, `unknown-project`, `bad-request`; `answer`'s six — `ok`,
`unknown-batch`, `unknown-remark`, `too-large`, `unknown-project`, `bad-request` — plus a second answer
for the same remark and an answer for a remark that was never marked as asking, which is deliberately
accepted and pinned so that decision cannot be quietly reversed; and `open`'s three — `ok` with the
accepted count, `ok` with `opened: 0` for an empty list, `unknown-project`, and `bad-request` with a
detail for a missing project, plus ⚠️ one test that a real file created with `fileUnderProjectRoot`
actually appears in `FileEditorManager.openFiles` after the EDT queue drains. That last one is the only
test in the class that says the action *does* anything: the other three read the response, and the
`opened` count is computed by the path filter whatever else happens, so all three passed with the
`openReviewFiles` call deleted outright. Also the unknown-action refusal. Phase 12 deleted every `start` and `ack`
case and the three fetch cases about a session, and task 15 of that phase went through the four
handlers' own `writer.name("status")` call sites to confirm nothing lost coverage on the way),
`OpenReviewFilesTest` (the string-only half of the path
filter: absolute paths and `..` segments are dropped, plus a fixture-backed class for the
diff-or-editor decision, since a light fixture project has no VCS root and every file takes the
plain-editor branch), `PreviewSelectionServiceTest` (since phase 9, fixture-backed for the same
reason: `remember`, `forget` and `current` on the project-level service that holds the preview's last
selection), `PreviewRemarkExtensionTest` (phase 14, fixture-backed because the push resolves remarks
against a real `Document`: an already-annotated file is highlighted as soon as the extension is
created, a remark added afterwards pushes again, and `dispose` stops both. ⚠️ It fakes
`MarkdownHtmlPanel` and `BrowserPipe` as plain Kotlin objects rather than treating the push as
untestable, which is what makes the `REMARKS_CHANGED` leak testable at all), and
`RemarkStatusLookTest` (fixture-backed, because loading an icon
needs the platform: the six rows of the two icon tracks as a decision table, plus one test on its own
for a question that is `READ` with no answer getting the **yellow** icon. A seventh test asserting that
the same input returns the same icon instance was deleted in the phase 12 review: `icon` returns an
object's `val`, so identity is a Kotlin language guarantee, and nothing depends on it —
`RemarkGutterIconRenderer.equals` keys on the five facts, never on the `Icon`).
Phase 12 deleted `WaitingReviewTest`, `WaitingReviewServiceTest` and
`ReviewLifecycleTest` outright, along with `AnswerReceiptTest`'s one review-contrast test and every
review case in `ReviewEndpointSmokeTest`.

**Phase 11 added four test classes.** `AnswerStateTest` is the answer's own storage guard, and its
first three tests were written before the feature existed and confirmed failing for the right reason:
a `RemarksState` holding one answer must serialize to XML that really contains that answer's fields,
`answersSnapshot()` must deep-copy, and two `getState()` calls must never hand back the same list
instance. ⚠️ That is the `@get:XCollection` trap this project has already paid for once with the
`remarks` list — without the annotation the whole list serializes to an empty element and every
answer is lost on restart with nothing logged. `AnswerResolveTest` resolves an answer against a real
file: it follows the code when twenty lines are inserted above it, orphans when the code is gone, and
an answer with an empty path resolves as itself the way a general remark does. `AnswerReceiptTest`
covers what the endpoint causes rather than what it returns — answering marks nothing read, does not
consume the batch, captures the anchor at the position the remark resolves to *now* rather than
copying the stored one, and stores an answer with no anchor rather than dropping it when the remark
was deleted in between. `AnswerPopupTest` is one fixture-backed test that
`DocMarkdownToHtmlConverter.convert` resolves and returns in this build — a `# heading` produces an
`<h` and a fenced block produces a `<pre`. It tests that the platform call works, not that anything
renders, and it is what would catch that class changing shape on a platform bump, since it carries no
`@ApiStatus` annotation at all.

**Phase 12 added two test classes and changed several more.** `RemarkIconsTest` is a plain JUnit class
asserting all six icon resources resolve, and it asks for each of them through
`RemarkIcons.resourcePath(name)` — the same expression production hands `IconLoader`, never a copy of
it, because a copy would leave a typo in the production template invisible while all six files sat
under the path the test checked. `RemarkIconsFixtureTest`, in the same file, asserts each of the three
icons reports a width of 16, and that the three are three distinct icons. ⚠️ The width of 16 is a real
assertion, not a tautology: a `CachedImageIcon` resolves on the first call that needs a size, and
`ScaledIconCache` falls back to the platform's **1×1** `EMPTY_ICON` when the image cannot be loaded at
all, so a path that resolves to nothing and an SVG that does not parse both report 1. Checked by
breaking one path by hand and watching the class fail. Same plain-plus-fixture split
`review/OpenReviewFilesTest.kt` already uses. `RemarkStatusLookTest` is the decision table described
above. `RemarksTreeTest` pins `hasAnswer` from both sides — a question with an answer nested under it
carries true, one whose only answer names another remark carries false — which nothing asserted until
the phase 12 review: the two `asksLabel` tests were the only ones that ever pushed a true `hasAnswer`
through `buildTreeRoot`, and they went with the grey word. `RemarkGutterTest` gained three tests: a remark with an answer produces a placement carrying
`hasAnswer = true` and one without produces `false`, both asserted through the icon the renderer draws
since `placementsFor` is private, and a third pinning that the answered-id set is derived from the
**unfiltered** answers list, with an answer stored against another file naming a question in this one.
The renderer-equality tests in `editor/RemarkGutterIconTest.kt` gained the two that matter most: two
renderers differing only in `hasAnswer` are not equal, and the same for `asksForAnswer`. Those two are
the whole assertion standing between the feature and a gutter icon that never updates.
`RemarksPanelTest` gained the delete confirmation, and with it the first use of
`TestDialog`/`TestDialogManager` in this repository: `TestDialog.DEFAULT` throws on `show()`, so a test
with no dialog registered fails loudly if a dialog ever appears, which is what proves deleting an
expanded question with an answer asks nothing, and the same for an answer row on its own, while
`TestDialogManager.setTestDialog(TestDialog.NO, testRootDisposable)` proves the three cases that do ask:
a selected file group, ⚠️ a question the person has **collapsed** by hand, and a question selected
together with a group.

⚠️ **A Gradle `--tests` filter that names a file rather than a class matches nothing, and Gradle does
not fail for it when another filter in the same command matches.** It reports BUILD SUCCESSFUL while
the tests you meant to run never ran. This cost phase 12 a real check: its plan told one task to run
`--tests 'dev.sasha.clauderemarks.editor.RemarkGutterIconTest'`, no class of that name exists, the
second filter in the same command matched, and the renderer-equality tests guarding that phase's one
real trap were the ones silently skipped. Check what a file actually declares before filtering on it.
The files here whose class names differ from the filename:

- `editor/RemarkGutterIconTest.kt` — holds `RemarkTooltipTest`, `AnswerTooltipTest`,
  `RemarkGutterRendererTest` and `AnswerGutterRendererTest`. Four classes, and none of them is named
  after the file.
- `review/OpenReviewFilesTest.kt` — holds `OpenReviewFilesTest` and `OpenReviewFilesFixtureTest`.
- `ui/RemarkIconsTest.kt` — holds `RemarkIconsTest` and `RemarkIconsFixtureTest`.

Phase 13's renderer tests went into a file of their own, `ui/RemarkTreeRendererTest.kt`, with a class
of the same name, rather than being added as a second class inside `ui/RemarksTreeTest.kt`. That was
this trap avoided on purpose: `RemarksTreeTest` is plain JUnit with no fixture, the renderer
assertions need one, and a second class added to that file would never run under a filter naming
`RemarksTreeTest` while the build stayed green.

Two more files are checked by hand, not by `./gradlew test`, because this repository's suite is
Kotlin and runs no shell: `docs/skill/claude-remarks/watch-remarks.sh` (added in phase 10; each
check is its own run, in the scratchpad directory, covering a deadline timeout, a nonce that has
already changed, a file that appears after the watcher starts, a malformed
header, and — since phase 11 reversed this one — that a second watcher on the same project does
**not** kill the first, that both are still alive afterwards, that the pid file then holds the second
watcher's pid, and that a watcher on another path is left completely untouched; plus that fetch mode
now starts with no `--session` while still refusing with no `--project` and no
`CLAUDE_REMARKS_TOKEN`; and, since the phase 11 follow-up on 2026-08-06, `--owner`: a live owner
keeps the watcher polling, a killed owner ends it inside one poll interval with exit 3 and its own
message, a non-numeric, empty or zero value is refused with exit 2, and no `--owner` at all leaves
every earlier behaviour untouched — plus that the `perl … setsid()` launch form really does leave the
launching shell's process group, checked by signalling that whole group and watching only that form
survive; and, since phase 12, that a five-line header is read and its nonce taken from line 2, that an
old eight-line header still yields its nonce because the three extra lines read as body, that
`--require-review` and `--session` are each refused with exit 2 rather than ignored, and that fetch mode
sends a body carrying `project` and no `session`, checked against a one-shot local HTTP server that
captured the raw POST body; and, since phase 14, `--stream` in both modes — two nonces out of one
process and no third line for an unchanged file — that a run without `--stream` still prints the batch
whole and exits 0, that `--claim` is refused without `--stream` or without `--project`, that a claim
that cannot connect prints `claim-failed http 000` **and** the nonce, that a stream run with no
`--claim` invokes `curl` zero times, and that the token reaches the endpoint's header and appears in no
recorded argv, proved with a `curl` shim that wrote down its own arguments. ⚠️ Every one of those runs
faked `HOME` **and** the port: a fake handshake left at the ordinary `63342` reaches the IDE the person
is actually working in, which happened once during phase 14 and was stopped only by the token check.
Point a fake handshake at a port nothing is listening on, or at a fake endpoint of your own) and
`docs/skill/claude-remarks/remote-config.sh` (added in phase 10; each check is its own run too,
with `HOME` pointed at a temporary directory, covering `save`/`show`/`forget`, that the token never
appears in any output, permission and validation refusals, and that two repository roots produce two
different stored files).

Every fixture-backed test class that asserts on the whole store clears it in `setUp`, not only in
`tearDown`: the light fixture project is shared across test classes, so remarks left behind by an
earlier class are still there when the next one starts. Until phase 12, `ReviewLifecycleTest` and
`RemarksPanelTest` also had to clear `WaitingReviewService` in both, for the same reason — one of
`ReviewLifecycleTest`'s failure-path tests deliberately left a review waiting when it finished, so the
next class to touch the tool window must not find it still there. That service is gone, and the store
is the only shared state left to clear. Anything registered on `testRootDisposable`, such as
`RemarksPanelTest`'s `TestDialog`, is unregistered by the fixture itself and needs no `tearDown` of its
own.

There are no UI-rendering or end-to-end tests. The popup appearing at the caret, the gutter icon
painting, the tree colours, the balloon, the settings page layout, whether the
answer popup actually draws a heading, a bullet list, a fence and a table as themselves rather than
as literal markdown, and — since phase 12 — whether the three question-mark colours are actually
distinguishable at gutter size in a light theme and in a dark one, are all checked by hand in a sandbox
IDE, not automated. A test can say an icon loaded and reports a width of 16; nothing automated can say
it reads as yellow rather than as green.

⚠️ **Phase 14 widened it again, in a new direction: there is no JavaScript test setup at all, and this
phase did not add one.** `./gradlew test` reaches `preview/PreviewHighlights.kt` and the push in
`preview/PreviewRemarkExtension.kt`, and it reaches nothing inside `claude-remarks-preview.js` or
`claude-remarks-preview.css`. Whether a highlight actually appears, whether it survives typing, whether
a question reads as different from a plain remark, and whether a heavily annotated document is still
comfortable to read are all hand checks. The answer to that is to keep the page dumb — every decision
that can be made in Kotlin is made there, which is why `PreviewHighlights.kt` exists as its own file
instead of the page working the exclusions out for itself.

⚠️ **Phase 13 widened that gap.** A test can say a row produced three visible line components and
that the fourth, grey one was drawn; nothing automated can say the fourth line of text was elided
rather than clipped, that the grey line reads as subordinate to the text above it, that selection
paints across every line of a tall row, or that the icon sitting inside the first line reads as a
hanging indent rather than as ragged. All of those are in phase 13's hand-check list.
