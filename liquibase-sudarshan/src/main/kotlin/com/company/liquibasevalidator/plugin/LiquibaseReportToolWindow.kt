package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.validation.Severity
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * "Liquibase Validation" tool window (right stripe, chakra icon): a Validation tab with the
 * findings/preview tree and a Datasource tab with connection status and a live table
 * browser. The stripe icon carries a red/amber/green status dot after each validation run.
 */
class LiquibaseReportToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val reportPanel = ReportPanel(project)
        val datasourcePanel = DatasourcePanel(project)
        val factory = ContentFactory.getInstance()
        toolWindow.contentManager.addContent(factory.createContent(reportPanel, "Validation", false))
        toolWindow.contentManager.addContent(factory.createContent(datasourcePanel, "Datasource", false))

        val service = ValidationReportService.getInstance(project)
        service.addListener { report ->
            // disposal-guarded: a validation finishing during project close must not touch
            // the disposed tool window
            ApplicationManager.getApplication().invokeLater(
                {
                    reportPanel.render(report)
                    toolWindow.setIcon(statusIcon(report))
                },
                project.disposed,
            )
        }
        service.report?.let {
            reportPanel.render(it)
            toolWindow.setIcon(statusIcon(it))
        }
    }

    /** INFO rows (dry-run pending notes) never dirty the stripe: green means clean. */
    private fun statusIcon(report: ValidationReport): Icon = when {
        report.errorCount > 0 -> LiquibaseIcons.ToolWindowError
        report.warningCount > 0 -> LiquibaseIcons.ToolWindowWarning
        else -> LiquibaseIcons.ToolWindowOk
    }

    companion object {
        const val TOOL_WINDOW_ID = "Liquibase Validation"

        /** Programmatic activation, optionally jumping straight to the Datasource tab. */
        fun activate(project: Project, datasourceTab: Boolean = false) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.activate {
                if (datasourceTab) {
                    toolWindow.contentManager.findContent("Datasource")
                        ?.let { toolWindow.contentManager.setSelectedContent(it) }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Validation tab
// -----------------------------------------------------------------------------------------

private class ReportPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val summary = JBLabel("Run 'Validate Liquibase Repository' to produce a report.")
    private val root = DefaultMutableTreeNode("Report")
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    private class ItemNode(val item: ReportItem) : DefaultMutableTreeNode() {
        override fun toString(): String = "${item.displayPath}:${item.line}  ${item.message}"
    }

    private class PreviewNode(val item: PreviewItem) : DefaultMutableTreeNode() {
        override fun toString(): String = "${item.displayPath}:${item.line}  ${item.label}"
    }

    init {
        summary.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: javax.swing.JTree, value: Any?, sel: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                icon = when {
                    value is ItemNode && value.item.severity == Severity.ERROR -> AllIcons.General.Error
                    value is ItemNode && value.item.severity == Severity.WARNING -> AllIcons.General.Warning
                    value is ItemNode -> AllIcons.General.Information
                    value is PreviewNode -> AllIcons.Actions.Preview
                    else -> AllIcons.Nodes.Folder
                }
                return component
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateSelected()
            }
        })
        @Suppress("DEPRECATION") // installOn is not available on the 2023.2 floor
        com.intellij.ui.TreeSpeedSearch(tree) // type-to-find within the findings tree
        TreePopupSupport.install(tree, buildPopupMenu())
        add(summary, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    /** Right-click menu on finding/preview lines. */
    private fun buildPopupMenu(): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Jump to Source", AllIcons.Actions.EditSource).apply {
            addActionListener { navigateSelected() }
        })
        add(JMenuItem("Copy Message", AllIcons.Actions.Copy).apply {
            addActionListener {
                selectedText()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
            }
        })
        addSeparator()
        add(JMenuItem("Validate Repository Again", AllIcons.Actions.Refresh).apply {
            addActionListener { runValidateRepositoryAction() }
        })
        add(JMenuItem("Configure Liquibase Sudarshan…", AllIcons.General.Settings).apply {
            addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Liquibase Sudarshan") }
        })
    }

    private fun navigateSelected() {
        when (val node = tree.lastSelectedPathComponent) {
            is ItemNode -> OpenFileDescriptor(project, node.item.file, node.item.offset).navigate(true)
            is PreviewNode -> OpenFileDescriptor(project, node.item.file, node.item.offset).navigate(true)
        }
    }

    private fun selectedText(): String? = when (val node = tree.lastSelectedPathComponent) {
        is ItemNode -> "${node.item.displayPath}:${node.item.line}  ${node.item.message}"
        is PreviewNode -> "${node.item.displayPath}:${node.item.line}  ${node.item.label}"
        else -> null
    }

    private fun runValidateRepositoryAction() {
        val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val action = manager.getAction("LiquibaseSudarshan.ValidateRepository") ?: return
        manager.tryToExecute(action, null, this, null, true)
    }

    fun render(report: ValidationReport) {
        summary.text = "Files scanned: ${report.filesScanned}    " +
            "Errors: ${report.errorCount}    Warnings: ${report.warningCount}" +
            (if (report.infoCount > 0) "    Info: ${report.infoCount}" else "") +
            (if (report.previews.isNotEmpty()) "    Preview rows: ${report.previews.size}" else "")
        root.removeAllChildren()

        val errors = DefaultMutableTreeNode("Errors (${report.errorCount})")
        val warnings = DefaultMutableTreeNode("Warnings (${report.warningCount})")
        val infos = DefaultMutableTreeNode("Info (${report.infoCount})")
        for (item in report.items) {
            val parent = when (item.severity) {
                Severity.ERROR -> errors
                Severity.INFO -> infos
                else -> warnings
            }
            parent.add(ItemNode(item))
        }
        if (errors.childCount > 0) root.add(errors)
        if (warnings.childCount > 0) root.add(warnings)
        if (infos.childCount > 0) root.add(infos)

        if (report.plan.isNotEmpty()) {
            val planRoot = DefaultMutableTreeNode("Execution plan — dry run (${report.plan.size} changesets)")
            report.plan.forEach { planRoot.add(PreviewNode(it)) }
            root.add(planRoot)
        }

        if (report.previews.isNotEmpty()) {
            val previewRoot = DefaultMutableTreeNode("Data preview — dry run (${report.previews.size} rows)")
            for ((changeset, rows) in report.previews.groupBy { it.changesetKey ?: "(no changeset)" }) {
                val group = DefaultMutableTreeNode("changeset $changeset (${rows.size})")
                rows.forEach { group.add(PreviewNode(it)) }
                previewRoot.add(group)
            }
            root.add(previewRoot)
        }
        model.reload()
        // rowCount grows as rows expand: re-evaluate every iteration for a full expansion
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }
    }
}

/** Installs a popup menu that first SELECTS the row under the right-click, so menu actions
 *  never operate on a stale or absent selection. */
internal object TreePopupSupport {
    fun install(tree: Tree, menu: JPopupMenu) {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: MouseEvent) = maybeShow(e)
            private fun maybeShow(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = tree.getClosestRowForLocation(e.x, e.y)
                if (row >= 0) tree.setSelectionRow(row)
                menu.show(tree, e.x, e.y)
            }
        })
    }
}
