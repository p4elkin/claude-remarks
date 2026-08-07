# Claude Remarks — Working Notes

An IntelliJ Platform plugin, version `0.12.0`. A person reads code in the IDE, marks the places worth
saying something about, and the plugin turns every mark into one markdown prompt for a Claude Code
session. Nothing is ever written into a source file.

Three documents, three jobs. Keep each one in its lane:

| file | job |
| --- | --- |
| `CLAUDE.md` (this file) | the rules, the map of the code, the traps, and what the plugin is |
| `docs/claude/design.md` | the whole design, and why every piece is shaped that way |
| `CHANGELOG.md` | how the project got here, phase by phase |

`.claude/rules/planning-rules.md` carries the rules every planning and execution agent works under.

When a line here says "see `design.md`, section X", the argument really is there. This file names a
decision; that file defends it.

## What the plugin is

### A remark

A remark is a note about code, held in IDE state and never in the file. It stores its text, the file
path, a line range, optionally a column range with the exact phrase between those columns, a hash of
the code it was written against, a few lines of context above and below, and the commit the
repository was on when it was written.

It is anchored. A two-pass search re-finds the code as the file changes around it, so the remark
keeps pointing at the right lines. A sub-line remark searches for its stored phrase first, which is
what lets it survive a paragraph reflowing onto another line. A remark whose code moved says
`(moved)`; one whose code is gone says `(orphaned…)`.

A remark can also be about no file at all — a general remark about the whole change. The tool
window's Add General Remark button is the only entry point for one, on purpose: the tool window is
where a person is looking at remarks rather than at code.

An answer is a second stored record with an anchor of its own, created only by an agent. See
"Asking, and the answer coming back" below.

### The three states

`PENDING` → `PUBLISHED` → `READ`.

- `PENDING` — written, not handed over.
- `PUBLISHED` — handed to a channel that cannot confirm a read: the clipboard, or the published file.
- `READ` — an agent said it read the remark, over one acknowledgement route, keyed to the nonce of
  the batch it read.

⚠️ **Publishing, however many times, only ever produces `PUBLISHED`.** Only an agent's own
acknowledgement produces `READ`. Guards 6 and 7 below are what hold that. A published remark stays in
the list and still draws at full strength, because it is still the work the next publish carries. It
greys only once it is `READ`.

### Writing one

- `Ctrl+Alt+Shift+R` on a selection, the "Add Claude Remark" intention under `Alt+Enter`, or the
  editor's right-click menu. Type a note, press Enter; Shift+Enter puts in a line break.
- `Ctrl+Alt+Shift+A` asks a question instead. That remark is stored marked as asking for an answer
  and published on the spot. See "Asking" below.
- `Cmd+Ctrl+Shift+Space` inside the box (`Ctrl+Alt+Shift+Space` off macOS) inserts a class name from
  the project. Deliberately not `Ctrl+Space` — see `CLASS_NAME_STROKE` for why.
- Add General Remark, in the tool window toolbar, for a remark about no file.

A gutter icon appears on the marked lines and follows the code as the file is edited.

⚠️ A remark written on the revision side of a diff is refused, with a sentence pointing at the
working copy, rather than stored with line numbers that describe a different revision. See
`design.md`, "Adding a Remark While Reading a Diff".

### Two places to mark: the editor and the rendered markdown preview

A source editor takes whole lines, or a phrase inside one line. The rendered markdown preview takes a
browser selection: right-click there and pick Add Claude Remark or Ask Claude, and the remark points
at the same characters in the `.md` source, not at the whole line.

The preview also shows what is already annotated. The paragraph, list item or heading a remark points
at gets a faint background, in one of two colours, so a question still waiting for an answer looks
different from a plain remark.

That half needs the Markdown plugin, which every JetBrains IDE bundles.
`org.intellij.plugins.markdown` is an optional dependency with its own config file, so with the
markdown plugin turned off only these two entry points and the highlighting are missing and
everything else works as before. ⚠️ A markdown id named in `plugin.xml` rather than in
`claude-remarks-markdown.xml` stops the whole plugin from loading when the markdown plugin is off,
with no dialog and no visible error — the tool window is simply not there. See `design.md`, "A Remark
on the Rendered Preview".

### The tool window

The tree splits into two top-level groups, **Open** and **Done**. A row is Done once it is `READ`
**or** once an answer has come back for it, and Open until then. Done starts collapsed with
everything inside it already expanded, so opening it is one click, and it stays open across a refresh
once a person opens it.

Inside each side the rows are grouped by file, with a General group above the files for a remark
about the whole change. Inside a file, Open is oldest first and Done is newest-processed first; the
file groups themselves stay in path order on both sides. A group called "Answers with no question"
sits above Open, and only when some answer's question is gone.

A row draws up to three lines of wrapped text, keeping any line breaks the person typed, with one
grey line under it carrying the position and any `(moved)`/`(orphaned…)` note. That grey line is
hidden when there is nothing to put in it. Resizing the tool window re-wraps every row.

**The icon column says two things at once.** A plain remark draws a note when written, a neutral tick
once handed over and a green tick once read. A question draws a question mark coloured by how far it
got: neutral pending, yellow published, green once an answer has come back.

Right-click a row for the menu the gutter icon and the tree share: Ask for an Answer, and Publish.
Nothing anywhere in the tree can be dragged.

⚠️ **A row under Done can still be picked up by Publish Unread.** Done's test is "`READ`, or has an
answer"; Publish Unread's is "not `READ`". The two deliberately disagree, and narrowing Publish
Unread to match would stop a batch nobody acknowledged ever being re-sent. The `DONE_KEY` KDoc says
so where somebody would go to "fix" it. See `design.md`, "Open, Done, and Rows That Wrap".

### Publishing

Publish Unread turns every remark that is not yet `READ` into one markdown prompt. It goes two places
at once: the clipboard, and one file under `~/.claude-remarks/<hash of the project's identity>.md`,
overwritten on every publish. A balloon says how many remarks across how many files. Publish Selected
does the same for the picked rows, which is what makes a published remark sendable again when the
paste went to the wrong window.

The project's identity is the git top level, or the project base path outside a git repository. One
function decides it, `projectIdentity` in `review/ReviewHandshake.kt`, and the handshake file and the
published file are both named from it.

The published file carries a five-line header — the marker, then `nonce:`, `published:`, `commit:`
and `remarks:` — then a blank line and the prompt. ⚠️ A reader addresses that header by line number
rather than searching for it, because a remark quoting `commit:` in its own text would otherwise read
as the header. The nonce is on line 2.

The prompt puts general remarks first, under their own `## General` heading with no code block at
all, then a section per remark carrying its line range, the short sha, and the code it points at.
Sub-line markers `⟦`/`⟧` wrap the selected characters inside the quoted code. A question's heading is
marked `— asks for an answer`, and every remark prints its `id:` on its own line, which is what a
session needs in order to answer one.

Clear Handed Over and Clear All archive to a history file in the IDE configuration directory before
they remove anything.

Nothing in the IDE ever waits for an agent. A session reads the published file when it is asked to,
and the IDE learns about it only when the acknowledgement arrives.

### Asking, and the answer coming back

`Ctrl+Alt+Shift+A` stores a remark marked as asking for an answer, then publishes on the spot. ⚠️ It
publishes **every question that is still open**, not only the new one: a second ask would otherwise
overwrite the first question's file and strand it. See `design.md`, "It publishes on the spot, and
that is the point".

An agent answers over `POST /api/claude-remarks/answer`, naming the batch's nonce, the remark's id
and the answer as markdown. The answer becomes its own stored record with a **fresh** anchor,
captured at the position the remark resolves to now, so it survives its question being cleared and
follows the code on its own. At most one answer per remark: a second one replaces the first, because
re-publishing mints a fresh nonce and a watcher compares nonces rather than content.

An answer nests under the question it answers, as a child row, so an answered question moves to Done
and takes its answer with it. It also gets a balloon icon on the gutter, and a popup rendering its
markdown when either row or icon is clicked.

### The endpoint

`review/ReviewRestService.kt` is a `RestService` on the IDE's own built-in HTTP server, at
`POST /api/claude-remarks/{fetch,published-read,answer,open}`.

- **`fetch`** reads the published file and returns its **content** in the response body, never a
  path, because a path on the IDE machine means nothing to an agent on another machine while a
  response body crosses a tunnel like any other. It changes nothing — no remark is marked read and no
  state moves — so it can be repeated as often as a poll needs and a lost response costs one retry. A
  response over one megabyte is refused rather than truncated, because a markdown prompt cut in the
  middle looks complete to a model reading it.
- **`published-read`** carries a batch's nonce and answers `ok`, `already-read` (naming who got there
  first) or `unknown-batch`. It is the only route that can produce `READ`.
