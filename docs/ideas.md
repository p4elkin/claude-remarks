# Ideas

Things worth building after phases 3-4. Not planned, not committed to. Each entry says what the
idea is, and what I already know about whether the platform makes it easy or hard, so the next
session does not have to find that out again.

Raised by Sasha on 2026-08-02, after the first real install into IntelliJ.

## Named buckets in the tool window

**Built in phase 5.** See `docs/claude/design.md`, section "Buckets", for the actual shape:
`RemarkState.bucket`, a third tree level that appears only once a bucket is used, and
`remarkNodesUnder` walking the whole subtree under a selected node. Two of the open questions below
were answered by cutting rather than building:

- **No current bucket.** A whole reading pass is bucketed at once, by selecting several rows and
  choosing Move to Bucket, so there was nothing for a "current bucket" default to save. Add one if
  someone forgets to move remarks afterwards and minds.
- **No Copy Bucket button.** Selecting the bucket node and pressing Copy Selected already means
  "copy this bucket," once Copy Selected walks the whole subtree instead of one level down. One
  fewer button, one fewer thing to grey out correctly.

The nesting order shipped as bucket → file → remark, not bucket → tag → file, for the reason
already given below: it keeps the file grouping people already have, and the copied prompt is
grouped by file for the same reason. There is no toggle between the two orders.

Group remarks into buckets the user names, instead of only by file. A bucket would be something
like "auth refactor" or "review of MR 412". Inside a bucket, sub-group by the tags that already
exist (bug, question, refactor, note).

Why: the tree groups by file today, which is the right default while reading one file. It is the
wrong shape when the remarks belong to two separate lines of thought that happen to touch the same
files. Buckets also give Copy a natural unit: copy one bucket, not everything pending.

How hard: easy. A bucket is IDE-side state like everything else — one more nullable field on
`RemarkState`, and one more level in `buildTreeRoot`. Nothing in the platform gets in the way.

Open questions, worth settling before writing code:

- Where the bucket is chosen. Either the input popup gains a bucket field, or the tree gains a
  "move to bucket" context menu, or both. A popup field on every remark is friction on the action
  that has to stay fast.
- Whether there is a current bucket that new remarks join by default. Probably yes, otherwise
  every remark needs the field filled in by hand.
- The nesting order. Bucket -> file -> tag, or bucket -> tag -> file. The first keeps the file
  grouping people already have; the second is what the request asks for. Could be a toggle, but a
  toggle is a setting and a setting is a thing to maintain — pick one first.
- What Copy All Pending means once buckets exist. Probably: the toolbar gains "Copy Bucket", and
  Copy All keeps meaning everything.

## Class name completion in the remark input

**Built in phase 5, the cheap version only.** `Cmd+Ctrl+Shift+Space` in the remark box
(`Ctrl+Alt+Shift+Space` off macOS) opens a chooser (`ui/ClassNameInsert.kt`) listing every class name
in the project and inserts the one picked at the caret. It was `Ctrl+Space` at first; that is the one
combination the IDE's own Basic Completion is offered even inside a modal popup, and macOS takes it for
switching input source, so it was given up. See `docs/claude/design.md`, section "Tag chips, and
picking one from the keyboard".

**The `EditorTextField` swap described below was cut, not built.** The phase 5 plan's own
recommendation was to drop it: the prompt already quotes the code each remark points at, so a
symbol name in the remark text is only useful when it names a *different* place, and the platform's
own Copy Reference (`Ctrl+Alt+Shift+C`) already puts a fully qualified name on the clipboard for
that — the remark box already accepts paste. The full swap would have cost the Enter and
Shift+Enter bindings, a fight over which of two nested popups owns Escape, and an IdeaVim
interaction nobody has tested, all to save typing a name that pasting already solves.

Offer completion for class names and fully qualified names while typing a remark, so a remark can
say `see JcrSessionProvider` without typing it out or getting it wrong.

Why: a remark is read later by Claude, so a correct symbol name in it is worth a lot. A wrong one
sends the reader to the wrong place.

How hard: this is the one with a real cost, and the cost is not the completion — it is the input
component.

- The remark box is a `JBTextArea` today. A plain Swing text area has no completion. The platform
  way to get it is `TextFieldWithCompletion` (`com.intellij.util.textCompletion`), which wraps an
  `EditorTextField` and takes a `TextCompletionProvider`.
- `EditorTextField` is a real editor. Enter and Shift+Enter would no longer be Swing key bindings
  in an input map — they go through the editor's action system instead. `RemarkInputPanelTest`
  pins the current binding, and that test would have to be rewritten against the new mechanism.
  This is the actual work.
- The completion data already exists in the IDE. `PsiShortNamesCache.getInstance(project)` gives
  every class name in the project, but it is Java-centric. The language-neutral source is the same
  one behind Ctrl+N: `ChooseByNameContributor` / `GotoClassModel2`. Prefer that, since the plugin
  itself is language-neutral and only depends on `com.intellij.modules.platform`.
- A lookup popup opening inside the remark popup is fiddly. The outer `JBPopup` must not cancel
  when the lookup takes focus. Expect to spend time on `setCancelOnWindowDeactivation` and on which
  popup owns the Escape key.

A smaller version that avoids all of the above: keep the text area, and add one explicit trigger
(a button, or a keystroke) that opens the ordinary "choose class" dialog and inserts the chosen
name at the caret. Less pleasant to use, a fraction of the work, and it does not touch the key
handling that is already tested.

## Annotating inside the diff viewer

Let the Add Remark action work while reading a diff, so a review pass over a set of changes can be
annotated without opening each file separately.

Why: this is the case the plugin is for. Reading a diff is exactly the "reading, not editing" mode
that remarks exist to serve.

How hard: partly free, partly blocked, and the blocked part is worth knowing before starting.

- The two panes of a diff are real editors created through `EditorFactory`, so the gutter service
  already sees them. Icons may well appear in the local pane today with no change at all. Worth
  trying by hand before writing anything.
- The right-hand pane usually holds the real file's `Document`, so `FileDocumentManager.getFile`
  returns the actual file and `remarkTargetProblem` is happy. Annotating that side should mostly
  work already.
- The left-hand pane is the problem. The "before" side of a diff is built from VCS content, not
  from a file under the project root, so it has no project-relative path and the action refuses it
  — correctly, by the rule that a remark must point at a file in the project. There is no obvious
  right answer here. Options: refuse the old side and say why, or anchor the remark to the new
  revision's corresponding line, which needs the diff's own line mapping and is real work.
- "Annotate just the changes" suggests more than making the action not refuse. The useful version
  probably restricts remarks to changed lines, or offers one remark per hunk. That is a design
  question, not a platform question.

Start by opening a diff with the plugin installed and seeing what already happens. The answer
changes how much of this is left to build.

## Keep the history, and stamp each remark with the commit it was written against

**Built in phase 5, with one change from the recommendation below.** See `docs/claude/design.md`,
sections "The commit stamp" and "The history file, and archiving before delete". The commit is read
from `.git` directly (`store/GitHead.kt`), captured once when the remark is written, never
refreshed — exactly as recommended. The history itself is a markdown file, not a second list: this
note below leans toward "move cleared remarks into a second list," but a persisted collection would
have copied `RemarkStore`'s whole thread-safety shape for a second time and added a second
`@get:XCollection` with its own silent-data-loss trap, for something that still needed a browse
window before anyone could read one archived remark either way. A markdown file gives the same
"nothing in the active list grows" property for about fifteen lines, and is readable, greppable and
pasteable today. What that trade gives up: there is no button that restores an archived remark. A
single Delete on one row still does not archive — only Clear Sent and Clear All do — for the reason
given in the phase 5 plan: an explicit "this one was a mistake" shouldn't be mixed into a history
file with every real remark.

Stop throwing remarks away when they are cleared. Keep every remark ever written, including the
ones Clear Sent and Clear All remove today. When one is stored, also record the repository HEAD
commit at that moment, so it is possible to say which version of the code the remark was written
about.

Why: a remark's line numbers and its stored hash only mean anything against one revision. Right
now nothing records which one, so an orphan is a mystery — the code may have moved, or the remark
may simply belong to a different commit. The commit turns that from a guess into a fact. Keeping
the history also means a cleared remark is recoverable, and that a past reading pass can be looked
at again.

How hard: two separate pieces, one easy and one with a real decision in it.

**Keeping the history** is easy in itself. Either add a cleared state and stop deleting, or move
cleared remarks into a second list. Prefer the second. Everything in the active list gets resolved
against its file on every change, and that cost is per remark, so an active list that only grows
would make the whole plugin slower the longer it is used. An archive that nothing resolves does
not.

The real decision is where the history lives, and the hard rule that nothing remark-related enters
version control decides it. `.idea/workspace.xml` is where remarks live today, and it grows
without bound if history goes in it. Two options:

- The IDE config directory (`PathManager.getConfigDir()`), keyed by project. Cannot ever be
  committed by accident, which is the whole point. Does not travel with the project, and is lost
  if the IDE config is wiped.
- A separate file next to the project. Travels with the project, and is exactly the thing the
  no-VCS rule exists to prevent from being committed.

The first is the safer default. Pick it unless there is a reason not to.

**Recording the commit** needs care about dependencies. The plugin depends only on
`com.intellij.modules.platform` today, which is why it loads in any JetBrains IDE. Git integration
lives in the separate Git4Idea plugin, so using its API would mean declaring a dependency on it
and requiring it to be installed.

The cheap way avoids that entirely: read `.git/HEAD` under the project root, and if it names a ref,
read that ref file. A few lines, no dependency, no VCS API. It only understands git, which is
almost certainly enough. Two cases to handle: `.git` is a file rather than a directory in a
worktree or a submodule, and a detached HEAD holds the sha directly instead of a ref.

Worth deciding at the same time: whether the commit is captured once when the remark is written,
or refreshed. Once is right — the point is to record what the author was looking at.

## Pick the tag without the drop-down

**Built in phase 5.** See `docs/claude/design.md`, section "Tag chips, and picking one from the
keyboard". The chips are `Row.segmentedButton`, as this note expected, with one correction the
phase 5 plan made and recorded: the lambda's first parameter is a receiver (`$this$segmentedButton`
in the bytecode), so the call is `segmentedButton(items) { text = it }`, not the
`{ presentation, item -> ... }` two-parameter shape written below. `Alt+0` through `Alt+4` pick the
chips from the keyboard, added as Swing input-map bindings — the same mechanism as Enter and
Shift+Enter — before the class-name-completion idea's `EditorTextField` swap, which this repo also
decided against (see "Class name completion in the remark input" above), so the ordering question
raised below never came up.

Replace the tag drop-down in the input popup with a row of chip buttons, and let the tag be chosen
from the keyboard so writing a remark never needs the mouse.

