---
name: claude-remarks-review
description: >
  Read remarks a person wrote in the Claude Remarks IntelliJ plugin. Two modes. Read a published
  file: use when asked to read the remarks someone published, read published remarks, look at the
  remarks they just published from the IDE, check whether anything was published for this
  repository, or act on remarks handed over through Publish All Pending or Publish Selected —
  no review is started and nothing is waited for, because the plugin already wrote them to a file
  under ~/.claude-remarks that this skill reads directly. Hand a review over and wait: use when
  asked to start a review session with the IDE, wait for review comments from an open
  IntelliJ/JetBrains project, or read back remarks the person just sent with "Send to Claude
  Code" from the Claude Remarks tool window. The IDE and this skill running on the same machine
  is the normal case for both modes. When the IDE is on another machine, reached over SSH, the
  review mode needs a tunnel the person sets up by hand and four connection values from them —
  see "Over SSH: the IDE on another machine" below.
---

# Claude Remarks review

Two ways to get a person's remarks out of the Claude Remarks tool window, and they do not overlap.

- **They already published.** The person pressed Publish All Pending or Publish Selected, which
  wrote the remarks to a file. Nothing was started and nothing is waiting. Read the file, act on
  it, done. That is the next section, and it is the whole of that mode.
- **Hand a review over and wait for it.** This skill starts a review, the IDE shows a banner, the
  person answers by pressing "Send to Claude Code", and this skill waits for that. That is
  `## Steps` below, and the section before it covers the case where the IDE sits on another
  machine.

Pick the first when the person says they published something or asks for remarks that already
exist. Pick the second when the person is being asked to review something now.

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

Run this as one Bash call. It is self-contained on purpose: it shares no variable with the review
flow in `## Steps`, and every name in it starts with `pub_` so it cannot collide with one. It reads
no connection value, needs no token, and does not talk to the IDE at all.

```sh
# Self-contained. Shares no variable with the review flow below, and defines none it reads.
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

# First line only, the same rule the rejection check in step 6 had to learn: a remark's own text
# starts lines too, so an anchored grep would match a remark that quotes the marker.
pub_first=$(head -1 "$pub_file")
if [ "$pub_first" != '<!-- claude-remarks: published -->' ]; then
  echo "$pub_file is not a published-remarks file — its first line is not the published marker"
  echo "its first line is: $pub_first"
  exit 1
fi

# The header is fixed: line 1 the marker, then published:, commit:, remarks:, then a blank line.
# Addressed by line number rather than searched for, so a remark quoting "commit:" cannot be read
# as the header.
pub_published=$(sed -n '2s/^published: //p' "$pub_file")
pub_commit=$(sed -n '3s/^commit: //p' "$pub_file")
pub_count=$(sed -n '4s/^remarks: //p' "$pub_file")
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

echo
cat "$pub_file"
```

**Say the stale line out loud when it appears.** A published file is overwritten by the next
publish and by nothing else, so it can be hours old and can describe code that has since changed.
The commit comparison is the only signal that this happened, and it is worth nothing if it is
printed and then not reported.

**Three things this mode must not do.**

- **Do not delete the file after reading it.** Nothing on this path confirms a read: no
  acknowledgement is sent, and the IDE never learns that anything read it. That is exactly why the
  plugin has a separate `PUBLISHED` state from `READ` — publishing hands remarks to a channel that
  cannot confirm. Deleting the file would destroy the only copy outside the IDE on the strength of
  a confirmation that does not exist, and a second agent, or the same one after a restart, would
  then find nothing.
- **Do not post anything to the endpoint.** There is no review here. `ack` names a session, and
  this mode has no session to name, so an acknowledgement would be answered `no-review` at best and
  would mark somebody else's waiting review at worst. No token is read in this mode for the same
  reason.
- **Do not set a trap.** The traps in step 6 exist to tell the IDE that an agent walked away from a
  review it was waiting on. Nothing is waiting here, so there is nothing to abandon and nothing to
  tell.

Then act on the remarks the same way step 7 describes: it is one markdown prompt, remarks grouped
by file, each with its severity, its tag and the code it points at. Act on it, then say plainly
what was done.

**If the file is missing**, say so and stop: "Nobody has published remarks for this repository. In
the IDE, press Publish All Pending (or Publish Selected) in the Claude Remarks tool window, then
ask again." Do not start a review instead — that is a different thing, and it puts a banner in
front of a person who was not asking to be interrupted.

