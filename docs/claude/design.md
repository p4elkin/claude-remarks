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
- `tag`: An optional category from `RemarkTag.BUG | QUESTION | REFACTOR | NOTE`, picked in the
  input popup (`ui/RemarkInputPanel.kt`) when the remark is written or edited.
- `status`: One of `RemarkStatus.PENDING` or `RemarkStatus.SENT`. Defaults to `PENDING`. Set to
  `SENT` by `markRemarksSent` once a copy reaches the clipboard — see "The Copy Pipeline" below.
  Sent remarks stay in the list, drawn gray, until Clear Sent.
- `createdAt`: Timestamp when the remark was created.
- `textHash`: The first 16 hex characters of a SHA-256 hash of the lines at creation time.
- `contextBefore`, `contextAfter`: A few lines of context from above and below the remark, joined with newlines in a single string. Stored this way instead of as a list because the serializer handles single strings more predictably.
- `severity`: One of `RemarkSeverity.VIBE | SUGGESTION | SHOULD | MUST`, low to high. Defaults to
  `SHOULD` rather than being nullable — a remark you bothered to write is usually something you want
  done, the two ends of the scale are the ones worth choosing on purpose, and a non-null default
  means a remark stored before this field existed loads with `SHOULD` instead of a null nothing
  downstream checks for. See "Severity" under "What Phase 5 Built" below.
- `bucket`: An optional name the user picks, like "auth refactor", or null for no bucket. Set only
  from the gutter icon menu or the tree's right-click menu, never in the input popup — see
  "Buckets" below.
- `commit`: The repository HEAD read straight out of `.git` when the remark was written, or null
  when there was no readable git repository. Never refreshed. See "The commit stamp" below.

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

Every method on the state class (`addRemark`, `removeRemark`, `editRemark`, `markSent`,
`removeSent`, `clear`, `snapshot`, `modCount`) is `@Synchronized` on the state object, so they all
lock the same monitor. The tool window resolves remarks from a pooled
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
  under the same lock the mutators hold, and it copies the remarks as well as the list, so the
  serializer walks objects nothing else can reach.
- `loadState(state)` swaps the field.
- `getStateModificationCount()` returns the live state's count, read through `modCount()` so that
  read also takes the lock. It is kept because dropping it would change when the platform saves:
  with a modification tracker the platform compares this number against the one it last saw and
  skips the component when nothing changed, so on an idle save `getState()` is not called at all.

The `@State(name = "ClaudeRemarks", ...)` annotation, the storage, and the `@get:XCollection` on the
list are all untouched, so what lands in `workspace.xml` is byte-for-byte the same shape as before.

**The copy goes all the way down: `snapshot()` copies each `RemarkState` too, so no reader shares
an object with the live state.** It did not always. It used to be `remarks.toList()`, which copies
the list and shares its elements, and there are two different readers to answer for. Both matter,
and for a long time only the first was written down here.

*The serializer, saving `workspace.xml` off the EDT.* `editRemark` writes `text` and then `tag`;
`markSent` writes `status`. With the shallow copy a save could land between the two writes in
`editRemark` and record the new text next to the old tag. That could never become permanent, and
the reason is the ORDER in `editRemark`: `incrementModificationCount()` runs after both writes, so
a save landing between them recorded the lower count it read on the way in, and the next save saw a
higher count and wrote both fields again. One save stale, the one after it right, no path to
permanent loss.

*The prompt and the tool window, reading on a pooled thread.* `resolveAll` and `collectForPrompt`
run inside a non-blocking read action, off the EDT, and walk the same objects `editRemark` and
`markSent` change on the EDT. They read the fields long after leaving the store's lock — for as
long as resolving every remark and slicing every file takes. The ordering argument above says
nothing about this reader: it is about what ends up on disk, and a prompt that already reached the
clipboard has no later pass to fix it. So the shallow copy let a copy in flight render a remark with
its new text under its old tag, or send a remark whose edit had landed a moment earlier and then
mark it sent, so the edit looked delivered when it was not. Narrow — the window is one copy — but
real, and invisible when it happens.

