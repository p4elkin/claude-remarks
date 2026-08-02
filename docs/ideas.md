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
