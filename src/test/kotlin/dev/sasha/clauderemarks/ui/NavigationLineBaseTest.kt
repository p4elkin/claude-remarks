package dev.sasha.clauderemarks.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * OpenFileDescriptor's line argument is 0-based, the same base everything stored here uses.
 * Proven from the bytecode (navigateIn builds LogicalPosition(getLine(), ...) with no adjustment),
 * and pinned here because a silent off-by-one in navigation is easy to miss by eye.
 */
class NavigationLineBaseTest : BasePlatformTestCase() {

    fun testOpeningAtLineTwoPutsTheCaretOnTheThirdLine() {
        val file = myFixture.configureByText("Foo.kt", "alpha\nbeta\ngamma\ndelta\n").virtualFile

        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, 2, 0), true)!!

        assertEquals(2, editor.caretModel.logicalPosition.line)
        assertEquals("gamma", editor.document.text.split("\n")[2])
    }
}
