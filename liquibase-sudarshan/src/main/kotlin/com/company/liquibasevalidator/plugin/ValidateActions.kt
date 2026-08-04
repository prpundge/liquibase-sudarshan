package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.database.DatabaseConfig
import com.company.liquibasevalidator.database.JdbcConnector
import com.company.liquibasevalidator.database.LiquibaseDryRun
import com.company.liquibasevalidator.plugin.vcs.TextLinesUtil
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.settings.DbPasswordStore
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/** Shared implementation for both validate actions. */
internal object ValidationRunner {

    private val log = logger<ValidationRunner>()

    fun run(project: Project, files: List<VirtualFile>, title: String) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val settings = LiquibaseSettings.getInstance(project)
                val options = settings.toOptions()
                val schema = SchemaIndexService.getInstance(project).schemaProvider()
                val engine = ValidationEngine(options)

                val items = mutableListOf<ReportItem>()
                data class FileData(val file: VirtualFile, val displayPath: String, val text: String)
                val fileData = HashMap<String, FileData>()
                val dryRunInputs = mutableListOf<LiquibaseDryRun.FileInput>()

                files.forEachIndexed { index, file ->
                    indicator.checkCanceled()
                    indicator.fraction = index.toDouble() / files.size
                    indicator.text2 = file.name
                    try {
                        val text = ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(file) }
                        val displayPath = RepositoryScanner.displayPath(project, file)
                        fileData[file.path] = FileData(file, displayPath, text)
                        val result = engine.validate(text, schema)
                        dryRunInputs += LiquibaseDryRun.FileInput(file.path, result.analysis)
                        result.problems.forEach { problem ->
                            items += ReportItem(
                                file = file,
                                displayPath = displayPath,
                                line = TextLinesUtil.lineOf(text, problem.range.start),
                                offset = problem.range.start,
                                severity = problem.severity,
                                message = problem.message,
                            )
                        }
                    } catch (e: Exception) {
                        log.warn("Validation failed for ${file.path}", e)
                    }
                }

                var pendingCount = 0
                var dryRunNote: String? = null
                val previewItems = mutableListOf<PreviewItem>()
                val planItems = mutableListOf<PreviewItem>()
                val state = settings.state
                if (state.dbValidationEnabled && state.dbUrl.isNotBlank() && dryRunInputs.isNotEmpty()) {
                    indicator.text2 = "Database dry run..."
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
                        pendingCount = dryRun.pending.size
                        for (finding in dryRun.findings) {
                            val data = fileData[finding.fileId] ?: continue
                            items += ReportItem(
                                file = data.file,
                                displayPath = data.displayPath,
                                line = TextLinesUtil.lineOf(data.text, finding.problem.range.start),
                                offset = finding.problem.range.start,
                                severity = finding.problem.severity,
                                message = finding.problem.message,
                            )
                        }
                        for (pending in dryRun.pending) {
                            val data = fileData[pending.fileId] ?: continue
                            items += ReportItem(
                                file = data.file,
                                displayPath = data.displayPath,
                                line = TextLinesUtil.lineOf(data.text, pending.headerRange.start),
                                offset = pending.headerRange.start,
                                severity = Severity.INFO,
                                message = "Dry run: changeset '${pending.key}' is pending (${pending.reason})",
                            )
                        }
                        for (row in dryRun.preview) {
                            val data = fileData[row.fileId] ?: continue
                            previewItems += PreviewItem(
                                file = data.file,
                                displayPath = data.displayPath,
                                line = TextLinesUtil.lineOf(data.text, row.range.start),
                                offset = row.range.start,
                                changesetKey = row.changesetKey,
                                label = "${row.action} ${row.tableName} ${row.keyColumn}='${row.keyValue}'",
                            )
                        }
                        for (step in dryRun.plan) {
                            val data = fileData[step.fileId] ?: continue
                            planItems += PreviewItem(
                                file = data.file,
                                displayPath = data.displayPath,
                                line = TextLinesUtil.lineOf(data.text, step.headerRange.start),
                                offset = step.headerRange.start,
                                changesetKey = step.key,
                                label = "${step.order}. ${step.action} '${step.key}' " +
                                    "(${step.statementCount} statement(s)) — ${step.reason}",
                            )
                        }
                    } catch (e: Exception) {
                        log.warn("Database dry run failed", e)
                        dryRunNote = "Database dry run could not connect: ${e.message}"
                    }
                }

                val report = ValidationReport(
                    files.size,
                    items.sortedWith(compareBy({ it.severity }, { it.displayPath }, { it.line })),
                    previewItems,
                    planItems,
                )
                val summary = buildString {
                    append("Files scanned: ${report.filesScanned}, errors: ${report.errorCount}, ")
                    append("warnings: ${report.warningCount}")
                    if (pendingCount > 0) append(", pending changesets: $pendingCount")
                    if (previewItems.isNotEmpty()) append(", preview rows: ${previewItems.size}")
                    dryRunNote?.let { append("\n").append(it) }
                }
                ApplicationManager.getApplication().invokeLater {
                    ValidationReportService.getInstance(project).setReport(report)
                    ToolWindowManager.getInstance(project).getToolWindow("Liquibase Validation")?.activate(null)
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Liquibase Sudarshan")
                        .createNotification(
                            "Liquibase validation finished",
                            summary,
                            if (report.errorCount > 0) NotificationType.ERROR else NotificationType.INFORMATION,
                        )
                        .addAction(
                            com.intellij.notification.NotificationAction.createSimple("Show Report") {
                                ToolWindowManager.getInstance(project)
                                    .getToolWindow("Liquibase Validation")?.activate(null)
                            },
                        )
                        .notify(project)
                }
            }
        }.queue()
    }
}

/** Right-click a file: "Validate Liquibase SQL" (directories validate recursively). */
class ValidateLiquibaseFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null &&
            (file.isDirectory || file.extension.equals("sql", ignoreCase = true))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val files = if (file.isDirectory) RepositoryScanner.filesUnder(file) else listOf(file)
        ValidationRunner.run(project, files, "Validating Liquibase SQL")
    }
}

/**
 * "Dry Run Against Database" on a single SQL file: validates it and simulates its execution
 * against the configured datasource (execution plan, data preview, live preconditions) —
 * the SQL Developer-style what-would-happen view, without executing anything.
 */
class DryRunFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null &&
            !file.isDirectory && file.extension.equals("sql", ignoreCase = true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val state = LiquibaseSettings.getInstance(project).state
        if (!state.dbValidationEnabled || state.dbUrl.isBlank()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Liquibase Sudarshan")
                .createNotification(
                    "Liquibase Sudarshan",
                    "No datasource configured — enable database validation and set the JDBC URL " +
                        "under Settings | Tools | Liquibase Sudarshan.",
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }
        ValidationRunner.run(project, listOf(file), "Dry run against database: ${file.name}")
    }
}

/** Validates the whole configured repository (global DDL + datasets + country datasets). */
class ValidateLiquibaseRepositoryAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = RepositoryScanner.repositoryFiles(project)
        if (!target.configuredRootsFound) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Liquibase Sudarshan")
                .createNotification(
                    "Liquibase Sudarshan",
                    "No repository directories found. Configure them under Settings | Tools | Liquibase Sudarshan.",
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }
        ValidationRunner.run(project, target.files, "Validating Liquibase repository")
    }
}
