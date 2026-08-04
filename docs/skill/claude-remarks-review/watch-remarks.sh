#!/bin/sh
# A background command that never exits never notifies the session waiting on it — so every path
# out of this script (a new batch, the deadline, an error) is an explicit exit, and none of them
# loop back.
set -u

usage() {
  echo "usage:" >&2
  echo "  watch-remarks.sh --file <path> [--seen <nonce>] [--require-review <session>]" >&2
  echo "                    [--deadline <seconds>] [--poll <seconds>]" >&2
  echo "  watch-remarks.sh --fetch <base_url> --session <id> --project <path>" >&2
  echo "                    [--seen <nonce>] [--deadline <seconds>] [--poll <seconds>]" >&2
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

while [ $# -gt 0 ]; do
  # Every flag below takes a value. Checked here rather than in each branch, because `set -u` turns
  # a missing one into "$2: unbound variable" — a shell error in place of the usage text the
  # unrecognized-argument branch already prints for every other mistake.
  case "$1" in
    --file | --fetch | --session | --project | --seen | --require-review | --deadline | --poll)
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
    *) echo "watch-remarks.sh: unrecognized argument: $1" >&2; usage ;;
  esac
done

case "$mode" in
  file)
    [ -n "$file" ] || usage
    [ -n "$poll_set" ] || poll=2
    ;;
  fetch)
    [ -n "$fetch_url" ] && [ -n "$session_id" ] && [ -n "$project" ] || usage
    [ -n "$poll_set" ] || poll=5
    if [ -n "$require_review" ]; then
      echo "watch-remarks.sh: --require-review is not supported with --fetch — the fetch" >&2
      echo "endpoint already answers ready only for the session's own review" >&2
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

# The pid file's name: the same 16 hex characters review/ReviewHandshake.kt's projectHash uses
# (sha256 of the project's real path, first 16 hex characters), so the pid file sits beside the
# handshake and published files the plugin already writes for this project. In --file mode the
# published file is already named <hash>.md by that same convention, so the hash comes straight
# from its own filename instead of being computed again — but only when that name really is 16 hex
# characters. A --file pointed anywhere else would otherwise name the pid file after whatever the
# basename happened to be, and the one-watcher-per-project rule would quietly stop holding: two
# watchers on two paths that share a basename would fight, two on the same project under different
# names would not see each other. Anything that is not 16 hex characters is hashed instead.
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

# The claim on the pid file below is read-then-kill-then-write, which is three steps, and two
# watchers launched for the same project in the same moment would otherwise both walk through them:
# both find no pid file (or both find the same old one), both write, and both run. `mkdir` is what
# makes the claim one step — creating a directory is atomic on every POSIX filesystem, and creating
# one that already exists fails rather than quietly succeeding. It is held only for the takeover and
# the pid write, and released before the poll loop starts, so it is never held for hours.
lockdir="$remarks_dir/$project_hash.watch.lock"
lock_waited=0
# Longer than the five seconds the takeover below spends waiting for the old watcher to die, so a
# watcher doing that legitimately is never treated as a stale lock.
lock_wait_limit=10
while ! mkdir "$lockdir" 2>/dev/null; do
  if [ "$lock_waited" -ge "$lock_wait_limit" ]; then
    # A watcher killed before it could release the lock leaves the directory behind. Waiting for it
    # for ever would mean no watcher can ever start for this project again, which is far worse than
    # the race the lock closes — so the lock is broken and taken instead. The worst this can cost is
    # one extra watcher; it can never cost the only one. It also costs the wait once and not again:
    # whoever breaks the lock replaces it with a live one.
    rm -rf "$lockdir"
    mkdir "$lockdir" 2>/dev/null || true
    break
  fi
  sleep 1
  lock_waited=$((lock_waited + 1))
done

# One watcher per project on this machine. Before writing our own pid, kill whichever one is
# already there — but only after confirming the pid still belongs to a watch-remarks.sh process
# watching this same thing. A pid alone is not enough: pids are recycled, and a recycled one can
# belong to another project's watcher, which is still a watch-remarks.sh. So the pid file carries a
# second line, the path this watcher was launched on (--file's file, or --fetch's project), and the
# live process's own command line has to contain that same path. A pid file written by an older
# watcher has no second line; the identity is then empty and matches anything, which is the old
# behaviour rather than a refusal to take over.
if [ -f "$pidfile" ]; then
  old_pid=$(sed -n '1p' "$pidfile" 2>/dev/null || true)
  old_identity=$(sed -n '2p' "$pidfile" 2>/dev/null || true)
  if [ -n "${old_pid:-}" ] && kill -0 "$old_pid" 2>/dev/null; then
    old_command=$(ps -p "$old_pid" -o command= 2>/dev/null || true)
    if printf '%s' "$old_command" | grep -q 'watch-remarks\.sh' \
       && printf '%s' "$old_command" | grep -qF -- "${old_identity:-}"; then
      kill "$old_pid" 2>/dev/null || true
      waited=0
      # `sleep 0.1` is not POSIX, and this script's #!/bin/sh promises nothing beyond it. A whole
      # second is the smallest portable step. The kill -0 is checked before each sleep, so the
      # ordinary case — the old watcher gone within milliseconds — still costs no wait at all.
      while kill -0 "$old_pid" 2>/dev/null && [ "$waited" -lt 5 ]; do
        sleep 1
        waited=$((waited + 1))
      done
    fi
  fi
fi

# Two lines: our pid, then what we are watching. Anything reading this file for a pid to kill must
# read the first line alone.
{ echo $$; echo "$watch_identity"; } > "$pidfile"

# Released here, before the traps below are installed rather than after: a signal arriving in the
# gap kills this shell with the lock still held, and the stale-lock break above is what clears that.
# Installing the traps first would mean the cleanup trap had to know about the lock as well, and
# releasing a lock from a trap that also runs on the takeover path is how a live watcher's lock ends
# up removed by a dying one.
rm -rf "$lockdir"

cleanup() {
  # Remove the pid file only if it is still ours: a later watcher for the same project may have
  # already killed us and overwritten it with its own pid, and this trap must not delete a live
  # watcher's file out from under it.
  if [ -f "$pidfile" ]; then
    current=$(sed -n '1p' "$pidfile" 2>/dev/null || true)
    if [ "$current" = "$$" ]; then
      rm -f "$pidfile"
    fi
  fi
}
trap cleanup EXIT
# EXIT alone does not fire when the shell is killed by a signal, and a signal is exactly how the
# takeover above ends the previous watcher. Without these three the killed watcher leaves both its
# pid file and its mktemp copy of the published file — a file holding the person's own remarks —
# behind in the temp directory. 143 is the conventional 128 + SIGTERM; a session reading an exit
# code above 128 should read it as "another watcher took over", never as a batch or a deadline.
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
  echo "no new batch answered session $session_id within $deadline seconds" >&2
  exit 1
}

if [ "$mode" = file ]; then
  tmpcopy=
  last_probe=
  while :; do
    now=$(date +%s)
    if [ "$now" -ge "$deadline_ts" ]; then
      timed_out_file
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

  resp=$(mktemp)
  body=$(jq -n --arg session "$session_id" --arg project "$project" \
    '{session:$session, project:$project}')
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
      echo "watch-remarks.sh: the published file is over the fetch size limit" >&2
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
