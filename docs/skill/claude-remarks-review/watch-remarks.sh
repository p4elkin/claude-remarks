#!/bin/sh
# A background command that never exits never notifies the session waiting on it — so every path
# out of this script (a new batch, the deadline, an owner that is gone, an error) is an explicit
# exit, and none of them loop back.
set -u

usage() {
  echo "usage:" >&2
  echo "  watch-remarks.sh --file <path> [--seen <nonce>] [--require-review <session>]" >&2
  echo "                    [--deadline <seconds>] [--poll <seconds>] [--owner <pid>]" >&2
  echo "  watch-remarks.sh --fetch <base_url> --project <path> [--session <id>]" >&2
  echo "                    [--seen <nonce>] [--deadline <seconds>] [--poll <seconds>]" >&2
  echo "                    [--owner <pid>]" >&2
  echo "" >&2
  echo "exit codes: 0 a batch (on stdout), 1 the deadline passed, 2 a refusal," >&2
  echo "            3 the --owner process is gone, above 128 this watcher was killed" >&2
  exit 2
}

mode=
file=
fetch_url=
session_id=
project=
seen=
require_review=
deadline=1800
poll=
poll_set=
owner=
owner_set=

while [ $# -gt 0 ]; do
  # Every flag below takes a value. Checked here rather than in each branch, because `set -u` turns
  # a missing one into "$2: unbound variable" — a shell error in place of the usage text the
  # unrecognized-argument branch already prints for every other mistake.
  case "$1" in
    --file | --fetch | --session | --project | --seen | --require-review | --deadline | --poll | --owner)
      if [ $# -lt 2 ]; then
        echo "watch-remarks.sh: $1 needs a value" >&2
        usage
      fi
      ;;
  esac
  case "$1" in
    --file) mode=file; file=$2; shift 2 ;;
    --fetch) mode=fetch; fetch_url=$2; shift 2 ;;
    --session) session_id=$2; shift 2 ;;
    --project) project=$2; shift 2 ;;
    --seen) seen=$2; shift 2 ;;
    --require-review) require_review=$2; shift 2 ;;
    --deadline) deadline=$2; shift 2 ;;
    --poll) poll=$2; poll_set=yes; shift 2 ;;
    --owner) owner=$2; owner_set=yes; shift 2 ;;
    *) echo "watch-remarks.sh: unrecognized argument: $1" >&2; usage ;;
  esac
done

case "$mode" in
  file)
    [ -n "$file" ] || usage
    [ -n "$poll_set" ] || poll=2
    ;;
  fetch)
    # --session is optional. Without one the endpoint hands back any batch published for this
    # project, which is what a listener wants; with one it hands back only that session's own
    # review's batches, exactly as it always did.
    [ -n "$fetch_url" ] && [ -n "$project" ] || usage
    [ -n "$poll_set" ] || poll=5
    if [ -n "$require_review" ]; then
      echo "watch-remarks.sh: --require-review is not supported with --fetch — a fetch carrying a" >&2
      echo "session already answers ready only for that session's own review, and a fetch without" >&2
      echo "one deliberately takes any batch" >&2
      exit 2
    fi
    if [ -z "${CLAUDE_REMARKS_TOKEN:-}" ]; then
      echo "watch-remarks.sh: CLAUDE_REMARKS_TOKEN must be set in the environment for --fetch" >&2
      exit 2
    fi
    ;;
  *) usage ;;
esac

# Zero is refused as well as a non-digit. `sleep 0` returns at once, so --poll 0 turns either loop
# into a busy poll for the whole deadline — in fetch mode, a curl flood into a server that allows
# 30 requests a minute. --deadline 0 has already passed before the first poll.
case "$deadline" in
  *[!0-9]* | '') echo "watch-remarks.sh: --deadline must be a positive whole number of seconds" >&2; exit 2 ;;
esac
[ "$deadline" -gt 0 ] || { echo "watch-remarks.sh: --deadline must be greater than zero" >&2; exit 2; }
case "$poll" in
  *[!0-9]* | '') echo "watch-remarks.sh: --poll must be a positive whole number of seconds" >&2; exit 2 ;;
