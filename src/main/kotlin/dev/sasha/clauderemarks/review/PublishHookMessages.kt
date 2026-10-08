package dev.sasha.clauderemarks.review

import com.intellij.notification.NotificationType
import com.intellij.openapi.util.text.StringUtil

private fun hookHtml(text: String): String = text.lines().joinToString("<br>") { StringUtil.escapeXmlEntities(it) }

/** Platform notification types stay outside the hook's file validation and process runner. */
fun outcomeMessage(outcome: HookOutcome, label: String): Pair<String, NotificationType> = when (outcome) {
    HookOutcome.Queued -> "Queued for ${hookHtml(label)}" to NotificationType.INFORMATION
    HookOutcome.Closed -> "The live review is closed" to NotificationType.WARNING
    HookOutcome.TimedOut -> "The live review hook timed out" to NotificationType.WARNING
    is HookOutcome.Failed -> ("The live review hook failed (exit ${outcome.exitCode})" +
        outcome.stderrTail.takeIf { it.isNotEmpty() }?.let { "<br>${hookHtml(it)}" }.orEmpty()) to NotificationType.WARNING
    is HookOutcome.NotStarted -> "The live review hook could not start<br>${hookHtml(outcome.reason)}" to NotificationType.WARNING
}
