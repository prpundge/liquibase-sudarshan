package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.bitbucket.BitbucketClient
import com.company.liquibasevalidator.bitbucket.BitbucketPr
import com.company.liquibasevalidator.cli.PatchFilter
import com.company.liquibasevalidator.database.DatabaseConfig
import com.company.liquibasevalidator.database.JdbcConnector
import com.company.liquibasevalidator.database.LiquibaseDryRun
import com.company.liquibasevalidator.plugin.vcs.TextLinesUtil
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.settings.BitbucketTokenStore
import com.company.liquibasevalidator.settings.DbPasswordStore
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
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Tools | Liquibase Sudarshan | Review Bitbucket PR… — connected end to end:
 * the PR is auto-detected from the checked-out branch's Bitbucket remote, the token is
 * remembered in the IDE credential store after the first use, the PR's changed lines are
 * validated, and — when a datasource is configured — the review also runs the read-only
 * dry run and reports, per table the PR touches, whether it exists in the connected
 * database and how many rows it holds. Optionally posts everything to the PR.
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
        if (token.isNotBlank()) {
            // "already connected" from now on: next review prefills these
            BitbucketTokenStore.save(hostOf(ref), dialog.user(), token)
        }
        runReview(project, ref, auth, dialog.post())
    }

    private fun hostOf(ref: BitbucketPr.PrRef): String = when (ref.kind) {
        BitbucketPr.Kind.CLOUD -> "bitbucket.org"
        BitbucketPr.Kind.SERVER -> ref.serverBase.substringAfter("://").substringBefore('/')
    }

    // ---------------------------------------------------------------------------------------

    private fun runReview(project: Project, ref: BitbucketPr.PrRef, auth: String?, post: Boolean) {
        object : Task.Backgroundable(project, "Reviewing Bitbucket PR ${ref.display}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.1
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

                indicator.fraction = 0.3
                indicator.text2 = "Validating changed lines..."
                val settings = LiquibaseSettings.getInstance(project)
                val engine = ValidationEngine(settings.toOptions())
                val schema = SchemaIndexService.getInstance(project).schemaProvider()
                val items = mutableListOf<ReportItem>()
                val comments = mutableListOf<Triple<String, Int, String>>() // path, line, text
                data class Analyzed(
                    val file: VirtualFile,
                    val displayPath: String,
                    val text: String,
                    val input: LiquibaseDryRun.FileInput,
                )
                val analyzed = mutableListOf<Analyzed>()
                val files = ReadAction.compute<List<Pair<VirtualFile, String>>, RuntimeException> {
                    RepositoryScanner.repositoryFiles(project).files
                        .filter { patch.touches(it.path) }
                        .map { it to String(it.contentsToByteArray(), it.charset) }
                }
                for ((file, text) in files) {
                    val changed = patch.changedLinesFor(file.path) ?: continue
                    val displayPath = RepositoryScanner.displayPath(project, file)
                    val result = engine.validate(text, schema)
                    analyzed += Analyzed(file, displayPath, text, LiquibaseDryRun.FileInput(file.path, result.analysis))
                    for (problem in result.problems) {
                        val line = TextLinesUtil.lineOf(text, problem.range.start)
                        if (line !in changed || problem.severity == Severity.INFO) continue
                        items += ReportItem(file, displayPath, line, problem.range.start, problem.severity, problem.message)
                        val label = if (problem.severity == Severity.ERROR) "error" else "warning"
                        comments += Triple(displayPath, line, "[$label] ${problem.message}")
                    }
                }

                // Connected datasource: live dry run + per-table presence for the PR's tables.
                val state = settings.state
                if (state.dbValidationEnabled && state.dbUrl.isNotBlank() && analyzed.isNotEmpty()) {
                    indicator.fraction = 0.6
                    indicator.text2 = "Read-only checks against ${state.dbUrl}..."
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
                        val byId = analyzed.associateBy { it.input.fileId }
                        val dryRun = LiquibaseDryRun(connector, settings.toOptions())
                            .run(analyzed.map { it.input }, schema)
                        for (finding in dryRun.findings) {
                            val entry = byId[finding.fileId] ?: continue
                            val line = TextLinesUtil.lineOf(entry.text, finding.problem.range.start)
                            items += ReportItem(
                                entry.file, entry.displayPath, line, finding.problem.range.start,
                                finding.problem.severity, "datasource: ${finding.problem.message}",
                            )
                            if (patch.changedLinesFor(entry.file.path)?.contains(line) == true) {
                                comments += Triple(entry.displayPath, line, "[db] ${finding.problem.message}")
                            }
                        }
                        // the tables this PR writes to, as they exist on the connected server
                        connector.withSession { session ->
                            val live = session.fetchTables()
                            for (entry in analyzed) {
                                val tables = LinkedHashSet<String>()
                                entry.input.analysis.directInserts.forEach { tables += it.tableNameLower }
                                entry.input.analysis.stagingFlows.forEach { flow ->
                                    flow.mappings.forEach { tables += it.targetTableName.lowercase() }
                                }
                                for (table in tables) {
                                    val message = if (table in live) {
                                        val rows = session.rowCount(table)
                                        "datasource: table '$table' is connected — " +
                                            (rows?.let { "$it row(s) in ${state.dbUrl}" } ?: "row count unavailable")
                                    } else {
                                        "datasource: table '$table' does not exist in ${state.dbUrl} yet"
                                    }
                                    items += ReportItem(entry.file, entry.displayPath, 1, 0, Severity.INFO, message)
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        notify(
                            project, NotificationType.WARNING, "Datasource checks skipped",
                            ex.message ?: ex.javaClass.simpleName,
                        )
                    }
                }

                indicator.fraction = 0.85
                val errors = items.count { it.severity == Severity.ERROR }
                val warnings = items.count { it.severity == Severity.WARNING || it.severity == Severity.WEAK_WARNING }
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

    // ---------------------------------------------------------------------------------------
    // Dialog: everything prefilled from the git remote + the IDE credential store.
    // ---------------------------------------------------------------------------------------

    private class PrDialog(private val project: Project) : DialogWrapper(project, false) {

        private val remote: BitbucketPr.RemoteRepo?
        private val branch: String?

        private val urlField = JTextField("", 44)
        private val userField = JTextField("", 20)
        private val tokenField = JBPasswordField()
        private val postBox = JBCheckBox("Post the review to the PR (otherwise preview in the tool window only)")
        private val detectButton = JButton("Detect Open PR")

        init {
            val base = project.basePath?.let { Paths.get(it) }
            remote = base?.resolve(".git/config")
                ?.takeIf { Files.isRegularFile(it) }
                ?.let { BitbucketPr.remoteFromGitConfig(Files.readString(it)) }
            branch = base?.resolve(".git/HEAD")
                ?.takeIf { Files.isRegularFile(it) }
                ?.let { BitbucketPr.branchFromGitHead(Files.readString(it)) }

            // stored connection first, environment as fallback
            val stored = remote?.let { BitbucketTokenStore.load(it.host) }
            userField.text = stored?.first ?: System.getenv("BITBUCKET_USER").orEmpty()
            tokenField.text = stored?.second ?: System.getenv("BITBUCKET_TOKEN").orEmpty()

            title = "Review Bitbucket Pull Request"
            setOKButtonText("Review")
            detectButton.isEnabled = remote != null && branch != null
            detectButton.addActionListener { detectOpenPr() }
            init()
        }

        override fun createCenterPanel(): JComponent {
            val urlRow = JPanel(BorderLayout(JBUI.scale(6), 0))
            urlRow.add(urlField, BorderLayout.CENTER)
            urlRow.add(detectButton, BorderLayout.EAST)

            val state = LiquibaseSettings.getInstance(project).state
            val connectionHint = when {
                remote == null -> "No Bitbucket remote detected in this project's git config."
                branch == null -> "Bitbucket remote ${remote.owner}/${remote.repo} detected (detached HEAD)."
                else -> "Connected: ${remote.owner}/${remote.repo} @ branch '$branch'."
            }
            val datasourceHint = if (state.dbValidationEnabled && state.dbUrl.isNotBlank()) {
                "Datasource: ${state.dbUrl} — the review includes read-only live checks and per-table row counts."
            } else {
                "No datasource configured — static review only (Settings | Tools | Liquibase Sudarshan)."
            }
            return FormBuilder.createFormBuilder()
                .addLabeledComponent("Pull-request URL:", urlRow)
                .addLabeledComponent("User (Cloud app password):", userField)
                .addLabeledComponent("Token / app password:", tokenField)
                .addComponent(postBox)
                .addComponentToRightColumn(hint(connectionHint))
                .addComponentToRightColumn(hint(datasourceHint))
                .panel
        }

        private fun hint(text: String) = JBLabel(text).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        /** Finds the open PR whose source branch is the checked-out branch and fills the URL. */
        private fun detectOpenPr() {
            val repo = remote ?: return
            val currentBranch = branch ?: return
            val token = String(tokenField.password)
            val auth = if (token.isBlank()) null else BitbucketPr.authHeader(userField.text.trim(), token)
            var prId: Long? = null
            var error: String? = null
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    try {
                        val json = BitbucketClient(auth).getText(BitbucketPr.openPrSearchUrl(repo, currentBranch))
                        prId = BitbucketPr.firstOpenPrId(json)
                    } catch (ex: Exception) {
                        error = ex.message ?: ex.javaClass.simpleName
                    }
                },
                "Searching the open PR for '$currentBranch'...", true, project,
            )
            val id = prId
            when {
                error != null -> Messages.showErrorDialog(project, error, "Bitbucket")
                id == null -> Messages.showInfoMessage(
                    project, "No open pull request found for branch '$currentBranch'.", "Bitbucket",
                )
                else -> urlField.text = when (repo.kind) {
                    BitbucketPr.Kind.CLOUD ->
                        "https://bitbucket.org/${repo.owner}/${repo.repo}/pull-requests/$id"
                    BitbucketPr.Kind.SERVER ->
                        "${repo.serverBase}/projects/${repo.owner}/repos/${repo.repo}/pull-requests/$id"
                }
            }
        }

        override fun doValidate(): ValidationInfo? =
            if (BitbucketPr.parse(urlField.text) == null) {
                ValidationInfo("Not a recognizable Bitbucket pull-request URL (use Detect Open PR or paste the link)", urlField)
            } else {
                null
            }

        fun url(): String = urlField.text.trim()
        fun user(): String = userField.text.trim()
        fun token(): String = String(tokenField.password)
        fun post(): Boolean = postBox.isSelected
    }
}
