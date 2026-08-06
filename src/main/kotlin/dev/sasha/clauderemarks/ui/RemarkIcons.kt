package dev.sasha.clauderemarks.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * The three question-mark icons the question track draws — [RemarkStatusLook] picks between these
 * and the two tick icons `AllIcons` already ships.
 *
 * `AllIcons` has no coloured question mark, so these three are files of our own under
 * `src/main/resources/dev/sasha/clauderemarks/icons/`, copied from the platform's own
 * `expui/general/questionMark.svg` shape and recoloured to match the tick pair (neutral), the
 * warning triangle (yellow) and the inspections check (green) — see spec section 13 for exactly
 * which files each colour was copied from. Each has a `_dark` sibling; `IconLoader` finds it by
 * that suffix without being told.
 *
 * ⚠️ **A wrong resource path fails only at runtime, and nothing in the build catches it.**
 * `IconLoader.getIcon` hands back a `CachedImageIcon` that has not looked at the path yet, so it
 * neither throws nor logs anything at this line; the load happens the first time something asks the
 * icon for a size or paints it, and a load that fails falls back to the platform's 1×1 `EMPTY_ICON`.
 * `verifyPlugin` never looks at a resource path string either, and a unit test that only calls
 * [RemarkStatusLook.icon] gets a non-null `Icon` back whatever the path says. `RemarkIconsTest` is
 * what actually catches it, from both sides: it asks the classpath for each resource by the path
 * [resourcePath] builds, and it reads each loaded icon's width, which is 16 for an SVG that parsed
 * and 1 for anything that did not.
 */
object RemarkIcons {
    val QuestionPending: Icon = load("questionPending")
    val QuestionPublished: Icon = load("questionPublished")
    val QuestionAnswered: Icon = load("questionAnswered")

    /**
     * The absolute resource path for an icon of ours, leading slash and all.
     *
     * Internal rather than private so `RemarkIconsTest` can ask the classpath for the very same
     * string this file hands `IconLoader`. Spelling the path out a second time in the test would
     * leave a typo *here* invisible: all six files would still be present under the path the test
     * checked, and the test would pass while every icon at runtime resolved to nothing.
     */
    internal fun resourcePath(name: String): String = "/dev/sasha/clauderemarks/icons/$name.svg"

    private fun load(name: String): Icon =
        IconLoader.getIcon(resourcePath(name), RemarkIcons::class.java)
}
