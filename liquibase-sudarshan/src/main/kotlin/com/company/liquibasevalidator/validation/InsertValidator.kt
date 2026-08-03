package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.ColumnSchema
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.ColumnDef
import com.company.liquibasevalidator.sql.InsertStatement
import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.SqlValueExpr
import com.company.liquibasevalidator.sql.SrcRange

/**
 * Validates INSERT statements: column existence, column/value counts, values against the
 * declared column types — and, for staging tables, against the *mapped target* columns so
 * problems are caught at the staging INSERT, before the MERGE would fail.
 */
internal class InsertValidator(
    private val options: ValidationOptions,
    private val sink: ProblemSink,
) {

    /** A column an inserted value is checked against. */
    private data class EffectiveColumn(
        val display: String,
        val dataType: SqlDataType,
        val notNull: Boolean,
        val hasDefault: Boolean,
    )

    // -----------------------------------------------------------------------------------------
    // INSERT into a staging temp table
    // -----------------------------------------------------------------------------------------

    fun validateStagingInsert(flow: StagingFlow, insert: InsertStatement) {
        val temp = flow.tempTable
        val tempColumnsLower = temp.columns.map { it.nameLower }

        insert.columns?.forEach { col ->
            if (col.nameLower !in tempColumnsLower) {
                sink.add(
                    Severity.ERROR, ProblemCategory.SCHEMA,
                    "Liquibase: column '${col.name}' does not exist in table '${temp.tableName}'",
                    col.range,
                )
            }
        }

        val resolved = insert.columns?.map { it.nameLower } ?: tempColumnsLower
        checkMissingNotNullColumns(insert, resolved) {
            temp.columns.map { EffectiveColumn("${temp.tableName}.${it.name}", it.dataType, it.notNull, it.hasDefault) to it.nameLower }
        }

        for (row in insert.rows) {
            if (row.values.size != resolved.size && insert.columns != null) {
                sink.add(
                    Severity.ERROR, ProblemCategory.MAPPING,
                    "Liquibase: INSERT into '${temp.tableName}' has ${row.values.size} value(s) " +
                        "but ${resolved.size} column(s)",
                    row.range,
                )
                continue
            }
            row.values.forEachIndexed { i, value ->
                val columnName = resolved.getOrNull(i) ?: return@forEachIndexed
                val tempColumn = temp.findColumn(columnName)
                validateValue(value, effectiveColumnsFor(flow, tempColumn, columnName))
            }
        }
    }

    /**
     * The columns a staged value must satisfy: every mapped target column first (stricter,
     * and the error message should name the real table), then the staging declaration itself.
     */
    private fun effectiveColumnsFor(
        flow: StagingFlow,
        tempColumn: ColumnDef?,
        columnNameLower: String,
    ): List<EffectiveColumn> {
        val columns = mutableListOf<EffectiveColumn>()
        for ((mapping, targetColName) in flow.targetsOf(columnNameLower)) {
            val schema = mapping.targetSchema ?: continue
            val target = schema.findColumn(targetColName) ?: continue
            columns += EffectiveColumn(
                "${schema.name}.${target.name}", target.dataType, !target.nullable, target.hasDefault,
            )
        }
        tempColumn?.let {
            columns += EffectiveColumn(
                "${flow.tempTable.tableName}.${it.name}", it.dataType, it.notNull, it.hasDefault,
            )
        }
        return columns
    }

    // -----------------------------------------------------------------------------------------
    // INSERT directly into a schema table
    // -----------------------------------------------------------------------------------------

    fun validateDirectInsert(insert: InsertStatement, table: TableSchema) {
        val tableColumnsLower = table.columns.map { it.nameLower }

        insert.columns?.forEach { col ->
            if (col.nameLower !in tableColumnsLower) {
                sink.add(
                    Severity.ERROR, ProblemCategory.SCHEMA,
                    "Liquibase: column '${col.name}' does not exist in table '${table.name}'",
                    col.range,
                )
            }
        }

        val resolved = insert.columns?.map { it.nameLower } ?: tableColumnsLower
        checkMissingNotNullColumns(insert, resolved) {
            table.columns.map { EffectiveColumn("${table.name}.${it.name}", it.dataType, !it.nullable, it.hasDefault) to it.nameLower }
        }

        for (row in insert.rows) {
            if (row.values.size != resolved.size && insert.columns != null) {
                sink.add(
                    Severity.ERROR, ProblemCategory.MAPPING,
                    "Liquibase: INSERT into '${table.name}' has ${row.values.size} value(s) " +
                        "but ${resolved.size} column(s)",
                    row.range,
                )
                continue
            }
            row.values.forEachIndexed { i, value ->
                val columnName = resolved.getOrNull(i) ?: return@forEachIndexed
                val column = table.findColumn(columnName) ?: return@forEachIndexed
                validateValue(value, listOf(toEffective(table, column)))
            }
        }
    }

    private fun toEffective(table: TableSchema, column: ColumnSchema) =
        EffectiveColumn("${table.name}.${column.name}", column.dataType, !column.nullable, column.hasDefault)

    // -----------------------------------------------------------------------------------------
    // Shared checks
    // -----------------------------------------------------------------------------------------

    private fun checkMissingNotNullColumns(
        insert: InsertStatement,
        resolvedColumns: List<String>,
        allColumns: () -> List<Pair<EffectiveColumn, String>>,
    ) {
        if (!options.validateNullConstraints || insert.columns == null) return
        for ((column, nameLower) in allColumns()) {
            if (column.notNull && !column.hasDefault && nameLower !in resolvedColumns) {
                sink.add(
                    Severity.ERROR, ProblemCategory.NULLABILITY,
                    "Liquibase: NOT NULL column '${column.display}' has no default and is missing " +
                        "from the INSERT column list",
                    insert.tableNameRange,
                )
            }
        }
    }

    private fun validateValue(value: SqlValueExpr, columns: List<EffectiveColumn>) {
        when (value) {
            is SqlValueExpr.DefaultKw -> return
            is SqlValueExpr.NullLit -> {
                if (!options.validateNullConstraints) return
                columns.firstOrNull { it.notNull }?.let { column ->
                    sink.add(
                        Severity.ERROR, ProblemCategory.NULLABILITY,
                        "Liquibase: NULL is not allowed for column '${column.display}' (NOT NULL)",
                        value.range,
                    )
                }
            }
            is SqlValueExpr.StringLit, is SqlValueExpr.NumberLit, is SqlValueExpr.BoolLit -> {
                if (options.treatEmptyStringAsNull && options.validateNullConstraints &&
                    value is SqlValueExpr.StringLit && value.value.isEmpty()
                ) {
                    columns.firstOrNull { it.notNull }?.let { column ->
                        sink.add(
                            Severity.ERROR, ProblemCategory.NULLABILITY,
                            "Liquibase: empty string '' is NULL in Oracle and is not allowed for " +
                                "column '${column.display}' (NOT NULL)",
                            value.range,
                        )
                        return
                    }
                }
                for (column in columns) {
                    val issue = LiteralValidators.check(value, column.dataType, column.display, options)
                    if (issue != null) {
                        sink.add(
                            issue.severity, issue.category, issue.message, value.range,
                            listOf(QuickFixData.SelectValue(value.range)),
                        )
                        return // one report per value: the first (target-side) violation wins
                    }
                }
            }
            else -> Unit // column refs / opaque expressions are not statically checkable
        }
    }

    companion object {
        /** Resolved (column → static literal value) pairs of a row, for duplicate detection. */
        fun staticRowValues(
            resolvedColumns: List<String>,
            row: List<SqlValueExpr>,
        ): Map<String, Pair<String, SrcRange>> {
            val map = LinkedHashMap<String, Pair<String, SrcRange>>()
            row.forEachIndexed { i, value ->
                val column = resolvedColumns.getOrNull(i) ?: return@forEachIndexed
                val literal = when (value) {
                    is SqlValueExpr.StringLit -> value.value
                    is SqlValueExpr.NumberLit -> value.literal.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
                        ?: value.literal
                    is SqlValueExpr.BoolLit -> value.value.toString()
                    else -> null
                }
                if (literal != null) map[column] = literal to value.range
            }
            return map
        }
    }
}
