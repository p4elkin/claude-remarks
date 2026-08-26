---
worth: later
where: src/main/resources/dev/sasha/clauderemarks/skill/SKILL.md
added: 2026-08-26
---
# the skill carries the listen mode into every session that never listens

Every session that invokes this skill loads the whole of `SKILL.md` before it does any work, and
more than half of that file describes a mode most sessions never enter. Measured on 2026-08-26,
85,016 bytes and 1,340 lines:

| bytes | share | section |
| --- | --- | --- |
| 2,544 | 3.0% | the opening |
| 6,971 | 8.2% | `## Open files in the IDE` |
| 14,164 | 16.7% | `## Read remarks the person already published` |
| **34,060** | **40.1%** | **`## Listen for the next batch`** |
| 9,152 | 10.8% | `## Answer the remarks that ask for an answer` |
| 3,335 | 3.9% | `## Where the skill's own files are` |
| **13,060** | **15.4%** | **`## The watcher script`** |

The two bold rows are one feature: 47,120 bytes, 55.5% of the file, and CLAUDE.md says that mode is
"started only when a person asks for it in words". The everyday path — open files, read the
published batch, answer what asks for an answer — is 30,287 bytes, 35.6%.

The same argument the last round already made. `CHANGELOG.md`, "after 0.12.1 — the skill loads less
of itself", split three reference files out by when a session needs them and took the file from
96,306 to 80,329 bytes. It moved the branches that were easy to lift. The listen mode is the big one
it did not touch, and it has grown back past where it started.

What the split would look like: `## Listen for the next batch` and `## The watcher script` become one
reference file, or two, pointed at by one sentence saying when to go and read them — the shape the
three existing reference files already use. Nothing is deleted. A session that is asked to listen
reads the same words it reads today, one file later.

Two things to get right, both of which the last round already learned:

- ⚠️ **Both update surfaces compare version strings only.** Splitting the file without bumping
  `build.gradle.kts` leaves every existing install on the old copy, `shouldNotifySkillInstall` stays
  quiet, and the settings row still says "up to date". The person's only route is the Reinstall
  button. Do it in a release, not between them.
- `SkillInstall.SKILL_FILES` is the only enumeration of what gets installed. A new reference file
  that is not added there is silently not installed, and `SkillResourceTest`'s pointer guard is what
  catches the other direction — a sentence sending a session to a file nobody ships.

Why it is worth doing at all, beyond tokens: the sibling VS Code extension is about to grow a skill
of its own, and the size budget was set by looking at this one. A skill that grows past comfortable
is a skill somebody switches to `name-only` in `skillOverrides` — and a skill loaded name-only cannot
be the thing that notices a publish. This file is already at the size where that trade starts to look
attractive.