- **`answer`** is above.
- **`open`** takes a project and a list of files, and opens one real diff over the ones with a local
  change, through `ShowDiffAction`, plus a plain editor for the rest. ⚠️ Its `opened` count is the
  paths that passed the filter, **not** editors that appeared: the opening hops to the EDT and the
  response is written before any of it happens.

A skill finds a running IDE through a handshake file the plugin writes under `~/.claude-remarks/`
when a project opens. It is found by repository path, never by scanning ports.

⚠️ **The security model is three independent conditions, and the platform's own Host check is not one
of them.** The built-in server only binds `127.0.0.1`, so a tunnel is the only way in from another
machine. `isHostTrusted` skips the platform check entirely, so that check is not what protects this
endpoint. The gate is the plugin's own token check, plus the refusal of any request carrying
`Origin` or `Referer`. See `design.md`, "The security rule: three independent conditions".

The whole endpoint is optional. With no skill installed and nothing listening the plugin behaves
exactly as it does with one — this is an addition beside the clipboard path, never a replacement for
it.

### The skill, and how it is installed

The skill is three files, shipped as ordinary plugin resources:
`src/main/resources/dev/sasha/clauderemarks/skill/{SKILL.md,watch-remarks.sh,remote-config.sh}`. One
copy and never two. They live there rather than under `docs/` because `docs/` never reaches the
plugin zip.

It has three modes, and only the first two run without being asked:

- **Open files in the IDE** — one `open` request, nothing started and nothing waited for.
- **Read remarks the person already published** — read the published file, acknowledge the batch,
  summarise it as two groups (things to change, questions to answer, with `none` written under an
  empty group), then act on it and answer the questions in it.
- **Listen for the next batch** — started only when a person asks for it in words. It claims whatever
  batch is already waiting when it starts, then watches for each new one and re-arms itself after
  every one. Several sessions may listen to one repository at once; the batch claim in the IDE
  decides who acts, and a session answered `already-read` acts on nothing and keeps listening.

Both reading modes work over an SSH tunnel when the IDE is on another machine. `remote-config.sh`
stores the four connection values — host, port, project path as the IDE machine sees it, and token —
once per repository, instead of the person retyping all four.

Settings → Tools → Claude Remarks lists every coding agent found on the machine and gives Claude Code
an Install / Reinstall button. A balloon on project open says the same thing when the installed copy
is missing, unstamped or a different version. Codex and Gemini are found and listed with no button,
and a sentence says why: each tool's own layout has to be read from its own documentation first, and
a guessed path writes a file nobody reads.

⚠️ **Seven decisions inside that install are the kind somebody undoes as a tidy-up.** It copies and
never symlinks; it refuses when any component below the home directory is a symlink; the version
stamp sits on line 2 of the installed `SKILL.md`; `SKILL.md` is written last; the executable bit is
set explicitly after the copy; detection keys on `~/.claude` rather than on `~/.claude/skills`; and
both hops back to the EDT pass `ModalityState.any()`. Every one is argued in `design.md`, "Shipping
the Skill Inside the Plugin". Read that section before changing any of it.

### The watcher script, and its two shapes

`watch-remarks.sh` polls for a new batch. `--stream` picks between two shapes:

- **Without `--stream`** it prints one batch on stdout and exits 0. That is the path every agent
  other than Claude Code takes, and it stays for that reason.
- **With `--stream`** it keeps polling and prints **one short line per batch** — the nonce, the path
  of a snapshot of that batch, and the claim's answer — never the batch body, because a harness turns
  each printed line into a notification and a monitor that emits too many events is stopped on its
  own.

⚠️ **In streaming mode a session reads the snapshot the line names, never the published file and
never a fresh `fetch`.** The claim goes out before the line is printed, so the batch is already
`READ` in the IDE; the published file is overwritten by the next publish; and Publish Unread selects
on "not `READ`". So a session reading the live file after a second publish would lose the first batch
for good, with its remarks sitting in Done looking handled. The script keeps the four newest
snapshots, names them from the nonce, and deletes none of them on exit — a watcher that dies at its
deadline must leave the last batch readable.

`--claim <base_url>` makes the watcher send the `published-read` acknowledgement itself, with
`--session <id>` and `--project <path>` beside it and the token in `CLAUDE_REMARKS_TOKEN`. It needs
`--stream`: without it stdout carries the batch and the session claims it, so a claim from the
watcher would take that batch out from under the session reading it. ⚠️ There are **five** outcome
words, not three — `ok`, `already-read <session>`, `unknown-batch`, `claim-failed <status>` and
`claim-failed http <code>`. `ok` means act, `already-read` means another session holds it, and
anything else means the IDE did not confirm, so act on the batch and send `published-read` yourself.
A failed claim prints the nonce anyway: claiming twice is recoverable, a batch nobody hears about is
not.

The script owns its own seen nonce, so nothing stale is ever typed back into a next launch line, and
the deadline restarts on every batch. `--owner <pid>` ends a watcher whose session is gone, with exit
3. The launch line goes through `perl … setsid()` so the watcher sits in a process group of its own:
`( nohup … & )` is **not** the same thing, because it keeps the shell's process group and one
group-wide signal still reaches it.

⚠️ **A watcher is stopped only by the pid on the first line of its own repository's `.watch` file**,
after checking that the pid is alive and that its command line names the same watched path. Never by
`pkill`, `killall` or a `ps | grep | kill` match on `watch-remarks.sh`. Every repository's watcher on
this machine runs a program with that name, and a blunt match stops all of them at once — which has
already happened, taking out watchers belonging to other sessions on other repositories. Streaming
changes nothing here: a streaming watcher writes the same pid file the exiting one does.

⚠️ **The token never goes in a command argument.** An argument sits in `curl`'s argv, which every
process on the machine can read out of `ps`, and the token is the only gate on the endpoint. Pass it
on stdin, through a `curl --config -` file. Never echo it, never log it, never commit it.

### Where the rest of the design is

`docs/claude/design.md` has a section for every piece named above, kept current with the code, and a
Known Issues list: real defects found by review and deliberately not fixed, each labelled with how
likely it is to happen and how bad it is if it does. Read the relevant section before changing the
code it describes.

## Still unchecked by hand

⚠️ **Almost nothing in this plugin has been watched running.** `docs/claude/hand-checks.md` is the
list of checks nobody has run — what needs a person in a running IDE, and which plan holds each list.
Read it before claiming anything here is proven; a green `./gradlew test` is not evidence for
anything that is drawn, clicked, or written in shell.

## Rules that must not break

1. **The anchoring module stays free of the platform.** `anchor/` is pure Kotlin, which is what
   keeps its tests running in milliseconds.

   ```bash
   grep -rn "com.intellij" src/main/kotlin/dev/sasha/clauderemarks/anchor/   # must find nothing
   ```

2. **The markdown renderer stays free of the platform too.** `render/PromptRenderer.kt` takes data
   classes and returns a string, the same shape as `anchor/`, for the same reason: no fixture,
   tests in milliseconds.

   ```bash
   grep -rn "com.intellij" src/main/kotlin/dev/sasha/clauderemarks/render/PromptRenderer.kt   # must find nothing
   ```

3. **`store/RemarkEdits.kt` holds the only twelve functions that touch a remark or an answer.**
   `RemarkStore`'s own mutators stay public, and `RemarkEdits.kt` sits in the same package, so
   nothing but this check keeps the claim true. A caller that reaches past the eleven that change
   stored data mutates the store without telling the gutter or the tool window to redraw. The grep
   allows through the two read-only methods by name, `all()` and `allAnswers()`, rather than listing
   the mutator names by hand: a hand-picked list has to be edited every time a mutator is added, and
   forgetting is silent — the guard keeps passing while it stops covering the new function.
   ⚠️ That has already happened here once: two mutators were added and the hand-picked six-name list
   of the day never saw either of them.
   The exempted names are readers, not mutators, which is why exempting a second
   one costs the guard nothing: `allAnswers()` is what the tree, the gutter and the resolver read the
   answers list through, the same way they read remarks through `all()`.

   ⚠️ **The count in the line above has to match what a reader finds by opening the file and
   counting, so fix it in the same commit that adds or removes a function there.** The eleven that
   change stored data are `addRemark`, `addGeneralRemark`, `editRemark`, `deleteRemark`,
   `markRemarksPublished`, `markRemarksRead`, `setRemarkAsksForAnswer`, `recordAnswer`,
   `deleteAnswer`, `clearHandedOverRemarks` and `clearAllRemarks`. The twelfth function in the file,
   `notifyRemarksChanged`, changes nothing itself. It is what every one of the eleven calls to
   announce the change. It is counted here too, because it is public and it lives in this file.
   `RemarksListener` is a type and `archive` is private, so neither counts.

   ```bash
   grep -rn "RemarkStore\.getInstance([^)]*)\." src/main/kotlin --include='*.kt' \
     | grep -v RemarkEdits.kt | grep -v "\.all()" | grep -v "\.allAnswers()"   # must be empty
   ```

   The glob has to be quoted. Unquoted, zsh expands `*.kt` itself before `grep` ever runs; with no
   match in the current directory it fails the whole line with "no matches found" before the
   pipeline starts, and that prints nothing. Nothing printed looks the same as an empty, passing
   result.
   Checked directly: `zsh -c` with the bare form fails that way, the quoted form runs.

   Test code is outside this one on purpose: fixture-backed test classes call
   `RemarkStore.getInstance(project).clear()` in `setUp` to clear the shared light-fixture project
   between test classes.

   **Two ways past it, named rather than patched.** The grep is a line-based text search, so it does
   not see either of these:

   ```kotlin
   val store = RemarkStore.getInstance(project)      // no dot after the call
   store.setAsksForAnswer(setOf(id), true)           // this line never says RemarkStore

   project.service<RemarkStore>().setAsksForAnswer(…) // never says getInstance either
   ```

   A wrapped chain hides the same way, and any line that also contains `.all()` is dropped whole by
   the third filter. Do not grow the pattern to chase these: the rule's own argument is that a guard
   which quietly stops covering things is the failure being fixed, and a cleverer regex is a guard
   nobody can reason about. Naming the holes is the honest version. If a bypass is ever found in a
   review, the fix is to move the call into `RemarkEdits.kt`, not to widen the grep.