## Over SSH: the IDE on another machine

The IDE and this Claude Code session on the same machine is the normal case, reading the
handshake file directly. When the IDE is on another machine — a laptop reached over SSH — both
the handshake file and the handoff file are local paths on that other machine, so there is
nothing on this machine to read directly. The endpoint's `fetch` action carries the handoff
file's content back in the HTTP response body instead, and an SSH tunnel is what lets this
machine reach that endpoint at all. Here is what the person needs to do, and what to tell the
agent:

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
  `127.0.0.1`.
- **A missing tunnel looks like connection refused**, and that is the whole of the error handling.
  The plugin does not manage the tunnel, does not detect it and does not report on it.
- **Restarting the IDE is what invalidates the token.** Re-opening a project rewrites the
  handshake file with the same token, because the token is minted once per IDE run.

## The watcher script

`watch-remarks.sh`, beside this file, is what both wait loops below use instead of polling inline.
The reason is the mechanic every long wait in this skill runs into: a foreground Bash call is
capped at ten minutes, but a **background** command has no such cap, keeps running across turns,
and re-invokes this session when it **exits**. So the watcher has to exit on its own event and must
never loop forever — a background command that never exits never notifies, and the session waits
for a signal that cannot arrive. Launch it with a background Bash call, never a foreground one, and
read what it printed once it exits.

**Two forms, one per branch of the wait:**

```
watch-remarks.sh --file <path> [--seen <nonce>] [--require-review <session>]
                  [--deadline <seconds>] [--poll <seconds>]
watch-remarks.sh --fetch <base_url> --session <id> --project <path>
                  [--seen <nonce>] [--deadline <seconds>] [--poll <seconds>]
```

- `--file <path>` is the local branch: poll the published file directly. Default poll interval 2
  seconds.
- `--fetch <base_url> --session <id> --project <path>` is the remote branch: poll
  `POST <base_url>/fetch` the same way step 6 below does by hand. Default poll interval 5 seconds,
  because the built-in server allows 30 requests a minute from one address. `--require-review` is
  not accepted here — the fetch endpoint already answers `ready` only for the session named in the
  request, so there is nothing left to filter client-side.
- `--seen <nonce>` is the nonce already known. Omit it, or pass an empty string, to mean "any batch
  is new."
- `--require-review <session>` (file mode only) makes the watcher keep waiting until the batch's
  `review:` header field equals that session, rather than reporting the first new batch it sees.
- `--deadline <seconds>` defaults to 1800. Listen mode passes 43200 (twelve hours).
- The token for `--fetch` is read from `CLAUDE_REMARKS_TOKEN` in the environment, never from an
  argument — an argument is visible to every process on the machine through `ps`, and the token is
  the only gate on the endpoint.

**Exit codes.** `0`, with the whole published file on stdout (header included), when a new batch
arrived. `1`, with one sentence, when the deadline passed with nothing new. `2`, with a reason, for
anything wrong: a file it cannot read, a header whose first line is not the marker or whose second
line does not start with `nonce: ` (which means the plugin that wrote it is older than this skill),
an HTTP status other than 200, or a `too-large` answer.

**One watcher per project on the machine.** On start it writes its own pid to
`~/.claude-remarks/<the file's own 16 hex characters>.watch`, creating that directory
`rwx------` first if the plugin has never run here. If a pid is already there and still belongs to
a live `watch-remarks.sh` process, it kills that process and waits for it to actually exit before
taking over — whichever session started it. It removes its own pid file when it exits, on every
exit path.

## Steps

