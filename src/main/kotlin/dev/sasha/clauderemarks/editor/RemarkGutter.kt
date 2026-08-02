package dev.sasha.clauderemarks.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.resolveAnchor
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.REMARKS_CHANGED
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.RemarksListener
import dev.sasha.clauderemarks.store.anchorOf
import dev.sasha.clauderemarks.store.notifyRemarksChanged
import dev.sasha.clauderemarks.store.projectRoot

/**
 * One document's placements, plus the modification stamp they were computed against. The line
 * numbers inside them mean nothing for any other stamp, which is why the stamp travels with them.
 */
data class DocumentPlacements(val stamp: Long, val placements: List<RemarkPlacement>)

/**
 * Keeps one RangeHighlighter per remark on every open document.
 *
 * A RangeHighlighter IS a RangeMarker, so the platform moves it as you type and there is nothing
 * to keep in step. The highlighters are rebuilt only when a remark changes or an editor opens, not
 * on every keystroke: resolving a remark can cost a SHA-256 over every candidate position inside
 * the 200-line search radius.
 *
 * Both maps below are touched on the EDT only.
 */
@Service(Service.Level.PROJECT)
class RemarkGutter(private val project: Project) : Disposable {

    /**
     * Every document this project has an editor for, whether or not it currently has any remarks.
     * This is what makes the FIRST remark added to an open file appear at once: an earlier draft
     * kept only documents that had placements, so a file with none was invisible to the refresh.
     */
    private val tracked = mutableSetOf<Document>()

    /** The highlighter painted for each remark id, per document. */
    private val byDocument = mutableMapOf<Document, MutableMap<String, RangeHighlighter>>()

    fun start() {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor
                    // NOT editor.virtualFile: it is still null while editorCreated runs, because
                    // the platform attaches the file to the editor after firing this event. Asking
                    // FileDocumentManager for the document's file works at this moment, and it is
                    // the same question placementsFor asks anyway.
                    if (editor.project != project ||
                        FileDocumentManager.getInstance().getFile(editor.document) == null
                    ) {
                        return
                    }
                    track(editor.document)
                    // The tool window resolves against open documents, so opening one can change
                    // what it should show, not only what the gutter should show.
                    notifyRemarksChanged(project)
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    val editor = event.editor
                    if (editor.project != project) return
                    val document = editor.document
                    val stillOpen = EditorFactory.getInstance()
                        .getEditors(document, project)
                        .any { it !== editor }
                    if (!stillOpen) drop(document)
                    notifyRemarksChanged(project)
                }
            },
            this,
        )

        project.messageBus.connect(this).subscribe(
            REMARKS_CHANGED,
            RemarksListener {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) syncAll()
                }
            },
        )

        // Editors restored with the project are already open by the time a postStartupActivity
        // runs, and nothing orders the two. Without this, reopening the IDE shows no icons at all
        // until every file is closed and opened again. invokeLater because start() runs off the
        // EDT and `tracked` is EDT-only.
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            EditorFactory.getInstance().allEditors
                .filter { it.project == project && it.virtualFile != null }
                .map { it.document }
                .distinct()
                .forEach { track(it) }
        }
    }

    /** EDT. */
    private fun track(document: Document) {
        tracked.add(document)
        scheduleSync(document)
    }

    /** EDT. */
    private fun syncAll() {
        tracked.toList().forEach { scheduleSync(it) }
    }

    private fun scheduleSync(document: Document) {
        ReadAction.nonBlocking<DocumentPlacements> { placementsFor(document) }
            .expireWith(this)
            .coalesceBy(this, document)
            .finishOnUiThread(ModalityState.defaultModalityState()) { computed ->
                apply(document, computed)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Runs inside a read action, off the EDT. */
    private fun placementsFor(document: Document): DocumentPlacements {
        val stamp = document.modificationStamp
        val nothing = DocumentPlacements(stamp, emptyList())
        val file = FileDocumentManager.getInstance().getFile(document) ?: return nothing
        val root = projectRoot(project) ?: return nothing
        val path = VfsUtilCore.getRelativePath(file, root) ?: return nothing
        val lines = document.text.split("\n")

        val placements = RemarkStore.getInstance(project).all()
            .filter { it.path == path && it.id != null }
            .map { remark ->
                val result = resolveAnchor(anchorOf(remark), lines)
                val range = placementRange(result)
                RemarkPlacement(
                    id = remark.id!!,
                    text = remark.text.orEmpty(),
                    tag = remark.tag,
                    sent = remark.status == RemarkStatus.SENT,
                    startLine = range.first,
                    endLine = range.last,
                    orphaned = result is AnchorResult.Orphaned,
                )
            }
        return DocumentPlacements(stamp, placements)
    }

    /**
     * EDT. Adds an icon for a remark that appeared, removes one that went away, and repaints the
     * rest. It does not clear and rebuild the whole document, because a live highlighter carries
     * a position the platform has been keeping exact and a rebuild would throw that away.
     */
    private fun apply(document: Document, computed: DocumentPlacements) {
        if (project.isDisposed || document !in tracked) return

        // Rule 4. The line numbers were computed against a snapshot on a pooled thread. If the
        // document has moved on since, they point at the wrong lines: drop them and ask again.
        if (document.modificationStamp != computed.stamp) {
            scheduleSync(document)
            return
        }

        val model = DocumentMarkupModel.forDocument(document, project, true)
        val painted = byDocument.getOrPut(document) { mutableMapOf() }
        val wanted = computed.placements.associateBy { it.id }

        // Deleted from the store, or no longer in this file.
        painted.keys.toList()
            .filter { it !in wanted }
            .forEach { id -> painted.remove(id)?.let { model.removeHighlighter(it) } }

        val lastLine = (document.lineCount - 1).coerceAtLeast(0)
        for (placement in computed.placements) {
            val existing = painted[placement.id]

            // Rule 3. A live highlighter is exact, because the platform moved it with the text.
            // An Orphaned answer here means the resolve could not find the block, which is what
            // happens as soon as a line is added inside it. Keep the live position, and repaint
            // only what is drawn on it.
            if (existing != null && existing.isValid && placement.orphaned) {
                existing.gutterIconRenderer = rendererFor(placement)
                continue
            }

            existing?.let {
                painted.remove(placement.id)
                model.removeHighlighter(it)
            }
            val start = document.getLineStartOffset(placement.startLine.coerceIn(0, lastLine))
            val end = document.getLineEndOffset(placement.endLine.coerceIn(0, lastLine))
            painted[placement.id] = model.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null,
                HighlighterTargetArea.LINES_IN_RANGE,
            ).also { it.gutterIconRenderer = rendererFor(placement) }
        }
    }

    private fun rendererFor(placement: RemarkPlacement) = RemarkGutterIconRenderer(
        project = project,
        id = placement.id,
        text = tooltipFor(placement),
        sent = placement.sent,
    )

    /** EDT. */
    private fun drop(document: Document) {
        tracked.remove(document)
        val painted = byDocument.remove(document) ?: return
        val model = DocumentMarkupModel.forDocument(document, project, false) ?: return
        painted.values.forEach { model.removeHighlighter(it) }
    }

    override fun dispose() {
        tracked.toList().forEach { drop(it) }
        tracked.clear()
        byDocument.clear()
    }

    companion object {
        fun getInstance(project: Project): RemarkGutter = project.service()
    }
}
