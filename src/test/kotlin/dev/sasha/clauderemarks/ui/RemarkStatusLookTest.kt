package dev.sasha.clauderemarks.ui

import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkStatus

/**
 * Fixture-backed, because `AllIcons` and `IconLoader` want a running application. The six cases
 * mirror the table in the plan's Technical Details section: three for the plain track, three for
 * the question track. [testAQuestionThatWasReadWithNoAnswerIsStillYellow] is pinned on its own —
 * it is the decision most likely to be quietly reversed later, since a green tick already means
 * "read" on the plain track and it would be easy to copy that rule onto the question track by
 * mistake.
 */
class RemarkStatusLookTest : BasePlatformTestCase() {

    fun testPlainPendingIsTheNoteIcon() {
        val icon = RemarkStatusLook.icon(RemarkStatus.PENDING, asksForAnswer = false, hasAnswer = false)

        assertSame(AllIcons.General.Note, icon)
    }

    fun testPlainPublishedIsTheNeutralTick() {
        val icon = RemarkStatusLook.icon(RemarkStatus.PUBLISHED, asksForAnswer = false, hasAnswer = false)

        assertSame(AllIcons.Actions.Checked, icon)
    }

    fun testPlainReadIsTheGreenTick() {
        val icon = RemarkStatusLook.icon(RemarkStatus.READ, asksForAnswer = false, hasAnswer = false)

        assertSame(AllIcons.General.InspectionsOK, icon)
    }

    fun testAQuestionWithAnAnswerIsTheGreenQuestionMark() {
        val icon = RemarkStatusLook.icon(RemarkStatus.READ, asksForAnswer = true, hasAnswer = true)

        assertSame(RemarkIcons.QuestionAnswered, icon)
    }

    fun testAQuestionWithNoAnswerStillPendingIsTheNeutralQuestionMark() {
        val icon = RemarkStatusLook.icon(RemarkStatus.PENDING, asksForAnswer = true, hasAnswer = false)

        assertSame(RemarkIcons.QuestionPending, icon)
    }

    fun testAQuestionWithNoAnswerPublishedIsTheYellowQuestionMark() {
        val icon = RemarkStatusLook.icon(RemarkStatus.PUBLISHED, asksForAnswer = true, hasAnswer = false)

        assertSame(RemarkIcons.QuestionPublished, icon)
    }

    /**
     * `READ` with no answer is the same position as `PUBLISHED` on the question track: handed over,
     * nothing back yet. Only an answer arriving earns green, so this stays yellow rather than
     * following the plain track's rule that `READ` alone is enough.
     */
    fun testAQuestionThatWasReadWithNoAnswerIsStillYellow() {
        val icon = RemarkStatusLook.icon(RemarkStatus.READ, asksForAnswer = true, hasAnswer = false)

        assertSame(RemarkIcons.QuestionPublished, icon)
    }
}