esac
[ "$poll" -gt 0 ] || { echo "watch-remarks.sh: --poll must be greater than zero" >&2; exit 2; }

# --owner is optional, and everything about this script is unchanged when it is absent. Given, it
# names the process this watcher belongs to — the Claude Code session that launched it — and the poll
# loops below stop as soon as that process is gone.
#
# It exists because the launch line in SKILL.md now puts the watcher in its own session and process
# group, so that a signal aimed at the launching shell's process group cannot reach it. That is the
# fix for watchers dying on a stray group-wide kill, and it has a cost: the watcher now outlives the
# session that started it, reparented to init, running to its deadline. Such an orphan catches a
# batch, writes it to a file nobody reads and exits. Nothing is marked read — the session claims a
# batch, not the watcher — so the batch is still there for somebody else. What it does cost is the
# pid file: the orphan holds it, so a person stopping "the watcher" for this repository stops the
# orphan and leaves the live one running, and something looks like it is listening when nothing is.
#
# Validated exactly the way --deadline is, refusals included. Zero is refused for a reason of its
# own: `kill -0 0` asks about every process in the caller's own process group, which is never what
# naming an owner means, and it would answer "alive" forever.
if [ -n "$owner_set" ]; then
  case "$owner" in
    *[!0-9]* | '') echo "watch-remarks.sh: --owner must be a positive whole number, a process id" >&2; exit 2 ;;
  esac
  [ "$owner" -gt 0 ] || { echo "watch-remarks.sh: --owner must be greater than zero" >&2; exit 2; }
fi

# The pid file's name: the same 16 hex characters review/ReviewHandshake.kt's projectHash uses
# (sha256 of the project's real path, first 16 hex characters), so the pid file sits beside the
# handshake and published files the plugin already writes for this project. In --file mode the
# published file is already named <hash>.md by that same convention, so the hash comes straight
# from its own filename instead of being computed again — but only when that name really is 16 hex
# characters. Anything that is not 16 hex characters is hashed instead, so a --file pointed
# somewhere else still gets a name of its own rather than one taken from whatever the basename
# happened to be.
#
# The name is per project because that is how a session finds this watcher again later. The file
# claims nothing and excludes nobody: several watchers may run for one project at once, and the
# file simply names the one that started most recently, so that one can be stopped by its pid.
# That is what makes a match on the program name unnecessary — every repository's watcher on this
# machine runs a program called watch-remarks.sh, so a match on that name would stop all of them.
hex16_of() {
  printf '%s' "$1" | shasum -a 256 | cut -c1-16
}

case "$mode" in
  file)
    file_base=$(basename "$file" .md)
    case "$file_base" in
      [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f])
        project_hash=$file_base ;;
      *) project_hash=$(hex16_of "$file") ;;
    esac
    watch_identity=$file
    ;;
  fetch)
    project_hash=$(hex16_of "$project")
    watch_identity=$project
    ;;
esac

remarks_dir="${HOME}/.claude-remarks"
pidfile="$remarks_dir/$project_hash.watch"

if [ ! -d "$remarks_dir" ]; then
  mkdir -m 700 "$remarks_dir"
fi

# A starting watcher never takes over from one already running for this project. Several sessions
# may listen to one repository at once, and it is the batch claim in the IDE — the published-read
# acknowledgement, answered ok to exactly one caller — that stops two of them acting on the same
# batch. A watcher that killed another would instead leave the losing session silently deaf.
#
# Two lines: our pid, then what we are watching (--file's file, or --fetch's project). Anything
# reading this file for a pid to stop must read the first line alone, and must also check that the
# live process's command line contains the path on line 2 — pids are recycled, and a recycled one
# can belong to another repository's watcher, which is still a watch-remarks.sh.
#
# Written to a temp name beside the pid file and then renamed onto it. A rename within one directory
# is atomic on every POSIX filesystem, so two watchers starting for the same project in the same
# moment cannot interleave the two lines — a reader sees one whole file or the other, never one
# watcher's pid above the other's watched path. This replaces a mkdir lock that guarded exactly this
# write and could wait ten seconds before breaking a stale one; the rename needs no waiting, no
# stale-lock rule and nothing released afterwards.
#
# This write overwrites whatever an earlier watcher for the same project left here, and does
# nothing whatever to that watcher: it keeps running, and the file now names this one.
pidtmp="$pidfile.$$"
{ echo $$; echo "$watch_identity"; } > "$pidtmp"
mv "$pidtmp" "$pidfile"

