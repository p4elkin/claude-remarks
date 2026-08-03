# Claude Remarks — Working Notes

This project builds a plugin for IntelliJ that lets you mark up code with remarks while reading,
then turn them all into one prompt for a Claude Code session.

Phases 1-7 are implemented and covered by unit tests. None of it has been loaded into a running
IDE in any of these sessions: every `runIde` check in the phase 1-2, phase 3-4, phase 5, phase 6
and phase 7 plans was skipped in the autonomous sessions that did the work, so treat "it works" as
"the tests pass" until someone does the hand checks listed at the end of each plan — phase 7's hand
checks matter most now, since a second delivery signal, a scheduled deadline, and a diff opened over
VCS all depend on platform behaviour no automated test in this project reaches. See
`docs/claude/design.md` for exactly which. Select lines, press `Ctrl+Alt+Shift+R` (or
use the "Add Claude Remark" intention through Alt+Enter), type a note, optionally pick a tag and a
severity level, and press Enter. A gutter icon appears on the marked lines and follows the code as
you keep editing. `Cmd+Ctrl+Shift+Space` in the box (`Ctrl+Alt+Shift+Space` off macOS) inserts a
class name from the project. The tool window lists every remark as a tree grouped by file, with a
bucket level above the files once any remark is put in one; right-click a row for the severity and
bucket menu. Press Copy All Pending in the tool window to turn every pending remark into one
markdown prompt on the clipboard; a balloon says how many. Copied remarks turn gray rather than
disappearing, so Copy Selected can send them again if the paste went to the wrong place. Clearing
(Clear Sent, Clear All) archives to a history file in the IDE configuration directory before it
removes anything. If a Claude Code skill has started a review, a banner reads "Claude Code is
waiting: <label>" above the tree and Send to Claude Code hands every pending remark to it the same
way Copy All Pending hands them to the clipboard — see "The Shared Review Session" below for how
the IDE finds the skill and hands the remarks back.

For the design — how anchoring, the gutter, the change notification, severity and buckets, the
commit stamp, the history file, the copy pipeline, and the shared review session work — see
`docs/claude/design.md`.

**Phase 5 is built.** It added a severity level and named buckets to a remark, tag chips with Alt
keys in place of the old tag drop-down, a commit stamp read straight out of `.git`, a history file
that cleared remarks are archived to instead of deleted, and a keystroke that inserts a class name
into the remark text. One specific automated-dispatch idea was dropped before it was built: a
pluggable `Dispatcher` interface, a tmux pane, a file inside `.idea/`. See `docs/claude/design.md`,
section "The Copy Pipeline", for why. That idea stays dropped — phase 6 below does not revive it.

**Phase 6 is built.** It adds a different, simpler automated path next to the clipboard, never
instead of it: a Claude Code skill can ask a running IDE to hold a review open through the IDE's
own built-in HTTP server, the person answers by pressing Send to Claude Code in the tool window,
and the remarks reach the skill through a file both sides agree on. The plugin works exactly as it
did before with no skill installed and nothing listening. See `docs/claude/design.md`, section "The
Shared Review Session", for the whole design, and `docs/ideas.md` for the reasoning this carries
forward from before it was built.

**Phase 7 is built.** It closes the gap between "the IDE wrote a file" and "the agent actually read
it." Rejecting a review in the banner now writes that decision to the handoff file and clears the
review — the link is called Reject, not Cancel — instead of only closing the banner while the skill
waits out its full timeout. A review carries a phase, `Waiting` or `Sent`: sending writes the file
and records which remarks it wrote, but does not mark them sent; only a `read` acknowledgement from
the skill does that, over a second endpoint action, `POST /api/claude-remarks/ack`. The skill also
declares how long it will wait, and the IDE clamps and enforces that deadline itself, so a killed
or abandoned session does not leave a stale banner and a live Send button on screen forever. A
review request that names files with a local change now opens one real diff over just those files,
through `ShowDiffAction`, instead of a plain editor per file — which also means a remark on the
revision side of a diff is now refused, with a sentence pointing at the working copy, rather than
stored with line numbers that described a different revision. See `docs/claude/design.md`, section
"The Shared Review Session", for both new subsections, and `docs/ideas.md` for the ideas this
carries forward.

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

