# Claude Remarks

An IntelliJ Platform plugin that lets you attach short remarks to line ranges while reading code, without touching the source files. Remarks do not modify your working tree.

Select lines, press `Ctrl+Alt+Shift+R` (or use the "Add Claude Remark" intention through Alt+Enter), type a note, optionally pick a tag from the chip row — or press `Alt+1` through `Alt+4`, `Alt+0` for no tag — and press Enter. Every remark also carries a severity level (`vibe` / `suggestion` / `should` / `must`, defaulting to `should`) that tells the model reading the copied prompt how strongly to act on it; change it afterwards from the gutter icon menu or the tool window's right-click menu, not in the input box, which stays a plain text area and a chip row so writing a remark never slows down. `Ctrl+Space` in the remark box opens a chooser that inserts a class name from the project at the caret. A gutter icon appears on the marked lines and follows the code as you keep editing. The tool window lists every remark as a tree grouped by file, with a bucket level above the files once you put any remark into one. When you are ready, press Copy All Pending in the tool window: every pending remark becomes one markdown prompt on the clipboard — each heading carries its tag, its severity, and the short sha of the commit it was written against, if there is one — a balloon says how many, and you paste it into a Claude Code session. Copied remarks turn gray in the tool window rather than disappearing, so Copy Selected can send them again if the paste went to the wrong place.

Click a gutter icon to edit or delete that remark, or to change its severity or move it to a bucket from the same menu. In the tool window, right-click a row for that same severity-and-bucket menu; select a row and press Delete to remove it, or select several rows and their files and choose Move to Bucket to sort a whole reading pass in one step. Selecting a bucket node and pressing Copy Selected copies every remark in it, which is why there is no separate Copy Bucket button. Selecting a whole file or bucket node and pressing Delete stands for every remark under it, and that case asks first. The toolbar has five buttons: **Copy All Pending**, **Copy Selected** (only the selected rows, already-sent ones included), **Clear Sent** and **Clear All** (both ask first, name how many, and archive the cleared remarks to a history file before removing anything — a single Delete on one row is the exception and does not archive), and **Refresh**. **Tools → Copy All Pending Claude Remarks** does the same copy without the tool window open, and can be given a keyboard shortcut in the keymap.

Remarks stay on your machine, stored in `.idea/workspace.xml`. The `.idea/.gitignore` that the IDE generates excludes that file, so remarks stay out of version control. A repository that deliberately tracks `.idea/workspace.xml` is the exception: there they would be committed like any other change to that file. Clearing writes the remarks that are about to go to a markdown file in the IDE configuration directory (under `claude-remarks/`) before removing them from the active list — that file is outside every project, so it can never be committed by accident either. The instruction header shown at the top of every copied prompt is editable in **Settings → Tools → Claude Remarks**; the severity scale's explanation is appended below it on every copy and is not part of that editable text, so rewriting the header cannot silently drop what the levels mean.

## Phases

- **Phase 1-2**: Storage, persistence, and the two-pass anchoring search that keeps a remark pointed at the right lines as the file changes around it.
- **Phase 3-4**: The input popup, the gutter icon, the tree tool window, the settings page, and the Copy Remarks action described above.
- **Phase 5** (this build): Severity and named buckets, tag chips picked from the keyboard, a commit stamp read straight out of `.git`, a history file that cleared remarks are archived to instead of deleted, and the `Ctrl+Space` class-name insert — all described above, and in more depth in `docs/claude/design.md`.

An earlier brief also planned a pluggable dispatch step beyond the clipboard — a `Dispatcher` interface, a tmux pane, a file inside `.idea/`. That was dropped before it was built: Copy Remarks already gets a prompt into a Claude Code session with none of that machinery. See `docs/claude/design.md`, section "The Copy Pipeline", for the reasoning.

This build has been through unit tests only. `./gradlew runIde` has not been run against it in the sessions that built it — see "Running in a Sandbox IDE" below before treating any of it as verified end to end.

## Building

You need a JDK (17 through 25) and network access on the first build. Gradle itself comes with the project as a wrapper, so nothing has to be installed for it. The first run downloads its own JDK 21 through the foojay resolver and the IDEA 2025.2 distribution, which took about 3m30s on a cold cache.

