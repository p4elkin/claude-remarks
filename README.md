# Claude Remarks

An IntelliJ Platform plugin that lets you attach short remarks to line ranges while reading code, without touching the source files. Remarks do not modify your working tree.

The goal: select lines, add a remark with a tag and a note, let them pile up across files, and later turn them all into a single prompt for a Claude Code session. This build is not there yet — the only way to create a remark is a right-click action with fixed text and no tag, and no keyboard shortcut is registered.

Remarks stay on your machine, stored in `.idea/workspace.xml`. The `.idea/.gitignore` that the IDE generates excludes that file, so remarks stay out of version control. A repository that deliberately tracks `.idea/workspace.xml` is the exception: there they would be committed like any other change to that file.

## Phases

- **Phase 1-2** (this build): You can select lines, add a remark through the debug action, and see the list in a tool window. Restart the IDE and remarks are still there. Edit the file outside the IDE and the tool window shows whether remarks moved or were orphaned.

  This is covered by unit tests only. The plugin has never been loaded into a running IDE, so the restart and relocation behavior above is what the tests show, not what anyone has watched happen. Running it once in a sandbox IDE is the first thing to do before phase 3.
- **Phase 3-5**: Real inline input, gutter icons, prompt rendering, and dispatch to Claude Code. Not yet built.

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

The sandbox IDE launches with the plugin loaded. Open or create any project inside it. A "Claude Remarks" button appears on the right edge.

In the sandbox, right-click on any selected lines and choose "Add Claude Remark (Debug)" to create a test remark. Click Refresh in the tool window to reload the list — the list does not update on its own yet. Close and reopen the sandbox IDE to confirm the remark persists.

## Installing into your own IDE

Build the zip, then in the IDE: **Settings → Plugins → the gear icon → Install Plugin from Disk…**, pick `build/distributions/claude-remarks-0.1.0.zip`, and restart when asked. The plugin needs a 2025.2 or newer build (`sinceBuild = 252`, no upper bound set).

## Testing

Run all tests:

```bash
./gradlew test
```

59 tests: 27 for anchoring (how remarks stay pointed at the right lines after edits), 10 for storage round-trips, 4 for the store service, 5 for the resolver helpers, 3 for resolving stored remarks against real files, 5 for the selection line math, 5 for the tool window row text.

All of them are plain JUnit except `RemarkStoreServiceTest`, which starts a light IDE fixture through `BasePlatformTestCase` to check the service wiring.

The tool window itself and the debug action have no automated tests. They are checked by hand in a sandbox IDE, as the plan's Testing Strategy describes.

## Architecture

- `src/main/kotlin/dev/sasha/clauderemarks/anchor/`: Pure Kotlin, no platform imports. Logic for hashing lines and finding anchored text after files change.
- `src/main/kotlin/dev/sasha/clauderemarks/model/`: The `RemarkState` record and its enums (`RemarkTag`, `RemarkStatus`).
- `src/main/kotlin/dev/sasha/clauderemarks/store/`: `RemarkStore.kt`, the project service that persists remarks to `.idea/workspace.xml`, and `RemarkResolver.kt`, which finds the project root and turns stored remarks into rows for the tool window.
- `src/main/kotlin/dev/sasha/clauderemarks/ui/`: The tool window UI that lists remarks.
- `src/main/kotlin/dev/sasha/clauderemarks/action/`: The debug action (temporary, replaced in phase 3).

See `docs/claude/design.md` for a deeper look at how anchoring works and why storage choices were made.
