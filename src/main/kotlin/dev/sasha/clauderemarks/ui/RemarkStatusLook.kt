package dev.sasha.clauderemarks.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.SimpleTextAttributes
import dev.sasha.clauderemarks.model.RemarkStatus
import javax.swing.Icon

/**
 * How a remark's status looks, read by both the gutter icon and the tool window tree, so that a
 * future change to how a status looks happens in one place instead of the two it used to.
 *
 * The rule the whole file encodes, since the meaning of the three states changed: what the next
 * publish carries is what still reads as work outstanding. Publish Unread takes everything that is
 * not `READ`, so `PUBLISHED` is still work, exactly like `PENDING` — only `READ` is done. The look
 * follows that split, not the old PENDING/everything-else line: `PENDING` and `PUBLISHED` both draw
 * at full strength with regular text, `READ` alone is faded and grey.
 *
 * `ui/RemarkActions.kt`'s `remarkChangeActions` already sits in `ui/` while being shared by the
 * gutter and the tree, so this file follows the same placement rather than inventing a new one.
 */
object RemarkStatusLook {

    private val FULL_ICON: Icon = AllIcons.General.Note

    /** Faded: an agent said it actually read this one. 0.25 is what the old READ_ICON already used. */
    private val READ_ICON: Icon = IconLoader.getTransparentIcon(AllIcons.General.Note, 0.25f)

    /** The icon for [status], the same instance the gutter and the tree row both draw. */
    fun icon(status: RemarkStatus): Icon = when (status) {
        RemarkStatus.PENDING, RemarkStatus.PUBLISHED -> FULL_ICON
        RemarkStatus.READ -> READ_ICON
    }

    /** The text attributes a tree row's body draws with for [status]. */
    fun textAttributes(status: RemarkStatus): SimpleTextAttributes = when (status) {
        RemarkStatus.PENDING, RemarkStatus.PUBLISHED -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        RemarkStatus.READ -> SimpleTextAttributes.GRAYED_ATTRIBUTES
    }
}
