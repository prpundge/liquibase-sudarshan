package com.company.liquibasevalidator.quickfix

import com.company.liquibasevalidator.sql.SrcRange
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * Replaces an exact text range (e.g. `VARCHAR(50)` → `VARCHAR(15)`). Safe: only rewrites a
 * datatype declaration, never business data.
 */
class ReplaceTextQuickFix(
    private val fixName: String,
    private val range: SrcRange,
    private val newText: String,
) : LocalQuickFix {

    override fun getFamilyName(): String = fixName

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        if (range.start < 0 || range.end > document.textLength || range.start >= range.end) return
        document.replaceString(range.start, range.end, newText)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

/**
 * "Open value for editing": selects the offending value in the editor so the developer can
 * change business data deliberately. Never truncates or modifies data automatically.
 */
class SelectValueQuickFix(
    private val range: SrcRange,
) : LocalQuickFix {

    override fun getFamilyName(): String = "Select value for editing"

    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val virtualFile = descriptor.psiElement?.containingFile?.virtualFile ?: return
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, virtualFile, range.start), true)
                ?: return@invokeLater
            val end = minOf(range.end, editor.document.textLength)
            if (range.start in 0 until end) {
                editor.selectionModel.setSelection(range.start, end)
            }
        }
    }
}
