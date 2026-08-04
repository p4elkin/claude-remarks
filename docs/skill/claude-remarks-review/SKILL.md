---
name: claude-remarks-review
description: >
  Read remarks a person wrote in the Claude Remarks IntelliJ plugin. Three ways, and only the
  first two are used without being asked. Read a published file: use when asked to read the
  remarks someone published, read published remarks, look at the remarks they just published from
  the IDE, check whether anything was published for this repository, or act on remarks handed over
  through Publish All Pending or Publish Selected — no review is started and nothing is waited
  for, because the plugin already wrote them to a file under ~/.claude-remarks that this skill
  reads directly, and it also acknowledges the batch it read. Hand a review over and wait: use
  when asked to start a review session with the IDE, wait for review comments from an open
  IntelliJ/JetBrains project, or read back remarks the person just sent with "Send to Claude
  Code" from the Claude Remarks tool window. Listen for the next batch: start this only when a
  person asks, in words, to watch or listen for remarks — never on your own initiative, never
  because a published file or a waiting review was noticed. It watches the same published file
  and reports each new batch as it arrives, acting on nothing published before listening started.
  The IDE and this skill running on the same machine is the normal case for all three. When the
  IDE is on another machine, reached over SSH, review mode needs a tunnel the person sets up by
  hand and four connection values from them, which this skill can also store so they are not
  retyped every run — see "Over SSH: the IDE on another machine" below.
---

# Claude Remarks review

Three ways to get a person's remarks out of the Claude Remarks tool window, and they do not
overlap.

- **They already published.** The person pressed Publish All Pending or Publish Selected, which
  wrote the remarks to a file. Nothing was started and nothing is waiting. Read the file,
  acknowledge the batch, act on it, done. That is the next section, and it is the whole of that
  mode.
- **Hand a review over and wait for it.** This skill starts a review, the IDE shows a banner, the
  person answers by pressing "Send to Claude Code", and this skill waits for that. That is
  `## Steps` below, and the section before it covers the case where the IDE sits on another
  machine.
- **Listen for the next batch.** Watch the published file and report each new batch as it comes
  in, with no review started and nothing sent anywhere else. Opt-in only: start this because a
  person asked, in words, to watch or listen, never because a published file was noticed or a
  review looked interesting. That is `## Listen for the next batch`, right after the next section.

Pick the first when the person says they published something or asks for remarks that already
exist. Pick the second when the person is being asked to review something now. Pick the third only
when the person asks, in those words or plainly equivalent ones, to be watched or listened to.

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
flow in `## Steps`, and every name in it starts with `pub_` so it cannot collide with one. It
reads the local handshake file to acknowledge the batch, but no remote connection value — this
mode reads the published file straight off this machine's disk, so it is same-machine only, unlike
review and listen mode.

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
pub_first=$(sed -n '1p' "$pub_file")
if [ "$pub_first" != '<!-- claude-remarks: published -->' ]; then
  echo "$pub_file is not a published-remarks file — its first line is not the published marker"
  echo "its first line is: $pub_first"
  exit 1
fi

# The header is fixed at eight lines: the marker, then nonce:, published:, commit:, remarks:,
# review:, label:, rejected:, then a blank line and the body. Addressed by line number rather than
# searched for, so a remark quoting "commit:" in its own text cannot be read as the header.
pub_nonce=$(sed -n '2s/^nonce: //p' "$pub_file")
pub_published=$(sed -n '3s/^published: //p' "$pub_file")
pub_commit=$(sed -n '4s/^commit: //p' "$pub_file")
pub_count=$(sed -n '5s/^remarks: //p' "$pub_file")
pub_review=$(sed -n '6s/^review: //p' "$pub_file")
pub_label=$(sed -n '7s/^label: //p' "$pub_file")
pub_rejected=$(sed -n '8s/^rejected: //p' "$pub_file")
# The first 8 characters of the full sha, never `--short=8`: for git, 8 is a floor, and it prints
# more characters as soon as 8 are not unique in this repository. The plugin always writes exactly
# 8, so `--short=8` would print a longer string, the comparison below would differ, and the STALE
# block would fire for remarks published against this very commit.
pub_head=$(git rev-parse HEAD 2>/dev/null | cut -c1-8)

