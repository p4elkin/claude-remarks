# Claude Remarks — Design Document

## Overview

A remark is a short note you attach to a line range in a file without modifying the file itself. The plugin stores all remarks persistently so they survive IDE restarts. If the file changes, the plugin checks whether the remark still points at the right lines.

This document describes how remarks are stored and kept pointed at the correct lines. Future sessions should be able to read this and understand the system without re-reading the source code.

## The Data Model

### What a Remark Contains

A remark has these fields:

- `id`: A unique identifier (generated UUID).
- `path`: The file path, relative to the project root. Produced by `VfsUtilCore.getRelativePath(file, projectRoot)`. This is what gets shown in the tool window and written into dispatch prompts.
- `startLine`, `endLine`: The 0-based, inclusive line numbers of the anchored range.
- `text`: The user's note (what they wrote in the remark).
- `tag`: An optional category from `RemarkTag.BUG | QUESTION | REFACTOR | NOTE`.
- `status`: One of `RemarkStatus.PENDING` or `RemarkStatus.SENT`. Defaults to `PENDING`. Nothing
  assigns `SENT` yet — the dispatch step that would is phase 5, so the tool window always shows
  `[PENDING]`. The same holds for `tag`: it is persisted and round-trip tested, but the debug
  action never sets it.
- `createdAt`: Timestamp when the remark was created.
- `textHash`: The first 16 hex characters of a SHA-256 hash of the lines at creation time.
- `contextBefore`, `contextAfter`: A few lines of context from above and below the remark, joined with newlines in a single string. Stored this way instead of as a list because the serializer handles single strings more predictably.

All fields are stored flat as XML attributes on a single element.

### Where Remarks are Stored

Remarks are stored in `.idea/workspace.xml` using the IntelliJ Platform's persistence API, under
`@State(name = "ClaudeRemarks")`. That name is the element to look for when checking the file by
hand: `<component name="ClaudeRemarks">`.

Why this location:

- The IDE's generated `.idea/.gitignore` excludes `/workspace.xml`. No extra work is needed to keep remarks out of version control.
- This is where the IDE keeps other local-only data like breakpoints and bookmarks.

Why not a custom file like `.idea/claudeRemarks.xml`:

- The IDE's `.gitignore` does not cover custom files. They would be committed in any repository that tracks `.idea/`.

Why not `CACHE_FILE`:

- It stores outside the project, which would work, but "Invalidate Caches" would silently wipe all remarks. We never silently delete anything.

Storage is configured with `RoamingType.DISABLED` so remarks do not travel through JetBrains Settings Sync to other machines, where file paths would not resolve.

### How Remarks are Persisted

The `RemarkStore` class implements `PersistentStateComponentWithModificationTracker<RemarksState>` itself, rather than extending `SimplePersistentStateComponent`. Why is in "Why the serializer is handed a copy" below. The nested `RemarksState` class extends `BaseState` and holds a list of `RemarkState` objects.

The list property uses this annotation:

```kotlin
@get:XCollection(style = XCollection.Style.v2)
val remarks by list<RemarkState>()
```

**This annotation is critical.** Without `@get:XCollection(style = XCollection.Style.v2)`, the entire list serializes to an empty element and every remark is silently lost on IDE restart, with no error logged. See `RemarkStoreStateTest` in the test suite — it is the regression guard for this exact trap.

The mutators (`addRemark`, `removeRemark`) live on the state class, not on the store, because
`incrementModificationCount()` is protected: it is only reachable from inside a `BaseState`
subclass.

A note on that call, because an earlier version of this document had the reason wrong. The list
property does track structural changes on its own: `ListStoredProperty.getModificationCount()`
compares the list's own `modCount` against the last one it saw, and `BaseState` sums the property
counts on top of its own. So adding or removing a remark already marks the state dirty, and
removing the `incrementModificationCount()` calls does not lose data. They are kept because they
are one cheap line that makes every mutation mark the state changed without having to reason about
what the property tracker sees. `RemarkStoreStateTest` asserts the modification count goes
up after an add and after a real remove, and stays put when `removeRemark` is given an unknown id.

