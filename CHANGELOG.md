# Changelog

This project was built in fifteen phases over six days, each one planned in a file under
`docs/plans/` before any code was written. The entries below are the record of those phases,
newest first.

They are development history, not release announcements. A version number mostly means "this is what
`build.gradle.kts` said when that phase finished". Every version and date below is read out of the
git history of `build.gradle.kts`, and every plan section named at the end is a file in this
repository. `0.12.0` is the first version that was ever submitted to the JetBrains Marketplace, and
it was rejected; `0.12.1` is the fix.

The design that came out of all this lives in `docs/claude/design.md`, which is kept current with
the code. These entries are how the work happened; that document is what the system now is.

---

## 0.12.1 — 2026-08-08 — the version lookup stops asking the platform

`0.12.0` was submitted to the JetBrains Marketplace and its automated verification refused it:
**1 usage of internal API**, `PluginManager.getPluginByClass(Class)`. Nothing user-visible changes in
this release.

- **What went wrong, and why a local run did not catch it.** `sinceBuild = "252"` carries no upper
  bound, so the plugin claims every later build and the Marketplace verifies against every later
  build — it used IntelliJ IDEA 2026.2.1 rc (262.9437.65). `getPluginByClass` carries no annotation
  at all in the 252 line the plugin compiles against and is `@ApiStatus.Internal` in 262, so
  `./gradlew verifyPlugin` against the compile target stayed green while the upload was refused.
- **Both obvious replacements are internal too.** `PluginManager.findEnabledPlugin` and
  `PluginManagerCore.getPlugin` are both `@ApiStatus.Internal` in 262, checked against that exact
  build's sources. 262 points at `PluginDetailsService` instead, which does not exist in 252, so
  there is nothing to compile against. ⚠️ There is no platform call for "what version am I" that is
  supported across the range this plugin claims.
- **So the build writes the answer into a resource.** `writePluginVersionResource` in
  `build.gradle.kts` puts the version in `dev/sasha/clauderemarks/plugin-version.txt`, and
  `bundledPluginVersion()` reads it back. The path sits inside the plugin's own package on purpose:
  `/META-INF/plugin.xml` and a bare `/version.txt` are names platform jars also carry, and which copy
  a classloader returns is not something to bet a version number on. `skill/BundledSkillVersion.kt`
  now has no `com.intellij` import at all, so unlike the old lookup it returns a real version inside
  a test fixture instead of null.
- **`PluginVersionResourceTest` guards it.** One Gradle task produces that resource and nothing else
  would fail if it stopped running: the settings row would quietly say "version unknown" and the
  install notification would return early and never fire, with nothing logged either way.
- **`pluginVerification.ides` now asks for more than the compile target.** ⚠️ It does not yet get it:
  `recommended()` resolves to 252 alone, and neither `2026.2` nor the build number `262.9437.65`
  resolves for IntelliJ IDEA Community through the default repositories, so the `select` block for
  262 and later currently matches nothing and the local run still schedules one verification. The
  block is left in place so it starts covering 262 the moment that build reaches the feed. Until
  then, ⚠️ **a green local `verifyPlugin` is not the same check the Marketplace runs.**

## 0.12.0 — 2026-08-07 — phase 15: the skill ships inside the plugin, and the plugin installs it

Until now the skill was installed by hand, and somebody who installed the plugin zip got no skill at
all: its three files lived under `docs/`, which never reaches the artifact. Two pieces close that,
and the first one is what the second one needed.

- **The three skill files are plugin resources now.** `SKILL.md`, `watch-remarks.sh` and
  `remote-config.sh` move from `docs/skill/claude-remarks/` to
  `src/main/resources/dev/sasha/clauderemarks/skill/`, with `git mv`, so there is one copy and never
  two. Two copies of a 96 KB prose file drift apart, and the drift would be invisible inside it. The
  built jar carries all three now, checked by listing the jar rather than by trusting the build.
  `SKILL.md`'s own directory-resolution block keeps its first two candidates, which are where an
  installed skill lives, and its third candidate points at the new path, so the skill still runs
  straight out of a checkout. ⚠️ A development symlink made before this points at a directory that no
  longer exists, so it has to be removed and made again.
- **A row on the settings page installs it.** Settings → Tools → Claude Remarks lists every harness
  found on the machine, says what is installed against what the plugin carries — "up to date",
  "0.11.0 installed, 0.12.0 bundled", "installed, version unknown", "not installed" — and gives
  Claude Code a button. The button says Install when nothing is there and Reinstall otherwise, and
  Reinstall stays enabled when the copy is up to date, because somebody who edited the installed copy
  needs a way back. Detection and the copy both run on the pooled executor and fill the labels back
  in on the EDT.
- **A notification on project open says the same thing.** It fires only when Claude Code is found,
  the installed copy is missing or unstamped or a different version, the person has not pressed
  Don't ask again, and nothing was shown yet in this IDE run — an in-memory flag, so opening three
  projects shows one balloon rather than three. Its three actions are Install, Settings and Don't ask
  again. ⚠️ The dismissal is persisted in `RemarkSettings`, which roams through Settings Sync, so
  pressing it on one machine also silences the other machine, where the skill may not be installed at
  all. That is accepted: the settings row still shows the state there and still installs.