4. **No code ever writes to a source file.** The whole point of the plugin is that the working tree
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

5. **The endpoint never touches the VFS, Swing, or `invokeAndWait`.** `execute` in
   `review/ReviewRestService.kt` runs on a netty IO thread, which is neither the EDT nor a thread
   holding any IntelliJ lock. That is the most fragile invariant in the whole plugin, and a paragraph
   in a plan file does not outlive the plan, so it gets a guard here instead.

   ```bash
   grep -rnE "invokeAndWait|projectRoot\(|FileEditorManager|VfsUtil|SwingUtilities" \
     src/main/kotlin/dev/sasha/clauderemarks/review/ReviewRestService.kt   # must be empty
   ```

   `toRealPath()` is deliberately fine inside `execute`: it is a plain `java.nio` filesystem call,
   never a call into the VFS. `projectRoot(project)` is not fine there, because it hands back a
   `VirtualFile` — which is why the file opening an `open` request causes lives in its own file,
   `review/OpenReviewFiles.kt`, and calls `invokeLater` rather than `invokeAndWait`. `execute`'s
   own KDoc deliberately does not spell out any of the five names above: this grep is line-based and
   cannot tell a comment from code, so an explanatory comment naming them would trip the guard it is
   explaining.

   **Every action's consequences live in another file, for exactly this rule.** `published-read`'s are
   in `review/PublishedAck.kt`, `answer`'s in `review/AnswerReceipt.kt`, and `open`'s in
   `review/OpenReviewFiles.kt` — that last one is the file that really does reach the VFS and the
   editor, and it hops there with `invokeLater`. Each handler's KDoc names its file by path and spells
   out none of the five forbidden symbols, not even to say they are absent.

   The fetch handler, `handleFetch`, also reads a file inside this class, through `readPublished`.
   Plain `java.nio` calls are what make that allowed, the same reason `toRealPath()` is allowed above.
   The comment trap is still live: the grep is line-based, so a comment naming any of the five
   forbidden symbols would trip it, even to say they are absent.

   **The grep never needs an edit when an action is added, relaxed or deleted, because it names the
   whole file.** A new action is covered the moment it is written. `handleAnswer` is the shape to
   copy: it does four things and nothing else — parses the body, checks the size cap, calls
   `matchProject`, calls one function in another file, and writes the status fields. Building an
   answer resolves a remark against a file, which reaches the VFS, so it could never have lived here.
   ⚠️ `handleOpen` is the one handler whose called function genuinely does touch the VFS and the
   editor, which is exactly why that call is one line here and every consequence of it is in
   `review/OpenReviewFiles.kt`.

6. **Only `store/RemarkEdits.kt` and `review/PublishedAck.kt` may call `markRemarksRead`.** `READ`
   means an agent said it read the remarks. There is exactly one route that can say so, and it is an
   answer to something the IDE itself minted: a `published-read` acknowledgement over
   `POST /api/claude-remarks/published-read`, keyed to a published batch's nonce and handled by
   `reportPublishedRead` in `review/PublishedAck.kt`. A publish is not that, however many times it runs
   — publishing, whether through the clipboard or the published file, can only ever move a remark to
   `PUBLISHED`. Letting anything else call `markRemarksRead` would let a copy or a publish quietly claim
   an agent read remarks it never saw.

   ```bash
   grep -rn "markRemarksRead(" src/main --include='*.kt' \
     | grep -v "store/RemarkEdits.kt" | grep -v "review/PublishedAck.kt"   # must be empty
   ```

   `reportPublishedRead` marks the batch's remarks read and shows one balloon, and nothing else.

   ⚠️ **One route is the design, not an accident.** If a second acknowledgement route is ever added,
   it cannot be independent of this one: two routes that both produce `READ` are two places that can
   disagree about a single batch, and the coupling that stops them disagreeing has to be written by
   hand in both. There were two once, and writing that coupling by hand is what it cost.

   **One way past it, named rather than patched.** The grep is a line-based text search on the
   literal substring `markRemarksRead(`. A method reference, `::markRemarksRead`, or an aliased
   import would reach the function from a third file without ever writing that substring, and the
   grep would not see it. Nothing exploits this today. Following guard 3's own argument above: the
   fix, if this is ever found in use, is to keep the two allowed callers as they are, not to grow the
   pattern chasing every way a call can be spelled.

7. **Only `store/RemarkEdits.kt` and `review/AnswerReceipt.kt` may create an answer.** An answer is a
   record that a Claude Code session answered a question. There is exactly one route that can say so:
   a `POST /api/claude-remarks/answer` request carrying the nonce of a published batch and the id of
   a remark that batch carried, handled by `reportAnswer` in `review/AnswerReceipt.kt`. Nothing else
   may mint one. Guard 6 makes the same argument about `markRemarksRead`.

   ```bash
   grep -rn "recordAnswer(" src/main --include='*.kt' \
     | grep -v "store/RemarkEdits.kt" | grep -v "review/AnswerReceipt.kt"   # must be empty
   ```

   The function is `recordAnswer` and not `addAnswer`, and the name is load-bearing: it upserts on
   `remarkId`, so a second answer to the same question replaces the first, and a function called
   `addAnswer` that silently replaced would be a lie in the one file this guard points at.

   **One way past it, named rather than patched.** The grep is a line-based search on the literal
   substring `recordAnswer(`. A method reference, `::recordAnswer`, or an aliased import would reach
   the function without ever writing it, and the grep would not see it. Nothing exploits this today.
   Following guard 3's own argument: the fix, if it is ever found in use, is to keep the two allowed
   callers as they are, not to grow the pattern chasing every way a call can be spelled.

   **There is deliberately no matching guard for `asksForAnswer`.** That flag is set by the person,
   from two places on purpose — the Ask Claude gesture and the toggle in `remarkChangeActions` — so
   there is nothing for a one-writer rule to protect. Guards 6 and 7 exist because `READ` and an
   answer are both claims about what an agent did, and only an agent's own message may make one. A
   person saying "I want an answer to this" is not a claim about anybody else.

Every command above must come back empty.

## Project structure

