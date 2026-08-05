package dev.sasha.clauderemarks.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleTextAttributes
import dev.sasha.clauderemarks.model.RemarkStatus
import javax.swing.Icon

/**
 * How a remark's status looks, read by both the gutter icon and the tool window tree, so that a
 * future change to how a status looks happens in one place instead of the two it used to.
 *
 * Two channels carrying two different facts, because the three states do not collapse onto one axis.
 *
 * **Colour answers "is this still the work", which has two answers.** Publish Unread takes everything
 * that is not `READ`, so `PUBLISHED` goes in the next publish exactly like `PENDING` does, and only
 * `READ` is done. So `PENDING` and `PUBLISHED` share regular text and `READ` alone is grey. This is
 * not the old PENDING/everything-else line, which greyed a published remark as though it were
 * finished when the next publish would carry it again.
 *
 * **The icon answers "which state is it", which has three answers.** One icon each, telling the
 * three steps in order: written, sent, confirmed. Making the icon follow the colour's two-way split
 * instead would leave `PENDING` and `PUBLISHED` indistinguishable at the start of a row, which is
 * the whole reason a row carries an icon.
 *
 * `PUBLISHED` deliberately borrows the toolbar button's own icon, so the mark on the row is the
 * picture of the action that put it there.
 *
 * `ui/RemarkActions.kt`'s `remarkChangeActions` already sits in `ui/` while being shared by the
 * gutter and the tree, so this file follows the same placement rather than inventing a new one.
 */
object RemarkStatusLook {

    /** Written, and handed nowhere yet. */
    private val PENDING_ICON: Icon = AllIcons.General.Note

    /** Handed to a channel that cannot confirm a read — the same icon the Publish buttons carry. */
    private val PUBLISHED_ICON: Icon = AllIcons.Actions.Upload

    /** An agent said it actually read this one. The only state that is done. */
    private val READ_ICON: Icon = AllIcons.Actions.Checked

    /** The icon for [status], the same instance the gutter and the tree row both draw. */
    fun icon(status: RemarkStatus): Icon = when (status) {
        RemarkStatus.PENDING -> PENDING_ICON
        RemarkStatus.PUBLISHED -> PUBLISHED_ICON
        RemarkStatus.READ -> READ_ICON
    }

    /** The text attributes a tree row's body draws with for [status]. */
    fun textAttributes(status: RemarkStatus): SimpleTextAttributes = when (status) {
        RemarkStatus.PENDING, RemarkStatus.PUBLISHED -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        RemarkStatus.READ -> SimpleTextAttributes.GRAYED_ATTRIBUTES
    }
}
