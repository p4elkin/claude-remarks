---
name: claude-remarks
description: >
  Work with the Claude Remarks IntelliJ plugin: put files in front of a person to mark up, and read
  the remarks they write. Three modes, and only the first two are used without being asked. Open
  files in the IDE: use when asked to show someone a diff, a commit, or a named set of files in
  their open IntelliJ/JetBrains project so they can read it and write remarks on it — one request,
  nothing is started and nothing is waited for. Read remarks the person already published: use when
  asked to read the remarks someone published, read published remarks, look at the remarks they just
  published from the IDE, check whether anything was published for this repository, or act on
  remarks handed over through Publish Unread or Publish Selected, because the plugin already wrote
  them to a file under ~/.claude-remarks that this skill reads directly, and it also acknowledges
  the batch it read and answers the remarks marked with Ask Claude. Listen for the next batch: start
  this only when a person asks, in words, to watch or listen for remarks — never on your own
  initiative, never because a published file was noticed. It claims whatever batch is already
  waiting when it starts, then watches the same published file and reports each new batch as it
  arrives, and keeps itself listening after every one until the person says to stop. The IDE and this skill
  running on the same machine is the normal case for all three. When the IDE is on another machine,
  reached over SSH, the one-shot read and listen mode both need a tunnel the person sets up by hand
  and four connection values from them, which this skill can also store so they are not retyped
  every run — see "Over SSH: the IDE on another machine" below.
---

# Claude Remarks

Three things this skill does with the Claude Remarks tool window, and they do not overlap.

- **Open files in the IDE.** Ask the running IDE to open a set of files — the files in a diff just
  produced, say — so the person can read them and write remarks on them. One request, no waiting,
  nothing to acknowledge. That is the next section.
- **They already published.** The person pressed Publish Unread or Publish Selected, which
  wrote the remarks to a file. Nothing was started and nothing is waiting. Read the file,
  acknowledge the batch, summarise it, act on it, done. That is
  `## Read remarks the person already published`.
- **Listen for the next batch.** Claim whatever batch is already waiting, then watch the published
  file and report each new batch as it comes in — with one watcher that keeps running where the
  harness has a `Monitor` tool, and with a fresh watcher after every batch everywhere else. Opt-in
  only: start this because a person asked, in words, to watch or listen, never because a published
  file was noticed. That is `## Listen for the next batch`.

Pick the first when there are files the person should be looking at. Pick the second when the person
says they published something, or asks for remarks that already exist. Pick the third only when the
person asks, in those words or plainly equivalent ones, to be watched or listened to.

**There is no review mode, and nothing here waits to be answered.** The plugin has no `start` and no
`ack` action any more, no banner above the tree, no deadline it enforces and no session id anything
is keyed to. A publish is the only way remarks leave the IDE, and a batch's own nonce is the only
thing an acknowledgement names. A request to "start a review" is served by the first mode below —
open the files — followed by one of the two reading modes.

## Open files in the IDE

`POST /api/claude-remarks/open` names a project and a list of files, and the IDE opens them. For
files carrying an uncommitted local change it opens **one real diff window** holding just those
files, with next-file navigation inside it; for everything else, a plain editor per file. Nothing is
started, nothing waits, and no remark is read, written or marked by this.

Use it when a session has just produced a diff and wants the person looking at those exact files
before they write remarks. Then use one of the two reading modes below to get the remarks back.

**Work out the file list first, and check it is not empty.** An empty list is accepted and opens
nothing at all, silently. Four shapes, each setting `open_files`:

**One commit** — the shape that is easy to get wrong. Use `git show`, never `git diff`:

```sh
open_commit=PUT_THE_COMMIT_ID_HERE          # this line is not optional
open_files=$(git show --name-only --format= "$open_commit" \
  | jq -R -s -c 'split("\n") | map(select(length > 0))')
```

`git diff --name-only <commit>` does NOT list that commit's files. It diffs that commit against the
working tree, so it prints everything changed *since* it — and prints nothing at all when the tree
already sits at that commit. Both answers are wrong and neither looks like an error.

**A range of commits** — two dots for "what changed on this side", three for "since they diverged",
the same distinction `git log` uses:

```sh
open_files=$(git diff --name-only PUT_THE_BASE_HERE...PUT_THE_TIP_HERE \
  | jq -R -s -c 'split("\n") | map(select(length > 0))')
```

**Uncommitted work** — what the person has edited but not committed. This is the only shape the IDE
can open as a real diff:

```sh
open_files=$(git diff --name-only HEAD \
  | jq -R -s -c 'split("\n") | map(select(length > 0))')
```

**A list decided here** — paths relative to the repository root, the same shape the three commands
above print:

```sh
open_files=$(jq -n -c '["src/main/kotlin/A.kt", "README.md"]')
```

**Say which of the two the person is getting.** For uncommitted work it is one diff window; for a
commit or a range it is a plain editor per file, because the IDE only knows about uncommitted
changes and a committed one has no diff for it to show. That is a known limit, not a failure — an
editor where the person expected a diff should not be read as a bug.

**Then the request.** Run it in the **same** Bash call as the shape above, since nothing carries a
shell across two Bash calls. Every name starts with `open_`, so it collides with nothing in the two
modes below.

```sh
open_root=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$open_root" ]; then
  echo "this directory is not in a git repository, so the handshake file's name cannot be computed"
  echo "here. Ask the person for the project path the IDE shows, and hash that instead."
  exit 1
fi
open_name=$(printf %s "$open_root" | shasum -a 256 | cut -c1-16)
open_handshake="$HOME/.claude-remarks/$open_name.json"
if [ ! -f "$open_handshake" ]; then
  echo "no IDE has $open_root open (no handshake file at $open_handshake), so nothing can be"
  echo "opened. Say that plainly and ask the person to open the project in the IDE."
  exit 1
fi
open_port=$(jq -r .port "$open_handshake")
open_token=$(jq -r .token "$open_handshake")

open_count=$(printf %s "$open_files" | jq 'length')
if [ "$open_count" -eq 0 ]; then
  echo "the file list came out empty, so there is nothing to open. Say so rather than sending a"
  echo "request that opens nothing silently."
  exit 1
fi
# The IDE keeps at most 20 paths and says nothing about the rest, so cap here and be honest.
if [ "$open_count" -gt 20 ]; then
  echo "note: $open_count files, the IDE opens only the first 20"
  open_files=$(printf %s "$open_files" | jq -c '.[0:20]')
fi

open_resp=$(mktemp)
open_body=$(jq -n --arg project "$open_root" --argjson files "$open_files" \
  '{project:$project, files:$files}')
# The token goes in on stdin, through a curl config file, never as an argument: an argument sits in
# curl's argv, which every process on this machine can read out of `ps`, and the token is the only
# gate on this endpoint. The body carries no secret, so it stays on the command line, which is what
# leaves stdin free for the config.
open_code=$(printf 'header = "X-Claude-Remarks-Token: %s"\n' "$open_token" \
  | curl -s --config - -o "$open_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
      -X POST "http://127.0.0.1:$open_port/api/claude-remarks/open" \
      -H "Content-Type: application/json" -d "$open_body")
open_status=$(jq -r '.status // empty' "$open_resp" 2>/dev/null)
echo "open: http $open_code, status ${open_status:-unknown}"
cat "$open_resp" ; echo
rm -f "$open_resp"
```

**Never use `curl -f`**: it throws the body away on a non-2xx response, and the body is what carries
every answer below. Never add `-H Origin:` or `-H Referer:` — `curl` sends neither by default, and
the endpoint refuses a request outright if either is present.

**The answers.** Three of them are HTTP 200 carrying a `status`; the last two are plumbing failures
that carry no `status` at all, so read the HTTP code before the body.

- `ok` — accepted, and `opened` says how many paths the IDE kept. **It counts paths accepted, not
  editors opened**: the opening happens on the IDE's UI thread after this response has already been
  sent, so a path that survived filtering is the whole of what the number promises. The IDE drops an
  absolute path and any path with a `..` segment, so an `opened` lower than the number sent means
  some path was not relative to the repository root.
- `unknown-project` — no open project has that identity. The body's `open` list names the paths the
  IDE does have open; send one of those as `project`.
- `bad-request` — `project` was missing or blank, or the body did not parse. The `detail` says which.
  That is a bug on one of the two sides, not something a retry fixes.
- **http 403** — the token is stale, because the IDE restarted since the handshake file was written.
  Ask the person to re-open the project, which rewrites it, then send it again.
- **http 429** — the built-in server's rate limit, 30 requests a minute from one address. Wait 20
  seconds and send it once more.

**In the remote case** — the IDE on another machine, see "Over SSH: the IDE on another machine"
below — there is no handshake file on this machine at all. POST to `$base_url/open`, use the token
out of the stored `remote-<hash>.env`, and put the **IDE machine's** path in the `project` field.
Everything else in the block is unchanged, the token line included.

## Read remarks the person already published

The Publish actions in the tool window put the same markdown the clipboard gets into
`~/.claude-remarks/<16 hex characters>.md`, where the 16 characters are the start of the sha256 of
the repository's real path — the same name the handshake file uses, with `.md` instead of `.json`.
So there is nothing to ask the IDE for: the name is computable here, and the file is either there
or it is not.

**Which path exactly.** The plugin hashes the git top level — what `git rev-parse --show-toplevel`
prints — whenever the open project sits anywhere inside a git repository, even on a module far below
the repository root. Only for a project that is in no git repository at all does it hash the project
base path instead, the directory holding `.idea`. This shell computes the first case. It cannot
compute the second, because nothing here knows what the IDE opened; in a directory that is not in a
git repository, ask the person for the project path shown in the IDE and hash that instead.

Run this as one Bash call. It is self-contained on purpose: every name in it starts with `pub_` so
it cannot collide with the other two modes. It reads the local handshake file to acknowledge the
batch, but no remote connection value — this mode reads the published file straight off this
machine's disk, so reading it is same-machine only, unlike listen mode. Only the acknowledgement and
the answers go over a tunnel in the remote case.

