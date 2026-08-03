package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.database.DriverMissingException
import com.company.liquibasevalidator.database.JdbcDrivers
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * "Datasource" tab: connection status lamp (red/amber/green), the configured JDBC
 * connection at a glance, Connect/Test + Refresh + Configure actions, and a live table
 * browser of the connected database (read-only metadata, like a lightweight DB navigator).
 */
internal class DatasourcePanel(private val project: Project) : JPanel(BorderLayout()) {

    private enum class Lamp(val color: Color, val fallbackText: String) {
        NONE(JBColor.GRAY, "No datasource configured"),
        WARN(JBColor(Color(0xF2C55C), Color(0xF2C55C)), "Not connected"),
        OK(JBColor(Color(0x59A869), Color(0x59A869)), "Connected"),
        ERROR(JBColor(Color(0xDB5C5C), Color(0xDB5C5C)), "Connection failed"),
    }

    private val statusLabel = JBLabel()
    private val infoLabel = JBLabel()
    private val root = DefaultMutableTreeNode("Database")
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    /** Last connection failure; keeps the lamp red until the next successful connect. */
    private var lastError: String? = null

    private class TableNode(val table: TableSchema, private val rowCount: Long?) : DefaultMutableTreeNode() {
        override fun toString(): String =
            "${table.name}  (${table.columns.size} columns" +
                (rowCount?.let { ", $it row(s)" } ?: "") + ")"
    }

    private class ColumnNode(val text: String) : DefaultMutableTreeNode() {
        override fun toString(): String = text
    }