Note on wording: the drop-down chooses the **tag** (bug, question, refactor, note). Status is
pending or sent, and the user never picks it — copying sets it. This idea is about the tag.

Why: adding a remark is the action that has to stay fast. Reaching for a drop-down breaks the
flow of typing a sentence and pressing Enter.

How hard: both halves are small, and the keyboard half is nearly free.

**Chips.** The platform already has the control. `SegmentedButton` is in the 2025.2 Kotlin UI DSL,
reached as `row.segmentedButton(items) { presentation, item -> ... }`, confirmed against the
downloaded jars. That renders exactly the chip row this asks for, and it handles selection and
theming without hand-built toggle buttons. Five items including "no tag".

**Keyboard.** The input panel already binds Enter and Shift+Enter through Swing input maps, and
`RemarkInputPanelTest` already pins that mechanism. Adding Alt+1 to Alt+4 for the four tags, and
something for "no tag", is the same mechanism again — a few lines and one more test in the shape
that already exists.

One interaction worth deciding before either is built: the class-name completion idea above
replaces the `JBTextArea` with an `EditorTextField`, which moves Enter and Shift+Enter out of Swing
input maps and into the editor's action system. Any tag keys added now would have to move with
them. If both are wanted, do the input-component swap first and add the tag keys on top of the new
mechanism, not the old one.

## IdeaVim

**Built in phase 5's task 1.** The two action ids and the tool window's activation id are now
documented in the README's "IdeaVim" section and pinned by `ActionIdsTest`, exactly as the cheap
path below describes — no code change was needed, only documenting and pinning the ids that already
worked.

Short answer: this already works, and the hassle is close to zero. What is missing is that nobody
is told.

IdeaVim can invoke any registered action by id with `:action <id>`, so the plugin's actions are
already reachable from a `.ideavimrc` mapping today, with no code change:

```
nnoremap <leader>r :action ClaudeRemarks.AddRemark<CR>
vnoremap <leader>r :action ClaudeRemarks.AddRemark<CR>
nnoremap <leader>c :action ClaudeRemarks.CopyAll<CR>
```

Both ids are already namespaced and stable. The cheap work is documenting them in the README, and
treating them from then on as a public interface that does not get renamed.

Two things to actually test before claiming it works, rather than assuming:

- **Visual-mode selection.** The whole point is to select lines with `V` and remark on them.
  `:action` invoked from visual mode has historically been awkward about whether the selection is
  still there when the action runs. `AddRemarkAction` reads `editor.selectionModel`, so if the
  selection is gone it would fall back to the caret line. Try it; if the selection is lost, the fix
  is on the mapping side (`:action` after leaving visual mode with the marks intact), not in this
  plugin.
- **Typing inside the popup.** Today the input is a plain `JBTextArea`, which IdeaVim does not
  touch, so typing behaves normally. The class-name completion idea above would replace it with an
  `EditorTextField`, which is a real editor. That is fine on its own: IdeaVim already supports
  editors that are not files — the Git commit message box is one, and it works there, starting in
  insert mode. IdeaVim has an option governing this (`ideavimsupport`), worth checking rather than
  assuming a default.

  What does need deciding is Escape and Enter, which the popup has opinions about. Escape cancels
  the input today; with vim active in the box, the first Escape would leave insert mode instead,
  and cancelling would take a second one. Enter submits today; in an editor with vim active, the
  editor's own action system sees it first. Neither is a blocker, and neither is a reason to prefer
  the cheaper completion variant. They are two behaviours to choose deliberately when the input
  component changes, and to cover with tests, since the current Enter and Shift+Enter behaviour is
  already pinned by a test that would have to be rewritten anyway.

A real `VimExtension` is available but is not worth it. Action ids plus a documented mapping give
the same result.

## How much a remark matters

**Built in phase 5, as `RemarkSeverity`.** See `docs/claude/design.md`, section "Severity". Named
`vibe / suggestion / should / must` as this note suggested, defaulting to `should`. The chooser
question below was answered by the second option listed: default the level and change it afterwards
from the gutter icon menu or the tree's right-click menu, rather than adding a second chooser to the
input popup or folding it into the tag chips. The scale note is appended by the renderer
(`SEVERITY_SCALE_NOTE` in `render/PromptRenderer.kt`), not stored in the editable header, exactly as
recommended: rewriting the header cannot silently strip the levels' meaning out from under them. The
copied prompt keeps its file grouping rather than leading with the must-dos, as this note also
recommended.

A second axis next to the tag: how strongly the remark should be acted on. The range runs from
"this is a vibe, take it or leave it" to "do this whatever it costs".

Why: the tag says what kind of remark it is, never how much it matters. A `refactor` remark might
be an idle thought or the whole point of the reading pass, and right now the prompt reads the same
either way. Claude has no way to tell which remarks are optional, so it either does everything or
guesses.

Storage is trivial — one more enum on `RemarkState`, same shape as `tag`. Two things are not.

**The prompt has to act on it, or it is decoration.** This is the part that decides whether the
idea is worth anything. The renderer must put the level where it cannot be missed, and the default
prompt header must explain the scale — something like: do every "must", do a "should" unless there
is a reason not to, treat a "vibe" as a suggestion you may decline and say why. Without that the
level is a word in a heading that nothing responds to.

There is a wrinkle in that: the header is user-editable. Someone who rewrites it loses the scale's
meaning without noticing, because the levels keep rendering. Either the scale is appended to
whatever header the user wrote, or the settings page says plainly that the header has to explain
the levels. The first is less fragile.

**The chooser is getting crowded.** The input popup already has a text area and a tag chooser. A
second chooser makes a small fast popup into a form, which is the opposite of what it is for. Some
options, none obviously right:

- One chip row for tag, one for level. Honest but doubles the popup's furniture.
- Default the level and let it be changed after the fact, from the gutter icon menu or the tree.
  Most remarks are probably the middle level, so most of the time nothing is chosen.
- Fold it into the tag chips: a modifier key when clicking, or repeated presses of the same tag key
  cycling the level. Compact, and unguessable without being told.

The second is the smallest thing that works and the easiest to undo.

**Naming.** "Severity" reads oddly for a scale whose bottom end is "nice to have" — severity
suggests bugs. Something like `vibe / suggestion / should / must` says what each level means
without borrowing a word from issue trackers. Four levels also matches the four tags, which keeps
the keyboard idea above symmetrical.

Two smaller choices that follow: whether the tree sorts or groups by level, and whether the copied
prompt keeps its file grouping or leads with the must-dos. Keep the file grouping — the code is
what makes a remark understandable, and splitting a file's remarks across sections to sort by level
costs more than it buys.

## Tick which remarks to send

Put a checkbox on every row in the tool window, and let Copy send exactly the ticked ones.

Why: choosing what to send is done by selecting rows today, and selection is the wrong tool for it.
A selection is a single transient thing — clicking anywhere else loses it, and it competes with the
other meanings selection already has, namely what to navigate to and what to delete. Ticking is
sticky, survives clicking around, and reads as a deliberate choice.

How hard: the control exists. `CheckboxTree` (`CheckboxTreeBase` plus `CheckedTreeNode`, all in
`com.intellij.ui`, confirmed in the platform checkout) is the standard tree with checkboxes, and it
already handles ticking a parent to tick everything under it. That is worth a lot here: with the
bucket level phase 5 adds, ticking a bucket node means "send this bucket" for free, which is
exactly the feature that was deliberately left unbuilt as a separate button.

Two things to get right:

**Ticks must survive a refresh.** The tree is rebuilt on every remark change and on every editor
opening or closing a file that holds a remark. That is often. An earlier fix pass already had to
teach the panel to capture and restore the selection and the collapsed groups across a rebuild;
ticked state has to join the same machinery. Skipping this would be worse than what exists today —
ticks that silently clear while you are choosing are more annoying than a selection that was never
meant to persist.

**Decide what selection means afterwards.** Once ticking means "send", selection should stop
meaning it. Copy Selected becomes Copy Ticked, and selection is left to mean navigate and delete.
Keeping both would leave two competing notions of "chosen" on screen at once.

Timing: this rewrites the tree, and phase 5 is already rewriting it to add the bucket level. Build
it after phase 5 lands, not alongside, or the two rewrites collide.

## A review session shared between Claude Code and the IDE

**Built in phase 6.** See `docs/claude/design.md`, section "The Shared Review Session", for the
actual shape: the handshake file, the atomic-write transport, `WaitingReviewService`, the
`ReviewRestService` endpoint and its three-part security rule, and the banner and Send to Claude
Code button in the tool window. The skill lives at
`docs/skill/claude-remarks-review/SKILL.md`. Everything decided below was carried in unchanged. Two
of the "What to borrow from revdiff" instructions further down were deliberately declined rather
than followed — see the notes marked **Declined in phase 6** in that section.

The largest idea here, and the one that changes what the plugin is.

The shape: a skill in Claude Code starts a review. It opens the IDE on a specific set of commits or
on the local diff. The agent session then waits. In the IDE you read the diff and write remarks as
usual, and one control hands them straight to the waiting session, which shows who is waiting for
them.

**This reverses a stated constraint, on purpose.** The original brief said: no MCP, no server, no
background process — annotate, dispatch, flush. The clipboard exists precisely because of that
rule. Anything that lets an agent wait for the IDE breaks it. That may well be the right trade now
that the plugin is used daily, but it should be a decision someone makes, not something that
happens because a feature seemed nice. Everything below assumes the reversal is wanted.

**Half of this does not live in this repository.** The skill side is a Claude Code skill under
`~/.claude/skills`. The plugin side is an endpoint and a button. They have to be designed together
but they ship separately, and the plugin must keep working with no skill installed.

### Going in: the IDE already has a server

Nothing new needs to be started on the IDE side, which removes most of the objection. IntelliJ runs
a built-in HTTP server, and a plugin registers an endpoint on it by extending `RestService`
(`platform/built-in-server/src/org/jetbrains/ide/RestService.kt`, confirmed in the checkout). The
default port is 63342. So the skill can ask a running IDE to open a diff for a commit range, and to
label the session that is waiting, with an ordinary HTTP call.

Two things to check before relying on it: the built-in server rejects or challenges requests it
does not trust, so the token handling has to be worked out; and the skill has to find the right IDE
when several are open on different projects. Passing the project path and letting the endpoint
refuse if it does not match is probably enough.

### Coming back: a socket, or a file

The plain choice. A socket delivers the remarks the moment the button is pressed, and carries the
waiting session's label naturally. A file the skill watches needs no port, no protocol and no
cleanup, and the skill side is a loop that waits for the file to appear.

What happens with a socket: the IDE connects and writes, the agent wakes at once, and both sides
have to handle the other going away — an IDE that quits mid-review, a session that was interrupted,
a stale port from a previous run.