1. **Find the repository root, and set the connection values if the IDE is on another machine.**

   ```sh
   # Remote case only: the IDE is on another machine, reached through an SSH tunnel this shell does
   # not manage. Leave ide_port EMPTY for the normal same-machine case and the rest is ignored.
   ide_port=          # the tunnel's local port ON THIS MACHINE, e.g. 8765
   ide_token=         # the token from the IDE machine's handshake file
   ide_project=       # the repository path as the IDE MACHINE sees it; empty means use this machine's
   ide_host=127.0.0.1 # the near end of the tunnel; change only if you tunnelled somewhere else

   root=$(git rev-parse --show-toplevel 2>/dev/null)
   [ -n "$ide_project" ] || ide_project=$root
   [ -n "$ide_project" ] || {
     echo "this directory is not in a git repository. The IDE then knows the project by its base"
     echo "path — the directory holding .idea. Ask the person for it and set ide_project to it."
     exit 1
   }
   ```

   `root` returns the physical path even for a symlinked checkout, which matters: the IDE side
   normalizes the same way, so the two have to agree. `$root` stays this machine's own path,
   because the file list in step 3 is built from this machine's own git; `$ide_project` is what
   goes in the request's `project` field and in every `ack` and `fetch` body. The two are the same
   string in the same-machine case, which is why the default is `$root`.

   **The git top level is what the IDE knows this project by, whatever directory it was opened on.**
   A project opened on a module below the repository root is still identified by the repository, so
   `$root` matches it. The one case `git rev-parse` cannot answer is a project in no git repository
   at all; the IDE then falls back to the project base path, which is why the third line above stops
   and asks for it instead of carrying on with an empty string.

2. **Compute the connection values: the tunnel values set above if `ide_port` is non-empty,
   otherwise the handshake file.**

   ```sh
   if [ -n "$ide_port" ]; then
     remote=yes
     host=$ide_host ; port=$ide_port ; token=$ide_token
     case $ide_port in *[!0-9]*|'') echo "ide_port must be a port number: '$ide_port'"; exit 1;; esac
     [ -n "$token" ] || { echo "the remote case needs the IDE run's token — read it on the IDE machine, see 'Over SSH' above"; exit 1; }
     echo "remote: $host:$port, project $ide_project"   # never echo the token
   else
     remote=
     host=127.0.0.1
     # $ide_project, not $root: the two are the same string here unless the person had to name the
     # project path by hand, which is the one case where $root is empty or is not what the IDE knows
     # this project by. The plugin hashes the same value it matches requests against.
     name=$(printf %s "$ide_project" | shasum -a 256 | cut -c1-16)
     handshake="$HOME/.claude-remarks/$name.json"
     [ -f "$handshake" ] || { echo "no IDE has $ide_project open (no handshake file at $handshake)"; exit 1; }
     port=$(jq -r .port "$handshake")
     token=$(jq -r .token "$handshake")
   fi
   base_url="http://$host:$port/api/claude-remarks"
   ```

   A missing handshake file means no running IDE has this repository open right now — not that
   the plugin is broken. Do not retry and do not scan ports; re-opening the project in the IDE is
   what creates this file. In the remote case there is no handshake file to read on this machine
   at all — see "Over SSH" above for where the person reads the port and the token.