Making `snapshot()` deep closes the second reader without weakening the first. `BaseState.copyFrom`
walks the property list every `BaseState` registers for itself, so a field added to `RemarkState`
later is copied with no edit to `snapshot()`. That was the one recorded argument against a deep
copy: cloning field by field, and a forgotten field then dropping out of `workspace.xml` with
nothing logged. `copyFrom` has no such failure mode, and `RemarkStoreStateTest` pins it by comparing
the serialized XML of a fully-populated remark against its copy, so the guard covers fields that do
not exist yet. It also holds a deterministic test (a snapshot taken before an edit does not see it)
and a bounded race probe (a reader looping on `snapshot()` while a writer flips `text` and `tag`
never sees a mixed pair); the probe fails within milliseconds if `snapshot()` goes back to
`remarks.toList()`.

The cost: one small object per remark per call, and `snapshot()` runs on every resolve, which is
every remark change and every editor open. A `RemarkState` is eleven stored properties, so a hundred
remarks is tens of microseconds against a resolve that SHA-256s candidate positions and splits whole
documents into lines. If a project ever holds enough remarks for that to be felt, the next step is
not a shallower copy but an immutable value type for the read path, so the copy happens once at the
store boundary and nothing downstream holds a `BaseState` at all.

One thing the copy does not fix, because nothing can: an edit that lands *after* the snapshot is not
in it. A copy in flight sends what the remarks said when it started. That is ordering, not a data
race, and it is what "snapshot then act" means.

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
  `REMARKS_CHANGED`, and navigates on double click through `EditSourceOnDoubleClickHandler`.
  `describe()` and the old `JBList<String>` are gone.

  Delete on rows the user picked out asks nothing — selecting a row and then pressing Delete on it
  is the confirmation. Delete on a *file* node asks, because that node stands for every remark
  under it and a shut node hides how many. The panel tells the two apart by counting rows covered
  against rows picked out, which only works if a refresh puts back the same kind of selection it
  found. So the rebuild captures and restores by key — a remark row is its id, a file group is its
  path — rather than capturing ids and restoring rows. Capturing ids turned a file-node selection
  into N picked-out rows on the first refresh, and refreshes are frequent, so the question stopped
  being asked. The rebuild remembers which file groups were shut for the same reason: `expandAll`
  runs on every refresh, so without putting them back, a group you closed sprang open again as soon
  as you opened any file holding a remark.

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

**Following a file that is renamed or moved.** A remark stores a project-relative path and nothing
ever updates it. Rename a file through Refactor > Rename, drag it in the project view, or move it
with `git mv`, and every remark in it is orphaned for good: `fileForStoredPath` finds nothing, the
resolver refuses with "no file under the project root at that path", the gutter drops the icons
because it matches on the path, and the copied prompt ships those remarks with their text and the
lines that surrounded them when they were written, but with no code from the file itself. There is
no way to re-point a remark from the UI, so they become dead records.

The fix is a `BulkFileListener` on `VFS_CHANGES` reading `VFileMoveEvent` and the rename form of
`VFilePropertyChangeEvent`, rewriting `RemarkState.path` for every remark under the old path —
including the remarks in every file under a renamed *directory*. It needs a ninth mutation
function in `store/RemarkEdits.kt`, because that file holds the only route that changes a remark,
and it needs its own tests. That is a task in its own right rather than a review fix, which is why
it is written down here instead of being half-built.

## What Phase 5 Built

Phase 5 adds three scalar fields to a remark — `severity`, `bucket`, `commit` — and everything that
reads and writes them. Nothing about how a remark is stored changes: they are plain `BaseState`
properties, the same shape as `tag`, and the eight-function rule in "The Change Notification" above
covers the two new mutators the same way it covers the older six.

### Severity

`RemarkSeverity` (`model/RemarkState.kt`) is a four-level enum, low to high: `VIBE`, `SUGGESTION`,
`SHOULD`, `MUST`. It is a second axis next to the tag. The tag says what kind of remark it is;
severity says how strongly to act on it. Without it a `refactor` remark reads the same in the
prompt whether it was an idle thought or the whole point of the reading pass, so the model reading
it either does everything or guesses.

`RemarkState.severity` defaults to `SHOULD` rather than being nullable. A remark you bothered to
write is usually something you want done, and the two ends of the scale are the ones worth choosing
on purpose. Non-null also means the renderer, the tree and the gutter tooltip can print it with no
null check, and a remark stored before this field existed loads with the default instead of a
null — `RemarkStoreStateTest` pins that by deserializing a hand-written XML element with no
`severity` attribute at all.

