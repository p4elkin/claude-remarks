package dev.sasha.clauderemarks.store

// How a remark's context lines are written into workspace.xml and read back. The pair sits on
// its own because both sides use it: the editor action writes with joinContext, the resolver
// reads with splitContext.

/**
 * Context is stored as one newline-joined string, with one extra newline in front of the
 * first line. Null means no context at all.
 *
 * The leading newline is not decoration. RemarkState.contextBefore/contextAfter go through
 * BaseState.string(), which is a NormalizedStringStoredProperty: its setter turns an empty
 * string into null on assignment, before anything is even written to XML. Without the extra
 * newline, one blank line of context would join to "", store as null, and read back as no
 * context at all — which quietly switches off that side of the context search. A remark on
 * the last real line of a file that ends with a newline hits exactly that case, because
 * document.text.split("\n") ends with an empty line.
 */
fun joinContext(lines: List<String>): String? =
    if (lines.isEmpty()) null else lines.joinToString("\n", prefix = "\n")

/**
 * removePrefix, not drop(1). Every other stored field here is treated as untrusted, and this
 * one is no different: a string without the marker (an older workspace.xml, or one edited by
 * hand) would otherwise lose its first context line, and a one-line context would come back as
 * emptyList() — which switches that side of the context search off with nothing to show for it.
 */
fun splitContext(stored: String?): List<String> =
    if (stored.isNullOrEmpty()) emptyList() else stored.removePrefix("\n").split("\n")
