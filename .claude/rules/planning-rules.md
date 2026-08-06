# Claude Remarks — rules for every planning and execution agent

These hold for the whole repository, in every task. Read `CLAUDE.md` at the repository root for the
design; this file is only the rules that must not be broken.

## Never write to a source file

The whole point of this plugin is that the working tree stays clean. No remark ever becomes a comment
in a source file, and no code path writes to one. There are no `// AI!` markers and none are ever
added. Guard 4 in `CLAUDE.md` greps for this and must stay empty.

## Never run `./gradlew runIde`

It starts an interactive sandbox IDE that never exits on its own, and an agent session that launches
it hangs until it is killed from outside. If a change can only be checked in a running IDE, write it
down as a hand check in the plan instead of trying to run it.

## Gradle runs in the foreground, one at a time

Every Gradle call runs in the **foreground** with a timeout of `600000` ms. Never background one —
the harness cannot tell a backgrounded Gradle daemon from a finished build. Never start a second
Gradle run while one is going: they fight over the same daemon and the same build directory.

The narrow per-task command is `./gradlew test --tests '<fully qualified test class>'`. The full
suite, `./gradlew test`, runs once in the verification task at the end, not per task.

## Verify platform APIs, never recall them

Do not trust training data for an IntelliJ Platform extension point name, a `plugin.xml` element, an
`AllIcons` constant, a Gradle plugin DSL block, or any method signature.

- **What does this actually do?** Read the checkout at `~/dev/oss/intellij-community`, shallow, tag
  `idea/2025.2.6.3`. Grepping it is cheap and it answers behaviour questions the jars cannot.
- **Does this method exist with this signature?** `javap` against
  `~/.gradle/caches/9.1.0/transforms/*/transformed/ideaIC-2025.2-aarch64/lib/`. Those jars are what
  the code is actually compiled against.

## Threading

- Swing and anything touching a `Document`, the VFS or a project service from the UI side runs on the
  EDT.
- Reading PSI or a `Document` off the EDT needs a read action, and the pattern in this codebase is
  `ReadAction.nonBlocking { … }.expireWith(…).coalesceBy(…).finishOnUiThread(…).submit(…)`.
- Never block the EDT and never call `invokeAndWait` from a request handler.
- `review/ReviewRestService.kt` runs on a netty IO thread. It must not touch the VFS, Swing, or
  `invokeAndWait`. Guard 5 in `CLAUDE.md` greps that file by name; anything with a consequence goes
  in another file that hops to the EDT with `invokeLater`.

## The seven guards in CLAUDE.md

Every command under "Rules that must not break" in `CLAUDE.md` must come back empty. Run the ones
your task could affect before you finish it. Do not widen a guard's grep to make it pass — move the
code instead.

## Never touch the real `~/.claude-remarks`

That directory holds a person's actual remarks and their published files. Any shell check that reads
or writes it runs in the scratchpad with `HOME` pointed at a temporary directory. This includes every
check of `src/main/resources/dev/sasha/clauderemarks/skill/watch-remarks.sh` and `remote-config.sh`.

⚠️ **Faking `HOME` is not enough on its own. Fake the port too.** A handshake file names a port, and
the ordinary port is `63342`, which is the IDE the person is actually working in. Phase 14's task 7
wrote a fake handshake under a fake `HOME`, left the port at `63342`, and its startup claim posted
`published-read` to the live IDE with a made-up token and nonce. It answered 403 and marked nothing,
so the only thing standing between that check and somebody's real batch being marked read was the
token check. Point every fake handshake at a port nothing is listening on, or at a fake endpoint you
started yourself — `8999` is what that task used afterwards.

## Never write into the real `~/.claude/skills`

Phase 15 adds code that installs the skill into `~/.claude/skills/claude-remarks`. ⚠️ On this machine
that path is a **symlink into the checkout**, and it is what Sasha's live agent sessions load. Running
the install path with the real `HOME` replaces the symlink with a copied directory and silently breaks
the development setup; a half-written copy breaks every listen session running at that moment.

So every test and every hand check of the install code runs with `HOME` pointed at a temporary
directory under the scratchpad. Never call the install code against the real home directory — not
once, not to "just see whether it works", not even when the code looks obviously correct. The one
person who may point it at a real harness is Sasha, by clicking the button in a running IDE, and that
is a hand check in the plan rather than anything an agent does.

## Never put the IDE token in a command argument

An argument sits in `curl`'s argv, which every process on the machine can read out of `ps`, and the
token is the only gate on the endpoint. Pass it on stdin:

```sh
printf 'header = "X-Claude-Remarks-Token: %s"\n' "$token" | curl --config - …
```

Never echo it, never write it into a log, never put it in a commit.

## Never kill a watcher by process name

No `pkill`, no `killall`, no `ps | grep | kill` matched on `watch-remarks.sh`. Every repository's
watcher on this machine runs a program with that name, so a name match kills other people's sessions.
A watcher is stopped only by the pid on line 1 of its own repository's `.watch` file, after checking
that pid is alive and that its command line names the same watched path.

## Toolchain facts you do not need to rediscover

- Kotlin 2.1.20, `jvmToolchain(21)`, Gradle wrapper 9.1.0.
- IntelliJ Platform Gradle Plugin 2.18.1, `intellijIdeaCommunity("2025.2")`, `sinceBuild = "252"`.
- `kotlin.stdlib.default.dependency = false`: the IDE ships its own stdlib.
- `verifyPlugin` subtracts `EXPERIMENTAL_API_USAGES` for two reasons, and both must go before the
  line can — the markdown preview extension and `ui/AnswerPopup.kt`'s `JBHtmlPane`.
- `INTERNAL_API_USAGES` is **not** subtracted any more. Do not revive the subtraction to make a new
  internal API call pass.

## Tests

Every task writes or updates tests for what it changed, as separate checklist items, and they pass
before the next task starts.

Pure Kotlin tests with no platform import run in milliseconds and are preferred. A test that needs a
project, a `Document` or a markup model extends `BasePlatformTestCase`. Every fixture-backed class
that asserts on the whole store clears it in `setUp` as well as `tearDown`: the light fixture project
is shared across test classes.