Severity is worthless unless the prompt acts on it, so `render/PromptRenderer.kt` carries a second
piece of text next to the header: `SEVERITY_SCALE_NOTE`, a `const val` appended under the header on
every copy, spelling out what each level asks of the reader — do a `must` whatever it costs, do a
`should` unless there is a concrete reason not to and say why if you skip it, and so on down to
`vibe`. It is deliberately not folded into `DEFAULT_PROMPT_HEADER`: the header is the one setting
this plugin lets the user rewrite, and anything living only inside it would vanish the moment
somebody replaced it with their own words, while the levels kept printing with nothing left to say
what they mean. Appending the note in the renderer instead means it survives any header, including
one written from scratch.

The input popup does not ask for a severity. That popup is the action that has to stay fast, and a
second chooser in it would turn a fast action into a form. The default is applied when the remark is
written, and changed afterwards from the gutter icon menu or the tree's right-click menu — see "One
menu, two places" below.

### Buckets

`RemarkState.bucket` is a nullable string, a name the user picks, like "auth refactor". Unlike
severity there is no default and no current bucket: a remark starts in no bucket, and a whole
reading pass is moved into one at once, by selecting several rows and choosing Move to Bucket —
buckets are assigned to a selection, not to one remark at a time, so there is nothing to default.
`setRemarkBucket` (`store/RemarkEdits.kt`) trims the name and turns a blank string into null, so
"auth refactor" typed with trailing whitespace cannot become a second bucket that looks identical to
the first one in the tree.

The tree (`ui/RemarksTree.kt`) grows a third level, but only when it is used: `buildTreeRoot` checks
whether any remark actually has a bucket before adding the level at all, so someone who never touches
buckets keeps exactly the tree they had before — root, then file, then remark. Once any bucket
exists, buckets sort by name with the unbucketed ones first, under the `(no bucket)` label
(`NO_BUCKET_LABEL`), because those are the remarks just written and the ones most likely to be
moved next.

A group row — a bucket or a file — is `GroupNode(key, label)`, not a bare string the way a file
group used to be. A bucket named "src" and a directory named "src" can coexist, and the panel
restores a selection after every rebuild by matching a key, so two groups sharing a key would
restore the wrong one after a refresh. The key is the whole path from the root down to that node;
the label is only what gets drawn. A bucket's own key is built from the raw bucket name, not from its
label, so that a bucket somebody actually calls "(no bucket)" does not collide with the null-bucket
group — the null one is keyed `"bucket: none"`, and a leading space cannot occur in a real name
because `setRemarkBucket` trims it.

`remarkNodesUnder` (also `RemarksTree.kt`) walks the whole subtree under a selected node, not one
level down the way it used to. That is what makes Copy Selected on a bucket node mean "copy this
bucket," and it is the entire reason there is no separate Copy Bucket button: select the bucket row
and press Copy Selected. The one-level walk this replaced would have found file nodes under a
bucket — not `RemarkNode`s — and silently answered an empty list, so Copy Selected and Delete on a
bucket node would have done nothing at all, with no message saying why.

### Tag chips, and picking one from the keyboard

The tag drop-down (a Swing `ComboBox`) is gone from `ui/RemarkInputPanel.kt`, replaced by a row of
chips built with the Kotlin UI DSL: `row("Tag:") { chips = segmentedButton(TAG_CHOICES) { text = it
} }`. `TAG_CHOICES` is `(no tag)` followed by the four tags in enum order, and the Alt keys below
index into that same list, so the chips and the keys cannot drift apart from each other.

This removes a special case rather than adding one. The drop-down made Enter ambiguous: with the
list open, Enter meant "commit the highlighted item"; closed, it meant "save the remark" — and the
plugin's own Enter-submits binding won both times, so arrowing down to "bug" and pressing Enter
saved the remark with whatever tag had been selected before. A chip selection is immediate, with no
open state, so that branch of behaviour no longer exists to reason about — for everyone whose chips
really are chips. With a screen reader active they are not: `SegmentedButtonImpl.rebuildUI` builds a
combo box when `ScreenReader.isActive()`, and the old ambiguity is back there. It is recorded in the
known limits of the phase 5 plan rather than fixed, because a correct guard would have to read the
combo popup's highlighted item and commit it by hand, which is more code than the case is worth on a
path no test can reach.