```sh
# Self-contained. Shares no variable with the other two modes, and defines none they read.
pub_root=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$pub_root" ]; then
  echo "this directory is not in a git repository, so the published file's name cannot be computed here."
  echo "The plugin then names it after the project base path — ask the person for the project path"
  echo "the IDE shows, and hash that instead of \$pub_root."
  exit 1
fi
pub_name=$(printf %s "$pub_root" | shasum -a 256 | cut -c1-16)
pub_file="$HOME/.claude-remarks/$pub_name.md"

if [ ! -f "$pub_file" ]; then
  echo "nobody has published remarks for $pub_root (there is no file at $pub_file)"
  exit 1
fi

# A copy of the batch, taken before anything else in this block runs. Everything below reads the
# copy, never $pub_file again. The acknowledgement further down marks every remark in this batch
# READ, and Publish Unread only ever picks up remarks that are not READ — so a publish landing while
# that request is in flight overwrites $pub_file, and the batch just marked read is gone with nobody
# having seen a word of it. A copy cannot change under the session reading it.
pub_copy=$(mktemp)
cp "$pub_file" "$pub_copy"

# First line only, never an anchored grep over the whole file: a remark's own text starts lines
# too, so a grep would match a remark that quotes the marker.
pub_first=$(sed -n '1p' "$pub_copy")
if [ "$pub_first" != '<!-- claude-remarks: published -->' ]; then
  echo "$pub_file is not a published-remarks file — its first line is not the published marker"
  echo "its first line is: $pub_first"
  rm -f "$pub_copy"
  exit 1
fi

# The header is fixed at five lines: the marker, then nonce:, published:, commit:, remarks:, then a
# blank line and the body. Addressed by line number rather than searched for, so a remark quoting
# "commit:" in its own text cannot be read as the header.
pub_nonce=$(sed -n '2s/^nonce: //p' "$pub_copy")
pub_published=$(sed -n '3s/^published: //p' "$pub_copy")
pub_commit=$(sed -n '4s/^commit: //p' "$pub_copy")
pub_count=$(sed -n '5s/^remarks: //p' "$pub_copy")
# The first 8 characters of the full sha, never `--short=8`: for git, 8 is a floor, and it prints
# more characters as soon as 8 are not unique in this repository. The plugin always writes exactly
# 8, so `--short=8` would print a longer string, the comparison below would differ, and the STALE
# block would fire for remarks published against this very commit.
pub_head=$(git rev-parse HEAD 2>/dev/null | cut -c1-8)

echo "published: ${pub_published:-unknown}, ${pub_count:-unknown} remarks"
echo "published at commit ${pub_commit:-unknown}; this checkout is at ${pub_head:-unknown}"
if [ -n "$pub_commit" ] && [ "$pub_commit" != none ] \
   && [ -n "$pub_head" ] && [ "$pub_commit" != "$pub_head" ]; then
  echo "STALE: these remarks were published against $pub_commit, not against $pub_head."
  echo "The code they point at has moved since. Re-read every line a remark names before acting,"
  echo "and say plainly in the report that the remarks predate the current checkout."
fi

# Acknowledge before acting: READ means an agent read the remarks, not that the work is finished.
# Keyed to the batch's own nonce, which is the only thing an acknowledgement ever names. Needs the
# local handshake file — with no IDE open there is none, and that is said plainly rather than
# failing.
if [ -z "$pub_nonce" ]; then
  echo
  echo "line 2 does not start with 'nonce: ' — this file predates the acknowledgement route."
  echo "Reading and acting on it anyway; there is nothing to acknowledge."
else
  pub_handshake="$HOME/.claude-remarks/$pub_name.json"
  if [ -f "$pub_handshake" ]; then
    pub_port=$(jq -r .port "$pub_handshake")
    pub_token=$(jq -r .token "$pub_handshake")
    pub_session=$(uuidgen)
    pub_ack_resp=$(mktemp)
    pub_ack_body=$(jq -n --arg session "$pub_session" --arg project "$pub_root" --arg nonce "$pub_nonce" \
      '{session:$session, project:$project, nonce:$nonce}')
    # The token goes in on stdin, through a curl config file, never as an argument: an argument sits
    # in curl's argv, which every process on this machine can read out of `ps`, and the token is the
    # only gate on this endpoint. The body carries no secret, so it stays on the command line, which
    # is what leaves stdin free for the config.
    pub_ack_code=$(printf 'header = "X-Claude-Remarks-Token: %s"\n' "$pub_token" \
      | curl -s --config - -o "$pub_ack_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
          -X POST "http://127.0.0.1:$pub_port/api/claude-remarks/published-read" \
          -H "Content-Type: application/json" -d "$pub_ack_body")
    if [ "$pub_ack_code" = 403 ]; then
      echo
      echo "published-read: http 403 — the token in $pub_handshake is stale (the IDE restarted"
      echo "since it was written). Reading and acting on the remarks below anyway; only the IDE"
      echo "was not told. Re-open the project in the IDE to fix this for next time."
    else
      pub_ack_answer=$(jq -r '.status // empty' "$pub_ack_resp" 2>/dev/null)
      echo
      echo "published-read: http $pub_ack_code, status ${pub_ack_answer:-unknown}"
    fi
    rm -f "$pub_ack_resp"
  else
    echo
    echo "no IDE has $pub_root open right now (no handshake file at $pub_handshake). Reading and"
    echo "acting on the remarks below anyway; only the IDE was not told."
  fi
fi

echo
cat "$pub_copy"
rm -f "$pub_copy"
```

⚠️ **The batch is read from the copy, never from `$pub_file` again, and the copy is taken before the
acknowledgement goes out.** The acknowledgement marks every remark in the batch `READ`, and Publish
Unread only ever picks up remarks that are not `READ`. So a publish landing while that request is in
flight — and the `curl` allows it twenty seconds — overwrites `$pub_file`, and the batch just marked
read can never come back. Its remarks would sit in the IDE's Done group looking handled while nobody
had read them. Do not "simplify" the copy away.

**Say the stale line out loud when it appears.** A published file is overwritten by the next
publish and by nothing else, so it can be hours old and can describe code that has since changed.
The commit comparison is the only signal that this happened, and it is worth nothing if it is
printed and then not reported.

**What this mode does, and does not, do.**

- **It does post to the endpoint, and it does read a token — both only for the acknowledgement
  above.** `published-read` names a batch's own nonce and nothing else. The token comes from the
  local handshake file, and only when that file exists — see the code above for what happens when it
  does not.
- **Do not delete `$pub_file` after reading it.** The block deletes `$pub_copy` and nothing else. The
  acknowledgement above may never reach the IDE at all — no handshake file, or a stale token — and
  even when it does, `$pub_file` stays the only copy outside the IDE. A second agent, or the same one
  after a restart, reads it until the next publish overwrites it.
- **Nothing here has to be told that this session walked away.** No wait is open in the IDE, so
  there is nothing to abandon and nothing to acknowledge beyond the batch itself. Set no trap.

**Summarise the batch before doing anything with it.** The acknowledgement above has gone out, and
the next thing is not the work. Write out what the batch says, as a bullet list **split into two
groups**:

- **Things to change** — every remark asking for something to be different.
- **Questions to answer** — every remark whose heading carries `asks for an answer`.

Keep both groups even when one of them is empty, and write `none` under that one. The split is read
off the headings rather than guessed at: the published prompt marks a question in its own heading
and marks nothing else. One flat list makes the person hunt for the questions.

**Each bullet names the file and the line range from the remark's own heading, then says the remark
in the person's own words.** Quote the remark text, do not paraphrase it. A paraphrase drops a
condition quietly, and a condition dropped here is dropped for the whole of the work that follows.

The shape, on a batch of three remarks:

```
Things to change:
- ui/RemarksTree.kt 41-47 — "this comparator sorts by path, so a row written just now lands in the
  middle of the file group"
- README.md 12 — "the icon legend still names the upload mark"

Questions to answer:
- action/PublishRemarks.kt 88-95 — "why does this publish every open question and not just the new
  one?"
```

**What the summary is for.** It is the person's one chance to see that a remark was misread, before
any of it turns into work. It is not ceremony and it is not a status line — the person wrote these
remarks and does not need them read back for their own sake. What they cannot see any other way is
whether this session understood them. In this mode nothing waits for a go-ahead, so the summary is
what a person reading along can interrupt on. Listen mode below turns the same list into a real
gate: it waits for go after it.

**Then answer whatever asks to be answered, before acting on anything else.** If any heading in the
file carries `asks for an answer`, work through `## Answer the remarks that ask for an answer`
below. It needs the nonce this block already read into `$pub_nonce`, and the remark ids off the
`id:` lines under those headings.

Then act on the rest: it is one markdown prompt, remarks grouped by file, each with the code it
points at. Act on it, then say plainly what was done.

**If the file is missing**, say so and stop: "Nobody has published remarks for this repository. In
the IDE, press Publish Unread (or Publish Selected) in the Claude Remarks tool window, then
ask again." If the person was expecting to be shown something first, that is the open mode above,
not a reason to keep polling this file.

## Listen for the next batch

Never started on this skill's own initiative — only when a person asks, in words, to watch or
listen for the next batch of remarks. Noticing a published file sitting there is not asking. Unlike
the two modes above, this one runs open-ended in the background instead of answering once.

**Two branches, and one question picks between them: does this harness have a `Monitor` tool?**

- **Yes — Claude Code.** Arm **one** watcher, as a persistent monitor, and never arm another. The
  watcher streams one line per batch, keeps its own seen nonce, and claims each batch itself.
- **No — every other agent.** Keep the exit-per-batch loop this skill has always used: one watcher
  per batch, read what it printed when it exits, then arm a fresh one. Nothing about that path
  changes, and it must keep working.

**What the two branches share is almost everything.** The setup below is one block for both. Both
watch the same published file, both claim a batch before acting on it, both summarise a batch into
the same two groups before touching anything, both answer the remarks that ask to be answered, both
wait for the person to say go before doing the work, and both stop for the same three endings.

**Where they differ is three things, and only three.**

| | monitor branch | exit-per-batch branch |
|---|---|---|
| how many watchers | one, for the whole session | one per batch |
| the seen nonce | the watcher's own, never passed in | `--seen`, typed into every launch line |
| who claims a batch | the watcher, with `--claim` | the session, after the watcher exits |

⚠️ **`--claim` and `--stream` go together, and the script refuses one without the other.** That is
not tidiness. Without `--stream` the batch itself goes to the watcher's stdout and the session that
reads it sends `published-read` for itself; a watcher claiming there would be answered `ok`, that
session's own claim would then be answered `already-read`, and it would walk away from a batch
nobody handled.

**It claims whatever is already waiting.** A batch already sitting in the published file when
listening starts is claimed exactly like one that arrives later, so a person who publishes and then
asks for a listener is not met with silence. In the monitor branch the watcher does that on its
first poll; in the exit-per-batch branch the setup block does it by hand before arming anything.

