package dev.sasha.clauderemarks.store

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The repository HEAD commit, read straight out of .git.
 *
 * No platform import, and no VCS API. Git integration lives in the separate Git4Idea plugin, and
 * using it would mean declaring a dependency on it and requiring it to be installed. This plugin
 * depends only on the base IntelliJ platform module, so that it loads in any JetBrains IDE, and
 * forty lines of file reading keeps that true. It only understands git, which is enough.
 *
 * Everything here answers null rather than throwing. A missing commit stamp is a missing field on
 * the remark; it is never a reason for the remark not to be added.
 */

private val SHA = Regex("[0-9a-f]{40}")

/** The HEAD commit of the repository [startDir] is in, walking up to find it, or null. */
fun headCommit(startDir: Path): String? {
    val gitDir = gitDirFor(startDir) ?: return null
    val head = readTrimmed(gitDir.resolve("HEAD")) ?: return null
    if (!head.startsWith("ref:")) return head.takeIf { SHA.matches(it) }

    val ref = head.removePrefix("ref:").trim()
    // A worktree's own directory holds its HEAD but not the refs: those live in the shared
    // directory that commondir names. A plain repository has no commondir, and then the two are
    // the same directory, so this one lookup covers both.
    val commonDir = readTrimmed(gitDir.resolve("commondir"))
        ?.let { runCatching { gitDir.resolve(it).normalize() }.getOrNull() }
        ?: gitDir

    val loose = refInside(commonDir, ref)?.let { readTrimmed(it) }?.takeIf { SHA.matches(it) }
    return loose ?: packedRef(commonDir, ref)
}

/**
 * [commonDir] joined with the ref path out of HEAD, but only if it stayed under [commonDir].
 *
 * A real ref always lives inside the git directory. A hand-written `ref: ../../../../etc/shadow`
 * would otherwise make readTrimmed open that file, and while only 40 lowercase hex characters survive
 * SHA.matches — so at most one bit about the file leaks — there is no reason to read it at all. Note
 * what this cannot cover: readString follows symlinks, so a symlinked refs/heads/main still resolves
 * wherever it points.
 */
private fun refInside(commonDir: Path, ref: String): Path? =
    try {
        commonDir.resolve(ref).normalize().takeIf { it.startsWith(commonDir) }
    } catch (e: RuntimeException) {
        null
    }

/**
 * The directory holding HEAD, found by walking up from [startDir]. A project can be opened at a
 * module below the repository root, so the first .git up the tree is the answer.
 *
 * .git is a file rather than a directory in a worktree and in a submodule. It then holds one line,
 * "gitdir: <path>", and that path may be relative to the file's own directory.
 *
 * The gitdir path is deliberately NOT constrained to stay under this directory, unlike the ref path
 * in headCommit. Pointing outside is what gitdir is for: a worktree's .git names
 * <main repo>/.git/worktrees/<name>, which is not under the worktree at all. A containment check here
 * would break every real worktree and submodule to guard against a hand-edited .git file, which
 * already implies write access to the repository.
 */
private fun gitDirFor(startDir: Path): Path? {
    val start = runCatching { startDir.toAbsolutePath().normalize() }.getOrNull() ?: return null
    val dotGit = generateSequence(start) { it.parent }
        .map { it.resolve(".git") }
        .firstOrNull { Files.exists(it) }
        ?: return null

    if (Files.isDirectory(dotGit)) return dotGit
    val named = readTrimmed(dotGit)
        ?.lineSequence()
        ?.firstOrNull { it.startsWith("gitdir:") }
        ?.removePrefix("gitdir:")
        ?.trim()
        ?: return null
    return runCatching { dotGit.parent.resolve(named).normalize() }.getOrNull()
}

/** After git gc or git pack-refs there is no loose ref file, and the branch is a line in here. */
private fun packedRef(commonDir: Path, ref: String): String? =
    readTrimmed(commonDir.resolve("packed-refs"))
        ?.lineSequence()
        ?.map { it.trim().split(' ') }
        ?.firstOrNull { it.size == 2 && it[1] == ref && SHA.matches(it[0]) }
        ?.first()

/**
 * IOException and RuntimeException, not Throwable. This reads a whole file into a String before
 * anything looks at it, and a `packed-refs` big enough to exhaust the heap would otherwise report
 * "no commit" having taken the process's memory down with it — an OutOfMemoryError swallowed and
 * relabelled. RuntimeException stays in for InvalidPathException.
 */
private fun readTrimmed(path: Path): String? =
    try {
        Files.readString(path).trim()
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }
