package com.company.liquibasevalidator.schema

import com.company.liquibasevalidator.sql.AlterTableAddConstraint
import com.company.liquibasevalidator.sql.CreateIndexStatement
import com.company.liquibasevalidator.sql.CreateTableStatement
import com.company.liquibasevalidator.sql.SqlParser
import com.company.liquibasevalidator.sql.TableConstraintDef
import com.company.liquibasevalidator.sql.TableConstraintKind

/**
 * Builds the schema model from repository DDL files: CREATE TABLE (non-temporary),
 * ALTER TABLE ADD CONSTRAINT and CREATE UNIQUE INDEX. Reusable by every inspection —
 * schema parsing logic lives only here.
 */
object DdlSchemaBuilder {

    data class DdlSource(val fileId: String, val text: String)

    fun build(sources: List<DdlSource>): Map<String, TableSchema> {
        val tables = LinkedHashMap<String, TableSchema>()
        val pendingConstraints = mutableListOf<Pair<String, List<ConstraintSchema>>>()

        for (source in sources) {
            val script = SqlParser.parse(source.text)
            for (statement in script.statements) {
                when (statement) {
                    is CreateTableStatement -> if (!statement.temporary) {
                        tables[statement.tableNameLower] = toTableSchema(statement, source.fileId)
                    }
                    is AlterTableAddConstraint ->
                        pendingConstraints += statement.tableNameLower to statement.constraints.mapNotNull(::toConstraintSchema)
                    is CreateIndexStatement -> if (statement.unique) {
                        pendingConstraints += statement.tableNameLower to listOf(
                            ConstraintSchema(ConstraintKind.UNIQUE, statement.indexName, statement.columns),
                        )
                    }
                    else -> { /* DDL directories may contain other statements; ignored */ }
                }
            }
        }

        for ((tableName, constraints) in pendingConstraints) {
            val table = tables[tableName] ?: continue
            tables[tableName] = table.copy(constraints = table.constraints + constraints)
        }
        return tables
    }

    fun toTableSchema(statement: CreateTableStatement, fileId: String): TableSchema {
        val columns = statement.columns.map {
            ColumnSchema(
                name = it.name,
                dataType = it.dataType,
                nullable = !it.notNull,
                primaryKey = it.primaryKey,
                unique = it.unique,
                hasDefault = it.hasDefault,
            )
        }
        val constraints = statement.constraints.mapNotNull(::toConstraintSchema) +
            statement.columns.mapNotNull { col ->
                col.references?.let {
                    ConstraintSchema(
                        ConstraintKind.FOREIGN_KEY, null, listOf(col.name), it.table,
                        listOfNotNull(it.column),
                    )
                }
            }
        return TableSchema(
            name = statement.tableName,
            columns = columns,
            constraints = constraints,
            origin = SchemaOrigin(fileId, statement.tableNameRange.start),
        )
    }

    fun toConstraintSchema(def: TableConstraintDef): ConstraintSchema? = when (def.kind) {
        TableConstraintKind.PRIMARY_KEY -> ConstraintSchema(ConstraintKind.PRIMARY_KEY, def.name, def.columns)
        TableConstraintKind.UNIQUE -> ConstraintSchema(ConstraintKind.UNIQUE, def.name, def.columns)
        TableConstraintKind.FOREIGN_KEY ->
            ConstraintSchema(ConstraintKind.FOREIGN_KEY, def.name, def.columns, def.refTable, def.refColumns)
        TableConstraintKind.CHECK -> null
    }
}
