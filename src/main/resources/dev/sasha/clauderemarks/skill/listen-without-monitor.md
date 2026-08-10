# The exit-per-batch branch: one watcher per batch

This file holds the exit-per-batch branch of `## Listen for the next batch` in `SKILL.md` — the path
taken by every harness without a `Monitor` tool. `### The setup, run first in both branches`, in
`SKILL.md`, runs before this branch does anything; read it there first, then come back here.

**The `perl` wrapper in front of the script is not decoration, and must not be simplified away.** It
puts the watcher in a session and a process group of its own, so that a signal aimed at this
session's process group cannot reach it. `watcher-background.md`, beside this file, carries the
measurements and the reason the obvious alternatives do not work.

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
`fetch`.** The startup claim, in `SKILL.md`'s `### The setup, run first in both branches`, marked
every remark in that batch `READ`, and Publish Unread only ever picks up remarks that are not
`READ`. So a publish landing after the claim overwrites `$listen_file`, and the claimed batch can
never come back — its remarks would sit in the IDE's Done group looking handled while nobody had
read them. The copy was taken before the claim and nothing rewrites it. This is the same rule the
monitor branch states for its snapshot, for the same reason.

**A publish landing between the claim and the arming loses neither batch.** The new one carries a
different nonce, and the watcher is armed with `--seen` set to the nonce just claimed, so it is
reported on the watcher's very first poll. The claimed one is safe because the session reads the copy
rather than the file that publish has just overwritten.

**When the watcher exits, act on what it printed. ⚠️ Nothing carries a variable from one Bash call to
the next**, so the acknowledgement block below starts from nothing: every value in it is either typed
back in from a line the setup block printed, or computed again inside the block itself.

`$listen_session` and `$listen_project` are typed back in, from the setup block's own
`listen_session=` and `listen_project=` lines. `$listen_project` is the value the startup claim sent
as the project, so the acknowledgement names the project the claim named. `$listen_root` and
`$listen_name` are **printed by nothing**, so there is nothing to type back for either — the block
computes both again itself, exactly as the setup block did: the git top level, then its sha256 cut to
the first sixteen characters, which is the name the handshake file carries.

**The exit code says which ending this is.**

- **Exit 0.** What the watcher printed is the whole published file, header included. Read the
  five-line header directly out of what it printed, by line number, the same way `SKILL.md`'s
  `## Read remarks the person already published` reads it off disk: line 2 is `nonce: `, line 3
  `published: `, line 4 `commit: `, line 5 `remarks: `. Every batch is the same kind of batch, so
  there is nothing in the header to check before deciding whether to act on it.
  - Acknowledge it the same way the one-shot mode does, with the setup block's session id in place
    of `$pub_session` and line 2's nonce in place of `$pub_nonce`. The first three lines are the
    only ones to fill in by hand:

    ```sh
    listen_nonce=THE_NONCE_FROM_LINE_2_ABOVE
    listen_session=THE_ID_THE_SETUP_BLOCK_PRINTED_AS_listen_session
    # Quoted, unlike the two above: a project path can hold a space, a nonce and a session id cannot.
    listen_project='THE_PATH_THE_SETUP_BLOCK_PRINTED_AS_listen_project'
    # The setup block printed neither $listen_root nor $listen_name, and no variable survives from
    # the Bash call it ran in, so both are computed again here the way it computed them.
    listen_root=$(git rev-parse --show-toplevel 2>/dev/null)
    listen_name=$(printf %s "$listen_root" | shasum -a 256 | cut -c1-16)
    listen_handshake="$HOME/.claude-remarks/$listen_name.json"
    if [ -f "$listen_handshake" ]; then
      listen_port=$(jq -r .port "$listen_handshake")
      listen_token=$(jq -r .token "$listen_handshake")
      listen_ack_resp=$(mktemp)
      listen_ack_body=$(jq -n --arg session "$listen_session" --arg project "$listen_project" \
        --arg nonce "$listen_nonce" '{session:$session, project:$project, nonce:$nonce}')
      # The token on stdin through a curl config file, never as an argument — see the one-shot
      # mode's copy of this block, in SKILL.md, for why.
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
    path, which is the failure "Where the skill's own files are" exists to stop. The two blocks are
    kept identical line for line in the part that matters, the `printf | curl --config -` shape:
    change one and change the other. ⚠️ The monitor branch sends the same call when its outcome word
    says the watcher's own claim did not land, but it copies the startup claim's block in `SKILL.md`
    rather than this one, and must keep doing so: this block builds its URL from a local handshake
    file and would answer "cannot acknowledge" over a tunnel, while that one builds it from
    `$listen_base_url` and works either way.

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
    in `SKILL.md`'s `### Stopping, and what ends the loop`.

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

