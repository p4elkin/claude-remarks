package dev.sasha.clauderemarks.store

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JUnit: the renderer is pure and the writer only needs a temporary directory. */
class RemarkHistoryTest {

    @Test
    fun `a rendered remark carries everything that was stored about it`() {
        val out = renderHistory(
            listOf(
                remark(
                    id = "r-1",
                    path = "src/Foo.kt",
                    startLine = 9,
                    endLine = 11,
                    text = "why is this locked?",
                    bucket = "auth refactor",
                    commit = "0123456789abcdef0123456789abcdef01234567",
                )
            ),
            now = 0L,
        )

        assertTrue(out, out.contains("**src/Foo.kt** lines 10-12"))
        assertTrue(out, out.contains("bucket auth refactor"))
        assertTrue(out, out.contains("commit 01234567"))
        assertTrue(out, out.contains("why is this locked?"))
    }

    /**
     * The heading gains the same shape `ui/RemarksTree.kt`'s `positionLabel` draws for a resolved
     * row: a sub-line remark inside one line reads "9:12-38", 1-based. Read straight off what was
     * STORED, not off any resolve — `renderHistory` never resolves anything.
     */
    @Test
    fun `a sub-line remark's heading carries the columns`() {
        val out = renderHistory(
            listOf(remark(id = "r-1", startLine = 8, endLine = 8, startColumn = 11, endColumn = 37)),
            now = 0L,
        )

        assertTrue(out, out.contains("lines 9:12-38"))
    }

    /**
     * Indented the same way the remark text already is: arbitrary source text, so it can hold a
     * fence or a heading character, and indenting it stops that from restructuring the file.
     */
    @Test
    fun `a sub-line remark's phrase is written indented`() {
        val out = renderHistory(
            listOf(
                remark(
                    id = "r-1",
                    startLine = 8,
                    endLine = 8,
                    startColumn = 11,
                    endColumn = 37,
                    phrase = "the locked value",
                )
            ),
            now = 0L,
        )

        assertTrue(out, out.contains("\n      the locked value\n"))
    }

    /**
     * The path most remarks take. Pinned character for character, not with `contains`, because a
     * mutation that prints columns for a whole-line remark must be visible here, not only in the
     * sub-line test above.
     */
    @Test
    fun `a whole-line remark's output is unchanged, character for character`() {
        val out = renderHistory(
            listOf(
                remark(
                    id = "r-1",
                    path = "src/Foo.kt",
                    startLine = 9,
                    endLine = 11,
                    text = "why is this locked?",
                    bucket = "auth refactor",
                    commit = "0123456789abcdef0123456789abcdef01234567",
                )
            ),
            now = 0L,
        )

        assertEquals(
            "**src/Foo.kt** lines 10-12 — bucket auth refactor — " +
                "commit 01234567\n\n      why is this locked?\n",
            out.substringAfter("\n- "),
        )
    }

    /**
     * A general remark has no file and no line range, so positionLabel has nothing to describe:
     * unlike the sub-line and whole-line cases above, no "lines" segment is printed at all.
     */
    @Test
    fun `a general remark's heading says general and prints no line numbers`() {
        val out = renderHistory(listOf(remark(id = "r-1", path = null)), now = 0L)

        assertTrue(out, out.contains("- **(general)**"))
        assertFalse(out, out.contains("lines"))
    }

    @Test
    fun `a remark with no bucket and no commit renders without empty separators`() {
        val out = renderHistory(listOf(remark(id = "r-1", bucket = null, commit = null)), now = 0L)

        assertFalse(out, out.contains("bucket "))
        assertFalse(out, out.contains("commit "))
        // The heading ends at the line range, with no dangling em dash where a field used to be.
        assertTrue(out, out.contains("**src/Foo.kt** lines 1-1\n"))
    }

    /**
     * The heading is what separates one reading pass from the next. Without it the archive is one
     * undifferentiated bullet list, and the `now` parameter — which exists only so this is testable —
     * has nothing reading it.
     */
    @Test
    fun `each pass is written under its own dated heading`() {
        val out = renderHistory(listOf(remark(id = "r-1")), now = 0L)

        assertTrue(out, Regex("""## cleared \d{4}-\d{2}-\d{2} \d{2}:\d{2}""").containsMatchIn(out))
        // Two different moments must render differently, or the timestamp is not being read.
        assertNotEquals(out, renderHistory(listOf(remark(id = "r-1")), now = 400L * 86_400_000L))
    }

    /**
     * The remark text is indented into a block, so a heading inside it cannot restructure the file.
     * The heading LINE is not indented, and the bucket is the only free-form field on it —
     * setRemarkBucket trims the ends but leaves an inner newline alone. Not reachable from today's
     * single-line chooser; this closes the asymmetry rather than relying on the chooser staying that
     * way.
     */
    @Test
    fun `a newline inside a bucket name cannot break out of the heading line`() {
        val out = renderHistory(listOf(remark(id = "r-1", bucket = "auth\n## forged")), now = 0L)

        assertTrue(out, out.contains("bucket auth ## forged"))
        assertFalse(out, out.lines().any { it.startsWith("## forged") })
    }