echo "published: ${pub_published:-unknown}, ${pub_count:-unknown} remarks"
echo "published at commit ${pub_commit:-unknown}; this checkout is at ${pub_head:-unknown}"
if [ -n "$pub_review" ] && [ "$pub_review" != none ]; then
  echo "this batch also answers review session $pub_review (label: ${pub_label:-none})"
fi
if [ -n "$pub_commit" ] && [ "$pub_commit" != none ] \
   && [ -n "$pub_head" ] && [ "$pub_commit" != "$pub_head" ]; then
  echo "STALE: these remarks were published against $pub_commit, not against $pub_head."
  echo "The code they point at has moved since. Re-read every line a remark names before acting,"
  echo "and say plainly in the report that the remarks predate the current checkout."
fi

if [ "$pub_rejected" = yes ]; then
  echo
  echo "rejected: yes — this batch is a review's rejection record, not remarks. Nothing to"
  echo "acknowledge and nothing to act on. Stop here."
  exit 0
fi

# Acknowledge before acting: READ means an agent read the remarks, not that the work is finished.
# Keyed to the batch's own nonce, never to a review session, since a publish can happen with no
# review waiting at all. Needs the local handshake file, the same one step 2 of the review flow
# reads — with no IDE open there is none, and that is said plainly rather than failing.
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
    pub_ack_code=$(jq -n --arg session "$pub_session" --arg project "$pub_root" --arg nonce "$pub_nonce" \
        '{session:$session, project:$project, nonce:$nonce}' \
      | curl -s -o "$pub_ack_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
          -X POST "http://127.0.0.1:$pub_port/api/claude-remarks/published-read" \
          -H "X-Claude-Remarks-Token: $pub_token" -H "Content-Type: application/json" -d @-)
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
cat "$pub_file"
```

**Say the stale line out loud when it appears.** A published file is overwritten by the next
publish and by nothing else, so it can be hours old and can describe code that has since changed.
The commit comparison is the only signal that this happened, and it is worth nothing if it is
printed and then not reported.

**What this mode does, and does not, do.**

- **It does post to the endpoint, and it does read a token — both only for the acknowledgement
  above.** `published-read` names a batch's own nonce, not a review session, so this can be sent
  with no review anywhere in the picture. The token comes from the local handshake file, read the
  same way step 2 of the review flow reads it, and only when that file exists — see the code above
  for what happens when it does not.
- **Do not delete the file after reading it.** The acknowledgement above may never reach the IDE at
  all — no handshake file, or a stale token — and even when it does, the file itself stays the only
  copy outside the IDE. A second agent, or the same one after a restart, reads the same file until
  the next publish overwrites it.
- **Do not set a trap.** The traps in step 6 of the review flow exist to tell the IDE that an agent
  walked away from a review it was waiting on. Nothing is waiting here — a publish is not a review
  — so there is nothing to abandon and nothing to tell.

Then act on the remarks the same way step 7 describes: it is one markdown prompt, remarks grouped
by file, each with its severity, its tag and the code it points at. Act on it, then say plainly
what was done.

**If the file is missing**, say so and stop: "Nobody has published remarks for this repository. In
the IDE, press Publish All Pending (or Publish Selected) in the Claude Remarks tool window, then
ask again." Do not start a review instead — that is a different thing, and it puts a banner in
front of a person who was not asking to be interrupted.

## Listen for the next batch

Never started on this skill's own initiative — only when a person asks, in words, to watch or
listen for the next batch of remarks. Noticing a published file, or a review waiting, is not
asking. Unlike the two modes above, this one runs open-ended in the background instead of
answering once.

**What it never does.** It never posts to `/start` — it never starts a review — and it never posts
to `/ack` — there is no review to acknowledge. The only request it ever sends is `published-read`,
the same acknowledgement the one-shot mode above sends, keyed to a batch's own nonce.

**Starting it.** Run this as one Bash call, self-contained the same way the one-shot mode above is
— every name in it starts with `listen_` so it cannot collide with that mode or with the review
flow in `## Steps`.