3. **`store/RemarkEdits.kt` holds the only eight functions that change a remark.**
   `RemarkStore`'s own mutators stay public, and `RemarkEdits.kt` sits in the same package, so
   nothing but this check keeps the claim true. A caller that reaches past the eight functions
   mutates the store without telling the gutter or the tool window to redraw. The grep allows
   through the one read-only method by name, `all()`, rather than listing the mutator names by
   hand: a hand-picked list has to be edited every time a mutator is added, and forgetting is
   silent — the guard keeps passing while it stops covering the new function. That is exactly
   what happened here: phase 5 added `setSeverity`/`setBucket`, and the old six-name list never
   saw them.

   ```bash
   grep -rn "RemarkStore\.getInstance([^)]*)\." src/main/kotlin --include=*.kt \
     | grep -v RemarkEdits.kt | grep -v "\.all()"   # must be empty
   ```

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
   remark sent, showing a balloon — live in `review/SendReview.kt`, not in `ReviewRestService.kt`,
   for exactly this rule. The comment in `ReviewRestService.kt` that explains why says "the file that
   owns the editor side" and names `review/SendReview.kt` by path, and does not spell out any of the
   five forbidden symbols, even to say they are absent.

Every command above must come back empty.

## Project structure

```
src/main/kotlin/dev/sasha/clauderemarks/
  anchor/Anchoring.kt              hashing, capture, the two-pass resolve. No platform imports.
  model/RemarkState.kt             the persisted record, RemarkTag (+ its label extension), RemarkStatus
  store/RemarkStore.kt             @Service project component, state in workspace.xml
  store/RemarkEdits.kt             the eight mutation functions, the REMARKS_CHANGED topic
  store/RemarkResolver.kt          projectRoot, resolveAll, and anchorOf
  store/RemarkTarget.kt            relativePathOf, remarkTargetProblem, the diff fallback, and the
                                   refusal for a remark on the revision side of a diff
  store/ContextFormat.kt           joinContext/splitContext, how context lines are stored
  store/GitHead.kt                 headCommit, reads .git directly, no platform import, no Git4Idea
  store/RemarkHistory.kt           historyFile, appendToHistory, renderHistory: the archive
  ui/RemarkInputPanel.kt           the popup's panel, the Enter/Shift+Enter keys, the tag chips and
                                   their Alt keys, CLASS_NAME_STROKE to insert a class name
  ui/RemarkActions.kt              remarkChangeActions: the severity and bucket menu, shared by the
                                   gutter icon and the tree
  ui/ClassNameInsert.kt            projectClassNames, chooseClassName: the class-name chooser the
                                   input popup opens on Cmd+Ctrl+Shift+Space (Ctrl+Alt+Shift+Space
                                   off macOS — NOT Ctrl+Space, see CLASS_NAME_STROKE for why)
  ui/RemarksTree.kt                node building (files, and buckets above them) and the tree cell
                                   renderer
  ui/RemarksToolWindowFactory.kt   RemarksPanel: the tree, the toolbar, self-refresh on REMARKS_CHANGED
  action/AddRemarkAction.kt        the shortcut / popup-menu entry point, plus selectedLines()
  action/AddRemarkIntention.kt     the Alt+Enter entry point
  action/CopyRemarks.kt            copyRemarks(project, ids), the whole copy pipeline, plus the
                                   Tools-menu action that calls it without the tool window
  editor/RemarkGutterIcon.kt       the placement record, the tooltip, the gutter icon renderer
  editor/RemarkGutter.kt           the project service that keeps gutter icons in step
  editor/RemarkGutterStartup.kt    the ProjectActivity that starts RemarkGutter, and
                                   ReviewHandshakeService
  settings/RemarkSettings.kt       the app-level service and the default prompt header
  settings/RemarkSettingsConfigurable.kt
  render/PromptRenderer.kt         pure Kotlin, zero platform imports. Remarks to markdown.
  render/PromptPayload.kt          collectForPrompt and clipboardPayload
  review/ReviewHandshake.kt        handshakeName, renderHandshake, writeHandshake/deleteHandshake,
                                   the per-run ReviewToken, and ReviewHandshakeService (@Service
                                   PROJECT, Disposable) — the file a skill reads to find this IDE
  review/AtomicWrite.kt            atomicWriteString: temp file beside the target, then rename
  review/WaitingReview.kt          WaitingReviewState (with its ReviewPhase, deadlineAt and
                                   isStale), StartResult, the pure startOrConflict, and
                                   WaitingReviewService (@Service PROJECT, Disposable) — at most one
                                   waiting review per project, in memory only, plus markSent,
                                   acknowledge and the scheduled expiry
  review/ReviewRestService.kt      the RestService at POST /api/claude-remarks/{start,ack}:
                                   isHostTrusted, execute (dispatches on the sub-path),
                                   clampDeadlineSeconds, and the pure requestIsAllowed/projectForPath
                                   helpers. Rule 5 above governs this file specifically
  review/SendReview.kt             sendToWaitingReview and SendReviewAction: the same prepare()
                                   pipeline Copy All Pending uses, written to the handoff file
                                   instead of the clipboard; rejectWaitingReview; finishReview and
                                   expireStaleReview, the ack's consequences (marking sent, the
                                   balloon), kept out of ReviewRestService.kt by rule 5
  review/OpenReviewFiles.kt        the only file in review/ that touches the VFS or the editor —
                                   opens a real diff over the files that have a local change,
                                   through ShowDiffAction, and a plain editor for the rest
src/main/resources/META-INF/plugin.xml           declares two dependencies: com.intellij.modules.platform
                                                  and, since phase 7, com.intellij.modules.vcs — for
                                                  ShowDiffAction, which lives in a module jar
                                                  (lib/modules/intellij.platform.vcs.impl.jar), not
                                                  in app.jar
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
./gradlew buildPlugin                       # build/distributions/claude-remarks-0.1.1.zip
./gradlew verifyPluginProjectConfiguration  # after any plugin.xml or build.gradle.kts change
./gradlew verifyPlugin                      # compatibility report against the target IDE
```

