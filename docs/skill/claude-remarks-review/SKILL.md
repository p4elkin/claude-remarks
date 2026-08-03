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
example a laptop reached over SSH. Sending to a remote agent session is planned for a later phase
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

3. **POST to the start endpoint.**

   If there is a commit range or a set of changed files this review is about, send their paths so
   the IDE opens them for the person before they start writing remarks. This is the cheap version
   of "open the diff": no real diff view, just the files opened in editors so the person can press
   the IDE's own diff shortcut on any of them.

   ```sh
   files_json="[]"
   if [ -n "$range" ]; then
     files_json=$(git diff --name-only "$range" | jq -R -s -c 'split("\n") | map(select(length > 0))')
   fi
   ```

   `$range` is whatever commit range this review is about — for example `main..HEAD` or a single
   commit — left unset when there is nothing to diff. `git diff --name-only` already prints paths
   relative to the repository root, which is what the endpoint expects.

   ```sh
   session=$(uuidgen)
   body=$(jq -n --arg session "$session" --arg label "$label" --arg project "$root" \
     --argjson files "$files_json" \
     '{session:$session, label:$label, project:$project, files:$files}')
   http_code=$(curl -s -o /tmp/claude-remarks-start.json -w '%{http_code}' \
     -X POST "http://127.0.0.1:$port/api/claude-remarks/start" \
     -H "X-Claude-Remarks-Token: $token" -H "Content-Type: application/json" \
     -d "$body")
   ```

   `$label` is a short description of what is being reviewed — shown to the person in the IDE
   banner. `$session` is invented once per run of this skill, so a retry of the same run reuses
   it rather than starting a second review. `files` is optional: an empty array opens nothing.

   **Do not name that variable `status`.** In zsh `status` is a read-only special variable, an
   alias for `$?`, so `status=$(curl ...)` fails with "read-only variable: status". Worse than
   failing outright: zsh runs the command substitution first and only then refuses the
   assignment, so the POST is sent, a review really does start in the IDE, and the script dies
   believing nothing happened. The next attempt then gets `conflict` and the cause looks like a
   stuck review rather than a shell error. Found on 2026-08-03, on the first real end-to-end run.

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

5. **On a 200 with parseable JSON, read `status`.** It is exactly one of four values:

   - `"waiting"` — accepted. Read `output` from the body; that is the path to wait for in step 6.
   - `"conflict"` — another review is already waiting in that IDE. The body carries `label` and
     `startedAt` for the one already there. Report it and stop; do not retry and do not wait.
   - `"unknown-project"` — the IDE does not have this repository open under the path this skill
     sent. The body carries `open`, the list of projects the IDE says are open. Report it and
     stop.
   - `"bad-request"` — the body carries `detail`. This means this skill and the plugin disagree
     about the shape of the request, which is a bug in one of them, not a transient failure.
     Report the detail and stop; do not retry.

6. **Wait for the handoff file.** Take `output` from the `waiting` response.

   ```sh
   deadline=$(( $(date +%s) + 1800 ))
   while [ ! -e "$output" ]; do
     [ "$(date +%s)" -ge "$deadline" ] && { echo "timed out waiting for the IDE"; exit 1; }
     sleep 1
   done
   cat "$output"
   ```

   Checking existence is enough: the plugin writes the file's full content to a temp file beside
   it and renames the temp file onto this path, and a same-filesystem rename is atomic on POSIX.
   So there is no partial state to observe — the file is either absent or complete, never
   half-written. Do not "improve" this into a size check or a lock file; the atomic rename is
   what makes the plain existence check correct.

   If it times out: nothing is lost. The remarks are still sitting in the IDE's tool window,
   marked pending, and the person can send them again or copy them by hand. A timeout does not
   clear the waiting review inside the IDE — the person clears it themselves from the banner's
   Cancel link.

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
  are still pending in the IDE. Send to Claude Code again when ready, or paste them here."