All four methods (`addRemark`, `removeRemark`, `snapshot`, `modCount`) are `@Synchronized` on the
state object, so they all lock the same monitor. The tool window resolves remarks from a pooled
thread while the editor action adds them on the EDT, and the backing collection is
`ModCountableList`, a plain `ArrayList` subclass with no thread safety. A read action does not
help: it guards platform data, not ours. `RemarkStore.all()` returns `liveState.snapshot()`, a copy
taken under that same lock, `RemarkStore.getState()` takes its copy through the same `snapshot()`
call, and `RemarkStore.getStateModificationCount()` reads the count through `modCount()` instead of
touching the live `modificationCount` property directly. That last one was a real gap, not just
belt-and-suspenders: `BaseState.getModificationCount()` sums each stored property's own
modification count, and for the `remarks` list that means `ListStoredProperty.getModificationCount()`
iterating the live list with a for-each, without a lock of its own. Before `modCount()` existed, the
platform's save pass (which calls `getStateModificationCount()` off the EDT) could iterate the list
while the editor action mutated it on the EDT, throwing `ConcurrentModificationException`. See
"Why the Serializer Is Handed a Copy" below for how that surfaces and how it was fixed.

### Why the Serializer Is Handed a Copy

The platform's state serializer is a third reader of that list, and it never takes the lock.
Workspace saving runs off the EDT. So whatever object `getState()` returns must be an object no
other thread can change while the serializer walks it.

`getStateModificationCount()` is a fourth reader, and it used to bypass the lock entirely: the
override read `liveState.modificationCount` straight off the live state object, and
`BaseState.getModificationCount()` walks the `remarks` list (through
`ListStoredProperty.getModificationCount()`) with no synchronization of its own. The platform calls
`getStateModificationCount()` on every save pass, off the EDT, right before deciding whether to
call `getState()` at all — so a save landing while the editor action was adding a remark on the EDT
could throw `ConcurrentModificationException` from that count read alone, without ever reaching
`getState()`. The fix is `RemarksState.modCount()`, a fourth `@Synchronized` method that reads
`modificationCount` under the same lock `addRemark`, `removeRemark`, and `snapshot` already hold;
`getStateModificationCount()` now calls it instead of touching the live property directly.
`RemarkStoreStateTest` has a concurrency probe for this: one thread adding remarks in a loop while
another reads `stateModificationCount`, asserting nothing escapes.

That is the whole reason `RemarkStore` does not extend `SimplePersistentStateComponent`. That base
class hands out the live state object and does not let a subclass change it — `javap` against the
2025.2 jars:

```
public abstract class SimplePersistentStateComponent<T extends BaseState>
        implements PersistentStateComponentWithModificationTracker<T> {
  public final T getState();                        // final
  public final long getStateModificationCount();    // final
  public void loadState(T);
}
```

The interface underneath has no such restriction:

```
public interface PersistentStateComponent<T> {
  public abstract T getState();
  public abstract void loadState(T);
  public default void noStateLoaded();
  public default void initializeComponent();
}
```

So `RemarkStore` implements `PersistentStateComponentWithModificationTracker<RemarksState>`
directly:

- The live state sits in a private `@Volatile` field. That matches what the base class did with its
  own `private volatile T state`.
- `getState()` builds a new `RemarksState` and fills its list from `snapshot()`. The copy is taken
  under the same lock `addRemark` and `removeRemark` hold, so the serializer only ever iterates a
  list nothing else can reach.
- `loadState(state)` swaps the field.
- `getStateModificationCount()` returns the live state's count, read through `modCount()` so that
  read also takes the lock. It is kept because dropping it would change when the platform saves:
  with a modification tracker the platform compares this number against the one it last saw and
  skips the component when nothing changed, so on an idle save `getState()` is not called at all.