3. **POST to the start endpoint.** **Run steps 1 to 6 as one Bash call, in one shell** — every step,
   from `git rev-parse` onwards, not just this one and the ones after it. `root` and `ide_project`
   are set in step 1, and `base_url`, `port` and `token` in step 2, and this step needs `base_url`,
   `token` and `ide_project`. Split them off and this step posts to an empty string in place of a
   URL, with an empty token and an empty project. The Bash tool starts a new shell for every call,
   and nothing crosses that boundary: no variables and no shell functions. Steps 4 and 5 are
   decisions about the answer this step writes to a file, so they cost nothing extra inside the
   same shell, and step 6 needs `$session`, `$base_url`, `$token`, `$ide_project`, `$remote` and
   `deadline_seconds` — all set here or in step 1 or 2 — plus `$output`, which step 6 reads out of
   the response body this step saved. Split across calls, step 6 posts to that same empty URL with
   an empty token and waits for a file called `""`.

   **Work out the file list before you POST, and check it is not empty.** The IDE opens the paths
   this request names. An empty list opens nothing at all, silently: the endpoint still answers
   `waiting`, the banner still appears, and the person sits in front of an IDE where nothing
   happened while this skill waits for remarks about files it never asked for. That has happened for
   real. So decide which of the four shapes below applies, run its command, and count the result.

   **Each shape sets two variables, `files_json` and `about_a_diff`, and the guard below reads
   them.** Copy one shape whole. Setting only the first leaves the guard with no answer, and the
   guard stops rather than guessing.

   **One commit** — the shape that is easy to get wrong. Use `git show`, never `git diff`:

   ```sh
   commit=PUT_THE_COMMIT_ID_HERE          # this line is not optional
   files_json=$(git show --name-only --format= "$commit" \
     | jq -R -s -c 'split("\n") | map(select(length > 0))')
   about_a_diff=yes
   ```

   `git diff --name-only <commit>` does NOT list that commit's files. It diffs that commit against
   the working tree, so it prints everything changed *since* it — and prints nothing at all when the
   tree already sits at that commit. Both answers are wrong and neither looks like an error.

   **A range of commits** — two dots for "what changed on this side", three for "since they
   diverged", the same distinction `git log` uses:

   ```sh
   base=PUT_THE_BASE_HERE                 # neither is optional
   tip=PUT_THE_TIP_HERE
   files_json=$(git diff --name-only "$base"..."$tip" \
     | jq -R -s -c 'split("\n") | map(select(length > 0))')
   about_a_diff=yes
   ```

   **Uncommitted work** — what the person has edited but not committed. This is the only shape the
   IDE can open as a real diff (see below):

   ```sh
   files_json=$(git diff --name-only HEAD \
     | jq -R -s -c 'split("\n") | map(select(length > 0))')
   about_a_diff=yes
   ```

   **Nothing in particular** — a review that is not about a diff at all. Then, and only then:

   ```sh
   files_json="[]"
   about_a_diff=no
   ```

   Every command above prints paths relative to the repository root, which is what the endpoint
   expects. Now check the list before sending it:

   ```sh
   # about_a_diff is set by the shape above and by nothing else. It used to be assigned here, always
   # to yes, which made the "nothing in particular" shape impossible to run at all: it tripped the
   # empty-list guard below every time and exited 1.
   case $about_a_diff in
     yes|no) ;;
     *) echo "about_a_diff was never set — copy one of the four shapes above whole"; exit 1;;
   esac
   files_count=$(printf %s "$files_json" | jq 'length')
   if [ "$about_a_diff" = yes ] && [ "$files_count" -eq 0 ]; then
     echo "the file-list command found nothing, so there is nothing to review — not starting a review"
     exit 1
   fi
   # The IDE keeps at most 20 paths and says nothing about the rest, so cap here and be honest.
   if [ "$files_count" -gt 20 ]; then
     echo "note: $files_count files, the IDE opens only the first 20"
     files_json=$(printf %s "$files_json" | jq -c '.[0:20]')
     files_count=20
   fi
   ```

   **Both guards are code on purpose.** They were prose once — "if the count is 0, stop here" — and
   prose cannot stop a script. Step 3 mandates one Bash call, so there is no moment between building
   the list and posting at which the agent gets to decide; whatever the shell does not check, nothing
   checks. An empty list would then reach the endpoint, the banner would appear over an IDE where
   nothing opened, and this skill would poll for the whole deadline. Found twice, the second time
   after the first fix.

   **What the person will actually see.** For uncommitted work the IDE opens one real diff window
   holding just these files, with next-file navigation inside it. For a commit or a range it opens a
   plain editor per file instead — `ChangeListManager` only knows about uncommitted changes, so a
   committed one has no diff for the IDE to show. That is a known limit, not a failure: say plainly
   which of the two the person is getting, so an editor where they expected a diff is not read as a
   bug.

   ```sh
   session=$(uuidgen)
   deadline_seconds=1800
   label="what is being reviewed, in a few words"   # replace this with the real thing
   start_resp=$(mktemp) ; ack_resp=$(mktemp) ; fetch_resp=$(mktemp)
   body=$(jq -n --arg session "$session" --arg label "$label" --arg project "$ide_project" \
     --argjson files "$files_json" --argjson deadline "$deadline_seconds" \
     '{session:$session, label:$label, project:$project, files:$files, deadlineSeconds:$deadline}')
   start_post() {
     curl -s -o "$start_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
       -X POST "$base_url/start" \
       -H "X-Claude-Remarks-Token: $token" -H "Content-Type: application/json" \
       -d "$body"
   }
   http_code=$(start_post)
   # The one retry step 4 describes, as code. The check below exits on any non-200, so a retry that
   # lived only in prose could never run: the script was already gone by the time anyone read it.
   # 429 is the built-in server's own rate limit, 30 requests a minute from one address.
   if [ "$http_code" = 429 ]; then
     echo "start: http 429 — the IDE is rate limiting; waiting 20 seconds and retrying once"
     sleep 20
     http_code=$(start_post)
   fi

   # Print both before deciding anything: steps 4 and 5 are about these two values, and if the
   # script does not print them the agent never sees them.
   echo "start: http $http_code"
   cat "$start_resp" ; echo
   start_status=$(jq -r '.status // empty' "$start_resp" 2>/dev/null)
   if [ "$http_code" != 200 ] || [ "$start_status" != waiting ]; then
     echo "the review did not start — see the status above and step 4 or 5 for what it means"
     exit 1
   fi
   ```

   **`mktemp`, not a fixed path in `/tmp`.** Two review sessions running at once would otherwise
   overwrite each other's response file, and one could read the other's `output` path and wait on the
   wrong review. A predictable name in a shared temp directory can also be pre-created as a symlink by
   another local user. The plugin refuses predictable paths for the handoff file for exactly these two
   reasons; the skill side has to hold the same line.

   **The `http_code` and `status` check is code, not prose, for the same reason the file-list guard
   is.** Steps 4 and 5 below say what each outcome means and what to tell the person — they are for
   *reporting*, and they cannot gate anything, because the whole flow is one shell. Without this
   check a `conflict` or a 403 would fall straight through to the wait loop and the only thing you
   would see is "the waiting response carried no output path".

   `$label` is a short description of what is being reviewed — shown to the person in the IDE
   banner. `$session` is invented once per run of this skill, so a retry of the same run reuses
   it rather than starting a second review. `files` carries the list built above; an empty array is
   only correct for a review that is not about a diff, which the check above has already settled.
   `deadline_seconds` is how long step 6 below will wait, declared here rather than left as a
   private literal: the IDE stops showing "Claude Code is waiting" once this many seconds have
   passed, and the only way to guarantee the IDE's clock and this script's clock agree is to send
   the same number to both. The IDE clamps whatever arrives to between 60 seconds and 24 hours, so
   a nonsense value here is corrected rather than obeyed.

   **Do not name that variable `status`.** In zsh `status` is a read-only special variable, an
   alias for `$?`, so `status=$(curl ...)` fails with "read-only variable: status". Worse than
   failing outright: zsh runs the command substitution first and only then refuses the
   assignment, so the POST is sent, a review really does start in the IDE, and the script dies
   believing nothing happened. The next attempt then gets `conflict` and the cause looks like a
   stuck review rather than a shell error. Found on 2026-08-03, on the first real end-to-end run.
   The same rule holds for every variable added below: `deadline_seconds`, `deadline`, `ack_code`
   and `ack_answer` — none of them collides today, but do not rename any of them to `status`, and in
   particular do not call the acknowledgement's `status` field `$status`.

   Never use `curl -f`: it throws the body away on a non-2xx response, and the body is exactly
   what carries the application-level outcomes in step 5. Never add `-H Origin:` or
   `-H Referer:` — `curl` sends neither by default, and the endpoint refuses the request outright
   if either is present.

