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
        //
        // The list property does track structural changes by itself (ListStoredProperty watches
        // the list's own modCount), so this is not the only thing that marks the state dirty.
        // It is called on every mutation anyway, because that is one cheap line against silent
        // data loss, and because a change we make to a remark already in the list is not
        // something we want to have to reason about.
        //
        // The three methods are synchronized on the same object: the tool window resolves
        // remarks from a pooled thread while the editor action adds them on the EDT, and the
        // backing list is a plain ArrayList subclass with no thread safety of its own. A read
        // action does not help here, it guards platform data, not ours.
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

    fun add(remark: RemarkState) = state.addRemark(remark)

    fun remove(id: String): Boolean = state.removeRemark(id)

    companion object {
        fun getInstance(project: Project): RemarkStore = project.service()
    }
}
