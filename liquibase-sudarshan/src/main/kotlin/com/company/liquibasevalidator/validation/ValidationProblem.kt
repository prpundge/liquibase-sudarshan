package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.sql.SrcRange

enum class Severity { ERROR, WARNING, WEAK_WARNING, INFO }

enum class ProblemCategory {
    SCHEMA,        // unknown table/column, unresolved schema
    DATATYPE,      // declared type or literal incompatible with column type
    LENGTH,        // VARCHAR/CHAR length exceeded
    NULLABILITY,   // NULL into NOT NULL
    MAPPING,       // MERGE / INSERT column mapping problems
    DUPLICATE,     // duplicate unique/PK values
    UNUSED,        // unused staging column
    LIQUIBASE,     // changeset structure problems
    SYNTAX,        // statement delimiter / splitStatements / endDelimiter problems
    DATABASE,      // database-backed findings
}

/** Declarative quick-fix payloads; the IntelliJ layer turns these into LocalQuickFixes. */
sealed class QuickFixData {
    /** Replace [range] with [newText] (e.g. change VARCHAR(50) to VARCHAR(15)). */
    data class ReplaceText(val range: SrcRange, val newText: String, val name: String) : QuickFixData()

    /** Select the offending value in the editor so the developer can edit it deliberately. */
    data class SelectValue(val range: SrcRange, val name: String = "Select value for editing") : QuickFixData()
}

data class ValidationProblem(
    val severity: Severity,
    val category: ProblemCategory,
    val message: String,
    val range: SrcRange,
    val fixes: List<QuickFixData> = emptyList(),
)

/** Validation toggles; mirrors the plugin settings, but IntelliJ-free for core reuse/tests. */
data class ValidationOptions(
    val validateVarcharLength: Boolean = true,
    val validateNumericRanges: Boolean = true,
    val validateNullConstraints: Boolean = true,
    val validateDuplicates: Boolean = true,
    val validateMergeMappings: Boolean = true,
    val validateForeignKeys: Boolean = true,
    val warnUnusedStagingColumns: Boolean = true,
    /** Map `tmp_x`/`temp_x`/`stg_x` staging tables to table `x` when no MERGE provides a mapping. */
    val tempTableNameHeuristic: Boolean = true,
    val validateLiquibaseStructure: Boolean = true,
    /** Liquibase manages the transaction — explicit COMMIT/ROLLBACK in a changeset warns. */
    val warnExplicitCommit: Boolean = true,
    /** Oracle semantics: an empty string literal '' is NULL and violates NOT NULL columns. */
    val treatEmptyStringAsNull: Boolean = false,
)
