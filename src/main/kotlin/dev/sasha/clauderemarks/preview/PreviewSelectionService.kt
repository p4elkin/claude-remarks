package dev.sasha.clauderemarks.preview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** The last selection a preview reported, and which file's preview reported it. */
data class StoredSelection(val fileUrl: String, val range: SourceRange)

/**
 * One selection per project, not one per file: a person selects in one preview at a time.
 *
 * The file url is stored beside the range so the reader can compare. Two previews open side by side,
 * the selection made in one and the right click done in the other, and the urls differ — the action
 * then refuses rather than writing a remark about a file the person was not looking at.
 *
 * In memory only, and deliberately so: a selection is worth less than the render it came from, and
 * an IDE restart throws the render away. There is no persisted field and no migration.
 *
 * The field is @Volatile because it is written from the browser's callback thread — a pipe handler
 * does not run on the EDT — and read from the EDT by the action's update(). No lock: [remember] and
 * [forget] are each one assignment of an immutable value, so there is no read-decide-write sequence
 * to tear.
 */
@Service(Service.Level.PROJECT)
class PreviewSelectionService {

    @Volatile
    private var stored: StoredSelection? = null

    /** Replaces whatever was there. Every message from the page overwrites the last one. */
    fun remember(fileUrl: String, range: SourceRange) {
        stored = StoredSelection(fileUrl, range)
    }

    /** What the page says when nothing is selected any more. */
    fun forget() {
        stored = null
    }

    fun current(): StoredSelection? = stored

    companion object {
        fun getInstance(project: Project): PreviewSelectionService = project.service()
    }
}