4. **Check the HTTP status before looking at the body.** Three non-200 outcomes are reachable,
   and none of them carries a `status` field — reading the body first on one of these means
   reading nothing and hanging until the wait in step 6 times out.

   - **403** — the token was refused. This is not usually an attack: the token is minted once per
     IDE run, but the handshake file survives an IDE that was killed rather than closed normally.
     A restarted IDE on the same port answers 403 to the old token. Tell the person to re-open the
     project — that rewrites the handshake file — and stop.
   - **429** — the built-in server's own rate limit, 30 requests per minute by default. The script
     in step 3 has already waited 20 seconds and retried the POST once by the time you read the
     status, so a 429 still showing here is the second one: report it and stop.
   - **404** — nothing claimed the request. Either the Claude Remarks plugin is not installed in
     that IDE, or the request was not a POST. Report it and stop.
   - anything else, or a 200 whose body does not parse as JSON — report the HTTP status and the
     raw body verbatim and stop. Do not guess what it means.

5. **On a 200 with parseable JSON, read `status`.** It is exactly one of five values:

   - `"waiting"` — accepted. Read `output` from the body; that is the path to wait for in step 6.
   - `"conflict"` — another review is already waiting in that IDE. The body carries `label` and
     `startedAt` for the one already there. Report it and stop; do not retry and do not wait.
   - `"unknown-project"` — the IDE does not have this repository open under the path this skill
     sent. The body carries `open`, the list of projects the IDE says are open. Report it and
     stop.
   - `"bad-request"` — the body carries `detail`. This means this skill and the plugin disagree
     about the shape of the request, which is a bug in one of them, not a transient failure.
     Report the detail and stop; do not retry.
   - `"failed"` — the IDE accepted the request and then could not set the review up, almost always
     because it could not create its temp directory. The body carries `detail`. Report it and stop;
     no review is waiting and nothing will be written.

