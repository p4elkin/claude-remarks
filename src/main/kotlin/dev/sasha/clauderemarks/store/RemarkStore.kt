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
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag

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
        // object (all four methods below lock the same monitor, `this` RemarksState instance), and
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
         * Changes a remark's text and tag in place, under the same lock every other mutator holds.
         *
         * In place, not replace-with-a-copy. The list copy getState() hands the serializer is
         * shallow, so it shares these objects: a save landing between the two field writes below
         * would see the new text with the old tag.
         *
         * That torn write cannot become permanent, and the reason is the ORDER below.
         * incrementModificationCount() runs after both fields are written, so a save that lands
         * between them records the lower count it read on the way in. The next save sees a higher
         * count and serializes both fields again. One save is stale, the one after it is right,
         * and there is no path to permanent loss.
         *
         * (A copy would also be possible: BaseState.copyFrom(BaseState) exists and copies every
         * stored property by reflection, so nothing would have to be cloned by hand. It is simply
         * not needed once the ordering above holds.)
         */
        @Synchronized
        fun editRemark(id: String, text: String, tag: RemarkTag?): Boolean {
            val target = remarks.firstOrNull { it.id == id } ?: return false
            target.text = text
            target.tag = tag
            incrementModificationCount()
            return true
        }

        /** Returns how many actually changed, so marking an already-sent remark is a no-op. */
        @Synchronized
        fun markSent(ids: Set<String>): Int {
            val changed = remarks.filter { it.id in ids && it.status != RemarkStatus.SENT }
            changed.forEach { it.status = RemarkStatus.SENT }
            if (changed.isNotEmpty()) incrementModificationCount()
            return changed.size
        }

        /** Returns how many were removed. */
        @Synchronized
        fun removeSent(): Int {
            val before = remarks.size
            remarks.removeIf { it.status == RemarkStatus.SENT }
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

        /** A copy, so readers never iterate the live list while the EDT mutates it. */
        @Synchronized
        fun snapshot(): List<RemarkState> = remarks.toList()

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

    fun edit(id: String, text: String, tag: RemarkTag?): Boolean = liveState.editRemark(id, text, tag)

    fun markSent(ids: Set<String>): Int = liveState.markSent(ids)

    fun removeSent(): Int = liveState.removeSent()

    fun clear(): Int = liveState.clear()

    /**
     * A fresh state carrying a copy of the list, taken under the same lock add and remove hold,
     * so the serializer never iterates a list anyone can mutate.
     *
     * The copy is shallow: it shares the RemarkState elements with the live state. `editRemark`
     * and `markSent` do mutate an element in place, and that is safe here for the reason spelled
     * out on editRemark: a single field write reads as either the old or the new value, never as a
     * corrupt one, and the next save writes the new value. A deep copy would mean cloning
     * RemarkState field by field, and a field forgotten there later would drop out of
     * workspace.xml with no error — a worse bug than the one being fixed.
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