The `@State(name = "ClaudeRemarks", ...)` annotation, the storage, and the `@get:XCollection` on the
list are all untouched, so what lands in `workspace.xml` is byte-for-byte the same shape as before.

**The list copy is shallow: it shares the `RemarkState` objects with the live state.** Nothing
mutates a remark after it has been added. Even when phase 5 starts flipping `status` in place, a
single field write reads as either the old value or the new one, never as a corrupt one, and the
next save writes the new value. A deep copy would mean cloning `RemarkState` field by field, and a
field forgotten in that clone later would drop out of `workspace.xml` with no error — a worse bug
than the one this fixes.

Two things about the platform side, both read out of the bytecode before making the change:

- The platform keeps the last modification count it saw in its own
  `ComponentWithStateModificationTrackerInfo.lastModificationCount` field, not on the state object.
  So returning a fresh object from `getState()` does not confuse its dirty tracking. The only
  `resetModificationCount()` call anywhere near this is in `XmlSerializer.deserializeAndLoadState`,
  and that runs on the way in, on the object about to be passed to `loadState`.
- The platform finds the state class by reflecting over the component's generic signature. It used
  to read it off the `SimplePersistentStateComponent` superclass and now has to read it off an
  implemented interface. `RemarkStoreStateTest` asserts that
  `ComponentSerializationUtil.getStateClass(RemarkStore::class.java)` still answers `RemarksState`,
  because if that ever stopped working every stored remark would vanish on restart with nothing
  logged.

`RemarkStoreStateTest` also holds the two guards for the copy itself: the state a previous
`getState()` handed out does not change when a remark is added afterwards, and two calls to
`getState()` never return the same list instance. Both were checked by mutation — making
`getState()` return the live state makes both fail.

## The Anchoring Design

Anchoring solves this problem: you mark lines 10-12 in a file. Then someone edits the file — adds lines above, changes the marked lines themselves, deletes their context. Your remark needs to say "these lines moved" or "I could not find them" rather than silently staying at the wrong place.

### The Anchor and AnchorResult Types

An `Anchor` holds:

- `startLine`, `endLine`: The original 0-based line numbers (0-based, inclusive).
- `textHash`: First 16 hex chars of SHA-256 over the trimmed lines. Trimming means reformatting that only changes indentation still resolves.
- `contextBefore`, `contextAfter`: Lists of the 3 lines above and below (by default, configurable).

`resolveAnchor` takes an `Anchor` and the current file contents and returns an `AnchorResult`:

- `Exact(startLine, endLine)`: The lines at the stored numbers still hash to the same text.
- `Relocated(startLine, endLine)`: The text moved elsewhere, or it changed but the surrounding lines stayed the same.
- `Orphaned(startLine, endLine)`: Could not find the text or its context nearby. The numbers are the stored ones, so you can see what is stale.

All three carry `startLine` and `endLine`, declared on the `AnchorResult` interface itself. That is what lets `describe()` read a position off any result without a `when` over the three types.

### The Two-Pass Search

Before either pass, `resolveAnchor` hashes the block sitting at the stored line numbers right now.
If that matches, nothing moved and the answer is `Exact`. This is the only place `Exact` comes
from, and it is also the fast path for the common case where the file did not change at all.

When it does not match, `resolveAnchor` works in two passes, nearest-first outward from the stored
line:

1. **Hash match (first pass)**: Scan up to 200 lines in each direction from the stored start position. Look for any block of the same length that hashes to the same value. If found, the text is unchanged but moved — return `Relocated`.

2. **Context match (second pass)**: Scan the same 200-line radius. For each candidate position, check whether the lines immediately above and below match the stored context (trimmed, so indentation is ignored). If at least one context line is non-blank and all context lines match, return `Relocated`.

Why two passes?

- Pass one catches the common case: lines added or removed above the marked block, but the block itself is unchanged.
- Pass two catches the other case: the block itself was edited, but what surrounds it stayed in place.

