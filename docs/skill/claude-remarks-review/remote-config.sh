#!/bin/sh
# Stores the four remote-IDE connection values SKILL.md step 1 otherwise needs pasted by hand on
# every run: the tunnel's local port on this machine, the token, the repository path as the IDE
# machine sees it, and the host. Keyed by THIS machine's own repository root — never by the value
# it stores — so two repositories on this machine can never share one configuration. See SKILL.md,
# "The four connection values are stored once, not pasted every time."
set -u

usage() {
  echo "usage:" >&2
  echo "  remote-config.sh save --port <port> --project <path> [--host <host>]" >&2
  echo "                   (reads the token from CLAUDE_REMARKS_TOKEN, never an argument —" >&2
  echo "                    an argument is world-readable through ps)" >&2
  echo "  remote-config.sh show" >&2
  echo "  remote-config.sh forget" >&2
  exit 2
}

cmd=${1:-}
if [ -z "$cmd" ]; then
  usage
fi
shift
case "$cmd" in
  save | show | forget) ;;
  *) usage ;;
esac

root=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$root" ]; then
  echo "remote-config.sh: this directory is not in a git repository, so the configuration cannot" >&2
  echo "be keyed by a repository root. Run this from inside the repository whose remote IDE is" >&2
  echo "being configured." >&2
  exit 1
fi

remarks_dir="${HOME}/.claude-remarks"
name=$(printf '%s' "$root" | shasum -a 256 | cut -c1-16)
target="$remarks_dir/remote-$name.env"

case "$cmd" in

  save)
    host=127.0.0.1
    port=
    project=

    while [ $# -gt 0 ]; do
      case "$1" in
        --host) host=$2; shift 2 ;;
        --port) port=$2; shift 2 ;;
        --project) project=$2; shift 2 ;;
        *) echo "remote-config.sh: unrecognized argument: $1" >&2; usage ;;
      esac
    done

    # Validated before anything is stored: a bad value stored now fails later and further away,
    # inside a curl whose error says nothing about where the value came from.
    case "$port" in
      *[!0-9]* | '') echo "remote-config.sh: --port must be a whole number: '$port'" >&2; exit 1 ;;
    esac
    case "$project" in
      /*) ;;
      *) echo "remote-config.sh: --project must be an absolute path: '$project'" >&2; exit 1 ;;
    esac

    token=${CLAUDE_REMARKS_TOKEN:-}
    if [ -z "$token" ]; then
      echo "remote-config.sh: CLAUDE_REMARKS_TOKEN must be set in the environment — never pass" >&2
      echo "the token as an argument, it would be world-readable through ps" >&2
      exit 1
    fi

    if [ -d "$remarks_dir" ]; then
      # Refuses to write unless the directory is already owner-only: writeHandshake in
      # review/ReviewHandshake.kt holds itself to exactly that standard for a file holding the
      # same token. stat's flag differs by platform, so BSD (macOS) is tried first, then GNU.
      mode=$(stat -f '%Lp' "$remarks_dir" 2>/dev/null || stat -c '%a' "$remarks_dir" 2>/dev/null)
      last2=${mode#"${mode%??}"}
      if [ "$last2" != "00" ]; then
        echo "remote-config.sh: $remarks_dir is not owner-only (mode $mode) — refusing to write" >&2
        echo "the config file there. Fix its permissions (it must carry no group or other access)" >&2
        echo "and try again." >&2
        exit 1
      fi
    else
      mkdir -m 700 "$remarks_dir"
    fi

    # Temp file beside the target, then rename, the same shape review/AtomicWrite.kt uses: a
    # reader never sees a partially written file, and chmod runs before the rename so the file is
    # never briefly world-readable under its final name.
    tmp=$(mktemp "$remarks_dir/.remote-config.XXXXXX")
    {
      printf 'ide_host=%s\n' "$host"
      printf 'ide_port=%s\n' "$port"
      printf 'ide_project=%s\n' "$project"
      printf 'ide_token=%s\n' "$token"
    } > "$tmp"
    chmod 600 "$tmp"
    mv "$tmp" "$target"
    echo "saved: $target"
    echo "host=$host port=$port project=$project"   # never the token
    ;;

  show)
    if [ ! -f "$target" ]; then
      echo "remote-config.sh: no stored configuration for $root (no file at $target)" >&2
      exit 1
    fi
    host=$(sed -n 's/^ide_host=//p' "$target")
    port=$(sed -n 's/^ide_port=//p' "$target")
    project=$(sed -n 's/^ide_project=//p' "$target")
    echo "host=$host"
    echo "port=$port"
    echo "project=$project"
    ;;

  forget)
    if [ -f "$target" ]; then
      rm -f "$target"
      echo "forgot: $target"
    else
      echo "no stored configuration for $root (no file at $target)"
    fi
    ;;

esac
