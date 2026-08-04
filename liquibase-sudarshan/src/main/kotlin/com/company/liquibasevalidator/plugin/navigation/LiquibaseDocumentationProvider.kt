package com.company.liquibasevalidator.plugin.navigation

import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.schema.SchemaOrigin
import com.company.liquibasevalidator.schema.TableSchema
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement

/**
 * Hover / Ctrl+Q documentation for table names in SQL files: shows the resolved schema
 * (columns, types, nullability, keys) as a quick-doc popup — like hovering a class name.
 */
class LiquibaseDocumentationProvider : AbstractDocumentationProvider() {

    /** Anchors the popup to the word under the caret in otherwise-plain text. */
    internal class TableDocElement(
        private val file: PsiFile,
        private val range: TextRange,
        val table: TableSchema,
    ) : FakePsiElement() {
        override fun getParent(): PsiElement = file
        override fun getTextRange(): TextRange = range
        override fun getName(): String = table.name
        override fun getText(): String = table.name
    }

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (!file.name.endsWith(".sql", ignoreCase = true)) return null
        val text = file.text
        val wordRange = SqlWordAt.wordRangeAt(text, targetOffset) ?: return null
        val word = text.substring(wordRange.first, wordRange.last + 1)
        val table = SchemaIndexService.getInstance(file.project).schemaProvider().findTable(word) ?: return null
        return TableDocElement(file, TextRange(wordRange.first, wordRange.last + 1), table)
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val doc = element as? TableDocElement ?: return null
        val table = doc.table
        return "table <b>${table.name}</b> — ${table.columns.size} columns, " +
            "${table.uniqueKeys().size} unique key(s), ${table.foreignKeys().size} foreign key(s)"
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val doc = element as? TableDocElement ?: return null
        val table = doc.table
        val origin = if (table.origin == SchemaOrigin.DATABASE) "live database" else "repository DDL"
        return buildString {
            append("<html><body>")
            append("<b>").append(esc(table.name)).append("</b> <i>(").append(origin).append(")</i>")
            append("<table>")
            append("<tr><td><b>Column</b></td><td><b>Type</b></td><td><b>Attributes</b></td></tr>")
            for (column in table.columns) {
                val flags = buildString {
                    if (!column.nullable) append("NOT NULL ")
                    if (column.primaryKey) append("PK ")
                    if (column.unique) append("UNIQUE ")
                    if (column.hasDefault) append("DEFAULT")
                }
                append("<tr><td><code>").append(esc(column.name)).append("</code>&nbsp;&nbsp;</td>")
                append("<td>").append(esc(column.dataType.display())).append("&nbsp;&nbsp;</td>")
                append("<td>").append(esc(flags.trim())).append("</td></tr>")
            }
            append("</table>")
            val fks = table.foreignKeys()
            if (fks.isNotEmpty()) {
                append("<br/><b>Foreign keys</b><br/>")
                for (fk in fks) {
                    append("&bull; ").append(esc(fk.columns.joinToString(", ")))
                        .append(" &rarr; ").append(esc(fk.refTable ?: "?"))
                    if (fk.refColumns.isNotEmpty()) {
                        append("(").append(esc(fk.refColumns.joinToString(", "))).append(")")
                    }
                    append("<br/>")
                }
            }
            append("</body></html>")
        }
    }

    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)
}