**What it sends.** `published-read`, the same acknowledgement the one-shot mode above sends, keyed
to a batch's own nonce; `answer`, for the remarks a batch marks; and, when the IDE is on another
machine, a `fetch` to read a batch it cannot read off a local disk. That is all of it in both
branches. Only who sends the first one changes.

**`--deadline 43200` is passed explicitly, always — twelve hours, not `watch-remarks.sh`'s own
1800-second default.** That default is sized for a single wait, not for a person asking to be
watched over a working session. Leaving it off would silently reuse 1800 seconds, and listening
would stop after half an hour with no explanation. In stream mode every batch restarts that clock,
so a person who keeps publishing keeps their watcher. Say plainly, when listening starts, what is
being watched, that it stops after twelve hours with nothing new, and how to stop it — the stopping
rules are at the end of this section.

**One rule covers every request listen mode makes when the IDE is on another machine: it goes to
`$listen_base_url`, with the token on stdin through `curl --config -`.** That is the claim wherever
it is made, the acknowledgement after a batch, and the answer POST. They already use that exact
shape; the only thing the remote case changes is the host and port they are pointed at, and where
the token comes from — the stored `remote-<hash>.env` rather than a handshake file this machine does
not have. In the remote case the `project` field carries `$listen_project`, the path **the IDE
machine** uses, not this machine's own root.

### The setup, run first in both branches

Run this as one Bash call, self-contained the same way the one-shot mode above is — every name in it
starts with `listen_` so it cannot collide with that mode or with the open mode. The first line is
the branch, and it is the only line in the block that differs between them.

```sh
# yes in a harness that has a Monitor tool — Claude Code — and empty in every other agent. It
# decides two things below: whether the pending batch is claimed here by hand, and which launch line
# is printed at the end.
listen_monitor=yes

listen_root=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$listen_root" ]; then
  echo "this directory is not in a git repository, so the published file's name cannot be computed"
  echo "here. Ask the person for the project path the IDE shows, and hash that instead of"
  echo "\$listen_root."
  exit 1
fi
listen_name=$(printf %s "$listen_root" | shasum -a 256 | cut -c1-16)
listen_file="$HOME/.claude-remarks/$listen_name.md"
listen_session=$(uuidgen)

# The four connection values, for the case where the IDE is on another machine. A whitelist parse,
# never `. "$listen_conf"` — sourcing runs the file, and a value holding a space or a quote could
# change what a later line means. With nothing stored all four stay empty and the local branch below
# runs exactly as it always did.
listen_host=127.0.0.1
listen_port=
listen_project=
listen_token=
listen_conf="$HOME/.claude-remarks/remote-$listen_name.env"
if [ -f "$listen_conf" ]; then
  while IFS='=' read -r listen_key listen_value; do
    case "$listen_key" in
      ide_host) listen_host=$listen_value ;;
      ide_port) listen_port=$listen_value ;;
      ide_project) listen_project=$listen_value ;;
      ide_token) listen_token=$listen_value ;;
    esac
  done < "$listen_conf"
fi
[ -n "$listen_project" ] || listen_project=$listen_root

# Where the watcher script is. See "Where the two scripts are, and how to name them" below: the
# skill's directory is not on PATH, so the launch line printed at the end of this block has to
# carry an absolute path or it answers "command not found".
listen_skill_dir=
for listen_candidate in \
  "$HOME/.claude/skills/claude-remarks" \
  "$PWD/.claude/skills/claude-remarks" \
  "$PWD/docs/skill/claude-remarks"
do
  [ -x "$listen_candidate/watch-remarks.sh" ] && { listen_skill_dir=$listen_candidate; break; }
done
if [ -z "$listen_skill_dir" ]; then
  echo "watch-remarks.sh was not found in ~/.claude/skills/claude-remarks/, in"
  echo "./.claude/skills/claude-remarks/ or in ./docs/skill/claude-remarks/."
  echo "Install the skill the way docs/skill/README.md describes, or say where it is. Do not run"
  echo "it by its bare name: it is not on PATH."
  exit 1
fi

# The connection values. Remote when the stored file gave a port, local otherwise.
if [ -n "$listen_port" ]; then
  listen_remote=yes
  listen_handshake=
else
  listen_remote=
  listen_handshake="$HOME/.claude-remarks/$listen_name.json"
  if [ -f "$listen_handshake" ]; then
    listen_port=$(jq -r .port "$listen_handshake")
    listen_token=$(jq -r .token "$listen_handshake")
  fi
fi
listen_base_url="http://$listen_host:$listen_port/api/claude-remarks"

# Everything from here to the printed launch line belongs to the exit-per-batch branch alone. In the
# monitor branch the watcher claims every batch itself, the one already waiting included, so there is
# no nonce to read here and no --seen to work out.
listen_seen=
# A copy of the pending batch, written before the claim below and read by the session instead of the
# published file. The claim marks every remark in that batch READ, and Publish Unread only ever picks
# up remarks that are not READ — so a publish landing after the claim overwrites $listen_file, and the
# claimed batch is gone with nobody having read a word of it. A copy cannot change underneath the
# session reading it, which is the same reason the monitor branch reads the watcher's snapshot.
listen_copy=
if [ -z "$listen_monitor" ]; then
  # The pending batch's nonce, taken OUT OF THE FILE (or off the wire) on every run, never from a
  # value remembered from an earlier one. Arming with a stale nonce makes the watcher exit 0 within a
  # second on a batch that was already handled, and nothing is listening afterwards. That has happened
  # twice in one day.
  if [ -n "$listen_remote" ]; then
    # No local file to read over a tunnel, so a fetch is what carries the pending batch's nonce back.
    # A fetch takes whatever batch is in the file, which is what a listener wants.
    listen_fetch_resp=$(mktemp)
    listen_fetch_code=$(printf 'header = "X-Claude-Remarks-Token: %s"\n' "$listen_token" \
      | curl -s --config - -o "$listen_fetch_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
          -X POST "$listen_base_url/fetch" -H "Content-Type: application/json" \
          -d "$(jq -n --arg project "$listen_project" '{project:$project}')")
    listen_fetch_status=$(jq -r '.status // empty' "$listen_fetch_resp" 2>/dev/null)
    case "$listen_fetch_status" in
      ready)
        listen_seen=$(jq -r '.nonce // empty' "$listen_fetch_resp")
        # The answer already carries the batch, so the copy costs no second request. Fetching again
        # after the claim would hand back whatever batch the IDE holds by then, which is the same
        # hole in a different shape.
        listen_copy=$(mktemp)
        jq -r '.content' "$listen_fetch_resp" > "$listen_copy"
        ;;
      # `no-review` means nothing has been published for this project. It kept that name from when a
      # review was the only thing that published; there are no reviews any more, and renaming the
      # status would break every deployed copy of this skill and the watcher at once.
      no-review)
        echo "nothing has ever been published for $listen_project — arming with no --seen"
        ;;
      *)
        echo "fetch: http $listen_fetch_code, status ${listen_fetch_status:-unknown}"
        cat "$listen_fetch_resp" ; echo
        echo "report this and stop — too-large, failed and bad-request are not fixed by polling, and"
        echo "http 000 with no status at all is a missing tunnel; see 'Over SSH' below"
        rm -f "$listen_fetch_resp"
        exit 2
        ;;
    esac
    rm -f "$listen_fetch_resp"
  elif [ -f "$listen_file" ]; then
    listen_copy=$(mktemp)
    cp "$listen_file" "$listen_copy"
    listen_line=$(sed -n '2p' "$listen_copy")
    case "$listen_line" in "nonce: "*) listen_seen=${listen_line#"nonce: "} ;; esac
  fi

  # The startup claim. `published-read` is the claim: it is atomic in the IDE, so exactly one of the
  # sessions listening to this repository is answered ok and that one is the one that acts. There is
  # nothing else in the header to check first — a batch is a batch now, and line 2's nonce says which.
  if [ -n "$listen_seen" ] && [ -n "$listen_token" ]; then
    listen_claim_resp=$(mktemp)
    listen_claim_body=$(jq -n --arg session "$listen_session" --arg project "$listen_project" \
      --arg nonce "$listen_seen" '{session:$session, project:$project, nonce:$nonce}')
    # The token on stdin through a curl config file, never as an argument — see the one-shot mode's
    # copy of this block above for why.
    listen_claim_code=$(printf 'header = "X-Claude-Remarks-Token: %s"\n' "$listen_token" \
      | curl -s --config - -o "$listen_claim_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
          -X POST "$listen_base_url/published-read" \
          -H "Content-Type: application/json" -d "$listen_claim_body")
    listen_claim_answer=$(jq -r '.status // empty' "$listen_claim_resp" 2>/dev/null)
    listen_claim_session=$(jq -r '.session // empty' "$listen_claim_resp" 2>/dev/null)
    echo "startup claim on nonce $listen_seen: http $listen_claim_code, status ${listen_claim_answer:-unknown}${listen_claim_session:+, held by $listen_claim_session}"
    rm -f "$listen_claim_resp"
    # A claim that never reached the IDE — a stale token answered 403, a dead tunnel, an http 000 with
    # no status at all — tells us nothing about whether the batch was handled. Arming with --seen set
    # to its nonce would then skip it for good, silently. Arming with an empty --seen instead makes the
    # watcher report it on its first poll, which is the honest answer: nobody knows, so look at it.
    case "$listen_claim_code" in
      2??) ;;
      *)
        echo "the claim did not reach the IDE (http $listen_claim_code). Nobody can say whether that"
        echo "batch was handled, so it is NOT being skipped: arming with an empty --seen, which makes"
        echo "the watcher report it on its first poll. Say so, and say the token or the tunnel may be"
        echo "the reason."
        listen_seen=
        ;;
    esac
  elif [ -n "$listen_seen" ]; then
    echo "a batch is pending (nonce $listen_seen) and there is no token to claim it with — no IDE has"
    echo "this project open. Say that plainly, do not act on the batch, and arm the watcher anyway."
  fi
fi

echo "listen_session=$listen_session"
echo "listen_project=$listen_project"
[ -n "$listen_remote" ] || echo "listen_file=$listen_file"
# Printed only when there is a pending batch to read. This is the path the table below says to open.
[ -z "$listen_copy" ] || echo "listen_copy=$listen_copy"

if [ -n "$listen_monitor" ]; then
  # The token is not in the printed line and is not printed anywhere else either. The command reads
  # it out of the file it already lives in, into its own environment, at the moment the monitor runs
  # it — so it reaches no argv, no transcript and no `ps` listing.
  if [ -n "$listen_remote" ]; then
    listen_token_prefix="CLAUDE_REMARKS_TOKEN=\$(sed -n 's/^ide_token=//p' '$listen_conf') "
    listen_claim_flags=" --claim '$listen_base_url' --session '$listen_session'"
  elif [ -n "$listen_token" ]; then
    listen_token_prefix="CLAUDE_REMARKS_TOKEN=\$(jq -r .token '$listen_handshake') "
    listen_claim_flags=" --claim '$listen_base_url' --session '$listen_session' --project '$listen_project'"
  else
    listen_token_prefix=
    listen_claim_flags=
    echo "no IDE has $listen_root open (no handshake file at $listen_handshake), so there is no token"
    echo "and the watcher cannot claim anything. Arming without --claim: batches still arrive, each"
    echo "line carries the nonce and no outcome word, and nothing is acknowledged. Say that plainly."
  fi
  echo "arm ONE persistent monitor with the command below, and never arm a second one:"
  if [ -n "$listen_remote" ]; then
    printf "  %s'%s/watch-remarks.sh' --fetch '%s' --project '%s' --stream%s --deadline 43200\n" \
      "$listen_token_prefix" "$listen_skill_dir" "$listen_base_url" "$listen_project" \
      "$listen_claim_flags"
  else
    printf "  %s'%s/watch-remarks.sh' --file '%s' --stream%s --deadline 43200\n" \
      "$listen_token_prefix" "$listen_skill_dir" "$listen_file" "$listen_claim_flags"
  fi
else
  echo "listen_seen=$listen_seen"
  # The owner pid, and why it is $PPID and not $$. $$ is this Bash call's own shell, and that shell
  # exits the moment this block finishes printing — a watcher owned by it would exit on its first poll.
  # $PPID is the Claude Code process this session runs as: it is the same number in every Bash call of
  # this session (measured: $$ moved from 70289 to 71025 between two calls while $PPID stayed 75461),
  # and it is gone exactly when the session is gone. So it is what "the session that started this
  # watcher" means. The number is baked into the printed line rather than left as $PPID, the same way
  # every other value in this block is.
  echo "run this next, as its own Bash call, marked background:"
  if [ -n "$listen_remote" ]; then
    echo "with CLAUDE_REMARKS_TOKEN set in its environment to the stored token — never echo the token"
    printf "  perl -e 'use POSIX qw(setsid); setsid(); exec @ARGV' -- '%s/watch-remarks.sh' --fetch '%s' --project '%s' --seen '%s' --owner %s --deadline 43200\n" \
      "$listen_skill_dir" "$listen_base_url" "$listen_project" "$listen_seen" "$PPID"
  else
    printf "  perl -e 'use POSIX qw(setsid); setsid(); exec @ARGV' -- '%s/watch-remarks.sh' --file '%s' --seen '%s' --owner %s --deadline 43200\n" \
      "$listen_skill_dir" "$listen_file" "$listen_seen" "$PPID"
  fi
fi
```