```sh
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
listen_seen=
if [ -f "$listen_file" ]; then
  listen_line=$(sed -n '2p' "$listen_file")
  case "$listen_line" in "nonce: "*) listen_seen=${listen_line#"nonce: "} ;; esac
  echo "a batch is already sitting in $listen_file (nonce ${listen_seen:-unknown}). Read it now,"
  echo "with the one-shot mode above, if it is wanted — listening starts from here and will not"
  echo "act on it."
fi
echo "listen_session=$listen_session"
echo "listen_file=$listen_file"
echo "listen_seen=$listen_seen"
echo "watching $listen_file, up to twelve hours, until the next new batch. Kill"
echo "~/.claude-remarks/$listen_name.watch's pid to stop early."
echo "run this next, as its own Bash call, marked background:"
printf "  watch-remarks.sh --file '%s' --seen '%s' --deadline 43200\n" "$listen_file" "$listen_seen"
```

**`--deadline 43200` is passed explicitly, always — twelve hours, not `watch-remarks.sh`'s own
1800-second default.** That default is sized for one review's wait, not for a person asking to be
watched over a working session. Leaving it off would silently reuse 1800 seconds, and listening
would stop after half an hour with no explanation. Say plainly, when listening starts, what is
being watched, that it stops after twelve hours with nothing new, and how to stop it early — both
printed by the block above.

**When the watcher exits, act on what it printed — built with `$listen_session`, `$listen_root`
and `$listen_name` typed again, since nothing carries a shell across two Bash calls:**

- **Exit 0.** What the watcher printed is the whole published file, header included. Read the
  eight-line header directly out of what it printed, by line number, the same way the one-shot
  mode above reads it off disk: line 2 is `nonce: `, line 6 `review: `, line 7 `label: `, line 8
  `rejected: `.
  - Line 6 is not `review: none` — a batch answering someone's review landed here. This is an
    anomaly: say so at the top of the answer, name the session, do not act on the remarks, and do
    not acknowledge them. The review's own `ack read` is what should mark those, not this mode.
  - Line 8 is `rejected: yes` — report it and do not acknowledge. There is nothing to mark read.
  - Otherwise, acknowledge it the same way the one-shot mode does, with `$listen_session` in place
    of `$pub_session` and line 2's nonce in place of `$pub_nonce`:

    ```sh
    listen_nonce=THE_NONCE_FROM_LINE_2_ABOVE
    listen_handshake="$HOME/.claude-remarks/$listen_name.json"
    if [ -f "$listen_handshake" ]; then
      listen_port=$(jq -r .port "$listen_handshake")
      listen_token=$(jq -r .token "$listen_handshake")
      listen_ack_resp=$(mktemp)
      listen_ack_code=$(jq -n --arg session "$listen_session" --arg project "$listen_root" \
          --arg nonce "$listen_nonce" '{session:$session, project:$project, nonce:$nonce}' \
        | curl -s -o "$listen_ack_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
            -X POST "http://127.0.0.1:$listen_port/api/claude-remarks/published-read" \
            -H "X-Claude-Remarks-Token: $listen_token" -H "Content-Type: application/json" -d @-)
      listen_ack_answer=$(jq -r '.status // empty' "$listen_ack_resp" 2>/dev/null)
      listen_ack_session=$(jq -r '.session // empty' "$listen_ack_resp" 2>/dev/null)
      echo "published-read: http $listen_ack_code, status ${listen_ack_answer:-unknown}"
      rm -f "$listen_ack_resp"
    else
      echo "no handshake file at $listen_handshake — can watch but cannot acknowledge. Say that"
      echo "plainly, then read the batch anyway."
    fi
    ```

    `already-read` naming a session other than `$listen_session` is an anomaly, the same shape as
    a foreign `review:` above: say so at the top, name that session, and do not act. `already-read`
    naming `$listen_session` itself is a retry after a lost response, not an anomaly — proceed as
    normal.
  - Then summarise the batch and what is planned, and **wait for the person to say go.** Do not
    act unattended, unlike the one-shot and review modes above — a listener runs unattended for
    hours, and nobody chose this exact moment for the work to start.
  - Re-arming — running `watch-remarks.sh` again to wait for the next batch after this one — is a
    choice, said out loud, run as its own new Bash call the same way the first one was. It is never
    automatic: the pid-file rule in `## The watcher script` still holds, one watcher per project on
    the machine, so re-arming without saying so risks two sessions each believing they own the
    listener.
