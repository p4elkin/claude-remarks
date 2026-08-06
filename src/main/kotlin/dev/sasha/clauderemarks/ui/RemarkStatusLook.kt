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
 * **The icon answers two questions now, with six answers between them: is this row a question, and
 * how far did it get.** [asksForAnswer] picks the track, and [hasAnswer] or [status] then picks the
 * colour inside it. Three icons per track, six in all.
 *
 * The plain track has three steps in order — written, sent, confirmed — one icon each: a note, a
 * neutral tick, a green tick. The two ticks are `AllIcons.Actions.Checked` and
 * `AllIcons.General.InspectionsOK`; the old `PUBLISHED` icon, `AllIcons.Actions.Upload`, is gone. It
 * used to be a picture of the button that put the remark there, but a neutral tick and a green tick
 * differing only in colour say "sent" and "confirmed" as one progression, which is what a two-step
 * track after PENDING needs, and a progression reads better than a button's own icon repeated on
 * every row.
 *
 * The question track has three colours of its own question mark, from [RemarkIcons]: neutral, yellow,
 * green. **The neutral colour sits at a different step in each track — `PUBLISHED` on the plain
 * track, `PENDING` on the question track.** That is intended, not an inconsistency to fix. The plain
 * track's middle state is not something a person waits on, so it needs no colour of its own beyond
 * "not yet". A question's middle state — handed over, nothing back — is exactly what a person waits
 * on, so it earns yellow, and neutral falls back to the step before it, the only one left. Each track
 * is ordered within itself; the two tracks are not aligned step for step against each other.
 *
 * **A question that was `READ` with no answer stays yellow, not green.** Green is earned by an
 * answer arriving, never by `READ` alone: `READ` on the question track means the same thing
 * `PUBLISHED` does — handed over, nothing back yet — and letting `READ` alone turn it green would
 * make an unanswered question look finished, which is the one thing this colour exists to say.
 *
 * `textAttributes` is unchanged by any of this: `PENDING` and `PUBLISHED` draw in regular text,
 * `READ` in grey, on both tracks. An answered question is deliberately not greyed — from the
 * person's side an answer arriving is work to do, not work finished.
 *
 * `ui/RemarkActions.kt`'s `remarkChangeActions` already sits in `ui/` while being shared by the
 * gutter and the tree, so this file follows the same placement rather than inventing a new one.
 */
object RemarkStatusLook {

    /**
     * Plain track, written and handed nowhere yet. The question track has its own icon for this step,
     * [RemarkIcons.QuestionPending], so this one is not shared between the two.
     */
    private val PENDING_ICON: Icon = AllIcons.General.Note

    /** Plain track, handed to a channel that cannot confirm a read. */
    private val PUBLISHED_ICON: Icon = AllIcons.Actions.Checked

    /** Plain track, an agent said it actually read this one. The only state that is done. */
    private val READ_ICON: Icon = AllIcons.General.InspectionsOK

    /**
     * The icon for a row, the same instance the gutter and the tree row both draw.
     *
     * The track split is stated once, by [asksForAnswer] in the `if`, in the order the class KDoc
     * argues for. Inside the question track [hasAnswer] is checked before [status], because a
     * read-but-unanswered question must not fall through to whatever branch answers a plain `READ`;
     * that ordering is why the question half stays a conditional `when` rather than becoming a second
     * `when (status)`.
     *
     * The plain half is `when (status)` with no `else`, so it is exhaustive over [RemarkStatus] and a
     * fourth status stops the build here. Written as one flat `when` over conditions instead, a plain
     * remark that matched no branch fell into the question track's `else` and silently drew a yellow
     * question mark on a remark that asks nothing.
     */
    fun icon(status: RemarkStatus, asksForAnswer: Boolean, hasAnswer: Boolean): Icon =
        if (asksForAnswer) when {
            hasAnswer -> RemarkIcons.QuestionAnswered
            status == RemarkStatus.PENDING -> RemarkIcons.QuestionPending
            else -> RemarkIcons.QuestionPublished
        } else when (status) {
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