```
src/main/kotlin/dev/sasha/clauderemarks/
  anchor/Anchoring.kt              hashing, capture, the two-pass resolve, plus phraseAt/findPhrase/
                                   resolveWithPhrase for the sub-line phrase. No platform
                                   imports.
  anchor/SubLineRange.kt           hasSubLineRange and positionLabel: the one place that decides
                                   whether a column pair is a real sub-line range, and the one place
                                   that writes a position down ("9-9", "9:12-38", "9:12-11:5").
                                   Asked by phraseAt, by markersValid in render/PromptRenderer.kt,
                                   by the tree row and by the history heading. Pure Kotlin, so the
                                   renderer can import it without breaking rule 2 above
  model/RemarkState.kt             the persisted record, RemarkStatus, phrase (the sub-line text
                                   between startColumn and endColumn), asksForAnswer, and readAt,
                                   which is 0 for a remark never read and for every remark stored
                                   before that field existed
  model/AnswerState.kt             the answer record: remarkId, the question copied at
                                   answer time, the markdown, answeredAt, and its own nine anchor
                                   fields. Its KDoc argues why it does not share a superclass with
                                   RemarkState
  store/RemarkStore.kt             @Service project component, state in workspace.xml. Two lists,
                                   remarks and answers, both @get:XCollection
  store/RemarkEdits.kt             the eleven mutation functions plus notifyRemarksChanged (twelve
                                   in all), the REMARKS_CHANGED topic. markRemarksRead is what
                                   reaches RemarkStore.markRead, which stamps readAt
  store/RemarkResolver.kt          projectRoot, resolveAll, anchorOf, and isAboutNoFile, which
                                   resolveOne checks before treating a remark with no path as
                                   itself rather than as an orphan. Also the pure
                                   StoredAnchor value type and resolveStored, which resolveOne and
                                   resolveAnswers both go through, so the file lookup, the Document
                                   lookup, the no-file case and the five refusals are written once
  store/RemarkTarget.kt            relativePathOf, remarkTargetProblem, the diff fallback, and the
                                   refusal for a remark on the revision side of a diff.
                                   fileTargetProblem is the file-only half of that refusal,
                                   split out so the preview's own action can reuse it without a diff
                                   or an editor
  store/ContextFormat.kt           joinContext/splitContext, how context lines are stored
  store/GitHead.kt                 headCommit and gitTopLevel, both off one walk up the tree for
                                   .git. Reads .git directly, no platform import, no Git4Idea
  store/RemarkHistory.kt           historyFile, appendToHistory, renderHistory: the archive, with
                                   a phrase line under a sub-line heading and a plain "(general)"
                                   heading for a remark about no file, and an
                                   "### answers" subsection whose markdown is indented so a heading
                                   or a fence inside an answer cannot restructure the document
  ui/RemarkInputPanel.kt           the popup's panel, the Enter/Shift+Enter keys, CLASS_NAME_STROKE
                                   to insert a class name. It returns a plain String
  ui/RemarkActions.kt              remarkChangeActions: the Ask for an Answer toggle and Publish,
                                   shared by the gutter icon and the tree. Two entries, no more
  ui/RemarkStatusLook.kt           RemarkStatusLook: the icon and the text attributes for a row,
                                   shared by the gutter icon and the tree the same way RemarkActions.kt
                                   is: a status's look is decided in exactly one place, because it
                                   was once decided in two and they drifted. icon() takes three facts
                                   — status, asksForAnswer, hasAnswer — and picks from two tracks of
                                   three icons; its KDoc argues the two decisions nobody should "fix"
  ui/RemarkIcons.kt                the three question-mark icons the plugin ships itself,
                                   loaded through IconLoader.getIcon(absolute path, class). A wrong
                                   path fails only at runtime, which is what RemarkIconsTest catches
  ui/ClassNameInsert.kt            projectClassNames, chooseClassName: the class-name chooser the
                                   input popup opens on Cmd+Ctrl+Shift+Space (Ctrl+Alt+Shift+Space
                                   off macOS — NOT Ctrl+Space, see CLASS_NAME_STROKE for why)
  ui/RemarksTree.kt                node building: an "Answers with no question" group at the very top
                                   when any answer's question is gone, then the two sides, OPEN_KEY
                                   and DONE_KEY, each holding a General group for a remark about no file
                                   and then files, with an answer nested under its own question.
                                   RemarkNode.processedAt is what Done orders by, the later of readAt
                                   and the nested answer's answeredAt, falling back to createdAt. Every group inside a side is keyed with its side's
                                   prefix. Also RemarkTreeRenderer, the stacked-line cell renderer:
                                   MAX_TEXT_LINES text rows plus a grey metadataLine below them.
                                   leavesOf recurses into a RemarkNode, which is what makes Delete on
                                   a question take its answer too. A row carries no text label
                                   saying it asks and none saying read or published: the icon, the
                                   colour and the Done group say all of that already
  ui/WrapText.kt                   wrapToLines and elideToWidth: the pure word-break the
                                   renderer wraps a row with, and the cut-short-but-never-re-flowed
                                   one the grey metadata line uses. NO imports at all — they take a
                                   widthOf measurer instead of a FontMetrics, which is what keeps the
                                   file free of java.awt and com.intellij and its tests running in
                                   milliseconds. The platform's own MultiLineTodoRenderer never wraps,
                                   so there is nothing to fall back on here
  ui/RemarksToolWindowFactory.kt   RemarksPanel: the tree, the toolbar (six buttons, each with its
                                   own description), self-refresh on REMARKS_CHANGED.
                                   setRowHeight(0) — the whole variable-row-height
                                   mechanism, cited from TodoPanel.java:251 — and expandAll walks the
                                   model rather than the rows, so a node inside a shut Done is
                                   expanded too, shutting Done again at the end with collapsePaths. It
                                   takes a keepDoneOpen flag read before the rebuild, since
                                   collapsedGroups cannot tell "the person opened Done" from "Done is
                                   new". A ComponentListener on the scroll pane's viewport restarts a
                                   one-shot 150 ms Timer that re-wraps every row for the new width,
                                   through nodeStructureChanged plus the same three restores.
                                   Nothing sits above the tree. deleteSelected asks
                                   selectionHidesRows whether the selection stands for a row that is
                                   off screen, rather than comparing two counts: a group row always
                                   does, and a question row does when it has an answer under it and
                                   is itself collapsed.
                                   It also resolves answers, deletes answer rows, and
                                   on an answer row navigates to the code AND then opens the popup.
                                   navigateTo is the one place that opens a file at a line, shared by
                                   the remark row and the answer row
  ui/AnswerPopup.kt                showAnswerPopup: DocMarkdownToHtmlConverter inside a
                                   ReadAction.nonBlocking, then a JBHtmlPane in a JBScrollPane on the
                                   EDT. Disposer.register(popup, pane) is not optional — JBHtmlPane
                                   is Disposable and nothing else in this plugin is
  action/AddRemarkAction.kt        the shortcut / popup-menu entry point, selectedLines(), and
                                   openGeneralRemarkInput, the tool window's entry point for a
                                   remark about no file
  action/AskClaudeAction.kt        the Ctrl+Alt+Shift+A gesture and AskClaudeIntention beside it:
                                   the same input popup, then addRemark with
                                   asksForAnswer = true, then publishRemarks on that one id
  action/AddRemarkIntention.kt     the Alt+Enter entry point
  action/AddPreviewRemarkAction.kt the entry point in the rendered markdown preview's right-click
                                   menu. Reads only what PreviewSelectionService already
                                   holds, asks the page nothing, and refuses with a dialog rather
                                   than a hint, since there is no editor here to put a hint in.
                                   updatePreviewRemarkEntryPoint and openPreviewRemarkInput live
                                   here and hold every check both preview actions make
  action/AskClaudePreviewAction.kt the preview's Ask Claude, beside the action above. Same
                                   pair one level down as AskClaudeAction is to AddRemarkAction in
                                   the editor: it supplies the popup title and calls askClaude, and
                                   nothing else differs
  action/PublishRemarks.kt         publishRemarks(project, ids), the whole publish pipeline, plus the
                                   Tools-menu action (PublishUnreadRemarksAction) that calls it without
                                   the tool window
  editor/RemarkGutterIcon.kt       the placement record, the tooltip, the gutter icon renderer, plus
                                   AnswerPlacement, answerTooltipFor and
                                   AnswerGutterIconRenderer, whose equals/hashCode include the
                                   markdown because that is what its click opens
  editor/RemarkGutter.kt           the project service that keeps gutter icons in step
  editor/RemarkGutterStartup.kt    the ProjectActivity that starts RemarkGutter and
                                   ReviewHandshakeService, and calls
                                   notifySkillInstallIfNeeded inline after both — execute is a
                                   suspend function already off the EDT, so its filesystem reads
                                   need no hop of their own
  settings/RemarkSettings.kt       the app-level service, the default prompt header, and
                                   skillInstallPromptDismissed, the persisted "don't ask
                                   again" for the skill-install balloon. It roams through Settings
                                   Sync with everything else in this class, so a dismissal on one
                                   machine silences the other one too
  settings/RemarkSettingsConfigurable.kt
                                   the settings page. It also holds the Claude
                                   Remarks skill group: one row per detected harness, detection and
                                   the copy both on AppExecutorUtil.getAppExecutorService() with
                                   the labels filled back in through invokeLater, and no ReadAction
                                   anywhere, because nothing here touches PSI, a Document or the VFS.
                                   ⚠️ Both invokeLater calls pass ModalityState.any(): the settings
                                   dialog is modal, and a bare invokeLater is skipped outright, which
                                   leaves every row hidden and the Install button unreachable
  skill/SkillInstall.kt            the pure half of the install: SKILL_FILES,
                                   claudeSkillDir (the one place ~/.claude/skills/claude-remarks is
                                   spelled out), stampVersion, stampedVersionOf, detectHarnesses,
                                   skillPresence and installSkill, which returns null or a sentence
                                   the way store/RemarkTarget.kt's problem functions do. NO
                                   com.intellij import — the resource reader is a parameter, which is
                                   what keeps the classloader out and lets a test feed fake contents.
                                   Detection keys on ~/.claude, the symlink refusal covers every
                                   component appended below the home directory rather than only the
                                   leaf, and SKILL.md is written last so a partial install never
                                   leaves a stamp behind
  skill/BundledSkillVersion.kt     bundledPluginVersion(): the plugin's own version, through
                                   PluginManager.getPluginByClass. Its own file precisely because
                                   that is a com.intellij import and SkillInstall.kt may carry none
  skill/SkillRowText.kt            skillRowText: the settings row's status sentence and its button
                                   label, pure so every combination is testable with no UI fixture
  skill/SkillInstallNotification.kt
                                   shouldNotifySkillInstall (pure, and it never fires for a symlink),
                                   skillInstallNotificationText (pure, one sentence per firing state
                                   so the balloon and the settings row can never disagree about the
                                   same machine) and notifySkillInstallIfNeeded, the balloon with
                                   Install, Settings and Don't ask again. A top-level AtomicBoolean
                                   is what makes it at most one balloon per IDE run
  render/PromptRenderer.kt         pure Kotlin, zero platform imports. Remarks to markdown, general
                                   remarks first under their own heading and with no code block.
                                   PROMPT_NOTES is the
                                   text appended under the editable header: what the asks marker and
                                   the id: line mean, the commit paragraph, the ⟦/⟧ paragraph
  render/PromptPayload.kt          collectForPrompt and clipboardPayload
  preview/PreviewSelection.kt      SourceRange, PreviewSelection, parseSelectionMessage and
                                   narrowToSelection: what a selection in the rendered markdown
                                   preview is allowed to say, and how it becomes a character range in
                                   the .md source. No platform import, so its tests need no fixture.
  preview/PreviewSelectionService.kt
                                   @Service PROJECT, in memory only: the last selection any preview
                                   reported, with the file url beside it so a reader can compare
  preview/PreviewHighlights.kt     which remarks the preview should highlight and where each starts
                                   in the .md source. PreviewHighlight, HighlightCandidate,
                                   highlightsFor and toJson. NO com.intellij import: it takes plain
                                   values, the same argument anchor/ and PreviewSelection.kt make,
                                   which is why its tests need no fixture. It drops an orphan, a
                                   remark about no file and a remark about another file
  preview/PreviewRemarkExtension.kt
                                   the browser half: the MarkdownBrowserPreviewExtension, its
                                   Provider and its ResourceProvider. Subscribes to one pipe message,
                                   parses it on the browser's callback thread, then hops to the EDT
                                   to narrow the range against the Document and store it. It also
                                   sends a message the other way, on creation and on every
                                   REMARKS_CHANGED, resolving the remarks inside a
                                   ReadAction.nonBlocking and disconnecting that subscription in
                                   dispose. It declares the stylesheet through styles as well as the
                                   script through scripts, and serves both from the same
                                   ResourceProvider
  review/ReviewHandshake.kt        projectIdentity (the ONE function that decides what the plugin
                                   hashes and compares as "this project": the git top level, or the
                                   base path outside a repository), projectHash, handshakeName,
                                   renderHandshake, writeHandshake/deleteHandshake,
                                   the per-run ReviewToken, and ReviewHandshakeService (@Service
                                   PROJECT, Disposable) — the file a skill reads to find this IDE
  review/AtomicWrite.kt            atomicWriteString: temp file beside the target, then rename
  review/PublishedRemarks.kt       PublishedHeader (nonce, publishedAt, commit, remarks) with
                                   render()/publishedHeaderOf(), PUBLISHED_MARKER, publishedName,
                                   writePublished: the one file a publish writes under handshakeDir().
                                   Nothing is sanitised on the way out: a control character
                                   in commit shifts the header, publishedHeaderOf reads back null and
                                   the fetch answers failed, which is the loud answer this file wants
  review/PublishedAck.kt           PublishedAckOutcome, PublishedBatch, PublishedAckAnswer,
                                   PublishedBatchService (@Service PROJECT, in memory only, the last
                                   sixteen published batches, @Synchronized record/acknowledge) and
                                   reportPublishedRead: the ONLY acknowledgement route, keyed to a
                                   published batch's nonce. Also BatchLookup and batchCarries, the
                                   non-destructive read the
                                   answer action asks "did this batch carry this remark" with — it
                                   never stamps readBy
  review/AnswerReceipt.kt          reportAnswer and buildAnswer: everything the answer
                                   action causes, kept out of ReviewRestService.kt by rule 5 the way
                                   PublishedAck.kt keeps published-read's consequences out. It
                                   resolves the remark and captures a FRESH anchor inside a
                                   ReadAction.nonBlocking, then calls recordAnswer and the balloon on
                                   the EDT
  review/ReviewRestService.kt      the RestService at
                                   POST /api/claude-remarks/{fetch,published-read,answer,open}:
                                   isHostTrusted, execute (dispatches on the sub-path), handleAnswer
                                   with MAX_ANSWER_BYTES, readPublished/PublishedRead (the file read
                                   back with a size cap), handlePublishedRead, handleOpen, and the
                                   pure requestIsAllowed/projectForPath helpers. Rule 5 above governs
                                   this file specifically. Four actions and no more
  review/OpenReviewFiles.kt        the only file in review/ that touches the VFS or the editor —
                                   opens a real diff over the files that have a local change,
                                   through ShowDiffAction, and a plain editor for the rest
src/main/resources/META-INF/plugin.xml           declares two hard dependencies:
                                                  com.intellij.modules.platform and
                                                  com.intellij.modules.vcs, for ShowDiffAction, which
                                                  lives in a module jar
                                                  (lib/modules/intellij.platform.vcs.impl.jar), not
                                                  in app.jar. It also declares one
                                                  optional dependency, org.intellij.plugins.markdown,
                                                  naming the config file below
src/main/resources/META-INF/claude-remarks-markdown.xml
                                                  everything that cannot exist without the markdown
                                                  plugin, skipped whole when it is disabled: the
                                                  browserPreviewExtensionProvider registration and,
                                                  in Markdown.PreviewGroup, the two preview actions
                                                  ClaudeRemarks.AddPreviewRemark and
                                                  ClaudeRemarks.AskClaudePreview. Neither id is
                                                  pinned by ActionIdsTest, because the test fixture
                                                  never loads the markdown plugin
src/main/resources/dev/sasha/clauderemarks/icons/question{Pending,Published,Answered}.svg
                                                  plus a _dark sibling for each: the three question
                                                  marks a question's row and its gutter icon draw.
                                                  The shape is the platform's own
                                                  expui/general/questionMark.svg, recoloured; its own
                                                  colours are NOT reused, because that file's dark
                                                  variant is drawn on a chip rather than a tree row
src/main/resources/dev/sasha/clauderemarks/preview/claude-remarks-preview.js
                                                  the script injected into the preview page. Listens
                                                  for selectionchange, walks up to the nearest element
                                                  carrying the position attribute (whose name it reads
                                                  from the page's own meta tag), and posts four
                                                  offsets plus the highlighted text. It also
                                                  subscribes to the highlight message, marks the
                                                  innermost element covering each offset, and
                                                  re-applies the last list on every DOM rebuild
                                                  through a MutationObserver
src/main/resources/dev/sasha/clauderemarks/preview/claude-remarks-preview.css
                                                  the two highlight classes, served as an
                                                  ordinary stylesheet because
                                                  MarkdownBrowserPreviewExtension declares a styles
                                                  list. Both colours are alpha-blended over whatever
                                                  background is there, since this page defines no
                                                  theme colour variables a stylesheet could read
src/main/resources/dev/sasha/clauderemarks/skill/{SKILL.md,watch-remarks.sh,remote-config.sh}
                                                  the Claude Code skill itself. It sits under
                                                  src/main/resources/ so that it reaches the plugin
                                                  zip — docs/ never does. ONE copy, never two. They
                                                  are ordinary resources: they need no line in
                                                  build.gradle.kts and none in plugin.xml.
                                                  ⚠️ Nothing enumerates this directory — the
                                                  three names live in SkillInstall.SKILL_FILES, and
                                                  a fourth file added here without being added
                                                  there is silently not installed
src/main/resources/intentionDescriptions/AddRemarkIntention/description.html
src/main/resources/intentionDescriptions/AskClaudeIntention/description.html
src/test/kotlin/dev/sasha/clauderemarks/...   mirrors the same packages
```