`Alt+0` through `Alt+4` pick a chip directly: `Alt+0` clears the tag, `Alt+1` through `Alt+4` pick
the four tags in the order `TAG_CHOICES` lists them. They are Swing input-map bindings on the text
area, the same mechanism Enter and Shift+Enter already use — ten lines added onto a mechanism that
already existed, rather than waiting on the larger rewrite covered next. See "What is proven and
what is not" below for the real limit of what this proves.

`Cmd+Ctrl+Shift+Space` in the text area (`Ctrl+Alt+Shift+Space` off macOS) opens a chooser
(`ui/ClassNameInsert.kt`) listing every class name the project knows about — the same source,
`ChooseByNameContributor.CLASS_EP_NAME`, that backs Ctrl+N. No extra platform dependency was needed
for it: that extension point is declared in the same descriptor that declares
`com.intellij.modules.platform`, so it is present wherever this plugin can load at all — a
`runCatching` still guards the call, for an IDE that ships its own descriptor instead of IDEA CORE.
Picking a name inserts it at the caret, or replaces the current selection if there is one. An empty
list says so in a message rather than doing nothing.

**Why not `Ctrl+Space`.** The keystroke was `Ctrl+Space` at first, on the reasoning that made the Alt
keys safe: the platform does not dispatch a modal-context-disabled action while a modal-context popup
is focused. Basic Completion is not one of those — `BaseCodeCompletionAction` calls
`setEnabledInModalContext(true)` — so `Ctrl+Space` really is offered to it inside this popup. On macOS
the OS takes that combination as well, for switching input source, so the IDE never sees it. The
binding is a hardcoded Swing input map, not a keymap shortcut, so it has to name a modifier per
platform itself: `Cmd` is `META_DOWN_MASK`, which off macOS is the Super or Windows key and is usually
taken by the window manager, so `Alt` stands in for it there. `ui/RemarkInputPanel.kt`'s
`CLASS_NAME_STROKE` is the one place that decides, and the placeholder text reads it.

This is deliberately the cheap version of an idea in `docs/ideas.md`: no completion popup living
inside the text area, no swap of the plain `JBTextArea` for an `EditorTextField`. That swap was
scoped and rejected before being built. It would have cost the Enter and Shift+Enter bindings, a
fight over which of two popups owns Escape, and an IdeaVim interaction nobody had tested — for a
feature the platform's own Copy Reference (`Ctrl+Alt+Shift+C`) already covers for naming code
elsewhere, since the remark box already accepts a paste.

**The cheap version does nest one popup inside another, and that had to be handled.** An earlier note
claimed it did not. It does: the chooser is a `JBPopup` opened while the remark popup is up. Two
things follow. The remark popup sets `setCancelOnWindowDeactivation(false)`, so a half-typed remark is
not thrown away when the chooser takes the window's focus. And `chooseClassName` checks
`target.isShowing` twice — the read action that fetches the names is asynchronous and
`expireWith(project)` only fires when the project is disposed, not when the remark popup closes, so
without the first check pressing the key and then Escape reached `showInCenterOf` on a component that
is no longer on screen (`IllegalComponentStateException`), and without the second the chosen name went
into a dead text area and the typed remark was lost with nothing said. `cancelOnClickOutside` is left
at its default `true`, so clicking elsewhere in the IDE still abandons a remark:
`StackingPopupDispatcherImpl` only ever cancels the top of the popup stack, which while the chooser is
up is the chooser.

### One menu, two places

`ui/RemarkActions.kt`'s `remarkChangeActions(project, ids)` builds one `ActionGroup` — a Severity
submenu plus "Move to Bucket…" — used from two places: the gutter icon's click menu, which acts on
the one remark under the icon, and the tree's right-click menu, which acts on whatever is selected.

