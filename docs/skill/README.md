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
- `REJECTED_MARKER` in `review/SendReview.kt` and the `grep -q '^<!-- claude-remarks: rejected -->'`
  in `SKILL.md`, character for character, anchor included;
- the five values the `ack` action answers — `ok`, `no-review`, `not-sent`, `unknown-project`,
  `bad-request` — and the branch in `SKILL.md` that reads them.

Keeping both halves of each in one place is what stops them drifting apart. It only works when the IDE and the Claude Code session run on the same machine —
see "Same machine only" in `claude-remarks-review/SKILL.md`.