## Toolchain

- Kotlin 2.1.20, `jvmToolchain(21)`.
- IntelliJ Platform Gradle Plugin 2.18.1, `intellijIdeaCommunity("2025.2")`, `sinceBuild = "252"`.
- Gradle wrapper 9.1.0 (the platform plugin needs Gradle 9). The foojay resolver in
  `settings.gradle.kts` downloads a JDK 21 on the first build, so any JDK 17-25 can start it.
- `kotlin.stdlib.default.dependency = false` in `gradle.properties`: the IDE ships its own Kotlin
  stdlib, and bundling a second copy in the plugin zip is a known source of conflicts.
- `org.intellij.plugins.markdown` is an optional dependency, declared in `plugin.xml` with
  `config-file="claude-remarks-markdown.xml"`, so the plugin still loads with it disabled. Because of
  it, `build.gradle.kts`'s `pluginVerification` subtracts `EXPERIMENTAL_API_USAGES` from
  `verifyPlugin`'s failure level: the three `MarkdownHtmlPanel` getters the preview extension calls
  all carry `@ApiStatus.Experimental`, and there is no non-experimental route to a preview's
  `BrowserPipe`. ⚠️ That subtraction has a **second** reason, and both have to go
  before the line can: `ui/AnswerPopup.kt` builds a `JBHtmlPane`, which is experimental at class
  level. Removing the markdown preview later does not make the subtraction removable — check the
  popup too.
