package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.liquibase.LiquibaseFile
import com.company.liquibasevalidator.liquibase.LiquibaseParser
import com.company.liquibasevalidator.schema.SchemaProvider
import com.company.liquibasevalidator.sql.CreateTableStatement
import com.company.liquibasevalidator.sql.DeleteStatement
import com.company.liquibasevalidator.sql.InsertStatement
import com.company.liquibasevalidator.sql.MergeStatement
import com.company.liquibasevalidator.sql.SqlParser
import com.company.liquibasevalidator.sql.SqlScript
import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.sql.UnknownStatement

/** Accumulates problems from all validators. */
internal class ProblemSink {
    private val list = mutableListOf<ValidationProblem>()

    fun add(
        severity: Severity,
        category: ProblemCategory,
        message: String,
        range: SrcRange,
        fixes: List<QuickFixData> = emptyList(),
    ) {
        list += ValidationProblem(severity, category, message, range, fixes)
    }

    fun problems(): List<ValidationProblem> = list.distinctBy { Triple(it.message, it.range, it.severity) }
}

/** Everything the engine learned about a file; reused by database-backed validation. */
data class FileAnalysis(
    val script: SqlScript,
    val liquibase: LiquibaseFile,
    val stagingFlows: List<StagingFlow>,
    /** INSERTs whose target is not a temp table defined in this file. */
    val directInserts: List<InsertStatement>,
)

data class ValidationResult(
    val problems: List<ValidationProblem>,
    val analysis: FileAnalysis,
)

/**
 * Orchestrates parsing and all static validators over one SQL file.
 * Pure JVM: no IntelliJ dependencies, no database access, never throws on malformed input.
 */
class ValidationEngine(private val options: ValidationOptions = ValidationOptions()) {

    fun validate(fileText: String, schema: SchemaProvider): ValidationResult {
        val sink = ProblemSink()
        val script = SqlParser.parse(fileText)
        val liquibase = LiquibaseParser.parse(fileText)

        if (options.validateLiquibaseStructure) {
            for (problem in liquibase.problems) {
                val fixes = if (problem.fixRange != null && problem.fixReplacement != null) {
                    listOf(
                        QuickFixData.ReplaceText(
                            problem.fixRange, problem.fixReplacement,
                            problem.fixName ?: "Change to '${problem.fixReplacement}'",
                        ),
                    )
                } else {
                    emptyList()
                }
                sink.add(
                    if (problem.isError) Severity.ERROR else Severity.WARNING,
                    ProblemCategory.LIQUIBASE, problem.message, problem.range, fixes,
                )
            }
            for (note in script.parseNotes) {
                sink.add(Severity.WARNING, ProblemCategory.SYNTAX, "Liquibase: ${note.message}", note.range)
            }
            validateDelimiters(sink, liquibase, script)
        }

        validateStatementKeywords(sink, script)
        DdlValidator(options, sink).validate(fileText, script, schema)

        val tempTables: Map<String, CreateTableStatement> = script.statementsOf<CreateTableStatement>()
            .filter { it.temporary }
            .associateBy { it.tableNameLower }
        val flows = DataFlow.analyze(script, schema, options)
        val directInserts = script.statementsOf<InsertStatement>()
            .filter { it.tableNameLower !in tempTables }

        reportUnresolvedSchemaOnce(sink, schema, directInserts, script)

        val tempValidator = TempTableValidator(options, sink)
        val insertValidator = InsertValidator(options, sink)
        val mergeValidator = MergeValidator(options, sink)
        val duplicateValidator = DuplicateValidator(options, sink)

        for (flow in flows) {
            tempValidator.validate(flow)
            flow.inserts.forEach { insertValidator.validateStagingInsert(flow, it) }
            duplicateValidator.validateStagingFlow(flow)
        }

        for (merge in script.statementsOf<MergeStatement>()) {
            mergeValidator.validate(merge, tempTables, schema)
        }

        val insertsByTable = directInserts.groupBy { it.tableNameLower }
        for ((tableName, inserts) in insertsByTable) {
            val table = schema.findTable(tableName)
            if (table == null) {
                if (schema.isResolved()) {
                    inserts.forEach { insert ->
                        sink.add(
                            Severity.WARNING, ProblemCategory.SCHEMA,
                            "Liquibase: unknown table '${insert.tableName}' — not found in the repository DDL; " +
                                "validation skipped for this INSERT",
                            insert.tableNameRange,
                        )
                    }
                }
                continue
            }
            inserts.forEach { insertValidator.validateDirectInsert(it, table) }
            duplicateValidator.validateDirectInserts(inserts, table)
        }

        validateRollbacks(sink, liquibase, tempTables, schema)

        return ValidationResult(sink.problems(), FileAnalysis(script, liquibase, flows, directInserts))
    }

    /** Requirement: when schema cannot be resolved, say so once and skip — never guess. */
    private fun reportUnresolvedSchemaOnce(
        sink: ProblemSink,
        schema: SchemaProvider,
        directInserts: List<InsertStatement>,
        script: SqlScript,
    ) {
        if (schema.isResolved()) return
        val firstReference = directInserts.firstOrNull()?.let { it.tableName to it.tableNameRange }
            ?: script.statementsOf<MergeStatement>().firstOrNull()?.let { it.targetTable to it.targetTableRange }
            ?: return
        sink.add(
            Severity.WEAK_WARNING, ProblemCategory.SCHEMA,
            "Liquibase: unable to resolve the target table schema — configure the DDL directories under " +
                "Settings | Tools | Liquibase Sudarshan. Validation skipped for '${firstReference.first}'",
            firstReference.second,
        )
    }

