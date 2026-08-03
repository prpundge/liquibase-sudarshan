package com.company.liquibasevalidator.database

import com.company.liquibasevalidator.liquibase.Changeset
import com.company.liquibasevalidator.schema.SchemaProvider
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.InsertStatement
import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.validation.FileAnalysis
import com.company.liquibasevalidator.validation.InsertValidator
import com.company.liquibasevalidator.validation.ProblemCategory
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.StagingFlow
import com.company.liquibasevalidator.validation.ValidationOptions
import com.company.liquibasevalidator.validation.ValidationProblem
import java.sql.SQLException

/**
 * Read-only "dry run" against a configured datasource: reports which changesets Liquibase
 * would execute (via DATABASECHANGELOG), evaluates SELECT-based preconditions, verifies
 * foreign-key references and primary-key conflicts for statically-known values, and builds
 * a per-row data preview (would this row INSERT or UPDATE?) in changeset order — like
 * previewing the effect in SQL Developer, without executing anything.
 *
 * Checks and the preview are limited to changesets that would actually run (pending,
 * runAlways, or runOnChange). Nothing is ever written — SELECTs only, on a read-only
 * connection.
 */
class LiquibaseDryRun(
    private val connector: DatabaseConnector,
    private val options: ValidationOptions,
) {

    data class FileInput(val fileId: String, val analysis: FileAnalysis)

    data class Finding(val fileId: String, val problem: ValidationProblem)

    data class PendingChangeset(val fileId: String, val key: String, val reason: String, val headerRange: SrcRange)

    enum class RowAction { INSERT, UPDATE, CONFLICT, UNKNOWN }

    enum class RunAction { RUN, SKIP, HALT, BLOCKED }

    /** One changeset in the simulated execution order — what `liquibase update` would do. */
    data class PlanStep(
        val fileId: String,
        val key: String,
        val order: Int,
        val action: RunAction,
        val reason: String,
        val statementCount: Int,
        val headerRange: SrcRange,
    )

    /** One statically-known data row and what would happen to it on the live database. */
    data class PreviewRow(
        val fileId: String,
        val changesetKey: String?,
        val tableName: String,
        val keyColumn: String,
        val keyValue: String,
        val action: RowAction,
        val range: SrcRange,
    )

    data class DryRunResult(
        val findings: List<Finding>,
        val pending: List<PendingChangeset>,
        val preview: List<PreviewRow>,
        /** Simulated `liquibase update` execution order across all files. */
        val plan: List<PlanStep>,
        /** False when DATABASECHANGELOG does not exist (fresh database). */
        val changelogTableFound: Boolean,
        val notes: List<String>,
    ) {
        val errorCount: Int get() = findings.count { it.problem.severity == Severity.ERROR }
    }

    fun run(files: List<FileInput>, schema: SchemaProvider): DryRunResult {
        val findings = mutableListOf<Finding>()
        val pending = mutableListOf<PendingChangeset>()
        val preview = mutableListOf<PreviewRow>()
        val notes = mutableListOf<String>()
        val haltedChangesets = mutableSetOf<Pair<String, String>>()
        var changelogFound = false
        var executedSet: Set<String>? = null

        connector.withSession { session ->
            val executed = session.executedChangesets()
            executedSet = executed
            changelogFound = executed != null
            if (executed == null) {
                notes += "DATABASECHANGELOG not found — fresh database, every changeset is pending"
            }

            for (file in files) {
                val gate = ExecutionGate(file.analysis, executed)
                collectPending(file, executed, pending)
                checkPreconditions(file, gate, session, findings, haltedChangesets)
                if (options.validateForeignKeys) checkForeignKeys(file, gate, schema, session, findings)
                if (options.validateDuplicates) checkPrimaryKeyConflicts(file, gate, schema, session, findings, preview)
                buildStagingPreview(file, gate, session, preview)
            }
        }
        if (preview.size > MAX_PREVIEW_ROWS) {
            notes += "Data preview truncated to $MAX_PREVIEW_ROWS rows (${preview.size} total)"
        }
        return DryRunResult(
            findings, pending, preview.take(MAX_PREVIEW_ROWS),
            buildPlan(files, executedSet, haltedChangesets),
            changelogFound, notes,
        )
    }

    /** Simulates the update run: RUN / SKIP (already executed) / HALT (failing precondition,
     *  onFail:HALT) — and everything after a HALT is BLOCKED, exactly as Liquibase behaves. */
    private fun buildPlan(
        files: List<FileInput>,
        executed: Set<String>?,
        halted: Set<Pair<String, String>>,
    ): List<PlanStep> {
        val plan = mutableListOf<PlanStep>()
        var order = 0
        var blocked = false
        for (file in files) {
            for (changeset in file.analysis.liquibase.changesets) {
                order++
                val statementCount = file.analysis.script.statements.count {
                    it.range.start >= changeset.bodyRange.start && it.range.start < changeset.bodyRange.end
                }
                val alreadyExecuted = executed != null && changeset.key.lowercase() in executed
                val runAlways = changeset.attributes["runalways"].equals("true", true)
                val (action, reason) = when {
                    alreadyExecuted && !runAlways && !changeset.runOnChange ->
                        RunAction.SKIP to "already in DATABASECHANGELOG"
                    blocked -> RunAction.BLOCKED to "blocked by an earlier HALT — will not run"
                    (file.fileId to changeset.key) in halted ->
                        RunAction.HALT to "precondition fails against the live database (onFail:HALT)"
                    alreadyExecuted && runAlways -> RunAction.RUN to "runAlways:true"
                    alreadyExecuted && changeset.runOnChange ->
                        RunAction.RUN to "runOnChange:true — runs only if its content changed"
                    else -> RunAction.RUN to
                        (if (executed == null) "fresh database" else "not in DATABASECHANGELOG yet")
                }
                if (action == RunAction.HALT) blocked = true
                plan += PlanStep(
                    file.fileId, changeset.key, order, action, reason, statementCount,
                    changeset.headerRange,
                )
            }
        }
        return plan
    }

    // ---------------------------------------------------------------------------------------
    // Execution gating: only changesets Liquibase would actually run are checked/previewed.
    // Identity approximation: Liquibase's full key is (id, author, filename); author:id is
    // used here, which is unique in well-formed repositories.
    // ---------------------------------------------------------------------------------------

    private class ExecutionGate(private val analysis: FileAnalysis, private val executed: Set<String>?) {
        fun appliesTo(changeset: Changeset): Boolean {
            val executedSet = executed ?: return true
            if (changeset.key.lowercase() !in executedSet) return true
            return changeset.attributes["runalways"].equals("true", true) || changeset.runOnChange
        }

        /** For statements located by offset; SQL outside any changeset is always checked. */
        fun appliesAt(offset: Int): Boolean {
            val changeset = analysis.liquibase.changesetAt(offset) ?: return true
            return appliesTo(changeset)
        }

        fun changesetKeyAt(offset: Int): String? = analysis.liquibase.changesetAt(offset)?.key
    }

    private fun collectPending(
        file: FileInput,
        executed: Set<String>?,
        pending: MutableList<PendingChangeset>,
    ) {
        for (changeset in file.analysis.liquibase.changesets) {
            val reason = when {
                executed == null -> "fresh database"
                changeset.key.lowercase() !in executed -> "not in DATABASECHANGELOG yet"
                changeset.attributes["runalways"].equals("true", true) -> "runAlways:true — executes on every update"
                else -> null
            }
            if (reason != null) {
                pending += PendingChangeset(file.fileId, changeset.key, reason, changeset.headerRange)
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun checkPreconditions(
        file: FileInput,
        gate: ExecutionGate,
        session: DatabaseSession,
        findings: MutableList<Finding>,
        haltedChangesets: MutableSet<Pair<String, String>>,
    ) {
        for (changeset in file.analysis.liquibase.changesets) {
            if (!gate.appliesTo(changeset)) continue
            val preconditions = changeset.preconditions ?: continue
            for (check in preconditions.sqlChecks) {
                val sql = check.sql.trim()
                if (!sql.lowercase().startsWith("select")) {
                    findings += Finding(
                        file.fileId,
                        ValidationProblem(
                            Severity.WARNING, ProblemCategory.DATABASE,
                            "Liquibase: dry run can only evaluate SELECT preconditions — skipped: $sql",
                            check.range,
                        ),
                    )
                    continue
                }
                val actual = try {
                    session.scalarSelect(sql)
                } catch (e: SQLException) {
                    findings += Finding(
                        file.fileId,
                        ValidationProblem(
                            Severity.WARNING, ProblemCategory.DATABASE,
                            "Liquibase: precondition query failed during dry run: ${e.message}",
                            check.range,
                        ),
                    )
                    continue
                }
                val expected = check.expectedResult ?: continue
                val onFail = preconditions.onFail ?: "HALT"
                if (actual == null) {
                    findings += Finding(
                        file.fileId,
                        ValidationProblem(
                            Severity.WARNING, ProblemCategory.DATABASE,
                            "Liquibase: precondition query returned no rows — Liquibase treats this as a " +
                                "failed precondition (changeset '${changeset.key}', onFail: $onFail)",
                            check.range,
                        ),
                    )
                } else if (!resultsMatch(expected, actual)) {
                    val halts = onFail.equals("HALT", true)
                    if (halts) haltedChangesets += file.fileId to changeset.key
                    findings += Finding(
                        file.fileId,
                        ValidationProblem(
                            if (halts) Severity.ERROR else Severity.WARNING,
                            ProblemCategory.DATABASE,
                            "Liquibase: precondition expects '$expected' but the database returned '$actual' — " +
                                "changeset '${changeset.key}' would ${onFail.uppercase()} on update",
                            check.range,
                        ),
                    )
                }
            }
        }
    }

    private fun resultsMatch(expected: String, actual: String): Boolean {
        val e = expected.trim()
        val a = actual.trim()
        if (e.equals(a, ignoreCase = true)) return true
        val en = e.toBigDecimalOrNull()
        val an = a.toBigDecimalOrNull()
        return en != null && an != null && en.compareTo(an) == 0
    }

    // ---------------------------------------------------------------------------------------

    private fun checkForeignKeys(
        file: FileInput,
        gate: ExecutionGate,
        schema: SchemaProvider,
        session: DatabaseSession,
        findings: MutableList<Finding>,
    ) {
        for (probe in collectForeignKeyProbes(file.analysis, schema)) {
            if (!gate.appliesAt(probe.range.start)) continue
            val exists = try {
                session.rowExists(probe.refTable, probe.refColumn, probe.value)
            } catch (_: SQLException) {
                null // cannot probe (e.g. ref table not deployed yet): stay silent
            }
            if (exists == false) {
                findings += Finding(
                    file.fileId,
                    ValidationProblem(
                        Severity.WARNING, ProblemCategory.DATABASE,
                        "Liquibase: foreign key check — value '${probe.value}' for " +
                            "'${probe.table}.${probe.column}' has no matching row in " +
                            "'${probe.refTable}.${probe.refColumn}' in the connected database",
                        probe.range,
                    ),
                )
            }
        }
    }

    /** Direct INSERTs: an existing PK row means the INSERT fails; also feeds the preview. */
    private fun checkPrimaryKeyConflicts(
        file: FileInput,
        gate: ExecutionGate,
        schema: SchemaProvider,
        session: DatabaseSession,
        findings: MutableList<Finding>,
        preview: MutableList<PreviewRow>,
    ) {
        for (insert in file.analysis.directInserts) {
            val table = schema.findTable(insert.tableName) ?: continue
            val pk = singleColumnKey(table) ?: continue
            val resolved = insert.columns?.map { it.nameLower } ?: table.columns.map { it.nameLower }
            for (row in insert.rows) {
                val value = InsertValidator.staticRowValues(resolved, row.values)[pk] ?: continue
                if (!gate.appliesAt(value.second.start)) continue
                val exists = try {
                    session.rowExists(table.name, pk, value.first)
                } catch (_: SQLException) {
                    null
                }
                preview += PreviewRow(
                    file.fileId, gate.changesetKeyAt(value.second.start), table.name, pk, value.first,
                    when (exists) {
                        true -> RowAction.CONFLICT
                        false -> RowAction.INSERT
                        null -> RowAction.UNKNOWN
                    },
                    value.second,
                )
                if (exists == true) {
                    findings += Finding(
                        file.fileId,
                        ValidationProblem(
                            Severity.WARNING, ProblemCategory.DATABASE,
                            "Liquibase: a row with $pk='${value.first}' already exists in " +
                                "'${table.name}' — this INSERT would fail (consider a MERGE upsert)",
                            value.second,
                        ),
                    )
                }
            }
        }
    }

    /** Staged MERGE flows: each staged row previews as UPDATE (key exists) or INSERT. */
    private fun buildStagingPreview(
        file: FileInput,
        gate: ExecutionGate,
        session: DatabaseSession,
        preview: MutableList<PreviewRow>,
    ) {
        for (flow in file.analysis.stagingFlows) {
            val tempColumns = flow.tempTable.columns.map { it.nameLower }
            for (mapping in flow.mappings) {
                val target = mapping.targetSchema ?: continue
                val targetKey = singleColumnKey(target) ?: continue
                val inverse = mapping.columnMap.entries
                    .flatMap { (staging, targets) -> targets.map { it to staging } }
                    .toMap()
                val stagingKey = inverse[targetKey] ?: continue
                for (insert in flow.inserts) {
                    val resolved = insert.columns?.map { it.nameLower } ?: tempColumns
                    for (row in insert.rows) {
                        val value = InsertValidator.staticRowValues(resolved, row.values)[stagingKey] ?: continue
                        if (!gate.appliesAt(value.second.start)) continue
                        val exists = try {
                            session.rowExists(target.name, targetKey, value.first)
                        } catch (_: SQLException) {
                            null
                        }
                        preview += PreviewRow(
                            file.fileId, gate.changesetKeyAt(value.second.start), target.name,
                            targetKey, value.first,
                            when (exists) {
                                true -> RowAction.UPDATE
                                false -> RowAction.INSERT
                                null -> RowAction.UNKNOWN
                            },
                            value.second,
                        )
                    }
                }
            }
        }
    }

    private fun singleColumnKey(table: TableSchema): String? =
        table.uniqueKeys().firstOrNull { it.size == 1 }?.first()

    private data class FkProbe(
        val table: String,
        val column: String,
        val refTable: String,
        val refColumn: String,
        val value: String,
        val range: SrcRange,
    )

    private fun collectForeignKeyProbes(analysis: FileAnalysis, schema: SchemaProvider): List<FkProbe> {
        val probes = mutableListOf<FkProbe>()
        for (insert in analysis.directInserts) {
            val table = schema.findTable(insert.tableName) ?: continue
            probes += insertProbes(insert, table, insert.columns?.map { it.nameLower }
                ?: table.columns.map { it.nameLower }) { fk -> fk }
        }
        for (flow in analysis.stagingFlows) {
            probes += stagingProbes(flow)
        }
        return probes.distinctBy { listOf(it.refTable, it.refColumn, it.value) }
    }

    private fun insertProbes(
        insert: InsertStatement,
        table: TableSchema,
        resolved: List<String>,
        columnMapper: (String) -> String,
    ): List<FkProbe> {
        val probes = mutableListOf<FkProbe>()
        for (row in insert.rows) {
            val values = InsertValidator.staticRowValues(resolved, row.values)
            for (fk in table.foreignKeys()) {
                if (fk.refTable == null || fk.columns.size != 1 || fk.refColumns.size != 1) continue
                val value = values[columnMapper(fk.columns.first().lowercase())] ?: continue
                probes += FkProbe(
                    table.name, fk.columns.first(), fk.refTable, fk.refColumns.first(),
                    value.first, value.second,
                )
            }
        }
        return probes
    }

    private fun stagingProbes(flow: StagingFlow): List<FkProbe> {
        val probes = mutableListOf<FkProbe>()
        val tempColumns = flow.tempTable.columns.map { it.nameLower }
        for (mapping in flow.mappings) {
            val target = mapping.targetSchema ?: continue
            val inverse = mapping.columnMap.entries
                .flatMap { (staging, targets) -> targets.map { it to staging } }
                .toMap()
            for (insert in flow.inserts) {
                probes += insertProbes(insert, target, insert.columns?.map { it.nameLower } ?: tempColumns) {
                    inverse[it] ?: " missing" // unmapped: never matches a static value
                }
            }
        }
        return probes
    }

    private companion object {
        const val MAX_PREVIEW_ROWS = 500
    }
}