    init {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 10, 4, 10)
        }
        statusLabel.border = JBUI.Borders.emptyBottom(4)
        infoLabel.border = JBUI.Borders.emptyBottom(4)
        header.add(statusLabel)
        header.add(infoLabel)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JButton("Connect / Test", AllIcons.Actions.Execute).apply { addActionListener { connect() } })
            add(JButton("Refresh Metadata", AllIcons.Actions.Refresh).apply { addActionListener { connect() } })
            add(JButton("Configure…", AllIcons.General.Settings).apply { addActionListener { openSettings() } })
        }
        header.add(buttons)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = "Connect to browse the database tables"
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: javax.swing.JTree, value: Any?, sel: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                icon = when (value) {
                    is TableNode -> AllIcons.Nodes.DataTables
                    is ColumnNode -> AllIcons.Nodes.DataColumn
                    else -> AllIcons.Nodes.DataSchema
                }
                return component
            }
        }
        TreePopupSupport.install(
            tree,
            JPopupMenu().apply {
                add(JMenuItem("Refresh Metadata", AllIcons.Actions.Refresh).apply { addActionListener { connect() } })
                add(JMenuItem("Configure Datasource…", AllIcons.General.Settings).apply { addActionListener { openSettings() } })
            },
        )

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        // settings may change while this tab is hidden: re-render whenever it becomes visible
        addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                render()
            }
        }
        render()
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Liquibase Sudarshan")
        render()
    }

    /** Read-only metadata fetch in the background; the lamp goes amber while connecting. */
    private fun connect() {
        val state = LiquibaseSettings.getInstance(project).state
        when {
            state.dbUrl.isBlank() -> {
                status(Lamp.NONE, "No datasource configured — use Configure… (Settings | Tools | Liquibase Sudarshan)")
                return
            }
            !state.dbValidationEnabled -> {
                status(Lamp.WARN, "Database validation is disabled — enable it under Configure…")
                return
            }
        }
        status(Lamp.WARN, "Connecting…")
        SchemaIndexService.getInstance(project).refreshDatabaseMetadata { result ->
            ApplicationManager.getApplication().invokeLater(
                {
                    result.fold(
                        onSuccess = {
                            lastError = null
                            render()
                        },
                        onFailure = { e ->
                            val missing = e as? DriverMissingException
                            if (missing != null) {
                                offerDriverDownload(missing.spec)
                            } else {
                                lastError = e.message ?: e.javaClass.simpleName
                                render()
                            }
                        },
                    )
                },
                project.disposed,
            )
        }
    }

    /** Drivers are not bundled (keeps the plugin ~20x smaller): ask once, download, retry. */
    private fun offerDriverDownload(spec: JdbcDrivers.DriverSpec) {
        lastError = "${spec.displayName} not downloaded yet"
        render()
        val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            "${spec.displayName} (${spec.sizeMb} MB) is required to connect but is not installed.\n\n" +
                "Download it from Maven Central now? The file is verified against a pinned " +
                "SHA-256 checksum and stored in ~/.liquibase-sudarshan/drivers.",
            "Liquibase Sudarshan",
            "Download (${spec.sizeMb} MB)",
            "Cancel",
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        )
        if (answer != com.intellij.openapi.ui.Messages.YES) return
        object : com.intellij.openapi.progress.Task.Backgroundable(project, "Downloading ${spec.displayName}", true) {
            var error: String? = null
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                try {
                    JdbcDrivers.download(spec) { read, total ->
                        indicator.checkCanceled()
                        indicator.fraction = read.toDouble() / total
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (error != null) {
                            lastError = "Driver download failed: $error"
                            render()
                        } else {
                            connect() // retry with the freshly installed driver
                        }
                    },
                    project.disposed,
                )
            }
        }.queue()
    }

    fun render() {
        val state = LiquibaseSettings.getInstance(project).state
        val tables = SchemaIndexService.getInstance(project).currentDatabaseTables()
        when {
            state.dbUrl.isBlank() -> status(Lamp.NONE, Lamp.NONE.fallbackText)
            !state.dbValidationEnabled -> status(Lamp.WARN, "Database validation disabled (enable under Configure…)")
            // a failed connect outranks stale cached tables: stay red until a connect succeeds
            lastError != null -> status(Lamp.ERROR, "Connection failed: $lastError")
            tables == null -> status(Lamp.WARN, "Not connected — press Connect / Test")
            else -> status(Lamp.OK, "Connected — ${tables.size} table(s) visible")
        }
        infoLabel.text = "<html>URL: <b>${escape(state.dbUrl.ifBlank { "—" })}</b><br>" +
            "User: <b>${escape(state.dbUser.ifBlank { "—" })}</b>&nbsp;&nbsp;" +
            "Schema: <b>${escape(state.dbSchema.ifBlank { "(default)" })}</b></html>"
        rebuildTree(tables)
    }

    private fun status(lamp: Lamp, text: String) {
        statusLabel.icon = ColorIcon(10, lamp.color)
        statusLabel.text = text
    }

    private fun rebuildTree(tables: Map<String, TableSchema>?) {
        root.removeAllChildren()
        if (tables != null) {
            // Execution tracking first: this is what the dry run compares changesets against.
            val executed = SchemaIndexService.getInstance(project).currentExecutedChangesets()
            val changelogNode = if (executed == null) {
                DefaultMutableTreeNode(
                    "DATABASECHANGELOG: not found — fresh database, every changeset is pending",
                )
            } else {
                DefaultMutableTreeNode("DATABASECHANGELOG: ${executed.size} executed changeset(s)").also { node ->
                    executed.forEach { node.add(ColumnNode(it)) }
                }
            }
            root.add(changelogNode)
        }
        val rowCounts = SchemaIndexService.getInstance(project).currentRowCounts()
        tables?.values?.sortedBy { it.name }?.forEach { table ->
            val node = TableNode(table, rowCounts[table.nameLower])
            for (column in table.columns) {
                val flags = buildString {
                    if (!column.nullable) append("  NOT NULL")
                    if (column.primaryKey) append("  PK")
                }
                node.add(ColumnNode("${column.name}  ${column.dataType.display()}$flags"))
            }
            root.add(node)
        }
        model.reload()
    }

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}
