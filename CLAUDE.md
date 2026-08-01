# Claude Remarks — Working Notes

This project builds a plugin for IntelliJ that lets you mark up code with remarks while reading,
then dispatch them all to Claude at once.

Phase 1-2 is implemented and covered by unit tests. It has never been loaded into a running IDE:
every `runIde` check in the plan was skipped, so treat "it works" as "the tests pass". Remarks can
be created from a debug action, persist to `.idea/workspace.xml`, and are kept pointed at the right
lines through file edits.

For the design and how anchoring works, see `docs/claude/design.md`.

Phases 3-5 (inline input, gutter, prompts, dispatch) are deferred.

## Rules that must not break

1. **The anchoring module stays free of the platform.** `anchor/` is pure Kotlin, which is what
   keeps its tests running in milliseconds.

   ```bash
   grep -rn "com.intellij" src/main/kotlin/dev/sasha/clauderemarks/anchor/   # must find nothing
   ```

2. **No code ever writes to a source file.** The whole point of the plugin is that the working tree
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

Both greps must exit with status 1 (no matches).

## Project structure

```
src/main/kotlin/dev/sasha/clauderemarks/
  anchor/Anchoring.kt          hashing, capture, the two-pass resolve. No platform imports.
  model/RemarkState.kt         the persisted record, plus RemarkTag and RemarkStatus
  store/RemarkStore.kt         @Service project component, state in workspace.xml
  store/RemarkResolver.kt      projectRoot, resolveAll, and anchorOf
  store/ContextFormat.kt       joinContext/splitContext, how context lines are stored
  ui/RemarksToolWindowFactory.kt   the list, the Refresh button, and describe()
  action/AddDebugRemarkAction.kt   throwaway phase 2 entry point (editor popup menu), plus
                                   selectedLines(), the selection-to-line-range math
src/main/resources/META-INF/plugin.xml
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

Anchoring, storage round-trips, the resolver helpers and the tool window row text are plain JUnit
tests with no fixture, so they run in milliseconds. Three classes need a light IDE fixture and are
slower: `RemarkStoreServiceTest` (the real project service), `ResolveAllTest` (stored remarks
resolved against real files, including a path that tries to climb out of the project) and
`SelectedLinesTest` (the selection line math against a real `Document`). They all extend
`BasePlatformTestCase`, which needs `testFramework(TestFrameworkType.Platform)` in
`build.gradle.kts`.

The tool window itself and the debug action's `update`/`actionPerformed` have no automated tests.
They still need a person at a sandbox IDE.
