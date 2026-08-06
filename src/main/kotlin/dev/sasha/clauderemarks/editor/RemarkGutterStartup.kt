package dev.sasha.clauderemarks.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.sasha.clauderemarks.review.ReviewHandshakeService
import dev.sasha.clauderemarks.skill.notifySkillInstallIfNeeded

/**
 * Touching the service is what creates it; start() is what registers its listeners and seeds the
 * editors that are already open. The gutter has to work whether or not the tool window was ever
 * opened, so it cannot be started from there. The handshake service copies that same shape.
 *
 * The skill-install check runs inline after both, on purpose: `execute` is a suspend function
 * already running off the EDT, so [notifySkillInstallIfNeeded]'s own filesystem reads need no
 * executor hop of their own — see that function's KDoc.
 */
class RemarkGutterStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        RemarkGutter.getInstance(project).start()
        ReviewHandshakeService.getInstance(project).start()
        notifySkillInstallIfNeeded(project)
    }
}
