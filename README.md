# Claude Remarks

An IntelliJ Platform plugin that lets you attach short remarks to line ranges while reading code, without touching the source files. Remarks do not modify your working tree.

Select lines, press `Ctrl+Alt+Shift+R` (or use the "Add Claude Remark" intention through Alt+Enter), type a note, optionally pick a tag, and press Enter. A gutter icon appears on the marked lines and follows the code as you keep editing. The tool window lists every remark as a tree grouped by file. When you are ready, press Copy All Pending in the tool window: every pending remark becomes one markdown prompt on the clipboard, a balloon says how many, and you paste it into a Claude Code session. Copied remarks turn gray in the tool window rather than disappearing, so Copy Selected can send them again if the paste went to the wrong place.

Remarks stay on your machine, stored in `.idea/workspace.xml`. The `.idea/.gitignore` that the IDE generates excludes that file, so remarks stay out of version control. A repository that deliberately tracks `.idea/workspace.xml` is the exception: there they would be committed like any other change to that file. The instruction header shown at the top of every copied prompt is editable in **Settings → Tools → Claude Remarks**.

## Phases

- **Phase 1-2**: Storage, persistence, and the two-pass anchoring search that keeps a remark pointed at the right lines as the file changes around it.
- **Phase 3-4** (this build): The input popup, the gutter icon, the tree tool window, the settings page, and the Copy Remarks action described above.
- **Phase 5 does not exist.** An earlier brief planned a pluggable dispatch step beyond the clipboard — a `Dispatcher` interface, a tmux pane, a file inside `.idea/`. That was dropped before it was built, because Copy Remarks already gets a prompt into a Claude Code session with none of that machinery. See `docs/claude/design.md`, section "The Copy Pipeline", for the reasoning.

This build has been through unit tests only. `./gradlew runIde` has not been run against it in the sessions that built it — see "Running in a Sandbox IDE" below before treating any of it as verified end to end.

## Building

You need a JDK (17 through 25) and network access on the first build. Gradle itself comes with the project as a wrapper, so nothing has to be installed for it. The first run downloads its own JDK 21 through the foojay resolver and the IDEA 2025.2 distribution, which took about 3m30s on a cold cache.

```bash
./gradlew build      # compiles, runs the tests, assembles
./gradlew buildPlugin
```

`buildPlugin` writes the installable plugin as `build/distributions/claude-remarks-0.1.0.zip`. Plain jars land in `build/libs/`; the zip is what an IDE installs.

## Running in a Sandbox IDE

To test the plugin in an isolated IntelliJ instance:

```bash
./gradlew runIde
```

The sandbox IDE launches with the plugin loaded. Open or create any project inside it, open a file, select some lines, and press `Ctrl+Alt+Shift+R` (or place the caret on a line and use Alt+Enter, then pick "Add Claude Remark"). Type a note, optionally pick a tag, and press Enter. A gutter icon should appear on the marked lines, and the "Claude Remarks" tool window on the right edge should show the remark under its file without pressing anything. Typing lines above the marked block should move the icon with the code. With a remark pending, press Copy All Pending in the tool window's toolbar and paste somewhere to see the rendered prompt — the remark's row should turn gray afterward. Close and reopen the sandbox IDE to confirm the remark, its tag, and its status persist.

## Installing into your own IDE

Build the zip, then in the IDE: **Settings → Plugins → the gear icon → Install Plugin from Disk…**, pick `build/distributions/claude-remarks-0.1.0.zip`, and restart when asked. The plugin needs a 2025.2 or newer build (`sinceBuild = 252`, no upper bound set).

## Testing

Run all tests:

```bash
./gradlew test
```

142 tests. Most are plain JUnit with no fixture and run in milliseconds: anchoring, the stored state's XML round trip and its mutators, the resolver helpers, the tree's node-building, the markdown renderer, and the settings round trip. The rest start a light IDE fixture (`BasePlatformTestCase`) and are slower, because each goes through a real project service, a real `Document`, or a real markup model — among them the mutation functions and their change notification, the input popup's key bindings, the Add Remark action, the gutter icon renderer's equality, the gutter service itself, the tool window's navigation, the payload collector, and the copy action.

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
