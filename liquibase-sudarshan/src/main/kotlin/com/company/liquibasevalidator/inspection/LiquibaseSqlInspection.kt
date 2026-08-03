package com.company.liquibasevalidator.inspection

import com.company.liquibasevalidator.quickfix.ReplaceTextQuickFix
import com.company.liquibasevalidator.quickfix.SelectValueQuickFix
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.company.liquibasevalidator.validation.QuickFixData
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Whole-file inspection for Liquibase SQL. Runs on the platform's highlighting passes
 * (debounced, in the background), parses the file once and maps every validation problem
 * onto its exact text range with severity-appropriate highlighting and quick fixes.
 */
class LiquibaseSqlInspection : LocalInspectionTool() {

    private val log = logger<LiquibaseSqlInspection>()

    override fun runForWholeFile(): Boolean = true

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (!file.name.endsWith(".sql", ignoreCase = true)) return null
        val text = file.text ?: return null
        if (text.isBlank() || text.length > MAX_FILE_SIZE) return null

        return try {
            val project = file.project
            val options = LiquibaseSettings.getInstance(project).toOptions()
            val schema = SchemaIndexService.getInstance(project).schemaProvider()
            val result = ValidationEngine(options).validate(text, schema)

            result.problems.mapNotNull { problem ->
                val range = clamp(problem.range.start, problem.range.end, text.length) ?: return@mapNotNull null
                val fixes = problem.fixes.map(::toQuickFix).toTypedArray()
                manager.createProblemDescriptor(
                    file,
                    TextRange(range.first, range.second),
                    problem.message,
                    highlightType(problem.severity),
                    isOnTheFly,
                    *fixes,
                )
            }.toTypedArray()
        } catch (e: Exception) {
            // A validator bug must never break the editor: log and stay silent.
            log.warn("Liquibase validation failed for ${file.name}", e)
            null
        }
    }

    private fun toQuickFix(data: QuickFixData): LocalQuickFix = when (data) {
        is QuickFixData.ReplaceText -> ReplaceTextQuickFix(data.name, data.range, data.newText)
        is QuickFixData.SelectValue -> SelectValueQuickFix(data.range)
    }

    private fun highlightType(severity: Severity): ProblemHighlightType = when (severity) {
        Severity.ERROR -> ProblemHighlightType.GENERIC_ERROR
        Severity.WARNING -> ProblemHighlightType.WARNING
        Severity.WEAK_WARNING, Severity.INFO -> ProblemHighlightType.WEAK_WARNING
    }

    private fun clamp(start: Int, end: Int, length: Int): Pair<Int, Int>? {
        if (length == 0) return null
        val s = start.coerceIn(0, length - 1)
        val e = end.coerceIn(s + 1, length)
        return s to e
    }

    companion object {
        private const val MAX_FILE_SIZE = 2_000_000
    }
}
