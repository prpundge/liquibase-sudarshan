package com.company.liquibasevalidator.plugin.navigation

import com.company.liquibasevalidator.liquibase.LiquibaseParser
import com.company.liquibasevalidator.plugin.LiquibaseIcons
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Gutter icon on every `--changeset` header: the tooltip summarizes id, contexts, labels
 * and rollback coverage; clicking navigates to the changeset's rollback (or its header
 * when no rollback exists — which the tooltip calls out).
 */
class ChangesetLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        // process each FILE once: pick the element that starts at offset 0
        val fileElement = elements.firstOrNull { it.textRange?.startOffset == 0 } ?: return
        val file = fileElement.containingFile ?: return
        if (!file.name.endsWith(".sql", ignoreCase = true)) return
        val text = file.text
        val liquibase = LiquibaseParser.parse(text)
        if (!liquibase.formatted && liquibase.changesets.isEmpty()) return

        for (changeset in liquibase.changesets) {
            val range = TextRange(
                changeset.headerRange.start,
                minOf(changeset.headerRange.end, text.length),
            )
            if (range.isEmpty) continue
            val leaf = file.findElementAt(range.startOffset) ?: continue
            val hasRollback = changeset.rollbacks.isNotEmpty()
            val tooltip = buildString {
                append("Changeset ").append(changeset.key)
                if (changeset.contexts.isNotEmpty()) append("  |  contexts: ").append(changeset.contexts.joinToString(", "))
                if (changeset.labels.isNotEmpty()) append("  |  labels: ").append(changeset.labels.joinToString(", "))
                if (changeset.runOnChange) append("  |  runOnChange")
                append("  |  rollback: ").append(if (hasRollback) "yes (click to jump)" else "MISSING")
            }
            result.add(
                LineMarkerInfo(
                    leaf,
                    range,
                    LiquibaseIcons.ToolWindow,
                    { tooltip },
                    { _, _ ->
                        val target = changeset.rollbacks.firstOrNull()?.range?.start ?: changeset.headerRange.start
                        file.virtualFile?.let {
                            OpenFileDescriptor(file.project, it, target).navigate(true)
                        }
                    },
                    GutterIconRenderer.Alignment.LEFT,
                    { "Liquibase changeset ${changeset.key}" },
                ),
            )
        }
    }
}