```bash
./gradlew build      # compiles, runs the tests, assembles
./gradlew buildPlugin
```

`buildPlugin` writes the installable plugin as `build/distributions/claude-remarks-0.1.1.zip`. Plain jars land in `build/libs/`; the zip is what an IDE installs.

## Running in a Sandbox IDE

To test the plugin in an isolated IntelliJ instance:

```bash
./gradlew runIde
```

The sandbox IDE launches with the plugin loaded. Open or create any project inside it, open a file, select some lines, and press `Ctrl+Alt+Shift+R` (or place the caret on a line and use Alt+Enter, then pick "Add Claude Remark"). Type a note, optionally pick a tag, and press Enter. A gutter icon should appear on the marked lines, and the "Claude Remarks" tool window on the right edge should show the remark under its file without pressing anything. Typing lines above the marked block should move the icon with the code. With a remark pending, press Copy All Pending in the tool window's toolbar and paste somewhere to see the rendered prompt — the remark's row should turn gray afterward. Close and reopen the sandbox IDE to confirm the remark, its tag, and its status persist.

## Installing into your own IDE

Build the zip, then in the IDE: **Settings → Plugins → the gear icon → Install Plugin from Disk…**, pick `build/distributions/claude-remarks-0.1.1.zip`, and restart when asked. The plugin needs a 2025.2 or newer build (`sinceBuild = 252`, no upper bound set).

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

159 tests. Most are plain JUnit with no fixture and run in milliseconds: anchoring, the stored state's XML round trip and its mutators, the resolver helpers, the tree's node-building, the markdown renderer, and the settings round trip. The rest start a light IDE fixture (`BasePlatformTestCase`) and are slower, because each goes through a real project service, a real `Document`, or a real markup model — among them the mutation functions and their change notification, the input popup's key bindings, the Add Remark action, the gutter icon renderer's equality, the gutter service itself, the tool window's tree and its navigation, the payload collector, and the copy action.

There are no UI-rendering or end-to-end tests. The popup appearing at the caret, the gutter icon painting, the tree colours, the balloon, and the settings page layout are checked by hand in a sandbox IDE — see "Running in a Sandbox IDE" above.

## Architecture

- `src/main/kotlin/dev/sasha/clauderemarks/anchor/`: Pure Kotlin, no platform imports. Logic for hashing lines and finding anchored text after files change.
- `src/main/kotlin/dev/sasha/clauderemarks/model/`: The `RemarkState` record and its enums (`RemarkTag`, `RemarkStatus`).
- `src/main/kotlin/dev/sasha/clauderemarks/store/`: `RemarkStore.kt`, the project service that persists remarks; `RemarkEdits.kt`, the six functions that are the only way production code changes a remark, plus the `REMARKS_CHANGED` notification; `RemarkResolver.kt`, which turns stored remarks into resolved rows; `RemarkTarget.kt`, which decides where a remark on the current editor would go; and `ContextFormat.kt`, which says how context lines are written into a remark and read back.
- `src/main/kotlin/dev/sasha/clauderemarks/ui/`: `RemarkInputPanel.kt`, the popup that captures a remark; `RemarksTree.kt` and `RemarksToolWindowFactory.kt`, the tool window's tree and its toolbar.
- `src/main/kotlin/dev/sasha/clauderemarks/action/`: `AddRemarkAction.kt` (the shortcut and popup-menu entry point) and `AddRemarkIntention.kt` (the Alt+Enter entry point), both opening the same input popup; `CopyRemarks.kt`, the copy pipeline.
- `src/main/kotlin/dev/sasha/clauderemarks/editor/`: `RemarkGutterIcon.kt` (the icon renderer) and `RemarkGutter.kt` (the project service that keeps gutter icons in step with the code), started by `RemarkGutterStartup.kt`.
- `src/main/kotlin/dev/sasha/clauderemarks/render/`: `PromptRenderer.kt`, pure Kotlin, turns resolved remarks into the markdown prompt; `PromptPayload.kt`, reads the code around each remark and decides whether the payload goes on the clipboard directly or through a temp file.
- `src/main/kotlin/dev/sasha/clauderemarks/settings/`: The app-level service holding the editable prompt header, and its settings page.

See `docs/claude/design.md` for a deeper look at how anchoring, the gutter, the change notification, and the copy pipeline work.
