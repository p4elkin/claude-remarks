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
        // object, and every reader and writer of the list goes through one of these three methods:
        // the tool window snapshots from a pooled thread, the editor action adds on the EDT, and
        // RemarkStore.getState() takes its copy for the platform's serializer through snapshot().
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

        /** A copy, so readers never iterate the live list while the EDT mutates it. */
        @Synchronized
        fun snapshot(): List<RemarkState> = remarks.toList()
    }

    fun all(): List<RemarkState> = liveState.snapshot()

    fun add(remark: RemarkState) {
        liveState.addRemark(remark)
    }

    /** No production caller yet: phase 3 is where deleting a remark from the tool window lands. */
    fun remove(id: String): Boolean = liveState.removeRemark(id)

    /**
     * A fresh state carrying a copy of the list, taken under the same lock add and remove hold,
     * so the serializer never iterates a list anyone can mutate.
     *
     * The copy is shallow: it shares the RemarkState elements with the live state. Nothing
     * mutates a remark after it has been added, and even if phase 5 starts flipping `status` in
     * place, a single field write reads as either the old or the new value, never as a corrupt
     * one. A deep copy would mean cloning RemarkState field by field, and a field forgotten there
     * later would drop out of workspace.xml with no error — a worse bug than the one being fixed.
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
     * not called at all on an idle save. Reads the live object, same as the base class did.
     */
    override fun getStateModificationCount(): Long = liveState.modificationCount

    companion object {
        fun getInstance(project: Project): RemarkStore = project.service()
    }
}
