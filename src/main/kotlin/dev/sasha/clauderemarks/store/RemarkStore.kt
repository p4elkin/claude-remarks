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
import dev.sasha.clauderemarks.model.RemarkSeverity
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

        /** Returns how many actually changed, so re-applying the level a remark already has is a
         *  no-op — the same shape as markSent, for the same reason. */
        @Synchronized
        fun setSeverity(ids: Set<String>, severity: RemarkSeverity): Int {
            val changed = remarks.filter { it.id in ids && it.severity != severity }
            changed.forEach { it.severity = severity }
            if (changed.isNotEmpty()) incrementModificationCount()
            return changed.size
        }

        /** [bucket] null takes a remark out of every bucket. Returns how many actually changed. */
        @Synchronized
        fun setBucket(ids: Set<String>, bucket: String?): Int {
            val changed = remarks.filter { it.id in ids && it.bucket != bucket }
            changed.forEach { it.bucket = bucket }
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

        /** Returns how many were removed. */
        @Synchronized
        fun clear(): Int {
            val removed = remarks.size
            if (removed > 0) {
                remarks.clear()
                incrementModificationCount()
            }
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
         * takes. An edit landing in that window is seen half-applied — `editRemark` writes `text`
         * and then `tag`, so a prompt could be rendered with the new text under the old tag. The
         * lock on the mutators did not stop that, because the reader had already left the lock and
         * was reading fields outside it.
         *
         * `BaseState.copyFrom` walks the property list every `BaseState` registers for itself, so a
         * field added to `RemarkState` later is copied without touching this line. That was the one
         * argument recorded against a deep copy: cloning field by field, and a forgotten field then
         * dropping out of workspace.xml with no error. `copyFrom` has no such failure mode, and
         * "a snapshot carries every field a remark is stored with" in RemarkStoreStateTest pins it.
         *
         * The cost is one small object per remark per call, and snapshot() runs on every resolve. A
         * RemarkState is eleven stored properties, so a project with a hundred remarks pays tens of
         * microseconds — against a resolve that SHA-256s candidate positions and splits whole
         * documents into lines.
         */
        @Synchronized
        fun snapshot(): List<RemarkState> =
            remarks.map { live -> RemarkState().also { it.copyFrom(live) } }

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

    fun setSeverity(ids: Set<String>, severity: RemarkSeverity): Int =
        liveState.setSeverity(ids, severity)

    fun setBucket(ids: Set<String>, bucket: String?): Int = liveState.setBucket(ids, bucket)

    fun removeHandedOver(): Int = liveState.removeHandedOver()

    fun clear(): Int = liveState.clear()

    /**
     * A fresh state carrying a copy of the list, taken under the same lock the mutators hold, so
     * the serializer never iterates a list anyone can mutate.
     *
     * The copy goes all the way down: snapshot() copies each RemarkState too, so the serializer
     * shares no object with the live state at all. See snapshot() for why that is not the
     * hand-cloning it once looked like.
     */
    override fun getState(): RemarksState =
        RemarksState().also { it.remarks.addAll(liveState.snapshot()) }

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