cleanup() {
  # Remove the pid file only if it is still ours: a later watcher for the same project overwrites
  # it with its own pid while this one keeps running, and this trap must not then delete a live
  # watcher's file out from under it.
  if [ -f "$pidfile" ]; then
    current=$(sed -n '1p' "$pidfile" 2>/dev/null || true)
    if [ "$current" = "$$" ]; then
      rm -f "$pidfile"
    fi
  fi
}
trap cleanup EXIT
# EXIT alone does not fire when the shell is killed by a signal. Without these three a killed
# watcher leaves both its pid file and its mktemp copy of the published file — a file holding the
# person's own remarks — behind in the temp directory. 143 is the conventional 128 + SIGTERM, which
# any kill produces: a harness restart, a machine suspending, a stray kill, or the person stopping
# this watcher on purpose. Nothing takes over from another watcher any more, so a session reading
# an exit code above 128 should report that its watcher was killed and start a new one — never read
# it as a batch or as the deadline.
trap 'cleanup; rm -f "${tmpcopy:-}" "${resp:-}"; exit 143' INT TERM HUP

start_ts=$(date +%s)
deadline_ts=$((start_ts + deadline))

# The sleep before the next poll is capped to what is left before the deadline, so a run that
# times out with nothing new does so close to --deadline seconds rather than overshooting by
# nearly a whole --poll interval.
sleep_capped() {
  now=$(date +%s)
  remaining=$((deadline_ts - now))
  if [ "$remaining" -le 0 ]; then
    return 1
  fi
  sleep_for=$poll
  if [ "$sleep_for" -gt "$remaining" ]; then
    sleep_for=$remaining
  fi
  sleep "$sleep_for"
  return 0
}

# The one sentence each mode prints when its deadline passes, written once. The top-of-loop check
# and every `sleep_capped ||` tail below call the same helper, so the wording cannot drift between
# them. It goes to stderr, not stdout: on success stdout carries the whole batch, and a caller
# reading stdout as the payload must get a batch or nothing, never a sentence. Exit 1 means the
# deadline and nothing else — every refusal in this script exits 2.
timed_out_file() {
  echo "no new batch appeared in $file within $deadline seconds" >&2
  exit 1
}

timed_out_fetch() {
  # Named after the project and not after the session: with --session absent this watcher is
  # waiting on any batch for the repository, and there is no session to name.
  echo "no new batch was published for $project within $deadline seconds" >&2
  exit 1
}

# The same pair for --owner, written the same way and for the same reason: the check runs once per
# mode, and the sentence must not drift between them. Exit 3 is this ending and nothing else — 0 is a
# batch, 1 the deadline, 2 a refusal, anything above 128 a signal. On stderr like every other ending,
# because stdout carries the batch and nothing else.
#
# ⚠️ The session that launched this watcher never sees this exit code. It is gone by definition; that
# is what the code means. It is here for a person reading the watcher's own output afterwards.
owner_gone_file() {
  echo "the session that started this watcher (pid $owner) is gone — stopping the watch on $file" >&2
  exit 3
}

owner_gone_fetch() {
  echo "the session that started this watcher (pid $owner) is gone — stopping the watch on $project" >&2
  exit 3
}

# One place decides whether the owner is still there, so the two loops cannot disagree about it. No
# --owner means no owner to lose, which is what keeps every existing run byte for byte as it was.
owner_is_gone() {
  [ -n "$owner_set" ] || return 1
  kill -0 "$owner" 2>/dev/null && return 1
  return 0
}

