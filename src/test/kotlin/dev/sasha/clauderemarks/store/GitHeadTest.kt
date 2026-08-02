package dev.sasha.clauderemarks.store

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plain JUnit against temporary directories: GitHead.kt has no platform import at all. */
class GitHeadTest {

    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val other = "89abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `a branch checkout reads the loose ref the HEAD names`() {
        val repo = repo()
        write(repo, ".git/HEAD", "ref: refs/heads/main\n")
        write(repo, ".git/refs/heads/main", "$sha\n")

        assertEquals(sha, headCommit(repo))
    }

    @Test
    fun `a detached HEAD holds the sha directly`() {
        val repo = repo()
        write(repo, ".git/HEAD", "$sha\n")

        assertEquals(sha, headCommit(repo))
    }

    /** After git gc there is no loose ref file: the branch lives in packed-refs. */
    @Test
    fun `a packed ref is found when there is no loose one`() {
        val repo = repo()
        write(repo, ".git/HEAD", "ref: refs/heads/main\n")
        write(
            repo,
            ".git/packed-refs",
            "# pack-refs with: peeled fully-peeled sorted \n" +
                "$other refs/heads/other\n" +
                "$sha refs/heads/main\n",
        )

        assertEquals(sha, headCommit(repo))
    }

    /**
     * A worktree and a submodule both replace .git with a file. Inside the directory it points at,
     * HEAD is local but the refs live in the shared directory that commondir names.
     */
    @Test
    fun `a worktree reads its own HEAD and the shared refs`() {
        val repo = repo()
        write(repo, ".git/refs/heads/feature", "$sha\n")
        write(repo, ".git/worktrees/wt/HEAD", "ref: refs/heads/feature\n")
        write(repo, ".git/worktrees/wt/commondir", "../..\n")
        val worktree = Files.createDirectories(repo.resolve("wt"))
        Files.writeString(worktree.resolve(".git"), "gitdir: ${repo.resolve(".git/worktrees/wt")}\n")

        assertEquals(sha, headCommit(worktree))
    }

    /** A module opened as its own project sits below the repository root. */
    @Test
    fun `the search walks up to find the repository`() {
        val repo = repo()
        write(repo, ".git/HEAD", "$sha\n")
        val module = Files.createDirectories(repo.resolve("modules/api/src"))

        assertEquals(sha, headCommit(module))
    }

    @Test
    fun `a directory with no repository above it has no commit`() {
        assertNull(headCommit(repo()))
    }

    /** Anything unreadable, truncated or hand-edited answers null rather than throwing: a missing
     *  commit stamp is a missing field, never a failure to add the remark. */
    @Test
    fun `a HEAD holding something that is not a sha has no commit`() {
        val repo = repo()
        write(repo, ".git/HEAD", "not a sha at all\n")

        assertNull(headCommit(repo))
    }

    private fun repo(): Path = Files.createTempDirectory("claude-remarks-git")

    private fun write(root: Path, relative: String, content: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
