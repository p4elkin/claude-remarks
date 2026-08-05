# Claude Remarks

An IntelliJ Platform plugin for reviewing code you are about to hand to a Claude Code session.

![The remark box open over the README text, the Claude Remarks tool window listing two remarks on the right, and the status bar reporting that a Claude Code session read them](docs/images/remarks.png)

*Marking up this very README from inside the IDE. The two greyed rows on the right are remarks a
Claude Code session has already read; the box in the middle is the next one being written.*

You read the code in the IDE, where you can actually navigate it, and mark the places you have
something to say about. The plugin holds those remarks next to the code without writing a single
byte into it, and when you are done it renders all of them into one markdown prompt: your note, the
lines it points at, the code itself, how strongly you mean it. You paste that into a Claude Code
session, or let the bundled skill pick it up on its own.

The alternative is typing "in `Foo.kt`, the thing around line 140, could you..." three times in a
row and hoping the model finds it. This is that, but the model gets the exact lines.

**This is early software.** The test suite is green, but almost none of it has been seen running in
a real IDE. See [Status](#status) before you decide to rely on it.

---

## Contents

- [What it does](#what-it-does)
- [Installing](#installing)
- [Working with it](#working-with-it)
- [The Claude Code skill](#the-claude-code-skill)
- [When the IDE is on another machine](#when-the-ide-is-on-another-machine)
- [Status](#status)
- [IdeaVim](#ideavim)
- [Building and testing](#building-and-testing)
- [Architecture](#architecture)
- [Licence](#licence)

## What it does

Mark up what you are reading, in the IDE, and hand the marks to Claude Code.

- **Annotate anything the editor shows** — source code, a rendered markdown preview, plain text. A
  remark can cover whole lines, or just a phrase inside one.
- **Nothing is written into your files.** Remarks live in IDE state only, and stay out of version
  control.
- **A remark remembers the code it points at**, and follows it as you keep editing.
- **One press turns every remark into one markdown prompt** — your note, the lines, the code, and how
  strongly you mean it — on the clipboard and in a file.
- **A bundled Claude Code skill reads that file**, so the prompt does not have to be pasted by hand.
  Tell a session `listen for my remarks in this project` and it waits in the background while you
  read, then picks up each batch you publish. See
  [The Claude Code skill](#the-claude-code-skill).

## Installing

Build the plugin zip, then install it from disk:

```bash
./gradlew buildPlugin      # writes build/distributions/claude-remarks-<version>.zip
```

In the IDE: **Settings → Plugins → the gear icon → Install Plugin from Disk…**, pick that zip, and
restart when asked.

It needs a 2025.2 or newer build (`sinceBuild = 252`, no upper bound), and an IDE that ships the VCS
module — every JetBrains IDE does, but if the plugin ever fails to load and the tool window simply
is not there, that hard `<depends>com.intellij.modules.vcs</depends>` is the first thing to check.
Building for the first time needs a JDK (17 through 25) and network access; see
[Building and testing](#building-and-testing).

## Working with it

The loop is: read, mark, publish.

1. Read the code. When something is worth saying, select the lines and press `Ctrl+Alt+Shift+R`.
   Type the note. Tag it if the tag helps you; set the severity later, from the tree, when you are
   triaging rather than reading.
2. Keep going. The tool window fills up on its own — no refresh needed. Sort the pass into buckets
   if it is large enough to be worth sorting.
3. Press **Publish Unread**. Every remark that has not been read becomes one markdown prompt on the
   clipboard: general remarks first under their own heading, then a section per remark carrying its
   tag, its severity, the short sha of the commit it was written against, and the code it points at.
   A balloon says how many remarks across how many files.
4. Paste it into a Claude Code session.

The rows do not disappear when you publish. They stay listed and stay full-strength, because the
next Publish Unread carries them again — that is what makes Publish Selected useful when a paste
went to the wrong window. They go grey only once an agent confirms it read them, and they leave the
list only when you clear them.

**Writing a remark.** Select some lines, press `Ctrl+Alt+Shift+R`, type a note, press Enter. The
same box opens from `Alt+Enter` ("Add Claude Remark") and from the editor's right-click menu,
including inside a diff. Optionally pick a tag from the chip row — `Alt+1` through `Alt+4` for
`bug`, `question`, `refactor` and `note`, `Alt+0` for none. `Cmd+Ctrl+Shift+Space`
(`Ctrl+Alt+Shift+Space` off macOS) inserts a class name from the project at the caret; it is
deliberately not `Ctrl+Space`, because the IDE offers Basic Completion even inside a popup and macOS
takes that combination for switching input source.

**A severity, decided later.** Every remark carries one of `vibe` / `suggestion` / `should` /
`must`, defaulting to `should`. It tells the model how strongly to act: a `must` gets done whatever
it costs, a `vibe` is an idle thought. The input box does not ask for it, on purpose — that box has
to stay fast, and a second chooser in it would turn a keystroke into a form. Change it afterwards
from the gutter icon's menu or the tree's right-click menu. The prompt always explains the scale to
the model in its own words, appended below your header rather than inside it, so rewriting the
header cannot silently drop the explanation.

**Remarks follow the code.** A gutter icon appears on the marked lines. As you keep editing, the
plugin re-finds the marked block by hashing it and by matching the lines around it — so text that
moved is followed, and text that cannot be found is shown as orphaned with its stale line numbers
rather than being quietly relocated onto the wrong code. Nothing is ever moved silently. Click the
icon to edit or delete that remark, or to change its severity or bucket.

**A remark can point at part of a line.** Select a phrase rather than whole lines and the remark
stores the exact words as well as the columns, so it can find them again after the paragraph
reflows. The published prompt draws `⟦`/`⟧` markers around those words inside the quoted code, and
the tree row and gutter tooltip show the column range next to the line number.

**A remark can also be written on rendered markdown.** Select words in IntelliJ's markdown preview,
right-click, and pick Add Claude Remark; the remark points at the same characters in the `.md`
source behind the selection. This needs the Markdown plugin, which every JetBrains IDE bundles. With
it disabled, that one entry point is simply absent and everything else is unchanged.

**A remark about nothing in particular.** Press Add General Remark in the tool window for a note
about the whole change rather than one file. It is rendered first in the prompt, under its own
`## General` heading with no code block, and sits at the very top of the tree.

**The tool window is a tree.** Grouped by file, with a General group above everything, and a bucket
level above the files once you put any remark in a bucket. Buckets are names you pick, like "auth
refactor", assigned to a whole selection at once — sorting a reading pass is the point, so there is
nothing to assign one at a time. Drag rows onto a bucket to move them, or onto `(no bucket)` to
clear it. Right-click a row for the same severity-and-bucket menu the gutter icon offers. Delete
removes the rows you picked out without asking; on a file or bucket node it stands for everything
underneath and asks first.

**Six toolbar buttons.**

| Button | What it takes |
| --- | --- |
| Add General Remark | Nothing. Opens the input box for a remark about no file |
| Publish Unread | Every remark that is not yet **read** — pending and published alike |
| Publish Selected | Exactly the selected rows, already-published and already-read ones included |
| Clear Handed Over | Every remark that has been published or read. Asks first, archives first |
| Clear All | Everything, including work you never published. Asks first, archives first |
| Refresh | Nothing. Re-resolves every remark against the files as they are now |

Selecting a bucket node and pressing Publish Selected publishes that whole bucket, which is why
there is no separate Publish Bucket button. **Tools → Publish Unread Claude Remarks** does the same
thing as Publish Unread without the tool window open, and can be given a shortcut in the keymap.

**Three states, and only an agent can grant the third.**

| State | Meaning | How the row looks |
| --- | --- | --- |
| `PENDING` | Written, handed nowhere | Full-strength text, note icon |
| `PUBLISHED` | Handed to a channel that cannot confirm a read | Full-strength text, upload icon |
| `READ` | An agent said it actually read this one | Grey text, check icon |

Colour and icon answer two different questions, and that is deliberate. **The colour says whether
this is still the work**, which has two answers: Publish Unread carries a published remark again, so
a published remark is not finished and must not look finished. **The icon says which of the three
states it is in**, which has three answers, so pending and published are still told apart at a
glance. `ui/RemarkStatusLook.kt` is the single place that decides both, read by the gutter icon and
the tree alike.

Publishing can never produce `READ`, however many times it runs. Only an agent's own
acknowledgement can, over one of two routes the IDE itself minted. Publishing a read remark again
hands it over as `PUBLISHED`, because nothing has confirmed that second handover.

**Where all this lives.** Remarks are stored in `.idea/workspace.xml`, which the IDE's own generated
`.idea/.gitignore` excludes — so they stay out of version control with no extra work. A repository
that deliberately tracks `workspace.xml` is the exception, and there they would be committed like
any other change to that file. Clearing archives the remarks to a markdown file in the IDE's
configuration directory, under `claude-remarks/`, before removing anything, and removes nothing if
that write fails. That file is outside every project, so it cannot be committed by accident either.
A single Delete on one row is the exception and does not archive: picking out one row and deleting
it means "this one was a mistake", and archiving every typo would make the history useless to read.

**The prompt header is yours.** Edit it in **Settings → Tools → Claude Remarks**.

Everything above works with nothing installed on the other end and nothing listening. The rest of
this file is about the other end.

## The Claude Code skill

`docs/skill/claude-remarks-review/` is a normal Claude Code skill that reads remarks out of the IDE.
Install it by symlinking or copying the directory into `~/.claude/skills/`:

```sh
ln -s "$(pwd)/docs/skill/claude-remarks-review" ~/.claude/skills/claude-remarks-review
# or
cp -r docs/skill/claude-remarks-review ~/.claude/skills/claude-remarks-review
```

It is kept in this repository rather than only under `~/.claude/skills` because the skill and the
IDE endpoint it talks to are one protocol, with three pairs of halves that have to agree — the
request shapes, the eight fixed header lines and their three readers, and the values each endpoint
action answers. Keeping both halves of each in one place is what stops them drifting.
`docs/skill/README.md` spells all three out.

The skill has three modes.

### Listen mode — the convenient one

You ask Claude Code, in words, to watch for your remarks. It starts a background watcher on the
published file and then leaves you alone. You read code, mark it up, and press Publish when you have
something worth handing over; the watcher picks the batch up and Claude Code reports what arrived.
Nothing is started in the IDE, no banner appears, and nothing interrupts you while you read.

**It never starts on its own.** Noticing a published file, or noticing that a review is waiting, is
not the same as being asked. This is a design decision, not an oversight: a skill that begins
watching because it saw something interesting would be watching a person who did not ask to be
watched. You have to say it.

Two more things it will not do unasked. The watcher stops at the first batch it sees, and starting
another one to wait for the next batch is a choice said out loud rather than something that happens
automatically — one watcher per project on the machine, so a silent re-arm would risk two sessions
each believing they own the listener. And when a batch does arrive, it summarises what came in and
waits for you to say go, rather than acting on it: a listener runs unattended for hours, and nobody
chose that exact moment for the work to start.

It waits up to twelve hours, acts on nothing published before it started, and says at the start how
to stop it early.

### Read what is already published

You published, and you want it acted on now. The skill computes the published file's path from the
repository root, reads it, acknowledges the batch by its nonce — which is what turns the rows grey —
and gets to work. No review is started and nothing is waited for.

This is the mode to reach for when you published first and asked afterwards.

### Hand a review over and wait

The skill asks the IDE to hold a review open for a repository. It finds the IDE through a small
handshake file the plugin writes under `~/.claude-remarks/` when a project opens, then posts one
request to the IDE's own built-in server. A banner appears above the tree:

> Claude Code is waiting: *label*
> Publish to answer, or **Reject**

If the request named files that have a local change, you also land straight in a diff of just those
files, with the rest opened as plain editors.

You answer it by pressing **Publish Unread** or **Publish Selected** — whichever you would have
pressed anyway. **There is no separate send control**; a publish is how a waiting review is
answered. The remarks go to the clipboard as always and into the file the skill is waiting on, and
the banner changes to say the review is waiting to read them. Nothing is marked read yet. That
happens only when the skill acknowledges it actually read the file, at which point the rows go grey
and the banner disappears.

**A review can only be answered once.** Publishing again while the same review still waits is still
a real publish — the clipboard and the file are both written — but it does not reach that review.
The review keeps the ids of the batch that actually went to the agent, the balloon says the new
batch did not go to the waiting session, and the banner says a further publish will not go to it
either. The reason is on the agent's side: its watcher exits on the first batch that answers the
review, and nothing re-arms it. Saying "publish again to add more" would be inviting the one thing
that does not work.

Pressing **Reject** instead writes that decision into the same file, so the waiting session hears
about it in a second or two rather than sitting out its own timeout, and clears the review. Every
remark stays exactly as it was. Reject *after* you have published writes nothing — the file already
holds the remarks, and taking them back is not what Reject means — and only closes the review,
saying so.

If the skill never answers, the review goes stale on its own after the deadline the skill declared,
and the banner disappears with a balloon saying the agent left. Nothing handed over this way is ever
lost, because remarks handed over are never marked read until something confirms the read.

One refusal worth knowing: writing a remark on the *revision* side of a diff — the "before" of a
change — is refused rather than stored. Its line numbers describe the revision, not the file on
disk, and the working copy is one click away.

### Why the skill waits with a script

A foreground `Bash` call is capped at ten minutes. A launched background command is not, and
`watch-remarks.sh` is what lets the skill wait out a real deadline — half an hour for a review,
twelve hours for listen mode — instead of pretending to. The two modes that wait go through it. The
one-shot read waits for nothing, so it reads the file inline and never launches anything.

## When the IDE is on another machine

Both the handshake file and the published file are local paths on the machine the IDE runs on, so a
Claude Code session on that same machine just reads them. That is the normal case and needs nothing
beyond installing the skill.

Reaching a session on another machine needs an SSH tunnel you set up by hand with `ssh -R`, and four
values handed to the skill: the tunnel's local port on the agent machine, the token from the IDE
machine's handshake file, the repository path as the IDE machine sees it, and the host if it is not
`127.0.0.1`. The skill stores those four after the first time, keyed to the repository, so they are
not retyped every run. The endpoint's `fetch` action is what makes this work at all: it returns the
file's content in the response body rather than a path, since a path on the IDE machine means
nothing to an agent on a different one.

Nothing about a missing tunnel is silent. The built-in server binds `127.0.0.1` only, so a request
with no tunnel gets connection refused, and the skill says so and stops rather than retrying or
guessing. That same binding, plus a per-run token and the refusal of any request carrying `Origin`
or `Referer`, is the whole security model.

The two commands that read the port and the token, and the `ssh -R` line that starts the tunnel, are
in the "Over SSH: the IDE on another machine" section of
`docs/skill/claude-remarks-review/SKILL.md`. They are not repeated here, so there is one copy to
keep right.

## Status

Early, and honestly so.

The automated suite is green, and it is a real suite — anchoring, storage, the renderer, the
resolver, the tree, the endpoint, the review lifecycle. But there are no UI-rendering and no
end-to-end tests, `./gradlew test` runs no shell so it never touches the two scripts the skill
ships, and **almost nothing has been seen running in a real IDE.**

Exactly one gating run has happened, on version `0.6.0`: a review started over the endpoint, the
banner appeared, remarks were written including sub-line ones with their markers, the handover
reached the agent, and the read acknowledgement turned the rows grey. Separately, the remote path
was run end to end between two machines — a tunnel carried the requests, the review started, the
banner appeared in the IDE on the far side, a fetch carried the remarks back across the tunnel, and
the acknowledgement was accepted. That is all of it.

Everything phase 10 built is therefore unproven, because `0.6.0` predates it: the merged published
file, the two acknowledgement routes, publishing answering a waiting review, the watcher script, the
skill's three modes, and the appearance rules described above. Several earlier things are unproven
too. `CHANGELOG.md` says which, and points at the per-phase hand-check lists in `docs/plans/`.

If you try it, expect to find things. It has had very little real use outside its own test suite.

## IdeaVim

IdeaVim can run any registered action by id with `:action <id>`, so the plugin works from a
`.ideavimrc` mapping with no extra code. These four ids are a public interface and will not be
renamed:

| Id | What it does |
| --- | --- |
| `ClaudeRemarks.AddRemark` | Open the remark box on the selection, or on the caret line |
| `ClaudeRemarks.AskClaude` | Ask a question about the selection and publish it on the spot |
| `ClaudeRemarks.CopyAll` | Publish every unread remark into one prompt on the clipboard |
| `ActivateClaudeRemarksToolWindow` | Open and focus the Claude Remarks tool window |

`ClaudeRemarks.CopyAll` keeps the word CopyAll on purpose. The button it drives has been called
Publish Unread since phase 10, but the id is the part promised above, and renaming it would break
every mapping to it with no error anywhere — IdeaVim fails an unknown id inside IdeaVim, not here.

There is a fourth id, `ClaudeRemarks.AddPreviewRemark`, for the markdown preview entry point. It is
deliberately not in that table: it is registered only where the markdown preview exists, so it is
absent when the Markdown plugin is off, and a mapping to it would then do nothing.

```vim
nnoremap <leader>r :action ClaudeRemarks.AddRemark<CR>
vnoremap <leader>r :action ClaudeRemarks.AddRemark<CR>
nnoremap <leader>c :action ClaudeRemarks.CopyAll<CR>
nnoremap <leader>R :action ActivateClaudeRemarksToolWindow<CR>
```

Two things to check the first time rather than assume:

- **Visual mode.** Select with `V`, then `<leader>r`. `:action` invoked from visual mode has
  historically been awkward about whether the selection still exists when the action runs. The
  action reads `editor.selectionModel`, so if the selection is gone you get a one-line remark
  instead of the block you chose. That fix is on the mapping side, not in the plugin.
- **Typing in the box.** It is a plain Swing text area, which IdeaVim does not touch, so typing,
  Enter and Escape all behave normally.

## Building and testing

You need a JDK (17 through 25) and network access on the first build. Gradle comes with the project
as a wrapper. The first run downloads its own JDK 21 through the foojay resolver and the IDEA 2025.2
distribution, which took about 3m30s on a cold cache.

```bash
./gradlew build            # compile, test, assemble
./gradlew test             # the suite on its own
./gradlew buildPlugin      # build/distributions/claude-remarks-<version>.zip
./gradlew runIde           # a sandbox IDE with the plugin loaded
```

Roughly two thirds of the suite is plain JUnit with no fixture and runs in milliseconds: anchoring
and the sub-line phrase functions, the stored state's XML round trip, the resolver helpers, the
tree's node-building, the markdown renderer, the settings round trip, the commit reader against real
`.git` directories built on disk, the history file's rendering, the published file's header, and the
pure halves of the review endpoint. The rest start a light IDE fixture and are slower, because each
goes through a real project service, a real `Document` or a real markup model. No test count is
given here on purpose: it goes stale on the next commit, and `./gradlew test` prints the real one.

`./gradlew runIde` starts an interactive sandbox IDE that never exits on its own — fine for a person,
not for an agent session.

**Two files that ship with the plugin have no automated check at all.** The suite is Kotlin and runs
no shell, so it never touches `docs/skill/claude-remarks-review/watch-remarks.sh` or
`remote-config.sh` — everything the skill's waiting and its stored remote configuration are built
on. Both are checked by hand instead, each check its own run; `CLAUDE.md`'s Testing section lists
what those cover. A green suite says nothing about either.

There are also no UI-rendering or end-to-end tests. The popup appearing at the caret, the gutter
icon painting, the tree colours, the balloon and the settings page layout are all hand checks.

## Architecture

- **`anchor/`** — pure Kotlin, no platform imports, which is what keeps its tests running in
  milliseconds. Hashing lines, capturing an anchor, the two-pass resolve, and the sub-line phrase
  functions. `SubLineRange.kt` is the one place that decides whether a column pair is a real
  sub-line range and how a position is written down.
- **`model/`** — the persisted `RemarkState` record and its enums.
- **`store/`** — `RemarkStore.kt`, the project service that persists remarks; `RemarkEdits.kt`, the
  only functions production code may use to change one, plus the `REMARKS_CHANGED` notification;
  `RemarkResolver.kt`, which turns stored remarks into resolved rows; `RemarkTarget.kt`, which
  decides where a remark on the current editor would go and refuses the revision side of a diff;
  `ContextFormat.kt`; `GitHead.kt`, which reads the repository HEAD straight out of `.git` with no
  Git4Idea dependency; `RemarkHistory.kt`, the archive cleared remarks are written to.
- **`ui/`** — the input popup and its key bindings; `RemarkActions.kt`, the severity-and-bucket menu
  shared by the gutter and the tree; `RemarkStatusLook.kt`, the one place a status's icon and colour
  are decided; `ClassNameInsert.kt`; `RemarksTree.kt` and `RemarksTreeDnd.kt`, the tree and its
  drag-to-bucket wiring; `RemarksToolWindowFactory.kt`, the panel, the toolbar and the review banner.
- **`action/`** — every entry point that opens the same input popup: the shortcut and popup-menu
  action, the `Alt+Enter` intention, the preview action, and the tool window's general remark. Plus
  `PublishRemarks.kt`, the whole publish pipeline and the Tools-menu action.
- **`editor/`** — the gutter icon renderer and the project service that keeps gutter icons in step
  with the code.
- **`render/`** — `PromptRenderer.kt`, pure Kotlin, remarks to markdown; `PromptPayload.kt`, which
  reads the code around each remark and decides whether the payload goes to the clipboard directly
  or through a temp file.
- **`preview/`** — the markdown preview half: the injected script, the browser extension that
  receives a selection, and the pure arithmetic that turns it into a character range.
- **`review/`** — the shared review session and the one file that carries both a plain publish and a
  review's answer. `ReviewHandshake.kt` writes the file a skill reads to find this IDE;
  `ReviewRestService.kt` is the endpoint at
  `POST /api/claude-remarks/{start,ack,fetch,published-read}`; `WaitingReview.kt` holds the one
  waiting review per project; `ReviewLifecycle.kt` answers or rejects it and carries out what an
  acknowledgement means; `PublishedRemarks.kt` is the merged file every publish, answer and
  rejection writes; `PublishedAck.kt` is the second acknowledgement route, keyed to a batch's nonce;
  `OpenReviewFiles.kt` is the only file in the package that touches the VFS or the editor;
  `AtomicWrite.kt` is the temp-file-then-rename write they all use.
- **`settings/`** — the app-level service holding the editable prompt header, and its page.

`docs/claude/design.md` is the deeper version of all of this, and is kept current with the code:
anchoring, the gutter, the change notification, the publish pipeline, the three states, the
published file, a remark about no file, and the shared review session. `CLAUDE.md` holds the six
grep-checkable rules that must not break. `CHANGELOG.md` is how the project got here.

## Licence

MIT. See [`LICENSE`](LICENSE).
