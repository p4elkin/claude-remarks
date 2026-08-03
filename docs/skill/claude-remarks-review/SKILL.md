---
name: claude-remarks-review
description: >
  Hand a code review over to a person working in the Claude Remarks IntelliJ plugin, then wait
  for their remarks and act on them. Use when asked to start a review session with the IDE, wait
  for review comments from an open IntelliJ/JetBrains project, or read back remarks the person
  just sent from the Claude Remarks tool window. Requires the IDE and this skill to run on the
  same machine — see "Same machine only" below before using it over SSH or on a remote box.
---

# Claude Remarks review

Starts a review that a person answers inside the Claude Remarks tool window in their IDE, waits
for them to press "Send to Claude Code", then reads what they wrote.

## Same machine only

This only works when the IDE and this Claude Code session run on the same machine. Both the
handshake file and the handoff file are local paths — `~/.claude-remarks/*.json` and a temp
directory under `$TMPDIR` — so there is nothing to read if the IDE is on a different machine, for
example a laptop reached over SSH. Sending to a remote agent session is planned for phase 8
(see `docs/ideas.md`, "Sending remarks to a remote agent session," in the `claude-remarks` repo);
it is not built. If asked to do this over SSH, say so and stop rather than trying step 3 below.

## Steps

1. **Find the repository root.**

   ```sh
   root=$(git rev-parse --show-toplevel)
   ```

   This returns the physical path even for a symlinked checkout, which matters: the IDE side
   normalizes the same way, so the two have to agree.

2. **Compute the handshake file name and read it.**

   ```sh
   name=$(printf %s "$root" | shasum -a 256 | cut -c1-16)
   handshake="$HOME/.claude-remarks/$name.json"
   [ -f "$handshake" ] || { echo "no IDE has $root open (no handshake file at $handshake)"; exit 1; }
   port=$(jq -r .port "$handshake")
   token=$(jq -r .token "$handshake")
   ```

   A missing file means no running IDE has this repository open right now — not that the plugin
   is broken. Do not retry and do not scan ports; re-opening the project in the IDE is what
   creates this file.

3. **POST to the start endpoint.** **Run steps 3 to 6 as one Bash call, in one shell.** The Bash
   tool starts a new shell for every call, and nothing crosses that boundary: no variables and no
   shell functions. Steps 4 and 5 are decisions about the answer this step writes to a file, so they
   cost nothing extra inside the same shell, and step 6 needs `$session`, `$port`, `$token`, `$root`,
   `$output` and `deadline_seconds`, all set here or in step 2. Split across calls, step 6 posts to
   `http://127.0.0.1:/api/claude-remarks/ack` with an empty token and waits for a file called `""`.

   **Work out the file list before you POST, and check it is not empty.** The IDE opens the paths
   this request names. An empty list opens nothing at all, silently: the endpoint still answers
   `waiting`, the banner still appears, and the person sits in front of an IDE where nothing
   happened while this skill waits for remarks about files it never asked for. That has happened for
   real. So decide which of the three shapes below applies, run its command, and count the result.

   **One commit** — the shape that is easy to get wrong. Use `git show`, never `git diff`:

   ```sh
   files_json=$(git show --name-only --format= "$commit" \
     | jq -R -s -c 'split("\n") | map(select(length > 0))')
   ```

   `git diff --name-only <commit>` does NOT list that commit's files. It diffs that commit against
   the working tree, so it prints everything changed *since* it — and prints nothing at all when the
   tree already sits at that commit. Both answers are wrong and neither looks like an error.

   **A range of commits** — two dots for "what changed on this side", three for "since they
   diverged", the same distinction `git log` uses:

   ```sh
   files_json=$(git diff --name-only "$base"..."$tip" \
     | jq -R -s -c 'split("\n") | map(select(length > 0))')
   ```

   **Uncommitted work** — what the person has edited but not committed. This is the only shape the
   IDE can open as a real diff (see below):

   ```sh
   files_json=$(git diff --name-only HEAD \
     | jq -R -s -c 'split("\n") | map(select(length > 0))')
   ```

   **Nothing in particular** — a review that is not about a diff at all. Then, and only then:

   ```sh
   files_json="[]"
   ```

   Every command above prints paths relative to the repository root, which is what the endpoint
   expects. Now check the list before sending it:

   ```sh
   files_count=$(printf %s "$files_json" | jq 'length')
   ```

   **If `files_count` is 0 and the review was about a commit, a range, or the current changes, stop
   here and say so.** Do not POST. An empty list means the command found nothing — a wrong commit id,
   a range the wrong way round, or a clean tree — and starting a review at that point produces the
   silent-nothing case above. Report which shape you used and the command you ran.

   **What the person will actually see.** For uncommitted work the IDE opens one real diff window
   holding just these files, with next-file navigation inside it. For a commit or a range it opens a
   plain editor per file instead — `ChangeListManager` only knows about uncommitted changes, so a
   committed one has no diff for the IDE to show. That is a known limit, not a failure: say plainly
   which of the two the person is getting, so an editor where they expected a diff is not read as a
   bug.

   ```sh
   session=$(uuidgen)
   deadline_seconds=1800
   body=$(jq -n --arg session "$session" --arg label "$label" --arg project "$root" \
     --argjson files "$files_json" --argjson deadline "$deadline_seconds" \
     '{session:$session, label:$label, project:$project, files:$files, deadlineSeconds:$deadline}')
   http_code=$(curl -s -o /tmp/claude-remarks-start.json -w '%{http_code}' \
     -X POST "http://127.0.0.1:$port/api/claude-remarks/start" \
     -H "X-Claude-Remarks-Token: $token" -H "Content-Type: application/json" \
     -d "$body")
   ```

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
   - **429** — the built-in server's own rate limit, 30 requests per minute by default. Wait a
     few seconds and retry the POST once. If it 429s again, report it and stop.
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

