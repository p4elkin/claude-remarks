# Installing the claude-remarks-review skill

`claude-remarks-review/` is a normal Claude Code skill. Install it by copying or symlinking the
directory into `~/.claude/skills/`:

```sh
ln -s "$(pwd)/docs/skill/claude-remarks-review" ~/.claude/skills/claude-remarks-review
```

or, without a symlink:

```sh
cp -r docs/skill/claude-remarks-review ~/.claude/skills/claude-remarks-review
```

It is kept in this repository, not only under `~/.claude/skills`, because the skill and the IDE
endpoint it talks to are one protocol, and three separate pairs of halves have to agree:

- the request shape in `review/ReviewRestService.kt` and the `curl` calls in `SKILL.md`;
- the eight fixed lines `PublishedHeader.render()` writes, in `review/PublishedRemarks.kt`, and the
  line-numbered reads in `SKILL.md` that depend on that exact order — including the `rejected:` field,
  which is how a rejection is told apart from a real batch since phase 10. Before phase 10 a
  rejection was its own file with its own first-line marker, `REJECTED_MARKER`; that marker and the
  separate handoff file are both gone, and the published file's header is the one place either side
  ever checks;
- the five values the `ack` action answers — `ok`, `no-review`, `not-sent`, `unknown-project`,
  `bad-request` — and the branch in `SKILL.md` that reads them; and, since phase 10, the five values
  the `published-read` action answers — `ok`, `already-read`, `unknown-batch`, `unknown-project`,
  `bad-request` — read by the same script, `watch-remarks.sh`, that also backs review mode's wait.

Keeping both halves of each in one place is what stops them drifting apart. The IDE and the
Claude Code session run on the same machine in the normal case, and over a tunnel in the remote
one — see "Over SSH: the IDE on another machine" in `claude-remarks-review/SKILL.md`.
