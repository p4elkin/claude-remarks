package dev.sasha.clauderemarks.preview

import com.google.gson.Gson

/**
 * The arithmetic behind a remark written on the rendered markdown preview: what the page is allowed
 * to send, and how that turns into a character range in the `.md` file.
 *
 * **Nothing in this file knows about the markdown plugin, about JCEF or about JavaScript**, for the
 * same reason `anchor/` knows nothing about the platform: its tests then need no fixture and run in
 * milliseconds. Gson is the one library here, and it is a plain JSON parser rather than a platform
 * class, so it costs a test nothing. The browser side lives in `preview/PreviewRemarkExtension.kt`.
 */

/**
 * A range of characters in the .md source, with an exclusive end.
 *
 * Exclusive because selectedLines and selectedColumns in action/AddRemarkAction.kt already take a
 * pair of plain document offsets whose end is exclusive, and they already carry every rule about
 * what counts as a whole-line selection. Reusing them is why this file needs no line and column
 * arithmetic of its own, and why SelectedLinesTest covers this path for free.
 */
data class SourceRange(val startOffset: Int, val endOffsetExclusive: Int)

/**
 * One message from the page. Every field came out of a browser, so none of it is trusted:
 * [parseSelectionMessage] refuses anything that is not four non-negative offsets in order, plus a
 * text that is not empty.
 *
 * The four offsets are two position ranges, not one. A page can only name whole elements, and
 * `md-src-pos` sits on the nearest ancestor of each end of the selection, which is usually a whole
 * source line. [startFrom] and [startTo] are that ancestor's range where the selection starts,
 * [endFrom] and [endTo] the one where it ends, and [text] is what the person actually highlighted.
 *
 * **Only [startFrom] and [endTo] are ever read.** [narrowToSelection] searches between those two and
 * never looks at the middle pair. [startTo] and [endFrom] are kept as a sanity check on the message
 * itself: [parseSelectionMessage] requires each pair to be in order, so a page that sends nonsense
 * for either end is refused instead of producing a range that happens to fit the source. Deleting
 * them would post three fields and lose that check, which is why they stay and are explained here
 * rather than removed.
 */
data class PreviewSelection(
    val startFrom: Int,
    val startTo: Int,
    val endFrom: Int,
    val endTo: Int,
    val text: String,
)

/**
 * Gson fills these by reflection, so every field is nullable and boxed: an absent number has to read
 * as null rather than as a zero somebody sent. Same shape as the request classes in
 * review/ReviewRestService.kt, for the same reason — the body is caller-supplied.
 */
private class SelectionMessage(
    val startFrom: Int? = null,
    val startTo: Int? = null,
    val endFrom: Int? = null,
    val endTo: Int? = null,
    val text: String? = null,
)

private val gson = Gson()

/**
 * The message a page posted, or null when it is not one this can act on. Null covers every refusal
 * with no distinction, because there is nobody to tell: the sender is a script in a browser, not a
 * person, and the only caller's answer to all of them is the same — forget whatever was stored.
 *
 * The checks are the whole contract. Four numbers, none negative, each pair in order, and the
 * coarse span from the first pair's start to the second pair's end not running backwards. Plus a
 * text that is not empty: an empty highlight is a caret, not a selection, and [narrowToSelection]
 * would find it at offset zero of any slice.
 */
fun parseSelectionMessage(raw: String): PreviewSelection? {
    val message = runCatching { gson.fromJson(raw, SelectionMessage::class.java) }.getOrNull() ?: return null
    val startFrom = message.startFrom ?: return null
    val startTo = message.startTo ?: return null
    val endFrom = message.endFrom ?: return null
    val endTo = message.endTo ?: return null
    val text = message.text ?: return null
    if (startFrom < 0 || startTo < 0 || endFrom < 0 || endTo < 0) return null
    if (startFrom > startTo || endFrom > endTo || startFrom > endTo) return null
    if (text.isEmpty()) return null
    return PreviewSelection(startFrom, startTo, endFrom, endTo, text)
}

/**
 * The coarse range the page could name, tightened onto the words the person highlighted, or null
 * when the offsets do not fit [source] at all.
 *
 * The coarse range runs from the start ancestor's first offset to the end ancestor's last offset,
 * which is usually one whole source line. Narrowing it is what makes a preview remark point at a
 * phrase rather than at a paragraph.
 *
 * **One indexOf, and nothing cleverer.** The rendered text and the source text differ wherever
 * markup sits between the words: the source says `some **bold** words`, the render says
 * `some bold words`, the search fails, and the remark then points at the whole line. That is the
 * same fallback resolveWithPhrase in anchor/Anchoring.kt takes for a phrase that no longer matches.
 * The alternative is to walk the markdown parse tree and map rendered characters back to source
 * characters, which buys precision on emphasised words and costs a parser-shaped subsystem with its
 * own tests, for a case where a whole-line remark is already an honest answer.
 *
 * **The first occurrence wins.** Select the second `the` on a line and get the first one. The coarse
 * range is usually a single line, so the two are a few characters apart and the remark is still on
 * the right line. Written down as a known limit rather than fixed here.
 *
 * Null is for a stale render: the page still holds offsets from the text it was built from, and the
 * file has since got shorter. A remark pointing past the end of a file is worse than no remark.
 */
fun narrowToSelection(source: String, selection: PreviewSelection): SourceRange? {
    val from = selection.startFrom
    val to = selection.endTo
    if (from > to || to > source.length) return null
    val slice = source.substring(from, to)
    // isEmpty is belt and braces: parseSelectionMessage already refuses an empty text, and this
    // function is public, so a direct caller must not be able to produce a zero-width range at the
    // start of the line.
    val index = if (selection.text.isEmpty()) -1 else slice.indexOf(selection.text)
    if (index < 0) return SourceRange(from, to)
    return SourceRange(from + index, from + index + selection.text.length)
}
