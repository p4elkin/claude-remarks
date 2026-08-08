# The IDE on another machine, and what to say when something goes wrong

This file holds two sections from `SKILL.md`. The first covers the tunnel setup for reading and
acting on remarks when the IDE is not on this machine. The second is the exact wording to use with
the person for every failure shape this skill's requests can answer with, same-machine and remote
alike. Read the first half only when the IDE is on another machine; read the second half only once
a request has actually failed — neither is needed for the ordinary same-machine, nothing-went-wrong
case.

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