### The monitor branch: one watcher, armed once

**Arm the printed command with the `Monitor` tool, `persistent: true`.** Not a background Bash call,
and not the `perl … setsid` wrapper the other branch uses: the monitor owns this process, and both of
those put it out of the monitor's reach. `persistent: true` rather than a `timeout_ms`, because that
field caps at one hour and this watch is measured in a working day. Give the monitor a description
naming the repository — it appears in every notification.

**Armed once, and never again. There is no re-arm step in this branch.** There is no `--seen` either,
not in the launch line and not anywhere else: the watcher keeps its own seen nonce, so nothing has to
be passed back in and there is no value left for a session to get wrong. ⚠️ That is what this branch
is for. Arming with a stale nonce made a watcher exit within a second on a batch that had already
been handled — twice in one day — and this branch cannot make that mistake, because it never arms a
second watcher at all.

**No `--owner` either.** The watcher is the monitor's own child and stops when the monitor stops, so
there is no orphan for `--owner` to catch. A wrong pid there would end the watch on its first poll,
which is the only thing passing it could still do.

**Each batch arrives as one line, three fields, the same shape in both modes.**

```
<nonce> <snapshot-path> <outcome>
```

No prefix and no label. Field 2 is a **snapshot**: a copy of that batch the watcher wrote the moment
it saw it, sitting in the temp directory and named after the batch's own nonce. It is not the
published file, and in `--fetch` mode it is not something this session fetched — the watcher already
had the batch in hand and wrote it down.

⚠️ **Read the snapshot. Never read the published file instead, and never fetch the batch again.**
That looks like the same thing and is not. The published file is one file that every publish
overwrites, and the batch was claimed — every remark in it marked `READ` in the IDE — before this
line was printed. So a publish landing between the claim and this session getting to work replaces
the batch this line names, and the replaced one can never come back: Publish Unread only ever picks
up remarks that are not `READ`. Those remarks would be gone while sitting in the IDE's Done group
looking handled. The snapshot cannot change under a session reading it, which is the whole reason it
exists. A `fetch` over the tunnel has exactly the same hole — it answers with whatever batch the IDE
holds *now* — so in `--fetch` mode too, the snapshot is what to open.

The watcher keeps the four most recent snapshots and deletes older ones as new batches arrive, so a
long watch does not fill the temp directory. Four is far past what a session reading its batches in
the turn they arrive ever needs. They are deliberately not deleted when the watcher stops, so a batch
reported just before the deadline is still readable.

**The outcome word is the claim the watcher already made.** Five words are possible, not three:

| outcome | what it means | what to do |
|---|---|---|
| `ok` | nobody had claimed that batch, and now this watcher has | genuine unhandled work. Act on it, through every step below |
| `already-read <session>` | another session got there first, and the word after it names that session | name the session that holds it, act on nothing, and keep listening. Do not read the batch and do not answer its marked remarks. ⚠️ Unless the name is a session id **this** session used earlier — see the exit-143 bullet below, where that is unhandled work |
| `unknown-batch` | the IDE does not remember this batch — it fell off the remembered sixteen, or the IDE restarted since it was published | act on it, and **say plainly that it may already have been done** rather than presenting it as fresh |
| `claim-failed <status>` | the IDE answered and disagreed about the request itself: `unknown-project`, `bad-request`, or no status field at all | act on the batch, and say the claim was refused for that reason |
| `claim-failed http <code>` | the claim never reached the IDE. `403` is a stale token; `000` is a connection never made — the IDE is not running, or the tunnel is down | act on the batch, and say the token or the tunnel is the likely reason |

**The rule under that table is one line: `ok` is this session's batch to act on, `already-read` is
somebody else's, and anything else means nobody confirmed anything — so act on it.** A batch claimed
twice is recoverable, because the second claimer is told `already-read` and can see it. A batch
nobody handles is not recoverable at all, and it is the failure this whole design exists to remove.

**Then, in this order, for every line except an `already-read` one that names another session.** An
`already-read` naming an id this session used earlier is handled as if it said `ok` — see the
exit-143 bullet below.

- **Read the batch**, from the snapshot path in field 2 of the line, in both modes. Its line 2 holds
  the same nonce as field 1 — the snapshot is a copy of that one batch and nothing rewrites it — so
  there is no mismatch to check for and no newer batch hiding behind the path. A batch published
  while this session was busy is a batch of its own, with a snapshot and a line of its own, and it
  arrives as the next notification.
- **Acknowledge the batch yourself when the outcome word was not `ok`.** Use the `published-read`
  block the exit-per-batch branch spells out below, with field 1's nonce in place of
  `$listen_nonce` and the session id the setup block printed in place of `$listen_session`. For
  `unknown-batch` that call answers `unknown-batch` again and marks nothing read — send it anyway,
  rather than keeping a second rule for the one case where it is only a wasted request.
- Then the three steps both branches share: **summarise, answer, wait for go.**
- **Arm nothing.** The monitor armed at the start is still running and the next batch is already
  covered. ⚠️ Arming a second watcher here is the mistake this branch exists to prevent: two watchers
  on one file report every batch twice, and the second one has a seen nonce of its own that nobody is
  tracking.

**When the monitor ends, it ends because the watcher exited, and the code says which ending.**

- **Exit 0 never happens here.** In stream mode the watcher does not exit on a batch; that exit
  belongs to the other branch.
- **Exit 1.** The twelve-hour deadline passed with nothing new. Report it and stop. There is nothing
  to acknowledge — `published-read` is never sent for a batch that never arrived.
- **Exit 2.** Something the watcher could not get past: a malformed header, a file it cannot read, or
  a `fetch` answer no amount of polling fixes. Report what it printed verbatim and stop.
- **Any exit code above 128, 143 in particular.** The watcher was killed. 143 is `128 + SIGTERM`,
  which any kill produces — a harness restart, a machine going to sleep, a stray `kill`. Nothing
  arrived and nothing is owed, so acknowledge nothing. Say in one line that the watcher was killed,
  then arm **one** fresh monitor, exactly as at the start, and go on listening.

  ⚠️ **Keep the session id the old setup block printed, and write it down in the reply.** The fresh
  setup block mints a new one. The kill can land in the gap between the old watcher's claim reaching
  the IDE and its line reaching this session: the batch is then marked read in the IDE under the old
  session id while nobody has seen a word of it. The fresh watcher finds that same batch still
  pending in the file, claims it, and is answered `already-read <the old session id>`.

  **An `already-read` naming an id this session used earlier is unhandled work, not somebody
  else's.** Act on that batch exactly as if the word had been `ok`, and say plainly that the claim
  came back naming this session's own earlier id. `already-read` means "act on nothing" only when the
  id it names belongs to a different session.
- **Exit 3 cannot arrive**, because this branch passes no `--owner`.

### The exit-per-batch branch: one watcher per batch

This is the path every agent without a `Monitor` tool takes, and it is unchanged.

**The `perl` wrapper in front of the script is not decoration, and must not be simplified away.** It
puts the watcher in a session and a process group of its own, so that a signal aimed at this
session's process group cannot reach it. "The watcher script" section below carries the measurements
and the reason the obvious alternatives do not work.

**What the startup claim answered decides what to do with the pending batch**, and the three answers
`published-read` already gives cover every case:

| answer | what it means | what to do |
|---|---|---|
| `ok` | nobody had claimed that batch | genuine unhandled work. Surface it exactly as if the watcher had just caught it: read the copy at `listen_copy`, summarise it, answer what asks to be answered, wait for go |
| `already-read` | another session got there first, and the answer names it | skip the batch and name the session that holds it. Do not read it, do not answer its marked remarks. Then go on listening |
| `unknown-batch` | it fell off the IDE's remembered sixteen, or the IDE restarted since it was published | nobody can confirm whether it was handled. Surface it from the same `listen_copy`, and **say plainly that it may already have been done** rather than presenting it as fresh |
| no nonce at all | nothing has ever been published for this project | nothing to claim, and no copy was printed. Arm the watcher with an empty `--seen` and wait |
| any non-2xx http code | the claim never reached the IDE — a stale token, a dead tunnel, `http 000` with no status | nobody can say whether the batch was handled. The block already cleared `--seen`, so the watcher will report the batch on its first poll; say that, and say the token or the tunnel is the likely reason |

⚠️ **Read the copy the setup block printed as `listen_copy`, never `$listen_file` and never a fresh
`fetch`.** The claim above marked every remark in that batch `READ`, and Publish Unread only ever
picks up remarks that are not `READ`. So a publish landing after the claim overwrites `$listen_file`,
and the claimed batch can never come back — its remarks would sit in the IDE's Done group looking
handled while nobody had read them. The copy was taken before the claim and nothing rewrites it. This
is the same rule the monitor branch states for its snapshot, for the same reason.

**A publish landing between the claim and the arming loses neither batch.** The new one carries a
different nonce, and the watcher is armed with `--seen` set to the nonce just claimed, so it is
reported on the watcher's very first poll. The claimed one is safe because the session reads the copy
rather than the file that publish has just overwritten.

**When the watcher exits, act on what it printed — built with `$listen_session`, `$listen_root`
and `$listen_name` typed again, since nothing carries a shell across two Bash calls:**

- **Exit 0.** What the watcher printed is the whole published file, header included. Read the
  five-line header directly out of what it printed, by line number, the same way the one-shot mode
  above reads it off disk: line 2 is `nonce: `, line 3 `published: `, line 4 `commit: `, line 5
  `remarks: `. Every batch is the same kind of batch, so there is nothing in the header to check
  before deciding whether to act on it.
  - Acknowledge it the same way the one-shot mode does, with `$listen_session` in place of
    `$pub_session` and line 2's nonce in place of `$pub_nonce`:

    ```sh
    listen_nonce=THE_NONCE_FROM_LINE_2_ABOVE
    listen_handshake="$HOME/.claude-remarks/$listen_name.json"
    if [ -f "$listen_handshake" ]; then
      listen_port=$(jq -r .port "$listen_handshake")
      listen_token=$(jq -r .token "$listen_handshake")
      listen_ack_resp=$(mktemp)
      listen_ack_body=$(jq -n --arg session "$listen_session" --arg project "$listen_root" \
        --arg nonce "$listen_nonce" '{session:$session, project:$project, nonce:$nonce}')
      # The token on stdin through a curl config file, never as an argument — see the one-shot
      # mode's copy of this block above for why.
      listen_ack_code=$(printf 'header = "X-Claude-Remarks-Token: %s"\n' "$listen_token" \
        | curl -s --config - -o "$listen_ack_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
            -X POST "http://127.0.0.1:$listen_port/api/claude-remarks/published-read" \
            -H "Content-Type: application/json" -d "$listen_ack_body")
      listen_ack_answer=$(jq -r '.status // empty' "$listen_ack_resp" 2>/dev/null)
      listen_ack_session=$(jq -r '.session // empty' "$listen_ack_resp" 2>/dev/null)
      echo "published-read: http $listen_ack_code, status ${listen_ack_answer:-unknown}"
      rm -f "$listen_ack_resp"
    else
      echo "no handshake file at $listen_handshake — can watch but cannot acknowledge. Say that"
      echo "plainly, then read the batch anyway."
    fi
    ```

    **This is the second copy of the `published-read` call, and it stays a copy on purpose.** A
    third script beside `watch-remarks.sh` and `remote-config.sh` would leave one copy, and
    `docs/skill/README.md` argues exactly that for the header format. It is not worth it here: the
    two copies agree on the wire call and differ in what they do with the answer — the one-shot mode
    spends a paragraph on a 403 and a stale token, this one compares `already-read` against its own
    session id — so a shared script would need a flag for each difference and would still leave both
    paragraphs of prose behind. It would also add a third thing that has to be found by absolute
    path, which is the failure "Where the two scripts are" exists to stop. The two blocks are kept
    identical line for line in the part that matters, the `printf | curl --config -` shape: change
    one and change the other. The monitor branch above sends the same call, when its outcome word
    says the watcher's own claim did not land, and it reuses this block rather than adding a third.

    `already-read` naming a session other than `$listen_session` means another session got to this
    batch first: say so at the top, name that session, and do not act. `already-read` naming
    `$listen_session` itself is a retry after a lost response, not an anomaly — proceed as normal.
  - **Then re-arm, immediately — before summarising anything and before answering anything.** Run
    the same launch line again as its own new Bash call, marked background, by the same absolute
    path the setup block resolved and printed and never by the bare name, keeping the `perl`
    wrapper and the same `--owner` value, with `--seen` set to
    this batch's nonce and a fresh `--deadline 43200`. `$PPID` in the new Bash call is the same
    number the setup block printed, so a re-arm can read it again rather than remembering it.
    Re-arming is not a choice put to the person
    and not something to ask about: listening carries on by itself until one of the three endings
    below.

    **This step is where it is for a reason.** Answering and summarising both take a while, and
    while they are being written the person is often still publishing. Re-arming after them leaves
    a gap with nothing listening, which is the exact failure this loop exists to remove.

    Each re-arm gets its own `--deadline 43200`, so any batch resets the clock and listening
    continues for as long as the person keeps working.
  - Then the three steps both branches share: **summarise, answer, wait for go.**
- **Exit 1.** The twelve-hour deadline passed with nothing new. Report it and stop. There is
  nothing to acknowledge — `published-read` is never sent for a batch that never arrived.
- **Exit 2.** Something the watcher could not get past. Report what it printed verbatim and stop.
- **Exit 3 never reaches this session, and there is nothing to write for it.** It means the process
  named by `--owner` is gone, and that process is this session. A session cannot be handed the exit
  code of a watcher that outlived it. It is written down here so nobody adds handling for a case
  that cannot arrive.
- **Any exit code above 128, 143 in particular.** The watcher was killed. 143 is `128 + SIGTERM`,
  which any kill produces — a harness restart, a machine going to sleep, a stray `kill`. Nothing
  arrived and nothing is owed, so acknowledge nothing. Say in one line that the watcher was killed,
  then **arm a new one** and go on listening. Do not read a pid file here and do not go looking for
  a watcher that displaced this one: nothing takes over from anything any more, so there is nothing
  for such a check to find.

### Then, in both branches: summarise, answer, wait for go

- **Summarise the batch, before answering anything and before any work.** The same bullet
  list the one-shot mode above writes, **split into two groups**: **things to change**, every
  remark asking for something to be different, and **questions to answer**, every remark whose
  heading carries `asks for an answer`. Keep both groups even when one of them is empty and write
  `none` under that one. Each bullet names the file and the line range from the remark's own
  heading, then says the remark **in the person's own words** rather than in a paraphrase — a
  paraphrase drops a condition quietly, and a condition dropped here is dropped for the whole of
  the work that follows.

  **This is the second copy of the summarise step, and it stays a copy on purpose**, for the same
  reason the `published-read` block above is one: the two modes agree on the part that matters —
  the two groups and the person's own words — and differ in everything around it. Change one and
  change the other.

  The summary is the person's one chance to see that a remark was misread before any of it turns
  into work, and here that is a real gate rather than something to interrupt: the wait for go two
  steps below is what stops the work starting on a misreading.

  A session told `already-read` writes no summary either. It lost the claim on
  the whole batch, and summarising a batch another session holds would read as work about to
  start on it. Name the winner and go on listening, as the acknowledgement step above says.
- **Then answer whatever asks to be answered:**
  `## Answer the remarks that ask for an answer` below, with line 2's nonce and the remark ids off
  the `id:` lines. Answering needs no go-ahead — it writes nothing to the working tree — and the
  wait for go below is about the work, not about the questions. A session told `already-read` skips
  this too: it lost the claim on the whole batch, marked remarks included.
- Then say what is planned, and **wait for the person to say go.** The batch itself was summarised
  two steps up, so this is the plan and nothing else. Do not act unattended, unlike the one-shot
  read above — a listener runs unattended for hours, and nobody chose this exact moment for the
  work to start. Waiting for go stops nothing: the watcher is still running while the summary is
  being read.

### Stopping, and what ends the loop

**Several sessions may listen to one repository at once, and nothing kills a watcher.** Starting a
listener never takes over from a listener already running for the same repository. That used to be
the rule and it was the wrong rule — the exclusion it was trying to provide already exists one layer
up, in the batch claim, which is the only place that can do it correctly.

**The batch claim is what decides who acts.** All the listening sessions wake on the same batch and
all post `published-read` for it — from the session in one branch, from the watcher in the other,
and the IDE cannot tell the two apart. Exactly one is answered `ok`, and that one acts on it. A
session answered `already-read` names the session that got there first, does not act on the batch,
does not answer its marked remarks, and **keeps listening**. Losing a claim is an ordinary outcome of
two sessions doing their job, not a reason to stop.

⚠️ **Never stop a watcher by matching on the program's name.** No `pkill`, no `killall`, no
`ps | grep | kill` on `watch-remarks.sh`. Every repository's watcher on this machine runs a program
with that name, so a blunt match stops all of them at once, including watchers belonging to work
nobody here is doing. That has happened for real, and the sessions it silenced said nothing about
it. A watcher is stopped **only** by the pid on the first line of its own repository's
`~/.claude-remarks/<16 hex characters>.watch` file, and only after checking two things: that the pid
is alive, and that the live process's command line names the same watched path that file's second
line names.

That is what the pid file is for now. It is not a claim of ownership and it excludes nobody. It
identifies one specific watcher, and that is exactly what makes a blunt match unnecessary.

**In the monitor branch there is a simpler way, and it is the one to use: `TaskStop` on the
monitor.** The watcher is the monitor's own child, so stopping the monitor stops it, and neither the
pid file nor a signal comes into it. The pid-file rule above is still what applies to any watcher
started the other way.