if [ "$mode" = file ]; then
  tmpcopy=
  last_probe=
  while :; do
    now=$(date +%s)
    if [ "$now" -ge "$deadline_ts" ]; then
      timed_out_file
    fi
    if owner_is_gone; then
      owner_gone_file
    fi

    if [ ! -f "$file" ]; then
      sleep_capped || timed_out_file
      continue
    fi

    # Line 2 of the header first — the nonce — and copy nothing when it has not moved since the
    # last poll. At the 2-second default over listen mode's twelve hours the loop runs about 21,600
    # times; without this every one of them creates, copies and deletes the whole file. This reads
    # two lines instead, and the copy below is taken only when the file really did change.
    #
    # The nonce rather than a modification time and a size, which is what this was first written
    # with. Both of those are coarse — the time is whole seconds, and two batches carrying the same
    # remarks differ by no bytes at all — so a batch replacing one of the same length inside the
    # same second read as unchanged, and, because the stamp was then recorded as seen, was never
    # looked at again until the deadline. The nonce is a fresh UUID on every write, so it moves
    # whenever the file does, and there is no such blind spot. A rename does not truncate the inode
    # behind it, so this read returns one whole line belonging to one batch, never a mix of two.
    #
    # It decides nothing on its own: once it says the file moved, everything below is read again out
    # of the copy, this line included. An unreadable or empty line 2 is left empty, which never
    # matches and so always copies — the copy is then what reports the malformed file.
    probe=$(sed -n '2{p;q;}' "$file" 2>/dev/null)
    if [ -n "$probe" ] && [ "$probe" = "$last_probe" ]; then
      sleep_capped || timed_out_file
      continue
    fi
    last_probe=$probe

    # Copy once, then read everything — the nonce and the body both — out of the copy. cp opens
    # an inode, and a rename does not truncate the inode it replaces, so the copy is always one
    # whole batch, old or new, never a mix of two.
    tmpcopy=$(mktemp)
    if ! cp "$file" "$tmpcopy" 2>/dev/null; then
      echo "watch-remarks.sh: could not read $file" >&2
      rm -f "$tmpcopy"
      exit 2
    fi

    first_line=$(sed -n '1p' "$tmpcopy")
    if [ "$first_line" != '<!-- claude-remarks: published -->' ]; then
      echo "watch-remarks.sh: $file's first line is not the published marker" >&2
      rm -f "$tmpcopy"
      exit 2
    fi

    second_line=$(sed -n '2p' "$tmpcopy")
    case "$second_line" in
      "nonce: "*) nonce=${second_line#"nonce: "} ;;
      *)
        echo "watch-remarks.sh: $file's line 2 does not start with 'nonce: ' — the plugin that" >&2
        echo "wrote it is older than this skill" >&2
        rm -f "$tmpcopy"
        exit 2
        ;;
    esac

    if [ -n "$require_review" ]; then
      # --require-review decides on its own, and --seen is not consulted at all. The review session
      # was invented moments before this watcher started, so any batch whose review: field names it
      # is this review's own answer, whatever nonce the file carries. Comparing the nonce too would
      # lose exactly the batch this is waiting for: the review flow reads --seen off the file in the
      # same shell that posts to /start, so an answer published in the gap between those two is
      # already recorded as seen before the watcher ever runs, and the wait would then run its whole
      # deadline out on a batch that had already arrived.
      review_line=$(sed -n '6p' "$tmpcopy")
      # Checked with a case, the same way lines 1 and 2 are. A bare ${review_line#"review: "} on a
      # line with no such prefix yields the whole line, which then compares against the session id
      # as if it were a field — publishedHeaderOf on the Kotlin side refuses the file instead.
      case "$review_line" in
        "review: "*) review_value=${review_line#"review: "} ;;
        *)
          echo "watch-remarks.sh: $file's line 6 does not start with 'review: ' — the plugin that" >&2
          echo "wrote it is older than this skill" >&2
          rm -f "$tmpcopy"
          exit 2
          ;;
      esac
      if [ "$review_value" = "$require_review" ]; then
        cat "$tmpcopy"
        rm -f "$tmpcopy"
        exit 0
      fi
    elif [ "$nonce" != "$seen" ]; then
      cat "$tmpcopy"
      rm -f "$tmpcopy"
      exit 0
    fi

    rm -f "$tmpcopy"
    tmpcopy=
    sleep_capped || timed_out_file
  done