6. **Wait for the handoff file, tell a rejection from remarks, then acknowledge.** The first line
   below is what puts `output` in hand: it is the path from the `waiting` response of step 3, read
   back out of the file `curl` saved it to. In the same-machine case it is also the path the loop
   waits on. In the remote case it is a path on the IDE machine, printed for the person but never
   tested with `-e` on this machine — see the comment above `handoff_ready` for why. Either way an
   empty `$output` fails right below, before either branch runs.

   ```sh
   output=$(jq -r .output "$start_resp")
   [ -n "$output" ] && [ "$output" != null ] \
     || { echo "the waiting response carried no output path"; exit 1; }

   # Where the remarks will be readable on THIS machine, and how often to look.
   # Same machine: the file the IDE wrote. Remote: a local copy the fetch writes into.
   # In the remote case $output is a path on the IDE MACHINE, so it is only ever printed, never
   # tested with -e. Five seconds, not one: the IDE's built-in server allows 30 requests a minute
   # from one address, shared with everything else talking to it, and a 429 is not something to
   # hit on purpose.
   if [ -n "$remote" ]; then handoff=$(mktemp); poll_seconds=5; else handoff=$output; poll_seconds=1; fi

   ack() {
     jq -n --arg session "$session" --arg project "$ide_project" --arg event "$1" \
       '{session:$session, project:$project, event:$event}' \
     | curl -s -o "$ack_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
         -X POST "$base_url/ack" \
         -H "X-Claude-Remarks-Token: $token" -H "Content-Type: application/json" -d @-
   }
   trap 'ack abandoned >/dev/null' EXIT
   trap 'ack abandoned >/dev/null; trap - EXIT; exit 130' INT TERM

   # 0 = the remarks are now in $handoff. 1 = not yet, keep waiting. 2 = stop, the reason is printed.
   # A 429 sets backoff_seconds so the loop below sleeps 20 seconds instead of poll_seconds for that
   # one iteration only; it must not also sleep here, or the real wait becomes poll_seconds longer
   # than the 20 seconds this file and design.md both document.
   handoff_ready() {
     if [ -z "$remote" ]; then
       [ -e "$handoff" ] || return 1
       return 0
     fi
     fetch_code=$(jq -n --arg session "$session" --arg project "$ide_project" \
         '{session:$session, project:$project}' \
       | curl -s -o "$fetch_resp" -w '%{http_code}' --connect-timeout 5 --max-time 30 \
           -X POST "$base_url/fetch" \
           -H "X-Claude-Remarks-Token: $token" -H "Content-Type: application/json" -d @-)
     if [ "$fetch_code" = 429 ]; then
       echo "the IDE is rate limiting (30 requests a minute from one address); backing off"
       backoff_seconds=20
       return 1
     fi
     if [ "$fetch_code" != 200 ]; then
       echo "fetch: http $fetch_code"; cat "$fetch_resp"; echo
       echo "see step 4 for what this HTTP status means"
       return 2
     fi
     fetch_answer=$(jq -r '.status // empty' "$fetch_resp" 2>/dev/null)
     case $fetch_answer in
       ready)
         # -j so jq adds no trailing newline: the copy is then byte-identical to the file the IDE wrote.
         jq -j -r .content "$fetch_resp" > "$handoff" \
           || { echo "the fetched body could not be read as JSON — it was probably cut off"; return 2; }
         return 0 ;;
       waiting) return 1 ;;
       too-large)
         echo "the review is too big to send through the tunnel: $(jq -r '"\(.bytes) bytes, limit \(.limit)"' "$fetch_resp")"
         echo "the remarks are still pending in the IDE. The file is at $output on the IDE machine."
         echo "Ask the person to read it there, or to send fewer remarks."
         return 2 ;;
       *)
         echo "fetch answered '$fetch_answer'"; cat "$fetch_resp"; echo
         return 2 ;;
     esac
   }

   deadline=$(( $(date +%s) + ${deadline_seconds:-1800} ))
   while :; do
     handoff_ready && break
     ready_status=$?
     [ "$ready_status" -eq 2 ] && exit 1
     [ "$(date +%s)" -ge "$deadline" ] && { echo "timed out waiting for the IDE"; exit 1; }
     sleep "${backoff_seconds:-$poll_seconds}"
     backoff_seconds=
   done
   cat "$handoff" || { echo "the handoff file could not be read"; exit 1; }
   trap - EXIT INT TERM

   if [ "$(head -1 "$handoff")" = '<!-- claude-remarks: rejected -->' ]; then
     echo "the person rejected this review; no remarks were sent"
     exit 0
   fi
   ack_code=$(ack read)
   ack_answer=$(jq -r .status "$ack_resp" 2>/dev/null)
   echo "ack read: http $ack_code, status $ack_answer"
   ```

   **`while :; do handoff_ready && break; ready_status=$?` and never `while ! handoff_ready`.**
   `!` collapses every non-zero status to 1, so the three-way answer above would become two-way
   and a hard stop (`too-large`, a bad HTTP status, a body that will not parse) would be read as
   "keep waiting" until the deadline instead of stopping now. With `&& break` the compound
   command's own status is `handoff_ready`'s own status, not a flattened one.

   Checking existence is enough for the same-machine branch: the plugin writes the file's full
   content to a temp file beside it and renames the temp file onto this path, and a
   same-filesystem rename is atomic on POSIX. So there is no partial state to observe — the file
   is either absent or complete, never half-written. Do not "improve" this into a size check or a
   lock file; the atomic rename is what makes the plain existence check correct. The remote branch
   has a different guarantee for the same problem: JSON is self-delimiting, so a response cut off
   by a dead tunnel makes `jq -j -r .content` fail rather than write a half prompt, which is what
   turns a truncated fetch into "the fetched body could not be read as JSON" instead of a silent
   partial copy.

   Six things about this block are load-bearing, and each one is a decision somebody will
   otherwise undo:

   - **`ack read` captures the answer; the trap's `ack abandoned` throws it away on purpose.** The
     read acknowledgement is the one request whose answer changes what to report — it is what marks
     the remarks sent in the IDE — so its HTTP code and its `status` field are both kept, in
     variables named `ack_code` and `ack_answer`. The trap runs while the shell is already leaving,
     with nowhere left to report to, so it discards its output instead.

   - **The trap is set only after a `waiting` response.** Before that there is no review to
     abandon, and an acknowledgement for a review that does not exist just gets `no-review`.
   - **The trap is cleared after `cat` succeeds and before `ack read`, and `cat` is checked so that
     "succeeds" is a fact rather than a hope.** Once the content is read, the read really has
     happened — even if the acknowledgement request then fails. Clearing the trap first means a
     failing `ack read` leaves the IDE to its own deadline, which keeps the remarks pending. The
     other order would tell the IDE the agent left after it had already read them. Without the `||`
     on `cat` the sentence above was a claim nothing enforced: an unreadable file would fall
     through to `ack read`, and the IDE would mark the remarks sent to an agent that never saw a
     byte of them.
   - **The `INT`/`TERM` handler ends with `exit 130`; the `EXIT` one must not.** A trap handler that
     returns without exiting hands control back to the interrupted command, so an interrupted wait
     would carry on polling, then read the file and send `ack read` — after having already told the
     IDE the agent abandoned the review. The handler also clears the `EXIT` trap before exiting, or
     leaving the shell would send a second `ack abandoned`. `EXIT` is separate because it fires on
     every exit path, including the clean ones, and must not itself exit.
   - **`trap - EXIT INT TERM` restores the default; it does not run the handler.** Writing
     `trap "" EXIT` instead would also work but reads as "run nothing", which is easy to misread
     as "run the old thing".
   - **The trap covers this one shell, which is why steps 1 to 6 belong in one Bash call.** It
     catches a timeout inside this loop and an interrupt of this command. An agent process killed
     between two Bash calls sends nothing at all, and the IDE's own deadline is what covers that
     case instead. `${deadline_seconds:-1800}` is a seatbelt for exactly that mistake: split across
     calls, the bare arithmetic would leave `deadline` empty, `[ "$(date +%s)" -ge "" ]` would never
     fire, and the loop would poll silently until the Bash tool's own timeout.
   - **The rejection check comes before the acknowledgement, and it compares the file's FIRST line,
     not any line.** `[ "$(head -1 "$handoff")" = '<!-- claude-remarks: rejected -->' ]`. This was
     `grep -q '^<!-- … -->'` and that was wrong: `^` anchors to the start of *a* line, and a remark's
     own text starts lines too. The prompt renderer escapes fences and heading characters but not
     `<!--`, so a remark that quotes the marker — writing about this protocol is enough — was read as
     a rejection, and a real review was thrown away with "the person rejected this review". The
     plugin's contract has always been first-line-only, so match that. There is nothing to
     acknowledge on a rejection: the IDE cleared the review as it wrote the file, so an `ack read`
     would only be answered `no-review`. The trap is cleared before this branch, so a rejection does
     not also report the agent as having left.

   **A rejection is a finished review, not a failure.** `exit 0`, and report it plainly to the
   person the way any other answer is reported. Do not retry, do not start a second review, and do
   not treat the body as remarks.

   If waiting times out: nothing is lost. The remarks are still sitting in the IDE's tool window,
   marked pending — they were never marked sent, because sending only writes the file, and marking
   sent waits for this step's `ack read` — and the person can send them again or copy them by
   hand. The `trap` above already sent `ack abandoned` on the way out, so the IDE's banner clears
   itself; there is nothing left to do by hand from the banner's Reject link for this run.

   **What the acknowledgement answers:** `ok`, `no-review` (nothing is waiting under that session
   — the review was cancelled, expired, or already finished), `not-sent` (a read acknowledgement
   for a review whose file was never written, which is a bug in one of the two sides),
   `unknown-project`, `bad-request`. `$ack_answer` above holds it, and `$ack_code` holds the HTTP
   status for the cases that carry no `status` field at all — see step 4 for those.

   On anything other than `ok`, say so plainly and name the value, then still do step 7: the remarks
   were really read, and they are still in hand. What the non-`ok` answer means for the person is
   that the IDE never marked them sent, so they are still pending in the tool window and can be sent
   again. Do not retry the acknowledgement more than once — the IDE's own deadline already covers a
   lost one — and do not start a second review.