**Repository isolation is a guarantee here, not something to work out from the script.** Two things
hold it up and both already exist: the pid file is named from the project's own 16 hex characters,
so two repositories never share one, and the check on the command line means a recycled pid
belonging to another repository's watcher never matches.

⚠️ **The pid file names the watcher that started most recently for that project.** With two sessions
listening to one repository, stopping by the pid file stops the newer watcher. A session that has to
stop an older one matches on **both** `watch-remarks.sh` **and** its own watched path, never on the
program name alone.

**Three things end the loop, and something must.**

- The deadline passes with nothing new — twelve hours of silence.
- A refusal, exit 2: a malformed header, or a file it cannot read.
- The person asks the session to stop listening.

**Nothing else ends it.** Another session listening to the same repository does not. Losing a batch
claim does not. A watcher killed by something in the environment does not — that one is answered by
arming a fresh watcher, in either branch.

⚠️ **A session that stops listening says so, and says why. It never goes quiet.** Whichever of the
three ended it gets one line at the moment it happens. This is the failure the whole section exists
to remove: a session stopped silently, and the person went on publishing into a listener that was no
longer there.

## Answer the remarks that ask for an answer

Written once here and used by both reading modes: the one-shot read above and listen mode above.
Each of them holds the two things this needs — the batch's nonce, off line 2 of the header, and the
batch's own markdown — so a marked remark is answered wherever it arrives.

**What a marked remark looks like.** The published prompt puts the marker in the heading and the
remark's id on its own line under it:

```
### 3. lines 41-47 — asks for an answer — commit a1b2c3d4

id: 7f1c2a9e-...
```

The marker is set by the person, by the Ask Claude gesture or by the Ask for an Answer toggle in
the tool window. **Never infer it.** A remark that reads like a question and does not carry the
marker is ordinary work or a topic to raise, and is treated as one. The `id:` line is how the POST
below names the remark, and there is no other way to name it.

**The step, in order.**

1. Take the nonce off line 2 of the batch header, and find every heading carrying
   `asks for an answer`.
2. **Answer each of them in this turn**, from the conversation and from the batch payload. The
   payload already carries the question, the file, the line range and the code around it, and in
   listen mode the session has usually been reading that code all along. Write markdown, and open
   with the substance rather than with a preamble: the tool window shows the answer's first line
   inline on a tree row, so "the class is a service because two modules bind it" is a row worth
   reading and "Good question — let me explain" is not.
3. POST each answer with the block below, one call per answer.
4. Report every `status` that is not `ok`, and say what it means. `ok` needs no line of its own.

**Answering needs no go-ahead, in any mode.** The rule about waiting for the person is about
changing code, not about reading it. An answer writes nothing to the working tree, nothing to VCS,
and nothing anywhere except the IDE side channel the person opened by pressing Ask Claude. The work
a question implies still needs the go-ahead each mode already asks for.

**A subagent is the escalation, not the default.** Answer directly whenever the conversation and the
batch payload are enough — that is cheap reuse of context this session already holds, and it is the
whole reason this route exists. A subagent starts with an empty context, so making it the default
pays again to re-derive what the session already knows. Spawn one only for a question this session
cannot answer from what it holds: one needing a file nobody here has read, or has not read recently
enough to trust. Give it the question, the file path, the line range, the code slice and the
repository root, ask for markdown back and nothing else under the same opening rule, and run several
in parallel when several questions need one.

**Two things answering is not.**

- **Answering a question is not licence to do the work the question implies.** "Why is this a
  service and not a helper" is answered, not refactored. The edit happens when the person asks for
  it, under whichever mode's own rule about acting.
- **A failed POST is reported, not retried more than once.** A retry is harmless in itself — the IDE
  replaces an answer for the same remark rather than adding a second — so the reason to stop is the
  other one: a POST that keeps failing means something a retry will not fix.

A question that arrives twice is answered twice, and that is fine. Nothing here tracks what has
already been answered, and nothing should start: the IDE keeps one answer per remark and the second
replaces the first.

⚠️ **A session told `already-read` answers nothing in that batch.** Several sessions may be
listening to one repository, and the `published-read` acknowledgement is what decides which of them
acts: the session answered `ok` won the batch, and a session answered `already-read` naming a
different session lost it. Losing it covers the marked remarks too. The loser names the winner,
sends no `answer` POST at all, and goes back to what it was doing. Two answers for one question
would corrupt nothing, since the IDE replaces — the cost is two sessions each spending a turn on the
same question while neither knows the other did.

**The POST.** One call per answer, run in the foreground.

```sh
# Self-contained, the same way the one-shot read block above is: every name starts with ans_, so it
# collides with nothing in the open_, pub_ or listen_ blocks.
ans_nonce=THE_NONCE_FROM_LINE_2_OF_THE_BATCH
ans_remark_id=THE_ID_LINE_UNDER_THAT_REMARK_S_HEADING

ans_root=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ans_root" ]; then
  echo "this directory is not in a git repository, so the handshake file's name cannot be computed"
  echo "here. Ask the person for the project path the IDE shows, and hash that instead."
  exit 1
fi
ans_name=$(printf %s "$ans_root" | shasum -a 256 | cut -c1-16)
ans_handshake="$HOME/.claude-remarks/$ans_name.json"
if [ ! -f "$ans_handshake" ]; then
  echo "no IDE has $ans_root open (no handshake file at $ans_handshake), so the answer cannot be"
  echo "sent. Say the answer here instead, and say that the IDE never got it."
  exit 1
fi
ans_port=$(jq -r .port "$ans_handshake")
ans_token=$(jq -r .token "$ans_handshake")
ans_session=$(uuidgen)

# The answer through a quoted heredoc, so the shell expands nothing inside it: markdown carries $,
# ` and " routinely, and one of them unquoted would rewrite the answer or break the call.
ans_md=$(mktemp)
cat > "$ans_md" <<'ANSWER'
PUT THE ANSWER HERE, AS MARKDOWN, OPENING WITH THE SUBSTANCE
ANSWER

ans_json=$(mktemp)
jq -n --arg session "$ans_session" --arg project "$ans_root" --arg nonce "$ans_nonce" \
  --arg remarkId "$ans_remark_id" --rawfile answer "$ans_md" \
  '{session:$session, project:$project, nonce:$nonce, remarkId:$remarkId, answer:$answer}' \
  > "$ans_json"

ans_resp=$(mktemp)
# The token goes in on stdin, through a curl config file, never as an argument: an argument sits in
# curl's argv, which every process on this machine can read out of `ps`, and the token is the only
# gate on this endpoint. This is one more copy of that `printf | curl --config -` shape, and it stays
# a copy for the reason the copy under listen mode states in full — every copy agrees on the wire
# call and differs in what it does with the answer, so a shared script would need a flag per
# difference, would leave every paragraph of prose behind, and would add one more thing that has to
# be found by absolute path. Change one of them and change all of them.
ans_code=$(printf 'header = "X-Claude-Remarks-Token: %s"\n' "$ans_token" \
  | curl -s --config - -o "$ans_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
      -X POST "http://127.0.0.1:$ans_port/api/claude-remarks/answer" \
      -H "Content-Type: application/json" -d @"$ans_json")
ans_status=$(jq -r '.status // empty' "$ans_resp" 2>/dev/null)
echo "answer for $ans_remark_id: http $ans_code, status ${ans_status:-unknown}"
cat "$ans_resp" ; echo
rm -f "$ans_resp" "$ans_json" "$ans_md"
```

**The body is a file here, `-d @"$ans_json"`, where the two `published-read` blocks pass a string.**
That is the one line of the shape that differs on purpose. An answer runs to sixteen kilobytes of
the person's own code and prose, and an argument is readable through `ps` by every process on the
machine. The token line above is byte for byte what the other two copies do.

**What the answers mean.**

- `ok` — stored. The row appears in the tool window's Answers group and a balloon says so. Nothing
  to report.
- `unknown-batch` — the nonce is not one of the last sixteen batches this IDE published. The batch
  is old, or the nonce was copied from the wrong line. Say so and stop; the answer was not stored.
- `unknown-remark` — that batch did not carry that remark id. Check the id came from the `id:` line
  under the right heading.
- `too-large` — over sixteen kilobytes. The response carries `bytes` and `limit`. Shorten the answer
  and send it once more.
- `unknown-project` — the `project` field is not a path this IDE has open.
- `bad-request` — the request shape is wrong, which is a bug on one of the two sides rather than a
  transient failure. Report the `detail` and stop.
- **http 403** — the token is stale, because the IDE restarted since the handshake file was written.
  Tell the person to re-open the project, which rewrites it. Say the answer here so it is not lost.

**In the remote case the port and the token come from somewhere else.** There is no handshake file
on this machine at all. POST to `$base_url/answer` instead of building a URL from `$ans_port`, use
the token out of the stored `remote-<hash>.env`, and put the stored project path — the **IDE
machine's** path — in the `project` field. Everything else in the block is unchanged, the token line
included.

## Over SSH: the IDE on another machine

The IDE and this Claude Code session on the same machine is the normal case, reading the
handshake file directly. When the IDE is on another machine — a laptop reached over SSH — both
the handshake file and the published file are local paths on that other machine, so there is
nothing on this machine to read directly. The endpoint's `fetch` action carries the published
file's content back in the HTTP response body instead, and an SSH tunnel is what lets this
machine reach that endpoint at all.

**Which modes need it, and for what.** Listen mode needs it for everything: the startup `fetch` that
reads the pending batch, the claim, the acknowledgement and the answers. The one-shot read needs it
for the same reasons in a different order — there is no published file on this machine to read, so
it reads the batch through one `fetch` in place of the `sed` reads, and then acknowledges and answers
over the tunnel. The open action needs it for its one request. Here is what the person needs to do,
and what to tell the agent:

- **On the IDE machine**, in the repository: read the port and the token out of the handshake
  file into shell variables, and print them so they are there to read. Do not send the token over
  anything that logs it.

  ```console
  HS=~/.claude-remarks/$(printf %s "$(git rev-parse --show-toplevel)" | shasum -a 256 | cut -c1-16).json
  PORT=$(jq -r .port "$HS")
  TOKEN=$(jq -r .token "$HS")
  echo "port: $PORT"
  echo "token: $TOKEN"
  ```
- **Start the tunnel from the IDE machine**, in that same shell, so `$PORT` still holds the value
  that was just read:

  ```console
  ssh -o ExitOnForwardFailure=yes -R 8765:127.0.0.1:"$PORT" the-agent-machine
  ```

  `-R` and not `-L`: the SSH connection already goes from the IDE machine to the agent machine,
  and `-R` forwards a port on the far end back through it. `-L` would need the IDE machine to be
  reachable by `sshd` from the agent machine, which is the harder direction to arrange on a
  laptop. `ExitOnForwardFailure=yes` is not decoration: without it, a port already taken on the
  agent machine makes `ssh` print a warning and connect anyway, and every later request answers
  connection refused for a reason that looks nothing like the cause. Pick a port nothing on the
  agent machine uses, and do not reuse 63342 if that machine runs an IDE too.
- **Then tell the agent four values:** the tunnel's local port on the agent machine (8765 above),
  the token, the repository path as the **IDE machine** sees it, and the host only if it is not
  `127.0.0.1`. The agent can store them so they are not retyped on every later run:
  `~/.claude/skills/claude-remarks/remote-config.sh save --port <port> --project <the
  IDE-machine path> [--host <host>]` — an absolute path, because the skill's own directory is on no
  shell's `PATH`; see "Where the two scripts are, and how to name them" below — with
  `CLAUDE_REMARKS_TOKEN` set to the token in its environment, never as an
  argument, which is world-readable through `ps`. It is keyed to **this** (the agent) machine's own
  repository root, so two repositories here never share one stored configuration. `show` prints
  back what is stored, without the token; `forget` deletes it. Listen mode's startup block reads a
  stored file automatically; with none stored, all four still have to be pasted by hand.
- **A missing tunnel looks like connection refused**, and that is the whole of the error handling.
  The plugin does not manage the tunnel, does not detect it and does not report on it.
- **Restarting the IDE is what invalidates the token.** Re-opening a project rewrites the
  handshake file with the same token, because the token is minted once per IDE run.

## Where the two scripts are, and how to name them

**Both scripts are named by absolute path, always. Never by their bare name.** `watch-remarks.sh`
and `remote-config.sh` sit in this skill's own directory, which is not on any shell's `PATH`, so a
line reading `watch-remarks.sh --file …` answers `command not found` and the whole wait mechanism
below silently does nothing. That has happened for real.

**A skill has no variable naming its own directory**, so where it sits is a convention, and the
convention is the one `docs/skill/README.md` installs: `~/.claude/skills/claude-remarks/`,
what both its `ln -s` and its `cp -r` create. Two other places are worth trying before giving up —
a project-level install under `.claude/skills/`, and a checkout of this repository being run
straight out of its own tree. The block below tries all three, in that order, and stops with a
sentence rather than printing a command that cannot run. **Every Bash call that prints a launch
line runs it first**, and the launch line it prints then carries an absolute path.

```sh
# Resolve this skill's own directory. Rename the two variables per mode, the same way every other
# block here does: listen_skill_dir/listen_candidate is listen mode's copy of exactly this.
skill_dir=
for candidate in \
  "$HOME/.claude/skills/claude-remarks" \
  "$PWD/.claude/skills/claude-remarks" \
  "$PWD/docs/skill/claude-remarks"