fi

# mode = fetch: POST /fetch and read the answer's status. The endpoint documents seven for this
# action: waiting (nothing published yet), ready (a new or repeated batch), no-review (the file, if
# any, answers someone else), too-large, failed (an IOException, an unparseable header, or a project
# directory that no longer resolves), plus bad-request and unknown-project, which mean this script
# and the plugin disagree about the request itself. Only waiting and no-review are worth another
# poll; every other one ends the run.
resp=
while :; do
  now=$(date +%s)
  if [ "$now" -ge "$deadline_ts" ]; then
    timed_out_fetch
  fi
  if owner_is_gone; then
    owner_gone_fetch
  fi

  resp=$(mktemp)
  # The body carries session only when there is one. An absent session is what the endpoint reads
  # as "any batch for this project"; a body carrying an empty string is a different thing, and the
  # endpoint would have to guess which was meant.
  if [ -n "$session_id" ]; then
    body=$(jq -n --arg session "$session_id" --arg project "$project" \
      '{session:$session, project:$project}')
  else
    body=$(jq -n --arg project "$project" '{project:$project}')
  fi
  # The token goes in on stdin, through a curl config file, never as an argument. An argument sits
  # in curl's own argv, which every process on this machine can read out of ps — and the token is
  # the only gate on the endpoint, so a local process is exactly the attacker it exists to stop.
  # The request body has no secret in it and stays on the command line, which is what leaves stdin
  # free for the config.
  http_code=$(printf 'header = "X-Claude-Remarks-Token: %s"\n' "$CLAUDE_REMARKS_TOKEN" \
    | curl -s --config - -o "$resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
      -X POST "$fetch_url/fetch" \
      -H "Content-Type: application/json" \
      -d "$body")

  if [ "$http_code" != "200" ]; then
    echo "watch-remarks.sh: fetch answered http $http_code, not 200" >&2
    cat "$resp" >&2
    rm -f "$resp"
    exit 2
  fi

  status=$(jq -r '.status // empty' "$resp")
  case "$status" in
    ready)
      nonce=$(jq -r '.nonce // empty' "$resp")
      if [ "$nonce" != "$seen" ]; then
        jq -r '.content' "$resp"
        rm -f "$resp"
        exit 0
      fi
      ;;
    too-large)
      # The two numbers are carried out, not just the fact: SKILL.md's message for this answer quotes
      # both back to the person, and the response already has them — handleFetch writes `bytes` and
      # `limit` beside the status. Without this line the skill has two placeholders it can never fill.
      echo "watch-remarks.sh: the published file is over the fetch size limit ($(
        jq -r '"\(.bytes // "unknown") bytes, limit \(.limit // "unknown")"' "$resp"
      ))" >&2
      rm -f "$resp"
      exit 2
      ;;
    failed)
      # A documented answer, not an unknown one: the IDE reached the file and could not use it.
      # Polling again cannot help — the detail says what broke on the IDE machine.
      echo "watch-remarks.sh: fetch answered failed: $(jq -r '.detail // "no detail"' "$resp")" >&2
      rm -f "$resp"
      exit 2
      ;;
    bad-request | unknown-project)
      echo "watch-remarks.sh: fetch answered $status — this script and the plugin disagree about" >&2
      echo "the request itself, which polling again cannot fix" >&2
      cat "$resp" >&2
      rm -f "$resp"
      exit 2
      ;;
    waiting | no-review)
      : # nothing new yet — keep polling
      ;;
    *)
      echo "watch-remarks.sh: fetch answered a status this script does not know: ${status:-<none>}" >&2
      cat "$resp" >&2
      rm -f "$resp"
      exit 2
      ;;
  esac
  rm -f "$resp"
  resp=
  sleep_capped || timed_out_fetch
done