- **Exit 1.** The twelve-hour deadline passed with nothing new. Report it and stop. There is
  nothing to acknowledge — `published-read` is never sent for a batch that never arrived.
- **Exit 2.** Something the watcher could not get past. Report what it printed verbatim and stop.

Nothing in listen mode ever sends `ack abandoned`: that request belongs to the review flow in
`## Steps` alone, keyed to a review session listen mode never has.

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
  `127.0.0.1`. The agent can store them so they are not retyped on every later run:
  `remote-config.sh save --port <port> --project <the IDE-machine path> [--host <host>]`, beside
  this file, with `CLAUDE_REMARKS_TOKEN` set to the token in its environment — never as an
  argument, which is world-readable through `ps`. It is keyed to **this** (the agent) machine's own
  repository root, so two repositories here never share one stored configuration. `show` prints
  back what is stored, without the token; `forget` deletes it. Step 1 below reads a stored file
  automatically; with none stored, all four still have to be pasted by hand exactly as before.
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
   # not manage. A stored configuration (remote-config.sh save, see "Over SSH" below) fills these
   # in automatically; with none stored all four stay empty and the normal same-machine case runs
   # untouched.
   ide_port=
   ide_token=
   ide_project=
   ide_host=127.0.0.1

   root=$(git rev-parse --show-toplevel 2>/dev/null)
   remote_conf=
   if [ -n "$root" ]; then
     remote_name=$(printf %s "$root" | shasum -a 256 | cut -c1-16)
     remote_conf="$HOME/.claude-remarks/remote-$remote_name.env"
     if [ -f "$remote_conf" ]; then
       # A whitelist parse, never `. "$remote_conf"` — sourcing runs the file, and a value holding
       # a space or a quote could change what a later line means. This only ever reads the four
       # names below and writes nothing.
       while IFS='=' read -r key value; do
         case "$key" in
           ide_host) ide_host=$value ;;
           ide_port) ide_port=$value ;;
           ide_project) ide_project=$value ;;
           ide_token) ide_token=$value ;;
         esac
       done < "$remote_conf"
     fi
   fi

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

