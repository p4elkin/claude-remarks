# Claude Remarks

An IntelliJ Platform plugin that lets you attach short remarks to line ranges while reading code, without touching the source files. Remarks do not modify your working tree.

Select lines, press `Ctrl+Alt+Shift+R` (or use the "Add Claude Remark" intention through Alt+Enter), type a note, optionally pick a tag from the chip row — or press `Alt+1` through `Alt+4`, `Alt+0` for no tag — and press Enter. Every remark also carries a severity level (`vibe` / `suggestion` / `should` / `must`, defaulting to `should`) that tells the model reading the copied prompt how strongly to act on it; change it afterwards from the gutter icon menu or the tool window's right-click menu, not in the input box, which stays a plain text area and a chip row so writing a remark never slows down. `Cmd+Ctrl+Shift+Space` in the remark box (`Ctrl+Alt+Shift+Space` on Windows and Linux) opens a chooser that inserts a class name from the project at the caret — not `Ctrl+Space`, which the IDE's own Basic Completion is offered even inside a popup, and which macOS takes for switching input source. A gutter icon appears on the marked lines and follows the code as you keep editing. The tool window lists every remark as a tree grouped by file, with a bucket level above the files once you put any remark into one. When you are ready, press Copy All Pending in the tool window: every pending remark becomes one markdown prompt on the clipboard — each heading carries its tag, its severity, and the short sha of the commit it was written against, if there is one — a balloon says how many, and you paste it into a Claude Code session. Copied remarks turn gray in the tool window rather than disappearing, so Copy Selected can send them again if the paste went to the wrong place.

Click a gutter icon to edit or delete that remark, or to change its severity or move it to a bucket from the same menu. In the tool window, right-click a row for that same severity-and-bucket menu; select a row and press Delete to remove it, or select several rows and their files and choose Move to Bucket to sort a whole reading pass in one step. Selecting a bucket node and pressing Copy Selected copies every remark in it, which is why there is no separate Copy Bucket button. Selecting a whole file or bucket node and pressing Delete stands for every remark under it, and that case asks first. The toolbar has five buttons: **Copy All Pending**, **Copy Selected** (only the selected rows, already-sent ones included), **Clear Sent** and **Clear All** (both ask first, name how many, and archive the cleared remarks to a history file before removing anything — a single Delete on one row is the exception and does not archive), and **Refresh**. **Tools → Copy All Pending Claude Remarks** does the same copy without the tool window open, and can be given a keyboard shortcut in the keymap.

Remarks stay on your machine, stored in `.idea/workspace.xml`. The `.idea/.gitignore` that the IDE generates excludes that file, so remarks stay out of version control. A repository that deliberately tracks `.idea/workspace.xml` is the exception: there they would be committed like any other change to that file. Clearing writes the remarks that are about to go to a markdown file in the IDE configuration directory (under `claude-remarks/`) before removing them from the active list — that file is outside every project, so it can never be committed by accident either. The instruction header shown at the top of every copied prompt is editable in **Settings → Tools → Claude Remarks**; the severity scale's explanation is appended below it on every copy and is not part of that editable text, so rewriting the header cannot silently drop what the levels mean.

## Phases

- **Phase 1-2**: Storage, persistence, and the two-pass anchoring search that keeps a remark pointed at the right lines as the file changes around it.
- **Phase 3-4**: The input popup, the gutter icon, the tree tool window, the settings page, and the Copy Remarks action described above.
- **Phase 5**: Severity and named buckets, tag chips picked from the keyboard, a commit stamp read straight out of `.git`, a history file that cleared remarks are archived to instead of deleted, and the `Cmd+Ctrl+Shift+Space` class-name insert — all described above, and in more depth in `docs/claude/design.md`.
- **Phase 6**: A review session shared between a Claude Code skill and the IDE — described in "Reviewing with a Waiting Claude Code Session" below, and in more depth in `docs/claude/design.md`, section "The Shared Review Session".
- **Phase 7**: The review session tells the truth about what happened to it. Rejecting it in the banner now writes that decision to the handoff file instead of only closing the banner, and the link is called Reject. A remark is marked sent only once the skill acknowledges it read the handoff file, not the moment the file is written; a review that never gets a reply goes stale on its own deadline, declared by the skill and enforced by the IDE. The review also opens a real diff over just the files the skill named, instead of a plain editor per file — described in "Reviewing with a Waiting Claude Code Session" below, and in more depth in `docs/claude/design.md`, section "The Shared Review Session".
- **Phase 8** (this build): A Claude Code session on another machine can read remarks too, over an SSH tunnel the person sets up by hand. The IDE's endpoint gains a fetch action that returns the handoff file's content in the response body instead of a path, since a path on the IDE machine means nothing to an agent on a different one. Fetching never marks anything sent. The `read` acknowledgement still does that, so it is safe to repeat as often as the skill's poll needs. Described in "Reviewing with a Waiting Claude Code Session" below, and in more depth in `docs/claude/design.md`, section "The Shared Review Session", subsection "Reaching an agent on another machine".

