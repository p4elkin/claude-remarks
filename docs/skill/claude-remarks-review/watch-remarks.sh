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

case "$deadline" in
  *[!0-9]* | '') echo "watch-remarks.sh: --deadline must be a positive whole number of seconds" >&2; exit 2 ;;
esac
case "$poll" in
  *[!0-9]* | '') echo "watch-remarks.sh: --poll must be a positive whole number of seconds" >&2; exit 2 ;;
esac

# The pid file's name: the same 16 hex characters review/ReviewHandshake.kt's projectHash uses
# (sha256 of the project's real path, first 16 hex characters), so the pid file sits beside the
# handshake and published files the plugin already writes for this project. In --file mode the
# published file is already named <hash>.md by that same convention, so the hash comes straight
# from its own filename instead of being computed again.
hex16_of() {
  printf '%s' "$1" | shasum -a 256 | cut -c1-16
}

case "$mode" in
  file) project_hash=$(basename "$file" .md) ;;
  fetch) project_hash=$(hex16_of "$project") ;;
esac

remarks_dir="${HOME}/.claude-remarks"
pidfile="$remarks_dir/$project_hash.watch"

if [ ! -d "$remarks_dir" ]; then
  mkdir -m 700 "$remarks_dir"
fi

# One watcher per project on this machine. Before writing our own pid, kill whichever one is
# already there — but only after confirming the pid still belongs to a watch-remarks.sh process,
# since a recycled pid could otherwise belong to something else entirely.
if [ -f "$pidfile" ]; then
  old_pid=$(cat "$pidfile" 2>/dev/null || true)
  if [ -n "${old_pid:-}" ] && kill -0 "$old_pid" 2>/dev/null; then
    if ps -p "$old_pid" -o command= 2>/dev/null | grep -q 'watch-remarks\.sh'; then
      kill "$old_pid" 2>/dev/null || true
      waited=0
      while kill -0 "$old_pid" 2>/dev/null && [ "$waited" -lt 50 ]; do
        sleep 0.1
        waited=$((waited + 1))
      done
    fi
  fi
fi

echo $$ > "$pidfile"

cleanup() {
  # Remove the pid file only if it is still ours: a later watcher for the same project may have
  # already killed us and overwritten it with its own pid, and this trap must not delete a live
  # watcher's file out from under it.
  if [ -f "$pidfile" ]; then
    current=$(cat "$pidfile" 2>/dev/null || true)
    if [ "$current" = "$$" ]; then
      rm -f "$pidfile"
    fi
  fi
}
trap cleanup EXIT

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

if [ "$mode" = file ]; then
  while :; do
    now=$(date +%s)
    if [ "$now" -ge "$deadline_ts" ]; then
      echo "no new batch appeared in $file within $deadline seconds"
      exit 1
    fi

    if [ ! -f "$file" ]; then
      sleep_capped || { echo "no new batch appeared in $file within $deadline seconds"; exit 1; }
      continue
    fi

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

    if [ "$nonce" != "$seen" ]; then
      if [ -n "$require_review" ]; then
        review_line=$(sed -n '6p' "$tmpcopy")
        review_value=${review_line#"review: "}
        if [ "$review_value" != "$require_review" ]; then
          rm -f "$tmpcopy"
          sleep_capped || { echo "no new batch appeared in $file within $deadline seconds"; exit 1; }
          continue
        fi
      fi
      cat "$tmpcopy"
      rm -f "$tmpcopy"
      exit 0
    fi

    rm -f "$tmpcopy"
    sleep_capped || { echo "no new batch appeared in $file within $deadline seconds"; exit 1; }
  done
fi

# mode = fetch: POST /fetch and read the answer's status, exactly the four the endpoint can give
# once a review is waiting: waiting (nothing published yet), ready (a new or repeated batch),
# no-review (the file, if any, answers someone else), too-large.
while :; do
  now=$(date +%s)
  if [ "$now" -ge "$deadline_ts" ]; then
    echo "no new batch answered session $session_id within $deadline seconds"
    exit 1
  fi

  resp=$(mktemp)
  body=$(jq -n --arg session "$session_id" --arg project "$project" \
    '{session:$session, project:$project}')
  http_code=$(curl -s -o "$resp" -w '%{http_code}' --connect-timeout 5 --max-time 20 \
    -X POST "$fetch_url/fetch" \
    -H "X-Claude-Remarks-Token: $CLAUDE_REMARKS_TOKEN" -H "Content-Type: application/json" \
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
  sleep_capped || { echo "no new batch answered session $session_id within $deadline seconds"; exit 1; }
done