3. **POST to the start endpoint.** **Run steps 1 to 5, plus the launch at the top of step 6, as one
   Bash call, in one shell** — every step, from `git rev-parse` onwards, not just this one and the
   ones after it. `root` and `ide_project` are set in step 1, and `base_url`, `port` and `token` in
   step 2, and this step needs `base_url`, `token` and `ide_project`. Split them off and this step
   posts to an empty string in place of a URL, with an empty token and an empty project. The Bash
   tool starts a new shell for every call, and nothing crosses that boundary: no variables and no
   shell functions. Steps 4 and 5 are decisions about the answer this step writes to a file, so
   they cost nothing extra inside the same shell, and the launch at the top of step 6 needs
   `$session`, `$base_url`, `$token`, `$ide_project`, `$remote`, `$name` (same-machine case only)
   and `deadline_seconds` — all set here or in step 1 or 2. Split across calls, this step posts to
   an empty URL with an empty token and an empty project, and step 6's launch line names a watcher
   that has nothing to watch.

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
   start_resp=$(mktemp)
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
   overwrite each other's response file, and one could read the other's `status` and believe its own
   review had started when it had not. A predictable name in a shared temp directory can also be
   pre-created as a symlink by another local user. The plugin refuses predictable paths for the
   published file for exactly these two reasons; the skill side has to hold the same line.

   **The `http_code` and `status` check is code, not prose, for the same reason the file-list guard
   is.** Steps 4 and 5 below say what each outcome means and what to tell the person — they are for
   *reporting*, and they cannot gate anything, because steps 1 to 5 are one shell. Without this
   check a `conflict` or a 403 would fall straight through to step 6's launch, and the watcher would
   be started for a review that was never accepted.

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
     IDE run, but the handshake file survives an IDE that was killed rather than closed normally,
     and a stored `remote-config.sh` file survives an IDE restart the same way. A restarted IDE on
     the same port answers 403 to the old token. In the same-machine case, tell the person to
     re-open the project — that rewrites the handshake file — and stop. In the remote case, name
     the stale file, `$remote_conf`, and say where a fresh token comes from: the `crtunnel` helper
     on the IDE machine prints it, or "Over SSH: the IDE on another machine" above is the by-hand
     route when that helper is not installed. Then re-save it —
     `remote-config.sh save --port <port> --project <path>` with `CLAUDE_REMARKS_TOKEN` set to the
     fresh value — and try again. In practice a re-save is what gets used, not `forget`, because
     the project path does not change when the IDE restarts.
   - **429** — the built-in server's own rate limit, 30 requests per minute by default. The script
     in step 3 has already waited 20 seconds and retried the POST once by the time you read the
     status, so a 429 still showing here is the second one: report it and stop.
   - **404** — nothing claimed the request. Either the Claude Remarks plugin is not installed in
     that IDE, or the request was not a POST. Report it and stop.
   - anything else, or a 200 whose body does not parse as JSON — report the HTTP status and the
     raw body verbatim and stop. Do not guess what it means.

5. **On a 200 with parseable JSON, read `status`.** It is exactly one of four values:

   - `"waiting"` — accepted. There is nothing else to read from the body: step 6 works out what to
     watch on its own, from `$ide_project` and, in the same-machine case, `$name` from step 2.
   - `"conflict"` — another review is already waiting in that IDE. The body carries `label` and
     `startedAt` for the one already there. Report it and stop; do not retry and do not wait.
   - `"unknown-project"` — the IDE does not have this repository open under the path this skill
     sent. The body carries `open`, the list of projects the IDE says are open. Report it and
     stop.
   - `"bad-request"` — the body carries `detail`. This means this skill and the plugin disagree
     about the shape of the request, which is a bug in one of them, not a transient failure.
     Report the detail and stop; do not retry.

   There is no `"failed"` any more: `start`'s handler no longer touches the filesystem at all, so
   `WaitingReviewService.start` only ever answers `Accepted` or `Conflict`, and there is nothing
   left for it to fail at.