An earlier brief also planned a pluggable dispatch step beyond the clipboard — a `Dispatcher` interface, a tmux pane, a file inside `.idea/`. That was dropped before it was built: Copy Remarks already gets a prompt into a Claude Code session with none of that machinery. See `docs/claude/design.md`, section "The Copy Pipeline", for the reasoning. Phase 6 below adds a different, later automated path; that earlier idea stays dropped regardless.

This build has been through unit tests only. `./gradlew runIde` has not been run against it in the sessions that built it — see "Running in a Sandbox IDE" below before treating any of it as verified end to end.

## Reviewing with a Waiting Claude Code Session

**The plugin works exactly as described above with no skill installed and nothing listening.** The
clipboard path is unchanged: Copy All Pending and Copy Selected still put a markdown prompt on the
clipboard, and nothing about them requires anything on the other end. What follows is an addition
next to that path, never a replacement for it.

A Claude Code skill (`docs/skill/claude-remarks-review/SKILL.md`) can ask a running IDE to hold a
review open for a repository. It reads a small handshake file the plugin writes under
`~/.claude-remarks/` when the project opens, then sends one HTTP request to the IDE's own built-in
server. If the IDE accepts, a banner appears at the top of the Claude Remarks tool window: "Claude
Code is waiting: <label>", and — if the request named files that have a local change — the person
lands directly in a diff of just those files, with the rest opened as plain editors; the "Reviewing"
walkthrough above is the plain-editor case, and this is what happens instead once the skill names
files. Read and write remarks as usual, then press **Send to Claude Code** — either the toolbar
button that appears next to the others while a review is waiting, or **Tools → Send Claude Remarks
to the Waiting Session**, which works even with the tool window closed. Every pending remark is
rendered the same way Copy All Pending renders them and written to a file the skill has been waiting
for; the banner then says the remarks are waiting to be read, the toolbar button greys out, and
pressing Send again from the banner or from Tools answers that it is already sent, so a second press
cannot overwrite what was just written. Nothing is marked sent yet — that happens only
once the skill acknowledges it actually read the file, at which point the remarks turn gray exactly
as they do after a copy and the banner disappears. Pressing **Reject** in the banner instead writes
that decision to the handoff file — so a Claude Code session waiting on it hears about it within a
second or two, instead of waiting out its own timeout — and clears the review; every remark stays
exactly as it was. Reject *after* a send writes nothing — the file already holds the remarks — and
only closes the review, saying so. If the skill never answers at all, the review goes stale on its own after the
deadline the skill declared when it started, and the banner disappears with a balloon saying the
agent left; nothing sent this way is ever lost, since the remarks it wrote were never marked sent in
the first place.

Writing a remark from a diff pane's revision side (the "before" of a change) is refused rather than
stored: its line numbers describe the revision, not the file on disk, and the working copy is one
click away. Working-copy remarks are unaffected.

**The same machine is the normal case.** Both the handshake file and the handoff file are local
paths, on the machine the IDE runs on. A Claude Code session on that same machine reads them
directly. Nothing extra is needed beyond installing the skill.

**Reaching a session on another machine needs a tunnel, and four values.** The person sets the
tunnel up by hand, with `ssh -R`. Then they give the skill four things: the tunnel's local port on
the agent machine, the token from the IDE machine's handshake file, the repository path as the IDE
machine sees it, and the host, only if it is not `127.0.0.1`. `SKILL.md`'s "Over SSH" section has the
two lines that read the port and the token on the IDE machine, and the `ssh -R` command that starts
the tunnel from there.

Nothing about a missing tunnel is silent. The built-in server only binds `127.0.0.1`, so a request
with no tunnel gets connection refused. The skill says so and stops, rather than retrying or
guessing.

## Building

You need a JDK (17 through 25) and network access on the first build. Gradle itself comes with the project as a wrapper, so nothing has to be installed for it. The first run downloads its own JDK 21 through the foojay resolver and the IDEA 2025.2 distribution, which took about 3m30s on a cold cache.

```bash
./gradlew build      # compiles, runs the tests, assembles
./gradlew buildPlugin
```

