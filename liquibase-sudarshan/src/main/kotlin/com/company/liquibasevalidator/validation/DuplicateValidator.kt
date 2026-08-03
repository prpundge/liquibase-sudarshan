package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.InsertStatement
import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.sql.TableConstraintKind

/**
 * Detects duplicate values for primary-key/unique columns within the static data of a file,
 * across all VALUES rows of all INSERTs into the same table, without executing SQL.
 */
internal class DuplicateValidator(
    private val options: ValidationOptions,
    private val sink: ProblemSink,
) {

    /**
     * @param uniqueKeys keys expressed in the *inserted* table's column names (lowercase);
     *                   for staging tables, target unique keys already translated to staging columns.
     * @param keyOwnerTable display name of the table owning the unique constraint.
     */
    fun validateInserts(
        inserts: List<InsertStatement>,
        tableColumnsLower: List<String>,
        uniqueKeys: List<List<String>>,
        keyOwnerTable: String,
    ) {
        if (!options.validateDuplicates || uniqueKeys.isEmpty()) return

        // Collect all statically-known rows first (row numbering spans all INSERT statements).
        data class StaticRow(val rowNumber: Int, val values: Map<String, Pair<String, SrcRange>>)
        val rows = mutableListOf<StaticRow>()
        var rowNumber = 0
        for (insert in inserts) {
            val resolved = insert.columns?.map { it.nameLower } ?: tableColumnsLower
            for (row in insert.rows) {
                rowNumber++
                rows += StaticRow(rowNumber, InsertValidator.staticRowValues(resolved, row.values))
            }
        }

        for (key in uniqueKeys.distinct()) {
            val seen = HashMap<String, Int>() // composite value -> first row number
            for (row in rows) {
                val present = key.mapNotNull { row.values[it] }
                if (present.size != key.size) continue // not all key parts statically known
                val composite = present.joinToString("|") { it.first.replace("|", "||") }
                val first = seen.putIfAbsent(composite, row.rowNumber)
                if (first != null) {
                    val valueDisplay = key.zip(present).joinToString(", ") { (col, v) -> "$col='${v.first}'" }
                    sink.add(
                        Severity.ERROR, ProblemCategory.DUPLICATE,
                        "Liquibase: duplicate value for unique key of '$keyOwnerTable' ($valueDisplay); " +
                            "already used in row $first, duplicated in row ${row.rowNumber}",
                        present.first().second,
                    )
                }
            }
        }
    }

    /** Unique keys of the target table translated into staging column names via the mapping. */
    fun stagingUniqueKeys(flow: StagingFlow): List<Pair<List<String>, String>> {
        val keys = mutableListOf<Pair<List<String>, String>>()

        // the staging table's own declared PK/unique constraints
        val temp = flow.tempTable
        temp.columns.filter { it.primaryKey || it.unique }.forEach { keys += listOf(it.nameLower) to temp.tableName }
        temp.constraints
            .filter { it.kind == TableConstraintKind.PRIMARY_KEY || it.kind == TableConstraintKind.UNIQUE }
            .filter { it.columns.isNotEmpty() }
            .forEach { keys += it.columns.map(String::lowercase) to temp.tableName }

        // target unique keys, reachable through the mapping
        for (mapping in flow.mappings) {
            val schema = mapping.targetSchema ?: continue
            // invert: target column -> staging columns feeding it
            val inverse = HashMap<String, MutableList<String>>()
            for ((staging, targets) in mapping.columnMap) {
                targets.forEach { inverse.getOrPut(it) { mutableListOf() } += staging }
            }
            for (targetKey in schema.uniqueKeys()) {
                val stagingKey = targetKey.mapNotNull { inverse[it]?.firstOrNull() }
                if (stagingKey.size == targetKey.size) {
                    keys += stagingKey to schema.name
                }
            }
        }
        return keys.distinctBy { it.first }
    }

    fun validateStagingFlow(flow: StagingFlow) {
        if (!options.validateDuplicates) return
        val tableColumnsLower = flow.tempTable.columns.map { it.nameLower }
        for ((key, owner) in stagingUniqueKeys(flow)) {
            validateInserts(flow.inserts, tableColumnsLower, listOf(key), owner)
        }
    }

    fun validateDirectInserts(inserts: List<InsertStatement>, table: TableSchema) {
        if (!options.validateDuplicates || inserts.isEmpty()) return
        validateInserts(
            inserts,
            table.columns.map { it.nameLower },
            table.uniqueKeys(),
            table.name,
        )
    }
}
