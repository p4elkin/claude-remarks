# Claude Remarks — Working Notes

This project builds a plugin for IntelliJ that lets you mark up code with remarks while reading,
then turn them all into one prompt for a Claude Code session.

Phases 1-4 are implemented and covered by unit tests. None of it has been loaded into a running
IDE in this run: every `runIde` check in the phase 1-2 and phase 3-4 plans was skipped in the
autonomous sessions that did the work, so treat "it works" as "the tests pass" until someone does
the hand checks listed at the end of each plan. Select lines, press `Ctrl+Alt+Shift+R` (or use the
"Add Claude Remark" intention through Alt+Enter), type a note, optionally pick a tag, and press
Enter. A gutter icon appears on the marked lines and follows the code as you keep editing. The
tool window lists every remark as a tree grouped by file. Press Copy All Pending in the tool
window to turn every pending remark into one markdown prompt on the clipboard; a balloon says how
many. Copied remarks turn gray rather than disappearing, so Copy Selected can send them again if
the paste went to the wrong place.

For the design — how anchoring, the gutter, the change notification, and the copy pipeline work —
see `docs/claude/design.md`.

Phase 5, an automated dispatch step beyond the clipboard, does not exist and was dropped before it
was built. See `docs/claude/design.md`, section "The Copy Pipeline", for why.

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

3. **`store/RemarkEdits.kt` holds the only six functions that change a remark.**
   `RemarkStore`'s own mutators stay public, and `RemarkEdits.kt` sits in the same package, so
   nothing but this check keeps the claim true. A caller that reaches past the six functions
   mutates the store without telling the gutter or the tool window to redraw.

   ```bash
   grep -rnE "RemarkStore\.getInstance\([^)]*\)\.(add|remove|edit|markSent|removeSent|clear)\(" \
     src/main/kotlin --include=*.kt | grep -v RemarkEdits.kt   # must be empty
   ```

   Test code is outside this one on purpose: fixture-backed test classes call
   `RemarkStore.getInstance(project).clear()` in `setUp` to clear the shared light-fixture project
   between test classes.

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

Every command above must come back empty.

## Project structure

```
src/main/kotlin/dev/sasha/clauderemarks/
  anchor/Anchoring.kt              hashing, capture, the two-pass resolve. No platform imports.
  model/RemarkState.kt             the persisted record, RemarkTag (+ its label extension), RemarkStatus
  store/RemarkStore.kt             @Service project component, state in workspace.xml
  store/RemarkEdits.kt             the six mutation functions, the REMARKS_CHANGED topic
  store/RemarkResolver.kt          projectRoot, resolveAll, and anchorOf
  store/RemarkTarget.kt            relativePathOf, remarkTargetProblem
  store/ContextFormat.kt           joinContext/splitContext, how context lines are stored
  ui/RemarkInputPanel.kt           the popup's panel, the Enter/Shift+Enter keys, tag labels
  ui/RemarksTree.kt                node building and the tree cell renderer
  ui/RemarksToolWindowFactory.kt   RemarksPanel: the tree, the toolbar, self-refresh on REMARKS_CHANGED
  action/AddRemarkAction.kt        the shortcut / popup-menu entry point, plus selectedLines()
  action/AddRemarkIntention.kt     the Alt+Enter entry point
  action/CopyRemarks.kt            copyRemarks(project, ids), the whole copy pipeline, plus the
                                   Tools-menu action that calls it without the tool window
  editor/RemarkGutterIcon.kt       the placement record, the tooltip, the gutter icon renderer
  editor/RemarkGutter.kt           the project service that keeps gutter icons in step
  editor/RemarkGutterStartup.kt    the ProjectActivity that starts RemarkGutter
  settings/RemarkSettings.kt       the app-level service and the default prompt header
  settings/RemarkSettingsConfigurable.kt
  render/PromptRenderer.kt         pure Kotlin, zero platform imports. Remarks to markdown.
  render/PromptPayload.kt          collectForPrompt and clipboardPayload
src/main/resources/META-INF/plugin.xml
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

## Commands

```bash
./gradlew test                              # the whole suite
./gradlew build                             # compile, test, assemble
./gradlew buildPlugin                       # build/distributions/claude-remarks-0.1.0.zip
./gradlew verifyPluginProjectConfiguration  # after any plugin.xml or build.gradle.kts change
./gradlew verifyPlugin                      # compatibility report against the target IDE
```

Do not run `./gradlew runIde` from an agent session: it starts an interactive sandbox IDE that
never exits on its own.

## Testing

Anchoring, storage round-trips, the resolver helpers, the tree's node-building, the markdown
renderer, and the settings round trip are plain JUnit tests with no fixture, so they run in
milliseconds. The rest need a light IDE fixture (`BasePlatformTestCase`, which needs
`testFramework(TestFrameworkType.Platform)` in `build.gradle.kts`) and are slower, because each
goes through a real project service, a real `Document`, or a real markup model:
`RemarkStoreServiceTest`, `ResolveAllTest` (stored remarks resolved against real files, including
a path that tries to climb out of the project), `SelectedLinesTest` (the selection line math
against a real `Document`), `RemarkEditsTest` (the six mutation functions publish
`REMARKS_CHANGED`), the key-binding half of `RemarkInputPanelTest`, `AddRemarkActionTest`, the
renderer-equality half of `RemarkGutterIconTest`, `RemarkGutterTest` (the gutter service),
`RemarksPanelTest` (the tool window panel: every file group ends up expanded, and the selection
survives a rebuild), `NavigationLineBaseTest` (pins `OpenFileDescriptor`'s 0-based line argument),
the collector half
of `PromptPayloadTest`, and `CopyRemarksTest`.

Every fixture-backed test class that asserts on the whole store clears it in `setUp`, not only in
`tearDown`: the light fixture project is shared across test classes, so remarks left behind by an
earlier class are still there when the next one starts.

There are no UI-rendering or end-to-end tests. The popup appearing at the caret, the gutter icon
painting, the tree colours, the balloon, and the settings page layout are checked by hand in a
sandbox IDE, not automated.