    /**
     * An answer's entry carries the three things section 16 of the spec names: where it was, the
     * question it answers, and the body. Both blocks are indented, and the label line between them is
     * the only thing written flat, so the two can be told apart without either being able to escape.
     */
    @Test
    fun `an answer's entry carries its position, its question and its markdown indented`() {
        val out = renderHistory(
            remarks = emptyList(),
            answers = listOf(
                answer(
                    path = "src/Foo.kt",
                    startLine = 9,
                    endLine = 11,
                    question = "why is this locked?",
                    markdown = "because two threads write it",
                    commit = "0123456789abcdef0123456789abcdef01234567",
                )
            ),
            now = 0L,
        )

        assertTrue(out, out.contains("### answers"))
        assertTrue(out, out.contains("- **src/Foo.kt** lines 10-12 — commit 01234567\n"))
        assertTrue(out, out.contains("\n      why is this locked?\n"))
        assertTrue(out, out.contains("\n  answered:\n"))
        assertTrue(out, out.contains("\n      because two threads write it\n"))
    }

    /**
     * The reason the body is indented at all. An answer holds a heading or a fence far more often
     * than a remark does — it is markdown a model wrote — and an unindented one would end the entry
     * and restructure everything under it.
     */
    @Test
    fun `a heading inside an answer cannot restructure the document`() {
        val out = renderHistory(
            remarks = emptyList(),
            answers = listOf(answer(markdown = "## forged\n```\nnot a fence\n```")),
            now = 0L,
        )

        assertFalse(out, out.lines().any { it.startsWith("## forged") })
        assertFalse(out, out.lines().any { it.startsWith("```") })
    }

    /** An answer to a general remark has no file and no line range, the same as a general remark. */
    @Test
    fun `a general remark's answer says general and prints no line numbers`() {
        val out = renderHistory(emptyList(), listOf(answer(path = null)), now = 0L)

        assertTrue(out, out.contains("- **(general)**"))
        assertFalse(out, out.contains("lines"))
    }

    /**
     * The other reason an answer can have no path, and it must not read as "(general)". A remark
     * deleted between the publish and the answer leaves an answer with no path and no question, and
     * calling that general says something that was never true: it was about a file, and the question
     * went.
     */
    @Test
    fun `an answer whose remark was deleted says so instead of saying general`() {
        val out = renderHistory(emptyList(), listOf(answer(path = null, question = "")), now = 0L)

        assertTrue(out, out.contains("- **(the remark was already deleted)**"))
        assertFalse(out, out.contains("(general)"))
    }

    /**
     * A blank question used to be written through the same indent as a real one, which produced a
     * line of six spaces and nothing else — a stray blank line saying nothing about the question
     * having been lost.
     */
    @Test
    fun `a blank question is written as a sentence rather than as an indented blank line`() {
        val out = renderHistory(emptyList(), listOf(answer(question = "")), now = 0L)

        assertTrue(out, out.contains("(the question was deleted before the answer arrived)"))
        assertFalse(out, out.lines().any { it.isBlank() && it.isNotEmpty() })
    }

    /**
     * One `## cleared` entry per pass, not one per list. Clear All takes both lists in a single
     * write, so a person reading the archive finds one heading covering the whole pass.
     */
    @Test
    fun `remarks and answers cleared together go under one dated heading`() {
        val out = renderHistory(listOf(remark(id = "r-1")), listOf(answer()), now = 0L)

        assertEquals(1, Regex("## cleared").findAll(out).count())
        assertTrue(out, out.indexOf("### answers") > out.indexOf("- **src/Foo.kt**"))
    }

    /** Nothing prints the subsection when there is nothing in it, which is every Clear Handed Over. */
    @Test
    fun `no answers means no answers section at all`() {
        val out = renderHistory(listOf(remark(id = "r-1")), now = 0L)

        assertFalse(out, out.contains("### answers"))
    }

    @Test
    fun `an archive of answers alone is still written`() {
        val file = Files.createTempDirectory("claude-remarks-history").resolve("history.md")

        assertEquals(1, appendToHistory(file, emptyList(), listOf(answer())))

        assertTrue(Files.readString(file).contains("because two threads write it"))
    }

    /** A project called "My App / v2" must not turn its archive's name into a path. */
    @Test
    fun `a project name is reduced to something that can be a file name`() {
        assertEquals("My_App___v2", safeName("My App / v2"))
    }

    @Test
    fun `writing twice appends rather than replacing`() {
        val file = Files.createTempDirectory("claude-remarks-history").resolve("deep/history.md")

        assertEquals(1, appendToHistory(file, listOf(remark(id = "r-1", text = "first"))))
        assertEquals(1, appendToHistory(file, listOf(remark(id = "r-2", text = "second"))))

        val written = Files.readString(file)
        assertTrue(written, written.contains("first"))
        assertTrue(written, written.contains("second"))
    }

    @Test
    fun `writing nothing writes no file at all`() {
        val file = Files.createTempDirectory("claude-remarks-history").resolve("history.md")

        assertEquals(0, appendToHistory(file, emptyList()))

        assertFalse(Files.exists(file))
    }
}