- **The install copies the files out, and never symlinks them.** An installed plugin lives under a
  versioned path, so a symlink into it dies on the next plugin update and leaves a skill entry
  pointing at nothing. Development does the opposite on purpose, and the two being opposite is why
  the reason is written into the install function's own KDoc.
- **The install refuses when any component it appends below the home directory is a symlink.**
  `~/.claude`, `~/.claude/skills`, `~/.claude/skills/claude-remarks` and the three files inside. On a
  developer's machine one of those points back into the checkout, so writing through it would
  overwrite the plugin's own source files with stamped copies and leave a dirty working tree nobody
  edited. ⚠️ The leaf alone is not enough — a dotfiles checkout can make `~/.claude` itself the link,
  and the leaf is then an ordinary directory the check would wave through. It is deliberately not a
  `toRealPath()` comparison: on macOS `/tmp` is a symlink and a temporary directory resolves under
  `/private/var/folders/…`, so that rule would refuse every temporary directory and any home
  directory under a symlinked mount. The check runs before anything is written, the row hides its
  button entirely rather than offering one that would refuse, and the notification does not fire in
  this case at all — a checkout is not a broken install.
- **Claude Code is detected on `~/.claude`, not on `~/.claude/skills`.** `skills/` is created when a
  first skill is added, so somebody who uses Claude Code but has never added a personal skill has
  `~/.claude` and no `skills/` at all. The target still goes inside `skills/`, which is created on
  the way — inside a `~/.claude` that already exists, so no harness directory is ever guessed into
  being.
- **`SKILL.md` is written last, after both scripts and their executable bits.** A stamp is only ever
  on disk once the install really finished. With it written first, a later failure left a stamped
  `SKILL.md` behind, the settings row said "up to date" and the notification never fired again, while
  a real session answered that `watch-remarks.sh` was not found.
- **The version stamp is one line, and it goes on line 2.** The installed `SKILL.md` gets
  `# claude-remarks-plugin-version: 0.12.0` right after the opening `---`. A YAML comment rather than
  a frontmatter key, because the keys are Claude Code's contract and a comment cannot collide with
  anything. ⚠️ Line 2 and nowhere lower: `description:` is a `>` block scalar, and a `#` line inside
  one is content rather than a comment, so a stamp placed further down would end up as text inside
  the description the harness matches the skill on. Reading it back gives three answers, never two —
  a version, "installed but unstamped", or "not installed" — and it never throws and never guesses.
  ⚠️ A byte-order mark and CRLF endings are both tolerated and both preserved: `"---\r"` compared
  against `"---"` would put the stamp in front of line 1, the frontmatter would never open, and
  Claude Code would silently not register the skill while the stamp still read back as up to date.
- **The executable bit is set explicitly after the copy.** A resource read out of a jar carries no
  permission bits, and `SKILL.md`'s own directory-resolution block tests `[ -x watch-remarks.sh ]`.
  So a copy without it makes the installed skill report that `watch-remarks.sh` was not found while
  the file is sitting right there. `File.setExecutable(true, true)` does it, not
  `Files.setPosixFilePermissions`, which throws on a filesystem with no POSIX view, and a false
  return becomes a failure sentence instead of a silent success.
- **Codex and Gemini are found and listed, with no button.** Both directories exist on this machine.
  Their own layouts have deliberately not been guessed: each has to be read from that tool's own
  documentation first, and that is its own later piece of work. Listing them with a sentence saying
  why is what keeps the gap visible instead of silent.

Plan: `docs/plans/20260806-claude-remarks-phase15.md`.

---

## 0.11.0 — 2026-08-06 — phase 14: the preview gets the other half, and the watcher stops needing a babysitter

Two halves that share nothing but the version bump. The rendered markdown preview gets what the editor
already had, and the watcher script stops needing a session to keep it alive.

- **Ask Claude works in the preview.** `action/AskClaudePreviewAction.kt` sits beside
  `action/AddPreviewRemarkAction.kt` in the preview's right-click menu, registered as
  `ClaudeRemarks.AskClaudePreview` in `claude-remarks-markdown.xml` and nowhere else — a markdown id in
  `plugin.xml` stops the whole plugin loading when the markdown plugin is off. The two actions differ in
  the popup's title and in calling `askClaude` rather than `addRemark`, and in nothing else: every
  refusal is written once, in `openPreviewRemarkInput`, and both call it.
