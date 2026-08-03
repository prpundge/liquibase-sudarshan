package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.SchemaProvider
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.AlterTableAddConstraint
import com.company.liquibasevalidator.sql.CreateTableStatement
import com.company.liquibasevalidator.sql.SqlScript
import com.company.liquibasevalidator.sql.TableConstraintKind
import com.company.liquibasevalidator.sql.UnknownStatement

/**
 * Validates the DDL statements themselves — duplicate columns, constraints referencing
 * missing columns, foreign keys pointing at unknown tables/columns, and CREATE TABLE
 * statements the parser could not understand (which would silently vanish from the schema).
 * Findings appear inline in the DDL files exactly like DML findings.
 */
internal class DdlValidator(
    private val options: ValidationOptions,
    private val sink: ProblemSink,
) {

    private val createTablePattern =
        Regex("^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:GLOBAL\\s+|PRIVATE\\s+|LOCAL\\s+|TEMPORARY\\s+|TEMP\\s+|UNLOGGED\\s+)*TABLE\\b", RegexOption.IGNORE_CASE)

    fun validate(fileText: String, script: SqlScript, schema: SchemaProvider) {
        // tables defined in THIS file are resolvable even before the file is saved
        val localTables = script.statementsOf<CreateTableStatement>()
            .filter { !it.temporary }
            .associateBy { it.tableNameLower }

        for (create in script.statementsOf<CreateTableStatement>()) {
            checkDuplicateColumns(create)
            checkConstraintColumns(create)
            if (!create.temporary) checkForeignKeyTargets(create, localTables, schema)
        }

        for (alter in script.statementsOf<AlterTableAddConstraint>()) {
            checkAlterConstraints(alter, localTables, schema)
        }

        // A CREATE TABLE the parser gave up on never reaches the schema model: say so,
        // instead of letting every reference to it report "unknown table" mysteriously.
        for (statement in script.statementsOf<UnknownStatement>()) {
            val snippet = fileText.substring(
                statement.range.start,
                minOf(statement.range.end, statement.range.start + 120),
            )
            if (createTablePattern.containsMatchIn(snippet)) {
                sink.add(
                    Severity.WARNING, ProblemCategory.SCHEMA,
                    "Liquibase: this CREATE TABLE statement could not be parsed — the table is " +
                        "missing from schema validation and statements referencing it will not " +
                        "be fully checked",
                    statement.leadingRange,
                )
            }
        }
    }

    private fun checkDuplicateColumns(create: CreateTableStatement) {
        val seen = HashSet<String>()
        for (column in create.columns) {
            if (!seen.add(column.nameLower)) {
                sink.add(
                    Severity.ERROR, ProblemCategory.SCHEMA,
                    "Liquibase: duplicate column '${column.name}' in table '${create.tableName}'",
                    column.nameRange,
                )
            }
        }
    }

    private fun checkConstraintColumns(create: CreateTableStatement) {
        for (constraint in create.constraints) {
            for (columnName in constraint.columns) {
                if (create.findColumn(columnName) == null) {
                    sink.add(
                        Severity.ERROR, ProblemCategory.SCHEMA,
                        "Liquibase: ${constraint.kind.name.replace('_', ' ')} constraint references " +
                            "unknown column '$columnName' in table '${create.tableName}'",
                        constraint.range,
                    )
                }
            }
        }
    }

    private fun checkForeignKeyTargets(
        create: CreateTableStatement,
        localTables: Map<String, CreateTableStatement>,
        schema: SchemaProvider,
    ) {
        if (!options.validateForeignKeys) return

        for (column in create.columns) {
            val ref = column.references ?: continue
            reportUnknownFkTarget(ref.table, ref.column?.let { listOf(it) } ?: emptyList(),
                create.tableName, column.range, localTables, schema)
        }
        for (constraint in create.constraints.filter { it.kind == TableConstraintKind.FOREIGN_KEY }) {
            reportUnknownFkTarget(constraint.refTable ?: continue, constraint.refColumns,
                create.tableName, constraint.range, localTables, schema)
        }
    }

    private fun checkAlterConstraints(
        alter: AlterTableAddConstraint,
        localTables: Map<String, CreateTableStatement>,
        schema: SchemaProvider,
    ) {
        val table = resolveColumns(alter.tableNameLower, localTables, schema)
        for (constraint in alter.constraints) {
            if (table != null) {
                for (columnName in constraint.columns) {
                    if (columnName.lowercase() !in table) {
                        sink.add(
                            Severity.ERROR, ProblemCategory.SCHEMA,
                            "Liquibase: ${constraint.kind.name.replace('_', ' ')} constraint references " +
                                "unknown column '$columnName' in table '${alter.tableName}'",
                            constraint.range,
                        )
                    }
                }
            }
            if (options.validateForeignKeys && constraint.kind == TableConstraintKind.FOREIGN_KEY) {
                constraint.refTable?.let {
                    reportUnknownFkTarget(it, constraint.refColumns, alter.tableName, constraint.range,
                        localTables, schema)
                }
            }
        }
    }

    private fun reportUnknownFkTarget(
        refTable: String,
        refColumns: List<String>,
        owningTable: String,
        range: com.company.liquibasevalidator.sql.SrcRange,
        localTables: Map<String, CreateTableStatement>,
        schema: SchemaProvider,
    ) {
        val targetColumns = resolveColumns(refTable.substringAfterLast('.').lowercase(), localTables, schema)
        if (targetColumns == null) {
            if (schema.isResolved()) {
                sink.add(
                    Severity.WARNING, ProblemCategory.SCHEMA,
                    "Liquibase: foreign key of '$owningTable' references unknown table '$refTable'",
                    range,
                )
            }
            return
        }
        for (columnName in refColumns) {
            if (columnName.lowercase() !in targetColumns) {
                sink.add(
                    Severity.ERROR, ProblemCategory.SCHEMA,
                    "Liquibase: foreign key of '$owningTable' references unknown column " +
                        "'$refTable.$columnName'",
                    range,
                )
            }
        }
    }

    /** Column names (lowercase) of a table found locally in this file or in the schema. */
    private fun resolveColumns(
        tableLower: String,
        localTables: Map<String, CreateTableStatement>,
        schema: SchemaProvider,
    ): Set<String>? {
        localTables[tableLower]?.let { local -> return local.columns.map { it.nameLower }.toSet() }
        val fromSchema: TableSchema? = schema.findTable(tableLower)
        return fromSchema?.columns?.map { it.nameLower }?.toSet()
    }
}
