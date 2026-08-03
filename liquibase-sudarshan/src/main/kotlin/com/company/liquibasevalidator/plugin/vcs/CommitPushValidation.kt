package com.company.liquibasevalidator.plugin.vcs

import com.company.liquibasevalidator.database.DatabaseConfig
import com.company.liquibasevalidator.database.JdbcConnector
import com.company.liquibasevalidator.database.LiquibaseDryRun
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.settings.DbPasswordStore
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Shared logic of the pre-commit and pre-push checks: validate the given SQL contents
 * (statically, plus the read-only database dry run when a datasource is configured) and
 * return ERROR-level findings as display lines.
 */
internal object CommitPushValidation {

    private val log = logger<CommitPushValidation>()

    /** One SQL payload to validate: pre-commit passes files, pre-push passes committed content. */
    data class SqlText(val displayName: String, val text: String)

    data class Outcome(val errors: List<String>, val dryRunNotes: List<String>)

    fun validateFiles(project: Project, files: List<VirtualFile>, indicator: ProgressIndicator?): Outcome =
        validateTexts(
            project,
            files.mapNotNull { file ->
                try {
                    SqlText(file.name, ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(file) })
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Cannot read ${file.path}", e)
                    null
                }
            },
            indicator,
        )

    fun validateTexts(project: Project, inputs: List<SqlText>, indicator: ProgressIndicator?): Outcome {
        val settings = LiquibaseSettings.getInstance(project)
        val options = settings.toOptions()
        val schema = SchemaIndexService.getInstance(project).schemaProvider()
        val engine = ValidationEngine(options)

        val errors = mutableListOf<String>()
        val dryRunInputs = mutableListOf<LiquibaseDryRun.FileInput>()

        inputs.forEachIndexed { index, input ->
            indicator?.checkCanceled()
            indicator?.text2 = input.displayName
            indicator?.fraction = index.toDouble() / inputs.size
            try {
                val result = engine.validate(input.text, schema)
                result.problems
                    .filter { it.severity == Severity.ERROR }
                    .forEach {
                        errors += "${input.displayName}:${TextLinesUtil.lineOf(input.text, it.range.start)}  ${it.message}"
                    }
                dryRunInputs += LiquibaseDryRun.FileInput(input.displayName, result.analysis)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("Liquibase validation failed for ${input.displayName}", e)
            }
        }

        val notes = mutableListOf<String>()
        val state = settings.state
        if (state.dbValidationEnabled && state.dbUrl.isNotBlank() && dryRunInputs.isNotEmpty()) {
            indicator?.text2 = "Database dry run..."
            try {
                val connector = JdbcConnector(
                    DatabaseConfig(
                        jdbcUrl = state.dbUrl,
                        user = state.dbUser,
                        password = DbPasswordStore.load(state.dbUrl, state.dbUser),
                        schemaName = state.dbSchema,
                        driverJarPath = state.dbDriverJarPath,
                    ),
                )
                val dryRun = LiquibaseDryRun(connector, options).run(dryRunInputs, schema)
                dryRun.findings
                    .filter { it.problem.severity == Severity.ERROR }
                    .forEach { errors += "${it.fileId}  ${it.problem.message}" }
                if (dryRun.pending.isNotEmpty()) {
                    notes += "Dry run: ${dryRun.pending.size} changeset(s) pending on the configured database"
                }
                if (dryRun.preview.isNotEmpty()) {
                    val inserts = dryRun.preview.count { it.action == LiquibaseDryRun.RowAction.INSERT }
                    val updates = dryRun.preview.count { it.action == LiquibaseDryRun.RowAction.UPDATE }
                    val conflicts = dryRun.preview.count { it.action == LiquibaseDryRun.RowAction.CONFLICT }
                    notes += buildString {
                        append("Dry run preview: $inserts row(s) would INSERT, $updates would UPDATE")
                        if (conflicts > 0) append(", $conflicts would CONFLICT")
                    }
                }
                notes += dryRun.notes
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("Database dry run failed", e)
                notes += "Database dry run could not connect: ${e.message}"
            }
        }
        return Outcome(errors, notes)
    }

    fun dialogMessage(outcome: Outcome, actionWord: String): String {
        val preview = outcome.errors.take(10).joinToString("\n")
        val more = if (outcome.errors.size > 10) "\n… and ${outcome.errors.size - 10} more" else ""
        val notes = if (outcome.dryRunNotes.isEmpty()) "" else "\n\n" + outcome.dryRunNotes.joinToString("\n")
        return "Liquibase validation found ${outcome.errors.size} error(s) in the SQL files being " +
            "$actionWord:\n\n$preview$more$notes"
    }

    /**
     * Runs [block] under a modal progress from the EDT (used by the commit flow).
     * Returns null when the user cancelled — the caller must NOT treat that as "validated".
     */
    fun runWithModalProgress(project: Project, title: String, block: (ProgressIndicator) -> Outcome): Outcome? {
        var outcome: Outcome? = null
        val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { outcome = block(ProgressManager.getInstance().progressIndicator!!) },
            title, true, project,
        )
        return if (completed) outcome else null
    }
}

/** 1-based line of an absolute text offset. */
internal object TextLinesUtil {
    fun lineOf(text: String, offset: Int): Int {
        var line = 1
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }
}