    /**
     * A statement starting with a word no database knows is a typo (e.g. `INSEffRT`): the
     * database would reject it — and the whole statement would otherwise silently skip
     * validation. Recognized-but-unparsed statements (GRANT, DROP, PL/SQL, …) stay silent.
     */
    private fun validateStatementKeywords(sink: ProblemSink, script: SqlScript) {
        for (statement in script.statementsOf<UnknownStatement>()) {
            val word = statement.leadingWord
            if (word.isEmpty() || !word.first().isLetter()) continue // '/' terminators etc.
            if (word.uppercase() in KNOWN_STATEMENT_WORDS) continue
            val suggestion = nearestStatementKeyword(word)
            val fixes = suggestion?.let {
                listOf(QuickFixData.ReplaceText(statement.leadingRange, it, "Change '$word' to $it"))
            } ?: emptyList()
            sink.add(
                Severity.ERROR, ProblemCategory.SYNTAX,
                "Liquibase: unknown SQL statement keyword '$word'" +
                    (suggestion?.let { " — did you mean $it?" } ?: "") +
                    " — the database will reject this statement and it is skipped by validation",
                statement.leadingRange,
                fixes,
            )
        }
    }

    private fun nearestStatementKeyword(word: String): String? {
        if (word.length < 4) return null
        return com.company.liquibasevalidator.sql.TextDistance.nearest(word, SUGGESTION_KEYWORDS, 2)
    }

    /**
     * Delimiter semantics: Liquibase splits a changeset body on the (end)delimiter before
     * sending statements to the database. Mismatched attributes ship multi-statement
     * batches that most JDBC drivers reject.
     */
    private fun validateDelimiters(sink: ProblemSink, liquibase: LiquibaseFile, script: SqlScript) {
        for (changeset in liquibase.changesets) {
            val statements = script.statements.filter {
                it.range.start >= changeset.bodyRange.start && it.range.start < changeset.bodyRange.end
            }
            if (statements.size <= 1) continue

            if (changeset.attributes["splitstatements"].equals("false", ignoreCase = true)) {
                sink.add(
                    Severity.WARNING, ProblemCategory.SYNTAX,
                    "Liquibase: changeset '${changeset.key}' has splitStatements:false but contains " +
                        "${statements.size} statements — the whole body is sent as ONE statement and " +
                        "most databases will reject it",
                    changeset.headerRange,
                )
            }

            val endDelimiter = changeset.attributes["enddelimiter"]
            if (!endDelimiter.isNullOrBlank() && endDelimiter != ";") {
                sink.add(
                    Severity.WEAK_WARNING, ProblemCategory.SYNTAX,
                    "Liquibase: changeset '${changeset.key}' sets endDelimiter:'$endDelimiter' but its body " +
                        "contains ${statements.size} ';'-terminated statements — Liquibase will NOT split " +
                        "on ';' (intended only for PL/SQL blocks and similar)",
                    changeset.headerRange,
                )
            }
        }
    }

    /** Rollback statements live in comments; parse them separately and sanity-check tables. */
    private fun validateRollbacks(
        sink: ProblemSink,
        liquibase: LiquibaseFile,
        tempTables: Map<String, CreateTableStatement>,
        schema: SchemaProvider,
    ) {
        if (!options.validateLiquibaseStructure || !schema.isResolved()) return
        for (changeset in liquibase.changesets) {
            for (rollback in changeset.rollbacks) {
                val script = SqlParser.parse(rollback.sql)
                for (statement in script.statements) {
                    val tableName = when (statement) {
                        is DeleteStatement -> statement.tableName
                        is InsertStatement -> statement.tableName
                        else -> null
                    } ?: continue
                    val lower = tableName.lowercase()
                    if (lower !in tempTables && schema.findTable(lower) == null) {
                        sink.add(
                            Severity.WARNING, ProblemCategory.LIQUIBASE,
                            "Liquibase: rollback of changeset '${changeset.key}' references unknown table " +
                                "'$tableName'",
                            rollback.range,
                        )
                    }
                }
            }
        }
    }
}

/** Statement starters any target database (or Liquibase itself) understands. */
private val KNOWN_STATEMENT_WORDS = setOf(
    "SELECT", "WITH", "INSERT", "MERGE", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP",
    "TRUNCATE", "GRANT", "REVOKE", "COMMENT", "SET", "BEGIN", "DECLARE", "END", "CALL",
    "EXEC", "EXECUTE", "EXPLAIN", "ANALYZE", "VACUUM", "LOCK", "RENAME", "PURGE",
    "FLASHBACK", "SAVEPOINT", "COMMIT", "ROLLBACK", "USE", "SHOW", "COPY", "VALUES",
    "REFRESH", "DO", "REM", "PROMPT", "WHENEVER", "DEFINE", "UNDEFINE",
)

/** Candidates for typo suggestions. */
private val SUGGESTION_KEYWORDS = listOf(
    "INSERT", "MERGE", "CREATE", "UPDATE", "DELETE", "SELECT", "ALTER", "DROP",
    "TRUNCATE", "GRANT", "REVOKE", "COMMENT", "VALUES", "BEGIN", "DECLARE",
)
