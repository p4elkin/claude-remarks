package dev.sasha.clauderemarks.ui

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.text.JTextComponent

/**
 * One keystroke in the remark box that inserts a class name from the project.
 *
 * Not a completion popup inside the text area, and not an EditorTextField. See the note at the top
 * of task 12 in docs/plans/20260803-claude-remarks-phase5.md for what that would have cost: the
 * Enter and Shift+Enter bindings, a fight over which of two nested popups owns Escape, and an
 * IdeaVim interaction nobody has tested.
 */

/**
 * Every class name the project knows about, or an empty list.
 *
 * CLASS_EP_NAME is the language-neutral source behind Ctrl+N. Its extension point,
 * com.intellij.gotoClassContributor, is declared in LangExtensionPoints.xml, which is included only
 * by PlatformLangPlugin.xml — the same descriptor that declares com.intellij.modules.platform. So
 * the point is registered wherever this plugin can load, and no dependency on
 * com.intellij.modules.lang is needed. The task 12 note in the phase 5 plan has the full chain.
 *
 * The catch stays anyway, for the case that reading the sources cannot rule out: an IDE that ships
 * its own descriptor instead of IDEA CORE. There the keystroke does nothing rather than throwing.
 *
 * Must run inside a read action, off the EDT. getNames walks indexes and returns tens of thousands
 * of strings on a large project.
 */
fun projectClassNames(project: Project): List<String> =
    runCatching {
        ChooseByNameContributor.CLASS_EP_NAME.extensionList
            .flatMap { runCatching { it.getNames(project, false).toList() }.getOrDefault(emptyList()) }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

/**
 * replaceSelection, not insert(text, caretPosition): insert ignores a selection, so choosing a name
 * with text selected would keep the old text and put the new one beside it. The same reason the
 * Shift+Enter action uses replaceSelection.
 */
fun insertAtCaret(target: JTextComponent, name: String) {
    target.replaceSelection(name)
}

/** EDT. Reads the names off the EDT, then opens the chooser. */
fun chooseClassName(project: Project, target: JTextComponent) {
    ReadAction.nonBlocking<List<String>> { projectClassNames(project) }
        .expireWith(project)
        .coalesceBy(::chooseClassName, project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { names ->
            if (names.isEmpty()) return@finishOnUiThread
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(names)
                .setTitle("Insert Class Name")
                .setNamerForFiltering { it }
                .setItemChosenCallback { insertAtCaret(target, it) }
                .createPopup()
                .showInCenterOf(target)
        }
        .submit(AppExecutorUtil.getAppExecutorService())
}