- **Annotated elements are highlighted in the preview.** `preview/PreviewHighlights.kt` is the pure
  half, with no `com.intellij` import: it drops an orphan, a remark about no file and a remark about
  another file, turns what is left into a character offset in the `.md` source, and writes a small JSON
  array. `preview/PreviewRemarkExtension.kt` pushes that array down the same `BrowserPipe` the page
  already posts selections up, from three places — the page's own `documentReady` message, every
  `REMARKS_CHANGED`, and a debounced edit to the previewed source — tearing all three down in
  `dispose`. ⚠️ It never pushes from `init`: the extension is built before the browser starts loading
  the page, and `BrowserPipe.send` queues nothing, so an `init` push is lost every time. The page
  marks the innermost element whose position range covers the offset, walked up to the nearest block,
  in one of two classes styled by `claude-remarks-preview.css`, which the
  platform serves as an ordinary stylesheet because `MarkdownBrowserPreviewExtension` declares a
  `styles` list beside `scripts`.
- **The highlight is a whole element, deliberately.** `md-src-pos` holds offsets in the source, and
  inside an element the rendered text is not the source text — a remark on `**bold**` covers eight
  source characters and four rendered ones. Phase 9 solved the opposite direction by searching for the
  highlighted string, which works only because the browser hands that string over; going the other way
  there is nothing to search with. So the innermost element whose range covers the offset lights up
  whole, and character-exact highlighting is not attempted.
- **A `MutationObserver` keeps the highlight alive through a re-render.** The preview rebuilds its DOM
  as the source is typed, so a class added once is gone on the next keystroke. The observer watches
  `document.documentElement` rather than `document.body`, because the script runs from a `<head>` script
  tag before `document.body` exists, and it watches `childList`, `subtree` and `characterData` but never
  `attributes`, because its own `classList.add` calls are attribute mutations and it would otherwise
  react to its own writes.
- **The watcher can stream.** `--stream` keeps `watch-remarks.sh` polling instead of exiting on a batch,
  and prints one short line per batch — the nonce, the path of a snapshot of that batch, and the claim's
  answer, the same three fields in `--file` and in `--fetch` mode — never the batch body, because the
  harness's `Monitor` tool turns each printed line into a notification and a monitor that emits too many
  events is stopped automatically. ⚠️ Field 2 names a snapshot the watcher wrote, never the live
  published file: the batch is already claimed when the line is printed, and the next publish overwrites
  the published file, so a session reading that file could lose the batch it was handed. The script keeps
  the four newest snapshots and deletes none of them on exit. Without `--stream` the script behaves
  exactly as it always has, which is the path every agent other than Claude Code takes.
- **The watcher owns its seen nonce.** After reporting a batch it sets its own seen nonce to that
  batch's, so nothing is passed back in on a next launch. That removes the whole class of repeat that
  came from a session typing a stale nonce into a launch line, which had happened twice in one day.
  The deadline restarts on every batch too.
- **The watcher can claim.** `--claim <base_url>`, with `--session <id>`, `--project <path>`, `--stream`
  and the token in `CLAUDE_REMARKS_TOKEN`, sends the `published-read` acknowledgement before printing
  the batch's line and puts the answer on the end of it. There are five outcome words — `ok`,
  `already-read <session>`, `unknown-batch`, `claim-failed <status>` and `claim-failed http <code>` — and
  a failed claim prints the nonce anyway, because claiming twice is recoverable and a batch nobody hears
  about is not. `--session` is a flag phase 12 refused; it is accepted again here with a new meaning, and
  still refused on its own.
- **Listen mode splits into two branches on one variable.** A harness with a `Monitor` tool arms one
  persistent monitor and never re-arms; every other agent keeps the exit-per-batch loop, unchanged. The
  summarise, answer and wait-for-go steps are written once, in a section both branches end in.

Plan: `docs/plans/20260806-claude-remarks-phase14.md`.

---

## 0.10.0 — 2026-08-06 — phase 13: the tool window rebuilt

Six changes to the tool window and one to the skill. The tree stops grouping by subject and starts
grouping by state, and a row stops being one cropped line.

- **Buckets are deleted whole.** The `bucket` field, `setRemarkBucket`, `RemarkStore.setBucket`, the
  bucket level in the tree, the `Move to Bucket…` menu entry and every piece of the drag and drop go
  together — `ui/RemarksTreeDnd.kt` is deleted with the file, and the tree reverts from `DnDAwareTree`
  to plain `Tree`. Dragging onto a bucket was the only drag anywhere in the plugin, so nothing drags
  now. They went because a reading pass was never sorted by subject in real use, and because the split
  people did want is by state — and the tree has room for one top-level split before a row costs two
  expansions to reach. An element carrying `<option name="bucket" value="x"/>` still deserializes and
  drops the option on the next save, the same migration phase 11 pinned for `tag` and `severity`. The
  history file's heading loses its `— bucket <name>` part, and every entry archived before this phase
  still carries one, because that file is append-only.
