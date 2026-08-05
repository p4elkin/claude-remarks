package dev.sasha.clauderemarks.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.hashLines

/**
 * An answer resolved against the file it points at.
 *
 * An answer carries its own anchor and is never resolved through the remark it answers, so these
 * are the same four cases `ResolveAllTest` pins for a remark, asked of an answer instead: it finds
 * its code, it follows that code when lines are inserted above it, it orphans when the code is
 * gone, and an answer to a general remark resolves as itself rather than as an orphan.
 *
 * Fixture-backed for the same reason `ResolveAllTest` is: real files, a real project root, real
 * Documents.
 */
class AnswerResolveTest : BasePlatformTestCase() {

    fun testAnAnswerResolvesAgainstTheFileItPointsAt() {
        val root = rootHolding("answer-found", "inside line\n")
        val stored = answer(path = "inside.kt", textHash = hashLines(listOf("inside line")))

        assertEquals(AnchorResult.Exact(0, 0), resolveAnswers(root, listOf(stored)).single().result)
    }

    /**
     * The whole reason an answer stores an anchor rather than a plain line number. Twenty lines go
     * in above the answered code and the answer moves with it, reported as Relocated so a reader
     * can tell it moved.
     */
    fun testAnAnswerFollowsTheCodeWhenTwentyLinesAreInsertedAboveIt() {
        val root = rootHolding("answer-moved", "\n".repeat(20) + "inside line\n")
        val stored = answer(path = "inside.kt", textHash = hashLines(listOf("inside line")))

        assertEquals(
            AnchorResult.Relocated(20, 20),
            resolveAnswers(root, listOf(stored)).single().result,
        )
    }

    /** The code the answer was about is gone, and nothing around it matches either. */
    fun testAnAnswerOrphansWhenTheCodeIsGone() {
        val root = rootHolding("answer-gone", "something else entirely\n")
        val stored = answer(
            path = "inside.kt",
            startLine = 0,
            endLine = 0,
            textHash = hashLines(listOf("inside line")),
        )

        assertEquals(
            AnchorResult.Orphaned(0, 0),
            resolveAnswers(root, listOf(stored)).single().result,
        )
    }

    /** A path that names no file at all under the root is the same refusal. */
    fun testAnAnswerWhoseFileIsMissingIsOrphaned() {
        val root = rootHolding("answer-missing", "inside line\n")
        val stored = answer(path = "nowhere.kt")

        assertEquals(
            AnchorResult.Orphaned(0, 0),
            resolveAnswers(root, listOf(stored)).single().result,
        )
    }

    /**
     * An answer to a general remark carries an empty path, and resolves as itself the way the
     * general remark does — not as an orphan, which is what a broken position looks like.
     */
    fun testAnAnswerWithAnEmptyPathResolvesAsItselfNotOrphaned() {
        val root = rootHolding("answer-general", "inside line\n")

        assertEquals(
            AnchorResult.Exact(0, 0),
            resolveAnswers(root, listOf(answer(path = ""))).single().result,
        )
        assertEquals(
            AnchorResult.Exact(0, 0),
            resolveAnswers(root, listOf(answer(path = null))).single().result,
        )
    }

    /** The same refusal `ResolveAllTest` pins for a remark: a stored path may not leave the root. */
    fun testAnAnswerPathThatClimbsOutOfTheProjectIsNotRead() {
        val root = rootHolding("answer-escape", "inside line\n")
            .also { myFixture.addFileToProject("secret.txt", "secret line\n") }
        val escaping = answer(path = "../secret.txt", textHash = hashLines(listOf("secret line")))

        assertEquals(
            AnchorResult.Orphaned(0, 0),
            resolveAnswers(root, listOf(escaping)).single().result,
        )
    }

    /** An answer is never dropped, the same rule a remark has: a root that did not resolve still
     *  produces one row per answer, orphaned at its stored lines. */
    fun testAnUnknownProjectRootStillProducesARowPerAnswer() {
        val stored = answer(path = "src/Foo.kt", startLine = 4, endLine = 6)

        val rows = resolveAnswers(null, listOf(stored))

        assertEquals(1, rows.size)
        assertEquals(AnchorResult.Orphaned(4, 6), rows.single().result)
    }

    /** A directory of its own per test, holding one file named `inside.kt`. */
    private fun rootHolding(dir: String, text: String) =
        myFixture.addFileToProject("$dir/inside.kt", text).virtualFile.parent
}
