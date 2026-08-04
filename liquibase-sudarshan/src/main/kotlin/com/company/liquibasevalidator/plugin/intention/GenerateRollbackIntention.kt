package com.company.liquibasevalidator.plugin.intention

import com.company.liquibasevalidator.liquibase.Changeset
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.company.liquibasevalidator.validation.InsertValidator
import com.company.liquibasevalidator.validation.ValidationEngine
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Intention (Alt+Enter) on a changeset without a rollback: generates the
 * `--rollback DELETE FROM <target> WHERE <key> IN (...)` comment from the changeset's
 * statically-known inserted key values — the values are read, never modified.
 */
class GenerateRollbackIntention : IntentionAction {

    override fun getText(): String = "Generate rollback from inserted data"
    override fun getFamilyName(): String = "Liquibase Sudarshan"
    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file == null || editor == null || !file.name.endsWith(".sql", ignoreCase = true)) return false
        val plan = computeRollback(project, file.text, editor.caretModel.offset)
        return plan != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file == null || editor == null) return
        val plan = computeRollback(project, file.text, editor.caretModel.offset) ?: return
        editor.document.insertString(plan.insertAt, plan.text)
    }

    private data class RollbackPlan(val insertAt: Int, val text: String)

    private fun computeRollback(project: Project, text: String, caretOffset: Int): RollbackPlan? {
        val schema = SchemaIndexService.getInstance(project).schemaProvider()
        val options = LiquibaseSettings.getInstance(project).toOptions()
        val analysis = ValidationEngine(options).validate(text, schema).analysis

        val changeset = analysis.liquibase.changesets.firstOrNull { changeset ->
            caretOffset >= changeset.headerRange.start && caretOffset <= changeset.bodyRange.end
        } ?: return null
        if (changeset.rollbacks.isNotEmpty()) return null

        val deletions = collectDeletions(analysis, changeset, schema) ?: return null
        val lines = deletions.entries.joinToString("") { (target, keys) ->
            val values = keys.second.joinToString(", ")
            "--rollback DELETE FROM ${target} WHERE ${keys.first} IN ($values);\n"
        }
        if (lines.isEmpty()) return null

        // insert at the end of the changeset body, before the next changeset header
        var insertAt = minOf(changeset.bodyRange.end, text.length)
        if (insertAt > 0 && text.getOrNull(insertAt - 1) != '\n') {
            return RollbackPlan(insertAt, "\n" + lines)
        }
        return RollbackPlan(insertAt, lines)
    }

    /** target table -> (key column, formatted key values) from the changeset's inserts. */
    private fun collectDeletions(
        analysis: com.company.liquibasevalidator.validation.FileAnalysis,
        changeset: Changeset,
        schema: com.company.liquibasevalidator.schema.SchemaProvider,
    ): Map<String, Pair<String, List<String>>>? {
        val result = LinkedHashMap<String, Pair<String, MutableList<String>>>()

        fun inChangeset(offset: Int) =
            offset >= changeset.bodyRange.start && offset < changeset.bodyRange.end

        // staged flows: keys of the MERGE target reachable through the mapping
        for (flow in analysis.stagingFlows) {
            for (mapping in flow.mappings) {
                val target = mapping.targetSchema ?: continue
                val targetKey = target.uniqueKeys().firstOrNull { it.size == 1 }?.first() ?: continue
                val inverse = mapping.columnMap.entries
                    .flatMap { (staging, targets) -> targets.map { it to staging } }
                    .toMap()
                val stagingKey = inverse[targetKey] ?: continue
                val tempColumns = flow.tempTable.columns.map { it.nameLower }
                for (insert in flow.inserts.filter { inChangeset(it.range.start) }) {
                    val resolved = insert.columns?.map { it.nameLower } ?: tempColumns
                    for (row in insert.rows) {
                        val value = InsertValidator.staticRowValues(resolved, row.values)[stagingKey] ?: continue
                        result.getOrPut(target.name) { targetKey to mutableListOf() }
                            .second.add(quote(value.first))
                    }
                }
            }
        }

        // direct inserts into schema tables: use the table's own single-column unique key
        for (insert in analysis.directInserts.filter { inChangeset(it.range.start) }) {
            val table = schema.findTable(insert.tableName) ?: continue
            val key = table.uniqueKeys().firstOrNull { it.size == 1 }?.first() ?: continue
            val resolved = insert.columns?.map { it.nameLower } ?: table.columns.map { it.nameLower }
            for (row in insert.rows) {
                val value = InsertValidator.staticRowValues(resolved, row.values)[key] ?: continue
                result.getOrPut(table.name) { key to mutableListOf() }
                    .second.add(quote(value.first))
            }
        }

        val cleaned = result.filterValues { it.second.isNotEmpty() }
            .mapValues { (_, v) -> v.first to v.second.distinct() }
        return cleaned.ifEmpty { null }
    }

    private fun quote(value: String): String =
        if (value.toBigDecimalOrNull() != null) value else "'${value.replace("'", "''")}'"
}
