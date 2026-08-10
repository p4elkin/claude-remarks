package dev.sasha.clauderemarks.skill

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.settings.RemarkSettings
import dev.sasha.clauderemarks.settings.RemarkSettingsConfigurable
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private const val NOTIFICATION_GROUP = "Claude Remarks"

/**
 * At most one balloon per IDE run, across however many projects open in it. Application level on
 * purpose: [RemarkSettings.SettingsState.skillInstallPromptDismissed] is a persisted "never show
 * this again," and this is the separate, unpersisted "not a second time in this run" — opening
 * three projects while the skill is missing must show one balloon, not three.
 */
private val shownThisRun = AtomicBoolean(false)

/**
 * Whether the skill-install notification should fire, decided from what detection found and from
 * the persisted and in-memory state around it. Pure — [SkillInstallNotificationTest] covers every
 * combination with no fixture, because it needs none: [presence] is the same platform-free sealed
 * class [SkillInstall.skillPresence] already returns.
 *
 * Fires only when Claude Code is detected ([claudeCodeFound]), **and** the installed copy is
 * missing, unstamped, or a different version than [bundledVersion], **and** the person has not
 * dismissed it ([dismissed]), **and** nothing was shown yet in this IDE run
 * ([alreadyShownThisRun]).
 *
 * ⚠️ **Never fires when [presence] is [SkillInstall.SkillPresence.Symlink].** That is the
 * development checkout on this machine: a checkout is not a broken install, and
 * [SkillInstall.installSkill] already refuses to write through a symlink, so a notification
 * offering an action that would only be refused is worse than staying quiet. This gets its own
 * line, rather than being folded into the version check below, because it is the rule most likely
 * to be dropped in a later rewrite of this function.
 */
fun shouldNotifySkillInstall(
    claudeCodeFound: Boolean,
    presence: SkillInstall.SkillPresence,
    bundledVersion: String,
    dismissed: Boolean,
    alreadyShownThisRun: Boolean,
): Boolean {
    if (!claudeCodeFound || dismissed || alreadyShownThisRun) return false
    return when (presence) {
        SkillInstall.SkillPresence.Symlink -> false
        SkillInstall.SkillPresence.Missing -> true
        is SkillInstall.SkillPresence.Present -> presence.version != bundledVersion
    }
}

/**
 * What the balloon says, for each of the three states it can fire in.
 *
 * ⚠️ **One fixed sentence is wrong here, and it was wrong in two states out of three.**
 * [shouldNotifySkillInstall] fires for [SkillInstall.SkillPresence.Missing], for a
 * [SkillInstall.SkillPresence.Present] carrying no stamp — a copy installed by hand before this
 * phase — and for a `Present` carrying a different version. Saying "is not installed" in all three
 * told somebody holding `0.11.0` that they had nothing, while the settings row said "0.11.0
 * installed, 0.12.0 bundled" at the same moment. The two surfaces must never disagree about the
 * same machine, so this distinguishes exactly the states
 * [dev.sasha.clauderemarks.skill.skillRowText] distinguishes, and invents no fourth one.
 *
 * Returns null for [SkillInstall.SkillPresence.Symlink], the development checkout: there is no
 * balloon in that state, so there is no sentence for it either, and inventing one would be a state
 * this function claims to have and the notification never shows.
 */
fun skillInstallNotificationText(
    presence: SkillInstall.SkillPresence,
    bundledVersion: String,
): String? = when (presence) {
    SkillInstall.SkillPresence.Symlink -> null
    SkillInstall.SkillPresence.Missing ->
        "The Claude Remarks skill ($bundledVersion) is not installed for Claude Code."
    is SkillInstall.SkillPresence.Present ->
        if (presence.version == null) {
            "Claude Code has a copy of the Claude Remarks skill, and it carries no version. " +
                "This plugin bundles $bundledVersion."
        } else {
            "Claude Code has version ${presence.version} of the Claude Remarks skill. " +
                "This plugin bundles $bundledVersion."
        }
}

/**
 * Checks whether [project] should see the skill-install notification, and shows it if so.
 *
 * Called inline from [dev.sasha.clauderemarks.editor.RemarkGutterStartup.execute], a suspend
 * function that already runs off the EDT — so the filesystem reads below (three directories, and
 * possibly the first five lines of an 80 KB `SKILL.md`) need no executor hop of their own, and
 * neither does showing the notification: [com.intellij.notification.Notification.notify] only
 * publishes onto the notification bus, which is safe to call from any thread.
 *
 * A missing or undetectable Claude Code harness, or a bundled version that fails to resolve
 * (`null` from [bundledPluginVersion]), both return with no notification and no attempt to show
 * one — there is nothing to compare against in either case.
 *
 * The three actions on the notification, once it fires: **Install** runs the same
 * [SkillInstall.installSkill] the settings row's own button runs, on the pooled executor, then
 * reports through [dev.sasha.clauderemarks.action.notifyRemarks] the same way a publish does — this
 * configurable-free function has no status label of its own to update. **Settings** opens this
 * plugin's settings page through [ShowSettingsUtil], by [RemarkSettingsConfigurable]'s class rather
 * than by name, so a renamed display name cannot silently point this at the wrong page or at Tools
 * root. **Don't ask again** persists
 * [RemarkSettings.SettingsState.skillInstallPromptDismissed] and expires the notification.
 */
fun notifySkillInstallIfNeeded(project: Project) {
    val home = Path.of(System.getProperty("user.home"))
    val claude = SkillInstall.detectHarnesses(home).find { it.displayName == "Claude Code" }
        ?: return
    // Claude Code's HarnessInfo always carries a real targetDir — only Codex and Gemini carry
    // null, on purpose (see HarnessInfo's own KDoc) — but the type is nullable, so this still has
    // to be satisfied.
    val targetDir = claude.targetDir ?: return
    val bundledVersion = bundledPluginVersion() ?: return
    val presence = SkillInstall.skillPresence(targetDir)
    val dismissed = RemarkSettings.getInstance().skillInstallPromptDismissed

    if (!shouldNotifySkillInstall(true, presence, bundledVersion, dismissed, shownThisRun.get())) {
        return
    }
    // Null only for a symlink, which the line above has already refused — so this is the
    // "impossible" branch, taken rather than forced with !! so a later change to either function
    // stays quiet instead of throwing on project open.
    val message = skillInstallNotificationText(presence, bundledVersion) ?: return
    // Double-checked against the same flag shouldNotifySkillInstall just read: two projects can
    // reach this point in the same run, and only the one that wins the compareAndSet may show a
    // balloon.
    if (!shownThisRun.compareAndSet(false, true)) return

    val notification = NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(message, NotificationType.INFORMATION)
    notification.addAction(
        NotificationAction.createSimple("Install") {
            AppExecutorUtil.getAppExecutorService().execute {
                val problem = SkillInstall.installSkill(targetDir, bundledVersion) { name ->
                    SkillInstall::class.java.getResourceAsStream(SkillInstall.resourcePath(name))
                }
                notifyRemarks(
                    project,
                    problem ?: "The Claude Remarks skill was installed.",
                    if (problem != null) NotificationType.ERROR else NotificationType.INFORMATION,
                )
            }
        },
    )
    notification.addAction(
        NotificationAction.createSimple("Settings") {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, RemarkSettingsConfigurable::class.java)
        },
    )
    notification.addAction(
        NotificationAction.createSimpleExpiring("Don't ask again") {
            RemarkSettings.getInstance().skillInstallPromptDismissed = true
        },
    )
    notification.notify(project)
}