do
  [ -x "$candidate/watch-remarks.sh" ] && { skill_dir=$candidate; break; }
done
if [ -z "$skill_dir" ]; then
  echo "watch-remarks.sh was not found in any of the three places this skill looks:"
  echo "  ~/.claude/skills/claude-remarks/, ./.claude/skills/claude-remarks/,"
  echo "  ./docs/skill/claude-remarks/"
  echo "Install the skill the way docs/skill/README.md describes, or say where it is, and run this"
  echo "again. Do not fall back to the bare name: it is not on PATH and never has been."
  exit 1
fi
```

`remote-config.sh` is named the same way. Where this file writes it out in prose it is written
`~/.claude/skills/claude-remarks/remote-config.sh`, which is the install path above; if the
skill is installed somewhere else, use that directory instead.

**The two scripts share one exit-code scheme.** `0` means the command did what it was asked. `2`
means a refusal — a bad argument, a value the script will not store, a file it cannot read, or an
answer no amount of polling can fix. `1` and `3` belong to `watch-remarks.sh` alone and mean one
thing each: `1` is the deadline passing with nothing new, which is not a failure, and `3` is the
process named by `--owner` being gone. `remote-config.sh` never exits `1` or `3`, so a caller can
read either as the watcher's wherever it sees it.

## The watcher script

`watch-remarks.sh`, beside this file, is what listen mode waits with instead of polling inline.

⚠️ **Everything in this section describes the exit-per-batch branch of listen mode. The monitor
branch does the opposite of most of it**, on purpose, and says so at its own place above. Read the
branch you are in, not this section, when the two disagree. In one table:

| | exit-per-batch branch | monitor branch |
|---|---|---|
| how it is launched | a background Bash call | the `Monitor` tool, `persistent: true` |
| the `perl … setsid` wrapper | required | must not be used |
| `--owner` | required, set to `$PPID` | not passed at all |
| how a batch arrives | the script exits and this session reads its stdout | the script prints one line and keeps running |

**In the exit-per-batch branch the watcher has to exit on its own event and must never loop
forever.** The reason is the mechanic every long wait in this skill runs into: a foreground Bash call
is capped at ten minutes, but a **background** command has no such cap, keeps running across turns,
and re-invokes this session when it **exits**. A background command that never exits never notifies,
and the session waits for a signal that cannot arrive. So launch it with a background Bash call,
never a foreground one, and read what it printed once it exits. The monitor branch is not bound by
any of that: a `Monitor` reports every line the process prints while it is still running, so there
the watcher runs with `--stream` and deliberately does not exit.

### Launching it, and why the `perl` line is there

**This applies to the exit-per-batch branch only.** Every launch line in that branch has this shape,
and the `perl` in front of the script is load-bearing. ⚠️ The monitor branch's launch line has no
`perl` and no `setsid`: the monitor owns the process, and `setsid` would put the watcher out of the
monitor's reach, so the wrapper would cost exactly the thing it is meant to protect.

```
perl -e 'use POSIX qw(setsid); setsid(); exec @ARGV' -- '<skill dir>/watch-remarks.sh' <flags…>
```

⚠️ **Do not "simplify" this to `nohup … &` or to a `( … & )` double fork. Both were tried and both
fail**, and they fail in a way that looks fine: the watcher starts, claims its pid file and polls
normally, right up until something signals the launching shell's process group.

**What went wrong.** A session launches its watcher as an ordinary background Bash task. Four
watchers died in one evening with `Terminated: 15` — a plain `SIGTERM` from outside. It was not a
takeover: nothing in this skill kills a watcher any more, and no second watcher was running on that
repository. Watchers belonging to a different session on a *different* repository died at the same
moment, which is what says the signal was aimed at a process group and swept them all up.

**The three launch shapes, measured:**

| launch | PPID | PGID | in the launching shell's process group? |
|---|---|---|---|
| plain background task | the shell | the shell's | yes |
| `( nohup … & )` double fork | 1 | **still the shell's** | yes |
| `perl -e 'use POSIX qw(setsid); setsid(); exec @ARGV' -- …` | 1 once the launching shell exits | **its own** | no |

The double fork is the trap. It does reparent to `init`, so `ps` shows PPID 1 and it looks detached —
but a process group is not inherited from the parent that way, and `kill -- -<pgid>` still reaches
it. Only `setsid()` puts the process in a group of its own. Checked directly: with the three forms
started inside one process group and `kill -TERM -<that group>` sent, the plain form died, the double
fork died, and the `setsid` form survived.

⚠️ **macOS ships no `setsid` binary**, which is why the obvious one-word fix is not available here.
Perl is on every Mac and `POSIX::setsid` is in its core, so the line above is the portable form.
`exec` replaces perl with the watcher, so the watcher keeps perl's own pid — the pid it writes to its
pid file is still the pid to stop it by, and nothing about the pid file changes.

**Detaching means the watcher outlives the session, and `--owner` is what pays for that.** A watcher
in its own session is not stopped when the session that started it goes away. It reparents to `init`
and runs to its deadline — twelve hours, in listen mode. Such an orphan is not dangerous in the way
it first looks: it catches a batch, writes it to a file nobody reads and exits, and nothing is marked
read, because the *session* claims a batch and the watcher never does. What it does cost is the pid
file. The orphan holds it, so a person stopping "the watcher" for that repository stops the orphan
and leaves the live one running, and something looks like it is listening when nothing is. So every
exit-per-batch launch line passes `--owner`, and the watcher stops on its own when its session is
gone. ⚠️ The monitor branch passes none, and needs none: nothing detached it in the first place, so
the watcher is the monitor's own child and stops when the monitor stops.

**`--owner` is passed `$PPID`, never `$$`.** `$$` is the Bash call's own shell, and that shell exits
as soon as the block printing the launch line finishes — a watcher owning it would exit on its first
poll. `$PPID` is the Claude Code process this session runs as. Measured in this repository: across
two Bash calls of one session `$$` was 70289 then 71025, while `$PPID` was 75461 both times, and
75461 was the `claude` process itself. It is the same number in every Bash call of a session, and it
is gone exactly when the session is gone, which is the whole definition of the owner.

**Two forms, one per branch of the wait.** The name is written bare here only because this is a
synopsis of the flags; every line actually run names the script by absolute path, for the reason the
section above gives.

```
watch-remarks.sh --file <path> [--seen <nonce>] [--stream]
                  [--claim <base_url> --session <id> --project <path>]
                  [--deadline <seconds>] [--poll <seconds>] [--owner <pid>]
watch-remarks.sh --fetch <base_url> --project <path>
                  [--seen <nonce>] [--stream] [--claim <base_url> --session <id>]
                  [--deadline <seconds>] [--poll <seconds>] [--owner <pid>]
