package com.company.liquibasevalidator.plugin.vcs

import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.intellij.dvcs.push.PrePushHandler
import com.intellij.dvcs.push.PushInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change

/**
 * Pre-push check: validates the COMMITTED content of every `.sql` file touched by the
 * outgoing commits (not the working tree, which may differ) — including the read-only
 * database dry run when a datasource is configured — and lets the developer abort the push.
 */
class LiquibasePrePushHandler : PrePushHandler {

    override fun getPresentableName(): String = "Liquibase validation"

    /**
     * 2022.3–2023.1 entry point: those builds call `handle(pushDetails, indicator)`
     * without the project parameter (on 2023.2+ it survives as a deprecated overload
     * and the platform calls the 3-parameter variant below instead).
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun handle(pushDetails: List<PushInfo>, indicator: ProgressIndicator): PrePushHandler.Result {
        val project = pushDetails.firstOrNull()?.repository?.project
            ?: return PrePushHandler.Result.OK
        return handle(project, pushDetails, indicator)
    }

    override fun handle(
        project: Project,
        pushDetails: List<PushInfo>,
        indicator: ProgressIndicator,
    ): PrePushHandler.Result {
        if (!LiquibaseSettings.getInstance(project).state.validateBeforePush) {
            return PrePushHandler.Result.OK
        }

        // Committed content per path. Commits are conventionally listed newest first, so the
        // first content seen for a path is the pushed tip version.
        val contents = LinkedHashMap<String, CommitPushValidation.SqlText>()
        for (info in pushDetails) {
            for (commit in info.commits) {
                for (change in commit.changes) {
                    indicator.checkCanceled()
                    committedSql(change)?.let { (path, sql) -> contents.putIfAbsent(path, sql) }
                }
            }
        }
        if (contents.isEmpty()) return PrePushHandler.Result.OK

        indicator.text = "Liquibase Sudarshan: validating ${contents.size} SQL file(s)"
        val outcome = CommitPushValidation.validateTexts(project, contents.values.toList(), indicator)
        if (outcome.errors.isEmpty()) return PrePushHandler.Result.OK

        var answer = Messages.NO
        ApplicationManager.getApplication().invokeAndWait {
            answer = Messages.showYesNoDialog(
                project,
                CommitPushValidation.dialogMessage(outcome, "pushed"),
                "Liquibase Sudarshan",
                "Push Anyway",
                "Cancel Push",
                Messages.getWarningIcon(),
            )
        }
        return if (answer == Messages.YES) PrePushHandler.Result.OK else PrePushHandler.Result.ABORT
    }

    /** The committed after-revision content of a `.sql` change; null for deletions/non-SQL. */
    private fun committedSql(change: Change): Pair<String, CommitPushValidation.SqlText>? {
        val after = change.afterRevision ?: return null // deletion: nothing to validate
        val path = after.file
        if (!path.name.endsWith(".sql", ignoreCase = true)) return null
        val text = try {
            after.content
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: VcsException) {
            null
        } ?: return null
        return path.path to CommitPushValidation.SqlText(path.name, text)
    }
}
