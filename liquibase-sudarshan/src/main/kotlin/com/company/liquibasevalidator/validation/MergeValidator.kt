package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.SchemaProvider
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.CreateTableStatement
import com.company.liquibasevalidator.sql.MergeStatement
import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.SqlValueExpr

/**
 * Validates MERGE statements: target/source table existence, every column reference,
 * source→target datatype compatibility and nullability of assigned values.
 */
internal class MergeValidator(
    private val options: ValidationOptions,
    private val sink: ProblemSink,
) {

    private class SourceColumns(
        val tableName: String,
        /** true when the source is a temp table defined in this file — its declaration
         *  compatibility is already validated by [TempTableValidator] via the mapping. */
        val isTempTable: Boolean,
        private val types: Map<String, SqlDataType>,
    ) {
        fun typeOf(columnLower: String): SqlDataType? = types[columnLower]
        fun contains(columnLower: String): Boolean = columnLower in types
    }

    fun validate(merge: MergeStatement, tempTables: Map<String, CreateTableStatement>, schema: SchemaProvider) {
        if (!options.validateMergeMappings) return

        val target: TableSchema? = schema.findTable(merge.targetTable)
        if (target == null && schema.isResolved()) {
            sink.add(
                Severity.WARNING, ProblemCategory.SCHEMA,
                "Liquibase: unknown table '${merge.targetTable}' — not found in the repository DDL; " +
                    "MERGE target validation skipped",
                merge.targetTableRange,
            )
        }

        val source: SourceColumns? = resolveSource(merge, tempTables, schema)

        for (eq in merge.onConditions) {
            checkColumnRef(eq.left, merge, target, source)
            checkColumnRef(eq.right, merge, target, source)
        }

        for (assignment in merge.updateAssignments) {
            val targetColumn = target?.findColumn(assignment.targetColumn.name)
            if (target != null && targetColumn == null) {
                sink.add(
                    Severity.ERROR, ProblemCategory.SCHEMA,
                    "Liquibase: column '${assignment.targetColumn.name}' does not exist in target table " +
                        "'${target.name}'",
                    assignment.targetColumn.range,
                )
            }
            checkColumnRef(assignment.value, merge, target, source)
            if (targetColumn != null) {
                checkAssignedValue(assignment.value, target, targetColumn.name, source, merge)
            }
        }

        merge.insertClause?.let { clause ->
            clause.columns?.forEach { col ->
                if (target != null && target.findColumn(col.name) == null) {
                    sink.add(
                        Severity.ERROR, ProblemCategory.SCHEMA,
                        "Liquibase: column '${col.name}' does not exist in target table '${target.name}'",
                        col.range,
                    )
                }
            }
            val targetColumns = clause.columns?.map { it.nameLower } ?: target?.columns?.map { it.nameLower }
            if (targetColumns != null && clause.values.isNotEmpty() && clause.values.size != targetColumns.size) {
                sink.add(
                    Severity.ERROR, ProblemCategory.MAPPING,
                    "Liquibase: MERGE INSERT has ${clause.values.size} value(s) but " +
                        "${targetColumns.size} column(s)",
                    clause.range,
                )
            } else if (targetColumns != null) {
                clause.values.forEachIndexed { i, value ->
                    checkColumnRef(value, merge, target, source)
                    val columnName = targetColumns.getOrNull(i) ?: return@forEachIndexed
                    if (target?.findColumn(columnName) != null) {
                        checkAssignedValue(value, target, columnName, source, merge)
                    }
                }
            }

            if (options.validateNullConstraints && target != null && clause.columns != null) {
                val listed = clause.columns.map { it.nameLower }.toSet()
                target.columns
                    .filter { !it.nullable && !it.hasDefault && it.nameLower !in listed }
                    .forEach { missing ->
                        sink.add(
                            Severity.WARNING, ProblemCategory.NULLABILITY,
                            "Liquibase: NOT NULL column '${target.name}.${missing.name}' has no default and " +
                                "is missing from the MERGE INSERT column list",
                            clause.range,
                        )
                    }
            }
        }
    }

    private fun resolveSource(
        merge: MergeStatement,
        tempTables: Map<String, CreateTableStatement>,
        schema: SchemaProvider,
    ): SourceColumns? {
        val sourceName = merge.sourceTable ?: return null // USING (subquery): not statically resolvable
        tempTables[merge.sourceTableLower]?.let { temp ->
            return SourceColumns(temp.tableName, true, temp.columns.associate { it.nameLower to it.dataType })
        }
        schema.findTable(sourceName)?.let { table ->
            return SourceColumns(table.name, false, table.columns.associate { it.nameLower to it.dataType })
        }
        if (schema.isResolved()) {
            sink.add(
                Severity.WARNING, ProblemCategory.SCHEMA,
                "Liquibase: unknown source table '$sourceName' — no temporary table definition or " +
                    "repository DDL found",
                merge.sourceTableRange ?: merge.range,
            )
        }
        return null
    }

    /** Existence check for a (possibly qualified) column reference used anywhere in the MERGE. */
    private fun checkColumnRef(
        value: SqlValueExpr,
        merge: MergeStatement,
        target: TableSchema?,
        source: SourceColumns?,
    ) {
        val ref = value as? SqlValueExpr.ColumnRef ?: return
        val qualifier = ref.qualifierLower
        when {
            merge.isTargetQualifier(qualifier) -> {
                if (target != null && target.findColumn(ref.column) == null) {
                    sink.add(
                        Severity.ERROR, ProblemCategory.SCHEMA,
                        "Liquibase: column '${ref.column}' does not exist in target table '${target.name}'",
                        ref.range,
                    )
                }
            }
            merge.isSourceQualifier(qualifier) -> {
                if (source != null && !source.contains(ref.columnLower)) {
                    sink.add(
                        Severity.ERROR, ProblemCategory.SCHEMA,
                        "Liquibase: column '${ref.column}' does not exist in source table '${source.tableName}'",
                        ref.range,
                    )
                }
            }
            qualifier == null -> {
                val inSource = source?.contains(ref.columnLower) ?: true
                val inTarget = target?.findColumn(ref.column) != null
                if (source != null && target != null && !inSource && !inTarget) {
                    sink.add(
                        Severity.ERROR, ProblemCategory.SCHEMA,
                        "Liquibase: column '${ref.column}' does not exist in source table " +
                            "'${source.tableName}' or target table '${target.name}'",
                        ref.range,
                    )
                }
            }
            else -> {
                sink.add(
                    Severity.WARNING, ProblemCategory.MAPPING,
                    "Liquibase: unknown table qualifier '${ref.qualifier}' in MERGE — expected " +
                        "'${merge.targetAlias ?: merge.targetTable}' or '${merge.sourceAlias ?: merge.sourceTable}'",
                    ref.range,
                )
            }
        }
    }

    /** Datatype/nullability validation of a value assigned to a known target column. */
    private fun checkAssignedValue(
        value: SqlValueExpr,
        target: TableSchema,
        targetColumnName: String,
        source: SourceColumns?,
        merge: MergeStatement,
    ) {
        val targetColumn = target.findColumn(targetColumnName) ?: return
        val display = "${target.name}.${targetColumn.name}"
        when (value) {
            is SqlValueExpr.NullLit -> {
                if (options.validateNullConstraints && !targetColumn.nullable) {
                    sink.add(
                        Severity.ERROR, ProblemCategory.NULLABILITY,
                        "Liquibase: NULL is not allowed for column '$display' (NOT NULL)",
                        value.range,
                    )
                }
            }
            is SqlValueExpr.ColumnRef -> {
                val qualifier = value.qualifierLower
                val isSourceRef = merge.isSourceQualifier(qualifier) ||
                    (qualifier == null && source?.contains(value.columnLower) == true)
                // temp-table sources: declaration compatibility is reported once, on the
                // staging column declaration, by TempTableValidator — do not double-report.
                if (isSourceRef && source != null && !source.isTempTable) {
                    val sourceType = source.typeOf(value.columnLower) ?: return
                    TypeCompatibility.check(sourceType, targetColumn.dataType)?.let { issue ->
                        sink.add(
                            issue.severity, issue.category,
                            "Liquibase: MERGE source/target datatype mismatch for '$display' — source column " +
                                "'${source.tableName}.${value.column}' is ${sourceType.display()}, target is " +
                                "${targetColumn.dataType.display()}: ${issue.detail}",
                            value.range,
                        )
                    }
                }
            }
            else -> {
                if (options.treatEmptyStringAsNull && options.validateNullConstraints &&
                    value is SqlValueExpr.StringLit && value.value.isEmpty() && !targetColumn.nullable
                ) {
                    sink.add(
                        Severity.ERROR, ProblemCategory.NULLABILITY,
                        "Liquibase: empty string '' is NULL in Oracle and is not allowed for " +
                            "column '$display' (NOT NULL)",
                        value.range,
                    )
                    return
                }
                LiteralValidators.check(value, targetColumn.dataType, display, options)?.let { issue ->
                    sink.add(issue.severity, issue.category, issue.message, value.range)
                }
            }
        }
    }
}