- **The tree splits into Open and Done.** A row is Done once it is `READ` **or** it has an answer, Open
  until then. Done starts collapsed, and stays open across a refresh once a person opens it. An
  answered question moves to Done at once, even when nothing acknowledged it — decided knowing the
  cost, and paid for by the answer staying nested under its question, expanded already so opening Done
  is one click, and by Done ordering newest-processed first inside each file group. The file groups
  themselves stay in path order on both sides. "Answers with no question" stays as its own group above
  Open, because an answer with no question is a loose end and not finished work. Every group inside a
  side carries its side's key as a prefix, since one file can hold rows on both sides and the panel
  matches groups by key alone. `expandAll` walks the model rather than the rows, so a node inside a
  shut Done is expanded too, and it takes a `keepDoneOpen` flag read before the rebuild:
  `collapsedGroups` records what is shut, and "not shut" also covers "no such group yet".
- **`RemarkState` gains `readAt`**, stamped in `RemarkStore.markRead` and nowhere else, so Done can be
  ordered by when a row was processed rather than when it was written. Inside a file, Open is oldest
  first by `createdAt` and Done is newest first by `processedAt` — the later of `readAt` and the time
  the nested answer came back, since either one alone puts the row in Done — falling back to
  `createdAt` when neither is set. Every remark read before this phase carries 0, and without the
  fallback the whole backlog would sort as one lump at the epoch.
- **Rows wrap, to at most three lines of text, with the rest elided.** `RemarkTreeRenderer` becomes a
  `JPanel` on a `GridBagLayout` stacking `SimpleColoredComponent` lines, because
  `ColoredTreeCellRenderer` is one of those and paints a single line by construction.
  `tree.setRowHeight(0)` is the entire variable-height mechanism, copied from the platform's own
  `TodoPanel.java:251`. The word-break is this plugin's own: the platform's `MultiLineTodoRenderer`
  receives lines that are already split and never wraps anything. `ui/WrapText.kt`'s `wrapToLines`
  takes a `widthOf` measurer rather than a `FontMetrics`, so that file has no import statement at all
  and its tests run in milliseconds. Line breaks typed with Shift+Enter are kept instead of flattened.
  Resizing the tool window re-wraps every row: a component listener on the scroll pane's viewport
  restarts a one-shot 150 ms timer, which drops JTree's cached row bounds and puts the expansion and
  the selection back.
- **The position moves below the text.** The line range, its `(moved)`/`(orphaned…)` suffix and an
  orphan-group answer's file name are one grey line under the wrapped text instead of a prefix in
  front of it, hidden outright when there is nothing to put in it. The three-line cap counts lines of
  text, not lines of row. The body wraps to the row's full width now, because the position no longer
  takes width off all three lines whether or not they draw it. That line is drawn in the smaller grey,
  because a `READ` row's body is the plain grey and the two were otherwise indistinguishable, and it is
  cut short with an ellipsis rather than pushing a horizontal scroll bar under the whole tree.
- **The trailing `read` and `published` words are gone.** The icon, the colour and the Done group each
  say it already; the same argument phase 12 used to delete `asks` and `answered`.
- **A session summarises a batch before acting on it.** `SKILL.md`'s read mode and listen mode both
  write a two-group bullet list — things to change, questions to answer — after acknowledging and
  before any work, quoting each remark rather than paraphrasing it, with `none` written under an empty
  group so a dropped group cannot be mistaken for nothing to report.

Plan: `docs/plans/20260806-claude-remarks-phase13.md`.

---

## 0.9.0 — 2026-08-06 — phase 12: review mode is retired, and the icon column says two things

The largest deletion in the project so far, plus two changes to how the tool window reads. The
*waiting* half of what phases 6, 7, 8 and 10 built is gone. What stays is the endpoint, the handshake,
the published file and the watcher.

- **Review mode is retired whole.** The `start` and `ack` endpoint actions, the banner above the tree,
  the deadline and its scheduled expiry, the review phases, the rejection and the acknowledgement keyed
  to a session id are all deleted, with `review/WaitingReview.kt`, `review/ReviewLifecycle.kt` and
  three test classes. A publish no longer looks for a review to answer. The published file's header
  goes from eight lines to five — `review:`, `label:` and `rejected:` go, and both `sanitizeLabel` and
  `sanitizeControls` with them, so a control character in `commit` shifts the header and the fetch
  answers `failed` rather than reporting a commit nobody has. `FetchRequest` loses its `session` field, so a
  readable published file is always `ready`. The skill's `## Steps` review flow, 531 lines, is deleted
  too. It went because it was a second protocol for something that already had one: the published
  file's nonce already answers "which batch is this", and every piece the review needed on top — a
  session id, a deadline, a phase machine, a scheduled expiry, a banner, its own acknowledgement route
  — was a place the two sides could disagree about a single handover. Thirteen entries in
  `docs/claude/design.md`'s Known Issues were struck out by the deletion, and not one of them was
  fixed.
- **One piece of it is kept, as an action of its own.** `POST /api/claude-remarks/open` takes a project
  and a list of files and opens a real diff over the ones with a local change, plus a plain editor for
  the rest — the useful half of `start`, with no waiting attached. `review/OpenReviewFiles.kt` needed
  no change at all. `opened` in the answer counts the paths that passed the filter, not editors that
  appeared, because the opening hops to the EDT after the response is already written.
