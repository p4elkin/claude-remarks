package dev.sasha.clauderemarks.store

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
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
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ClaudeRemarks",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED)],
)
// The nested state class is called RemarksState, not State, on purpose: this file already
// imports the @State annotation, and a nested classifier with the same name is asking for a
// resolution clash at the annotation site.
class RemarkStore : SimplePersistentStateComponent<RemarkStore.RemarksState>(RemarksState()) {

    class RemarksState : BaseState() {
        // @get:XCollection is NOT optional and NOT cosmetic. Without it this list serializes
        // to an empty <RemarksState /> and every remark is silently lost on restart, with no
        // error logged anywhere. Verified by running it against the 2025.2 jars: a plain
        // `val x by list<T>()` emits nothing at all.
        @get:XCollection(style = XCollection.Style.v2)
        val remarks by list<RemarkState>()

        // The mutators live here, not on RemarkStore: BaseState.incrementModificationCount() is
        // protected, so only a BaseState subclass can reach it. @Synchronized keeps the plugin's
        // own two callers apart (the tool window snapshots from a pooled thread, the editor
        // action adds on the EDT), and does NOT cover the platform's serializer, which reads the
        // live list without this lock.
        // ponytail: a workspace save racing an add can still throw ConcurrentModificationException.
        // Why that is left alone, and what the real fix would cost, is in docs/claude/design.md
        // under "How Remarks are Persisted".
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

    fun all(): List<RemarkState> = state.snapshot()

    fun add(remark: RemarkState) {
        state.addRemark(remark)
    }

    /** No production caller yet: phase 3 is where deleting a remark from the tool window lands. */
    fun remove(id: String): Boolean = state.removeRemark(id)

    companion object {
        fun getInstance(project: Project): RemarkStore = project.service()
    }
}
