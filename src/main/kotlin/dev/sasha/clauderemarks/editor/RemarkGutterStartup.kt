package dev.sasha.clauderemarks.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.sasha.clauderemarks.review.ReviewHandshakeService

/**
 * Touching the service is what creates it; start() is what registers its listeners and seeds the
 * editors that are already open. The gutter has to work whether or not the tool window was ever
 * opened, so it cannot be started from there. The handshake service copies that same shape.
 */
class RemarkGutterStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        RemarkGutter.getInstance(project).start()
        ReviewHandshakeService.getInstance(project).start()
    }
}
