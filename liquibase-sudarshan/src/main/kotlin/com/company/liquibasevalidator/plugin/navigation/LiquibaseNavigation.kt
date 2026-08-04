package com.company.liquibasevalidator.plugin.navigation

import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.schema.SchemaOrigin
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.CreateTableStatement
import com.company.liquibasevalidator.sql.SqlParser
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/** Identifier-word extraction shared by navigation and hover documentation. */
internal object SqlWordAt {

    fun wordRangeAt(text: String, offset: Int): IntRange? {
        if (offset !in text.indices) return null
        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '#'
        var start = offset
        if (!isWordChar(text[start])) {
            if (start > 0 && isWordChar(text[start - 1])) start-- else return null
        }
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = offset
        while (end < text.length && isWordChar(text[end])) end++
        return if (start < end) start until end else null
    }

    fun wordAt(text: String, offset: Int): String? =
        wordRangeAt(text, offset)?.let { text.substring(it.first, it.last + 1) }

    /** Resolves a table name to its definition: same-file temp/local table, or the DDL file. */
    fun resolveTable(file: PsiFile, word: String): Pair<PsiFile, Int>? {
        val lower = word.lowercase()

        // 1) tables created in this very file (temp/staging or local DDL)
        val local = SqlParser.parse(file.text).statementsOf<CreateTableStatement>()
            .firstOrNull { it.tableNameLower == lower }
        if (local != null) return file to local.tableNameRange.start

        // 2) repository DDL via the schema index (origin carries the VFS url + offset)
        val table = SchemaIndexService.getInstance(file.project).schemaProvider().findTable(lower) ?: return null
        return resolveOrigin(file, table)
    }

    fun resolveOrigin(context: PsiFile, table: TableSchema): Pair<PsiFile, Int>? {
        if (table.origin == SchemaOrigin.DATABASE) return null
        val virtualFile: VirtualFile =
            VirtualFileManager.getInstance().findFileByUrl(table.origin.fileId) ?: return null
        val psi = PsiManager.getInstance(context.project).findFile(virtualFile) ?: return null
        return psi to table.origin.offset
    }
}

/** Ctrl+Click on a table name jumps to its CREATE TABLE (same file or repository DDL). */
class LiquibaseGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        if (!file.name.endsWith(".sql", ignoreCase = true)) return null
        val word = SqlWordAt.wordAt(file.text, offset) ?: return null

        val (targetFile, targetOffset) = SqlWordAt.resolveTable(file, word) ?: return null
        // clicking the definition itself must not "navigate" to itself
        if (targetFile == file && SqlWordAt.wordRangeAt(file.text, offset)?.first == targetOffset) return null
        val target = targetFile.findElementAt(targetOffset) ?: targetFile
        return arrayOf(target)
    }
}

/**
 * Navigate | Related Symbol on a country dataset file offers: the same file in the other
 * countries, the global variant, and the DDL files of every table the file references.
 */
class LiquibaseGotoRelatedProvider : GotoRelatedProvider() {

    override fun getItems(context: DataContext): List<GotoRelatedItem> {
        val file = com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE.getData(context) ?: return emptyList()
        if (!file.name.endsWith(".sql", ignoreCase = true)) return emptyList()
        val virtualFile = file.virtualFile ?: return emptyList()
        val psiManager = PsiManager.getInstance(file.project)
        val items = mutableListOf<GotoRelatedItem>()

        // same-named dataset files in sibling country directories and in global
        val parent = virtualFile.parent          // .../<CC>/staticdatasetup or global/staticdatasetup
        val countryDir = parent?.parent          // .../<CC> or global
        val countriesRoot = countryDir?.parent   // .../countries
        if (parent != null && countryDir != null && countriesRoot != null) {
            for (sibling in countriesRoot.children.orEmpty()) {
                if (!sibling.isDirectory || sibling == countryDir) continue
                val candidate = sibling.findChild(parent.name)?.findChild(virtualFile.name) ?: continue
                psiManager.findFile(candidate)?.let {
                    items += GotoRelatedItem(it, "Datasets (${sibling.name})")
                }
            }
        }

        // DDL definitions of every schema table referenced in this file
        val schema = SchemaIndexService.getInstance(file.project).schemaProvider()
        val referenced = LinkedHashSet<String>()
        val text = file.text
        for (statement in SqlParser.parse(text).statements) {
            when (statement) {
                is com.company.liquibasevalidator.sql.InsertStatement -> referenced += statement.tableNameLower
                is com.company.liquibasevalidator.sql.MergeStatement -> {
                    referenced += statement.targetTableLower
                    statement.sourceTableLower?.let { referenced += it }
                }
                else -> Unit
            }
        }
        for (name in referenced) {
            val table = schema.findTable(name) ?: continue
            val resolved = SqlWordAt.resolveOrigin(file, table) ?: continue
            if (resolved.first != file) {
                items += GotoRelatedItem(resolved.first, "DDL")
            }
        }
        return items
    }
}