- `INTERNAL_API_USAGES` is **not** subtracted, and must stay that way. The verifier reports no
  internal API usage at all, so a new one fails the build rather than passing unnoticed. If a future
  change needs an internal API, weigh it on its own rather than reviving the subtraction.
- `com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter` and `com.intellij.ui.components.JBHtmlPane`
  are both in `lib/app-client.jar`, already on the compile classpath. The answer popup needed no
  change to the `dependencies` block and no `bundledModule` line.
- **The plugin ships icons of its own**, under
  `src/main/resources/dev/sasha/clauderemarks/icons/`, and `ui/RemarkIcons.kt` loads each with
  `IconLoader.getIcon(path, aClass)`. The path is absolute and starts with a slash. The `_dark` suffix
  goes before the extension and `IconLoader` finds the dark variant without being told, so only the
  light name appears in Kotlin. Nothing in `build.gradle.kts` or `plugin.xml` had to change for them —
  they are ordinary resources. ⚠️ A wrong path is not a compile error; it is a missing icon at runtime,
  which is what `RemarkIconsTest` exists to catch.

## Reading the platform

A checkout of IntelliJ Community sits at `~/dev/oss/intellij-community`, shallow, pinned to tag
`idea/2025.2.6.3`. Use it. Grepping it is far cheaper than unzipping jars, and it answers a kind of
question the jars cannot.

Which source to trust for which question:

- **What does this actually do?** Read the checkout. `javap` gives signatures and nothing else, so
  any question about behaviour is unanswerable from the jars. A real example from this project: we
  needed to know whether a diff pane's editor carries the real project file. Guessing said yes.
  `DiffUtil.configureEditor` says no — it sets the editor's file from
  `FileDocumentManager.getFile(content.getDocument())`, the same call that already fails there. Only
  the source settled it.
- **Does this method exist, with this signature?** Check the jars, with `javap` against the exact
  build under `~/.gradle/caches/9.1.0/transforms/`. ⚠️ **More than one transform directory exists and
  they do not hold the same classes**, so do not hard-code one path: glob every transform, build one
  classpath out of all the jars, and search that. A `javap` that finds nothing is not evidence the
  method is absent — it may be evidence you looked in the wrong directory.

  ```sh
  cp=$(ls ~/.gradle/caches/9.1.0/transforms/*/transformed/ideaIC-*/lib/*.jar | tr '\n' ':')
  javap -cp "$cp" com.intellij.ide.plugins.PluginManager | grep getPluginByClass
  ```

  The checkout is tag `2025.2.6.3` and we compile against `2025.2` (build 252.28539.97). Same 252
  line, so this is stable in practice, but the jars are what the code is actually compiled against.

The exact tag `idea/252.28539.97` does exist upstream. It would not fetch into the shallow clone,
and re-cloning for a patch-level difference costs another 1.9G, so the mismatch is accepted and
written down here rather than silently carried.

## Commands

```bash
./gradlew test                              # the whole suite
./gradlew build                             # compile, test, assemble
./gradlew buildPlugin                       # build/distributions/claude-remarks-<version>.zip
./gradlew verifyPluginProjectConfiguration  # after any plugin.xml or build.gradle.kts change
./gradlew verifyPlugin                      # compatibility report against the target IDE
```

Do not run `./gradlew runIde` from an agent session: it starts an interactive sandbox IDE that
never exits on its own.

## Testing

