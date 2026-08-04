package com.company.liquibasevalidator.sql

/**
 * Intermediate SQL model. Every node keeps [SrcRange] offsets into the original file text
 * so inspections can highlight the exact offending token.
 */
sealed class SqlStatement {
    abstract val range: SrcRange
}

/** A statement the parser does not model; kept for changeset mapping and recovery.
 *  [leadingWord] is the raw first token; [leadingRange] its exact range (for highlighting). */
data class UnknownStatement(
    override val range: SrcRange,
    val leadingWord: String,
    val leadingRange: SrcRange,
) : SqlStatement()

// ---------------------------------------------------------------------------------------------
// CREATE TABLE
// ---------------------------------------------------------------------------------------------

data class ColumnDef(
    val name: String,
    val nameRange: SrcRange,
    val dataType: SqlDataType,
    val typeRange: SrcRange,
    val notNull: Boolean,
    val primaryKey: Boolean,
    val unique: Boolean,
    val hasDefault: Boolean,
    val references: ColumnRefTarget?,   // inline REFERENCES table(column)
    val range: SrcRange,
) {
    val nameLower: String get() = name.lowercase()
}

data class ColumnRefTarget(val table: String, val column: String?)

enum class TableConstraintKind { PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK }

data class TableConstraintDef(
    val kind: TableConstraintKind,
    val name: String?,
    val columns: List<String>,
    val refTable: String? = null,
    val refColumns: List<String> = emptyList(),
    val range: SrcRange,
)

data class CreateTableStatement(
    val tableName: String,
    val tableNameRange: SrcRange,
    val temporary: Boolean,
    val columns: List<ColumnDef>,
    val constraints: List<TableConstraintDef>,
    override val range: SrcRange,
) : SqlStatement() {
    val tableNameLower: String get() = tableName.lowercase()
    fun findColumn(name: String): ColumnDef? = columns.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

// ---------------------------------------------------------------------------------------------
// Values / expressions
// ---------------------------------------------------------------------------------------------

/** A value expression appearing in VALUES(...) rows, UPDATE SET or MERGE INSERT. */
sealed class SqlValueExpr {
    abstract val range: SrcRange

    data class StringLit(val value: String, val castType: SqlDataType?, override val range: SrcRange) : SqlValueExpr()
    data class NumberLit(val text: String, val negative: Boolean, override val range: SrcRange) : SqlValueExpr() {
        val literal: String get() = (if (negative) "-" else "") + text
    }
    data class BoolLit(val value: Boolean, override val range: SrcRange) : SqlValueExpr()
    data class NullLit(override val range: SrcRange) : SqlValueExpr()
    data class DefaultKw(override val range: SrcRange) : SqlValueExpr()
    /** Qualified or unqualified column reference, e.g. `source.code` or `code`. */
    data class ColumnRef(val qualifier: String?, val column: String, override val range: SrcRange) : SqlValueExpr() {
        val columnLower: String get() = column.lowercase()
        val qualifierLower: String? get() = qualifier?.lowercase()
    }
    /** Anything else (function calls, arithmetic, subselects): opaque, not statically validated. */
    data class Opaque(val text: String, override val range: SrcRange) : SqlValueExpr()
}

data class ValuesRow(val values: List<SqlValueExpr>, val range: SrcRange)

// ---------------------------------------------------------------------------------------------
// INSERT
// ---------------------------------------------------------------------------------------------

data class ColumnNameRef(val name: String, val range: SrcRange) {
    val nameLower: String get() = name.lowercase()
}

data class InsertStatement(
    val tableName: String,
    val tableNameRange: SrcRange,
    /** Explicit column list, or null when omitted (positional mapping against table definition). */
    val columns: List<ColumnNameRef>?,
    val rows: List<ValuesRow>,
    /** True for INSERT INTO ... SELECT — rows is empty, only column checks apply. */
    val fromSelect: Boolean,
    override val range: SrcRange,
) : SqlStatement() {
    val tableNameLower: String get() = tableName.lowercase()
}

// ---------------------------------------------------------------------------------------------
// MERGE
// ---------------------------------------------------------------------------------------------

data class MergeAssignment(
    /** Left side: target column (unqualified or target-qualified). */
    val targetColumn: ColumnNameRef,
    val value: SqlValueExpr,
)

data class MergeInsertClause(
    val columns: List<ColumnNameRef>?,
    val values: List<SqlValueExpr>,
    val range: SrcRange,
)

/** One `target.col = source.col` equality from the ON clause; sides may be opaque. */
data class OnEquality(val left: SqlValueExpr, val right: SqlValueExpr)

data class MergeStatement(
    val targetTable: String,
    val targetTableRange: SrcRange,
    val targetAlias: String?,
    /** Source table name; null when USING (subquery). */
    val sourceTable: String?,
    val sourceTableRange: SrcRange?,
    val sourceAlias: String?,
    val onConditions: List<OnEquality>,
    val updateAssignments: List<MergeAssignment>,
    val insertClause: MergeInsertClause?,
    val hasDelete: Boolean,
    override val range: SrcRange,
) : SqlStatement() {
    val targetTableLower: String get() = targetTable.lowercase()
    val sourceTableLower: String? get() = sourceTable?.lowercase()

    fun isTargetQualifier(q: String?): Boolean =
        q != null && (q.equals(targetAlias, true) || q.equals(targetTable, true))

    fun isSourceQualifier(q: String?): Boolean =
        q != null && (q.equals(sourceAlias, true) || (sourceTable != null && q.equals(sourceTable, true)))
}

// ---------------------------------------------------------------------------------------------
// ALTER TABLE / CREATE INDEX / DELETE (subset needed for schema building and rollback checks)
// ---------------------------------------------------------------------------------------------

data class AlterTableAddConstraint(
    val tableName: String,
    val constraints: List<TableConstraintDef>,
    override val range: SrcRange,
) : SqlStatement() {
    val tableNameLower: String get() = tableName.lowercase()
}

data class CreateIndexStatement(
    val unique: Boolean,
    val indexName: String?,
    val tableName: String,
    val columns: List<String>,
    override val range: SrcRange,
) : SqlStatement() {
    val tableNameLower: String get() = tableName.lowercase()
}

data class DeleteStatement(
    val tableName: String,
    val tableNameRange: SrcRange,
    val hasWhere: Boolean,
    override val range: SrcRange,
) : SqlStatement()

data class UpdateStatement(
    val tableName: String,
    val tableNameRange: SrcRange,
    val hasWhere: Boolean,
    override val range: SrcRange,
) : SqlStatement() {
    val tableNameLower: String get() = tableName.lowercase()
}

data class TruncateStatement(
    val tableName: String,
    val tableNameRange: SrcRange,
    override val range: SrcRange,
) : SqlStatement()

data class DropStatement(
    /** TABLE, SEQUENCE, INDEX or VIEW. */
    val objectKind: String,
    val name: String,
    val nameRange: SrcRange,
    override val range: SrcRange,
) : SqlStatement() {
    val nameLower: String get() = name.lowercase()
}

/** A parsed script: ordered statements plus non-fatal parse notes. */
data class SqlScript(
    val statements: List<SqlStatement>,
    val parseNotes: List<ParseNote>,
) {
    inline fun <reified T : SqlStatement> statementsOf(): List<T> = statements.filterIsInstance<T>()
}

data class ParseNote(val message: String, val range: SrcRange)
