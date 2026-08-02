# Ideas

Things worth building after phases 3-4. Not planned, not committed to. Each entry says what the
idea is, and what I already know about whether the platform makes it easy or hard, so the next
session does not have to find that out again.

Raised by Sasha on 2026-08-02, after the first real install into IntelliJ.

## Named buckets in the tool window

Group remarks into buckets the user names, instead of only by file. A bucket would be something
like "auth refactor" or "review of MR 412". Inside a bucket, sub-group by the tags that already
exist (bug, question, refactor, note).

Why: the tree groups by file today, which is the right default while reading one file. It is the
wrong shape when the remarks belong to two separate lines of thought that happen to touch the same
files. Buckets also give Copy a natural unit: copy one bucket, not everything pending.

How hard: easy. A bucket is IDE-side state like everything else — one more nullable field on
`RemarkState`, and one more level in `buildTreeRoot`. Nothing in the platform gets in the way.

Open questions, worth settling before writing code:

- Where the bucket is chosen. Either the input popup gains a bucket field, or the tree gains a
  "move to bucket" context menu, or both. A popup field on every remark is friction on the action
  that has to stay fast.
- Whether there is a current bucket that new remarks join by default. Probably yes, otherwise
  every remark needs the field filled in by hand.
- The nesting order. Bucket -> file -> tag, or bucket -> tag -> file. The first keeps the file
  grouping people already have; the second is what the request asks for. Could be a toggle, but a
  toggle is a setting and a setting is a thing to maintain — pick one first.
- What Copy All Pending means once buckets exist. Probably: the toolbar gains "Copy Bucket", and
  Copy All keeps meaning everything.

## Class name completion in the remark input

Offer completion for class names and fully qualified names while typing a remark, so a remark can
say `see JcrSessionProvider` without typing it out or getting it wrong.

Why: a remark is read later by Claude, so a correct symbol name in it is worth a lot. A wrong one
sends the reader to the wrong place.

How hard: this is the one with a real cost, and the cost is not the completion — it is the input
component.

- The remark box is a `JBTextArea` today. A plain Swing text area has no completion. The platform
  way to get it is `TextFieldWithCompletion` (`com.intellij.util.textCompletion`), which wraps an
  `EditorTextField` and takes a `TextCompletionProvider`.
- `EditorTextField` is a real editor. Enter and Shift+Enter would no longer be Swing key bindings
  in an input map — they go through the editor's action system instead. `RemarkInputPanelTest`
  pins the current binding, and that test would have to be rewritten against the new mechanism.
  This is the actual work.
- The completion data already exists in the IDE. `PsiShortNamesCache.getInstance(project)` gives
  every class name in the project, but it is Java-centric. The language-neutral source is the same
  one behind Ctrl+N: `ChooseByNameContributor` / `GotoClassModel2`. Prefer that, since the plugin
  itself is language-neutral and only depends on `com.intellij.modules.platform`.
- A lookup popup opening inside the remark popup is fiddly. The outer `JBPopup` must not cancel
  when the lookup takes focus. Expect to spend time on `setCancelOnWindowDeactivation` and on which
  popup owns the Escape key.

A smaller version that avoids all of the above: keep the text area, and add one explicit trigger
(a button, or a keystroke) that opens the ordinary "choose class" dialog and inserts the chosen
name at the caret. Less pleasant to use, a fraction of the work, and it does not touch the key
handling that is already tested.

## Annotating inside the diff viewer

Let the Add Remark action work while reading a diff, so a review pass over a set of changes can be
annotated without opening each file separately.

Why: this is the case the plugin is for. Reading a diff is exactly the "reading, not editing" mode
that remarks exist to serve.

How hard: partly free, partly blocked, and the blocked part is worth knowing before starting.

- The two panes of a diff are real editors created through `EditorFactory`, so the gutter service
  already sees them. Icons may well appear in the local pane today with no change at all. Worth
  trying by hand before writing anything.
- The right-hand pane usually holds the real file's `Document`, so `FileDocumentManager.getFile`
  returns the actual file and `remarkTargetProblem` is happy. Annotating that side should mostly
  work already.
- The left-hand pane is the problem. The "before" side of a diff is built from VCS content, not
  from a file under the project root, so it has no project-relative path and the action refuses it
  — correctly, by the rule that a remark must point at a file in the project. There is no obvious
  right answer here. Options: refuse the old side and say why, or anchor the remark to the new
  revision's corresponding line, which needs the diff's own line mapping and is real work.
- "Annotate just the changes" suggests more than making the action not refuse. The useful version
  probably restricts remarks to changed lines, or offers one remark per hunk. That is a design
  question, not a platform question.

Start by opening a diff with the plugin installed and seeing what already happens. The answer
changes how much of this is left to build.

## Keep the history, and stamp each remark with the commit it was written against

Stop throwing remarks away when they are cleared. Keep every remark ever written, including the
ones Clear Sent and Clear All remove today. When one is stored, also record the repository HEAD
commit at that moment, so it is possible to say which version of the code the remark was written
about.

Why: a remark's line numbers and its stored hash only mean anything against one revision. Right
now nothing records which one, so an orphan is a mystery — the code may have moved, or the remark
may simply belong to a different commit. The commit turns that from a guess into a fact. Keeping
the history also means a cleared remark is recoverable, and that a past reading pass can be looked
at again.

