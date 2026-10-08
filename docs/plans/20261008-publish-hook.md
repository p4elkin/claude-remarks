# Publish hook for the agterm live review — plan

Spec: `/Users/sasha/dev/oss/agterm-vim/docs/plans/20261008-ide-live-review-spec.md`, section
"claude-remarks: the publish hook". This plan is the claude-remarks half only. The agterm-vim overlay and
the agterm-agents launcher and flush are separate plans.

## Contents

- [Goal](#goal)
- [Contract](#contract)
- [Tasks](#tasks)
- [Consumers](#consumers)
- [Out of scope](#out-of-scope)

## Goal

When a live review owns this project, every publish also hands its exact bytes to the review's flush
command. Two store rules change too: a real text edit of a READ remark moves it back to PENDING, and an
acknowledgement never marks READ a remark whose text changed after its batch was recorded.

## Contract

- Hook file: `~/.claude-remarks/<hash>.hook.json`, named by a new `hookName(realPath)` beside
  `publishedName` in `review/PublishedRemarks.kt` (`projectHash(realPath) + ".hook.json"`).
  Shape: `{"argv": [...], "label": "...", "owner": "...", "state": "opening|active", "port": <int>}`.
  The plugin reads `argv`, `label` and `port`. `owner` and `state` belong to agterm-agents and are ignored.
  A missing or empty `label` reads as `live review`.
- A hook is **used** only when all hold; otherwise the publish goes on as today and nothing is said:
  - the file exists, parses (Gson), `argv` is a non-empty list of strings whose first element is an
    absolute path;
  - the file is owned by the current user and is not writable by group or others;
  - `port` equals this IDE's built-in server port (`BuiltInServerManager.getInstance().port`), so a second
    IDE on the same checkout never feeds a review it does not host. `.port` is the bound port at publish
    time because `ReviewHandshakeService.start()` already waited for the server (`ReviewHandshake.kt`).
- A hook file that exists but fails a check is logged at `warn` with the reason.
- The bytes: `(header + "\n" + markdown).toByteArray(UTF_8)`, the same string `writePublished` writes
  (UTF-8, `AtomicWrite.kt`), built once before the write. The published file's path is never passed.
- The run: argv through `ProcessBuilder`, never a shell, on **one serial executor**
  (`AppExecutorUtil.createBoundedApplicationPoolExecutor("Claude Remarks publish hook", 1)`), so hooks
  run in publish order. stdin is written on its own thread and closed; a broken pipe is ignored and the
  outcome is decided by the exit code. stdout and stderr are drained on their own threads. The 10 s
  timeout starts at `start()`. On timeout the descendants are destroyed forcibly, then the process.
  After the exit, the drain threads are joined for at most 1 s and whatever stderr arrived is used.
- ⚠️ The hook must detach its own long work from the inherited stdio. agterm-agents' flush spawns its
  worker with stdio on `worker.log` (`spawn_worker`), which satisfies this; the agterm-agents plan
  keeps it so.
- Outcome balloon: exit 0 → "Queued for <label>"; exit 3 → "The live review is closed";
  timeout → "The live review hook timed out"; any other exit or a start failure → a warning balloon with
  the exit code and the last 5 lines of stderr. The publish result (clipboard, published file,
  `PUBLISHED`) is the same in every case.
- No hook runs when the published file was not written (`writeFailure != null`).
- Edit rule: `RemarksState.editRemark` in `store/RemarkStore.kt` changes nothing when the new text
  equals the old. On a real change it increments a new persisted `RemarkState.revision` and, when the
  status is `READ`, sets `PENDING` and `readAt = 0`.
- Ack rule: `prepare` takes each remark's `revision` from the same rows that rendered the markdown and
  puts the map in `Prepared`. `record(ids, revisions: Map<String, Int>)`, with **no default** so the compiler finds every caller, stores it on
  `PublishedBatch` beside `ids`, which stays (`batchCarries` and `AnswerReceipt` read it).
  `reportPublishedRead` passes the map to `markRemarksRead(project, ids, revisions = emptyMap())`, and
  `markRead` skips an id present in the map whose current revision differs. The `markRemarksRead` default keeps its
  existing test callers compiling; the `record` test callers (about 28, in `PublishedAckTest`,
  `AnswerReceiptTest`, `ReviewEndpointSmokeTest`) pass `emptyMap()` explicitly. A skipped remark stays `PENDING` or `PUBLISHED`, so the next Publish Unread
  sends it. `markRemarksRead` returns the count `markRead` returns, and the balloon says that count;
  a count of 0 shows no balloon.

## Tasks

Test first in every task: write the failing tests, then the code.

### Task 1: hook file validation

- [ ] Tests in `review/PublishHookTest.kt` against temp files: absent → `Skip` with no reason; malformed
  JSON; empty argv; relative first element; group-writable; others-writable; another owner (pass a
  `currentUser` that is not the file's owner); wrong port; missing label → `live review`; valid → `Use`.
- [ ] New `review/PublishHook.kt`, free of `com.intellij`, with `hookName` (in `PublishedRemarks.kt`) and a
  pure `readHook(file: Path, expectedPort: Int, currentUser: String): HookDecision`
  (`Use(argv, label)` or `Skip(reason?)`). Owner and mode through `java.nio` POSIX attributes.

Check: `./gradlew test --tests 'dev.sasha.clauderemarks.review.PublishHookTest'`

### Task 2: hook runner

- [ ] Tests in `review/PublishHookTest.kt` with small `/bin/sh` scripts in a temp directory:
  - stdin arrives byte for byte: UTF-8 with non-ASCII, and 200 KB;
  - exit 0 → `Queued`, 3 → `Closed`, 7 → `Failed(7, tail)`, the tail being the last 5 stderr lines;
  - `exit 3` without reading 200 KB of stdin → `Closed`;
  - `sleep 30` with 200 KB on stdin and a 1 s timeout → `TimedOut` within 3 s;
  - `sleep 30 &` then `exit 0` → `Queued` within 3 s;
  - a missing executable → `NotStarted`;
  - `outcomeMessage` for each outcome.
- [ ] `runHook(argv: List<String>, input: ByteArray, timeout: Duration): HookOutcome` and
  `outcomeMessage(outcome, label): Pair<String, NotificationType>` in `review/PublishHook.kt`, as the
  Contract says.

depends: 1
Check: `./gradlew test --tests 'dev.sasha.clauderemarks.review.PublishHookTest'`

### Task 3: run the hook from publish

- [ ] Tests in `action/PublishRemarksTest.kt` against a new seam,
  `startPublishHook(root: Path, published: ByteArray, port: Int, dir: Path, run: (Runnable) -> Unit, onOutcome: (HookOutcome, String) -> Unit): HookDecision`
  (`onOutcome` gets the outcome and the label, on the executor's thread):
  - create the fixture's `basePath` first, the way `identity()` in `ReviewEndpointSmokeTest.kt` does,
    so `projectIdentity` resolves;
  - two calls with different bytes, the published file overwritten between them, each deliver their own
    bytes, in call order (a hook script that appends stdin to a file);
  - a hook for another port returns `Skip` and runs nothing;
  - `tearDown` deletes the hook and published files, as `deletePublishedFile()` in
    `ReviewEndpointSmokeTest.kt` does, because `TEST_HANDSHAKE_DIR` lives for the whole test JVM.
- [ ] In `publishRemarks` (`action/PublishRemarks.kt`): build the bytes before `writePublished`; inside
  the `writeFailure == null` path call `startPublishHook` with the serial executor; `onOutcome` checks
  `project.isDisposed`, then posts `outcomeMessage` through `notifyRemarks`; a `Skip` with a reason is `LOG.warn`. "A failed write runs no hook" holds by
  the call's position; it is a code-reading fact, not a test.
- [ ] Update the KDoc of `publishRemarks` and of `PublishRemarksTest` that say the pipeline has no
  automated coverage: the hook step now has it through the seam.
- [ ] The rule 3 grep in `CLAUDE.md` ("Rules that must not break") still prints nothing.

depends: 2
Check: `./gradlew test --tests 'dev.sasha.clauderemarks.action.PublishRemarksTest' --tests 'dev.sasha.clauderemarks.review.PublishHookTest'`

### Task 4: a real edit reopens a READ remark; a late ack skips it

- [ ] Tests:
  - `store/RemarkStoreStateTest.kt` (plain `RemarkStore()`): real edit of READ → PENDING, `readAt` 0,
    revision +1; unchanged edit of READ → still READ, revision unchanged; edit of PENDING and PUBLISHED →
    status unchanged, revision +1; `markRead` with a stale revision skips that id and marks the others.
  - `store/RemarkEditsTest.kt`: extend `testEditingARemarkPublishes` so an unchanged edit sends no
    change notification.
  - `review/PublishedAckTest.kt`: record a batch, edit one remark, acknowledge → that remark is not READ,
    the others are.
  - `action/PublishRemarksTest.kt`: an edited READ remark is picked by `prepare(project, null)`, an
    answered one included.
  - `store/RemarkStoreStateTest.kt` also: `revision` set to a non-default value in the `remark()` helper
    and in "every field survives a write and read cycle" and "a snapshot carries every field"; a remark
    stored before `revision` existed loads at 0.
  - `review/PublishedAckTest.kt` also: the ack count is the number actually marked READ.
  - `ui/RemarksTreeTest.kt`: a READ remark edited **through `RemarksState.editRemark`** with no answer
    lands in Open; an answered one stays under Done (Done is "READ or has an answer", `RemarksTree.kt`).
- [ ] `Prepared.revisions: Map<String, Int>`, filled in `prepare` from the same `rows` that render the
  markdown; `publishRemarks` calls `record(prepared.ids, prepared.revisions)`. Test in
  `action/PublishRemarksTest.kt`: `prepare(project, null).revisions` maps each returned id to its current
  revision.
- [ ] `RemarksState.editRemark` returns whether the text changed (it returned "found"); `editRemark` in
  `RemarkEdits.kt` notifies only then.
- [ ] `RemarkState.revision` (persisted, default 0); the edit rule in `RemarksState.editRemark`, inside
  its `@Synchronized` body, with `incrementModificationCount()` still last; `PublishedBatch` carries the id→revision map from `record`; `markRead` takes it.
  No new function in `RemarkEdits.kt`, so rule 3's count of twelve stays.
- [ ] Rewrite the KDocs the `readAt` reset and the new property make false: `markRead` in
  `store/RemarkStore.kt` ("the first time and only the first time"), `markRemarksRead` in
  `store/RemarkEdits.kt` ("the only place that stamps it"), `readAt` in `model/RemarkState.kt` ("exactly
  one writer"), the readAt test KDoc in `RemarkStoreStateTest.kt`, and the stored-property count in
  `store/RemarkStore.kt` ("sixteen stored properties" → seventeen), the `editRemark` KDoc in
  `store/RemarkStore.kt` ("two fields rather than one", "One write"), and the `Prepared` KDoc in
  `action/PublishRemarks.kt` (no test drives the pipeline; it now carries `revisions`).

depends: 3
Check: `./gradlew test --tests 'dev.sasha.clauderemarks.store.RemarkStoreStateTest' --tests 'dev.sasha.clauderemarks.store.RemarkEditsTest' --tests 'dev.sasha.clauderemarks.review.PublishedAckTest' --tests 'dev.sasha.clauderemarks.action.PublishRemarksTest' --tests 'dev.sasha.clauderemarks.ui.RemarksTreeTest'`

### Task 5: docs and version

- [ ] `CLAUDE.md`: "The three states" gets the edit and ack rules; every place that says `readAt` has a
  single writer is corrected (grep `readAt` in `CLAUDE.md`); "Publishing" gets one paragraph on
  the hook file and its owner, the agterm live review; the project-structure list gets `PublishHook.kt`;
  the Testing list gets `PublishHookTest` and the new `RemarkStoreStateTest`, `PublishedAckTest` and
  `PublishRemarksTest` cases.
- [ ] `docs/claude/design.md`: a section for the hook, and the edit and ack rules.
- [ ] `docs/claude/hand-checks.md`: an unchecked item, "a publish inside agterm's embedded Rebased runs
  the hook and the room receives the batch".
- [ ] Bundled skill `src/main/resources/dev/sasha/clauderemarks/skill/SKILL.md`: one short section — when
  `~/.claude-remarks/<hash>.hook.json` exists, an agterm live review acknowledges this project's batches;
  do not start `watch-remarks.sh` for it; answer through `agterm-review-flush <run> --answer`. If the skill
  says a READ remark is never sent again, add the edit rule.
- [ ] Version: `version` in `build.gradle.kts` and the version on `CLAUDE.md`'s line 3 (`0.12.1` →
  `0.13.0`), so `BundledSkillVersion` sees the skill change.
- [ ] `CHANGELOG.md`: an Unreleased entry for the hook and the two rules.

depends: 3, 4
Check: `./gradlew test && ./gradlew verifyPluginProjectConfiguration`

## Consumers

| New thing | Consumers | Count |
|---|---|---|
| `<hash>.hook.json` | `readHook` here; agterm-agents launcher and flush (other plan) | 1 here |
| `hookName` | `startPublishHook`, `PublishHookTest`, `PublishRemarksTest` | 3 |
| `HookDecision`, `HookOutcome` | `startPublishHook`, `publishRemarks`, `outcomeMessage`, `PublishHookTest`, `PublishRemarksTest` | 5 |
| `RemarkState.revision` | `RemarksState.editRemark`, `prepare` (into `Prepared`), `markRead`, `snapshot()` | 4 |
| `Prepared.revisions` / `PublishedBatch` revision map | `publishRemarks` (passes to `record`), `record`, `acknowledge`, `reportPublishedRead`, `markRemarksRead`, `markRead` | 6 |
| `markRemarksRead` count | `reportPublishedRead` balloon | 1 |
| edit resets READ → PENDING | `prepare` (`PublishRemarks.kt`), `PublishUnreadRemarksAction.update`, tool window Publish Unread enablement and `handedOverCount` (`RemarksToolWindowFactory.kt`), `clearHandedOverRemarks` / `removeHandedOver`, the Done split and `processedAt` (`RemarksTree.kt`), icon and grey text (`RemarkStatusLook.kt`), gutter tooltip "(read)" (`RemarkGutterIcon.kt`), `openQuestionIds` (`AskClaudeAction.kt`) | 10 |

One visible change follows: Clear Handed Over no longer removes an edited READ remark, because it is
PENDING again.

## Out of scope

- How agterm-agents writes, claims and deletes the hook file.
- Any change to the published file's format, the endpoint or the watcher.
- Installing this plugin into agterm's embedded Rebased (a manual prerequisite).

<!-- plan-review: planning:plan-review 2026-10-08 findings=27 resolved -->
