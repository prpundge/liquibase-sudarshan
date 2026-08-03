package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.SchemaProvider
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.CreateTableStatement
import com.company.liquibasevalidator.sql.InsertStatement
import com.company.liquibasevalidator.sql.MergeStatement
import com.company.liquibasevalidator.sql.SqlScript
import com.company.liquibasevalidator.sql.SqlValueExpr

/**
 * Column mapping from a staging (temp) table into a target table, derived from one MERGE
 * statement: ON equalities, `UPDATE SET t = source.s` assignments and
 * `WHEN NOT MATCHED INSERT (…) VALUES (source.…)` pairs. Mapping is by column name/pairing,
 * never by blind position.
 */
data class StagingMapping(
    val merge: MergeStatement?,
    val targetTableName: String,
    val targetSchema: TableSchema?,
    /** staging column (lower) → target column names (lower) it flows into. */
    val columnMap: Map<String, Set<String>>,
    /** every staging column referenced anywhere in the MERGE (for unused-column analysis). */
    val usedSourceColumns: Set<String>,
    /** true when derived from the tmp_-prefix naming heuristic instead of a MERGE. */
    val heuristic: Boolean,
)

/** One staging table with everything that flows through it in this file. */
data class StagingFlow(
    val tempTable: CreateTableStatement,
    val inserts: List<InsertStatement>,
    val mappings: List<StagingMapping>,
) {
    /** Target columns a staging column feeds, across all merges. */
    fun targetsOf(stagingColumnLower: String): List<Pair<StagingMapping, String>> =
        mappings.flatMap { m -> (m.columnMap[stagingColumnLower] ?: emptySet()).map { m to it } }
}

object DataFlow {

    private val STAGING_PREFIXES = listOf("tmp_", "temp_", "stg_", "staging_")
    private val STAGING_SUFFIXES = listOf("_tmp", "_temp", "_stg", "_staging")

    fun analyze(script: SqlScript, schema: SchemaProvider, options: ValidationOptions): List<StagingFlow> {
        val tempTables = script.statementsOf<CreateTableStatement>().filter { it.temporary }
        val inserts = script.statementsOf<InsertStatement>()
        val merges = script.statementsOf<MergeStatement>()

        return tempTables.map { temp ->
            val tempInserts = inserts.filter { it.tableNameLower == temp.tableNameLower }
            val tempMerges = merges.filter { it.sourceTableLower == temp.tableNameLower }
            val mappings = tempMerges.map { buildMergeMapping(it, temp, schema) }.toMutableList()
            if (mappings.isEmpty() && options.tempTableNameHeuristic && schema.isResolved()) {
                heuristicMapping(temp, schema)?.let { mappings += it }
            }
            StagingFlow(temp, tempInserts, mappings)
        }
    }

    private fun buildMergeMapping(
        merge: MergeStatement,
        temp: CreateTableStatement,
        schema: SchemaProvider,
    ): StagingMapping {
        val targetSchema = schema.findTable(merge.targetTable)
        val columnMap = LinkedHashMap<String, MutableSet<String>>()
        val used = LinkedHashSet<String>()

        fun sourceColumnOf(expr: SqlValueExpr): String? {
            val ref = expr as? SqlValueExpr.ColumnRef ?: return null
            val qualifier = ref.qualifierLower
            return when {
                merge.isSourceQualifier(qualifier) -> ref.columnLower
                qualifier == null && temp.findColumn(ref.column) != null -> ref.columnLower
                else -> null
            }
        }

        fun map(sourceCol: String?, targetCol: String?) {
            if (sourceCol != null) used += sourceCol
            if (sourceCol != null && targetCol != null) {
                columnMap.getOrPut(sourceCol) { LinkedHashSet() } += targetCol.lowercase()
            }
        }

        for (eq in merge.onConditions) {
            val leftTarget = (eq.left as? SqlValueExpr.ColumnRef)?.takeIf { merge.isTargetQualifier(it.qualifierLower) }
            val rightTarget = (eq.right as? SqlValueExpr.ColumnRef)?.takeIf { merge.isTargetQualifier(it.qualifierLower) }
            map(sourceColumnOf(eq.right), leftTarget?.columnLower)
            map(sourceColumnOf(eq.left), rightTarget?.columnLower)
        }
        for (assignment in merge.updateAssignments) {
            map(sourceColumnOf(assignment.value), assignment.targetColumn.nameLower)
        }
        merge.insertClause?.let { clause ->
            val targetColumns = clause.columns?.map { it.nameLower }
                ?: targetSchema?.columns?.map { it.nameLower }
            clause.values.forEachIndexed { i, value ->
                map(sourceColumnOf(value), targetColumns?.getOrNull(i))
            }
        }
        return StagingMapping(
            merge = merge,
            targetTableName = merge.targetTable,
            targetSchema = targetSchema,
            columnMap = columnMap,
            usedSourceColumns = used,
            heuristic = false,
        )
    }

    /** `tmp_account_type` → table `account_type`, columns matched by name. */
    private fun heuristicMapping(temp: CreateTableStatement, schema: SchemaProvider): StagingMapping? {
        val target = heuristicTargetName(temp.tableNameLower)?.let { schema.findTable(it) } ?: return null
        val columnMap = temp.columns
            .filter { target.findColumn(it.name) != null }
            .associate { it.nameLower to setOf(it.nameLower) }
        if (columnMap.isEmpty()) return null
        return StagingMapping(
            merge = null,
            targetTableName = target.name,
            targetSchema = target,
            columnMap = columnMap,
            usedSourceColumns = columnMap.keys,
            heuristic = true,
        )
    }

    fun heuristicTargetName(tempNameLower: String): String? {
        for (prefix in STAGING_PREFIXES) {
            if (tempNameLower.startsWith(prefix) && tempNameLower.length > prefix.length) {
                return tempNameLower.removePrefix(prefix)
            }
        }
        for (suffix in STAGING_SUFFIXES) {
            if (tempNameLower.endsWith(suffix) && tempNameLower.length > suffix.length) {
                return tempNameLower.removeSuffix(suffix)
            }
        }
        return null
    }
}