How hard: two separate pieces, one easy and one with a real decision in it.

**Keeping the history** is easy in itself. Either add a cleared state and stop deleting, or move
cleared remarks into a second list. Prefer the second. Everything in the active list gets resolved
against its file on every change, and that cost is per remark, so an active list that only grows
would make the whole plugin slower the longer it is used. An archive that nothing resolves does
not.

The real decision is where the history lives, and the hard rule that nothing remark-related enters
version control decides it. `.idea/workspace.xml` is where remarks live today, and it grows
without bound if history goes in it. Two options:

- The IDE config directory (`PathManager.getConfigDir()`), keyed by project. Cannot ever be
  committed by accident, which is the whole point. Does not travel with the project, and is lost
  if the IDE config is wiped.
- A separate file next to the project. Travels with the project, and is exactly the thing the
  no-VCS rule exists to prevent from being committed.

The first is the safer default. Pick it unless there is a reason not to.

**Recording the commit** needs care about dependencies. The plugin depends only on
`com.intellij.modules.platform` today, which is why it loads in any JetBrains IDE. Git integration
lives in the separate Git4Idea plugin, so using its API would mean declaring a dependency on it
and requiring it to be installed.

The cheap way avoids that entirely: read `.git/HEAD` under the project root, and if it names a ref,
read that ref file. A few lines, no dependency, no VCS API. It only understands git, which is
almost certainly enough. Two cases to handle: `.git` is a file rather than a directory in a
worktree or a submodule, and a detached HEAD holds the sha directly instead of a ref.

Worth deciding at the same time: whether the commit is captured once when the remark is written,
or refreshed. Once is right — the point is to record what the author was looking at.

## Pick the tag without the drop-down

Replace the tag drop-down in the input popup with a row of chip buttons, and let the tag be chosen
from the keyboard so writing a remark never needs the mouse.

Note on wording: the drop-down chooses the **tag** (bug, question, refactor, note). Status is
pending or sent, and the user never picks it — copying sets it. This idea is about the tag.

Why: adding a remark is the action that has to stay fast. Reaching for a drop-down breaks the
flow of typing a sentence and pressing Enter.

How hard: both halves are small, and the keyboard half is nearly free.

**Chips.** The platform already has the control. `SegmentedButton` is in the 2025.2 Kotlin UI DSL,
reached as `row.segmentedButton(items) { presentation, item -> ... }`, confirmed against the
downloaded jars. That renders exactly the chip row this asks for, and it handles selection and
theming without hand-built toggle buttons. Five items including "no tag".

**Keyboard.** The input panel already binds Enter and Shift+Enter through Swing input maps, and
`RemarkInputPanelTest` already pins that mechanism. Adding Alt+1 to Alt+4 for the four tags, and
something for "no tag", is the same mechanism again — a few lines and one more test in the shape
that already exists.

One interaction worth deciding before either is built: the class-name completion idea above
replaces the `JBTextArea` with an `EditorTextField`, which moves Enter and Shift+Enter out of Swing
input maps and into the editor's action system. Any tag keys added now would have to move with
them. If both are wanted, do the input-component swap first and add the tag keys on top of the new
mechanism, not the old one.

## IdeaVim

Short answer: this already works, and the hassle is close to zero. What is missing is that nobody
is told.

IdeaVim can invoke any registered action by id with `:action <id>`, so the plugin's actions are
already reachable from a `.ideavimrc` mapping today, with no code change:

```
nnoremap <leader>r :action ClaudeRemarks.AddRemark<CR>
vnoremap <leader>r :action ClaudeRemarks.AddRemark<CR>
nnoremap <leader>c :action ClaudeRemarks.CopyAll<CR>
```

Both ids are already namespaced and stable. The cheap work is documenting them in the README, and
treating them from then on as a public interface that does not get renamed.

Two things to actually test before claiming it works, rather than assuming:

- **Visual-mode selection.** The whole point is to select lines with `V` and remark on them.
  `:action` invoked from visual mode has historically been awkward about whether the selection is
  still there when the action runs. `AddRemarkAction` reads `editor.selectionModel`, so if the
  selection is gone it would fall back to the caret line. Try it; if the selection is lost, the fix
  is on the mapping side (`:action` after leaving visual mode with the marks intact), not in this
  plugin.
- **Typing inside the popup.** Today the input is a plain `JBTextArea`, which IdeaVim does not
  touch, so typing behaves normally. The class-name completion idea above would replace it with an
  `EditorTextField`, which is a real editor. That is fine on its own: IdeaVim already supports
  editors that are not files — the Git commit message box is one, and it works there, starting in
  insert mode. IdeaVim has an option governing this (`ideavimsupport`), worth checking rather than
  assuming a default.

  What does need deciding is Escape and Enter, which the popup has opinions about. Escape cancels
  the input today; with vim active in the box, the first Escape would leave insert mode instead,
  and cancelling would take a second one. Enter submits today; in an editor with vim active, the
  editor's own action system sees it first. Neither is a blocker, and neither is a reason to prefer
  the cheaper completion variant. They are two behaviours to choose deliberately when the input
  component changes, and to cover with tests, since the current Enter and Shift+Enter behaviour is
  already pinned by a test that would have to be rewritten anyway.

A real `VimExtension` is available but is not worth it. Action ids plus a documented mapping give
the same result.