6. **Wait for the handoff file, tell a rejection from remarks, then acknowledge.** Take `output`
   from the `waiting` response.

   ```sh
   ack() {
     jq -n --arg session "$session" --arg project "$root" --arg event "$1" \
       '{session:$session, project:$project, event:$event}' \
     | curl -s -o /tmp/claude-remarks-ack.json -w '%{http_code}' \
         -X POST "http://127.0.0.1:$port/api/claude-remarks/ack" \
         -H "X-Claude-Remarks-Token: $token" -H "Content-Type: application/json" -d @-
   }
   trap 'ack abandoned >/dev/null' EXIT INT TERM

   deadline=$(( $(date +%s) + ${deadline_seconds:-1800} ))
   while [ ! -e "$output" ]; do
     [ "$(date +%s)" -ge "$deadline" ] && { echo "timed out waiting for the IDE"; exit 1; }
     sleep 1
   done
   cat "$output"
   trap - EXIT INT TERM

   if grep -q '^<!-- claude-remarks: rejected -->' "$output"; then
     echo "the person rejected this review; no remarks were sent"
     exit 0
   fi
   ack_code=$(ack read)
   ack_answer=$(jq -r .status /tmp/claude-remarks-ack.json 2>/dev/null)
   echo "ack read: http $ack_code, status $ack_answer"
   ```

   Checking existence is enough: the plugin writes the file's full content to a temp file beside
   it and renames the temp file onto this path, and a same-filesystem rename is atomic on POSIX.
   So there is no partial state to observe — the file is either absent or complete, never
   half-written. Do not "improve" this into a size check or a lock file; the atomic rename is
   what makes the plain existence check correct.

   Six things about this block are load-bearing, and each one is a decision somebody will
   otherwise undo:

   - **`ack read` captures the answer; the trap's `ack abandoned` throws it away on purpose.** The
     read acknowledgement is the one request whose answer changes what to report — it is what marks
     the remarks sent in the IDE — so its HTTP code and its `status` field are both kept, in
     variables named `ack_code` and `ack_answer`. The trap runs while the shell is already leaving,
     with nowhere left to report to, so it discards its output instead.

   - **The trap is set only after a `waiting` response.** Before that there is no review to
     abandon, and an acknowledgement for a review that does not exist just gets `no-review`.
   - **The trap is cleared after `cat` succeeds and before `ack read`.** Once the content is read,
     the read is a fact — even if the acknowledgement request then fails. Clearing the trap first
     means a failing `ack read` leaves the IDE to its own deadline, which keeps the remarks
     pending. The other order would tell the IDE the agent left after it had already read them.
   - **`trap - EXIT INT TERM` restores the default; it does not run the handler.** Writing
     `trap "" EXIT` instead would also work but reads as "run nothing", which is easy to misread
     as "run the old thing".
   - **The trap covers this one shell, which is why steps 3 to 6 belong in one Bash call.** It
     catches a timeout inside this loop and an interrupt of this command. An agent process killed
     between two Bash calls sends nothing at all, and the IDE's own deadline is what covers that
     case instead. `${deadline_seconds:-1800}` is a seatbelt for exactly that mistake: split across
     calls, the bare arithmetic would leave `deadline` empty, `[ "$(date +%s)" -ge "" ]` would never
     fire, and the loop would poll silently until the Bash tool's own timeout.
   - **The rejection check comes before the acknowledgement, and it is anchored to the start of
     the line.** `grep -q '^<!-- claude-remarks: rejected -->'` — without the `^` a remark quoting
     that string in its own text would be read as a rejection. There is nothing to acknowledge on
     a rejection: the IDE cleared the review as it wrote the file, so an `ack read` would only be
     answered `no-review`. The trap is cleared before this branch, so a rejection does not also
     report the agent as having left.

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
- Timeout waiting for the handoff file: "No remarks arrived in 30 minutes. Nothing is lost — they
  are still pending in the IDE, never marked sent. Send to Claude Code again when ready, or paste
  them here."
- The person rejected the review: "The review was rejected in the IDE. No remarks were sent."
  Stop; do not retry and do not start a second review for the same request.
- An acknowledgement answers anything other than `ok`: report the outcome (`no-review`,
  `not-sent`, `unknown-project`, `bad-request`) and the body verbatim, and add that the remarks were
  read here but stay marked pending in the IDE, so the person can send them again.
- `start` answers `failed`: "The IDE could not open a review session: <detail>." No review is
  waiting, so there is nothing to wait for and nothing to reject.
