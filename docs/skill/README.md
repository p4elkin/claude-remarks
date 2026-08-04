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
  line-numbered reads that depend on that exact order. **There are three readers, not two, and a
  header reorder has to be checked against all of them** — this is the one bullet where a silent
  drift is possible, because reading the wrong line raises no error at all, it just returns the wrong
  string:
  - `review/PublishedRemarks.kt` writes the eight lines and `publishedHeaderOf` parses them back;
  - `SKILL.md` reads the header by line number in its inline shell, in all three modes;
  - `watch-remarks.sh` reads it by line number too: **line 1** for the marker
    `<!-- claude-remarks: published -->`, **line 2** for `nonce: `, and **line 6** for `review: `,
    which is how `--require-review` recognizes the batch that answers its own review. It reads no
    other line.

  This includes the `rejected:` field, which is how a rejection is told apart from a real batch since
  phase 10. Before phase 10 a rejection was its own file with its own first-line marker,
  `REJECTED_MARKER`; that marker and the separate handoff file are both gone, and the published
  file's header is the one place any of the three ever checks;
- the five values the `ack` action answers — `ok`, `no-review`, `not-sent`, `unknown-project`,
  `bad-request` — and the branch in `SKILL.md` that reads them; and, since phase 10, the five values
  the `published-read` action answers — `ok`, `already-read`, `unknown-batch`, `unknown-project`,
  `bad-request` — read by the inline shell in `SKILL.md`'s two published-file modes, the one-shot
  read and listen mode. `watch-remarks.sh` is not the other half of *this* pair: it never sends
  `published-read` at all, it only polls the published file or `POST /fetch`. The seven values
  `fetch` answers — `ready`, `waiting`, `no-review`, `too-large`, `failed`, `unknown-project`,
  `bad-request` — are the status pair `watch-remarks.sh` holds the other half of, in its
  `--fetch` loop. It is still one of the three readers of the header above; the two facts are
  separate.

Keeping both halves of each in one place is what stops them drifting apart. The IDE and the
Claude Code session run on the same machine in the normal case, and over a tunnel in the remote
one — see "Over SSH: the IDE on another machine" in `claude-remarks-review/SKILL.md`.
