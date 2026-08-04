package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.plugin.vcs.TextLinesUtil
import com.company.liquibasevalidator.release.ReleaseAssembler
import com.company.liquibasevalidator.release.ReleaseConfigLoader
import com.company.liquibasevalidator.release.ReleaseSimulator
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.FormBuilder
import java.nio.file.Paths
import javax.swing.JComponent

/**
 * Tools | Liquibase Sudarshan | Simulate Release…: choose country + environment, then
 * validate the exact ordered Jenkins run (sequential schema accumulation, cross-file
 * checks, environment policies) and show the manifest + findings in the tool window.
 */
class SimulateReleaseAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return
        val root = Paths.get(basePath)
        val loaded = ReleaseConfigLoader.load(root)
        val countries = ReleaseAssembler(root, loaded.config).availableCountries().ifEmpty { listOf("--") }
        val environments = loaded.config.allEnvironments()

        val state = LiquibaseSettings.getInstance(project).state
        val dialog = ChoiceDialog(project, countries, environments, state.countryCode)
        if (!dialog.showAndGet()) return
        val country = dialog.country()
        val environment = dialog.environment()

        object : Task.Backgroundable(project, "Simulating release $country/$environment", true) {
            override fun run(indicator: ProgressIndicator) {
                val options = LiquibaseSettings.getInstance(project).toOptions()
                val result = ReleaseSimulator(options, loaded.config).simulate(root, country, environment)

                val items = mutableListOf<ReportItem>()
                val manifest = mutableListOf<PreviewItem>()
                val fs = LocalFileSystem.getInstance()
                for (report in result.reports) {
                    val virtualFile = fs.findFileByPath(report.entry.path.toString().replace('\\', '/'))
                        ?: continue
                    val displayPath = RepositoryScanner.displayPath(project, virtualFile)
                    manifest += PreviewItem(
                        file = virtualFile, displayPath = displayPath, line = 1, offset = 0,
                        changesetKey = null,
                        label = "${report.entry.order}. [${report.entry.stage.display}] " +
                            "${report.entry.path.fileName} — ${report.changesetCount} changeset(s)",
                    )
                    for (problem in report.problems) {
                        items += ReportItem(
                            file = virtualFile,
                            displayPath = displayPath,
                            line = TextLinesUtil.lineOf(report.text, problem.range.start),
                            offset = problem.range.start,
                            severity = problem.severity,
                            message = problem.message,
                        )
                    }
                }

                val report = ValidationReport(
                    filesScanned = result.manifest.size,
                    items = items.sortedWith(compareBy({ it.severity }, { it.displayPath }, { it.line })),
                    manifest = manifest,
                )
                ApplicationManager.getApplication().invokeLater(
                    {
                        ValidationReportService.getInstance(project).setReport(report)
                        ToolWindowManager.getInstance(project).getToolWindow("Liquibase Validation")?.activate(null)
                        val verdict = if (report.errorCount == 0) "release would EXECUTE" else "release would FAIL"
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Liquibase Sudarshan")
                            .createNotification(
                                "Release simulation: $country / $environment",
                                "${result.manifest.size} file(s), ${report.errorCount} error(s), " +
                                    "${report.warningCount} warning(s) — $verdict",
                                if (report.errorCount > 0) NotificationType.ERROR else NotificationType.INFORMATION,
                            )
                            .notify(project)
                    },
                    project.disposed,
                )
            }
        }.queue()
    }

    private class ChoiceDialog(
        project: Project,
        countries: List<String>,
        environments: List<String>,
        preselectedCountry: String,
    ) : DialogWrapper(project, false) {

        private val countryBox = ComboBox(countries.toTypedArray()).apply {
            countries.indexOfFirst { it.equals(preselectedCountry, true) }
                .takeIf { it >= 0 }?.let { selectedIndex = it }
        }
        private val environmentBox = ComboBox(environments.toTypedArray())

        init {
            title = "Simulate Release"
            setOKButtonText("Simulate")
            init()
        }

        override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("Country:", countryBox)
            .addLabeledComponent("Environment:", environmentBox)
            .panel

        fun country(): String = countryBox.selectedItem as String
        fun environment(): String = environmentBox.selectedItem as String
    }
}