`buildPlugin` writes the installable plugin as `build/distributions/claude-remarks-<version>.zip`, where the version is the one in `build.gradle.kts`. Plain jars land in `build/libs/`; the zip is what an IDE installs.

## Running in a Sandbox IDE

To test the plugin in an isolated IntelliJ instance:

```bash
./gradlew runIde
```

The sandbox IDE launches with the plugin loaded. Open or create any project inside it, open a file, select some lines, and press `Ctrl+Alt+Shift+R` (or place the caret on a line and use Alt+Enter, then pick "Add Claude Remark"). Type a note, optionally pick a tag, and press Enter. A gutter icon should appear on the marked lines, and the "Claude Remarks" tool window on the right edge should show the remark under its file without pressing anything. Typing lines above the marked block should move the icon with the code. With a remark pending, press Copy All Pending in the tool window's toolbar and paste somewhere to see the rendered prompt — the remark's row should turn gray afterward. Close and reopen the sandbox IDE to confirm the remark, its tag, and its status persist.

That walkthrough covers the phase 3-4 flow only. Phase 5 added severity, buckets, the `Alt+0`-`Alt+4` tag keys, the class-name insert, the commit stamp and the history file, and none of those is checked by an automated test end to end — the key combinations in particular can be taken by the IDE keymap or by the OS before the plugin sees them. Section 10 of `docs/plans/20260803-claude-remarks-phase5.md` lists ten specific hand checks for exactly these. Work through that list in the sandbox before trusting any of phase 5. Phase 6 and phase 7 each added their own list, and section 12 of `docs/plans/20260805-claude-remarks-phase7.md` is the one that matters most: it is the only thing that can show the scheduled deadline really firing, the read acknowledgement turning remarks gray, and one real diff window opening over just the changed files. None of those has been run yet. Phase 8 added its own list too, in section 13 of `docs/plans/20260803-claude-remarks-phase8.md`, and it needs something no earlier phase did: a second machine, an `sshd`, and an agent session on the far side of a tunnel, for the checks that prove the remote path actually works. None of those has been run either.

## Installing into your own IDE

Build the zip, then in the IDE: **Settings → Plugins → the gear icon → Install Plugin from Disk…**, pick the zip `buildPlugin` wrote under `build/distributions/`, and restart when asked. The plugin needs a 2025.2 or newer build (`sinceBuild = 252`, no upper bound set). It also needs an IDE that ships the VCS module — every JetBrains IDE does, but if the plugin ever fails to load and the tool window simply does not appear, that hard `<depends>com.intellij.modules.vcs</depends>` is the first thing to check.

## IdeaVim

IdeaVim can run any registered action by id with `:action <id>`, so the plugin works from a
`.ideavimrc` mapping with no extra code. These three ids are a public interface and will not be
renamed:

| Id | What it does |
| --- | --- |
| `ClaudeRemarks.AddRemark` | Open the remark box on the selection, or on the caret line |
| `ClaudeRemarks.CopyAll` | Turn every pending remark into one prompt on the clipboard |
| `ActivateClaudeRemarksToolWindow` | Open and focus the Claude Remarks tool window |

Example mappings:

```vim
nnoremap <leader>r :action ClaudeRemarks.AddRemark<CR>
vnoremap <leader>r :action ClaudeRemarks.AddRemark<CR>
nnoremap <leader>c :action ClaudeRemarks.CopyAll<CR>
nnoremap <leader>R :action ActivateClaudeRemarksToolWindow<CR>
```

Two things to check the first time you use it, rather than assume:

- **Visual mode.** Select lines with `V`, then `<leader>r`. `:action` invoked from visual mode has
  historically been awkward about whether the selection still exists when the action runs. The
  action reads `editor.selectionModel`, so if the selection is gone it falls back to the caret line
  and you get a one-line remark instead of the block you chose. If that happens, the fix is on the
  mapping side, not in the plugin.
- **Typing in the box.** The remark box is a plain Swing text area, which IdeaVim does not touch, so
  typing, Enter and Escape all behave normally.

## Testing

Run all tests:

```bash
./gradlew test
```

