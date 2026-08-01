# Claude Remarks

An IntelliJ Platform plugin that lets you attach short remarks to line ranges while reading code, without touching the source files. Remarks do not modify your working tree.

You can select lines, press a hotkey, and add a remark with a tag and note. The plugin keeps all your remarks. Later, one action turns them all into a single prompt to send to a Claude Code session.

Remarks stay on your machine, stored in `.idea/workspace.xml`, which the IDE's own `.gitignore` excludes from version control.

## Phases

- **Phase 1-2** (this build): You can select lines, add a remark, and see the list in a tool window. Restart the IDE and remarks are still there. Edit the file outside the IDE and the tool window shows whether remarks moved or were orphaned.
- **Phase 3-5**: Real inline input, gutter icons, prompt rendering, and dispatch to Claude Code. Not yet built.

## Building

You need Gradle 9.0 or newer. The project brings its own wrapper:

```bash
./gradlew build
```

The build creates a plugin JAR under `build/distributions/`.

## Running in a Sandbox IDE

To test the plugin in an isolated IntelliJ instance:

```bash
./gradlew runIde
```

The sandbox IDE launches with the plugin loaded. Open or create any project inside it. A "Claude Remarks" button appears on the right edge. 

In the sandbox, right-click on any selected lines and choose "Add Claude Remark (Debug)" to create a test remark. Click Refresh in the tool window to reload the list. Close and reopen the sandbox IDE to confirm the remark persists.

## Testing

Run all tests:

```bash
./gradlew test
```

Tests cover the anchoring logic (how remarks stay pointed at the right lines after edits), serialization, and record round-trips.

The tool window and debug action have no automated tests. They are checked by hand in a sandbox IDE, as the plan's Testing Strategy describes.

## Architecture

- `src/main/kotlin/dev/sasha/clauderemarks/anchor/`: Pure Kotlin, no platform imports. Logic for hashing lines and finding anchored text after files change.
- `src/main/kotlin/dev/sasha/clauderemarks/model/`: The `RemarkState` record and its enums (`RemarkTag`, `RemarkStatus`).
- `src/main/kotlin/dev/sasha/clauderemarks/store/`: The project service that persists remarks to `.idea/workspace.xml`.
- `src/main/kotlin/dev/sasha/clauderemarks/ui/`: The tool window UI that lists remarks.
- `src/main/kotlin/dev/sasha/clauderemarks/action/`: The debug action (temporary, replaced in phase 3).

See `docs/claude/design.md` for a deeper look at how anchoring works and why storage choices were made.