What happens with a file: the agent notices within its poll interval instead of instantly, and
nothing has to be cleaned up because the path is chosen per run. Failure looks like a file that
never appears, which is easy to reason about and easy to report.

The file is the smaller, more reversible option, and the delay it costs is a second or two on a
task that took minutes of reading. Start there. Moving to a socket later changes only the transport
and neither the skill's shape nor the plugin's.

### What the IDE side actually needs

Less than it sounds, because the payload already exists. `copyRemarks` builds the whole markdown
prompt today and puts it on the clipboard. This is the same function with a different destination,
plus:

- somewhere to record that a session is waiting, and its label, so the tool window can say so
- one more toolbar action, enabled only while a session is waiting
- a decision about what "sent" means now. Today copying marks remarks SENT. Handing them to a
  waiting agent should do the same, and the history idea above becomes more valuable here, because
  a review handed over is exactly the thing worth keeping.

### Decided

Sasha approved this on 2026-08-03, with the file transport rather than a socket. Two things follow
and neither should be quietly revisited:

- **The no-server constraint is deliberately reversed for this feature.** It was reversed with the
  reason known: reading a diff in the IDE beats reading it in a terminal, and the clipboard round
  trip is the last manual step in the loop. Everything already built stays as it is — the clipboard
  path is not removed, and the plugin must keep working with no skill installed and nothing
  listening.
- **The transport is a file the skill watches.** Not a socket. Failure then looks like a file that
  never appeared, which is easy to report and easy to reason about, instead of two sides each
  handling the other vanishing. Moving to a socket later changes only the transport.

This becomes its own phase, planned after phase 5 lands. Half of it is a Claude Code skill under
`~/.claude/skills`, outside this repository, and the two halves have to be designed together even
though they ship separately.

### What to borrow from revdiff

revdiff (`~/dev/oss/revdiff`) already runs this exact loop, with a terminal overlay standing in for
the IDE. Its Claude Code plugin launches a TUI, waits, and hands annotations back to the calling
skill. What follows names the actual mechanism, in code, so the next phase does not have to
rediscover it by reading the revdiff source again.

**The handoff is a file, written atomically, plus a second durable copy.**

The Claude Code plugin's launcher script (`launch-revdiff.sh`) creates an empty file up front with
`mktemp "${TMPDIR:-/tmp}/revdiff-output-XXXXXX"`, then runs revdiff with `--output=<that path>` and
the env var `REVDIFF_EXIT_CODE_ON_ANNOTATIONS=true`. revdiff itself (`app/annotation/store.go`,
`Store.WriteFile`) writes to that path on quit (`q`), and again on every mid-session flush (`O`).
The launcher deletes the file itself, in a shell `trap 'rm -f "$OUTPUT_FILE"' EXIT`, once it has
already read the content into its own stdout.

The write is never a direct write to the target path. `fsutil.AtomicWriteFile` writes the full
content to a new temp file in the same directory, then renames that temp file onto the target path.
A rename that lands in the same filesystem is atomic on POSIX, so a reader watching that path always
sees either the previous complete content or the new complete content — never a half-written file.
This is the answer to "how does the waiting side know the write is complete": it does not need to
check. There is no partial state to observe. This one helper backs every file revdiff writes as a
deliverable, so there is exactly one atomic-write routine in the whole codebase, used everywhere.

There is a second, independent copy: on every non-discarded, non-empty quit — including a process
kill by SIGHUP or SIGTERM — revdiff also writes the full annotation set to
`~/.config/revdiff/history/<repo-name>/<timestamp>.md`, through the same atomic-write helper. This
is not part of the handoff protocol. It is a safety net for the case where the ephemeral handoff
file is already gone (cleanup already ran) or the process that was going to read it died first.

**Copy:** the atomic write (temp file in the same directory, then rename) outright, for the file
transport chosen for Claude Remarks. It is the one piece of plumbing that removes an entire class of
race, regardless of file vs. socket, IDE vs. terminal.

**Declined in phase 6: the two-tier idea** — one file for the fast path, one durable log for
recovery when the fast path fails. revdiff needs the second tier because its handoff file is deleted
by the calling script's own `trap` the moment its process is about to exit. Neither half of that is
true here: the plugin never deletes the handoff file, and remarks stay in the store marked `SENT`
until somebody clears them, so the store is already the durable tier. A second write would also
double-count against the phase 5 history file, which archives on *clear*: a remark handed over and
later cleared would appear in it twice. What is given up: if the handoff file is gone and the person
has already cleared the remarks, the payload survives only in the history file's format, not in the
prompt format the agent would have received.

**Declined in phase 6: the "adapt" suggestion to use one fixed, predictable path per review up
front** instead of revdiff's fresh `mktemp` per invocation. Simpler, yes, and wrong here for the same
reason `render/PromptPayload.kt`'s own temp file is unpredictable: the system temp directory is
shared and world-writable, so a predictable name can be pre-created as a symlink by another local
user, and the plugin's write then lands wherever that symlink points. The path stays unpredictable —
a fresh `Files.createTempDirectory` per accepted review — and the plugin hands it back in the
response instead of both sides agreeing on it in advance. What is given up: a skill that loses the
response cannot guess the path and has to re-run `start`, which the idempotency rule in
`WaitingReviewService` turns into a no-op that returns the same path again.

**The waiting side blocks on a process first, and polls a marker file only as a fallback — the IDE
version should flip that priority.**