Anchoring (`AnchoringTest`, including `phraseAt`, `findPhrase` and `resolveWithPhrase`),
`SubLineRangeTest` (the shared rule: one line needs the end column after the start, across lines the
two columns are never ordered against each other, and the three shapes `positionLabel` prints),
storage round-trips, the resolver helpers (including `isAboutNoFile`), the tree's node-building
(including the General group, and the answer nesting: a matched answer is its
question's child, an answer naming nothing is in the top-level "Answers with no question" group, that
group is absent when every answer has a question, a nested row carries no file name and a top-level one
does, and an answer naming a remark that produced no node lands in the top-level group rather than
disappearing; and the Open/Done split: a `READ` remark is under Done, an answered
question is under Done with its answer still nested, `PENDING` and `PUBLISHED` are under Open, Done
orders by `readAt`, a remark with `readAt == 0` falls back to `createdAt`, two Done rows sharing a
processed time fall back to the resolved line, a question answered but never acknowledged is processed
when its answer came back, and an empty side produces
no group at all), `WrapTextTest` (the word-break, with a fixed width per character so the
arithmetic is exact: a short text staying on one line, a break on a space, a run of spaces collapsing,
a single word longer than the width breaking mid-word, a `\n` starting a new line, more lines than the
cap truncating with an ellipsis, empty text giving one empty line, the space in front of an ellipsis
being trimmed, `maxLines` below one still giving a line, a 20,000-character word being measured a
bounded number of times rather than a number that grows with it, and `elideToWidth` leaving text that
fits exactly as it was written), the markdown renderer (including the General section, rendered
first with no code block), the settings round trip, `GitHeadTest` (reads real `.git` directories built on disk for
the test, including a worktree, a detached HEAD and packed refs, plus `gitTopLevel` for a directory
below the repository root, for a worktree, and with no repository at all), `RemarkHistoryTest` (the
archive's markdown rendering, and the write itself against a temp file, plus the
sub-line position shape in the heading and the phrase written indented under it, and a general
remark's `(general)` heading with no line numbers), `AtomicWriteTest` (the
temp file lands beside the target, not in the system temp directory, and no temp file is left
behind), `ReviewHandshakeTest` (the name, the rendering, the escaping, the owner-only
permissions, and `projectIdentity`: the repository for a project opened below its root, the base
path with no repository, and null for a base path that is missing or unusable), and
`ReviewRequestTest` (the pure `requestIsAllowed` and `projectForPath`, plus `readPublished` and its
size cap), `PreviewSelectionTest`
(`parseSelectionMessage`'s refusals and `narrowToSelection`'s search, including the
cross-line case and the malformed-message case), `PreviewRemarkProblemTest` (the
pure `previewRemarkProblem`: no stored selection, a stored selection in another preview, and one that
matches), and `PreviewHighlightsTest` (the pure `highlightsFor` and `toJson`: a plain remark
becoming an offset, an unanswered question becoming the question kind, an answered question falling
back to the plain kind, the three exclusions one test each — orphaned, about no file, about another
file — several remarks each keeping their own offset, a start line past the end of the source being
excluded rather than throwing, three tests on the column being clamped to its own line, and the two
`toJson` shapes), and the four skill classes — `SkillResourceTest` (all three skill resources
resolve on the classpath and are not empty, asking production for the path rather than writing a copy
of it, the same argument `RemarkIconsTest` makes), `SkillInstallTest` (the stamp on both branches
⚠️ including CRLF and a byte-order mark, the pin against the real `SKILL.md` that the stamp never
lands inside the `description:` block scalar, `stampedVersionOf`'s three answers and its five-line
cutoff, detection ⚠️ including a bare `~/.claude` with no `skills/` inside it, the install's success
path with both scripts executable, ⚠️ that `SKILL.md` is written **last** and that a later file
failing leaves no stamp behind, and the symlink refusals ⚠️ for a symlinked `~/.claude` and a
symlinked `skills/` as well as for the leaf and the three files, each asserting nothing at the far end
changed, plus one that an ordinary temporary directory is **not** refused, which is what fails if
somebody replaces the check with a `toRealPath()` comparison), `SkillRowTextTest` (every branch of
the settings row's sentence and button label) and `SkillInstallNotificationTest`
(`shouldNotifySkillInstall` across every combination, and ⚠️ that the balloon's sentence never says
"not installed" about a machine that has a copy) — are plain JUnit tests with
no fixture, so they run in milliseconds. The rest
need a light IDE fixture
(`BasePlatformTestCase`, which needs `testFramework(TestFrameworkType.Platform)` in
`build.gradle.kts`) and are slower, because each goes through a real project service, a real
`Document`, or a real markup model: `RemarkStoreServiceTest`, `ResolveAllTest` (stored remarks
resolved against real files, including a path that tries to climb out of the project, and that a
resolved row carries the phrase's refreshed columns),
`SelectedLinesTest` (the selection line math against a real `Document`), `RemarkEditsTest` (the
eleven mutation functions publish `REMARKS_CHANGED`; that `recordAnswer` upserts
on the remark id rather than appending, that `deleteAnswer` is keyed on the answer's own id, that
`clearAllRemarks` archives and clears both lists while `clearHandedOverRemarks` leaves answers
alone; and that `markRemarksRead` stamps `readAt` and that a second mark leaves an
already-set stamp alone, so re-publishing and re-acknowledging cannot make a row jump to the top of
Done), the key-binding half of
`RemarkInputPanelTest`, `AddRemarkActionTest`, `ActionIdsTest` (pins the two action ids and the
tool window's derived activation id, so a rename is caught rather than silently breaking every
`.ideavimrc`), `RemarkActionsTest` (the shared menu offers Ask for an Answer and Publish in that
order, and acts on the ids it is given at press time, not at build time; the toggle
is off when there is nothing to act on and flips across several ids at once), `ClassNameInsertTest`
(inserting a class name at the caret, and over a selection; its two extension-point tests assert
that the contributed names are present, that a repeated name comes back once and that the list is
sorted, rather than comparing the whole list — the light fixture also answers the Kotlin builtin
names now, and the old exact comparison broke on that, which is fixture drift and not a plugin
change), `DiffRemarkTargetTest` (adding a remark from a diff pane: a real
`DiffContentFactory` content standing in for a VCS revision, since a light fixture cannot build a
diff viewer), the renderer-equality half of `RemarkGutterIconTest`, `RemarkGutterTest` (the gutter
service, including that a general remark produces no placement anywhere), `RemarksPanelTest` (the
tool window panel: every group ends up expanded, the selection survives a rebuild,
and the Add General Remark button is offered and enabled with no selection and no editor open; that
Done starts collapsed while Open is expanded, that a Done a person opened by
hand is still open after a refresh, and ⚠️ that expanding **only** the Done row shows the file group,
the question and its nested answer with no further clicks — the one that pins `expandAll` walking the
model, since a row walk can never reach inside a collapsed node. ⚠️ Three refresh-based tests go through a `refreshAndSettle`
helper that asserts the tree root object actually changed: "the tree looks the same after a refresh"
passes just as happily when the async `ReadAction` never finished, and that vacuity would hide the
whole `keepDoneOpen` path), `RemarkTreeRendererTest` (fixture-backed because every
assertion needs a real `SimpleColoredComponent`, a real `Tree` and `UIUtil`'s theme colours: how many
line components a row drew, what each carries, and whether the grey metadata row was drawn at all —
a general remark draws none and a positioned remark does; plus the wrap width losing one indent per
level of depth and falling back to its floor at width zero, the metadata line being cut short with an
ellipsis rather than overflowing, a row's accessible name being the text it drew, and
`selectionAdjusted` rewriting a grey fragment's colour on a focused selected row. ⚠️ That last one
sets `Tree.forceFocusedSelectionForeground` in `UIManager` and puts it back in `tearDown`: a test
fixture loads no theme, so reading the key instead would silently assert nothing),
`NavigationLineBaseTest` (pins `OpenFileDescriptor`'s
0-based line argument), the collector half of `PromptPayloadTest`, `PublishRemarksTest` (that a
publish with no ids takes every remark that is not `READ`, not only `PENDING` ones),
`PublishedRemarksTest` (the published file's name and write, `PublishedHeader`'s **five**-line
`render()`/`publishedHeaderOf()`
round trip, that a four-line text reads back null, that a missing prefix on any of lines 2 to 5 or a
non-integer `remarks:` reads back null, and that a `commit` carrying a newline shifts the header so
`publishedHeaderOf` reads back null rather than reporting a commit nobody has),
`PublishedAckTest` (fixture-backed: an acknowledgement of a recorded batch marks
its remarks read and answers `ok`; a second session, or the same session twice, gets `already-read`
naming who got there first; an unknown nonce answers `unknown-batch`; only the last sixteen batches
are remembered; and an acknowledgement marks only its own batch),
`ReviewEndpointSmokeTest` (the one test that calls `ReviewRestService.execute` itself, through a
real `EmbeddedChannel`, so the response actually carries a body. It covers all four actions
and every status each of them can answer: `fetch`'s six — `ready` with the whole prompt, `no-review`
with nothing published, `too-large`, `unknown-project`, `bad-request`, and `failed` with a test each for
its two causes — plus that a fetch marks nothing read; `published-read`'s five — `ok`, `already-read`
naming who got there first, `unknown-batch`, `unknown-project`, `bad-request`; `answer`'s six — `ok`,
`unknown-batch`, `unknown-remark`, `too-large`, `unknown-project`, `bad-request` — plus a second answer
for the same remark and an answer for a remark that was never marked as asking, which is deliberately
accepted and pinned so that decision cannot be quietly reversed; and `open`'s three — `ok` with the
accepted count, `ok` with `opened: 0` for an empty list, `unknown-project`, and `bad-request` with a
detail for a missing project, plus ⚠️ one test that a real file created with `fileUnderProjectRoot`
actually appears in `FileEditorManager.openFiles` after the EDT queue drains. That last one is the only
test in the class that says the action *does* anything: the other three read the response, and the
`opened` count is computed by the path filter whatever else happens, so all three passed with the
`openReviewFiles` call deleted outright. Also the unknown-action refusal. ⚠️ When an action's set of
statuses changes, walk that handler's own `writer.name("status")` call sites and confirm this class
still covers every one of them — a status that loses its case leaves no failure behind),
`OpenReviewFilesTest` (the string-only half of the path
filter: absolute paths and `..` segments are dropped, plus a fixture-backed class for the
diff-or-editor decision, since a light fixture project has no VCS root and every file takes the
plain-editor branch), `PreviewSelectionServiceTest` (fixture-backed for the same
reason: `remember`, `forget` and `current` on the project-level service that holds the preview's last
selection), `PreviewRemarkExtensionTest` (fixture-backed because the push resolves remarks
against a real `Document`: nothing is pushed from the constructor, an already-annotated file is
highlighted as soon as the page posts `documentReady`, that handler answers `true` so later
subscribers still run, a remark added afterwards pushes again, typing in the source pushes again, an
unanswered question pushes as a question and an answered one as a plain remark, and `dispose` stops
all of it. ⚠️ It fakes `MarkdownHtmlPanel` and `BrowserPipe` as plain Kotlin objects rather than
treating the push as untestable, which is what makes the `REMARKS_CHANGED` leak testable at all),
`PreviewStoreTest` (fixture-backed: what each preview entry point does with the typed text,
which is the only place the two differ — Add Claude Remark stores a plain pending remark, Ask Claude
marks it as asking and publishes it, and a second ask carries every question still waiting), and
`RemarkStatusLookTest` (fixture-backed, because loading an icon
needs the platform: the six rows of the two icon tracks as a decision table, plus one test on its own
for a question that is `READ` with no answer getting the **yellow** icon. ⚠️ There is deliberately no
test that the same input returns the same icon instance: `icon` returns an object's `val`, so identity
is a Kotlin language guarantee and nothing depends on it — `RemarkGutterIconRenderer.equals` keys on
the five facts, never on the `Icon`).

**Four classes cover the answer.** `AnswerStateTest` is the answer's own storage guard, and its
first three tests were written before the feature existed and confirmed failing for the right reason:
a `RemarksState` holding one answer must serialize to XML that really contains that answer's fields,
`answersSnapshot()` must deep-copy, and two `getState()` calls must never hand back the same list
instance. ⚠️ That is the `@get:XCollection` trap this project has already paid for once with the
`remarks` list — without the annotation the whole list serializes to an empty element and every
answer is lost on restart with nothing logged. ⚠️ A migration test — one asserting that an element
written by an older build still loads — has to be written in `<option name= value=/>` form. Attribute
form parses into a state with every property still at its default, so a test written that way passes
against any state at all, including one that dropped every field the test claims to check. See
`design.md`, "How Remarks are Persisted". `AnswerResolveTest` resolves an answer against a real
file: it follows the code when twenty lines are inserted above it, orphans when the code is gone, and
an answer with an empty path resolves as itself the way a general remark does. `AnswerReceiptTest`
covers what the endpoint causes rather than what it returns — answering marks nothing read, does not
consume the batch, captures the anchor at the position the remark resolves to *now* rather than
copying the stored one, and stores an answer with no anchor rather than dropping it when the remark
was deleted in between. `AnswerPopupTest` is one fixture-backed test that
`DocMarkdownToHtmlConverter.convert` resolves and returns in this build — a `# heading` produces an
`<h` and a fenced block produces a `<pre`. It tests that the platform call works, not that anything
renders, and it is what would catch that class changing shape on a platform bump, since it carries no
`@ApiStatus` annotation at all.

**What guards the icon column, and the rows that know about an answer.** `RemarkIconsTest` is a plain
JUnit class asserting all six icon resources resolve, and it asks for each of them through
`RemarkIcons.resourcePath(name)` — the same expression production hands `IconLoader`, never a copy of
it, because a copy would leave a typo in the production template invisible while all six files sat
under the path the test checked. `RemarkIconsFixtureTest`, in the same file, asserts each of the three
icons reports a width of 16, and that the three are three distinct icons. ⚠️ The width of 16 is a real
assertion, not a tautology: a `CachedImageIcon` resolves on the first call that needs a size, and
`ScaledIconCache` falls back to the platform's **1×1** `EMPTY_ICON` when the image cannot be loaded at
all, so a path that resolves to nothing and an SVG that does not parse both report 1. Checked by
breaking one path by hand and watching the class fail. Same plain-plus-fixture split
`review/OpenReviewFilesTest.kt` already uses. `RemarkStatusLookTest` is the decision table described
above. ⚠️ `RemarksTreeTest` pins `hasAnswer` from both sides — a question with an answer nested under
it carries true, one whose only answer names another remark carries false. Those two are the only
tests in the suite that ever push a true `hasAnswer` through `buildTreeRoot`, so if they go, that
whole branch goes untested and nothing fails. `RemarkGutterTest` has three tests of its own: a remark
with an answer produces a placement carrying
`hasAnswer = true` and one without produces `false`, both asserted through the icon the renderer draws
since `placementsFor` is private, and a third pinning that the answered-id set is derived from the
**unfiltered** answers list, with an answer stored against another file naming a question in this one.
⚠️ The renderer-equality tests in `editor/RemarkGutterIconTest.kt` carry the two that matter most: two
renderers differing only in `hasAnswer` are not equal, and the same for `asksForAnswer`. Those two are
the whole assertion standing between the feature and a gutter icon that never updates.
`RemarksPanelTest` carries the delete confirmation, and with it the only use of
`TestDialog`/`TestDialogManager` in this repository: `TestDialog.DEFAULT` throws on `show()`, so a test
with no dialog registered fails loudly if a dialog ever appears, which is what proves deleting an
expanded question with an answer asks nothing, and the same for an answer row on its own, while
`TestDialogManager.setTestDialog(TestDialog.NO, testRootDisposable)` proves the three cases that do ask:
a selected file group, ⚠️ a question the person has **collapsed** by hand, and a question selected
together with a group.

⚠️ **A Gradle `--tests` filter that names a file rather than a class matches nothing, and Gradle does
not fail for it when another filter in the same command matches.** It reports BUILD SUCCESSFUL while
the tests you meant to run never ran. This has already cost a real check here: a task was told to run
`--tests 'dev.sasha.clauderemarks.editor.RemarkGutterIconTest'`, no class of that name exists, the
second filter in the same command matched, and the renderer-equality tests guarding the one real trap
of that change were the ones silently skipped. Check what a file actually declares before filtering
on it.
The files here whose class names differ from the filename:

- `editor/RemarkGutterIconTest.kt` — holds `RemarkTooltipTest`, `AnswerTooltipTest`,
  `RemarkGutterRendererTest` and `AnswerGutterRendererTest`. Four classes, and none of them is named
  after the file.
- `review/OpenReviewFilesTest.kt` — holds `OpenReviewFilesTest` and `OpenReviewFilesFixtureTest`.
- `ui/RemarkIconsTest.kt` — holds `RemarkIconsTest` and `RemarkIconsFixtureTest`.

The renderer tests sit in a file of their own, `ui/RemarkTreeRendererTest.kt`, with a class of the
same name, rather than as a second class inside `ui/RemarksTreeTest.kt`. That is this trap avoided on
purpose: `RemarksTreeTest` is plain JUnit with no fixture, the renderer assertions need one, and a
second class added to that file would never run under a filter naming `RemarksTreeTest` while the
build stayed green.

Two more files are checked by hand, not by `./gradlew test`, because this repository's suite is
Kotlin and runs no shell: `src/main/resources/dev/sasha/clauderemarks/skill/watch-remarks.sh` (each
check is its own run, in the scratchpad directory, covering a deadline timeout, a nonce that has
already changed, a file that appears after the watcher starts, a malformed
header, ⚠️ that a second watcher on the same project does
**not** kill the first, that both are still alive afterwards, that the pid file then holds the second
watcher's pid, and that a watcher on another path is left completely untouched; plus that fetch mode
starts with no `--session` while still refusing with no `--project` and no
`CLAUDE_REMARKS_TOKEN`; and `--owner`: a live owner
keeps the watcher polling, a killed owner ends it inside one poll interval with exit 3 and its own
message, a non-numeric, empty or zero value is refused with exit 2, and no `--owner` at all leaves
every earlier behaviour untouched — plus that the `perl … setsid()` launch form really does leave the
launching shell's process group, checked by signalling that whole group and watching only that form
survive; that a five-line header is read and its nonce taken from line 2, that an
old eight-line header still yields its nonce because the three extra lines read as body, that
`--require-review` is refused with exit 2 rather than ignored, and `--session` on its own is refused
the same way — it is only accepted beside `--claim` — and that fetch mode
sends a body carrying `project` and no `session`, checked against a one-shot local HTTP server that
captured the raw POST body; and `--stream` in both modes — two nonces out of one
process and no third line for an unchanged file — that a run without `--stream` still prints the batch
whole and exits 0, that `--claim` is refused without `--stream` or without `--project`, that a claim
that cannot connect prints `claim-failed http 000` **and** the nonce, that a stream run with no
`--claim` invokes `curl` zero times, and that the token reaches the endpoint's header and appears in no
recorded argv, proved with a `curl` shim that wrote down its own arguments; and the batch snapshot —
that a batch overwritten in the published file **after** its line was
printed is still whole in the snapshot that line named, checked in `--file` mode against a real
overwrite and in `--fetch` mode against a fake endpoint that starts answering with a second batch, that
six batches leave only the four newest snapshots on disk, that a killed watcher leaves them behind
rather than cleaning them up, and that a run without `--stream` writes no snapshot at all.
⚠️ Every one of those runs
faked `HOME` **and** the port: a fake handshake left at the ordinary `63342` reaches the IDE the person
is actually working in, which has already happened once and was stopped only by the token check.
Point a fake handshake at a port nothing is listening on, or at a fake endpoint of your own) and
`src/main/resources/dev/sasha/clauderemarks/skill/remote-config.sh` (each check is
its own run too, with `HOME` pointed at a temporary directory, covering `save`/`show`/`forget`, that the token never
appears in any output, permission and validation refusals, and that two repository roots produce two
different stored files).

Every fixture-backed test class that asserts on the whole store clears it in `setUp`, not only in
`tearDown`: the light fixture project is shared across test classes, so remarks left behind by an
earlier class are still there when the next one starts. The store is the only shared state there is
to clear. Anything registered on `testRootDisposable`, such as
`RemarksPanelTest`'s `TestDialog`, is unregistered by the fixture itself and needs no `tearDown` of its
own.

There are no UI-rendering or end-to-end tests. The popup appearing at the caret, the gutter icon
painting, the tree colours, the balloon, the settings page layout, whether the
answer popup actually draws a heading, a bullet list, a fence and a table as themselves rather than
as literal markdown, and whether the three question-mark colours are actually
distinguishable at gutter size in a light theme and in a dark one, are all checked by hand in a sandbox
IDE, not automated. A test can say an icon loaded and reports a width of 16; nothing automated can say
it reads as yellow rather than as green.

⚠️ **There is no JavaScript test setup at all.** `./gradlew test` reaches
`preview/PreviewHighlights.kt` and the push in
`preview/PreviewRemarkExtension.kt`, and it reaches nothing inside `claude-remarks-preview.js` or
`claude-remarks-preview.css`. Whether a highlight actually appears, whether it survives typing, whether
a question reads as different from a plain remark, and whether a heavily annotated document is still
comfortable to read are all hand checks. The answer to that is to keep the page dumb — every decision
that can be made in Kotlin is made there, which is why `PreviewHighlights.kt` exists as its own file
instead of the page working the exclusions out for itself.

⚠️ **The row renderer has the same gap.** A test can say a row produced three visible line components
and that the fourth, grey one was drawn; nothing automated can say the fourth line of text was elided
rather than clipped, that the grey line reads as subordinate to the text above it, that selection
paints across every line of a tall row, or that the icon sitting inside the first line reads as a
hanging indent rather than as ragged. All of those are hand checks; `docs/claude/hand-checks.md`
points at the list they are on.
