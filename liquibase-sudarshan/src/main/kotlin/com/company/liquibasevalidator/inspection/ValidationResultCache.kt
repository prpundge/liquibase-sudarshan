package com.company.liquibasevalidator.inspection

import com.company.liquibasevalidator.validation.ValidationProblem
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-file memoization of validation findings. Highlighting passes re-run frequently
 * without the file, the schema or the settings having changed — this cache turns those
 * passes into a map lookup instead of a full lex/parse/validate.
 *
 * Stores ONLY the problems, never the [com.company.liquibasevalidator.validation.FileAnalysis]
 * that produced them: the analysis holds the file's whole parsed AST (every token, statement
 * and range), the inspection never reads it, and keeping it retained up to 128 complete ASTs
 * per project for the lifetime of the project.
 *
 * Key: the file's document modification stamp + the combined schema/settings/database
 * state stamp from [com.company.liquibasevalidator.schema.SchemaIndexService.validationStamp].
 */
@Service(Service.Level.PROJECT)
class ValidationResultCache : Disposable {

    private data class Entry(val fileStamp: Long, val stateStamp: Long, val problems: List<ValidationProblem>)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(fileUrl: String, fileStamp: Long, stateStamp: Long): List<ValidationProblem>? =
        entries[fileUrl]?.takeIf { it.fileStamp == fileStamp && it.stateStamp == stateStamp }?.problems

    fun put(fileUrl: String, fileStamp: Long, stateStamp: Long, problems: List<ValidationProblem>) {
        if (entries.size > MAX_ENTRIES) entries.clear() // blunt but safe eviction
        entries[fileUrl] = Entry(fileStamp, stateStamp, problems)
    }

    /** Frees every retained finding — the project is closing. */
    override fun dispose() {
        entries.clear()
    }

    companion object {
        private const val MAX_ENTRIES = 128

        fun getInstance(project: Project): ValidationResultCache =
            project.getService(ValidationResultCache::class.java)
    }
}