**The second pass only finds a block that kept its line count, and this is a deliberate limit.**
`contextBefore` must end at the candidate start and `contextAfter` must begin at exactly
`start + span + 1`, where `span` is the stored length. So a line added or removed inside the marked
block orphans the remark. The block did not move, but its trailing context is now one line off, and
nothing matches.

That is a real cost, and it was paid on purpose. A pass that instead searched for `contextAfter` at
other lengths was written once and reverted, because it relocated remarks onto unrelated code:

- Context lines are compared trimmed, so every closing brace is the same line. In an ordinary test
  class, the trailing context of a remark inside `testA` is `}` / blank / `@Test` — the same three
  lines that sit below `testA`'s neighbour. Deleting the remarked line made the search skip past the
  end of `testA` and answer a range that started at `testA`'s closing brace and ran into the body of
  `testB`.
- A block whose trailing context is `}` / blank / `}` has that triple inside itself, so the search
  could also land in the middle of the block and report 3 of its 12 lines.

The rule the plugin follows is that a remark is never silently moved to the wrong place. An orphan
is visible: the row says `(orphaned)` and keeps the stale line numbers, and a person can see what
happened. A range covering the wrong method is not visible at all. So the orphan is the answer we
keep, and `AnchoringTest` pins it with three tests for the edit shapes (line added inside, line
removed inside, block that grew while it also moved) plus
`a repeating trailing context never relocates onto the code after it`, which is the guard against
bringing the variable-length search back.

Why require at least one non-blank context line to match?

- A run of empty lines should not match everywhere in the file. Requiring substance prevents false positives.

The order of the two passes matters and is tested: a block that moved is followed to where it went,
even when its old context is still sitting untouched at the original position. Nearest-first
matters too, and is tested with two identical copies of a block, one above the stored line and one
below.

If neither pass finds a match within 200 lines, the remark is orphaned. It is kept (not deleted) but shown with its stale line numbers.

A resolved position is never written back into the stored `RemarkState`. Every refresh searches
again from the original line numbers. That follows the rule that nothing is relocated silently,
but it also means many small moves add up until they pass the search radius and the remark
orphans, even though it was found on every refresh along the way. Writing the new position back is
a phase 3 decision, not a bug to fix quietly: it changes what "stale line numbers" means.

### Why Trimmed Hashing

Lines are trimmed before hashing so that reformatting that only changes indentation still resolves. The hash is SHA-256 truncated to 16 hex characters to keep `workspace.xml` small. A hash collision would relocate a remark to the wrong place, which is visible and correctable, not silent data loss.

### Why Context Lines

The plain hash alone can miss edits. If you mark lines 5-7 and someone edits them, the hash no longer matches. The second pass then looks at what is above and below. If the surrounding lines stayed the same, the remark likely still points at the right block, just with different content. Context matching finds it.

## From Stored Remarks to Tool Window Rows

`RemarkResolver.kt` is the bridge between the store and the screen. `resolveAll(project)` is the
one entry point. It must run inside a read action and off the EDT, because it reads `Document`s.

- It reads every remark through `RemarkStore.all()`, so it always sees a snapshot, never the live
  list.
- Each row comes back as `ResolvedRemark(remark, result)`: the stored record plus the
  `AnchorResult` for where it is now.
- **A remark is never dropped.** If the project root cannot be resolved, if `path` is null, if the
  file is gone, or if the file has no `Document`, the row still comes back — as `Orphaned` carrying
  the stored line numbers. An early version returned an empty list when the project root was null,
  which made every remark vanish from the tool window with no explanation.
- A stored path is resolved with `VfsUtil.findRelativeFile(root, *path.split('/'))`, then checked
  with `VfsUtilCore.isAncestor(root, file, false)`. Without that check a hand-edited
  `workspace.xml` holding `..` segments could point a remark at any file on the machine.