6. **Launch the watcher, then act on what it prints when it exits.** The nonce already on the
   published file, if any, is read here — before this review's own answer can possibly have
   landed — so the watcher does not mistake something already sitting there for a new batch. This
   step ends by printing the exact `watch-remarks.sh` line to run next. Nothing in this shell waits
   for a batch, and this shell exits as soon as that line is printed.

   ```sh
   if [ -z "$remote" ]; then
     # The same file "Read remarks the person already published" above reads, named by $name from
     # step 2.
     published_file="$HOME/.claude-remarks/$name.md"
     seen_nonce=
     if [ -f "$published_file" ]; then
       seen_line=$(sed -n '2p' "$published_file")
       case "$seen_line" in "nonce: "*) seen_nonce=${seen_line#"nonce: "} ;; esac
     fi
     echo "session=$session"
     echo "published_file=$published_file"
     echo "seen_nonce=$seen_nonce"
     echo "run this next, as its own Bash call, marked background:"
     printf "  watch-remarks.sh --file '%s' --seen '%s' --require-review '%s' --deadline '%s'\n" \
       "$published_file" "$seen_nonce" "$session" "$deadline_seconds"
   else
     echo "session=$session"
     echo "base_url=$base_url"
     echo "ide_project=$ide_project"
     echo "run this next, as its own Bash call, marked background, with CLAUDE_REMARKS_TOKEN set in"
     echo "its environment to the token read in step 2 — do not echo the token itself"
     printf "  watch-remarks.sh --fetch '%s' --session '%s' --project '%s' --deadline '%s'\n" \
       "$base_url" "$session" "$ide_project" "$deadline_seconds"
   fi
   ```

   `--require-review` is file-mode only. Task 9 built `--fetch` to refuse it outright: the fetch
   endpoint already answers `ready` only for the session named in the request, so there is nothing
   left for the flag to filter, and `--seen` is left at its default there for the same reason — a
   session invented moments ago in step 3 cannot already have been answered.

   **Stop the foreground call there.** Launch the printed line as its own Bash call, marked
   background — the distinction "The watcher script" section above draws: a foreground call is
   capped at ten minutes, a background one is not, and it is what re-invokes this session when it
   **exits**. Do not run `watch-remarks.sh` inside this shell, and do not add a wait loop after the
   block above; there is nothing left in this shell to wait with.

   **`$deadline_seconds` is now the truth, not a claim.** It reaches the watcher unchanged through
   `--deadline`, so it is what the watcher actually waits, with no ten-minute cap silently cutting
   it short the way a foreground call would have. The 1800-second default in step 3, and the
   timeout sentence in "What to say if something goes wrong" below, both describe what really
   happens now.

   **When the watcher exits, what it printed and its exit code are the whole answer** — there is
   nothing on disk to go read separately for the same-machine case, and the remote case's own copy
   lives only in that call's own output too. What to do next depends on which of three exit codes
   it used, and each is its own short foreground Bash call, built with `$session`, `$base_url`,
   `$token` and `$ide_project` typed again exactly as they were before the launch — nothing carries
   a shell across two Bash calls, so nothing here is read back from a variable that no longer
   exists:

   - **Exit 0.** What the watcher printed is the whole published file, header included. Its eighth
     line is `rejected: yes` or `rejected: no` — read it directly out of what the watcher printed.
     This is the header's own field now, checked by line number, not the body's first line the way
     it used to be compared against a marker of its own.

     `rejected: yes` — the person rejected this review. No remarks were sent and there is nothing
     to acknowledge — the IDE cleared the review as it wrote the file, so an `ack read` would only
     be answered `no-review`. Report it plainly and stop; do not retry and do not start a second
     review.

     `rejected: no` — real remarks arrived. Acknowledge with `ack read`, the same request this step
     has always sent, run as its own foreground Bash call — it needs only `$session`, `$base_url`,
     `$token` and `$ide_project`, not the published file's content:

     ```sh
     ack_resp=$(mktemp)
     ack_code=$(jq -n --arg session "$session" --arg project "$ide_project" --arg event read \
         '{session:$session, project:$project, event:$event}' \
       | curl -s -o "$ack_resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
           -X POST "$base_url/ack" \
           -H "X-Claude-Remarks-Token: $token" -H "Content-Type: application/json" -d @-)
     ack_answer=$(jq -r .status "$ack_resp" 2>/dev/null)
     echo "ack read: http $ack_code, status $ack_answer"
     ```

     then go on to step 7, with what the watcher printed as the file to act on.

   - **Exit 1.** The deadline passed with nothing new. There is no trap to send it now — see
     below — so send `ack abandoned` yourself, report the timeout, and stop:

     ```sh
     curl -s -o /dev/null -w 'ack abandoned: http %{http_code}\n' --connect-timeout 5 --max-time 20 \
       -X POST "$base_url/ack" -H "X-Claude-Remarks-Token: $token" -H "Content-Type: application/json" \
       -d "$(jq -n --arg session "$session" --arg project "$ide_project" --arg event abandoned \
              '{session:$session, project:$project, event:$event}')"
     ```

   - **Exit 2.** Something the watcher could not get past: a file it could not read, a header older
     than this skill, an HTTP status other than 200, or a `too-large` answer. Report what it
     printed verbatim and stop. Do not send `ack abandoned` here — nothing here has actually given
     up on the review, and it may still be genuinely waiting for a batch that has not arrived yet.
     The IDE's own scheduled deadline is what eventually clears the banner if nothing else does.

   **The trap goes, and nothing replaces it in the same shell.** The old code kept
   `trap 'ack abandoned' EXIT` in the same shell as the wait loop. With the wait moved to a
   background command, the foreground call that launches it returns at once — and its own `EXIT`
   trap would have fired immediately, abandoning the review before the watcher had even started. So
   there is no trap anywhere in this flow any more. The session itself sends `ack abandoned`, in
   the foreground, in exactly the one case above (the watcher's own deadline passing) plus one more
   that no exit code covers: the person says to stop waiting. Do that by killing the watcher first,
   so it cannot go on to report the same batch a second time, then acknowledging the same way exit
   1 does:

   ```sh
   watch_hash=$(printf '%s' "$ide_project" | shasum -a 256 | cut -c1-16)
   kill "$(cat "$HOME/.claude-remarks/$watch_hash.watch" 2>/dev/null)" 2>/dev/null
   ```

   **What this gives up, written down here so nobody re-adds the trap:** a session killed
   outright — between two Bash calls, or while the background call is still running — sends
   nothing at all, and the IDE's own scheduled deadline is what covers that, which is what phase 7
   built it for. A killed session leaves the banner up until the deadline instead of clearing it at
   once. That was already true for a session killed between two Bash calls; it is now also true for
   one killed while the watcher itself is running.

   If waiting times out: nothing is lost. The remarks, if the person ever wrote any, are still
   sitting in the IDE's tool window, marked pending — they were never marked sent, because sending
   only writes the file, and marking sent waits for `ack read` — and the person can send them again
   or copy them by hand. The `ack abandoned` sent above clears the IDE's banner; there is nothing
   left to do by hand from the banner's Reject link for this run.

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
- 403, same-machine case: "The IDE at this port answered with a stale token — re-open the project
  in the IDE, which writes a fresh handshake, then try again."
- 403, remote case: "The token stored in `<remote_conf>` is stale — the IDE was restarted since it
  was saved. Get a fresh one (`crtunnel` on the IDE machine prints it, or 'Over SSH' above if that
  helper is not installed), then run `remote-config.sh save --port <port> --project <path>` again
  with `CLAUDE_REMARKS_TOKEN` set to it."
- Timeout waiting for a new batch: "No remarks arrived within the declared deadline
  (`deadline_seconds`, 1800 seconds by default). That is now the real wait: the watcher runs in the
  background with no ten-minute cap, so 1800 seconds means 1800 seconds. Nothing is lost — they
  are still pending in the IDE, never marked sent. Send to Claude Code again when ready, or paste
  them here." Send `ack abandoned` yourself before saying this — see step 6; there is no trap to do
  it automatically any more.
- The person rejected the review: "The review was rejected in the IDE. No remarks were sent."
  Stop; do not retry and do not start a second review for the same request.
- An acknowledgement answers anything other than `ok`: report the outcome (`no-review`,
  `not-sent`, `unknown-project`, `bad-request`) and the body verbatim, and add that the remarks were
  read here but stay marked pending in the IDE, so the person can send them again.
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
