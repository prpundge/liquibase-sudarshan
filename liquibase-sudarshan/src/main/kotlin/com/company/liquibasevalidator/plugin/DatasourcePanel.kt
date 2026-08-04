package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.database.DriverMissingException
import com.company.liquibasevalidator.database.IndexInfo
import com.company.liquibasevalidator.database.JdbcDrivers
import com.company.liquibasevalidator.database.SequenceInfo
import com.company.liquibasevalidator.database.TableData
import com.company.liquibasevalidator.schema.ConstraintKind
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * "Datasource" tab: an SQL Developer-style, strictly READ-ONLY database navigator —
 * DATABASECHANGELOG execution entries, tables (columns / constraints / indexes / data
 * preview), sequences and views — plus connection status and driver management.
 * Every query runs on a server-enforced READ ONLY transaction; there is no edit path.
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

    // ---------------------------------------------------------------------------------------
    // Node types
    // ---------------------------------------------------------------------------------------

    private class TableNode(val table: TableSchema, private val rowCount: Long?) : DefaultMutableTreeNode() {
        override fun toString(): String =
            "${table.name}  (${table.columns.size} columns" +
                (rowCount?.let { ", $it row(s)" } ?: "") + ")"
    }

    private class DataNode(val tableName: String) : DefaultMutableTreeNode() {
        override fun toString(): String = "Data — double-click to preview (read-only)"
    }

    private class LeafNode(val text: String, val kind: Kind) : DefaultMutableTreeNode() {
        enum class Kind { COLUMN, CONSTRAINT, INDEX, SEQUENCE, VIEW, INFO }
        override fun toString(): String = text
    }

    private class GroupNode(text: String) : DefaultMutableTreeNode(text)

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
        tree.emptyText.text = "Connect to browse tables, sequences, constraints and data"
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: javax.swing.JTree, value: Any?, sel: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                icon = when {
                    value is TableNode -> AllIcons.Nodes.DataTables
                    value is DataNode -> AllIcons.Actions.Preview
                    value is LeafNode -> when (value.kind) {
                        LeafNode.Kind.COLUMN -> AllIcons.Nodes.DataColumn
                        LeafNode.Kind.CONSTRAINT -> AllIcons.Nodes.Constant
                        LeafNode.Kind.INDEX -> AllIcons.Nodes.Type
                        LeafNode.Kind.SEQUENCE -> AllIcons.Actions.ListChanges
                        LeafNode.Kind.VIEW -> AllIcons.Actions.ShowAsTree
                        LeafNode.Kind.INFO -> AllIcons.General.Information
                    }
                    else -> AllIcons.Nodes.DataSchema
                }
                return component
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                when (val node = tree.lastSelectedPathComponent) {
                    is DataNode -> previewData(node.tableName)
                    is TableNode -> previewData(node.table.name)
                    else -> Unit
                }
            }
        })
        TreePopupSupport.install(
            tree,
            JPopupMenu().apply {
                add(JMenuItem("Preview Data (read-only)", AllIcons.Actions.Preview).apply {
                    addActionListener { selectedTableName()?.let { previewData(it) } }
                })
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

    private fun selectedTableName(): String? = when (val node = tree.lastSelectedPathComponent) {
        is TableNode -> node.table.name
        is DataNode -> node.tableName
        else -> {
            var parent = (node as? DefaultMutableTreeNode)?.parent
            var name: String? = null
            while (parent != null) {
                if (parent is TableNode) { name = parent.table.name; break }
                parent = parent.parent
            }
            name
        }
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Liquibase Sudarshan")
        render()
    }

    // ---------------------------------------------------------------------------------------
    // Connect / render
    // ---------------------------------------------------------------------------------------

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
        val answer = Messages.showYesNoDialog(
            project,
            "${spec.displayName} (${spec.sizeMb} MB) is required to connect but is not installed.\n\n" +
                "Download it from Maven Central now? The file is verified against a pinned " +
                "SHA-256 checksum and stored in ~/.liquibase-sudarshan/drivers.",
            "Liquibase Sudarshan",
            "Download (${spec.sizeMb} MB)",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        object : Task.Backgroundable(project, "Downloading ${spec.displayName}", true) {
            var error: String? = null
            override fun run(indicator: ProgressIndicator) {
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
        val index = SchemaIndexService.getInstance(project)
        val tables = index.currentDatabaseTables()
        when {
            state.dbUrl.isBlank() -> status(Lamp.NONE, Lamp.NONE.fallbackText)
            !state.dbValidationEnabled -> status(Lamp.WARN, "Database validation disabled (enable under Configure…)")
            // a failed connect outranks stale cached tables: stay red until a connect succeeds
            lastError != null -> status(Lamp.ERROR, "Connection failed: $lastError")
            tables == null -> status(Lamp.WARN, "Not connected — press Connect / Test")
            else -> status(
                Lamp.OK,
                "Connected — ${tables.size} table(s), ${index.currentSequences().size} sequence(s), " +
                    "${index.currentViews().size} view(s) — read-only",
            )
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
        val index = SchemaIndexService.getInstance(project)
        if (tables != null) {
            // Execution tracking first: this is what the dry run compares changesets against.
            val executed = index.currentExecutedChangesets()
            val changelogNode = if (executed == null) {
                DefaultMutableTreeNode(
                    "DATABASECHANGELOG: not found — fresh database, every changeset is pending",
                )
            } else {
                DefaultMutableTreeNode("DATABASECHANGELOG: ${executed.size} executed changeset(s)").also { node ->
                    executed.forEach { node.add(LeafNode(it, LeafNode.Kind.INFO)) }
                }
            }
            root.add(changelogNode)

            val rowCounts = index.currentRowCounts()
            val allIndexes = index.currentIndexes()
            val tablesNode = GroupNode("Tables (${tables.size})")
            tables.values.sortedBy { it.name }.forEach { table ->
                tablesNode.add(buildTableNode(table, rowCounts[table.nameLower], allIndexes[table.nameLower]))
            }
            root.add(tablesNode)

            val sequences = index.currentSequences()
            if (sequences.isNotEmpty()) {
                val node = GroupNode("Sequences (${sequences.size})")
                sequences.forEach { node.add(LeafNode(sequenceText(it), LeafNode.Kind.SEQUENCE)) }
                root.add(node)
            }

            val views = index.currentViews()
            if (views.isNotEmpty()) {
                val node = GroupNode("Views (${views.size})")
                views.forEach { node.add(LeafNode(it, LeafNode.Kind.VIEW)) }
                root.add(node)
            }
        }
        model.reload()
    }

    private fun buildTableNode(table: TableSchema, rowCount: Long?, tableIndexes: List<IndexInfo>?): TableNode {
        val node = TableNode(table, rowCount)

        val columns = GroupNode("Columns (${table.columns.size})")
        for (column in table.columns) {
            val flags = buildString {
                if (!column.nullable) append("  NOT NULL")
                if (column.primaryKey) append("  PK")
                if (column.hasDefault) append("  DEFAULT")
            }
            columns.add(LeafNode("${column.name}  ${column.dataType.display()}$flags", LeafNode.Kind.COLUMN))
        }
        node.add(columns)

        if (table.constraints.isNotEmpty()) {
            val constraints = GroupNode("Constraints (${table.constraints.size})")
            for (constraint in table.constraints) {
                val text = buildString {
                    append(constraint.kind.name.replace('_', ' '))
                    constraint.name?.let { append("  ").append(it) }
                    append("  (").append(constraint.columns.joinToString(", ")).append(")")
                    if (constraint.kind == ConstraintKind.FOREIGN_KEY && constraint.refTable != null) {
                        append("  → ").append(constraint.refTable)
                        if (constraint.refColumns.isNotEmpty()) {
                            append("(").append(constraint.refColumns.joinToString(", ")).append(")")
                        }
                    }
                }
                constraints.add(LeafNode(text, LeafNode.Kind.CONSTRAINT))
            }
            node.add(constraints)
        }

        if (!tableIndexes.isNullOrEmpty()) {
            val indexesNode = GroupNode("Indexes (${tableIndexes.size})")
            for (indexInfo in tableIndexes) {
                val text = (if (indexInfo.unique) "UNIQUE  " else "") + "${indexInfo.name}  (${indexInfo.columns})"
                indexesNode.add(LeafNode(text, LeafNode.Kind.INDEX))
            }
            node.add(indexesNode)
        }

        node.add(DataNode(table.name))
        return node
    }

    private fun sequenceText(sequence: SequenceInfo): String = buildString {
        append(sequence.name)
        sequence.incrementBy?.let { append("  increment by ").append(it) }
        sequence.lastNumber?.let { append("  last number ").append(it) }
    }

    // ---------------------------------------------------------------------------------------
    // Read-only data preview (SQL Developer's "Data" tab)
    // ---------------------------------------------------------------------------------------

    private fun previewData(tableName: String) {
        object : Task.Backgroundable(project, "Loading data preview: $tableName", true) {
            var data: TableData? = null
            var error: String? = null
            override fun run(indicator: ProgressIndicator) {
                try {
                    data = SchemaIndexService.getInstance(project).fetchDataPreview(tableName, PREVIEW_ROWS)
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater(
                    {
                        val tableData = data
                        when {
                            error != null ->
                                Messages.showErrorDialog(project, "Preview failed: $error", "Liquibase Sudarshan")
                            tableData == null ->
                                Messages.showInfoMessage(
                                    project,
                                    "No preview available for '$tableName' (not connected or not readable).",
                                    "Liquibase Sudarshan",
                                )
                            else -> DataPreviewDialog(project, tableName, tableData).show()
                        }
                    },
                    project.disposed,
                )
            }
        }.queue()
    }

    private class DataPreviewDialog(
        project: Project,
        tableName: String,
        private val data: TableData,
    ) : DialogWrapper(project, false) {

        init {
            title = "$tableName — first ${data.rows.size} row(s) — READ-ONLY preview"
            setOKButtonText("Close")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val model = object : DefaultTableModel(
                data.rows.map { row -> row.map { it ?: "(null)" }.toTypedArray() }.toTypedArray(),
                data.columnNames.toTypedArray(),
            ) {
                override fun isCellEditable(row: Int, column: Int): Boolean = false // strictly read-only
            }
            val table = JBTable(model).apply {
                autoResizeMode = javax.swing.JTable.AUTO_RESIZE_OFF
                columnModel.columns.asIterator().forEach { it.preferredWidth = 140 }
            }
            return JBScrollPane(table).apply { preferredSize = Dimension(760, 420) }
        }

        override fun createActions() = arrayOf(okAction)
    }

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    private companion object {
        const val PREVIEW_ROWS = 50
    }
}
