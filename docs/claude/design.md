# Claude Remarks — Design Document

## Contents

1. [Overview](#overview)
2. [The Data Model](#the-data-model)
3. [The Anchoring Design](#the-anchoring-design)
4. [From Stored Remarks to Tool Window Rows](#from-stored-remarks-to-tool-window-rows)
5. [The ProjectUtil Trap](#the-projectutil-trap)
6. [Why the Bookmarks API Was Rejected](#why-the-bookmarks-api-was-rejected)
7. [What Phases 3-4 Built](#what-phases-3-4-built)
8. [What Phase 5 Built](#what-phase-5-built)
9. [Adding a Remark While Reading a Diff](#adding-a-remark-while-reading-a-diff)
10. [The Editor Side](#the-editor-side)
11. [The Change Notification](#the-change-notification)
12. [The Publish Pipeline](#the-publish-pipeline)
13. [A Remark About No File](#a-remark-about-no-file)
14. [One Pass Over The Tree](#one-pass-over-the-tree)
15. [A Remark on the Rendered Preview](#a-remark-on-the-rendered-preview)
16. [The Shared Review Session](#the-shared-review-session)
17. [Two Positions On Screen, And When They Differ](#two-positions-on-screen-and-when-they-differ)
18. [Build Choices Worth Remembering](#build-choices-worth-remembering)
19. [Performance Tuning Knobs](#performance-tuning-knobs)
20. [Known Issues](#known-issues)

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
- `status`: One of `RemarkStatus.PENDING`, `PUBLISHED` or `READ`. Defaults to `PENDING`. Set to
  `PUBLISHED` by `markRemarksPublished` once a publish reaches the clipboard or the published file,
  and to `READ` by `markRemarksRead` once a review acknowledgement says an agent read it. See "The
  three states, and why published is not read" below. Published and read remarks both stay in the
  list, drawn gray, until Clear Handed Over.
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

Every method on the state class (`addRemark`, `removeRemark`, `editRemark`, `markPublished`,
`markRead`, `removeHandedOver`, `clear`, `snapshot`, `modCount`) is `@Synchronized` on the state
object, so they all
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

### The phrase a remark points at

A remark can point at part of one line, or at a range across a few lines, not only at whole lines.
`RemarkState.startColumn` and `endColumn` hold that sub-line range. Phase 9 added a third field next
to them, `phrase`: the exact text between the two columns, joined with newlines when the range spans
more than one line. Null for a whole-line remark, and null for every remark stored before this field
existed, because `BaseState` omits a property still at its default.

**Why the text is stored, not a hash of it.** A hash can only confirm a guess: given a candidate
position, it says whether that position is right. It cannot produce the candidate in the first
place. Finding a phrase that moved needs something to search *for*, and for a sub-line range the
candidates are every substring of every line near where the remark used to be. With the phrase
stored as real text, finding it again is one `indexOf` per candidate line. The cost is a slightly
bigger `workspace.xml`. That cost was already being paid: `contextBefore` and `contextAfter` store
six lines of real source per remark. A phrase is short by definition, since a long selection is
rare, so it adds little next to that.

**Two pure functions in `anchor/Anchoring.kt`, next to `hashLines` and `captureAnchor`, and neither
one touches them.**

- `phraseAt(lines, startLine, endLine, startColumn, endColumn)` reads the phrase out of the file at
  write time. Inside one line it is the plain substring. Across lines it is the tail of the first
  line, the whole lines between, and the head of the last, joined with newlines. This is the same shape
  `withSelectionMarkers` in `render/PromptRenderer.kt` already assumes when it draws the two
  `⟦`/`⟧` markers on separate quoted lines. Returns null for anything that is not a real sub-line
  range, so a whole-line remark never gets a phrase. What counts as a real sub-line range is not
  decided here. It is `hasSubLineRange` in `anchor/SubLineRange.kt`, described below.
- `findPhrase(lines, phrase, origin, radius)` is the reverse: given a phrase, find where it sits now.
  Nearest-first outward from `origin`, the same search order `resolveAnchor` already uses, because a
  short phrase often repeats in a file and the occurrence the remark meant is the one closest to
  where it used to be.

**`resolveWithPhrase` composes `resolveAnchor` and `findPhrase`, and changes neither.** It is the one
new function in `store/RemarkResolver.kt`'s call path, and it answers one of four ways:

- No stored phrase: `resolveAnchor`'s own answer, with the stored columns. Every remark written
  before this field existed, and every whole-line remark, takes this path and it must be identical
  to the line-only resolve, not merely close to it.
- A phrase, and the lines were found (`Exact` or `Relocated`): look for the phrase on the resolved
  line. Found: the same line result, with the columns where the phrase actually sits now. This is
  what keeps the tree row and the prompt markers on the right words after the line was reindented.
  Not found: the same result, with the stored columns, and `markersValid` in the renderer then
  decides on its own whether those stale columns are still in bounds.
- A phrase, and the lines orphaned: search for the phrase near the stored line, inside the same
  search radius the line search uses. Found: `Relocated` onto it. This is the one case the anchor
  could not resolve on its own before this field existed. A paragraph that reflowed has no line left
  that hashes or context-matches, but the words themselves are still in the file, findable by text.
  Not found: orphaned, exactly as before.

**Where the phrase shows up.** `ui/RemarksTree.kt`'s tree row and `store/RemarkHistory.kt`'s archive
heading both print the resolved position in the same shape: `9-9` for a whole-line remark, `9:12-38`
for a sub-line remark inside one line, `9:12-11:5` for one that crosses lines, columns shown 1-based.
The phrase text itself is shown only in the gutter tooltip and, since this task, on its own indented
line in the history file. It is never shown in the tree row, which already crops on the right and has no room.

**One rule, one place: `anchor/SubLineRange.kt`.** Four callers ask the same two questions — is there
a sub-line range here, and what is it called. `phraseAt` asks it when the remark is written,
`markersValid` in `render/PromptRenderer.kt` asks it when the prompt is drawn, and the tree row and
the history heading ask it when a person reads the position. The rule is not a plain comparison of
the two columns, and that is why it is worth a file of its own:

- Inside one line the two columns bound the same line, so a real range means `endColumn >
  startColumn`.
- Across lines they are two independent offsets, each measured from the start of *its own* line, by
  `selectedColumns` in `action/AddRemarkAction.kt`. A long first line and a short last line then make
  `endColumn < startColumn` for a perfectly ordinary selection — drag from column 25 of one line down
  to column 8 of another. Ordering the two columns against each other throws that selection away, so
  across lines the check is `endColumn > 0`, which asks only whether this is the `0 to 0` sentinel a
  whole-line remark carries.

Two of the four callers used the ordered check on both shapes until this was shared. The cost was
real and silent: about half of all partial multi-line selections were stored with no phrase, so they
never got the reflow rescue above, and their published prompt carried no `⟦`/`⟧` markers at all —
while the tree row and the history file went on printing the exact columns. The shape itself,
`positionLabel`, lives in the same file for the same reason, with the tree passing the `0 to 0`
sentinel for an orphaned row rather than keeping a second copy of the rule.

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
- **The markdown renderer** (`render/PromptRenderer.kt`) and **the publish pipeline**
  (`render/PromptPayload.kt`, `action/PublishRemarks.kt`, renamed from `CopyRemarks.kt` in phase 9)
  are covered in "The Publish Pipeline" below.
- **The toolbar** has six buttons today, in this order: Add General Remark, Publish Unread, Publish
  Selected, Clear Handed Over, Clear All, Refresh. Phases 3-4 built five of them, called Copy All
  Pending, Copy Selected, Clear Sent, Clear All and Refresh. Phase 9 renamed the first three to
  Publish All Pending, Publish Selected and Clear Handed Over, and its group three added Add General
  Remark as the sixth. Phase 10 renamed Publish All Pending to Publish Unread, when its filter
  changed from "still `PENDING`" to "not yet `READ`". Both Clear buttons ask first and name their
  count. Publish Selected and Clear Handed Over grey out when
  there is nothing to act on, because a live button that does nothing when pressed is its own kind
  of silent failure. This is the same reasoning that keeps `AddRemarkAction` visible above.

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
because it matches on the path, and the published prompt ships those remarks with their text and the
lines that surrounded them when they were written, but with no code from the file itself. There is
no way to re-point a remark from the UI, so they become dead records.

The fix is a `BulkFileListener` on `VFS_CHANGES` reading `VFileMoveEvent` and the rename form of
`VFilePropertyChangeEvent`, rewriting `RemarkState.path` for every remark under the old path —
including the remarks in every file under a renamed *directory*. It needs one more mutation
function in `store/RemarkEdits.kt`, past the eleven public functions already there today, because
that file holds the only route that changes a remark, and it needs its own tests. That is a task in
its own right rather than a review fix, which is why it is written down here instead of being
half-built.

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
every publish, spelling out what each level asks of the reader — do a `must` whatever it costs, do a
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
level down the way it used to. That is what makes Publish Selected on a bucket node mean "publish
this bucket," and it is the entire reason there is no separate Publish Bucket button: select the
bucket row and press Publish Selected. The one-level walk this replaced would have found file nodes
under a bucket rather than `RemarkNode`s, and silently answered an empty list, so Publish Selected and
Delete on a bucket node would have done nothing at all, with no message saying why.

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

`projectClassNames` tolerates a broken contributor and never a cancellation. `getNames` walks stub
indexes, which call `ProgressManager.checkCanceled()` constantly, and this whole function runs inside
`ReadAction.nonBlocking` — so when a write action asks for the lock, `ProcessCanceledException` is how
the read action is told to unwind and give it back. A `catch` on `Throwable` turned that into an empty
list and carried on to the next contributor, which swallowed its own cancellation in turn: the lock
stayed held for the length of the whole walk plus `distinct().sorted()` over tens of thousands of
strings, which is the EDT stall a non-blocking read action exists to prevent. Both catches now rethrow
`ProcessCanceledException` ahead of the `Throwable` case that covers a missing extension point.

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

Two things it does guard. The ref path out of `HEAD` is required to stay under the git directory
(`refInside`), so a hand-written `ref: ../../../../etc/shadow` is refused rather than opened — only 40
lowercase hex survives `SHA.matches`, so at most one bit would leak, but there is no reason to read
the file. What that cannot cover: `readString` follows symlinks, so a symlinked `refs/heads/main`
still resolves wherever it points. The `gitdir:` path is deliberately NOT constrained the same way,
because pointing outside is what `gitdir` is for — a worktree's `.git` names
`<main repo>/.git/worktrees/<name>`, which is not under the worktree at all. And `readTrimmed` catches
`IOException` and `RuntimeException` rather than `Throwable`: it reads a whole file into a `String`
before anything looks at it, so a `packed-refs` big enough to exhaust the heap used to be reported as
"no commit" with the `OutOfMemoryError` swallowed and relabelled.

The read stays on the EDT, and the comment in `addRemark` now names the real ceiling rather than "two
small file reads": for a loose ref it is two, but after `git gc` the third read is all of `packed-refs`,
which on a large repository is megabytes read into a `String` and split. Still once per remark at
typing speed, so it stays where it is, with the ceiling written down.

The commit is shown in three places, each treating it differently because of how crowded the row
already is. The gutter tooltip always has it, cut to eight characters
(`editor/RemarkGutterIcon.kt`'s `tooltipFor`, fed from `RemarkPlacement.commit`). The
published prompt's heading always has it (`— commit <sha, first 8 chars>` in
`render/PromptRenderer.kt`). The tree row shows it only when the remark is orphaned (`", written at
<sha>"`, in `ui/RemarksTree.kt`'s `remarkNode`), because everywhere else it would be one more thing
on a row that already carries a position, a text, a tag and a level — and it matters most exactly
when a remark has gone missing and someone needs to know which revision to diff the file against.

### The history file, and archiving before delete

Clearing used to delete outright. Now `clearHandedOverRemarks` and `clearAllRemarks`
(`store/RemarkEdits.kt`) write the remarks about to go to a history file first, and only call
`removeHandedOver()` or `clear()` if that write succeeds: the private `archive(...)` helper returns
false and shows a red balloon on `IOException`, and nothing is removed when it does. Phase 9 renamed
this pair from `clearSentRemarks`/`removeSent()`, once "sent" stopped being the only word for
"handed over". See "The three states, and why published is not read" below. A single Delete on
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

Two details of the write are less obvious than they look. The history file is a **nullable** parameter
on `clearHandedOverRemarks` and `clearAllRemarks`, not one defaulted to `historyFile(project)`:
Kotlin evaluates a default argument in the synthetic bridge, before the body runs, so a default
would resolve the path OUTSIDE `archive`'s try. Anything it threw would then leave the function as
an unhandled exception out of the toolbar action rather than as the balloon written for that case.
Null means "the real one", resolved inside the try. And Clear Handed Over shows its success balloon
only when something was actually removed: `clearHandedOverRemarks` returns 0 both for "nothing was
handed over" and "the archive failed", the handed-over count is already known non-zero by then, so 0
can only mean failure. "Removed 0 handed-over remarks." beside a red error balloon was therefore the
wrong half of the truth.

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

**Such a remark is refused, since phase 7.** The route above finds the working tree's file, and the
anchor would still be captured from `editor.document`, which is the *revision's* text. Phase 6 shipped
that combination as a stored remark; phase 7 refuses it. `remarkTargetProblem` answers with a sentence
naming the working copy, and the reasoning for the reversal is in "Opening the diff the skill asked
for" below — opening a diff by default made this pane common rather than rare, and a remark whose line
numbers describe a different revision either lands by luck or orphans with no warning.

**The anchor-is-a-fingerprint reasoning still holds, for the working-copy side.** An anchor is a
fingerprint, never a pointer: `textHash` plus a few context lines, resolved against the working tree
by the two-pass search in `anchor/Anchoring.kt`. That is why the working-copy pane of a diff needs no
special case at all — its document *is* the file the remark is stored against — and it is also why the
revision side is not merely imprecise but describes text that is not there.

**The path-escape guard is not bypassed.** Both candidate files — the document's own file first,
the highlight file second — go through the same `VfsUtilCore.getRelativePath(file, root)`, which
returns null unless `root` really is an ancestor. There is no second route around the check, which
is the whole reason the fallback is a second entry in one list rather than a branch of its own.

**Since the refusal, the second candidate is not a storage route at all.** It is what lets
`remarkTargetProblem` tell a revision pane apart from a file genuinely outside the project, so each
gets its own sentence. `relativePathOf` still reads the same list, but in production only its first
candidate can ever answer: its one caller, `openNewRemarkInput`, returns before it whenever
`remarkTargetProblem` is non-null. `DiffRemarkTargetTest` asserts the second candidate resolves, and
that assertion pins the two functions reading one candidate list — not a path a remark can take.

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
`store/RemarkEdits.kt`, beside the ten functions that publish it, not inside `RemarkStore`. Two
things need to hear about a change: the gutter service and the tool window tree. Keeping the topic
out of the store is about cost, not purity: adding a `Project` constructor parameter to
`RemarkStore` would touch fourteen call sites that build it directly, and keeping the store free
of the message bus is what lets `RemarkStoreStateTest` stay a plain JUnit test with no IDE fixture.

`store/RemarkEdits.kt` holds the only ten functions production code uses to change a remark:
`addRemark`, `editRemark`, `deleteRemark`, `markRemarksPublished`, `markRemarksRead`,
`setRemarkSeverity`, `setRemarkBucket`, `clearHandedOverRemarks`, `clearAllRemarks`, and
`addGeneralRemark`. Phase 9 grew this list from eight in two steps: group one split
`markRemarksSent` into `markRemarksPublished` and the new `markRemarksRead`, and renamed
`clearSentRemarks` to `clearHandedOverRemarks`. See "The three states, and why published is not
read" below. Group three added `addGeneralRemark`, the one entry point for a remark about no file.
See "A Remark About No File" below. The file's eleventh public function, `notifyRemarksChanged`, is
what every one of the ten calls to publish the topic; it counts too, because `CLAUDE.md` rule 3
checks the file by counting every public function it finds there, not by naming the mutators by
hand. Each one mutates through `RemarkStore` and then
publishes. That pairing is the whole mechanism. There is no separate listener list or observer
class. `RemarkStore`'s own `add`/`remove`/`edit`/`setSeverity`/`setBucket`/... stay public, and
nothing in the language stops a caller from reaching past the ten functions and calling them
directly, so the rule is checked rather than assumed. The check used to list the mutator names by
hand, which is exactly what let phase 5 add `setSeverity`/`setBucket` to `RemarkStore` without the
old grep noticing: a hand-picked list has to be edited every time a mutator is added, and forgetting
is silent — the guard keeps passing while it stops covering the new function. The grep in
`CLAUDE.md` now allows through the one read-only method by name, `all()`, instead:

```bash
grep -rn "RemarkStore\.getInstance([^)]*)\." src/main/kotlin --include='*.kt' \
  | grep -v RemarkEdits.kt | grep -v "\.all()"   # must be empty
```

The glob has to be quoted. Unquoted, zsh expands `*.kt` itself before `grep` ever runs, and if
nothing in the current directory matches it, zsh fails the whole line with "no matches found"
before the pipeline starts. That prints nothing, exactly what an empty, passing result also looks
like. Checked directly: `zsh -c` with the bare form fails that way; the quoted form runs. See
`CLAUDE.md`, rule 3, for the same fix.

Test code is outside that check on purpose: fixture-backed test classes call
`RemarkStore.getInstance(project).clear()` in `setUp` to clear the shared light-fixture project
between test classes, and that call has nothing to publish to.

## The Publish Pipeline

This section used to be called "The Copy Pipeline". Phase 9 renamed the action from Copy to
Publish and added a second destination, the published file, described below in "The published
file".

Publish Unread and Publish Selected both end up in `publishRemarks(project, ids)`
(`action/PublishRemarks.kt`). `ids == null` means every remark that is not yet `READ` — every
`PENDING` or `PUBLISHED` one, renamed from Publish All Pending and widened from "still `PENDING`" in
phase 10 — and a non-null list is used as given, published or even already-read remarks included,
which is what makes publishing again after a paste went to the wrong place work.

### The three states, and why published is not read

`RemarkStatus` (`model/RemarkState.kt`) has three values: `PENDING`, `PUBLISHED`, `READ`. A remark
starts `PENDING`. Publishing it, through either the clipboard or the published file described below,
moves it to `PUBLISHED`. Since phase 10, two things can move it to `READ`, and both are answers to
something the IDE itself minted rather than a side effect of a handover: an agent telling the IDE it
read a waiting review's answer, in `reportReviewEnd`'s `ReviewEnd.READ` branch in
`review/SendReview.kt`, over `POST /api/claude-remarks/ack`, keyed to the session id the review
handed out when it started; or an agent telling the IDE it read a published batch, in
`reportPublishedRead` in `review/PublishedAck.kt`, over `POST /api/claude-remarks/published-read`,
keyed to the nonce that batch's own header carries. Before phase 10 only the first of these existed,
and answering a waiting review was the only way to ever confirm a read; a plain publish with no
review open could only ever reach `PUBLISHED`, with no way out of it. Writing the file in the first
place changes no state at all, on either route: the file is written, but the remarks stay whatever
they already were until one of the two acknowledgements arrives, or forever if neither does.
`CLAUDE.md` rule 6 keeps this true by grep: only `store/RemarkEdits.kt`, `review/SendReview.kt` and
`review/PublishedAck.kt` may call `markRemarksRead`.

The reason for two separate words, `PUBLISHED` and `READ`, rather than one, is that a status only
earns `READ` once something actually confirms a read happened. The clipboard is one-way and stays
that way forever: the plugin hands the text over and has no way to learn whether anyone pasted it.
The published file used to be one-way too, in phase 9, but phase 10 gave every write to it a nonce
and an acknowledgement route, so a plain publish can now be confirmed the same way a review's own
answer always could — it just usually isn't, because nothing is watching for it. `PUBLISHED` means
"handed to a channel that has not yet been confirmed read," not "handed to a channel that never
can be." `READ` is what either kind of confirmation is worth once it arrives. Treating the two as the
same state would let a publish claim a confirmation nobody gave, on either route.

Publishing a remark that is already `READ` moves it back to `PUBLISHED`, never the other way round.
Handing a remark over again is a new handover, and nothing has confirmed that second one yet, so
claiming `READ` for it would be a lie. `markPublished` (`store/RemarkStore.kt`) counts a remark as
changed whenever its status is not already `PUBLISHED`, which is what makes that move happen.
`removeHandedOver` removes everything that is not `PENDING`, so Clear Handed Over takes `PUBLISHED`
and `READ` remarks together, archiving them to the history file first exactly as before.

The tree row and the gutter icon draw three appearances, one per state, all through the same
`GRAYED_ATTRIBUTES`. The word at the end of the row, "published" or "read", is what actually tells
the two grey states apart, because a second shade of grey is a distinction a person could not read
reliably on screen. `PENDING` keeps `AllIcons.General.Note` at full strength and a black row.
`PUBLISHED` keeps the icon at 45% opacity, exactly what "sent" used before this phase. `READ` is
fainter still, at 25%, because it is one step further into "already dealt with."

`RemarkStatus` used to be a two-value enum, `PENDING` and `SENT`, and its own KDoc said `SENT` meant
"a copy reached the clipboard" while the review path wrote the same value for "an agent read it":
one stored value doing two different jobs. Nothing outside Kotlin ever reads the persisted string
(only `workspace.xml` does, through the platform's own `BaseState` serializer), so the rename costs
exactly one thing: a remark a pre-phase-9 build stored as `SENT` does not parse against the new enum
and loads as `PENDING`. That is accepted. Those remarks had already been handed over once, under the
old, single meaning, and nothing about them is lost but a colour. See `RemarkStoreStateTest`'s
`a remark stored as SENT by an older build loads as pending`, which pins the reset as a decision.

Everything expensive runs inside one `ReadAction.nonBlocking` block, off the EDT: resolving
(`resolveAll`), reading each file's `Document` once and slicing the anchored lines plus
`PROMPT_CONTEXT_LINES` (3) either side (`render/PromptPayload.kt`'s `collectForPrompt`), and
rendering the markdown (`render/PromptRenderer.kt`'s `renderPrompt`, zero platform imports, the
same shape as `anchor/Anchoring.kt`). The EDT step that follows builds the clipboard payload, copies
it, writes the published file (see "The published file" below), runs `markRemarksPublished` and
shows one balloon.

The balloon's file count leaves general remarks out. A remark about the whole change carries no
path — an empty string by the time `collectForPrompt` has run — so counting distinct paths without
filtering counted "no file" as a file, and one general remark on its own made the balloon say
"across 1 file" with no file involved at all.

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

The publish has a failure path, and it now has two destinations that can each fail on their own.
An `IOException` from the temp-file write or an `IllegalStateException` from the clipboard is
reported through a red balloon, and `markRemarksPublished` is skipped entirely. Nothing was handed
over, so nothing is marked as handed over. A failure in the published-file write is different: it
runs after the clipboard already succeeded, so the remarks **are** still marked published, and the
balloon names the file failure in the same sentence rather than staying silent about it. See "The
published file" below for why that asymmetry is the honest answer. The read action has a failure
path too: `onError` on the promise reports anything thrown inside `prepare` the same way. Without it
a failure there skipped `finishOnUiThread` entirely, so the whole action put nothing on the
clipboard, marked nothing published and said nothing at all, with the reason only in the platform
log. A run dropped by `coalesceBy` or expired with the project lands in `onError` too and stays
quiet, which is why the cancellation types are checked there.

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

**Correction.** This section used to end by saying an automated dispatch step beyond the clipboard
was never built, full stop. That specific idea — the `Dispatcher` interface, the tmux pane, a file
inside `.idea/` — stays dropped for the reasons above. Phase 6 built a different automated path
instead, over a different transport: see "The Shared Review Session" below. Phase 10 then merged
that path's own file into this one — a publish, a review's answer and a rejection all now write
through `writePublished` in `review/PublishedRemarks.kt`, described below in "The published file" —
so the two sections now describe one shared destination, even though starting and answering a review
is still driven from `review/`, not from here. Publish All Pending and Publish Selected still end at
`publishRemarks`, unchanged in shape. They were renamed from Copy All Pending and Copy Selected in
phase 9, which also gave them a second destination, the published file. Publish All Pending was
renamed again, to Publish Unread, in phase 10, when its filter changed from "still `PENDING`" to
"not yet `READ`" — described below in "The published file".

`resolveAll` and `collectForPrompt` never drop a remark. A file that cannot be read, or a remark
that resolves as `Orphaned`, still gets a row in the prompt rather than silently disappearing from
the publish. Neither quotes code at the stale line numbers: whatever drifted into that position is
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
every remark listed after it. "Turn the line above into one" means a SETEXT underline, and it takes
only ONE character: a line of `=` under a paragraph is an H1, a line of `-` is an H2. `STRUCTURE_LINE`
therefore matches `[-=]+`, not three-or-more dashes — the earlier pattern caught a thematic break and
missed both setext forms, in the class written to stop exactly this. A bullet like `- item` still
passes through, because the whole line after the optional indent has to be dashes. The prompt header
itself is the user's own text and is written out as given: it is deliberate markdown, at the top,
where its author can see what it did.

The header follows revdiff's model: a remark that asks a question gets answered rather than turned
into an edit.

### The published file

Publishing writes to a second destination besides the clipboard: one file a Claude Code skill can
read whenever it likes, with no review ever having been started. Since phase 10 the same file is
also where a waiting review's answer and a rejection land — see "The Shared Review Session" below for
how that side writes it — so this section describes the file's shape and the plain-publish write; the
review side reuses everything here rather than owning a second format. `review/PublishedRemarks.kt`
holds it: `publishedName(realPath)` names it, `PublishedHeader.render()`/`publishedHeaderOf()` build
and parse what sits above the prompt, and `writePublished(root, body, dir)` writes it. Before phase
10 the header was three fixed fields, built by a plain `publishedHeader(now, commit, count)`
function; phase 10 replaced that with the `PublishedHeader` data class described below, once the
header also had to carry which review it answers and whether it is a rejection.

**The name and the location.** `~/.claude-remarks/<first 16 hex of sha256 of the project's
identity>.md`. That is the same 16 characters `handshakeName` (`review/ReviewHandshake.kt`) computes
for the handshake file, with `.md` in place of `.json`. `projectHash` was pulled out of
`handshakeName` so both callers share one function rather than two copies of the same hash, and what
goes into it is always `projectIdentity` — the git top level, or the base path outside a repository.
See "What 'the project's identity' is" under the handshake section below. A skill running inside
the repository computes the same name with the one-line `shasum` it already runs for the handshake.
Permissions are `rw-------`, in a directory already `rwx------`, because the file holds remark text
and slices of real source.

**One file, overwritten, not a file per publish or per batch.** Publishing again replaces what was
there, whatever wrote it last — another publish, a review's answer, or a rejection. Publish three
remarks, write a fourth, publish again, and the file then holds the fourth batch alone. The first
three batches are not lost from the remarks' own point of view: the remarks are still in the store,
still shown in the tree as published, and Publish Selected can hand any of them over again. What is
lost is the ability to acknowledge that specific old batch by reading the file — but not by nonce:
`review/PublishedAck.kt`'s `PublishedBatchService` remembers the last sixteen nonces regardless of
what the file currently holds, so an agent that already has an old nonce cached (from a fetch, or
from watching the file earlier) can still acknowledge it and get `ok`, even though a later publish
has since overwritten the file's own copy of it. The alternative, keeping every publish as its own
timestamped file, would need the skill to list a directory, pick the newest by name or by mtime, and
something to delete the old ones. The old ones would also stay readable, so an agent could be pointed
at yesterday's file and act on it with full confidence. One truth wins here because the store in
`workspace.xml` is already the durable tier: see "The store stays the durable tier" below, which
makes the same argument for the review's own answer.

**The header, so a reader can tell how old the file is, which batch it is, and what it answers.**
`PublishedHeader` is eight fixed lines, every field always written and `none` when there is nothing
to say, because the header is read by line number, not by grep — a remark's own text could start a
line with `commit:` or `review:`, so matching by content would be unsafe. In order: the marker
`PUBLISHED_MARKER` (`<!-- claude-remarks: published -->`); `nonce:`, a fresh `UUID` minted on every
write, which is what an acknowledgement names to say exactly which batch it read; `published:`;
`commit:`, cut to eight characters, the same way the prompt heading and the tree already cut it, with
a missing commit saying `none` rather than leaving the field blank; `remarks:`, the count; `review:`,
the session id this batch answers, or `none` for a plain publish with no review waiting; `label:`,
that review's label, sanitized — every character below U+0020 becomes a space and the result is cut
to 120 characters, since the label arrives over HTTP from the skill and a stray newline in it would
split the header and move every line after it — or `none`; and `rejected:`, `yes` or `no`. Then a
blank line, then the same markdown the clipboard gets. The clipboard never gets this header. A paste
is never read later, but a file found on disk might be read hours afterward, or twice, and nothing on
this path confirms a read by itself, so the reader needs to be able to see how stale it might be, and
now which batch and which review it is looking at. `publishedHeaderOf` is strict: a missing prefix on
any line, or a `remarks:` value that is not an integer, returns null rather than guessing, and the
fetch and the published-read handler both turn that into a `failed` answer with a detail rather than
serving a header that might be lying.

**A failed file write still marks the remarks published.** The order inside the publish's UI
callback is clipboard, then file, then mark, then one balloon. If the clipboard write fails, nothing
is marked and nothing is written. Nothing was handed over, so nothing says it was. If the file write
fails after the clipboard already succeeded, the remarks are still marked `PUBLISHED`, because that
state means "handed to a channel that cannot confirm a read," and the clipboard handover really did
happen. Refusing to mark them would be a lie in the other direction. The balloon says the file was
not updated in the same sentence, through `publishMessage`, and says why: the exception's own
message for a failed write, and "the project root did not resolve" when the write was skipped
because the identity did not resolve. The whole exception goes to the platform log beside it. A
permissions failure, a full disk and a name the filesystem refuses all reach that one branch, and a
sentence carrying none of them leaves nothing to act on. This is also the entry in Known Issues
below, "a failed published-file write leaves the previous file in place": the file still on disk
after a failed write looks exactly like a current one to a skill reading it, and only the header's
`published:` timestamp gives it away.

**Never an empty published file.** Publishing with nothing pending keeps the early return that skips
the whole write. An agent reading an empty published file could not tell "nothing to say" from
"something went wrong," so the file is left exactly as the last successful publish wrote it, and its
header still tells the true story.

**A waiting review is answered, not left alone — reversed in phase 10.** Phase 9 left a waiting
review untouched by a publish, on the reasoning that the review was a different contract — a session
id and an acknowledgement that moves remarks to `READ` — and satisfying it from a publish would hand
the same remarks to two channels at once. Phase 10 folds the two channels into one instead of keeping
them apart: `waitingReviewForPublish(project)` reads whichever review is current, and if one is
current, `answerWaitingReview(project, session, ids)` stamps `WaitingReviewService.markSent` right
after the file write succeeds, in the same `finishOnUiThread` block that builds the header — before
the write, the header's `reviewSession` and `reviewLabel` fields are filled from that same review, so
the two never disagree about what this batch answers. There is now exactly one route a remark's
handover can be confirmed through, however it got published: the header names the review it answers
if there is one, and the same nonce that lets a plain publish be acknowledged is what lets a review's
answer be acknowledged too. `markSent` can return `false` if the review ended in the gap between the
check and the write — see "Three signals that the remarks arrived" below — and `answerWaitingReview`
turns that into the balloon's "the review ended first" sentence instead of silently claiming a
handover that did not land on a live review.

**How the skill reads it.** `docs/skill/claude-remarks-review/SKILL.md` has three modes that all
read this one file, sharing the repository root, the project hash and the "act on the markdown" step
so none of the three duplicates that shell: a one-shot read of whatever is published right now, an
opt-in listen mode that waits for the next batch, started only when asked for in words, and review
mode, written out step by step under `## Steps` — the same file as the other two once phase
10 merged the review's own answer into it. All three find the repository root, compute the hash,
build the path, and stop with a plain sentence if the file is missing or its first line is not the
marker. The one-shot read and listen mode both post to `published-read` once they have read a batch,
naming its nonce, which moves the remarks to `READ` — before phase 10 the published mode never posted
anything and could not confirm a read at all, which was the whole reason `PUBLISHED` stayed a
separate state from `READ` for a plain publish; phase 10 gave it a route to `READ` without changing
what the states mean. Review mode still answers through `POST /api/claude-remarks/ack` instead,
keyed to its session rather than to a nonce, since a review already has a stronger identity than any
one batch. Waiting, in review mode and in listen mode alike, is a launched background script,
`watch-remarks.sh` (see "Why a file, not a socket" and "The watcher script, and why it has to exit"
under "The Shared Review Session" below), not a foreground poll loop: a foreground `Bash` call is
capped at ten minutes, and the skill's declared deadline can be much longer than that.

## A Remark About No File

Phase 9's group three lets a remark be about the whole change instead of one file: a thought worth
writing down before it is forgotten, that does not belong on any single line. The one way to write
one is the toolbar button in the tool window, called Add General Remark. There is no `plugin.xml`
action, no Tools menu entry, and no keystroke for it, on purpose. The tool window is the one place a
person is looking at remarks rather than at code, which is where a thought about the whole change
gets written. A second entry point can be added later if the first one turns out to be missed.

**`RemarkEdits.kt`'s `addGeneralRemark(project, text, tag)`.** It stores a remark with a null path,
both lines and both columns at zero, no `textHash`, no context, and the commit stamp, then publishes
`REMARKS_CHANGED` like every other mutator in the file. It is the tenth mutation function, and the
count in the file's own KDoc and in `CLAUDE.md` rule 3 moved with it. `action/AddRemarkAction.kt`'s
`openGeneralRemarkInput` opens the popup for it, reusing `RemarkInputPanel` the same way the ordinary
entry points do, through a small shared `buildInputPopup` helper the two now call. It cannot use
`showInBestPositionFor(editor)`, since there is no editor to be near, so it shows the popup centred
over the tool window's tree instead.

**`render/PromptRenderer.kt`'s General section.** `RenderedRemark.path` stays a plain, non-null
`String`; `""` is what "about no file" means, the same expressiveness a nullable `path` would give
without touching every construction site and every existing test that builds one. `renderPrompt`
splits the general remarks out by `path.isEmpty()` and renders them first, under one `## General`
heading, before any file section. Each keeps its number, its tag, its level, its commit and its
text, and none of them gets a code block. That last point matters more than it looks: a general
remark has no `startLine`, no `textHash` and no context, which is exactly the shape the renderer
already used for an orphan, the remark whose code could not be found. Routed through the ordinary
path a general remark would read as broken instead of deliberate, so it is split out before that
branch is ever reached.

**`store/RemarkResolver.kt`'s `isAboutNoFile`.** `resolveOne` used to refuse any remark with no path
and mark it orphaned, which was the one wrong answer in the whole resolver: a general remark is not
a remark whose file disappeared. `isAboutNoFile(remark)` is `remark.path.isNullOrEmpty()`, checked
before that refusal, and a remark it is true for resolves as `Exact(0, 0)`, the same "no sub-line
range" pair a whole-line remark already carries. No new case was added to the `AnchorResult` sealed
interface for this. A fourth case would have touched every `when` that reads one, in the tree, the
payload collector and the gutter, to express something those readers already have to ask about on
their own. `isAboutNoFile` is the one question they ask instead, and it is public for exactly that
reason. The gutter needed no change at all: `RemarkGutter.placementsFor` already filters
`it.path == path` against a real document's relative path, which is never empty, so a general remark
was already skipped there before this task, and a test now pins that it stays skipped.

**`ui/RemarksTree.kt`'s General group.** `buildTreeRoot` partitions the sorted rows on
`path.isEmpty()` before the existing bucket logic ever runs, and puts every general remark under one
group first, keyed `GENERAL_KEY` ("general") and labelled "General". The key is a bare word: a file
key always starts with `file:` and a bucket key always starts with `bucket:`, so `"general"` cannot
collide with either, and `RemarksPanel`'s selection restore, which matches groups by key, keeps
working. A general remark's own bucket is ignored for this grouping, and that is a real cost, not an
oversight. Put a general remark in a bucket and the bucket does not gather it. The reason it is still
the right shape: a general remark is about the whole change, so the top of the tree is where it
should be read, and a tree with the same remark reachable from two places is worse than one that
ignores a field on it. `docs/ideas.md` already names this the layered-ordering question the tree
answered once before, for buckets above files.

**`store/RemarkHistory.kt`'s heading.** A general remark's archived heading reads `**(general)**`
and prints no `lines` part at all, since `positionLabel` has nothing to describe for a remark with no
line range. This reuses the same word the renderer's `## General` heading and the tree's
`GENERAL_KEY` group use for the same kind of remark.

## One Pass Over The Tree

Phase 9's group four is two changes, both in `ui/RemarksTree.kt` and `ui/RemarksToolWindowFactory.kt`
and nowhere else: a file row shows its file name first, and a remark, several remarks, or a whole
file or bucket group can be dragged onto a bucket row to move them there.

### The file row shows the file name first

A file group used to draw the whole path as its label. On a deep path the row cropped from the
right, and what got cut off first was the file name itself, the one piece of the row a person
actually needs to read. Now the row draws the file name in bold, with the directory after it in
grey. A cropped row now loses a half-visible directory, which is fine to lose. It no longer loses
the file name.

`GroupNode` gained a second field, `detail: String?`, next to the `key` and `label` it already had.
`addFileGroups` fills `label` with the file name (`path.substringAfterLast('/')`) and `detail` with
the shortened directory, through a new pure function, `shortDirectory(path)`. A file sitting in the
project root has no directory to show, so `shortDirectory` returns null for it, and the renderer
prints nothing after the name for that row. A deep directory is shortened to its last two segments
with a leading ellipsis, so `a/b/c/d/e` reads as `…/d/e`.

**The group's key does not change.** It is still the whole path, `"${keyPrefix}file:$path"`, exactly
as before. Only the label and the new detail field changed. This is what keeps
`RemarksPanel`'s selection restore working across a tree rebuild: the panel matches a group by its
key, not by its label, and a rebuild after this change still finds the same key it found before.

**The alternative rejected: a node per directory segment.** Turning
`src/main/kotlin/dev/sasha/clauderemarks/ui/Foo.kt` into one node per path segment reads well on
paper, but a chain of nodes with one child each is not useful to look at on its own. Making it
useful needs single-child chain compression, folding a run of one-child nodes back into a single
row, and that is real logic with its own tests, not a one-line change to what a row draws. It is
worth trying only if the file-name-first row, once seen in a real IDE, still reads badly on a
project with deep paths.

### Dragging a remark onto a bucket

A remark, several selected remarks, or a whole file or bucket group can be dragged onto a bucket row
to move them there, or onto the `(no bucket)` row to clear their bucket. The move itself is nothing
new: `setRemarkBucket`, one of the mutation functions in `store/RemarkEdits.kt`, already does it and
already publishes `REMARKS_CHANGED`, so the tree redraws itself once the drop lands. What phase 9
adds is the drag and drop machinery around that existing call, and one new pure function that
decides what a drop means.

**`bucketDropTarget(node)` is pure, and it answers what a drop on a given node means.** Given the
tree node under the pointer, it decides: a bucket group is a target for its own name; the
`(no bucket)` group is a target that clears the bucket; a file group or a remark row inside a bucket
targets that bucket; the General group, the tree root, and a file group with no bucket level above
it are not drop targets at all.

It returns a wrapper type, `BucketDrop(bucket: String?)`, rather than a bare nullable string. A
nullable string can only give one of two answers a null value: "this row is not a drop target," or
"the target is: clear the bucket." Those are two different answers, and a plain `String?` cannot
tell them apart. `bucketDropTarget` itself returns null for "not a target," and a `BucketDrop`
wrapping either a bucket name or a null bucket for "this is a target, and here is what it means."

**It walks up to the top-level ancestor and reads that node's key, rather than parsing the key of
the node under the pointer, because a bucket name containing a slash makes file keys
unparseable.** A file group's key is built by joining the bucket's own key in front of the file's
path, so a bucket named `a/b` produces a file key like `bucket:a/b/file:src/Foo.kt`. No string split
on that key can tell where the bucket name ends and the file path begins. Reading the key of the
node at the very top of the tree instead sidesteps the whole problem: that node's own key already
names the bucket in full, whatever characters the bucket name contains. `bucketDropTarget` also
reads that top node's key, never its label, which is what makes a bucket a person actually named
`(no bucket)` set that literal name rather than being read as a request to clear the bucket.

**The drag bean is a private type.** `ui/RemarksTreeDnd.kt` defines a private data class,
`DraggedRemarks(ids: List<String>)`, and both the drag's target checker and its drop handler refuse
anything that is not exactly that type. A drag started in the project view, the commit tool window,
or anywhere else in the IDE is refused rather than misread as a list of remark ids that happened to
arrive at the right component.

**Three small decisions, settled rather than left open.** Dropping on the `(no bucket)` row clears
the bucket, because `setRemarkBucket` already accepts null and clearing is its natural opposite.
Dragging a file group or a bucket group moves every remark under it, the same subtree walk Publish
Selected already uses on those groups. The drag image stays the platform default: showing a count of
remarks being dragged is feedback for a problem nobody has run into yet.

**The "New bucket…" drop target was considered and cut.** Buckets in this plugin are derived, not
declared: a bucket exists because some remark carries its name, and an empty one vanishes on the
next tree rebuild. A "New bucket…" row that a person could drop onto would have to be a permanent
fake row in the node building, sitting there for a gesture nobody has tried yet. `Move to Bucket…`
in the right-click menu already creates a bucket by typing its name, and that path is unchanged. Not
building the new-bucket drop target also removes an obligation `docs/ideas.md` names for it: a drop
target that creates something needs an on-screen tip explaining that, and the tip is not needed once
the part it would have explained is not there to explain.

**The wiring lives beside the decision, not in the panel.** `installDragToBucket` and the node lookup
under the pointer sit in `ui/RemarksTreeDnd.kt`, next to `ui/RemarksTree.kt`'s pure
`bucketDropTarget`. `RemarksToolWindowFactory.kt` already owns the tree, the banner, the toolbar, the
right-click menu, selection and collapse restore, navigation and the delete confirmations, and the
drag is a separate subject with its own platform dependency on `com.intellij.ide.dnd`. The panel
passes its own selection rule in as a lambda rather than letting the drag read the tree itself, so a
drag and Publish Selected can never disagree about what a selected group covers.

**What no test covers, and what is tested instead.** Nothing in the test suite performs a drag: a
press-and-move gesture, a real drop landing on a real row, and the tree redrawing after it are not
things `RemarksTreeTest` or `RemarksPanelTest` can drive, and they are an owed hand check instead.
What is tested is `bucketDropTarget` itself, the pure decision about what a drop on a given node
means, covering every kind of node the tree can hold, and that `RemarksPanel` still builds with the
drag-and-drop support installed, since the light test fixture registers a headless `DnDManager`
whose `registerSource`/`registerTarget` do nothing.

**A `Published` group above the buckets was designed for this same pass and deliberately left
unbuilt.** See `docs/ideas.md`, "Where published remarks live in the tree," for the condition that
would make it worth building.

## A Remark on the Rendered Preview

Phase 9's group five lets a remark be written from IntelliJ's rendered markdown preview, not only
from the source file. Select words in the preview, right-click, and pick Claude Remarks, and the
remark points at the exact characters in the `.md` file behind the selection, not at the whole line.
Section 9 of `docs/plans/completed/20260803-claude-remarks-phase9.md` holds every platform fact this design
rests on, with the file in the IntelliJ checkout each fact came from. This section is the shorter,
durable record: what a later session needs, without re-reading the checkout to find it again.

**The five pieces.** `src/main/resources/dev/sasha/clauderemarks/preview/claude-remarks-preview.js`
is the script injected into the preview page. It listens for `selectionchange` and posts one message
on every change. `preview/PreviewRemarkExtension.kt` is the `MarkdownBrowserPreviewExtension` that
receives it: a provider handed the preview panel, which takes the panel's pipe, subscribes to that
one message type, and serves the script's bytes from its own `ResourceProvider`.
`preview/PreviewSelection.kt` is the pure arithmetic, `parseSelectionMessage` and `narrowToSelection`,
that turns a browser message into a character range in the `.md` source.
`preview/PreviewSelectionService.kt` is the project service that keeps the one most recent range,
with the file url beside it. `action/AddPreviewRemarkAction.kt` is the entry point in the preview's
right-click menu, which reads only what the service already holds and never asks the page anything.

**The page pushes the selection. The IDE never asks for it.** A request-and-response design was
considered and rejected. An action cannot grey itself out while it waits for an answer that has not
arrived yet, so a request-based version would have had to stay always enabled or always guess. And
the code that opens the remark box would then have to run on whatever thread a pipe handler answers
on, instead of on the EDT, where every other entry point in this plugin already lives. Pushing avoids
both problems: by the time the right-click menu opens, the answer is already sitting in
`PreviewSelectionService`.

**The pipe handler does not run on the EDT.** This is the fact a future session is least likely to
guess, and the platform's own source never states it in words. `JBCefJSQuery.addHandler` wraps the
handler in a `CefMessageRouterHandlerAdapter` and calls it straight from `onQuery`. `onQuery` itself
is reached from native code, with no Java-side dispatch loop between the native call and the handler.
So `PreviewRemarkExtension.receive` may only parse the string it was handed. Reading a `Document`,
calling a project service, touching Swing: all of that has to hop to the EDT itself, with
`invokeLater`, which is exactly what `receive` does after the parse. Every other extension built on
this same pipe agrees: `CodeFenceCopyButtonBrowserExtension` and `CommandRunnerExtension` both wrap
their own work in `invokeLater` too, and nothing in the platform contradicts it.

**`md-src-pos` is written on every tag the markdown generator opens, not only on the ones worth
selecting.** `ParagraphGeneratingProvider.kt` wraps each source line of a paragraph in its own
`<span>` carrying this attribute, which is what usually lets a selection narrow down to one line. But
the same attribute sits on a Mermaid fence too, and a Mermaid diagram is drawn as one block, with no
span per line inside it the way a paragraph has. So the nearest ancestor carrying a position, walked
up from a selection made inside a drawn diagram, is the whole fence: every line of the diagram's own
source, not one node's label. `narrowToSelection` then runs its one `indexOf` search for the
highlighted text inside that whole-fence source. If the diagram's own source really contains the
selected label, which it usually does, since a Mermaid label is written as plain text, the search
finds it and the remark lands on the label exactly. If it does not, the remark falls back to the
whole fence, every line of the diagram at once, not to one line the way ordinary prose falls back.
That difference is why this gets its own entry in Known Issues below, separate from the ordinary
crossing-markup case.

**The offsets are into the same `Document` the editor half of the split shows, not into the file on
disk.** `HtmlSourceTextPreprocessor.kt` calls `generateMarkdownHtml(file, document.text, project)`,
and nothing rewrites the text before it is parsed. So an offset the page reports describes the
unsaved buffer. `PreviewRemarkExtension.receive` reads the `Document` for exactly this reason, not
the file's bytes.

**A selection is narrowed by searching the source, not by mapping the parse tree.** A browser
selection names two DOM containers, not one range. The design finds the nearest ancestor of each end
that carries `md-src-pos`, takes the whole span between them as a coarse range, then searches inside
that slice for the text the person actually highlighted. The alternative, walking the markdown
parser's own tree and mapping rendered offsets back to source offsets, would buy precision on
emphasised text inside a paragraph. It would also cost a parser-shaped subsystem with its own tests,
for a case where a whole-line remark is already an honest answer. `preview/PreviewSelection.kt`'s own
KDoc lays out the same trade.

**The markdown plugin is an optional dependency, so the plugin still loads without it.** `plugin.xml`
declares `org.intellij.plugins.markdown` with `optional="true"` and its own config file,
`claude-remarks-markdown.xml`. Every class and action group id this group needs comes from the
markdown plugin. Naming one of them directly in `plugin.xml` would stop the whole plugin from loading
the moment the markdown plugin is disabled, with no dialog and no visible error: the only symptom
would be the Claude Remarks tool window simply not being there. Keeping every markdown reference
inside the optional config file is what prevents that.

`build.gradle.kts` also has to subtract `EXPERIMENTAL_API_USAGES`, not only `INTERNAL_API_USAGES` as
before, from what `verifyPlugin` treats as a build failure. `MarkdownHtmlPanel.getBrowserPipe()`,
`.getProject()` and `.getVirtualFile()` all carry `@ApiStatus.Experimental`, and the pipe is the only
published route to what a person selected in the preview: the alternative, the panel's
`PREVIEW_BROWSER` user-data key, sits on a class marked `@ApiStatus.Internal`, which is worse. The
comment beside that line in `build.gradle.kts` explains the trade and what subtracting the whole
category costs: a future experimental-API use in this plugin would also go unreported.

**JCEF is the renderer this design needs, and the Compose one gives nothing to select.**
`MarkdownHtmlPanel.getBrowserPipe()` defaults to null, and only the JCEF preview panel returns one.
The Compose renderer is off by default in this IDE version, but if it were ever turned on, the
extension in this group would simply never be created for it, and the right-click action would find
nothing stored. That is a documented limit, not a crash: see Known Issues below.

### A stored selection must not outlive what it describes

Two ways a stored selection can outlive the thing it describes, and what closes each.

**The preview is closed.** Closing a page fires no `selectionchange`, so the script never gets to say
the selection is gone, and the stored entry would sit there until some other preview replaced it. The
right-click action treats a data context that names no file as "act on the stored url", which is a
deliberate choice — the stored url is then the only thing naming a file — so a selection left over
from a closed preview could answer a right click in a different preview and write the remark against
the wrong file, with no warning. `PreviewRemarkExtension.dispose` now calls
`PreviewSelectionService.forgetSelectionIn(file.url)`. The url check is what makes it safe to call
during disposal: a plain `forget` would take a live selection a second, still open preview had just
stored, which is exactly the case the url comparison exists to protect.

**The source is edited between the page reporting a selection and the person right-clicking.** The
offsets are plain numbers. An edit that leaves the file the same length or longer moves the words
under them while they still fit, and the length check the action makes would let that through. So
`StoredSelection` carries a third field, `sourceStamp`: the `Document.getModificationStamp()` read on
the EDT in the same pass that narrowed the range against that text. `previewStampProblem` in
`action/AddPreviewRemarkAction.kt` compares it again before anything is written and refuses with the
sentence the action already had for a file that moved on. It is pure, like `previewRemarkProblem`
beside it, and for the same reason: it is the part of this entry point a test can reach.

The stamp is checked in `actionPerformed`, never in `update`. Reading a `Document` needs a read
action, and `update` declares `ActionUpdateThread.BGT`; the menu item stays enabled and refuses with
a dialog, which is what every other refusal on this action already does.

## The Shared Review Session

Phase 6 lets a Claude Code skill start a review inside a running IDE, wait while a person answers
it in the tool window, then read back what they wrote — with no server the plugin manages beyond
the one the platform already runs, and no state that survives an IDE restart. The pieces live in
`review/`: `ReviewHandshake.kt`, `AtomicWrite.kt`, `WaitingReview.kt`, `ReviewRestService.kt`,
`SendReview.kt`, `OpenReviewFiles.kt`, and, since phase 9 and phase 10 respectively,
`PublishedRemarks.kt` and `PublishedAck.kt` — the file both a plain publish and a review's answer now
write, and its second acknowledgement route. The skill side is
`docs/skill/claude-remarks-review/SKILL.md`, outside the plugin proper.

### Why a file, not a socket

The skill polls a file for existence — since phase 10, the one predictable published file at
`handshakeDir().resolve(publishedName(identity))`, watched for a nonce it has not seen before, rather
than a fresh path minted per review. The IDE writes it once, atomically, when the person presses
Publish, and never deletes it. A socket would deliver the remarks the instant the button is pressed,
but both sides would then have to handle the other going away mid-review — an IDE that quits, a skill
process that was interrupted. A file needs none of that: failure looks like a file that never
appeared, or a nonce that never changed, which is legible on its own. The cost is a poll interval
instead of an instant wake-up, and that is cheap against a task that takes minutes of reading.

### Why the atomic rename means the reader never has to ask "is this done yet"

`AtomicWrite.kt`'s `atomicWriteString` writes the whole content to a temp file in the *same
directory* as the target, then renames the temp file onto the target with `ATOMIC_MOVE`. A rename
inside one filesystem is atomic on POSIX, so a reader watching the target path sees either nothing
or the complete content, never a half-written file. That is the entire reason the skill's wait loop
in `SKILL.md` can be "while the file does not exist, sleep" — there is no partial state it needs to
rule out separately. `ReviewHandshake.kt`'s own write goes through the same function, for the same
reason, on the file the skill reads to find the IDE in the first place.

### The watcher script, and why it has to exit

`docs/skill/claude-remarks-review/watch-remarks.sh` is the skill side's whole wait. It is not part of
the plugin, but the plugin's design depends on it, so it is written down here: it is what makes the
deadline the skill declares to the endpoint a real number rather than a claim.

**The cap that forced it.** A foreground `Bash` call in a Claude Code session is capped at ten
minutes. The skill's default deadline is 1800 seconds, and listen mode's is twelve hours, so a poll
loop written inline in a foreground call gets cut off long before the deadline it told the IDE about.
That is what the skill did before phase 10: its loop counted to 1800 seconds inside one foreground
call, so the number it sent the endpoint as `deadlineSeconds` was true on the IDE side and a fiction
on its own.

**A background command has no such cap, and it re-invokes the session when it exits.** That one
sentence decides the whole shape. The session is woken by the *exit*, not by anything the command
prints while it runs. So a watcher that loops forever would never notify anybody: the session would
sit waiting for a signal that cannot arrive, and the deadline would pass unnoticed. Every path out of
the script is therefore an explicit exit, and none of them loops back — a new batch exits 0 with the
whole file on stdout, the deadline exits 1 with one sentence, anything wrong exits 2 with a reason,
and a watcher killed by the one that replaced it exits 143 (128 plus `SIGTERM`), which a session must
read as "another watcher took over", never as a batch or a deadline. The skill reads the exit code
and the output once, in a fresh foreground call, and decides what to do from those two things alone.
Nothing is left behind for it to go and read.

**Two modes, one per transport, matching the two ways a skill can see the published file.** `--file
<path>` polls the published file directly, every 2 seconds by default; this is the same-machine case,
and it is also the only mode the two published-file modes can use. `--fetch <base_url>`, with
`--session` and `--project` beside it, posts to the fetch action instead, every 5 seconds by
default, for a review whose IDE is on the other end of a tunnel — 5 rather than 2 because the
built-in server allows 30 requests a minute from one address and every tunnelled request shares
`127.0.0.1`.

**Two ways of deciding a batch is new, and only one of them is used at a time.** `--seen <nonce>` is
the batch already known: report the first batch whose nonce differs from it. `--require-review
<session>` is the review's own form, file mode only: report the first batch whose `review:` header
field names that session, whatever its nonce is. When `--require-review` is given, `--seen` is not
consulted at all. The reason is a gap the review flow cannot close: the skill reads the file's
current nonce in the same shell that posts to `start`, so a publish landing between those two lines
would already be recorded as seen, and a watcher filtering on the nonce would then wait out its whole
deadline for an answer that had already arrived. The session id has no such gap, because it is
invented moments before the `start` it is sent with. `--require-review` is refused outright with
`--fetch` rather than ignored: the fetch action already answers `ready` only for the session named in
the request, so there is nothing left to filter on the skill's side.

**Why it polls a copy of the file rather than the file.** In file mode the watcher copies the
published file with `cp` and reads the header and the body out of the copy. `cp` opens an inode, and
the plugin's atomic rename replaces the directory entry without truncating the inode behind it, so
the copy is always one whole batch — the old one or the new one, never a mix. Reading the header in
one call and the body in another, straight off the target path, could straddle a rename. The copy is
skipped entirely when the file's modification time and size are both unchanged since the last poll,
which is most polls: at the 2-second default over listen mode's twelve hours the loop runs about
21,600 times.

**One watcher per project, through a pid file.** On start the watcher writes two lines to
`~/.claude-remarks/<16 hex characters>.watch` — its own pid, then the path it was launched on
(`--file`'s file, or `--fetch`'s project) — creating that directory `rwx------` first if the plugin
has never run here. The 16 characters are the same `projectHash` computes for the handshake and
published files, so the pid file sits beside them: in file mode they are taken straight off the
published file's own name when that name really is 16 hex characters, and hashed from the given path
when it is not. If a pid is already there and still belongs to a live `watch-remarks.sh` process
watching the same path, the new watcher kills it and waits for it to actually exit before taking
over. Without that, two sessions asked to listen on one repository would both wake on the same batch
and both acknowledge it, and the second would be told `already-read` for a batch it had every right
to think was its own. Both the command line and the second line's path are checked before anything is
killed, since a recycled pid can belong to another project's watcher, which is still a
`watch-remarks.sh`. On exit the watcher removes the pid file only if the first line is still its own
pid, so a watcher that has already been replaced cannot delete the live one's file — and it traps
`INT`, `TERM` and `HUP` as well as `EXIT`, because a shell killed by a signal never runs an `EXIT`
trap, and a signal is exactly how a takeover ends the previous watcher.

**The token for `--fetch` never appears in an argument.** It is read from `CLAUDE_REMARKS_TOKEN` in
the environment, and it reaches `curl` through a config file on stdin rather than through a `-H`
argument, so it is in neither the watcher's own argv nor `curl`'s. Every process on the machine can
read an argument out of `ps`, and the token is the only gate on the endpoint — see "The security
rule" below for why that gate is the whole of it. The request body carries no secret, so it stays on
the command line, which is what leaves stdin free for the config.

### Why the published file's path is predictable, and that is safe here

Before phase 10, `WaitingReviewState.outputPath` was a fresh
`Files.createTempDirectory("claude-remarks-review-")` per accepted review, with the handoff file
named inside it — `<that directory>/remarks.md` — by a `handoffFile(outputPath)` function the
endpoint's response and `SendReview.kt`'s write both went through, so the two sides could never drift
into naming the file differently. The reason the path was unpredictable in the first place,
`docs/ideas.md`'s own argument at the time: the system temp directory is shared and world-writable,
so a fixed, predictable name there can be pre-created as a symlink by another local user, and the
plugin's write then lands wherever that symlink points — the same reason
`render/PromptPayload.kt`'s own overflow file stays unpredictable today.

Phase 10 makes the review's answer land at a predictable path after all —
`handshakeDir().resolve(publishedName(identity))`, the same computed path a plain publish and a
rejection also write to — and that is a different case from the one the symlink argument was made
against. The published file has lived at that exact predictable path since phase 9, for the
plain-publish case, with no incident: it sits inside `~/.claude-remarks/`, a directory the plugin
creates `rwx------` on first use, not inside the shared, world-writable system temp directory the
symlink attack needs. A symlink placed by another local user inside a directory only this account can
read, write or list is not reachable by that user in the first place. Merging the review's own answer
onto the same path adds nothing new to attack: it is the identical directory, the identical
permission model, and the identical file. What disappears is the machinery that used to buy safety a
different way — a fresh temp directory per review, and `outputPath` as a supplier so that directory
was only ever created on the accepting branch of the pure decision function (see "One waiting review
per project" below). `startOrConflict` no longer takes an `outputPath` parameter at all,
`WaitingReviewState` no longer carries one, and `start` does no filesystem work of its own; a fetch,
an `ack`, or a `published-read` all resolve the one path themselves instead of being handed one back
in a response. The cost phase 6 accepted in exchange — a skill that loses the `start` response cannot
guess the path and has to re-run `start`, which the same-session reuse branch in `startOrConflict`
turns into a no-op — goes away with it: there is no path left to lose, only a nonce, and a lost nonce
just means the next read of the file finds the same batch again with the same nonce in it.

### The security rule: three independent conditions, and why the platform's own check is not enough

`ReviewRestService.isHostTrusted` (`requestIsAllowed` is the pure form, tested on its own) accepts a
request only if it is a POST, carries no `Origin` and no `Referer` header, and carries the right
secret in `X-Claude-Remarks-Token`. All three are needed, and each closes a different hole:

- **POST only.** `isMethodSupported` overrides the platform's GET-only default. A page can only
  issue a cross-origin `<img>` GET with no `Origin` and a suppressed `Referer`; refusing every method
  but POST removes that whole class of request. A form submit or `fetch` can send a cross-origin
  POST, but both always attach `Origin`, which the next rule rejects.
- **Refusing `Origin` and `Referer` outright**, rather than only checking they name a local host.
  A command-line client never sends either header. A browser almost always sends at least one. This
  inverts the platform's own default, which is to trust a request that carries neither.
- **This is not redundant with the platform's own local-origin check, and the reason is specific to
  an IDE.** The built-in web server serves files out of every open project at `127.0.0.1:63342`.
  A malicious `.html` file sitting in a repository the person opens is therefore served from a
  *local* origin, so the platform's own `isLocalOrigin()` check passes it straight through to
  `process`. Only the "refuse any `Origin`" rule stops that request. Deleting this rule as
  duplicate of the platform's own check would reopen exactly that hole.
- **The token**, minted once per IDE run (`ReviewToken.value`) and delivered through the handshake
  file with owner-only permissions. The two rules above stop a web page; they do nothing against
  another local process. A process that cannot read the handshake file cannot learn the token, and
  cannot drive the endpoint.

`isHostTrusted` does not call `super`. The default implementation would show a modal Yes/No dialog
for the null-host case a command-line client always produces, and it would pop on every request
because a null host is never cached — worse than no feature at all.

### The handshake: found by repository path, never by scanning ports

`ReviewHandshakeService.start()` writes one JSON file per open project under `~/.claude-remarks/`,
named `handshakeName(realPath)` — the first 16 hex characters of `sha256(the project's identity)`. A
skill computes the same name with one line of shell (`shasum -a 256`) from
`git rev-parse --show-toplevel`. The alternative — the skill scanning ports
upward and asking each one whether it has this project open — was rejected because every one of
those probe requests would need a token the skill does not have yet, so token delivery would still
need solving on top. One file write answers port discovery, token delivery, and project matching
together.

**What "the project's identity" is, and why it is not the base path.** `projectIdentity`
(`review/ReviewHandshake.kt`) answers it, and it is the only function allowed to: the git top level
when the project sits inside a git repository, and the project base path when it does not, real path
in both cases. It reuses `gitTopLevel` in `store/GitHead.kt`, which is the same walk up the tree
`headCommit` already made to find `.git`, so there is one walk rather than two that can drift.

Three places hash or compare a root, and each of them used to read `project.basePath` on its own:
the handshake file's name, the published file's name (`review/PublishedRemarks.kt`) and the
endpoint's project matching (`matchProject` in `review/ReviewRestService.kt`). The skill has always
sent and hashed `git rev-parse --show-toplevel`. Those are the same string only for a project opened
exactly at the repository root. Open the project on a module below it and both modes broke, with no
way back: the review mode found no handshake file, so it never learned the port or the token and
told the person no IDE had the repository open, and the published mode wrote one name while the
skill looked for another and told the person to press Publish, which they just had. Routing all
three through one function is what makes those three names impossible to disagree again.

The real path matters for the same reason it always did: `git rev-parse --show-toplevel` prints the
physical path, so a symlinked checkout only matches if the plugin side resolves symlinks too.

**The port is read only after `BuiltInServerManager.getInstance().waitForStart().port`, never
`.port` alone.** `BuiltInServerManagerImpl.port` falls back to the 63342 default until the real bind
finishes, and the bind runs asynchronously after the registry loads. A `ProjectActivity` at project
open can easily win that race and write a port nothing listens on. `waitForStart()` joins the bind
job first; it is safe to block on because a `ProjectActivity` coroutine is never the EDT.

### One waiting review per project, and two decisions that do not re-derive from reading the code casually

`WaitingReviewService.state` is `@Volatile`, guarded by `@Synchronized` on `start` and `clear`, not
an `AtomicReference`. **This is the least re-derivable fact in the whole design, so it is written
down properly here.** Before phase 10, the mutation `start` performed was a read, a decision, a
directory creation and a write — too much for a compare-and-set lambda, because
`AtomicReference.updateAndGet` re-runs its lambda on contention, and that lambda created a temp
directory; a retried compare-and-set would have created two directories, one of them orphaned. Since
phase 10, `start` no longer touches the filesystem at all — see "Why the published file's path is
predictable" above — so that specific hazard is gone, but the read-decide-write sequence itself is
still one unit that must not interleave with a concurrent `clear`, so `@Synchronized` stays, on the
same reasoning `RemarkStore` already uses for its own mutators: it costs nothing at this call rate,
and a lock nobody needs yet is cheaper to keep than a race nobody meant to allow.

`WaitingReviewService.current()` is deliberately left **unsynchronized**, even though `state` is the
same field `start` holds the lock across. The toolbar's `update()` calls `current()` on the EDT. A
stale read from `current()` is harmless — the toolbar redraws again on the next `REMARKS_CHANGED`
regardless — so this is a deliberate, accepted bend of "guard every mutable field together," not an
oversight. It predates phase 10's removal of the filesystem work from `start`, and stays exactly as
it was: an unsynchronized read costs nothing extra to keep, and revisiting it is not something phase
10 had a reason to do.

### The endpoint stays off the VFS and Swing, and the file opening lives in its own file

`ReviewRestService.execute` runs on a netty IO thread, which holds no IntelliJ lock and is not the
EDT. It sets a field in a service and makes one plain `java.nio` filesystem call
(`Path.toRealPath()`), and returns. Rule 5 in `CLAUDE.md`'s "Rules that must not break" is the guard
that keeps this true after every future change; see that file for the exact grep. Opening the files
a review names — the one thing task 9 needs that does reach the VFS and the editor — lives in its
own file, `OpenReviewFiles.kt`, and calls `invokeLater`, never `invokeAndWait`: the HTTP response
must not wait for editors to appear on screen.

`execute`'s own KDoc does not name any of the five forbidden symbols. That is deliberate, not an
oversight to fix: the grep rule 5 runs is line-based text matching and cannot tell a comment from
code, so writing "this must never call invokeAndWait" as an explanatory comment would itself trip
the guard it is explaining. If that comment is ever added back, the grep starts failing on a file
that has not actually broken the rule, and somebody will "fix" it by weakening the pattern instead of
removing the comment.

### `projectForPath` is generic on purpose

`projectForPath<T>(wanted, open: List<Pair<Path, T>>)` takes the project's normalized real path
and returns whichever second element in the list matches. It is generic so the same function serves
two different tests: the pure `ReviewRequestTest` passes `(Path, String)` pairs to check the matching
logic with no platform involved, while `execute` passes `(Path, Project)` pairs and gets back the
actual `Project` it needs to reach `WaitingReviewService`. Two call sites needing two different
second elements is exactly the case a type parameter is for, rather than writing a name-lookup
function and a second, nearly identical scan.

### The store stays the durable tier — no second write on handover

Nothing in phase 6 writes to `RemarkHistory.kt`'s archive when a review is answered. Moving the
remarks to `READ` (`markRemarksRead`, once an agent acknowledges reading them — over either of the
two routes described under "The three states, and why published is not read" above, since phase 10)
is the only state change: remarks stay in the active list, drawn gray, exactly as after a publish,
and are only archived later when Clear Handed Over or Clear All runs. `docs/ideas.md`'s notes on
revdiff recommended a second durable copy of the payload alongside the ephemeral handoff file,
matching revdiff's own two-tier design. That was declined: revdiff needs the second tier because its
handoff file is deleted by the calling script's `trap` the moment its own process is about to exit.
Neither is true here — the plugin never deletes the published file. Before phase 10 it also never
deleted the review's own separate handoff file or the temp directory holding it, so every review left
one `$TMPDIR/claude-remarks-review-*/remarks.md` behind for the operating system to clean up (0600 on
the file, 0700 on the directory, so the leftovers were readable only by the person who ran the IDE).
Since phase 10 there is no separate handoff file or directory left to leak in the first place: a
review's answer overwrites the same one predictable file a plain publish writes,
`~/.claude-remarks/<hash>.md`, `rw-------` in a directory `rwx------`, so the question of leftovers
in the system temp directory does not arise any more — and the store already keeps every sent remark
until somebody clears it. Writing a second copy would also double-count against the history file,
which archives on *clear*: a remark handed over and later cleared would then appear in the history
twice.

### Three signals that the remarks arrived

Phase 7 closes the gap between "the IDE wrote a file" and "the agent read it." Before this phase the
IDE knew only that a write succeeded; it never learned whether the skill on the other end actually
saw the remarks, gave up, or was killed outright. `WaitingReviewState` now carries a `phase`
(`ReviewPhase.Waiting` or `ReviewPhase.Sent(ids)`) and a `deadlineAt`, and `WaitingReviewService`
gained `markSent`, `acknowledge` and `expireIfStale` to move between them.

**Rejecting writes into the published file, then clears — it does not just close the banner.** Before
phase 10, `rejectWaitingReview` (`review/SendReview.kt`) wrote `REJECTION_BODY` through
`atomicWriteString` onto the review's own handoff path, with a first line,
`<!-- claude-remarks: rejected -->`, that both the plugin's test and `SKILL.md` spelled out as a
literal — an HTML comment, so it read as invisible prose to a model and as a first-line match in the
skill's shell loop, checked with `head -1`, never `grep`, because a remark's own text could start a
line with the same marker. Since phase 10 there is no separate handoff path and no `REJECTED_MARKER`:
`rejectWaitingReview` instead builds a `PublishedHeader` with `rejected = true`, `remarks = 0`, a
fresh nonce, and the review's own session and label, and writes it plus `REJECTION_BODY` — which lost
its own marker line, since the header's `rejected:` field carries that now — through the same
`writePublished` a plain publish uses, onto the one predictable published-file path. It then calls
`WaitingReviewService.clear()`, records the rejection as a published batch with an empty id list
through `PublishedBatchService`, so "every write to the file records a batch" holds without
exception, and the link is still called Reject rather than Cancel, for the same reason phase 7 gave
it that name: "Cancel" reads as "close this banner", which was exactly the wrong behaviour that
produced the original defect. The skill reads the rejection the same way it reads any other batch,
by the header's `rejected:` field, rather than by a first-line marker unique to this one case.

**Reject in the `Sent` phase writes nothing.** Once a publish has answered the review, the published
file already holds the rendered remarks, and the agent may already be reading them. Overwriting it
with a rejection would destroy remarks that were never actually delivered, silently, and take that
batch away from any session that has not read it yet. So `rejectWaitingReview` checks the phase
first: in `Sent`, it only clears the review and tells the person the remarks were already published —
there is nothing left to reject.

**A publish no longer clears the review, and nothing is marked read until the agent says it read the
file.** This is the phase 7 mechanism phase 10 kept and moved onto the publish pipeline. Before phase
7, the old send action called what was then `markRemarksSent` and then `WaitingReviewService.clear()`
in the same breath as the write, so by the time the skill finished reading the file there was no
state left to record a read acknowledgement on, and "sent" meant only "written," never "delivered."
Since phase 7, and unchanged in shape by phase 10, a successful publish that answers a waiting review
calls `WaitingReviewService.markSent(session, ids)` through `answerWaitingReview` — which replaced the
old `sendToWaitingReview` in phase 10, called from inside the publish pipeline rather than from a
separate action — moving the phase to `Sent(ids)` and keeping the review current; the remarks
themselves move only to `PUBLISHED`, the same as any other publish, not straight to `READ`. Only a
`read` acknowledgement, `finishReview` in `review/SendReview.kt`, reached through the `ack` endpoint
action, calls `markRemarksRead(project, ids)`, moving them to `READ`. This is also why no mutation
function marks a remark pending again: nothing is ever marked handed over early, so there is nothing
to undo when a review is abandoned or rejected after a publish.

**One gap in that handover stays open, and the balloon is what makes it honest.** The publish checks
whether a review is still live (`waitingReviewForPublish`) before it writes the file, then writes,
then calls `answerWaitingReview` to stamp the phase. Between the check and the stamp the deadline task
can still end the review — `markSent` returns `false` when it finds no review left to stamp, and
`answerWaitingReview` turns that into the sentence "the review ended first" rather than the usual
"Waiting for it to read them," added to the balloon `publishMessage` already builds. The file still
exists and still holds the remarks, so the skill can still read them; its `ack read` is then answered
`no-review` and the review's phase never reaches `Sent`, which is the direction where nothing is
lost. Closing the gap for real would mean holding a lock across the file write, and that trade is
worse than the message.

**The deadline is declared by the skill, not configured in the plugin, and it is clamped at the
endpoint.** The skill already had the number as a literal in its own wait loop; a plugin setting
would be a second source of truth for the same value, and the two drifting apart is bad in both
directions — a shorter plugin deadline calls a live agent dead, a longer one leaves the banner lying
for exactly the window this phase exists to close. So the `start` request carries `deadlineSeconds`,
and `clampDeadlineSeconds` in `ReviewRestService.kt` bounds whatever arrives into 60 seconds through
24 hours (absent means 1800). The clamp lives at the endpoint, where untrusted input arrives, not
inside the service — which also lets a test hand the service a deadline of zero and get an instantly
stale review, the only practical way to test staleness without waiting a minute.

**The deadline is enforced two ways, and each covers a hole the other leaves alone.**
`WaitingReviewState.isStale(now)` is a pure comparison of two longs, used inside `current()`:
`state?.takeIf { !it.isStale() }`. That is what stops a stale review from ever being sent to or
enabling a button, no matter what the scheduler did — but a predicate alone leaves a stale banner on
screen until something else happens to trigger a repaint, since the banner only redraws from
`refresh()`. A `ScheduledFuture`, one `schedule` per accepted review (cancelled on `clear` and on
`dispose`, never a repeating poll), is what makes the *screen* catch up: it fires at the deadline and
calls `expireStaleReview(project)`, which is `expireIfStale()` plus the same balloon an abandoned
review gets. The task alone would be a promise about timing that a laptop asleep past the deadline
breaks; together the two cost about fifteen lines and cover both failure shapes. `current()` staying
unsynchronized while `start`/`clear` hold a lock is the same deliberate bend recorded above under "One
waiting review per project" — masking a stale review is one more comparison of two longs on that same
unsynchronized read, not a lock, not IO, and does not disturb that decision.

**The branch order in `startOrConflict` matters: same session before staleness.** The branches, in
order: an absent review accepts; the same session id gets its own state back — the same output path,
with the deadline moved forward from the retry's own instant — an honest retry, however late; only then
does a stale review get replaced by a different session's request; anything else conflicts. The
deadline has to move: handing back a deadline already in the past would answer `waiting` for a review
the scheduled expiry then kills in the same millisecond, so both sides would be lying at once. That
retry is also marked `fresh = false`, which is how the endpoint knows not to open a second diff window
over changes the person is already reading. Checking staleness before the same-session branch would mean a slow retry of
the review that is legitimately still running could get bumped by its own lateness. Checking it after
means a killed session's stale state does not block a next, different review from starting — the
person is not stuck pressing Cancel/Reject on a dead banner before they can start again.

**The agent's read is reported, never detected.** There is no portable signal on the plugin side for
"this file was read" — anything built on access times or file locks would be wrong on some
filesystem. The skill says so itself, through the `ack` action, and the IDE takes its word for it.

**The endpoint now dispatches on a sub-path, and an unrecognized one refuses rather than starting a
review.** `execute` splits the request path the same way the platform's own `UploadLogsService`
does — `urlDecoder.path().split(getServiceName()).last().trimStart('/')` — so every action
reaches the same handler under one `isHostTrusted` check and one rate limit. Before this phase `execute`
never looked at the path at all, so any sub-path — including a typo — silently started a review.
Phase 7 recognized two actions, `start` and `ack`. There are four today: phase 8 added `fetch` and
phase 10 added `published-read`. Anything else answers `bad-request` and starts nothing.

### Opening the diff the skill asked for

Before phase 7 a review request's `files` list was opened as one plain editor per file — the skill's
own comment called this "the cheap version of the diff." `OpenReviewFiles.kt` now decides, per file,
whether to open a real diff instead.

**The IDE decides diff-or-editor per file; the skill is never asked.** Whether a file has a local
change is a fact the IDE already holds and the skill would only be guessing at, so there is no new
request field and no mode flag. `ChangeListManager.getInstance(project).getChange(file)` answers
`null` for a file with no local change — that file opens as a plain editor, exactly as before, which
is also the right answer for a file the person should read but has not touched. A non-null `Change`
is collected instead of opened immediately.

**One diff window holds every changed file.** After the loop, `ShowDiffAction.showDiffForChange(project,
changes)` opens a single window over every collected `Change`, with next-file and previous-file
navigation built into it. A window per file would put the person back in the tab-shuffling this
feature exists to remove. Everything still runs inside the `invokeLater` `OpenReviewFiles.kt` already
had, so the HTTP response never waits for an editor or a diff window to appear on screen.

**This is why the plugin now declares a second `plugin.xml` dependency, `com.intellij.modules.vcs`,
and why `build.gradle.kts` needed a line for it.** `ShowDiffAction` lives in
`lib/modules/intellij.platform.vcs.impl.jar`, not in `app.jar` — confirmed by `javap` against the
2025.2 jars — while `ChangeListManager` and `Change` are both in `app.jar` and would have resolved
either way. Whether that module jar was already on the compile classpath was settled by compiling,
not by reading: the bare import did not resolve, so `bundledModule("intellij.platform.vcs.impl")`
was added to the `intellijPlatform` dependencies block in `build.gradle.kts`, the only entry there.
The dependency is a hard `<depends>`, not an optional one — every JetBrains IDE ships VCS, so the
optional form would need a second descriptor file and a code path that could never be tested; the
cost of being wrong this way is the plugin refusing to load, loud rather than half-working.

**A review of committed revisions degrades to plain editors, silently.** `ChangeListManager` only
knows about uncommitted work, so a review of `main..HEAD` gets `null` back for every file and every
one of them opens as a plain editor — indistinguishable, from the IDE's side, from a file with no
local change at all. Building `Change` objects out of two committed revisions needs the Git plugin
rather than the platform's VCS API, and is real work left for later. Local changes were the case
worth building first: that is when the work is unfinished, which is when a remark is worth writing.

**A remark on the revision side of a diff is refused, not mapped.** Opening a diff by default makes
this pane common rather than rare, so it had to be answered as part of this work rather than after
it. `remarkTargetProblem` (`store/RemarkTarget.kt`) now refuses a remark whose only resolving
candidate is the revision's highlight file, with a sentence naming the working copy as the other
side, one click away. Before this phase such a remark was stored, sometimes landing correctly through
the content hashing in `anchor/` when the region happened to be unchanged between the two revisions —
which is exactly the case where the remark mattered least — and orphaning with no warning when the
region had actually changed, which is the case the review is usually about. Mapping the line through
the diff's own line mapping onto the working copy is real work and stays a later phase; refusing costs
one branch and a sentence the person can act on immediately.

### Reaching an agent on another machine

Phase 8 lets the skill run on a machine other than the one the IDE is on, connected through an SSH
tunnel the person sets up by hand. Three things had to change for that. Nothing else did.

**The transport fact.** An HTTP response body crosses a tunnel. A filesystem path does not. Phase 6
handed back a path because both sides shared one filesystem. Two machines do not share a filesystem,
so the handover has to put the bytes inside the response instead. This is the whole reason a fetch
action exists. `POST /api/claude-remarks/fetch` reads the file the waiting review answers into —
since phase 10, the same one predictable published file every other write goes through — and returns
its content, in the same JSON body shape the other actions already use.

**Why the security model needs no change.** The built-in server only binds `127.0.0.1`
(`platform/built-in-server/src/org/jetbrains/io/BuiltInServer.kt`, the `bind` call). So the only way
into it from another machine is a tunnel. `isHostTrusted` in `ReviewRestService.kt` does not call
`super`. That skips the platform's own Host-header check completely: `RestService.process` calls
only this override, and nothing above it, not `BuiltInServer`, not `PortUnificationServerHandler`,
checks the Host header again. So the platform's local-host requirement never runs at all, and it is
not what protects this endpoint. The only gate is `requestIsAllowed`: the token, plus the absence of
`Origin` and `Referer`. That is why the token matters here. On the agent machine, the near end of the
tunnel is a loopback port that every process on that machine can reach. Without the token, any
process there could drive the IDE: start reviews, read someone else's remarks, end their sessions.

**Fetching is not reading.** Neither acknowledgement route marks a remark read as a side effect of a
fetch. A fetch changes nothing: not the store, not the review's phase, not the deadline. Two reasons
decide this. A fetch is a poll, so it runs many times in one review, and anything it changed would
have to be idempotent. And a fetch response can be lost in the tunnel. If the fetch itself marked
remarks sent, a lost response would leave the IDE believing the remarks were delivered when they
never arrived. Keeping the two separate means the skill can fetch again as often as it likes, and
the IDE only believes delivery once the agent says so — over `ack`, for the review this fetch is
part of — in a request that can only be sent after the bytes arrived. Since phase 10 the published
file also has a second acknowledgement route, `published-read`, keyed to a batch's nonce instead of a
review session, for the plain-publish case with no review open. Phase 10 did not extend the tunnel to
reach it: reading a published batch from another machine with no review open is still not possible.
See "Known Issues" below and `docs/ideas.md`'s entry on the push-service direction that would take.

**Why a fetch can still reach a rejection after the review has already ended, and why that no longer
needs the plugin to remember anything about it.** Rejecting a review writes the rejection into the
published file, then clears the review. See "Three signals that the remarks arrived" above. A fetch
keyed only to a *live* review would answer "nothing is waiting" for a review that just ended, and a
remote agent could not tell a rejection from a timeout. Phase 8 solved this by having
`WaitingReviewService` remember `lastEnded`, one nullable field set in `endReview()`; `handleFetch`
read it back through `endedOutputPath(session)`, checked against the session id, once `current()` no
longer named a live review. Phase 10 removed that field along with the per-review output path it
existed to serve: `endReview()` no longer sets anything for a fetch to read later, because there is
nothing left that only a live review would know. `handleFetch` instead falls straight through to
reading the one predictable published-file path once the request's session is not the current
review's own `Waiting`-phase session, and the header's `reviewSession` field is what the session id
is checked against — the same check a fetch for a still-`Sent` review already needed, so a review that
has fully ended is not a special case for the fetch handler at all any more, only a difference in
whether `current()` still names anything on the way there.

**`start` is also one of the places that ends a review, not only `clear`, `acknowledge` and
`expireIfStale`.** `startOrConflict`'s stale branch (see "The branch order in `startOrConflict`
matters" below) accepts a different session once the current review is stale, and that means the
old review is being replaced, not continued. `start` calls `endReview()` on the old review before it
installs the new one and calls `scheduleExpiry` for it. Without that call, the old review's own
scheduled expiry task gets cancelled a few lines later, by the new `scheduleExpiry`, before it ever
gets to run and set `lastEnded` itself. The old review's output path would then be lost for good,
not just for the short window the scheduled task normally covers.

**The size cap, and why it refuses instead of truncating.** A response over 1 MiB is refused with
`status: "too-large"`, and no content field at all. Truncating was the alternative, and it is worse:
a markdown prompt cut in the middle looks complete to a model reading it. The check runs on the
file's size, before any of it is read, so an oversized file never becomes an oversized allocation
either.

**The rate limit as a design input.** The built-in server allows 30 requests a minute from one
address, and every tunnelled request shares one address, `127.0.0.1`. So the remote poll interval is
5 seconds, not the local case's 1 second, and a `429` answer means "wait longer," never "stop." The
skill sleeps 20 seconds and keeps polling; its own deadline is still the only thing that gives up.

**Four connection values, not three.** `docs/ideas.md`'s original plan named host, port and token.
It missed a fourth: the repository path as the IDE machine sees it. The `start` request's `project`
field is matched against the IDE machine's own open project paths, and two machines can have the
same repository checked out at two different paths. The fourth value defaults to the agent's own
`git rev-parse --show-toplevel`, so the common case, where both machines agree, needs nothing extra.

**The four values are stored on the agent machine, by
`docs/skill/claude-remarks-review/remote-config.sh`.** Phase 10 added it. Before it, all four had to
be pasted into the session again on every run, and the token is a UUID nobody retypes correctly.
`save` writes `~/.claude-remarks/remote-<16 hex>.env`, four `key=value` lines: `ide_host`,
`ide_port`, `ide_project` and `ide_token`. `show` prints back the first three; `forget` deletes the
file. Step 1 of `SKILL.md` reads it automatically, and with no file stored the same-machine case runs
exactly as it did before.

**Two different repository paths, one file, and that is deliberate.** The file's name is the first 16
hex characters of the sha256 of the **agent** machine's own repository root — the same hash shape the
plugin uses, computed here over a path the plugin has never seen. `ide_project` *inside* the file is
the repository path as the **IDE** machine sees it, which is the value the `start`, `fetch` and `ack`
bodies carry. The two are usually different strings, and nothing tries to make them agree. The name
answers "which checkout on this machine is this configuration for", so two repositories here can
never share one stored configuration. The content answers "what does the IDE call the project", which
is the only thing the endpoint will match against.

**The token never travels as an argument, and never comes back out.** `save` reads it from
`CLAUDE_REMARKS_TOKEN` in the environment and refuses to run without it. An argument would be
readable by every process on the machine through `ps`, for as long as the command ran, and the token
is the only gate on the endpoint — see "Why the security model needs no change" above. For the same
reason `save`'s own confirmation line prints the host, the port and the project and nothing else, and
`show` prints only those three. A person who needs the token again reads it off the handshake file on
the IDE machine, where it lives in the first place.

**The write is held to the same standard as the handshake file's.** If `~/.claude-remarks` already
exists and is not owner-only, `save` refuses rather than writing a file holding a token into a
directory other accounts can read — `writeHandshake` (`review/ReviewHandshake.kt`) makes the same
demand for the same token. If the directory does not exist, it is created `700`. The file itself is
written to a temp file beside the target, `chmod 600`, then renamed: the same temp-then-rename shape
`AtomicWrite.kt` uses, with the permission set *before* the rename so the file never exists briefly
world-readable under its final name.

**The skill parses the file with a whitelist loop, never `.` (source).** Sourcing runs the file as
shell. A value holding a space, a quote or a backtick would then change what a later line means, and
this file is written by one script and read by another with nothing checking it in between. The
reader instead splits each line on the first `=` and assigns only the four names it knows, ignoring
everything else. That costs six lines and takes the whole class of "a stored value became a command"
off the table. The writer holds up the other end of the same contract: `save` refuses a `--host` or
a `--project` carrying a line break, because a line break either truncates what the reader sees after
it or adds a key of its own, and `ide_token` is the key worth adding. `--port` is already digits
only, so those two are the whole check.

**The honest cost: the token is now on a second machine's disk.** Before this, the token existed in
the IDE machine's handshake file and in whatever the person pasted into the session. Now it also sits
in a file on the agent machine, at rest, until `forget` runs. An IDE restart mints a new token, which
makes the stored one useless but does not remove it. The mitigations are the permission checks above
and the fact that the token is worth nothing without the tunnel. The alternative — retyping a UUID
every run — is what people work around by writing the token somewhere less careful than this.

**The handshake file did not change.** `renderHandshake` writes three fields: the project path, the
port and the token. The path only feeds the filename hash that names the file; the person reads the
port and the token off it by hand. Host is not one of the three fields. It never lived in the
handshake, because the file already tells the person which machine wrote it. Host is a skill
argument instead, with a default the person can override. Nothing that could be added to the
handshake would help the agent on a different machine. The agent cannot read this file at all, no
matter what is in it, and a field describing a tunnel would be state the plugin does not manage,
does not detect and does not report on.

**The skill keeps one wait loop, with a small switch inside it.** `handoff_ready()` is the one thing
that differs between the two transports: the local case checks whether the file exists, the remote
case posts a fetch. Everything else, the deadline, the two traps, the rejection check, the
acknowledgement, is one copy of code instead of two. The loop is written as
`while :; do handoff_ready && break; ...`, never as `while ! handoff_ready`. `!` collapses the
function's three possible answers, ready, not yet, and stop, down to two, so a hard stop would be
read as "keep waiting" until the deadline.

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

## Known Issues

Real defects found by review, deliberately not fixed. Each was judged remote enough that the fix was
not worth the churn at the time. They are written down rather than dropped so a later session finds
them here instead of rediscovering them, and so nobody treats this design as flawless. All of them
are in `review/`, except the last five.

**Each entry starts with two labels: how likely it is to happen, then how bad it is if it does.**
Severity on its own describes the worst case, so it makes a defect needing two coincidences in the
same second read as urgently as one firing every other day. Both labels together are what let you
decide which of these is worth opening.

Likelihood is one of four words. **Certain** means it happens every time. **Likely** means it happens
in ordinary use. **Occasional** means it needs a specific sequence that does come up. **Rare** means it
needs a coincidence, a hostile input, or a machine misbehaving.

Severity is one of three. **Critical** means data loss or a security hole. **Major** means wrong
behaviour a person would act on. **Minor** means a missing signal, a repaint, or a logged exception,
with nothing lost.

Nothing here is critical. One thing to know when reading the two `Occasional` entries: both are
described as a scheduler-latency window, which sounds like milliseconds. Suspending a laptop widens
it. `isStale` reads the wall clock while the scheduler counts monotonic time, so a machine that sleeps
for an hour wakes with reviews that are stale and expiry tasks that have not run.

**RARE, MAJOR: a same-session retry after a publish is handed the previous batch.**
`WaitingReview.kt`, `startOrConflict`'s same-session branch copies the existing state forward with a
fresh deadline, and that copy keeps its `phase`. A retry landing after a publish therefore still
carries `Sent`, and `handleStart` cannot tell that case from a first request. Phase 10 removed the
shape this defect used to have — `handleStart` no longer answers with an output path, and the skill
no longer has a `while [ ! -e "$output" ]` loop that could return from one immediately — so the
entry is re-derived here rather than carried forward. What is left rests on one assumption both
branches of the wait make: a review's session id is invented moments before the `start` it is sent
with, so any batch naming that session is this run's own answer. The local branch acts on that
directly — `watch-remarks.sh --require-review <session>` reports the first batch whose `review:`
header field names the session and ignores the nonce entirely. The remote branch acts on it by
passing no `--seen`, so the fetch action's `ready` for the session's own batch is taken at face
value. Both are right for a fresh session id and wrong for a retried one: after a publish the file
already holds a batch naming that session, so a retry is handed the answer to the *earlier* request
at once and acts on it instead of waiting for the new one. Still rare, and for the same reason as
before: it needs an agent that re-posts `start` with the same session id after a publish, which
nothing in the skill does today.

**RARE, MAJOR: a backwards clock step can consume the deadline task.** `scheduleExpiry` computes its delay from
the wall clock (`deadlineAt - currentTimeMillis`) but `ScheduledExecutorService` counts elapsed
monotonic time, and `expireIfStale` re-checks against the wall clock again. If the clock steps back —
an NTP correction, someone changing it — the task fires while `now < deadlineAt`, `expireIfStale`
finds nothing stale and returns null, and nothing reschedules. The deadline task is spent. `current()`
still masks the review so the Send button stays correct, but the banner only repaints on a refresh,
so "Claude Code is waiting" can stay on screen indefinitely. That is the exact lie the scheduled task
exists to prevent, in the one case it cannot cover.

**RESOLVED IN PHASE 10: the EDT used to be able to block behind a netty thread's filesystem call.**
`start` is `@Synchronized` and used to hold the service monitor across `Files.createTempDirectory`,
the per-review output directory. `markSent` and `clear` take the same monitor and are both called
from the EDT — the publish's `finishOnUiThread` block and the banner's Reject link — so a hung or
full `TMPDIR` made the UI thread wait. The class KDoc argued carefully that `current()` is
unsynchronized so the EDT never blocks on that call, which was true and was not the only path. Phase
10 removed the per-review directory rather than narrowing the lock: `start` now does no filesystem
work at all, and the monitor is held only across a few field reads and writes. The one
`Files.createTempDirectory` left in `src/main` is `handshakeDir()`'s unit-test branch, which runs
inside a lazy delegate on the test's own thread and never under this lock.

**RARE, MINOR: the disposal guard on the scheduled expiry narrows the race rather than closing it.** The task body
checks `!project.isDisposed`, then `expireStaleReview` calls `getInstance(project)`, which throws
`AlreadyDisposedException` if disposal lands between the two. Costs a logged exception in the shared
scheduler, no data loss. Needs a project closed within microseconds of a deadline firing.

**RARE, MINOR: a concurrent `current()` read can see no review at all during a stale-replacement.** `start()`'s
stale-replacement branch calls `endReview()`, which sets `state` to null, and only assigns
`state = result.state` a few lines later, inside the same `@Synchronized` block. `current()` reads
`state` without synchronization on purpose (see the class KDoc), so a read landing in that gap sees
`current() == null`, as if no review were waiting, instead of a review being replaced. The old code
did the replacement as one write, with nothing in between, so this gap did not exist before that fix.
The window is as short as two statements running one after the other, with no lock wait and no
filesystem call between them, and the next `notifyPanel()` a few lines down repaints regardless. Not
fixed here: closing it would mean giving `start()` a way to write both fields together, which today
only `endReview()` and the state assignment below it do apart.

**OCCASIONAL, MINOR: a superseded review's balloon never fires.** In `WaitingReview.kt`, `start()`'s stale-replacement
branch calls `endReview()` but discards the return value. `endReview()` returns the state it removed,
and that is what `acknowledge()` and `expireIfStale()` pass to `reportReviewEnd` in `SendReview.kt`,
the function that shows the balloon. Here nothing passes that value on, so `reportReviewEnd` never
runs for the review that got replaced. If that review was in `Sent` phase, the person never sees
"Claude Code left without reading the N remarks you sent." This is not a regression from phase 8. The
code before this phase also overwrote `state` directly and never called `reportLater` either, so the
missing balloon predates this phase, and this phase leaves it unchanged. It is noticed only in a
narrow window: a second `start` request for a different session arrives after the current review's
deadline has passed, but before the scheduled expiry task has run and shown its own balloon. No
remarks are lost in either case. Nothing was marked sent, so they stay pending in the tool window.
Not fixed here: the fix would mean threading `endReview()`'s return value out of the stale-replacement
branch and into `reportLater`, a behaviour change nobody has asked for.

**RESOLVED IN PHASE 10: a short window used to let a fetch miss a real, unread review.** Before phase
10, `current()` masked a review the moment `isStale()` turned true, before the scheduled expiry task
had actually run and called `endReview()` to set `lastEnded`. A fetch landing in that window fell
through both checks in `handleFetch` and answered `no-review`, even though a file with real, unread
remarks was sitting in the review's own temp directory. Phase 10 removed the mechanism this defect
lived in, not by patching it: `handleFetch` no longer reads `lastEnded` or an ended review's output
path at all. Once a request's session is not the current review's own `Waiting`-phase session, it
falls straight through to reading the one predictable published-file path — the same path a `Sent`
phase's answer, a rejection, or a plain publish all write to — and the header's `reviewSession` field,
not any live-review lookup, is what decides whether this session may see it. There is no window left
for a scheduler's own latency to open.

**RESOLVED IN PHASE 10: the fetch action used to inherit the defect where retrying `start` with the
same session id after a send still returned the old remarks.** `handleFetch` used to read through
`current()` and `endedOutputPath`, both fed by the same state `startOrConflict`'s same-session branch
copied forward with its old `phase`, so a `start` retried with the same session id after a send still
answered `waiting` with an output path whose file already existed, and a fetch right after that first
poll returned the previous review's remarks immediately. Phase 10 removed the per-review output path
this defect depended on. A retried `start` still copies the review's phase forward, but a fetch no
longer trusts that phase for anything beyond "is a fresh answer still pending" (`Waiting`); once the
phase is `Sent`, the fetch reads the published file's header directly, and the header genuinely does
name this session's own batch, correctly, because that is what a `Sent`-phase publish wrote there.
What used to be a defect the redesign happened to remove as a side effect, not a case anyone
re-verified by writing a new test against it — worth a deliberate check before relying on it further.

**RARE, MAJOR: a file path is written into the prompt as a Markdown heading, unescaped.** `render/PromptRenderer.kt`
emits `## <path>` per group. The prompt is instructions a model then acts on, so a path containing a
newline or heading characters can forge structure outside the code fence — a fake heading, or text
that reads as a new instruction. `escapeMarkdown` exists in that file and is applied to remark text
and to the selection phrase, but not to the path. Git permits newlines in filenames, so this is
reachable in principle; it needs a hostile file in a repository you then annotate, which is why it was
left. It is the cheapest entry here to fix. It is also the only one that is a trust-boundary issue
rather than a race, which is why it is the one to fix first if any of these are ever fixed.

**RARE, MAJOR: a failed published-file write leaves the previous file in place.** `publishRemarks`
(`action/PublishRemarks.kt`) still marks the remarks `PUBLISHED` when the clipboard succeeded but the
published-file write failed, and the balloon says the file was not updated. See "The published
file" above for why that is the honest answer rather than a bug. What that leaves unsaid is what is
still on disk: the file from the last successful publish, untouched, sitting at the same path a skill
would compute for right now. It looks exactly like a current answer to that skill. The only thing
that gives it away is the header's `published:` timestamp, which nothing forces a reader to check. It
needs a real filesystem failure on the second of two writes in the same callback: a full disk, or a
permissions change on `~/.claude-remarks/` mid-session. That is why it is judged rare rather than
occasional. Two behaviours in this publish pipeline have no automated test at all, and this is one of
them: `publishMessage`'s `count` comes from `prepared.ids.size`, computed before `markRemarksPublished`
runs, so a unit test that skips that call and checks the balloon text alone cannot tell the difference.
This was confirmed by mutating the code this way and watching the whole suite stay green. The other
untested behaviour is a null project root reaching the same failed-write branch. Both are catchable
only by the hand check in section 12 of the phase 9 plan: publish with a filesystem failure forced on
the published-file write, and confirm the balloon says so while the store still shows the remarks
handed over.

**OCCASIONAL, MAJOR: a project opened below the repository root writes remark paths the agent
resolves against the wrong directory.** The project's identity — what names the handshake file and
the published file, and what the endpoint matches a request against — is the git top level
(`projectIdentity`, `review/ReviewHandshake.kt`). The paths inside the prompt are something else:
`store/RemarkTarget.kt`'s `relativePathOf` makes them relative to the project base path, and that
was deliberately left alone, because every stored remark already holds one and the tree, the gutter
and the resolver all read them that way. So a project opened on `modules/api` inside a repository
writes `Service.kt` for `modules/api/Service.kt`, while the agent reading it is almost certainly
sitting at the repository root, where that path is either missing or a different file. The two
halves of the same prompt now answer to two different directories. It shows up only for a project
opened below its repository root, which is why it is occasional rather than certain, and the agent
notices — a path that does not exist is visible, unlike a path that quietly names another file.
Not fixed here: making the prompt's paths repository-relative means changing what every stored
remark's `path` field means, plus a migration for the ones already stored, which is a phase of its
own and not a side effect of naming files correctly.

**OCCASIONAL, MINOR: a remark written on the preview can point at whole lines rather than at the
words that were selected.** `narrowToSelection` (`preview/PreviewSelection.kt`) searches for the
highlighted text inside the coarse range with one `indexOf`. The source says `some **bold** words`,
the render says `some bold words`, the search fails, and the coarse range stands, usually one whole
line. This is the same fallback the renderer's own `markersValid` already uses for a phrase that no
longer matches, not a new kind of defect. See "A Remark on the Rendered Preview" above.

**OCCASIONAL, MINOR: inside a drawn Mermaid diagram, whether a remark lands on one label or on the
whole diagram depends only on whether the search finds the label's text.** `md-src-pos` is written on
the whole fence, not per line inside a diagram the way it is per line inside a paragraph, so there is
no line-sized fallback to land on when the search fails. A remark can then point at the entire
diagram's source instead of at one node. See "A Remark on the Rendered Preview" above for why.

Six more, recorded in phase 10's own plan rather than found by review, because each is a limit the
phase's design accepts rather than a defect in it. All of them are in `review/` or in the skill.

**CERTAIN, MINOR: publishes grow until something acknowledges them.** Publish Unread carries every
remark that is not `READ`. A person who never lets any session acknowledge — neither a one-shot read
nor a listener — gets a bigger published file every time, until Clear Handed Over runs. Nothing is
lost; the relief valves are Clear Handed Over and a narrower Publish Selected batch.

**LIKELY, MINOR: a second publish overwrites a batch nobody has read yet.** The remarks it carried are
still in the store, and the next Publish Unread carries them again. The one case that does not
recover by itself is two Publish Selected batches with different rows: the first batch's rows come
back only through a later Publish Unread or by selecting them again.

**OCCASIONAL, MINOR: a rejection erases the last published batch from the file.** Same recovery as
the overwrite above — the remarks stay in the store and a later publish carries them again.

**OCCASIONAL, MINOR: the batch memory does not survive an IDE restart.** `PublishedBatchService`
holds its batches in memory only, the same as `WaitingReviewService`. An acknowledgement after a
restart is answered `unknown-batch`, the remarks stay `PUBLISHED`, and publishing again gives the
skill a nonce the restarted service actually remembers.

**LIKELY, MINOR: a killed agent session leaves the banner up until the IDE's own deadline.** Before
phase 10 the skill's wait loop ran in the same foreground shell as `trap 'ack abandoned' EXIT`, so an
ordinary interrupt abandoned the review at once; only a hard `SIGKILL` left the banner stale until the
deadline. Since phase 10 the wait is a launched background watcher, and there is no shell left running
the trap once the foreground call that launched it returns — abandoning is now something the session
posts itself, in the foreground, when the watcher reports the deadline passed or the person says stop.
A session that stops answering without saying either now leaves the banner up until the IDE's own
scheduled deadline the same way a `SIGKILL`ed session always did. Nothing is lost: the remarks were
never marked read, so they stay exactly where they were.

**CERTAIN, MINOR: the published file cannot be read from a session on another machine.** The remote
review path still works, because a fetch is keyed to a session and crosses the tunnel phase 8 built.
There is no remote equivalent of the one-shot read or listen mode: reading or watching a published
batch with no review open only ever resolves the file locally. The direction for solving this later is
a small push service on the IDE machine that a remote session subscribes to, not a further extension
of the tunnel-and-poll pattern, which would run straight into the built-in server's 30-requests-a-minute
limit. See `docs/ideas.md`, "Sending remarks to a remote agent session", for the reasoning this is
deferred with.
