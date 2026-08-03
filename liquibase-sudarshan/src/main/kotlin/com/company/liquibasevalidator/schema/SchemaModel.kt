package com.company.liquibasevalidator.schema

import com.company.liquibasevalidator.sql.SqlDataType

/** Where a schema definition came from — used in messages and for navigation. */
data class SchemaOrigin(val fileId: String, val offset: Int) {
    companion object {
        val DATABASE = SchemaOrigin("<database>", 0)
    }
}

data class ColumnSchema(
    val name: String,
    val dataType: SqlDataType,
    val nullable: Boolean,
    val primaryKey: Boolean,
    val unique: Boolean,
    val hasDefault: Boolean,
) {
    val nameLower: String get() = name.lowercase()
}

enum class ConstraintKind { PRIMARY_KEY, UNIQUE, FOREIGN_KEY }

data class ConstraintSchema(
    val kind: ConstraintKind,
    val name: String?,
    val columns: List<String>,
    val refTable: String? = null,
    val refColumns: List<String> = emptyList(),
) {
    val columnsLower: List<String> get() = columns.map { it.lowercase() }
}

data class TableSchema(
    val name: String,
    val columns: List<ColumnSchema>,
    val constraints: List<ConstraintSchema>,
    val origin: SchemaOrigin,
) {
    val nameLower: String get() = name.lowercase()

    fun findColumn(name: String): ColumnSchema? =
        columns.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** All unique keys: PK + unique constraints + single-column unique/PK flags. */
    fun uniqueKeys(): List<List<String>> {
        val keys = mutableListOf<List<String>>()
        constraints.filter { it.kind == ConstraintKind.PRIMARY_KEY || it.kind == ConstraintKind.UNIQUE }
            .forEach { if (it.columns.isNotEmpty()) keys += it.columnsLower }
        columns.filter { it.primaryKey || it.unique }.forEach { keys += listOf(it.nameLower) }
        return keys.distinct()
    }

    fun foreignKeys(): List<ConstraintSchema> = constraints.filter { it.kind == ConstraintKind.FOREIGN_KEY }

    fun isColumnInPrimaryKey(column: String): Boolean {
        val lower = column.lowercase()
        if (columns.any { it.nameLower == lower && it.primaryKey }) return true
        return constraints.any { it.kind == ConstraintKind.PRIMARY_KEY && lower in it.columnsLower }
    }
}

/**
 * Read-only access to the resolved schema. Implementations: repository DDL snapshot,
 * database metadata overlay, fakes in tests.
 */
interface SchemaProvider {
    /** Look up a table by (possibly qualified) name; qualifiers are ignored. */
    fun findTable(name: String): TableSchema?

    /** All known tables, keyed by lowercase name. */
    fun allTables(): Map<String, TableSchema>

    /** False when no schema source is configured/available — validators must stay silent then. */
    fun isResolved(): Boolean
}

class MapSchemaProvider(private val tables: Map<String, TableSchema>, private val resolved: Boolean = true) : SchemaProvider {
    override fun findTable(name: String): TableSchema? = tables[name.substringAfterLast('.').lowercase()]
    override fun allTables(): Map<String, TableSchema> = tables
    override fun isResolved(): Boolean = resolved

    companion object {
        val UNRESOLVED = MapSchemaProvider(emptyMap(), resolved = false)
    }
}