- **An answer nests under the question it answers.** It is a child row of its own question, inside the
  file group that question already sits in, added expanded, instead of sitting in a flat group at the
  top of the tree. That flat group narrows to hold only answers whose question is gone, and is
  relabelled "Answers with no question"; its key is unchanged, so a collapsed state survives the
  upgrade. Delete on an expanded question now takes its answer with it, in one action and with no
  dialog, and the confirmation rule becomes "ask when the selection stands for a row that is not on
  screen" — a group row always, and a question row that has an answer under it and is itself collapsed.
  That is what the old arithmetic was a proxy for, and which nesting broke.
- **The icon column carries two facts instead of one.** A question draws a question mark coloured by
  how far it got: neutral pending, yellow published, green once an answer is back. A plain remark draws
  a note, then a neutral tick, then a green tick, replacing the upload mark. The three question marks
  are the plugin's own SVGs, recoloured from the platform's own question-mark shape, and the grey
  `asks` word at the end of a row is deleted, because three things now say it.
  `RemarkGutterIconRenderer` carries both new facts in its `equals` and `hashCode`; without that the
  gutter icon would never change when an answer arrived, and it would look like it worked because the
  tree updates through a different path.
- **The skill directory is renamed** from `docs/skill/claude-remarks-review/` to
  `docs/skill/claude-remarks/`, so a symlink under `~/.claude/skills/` made before this is left
  dangling and has to be recreated. The watcher script refuses `--require-review` and `--session` with
  exit 2 rather than ignoring them, so an old launch line fails loudly instead of watching for the
  wrong thing.

Plan: `docs/plans/20260806-claude-remarks-phase12.md`, and its spec beside it.

---

## after 0.8.0 — 2026-08-06 — follow-up to phase 11: the watcher survives a group kill

Nothing in the plugin changed here. This is the skill side only —
`docs/skill/claude-remarks-review/watch-remarks.sh` and `SKILL.md` — driven by something that
happened in real use: four watchers died in one evening with `Terminated: 15`, a plain `SIGTERM`
from outside, together with watchers belonging to a different session on a different repository.
A watcher launched as an ordinary background task sits in the launching shell's process group, and
one group-wide signal takes all of them.

- **The watcher is launched in a session and a process group of its own**, through
  `perl -e 'use POSIX qw(setsid); setsid(); exec @ARGV' -- …`, on every launch line in `SKILL.md`:
  listen mode and review mode, local and `--fetch`. macOS ships no `setsid` binary, and a
  `( nohup … & )` double fork does **not** do the same thing — it reparents to `init` but keeps the
  shell's process group, so a group-wide kill still reaches it. Both facts are measured and written
  into `SKILL.md` beside the launch lines, because the double fork is the form somebody will
  otherwise "simplify" back to.
- **`--owner <pid>` stops a watcher whose session is gone.** Detaching means the watcher now outlives
  the session that started it, and an orphan holding the pid file makes something look like it is
  listening when nothing is. Given `--owner`, the poll loop tests that pid with `kill -0` once per
  iteration, beside the deadline check, in both the file loop and the fetch loop, and exits `3` with
  its own sentence on stderr. Optional, and the script behaves exactly as before without it. Every
  launch line passes `$PPID`, the session's own `claude` process — not `$$`, which is the Bash call's
  short-lived shell.
- **Exit `3` is documented as the code no session ever sees**, because the process it names is the
  session itself. Both modes say plainly that nothing should be written to handle it.
- Two paragraphs describing a `.watch.lock` directory were corrected: the pid write has been a
  temp-file-and-rename since phase 11 and takes no lock at all.

Checked by hand, each its own run, against a temporary `HOME`: `sh -n`; a watcher with `--owner` on a
live pid keeps polling; killing that pid ends the watcher inside one poll interval with code `3` and
its own message, and it removes its pid file; a watcher with no `--owner` still times out at `1` and
still reports a new batch at `0`; a non-numeric, empty or zero `--owner` is refused with `2`; and the
three launch forms were started inside one process group and signalled with `kill -TERM -<group>` —
the plain form and the double fork died, the `setsid` form survived.

Written in: no plan file. This is a small follow-up to phase 11.

---

## 0.8.0 — 2026-08-05 — phase 11: the answer comes back

Until now everything the plugin did pointed one way: a person marks code, an agent reads it. This
phase turns the arrow around for one kind of remark. You can ask a question, and the answer comes
back into the IDE, onto the line you asked about.

- **A remark loses its tag and its severity level.** Both were dead weight, and use is what settled
  it: over every remark ever published, the severity was never changed from its default and a tag was
  never picked, so everything shipped as an untagged `should` while the prompt spent a paragraph
  teaching a four-level scale it then used one value of. `RemarkTag`, `RemarkSeverity`, the input
  popup's chip row with its five Alt bindings, the shared menu's Severity submenu and
  `setRemarkSeverity` are all gone. An element stored with the old fields still loads — their two
  `<option>` entries are ignored and dropped on the next save. The chip row was also the only reason the build tolerated an
  internal-API usage, so that tolerance went with it.