Roughly two thirds of the suite is plain JUnit with no fixture and runs in milliseconds: anchoring (`AnchoringTest`), the stored state's XML round trip and its mutators (`RemarkStoreStateTest`), the resolver helpers, the tree's node-building (`RemarksTreeTest`), the markdown renderer (`PromptRendererTest`), the settings round trip, the commit reader against real `.git` directories built on disk (`GitHeadTest`), and the archive's rendering (`RemarkHistoryTest`). The rest start a light IDE fixture (`BasePlatformTestCase`) and are slower, because each goes through a real project service, a real `Document`, or a real markup model: the eight mutation functions and their change notification (`RemarkEditsTest`), the input popup's key bindings (`RemarkInputPanelTest`), the Add Remark action (`AddRemarkActionTest`, `DiffRemarkTargetTest`), the action ids a `.ideavimrc` maps (`ActionIdsTest`), the severity-and-bucket menu (`RemarkActionsTest`), the class-name insert (`ClassNameInsertTest`), the gutter icon renderer's equality (`RemarkGutterIconTest`), the gutter service itself (`RemarkGutterTest`), the tool window's tree and its navigation (`RemarksPanelTest`, `NavigationLineBaseTest`), the payload collector (`PromptPayloadTest`), and the copy action (`CopyRemarksTest`). The shared review session has its own set, split the same way: `AtomicWriteTest`, `ReviewHandshakeTest`, `ReviewRequestTest` and `WaitingReviewTest` need no fixture, while `WaitingReviewServiceTest`, `ReviewEndpointSmokeTest`, `SendReviewTest` and `OpenReviewFilesTest` do. No count is given on purpose: it goes stale on the next commit, and `./gradlew test` prints the real one.

There are no UI-rendering or end-to-end tests. The popup appearing at the caret, the gutter icon painting, the tree colours, the balloon, and the settings page layout are checked by hand in a sandbox IDE — see "Running in a Sandbox IDE" above.

## Architecture

- `src/main/kotlin/dev/sasha/clauderemarks/anchor/`: Pure Kotlin, no platform imports. Logic for hashing lines and finding anchored text after files change.
- `src/main/kotlin/dev/sasha/clauderemarks/model/`: The `RemarkState` record and its enums (`RemarkTag`, `RemarkStatus`).
- `src/main/kotlin/dev/sasha/clauderemarks/store/`: `RemarkStore.kt`, the project service that persists remarks; `RemarkEdits.kt`, the eight functions that are the only way production code changes a remark, plus the `REMARKS_CHANGED` notification; `RemarkResolver.kt`, which turns stored remarks into resolved rows; `RemarkTarget.kt`, which decides where a remark on the current editor would go, and refuses one on the revision side of a diff; `ContextFormat.kt`, which says how context lines are written into a remark and read back; `GitHead.kt`, which reads the repository HEAD straight out of `.git` — it still needs no VCS plugin API of its own, even though the plugin as a whole now depends on `com.intellij.modules.vcs` for the diff window described below; and `RemarkHistory.kt`, the markdown archive that cleared remarks are written to.
- `src/main/kotlin/dev/sasha/clauderemarks/ui/`: `RemarkInputPanel.kt`, the popup that captures a remark, its key bindings and its tag chips; `RemarkActions.kt`, the severity-and-bucket menu shared by the gutter icon and the tree; `ClassNameInsert.kt`, the class-name chooser the remark box opens; `RemarksTree.kt` and `RemarksToolWindowFactory.kt`, the tool window's tree and its toolbar.
- `src/main/kotlin/dev/sasha/clauderemarks/action/`: `AddRemarkAction.kt` (the shortcut and popup-menu entry point) and `AddRemarkIntention.kt` (the Alt+Enter entry point), both opening the same input popup; `CopyRemarks.kt`, the copy pipeline.
- `src/main/kotlin/dev/sasha/clauderemarks/editor/`: `RemarkGutterIcon.kt` (the icon renderer) and `RemarkGutter.kt` (the project service that keeps gutter icons in step with the code), started by `RemarkGutterStartup.kt`.
- `src/main/kotlin/dev/sasha/clauderemarks/render/`: `PromptRenderer.kt`, pure Kotlin, turns resolved remarks into the markdown prompt; `PromptPayload.kt`, reads the code around each remark and decides whether the payload goes on the clipboard directly or through a temp file.
- `src/main/kotlin/dev/sasha/clauderemarks/review/`: the shared review session. `ReviewHandshake.kt` writes the file a skill reads to find this IDE; `ReviewRestService.kt` is the endpoint at `POST /api/claude-remarks/{start,ack}`; `WaitingReview.kt` holds the one waiting review per project, its phase and its deadline; `SendReview.kt` writes the handoff file, writes a rejection, and carries out what an acknowledgement means; `OpenReviewFiles.kt` opens the diff or the plain editors; `AtomicWrite.kt` is the temp-file-then-rename write both handoff writes use.
- `src/main/kotlin/dev/sasha/clauderemarks/settings/`: The app-level service holding the editable prompt header, and its settings page.

See `docs/claude/design.md` for a deeper look at how anchoring, the gutter, the change notification, and the copy pipeline work.
