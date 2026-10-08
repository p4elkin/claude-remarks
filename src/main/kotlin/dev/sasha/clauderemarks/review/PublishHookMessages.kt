package dev.sasha.clauderemarks.review

import com.intellij.notification.NotificationType

/** Platform notification types stay outside the hook's file validation and process runner. */
fun outcomeMessage(outcome: HookOutcome, label: String): Pair<String, NotificationType> = when (outcome) {
    HookOutcome.Queued -> "Queued for $label" to NotificationType.INFORMATION
    HookOutcome.Closed -> "The live review is closed" to NotificationType.WARNING
    HookOutcome.TimedOut -> "The live review hook timed out" to NotificationType.WARNING
    is HookOutcome.Failed -> ("The live review hook failed (exit ${outcome.exitCode})" +
        outcome.stderrTail.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()) to NotificationType.WARNING
    is HookOutcome.NotStarted -> "The live review hook could not start\n${outcome.reason}" to NotificationType.WARNING
}