- **Publish is in the menu the gutter icon and the tree share**, beside Move to Bucket… and a new Ask
  for an Answer toggle. Publishing one remark used to exist only as a toolbar button, so asking one
  question took five steps. The six toolbar buttons also got real descriptions, saying what each one
  *takes* rather than repeating its own name.
- **`Ctrl+Alt+Shift+A` asks a question.** Same input box, plus one stored bit and an immediate
  publish of that one remark — asking is one motion. The `Alt+Enter` intention and the editor's
  right-click menu offer it too. The published prompt marks such a remark's heading
  `— asks for an answer` and prints every remark's `id:` on its own line, which is what a session
  needs in order to answer one.
- **An answer is a stored record of its own**, with its own anchor captured fresh at the position the
  remark resolves to when the answer arrives. So it follows the code by itself, and it survives its
  question being cleared: Clear Handed Over takes the remarks and leaves the answers. At most one
  answer per remark — a second one replaces the first, because re-publishing the same remarks is
  ordinary and a watcher compares nonces rather than content.
- **Reading an answer, three ways.** An Answers group at the very top of the tree, newest first,
  showing the answer's first line; a gutter icon on the code; and a popup rendering the whole thing
  as markdown, with headings, lists, tables and coloured code fences.
- **The endpoint gains a fifth action**, `POST /api/claude-remarks/answer`, keyed to a published
  batch's nonce and a remark id, capped at 16 KiB. It never consumes the batch, works with no review
  ever started, and deliberately accepts an answer to a remark nobody marked.
- **Listen mode stops needing to be babysat.** It claims the batch already waiting when it starts,
  and re-arms itself after each one. Two earlier promises are reversed on purpose: it no longer acts
  on nothing published before it started, and re-arming is no longer a choice said out loud. The
  one-watcher-per-repository rule is gone with them — several sessions may listen at once, nothing
  kills a watcher, and the batch claim in the IDE decides who acts.
- **Listen mode works over the tunnel**, because `fetch` no longer requires a session. A plain
  publish writes `review: none`, so the old header gate meant a remote session could never see one.

Two things found live during the phase and fixed in it. A session read exit code 143 as "another
watcher took over" and stopped listening — 143 is just `128 + SIGTERM`, which any kill produces, so a
stray signal made a session go quiet while the person kept publishing. And a session stopped a
watcher by matching on the program name, which killed every repository's watcher on the machine at
once. A watcher is now stopped only by the pid in its own repository's pid file, after checking that
the pid is alive and that its command line names the same watched path.

## 0.7.0 — 2026-08-05 — phase 10: one file, two acknowledgements

The published file and the review's own handoff file were two files answering two contracts. They
merge into one file with one eight-line header, so an agent's read of it goes through one of two
acknowledgement routes — a review's own session id, or a published batch's nonce — instead of two
separate files each with their own rules.

- Publish All Pending becomes **Publish Unread**, and its filter changes from "still `PENDING`" to
  "not yet `READ`". Publishing again after an acknowledgement is now the ordinary case rather than
  a refused one.
- **Publishing is how a waiting review gets answered.** The banner's "Send remarks" link, the
  toolbar's "Send to Claude Code" button and the Tools menu's `ClaudeRemarks.SendToWaiting` action
  are all gone. `answerWaitingReview` in `review/ReviewLifecycle.kt` replaces `sendToWaitingReview`,
  called from inside the publish pipeline once the file write succeeds.
- A review can only be answered once. A second publish while the same review still waits is still a
  real publish — clipboard and file both — but the review keeps the ids of the batch that actually
  reached the agent, and the balloon says so. The agent's watcher exits on the first batch and
  nothing re-arms it, so telling somebody a second publish had been added to what the agent is
  reading would have been a lie.
- A rejection is now just another batch written to that same file, carrying `rejected: yes` and
  `remarks: 0`. The per-review temp directory the review used to own is gone entirely, along with
  `WaitingReviewState.outputPath` and `endedOutputPath`: a fetch now resolves one predictable path
  instead of a path that had to be handed back in a response first.
- The endpoint gains a fourth action, `POST /api/claude-remarks/published-read`, keyed to a batch's
  nonce. `review/PublishedAck.kt` remembers the last sixteen published batches in memory and answers
  `ok`, `already-read` (naming who got there first) or `unknown-batch`.
- The skill gains a background watcher script, `watch-remarks.sh`, launched once and waited on. A
  foreground shell call is capped at ten minutes; a launched background command is not, which is
  what lets review mode and listen mode wait out a real deadline. It also gains
  `remote-config.sh`, so a remote IDE's four connection values are stored once per repository
  instead of retyped on every run.
- The skill's three modes are settled here: a one-shot read of what is already published, an
  opt-in listen mode that waits for the next batch, and review mode.

After the phase closed, three more fixes landed on how a remark looks:

- The Publish buttons stopped wearing a Copy icon; they carry Upload now, which is what they do.
- The tree and the gutter grey a remark only once it is **read**. A published remark draws at full
  strength, because Publish Unread carries it again.