```

- `--file <path>` is the local branch: poll the published file directly. Default poll interval 2
  seconds.
- `--fetch <base_url> --project <path>` is the remote branch: poll `POST <base_url>/fetch`, the same
  request listen mode's startup block sends once by hand. Default poll interval 5 seconds, because
  the built-in server allows 30 requests a minute from one address. The request body is `{project}`
  and nothing else, and the endpoint hands back whatever batch was last published for that project.
- `--seen <nonce>` is the nonce already known. Omit it, or pass an empty string, to mean "any batch
  is new."
- `--stream` keeps the watcher polling instead of exiting on a batch. It then prints **one short
  line per batch** — the nonce, then the path of a snapshot of that batch, then the claim's answer
  when there is one — never the batch body, and it keeps its own seen nonce, so nothing has to be
  passed back in on a next launch. Listen mode's monitor branch passes it and nothing else does.
  Without it the script behaves exactly as it always has: one batch to stdout whole, then exit 0.
  ⚠️ The snapshot is what a session reads, in both modes: the published file is overwritten by the
  next publish, the batch was already claimed before the line was printed, and a batch that is
  `READ` in the IDE is never published again. The script keeps the four most recent snapshots and
  deletes nothing on exit.
- `--claim <base_url> --session <id>` makes the watcher send the `published-read` acknowledgement
  itself, before it prints a batch's line, and put the answer on the end of that same line: `ok`,
  `already-read <session>`, `unknown-batch`, `claim-failed <status>` or `claim-failed http <code>`.
  ⚠️ The nonce is printed whatever the answer is — claiming twice is recoverable, a batch nobody
  hears about is not. It needs `--stream`, it needs `--project` in `--file` mode too, and it needs
  the IDE token in `CLAUDE_REMARKS_TOKEN`. Without it the script sends nothing at all, exactly as
  before. `--session` is the name the caller invents for itself, and it is what the endpoint reports
  back to whoever claims the same batch second.
- `--deadline <seconds>` defaults to 1800. Listen mode passes 43200 (twelve hours). Zero is refused,
  as is `--poll 0`: `sleep 0` returns at once, which would turn either loop into a busy poll for the
  whole deadline, and in fetch mode into a curl flood.
- ⚠️ **`--require-review` is gone**, and the script refuses it with exit `2` rather than ignoring it.
  It belonged to review mode. `--session` was refused the same way and now means something else
  entirely — the caller's own name, for the claim above — but it is still refused on its own, without
  `--claim`, so a launch line copied from an older version of this file, or from an older session's
  notes, still fails loudly. Which is wanted.
- `--poll <seconds>` is for hand runs and the by-hand checks only. Nothing in this file passes it;
  both defaults above are chosen inside the script. It is kept because a deadline check that had to
  wait the real 2 or 5 seconds per poll would take too long to run by hand.
- `--owner <pid>` names the process the watcher belongs to. Every exit-per-batch launch line passes
  it, set to `$PPID` — see "Launching it, and why the `perl` line is there" just above for what that
  is and why the watcher would otherwise outlive the session. ⚠️ The monitor branch passes no
  `--owner` at all; a wrong pid there would end the watch on its first poll, which is the only thing
  passing it could still do. The loop tests it with `kill -0` once per
  poll, beside the deadline check, and exits `3` when it is gone. Optional: with no `--owner` the
  script behaves exactly as it did before this flag existed. Validated the way `--deadline` is —
  a non-numeric value, an empty one and zero are all refused with exit `2`. Zero has a reason of its
  own: `kill -0 0` asks about the caller's whole process group and would answer "alive" forever.
- The token for `--fetch` is read from `CLAUDE_REMARKS_TOKEN` in the environment, never from an
  argument — an argument is visible to every process on the machine through `ps`, and the token is
  the only gate on the endpoint. The script then hands it to `curl` on stdin, through
  `curl --config -`, for the same reason: `-H "…: $token"` would put it straight back into `curl`'s
  own argv, where `ps` reads it. Every `curl` in this file does the same.

**Exit codes.** `0`, with the whole published file on stdout (header included), when a new batch
arrived — ⚠️ with `--stream` there is no exit `0` at all, because a batch is a printed line there and
the loop goes on. `1`, with one sentence on stderr, when the deadline passed with nothing new. `2`, with a
reason on stderr, for anything wrong: an argument it does not accept, a file it cannot read, a header
whose first line is not the marker, or whose second line does not start with `nonce: ` (either of
those two means something other than this plugin wrote the file),
an HTTP status other than 200, or one of the fetch answers that no amount of polling can fix —
`too-large` (whose sentence on stderr carries the file's size and the limit, both read out of the
response), `failed` (the IDE reached the published file and could not use it: an IOException, a
header it could not parse, or a project directory that no longer resolves), `bad-request` and
`unknown-project`.

`3`, with one sentence on stderr, when the process named by `--owner` is gone. **No session ever
sees this exit code**, because the process it names is the session itself: by the time the watcher
exits this way there is nobody left to hand the code to. It exists so that a detached watcher stops
instead of running to its deadline, and so that a person reading a stopped watcher's own output can
tell this ending from the deadline. Write no handling for it in either mode.

**An exit code above 128 is a signal, and it means this watcher was killed.** 143 is the one to
expect, `128 + SIGTERM`, which any kill produces: a harness restart, a machine going to sleep, a
stray `kill`. It does **not** mean another watcher took over, because nothing takes over from
anything. It is not a batch, not a deadline and not an error — see the exit-code list in listen mode
above for what to do with it, which is to say so in one line and start a new watcher.

**Several watchers may run for one project, and no watcher ever kills another.** On start a watcher
writes two lines to `~/.claude-remarks/<the file's own 16 hex characters>.watch` — its own pid, then
the path it is watching — creating that directory `rwx------` first if the plugin has never run
here. A pid already sitting in that file is overwritten, never signalled. The exclusion that killing
used to provide lives one layer up now, in the batch claim: `published-read` is atomic in the IDE,
so of all the sessions woken by one batch exactly one is answered `ok` and acts on it.

**What the pid file is for: identifying one specific watcher, so a blunt match is never needed.**
Anything stopping a watcher reads **the first line alone** for the pid, and then checks that the pid
is alive and that the live process's command line names the path on the second line. Both halves
matter: a pid on its own gets recycled, and a recycled one can belong to another project's watcher,
which is still a `watch-remarks.sh`. ⚠️ Never `pkill`, `killall` or `ps | grep | kill` on the
program's name — every repository's watcher on this machine answers to it. A watcher removes its own
pid file when it exits, on every exit path, signals included, and only if the file still names its
own pid: a later watcher for the same project may have overwritten it while this one was running.

The pid file names the watcher that started most recently for that project, so with two listeners on
one repository, stopping by that file stops the newer one. Writing those two lines takes no lock: they
go to a temp name beside the pid file and are then renamed onto it, and a rename within one directory
is atomic on every POSIX filesystem, so two watchers starting in the same moment cannot interleave
their lines and leave a file holding one watcher's pid above the other's path. A reader sees one whole
file or the other. No `.watch.lock` directory exists any more; anything still describing one is out of
date.

The 16 hex characters come straight off the `--file` path's own basename when that basename really
is 16 hex characters, which is what every path this file prints looks like. A `--file` pointed
anywhere else is hashed instead, so the file still names one specific watcher rather than quietly
lapsing under a nonsense name. In `--fetch` mode the name comes from `--project` instead, the path
the **IDE machine** uses. ⚠️ When those two strings differ — the ordinary remote case — a local
watcher and a remote watcher for one repository write two different pid files and never see each
other. That is no conflict, since no watcher excludes another anyway. It costs one thing: stopping a
watcher means knowing which of the two files names it.

## What to say if something goes wrong

- Missing handshake file: "No IDE has this repository open right now. Open the project in
  IntelliJ (or another JetBrains IDE with the Claude Remarks plugin) and try again."
- 403, same-machine case: "The IDE at this port answered with a stale token — re-open the project
  in the IDE, which writes a fresh handshake, then try again."
- 403, remote case: "The token stored in `<remote_conf>` is stale — the IDE was restarted since it
  was saved. Get a fresh one (`crtunnel` on the IDE machine prints it, or 'Over SSH' above if that
  helper is not installed), then run
  `~/.claude/skills/claude-remarks/remote-config.sh save --port <port> --project <path>`
  again with `CLAUDE_REMARKS_TOKEN` set to it."
- An acknowledgement answers anything other than `ok`: report the outcome (`already-read`,
  `unknown-batch`, `unknown-project`, `bad-request`) and the body verbatim, and add that the remarks
  were read here but the IDE never marked them read, so the next Publish Unread will carry them
  again.
- A published file left behind by plugin version `0.8.0` or earlier: it carries the old eight-line
  header, with `review:`, `label:` and `rejected:` after `remarks:`. **It still reads**, because the
  first five lines are byte for byte what the five-line header writes and nothing checks line 6 — so
  the nonce, the date, the commit and the count all come out right, and the three extra lines simply
  appear at the top of the body. What does go wrong is the acknowledgement: that nonce was minted by
  an IDE run that has since ended, so `published-read` answers `unknown-batch`. Say that the file
  predates the installed plugin, act on it if the person wants, and say the next publish overwrites it
  with a five-line header. Do not poll for it to fix itself.
- No tunnel in the remote case (connection refused): "There is no tunnel reaching the IDE machine
  at this host and port. On the IDE machine, start one with
  `ssh -o ExitOnForwardFailure=yes -R <port>:127.0.0.1:<the IDE's port> <this machine>`, then try
  again." `ExitOnForwardFailure=yes` matters here: without it a taken port on this machine makes
  `ssh` connect anyway with no forwarding, and every request after that answers connection refused
  for a reason that looks nothing like the real cause.
- `fetch` answers `too-large`: "The published batch is too big to send through the tunnel
  (`<bytes>` bytes, limit `<limit>`)." Take both numbers from the watcher's own stderr line, which
  reads them out of the response and prints them in brackets. Then: "The remarks are still in the
  IDE, in the published file under `~/.claude-remarks/` on the IDE machine. Ask the person to read
  them there, or to publish fewer remarks at a time." Not a failure to retry — the same file comes
  back the same size every poll, so this stops here.
- `fetch` answers `failed`: "The IDE reached the published file and could not use it: `<detail>`."
  The `detail` field says which of three things happened — the file could not be read (an
  IOException), its header could not be parsed (something other than this plugin wrote it, or an
  older plugin's file is still sitting there), or the open project's own directory no longer
  resolves (the checkout was deleted, moved or unmounted under the IDE). None of the three gets
  better by polling, which is why the watcher exits 2 on it rather than waiting the deadline out.
  Report the detail verbatim, and stop. There is nothing to acknowledge — no batch reached this side
  — and the person can publish again once the cause is fixed.
- `fetch` answers `unknown-project` in the remote case: "The two machines disagree about where the
  repository lives. The response's `open` list names the paths the IDE has open — pass one of
  those as `ide_project` and try again." This is the normal first failure of the remote case, not
  a rare mistake.
