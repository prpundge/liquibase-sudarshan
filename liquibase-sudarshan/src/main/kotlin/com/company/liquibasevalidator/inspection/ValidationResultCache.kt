package com.company.liquibasevalidator.inspection

import com.company.liquibasevalidator.validation.ValidationResult
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-file memoization of validation results. Highlighting passes re-run frequently
 * without the file, the schema or the settings having changed — this cache turns those
 * passes into a map lookup instead of a full lex/parse/validate.
 *
 * Key: the file's document modification stamp + the combined schema/settings/database
 * state stamp from [com.company.liquibasevalidator.schema.SchemaIndexService.validationStamp].
 */
@Service(Service.Level.PROJECT)
class ValidationResultCache {

    private data class Entry(val fileStamp: Long, val stateStamp: Long, val result: ValidationResult)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(fileUrl: String, fileStamp: Long, stateStamp: Long): ValidationResult? =
        entries[fileUrl]?.takeIf { it.fileStamp == fileStamp && it.stateStamp == stateStamp }?.result

    fun put(fileUrl: String, fileStamp: Long, stateStamp: Long, result: ValidationResult) {
        if (entries.size > MAX_ENTRIES) entries.clear() // blunt but safe eviction
        entries[fileUrl] = Entry(fileStamp, stateStamp, result)
    }

    companion object {
        private const val MAX_ENTRIES = 128

        fun getInstance(project: Project): ValidationResultCache =
            project.getService(ValidationResultCache::class.java)
    }
}