- Each of the three states gets its own icon, so pending and published are told apart at the start
  of a row. `ui/RemarkStatusLook.kt` is the one place that decides both.

## 0.6.0 — 2026-08-04 — phase 9: three states, phrases, general remarks, drag, preview

Five groups of work.

- **Three states in place of two.** `PENDING`, `PUBLISHED` and `READ` replace `PENDING` and `SENT`.
  Only an agent's own acknowledgement produces `READ`; publishing, however many times, only ever
  produces `PUBLISHED`. Copy All Pending and Copy Selected become Publish All Pending and Publish
  Selected. The `ClaudeRemarks.CopyAll` action id stays as it is, because it is a public interface.
  Publishing also starts writing the prompt to a file under `~/.claude-remarks/`, so a Claude Code
  skill can read published remarks on its own schedule with no review started at all.
- **A sub-line remark keeps the words it points at.** The selected text is stored beside the line
  range, and the anchor searches for it once the plain line resolve orphans — which is the one case
  a line-only anchor could never handle, a paragraph that reflowed. The tree row and the gutter
  tooltip show the sub-line range, and the tooltip shows the phrase.
- **A remark about the whole change, not one file.** Written with the Add General Remark toolbar
  button, rendered first in the published prompt under its own `## General` heading with no code
  block, and grouped at the very top of the tree above the buckets.
- **A file row shows its name first**, with the directory shortened in grey after it. A remark,
  several selected remarks, or a whole file or bucket group can be dragged onto a bucket row to move
  them, or onto `(no bucket)` to clear it.
- **A remark written from the rendered markdown preview.** Select words there, right-click, and the
  remark points at the same characters in the `.md` source. The markdown plugin is an optional
  dependency, so the plugin still loads with it disabled — that one entry point is simply absent.

## 0.5.0 — 2026-08-03 — phase 8: an agent on another machine

A Claude Code session on another machine can read remarks over an SSH tunnel the person sets up by
hand.

- The endpoint gains `POST /api/claude-remarks/fetch`, returning the file's content in the response
  body instead of a path — a path on the IDE machine means nothing to an agent on a different one.
- Fetching marks nothing read. The `read` acknowledgement is still the only thing that does, so a
  fetch is safe to repeat as often as a poll needs, and a lost response costs one retry.
- A response over one megabyte is refused rather than truncated, because a markdown prompt cut in
  the middle looks complete to a model reading it.
- Nothing about the security model changed: the built-in server only binds `127.0.0.1`, so the
  tunnel is the only way in, and the plugin's own token check plus the refusal of any request
  carrying `Origin` or `Referer` are the whole gate.

## 0.4.1 — 2026-08-03 — a remark can point at part of a line

A remark records the columns of the selection, not only its lines, and the published prompt draws
`⟦`/`⟧` markers around the selected characters inside the quoted code.

## 0.4.0 — 2026-08-03 — phase 7: the review tells the truth about itself

- Rejecting a review in the banner writes that decision to the handoff file instead of only closing
  the banner, and the link is called Reject rather than Cancel.
- A remark is marked read only once the skill acknowledges it read the file, not the moment the
  file is written. A review that never gets a reply goes stale on its own deadline, declared by the
  skill and clamped and enforced by the IDE.
- A review request that names files with a local change opens one real diff over just those files,
  through `ShowDiffAction`, instead of a plain editor each. Which also means a remark written on the
  revision side of a diff is now refused, with a sentence pointing at the working copy: its line
  numbers describe the revision, not the file on disk.

## 0.3.0 — 2026-08-03 — phase 6: the shared review session

A Claude Code skill can ask a running IDE to hold a review open for a repository, through the IDE's
own built-in HTTP server. The plugin writes a small handshake file under `~/.claude-remarks/` when a
project opens; the skill reads it to find the IDE, and posts one request. A banner appears above the
tree, and the remarks reach the skill through a file both sides agree on.

The plugin works exactly as it did before with no skill installed and nothing listening. This is an
addition next to the clipboard path, never a replacement for it.

## 0.2.0 — 2026-08-03 — phase 5: severity, buckets, chips, commits, history

- **Severity** — `vibe` / `suggestion` / `should` / `must`, defaulting to `should`. A second axis
  next to the tag: the tag says what kind of remark it is, severity says how strongly to act on it.
  The renderer appends a note explaining the scale below the prompt header on every publish, so
  rewriting the header cannot silently drop what the levels mean.
- **Named buckets**, assigned to a selection rather than to one remark at a time, so a whole reading
  pass moves in one step. The tree grows a bucket level only once any remark actually has a bucket.
- **Tag chips picked from the keyboard**, `Alt+0` through `Alt+4`, replacing the tag drop-down —
  which had made Enter ambiguous, since the plugin's own Enter-submits binding beat the list's.
- **A commit stamp** read straight out of `.git`, with no platform import and no dependency on
  Git4Idea, recorded when the remark is written and never refreshed.