- `ProgressManager.checkCanceled()` runs once per remark, so a pending write does not have to wait
  for the whole sweep. Each remark can cost a SHA-256 over every candidate position in the radius.

Context lines are stored as one newline-joined string, with **one extra newline written in front of
the first line**, and `joinContext` / `splitContext` are the pair that converts. They live in
`store/ContextFormat.kt`, not in the resolver: the editor action writes with one of them and the
resolver reads with the other, so neither side owns the format. Null means "no context at all";
anything else is a marker plus the lines.

That leading newline looks pointless and is not. `RemarkState.contextBefore` and `contextAfter` are
declared with `BaseState.string()`, which resolves to `NormalizedStringStoredProperty`, and its
setter is `newValue = value.isNullOrEmpty() ? null : value`. Assigning `""` stores `null`
immediately — not after an XML round trip, on assignment. So a plain join would turn one blank line
of context into "no context at all" the moment the action wrote it. The marker keeps the string
non-empty for every non-empty list.

`splitContext` strips that marker with `removePrefix("\n")`, not with `drop(1)`. Everything else read
back out of `workspace.xml` is treated as untrusted here — a negative span, a path climbing out of
the project, a null path — and a context string is no different. A string stored without the marker
(an older format, or a file someone edited by hand) must not lose its first line, and a one-line
context must not come back as `emptyList()`, which would switch that side of the context search off
without saying so.

This matters in a very ordinary case, not a corner one: `document.text.split("\n")` on a file that
ends with a newline produces a trailing empty line, so a remark on the last real line of such a file
captures `contextAfter == [""]`. Losing that side weakens the context search, and when both sides
end up empty the second pass can never fire at all.

`RemarkResolverTest` covers the two functions on their own, and it also assigns through a real
`RemarkState` and reads back — the pure round trip alone would not have caught this.

**Line numbers are 0-based everywhere they are stored, resolved, or hashed** — that matches
IntelliJ's `Document`. They are converted to 1-based in exactly one place, `describe()` in
`ui/RemarksToolWindowFactory.kt`, because that is how an editor shows them to a person.

`describe()` also decides the `(moved)` label, by comparing **both ends** of the resolved range
against the stored one. A `Relocated` result that came back at exactly the stored range is not
labelled: that is the case where the block was edited where it stands and the context pass found it
again. Comparing the start line alone would call a range that kept its start but not its end
unmoved.

## The ProjectUtil Trap

`Project.getBaseDir()` is deprecated. Its deprecation note points at `com.intellij.openapi.project.ProjectUtil.guessProjectDir`. However, that class lives in the platform's internal API — it is on the compile classpath but marked as Kotlin-internal, so the Kotlin compiler rejects it with "Unresolved reference 'ProjectUtil'" even though Java code can use it.

The replacement is `project.basePath` (a String) resolved through `LocalFileSystem.getInstance().findFileByPath(it)` to get the `VirtualFile`. This is wrapped in one helper function, `projectRoot`, in `store/RemarkResolver.kt`.

## Why the Bookmarks API Was Rejected

The IntelliJ Platform's Bookmarks API is close:

- `LineBookmark` anchors to a file plus a line.
- `LineBookmarkImpl` stores `expectedText` (the line's text at creation time), used to detect and repair drift. That is the same trick we use, just unhashed.
- `BookmarkState` has a `description` field, so free-text notes already persist.

**But it does not fit for one concrete reason.** `LineBookmark.line` is a single `Int`. There is no range bookmark in the provider hierarchy. Remarks need line ranges. Building that would mean writing a custom `BookmarkProvider` against an API designed around one line. The only gain would be reusing `BookmarkState` for storage — a handful of lines. So we built a custom persistent state component.

## What Phases 3-4 Built

Phase 3 is the editor side: creating and viewing remarks without leaving the editor.

- **The input popup.** `Ctrl+Alt+Shift+R`, the "Add Claude Remark" intention (Alt+Enter), or the
  editor popup menu opens `RemarkInputPanel` at the caret through `JBPopup.showInBestPositionFor`.
  Enter submits, Shift+Enter inserts a newline, Esc cancels through the popup's own
  `setCancelKeyEnabled`. An inlay was considered and rejected: `EditorCustomElementRenderer` only
  paints and hit-tests (`calcWidthInPixels`, `paint`, ...), so it cannot host a focusable text
  field. A popup is the only option that can.
- **The Add Remark action stays visible when it cannot fire.** `AddRemarkAction` replaces the
  debug action. `RemarkTarget.remarkTargetProblem` returns a reason string instead of a boolean,
  and the action goes disabled with that reason in its description instead of vanishing from the
  menu. The old `isEnabledAndVisible = false` made the whole item disappear, which read as "the
  plugin is broken" rather than "this file is out of scope" — worst on macOS, where `/tmp` and
  `/var` are symlinks into `/private`, so any project reached through one of them hid the action
  for every file in it.
- **A gutter icon that follows the code.** Covered in "The Editor Side" below.
- **The tool window is a tree, not a flat list.** `RemarksPanel` (in
  `ui/RemarksToolWindowFactory.kt`) builds a `Tree` grouped by file, refreshes itself on
  `REMARKS_CHANGED`, navigates on double click through `EditSourceOnDoubleClickHandler`, and
  deletes the selection on the Delete key with no confirmation dialog — selecting the row and then
  pressing Delete on it is the confirmation. `describe()` and the old `JBList<String>` are gone.

Phase 4 is the output side: turning pending remarks into one prompt.

- **Settings hold one editable string**, the prompt header. `RemarkSettings` is an app-level
  `SimplePersistentStateComponent` with the default `RoamingType`, so the header travels through
  JetBrains Settings Sync — right for a template written once and wanted on every machine, unlike
  the project data, which is `RoamingType.DISABLED` because file paths do not travel.
- **The markdown renderer** (`render/PromptRenderer.kt`) and **the copy pipeline**
  (`render/PromptPayload.kt`, `action/CopyRemarks.kt`) are covered in "The Copy Pipeline" below.
- **The toolbar** has five buttons: Copy All Pending, Copy Selected, Clear Sent, Clear All,
  Refresh. Both Clear buttons ask first and name their count. Copy Selected and Clear Sent grey
  out when there is nothing to act on, because a live button that does nothing when pressed is
  its own kind of silent failure — the same reasoning that keeps `AddRemarkAction` visible above.

### What is still not built

**Writing a resolved position back into the stored `RemarkState`.** Every refresh still searches
again from the original stored line numbers. A remark whose code drifts more than 200 lines from
where it was first stored orphans, even though every refresh along the way found it.

It stays unbuilt because the win is small against the risk. What it would cost: a new persistence
write path, a hook to decide when to run it, and a guard so that deleting the marked lines does
not write a collapsed range back over a good anchor. Getting that guard wrong destroys the anchor,
which is the one failure this plugin promises not to have. What happens without it: remarks are
written and copied within about an hour in ordinary use, which is what the 200-line search radius
was sized for, and an orphaned remark is still listed, still shows its text, and the prompt header
tells the reader to find it by content rather than by the stale line numbers.

Add it when someone reports remarks orphaning during ordinary use. The trigger is
`editorReleased` for the last editor of a document; the guard is that the lines under the live
highlighter must still hash to the remark's stored `textHash`.

## The Editor Side

The single fact that shapes this whole side: `RangeHighlighter extends RangeMarker`. One object
carries both the gutter icon and the live position. There is no separate `RangeMarker` to keep in
step — an earlier draft of this document assumed there would be one, and that assumption is gone.

`RemarkGutter` (`editor/RemarkGutter.kt`) is a project service, started by the
`postStartupActivity` `RemarkGutterStartup`. It listens with `EditorFactoryListener`, not
`FileEditorManagerListener`: the factory listener fires once per raw editor, including a diff
viewer's editors and a split's second view of the same file, and the gutter icon has to appear in
all of them. `DocumentMarkupModel.forDocument` is keyed on the `Document`, not the editor, so two
splits of the same file share one set of highlighters without the service doing anything extra for
the second one.

The service tracks every open document in `tracked`, not only the ones that currently carry a
remark: that is what makes the *first* remark added to an open file show its icon at once, rather
than only after the next unrelated refresh. Rebuilding highlighters runs on `REMARKS_CHANGED`, on
an editor opening, and on an editor closing — not on every keystroke, because resolving one remark
can cost a SHA-256 over every candidate position inside the 200-line search radius.

`RemarkGutterIconRenderer.equals` and `hashCode` are keyed on the remark's id plus everything that
changes what gets painted — the tooltip text and the sent flag. They must not fall back to
instance identity. The platform compares the old and new renderer on every highlighting pass to
decide whether to repaint, so identity equality would make the icon flicker on every pass.

One more rule the service holds by hand: when a fresh resolve for a remark that already has a
live, valid highlighter comes back `Orphaned`, the service keeps the highlighter where the
platform moved it, and only repaints what is drawn on it. Rebuilding the highlighter from the
(stale) orphaned line numbers would throw away a position the platform has been keeping exact.

## The Change Notification

`REMARKS_CHANGED` (a `Topic<RemarksListener>`, project-level, `BroadcastDirection.NONE`) lives in
`store/RemarkEdits.kt`, beside the six functions that publish it, not inside `RemarkStore`. Two
things need to hear about a change: the gutter service and the tool window tree. Keeping the topic
out of the store is about cost, not purity: adding a `Project` constructor parameter to
`RemarkStore` would touch fourteen call sites that build it directly, and keeping the store free
of the message bus is what lets `RemarkStoreStateTest` stay a plain JUnit test with no IDE fixture.

`store/RemarkEdits.kt` holds the only six functions production code uses to change a remark:
`addRemark`, `editRemark`, `deleteRemark`, `markRemarksSent`, `clearSentRemarks`,
`clearAllRemarks`. Each one mutates through `RemarkStore` and then publishes — that pairing is the
whole mechanism, there is no separate listener list or observer class. `RemarkStore`'s own
`add`/`remove`/`edit`/... stay public, and nothing in the language stops a caller from reaching
past the six functions and calling them directly, so the rule is checked rather than assumed:

```bash
grep -rnE "RemarkStore\.getInstance\([^)]*\)\.(add|remove|edit|markSent|removeSent|clear)\(" \
  src/main/kotlin --include=*.kt | grep -v RemarkEdits.kt   # must be empty
```

Test code is outside that check on purpose: fixture-backed test classes call
`RemarkStore.getInstance(project).clear()` in `setUp` to clear the shared light-fixture project
between test classes, and that call has nothing to publish to.

## The Copy Pipeline

Copy All Pending and Copy Selected both end up in `copyRemarks(project, ids)`
(`action/CopyRemarks.kt`). `ids == null` means every `PENDING` remark; a non-null list is used as
given, sent remarks included, which is what makes copying again after a paste went to the wrong
place work.

Everything expensive runs inside one `ReadAction.nonBlocking` block, off the EDT: resolving
(`resolveAll`), reading each file's `Document` once and slicing the anchored lines plus
`PROMPT_CONTEXT_LINES` (3) either side (`render/PromptPayload.kt`'s `collectForPrompt`), rendering
the markdown (`render/PromptRenderer.kt`'s `renderPrompt`, zero platform imports, the same shape
as `anchor/Anchoring.kt`), and building the clipboard payload (`clipboardPayload`). The EDT step
that follows does three cheap things only: copy to the clipboard, `markRemarksSent`, show a
balloon.

**`clipboardPayload` is one function with a size check, not two implementations.** Under
`INLINE_LIMIT_BYTES` (100 KB, measured in UTF-8 bytes, not characters) the markdown goes on the
clipboard as text. At or above that, it is written to a file under `java.io.tmpdir` and the file's
path is copied instead — `Clipboard(text = path, file = path)`. The caller does not branch on
which mode ran; it reads `Clipboard.file` only to word the balloon differently. There is no
`Dispatcher` interface, because there is one implementation.

The write happens inside `prepare()`, not in the `finishOnUiThread` block. Writing a 100 KB+ file
is exactly the case where doing it on the EDT would freeze the UI, so the whole payload — the
render and any file write — is built off the EDT.

**The temp file is why nothing remark-related can enter version control by this route.** A file
under `java.io.tmpdir` is outside the project directory, so no `.gitignore` question ever arises.
An earlier brief's plan of a pluggable `Dispatcher` interface — one implementation writing a file
into `.idea/claude-remarks/` and driving a tmux pane with `send-keys` — was dropped for the same
reason, before it was built: the IDE's generated `.idea/.gitignore` does not cover a custom
subdirectory there, so that path would have been committed in any repository that tracks
`.idea/`. A system temp file cannot enter version control at all, so "nothing remark-related
enters VCS" holds by construction rather than by a gitignore rule someone has to remember to add.
Dropping the `Dispatcher` interface also removed the tmux pane discovery and the `send-keys`
fragmenting problem — Claude Code's TUI submits on newline, so sending a multi-line payload through
`send-keys` fires fragments — and shrank settings to the one editable thing that is left: the
prompt header.

`resolveAll` and `collectForPrompt` never drop a remark. A file that cannot be read, or a remark
that resolves as `Orphaned`, still gets a row in the prompt — with no code and a note that the
line numbers are stale — rather than silently disappearing from the copy. The prompt header
itself follows revdiff's model: a remark that asks a question gets answered rather than turned
into an edit.

## Two Positions On Screen, And When They Differ

The gutter shows the live highlighter position. The platform moves it as you type, for free and
exactly, because it is a `RangeMarker`. The tree shows the position `resolveAll` last computed,
and that only happens on a remark change or an editor opening or closing — not on every keystroke,
for the same SHA-256 cost reason as the gutter's own rebuild.

So while you are typing, the gutter is right and the tree can be a few lines stale. They agree
again the moment anything triggers a refresh, and there is a Refresh button in the toolbar as the
manual escape.

The one case where they say genuinely different things, and keep disagreeing, is a block edited
inside the marked lines: a line added or removed there changes the block's length, and the search
keeps the block pinned to its stored length, so the resolve for that remark comes back `Orphaned`.
The gutter icon stays exactly where the platform moved the highlighter; the tree row says orphaned
at the stale stored numbers. Both are honest about what they know. Neither is silently wrong.

## Build Choices Worth Remembering

- `sinceBuild = "252"` is set and `untilBuild` is left unset on purpose. The platform plugin would
  otherwise pin an upper bound at the current branch, and the plugin would refuse to load in the
  next IDE release for no reason. Nothing here uses API that is expected to break.
- `kotlin.stdlib.default.dependency = false` in `gradle.properties`: the IDE ships its own Kotlin
  stdlib. Bundling a second copy inside the plugin zip is a known source of conflicts.
- `testFramework(TestFrameworkType.Platform)` is needed for `BasePlatformTestCase`. Without it the
  service test does not compile.

## Performance Tuning Knobs

The search radius is 200 lines. It is the distance the search is willing to look. Raise it if
remarks orphan more often than expected in real use — a bigger radius finds blocks that moved
further. Lower it if the sweep feels slow, and accept more orphans in exchange.

The context lines count is 3. If context matching finds false positives, raise it. If it misses real matches, lower it.

Both are in `Anchoring.kt` as `SEARCH_RADIUS` and `CONTEXT_LINES`. There is no knob for how much the
block's own length may have changed: the answer is zero, for the reason above.