Do not run `./gradlew runIde` from an agent session: it starts an interactive sandbox IDE that
never exits on its own.

## Testing

Anchoring, storage round-trips, the resolver helpers, the tree's node-building, the markdown
renderer, the settings round trip, `GitHeadTest` (reads real `.git` directories built on disk for
the test, including a worktree, a detached HEAD and packed refs), `RemarkHistoryTest` (the
archive's markdown rendering, and the write itself against a temp file), `AtomicWriteTest` (the
temp file lands beside the target, not in the system temp directory, and no temp file is left
behind), `ReviewHandshakeTest` (the name, the rendering, the escaping, and the owner-only
permissions), `WaitingReviewTest` (the pure `startOrConflict`: accept, honest-retry reuse, a
same-session retry after the deadline, and conflict, plus `isStale`'s boundary), and
`ReviewRequestTest` (the pure `requestIsAllowed`, `projectForPath`, and, since phase 7,
`clampDeadlineSeconds`) are plain JUnit tests with no fixture, so they run in milliseconds. The rest
need a light IDE fixture
(`BasePlatformTestCase`, which needs `testFramework(TestFrameworkType.Platform)` in
`build.gradle.kts`) and are slower, because each goes through a real project service, a real
`Document`, or a real markup model: `RemarkStoreServiceTest`, `ResolveAllTest` (stored remarks
resolved against real files, including a path that tries to climb out of the project),
`SelectedLinesTest` (the selection line math against a real `Document`), `RemarkEditsTest` (the
eight mutation functions publish `REMARKS_CHANGED`), the key-binding half of
`RemarkInputPanelTest`, `AddRemarkActionTest`, `ActionIdsTest` (pins the two action ids and the
tool window's derived activation id, so a rename is caught rather than silently breaking every
`.ideavimrc`), `RemarkActionsTest` (the severity and bucket menu acts on the ids it is given at
press time, not at build time), `ClassNameInsertTest` (inserting a class name at the caret, and
over a selection), `DiffRemarkTargetTest` (adding a remark from a diff pane: a real
`DiffContentFactory` content standing in for a VCS revision, since a light fixture cannot build a
diff viewer), the renderer-equality half of `RemarkGutterIconTest`, `RemarkGutterTest` (the gutter
service), `RemarksPanelTest` (the tool window panel: every file and bucket group ends up expanded,
and the selection survives a rebuild), `NavigationLineBaseTest` (pins `OpenFileDescriptor`'s
0-based line argument), the collector half of `PromptPayloadTest`, `CopyRemarksTest`,
`ReviewEndpointSmokeTest` (the one test that calls `ReviewRestService.execute` itself, through a
real `EmbeddedChannel`, so the response actually carries a body, plus the ack action and the
unknown-action refusal added in phase 7), `OpenReviewFilesTest` (the string-only half of the path
filter: absolute paths and `..` segments are dropped, plus a fixture-backed class for the
diff-or-editor decision, since a light fixture project has no VCS root and every file takes the
plain-editor branch), `SendReviewTest` (the send action's success and failure paths, that nothing is
marked sent until the read acknowledgement, the reject action, and the phase guards that refuse a
second send or an overwrite after a send), and `WaitingReviewServiceTest` (fixture-backed, because a
project-level service needs a project: `markSent`, `acknowledge`, `expireIfStale`, and that a stale
review is not `current()`).

Every fixture-backed test class that asserts on the whole store clears it in `setUp`, not only in
`tearDown`: the light fixture project is shared across test classes, so remarks left behind by an
earlier class are still there when the next one starts. `SendReviewTest` and `RemarksPanelTest`
both clear `WaitingReviewService` in `setUp` and `tearDown` for the same reason: the fixture
project is shared between test classes too, and task 6's failure-path test in `SendReviewTest`
deliberately leaves a review waiting when it finishes, so the next test class to touch the tool
window must not find it still there.

There are no UI-rendering or end-to-end tests. The popup appearing at the caret, the gutter icon
painting, the tree colours, the balloon, and the settings page layout are checked by hand in a
sandbox IDE, not automated.
