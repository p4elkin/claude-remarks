# Installing the claude-remarks skill

`claude-remarks/` is a normal Claude Code skill. Install it by copying or symlinking the
directory into `~/.claude/skills/`:

```sh
ln -s "$(pwd)/docs/skill/claude-remarks" ~/.claude/skills/claude-remarks
```

or, without a symlink:

```sh
cp -r docs/skill/claude-remarks ~/.claude/skills/claude-remarks
```

⚠️ **The directory was called `claude-remarks-review` until phase 12 retired review mode.** An
install made before that points at a path this repository no longer has, so it has to be removed and
recreated under the new name; a symlink left behind simply dangles.

It is kept in this repository, not only under `~/.claude/skills`, because the skill and the IDE
endpoint it talks to are one protocol, and five separate pairs of halves have to agree:

- the request shape in `review/ReviewRestService.kt` and the `curl` calls in `SKILL.md`. There are
  four actions: `fetch`, `published-read`, `answer` and `open`. `fetch`'s body is `{project}` and
  nothing else — its optional `session` field went with review mode in phase 12, so it now always
  hands back whatever batch was last published for that project;
- the five fixed lines `PublishedHeader.render()` writes, in `review/PublishedRemarks.kt`, and the
  line-numbered reads that depend on that exact order. **There are three readers, not two, and a
  header reorder has to be checked against all of them** — this is the one bullet where a silent
  drift is possible, because reading the wrong line raises no error at all, it just returns the wrong
  string:
  - `review/PublishedRemarks.kt` writes the five lines and `publishedHeaderOf` parses lines 2 to 5
    back;
  - `SKILL.md` reads the header by line number in its inline shell, in both reading modes;
  - `watch-remarks.sh` reads it by line number too: **line 1** for the marker
    `<!-- claude-remarks: published -->` and **line 2** for `nonce: `. It reads no other line.

  The header was eight lines until phase 12, carrying `review:`, `label:` and `rejected:` after
  `remarks:`, which is how a rejection was told apart from a real batch. All three are gone with
  review mode: there is one kind of batch now. A file left behind by version `0.8.0` or earlier still
  reads correctly through every one of the three readers, because lines 1 to 5 did not move and
  nothing checks line 6 — the three extra lines simply read as part of the body. What does not
  survive is its acknowledgement, since the IDE forgot that batch when it restarted;
- the five values the `published-read` action answers — `ok`, `already-read`, `unknown-batch`,
  `unknown-project`, `bad-request` — read by the inline shell in `SKILL.md`'s two reading modes and,
  since phase 14, by `watch-remarks.sh` too. The watcher sends `published-read` only when it is
  launched with `--claim` and `--session`; with neither it sends nothing and only polls the published
  file or `POST /fetch`, which is what every caller before phase 14 gets. When it does claim, it puts
  the answer on the end of the one line it prints for the batch — `ok`, `already-read <session>` or
  `unknown-batch` — and an answer it could not get at all becomes `claim-failed` there instead, with
  the nonce still beside it, because a batch nobody hears about is worse than one claimed twice. The
  six values `fetch` answers — `ready`,
  `no-review`, `too-large`, `failed`, `unknown-project`, `bad-request` — are the status pair
  `watch-remarks.sh` holds the other half of, in its `--fetch` loop. ⚠️ `no-review` means "nothing
  has been published for this project". It kept that name from when a review was the only thing that
  published, and renaming it would break every deployed copy of the skill and of the watcher at once.
  `watch-remarks.sh` is still one of the three readers of the header above; the two facts are
  separate;
- the six values the `answer` action answers — `ok`, `unknown-batch`, `unknown-remark`, `too-large`,
  `unknown-project`, `bad-request` — plus the 16 KiB `MAX_ANSWER_BYTES` cap on the body, against the
  answer POST block in `SKILL.md`. This is the one pair where the IDE stores something a person then
  reads, so a drift here loses work rather than a poll: an answer refused as `too-large` is a body
  the session has to be told to shorten, and a session that treats every non-`ok` as retryable will
  send it again for ever; and
- the three values the `open` action answers — `ok` with an `opened` count, `unknown-project`,
  `bad-request` — against the open block in `SKILL.md`. This is the smallest pair: nothing is stored
  and nothing waits on it, so a drift costs one request rather than any work. `opened` counts paths
  the IDE accepted, not editors it opened, and `SKILL.md` has to keep saying so — the opening happens
  after the response has already been sent.

Keeping both halves of each in one place is what stops them drifting apart. The IDE and the
Claude Code session run on the same machine in the normal case, and over a tunnel in the remote
one — see "Over SSH: the IDE on another machine" in `claude-remarks/SKILL.md`.