On the tree side, "whatever is selected" needs one thing the platform does not give for free.
`PopupHandler.installPopupMenu` only shows the menu, and `BasicTreeUI` moves the tree selection on
button 1 only — so right-clicking a row that was not selected opened the menu against the previous
selection, and with nothing selected every item was a silent no-op. `RemarksPanel`'s own
`PopupHandler` subclass therefore calls `selectRowForPopup` before it shows the menu. It ADDS the
clicked path rather than replacing the selection, so a right-click inside a selection of several rows
does not collapse it to one — moving a whole reading pass into a bucket is exactly what that selection
is for.
`ids` is a lambda, not a list, because the tree rebuilds itself on every remark change, so a list
captured at the moment the menu was built would be stale by the time anything in it is pressed. The
bucket chooser is `Messages.showEditableChooseDialog`, offering every bucket name already in use
rather than a plain text prompt, because typing the name freehand each time is exactly how "auth
refactor" and "auth-refactor" become two buckets that look like one from across the tree.

### The commit stamp

`store/GitHead.kt`'s `headCommit(startDir)` reads the repository HEAD straight out of `.git`, with
no platform import and no dependency on Git4Idea — git integration lives in that separate plugin,
and depending on it would mean requiring it to be installed. Reading `.git` directly is enough, and
keeps this plugin loading in any JetBrains IDE.

It walks up from the given directory to find the nearest `.git` (a project can be opened at a module
below the repository root). `.git` is a file rather than a directory in a worktree or a submodule,
holding one line, `gitdir: <path>`, which may be relative to the file's own directory. `HEAD` either
holds a sha directly (a detached HEAD) or a line like `ref: refs/heads/main`; the ref is then read
relative to the worktree's own directory joined with its `commondir` file — a plain repository has
no `commondir`, and then the two are the same directory, so one lookup covers both shapes. If there
is no loose ref file left — `git gc` or `git pack-refs` removes it — the sha is read out of
`packed-refs` instead. Everything in this file answers null rather than throwing: a missing commit
stamp is a missing field on the remark, never a reason for the remark not to be added.

`addRemark` (`store/RemarkEdits.kt`) calls `headCommit` once, when the remark is written, and never
again — the point is to record what the author was looking at, not to track the current branch. No
result is cached: two small file reads on the EDT, once per remark, at human typing speed, cost less
than the code a cache would add.

One thing `headCommit` deliberately does not do is stop at the project root. A project that is not
itself a repository but sits inside one — a scratch directory under a `$HOME` dotfiles repo is the
real case — gets that repository's HEAD. This matches what `git` itself would answer from the same
directory, and the walk up is required for the case it exists for: a project opened at a module below
the repository root. It is written down in the known limits because it can mislead, not because it is
wrong: the prompt's scale note tells the model to diff an orphan against the recorded revision.

The commit is shown in three places, each treating it differently because of how crowded the row
already is. The gutter tooltip always has it, cut to eight characters
(`editor/RemarkGutterIcon.kt`'s `tooltipFor`, fed from `RemarkPlacement.commit`). The
copied prompt's heading always has it (`— commit <sha, first 8 chars>` in
`render/PromptRenderer.kt`). The tree row shows it only when the remark is orphaned (`", written at
<sha>"`, in `ui/RemarksTree.kt`'s `remarkNode`), because everywhere else it would be one more thing
on a row that already carries a position, a text, a tag and a level — and it matters most exactly
when a remark has gone missing and someone needs to know which revision to diff the file against.

### The history file, and archiving before delete

Clearing used to delete outright. Now `clearSentRemarks` and `clearAllRemarks`
(`store/RemarkEdits.kt`) write the remarks about to go to a history file first, and only call
`removeSent()` or `clear()` if that write succeeds: the private `archive(...)` helper returns false
and shows a red balloon on `IOException`, and nothing is removed when it does. A single Delete on
one row does not archive anything. Picking out one row and deleting it is an explicit "this one was
a mistake," and archiving every typo along with every real remark would make the history file
useless to read later.

The archive is a markdown file, not a second persisted collection. `store/RemarkHistory.kt`'s
`historyFile(project)` names it `<IDE configuration directory>/claude-remarks/<project
name>-<location hash>.md`, one file per project. This was a deliberate choice against a structured
XML archive: the active remarks list must not grow, because every remark in it is resolved against
its file on every change, and a markdown file satisfies that completely — nothing ever resolves it.
The alternative, a second `PersistentStateComponentWithModificationTracker`, would copy
`RemarkStore`'s whole thread-safety shape (the deep `snapshot()`, the `@Synchronized` mutators,
`modCount()`), add a second `@get:XCollection` and its silent-data-loss trap, and still need a browse
window before anyone could read a single archived remark — which this plan does not build either
way. The markdown file is about fifteen lines of code, and can be opened, grepped and pasted from
today. What is given up: there is no button that restores an archived remark.