The calling skill does not, as its main mechanism, poll a file for content. It runs the launcher
script as one ordinary blocking command, the same way it would block on any shell command finishing.
Inside that one call, revdiff's launcher picks a terminal backend. Some backends block natively
(`tmux display-popup -E`, or agterm's `agtermctl session overlay open ... --block`). Backends without
a native block (Zellij, kitty, wezterm/Kaku, cmux, Ghostty, iTerm2, Emacs vterm) get a small
companion script that runs revdiff and, after it exits, writes revdiff's exit code to a second,
separate marker file — not the annotation file itself. The launcher then runs the one real poll loop
in the whole system:

```sh
while [ ! -f "$SENTINEL" ]; do sleep 0.3; done
```

That loop asks only "has revdiff finished," a much simpler question than "is this file done being
written" — the atomic write already answers that one.

What each outcome looks like on the agent side:
- **Quit without annotating.** `Store.FormatOutput()` returns an empty string. `finalize()`'s first
  check is `if discarded || annotations == "" { write nothing }`. Exit stays 0. The skill sees empty
  stdout and exit 0, and its own instructions say: stop the loop, do not relaunch.
- **The tool is killed or disconnects mid-review** (SIGHUP from a dropped SSH/tmux client, or
  SIGTERM). revdiff still writes the durable history copy, but deliberately does **not** write the
  handoff file. The reasoning, from the code: a signal is not the deliberate handoff that quitting or
  flushing is. A killed session never produces a payload that looks like the person approved sending
  it. Recovery is through the durable history copy only.
- **The calling agent's own process dies or times out** while revdiff is still open and the person is
  still annotating — this is the case closest to what the IDE version has to handle, since an IDE
  process genuinely keeps running independent of the Claude Code skill, unlike a terminal overlay.
  Nothing is lost on revdiff's side, because its file writes never depended on the caller staying
  alive. The skill's documented fallback: tell the person nothing is lost, wait for their reply, then
  read the newest matching temp file by modification time, falling back to the newest history file if
  that one is already cleaned up.

**Copy:** the marker-versus-content split, and the graceful-versus-killed distinction — a review
only counts as "sent" when the person took an explicit action, never when the IDE was merely closed
or crashed. **Copy, promoted to primary:** the poll-a-file idea the plan already wants is exactly
revdiff's *fallback* path for an agent time-out, just running as the only path instead of the backup
one — that reuse is sound, because an IDE process really is independent of the skill in the way a
terminal overlay is not. **Leave behind:** the five-plus backend-specific launcher branches
(tmux/Zellij/kitty/wezterm/cmux/Ghostty/iTerm2/Emacs). Those exist only because revdiff has to work
across many terminal multiplexers with no shared blocking primitive. An IDE plugin has exactly one
place remarks come from, so none of that branching applies.

**The payload is markdown records with a fixed header grammar, and no instruction prompt inside it.**

```
## handler.go (file-level)
consider splitting this file into smaller modules

## handler.go:43 (+)
use errors.Is() instead of direct comparison

## handler.go:43-67 (+)
refactor this hunk to reduce nesting

## store.go:18 (-)
don't remove this validation
```

Header shape: `## path[:line[-endline]] (type)`, where type is `+`, `-`, a literal space for a
context line, or the word `file-level` in place of a line number. The body is every line up to the
next `## ` header. Records are sorted by file, then by line ascending, file-level notes first within
a file. A range header (`:43-67`) is generated automatically whenever the comment text contains the
word "hunk," so the range reaches the model without the person doing anything extra. One escaping
rule: a body line that itself starts with `## ` gets one leading space added on write and stripped
back off on read, so a comment that happens to say "## foo" is never read as a new record.

No instruction prompt is wrapped around this file. It is purely the payload — path, line range,
comment text, nothing else. The classification Claude Remarks may have expected to find here instead
lives in the skill's prose, applied *after* parsing: SKILL.md sorts every comment into an explanation
request (two or more consecutive question marks anywhere, or starts with "explain", "remind",
"describe", "what is", "what are", "how does", "how do", "clarify") or a code-change directive
(everything else). That is the actual analog to Claude Remarks' own header. `DEFAULT_PROMPT_HEADER`
in `RemarkSettings.kt` draws the same line — "A remark that asks something... is a QUESTION... Any
other remark is an INSTRUCTION" — just inline in the rendered document, applied by the model reading
it, instead of as a separate step the skill runs before the model ever sees the text. Both are valid.
They are not the same design. Worth a deliberate choice once a real file handoff exists, since the
file format itself carries no opinion either way.

**Copy:** the header grammar closely — path, line or range, one-character change-type marker,
file-level as an explicit special case, sorted by file then line. It is compact and greppable, and
Claude Remarks already produces something structurally close (its own `## path` / `### N. lines A-B`
layout). **Adapt:** Claude Remarks carries richer per-remark data than revdiff's format has room for
— tag, severity, orphaned flag, captured context — keep all of that; just make sure a file-based
payload still opens each record with one `## file[:line[-line]]` line so a skill can index by file
without parsing the whole body first. **Leave behind:** nothing to add — revdiff's choice to keep the
instruction prompt out of the payload is itself worth keeping, not overriding.

**Invocation, and the one thing revdiff never needed: a session label.**

The launcher (`launch-revdiff.sh`) takes ref arguments positionally, plus flags: `revdiff HEAD~1` for
a single commit, `revdiff main` for branch-vs-current, `revdiff main feature` / `main..feature` /
`main...feature` for two-ref or divergence diffs, `--staged` for the index, no ref at all for the
local/uncommitted diff (revdiff's default), `--untracked` added on top to fold in new files the
recent change created. A separate `resolve-launcher.sh` resolves the actual script path through a
`user → bundled` override chain, so a person can drop in a replacement without touching the plugin.

There is no generic "who is waiting" field anywhere in the protocol. Two things stand in for it:
`--description` / `--description-file`, prose Markdown shown in revdiff's info popup, explaining what
the review is and why — the closest existing analog to labelling a session; and the overlay's own
window/pane/tab title, built by the launcher from the working directory and ref
(`"rd: ${DIR_NAME}${TITLE_REF:+ [$TITLE_REF]}"`), visible before the person even opens that popup.
agterm goes one step further and flags the *calling* agent session itself as blocked and blinking for
the duration, restored to active afterward — a status on the waiting side, not a label carried in any
file.

**Copy:** `--description`-style prose context, surfaced somewhere in the IDE, saying what the review
is for. **Copy, if the runtime allows it:** flipping a visible "waiting" status on the calling session
while it blocks. **New work, not adaptation:** an actual session id or label. revdiff never needed
one because a terminal overlay is inherently modal — nothing else can happen in that terminal while
it is up — but an IDE reviewer can have several projects and several waiting sessions open at once,
so this has to be designed, not borrowed.

**What looks like it was learned the hard way.**

- The atomic write (temp file, same directory, rename) is the one universal lesson here — copy it
  outright, it is the fix for a whole category of "reader sees a half-written file" bugs before they
  happen.
- The signal-versus-deliberate-quit split is a real design decision, not an accident: a killed
  process must never produce a payload that looks approved by the person. Worth carrying as a
  principle into the IDE version's "hand back" control — it must be a real click, and an IDE crash or
  close must not silently look like one.
- Cleanup of the ephemeral handoff file was a real regression, not something that stayed correct by
  itself: the changelog lists "restore output-file cleanup in the agterm overlay launcher" as its own
  bug fix. Treat "the handoff file is always removed, on every exit path, including error paths" as
  something to write an explicit check for, not something that falls out of the happy path.
- The two-tier durability (ephemeral handoff file plus a separate durable history log) exists because
  either the file or the process reading it can die before the handoff completes. Worth the same
  shape here: even a file-based handoff needs a place remarks survive if the *skill's own* process is
  what dies, not just the IDE side.
- The empty-annotation case is checked first, before any write happens at all, so quitting with
  nothing never creates a stray file or a phantom history entry. Keep "nothing to send" cheap and
  silent, the same way.
- **A real, acknowledged gap, not a pattern to copy:** revdiff has no lock file and no guard against
  two concurrent reviews. Each invocation gets a fresh unique path, which avoids collisions by luck
  rather than by design, and the one recovery path (reading history) picks by modification time, with
  no sharper tie-breaker than that. An IDE session is more likely than a terminal ever is to have two
  reviews open at once — multiple project windows are normal — so this is exactly the kind of gap the
  next plan should decide about on purpose, rather than inherit silently.
- The `--annotations=<file>` preload path revalidates every record's file and line against the
  freshly recomputed diff before trusting it, dropping anything stale with a warning instead of
  silently keeping wrong line numbers. Worth the same discipline if the IDE payload can ever be
  generated, hand-edited, or reloaded after the code under review has moved.

## Sending remarks to a remote agent session

**Built in phase 8.** See `docs/claude/design.md`, section "The Shared Review Session", subsection
"Reaching an agent on another machine", for the actual shape: a fetch action on the endpoint that
returns the handoff file's content in the response body, the plugin remembering one ended review's
output path so a rejection still reaches a remote agent, a size cap that refuses rather than
truncates, and a skill that keeps one wait loop for both transports. (Renumbered from phase 7: phase
7 turned out to carry the delivery-acknowledgement signals and the diff opening instead. See "Tell
the IDE the remarks were actually delivered" and "Open the real diff for just the files the skill
named" below.)

**Phase 10 merged the published file and the handoff file into one, but did not extend the remote
path to the published-file modes.** A remote session can still only reach remarks through a waiting
review, fetched over the tunnel exactly as phase 8 built it. Listening for the next published batch
from another machine is still not possible, and the direction for solving it later is deliberately
not another poll: phase 8's fetch is keyed to a review session, and a remote listener has none, so
the shape that suggests itself — a fetch keyed to the project plus a poll for a new nonce across the
tunnel — runs straight into the built-in server's 30 requests a minute from one address, the same
limit that already forced the remote review poll down to five seconds. Sasha's direction instead: a
small push service on the machine the IDE runs on, which a remote session subscribes to, so a new
batch is pushed once when it happens and nothing polls anything. Whoever designs this later should
start from that shape, not from stretching the tunnel-and-poll pattern further. It is not designed
here, and the questions it opens — what the service is, how a subscription is authenticated, and
what happens to a subscriber that went away — are all open.

**One thing below turned out wrong, and it is worth keeping rather than deleting.** The plan below
says the person passes three values: host, port and token. It is four. The `start` request's
`project` field is matched against the IDE machine's own open project paths, and two machines can
have the same repository checked out at two different paths. The fourth value, the repository path
as the IDE machine sees it, defaults to the agent's own `git rev-parse --show-toplevel`, so the
common case, where both machines agree, needs nothing extra.

Sasha sometimes works from a laptop and attaches to a Claude Code session running on the main
machine over SSH. Today the remarks cannot reach that session at all.

**The topology is the harder of the two.** The IDE runs on the laptop. Claude Code runs on the main
machine. Two filesystems, two home directories, nothing shared. So neither file phase 6 writes is
readable by the agent: not the handoff file under the laptop's temp directory, and not the handshake
file under the laptop's home directory.

**Why the file transport does not reach it, and why that is not a design mistake.** Phase 6 chose a
file because the agent and the IDE were assumed to share a filesystem, and for the local case that is
still the right choice — a rename is atomic, the reader needs no completeness check, and failure looks
like a file that never appeared. The remote case simply falls outside that assumption. Nothing about
phase 6 has to be undone.

**Phase 6 needs no protocol change, and phase 8 must not start from the opposite belief.** The endpoint
is the IDE's own built-in server, so it always runs on the same machine as the IDE, whatever machine the
agent is on. The handoff file is therefore always local *to the endpoint*. Reading it is a local read
that the endpoint can already do.

Three things phase 8 needs:

- **A fetch action on the existing endpoint** that reads the waiting review's local handoff file and
  returns its content in the HTTP response body, instead of returning a path. Phase 6's two guarantees
  make this cheap: the plugin never deletes the handoff file, and `WaitingReviewService.current()` keeps
  the output path retrievable while the review is waiting, so the fetch does not have to guess a
  temp-directory name.
- **The skill taking host, port and token as arguments.** It cannot compute the handshake file's name
  from a repository path it does not have, and it could not read the file even if it could name it. The
  person passes the three values once.
- **A tunnel.** `ssh -R` or `ssh -L`, set up by the person. The plugin does not manage it, does not
  detect it, and does not report on it. A missing tunnel looks like connection refused, which is the
  same legible failure phase 6 already relies on.

**The security model is unaffected.** A tunnelled request arrives on the loopback interface, so it
still satisfies the platform's own local-host requirement, and it still carries no `Origin` and no
`Referer`, so the refusal rule from phase 6 still admits it. The token is what makes this safe to expose
through a tunnel at all: without it, anything that could reach the tunnel could drive the endpoint.

What is given up by not doing this in phase 6: the laptop case waits. That is accepted, because the
local case is the daily one and the remote case adds an argument-passing story the local case does not
need.

## Drag a remark onto a bucket

**Built in phase 9's group four, with one deliberate cut from the cheap version below.** Drag one or
more selected rows, or a whole file or bucket group, onto a bucket row to move them there, or onto
`(no bucket)` to clear it. `ui/RemarksTree.kt`'s `bucketDropTarget` decides the target, and
`RemarksToolWindowFactory` wires `DnDAwareTree`/`DnDSupport` onto the existing `setRemarkBucket`.
**The "New bucket…" drop target was not built.** Dragging can only move remarks onto a bucket that
already exists; creating one by name is still only `Move to Bucket…` in the right-click menu. See
the phase 9 plan's "What this phase deliberately does not build" for why: an empty bucket has
nowhere to live in state that does not already exist, since a bucket is derived from its members
rather than stored on its own, and the drop-target version that shipped does not need that new
state at all. Only creating one out of thin air would have.

**Decided: build the cheap version.** Drag one or more selected rows in the tool window onto a bucket
row to move them there. Dropping onto a "New bucket…" row asks for a name and creates the bucket with
those remarks already in it.

Why the cheap version and not stored buckets: see the wrinkle below. The cheap version is most of the
value for a fraction of the work, and it answers a question first — whether dragging is actually the
gesture reached for, or whether Move to Bucket was already enough.

Three pieces, and two of them already exist:

- `com.intellij.ide.dnd.aware.DnDAwareTree` extends `com.intellij.ui.treeStructure.Tree`, which is
  the class `RemarksPanel.tree` already is. So this is a superclass swap, not a rewrite. Checked
  against the 2025.2 jars, not remembered.
- `com.intellij.ide.dnd.DnDSupport` carries the whole drag-and-drop lifecycle behind a builder, so
  none of this needs a raw Swing `TransferHandler`.
- **The drop action already exists.** `setRemarkBucket(project, ids, bucket)` is one of the eight
  mutation functions, and it already publishes `REMARKS_CHANGED` so the tree redraws itself. The drop
  handler's entire job is to work out the target bucket and call it.

**The wrinkle: buckets are derived, not stored.** A bucket exists because some remark carries its name
in `RemarkState.bucket`, and `buildTreeRoot` groups the remarks it has. So there is no such thing as an
empty bucket — create one and it vanishes on the next refresh, because nothing renders it. Anything
shaped like "make a bucket, then fill it later" needs new persisted state: a list of known bucket names
in the store, separate from the remarks, plus a rule for when an empty one is cleaned up, plus a
migration for existing workspaces. The cheap version sidesteps all of that by letting the bucket come
into existence with its first member, which is exactly how it already works today.

**A tip is required, not optional.** Dropping onto a "New bucket…" row is the only way to create one
by dragging, and nobody will guess that a drop target creates something. Say it where the person is
already looking: the row's own label, its tooltip, or empty-state text in the tree. A feature that
needs to be explained in a README is a feature that will not be found.

What stays either way: `Move to Bucket…` in the right-click menu. Dragging does nothing for keyboard
use, and the keyboard path is the one this plugin is built around — so this is an addition, never a
replacement.

Still open:

- Whether dropping onto the `(no bucket)` row clears the bucket. It reads as the natural inverse, and
  `setRemarkBucket` already takes null, so it is nearly free.
- Whether dragging a whole file group moves every remark under it. `remarkNodesUnder` already walks a
  subtree for Copy Selected, so the machinery is there.
- Whether the drag image should say how many remarks are moving. Cheap, and it is the only feedback
  that a multi-row drag picked up what was intended.

## A button that installs the skill into every detected harness

A button in the plugin's settings page that finds every agent harness on the machine and installs the
review skill into each one, instead of the person copying a directory by hand. Raised by Sasha on
2026-08-03, right after doing it by hand.

Today the skill is installed by hand. During development it was symlinked:
`~/.claude/skills/claude-remarks-review` → `docs/skill/claude-remarks-review` in the checkout.

**The blocker comes first: the skill is not in the plugin zip.** It lives in `docs/skill/`, which
never reaches the artifact — checked against `claude-remarks-0.3.0.zip`, which contains no skill file
at all. So step one is not the button; it is making `SKILL.md` a plugin resource under
`src/main/resources/`, the way `intentionDescriptions/` already is. Until that happens the button has
nothing to install.

**Copy, do not symlink — and this is the opposite of what development does.** An installed plugin lives
under a versioned path, so a symlink into it dies on the next plugin update and leaves a broken skill
entry behind. The button must copy the file out. The dev symlink is right for a checkout, wrong for an
install, and the reason they differ is worth stating in the code.

**Detection, not assumption.** Offer only harnesses whose directory already exists, and never create a
harness directory that was not there — that would be the plugin guessing that a tool is wanted. Three
exist on this machine: `~/.claude/skills`, `~/.codex`, `~/.gemini`. Each has its own convention for
where a skill goes and what shape it takes, and those conventions must be **read from each tool's own
documentation, not remembered.** The same rule as the platform APIs: a guessed path writes a file
nobody reads.

Where the button goes: `settings/RemarkSettingsConfigurable.kt` already exists as a `BoundConfigurable`
under Tools, so this is a row on a page that is already there, not a new surface.

What it must show, not just do:

- which harnesses were found, and which of them already have the skill
- the version installed versus the version the plugin carries, so an out-of-date copy is visible
- one button per harness, or one button and a checkbox list — not a single silent "install everywhere"

**Consent, because this writes outside the project.** A click is the consent, and the write is to the
person's home directory rather than to the working tree, so it does not touch the rule that nothing
remark-related enters VCS. `store/RemarkHistory.kt` already writes outside the project for the same
reason, so there is a precedent to follow rather than a new argument to have.

**It only helps the local case.** For a remote agent session — see
[[Sending remarks to a remote agent session]] above — the skill has to exist on the *agent's* machine,
which this button cannot reach. The honest version prints the one-line command to run over there rather
than pretending the button covered it.

Still open:

- Whether uninstalling the plugin should remove the skills it installed. Probably not: silently deleting
  files from a person's home on uninstall is worse than leaving a stale directory.
- Whether a project-level install (`.claude/skills/` in the repo) should be offered too. It would put
  the skill in VCS, which is fine for a shared team skill and wrong by default for this plugin.

## Tell the IDE the remarks were actually delivered

**Built in phase 7.** See `docs/claude/design.md`, section "The Shared Review Session", subsection
"Three signals that the remarks arrived", for the actual shape: the phase machine (`Waiting` /
`Sent`), the deadline declared by the skill and clamped at the endpoint, and the two acknowledgement
events (`read`, `abandoned`) plus the scheduled staleness check that covers a killed session. Both
open questions below were settled the same way: nothing is marked sent until the read
acknowledgement arrives, so there is nothing to "un-send" on an abandoned or rejected review, and
the deadline is a number the skill declares in the `start` request rather than a plugin setting, so
the two sides cannot drift apart. The rejection defect described in the subsection right below this
one was fixed in the same phase, first.

Right now the IDE knows it **wrote a file**. It has no idea whether anything read it. Raised by Sasha
on 2026-08-03, straight after the first real end-to-end run.

One change fixes three separate weaknesses, which is why it is worth doing as one piece of work rather
than three:

1. **The toast overclaims.** `review/SendReview.kt` fires "Sent N remarks to Claude Code." after the
   write succeeds. If the agent had already died, that message still appears, cheerfully and wrongly.
   It reports a write, and it is worded like a delivery.
2. **The banner outlives the agent.** `updateBanner` hides the banner only when the waiting review is
   cleared, and the only things that clear it are Send and Cancel, both inside the IDE. So when the
   agent gives up — the skill's 30-minute timeout, a killed session, or a shell error like the zsh
   `status` collision found on the first run — the IDE is never told and goes on saying "Claude Code is
   waiting" for something that stopped waiting. The skill's own documentation admits this and tells the
   person to press Cancel by hand.
3. **A dead review looks live.** The Send button stays enabled, so the next thing sent goes into a file
   nobody will ever read, and the remarks are marked sent.

**The shape.** Three signals rather than one, because an acknowledgement alone cannot cover a process
that was killed:

- **read** — the skill POSTs an acknowledgement after it reads the handoff file. The IDE records it on
  `WaitingReviewState` and the balloon becomes truthful: "Claude Code read N remarks" rather than
  "Sent".
- **abandoned** — the skill sends this from a shell `trap` when it exits without having read anything,
  which covers a timeout and an ordinary interrupt. The IDE clears the review and says the agent left.
- **stale after a deadline** — the backstop, because a `SIGKILL`ed session sends nothing at all. The
  IDE treats a review older than the skill's own deadline as stale on its own. This is exactly
  revdiff's graceful-versus-killed distinction, which phase 6 already borrowed for the handoff file, so
  it is the same idea applied one level up.

**Most of the machinery exists.** `requestIsAllowed` in `review/ReviewRestService.kt` already enforces
the three security conditions and can guard a second action unchanged. The notification group is
already registered. `WaitingReviewState` gains a field or two — it carries `sessionId`, `label`,
`outputPath` and `startedAt` today. The service is already `@Synchronized` where it needs to be.

**What not to do:** do not try to detect the read on the plugin side. There is no portable signal for
"this file was read", and anything built on access times or file locks will be wrong on some
filesystem. The agent knows it read the file; let it say so.

**The banner should stop being binary.** Once there are three signals it can say which one it is:
waiting, read at a time, or no longer waiting because the agent left. A banner that only knows
"waiting" is what makes the current one able to lie.

**This matters more in phase 8, not less.** Over an SSH tunnel the ways a handoff can fail multiply,
and a local file existing proves even less about what happened on the other machine. See
[[Sending remarks to a remote agent session]].

Still open, now answered:

- Whether an abandoned review should keep the remarks pending, or mark them pending again if they were
  already marked sent. Pending is the safe answer: nothing was delivered, so nothing was sent.
  **Answered: they are never marked sent in the first place**, so there is nothing to undo. The send
  writes the file and records which ids it wrote; only the read acknowledgement calls
  `markRemarksSent`. See `docs/claude/design.md`'s "Three signals that the remarks arrived" and the
  phase 7 plan's section "The four open questions, decided".
- Whether the deadline is the plugin's own setting or a number the skill declares when it starts the
  review. The skill declaring it keeps the two from drifting apart. **Answered: the skill declares
  it**, in the `start` request's `deadlineSeconds`, clamped at the endpoint between 60 seconds and 24
  hours (`clampDeadlineSeconds` in `review/ReviewRestService.kt`).

### Rejecting a review has to reach Claude Code, and the link should say Reject

**Built in phase 7, first, before any of the new machinery above.** `rejectWaitingReview` in
`review/SendReview.kt` writes the handoff file through the same `atomicWriteString` the send path
uses, with a body whose first line is the wire-format marker `<!-- claude-remarks: rejected -->`,
then clears the review. The banner's second link is now labelled Reject. See `docs/claude/design.md`,
"Three signals that the remarks arrived", for the phase and clearing order, and the two questions
below for how the open decisions were settled.

Found by hand on 2026-08-03, in a real IDE. This is a defect, not a wish.

`RemarksToolWindowFactory.kt:103` builds the banner's second link as
`createActionLabel("Cancel") { WaitingReviewService.getInstance(project).clear() }`, and `clear()`
in `WaitingReview.kt:91` sets the in-memory state to null and redraws the panel. It writes nothing.
Meanwhile the skill's step 6 sits in `while [ ! -e "$output" ]` for its full 1800 seconds. So
pressing Cancel ends the review inside the IDE and leaves Claude Code waiting half an hour for a
file that will now never be written. The person believes they answered. The agent believes it was
never answered. Both are wrong at once, which is worse than either.

This is the same class of problem as the rest of this section, with one difference that makes it the
first one to fix: for the other signals the IDE genuinely does not know what happened on the other
side. Here the IDE knows exactly what happened — a person decided — and throws that decision away.

**The fix reuses what is already there.** Write the handoff file with a rejection body instead of
leaving it absent, through the same `atomicWriteString` the send path uses. The skill already waits
on that path and already reads it, so nothing changes about what it waits for. It needs a
machine-checkable first line so the skill can tell a rejection from a set of remarks before it hands
the body to the model — a `<!-- claude-remarks: rejected -->` marker line and then the prose saying
so plainly. One `grep -q` in the skill, no second path, no new request field.

**Rename the link to Reject.** "Cancel" reads as "close this banner", which is exactly the behavior
it has, and that is how the defect got written in the first place. "Reject" says a decision was made
and is being sent. The word and the behavior should arrive together, in one change, so neither can
ship without the other.

Two things to settle while building it, now settled:

- Whether the rejection body should carry a reason the person typed, or just the fact. Just the fact
  is the smaller version and probably enough — the person is sitting next to the agent and can say
  more in the next message if they want to. **Answered: only the fact.** The body is a fixed
  constant, `REJECTION_BODY` in `review/SendReview.kt`; no modal input box.
- What the remarks' own status should be. Rejecting the request is not the same as discarding the
  remarks: the person may be refusing this particular handoff and still want to keep what they wrote.
  Pending is almost certainly the right answer, the same as for an abandoned review above. **Answered:
  nothing at all.** `rejectWaitingReview` never touches the store, so every remark stays exactly as it
  was — pending if it was pending, sent if a send had already recorded it. Rejecting after a send
  writes nothing to the handoff file either, so the remarks already written are not overwritten with
  the rejection body.

Also fix the skill's own text. Its step 6 currently tells the reader "A timeout does not clear the
waiting review inside the IDE — the person clears it themselves from the banner's Cancel link." That
sentence describes today's broken behavior accurately, so it stops being true the moment this is
built.

## Open the real diff for just the files the skill named

**Built in phase 7.** See `docs/claude/design.md`, "The Shared Review Session", subsection "Opening
the diff the skill asked for", for the actual shape: `ChangeListManager.getChange` decides per file
whether it has a local change, every changed file lands in one `showDiffForChange` window, and a
file with no local change still opens as a plain editor exactly as before. The one real hazard below
was settled by refusing, not mapping — see the hazard's own paragraph, now updated — and committed
ranges stayed out of scope exactly as this entry already expected.

Today a review request carries a `files` list and the IDE opens each one as a plain editor. The
skill's own comment calls this "the cheap version of the diff": the person still has to press the
IDE's diff shortcut on each file themselves. Make the IDE open a real diff instead, holding only
the files the skill named, so Claude Code decides what is under review and the person lands
directly in it.

Why: the reason for reading a diff and the reason for writing remarks are the same reason. Sending
someone into a diff of forty changed files when the review is about three of them wastes the part
of the work only a person can do.

### What the platform gives us

Verified against the checkout at `~/dev/oss/intellij-community` (tag `idea/2025.2.6.3`), not from
memory:

- `ShowDiffAction.showDiffForChange(project, Iterable<? extends Change>)` —
  `platform/vcs-impl/src/com/intellij/openapi/vcs/changes/actions/diff/ShowDiffAction.java:73`.
  Six overloads; one of them also takes a starting index. It opens one diff window over exactly the
  changes handed to it, with next-file and previous-file navigation inside it. That window holds no
  file the caller did not put there, which is the whole point.
- `ChangeListManager.getChange(VirtualFile)` —
  `platform/vcs-api/src/com/intellij/openapi/vcs/changes/ChangeListManager.java:152`, with a
  `getChange(FilePath)` overload on the next line. This turns each path the skill sent into the
  `Change` object the call above wants, and returns null for a file with no local change.

So the shape is small: map the paths that survive `filterReviewPaths` through `getChange`, drop the
nulls, and hand what is left to `showDiffForChange`. A path with no local change keeps today's
behavior and opens as a plain editor, which is also the right answer for a file the person should
read but has not touched.

### What it costs

- **A new plugin dependency.** `plugin.xml` declares only `com.intellij.modules.platform` right now.
  `ShowDiffAction` lives in `vcs-impl`, so this needs `com.intellij.modules.vcs` as well. Every
  JetBrains IDE ships VCS, so a hard `<depends>` is honest; an optional dependency with its own
  config file is the careful version and costs a second file.
- **It belongs in `OpenReviewFiles.kt`, not in the endpoint.** CLAUDE.md rule 5 greps
  `review/ReviewRestService.kt` for `FileEditorManager` and `VfsUtil` and must stay empty, and this
  work needs both. `OpenReviewFiles.kt` already exists for exactly this reason and already does its
  own `invokeLater` rather than making the HTTP response wait for editors.
- **No protocol change.** The `files` field already carries what is needed. No new request field, no
  new setting, no mode flag. The IDE decides diff-or-editor per file from whether that file has a
  local change, which is a fact the IDE already knows and the skill would only be guessing at.

### What is already built

Writing a remark from inside a diff pane mostly works today. `store/RemarkTarget.kt` has the diff
fallback: when the pane's own document belongs to a `LightVirtualFile` holding a VCS revision, the
target falls back to `DiffDataKeys.CURRENT_CONTENT`'s `highlightFile`, which is the real project
file the revision is a version of. `DiffRemarkTargetTest` covers it. So this idea is about getting
the person into the diff, not about making remarks work once they are there.

### The one real hazard

**A remark written on the old side of the diff carries the old side's line numbers.** The fallback
above finds the right *file*, but the anchor comes from the document being read, and on the "before"
pane that document is the previous revision. The line numbers in it do not describe the working
copy. Content hashing and the context lines may recover the anchor on the next resolve, or may
orphan it — neither is guaranteed. Opening a diff by default makes this case common rather than
rare, so it has to be answered as part of this work and not after it. See
[[Annotating inside the diff viewer]], which already lists the options: refuse the old side with a
sentence saying why, or map the line through the diff's own line mapping onto the new revision.
**Answered: refuse.** `remarkTargetProblem` in `store/RemarkTarget.kt` refuses a remark on the
revision side of a diff with a sentence naming the working copy as the other side, one click away.
Mapping the line through the diff's own line mapping is real work and stays a later phase.

### Committed ranges are a separate, bigger job

`ChangeListManager` only knows about uncommitted work. A review of `main..HEAD` is a review of
changes that are already committed, so `getChange` returns null for every one of them and this whole
path degrades to today's plain editors. Building `Change` objects out of two committed revisions is
real work and needs the Git plugin rather than the platform's VCS API alone. Local changes are the
case worth building first — they are the case where a person is reviewing something not yet
finished, which is when a remark is worth writing.

## A remark that belongs to no file

**Built in phase 9's group three.** See `docs/claude/design.md`, section "A Remark About No File",
for the actual shape: `store/RemarkEdits.kt`'s `addGeneralRemark`, the Add General Remark toolbar
button as the one entry point, the renderer's `## General` section rendered first with no code
block, the tree's General group at the very top, and `RemarkResolver.kt`'s `isAboutNoFile`. One
thing below turned out already true rather than something to build. See the correction under
"Where the work actually is".

Let a remark be written without pointing at any code: "the whole approach to caching here is wrong",
"this needs a CHANGELOG entry", "answer these before you touch anything". Today every remark starts
from a selection in an editor, so a thought about the change as a whole has nowhere to live and ends
up stapled to whichever line happened to be under the caret.

Why: the general remark is usually the most important one in a review. Forcing it onto an arbitrary
line makes it look local, and the model then treats it as a note about that line.

### What is already in place

Less work than it looks. `RemarkState.path` is `by string()`, so it is already nullable — no change
to what is written into `workspace.xml`, and remarks stored before this existed keep loading.
`RemarksTree.kt:56` already does `path = row.remark.path.orEmpty()`, so a pathless remark does not
crash the tree today; it lands in a group with an empty name.

### Where the work actually is

The entry point, and then every place that assumes a remark has a path.

- **A new entry point.** `AddRemarkAction` starts from `selectedLines()` on an editor. A global remark
  has no editor, so it needs its own way in — a toolbar button in the tool window is the obvious one,
  since that is the one place you are looking at remarks rather than at code.
- **The renderer needs its own section, and it should come first.**
  `PromptRenderer.kt:50-53` sorts and groups by `path` and prints `## <path>` per group. Its own
  input type declares `path` as a non-null `String`, so a global remark either needs a nullable field
  there or a separate list. A general remark placed after forty file sections has already been read
  as an afterthought — it sets the frame for everything else, so it belongs at the top.
- **The orphan path is the sharp trap.** A global remark has no `startLine`, no `textHash`, and no
  context lines. That is exactly the shape the renderer currently describes as an orphan: "stale line
  numbers, the code moved, search the file for the surrounding lines". A general note rendered through
  that path reads as a broken remark rather than as a deliberate one. These two cases must be told
  apart explicitly, and the test that proves it is the one worth writing first.
- **The resolver needed the change; the gutter did not.** `resolveAll` needed a new check,
  `isAboutNoFile`, so a pathless remark resolves as itself instead of being refused as an orphan.
  `RemarkGutter.placementsFor` needed nothing: it already filters on an exact path match against a
  real document, and a pathless remark never matches that. Task 13 proved this with a test rather
  than a code change.
- **The tree needs a real group, not the empty-string one.** A "General" node, and a decision about
  where it sits: above the files reads as more important, which matches what these remarks usually
  are. Buckets already sit above files, so the layered ordering question is one this tree has answered
  once already.
- **The history file** renders remarks too, and has the same grouping-by-file assumption.

### Not part of phase 7

Phase 7 already carries two subjects — the acknowledgement signals and opening the diff. This is a
third, it touches the renderer and the tree rather than the review handoff, and it has a real design
question in it (the orphan case above). It should be its own phase.

## The file rows in the tree show the whole path, so the file name is what gets cropped

**Built in phase 9's group four.** The first choice below is the one that shipped: a file row now
reads the file name in bold first, with the directory after it in grey, shortened to its last two
segments with a leading ellipsis when it would otherwise run long. `ui/RemarksTree.kt`'s
`shortDirectory` is the shortening function. The tree-levels alternative was not built.

Reported by hand on 2026-08-03. A file group row is labelled with the whole project-relative path,
so on any real path the row runs out of width and Swing crops the right-hand end — which is exactly
where the file name is. The one part you need to read is the one part you lose.

`RemarksTree.kt:139` is the whole cause:

```kotlin
val fileNode = DefaultMutableTreeNode(GroupNode("${keyPrefix}file:$path", path))
```

The second argument is the label, and it is the full path. `RemarksTree.kt:173` then draws it with a
single `append(user.label, REGULAR_BOLD_ATTRIBUTES)`.

### The choice

Either put the file name first and let the directory crop, or split the path into real tree levels.

**Put the file name first** — the row reads `Foo.kt` in bold, then the directory after it in grey.
The row still crops on the right, but now the directory is what is lost, and a half-visible
directory is readable while a half-visible file name is not. This is what the IDE itself does in
Find Usages and in Recent Files, so it also looks familiar.

**Split the path into tree levels** — a node per directory, so the file name gets a row to itself and
never competes with anything. Reads better on deep paths, and the tree already has a layered shape
because buckets sit above files.

## Annotate a selection inside a rendered markdown preview

**Built in phase 9's group five.** See `docs/claude/design.md`, section "A Remark on the Rendered
Preview", for the actual shape. Two things the design settled that this entry never asked about: a
preview selection is narrowed into a source range by searching for the highlighted text inside a
coarse `md-src-pos` range with one `indexOf`, not by mapping the selection through the markdown
parse tree. That is the same fallback-to-whole-line answer `markersValid` already gives when a
phrase no longer matches. And the markdown plugin is declared as an optional dependency, so the plugin still
loads with the tool window intact when Markdown is turned off; only this one entry point goes
missing.

Reported by hand on 2026-08-03. Right now a remark can only be anchored to a source file: `anchor/`
hashes lines of a real `Document` in a real file. The ask is to select text inside a rendered
markdown preview (IntelliJ's built-in preview, or the file rows this plugin already renders) and add
a remark on that selection too.

**Why this is tricky, not just another entry point.** Every existing entry point —
`AddRemarkAction`, the Alt+Enter intention, the diff pane — ends up at `remarkTargetProblem`, whose
whole job is finding a real project file and a real line range under the caret. A markdown preview
breaks that in at least two ways:

- **The preview is rendered HTML, not a `Document`.** IntelliJ's own markdown preview is a JCEF/JEditorPane
  view over the *rendered* output. A text selection inside it is a browser-style DOM range or a
  Swing text offset into HTML, not a line number in the `.md` source. Getting from "the person
  selected this rendered span" back to "these source lines" needs its own mapping, and Markdown
  syntax (headers, lists, code fences, inline emphasis) does not sit at a fixed offset from the
  rendered text the way plain code does.
- **`anchor/`'s two-pass resolve is line-hash based**, and a markdown file edited by hand drifts
  the same way source does — so the anchoring problem itself is not new. What is new is the first
  hop: turning a preview selection into a starting line range to hash, before `anchor/` ever runs.

**What is already in place.** `store/RemarkTarget.kt`'s `remarkTargetProblem` is still the one gate
every entry point goes through, so a preview entry point would want to become a third case there
rather than a parallel mechanism. `render/PromptRenderer.kt` and `anchor/` stay platform-free either
way — nothing about this idea touches them, only what feeds them a line range.

**Not part of phase 7 or any phase yet.** No design decision has been made — whether to support
IntelliJ's built-in preview, this plugin's own rendered views, or both; whether a preview selection
maps to the nearest heading/block instead of an exact line; whether it is worth the source-mapping
work at all versus telling the person to add the remark in the source file, where the tooling already
works. Needs its own brainstorm before a plan.

I would take the first. Three reasons, all of them about work already done:

- **The label change cannot break selection.** `GroupNode` already separates `key` from `label`, and
  the KDoc at `RemarksTree.kt:18-21` says why: the panel restores the selection after every rebuild
  by matching keys. So what is drawn is free to change, and `RemarksPanelTest`'s "the selection
  survives a rebuild" assertion keeps holding.
- **The two-colour row already exists in this file.** Remark rows at `RemarksTree.kt:166-170` already
  do several `append` calls with `GRAYED_ATTRIBUTES` for the dim parts. The file row needs the same
  two-append shape, not a new mechanism. `GroupNode` gains the directory as a second field, or the
  renderer splits the label at the last `/`.
- **Directory levels bring a problem the path version does not have.** One node per path segment
  turns `src/main/kotlin/dev/sasha/clauderemarks/ui/Foo.kt` into six nested nodes that each hold
  exactly one child, which is worse to read than the cropping being fixed. The standard answer is to
  compress single-child chains into one node, and that is real logic to write and to test. Worth it
  only if the file-name-first version is tried and still reads badly.

If the first version is built and deep paths still feel wrong, the middle option is to grey a
*shortened* directory — last two segments with a leading ellipsis — which is one string function and
no change to the tree's shape.

## A remark should point at the selection, not at the whole line

**Built.** The model, prompt and renderer changes below were already built by phase 8's hotfix
before phase 9 started: `RemarkState.startColumn`/`endColumn`, and `render/PromptRenderer.kt`'s
`markersValid`/`withSelectionMarkers` wrapping the exact selection in `⟦` and `⟧`. Phase 9's group
two then built what was actually still missing: the anchor, the tree's position label, and the
history file's heading. See `docs/claude/design.md`, section "The phrase a remark points at" (under
"The Anchoring Design"), for the actual shape, and the corrections below for what this entry got
wrong about the remaining work.

Noticed by Sasha on 2026-08-03. A remark stores `startLine` and `endLine` and nothing finer, so
selecting three words gets you the whole line they sit on. In code that is usually tolerable — a line
is a small enough unit to be a useful pointer. In markdown it is not: a paragraph is one long line,
so "this phrase is wrong" becomes a remark about six sentences, and the reader has to guess which
part was meant.

This matters more now than it did, for two reasons. Plan review is moving into IntelliJ, and a plan
is markdown. And [[Annotate a selection inside a rendered markdown preview]] hands back character
offsets from `md-src-pos`, not line numbers — so that idea cannot be built honestly on a line-only
model. Treat this as its prerequisite rather than a separate wish.

### The offsets are already there and get thrown away

`action/AddRemarkAction.kt:150`:

```kotlin
fun selectedLines(document: Document, selectionStart: Int, selectionEnd: Int): IntRange {
    val startLine = document.getLineNumber(selectionStart)
    val endLine = document.getLineNumber(selectionEnd)
    ...
}
```

`selectionStart` and `selectionEnd` are character offsets into the document. The function converts
them to line numbers and discards the rest. Nothing upstream loses the information — it is dropped
right here, on purpose, because line numbers were all the model could hold.

### What has to change

- **The model. Already done, by phase 8's hotfix.** `model/RemarkState.kt` already has
  `startColumn` and `endColumn`, defaulting to 0 and meaning "the whole line". `BaseState` omits
  default values when serializing, so every remark stored before this keeps loading with that
  meaning.
- **The anchor. This is where storing the phrase, not hashing it, does the work.** Hashing the
  selected text cannot recover a moved phrase. A hash only confirms a guess; producing the guess in
  the first place means searching every substring of every line, which a hash gives no way to
  narrow down. Storing the phrase text turns the same search into one `indexOf` per candidate line.
  `anchor/Anchoring.kt`'s `phraseAt` stores it when the remark is written, and `findPhrase` searches
  for it starting from the line the plain line resolve landed on, spreading outward when that line
  orphans. That is exactly what a reflowed markdown paragraph does. `contextBefore` and
  `contextAfter` already stored six lines of real source in `workspace.xml`, so a stored phrase is
  not a new kind of data there.
- **The prompt. Already done, by phase 8's hotfix.** `render/PromptRenderer.kt` already wraps the
  exact selection in `⟦` and `⟧` through `markersValid`/`withSelectionMarkers`, and
  `SEVERITY_SCALE_NOTE` already explains the two markers to the model.
- **The tree's position label.** `ui/RemarksTree.kt` now prints `9:12-38` on one line and
  `9:12-11:5` across lines, both 1-based, instead of the old `9-9`. The gutter tooltip shows the
  phrase itself alongside it.
- **The history file** renders the same sub-line heading and writes the phrase indented under it.

### What deliberately does not change

**The gutter icon stays per line.** IntelliJ's gutter is a line-level surface — there is no such thing
as an icon at a column. A sub-line remark shows its icon on the line it starts on, the same as today.
If the range should be visible in the text, that is a `RangeHighlighter` with an underline attribute,
which is a separate, optional piece of work — do not confuse it with this one.

### The honest cost

A character range is more fragile than a line range under editing, and the two-pass resolve in
`anchor/` was designed around lines. The phrase hash offsets some of that, but not all: a remark on
three words is orphaned by an edit to those three words, where the line version survived. That is
arguably correct — if the words changed, the remark about them is stale — but it will orphan more
remarks than today, and the orphan path has to stay good enough to be useful when it does.

## Ask the running session a question, instead of leaving it a remark

Sasha's idea, and it is a different thing from every other idea in this file. Every existing feature
collects something for Claude to read *later*. This one wants an answer *now*, while reading the code.

Select something, press a second control, type a question instead of a remark. The question goes to
the Claude Code session that is already running. The answer comes back and shows up near the
selection.

### Why this is the hard one: the arrow points the wrong way

Phases 6 and 7 built one direction only. The IDE is the server, the agent is the client. The agent
calls the IDE, waits, and reads. The IDE never starts anything.

This idea needs the opposite. The IDE wants to start a conversation with an agent that is busy doing
something else. Nothing built so far does that, and no amount of extending the endpoint will, because
the endpoint lives in the IDE and an endpoint cannot call out.

So this feature is not an extension of the review session. It is a second, separate channel, and it
has to be designed as one.

### `/btw` is the wrong model for this, and the documentation says why

Checked against the Claude Code documentation, "Side questions with `/btw`", rather than assumed.

`/btw` asks a question against the live conversation. The question and the answer are ephemeral: they
appear in a dismissible overlay and never enter the conversation history. It can be used while the
session is working, and it does not interrupt the main turn. It is not a fork — forking is a separate
thing a person can then choose, by pressing `f`, which starts a **new session** inheriting the parent
conversation plus that one question and answer as real transcript turns.

**The part that rules it out: a side question has no tools.** The documentation is explicit — a side
question answers only from what is already in context, and cannot read files, run commands or search.

So a question about a line the session has never read cannot be answered by `/btw`, and `/btw` cannot
go and look. For this feature that is fatal, because the whole point is asking about the code being
read right now, which is usually code the session has not seen.

The documentation names the shape in one line: `/btw` is the inverse of a subagent — it sees the full
conversation but has no tools, while a subagent has full tools but starts empty.

**What this feature needs is neither of those two. It needs both halves.** The session's context, so
the answer knows what has already been discussed, and tool access, so it can read the file the
question is about. That combination has a plain command-line form:

```sh
claude -p -r "$session_id" --fork-session "$question"
```

`-r` resumes the session, so the question is asked against that session's full recorded context.
`--fork-session` gives the resumed conversation a new session id, so the original transcript is not
appended to, not locked, and not disturbed. `-p` prints the answer to stdout and exits.

That is the whole transport, in one line, and it needs no agterm, no tmux, no send-keys and no screen
scraping. Verified present in the installed CLI: `-p/--print`, `-r/--resume`, `--fork-session`.

**This is not an imitation of `/btw`, and it should not be described as one.** It differs in three
ways, and two of them are in its favour:

- **It has tools.** `/btw` does not. This is the whole reason to prefer it: a question about the line
  being read needs the file read, and only this form can do that.
- **It reads the transcript from disk, so it does not see the turn in flight.** `/btw` sees the live
  context. This is the one way `/btw` is better, and it is a real limit, not a timing detail — see the
  third open problem below.
- **It is a separate process, so the prompt cache is a hope rather than a mechanism.** The
  documentation says `/btw` reuses the parent conversation's cache directly, which is why it is called
  low cost. A fork replays an identical prefix and should hit the cache, but nothing promises it.

**So the framing should be dropped.** Do not build this as "drive the `/btw` panel from the IDE."
Build it as "ask a session-aware question with tools, and read stdout."

### The one real choice: fork the session, or type into it

**Fork it.** The question is answered by a separate process against a copy of the session's context.
The answer arrives on a pipe, as text.

**Type into it.** The plugin uses agterm to send `/btw <question>` into the pane the session lives in,
then reads the answer back out of the session's transcript file.

What happens with the fork: the answer is clean text, arriving on stdout, with nothing to parse and
nothing to race. But the answer exists only in the IDE. Sasha's session never learns the question was
asked, so it does not appear in his scrollback, and a follow-up question does not build on the
previous answer unless the plugin keeps its own thread of them.

What happens when typing into it: the question and the answer land in the session Sasha is actually
watching. Follow-ups work by themselves, because it is one conversation. The cost is everything about
the I/O. The plugin has to shell out to agterm, know which pane, and type into a live terminal user
interface — which races with whatever turn is in progress. Getting the answer text back means reading
the session's JSONL transcript, whose format is not a promise to anyone.

The property being traded: the fork buys clean, robust plumbing and gives up shared conversation
state. Typing in keeps shared state and pays for it with fragile plumbing.

**The fork is the one to build.** Not because shared state is worthless — it is the nicer end state —
but because the fork can be built and tested, and the typing version cannot be tested at all without
a live terminal and a running session. If shared state turns out to matter, the fork is the thing that
proves the feature is worth the harder version.

### Three problems the fork still has to answer

- **Which session.** The plugin needs a session id. Transcripts live at
  `<config dir>/projects/<the working directory with slashes turned into dashes>/<session id>.jsonl`,
  so the newest transcript for this project's own directory is a cheap and usually correct guess. The
  wrinkle is the config directory: this machine has `~/.claude`, `~/.claude-work` and
  `~/.claude-personal`, chosen by `CLAUDE_CONFIG_DIR`. A guess has to search all of them, or the
  person configures the session in settings. Offer both, guess first.
- **What a question costs.** A fork replays the transcript as a prompt. On a session with a very long
  history that is a large prompt for one short question. It probably hits the prompt cache, because
  the prefix is identical — but that is a belief, not a measurement, and it should be measured before
  the feature is called cheap.
- **A question asked mid-turn.** `-r` reads the transcript from disk, and the turn in progress is not
  in it yet. So a question asked while the session is working is answered against the state before
  that work. Usually harmless, occasionally confusing, and worth saying out loud in the UI rather than
  hiding.

### Where the answer goes on screen

A question is a remark with two differences: a different kind, and a field for the answer. Everything
else already exists and should be reused — the anchoring in `anchor/`, the store, the gutter service,
the tool window tree. That is what keeps this small.

- **The gutter says where.** A different icon on the line the question starts on, and it means "there
  is an answer here." Reuse `editor/RemarkGutterIcon.kt`. Do not put the answer in the tooltip: an
  answer is markdown and can be long, and a tooltip is neither scrollable nor selectable.
- **The tool window says what.** The answer belongs in the existing tree, as a child row under the
  question, or in a second tab beside the remarks. Clicking the gutter icon jumps there. This is the
  cheap option and it is also the consistent one.
- **Block inlays are the tempting wrong first step.** IntelliJ can render a multi-line block inlay
  under the line, and that is what most AI plugins do. It looks best and it is the most work by a wide
  margin: markdown rendering, a width that reflows, and a surface that fights the editor for space.
  Build it later, if the tool window turns out to be too far from the code to be useful.

### What is given up by starting here

The forked answer is a dead end conversationally — one question, one answer, no thread. If the feature
gets used the way remarks get used, the next thing wanted will be "ask again about the same thing,"
and that means the plugin holding a real thread of its own. That is a bigger design than this, and it
should not be guessed at until the one-shot version has been used.

## Copying is not sending, and one state is doing two jobs

**Built in phase 9's group one.** See `docs/claude/design.md`, sections "The Publish Pipeline", "The
three states, and why published is not read", and "The published file", for the actual shape:
`RemarkStatus` gained `PUBLISHED` and `READ` in place of `SENT`, exactly as the three states below
describe. The copy action was renamed to Publish; the `ClaudeRemarks.CopyAll` action id keeps its
old name on purpose, since `README.md` promises it will not be renamed. Publishing also writes the
same markdown to a file under `~/.claude-remarks/`, so a Claude Code skill can read it on its own
schedule with no review ever started. The tree question at the end of this entry, where published
remarks should sit, was decided separately. See "Decided in phase 9: planned, not built" below.

Sasha's complaint: pressing Copy All Pending greys the remarks out as though they had been sent, and
that is not what happened. Something went to the clipboard. Nobody read anything.

**The complaint is right, and phase 7 is the argument for it.** `RemarkStatus` has two values,
`PENDING` and `SENT`. Its own KDoc in `model/RemarkState.kt` says `SENT` is written "once a copy
reaches the clipboard". The review path writes the same value, but only after the agent sends `ack
read`. So one state means two different things:

- the text is on the clipboard, and nothing is known about whether it ever reached an agent
- an agent said in a separate HTTP request that it read the remarks

Phase 7 built the second meaning deliberately. It made the `read` acknowledgement the only thing that
marks a remark sent, because a fetch response can be lost and the IDE must not claim a delivery that
did not happen. Then the clipboard path kept marking remarks sent with no confirmation at all. The
two paths disagree about what the same grey icon means.

### Three states, not two renamed

Renaming `SENT` to `PUBLISHED` on its own does not fix this. Both paths would still land on one state,
and the conflation would survive under a new name.

The honest model is three states:

- **Pending.** Written, not handed anywhere.
- **Published.** Handed to a channel that cannot confirm a read. The clipboard is one. A file on disk
  is another. This is a terminal state for the manual path, because pasting into a chat window is
  invisible to the IDE and always will be.
- **Read.** An agent said it read them, over the endpoint. Only the review path could produce this
  when phase 9 built it. **Built in phase 10:** a second route, `published-read`, keyed to a
  published batch's nonce rather than to a review session, so an agent can confirm a read with no
  review ever started. See `docs/claude/design.md`, "The three states, and why published is not
  read".

Then the grey icon splits into two weights, and the copy action can honestly be called Publish.

**What this costs.** `RemarkStatus` is persisted in `workspace.xml`. Adding a third value is safe as
long as `PENDING` stays the default, because `BaseState` omits defaults when it serializes. Renaming
`SENT` is not free: remarks already stored as `SENT` would no longer parse and would fall back to
pending. With one user and a project this young that is an acceptable one-time reset, but it should be
a decision rather than a surprise. Keeping the persisted name `SENT` for the read state and adding
`PUBLISHED` alongside avoids it entirely, at the cost of a name in the code that no longer matches the
label in the UI.

### Publishing writes a file as well as the clipboard

Today the clipboard is the only destination for a manual publish. The idea is to write the same
markdown to a file at the same time, and to let the skill find it.

That gives the feature something it does not have: **the person can publish first and the agent can
read later.** Every path built so far has the agent asking and then waiting. This inverts it. Mark up
some code, publish, then tell a session to go read it whenever. No banner, no deadline, no waiting
loop, no endpoint call at all on the agent side. It is likely to become the common case precisely
because it does not need the two sides to be present at the same time.

**Where the file goes.** Beside the handshake file, under `~/.claude-remarks/`, never in the
repository. The rule that nothing remark-related enters VCS still holds. The handshake file already
proves the naming scheme works: `handshakeName` in `review/ReviewHandshake.kt` hashes the project
root, so a skill running in the same repository can compute the name without being told it.

**Three cases, and they want different names.**

- **A manual publish with no review waiting.** One file per project, named from the project hash, the
  same way the handshake file is. The skill reads it when told to read published remarks.
- **A send while a review is waiting.** This already exists. The handoff file lives in a temp
  directory the review owns, and the review carries the session id, so two sessions cannot collide.
  Nothing here needs changing.
- **A send after the review has already ended.** This is the case Sasha named and the one with no
  answer today. The review timed out, so the banner is gone and Send is not offered. The remarks are
  still pending. A publish should write the per-project file, and then he tells the session to read it
  by hand. That is the same file as the first case, which is why the first case is worth having.

### The risk this adds, and it is a familiar one

A published file that sits on disk is a file an agent can read twice, or read long after it was
written. That is the same shape as the defect already recorded in `docs/claude/design.md` under Known
Issues, where a same-session retry hands back the previous review's remarks. A stale published file
would let an agent act confidently on remarks about code that has since changed.

So the file needs something the handoff file does not: a way to tell how old it is and what it was
about. The cheapest version is a timestamp and the head commit inside the file, both of which the
renderer already has. The skill can then say what it is reading and how old it is rather than reading
silently. Deleting the file after a read is the wrong answer, because nothing confirms the read on the
manual path, which is the whole reason Published is a separate state.

### Where published remarks live in the tree

Sasha's second point: greying them in place is not the right shape either.

The choice is between keeping published remarks where they are and moving them out of the working
list. Grey in place keeps the code position visible, so it is obvious what was already handed over
while reading the same file, and Copy Selected can hand it over again after a paste went to the wrong
window. A separate section keeps the pending list short, which is what matters once a session has
produced twenty remarks and eight are done with. The property being traded is context against
focus.

There is already an archive for this: `store/RemarkHistory.kt` writes cleared remarks to a history
file in the IDE configuration directory. That is a different thing though. History is for remarks that
are gone from the tree. This is about remarks still in the tree that are no longer the work.

Three shapes worth thinking about before picking one:

- **A Published group at the top level**, beside the bucket groups that already exist above the file
  groups in `ui/RemarksTree.kt`. Fits the tree's existing structure and needs no new surface.
- **A filter toggle**, so published remarks are hidden until asked for. Smallest change, and it leaves
  no visible record of what was handed over, which is the thing Sasha would then have to remember.
- **Collapse published file groups by default** and leave everything else alone. Cheapest of the
  three and it keeps position, but it only helps when whole files are done.

The first is the most likely right answer, and it is worth checking against the bucket level rather
than designed on its own: a remark can be in a bucket and published at the same time, so two grouping
axes have to agree on which one wins at the top.

### Decided in phase 9: planned, not built

The phase 9 plan wrote this idea up as an optional task, a Published group at the top of the tree.
The task was built to be dropped: nothing else in the plan depends on it. It was dropped.

The reason is simple. The project is two days old and the tree holds a handful of remarks. The
condition the task itself named for building it is more than about twenty remarks in the tree, with
a third of them handed over, and the person scrolling past grey rows to try to find the work still
left to do. That condition is not met.

There is a second reason, and it matters more than the first. Before planning, Sasha was asked
directly where published remarks should sit in the tree. He chose to decide that after living with
the three states for a while, not before. Building the group now, during planning, would answer a
question he chose to leave open on purpose.

**The real cost, if it is ever built.** The group goes at the very top, above the bucket groups, and
bucket grouping still applies inside it. So one bucket can then show up twice: once inside Published,
once outside it, for the remarks in that bucket that are still pending. That is a real cost, not a
detail to smooth over later.

**Why that order is still the right one, cost and all.** The first question the tree should answer
is what work is left to do. Everything under a Published group is, by definition, not that. So
Published has to sit above the buckets, even though it means a bucket name can appear twice.

**What grey-in-place buys today, while this stays undecided.** A published remark keeps its place in
its file group. The code position stays visible. That is what makes "this file is already handled"
readable while reading the file itself, without a second structure to check.

### One thing this does not revive

Phase 5 dropped a pluggable `Dispatcher` interface, a tmux pane and a file inside `.idea/`. See
`docs/claude/design.md`, section "The Copy Pipeline", for why. Writing one extra file next to the
handshake file is not that idea coming back. There is no dispatcher, no plugin point, no choice of
destination, and nothing in the repository. It is one more write on a path that already renders the
same markdown.
