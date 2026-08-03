# Claude Remarks — Phase 7 Implementation Plan

**Tell the IDE the remarks were actually delivered.**

**Status: not started.** Branch `claude-remarks-phase1-2`, HEAD `041d5a3`, tree clean. Phase 6 is
complete, released as 0.3.0, and checked by hand in a real IDE.

**This plan takes the number 7, so the remote-over-SSH work becomes phase 8.** Four documents still
promise that phase 7 is the remote work. Task 9 corrects every one of them. Do not leave two
documents disagreeing about what phase 7 is.

**Citations name symbols, not line numbers.** Same reason as phase 6: a symbol name survives the
next commit in the same file, a line number does not.

## Contents

1. [What is true today](#1-what-is-true-today)
2. [Platform facts, checked against the real artifacts](#2-platform-facts-checked-against-the-real-artifacts)
3. [The two open questions, decided](#3-the-two-open-questions-decided)
4. [The shape of the change](#4-the-shape-of-the-change)
5. [Decisions, and the alternatives rejected](#5-decisions-and-the-alternatives-rejected)
6. [Still to verify](#6-still-to-verify)
7. [Scope judgement: what I cut](#7-scope-judgement-what-i-cut)
8. [Rules that must hold at every step](#8-rules-that-must-hold-at-every-step)
9. [Ordering and parallel waves](#9-ordering-and-parallel-waves)
10. [Implementation steps](#10-implementation-steps)
    - [Task 1: Check the ground before building on it](#task-1-check-the-ground-before-building-on-it)
    - [Task 2: The review's phase and its deadline, as plain data](#task-2-the-reviews-phase-and-its-deadline-as-plain-data)
    - [Task 3: The service transitions, and the deadline task](#task-3-the-service-transitions-and-the-deadline-task)
    - [Task 4: The endpoint's second action](#task-4-the-endpoints-second-action)
    - [Task 5: Send writes, the acknowledgement marks sent](#task-5-send-writes-the-acknowledgement-marks-sent)
    - [Task 6: The banner and the buttons stop lying](#task-6-the-banner-and-the-buttons-stop-lying)
    - [Task 7: The skill declares a deadline, acknowledges the read, and reports leaving](#task-7-the-skill-declares-a-deadline-acknowledges-the-read-and-reports-leaving)
    - [Task 8: Verify the constraints and the whole suite](#task-8-verify-the-constraints-and-the-whole-suite)
    - [Task 9: Documentation, the phase renumbering, and the version](#task-9-documentation-the-phase-renumbering-and-the-version)
11. [Known limits](#11-known-limits)
12. [Hand checks in a sandbox IDE](#12-hand-checks-in-a-sandbox-ide)

## 1. What is true today

Read from the source on this branch, not assumed.

**The send clears the waiting review immediately.** `sendToWaitingReview` in `review/SendReview.kt`
writes the handoff file, then calls `markRemarksSent(project, prepared.ids)`, then
`WaitingReviewService.getInstance(project).clear()`, then shows the balloon. **This is the single
most important fact in the plan.** The brief says the read acknowledgement should be recorded on
`WaitingReviewState`, but by the time the skill reads the file there is no state left to record it
on. So the send has to stop clearing the review. That one change is what the rest of the design
hangs from.

**The balloon says "Sent", and it is the last thing that happens.**
`notifyRemarks(project, "Sent $count remark${...} to Claude Code.")` in `sendToWaitingReview`. It
runs after a successful `atomicWriteString` and nothing else. A write is all it knows about.

**The banner is binary and only the IDE can clear it.** `updateBanner` in
`ui/RemarksToolWindowFactory.kt` reads `WaitingReviewService.current()`, hides the banner when it is
null, and otherwise sets one text: `"Claude Code is waiting: " + escapeXmlEntities(label.take(120))`.
The only two callers of `clear()` are the send and the banner's Cancel link, both inside the IDE.

**The banner's two links are created once, in the field initializer**, not per refresh:
`createActionLabel("Send remarks")` and `createActionLabel("Cancel")` sit in the `banner` property of
`RemarksPanel`. Only `banner.text` is changed later. That shapes task 6: the text can vary per phase
for free, the link set cannot.

**The banner repaints only from `refresh()`.** `updateBanner()` is called at the end of the
`finishOnUiThread` block in `RemarksPanel.refresh()`, and `refresh()` runs from the constructor and
from the `REMARKS_CHANGED` subscription. So a state change that publishes nothing leaves the banner
showing the old text. `WaitingReviewService.notifyPanel()` already publishes on every `start` and
`clear`, through `invokeLater`.

**The toolbar polls, the banner does not.** `ToolbarAction.update` runs on the EDT on every toolbar
tick and calls `WaitingReviewService.getInstance(project).current()` for the Send button. So a
decision expressed inside `current()` reaches the Send button on its own, and reaches the banner only
on the next publish. The tick is real, and its conditions matter for the hand checks:
`ToolbarUpdater.MyTimerListener` registers with `ActionManager.addTimerListener`, and
`ActionManagerImpl` fires that timer every `TIMER_DELAY = 500` milliseconds while the IDE window is
active and every `DEACTIVATED_TIMER_DELAY = 5000` while it is not. The listener returns early unless
`updater.component.isShowing()`. So: about half a second, and only while the tool window is open and
the IDE has focus.

**`WaitingReviewState` carries four fields:** `sessionId`, `label`, `outputPath`, `startedAt`. There
is no deadline and no phase. `outputPath` is the temp *directory*; `handoffFile(outputPath)` in
`review/ReviewRestService.kt` names the file inside it.

**`startOrConflict` is pure and takes the output path as a supplier.** Three branches: no current
review accepts a new one, the same session id gets the current state back unchanged, anything else
conflicts. The supplier is only invoked on the first branch, which is what stops a retry or a
conflict from creating a temp directory.

**`WaitingReviewService` is `@Volatile` field plus `@Synchronized` methods, on purpose.** Not an
`AtomicReference`: `updateAndGet` re-runs its lambda on contention and that lambda creates a
directory. `current()` is deliberately unsynchronized so the EDT never blocks on a lock a netty
thread holds across `Files.createTempDirectory`. Both facts are in `docs/claude/design.md`, section
"One waiting review per project". This plan keeps both.

**The service is not `Disposable` today.** `ReviewHandshakeService` in `review/ReviewHandshake.kt`
is, and it is the shape to copy: a project-level `@Service` implementing `Disposable`.

**`requestIsAllowed` guards the whole service, not one action.** It is called from
`ReviewRestService.isHostTrusted`, which the platform calls in `process` before `execute`. A second
action under the same service name is guarded by it with no change at all.

**`execute` ignores the request path completely.** It parses the body and starts a review. Combined
with the platform's prefix matching in [section 2](#2-platform-facts-checked-against-the-real-artifacts),
that means `POST /api/claude-remarks/anything` starts a review today. Task 4 makes only `/start` do
that, and answers `bad-request` for an unknown action.

**Marking a remark sent is idempotent.** `RemarkStore.markSent` filters to remarks whose status is
not already `SENT` and returns how many changed; `markRemarksSent` in `store/RemarkEdits.kt`
publishes only when that count is above zero.

**There is no function that marks a remark pending again.** `store/RemarkEdits.kt` exports eight
mutation functions and none of them moves a remark back from `SENT`. That is why
[section 3](#3-the-two-open-questions-decided) resolves the first open question without needing one.

**`notifyRemarks` is `internal` in `action/CopyRemarks.kt`** and takes a `NotificationType`. The
notification group `Claude Remarks` is already registered in `plugin.xml`. No new registration is
needed anywhere in this phase, and `plugin.xml` is not edited at all.

**The skill declares its own timeout already, as a literal.**
`docs/skill/claude-remarks-review/SKILL.md` step 6 has `deadline=$(( $(date +%s) + 1800 ))`. The IDE
is never told about it. That literal becomes the number the skill sends.

**The skill's documentation admits the problem this phase fixes.** Step 6 says "A timeout does not
clear the waiting review inside the IDE — the person clears it themselves from the banner's Cancel
link."

**`SendReviewTest` is the guard that will move.** It asserts that a successful write marks the
remarks sent, and that a failed write marks nothing. The first assertion inverts in task 5; the
second one stays exactly as it is.

## 2. Platform facts, checked against the real artifacts

Source read from `~/dev/oss/intellij-community`, tag `idea/2025.2.6.3`. Signatures checked with
`javap` against
`/Users/sasha/.gradle/caches/9.1.0/transforms/c3bd2a49efd270bc2558f65097ad6f39/transformed/ideaIC-2025.2-aarch64/lib/`.

**A second action needs no plugin.xml change: `RestService.isSupported` already accepts sub-paths.**
From `platform/built-in-server/src/org/jetbrains/ide/RestService.kt`, after it has matched
`/api/<serviceName>`:

```kotlin
if (uri.length == minLength) {
  return true
}
else {
  val c = uri[minLength]
  return c == '/' || c == '?'
}
```

So `/api/claude-remarks`, `/api/claude-remarks/start` and `/api/claude-remarks/ack` all reach the
same handler. `isMethodSupported` is checked first, so all of them stay POST-only.

**The platform's own way to dispatch on the sub-path.** `UploadLogsService` in the same package is
one `RestService` with two actions, and it splits the path by hand:

```kotlin
val path = urlDecoder.path().split(serviceName).last().trimStart('/')
if (path == "status") { ... }
if (path != "uploads") { sendStatus(HttpResponseStatus.BAD_REQUEST, false, channel); return null }
```

Task 4 copies that expression. **Do not use `substringAfterLast('/')`** — on the bare path
`/api/claude-remarks` that returns `claude-remarks`, which would be read as an action name.

**`QueryStringDecoder` has both `path()` and `rawPath()`.** Confirmed by `javap`. `path()` is the
decoded path with the query string already removed, so `/api/claude-remarks/ack?x=1` gives
`/api/claude-remarks/ack`.

**The rate limit is 30 requests per minute per remote address**, from
`getMaxRequestsPerMinute() = Registry.intValue("ide.rest.api.requests.per.minute", 30)`. One review
now costs at most three requests instead of one: start, then either the read or the abandoned
acknowledgement. Nowhere near the limit.

**The scheduled executor exists and returns a plain `ScheduledExecutorService`.** `javap` on
`com.intellij.util.concurrency.AppExecutorUtil`:

```
public static java.util.concurrent.ScheduledExecutorService getAppScheduledExecutorService();
```

`AppExecutorUtil` is already imported in `review/SendReview.kt` and
`ui/RemarksToolWindowFactory.kt`, for `getAppExecutorService()`. Task 3 uses the scheduled one from
the same class, so no new dependency and no new import root.

**The existing smoke test already exercises the path dispatch, so task 4 needs no new mechanism.**
`ReviewEndpointSmokeTest` builds `DefaultFullHttpRequest(HTTP_1_1, POST, "/api/claude-remarks/start",
body)` and calls `execute` with `QueryStringDecoder(request.uri())`. So `urlDecoder.path()` inside
`execute` already returns `/api/claude-remarks/start` in that test today, and a second test with a
different uri exercises a second action for real.

**`process` runs `isHostTrusted`, then the rate limit, then `execute`.** Unchanged from phase 6, and
it is the reason a second action inherits the whole security rule for free.

## 3. The two open questions, decided

**Question one: does an abandoned review leave the remarks pending, or mark them pending again if
they were already marked sent?**

**Decided: they are never marked sent in the first place, so there is nothing to undo.** The rule the
codebase already states is "nothing is marked sent unless the handover succeeded". This phase only
sharpens what "succeeded" means: not "the file was written" but "the agent said it read the file". So
the send records which ids it wrote and leaves them pending; the read acknowledgement is what calls
`markRemarksSent`.

Why this and not "mark them sent, then mark them pending again on abandon": the second version needs
a ninth mutation function in `store/RemarkEdits.kt`, needs the ids kept somewhere so it can find
them, and needs a rule for a remark the person edited in between. The version chosen needs the ids
kept — that part is the same — and nothing else. It is strictly less code and it never shows the
person a gray remark that was not delivered.

What it costs: between pressing Send and the acknowledgement arriving, the remarks stay black. The
skill polls once a second, so that is about one second in the normal case. The banner says what is
happening during it. And if no acknowledgement ever comes, the remarks stay pending, which is the
direction where nothing is lost.

**Question two: is the deadline a plugin setting or a number the skill declares?**

**Decided: the skill declares it, in the `start` request.** The skill already has the number as a
literal in its own wait loop. Two numbers in two places drift, and the failure when they drift is
ugly in both directions: a plugin deadline shorter than the skill's makes the IDE call a live agent
dead, and a longer one leaves the banner lying for exactly the window this phase exists to close. One
number, sent once, used by both sides.

The cost is that the number arrives over HTTP from outside, so it has to be validated at the
boundary. `clampDeadlineSeconds` in `ReviewRestService.kt` does that: absent means 1800 seconds, and
anything present is clamped into 60 seconds through 24 hours. The clamp lives at the endpoint, not in
the service, because that is where untrusted input arrives — and because it lets a test hand the
service a deadline of zero and get an instantly stale review, which is the only practical way to test
staleness without waiting a minute.

## 4. The shape of the change

One new field pair on the review state, three new service transitions, one new endpoint action, and
a banner that reads the phase instead of just checking for null. Nothing about rendering, the store's
shape, the clipboard path, the handshake or the atomic write moves.

The review's life, and where each of the three signals enters:

```mermaid
stateDiagram-v2
    [*] --> Waiting : "POST start, deadline declared by the skill"
    Waiting --> Sent : "person presses Send<br/>file written, ids recorded<br/>NOTHING marked sent yet"
    Sent --> [*] : "POST ack read<br/>markRemarksSent, balloon 'Claude Code read N remarks'"
    Waiting --> [*] : "POST ack abandoned<br/>balloon 'Claude Code stopped waiting'"
    Sent --> [*] : "POST ack abandoned<br/>balloon 'left without reading, still pending'"
    Waiting --> [*] : "deadline passed<br/>same as abandoned, no request needed"
    Sent --> [*] : "deadline passed<br/>same as abandoned, no request needed"
    Waiting --> [*] : "person presses Cancel"
    Sent --> [*] : "person presses Cancel"
```

The normal run, end to end:

```mermaid
sequenceDiagram
    participant Skill as Claude Code skill
    participant IDE as endpoint<br/>/api/claude-remarks
    participant Svc as WaitingReviewService
    participant Panel as tool window
    participant Out as handoff file

    Skill->>IDE: POST /start with deadlineSeconds
    IDE->>Svc: start, phase Waiting, deadlineAt set
    Svc->>Panel: banner "Claude Code is waiting: label"
    Note over Skill: poll for the handoff file
    Panel->>Out: Send: render, write, rename
    Panel->>Svc: markSent(ids), phase Sent
    Svc->>Panel: banner "Sent N remarks.<br/>Waiting for Claude Code to read them."
    Note over Panel: balloon "Wrote N remarks for Claude Code."
    Skill->>Out: the file exists, read it whole
    Skill->>IDE: POST /ack event read
    IDE->>Svc: acknowledge, clear
    Svc->>Panel: markRemarksSent, banner hidden
    Note over Panel: balloon "Claude Code read N remarks."
```

Where the code changes:

```mermaid
classDiagram
    class WaitingReviewState {
        «plain data, no platform»
        +sessionId, label, outputPath, startedAt
        +deadlineAt Long  NEW
        +phase ReviewPhase  NEW
        +isStale(now) Boolean  NEW
    }
    class ReviewPhase {
        «sealed, NEW»
        Waiting
        Sent(ids, at)
    }
    class WaitingReviewService {
        «now also Disposable»
        +current() masks a stale review  CHANGED
        +start(session, label, deadlineSeconds)  CHANGED
        +markSent(ids)  NEW
        +acknowledge(session, event)  NEW
        +expireIfStale()  NEW
        -expiry ScheduledFuture  NEW
    }
    class ReviewRestService {
        +clampDeadlineSeconds(s)  NEW
        +execute dispatches on the path  CHANGED
        +the ack action  NEW
    }
    class SendReview {
        +sendToWaitingReview: no markRemarksSent  CHANGED
        +finishReview(project, session, end)  NEW
        +expireStaleReview(project)  NEW
    }
    class RemarksPanel {
        +updateBanner reads the phase  CHANGED
        +Send enabled only in Waiting  CHANGED
    }
    WaitingReviewState o-- ReviewPhase
    WaitingReviewService o-- WaitingReviewState
    ReviewRestService --> SendReview : ack consequences
    SendReview --> WaitingReviewService
    RemarksPanel --> WaitingReviewService
```

## 5. Decisions, and the alternatives rejected

**One `ack` action with an `event` field, not two paths.** `/api/claude-remarks/ack` with
`{"session": ..., "project": ..., "event": "read" | "abandoned"}`. Two paths, `/read` and
`/abandoned`, would need two request classes and two dispatch branches for one shared session and
project check. The event is one string compared in one place.

**The answer stays "always HTTP 200 with a `status` field".** Phase 6's contract, in
`ReviewRestService`'s KDoc: real status codes are reserved for what the platform produces above
`execute`, so a plumbing failure never looks like an application answer to a shell script. The ack
adds three application answers — `ok`, `no-review`, `not-sent` — and reuses `unknown-project` and
`bad-request` unchanged.

**The deadline is enforced two ways, and each covers a hole the other leaves.**
`WaitingReviewState.isStale(now)` is a pure comparison used by `current()`, so a stale review can
never be sent to and can never enable a button, no matter what the scheduler did. A scheduled task at
the deadline is what makes the *screen* catch up, because the banner only repaints when something
publishes. The predicate alone leaves a stale banner on screen until the person happens to change a
remark. The task alone is a promise about timing, and a laptop that slept through the deadline breaks
it. Together they are about fifteen lines.

**The scheduled task is not a repeating poll.** One `schedule` per accepted review, cancelled on
`clear` and on `dispose`. A repeating tick would run for the whole IDE session for a feature that is
idle almost all of the time.

**A review whose deadline has passed no longer blocks a new one.** `startOrConflict` gains a stale
branch, so a killed session does not leave the project unusable for the next review until somebody
presses Cancel. The branch order matters and is not arbitrary: the same-session retry is checked
*before* staleness, so a retry always gets its own state and its own output path back, even a late
one. A different session gets in only when the current review is dead.

**The banner says "the agent left" with a balloon, not with a third banner state.** Keeping a
finished review on screen means keeping state that nothing removes, and then answering "when does
this text go away". A balloon is the platform's own idiom for a fact that has already happened, and
the notification group already exists.

**The banner's two links stay as they are, and Send refuses politely in the Sent phase.**
`createActionLabel` only adds a label, and the links are built once in the field initializer. Making
the link set depend on the phase means rebuilding the banner. Instead `sendToWaitingReview` answers a
press in the Sent phase with "Already sent. Waiting for Claude Code to read them." — a dead control
is what this codebase avoids, and a control that says why is the cheap version of not being dead.

**Re-sending in the Sent phase is refused, not merged.** The agent may already have read the first
file. Overwriting it would mean the person cannot tell which version was read.

**The deadline is not extended when the remarks are sent.** The skill's own wait loop is not
extended either, so extending the IDE's copy would recreate exactly the drift
[section 3](#3-the-two-open-questions-decided) rejects. If the person sends at second 1799 of 1800,
the acknowledgement has one second to arrive. That is the truth about that agent, not a bug.

**The ack carries the project path, like `start` does.** The alternative — find the project by
scanning open projects for one holding this session id — would let the skill send one field less, and
`projectForPath` would go unused for the new action. Symmetry with `start` is worth more than one
field, and the skill already has `$root` in a variable.

**No read detection on the plugin side.** Carried straight from `docs/ideas.md`: there is no portable
signal for "this file was read", and anything built on access times or file locks is wrong on some
filesystem. The agent knows; it says so.

## 6. Still to verify

Four things. Each has the check written into the task that needs it.

**A scheduled task can outlive the project it names.** The delay can be up to 24 hours, so the task
can fire after the project closed, and `project.service<...>()` on a disposed project throws. I
believe two cheap guards cover it, and **task 3 must do both**: `WaitingReviewService` implements
`Disposable` and cancels the future in `dispose()`, and the task body starts with
`if (project.isDisposed) return`. The cancel is the normal path; the `isDisposed` check covers the
task already being handed to a thread when disposal happens. Task 3 also asserts that
`ScheduledFuture.cancel(false)` is the right call — do not use `cancel(true)`, which interrupts a
running task for no benefit here.

**Whether `EditorNotificationPanel` can remove an action label.** I did not check the jars, because
the plan does not need it: both links stay and the phase only changes `banner.text`. If a later change
does want per-phase links, `javap com.intellij.ui.EditorNotificationPanel` first. Do not add this to
phase 7.

**Whether the notification balloon is safe to raise from the thread that ends the review.** The read
and abandoned acknowledgements arrive on a netty IO thread. The plan does not depend on the answer:
task 4 puts the store mutation and the balloon inside `invokeLater`, the same way
`WaitingReviewService.notifyPanel` already does, so both run on the EDT. **Task 4 must not "simplify"
that away** even if a balloon appears to work from a background thread.

**Whether `./gradlew verifyPlugin` still reports exactly one accepted internal-API usage.**
`SegmentedButton.getComponent()`, allowed in `a055473`. Nothing in this plan reaches for an internal
API — `AppExecutorUtil` is public, `ScheduledFuture` is the JDK. Task 8 confirms the count did not
grow.

## 7. Scope judgement: what I cut

**No plugin setting for the deadline.** [Section 3](#3-the-two-open-questions-decided) settles it: the
skill declares the number. A setting would be a second source of truth for the same value.

**No "the agent is thinking" progress state.** A signal for "the agent read the remarks and is now
working" was tempting and is not in the brief. Reading is the last thing the IDE can know about
without the agent reporting its own progress, which is a much larger idea.

**No history entry for an abandoned review.** `store/RemarkHistory.kt` archives on *clear*, and
phase 6 deliberately declined to write a second durable copy on handover. Nothing about an abandoned
review changes that argument: the remarks are still in the store, still pending, and still visible.

**No retry of a failed acknowledgement beyond one attempt.** The skill sends the ack once and reports
a failure. The deadline backstop already covers "the IDE never heard about it", so retry logic in a
shell script would add a loop to cover a case that is already covered.

**No change to `plugin.xml`.** The sub-path routes to the existing handler, and the notification group
is registered.

## 8. Rules that must hold at every step

The five grep guards in `CLAUDE.md` must come back empty after every task.

1. **`anchor/` and `render/PromptRenderer.kt` stay free of `com.intellij`.** Phase 7 touches neither.
2. **`store/RemarkEdits.kt` holds the only functions that change a remark.** Phase 7 calls
   `markRemarksSent` and nothing else, from `review/SendReview.kt`, which already imports it. It adds
   no ninth mutation function — see [section 3](#3-the-two-open-questions-decided).
3. **No code writes to a source file.** Phase 7 writes no file at all that phase 6 did not already
   write.
4. **Nothing remark-related enters version control.** No new file outside the project and no new
   file inside one.
5. **`review/ReviewRestService.kt` never touches the VFS, Swing, or `invokeAndWait`:**

   ```bash
   grep -rnE "invokeAndWait|projectRoot\(|FileEditorManager|VfsUtil|SwingUtilities" \
     src/main/kotlin/dev/sasha/clauderemarks/review/ReviewRestService.kt   # must be empty
   ```

   **The trap phase 6 hit is live again in this phase.** That grep is line-based and cannot tell a
   comment from code. Task 4 adds an explanation of why the ack's consequences live in another file,
   and that explanation **must not name any of the five symbols above**. Say "the file that owns the
   editor side" and point at `review/SendReview.kt` by name instead.

Five more, carried in or new:

6. **Prove every test is a real guard by mutation.** Break the production line the test covers, watch
   the named test fail, restore. Every task below names its mutation.
7. **Never block the EDT, and never touch Swing off it.** The endpoint runs on a netty IO thread. All
   store and UI work triggered by an acknowledgement goes through `invokeLater`.
8. **Keep the two threading decisions in `WaitingReviewService`.** `@Volatile` plus `@Synchronized`,
   never an `AtomicReference`; `current()` stays unsynchronized. Both are recorded in
   `docs/claude/design.md`. Task 3 adds a pure comparison inside `current()` and nothing that takes a
   lock or does IO.
9. **Nothing is marked sent unless the agent said it read the file.** This is the phase's whole point
   and `SendReviewTest` is its guard.
10. **Never run `git add -A` or `git add .`** Several agents work in this repository. Every commit
    step names the exact files to stage. If `git status --porcelain` shows a file you did not touch,
    leave it and say so in the task report.

## 9. Ordering and parallel waves

**No parallel waves.** Every task consumes the one before it: task 2 defines the phase and the
deadline that task 3's transitions move between, task 3 defines the transitions task 4's endpoint
calls, task 4 creates the acknowledgement path that task 5 hands the marking over to, and task 6
renders the phase the first five produce. Nine small tasks in one subsystem; splitting them into
waves would cost more coordination than it saves.

The order is: check the ground (1), the phase and deadline as data (2), the service transitions (3),
the endpoint action (4), the send stops marking sent (5), the banner and the buttons (6), the skill
(7), verify (8), document (9).

**Task 4 comes before task 5 on purpose.** Task 5 is what stops `sendToWaitingReview` from calling
`markRemarksSent`. If it ran first, there would be a commit where nothing in the plugin ever marks a
remark sent through the review path. In this order the acknowledgement can already mark them before
the send stops doing it, so no step leaves the plugin worse than it started.

## 10. Implementation steps

TDD throughout: write the failing test, run it, watch it fail for the right reason, then implement.
Run the narrow per-task command after each change. The full suite runs once, in task 8. Complete each
task before starting the next.

### Task 1: Check the ground before building on it

**Model:** haiku

**Files:**
- Read only: `CLAUDE.md`, the "Rules that must not break" section
- Read only: `src/main/kotlin/dev/sasha/clauderemarks/review/WaitingReview.kt`
- Read only: `src/main/kotlin/dev/sasha/clauderemarks/review/SendReview.kt`

Five minutes, and it stops the whole phase from being built on a base that moved.

- [ ] `git status --porcelain` must be empty. Another agent may be mid-task in this worktree. If it is
      not empty, **stop and report** what is there rather than working around it.
- [ ] `WaitingReviewState` still has exactly the four fields `sessionId`, `label`, `outputPath`,
      `startedAt`. If it already has more, this plan was written against an older tree — stop and
      report.
- [ ] `sendToWaitingReview` still calls `markRemarksSent` and then
      `WaitingReviewService.getInstance(project).clear()`. Tasks 5 and 6 both depend on that being the
      starting point.
- [ ] run all five grep guards from
      [section 8](#8-rules-that-must-hold-at-every-step) now, before any change. All five must be
      empty. A guard that was already failing must not be blamed on this phase.
- [ ] `./gradlew test` passes on the untouched tree. Report the test count, so task 8 can compare.
- [ ] no commit — this task writes nothing

### Task 2: The review's phase and its deadline, as plain data

**Model:** sonnet

**Files:**
- Edit: `src/main/kotlin/dev/sasha/clauderemarks/review/WaitingReview.kt` — add the `ReviewPhase`
  sealed interface, two fields and `isStale` on `WaitingReviewState`, and the stale branch in
  `startOrConflict`. Do not touch `WaitingReviewService` in this task.
- Edit: `src/test/kotlin/dev/sasha/clauderemarks/review/WaitingReviewTest.kt` — the existing plain
  JUnit class for `startOrConflict`

The whole phase decision as data a test can build by hand, with no service and no fixture.

```kotlin
/**
 * How far a review has got. A review in [Sent] has had its handoff file written and carries the ids
 * that were written, so the read acknowledgement knows what to mark sent. A sealed pair rather than
 * a nullable id list, so "ids exist only after a send" is not a rule a caller can forget.
 */
sealed interface ReviewPhase {
    object Waiting : ReviewPhase
    data class Sent(val ids: List<String>, val at: Long) : ReviewPhase
}
```

`WaitingReviewState` gains `deadlineAt: Long` and `phase: ReviewPhase = ReviewPhase.Waiting`, plus:

```kotlin
/** Past its deadline, so the agent that asked for it is presumed gone. */
fun isStale(now: Long = System.currentTimeMillis()): Boolean = now >= deadlineAt
```

The default argument is what lets production callers stay quiet and tests pass a clock.

`startOrConflict` gains `deadlineSeconds: Long` and `now: Long = System.currentTimeMillis()`, both
**before** the `outputPath` supplier so the trailing-lambda call style keeps working. Its branches
become, in this order:

```kotlin
current == null -> accept a new state
current.sessionId == session -> StartResult.Accepted(current)   // an honest retry, however late
current.isStale(now) -> accept a new state, replacing the dead one
else -> StartResult.Conflict(current)
```

**The order is the decision, not an accident.** Same session first means a retry always gets its own
output path back, even after the deadline, so a slow skill never starts polling a directory the
plugin has just replaced. Staleness second means a *different* session gets in only when the current
review is really dead.

- [ ] add the failing tests to `WaitingReviewTest.kt`. Plain JUnit, no fixture:
  - `a review is stale once its deadline has passed` — build a state with `deadlineAt = 1000`, assert
    `isStale(999)` is false, `isStale(1000)` is true, `isStale(1001)` is true. The boundary matters:
    the scheduled task in task 3 fires exactly at the deadline, and with a `>` comparison it would
    find the review not yet stale and do nothing.
  - `a stale review does not block a different session` — a current state with a passed deadline, a
    new session id, assert `Accepted` and that the accepted state is the new one
  - `a retry of the same session gets the same state back even after the deadline` — assert
    `Accepted(current)` is the *same* state instance, and that the output-path supplier was never
    called
  - `a live review still conflicts with a different session` — the existing test, unchanged except
    for the new arguments
  - `the deadline is computed from the same instant as the start time` — pass `now = 5000` and
    `deadlineSeconds = 60`, assert `startedAt == 5000` and `deadlineAt == 65000`. Two separate
    `System.currentTimeMillis()` calls would make these disagree by a millisecond or two, which is
    harmless in production and exactly the kind of drift a reader later has to rule out.
- [ ] run `./gradlew test --tests "dev.sasha.clauderemarks.review.WaitingReviewTest"` — expect a
      compile failure
- [ ] implement. Every existing caller of `startOrConflict` and of the `WaitingReviewState`
      constructor has to pass the new arguments; the compiler finds them all.
- [ ] the same command passes
- [ ] **mutation:** change `isStale` to `now > deadlineAt` — `a review is stale once its deadline has
      passed` must fail on the boundary case. Then move the staleness branch above the same-session
      branch — `a retry of the same session gets the same state back even after the deadline` must
      fail. Restore both.
- [ ] commit: `feat: a review carries a phase and a deadline` — stage exactly
      `src/main/kotlin/dev/sasha/clauderemarks/review/WaitingReview.kt` and
      `src/test/kotlin/dev/sasha/clauderemarks/review/WaitingReviewTest.kt`

### Task 3: The service transitions, and the deadline task

**Model:** sonnet

**Files:**
- Edit: `src/main/kotlin/dev/sasha/clauderemarks/review/WaitingReview.kt` —
  `WaitingReviewService`: the `current()` mask, the new `start` parameter, `markSent`,
  `acknowledge`, `expireIfStale`, the `expiry` future, `Disposable`
- Create: `src/test/kotlin/dev/sasha/clauderemarks/review/WaitingReviewServiceTest.kt` —
  fixture-backed, because a project-level service needs a project

The service owns the state field and the lock. It does **not** touch the store and shows no balloon;
those are task 4's, in `review/SendReview.kt`. Keeping the store out of this file is what stops the
review lifecycle and the remark model from growing into each other.

```kotlin
/** What an acknowledgement did, so the endpoint can answer honestly. */
enum class AckOutcome { OK, NO_REVIEW, NOT_SENT }

/** What ended a review. STALE is the deadline; the other two come from the skill. */
enum class ReviewEnd { READ, ABANDONED, STALE }
```

Six changes to the service:

**`current()` masks a stale review:** `fun current(): WaitingReviewState? = state?.takeIf { !it.isStale() }`.
One comparison of two longs. **No lock, no IO** — rule 8 in
[section 8](#8-rules-that-must-hold-at-every-step). This single line is what disables the Send button
and hides the banner for a dead review, everywhere, at once.

**`start` takes `deadlineSeconds: Long`** and passes it through to `startOrConflict`, then schedules
the expiry for the accepted state. The signature becomes
`start(session: String, label: String, deadlineSeconds: Long, outputPath: Path? = null)`. **Give
`deadlineSeconds` no default value.** Six existing calls pass `outputPath` positionally as the third
argument, and without a default every one of them stops compiling and has to be looked at — which is
the point. A default of 1800 would let them all keep compiling while silently ignoring the deadline
the test meant to set. Schedule on every `Accepted`, including the retry branch:
`scheduleExpiry` cancels the previous future first, so re-scheduling the same deadline is harmless
and there is no branch to get wrong.

**`markSent(ids: List<String>)`**, `@Synchronized`: replaces the phase with `ReviewPhase.Sent(ids,
System.currentTimeMillis())` and calls `notifyPanel()`. It uses `state`, not `current()`: the send
already checked the review was live, and a review that went stale in the millisecond between must
still record what was written, so the abandoned message can name the count.

**`acknowledge(session: String, end: ReviewEnd): Pair<AckOutcome, WaitingReviewState?>`**,
`@Synchronized`. It returns the state it acted on, because the caller needs the ids and the count for
the store mutation and the balloon:

- nothing in `state`, or `state.sessionId != session` → `NO_REVIEW`, null, no change
- `end == READ` and the phase is `Waiting` → `NOT_SENT`, null, no change. A read acknowledgement for a
  file that was never written is a bug in one of the two sides, not a transient failure, so it must
  not silently clear a live review.
- otherwise → clear the state, cancel the expiry future, `notifyPanel()`, return `OK` with the state
  as it was

**`expireIfStale(): WaitingReviewState?`**, `@Synchronized`: returns null unless `state` exists and is
stale; otherwise clears it, cancels the future, notifies, and returns the state it removed. No
session argument. If a newer review has replaced the one the task was scheduled for, that newer one is
not stale and this does nothing — which is why the task needs no bookkeeping to identify itself.

**`Disposable`, and the future.** `@Volatile private var expiry: ScheduledFuture<*>? = null`.
`scheduleExpiry` cancels the old one, then:

```kotlin
expiry = AppExecutorUtil.getAppScheduledExecutorService().schedule(
    { if (!project.isDisposed) expireIfStale() },
    (accepted.deadlineAt - System.currentTimeMillis()).coerceAtLeast(0),
    TimeUnit.MILLISECONDS,
)
```

**In this task the scheduled body calls `expireIfStale()` and nothing else**, so task 3 stands on its
own and its tests do not depend on a file it has not created yet. Task 4 replaces that one call with
`expireStaleReview(project)`, which is the same transition plus the balloon. Do not try to write
task 4's function here.

`dispose()` cancels the future with `cancel(false)`, never `cancel(true)`: interrupting a task whose
whole body is a field swap buys nothing and can only surprise the shared pool. The task body's own
first line is `if (project.isDisposed) return`, because cancellation cannot catch a task already
handed to a thread. See [section 6](#6-still-to-verify).

- [ ] write the failing tests in `WaitingReviewServiceTest.kt`. `BasePlatformTestCase`, and it must
      clear `WaitingReviewService` in **both** `setUp` and `tearDown` — the light fixture project is
      shared across test classes, and `CLAUDE.md` records what that costs when a class forgets.
  - `a review past its deadline is not current` — `start(session, label, deadlineSeconds = 0, outputPath = tempDir)`,
    then assert `current()` is null. A deadline of zero is why the clamp lives at the endpoint and not
    here.
  - `marking sent moves the phase and keeps the review` — after `markSent(listOf("a", "b"))`,
    `current()` is not null and its phase is `ReviewPhase.Sent` with those two ids
  - `a read acknowledgement on a sent review clears it and reports OK` — assert the outcome is `OK`,
    the returned state carries the two ids, and `current()` is null afterwards
  - `a read acknowledgement on a waiting review changes nothing` — outcome `NOT_SENT`, `current()`
    still not null
  - `an acknowledgement for another session changes nothing` — outcome `NO_REVIEW`, `current()` still
    not null. Without the session check, an ack from a review the person cancelled an hour ago would
    clear the one running now.
  - `an abandoned acknowledgement clears a waiting review` — outcome `OK`, `current()` null
  - `expireIfStale removes a review past its deadline and nothing else` — with `deadlineSeconds = 0`
    it returns the state; started again with a long deadline it returns null and leaves the review
    alone
- [ ] run `./gradlew test --tests "dev.sasha.clauderemarks.review.WaitingReviewServiceTest"` — expect
      a compile failure
- [ ] implement
- [ ] the same command passes, and `./gradlew test --tests "dev.sasha.clauderemarks.review.*"` still
      passes — `SendReviewTest` and `ReviewEndpointSmokeTest` call `start`, so its new parameter
      reaches them
- [ ] **mutation:** drop the `takeIf { !it.isStale() }` from `current()` — `a review past its deadline
      is not current` must fail. Drop the session comparison in `acknowledge` — `an acknowledgement
      for another session changes nothing` must fail. Let a `READ` acknowledgement clear a `Waiting`
      review — `a read acknowledgement on a waiting review changes nothing` must fail. Restore all
      three.
- [ ] commit: `feat: the waiting review can be marked sent, acknowledged and expired` — stage exactly
      the two files above

### Task 4: The endpoint's second action

**Model:** sonnet

**Files:**
- Edit: `src/main/kotlin/dev/sasha/clauderemarks/review/ReviewRestService.kt` —
  `clampDeadlineSeconds`, `deadlineSeconds` on `StartRequest`, a new `AckRequest`, the path dispatch
  in `execute`, and the ack branch
- Edit: `src/main/kotlin/dev/sasha/clauderemarks/review/SendReview.kt` — `finishReview` and
  `expireStaleReview`: the store mutation and the balloon, on the EDT
- Edit: `src/test/kotlin/dev/sasha/clauderemarks/review/ReviewRequestTest.kt` — the pure class, for
  `clampDeadlineSeconds`
- Edit: `src/test/kotlin/dev/sasha/clauderemarks/review/ReviewEndpointSmokeTest.kt` — the ack through
  a real `EmbeddedChannel`, and the unknown action

**The path dispatch.** Copy the platform's own expression from `UploadLogsService`, quoted in
[section 2](#2-platform-facts-checked-against-the-real-artifacts):

```kotlin
val action = urlDecoder.path().split(getServiceName()).last().trimStart('/')
```

`getServiceName()` is `claude-remarks`, and `ReviewEndpointSmokeTest` already builds its decoder
from the request uri, so this expression is exercised by a real request in the tests and not only in
production. `start` keeps everything it does today. `ack` is new. Anything else — including the bare
`/api/claude-remarks` with no action — answers `bad-request` with a `detail` naming the action it did
not understand. **This is a real behaviour change worth a comment:** today any sub-path starts a
review, because `execute` never looks at the path.

**The clamp, pure and tested on its own:**

```kotlin
/**
 * The skill declares how long it will wait. The number arrives over HTTP, so it is bounded here, at
 * the edge, rather than deeper in. Absent means the 1800 seconds the skill's own documentation has
 * always used.
 */
internal fun clampDeadlineSeconds(seconds: Long?): Long =
    (seconds ?: 1800L).coerceIn(60L, 86_400L)
```

**`AckRequest(session, project, event)`**, all nullable, filled by Gson like `StartRequest`. Blank
session, blank project, or an `event` that is neither `read` nor `abandoned` answers `bad-request`.
Then: normalize the open projects the same way the start branch does — pull that mapping out into one
private helper so the two branches cannot drift — and `projectForPath`. No project match answers
`unknown-project` with the `open` list, exactly like `start`.

On a match, call **one** function and write its answer into the response:

```kotlin
// in review/SendReview.kt
fun finishReview(project: Project, session: String, end: ReviewEnd): AckOutcome
```

**Everything the acknowledgement causes lives in `SendReview.kt`, not here.** `finishReview` asks the
service for the transition, then does the consequences inside `invokeLater`: for `READ`,
`markRemarksSent(project, state.ids)` and the balloon "Claude Code read N remarks."; for `ABANDONED`
after a send, "Claude Code left without reading the N remarks you sent. They are still pending." and
**no store call at all**; for `ABANDONED` while still waiting, "Claude Code stopped waiting for your
remarks."

`expireStaleReview(project)` is the same tail for the scheduled task: `expireIfStale()`, and if it
removed something, the same message as `ABANDONED` for that phase.

**Rule 5 applies to `ReviewRestService.kt` and the trap is live.** Explain in that file *why* the
consequences live elsewhere, and name `review/SendReview.kt` as the place — but **do not write any of
the five symbols the guard greps for**, not even inside a comment. See
[section 8](#8-rules-that-must-hold-at-every-step).

- [ ] write the failing tests:
  - in `ReviewRequestTest.kt` (plain JUnit): `an absent deadline falls back to thirty minutes`
    (`clampDeadlineSeconds(null) == 1800`); `a deadline below the floor is raised`
    (`clampDeadlineSeconds(5) == 60`); `a deadline above the ceiling is capped`
    (`clampDeadlineSeconds(999_999_999) == 86_400`); `a sensible deadline is passed through`
    (`clampDeadlineSeconds(300) == 300`)
  - in `ReviewEndpointSmokeTest.kt` (fixture, real `EmbeddedChannel`): `an acknowledgement of a sent
    review answers ok` — `start` the service, `markSent(listOf(id))`, then POST to
    `/api/claude-remarks/ack` with `event: "read"`; assert the response body is **non-empty** and
    contains `"status"` and `"ok"`, and that the remark's status is now `SENT`. Non-empty matters:
    phase 6's first draft shipped a missing `writer.close()` and every response had an empty body.
  - in `ReviewEndpointSmokeTest.kt`: `an unknown action does not start a review` — POST to
    `/api/claude-remarks/frobnicate` with a valid start body; assert the body says `bad-request` and
    that `WaitingReviewService.current()` is still null. This is the only guard on the behaviour change
    above.
- [ ] run `./gradlew test --tests "dev.sasha.clauderemarks.review.ReviewRequestTest" --tests "dev.sasha.clauderemarks.review.ReviewEndpointSmokeTest"`
      — expect a compile failure
- [ ] implement
- [ ] the same command passes
- [ ] run guard 5 from [section 8](#8-rules-that-must-hold-at-every-step). If it is not empty, the
      cause is almost certainly a comment, not code — remove the symbol from the comment, do not widen
      the grep.
- [ ] **mutation:** make the dispatch treat every unrecognized action as `start` — `an unknown action
      does not start a review` must fail. Remove `writer.close()` from the ack branch — `an
      acknowledgement of a sent review answers ok` must fail on the empty body. Restore both.
- [ ] commit: `feat: the endpoint accepts a read or abandoned acknowledgement` — stage exactly the
      four files above

### Task 5: Send writes, the acknowledgement marks sent

**Model:** sonnet

**Files:**
- Edit: `src/main/kotlin/dev/sasha/clauderemarks/review/SendReview.kt` — the `finishOnUiThread` block
  in `sendToWaitingReview`, and the phase guard at its top
- Edit: `src/test/kotlin/dev/sasha/clauderemarks/review/SendReviewTest.kt` — the success-path
  assertion inverts; the failure-path test does not move

Three edits to `sendToWaitingReview`, and nothing else in the pipeline changes. `prepare`, the read
action, `coalesceBy`, the write and the failure path all stay exactly as they are.

**One: the top refuses a second send.**

```kotlin
val waiting = WaitingReviewService.getInstance(project).current() ?: return
if (waiting.phase is ReviewPhase.Sent) {
    notifyRemarks(project, "Already sent. Waiting for Claude Code to read them.")
    return
}
```

`current()`, not `state`, so a review past its deadline returns early exactly as an absent one does.

**Two: after a successful write, record instead of marking.** `markRemarksSent(project,
prepared.ids)` and `WaitingReviewService.getInstance(project).clear()` both go. In their place:
`WaitingReviewService.getInstance(project).markSent(prepared.ids)`.

**Three: the balloon stops claiming a delivery.**

```kotlin
notifyRemarks(
    project,
    "Wrote $count remark${if (count == 1) "" else "s"} for Claude Code. Waiting for it to read them.",
)
```

- [ ] change the tests first, and run them before touching the production file so they fail for the
      right reason:
  - `testSendingMarksTheRemarksSent` inverts: after a successful send the remarks are **still
    `PENDING`**. Rename it to say what it now claims, for example
    `testSendingMarksNothingUntilTheAgentAcknowledges`.
  - `testSendingClearsTheWaitingReview` inverts too, and it is easy to miss: the send now **keeps**
    the review and moves it to `ReviewPhase.Sent` carrying the sent ids. Rename it to something like
    `testSendingKeepsTheReviewAndRecordsWhatWasWritten`.
  - `testSendingWritesTheWholePromptToTheWaitingReviewsOutputPath`,
    `testAFailedWriteMarksNothingSentAndLeavesTheReviewWaiting` and
    `testSendingWithNothingPendingLeavesTheReviewWaitingAndWritesNoFile` do **not** change beyond the
    new `start` argument. The failure-path one still asserts nothing is marked sent on a failed write,
    which is still true for a different reason. Leave its name and body alone and say in the task
    report that you did.
  - new: `a second send while waiting for the acknowledgement is refused` — `markSent` first, then
    call `sendToWaitingReview` and assert the handoff file's content did not change
  - new: `a read acknowledgement after a send marks the remarks sent` — the whole path in one test:
    send, then `finishReview(project, session, ReviewEnd.READ)`, then assert the remarks are `SENT`
    and `current()` is null. This is the one test that covers the phase's headline claim end to end
    inside the plugin.
- [ ] run `./gradlew test --tests "dev.sasha.clauderemarks.review.SendReviewTest"` — expect failures
      in the success-path test and the two new ones
- [ ] implement
- [ ] the same command passes
- [ ] **mutation:** put `markRemarksSent(project, prepared.ids)` back into the send — `a successful
      send records the ids and marks nothing sent` must fail. Remove the phase guard at the top — `a
      second send while waiting for the acknowledgement is refused` must fail. Restore both.
- [ ] commit: `feat: a remark is marked sent when the agent says it read the file` — stage exactly
      the two files above

### Task 6: The banner and the buttons stop lying

**Model:** sonnet

**Files:**
- Edit: `src/main/kotlin/dev/sasha/clauderemarks/ui/RemarksToolWindowFactory.kt` — `updateBanner`,
  and the `enabled` lambda of the "Send to Claude Code" `ToolbarAction`
- Edit: `src/main/kotlin/dev/sasha/clauderemarks/review/SendReview.kt` — `SendReviewAction.update`
- Edit: `src/test/kotlin/dev/sasha/clauderemarks/ui/RemarksPanelTest.kt` — the banner tests

**`updateBanner` reads the phase:**

```kotlin
private fun updateBanner() {
    val waiting = WaitingReviewService.getInstance(project).current()
    if (waiting == null) {
        banner.isVisible = false
        return
    }
    banner.text = when (val phase = waiting.phase) {
        ReviewPhase.Waiting ->
            "Claude Code is waiting: " + StringUtil.escapeXmlEntities(waiting.label.take(120))
        is ReviewPhase.Sent ->
            "Sent ${phase.ids.size} remark${if (phase.ids.size == 1) "" else "s"}. " +
                "Waiting for Claude Code to read them."
    }
    banner.isVisible = true
}
```

**Keep the escaping exactly where it is, and nowhere else.** The label is caller-supplied text that
arrived over HTTP, and `EditorNotificationPanel.setText` feeds a `JLabel`, which renders a string
starting with `<html>` as markup. The Sent text contains no caller-supplied content — only a count —
so escaping it would be cargo cult. The KDoc already explains the label case; extend it with this
one sentence rather than rewriting it.

**Both Send controls require the Waiting phase.** In the toolbar's `enabled` lambda and in
`SendReviewAction.update`, the condition becomes: a current review whose phase is `ReviewPhase.Waiting`,
and at least one pending remark. `current()` already masks a stale review, so this is one added
comparison in each place, not a new mechanism.

- [ ] write the failing tests in `RemarksPanelTest.kt`, using the private `settle()` helper after
      anything that hops off the EDT and back, and clearing `WaitingReviewService` in both `setUp` and
      `tearDown` as that class already does:
  - `the banner names the label while the review is waiting` — the existing test, unchanged
  - `the banner says the remarks are waiting to be read after a send` — `start`, `markSent(listOf("a"))`,
    refresh, assert the banner text contains "Waiting for Claude Code to read"
  - `the banner is hidden for a review past its deadline` — `start(..., deadlineSeconds = 0)`, refresh,
    assert `banner.isVisible` is false. This is the test that proves the whole staleness backstop
    reaches the screen.
  - `the send button is disabled once the remarks are sent` — with a pending remark and a review in
    the Sent phase, assert the toolbar's Send action is disabled
- [ ] run `./gradlew test --tests "dev.sasha.clauderemarks.ui.RemarksPanelTest"` — expect failures
- [ ] implement
- [ ] the same command passes
- [ ] **mutation:** make `updateBanner` use the Waiting text for both phases — `the banner says the
      remarks are waiting to be read after a send` must fail. Drop the phase comparison from the
      toolbar's `enabled` lambda — `the send button is disabled once the remarks are sent` must fail.
      Restore both.
- [ ] commit: `feat: the banner says which of the three states the review is in` — stage exactly the
      three files above

### Task 7: The skill declares a deadline, acknowledges the read, and reports leaving

**Model:** sonnet

**Files:**
- Edit: `docs/skill/claude-remarks-review/SKILL.md` — step 3's body, step 6 in full, a new step for
  the acknowledgement, the "Same machine only" paragraph, and "What to say if something goes wrong"

No plugin code and no test. Verified by reading, and by the end-to-end hand check in
[section 12](#12-hand-checks-in-a-sandbox-ide).

**One number, declared once.** Replace the literal in step 6 with a variable set in step 3 and sent
in the same body:

```sh
deadline_seconds=1800
body=$(jq -n --arg session "$session" --arg label "$label" --arg project "$root" \
  --argjson files "$files_json" --argjson deadline "$deadline_seconds" \
  '{session:$session, label:$label, project:$project, files:$files, deadlineSeconds:$deadline}')
```

Say why in the file: the IDE stops showing "Claude Code is waiting" once this many seconds have
passed, so the two sides must use the same number, and the only way to guarantee that is to send it.
Note that the IDE clamps it to between 60 seconds and 24 hours, so a nonsense value is corrected
rather than obeyed.

**Step 6 becomes one shell block: wait, read, acknowledge.**

```sh
ack() {
  jq -n --arg session "$session" --arg project "$root" --arg event "$1" \
    '{session:$session, project:$project, event:$event}' \
  | curl -s -o /dev/null -X POST "http://127.0.0.1:$port/api/claude-remarks/ack" \
      -H "X-Claude-Remarks-Token: $token" -H "Content-Type: application/json" -d @-
}
trap 'ack abandoned' EXIT INT TERM

deadline=$(( $(date +%s) + deadline_seconds ))
while [ ! -e "$output" ]; do
  [ "$(date +%s)" -ge "$deadline" ] && { echo "timed out waiting for the IDE"; exit 1; }
  sleep 1
done
cat "$output"
trap - EXIT INT TERM
ack read
```

Five points the file has to explain, because each one is a decision somebody will otherwise undo:

- **The trap is set only after a `waiting` response.** Before that there is no review to abandon, and
  an acknowledgement for a review that does not exist just gets `no-review`.
- **The trap is cleared after `cat` succeeds and before `ack read`.** Once the content is read, the
  read is a fact — even if the acknowledgement request then fails. Clearing the trap first means a
  failing `ack read` leaves the IDE to its deadline, which keeps the remarks pending. The other order
  would tell the IDE the agent left after it had already read them.
- **`trap - EXIT INT TERM` restores the default; it does not run the handler.** Writing
  `trap "" EXIT` instead would also work but reads as "run nothing", which is easy to misread as
  "run the old thing".
- **The trap covers this command's shell, not the whole session.** Each step here runs as its own
  shell, so the trap catches a timeout inside this loop and an interrupt of this command. An agent
  process killed between steps sends nothing at all, and the IDE's own deadline is what covers that.
  Say this plainly; it is the honest limit of the mechanism.
- **The `status` rule still holds, for every new variable too.** In zsh `status` is read-only, and zsh
  runs the command substitution before refusing the assignment — so the request is sent, a review
  really starts, and the script dies believing nothing happened. Found on 2026-08-03 on the first real
  end-to-end run, fixed in `95cbd1a`. The new variables here are `deadline_seconds`, `deadline` and
  `ack_code` if you capture one; none of them collides. Do not rename any of them to `status`.

**What the acknowledgement answers, in one short list:** `ok`, `no-review` (nothing is waiting under
that session — the review was cancelled, expired, or already finished), `not-sent` (a read
acknowledgement for a review whose file was never written, which is a bug in one of the two sides),
`unknown-project`, `bad-request`. Report and stop in every case except `ok`. Do not retry more than
once: the IDE's own deadline already covers a lost acknowledgement.

**Two paragraphs that are now out of date.**

- The timeout advice at the end of the file said the remarks "are still pending in the IDE". That is
  now literally true instead of nearly true — they were never marked sent. Say so.
- The "Same machine only" paragraph says the remote case is "planned for a later phase". It is
  **phase 8** now. Same for the pointer to `docs/ideas.md`.

- [ ] edit `SKILL.md` as above
- [ ] read it back once as a person following it line by line, with no memory of this plan. Every
      variable must be set before it is used, and step 3's `deadline_seconds` must be in scope in
      step 6.
- [ ] `grep -n "status=" docs/skill/claude-remarks-review/SKILL.md` — must find nothing that assigns
      to a bare `status`
- [ ] commit: `docs: the skill declares its deadline and acknowledges the read` — stage exactly
      `docs/skill/claude-remarks-review/SKILL.md`

### Task 8: Verify the constraints and the whole suite

**Model:** sonnet

**Files:**
- Read only: everything phase 7 touched

- [ ] all five grep guards from [section 8](#8-rules-that-must-hold-at-every-step), each pasted with
      its output. All five empty.
- [ ] `./gradlew build` — compiles, runs the whole suite, assembles. Paste the tail.
- [ ] `./gradlew verifyPluginProjectConfiguration` — `plugin.xml` was not edited, so this should be
      clean; run it anyway because `build.gradle.kts` changes in task 9.
- [ ] `./gradlew verifyPlugin` — the report must still name **exactly one** internal-API usage,
      `SegmentedButton.getComponent()`. A second one is not free; if one appeared, find it and remove
      it rather than accepting it.
- [ ] `./gradlew test` once more on its own and report the test count next to task 1's number, so a
      test that quietly stopped being registered is visible.
- [ ] confirm by reading that no file under `src/main/kotlin/dev/sasha/clauderemarks/review/` calls
      `RemarkStore.getInstance` — guard 2's grep covers it, but say you looked
- [ ] no commit — this task writes nothing

### Task 9: Documentation, the phase renumbering, and the version

**Model:** sonnet

**Files:**
- Edit: `docs/claude/design.md` — a new subsection at the end of "The Shared Review Session"
- Edit: `CLAUDE.md` — the opening paragraphs, the `review/` lines of the project structure, and the
  testing section
- Edit: `README.md` — the review paragraph, and the "later phase" sentence
- Edit: `docs/ideas.md` — mark the phase 7 entry built, answer its two open questions, renumber
- Edit: `docs/plans/20260804-claude-remarks-phase6.md` — renumber only
- Edit: `docs/skill/claude-remarks-review/SKILL.md` — only if task 7 left a "later phase" behind
- Edit: `build.gradle.kts` — `version = "0.4.0"`

**The design doc gets one new subsection**, "Three signals that the remarks arrived", under "The
Shared Review Session". `docs/plans/` records how the work happened; the design doc is what the system
now is. Write it so a future session can load the design from `CLAUDE.md` instead of re-deriving it
from code. It must cover:

- the phase machine, and that the send no longer clears the review
- **why nothing is marked sent until the read acknowledgement**, and that this is why no function
  marks a remark pending again
- why the deadline comes from the skill and is clamped at the endpoint
- why the deadline is enforced twice — the pure predicate inside `current()` for every decision, the
  scheduled task for the repaint — and what each one alone would miss
- that `current()` masking a stale review is a comparison of two longs, and that this does **not**
  disturb the unsynchronized-`current()` decision already recorded above it
- the branch order in `startOrConflict`: same session before staleness, and why
- that the agent's read is reported, never detected: no portable signal exists for "this file was
  read"
- the sub-path dispatch, and that any unrecognized action now answers `bad-request` where it used to
  start a review

**`CLAUDE.md`.** The opening says phases 1-6 are implemented and that nothing has been loaded into a
running IDE — phase 6 *was* checked by hand in this run, so that sentence needs care rather than a
blind edit: say which phases are hand-checked and which are not. Add a "**Phase 7 is built.**"
paragraph in the same shape as phase 5's and phase 6's. Update the `review/` lines of the project
structure for the new functions. Add the new test classes to the testing section, in the right group:
`WaitingReviewServiceTest` needs a fixture, the `clampDeadlineSeconds` tests do not. Rule 5 does not
change — but say in the rule that phase 7 hit the same comment trap and it is still live.

**`README.md`.** The paragraph describing a review ends "the remarks turn gray, `markRemarksSent` runs
exactly as it does after a copy, and the banner disappears". That is now wrong in order: the banner
first says the remarks are waiting to be read, and the gray arrives with the acknowledgement. Rewrite
those two sentences and describe what the person sees when the agent never comes back.

**The renumbering. Phase 7 is this plan; the remote work is phase 8.** Find every occurrence, do not
trust a list:

```bash
grep -rn "phase 7\|Phase 7\|phase7" --include="*.md" --include="*.kt" --include="*.xml" . | grep -v '^./build'
grep -rn "later phase\|next phase" --include="*.md" . | grep -v '^./build'
```

Known ones, from a grep run while writing this plan:

- `docs/ideas.md`, "Sending remarks to a remote agent session": "Decided for phase 7", "phase 7 must
  not start from the opposite belief", "Three things phase 7 needs"
- `docs/ideas.md`, "Tell the IDE the remarks were actually delivered": "This matters more in phase 7,
  not less"
- `docs/plans/20260804-claude-remarks-phase6.md`, in "Known limits": four sentences naming phase 7
- `docs/skill/claude-remarks-review/SKILL.md`, "Same machine only": "planned for a later phase"
  (task 7 should already have fixed this — check)
- `README.md`: "Sending to a remote agent session is planned for a later phase and is not built"
- `docs/plans/completed/20260801-claude-remarks-phase1-2.md` mentions "later phases" generically, with
  no number. **Leave it alone.** Only change text that names a number, or that names *this* phase's
  work as belonging to a later one.

**In the phase 6 plan, change the number and nothing else.** It is a record of how that work
happened. Correcting "phase 7" to "phase 8" keeps the record true; rewriting the sentences around it
would not.

**In `docs/ideas.md`, the phase 7 entry gets a "Built in phase 7" line** and its two open questions
are answered in place, each in one or two sentences, pointing at the design doc for the reasoning.
Do not delete the questions: what was open and how it was settled is the useful part.

- [ ] write the design doc subsection
- [ ] update `CLAUDE.md`, `README.md`, `docs/ideas.md`
- [ ] run both greps above and fix every remaining occurrence, then paste the greps again showing
      only the ones you deliberately left
- [ ] bump `version = "0.4.0"` in `build.gradle.kts`
- [ ] `./gradlew verifyPluginProjectConfiguration` after the version change
- [ ] commit: `docs: record the three delivery signals, and renumber the remote work as phase 8` —
      stage exactly the files listed above

## 11. Known limits

**A skill that never acknowledges leaves the remarks pending until the deadline.** An old copy of the
skill installed by hand — a copy, not the development symlink — sends no acknowledgement, so its
review sits in the Sent phase until the deadline, then says the agent left and the remarks stay
pending. Nothing is lost and Copy All Pending still works, but the person has to send again. The
message names the cause, which is the best a plugin can do about a stale copy of a file in somebody's
home directory.

**The trap covers a shell, not a session.** Each step of the skill runs in its own shell, so the
abandoned acknowledgement fires for a timeout inside the wait loop and for an interrupt of that
command. An agent killed between two steps sends nothing. That is exactly why the deadline exists, and
it is the reason the design has three signals instead of one.

**The deadline task fires late after a suspend, and the predicate is what makes that harmless.** A
laptop asleep past the deadline wakes with the task firing immediately. During the sleep the review
was already not `current()`, so nothing could be sent to it and no button was enabled; only the banner
on a screen nobody was looking at was out of date.

**A same-session retry after the deadline leaks one empty temp directory.** `startOrConflict` hands
the same state back for the same session id, so this needs an unusual sequence: a review accepted, the
deadline passed, then a `start` from a *different* session, and the first review's directory is left
behind. It is an empty directory in the system temp directory. Phase 6 already accepts that the plugin
never deletes the handoff file, so this is the same family of leftover and the operating system's
cleanup is the answer.

**Cancel in the Sent phase throws away the handover, silently.** The file stays on disk, the remarks
stay pending, and no balloon appears — the person pressed Cancel, so nothing needs to be reported to
them. If an agent then reads the file and acknowledges, it gets `no-review` and reports that.

**Nothing tells the person a review was refused as a conflict.** Unchanged from phase 6: the skill is
told, the IDE is not. Out of scope here.

**One automated test reaches `execute`, and none reaches `process`.** The security rule, the method
filter and the rate limit are covered by the pure `requestIsAllowed` tests and by the hand checks
below. That is unchanged by adding a second action, because the second action is guarded by the same
`isHostTrusted`.

**The scheduling itself is not tested, only its body.** A test that waits for a scheduled task is a
test that sometimes fails on a loaded machine. `expireIfStale` and `expireStaleReview` are called
directly by the tests; that the future is actually scheduled and cancelled is covered by the hand
checks.

**This still only works when the IDE and the agent share a machine.** The remote case is **phase 8**
— `docs/ideas.md`, "Sending remarks to a remote agent session". Phase 7 helps it rather than blocking
it: over a tunnel, a local file existing proves even less about what happened on the other machine,
so the acknowledgement matters more there, and it needs no protocol change to work over one.

## 12. Hand checks in a sandbox IDE

None of these are automated. Run `./gradlew runIde` **by hand**, never from an agent session — it
starts a sandbox IDE that does not exit on its own.

```bash
HS=~/.claude-remarks/$(printf %s "$(git rev-parse --show-toplevel)" | shasum -a 256 | cut -c1-16).json
PORT=$(jq -r .port "$HS"); TOKEN=$(jq -r .token "$HS"); ROOT=$(git rev-parse --show-toplevel)
POST() { curl -s -X POST -H "X-Claude-Remarks-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d "$2" "http://127.0.0.1:$PORT/api/claude-remarks/$1"; }
```

- [ ] `POST start '{"session":"s1","label":"test","project":"'"$ROOT"'","deadlineSeconds":120}'`
      answers `"status": "waiting"` and the banner appears
- [ ] write two remarks, press Send to Claude Code. The balloon says **wrote**, not sent; the banner
      changes to "Sent 2 remarks. Waiting for Claude Code to read them."; the remarks are **still
      black**; the Send button is greyed out.
- [ ] press Send to Claude Code from **Tools →** while in that state — a balloon says it is already
      sent, and the handoff file's modification time does not change
- [ ] `POST ack '{"session":"s1","project":"'"$ROOT"'","event":"read"}'` answers `ok`; the remarks turn
      gray; the banner disappears; a balloon says "Claude Code read 2 remarks."
- [ ] repeat the same ack — it now answers `no-review` and nothing on screen changes
- [ ] start a review, send the remarks, then
      `POST ack '{"session":"s2","project":"'"$ROOT"'","event":"abandoned"}'` — the banner disappears,
      the balloon says the agent left without reading, and the remarks are **still black**
- [ ] start a review and abandon it **before** sending — the banner disappears and the balloon says
      Claude Code stopped waiting
- [ ] **the deadline, which no test covers:** start a review with `"deadlineSeconds":60`, wait past a
      minute without touching anything, and confirm the banner disappears **on its own** and a balloon
      appears. This is the only check that the scheduled task is really scheduled.
- [ ] within that minute, with the tool window open and the IDE focused, confirm the Send button greys
      out within about a second of the deadline passing and before any other interaction — that is
      `current()` masking the stale review on a toolbar tick. The tick needs both conditions: it is
      500 ms while the window is active, 5 s while it is not, and it is skipped entirely while the
      toolbar is not showing.
- [ ] after the deadline has passed, `POST start` with a **different** session id is accepted rather
      than answering `conflict`
- [ ] `POST ack '{"session":"s1","project":"'"$ROOT"'","event":"read"}'` for a review that was never
      sent answers `not-sent`, and the review is still on screen
- [ ] `POST ack '{"session":"s1","project":"/nope","event":"read"}'` answers `unknown-project`
- [ ] `POST ack '{"session":"s1","project":"'"$ROOT"'","event":"nonsense"}'` answers `bad-request`
- [ ] `POST frobnicate '{"session":"s1","label":"x","project":"'"$ROOT"'"}'` answers `bad-request` and
      **no review starts** — the behaviour change in task 4
- [ ] the ack with a wrong token returns 403 and **no dialog appears in the IDE** — the second action
      inherits the whole security rule
- [ ] a `GET` to `/api/claude-remarks/ack` returns 404, not 405
- [ ] close the project while a review is waiting, and confirm the IDE log holds no exception from the
      scheduled task
- [ ] with no skill and nothing listening, confirm Copy All Pending still works exactly as before
- [ ] install the updated skill and run one real review end to end. Then run one where you never press
      Send and let the skill time out, and confirm the IDE says the agent left and the remarks are
      still pending.
