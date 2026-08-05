package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.database.DatabaseConfig
import com.company.liquibasevalidator.database.DriverMissingException
import com.company.liquibasevalidator.database.JdbcConnector
import com.company.liquibasevalidator.database.LiquibaseDryRun
import com.company.liquibasevalidator.release.ReleaseAssembler
import com.company.liquibasevalidator.release.ReleaseConfigLoader
import com.company.liquibasevalidator.release.ReleaseDryRun
import com.company.liquibasevalidator.settings.DbPasswordStore
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.company.liquibasevalidator.validation.Severity
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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Tools | Liquibase Sudarshan | Release Dry Run… — pick a country, an environment and a
 * server, then see, WITHOUT executing anything: the execution plan (RUN, or SKIP because
 * the changeset is already in that server's DATABASECHANGELOG from a previous release)
 * and a per-column comparison of the data the branch would write vs the data currently
 * in the database (INSERT / UPDATE with diffs / SAME / CONFLICT).
 */
class ReleaseDryRunAction : AnAction() {

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

        val state = LiquibaseSettings.getInstance(project).state
        val dialog = SetupDialog(project, countries, loaded.config.allEnvironments(), state)
        if (!dialog.showAndGet()) return

        val country = dialog.country()
        val environment = dialog.environment()
        val dbConfig = dialog.databaseConfig()

        object : Task.Backgroundable(project, "Release dry run $country/$environment", true) {
            override fun run(indicator: ProgressIndicator) {
                val options = LiquibaseSettings.getInstance(project).toOptions()
                val result = try {
                    ReleaseDryRun(options, loaded.config)
                        .run(root, country, environment, JdbcConnector(dbConfig))
                } catch (missing: DriverMissingException) {
                    notify(
                        project, NotificationType.WARNING, "JDBC driver missing",
                        "${missing.message} — configure or download it under " +
                            "Settings | Tools | Liquibase Sudarshan, or open the Datasource tab.",
                    )
                    return
                } catch (e: Exception) {
                    notify(
                        project, NotificationType.ERROR, "Release dry run failed",
                        e.message ?: e.javaClass.simpleName,
                    )
                    return
                }
                ApplicationManager.getApplication().invokeLater(
                    { ResultsDialog(project, dbConfig.jdbcUrl, result).show() },
                    project.disposed,
                )
            }
        }.queue()
    }

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Liquibase Sudarshan")
            .createNotification(title, content, type)
            .notify(project)
    }

    // ---------------------------------------------------------------------------------------
    // Setup dialog: country + environment + server (prefilled from settings, editable so a
    // different server — e.g. the PROD replica — can be checked without changing settings).
    // ---------------------------------------------------------------------------------------

    private class SetupDialog(
        project: Project,
        countries: List<String>,
        environments: List<String>,
        state: LiquibaseSettings.State,
    ) : DialogWrapper(project, false) {

        private val countryBox = ComboBox(countries.toTypedArray()).apply {
            countries.indexOfFirst { it.equals(state.countryCode, true) }
                .takeIf { it >= 0 }?.let { selectedIndex = it }
        }
        private val environmentBox = ComboBox(environments.toTypedArray())
        private val urlField = JTextField(state.dbUrl, 36)
        private val userField = JTextField(state.dbUser, 20)
        private val passwordField = JBPasswordField().apply {
            text = DbPasswordStore.load(state.dbUrl, state.dbUser)
        }
        private val schemaField = JTextField(state.dbSchema, 20)
        private val driverField = JTextField(state.dbDriverJarPath, 36)

        init {
            title = "Release Dry Run"
            setOKButtonText("Compare")
            init()
        }

        override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("Country:", countryBox)
            .addLabeledComponent("Environment:", environmentBox)
            .addSeparator()
            .addLabeledComponent("JDBC URL:", urlField)
            .addLabeledComponent("User:", userField)
            .addLabeledComponent("Password:", passwordField)
            .addLabeledComponent("Schema/owner:", schemaField)
            .addLabeledComponent("Driver JAR (optional):", driverField)
            .addComponentToRightColumn(
                JBLabel("Read-only: the dry run only ever executes SELECTs.").apply {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                },
            )
            .panel

        override fun doValidate() =
            if (urlField.text.isBlank()) com.intellij.openapi.ui.ValidationInfo("JDBC URL is required", urlField)
            else null

        fun country(): String = countryBox.selectedItem as String
        fun environment(): String = environmentBox.selectedItem as String
        fun databaseConfig() = DatabaseConfig(
            jdbcUrl = urlField.text.trim(),
            user = userField.text.trim(),
            password = String(passwordField.password),
            schemaName = schemaField.text.trim(),
            driverJarPath = driverField.text.trim(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Results window: execution plan + branch-vs-database data comparison + findings.
    // ---------------------------------------------------------------------------------------

    private class ResultsDialog(
        project: Project,
        serverUrl: String,
        private val result: ReleaseDryRun.Result,
    ) : DialogWrapper(project, false) {

        init {
            title = "Release Dry Run — ${result.country}/${result.environment} @ $serverUrl"
            isModal = false
            setOKButtonText("Close")
            init()
        }

        override fun createActions() = arrayOf(okAction)

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
            val changelog = if (result.changelogTableFound) {
                "DATABASECHANGELOG found — ${result.wouldSkip} changeset(s) from previous releases will NOT re-execute."
            } else {
                "DATABASECHANGELOG not found — fresh database, every changeset executes."
            }
            panel.add(
                JBLabel(
                    "<html><b>${result.manifest.size} file(s) · ${result.wouldExecute} changeset(s) would execute · " +
                        "${result.wouldSkip} skipped</b> — $changelog</html>",
                ),
                BorderLayout.NORTH,
            )

            val tabs = JBTabbedPane()
            tabs.addTab("Data comparison (${result.comparisons.size} rows)", comparisonTable())
            tabs.addTab("Execution plan (${result.plan.size})", planTable())
            tabs.addTab("Findings (${result.findings.size})", findingsTable())
            panel.add(tabs, BorderLayout.CENTER)
            panel.preferredSize = JBUI.size(980, 560)
            return panel
        }

        private fun readOnlyModel(columns: Array<String>) = object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        private fun comparisonTable(): JComponent {
            val model = readOnlyModel(
                arrayOf("Table", "Key", "Column", "Branch value", "Database value", "Result"),
            )
            for (row in result.comparisons) {
                if (row.diffs.isEmpty()) {
                    model.addRow(arrayOf(row.tableName, "${row.keyColumn}=${row.keyValue}", "", "", "", label(row)))
                }
                for ((index, diff) in row.diffs.withIndex()) {
                    model.addRow(
                        arrayOf(
                            if (index == 0) row.tableName else "",
                            if (index == 0) "${row.keyColumn}=${row.keyValue}" else "",
                            diff.column,
                            diff.branchValue ?: "",
                            diff.databaseValue ?: (if (row.fate == ReleaseDryRun.RowFate.INSERT) "(no row)" else ""),
                            if (index == 0) label(row) else (if (diff.changed) "≠ changed" else "="),
                        ),
                    )
                }
            }
            val table = JBTable(model)
            table.setDefaultRenderer(Object::class.java, object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    tbl: javax.swing.JTable, value: Any?, selected: Boolean,
                    focus: Boolean, row: Int, column: Int,
                ): Component {
                    val component = super.getTableCellRendererComponent(tbl, value, selected, focus, row, column)
                    if (!selected) {
                        val text = tbl.getValueAt(row, 5)?.toString().orEmpty()
                        foreground = when {
                            text.startsWith("CONFLICT") -> Color(0xC7, 0x22, 0x22)
                            text.startsWith("UPDATE") || text.startsWith("≠") -> Color(0xB5, 0x6A, 0x00)
                            text.startsWith("INSERT") -> Color(0x1E, 0x7D, 0x32)
                            text.startsWith("SKIP") -> Color.GRAY
                            else -> tbl.foreground
                        }
                    }
                    return component
                }
            })
            return JBScrollPane(table)
        }

        private fun label(row: ReleaseDryRun.RowComparison): String = when (row.fate) {
            ReleaseDryRun.RowFate.INSERT -> "INSERT (new row)"
            ReleaseDryRun.RowFate.UPDATE -> "UPDATE (${row.changedColumns} column(s) change)"
            ReleaseDryRun.RowFate.SAME -> "SAME (no change)"
            ReleaseDryRun.RowFate.CONFLICT -> "CONFLICT (key exists — INSERT fails)"
            ReleaseDryRun.RowFate.SKIP -> "SKIP (already released)"
            ReleaseDryRun.RowFate.UNKNOWN -> "UNKNOWN (cannot probe)"
        }

        private fun planTable(): JComponent {
            val model = readOnlyModel(arrayOf("#", "Changeset", "File", "Action", "Reason"))
            for (step in result.plan) {
                model.addRow(arrayOf(step.order, step.key, step.fileId, step.action.name, step.reason))
            }
            return JBScrollPane(JBTable(model))
        }

        private fun findingsTable(): JComponent {
            val model = readOnlyModel(arrayOf("Severity", "File", "Message"))
            for (finding in result.findings.sortedBy { it.problem.severity != Severity.ERROR }) {
                model.addRow(arrayOf(finding.problem.severity.name, finding.fileId, finding.problem.message))
            }
            return JBScrollPane(JBTable(model))
        }
    }
}
