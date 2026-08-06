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
 * `IconLoader.getIcon` returns a placeholder icon and logs a warning rather than throwing, and
 * `verifyPlugin` never looks at a resource path string. A unit test that only calls
 * [RemarkStatusLook.icon] still gets a non-null `Icon` back even if the path underneath it is
 * wrong. `RemarkIconsTest` is what actually catches that: it asks for each resource by path
 * directly, the same way a typo or a resource left out of the jar would surface.
 */
object RemarkIcons {
    val QuestionPending: Icon = load("questionPending")
    val QuestionPublished: Icon = load("questionPublished")
    val QuestionAnswered: Icon = load("questionAnswered")

    private fun load(name: String): Icon =
        IconLoader.getIcon("/dev/sasha/clauderemarks/icons/$name.svg", RemarkIcons::class.java)
}
