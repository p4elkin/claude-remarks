package dev.sasha.clauderemarks.store

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

    val loose = readTrimmed(commonDir.resolve(ref))?.takeIf { SHA.matches(it) }
    return loose ?: packedRef(commonDir, ref)
}

/**
 * The directory holding HEAD, found by walking up from [startDir]. A project can be opened at a
 * module below the repository root, so the first .git up the tree is the answer.
 *
 * .git is a file rather than a directory in a worktree and in a submodule. It then holds one line,
 * "gitdir: <path>", and that path may be relative to the file's own directory.
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

private fun readTrimmed(path: Path): String? =
    runCatching { Files.readString(path).trim() }.getOrNull()