`appendToHistory` writes what was STORED about each remark — its stored line numbers, text, tag,
severity, bucket and commit — not a fresh resolve against the file as it stands now: by the time
anyone reads the archive the code has likely moved on, and the file it once lived in may not even
exist any more. Each entry is indented under its heading, the same defence the prompt renderer uses
against backtick fences and stray headings in a remark's own text, so a remark whose text happens to
contain a markdown heading cannot restructure the archive around it.

### What is proven and what is not

Everything above is covered by a plain JUnit test, a fixture-backed test, or both, except for the
keystrokes, which are flagged here rather than claimed as working:

- **Whether `Alt+1` through `Alt+4` actually reach the popup.** They are Swing input-map bindings on
  a `JBTextArea` inside a `JBPopup`, and the IDE's default keymap binds those same key combinations
  to the four numbered tool windows. The automated tests (`RemarkInputPanelTest`) prove the bindings
  exist and act on the right chip when invoked directly — they cannot prove the key event reaches
  the text area rather than the tool-window shortcut first. The reasoning for why it should still
  work is source-level: the popup is a heavyweight, modal-context popup, and the platform does not
  dispatch a modal-context-disabled action (`ActivateToolWindowAction`, behind the default Alt+1..4
  bindings, is one) while such a popup is focused. That reasoning was worked out across several
  platform classes during phase 5, not observed in a live IDE — `./gradlew runIde` is not run from
  an agent session. Hand check 1 in the phase 5 plan (`docs/plans/20260803-claude-remarks-phase5.md`,
  section 10) is the actual authority on this until someone runs it.
- **Whether `Cmd+Ctrl+Shift+Space` reaches the popup, and how it behaves on macOS.** Same shape of
  gap as the Alt keys, and the same kind of reasoning behind the choice — see "Why not `Ctrl+Space`"
  above. `RemarkInputPanelTest` proves the binding exists on the right keystroke and that `Ctrl+Space`
  is no longer bound; it cannot prove the key event arrives, and it says nothing about how the chooser
  and the remark popup behave next to each other on screen. Hand check 10 in the phase 5 plan is the
  authority.

The commit capture inside `addRemark` used to be listed here as unprovable. It is not: the light
fixture project has a real base directory, and `RemarkEditsTest` now writes the same three files
`GitHeadTest` builds — `.git/HEAD` and the loose ref it names — into it, then asserts the stored
`commit`. `GitHeadTest` still covers `headCommit` itself against a plain repository, a worktree, a
detached HEAD and packed refs. Hand check 6 remains worth doing, because only a real repository proves
the stamp equals `git rev-parse HEAD`.

## Adding a Remark While Reading a Diff

A diff pane is an editor, so `Ctrl+Alt+Shift+R` and the right-click item reach it. The file behind
it is the problem. For the side that shows a VCS revision, the document is not the project's file:
`DiffContentFactoryImpl` builds a `LightVirtualFile` from the revision's bytes, and that file
carries the right NAME but no path under the project. `FileDocumentManager.getFile(document)`
returns it, `VfsUtilCore.getRelativePath` returns null for it, and the plugin used to refuse with
"X is outside the project directory" for a file the user could plainly see in the project tree.

**The route back to the real file.** `DocumentContent.getHighlightFile()`. For a VCS revision the
chain is `ChangeDiffRequestProducer.createContent` → `DiffContentFactoryEx.createFromBytes(project,
bytes, filePath)` → the content builder's `Context.ByFilePath`, whose `getHighlightFile()` is
`filePath.getVirtualFile()` — the working tree's file. The content itself is reached through
`DiffDataKeys.CURRENT_CONTENT` (note the package: `com.intellij.diff.tools.util`), which
`TwosideDiffViewer.uiDataSnapshot` fills with `getCurrentSide().select(request.contents)`, and
`getCurrentSide()` is the focus tracker's side. So the content in the data context is the pane the
user is reading, which is the pane they meant.

**Why it needs a `DataContext`.** There is no route from an `Editor` alone. `DiffUtil.createEditor`
builds the diff editors with no file at all, and `DiffUtil.configureEditor` then sets
`editor.setFile(FileDocumentManager.getInstance().getFile(content.getDocument()))` — the same
LightVirtualFile that already failed. `editor.virtualFile` is therefore a dead end, checked against
the 2025.2 jars and against the platform source. So `relativePathOf` and `remarkTargetProblem` in
`store/RemarkTarget.kt` take an optional `DataContext`. The action passes `e.dataContext`. The
intention builds one with `DataManager.getInstance().getDataContext(editor.contentComponent)` in
`invoke`, and only there: that call asserts it is on the EDT, while the daemon computes
`isAvailable` on a background thread. That is why `isAvailable` offers the intention in any
`EditorKind.DIFF` editor without deciding, and lets `invoke` either open the input or show the
reason at the caret.

**What such a remark means.** The path is the working tree's file. The anchor is captured from
`editor.document`, which is the *revision's* text — and that is correct, not a bug to fix later. An
anchor is a fingerprint, never a pointer: `textHash` plus a few context lines. Resolving it runs
against the working tree like every other remark, so the two-pass search in `anchor/Anchoring.kt`
either finds the block unchanged, relocates it within the search radius, or orphans it. A remark
written against a revision whose lines still exist in the working tree lands on them. One written
against lines that the working tree no longer has is orphaned, which is the truth about it. That is
what makes annotating a revision honest rather than wrong.

**The path-escape guard is not bypassed.** Both candidate files — the document's own file first,
the highlight file second — go through the same `VfsUtilCore.getRelativePath(file, root)`, which
returns null unless `root` really is an ancestor. There is no second route around the check, which
is the whole reason the fallback is a second entry in one list rather than a branch of its own.

**What is checked and what is not.** `DiffRemarkTargetTest` builds the exact content shape a
revision produces, from the platform's own `DiffContentFactory`, and drives the real action through
a `TestActionEvent`. It does not build a `SimpleDiffViewer`, which a light fixture cannot do, so
nothing in the suite proves the platform really publishes `CURRENT_CONTENT` for the focused pane —
that came from reading `TwosideDiffViewer` and still needs one hand check in a sandbox IDE.

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

That rule has one exception, and it is the reason the service also listens to
`FileDocumentManagerListener.fileContentReloaded`. A git checkout, a branch switch, a VCS revert or
an external edit replaces an open document's whole text in one write action. The position the
platform kept through that means nothing, so keeping the live highlighter would leave the icon on
whatever code now sits at those offsets, with nothing saying it moved — the wrong relocation that
this plugin's anchoring rules exist to avoid. On a reload the service therefore drops the document
and tracks it again, which resolves its remarks against the new content exactly as opening the file
would. The Refresh button in the tool window publishes `REMARKS_CHANGED` for the same reason: it is
the manual escape for the gutter as well as for the tree.

## The Change Notification

`REMARKS_CHANGED` (a `Topic<RemarksListener>`, project-level, `BroadcastDirection.NONE`) lives in
`store/RemarkEdits.kt`, beside the eight functions that publish it, not inside `RemarkStore`. Two
things need to hear about a change: the gutter service and the tool window tree. Keeping the topic
out of the store is about cost, not purity: adding a `Project` constructor parameter to
`RemarkStore` would touch fourteen call sites that build it directly, and keeping the store free
of the message bus is what lets `RemarkStoreStateTest` stay a plain JUnit test with no IDE fixture.

`store/RemarkEdits.kt` holds the only eight functions production code uses to change a remark:
`addRemark`, `editRemark`, `deleteRemark`, `markRemarksSent`, `setRemarkSeverity`,
`setRemarkBucket`, `clearSentRemarks`, `clearAllRemarks`. Each one mutates through `RemarkStore` and
then publishes — that pairing is the whole mechanism, there is no separate listener list or observer
class. `RemarkStore`'s own `add`/`remove`/`edit`/`setSeverity`/`setBucket`/... stay public, and
nothing in the language stops a caller from reaching past the eight functions and calling them
directly, so the rule is checked rather than assumed. The check used to list the mutator names by
hand, which is exactly what let phase 5 add `setSeverity`/`setBucket` to `RemarkStore` without the
old grep noticing: a hand-picked list has to be edited every time a mutator is added, and forgetting
is silent — the guard keeps passing while it stops covering the new function. The grep in
`CLAUDE.md` now allows through the one read-only method by name, `all()`, instead:

```bash
grep -rn "RemarkStore\.getInstance([^)]*)\." src/main/kotlin --include=*.kt \
  | grep -v RemarkEdits.kt | grep -v "\.all()"   # must be empty
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
`PROMPT_CONTEXT_LINES` (3) either side (`render/PromptPayload.kt`'s `collectForPrompt`), and
rendering the markdown (`render/PromptRenderer.kt`'s `renderPrompt`, zero platform imports, the
same shape as `anchor/Anchoring.kt`). The EDT step that follows builds the clipboard payload, copies
it, runs `markRemarksSent` and shows a balloon.

**`clipboardPayload` is one function with a size check, not two implementations.** Under
`INLINE_LIMIT_BYTES` (100 KB, measured in UTF-8 bytes, not characters) the markdown goes on the
clipboard as text. At or above that, it is written to a file under `java.io.tmpdir` and the file's
path is copied instead — `Clipboard(text = path, file = path)`. The caller does not branch on
which mode ran; it reads `Clipboard.file` only to word the balloon differently. There is no
`Dispatcher` interface, because there is one implementation.

**`clipboardPayload` runs in the `finishOnUiThread` block, so the file write is on the EDT.** An
earlier design put it inside the read action, to keep every slow thing off the EDT. That is wrong
for this particular write: `ReadAction.nonBlocking` cancels and re-runs its block whenever a write
action asks for the lock, so a file write in there runs again on each retry and leaves a temp file
behind every time. The third option — a second executor hop after the read action and then back to
the EDT — was weighed and dropped: it is more machinery than the case is worth. Below the 100 KB
inline limit nothing is written at all, and above it one `Files.writeString` of a few hundred
kilobytes into the system temp directory is a millisecond or two. If a payload ever grows large
enough for that to be felt, the extra hop is the fix.

The copy has a failure path. `IOException` from the write and `IllegalStateException` from the
clipboard are both reported through a red balloon, and `markRemarksSent` is skipped — nothing was
handed over, so nothing is marked as handed over. The read action has one too: `onError` on the
promise reports anything thrown inside `prepare` the same way. Without it a failure there skipped
`finishOnUiThread` entirely, so the whole action put nothing on the clipboard, marked nothing sent
and said nothing at all, with the reason only in the platform log. A run dropped by `coalesceBy` or
expired with the project lands in `onError` too and stays quiet, which is why the cancellation types
are checked there.

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
that resolves as `Orphaned`, still gets a row in the prompt rather than silently disappearing from
the copy. Neither quotes code at the stale line numbers: whatever drifted into that position is
not the remark's code, and the header tells the reader to trust the quoted lines over the numbers,
so quoting unrelated code under a `>` marker would be an instruction to edit the wrong lines.

What an orphan does quote is the context stored with it — the lines that sat just above and just
below it when it was written, with `... the lines this remark points at were here ...` between the
two halves and no line numbers or `>` markers, because neither would be true. That block is the
only thing left to search the file for, and it costs nothing: the context is already persisted for
the anchor search. Without it a renamed file, which orphans every remark in it at once, would ship
a whole file's remarks with a path, numbers the header says to ignore, and nothing to look for.

Two things go into the markdown that the plugin does not control. The quoted code can hold a fence
of its own (a `.md` file, a doc comment with an example), so the fence is sized one backtick past
the longest backtick run inside the block. The remark text is prose outside every fence, and
Shift+Enter makes a pasted snippet ordinary, so each of its lines that would open a fence, forge a
heading or turn the line above into one is escaped with a backslash. Either one, left alone, breaks
every remark listed after it. The prompt header itself is the user's own text and is written out as
given: it is deliberate markdown, at the top, where its author can see what it did.

The header follows revdiff's model: a remark that asks a question gets answered rather than turned
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
