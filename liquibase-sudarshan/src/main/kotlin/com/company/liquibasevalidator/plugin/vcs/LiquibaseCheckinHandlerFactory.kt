package com.company.liquibasevalidator.plugin.vcs

import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory

/**
 * Pre-commit check: validates every `.sql` file in the commit before it is created and
 * lets the developer cancel when Liquibase validation errors are present.
 */
class LiquibaseCheckinHandlerFactory : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        object : CheckinHandler() {
            override fun beforeCheckin(): ReturnResult {
                val project = panel.project
                if (!LiquibaseSettings.getInstance(project).state.validateBeforeCommit) {
                    return ReturnResult.COMMIT
                }
                val sqlFiles = panel.virtualFiles.filter { it.extension.equals("sql", ignoreCase = true) }
                if (sqlFiles.isEmpty()) return ReturnResult.COMMIT

                val outcome = CommitPushValidation.runWithModalProgress(project, "Liquibase validation") {
                    CommitPushValidation.validateFiles(project, sqlFiles, it)
                } ?: return ReturnResult.CANCEL // user cancelled validation: do not commit unvalidated
                if (outcome.errors.isEmpty()) return ReturnResult.COMMIT

                val answer = Messages.showYesNoDialog(
                    project,
                    CommitPushValidation.dialogMessage(outcome, "committed"),
                    "Liquibase Sudarshan",
                    "Commit Anyway",
                    "Cancel Commit",
                    Messages.getWarningIcon(),
                )
                return if (answer == Messages.YES) ReturnResult.COMMIT else ReturnResult.CANCEL
            }
        }
}
