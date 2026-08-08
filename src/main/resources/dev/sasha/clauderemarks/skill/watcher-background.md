# Why the perl launch line is not the same as `nohup … &`

This file is the background half of `### Launching it, and why the perl line is there` in
`SKILL.md`: the measured proof for why the launch line is
`perl -e 'use POSIX qw(setsid); setsid(); exec @ARGV' -- '<skill dir>/watch-remarks.sh' <flags…>`
and not `nohup … &` or a `( … & )` double fork. The launch line itself, its flags, and the
instruction to use it stay in `SKILL.md` — this file is not needed to run one. Read it only when the
watcher will not start, or dies unexpectedly.

**What went wrong.** A session launches its watcher as an ordinary background Bash task. Four
watchers died in one evening with `Terminated: 15` — a plain `SIGTERM` from outside. It was not a
takeover: nothing in this skill kills a watcher any more, and no second watcher was running on that
repository. Watchers belonging to a different session on a *different* repository died at the same
moment, which is what says the signal was aimed at a process group and swept them all up.

**The three launch shapes, measured:**

| launch | PPID | PGID | in the launching shell's process group? |
|---|---|---|---|
| plain background task | the shell | the shell's | yes |
| `( nohup … & )` double fork | 1 | **still the shell's** | yes |
| `perl -e 'use POSIX qw(setsid); setsid(); exec @ARGV' -- …` | 1 once the launching shell exits | **its own** | no |

The double fork is the trap. It does reparent to `init`, so `ps` shows PPID 1 and it looks detached —
but a process group is not inherited from the parent that way, and `kill -- -<pgid>` still reaches
it. Only `setsid()` puts the process in a group of its own. Checked directly: with the three forms
started inside one process group and `kill -TERM -<that group>` sent, the plain form died, the double
fork died, and the `setsid` form survived.

⚠️ **macOS ships no `setsid` binary**, which is why the obvious one-word fix is not available here.
Perl is on every Mac and `POSIX::setsid` is in its core, so the line above is the portable form.
`exec` replaces perl with the watcher, so the watcher keeps perl's own pid — the pid it writes to its
pid file is still the pid to stop it by, and nothing about the pid file changes.

**Detaching means the watcher outlives the session, and `--owner` is what pays for that.** A watcher
in its own session is not stopped when the session that started it goes away. It reparents to `init`
and runs to its deadline — twelve hours, in listen mode. Such an orphan is not dangerous in the way
it first looks: it catches a batch, writes it to a file nobody reads and exits, and nothing is marked
read, because the *session* claims a batch and the watcher never does. What it does cost is the pid
file. The orphan holds it, so a person stopping "the watcher" for that repository stops the orphan
and leaves the live one running, and something looks like it is listening when nothing is. So every
exit-per-batch launch line passes `--owner`, and the watcher stops on its own when its session is
gone. ⚠️ The monitor branch passes none, and needs none: nothing detached it in the first place, so
the watcher is the monitor's own child and stops when the monitor stops.
