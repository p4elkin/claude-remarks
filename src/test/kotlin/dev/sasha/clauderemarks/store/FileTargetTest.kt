package dev.sasha.clauderemarks.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The file half of the one gate, asked without an editor.
 *
 * [fileTargetProblem] and the file-taking [relativePathOf] were split out of the two functions that
 * start from an `Editor`, so the markdown preview can ask the same question: it holds a
 * `VirtualFile` and no `Editor` at all. What this class pins is that the split kept every answer the
 * editor version already gave. The other half of that proof is `DiffRemarkTargetTest`, which was not
 * edited when the split happened and still passes.
 *
 * Fixture-backed, because both functions need a real project root and real `VirtualFile`s.
 */
class FileTargetTest : BasePlatformTestCase() {

    fun testAFileInsideTheProjectRootIsAccepted() {
        val inside = fileUnderProjectRoot(project, "Inside.kt", TEXT)

        assertNull(fileTargetProblem(project, inside))
    }

    /**
     * The fixture's in-memory file system is not under the project directory on disk, so a file
     * added there is exactly the "outside the root" case, and the sentence has to name it.
     */
    fun testAFileOutsideTheProjectRootIsRefusedInASentenceThatNamesIt() {
        val outside = myFixture.addFileToProject("elsewhere/Outside.kt", TEXT).virtualFile

        assertEquals(
            "Outside.kt is outside the project directory, so a remark on it could not be found again.",
            fileTargetProblem(project, outside),
        )
    }

    /** No file at all: the preview's own "nothing named a file" case, and a diff pane's too. */
    fun testANullFileIsRefusedWithTheNoFileOnDiskSentence() {
        assertEquals(
            "There is no file on disk here, so a remark could not be pointed back at it.",
            fileTargetProblem(project, null),
        )
    }

    /**
     * The two [relativePathOf] overloads have to agree, because the preview stores a remark through
     * the file-taking one against a path the editor entry point would have produced through the
     * other. A remark whose path depends on which entry point wrote it is a defect nothing else
     * would catch.
     */
    fun testBothRelativePathOverloadsAnswerTheSameForTheSameFile() {
        val inside = fileUnderProjectRoot(project, "Inside.kt", TEXT)
        myFixture.openFileInEditor(inside)

        assertEquals("Inside.kt", relativePathOf(project, inside))
        assertEquals(relativePathOf(project, myFixture.editor), relativePathOf(project, inside))
    }

    private companion object {
        const val TEXT = "alpha\nbeta\n"
    }
}
