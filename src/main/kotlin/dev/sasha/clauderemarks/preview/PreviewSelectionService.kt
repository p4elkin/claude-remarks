package dev.sasha.clauderemarks.preview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The last selection a preview reported, which file's preview reported it, and what the source of
 * that file looked like at the time.
 *
 * [sourceStamp] is the `Document.getModificationStamp()` of the `.md` source, read on the EDT in the
 * same pass that narrowed the range against that text. It is the only thing that can tell a stale
 * selection apart from a fresh one: the offsets are plain numbers, and numbers taken from an older
 * text still fit a newer text of the same length. See `previewStampProblem` in
 * `action/AddPreviewRemarkAction.kt` for the refusal it feeds.
 */
data class StoredSelection(val fileUrl: String, val range: SourceRange, val sourceStamp: Long)

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
 * The field is @Volatile because it is read from a thread that never writes it. Every write is on
 * the EDT — `PreviewRemarkExtension.receive` hops there with `invokeLater` before it stores anything,
 * and `dispose` runs there too — while the reader is `AddPreviewRemarkAction.update`, which declares
 * `ActionUpdateThread.BGT` and so runs on a background thread. Without @Volatile that reader could go
 * on seeing a selection the EDT has already replaced or cleared.
 *
 * No lock: [remember] and [forget] are each one assignment of an immutable value. [forgetSelectionIn]
 * is the one read-decide-write sequence here, and its own KDoc says what losing that race costs.
 */
@Service(Service.Level.PROJECT)
class PreviewSelectionService {

    @Volatile
    private var stored: StoredSelection? = null

    /** Replaces whatever was there. Every message from the page overwrites the last one. */
    fun remember(fileUrl: String, range: SourceRange, sourceStamp: Long) {
        stored = StoredSelection(fileUrl, range, sourceStamp)
    }

    /** What the page says when nothing is selected any more. */
    fun forget() {
        stored = null
    }

    /**
     * Drops the stored selection only when it belongs to [fileUrl]. This is what a closing preview
     * calls: a closed page reports nothing, so without it the selection made there stays stored and
     * the next right click in another preview can be answered with it.
     *
     * The url check is the whole point. A plain [forget] on close would throw away a live selection
     * a second preview had just stored, which is the case the two-previews-open comparison above
     * exists to protect.
     *
     * A read, a decision and a write, so two threads could in principle interleave here. In practice
     * both writers are on the EDT (see the class KDoc), and the worst a lost race could cost is one
     * selection a person has to make again — never a remark on the wrong file, because the only way
     * this clears anything is when the url matched.
     */
    fun forgetSelectionIn(fileUrl: String) {
        if (stored?.fileUrl == fileUrl) stored = null
    }

    fun current(): StoredSelection? = stored

    companion object {
        fun getInstance(project: Project): PreviewSelectionService = project.service()
    }
}
