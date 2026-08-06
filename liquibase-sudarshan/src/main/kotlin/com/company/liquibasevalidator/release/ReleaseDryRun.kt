package com.company.liquibasevalidator.release

import com.company.liquibasevalidator.database.DatabaseConnector
import com.company.liquibasevalidator.database.DatabaseSession
import com.company.liquibasevalidator.database.LiquibaseDryRun
import com.company.liquibasevalidator.liquibase.Changeset
import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.CreateTableStatement
import com.company.liquibasevalidator.sql.SqlParser
import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.validation.FileAnalysis
import com.company.liquibasevalidator.validation.InsertValidator
import com.company.liquibasevalidator.validation.ValidationEngine
import com.company.liquibasevalidator.validation.ValidationOptions
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.readText

/**
 * Country/environment release dry run against one live server: assembles the exact ordered
 * Jenkins run ([ReleaseAssembler]), reuses the read-only [LiquibaseDryRun] for the
 * execution plan (RUN / SKIP when the changeset is already in DATABASECHANGELOG from a
 * previous release / HALT / BLOCKED), and adds a **per-column comparison** of every
 * statically-known data row: the value the repository branch would write next to the value
 * currently present in the database. Strictly read-only — nothing is ever executed.
 */
class ReleaseDryRun(
    private val options: ValidationOptions = ValidationOptions(),
    private val config: ReleaseConfig = ReleaseConfig(),
) {

    /** What happens to one data row when this release reaches the connected database. */
    enum class RowFate {
        /** Row does not exist in the database — the release inserts it. */
        INSERT,
        /** Row exists with different values — a MERGE updates it (see the diffs). */
        UPDATE,
        /** Row exists and every compared column already matches the branch. */
        SAME,
        /** Row exists but a direct INSERT would collide on the key — the release fails. */
        CONFLICT,
        /** Changeset already executed in a previous release — Liquibase will not run it again. */
        SKIP,
        /** The database could not be probed for this row (no permission, missing table…). */
        UNKNOWN,
    }

    data class ColumnDiff(val column: String, val branchValue: String?, val databaseValue: String?) {
        val changed: Boolean get() = (branchValue ?: "").trim() != (databaseValue ?: "").trim()
    }

    data class RowComparison(
        val fileId: String,
        val changesetKey: String?,
        val tableName: String,
        val keyColumn: String,
        val keyValue: String,
        val fate: RowFate,
        /** Branch vs database value per column, in branch column order. */
        val diffs: List<ColumnDiff>,
        val range: SrcRange,
    ) {
        val changedColumns: Int get() = diffs.count { it.changed }
    }

    // ---------------------------------------------------------------------------------------
    // Per-table difference: branch DDL vs the live database — for EVERY table on either side.
    // ---------------------------------------------------------------------------------------

    enum class TableStatus {
        /** Defined by the branch, absent in the database — this release creates it. */
        NEW,
        /** Exists in the database but the branch DDL does not define it (drift). */
        DATABASE_ONLY,
        /** Same columns, types and nullability on both sides. */
        MATCH,
        /** Present on both sides with column-level differences (see the deltas). */
        DIFFERENT,
    }

    enum class DeltaKind { MISSING_IN_DATABASE, DATABASE_ONLY, TYPE, NULLABILITY }

    data class ColumnDelta(
        val column: String,
        val kind: DeltaKind,
        val branchValue: String?,
        val databaseValue: String?,
    )

    /** What this release's statically-known rows do to one table. */
    data class RowImpact(
        val inserts: Int, val updates: Int, val same: Int,
        val conflicts: Int, val skips: Int, val unknown: Int,
    ) {
        val total: Int get() = inserts + updates + same + conflicts + skips + unknown
    }

    data class TableDiff(
        val tableName: String,
        val status: TableStatus,
        val deltas: List<ColumnDelta>,
        /** Current row count in the database; null when absent or not countable. */
        val databaseRowCount: Long?,
        val rowImpact: RowImpact,
    )

    data class Result(
        val country: String,
        val environment: String,
        val manifest: List<ManifestEntry>,
        val plan: List<LiquibaseDryRun.PlanStep>,
        val comparisons: List<RowComparison>,
        /** One entry for EVERY table in the branch DDL or the database, sorted by name. */
        val tableDiffs: List<TableDiff>,
        val findings: List<LiquibaseDryRun.Finding>,
        val pending: List<LiquibaseDryRun.PendingChangeset>,
        /** False when DATABASECHANGELOG does not exist (fresh database). */
        val changelogTableFound: Boolean,
        val notes: List<String>,
    ) {
        val wouldExecute: Int get() = plan.count { it.action == LiquibaseDryRun.RunAction.RUN }
        val wouldSkip: Int get() = plan.count { it.action == LiquibaseDryRun.RunAction.SKIP }
    }

    fun run(root: Path, country: String, environment: String, connector: DatabaseConnector): Result {
        val manifest = ReleaseAssembler(root, config).assemble(country, environment)
        val texts = manifest.associateWith { it.path.readText() }

        // Schema truth for the comparison: the live database wins; repository DDL fills in
        // tables the target schema does not have yet (they will be created by this release).
        val liveTables = connector.withSession { it.fetchTables() }
        val repoTables = HashMap<String, TableSchema>()
        for ((entry, text) in texts) {
            for (create in SqlParser.parse(text).statementsOf<CreateTableStatement>()) {
                if (!create.temporary && create.tableNameLower !in repoTables) {
                    repoTables[create.tableNameLower] =
                        DdlSchemaBuilder.toTableSchema(create, entry.path.toString())
                }
            }
        }
        val provider = MapSchemaProvider(HashMap(repoTables + liveTables))

        val engine = ValidationEngine(options)
        val analyzed = manifest.map { entry ->
            val fileId = (root.relativize(entry.path).toString().replace('\\', '/'))
            Triple(entry, fileId, engine.validate(texts.getValue(entry), provider).analysis)
        }

        val dryRun = LiquibaseDryRun(connector, options).run(
            analyzed.map { (_, fileId, analysis) -> LiquibaseDryRun.FileInput(fileId, analysis) },
            provider,
        )

        val (comparisons, rowCounts) = connector.withSession { session ->
            val executed = session.executedChangesets()
            val rows = analyzed.flatMap { (_, fileId, analysis) ->
                compareFile(fileId, analysis, provider, session, executed)
            }
            val counts = liveTables.keys.associateWith { table ->
                try {
                    session.rowCount(table)
                } catch (_: SQLException) {
                    null
                }
            }
            rows to counts
        }

        return Result(
            country, environment, manifest, dryRun.plan, comparisons,
            diffTables(repoTables, liveTables, comparisons, rowCounts),
            dryRun.findings, dryRun.pending, dryRun.changelogTableFound, dryRun.notes,
        )
    }

    // ---------------------------------------------------------------------------------------

    private fun diffTables(
        repoTables: Map<String, TableSchema>,
        liveTables: Map<String, TableSchema>,
        comparisons: List<RowComparison>,
        rowCounts: Map<String, Long?>,
    ): List<TableDiff> = (repoTables.keys + liveTables.keys).toSortedSet().map { name ->
        val repo = repoTables[name]
        val live = liveTables[name]
        val impact = rowImpactFor(comparisons, name)
        when {
            live == null -> TableDiff(name, TableStatus.NEW, emptyList(), null, impact)
            repo == null -> TableDiff(name, TableStatus.DATABASE_ONLY, emptyList(), rowCounts[name], impact)
            else -> {
                val deltas = columnDeltas(repo, live)
                TableDiff(
                    name, if (deltas.isEmpty()) TableStatus.MATCH else TableStatus.DIFFERENT,
                    deltas, rowCounts[name], impact,
                )
            }
        }
    }

    private fun columnDeltas(repo: TableSchema, live: TableSchema): List<ColumnDelta> {
        val deltas = mutableListOf<ColumnDelta>()
        for (column in repo.columns) {
            val liveColumn = live.findColumn(column.name)
            if (liveColumn == null) {
                deltas += ColumnDelta(column.nameLower, DeltaKind.MISSING_IN_DATABASE, column.dataType.display(), null)
                continue
            }
            if (!column.dataType.display().equals(liveColumn.dataType.display(), ignoreCase = true)) {
                deltas += ColumnDelta(
                    column.nameLower, DeltaKind.TYPE,
                    column.dataType.display(), liveColumn.dataType.display(),
                )
            }
            if (column.nullable != liveColumn.nullable) {
                deltas += ColumnDelta(
                    column.nameLower, DeltaKind.NULLABILITY,
                    if (column.nullable) "NULL" else "NOT NULL",
                    if (liveColumn.nullable) "NULL" else "NOT NULL",
                )
            }
        }
        for (liveColumn in live.columns) {
            if (repo.findColumn(liveColumn.name) == null) {
                deltas += ColumnDelta(liveColumn.nameLower, DeltaKind.DATABASE_ONLY, null, liveColumn.dataType.display())
            }
        }
        return deltas
    }

    private fun rowImpactFor(comparisons: List<RowComparison>, table: String): RowImpact {
        val rows = comparisons.filter { it.tableName.lowercase() == table }
        return RowImpact(
            inserts = rows.count { it.fate == RowFate.INSERT },
            updates = rows.count { it.fate == RowFate.UPDATE },
            same = rows.count { it.fate == RowFate.SAME },
            conflicts = rows.count { it.fate == RowFate.CONFLICT },
            skips = rows.count { it.fate == RowFate.SKIP },
            unknown = rows.count { it.fate == RowFate.UNKNOWN },
        )
    }

    // ---------------------------------------------------------------------------------------

    /** Same skip rule Liquibase applies: already executed and neither runAlways nor runOnChange. */
    private fun willRunAgain(changeset: Changeset?, executed: Set<String>?): Boolean {
        if (changeset == null || executed == null) return true
        if (changeset.key.lowercase() !in executed) return true
        return changeset.attributes["runalways"].equals("true", true) || changeset.runOnChange
    }

    private fun compareFile(
        fileId: String,
        analysis: FileAnalysis,
        provider: MapSchemaProvider,
        session: DatabaseSession,
        executed: Set<String>?,
    ): List<RowComparison> {
        val comparisons = mutableListOf<RowComparison>()

        // Direct INSERTs: the branch writes exactly these values; an existing key collides.
        for (insert in analysis.directInserts) {
            val table = provider.findTable(insert.tableName) ?: continue
            val key = table.uniqueKeys().firstOrNull { it.size == 1 }?.first() ?: continue
            val resolved = insert.columns?.map { it.nameLower } ?: table.columns.map { it.nameLower }
            for (row in insert.rows) {
                val values = InsertValidator.staticRowValues(resolved, row.values)
                val keyValue = values[key] ?: continue
                val changeset = analysis.liquibase.changesetAt(keyValue.second.start)
                val branchRow = resolved.mapNotNull { col -> values[col]?.let { col to it.first } }.toMap()
                comparisons += compareRow(
                    fileId, changeset, table.name, key, keyValue.first, branchRow,
                    directInsert = true, session = session, executed = executed,
                    range = keyValue.second,
                )
            }
        }

        // Staged MERGE flows: staging values land in the target through the column mapping.
        for (flow in analysis.stagingFlows) {
            val tempColumns = flow.tempTable.columns.map { it.nameLower }
            for (mapping in flow.mappings) {
                val target = mapping.targetSchema ?: continue
                val targetKey = target.uniqueKeys().firstOrNull { it.size == 1 }?.first() ?: continue
                val inverse = mapping.columnMap.entries
                    .flatMap { (staging, targets) -> targets.map { it to staging } }
                    .toMap()
                val stagingKey = inverse[targetKey] ?: continue
                for (insert in flow.inserts) {
                    val resolved = insert.columns?.map { it.nameLower } ?: tempColumns
                    for (row in insert.rows) {
                        val values = InsertValidator.staticRowValues(resolved, row.values)
                        val keyValue = values[stagingKey] ?: continue
                        val changeset = analysis.liquibase.changesetAt(keyValue.second.start)
                        val branchRow = LinkedHashMap<String, String>()
                        for ((staging, targets) in mapping.columnMap) {
                            val value = values[staging] ?: continue
                            for (targetColumn in targets) branchRow[targetColumn] = value.first
                        }
                        comparisons += compareRow(
                            fileId, changeset, target.name, targetKey, keyValue.first, branchRow,
                            directInsert = false, session = session, executed = executed,
                            range = keyValue.second,
                        )
                    }
                }
            }
        }
        return comparisons
    }

    private fun compareRow(
        fileId: String,
        changeset: Changeset?,
        tableName: String,
        keyColumn: String,
        keyValue: String,
        branchRow: Map<String, String>,
        directInsert: Boolean,
        session: DatabaseSession,
        executed: Set<String>?,
        range: SrcRange,
    ): RowComparison {
        val databaseRow = try {
            session.selectRowByKey(tableName, keyColumn, keyValue)
        } catch (_: SQLException) {
            null
        }
        val exists = if (databaseRow != null) {
            true
        } else {
            try {
                session.rowExists(tableName, keyColumn, keyValue)
            } catch (_: SQLException) {
                null
            }
        }
        val diffs = branchRow.map { (column, branchValue) ->
            ColumnDiff(column, branchValue, databaseRow?.get(column))
        }
        val fate = when {
            !willRunAgain(changeset, executed) -> RowFate.SKIP
            exists == null -> RowFate.UNKNOWN
            exists == false -> RowFate.INSERT
            directInsert -> RowFate.CONFLICT
            diffs.any { it.changed } -> RowFate.UPDATE
            else -> RowFate.SAME
        }
        return RowComparison(fileId, changeset?.key, tableName, keyColumn, keyValue, fate, diffs, range)
    }
}
