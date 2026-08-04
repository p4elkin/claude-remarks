#!/bin/sh
# Stores the four remote-IDE connection values SKILL.md step 1 otherwise needs pasted by hand on
# every run: the tunnel's local port on this machine, the token, the repository path as the IDE
# machine sees it, and the host. Keyed by THIS machine's own repository root — never by the value
# it stores — so two repositories on this machine can never share one configuration. See SKILL.md,
# "The four connection values are stored once, not pasted every time."
#
# Exit codes: 0 when the command did what it was asked, 2 for every refusal — a bad argument, a
# value this script will not store, a directory it will not write a token into, or a configuration
# that is not there to show. 2 is what watch-remarks.sh already uses for the same kind of refusal.
# Nothing here exits 1: in the watcher that code means "the deadline passed with nothing new", which
# is not a failure, and one code meaning two unrelated things across two sibling scripts is how a
# caller ends up acting on the wrong one.
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
  exit 2
fi

# The same 16 hex characters watch-remarks.sh's own hex16_of computes, and the same ones
# review/ReviewHandshake.kt's projectHash names its files with: sha256 of the path, first 16 hex
# characters. Written as a function here rather than inline, so the two scripts spell one idea one
# way. POSIX sh has no import, so the body itself is still duplicated; the name is what a reader
# matches across the two files.
hex16_of() {
  printf '%s' "$1" | shasum -a 256 | cut -c1-16
}

remarks_dir="${HOME}/.claude-remarks"
name=$(hex16_of "$root")
target="$remarks_dir/remote-$name.env"

case "$cmd" in

  save)
    host=127.0.0.1
    port=
    project=

    while [ $# -gt 0 ]; do
      # Every flag below takes a value. Checked before the branch that reads $2, because `set -u`
      # turns a missing one into "$2: unbound variable" — a shell error in place of the usage text
      # the unrecognized-argument branch already prints for every other mistake.
      case "$1" in
        --host | --port | --project)
          if [ $# -lt 2 ]; then
            echo "remote-config.sh: $1 needs a value" >&2
            usage
          fi
          ;;
      esac
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
      *[!0-9]* | '') echo "remote-config.sh: --port must be a whole number: '$port'" >&2; exit 2 ;;
    esac
    case "$project" in
      /*) ;;
      *) echo "remote-config.sh: --project must be an absolute path: '$project'" >&2; exit 2 ;;
    esac

    # A line break in either value would break the file this writes. SKILL.md step 1 reads it back
    # one line at a time, splitting each on the first `=`, so a value carrying a line break either
    # truncates what is read after it or injects a key of its own — ide_token being the one worth
    # injecting. --port is already digits only, so only these two need the check.
    newline='
'
    carriage_return=$(printf '\rx'); carriage_return=${carriage_return%x}
    for checked in "$host" "$project"; do
      case "$checked" in
        *"$newline"* | *"$carriage_return"*)
          echo "remote-config.sh: --host and --project must not contain a line break" >&2
          exit 2
          ;;
      esac
    done

    token=${CLAUDE_REMARKS_TOKEN:-}
    if [ -z "$token" ]; then
      echo "remote-config.sh: CLAUDE_REMARKS_TOKEN must be set in the environment — never pass" >&2
      echo "the token as an argument, it would be world-readable through ps" >&2
      exit 2
    fi

    if [ -d "$remarks_dir" ]; then
      # Refuses to write unless the directory is already owner-only: writeHandshake in
      # review/ReviewHandshake.kt holds itself to exactly that standard for a file holding the
      # same token.
      #
      # The two stat forms cannot be chained with a plain `||`: on GNU coreutils `-f` means
      # --file-system, takes no format argument, prints a filesystem block and exits non-zero, so
      # `stat -f ... || stat -c ...` would concatenate that block with the real answer. The BSD
      # form's own output is therefore thrown away unless it is octal digits and nothing else, and
      # only then is it used.
      mode=$(stat -f '%Lp' "$remarks_dir" 2>/dev/null) || mode=
      case "$mode" in
        '' | *[!0-7]*) mode=$(stat -c '%a' "$remarks_dir" 2>/dev/null) || mode= ;;
      esac
      case "$mode" in
        '' | *[!0-7]*)
          echo "remote-config.sh: neither stat form could read $remarks_dir's permissions, so this" >&2
          echo "script cannot confirm the directory is owner-only before writing a token into it." >&2
          echo "Check it by hand (it must carry no group or other access) and report this." >&2
          exit 2
          ;;
      esac
      last2=${mode#"${mode%??}"}
      if [ "$last2" != "00" ]; then
        echo "remote-config.sh: $remarks_dir is not owner-only (mode $mode) — refusing to write" >&2
        echo "the config file there. Fix its permissions (it must carry no group or other access)" >&2
        echo "and try again." >&2
        exit 2
      fi
    else
      mkdir -m 700 "$remarks_dir"
    fi

    # Temp file beside the target, then rename, the same shape review/AtomicWrite.kt uses: a
    # reader never sees a partially written file, and chmod runs before the rename so the file is
    # never briefly world-readable under its final name.
    #
    # Every one of the four steps is checked, and none of them used to be. "saved" printed and an
    # exit 0 followed whatever happened, so an unwritable directory or a full disk left a person
    # believing the configuration was stored and finding out on the next run, far from here. One
    # printf writes all four lines rather than four in a row, so one status covers the whole write:
    # with four, only the last one's status would be seen.
    tmp=$(mktemp "$remarks_dir/.remote-config.XXXXXX") || tmp=
    if [ -z "$tmp" ]; then
      echo "remote-config.sh: could not create a temporary file in $remarks_dir — check that the" >&2
      echo "directory is writable and that the disk is not full." >&2
      exit 2
    fi
    if ! printf 'ide_host=%s\nide_port=%s\nide_project=%s\nide_token=%s\n' \
      "$host" "$port" "$project" "$token" > "$tmp"; then
      echo "remote-config.sh: could not write $tmp — nothing was stored." >&2
      rm -f "$tmp"
      exit 2
    fi
    if ! chmod 600 "$tmp"; then
      # The token is already in that file, so it is removed rather than left behind under whatever
      # permissions it was created with.
      echo "remote-config.sh: could not make $tmp owner-only — nothing was stored." >&2
      rm -f "$tmp"
      exit 2
    fi
    if ! mv "$tmp" "$target"; then
      echo "remote-config.sh: could not move $tmp into place at $target — nothing was stored." >&2
      rm -f "$tmp"
      exit 2
    fi
    echo "saved: $target"
    echo "host=$host port=$port project=$project"   # never the token
    ;;

  show)
    if [ ! -f "$target" ]; then
      echo "remote-config.sh: no stored configuration for $root (no file at $target)" >&2
      exit 2
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