7. **Read the file and act on it.** It is one markdown prompt built the same way "Copy All
   Pending" builds one — remarks grouped by file, each with its severity, its tag and the code it
   points at. Act on it, then say plainly what was done, the same way you would after reading any
   other review feedback.

## What to say if something goes wrong

- Missing handshake file: "No IDE has this repository open right now. Open the project in
  IntelliJ (or another JetBrains IDE with the Claude Remarks plugin) and try again."
- 403: "The IDE at this port answered with a stale token — re-open the project in the IDE, which
  writes a fresh handshake, then try again."
- Timeout waiting for the handoff file: "No remarks arrived within the declared deadline
  (`deadline_seconds`, 1800 by default). Nothing is lost — they
  are still pending in the IDE, never marked sent. Send to Claude Code again when ready, or paste
  them here."
- The person rejected the review: "The review was rejected in the IDE. No remarks were sent."
  Stop; do not retry and do not start a second review for the same request.
- An acknowledgement answers anything other than `ok`: report the outcome (`no-review`,
  `not-sent`, `unknown-project`, `bad-request`) and the body verbatim, and add that the remarks were
  read here but stay marked pending in the IDE, so the person can send them again.
- `start` answers `failed`: "The IDE could not open a review session: <detail>." No review is
  waiting, so there is nothing to wait for and nothing to reject.
- No tunnel in the remote case (connection refused): "There is no tunnel reaching the IDE machine
  at this host and port. On the IDE machine, start one with
  `ssh -o ExitOnForwardFailure=yes -R <port>:127.0.0.1:<the IDE's port> <this machine>`, then try
  again." `ExitOnForwardFailure=yes` matters here: without it a taken port on this machine makes
  `ssh` connect anyway with no forwarding, and every request after that answers connection refused
  for a reason that looks nothing like the real cause.
- `fetch` answers `too-large`: "The review is too big to send through the tunnel (`<bytes>` bytes,
  limit `<limit>`). The remarks are still pending in the IDE, at `<output>` on the IDE machine. Ask
  the person to read them there, or to send fewer remarks." Not a failure to retry — the review
  cannot be re-sent from the IDE either, so this stops here.
- `fetch` answers `unknown-project` in the remote case: "The two machines disagree about where the
  repository lives. The response's `open` list names the paths the IDE has open — pass one of
  those as `ide_project` and try again." This is the normal first failure of the remote case, not
  a rare mistake.
