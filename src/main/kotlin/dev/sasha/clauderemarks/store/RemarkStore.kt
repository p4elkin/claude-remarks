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

        // The mutators live here, not on RemarkStore. BaseState.incrementModificationCount()
        // is protected, so it is only reachable from inside a BaseState subclass. Calling it
        // as state.incrementModificationCount() from RemarkStore does not compile.
        // It must be called at all: BaseState does not notice in-place collection changes,
        // and without it the state is never written to disk.
        fun addRemark(remark: RemarkState) {
            remarks.add(remark)
            incrementModificationCount()
        }

        fun removeRemark(id: String): Boolean {
            val removed = remarks.removeIf { it.id == id }
            if (removed) incrementModificationCount()
            return removed
        }
    }

    fun all(): List<RemarkState> = state.remarks.toList()

    fun add(remark: RemarkState) = state.addRemark(remark)

    fun remove(id: String) {
        state.removeRemark(id)
    }

    companion object {
        fun getInstance(project: Project): RemarkStore = project.service()
    }
}
