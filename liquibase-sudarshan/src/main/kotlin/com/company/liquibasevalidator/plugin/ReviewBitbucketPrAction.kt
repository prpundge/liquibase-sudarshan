package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.bitbucket.BitbucketClient
import com.company.liquibasevalidator.bitbucket.BitbucketPr
import com.company.liquibasevalidator.cli.PatchFilter
import com.company.liquibasevalidator.plugin.vcs.TextLinesUtil
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * Tools | Liquibase Sudarshan | Review Bitbucket PR… — paste a pull-request link
 * (bitbucket.org or Bitbucket Server/Data Center), the plugin fetches the PR's diff,
 * validates ONLY the changed lines of this repository, shows the review in the tool
 * window, and (optionally) posts it to the PR as inline comments plus a summary.
 */
class ReviewBitbucketPrAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = PrDialog(project)
        if (!dialog.showAndGet()) return
        val ref = BitbucketPr.parse(dialog.url()) ?: return
        val token = dialog.token()
        val auth = if (token.isBlank()) null else BitbucketPr.authHeader(dialog.user(), token)
        val post = dialog.post()

        object : Task.Backgroundable(project, "Reviewing Bitbucket PR ${ref.display}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text2 = "Fetching the PR diff..."
                val diff = try {
                    BitbucketClient(auth).getText(ref.diffUrl)
                } catch (ex: Exception) {
                    notify(project, NotificationType.ERROR, "Cannot fetch the PR diff", ex.message ?: "")
                    return
                }
                if (!BitbucketPr.looksLikeUnifiedDiff(diff)) {
                    notify(
                        project, NotificationType.ERROR, "Unexpected diff format",
                        "Bitbucket did not return a unified diff for ${ref.display}.",
                    )
                    return
                }
                val patch = PatchFilter.parse(diff)

                indicator.text2 = "Validating changed lines..."
                val engine = ValidationEngine(LiquibaseSettings.getInstance(project).toOptions())
                val schema = SchemaIndexService.getInstance(project).schemaProvider()
                val items = mutableListOf<ReportItem>()
                val comments = mutableListOf<Triple<String, Int, String>>() // path, line, text
                val files = ReadAction.compute<List<Pair<com.intellij.openapi.vfs.VirtualFile, String>>, RuntimeException> {
                    RepositoryScanner.repositoryFiles(project).files
                        .filter { patch.touches(it.path) }
                        .map { it to String(it.contentsToByteArray(), it.charset) }
                }
                for ((file, text) in files) {
                    val changed = patch.changedLinesFor(file.path) ?: continue
                    for (problem in engine.validate(text, schema).problems) {
                        val line = TextLinesUtil.lineOf(text, problem.range.start)
                        if (line !in changed || problem.severity == Severity.INFO) continue
                        val displayPath = RepositoryScanner.displayPath(project, file)
                        items += ReportItem(file, displayPath, line, problem.range.start, problem.severity, problem.message)
                        val label = if (problem.severity == Severity.ERROR) "error" else "warning"
                        comments += Triple(displayPath, line, "[$label] ${problem.message}")
                    }
                }

                val errors = items.count { it.severity == Severity.ERROR }
                val warnings = items.size - errors
                val summary = BitbucketPr.summaryText(errors, warnings, patch.fileCount)

                var posted = -1
                if (post && auth != null) {
                    indicator.text2 = "Posting the review to Bitbucket..."
                    try {
                        val client = BitbucketClient(auth)
                        client.postJson(ref.commentsUrl, BitbucketPr.summaryCommentJson(ref.kind, summary))
                        val toPost = comments.take(MAX_COMMENTS)
                        for ((path, line, text) in toPost) {
                            client.postJson(ref.commentsUrl, BitbucketPr.inlineCommentJson(ref.kind, path, line, text))
                        }
                        posted = toPost.size
                    } catch (ex: Exception) {
                        notify(project, NotificationType.ERROR, "Posting the review failed", ex.message ?: "")
                    }
                }

                ApplicationManager.getApplication().invokeLater(
                    {
                        ValidationReportService.getInstance(project).setReport(
                            ValidationReport(filesScanned = patch.fileCount, items = items),
                        )
                        ToolWindowManager.getInstance(project)
                            .getToolWindow(LiquibaseReportToolWindowFactory.TOOL_WINDOW_ID)?.activate(null)
                        val postedNote = when {
                            posted >= 0 -> " Posted $posted inline comment(s) + summary to the PR."
                            post && auth == null -> " Not posted: no token provided."
                            else -> " Preview only — not posted to the PR."
                        }
                        notify(
                            project,
                            if (errors > 0) NotificationType.ERROR else NotificationType.INFORMATION,
                            "PR review: ${ref.display}",
                            "$summary$postedNote",
                        )
                    },
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

    private companion object {
        const val MAX_COMMENTS = 25
    }

    private class PrDialog(project: Project) : DialogWrapper(project, false) {
        private val urlField = JTextField("", 44)
        private val userField = JTextField(System.getenv("BITBUCKET_USER").orEmpty(), 20)
        private val tokenField = JBPasswordField().apply { text = System.getenv("BITBUCKET_TOKEN").orEmpty() }
        private val postBox = JBCheckBox("Post the review to the PR (otherwise preview in the tool window only)")

        init {
            title = "Review Bitbucket Pull Request"
            setOKButtonText("Review")
            init()
        }

        override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("Pull-request URL:", urlField)
            .addLabeledComponent("User (Cloud app password):", userField)
            .addLabeledComponent("Token / app password:", tokenField)
            .addComponent(postBox)
            .addComponentToRightColumn(
                JBLabel("bitbucket.org/…/pull-requests/N or https://host/projects/KEY/repos/slug/pull-requests/N").apply {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                },
            )
            .panel

        override fun doValidate(): ValidationInfo? =
            if (BitbucketPr.parse(urlField.text) == null) {
                ValidationInfo("Not a recognizable Bitbucket pull-request URL", urlField)
            } else {
                null
            }

        fun url(): String = urlField.text.trim()
        fun user(): String = userField.text.trim()
        fun token(): String = String(tokenField.password)
        fun post(): Boolean = postBox.isSelected
    }
}