- **A history file.** Clearing archives the remarks about to go to a markdown file in the IDE
  configuration directory before removing anything, and removes nothing if that write fails.
- **`Cmd+Ctrl+Shift+Space`** in the remark box inserts a class name from the project. Deliberately
  not `Ctrl+Space`: Basic Completion is offered even inside a popup, and macOS takes `Ctrl+Space`
  for switching input source.

## 0.1.1 — 2026-08-02 — phases 3 and 4: the editor side and the output side

Phase 3 is creating and viewing remarks without leaving the editor: the input popup, the gutter
icon that follows the code, and the tool window as a tree grouped by file. Phase 4 is turning
remarks into one prompt: the editable prompt header in Settings, the markdown renderer, the publish
pipeline, and the toolbar.

One idea from an earlier brief was dropped here before it was built: a pluggable `Dispatcher`
interface, a tmux pane, a file inside `.idea/`. Publishing already gets a prompt into a Claude Code
session with none of that machinery. `docs/claude/design.md`, section "The Publish Pipeline", has
the reasoning. Phase 6 later added a different, simpler automated path; the dropped idea stays
dropped.

## 0.1.0 — 2026-08-02 — phases 1 and 2: storage and anchoring

The persistent store in `.idea/workspace.xml`, the remark record, and the two-pass anchoring search
that keeps a remark pointed at the right lines as the file changes around it. The Bookmarks API was
considered and rejected: `LineBookmark.line` is a single `Int` and there is no range bookmark in the
provider hierarchy.

---

## What has actually been checked by hand

⚠️ **`docs/claude/hand-checks.md` is the live list and the one to keep current.** What follows is the
record as of the last phase, kept here because a changelog is where a reader looks for it. When the
two disagree, that file is right.

Almost nothing. The automated suite is green and has been throughout, but a green suite says very
little about a plugin: there are no UI-rendering and no end-to-end tests, and the two shell scripts
the skill ships are not run by `./gradlew test` at all.

**One gating run happened, on version `0.6.0`, and it is the whole of what has been seen working.**
A review was started over the endpoint, the waiting banner appeared, the file the request named
opened, remarks were written including sub-line ones with their markers, the handover reached the
agent, and the read acknowledgement turned the rows grey. Separately, the remote path was proven end
to end between two machines: a tunnel carried the requests, the token was accepted, `start` was
accepted, the banner appeared in the IDE on the far side, a fetch carried the content back across
the tunnel, and the acknowledgement was accepted. The published file it carried had correct sub-line
markers.

⚠️ **Half of that run exercised review mode, which phase 12 has since deleted.** What it still proves:
the plugin loads at all, the handshake file is found from another machine, the endpoint accepts a token
and works across a tunnel, a publish renders sub-line markers correctly, and an acknowledgement really
does mark remarks read. What it proved about `start`, `ack`, the banner and the deadline is history.

**Everything phases 10 to 15 built is unproven**, because `0.6.0` predates all six — the merged
published file, the acknowledgement by nonce, the watcher script and its streaming shape, the skill's
modes, the whole Ask Claude round trip, the answer nesting, the three question-mark icons, the
Open/Done split, the wrapped rows, the preview's two actions and its highlighting, and the skill
install are all in that set. Phase 6's seven security hand checks were run in a real IDE before
`0.3.0`, and phase 5's commit stamp was checked in a real IDE; the `runIde` checks in the phase 1-2,
phase 3-4 and phase 5 plans were skipped in the autonomous sessions that did that work.

Each plan keeps its own list of what it owes, and those lists are the detail:

| Phase | Hand checks |
| --- | --- |
| 5 | Section 10 of `docs/plans/20260803-claude-remarks-phase5.md` |
| 7 | Section 12 of `docs/plans/20260805-claude-remarks-phase7.md` |
| 8 | Section 13 of `docs/plans/completed/20260803-claude-remarks-phase8.md` |
| 9 | Section 12 of `docs/plans/completed/20260803-claude-remarks-phase9.md` |
| 10 | Section 8 of `docs/plans/completed/20260805-claude-remarks-phase10.md` |
| 11 | "Hand checks" in `docs/plans/20260805-claude-remarks-phase11.md` |
| 12 | "Hand checks" in `docs/plans/completed/20260806-claude-remarks-phase12.md` |
| 13 | "Hand checks" in `docs/plans/completed/20260806-claude-remarks-phase13.md` |
| 14 | "Hand checks" in `docs/plans/completed/20260806-claude-remarks-phase14.md` |
| 15 | "Hand checks" in `docs/plans/completed/20260806-claude-remarks-phase15.md` |

Phase 8's and phase 10's lists need something no other phase does: a second machine, an `sshd`, and
an agent session on the far side of a tunnel.

⚠️ **A later list supersedes an earlier one wherever the two overlap** — phase 13's beats phase 12's,
which beats phase 11's, because all three rewrote the same tree rows. Phase 7's is mostly moot,
because the machinery it checked is deleted and only its diff opening survives, now as the `open`
action. `docs/claude/hand-checks.md` carries the current reading of all of that.
