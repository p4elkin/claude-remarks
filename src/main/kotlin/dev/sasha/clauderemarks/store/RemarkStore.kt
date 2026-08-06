package dev.sasha.clauderemarks.store

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponentWithModificationTracker
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.XCollection
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus

/**
 * Holds every remark for one project.
 *
 * Stored in .idea/workspace.xml, which the IDE's generated .idea/.gitignore excludes, so
 * remarks never reach version control. RoamingType.DISABLED keeps them off Settings Sync,
 * where the project-relative paths would not resolve on another machine.
 *
 * This implements PersistentStateComponent by hand instead of extending
 * SimplePersistentStateComponent, for one reason: that base class declares
 * `public final T getState()` and hands the platform's serializer the live state object. The
 * serializer runs off the EDT and never takes this plugin's lock, so a workspace save landing
 * while the editor action is adding a remark could throw ConcurrentModificationException and lose
 * that save. The interface method is not final, so getState() below can return a copy instead.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ClaudeRemarks",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED)],
)
// The nested state class is called RemarksState, not State, on purpose: this file already
// imports the @State annotation, and a nested classifier with the same name is asking for a
// resolution clash at the annotation site.
class RemarkStore : PersistentStateComponentWithModificationTracker<RemarkStore.RemarksState> {

    // Volatile rather than lock-guarded, which is exactly what SimplePersistentStateComponent did
    // with its own `private volatile T state` field. loadState swaps the whole object; the lock
    // that keeps the list intact lives on the state object itself.
    @Volatile
    private var liveState = RemarksState()

    class RemarksState : BaseState() {
        // @get:XCollection is NOT optional and NOT cosmetic. Without it this list serializes
        // to an empty <RemarksState /> and every remark is silently lost on restart, with no
        // error logged anywhere. Verified by running it against the 2025.2 jars: a plain
        // `val x by list<T>()` emits nothing at all.
        @get:XCollection(style = XCollection.Style.v2)
        val remarks by list<RemarkState>()

        // The second list, and the annotation above it is needed here for exactly the same reason
        // it is needed on `remarks`: without it this list serializes to nothing at all and every
        // answer is lost on restart, silently. AnswerStateTest reads the written XML for an answer's
        // own values rather than only round-tripping, because a round trip through a list that
        // serializes empty is consistent with itself and passes.
        //
        // Answers live beside remarks in one state object rather than in a second @State component:
        // a second component would be a second copy of the @Synchronized mutators, the deep
        // snapshot, the hand-written getState() and the modification-count override below — four
        // more chances to get one of them wrong, for data that is read together, written from the
        // same places and saved into the same file.
        @get:XCollection(style = XCollection.Style.v2)
        val answers by list<AnswerState>()

        // The mutators live here, not on RemarkStore: BaseState.incrementModificationCount() is
        // protected, so only a BaseState subclass can reach it. @Synchronized locks this state
        // object (every method below locks the same monitor, `this` RemarksState instance), and
        // every reader and writer of the list goes through one of them: the tool window snapshots
        // from a pooled thread, the editor action adds on the EDT, RemarkStore.getState() takes its
        // copy for the platform's serializer through snapshot(), and RemarkStore.getStateModificationCount()
        // reads the count through modCount().
        @Synchronized
        fun addRemark(remark: RemarkState) {
            remarks.add(remark)
            incrementModificationCount()
        }

        @Synchronized
        fun removeRemark(id: String): Boolean {
            val removed = remarks.removeIf { it.id == id }
            if (removed) incrementModificationCount()
            return removed
        }

        /**
         * Changes a remark's text in place, under the same lock every other mutator holds.
         *
         * In place, not replace-with-a-copy. Until phase 11 took the tag off a remark this wrote
         * two fields rather than one, and the ORDER below is what made that safe on disk:
         * incrementModificationCount() runs after the write, so a save that landed in between
         * recorded the lower count it read on the way in, and the next save saw a higher count and
         * wrote the field again. Keep that order. One write is not a reason to reverse it — the
         * next field added here would silently lose the argument.
         *
         * The other reader is the prompt and the tool window, on a pooled thread. Those walk the
         * fields long after leaving this lock, so the ordering argument says nothing about them.
         * What covers them is snapshot() being a deep copy; see snapshot() for why.
         */
        @Synchronized
        fun editRemark(id: String, text: String): Boolean {
            val target = remarks.firstOrNull { it.id == id } ?: return false
            target.text = text
            incrementModificationCount()
            return true
        }

        /** Returns how many actually changed, so publishing an already-published remark is a
         *  no-op. A READ remark moves back to PUBLISHED too: Publish Selected exists exactly to
         *  re-publish something already handed over. */
        @Synchronized
        fun markPublished(ids: Set<String>): Int {
            val changed = remarks.filter { it.id in ids && it.status != RemarkStatus.PUBLISHED }
            changed.forEach { it.status = RemarkStatus.PUBLISHED }
            if (changed.isNotEmpty()) incrementModificationCount()
            return changed.size
        }

        /** Returns how many actually changed, the same shape as markPublished. Only an agent's own
         *  acknowledgement produces READ — either of the two routes, never a publish; see
         *  CLAUDE.md's guard 6 on this. */
        @Synchronized
        fun markRead(ids: Set<String>): Int {
            val changed = remarks.filter { it.id in ids && it.status != RemarkStatus.READ }
            changed.forEach { it.status = RemarkStatus.READ }
            if (changed.isNotEmpty()) incrementModificationCount()
            return changed.size
        }

        /**
         * Marks remarks as asking for an answer, or stops them asking. Returns how many actually
         * changed, the same shape [markPublished] and [markRead] use, so a toggle that changes
         * nothing publishes nothing.
         */
        @Synchronized
        fun setAsksForAnswer(ids: Set<String>, asksForAnswer: Boolean): Int {
            val changed = remarks.filter { it.id in ids && it.asksForAnswer != asksForAnswer }
            changed.forEach { it.asksForAnswer = asksForAnswer }
            if (changed.isNotEmpty()) incrementModificationCount()
            return changed.size
        }

        /** Removes everything that is not PENDING — PUBLISHED and READ alike. Returns how many
         *  were removed. */
        @Synchronized
        fun removeHandedOver(): Int {
            val before = remarks.size
            remarks.removeIf { it.status != RemarkStatus.PENDING }
            val removed = before - remarks.size
            if (removed > 0) incrementModificationCount()
            return removed
        }

        /**
         * Removes every remark and every answer, and returns how many records went in all.
         *
         * Both lists, because Clear All is the person saying "take everything the tool window
         * shows", and an answer left behind would be a row whose question is gone. Clear Handed Over
         * is the one that keeps answers: it goes through [removeHandedOver], which this does not
         * touch, because an answer was never handed anywhere.
         *
         * The count is remarks plus answers rather than remarks alone, so a project holding only
         * answers still reports something removed. [clearAllRemarks] returns this straight to its
         * caller and skips its change notification when it is zero, so a remarks-only count would
         * leave the tree showing answers that are already gone.
         */
        @Synchronized
        fun clear(): Int {
            val removed = remarks.size + answers.size
            if (removed > 0) {
                remarks.clear()
                answers.clear()
                incrementModificationCount()
            }
            return removed
        }

        /**
         * Stores an answer, replacing any answer already held for the same remark.
         *
         * An upsert keyed on [AnswerState.remarkId], not a plain add, so a question answered twice
         * ends with one answer carrying the second body and its own fresh anchor. Enforced here
         * rather than left to the answering session: every publish mints a fresh nonce and a watcher
         * compares nonces rather than content, so the same question reaching a session twice is the
         * ordinary case, and a rule written in a skill's markdown is advice a model may or may not
         * follow.
         *
         * A replaced answer is not archived, which matches [editRemark] overwriting a remark's text
         * with no archive either. The clear-then-archive rule covers a deletion the person asked for.
         *
         * **An older answer never replaces a newer one.** Two `answer` requests for the same remark,
         * arriving close together, are two asynchronous pipelines in flight at once
         * (`review/AnswerReceipt.kt`), and without this line the one that happened to finish last
         * would win — so a stale body could overwrite the fresh one with nothing to show it had
         * happened, and the person would read the wrong answer. `answeredAt` is the arrival stamp,
         * taken when the request was accepted rather than when its answer was built, so comparing it
         * here restores request order. It is strictly increasing per request, so two answers can
         * never carry the same stamp and the comparison never has a tie to settle by luck.
         *
         * The comparison sits inside this method, under the same lock as the removal, because the
         * two together have to be one step: read the stored stamp outside, and the answer being
         * compared against can be replaced before this write lands.
         *
         * Equal stamps still replace. That case cannot come from the endpoint at all, and treating
         * it as a refusal would mean two records written by hand with the same stamp — a test, or a
         * future caller with its own stamping scheme — silently doing nothing.
         */
        @Synchronized
        fun putAnswer(answer: AnswerState) {
            val stored = answers.firstOrNull { it.remarkId == answer.remarkId }
            if (stored != null && stored.answeredAt > answer.answeredAt) return
            answers.removeIf { it.remarkId == answer.remarkId }
            answers.add(answer)
            incrementModificationCount()
        }

        /** Removes one answer by its own id, not by the remark's. Returns whether anything went. */
        @Synchronized
        fun removeAnswer(id: String): Boolean {
            val removed = answers.removeIf { it.id == id }
            if (removed) incrementModificationCount()
            return removed
        }

        /**
         * A copy of the list AND of every remark in it, taken under the lock the mutators hold, so
         * a reader never touches an object anything can still change.
         *
         * Deep, not shallow, and that is the whole point. `editRemark` and `markPublished` write fields
         * on a remark that is already in the list. A shallow copy hands those same objects to
         * readers running on other threads: `resolveAll` and `collectForPrompt` walk them inside a
         * non-blocking read action on a pooled thread, for as long as a copy of the whole project
         * takes. A remark a reader is holding must not change under it: with a shallow copy the
         * reader holds the live object, so the same field read twice can come back with two answers
         * and a prompt gets rendered from a remark that was never in one piece. The lock on the
         * mutators did not stop that, because the reader had already left the lock and was reading
         * fields outside it.
         *
         * `BaseState.copyFrom` walks the property list every `BaseState` registers for itself, so a
         * field added to `RemarkState` later is copied without touching this line. That was the one
         * argument recorded against a deep copy: cloning field by field, and a forgotten field then
         * dropping out of workspace.xml with no error. `copyFrom` has no such failure mode, and
         * "a snapshot carries every field a remark is stored with" in RemarkStoreStateTest pins it.
         *
         * The cost is one small object per remark per call, and snapshot() runs on every resolve. A
         * RemarkState is sixteen stored properties, so a project with a hundred remarks pays tens of
         * microseconds — against a resolve that SHA-256s candidate positions and splits whole
         * documents into lines.
         */
        @Synchronized
        fun snapshot(): List<RemarkState> =
            remarks.map { live -> RemarkState().also { it.copyFrom(live) } }

        /**
         * The answers half of [snapshot], deep for the same reason and with the same argument behind
         * it: the tree, the gutter and the resolver all walk answers on a pooled thread long after
         * they have left this lock, so a shallow `answers.toList()` would hand them objects
         * [putAnswer] can still replace under them.
         */
        @Synchronized
        fun answersSnapshot(): List<AnswerState> =
            answers.map { live -> AnswerState().also { it.copyFrom(live) } }

        /**
         * BaseState.getModificationCount() sums property.getModificationCount() over the stored
         * properties, and for `remarks` that is ListStoredProperty.getModificationCount(), which
         * iterates the live list with a for-each and takes no lock of its own. Reading
         * `modificationCount` directly off the live state (as the override below used to) let the
         * platform's save pass iterate the list on one thread while addRemark mutated it on
         * another, throwing ConcurrentModificationException. Locking here, on the same monitor the
         * mutators use, serializes that iteration against every add and remove.
         */
        @Synchronized
        fun modCount(): Long = modificationCount
    }

    fun all(): List<RemarkState> = liveState.snapshot()

    fun add(remark: RemarkState) {
        liveState.addRemark(remark)
    }

    fun remove(id: String): Boolean = liveState.removeRemark(id)

    fun edit(id: String, text: String): Boolean = liveState.editRemark(id, text)

    fun markPublished(ids: Set<String>): Int = liveState.markPublished(ids)

    fun markRead(ids: Set<String>): Int = liveState.markRead(ids)

    fun setAsksForAnswer(ids: Set<String>, asksForAnswer: Boolean): Int =
        liveState.setAsksForAnswer(ids, asksForAnswer)

    fun removeHandedOver(): Int = liveState.removeHandedOver()

    fun clear(): Int = liveState.clear()

    /** The read-only accessor the tree, the gutter and the resolver all read answers through, the
     *  same shape [all] is for remarks: a deep copy, so no reader holds a live object. */
    fun allAnswers(): List<AnswerState> = liveState.answersSnapshot()

    fun putAnswer(answer: AnswerState) {
        liveState.putAnswer(answer)
    }

    fun removeAnswer(id: String): Boolean = liveState.removeAnswer(id)

    /**
     * A fresh state carrying a copy of both lists, taken under the same lock the mutators hold, so
     * the serializer never iterates a list anyone can mutate.
     *
     * The copy goes all the way down: snapshot() and answersSnapshot() copy each element too, so the
     * serializer shares no object with the live state at all. See snapshot() for why that is not the
     * hand-cloning it once looked like.
     *
     * Both lists are copied here by hand, which means a third list added later is a third line to
     * write and a third line to forget. Forgetting one loses every record in it on the next save with
     * nothing logged — the same silent loss a missing @get:XCollection causes, and indistinguishable
     * from it. RemarkStoreStateTest pins each list separately for that reason.
     */
    override fun getState(): RemarksState = RemarksState().also {
        it.remarks.addAll(liveState.snapshot())
        it.answers.addAll(liveState.answersSnapshot())
    }

    override fun loadState(state: RemarksState) {
        liveState = state
    }

    /**
     * Kept because SimplePersistentStateComponent implemented it too, and dropping it would change
     * when the platform saves: with a modification tracker the platform compares this count against
     * the one it last saw and skips the component entirely when nothing changed, so getState() is
     * not called at all on an idle save. Goes through modCount(), not the live `modificationCount`
     * property directly, so this read takes the same lock addRemark and removeRemark do. Without
     * that, the platform's save pass (which calls this off the EDT) could iterate the live list
     * while the editor action was adding to it on the EDT, throwing ConcurrentModificationException.
     */
    override fun getStateModificationCount(): Long = liveState.modCount()

    companion object {
        fun getInstance(project: Project): RemarkStore = project.service()
    }
}
