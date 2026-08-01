# Claude Remarks — Working Notes

This project builds a plugin for IntelliJ that lets you mark up code with remarks while reading, then dispatch them all to Claude at once.

Phase 1-2 (this build) is complete. Remarks can be created, persist to `.idea/workspace.xml`, and are kept pointed at the right lines through file edits.

For the design and how anchoring works, see `docs/claude/design.md`.

Phases 3-5 (inline input, gutter, prompts, dispatch) are deferred.

## Testing

Anchoring logic is tested in unit tests with no platform imports, so tests run in milliseconds.

```bash
./gradlew test
```

Storage round-trips are tested. The tool window and debug action are checked by hand in `./gradlew runIde`.
