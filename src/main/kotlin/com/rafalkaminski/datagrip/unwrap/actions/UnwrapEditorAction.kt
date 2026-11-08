package com.rafalkaminski.datagrip.unwrap.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.rafalkaminski.datagrip.unwrap.unwrap.PlsqlUnwrapper

/**
 * Odwija zawinięty kod PL/SQL w edytorze: w zaznaczeniu, a bez zaznaczenia w całym dokumencie.
 * Wynik zastępuje tekst w jednej komendzie, więc jeden Ctrl+Z cofa całość.
 */
class UnwrapEditorAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val enabled = editor != null &&
            editor.document.isWritable &&
            PlsqlUnwrapper.containsWrapped(targetText(editor))
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project
        val selection = editor.selectionModel
        val start = if (selection.hasSelection()) selection.selectionStart else 0
        val end = if (selection.hasSelection()) selection.selectionEnd else editor.document.textLength
        val original = editor.document.getText(com.intellij.openapi.util.TextRange(start, end))

        val result = try {
            PlsqlUnwrapper.unwrap(original)
        } catch (ex: PlsqlUnwrapper.UnwrapException) {
            notify(project, ex.message ?: "Nie udało się odwinąć kodu.", NotificationType.ERROR)
            return
        }
        if (result.unitsUnwrapped == 0) {
            notify(project, "Nie znaleziono zawiniętego kodu PL/SQL w formacie Oracle 10g lub nowszym.", NotificationType.WARNING)
            return
        }

        WriteCommandAction.runWriteCommandAction(project, "Unwrap PL/SQL", null, Runnable {
            editor.document.replaceString(start, end, result.text)
        })
    }

    private fun targetText(editor: Editor): String {
        val selection = editor.selectionModel
        return if (selection.hasSelection()) selection.selectedText.orEmpty() else editor.document.charsSequence.toString()
    }

    private fun notify(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("PL/SQL Unwrap", message, type)
            .notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "PL/SQL Unwrap"
    }
}
