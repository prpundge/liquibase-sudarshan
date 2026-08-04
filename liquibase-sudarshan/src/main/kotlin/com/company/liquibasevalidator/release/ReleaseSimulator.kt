package com.company.liquibasevalidator.release

import com.company.liquibasevalidator.liquibase.Changeset
import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.AlterTableAddConstraint
import com.company.liquibasevalidator.sql.CreateIndexStatement
import com.company.liquibasevalidator.sql.CreateTableStatement
import com.company.liquibasevalidator.sql.DeleteStatement
import com.company.liquibasevalidator.sql.DropStatement
import com.company.liquibasevalidator.sql.InsertStatement
import com.company.liquibasevalidator.sql.MergeStatement
import com.company.liquibasevalidator.sql.SqlParser
import com.company.liquibasevalidator.sql.SqlScript
import com.company.liquibasevalidator.sql.SqlStatement
import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.sql.TableConstraintDef
import com.company.liquibasevalidator.sql.TruncateStatement
import com.company.liquibasevalidator.sql.UpdateStatement
import com.company.liquibasevalidator.validation.InsertValidator
import com.company.liquibasevalidator.validation.ProblemCategory
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.company.liquibasevalidator.validation.ValidationOptions
import com.company.liquibasevalidator.validation.ValidationProblem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.toList

data class ManifestEntry(val order: Int, val stage: ReleaseStage, val path: Path)

/**
 * Selects the exact ordered file list Jenkins would execute for (country, environment):
 * the six stages of requirements §1, files within a stage in [SqlFileOrder].
 * PROD never receives environment SQL (requirements A4, configurable by layout).
 */
class ReleaseAssembler(private val root: Path, private val config: ReleaseConfig) {

    fun availableCountries(): List<String> {
        val dir = root.resolve(config.countryRoot)
        if (!dir.isDirectory()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.isDirectory() }.map { it.name }.sorted().toList()
        }
    }

    fun assemble(country: String, environment: String): List<ManifestEntry> {
        val directories = mutableListOf<Pair<ReleaseStage, Path>>(
            ReleaseStage.GLOBAL_DDL to root.resolve(config.globalDdl),
            ReleaseStage.GLOBAL_STATIC to root.resolve(config.globalStatic),
            ReleaseStage.COUNTRY_STATIC to
                root.resolve(config.countryRoot).resolve(country).resolve(config.countryStaticSub),
            ReleaseStage.GLOBAL_UPDATE to root.resolve(config.globalUpdate),
            ReleaseStage.COUNTRY_UPDATE to
                root.resolve(config.countryRoot).resolve(country).resolve(config.countryUpdateSub),
        )
        if (!config.isProd(environment)) {
            val envDir = root.resolve(config.environmentsRoot).resolve(environment)
            directories += ReleaseStage.ENVIRONMENT to envDir
            directories += ReleaseStage.ENVIRONMENT to
                envDir.resolve("countries").resolve(country) // optional per-country subfolder
        }

        val manifest = mutableListOf<ManifestEntry>()
        var order = 0
        for ((stage, dir) in directories) {
            if (!dir.isDirectory()) continue
            val files = Files.list(dir).use { stream ->
                stream.filter { !it.isDirectory() && it.extension.equals("sql", true) }.toList()
            }.sortedWith(compareBy(SqlFileOrder) { it.name })
            for (file in files) {
                manifest += ManifestEntry(++order, stage, file)
            }
        }
        return manifest
    }
}

/**
 * Simulates the release: validates every file in execution order against the schema
 * **as it exists at that point of the run** (sequential schema accumulation), then runs
 * whole-release cross-file checks and per-environment policy guardrails.
 * Pure static analysis — nothing executes.
 */
class ReleaseSimulator(
    private val options: ValidationOptions = ValidationOptions(),
    private val config: ReleaseConfig = ReleaseConfig(),
) {

    data class FileReport(
        val entry: ManifestEntry,
        val text: String,
        val changesetCount: Int,
        val problems: List<ValidationProblem>,
        val analysis: com.company.liquibasevalidator.validation.FileAnalysis,
    )

    data class Result(
        val country: String,
        val environment: String,
        val manifest: List<ManifestEntry>,
        val reports: List<FileReport>,
    ) {
        val errorCount: Int get() = reports.sumOf { r -> r.problems.count { it.severity == Severity.ERROR } }
    }

    fun simulate(root: Path, country: String, environment: String, baseSchema: Map<String, TableSchema> = emptyMap()): Result {
        val manifest = ReleaseAssembler(root, config).assemble(country, environment)
        val engine = ValidationEngine(options)

        data class ParsedFile(val entry: ManifestEntry, val text: String, val script: SqlScript)
        val parsed = manifest.map { entry ->
            val text = entry.path.readText()
            ParsedFile(entry, text, SqlParser.parse(text))
        }

        // pre-scan: where (in run order) is each table defined?
        data class Definition(val entry: ManifestEntry, val range: SrcRange)
        val definedAt = HashMap<String, Definition>()
        for (file in parsed) {
            for (create in file.script.statementsOf<CreateTableStatement>().filter { !it.temporary }) {
                definedAt.putIfAbsent(create.tableNameLower, Definition(file.entry, create.tableNameRange))
            }
        }

        val accumulated = LinkedHashMap(baseSchema)
        val reports = mutableListOf<FileReport>()
        val crossFile = CrossFileState()

        for (file in parsed) {
            val problems = mutableListOf<ValidationProblem>()
            val pointInTime = MapSchemaProvider(HashMap(accumulated))

            // per-file validation against the schema as of THIS point of the run
            val result = engine.validate(file.text, pointInTime)
            problems += result.problems

            // used-before-created: referenced now, defined by a LATER file
            for ((tableLower, range) in referencedSchemaTables(file.script)) {
                if (accumulated.containsKey(tableLower)) continue
                val definition = definedAt[tableLower] ?: continue
                if (definition.entry.order > file.entry.order) {
                    problems += ValidationProblem(
                        Severity.ERROR, ProblemCategory.SCHEMA,
                        "Liquibase: table '$tableLower' is used before its DDL runs — it is created in " +
                            "step ${definition.entry.order} (${definition.entry.path.name}, " +
                            "${definition.entry.stage.display}); Jenkins executes this file first and fails",
                        range,
                    )
                }
            }

            // apply this file's DDL to the accumulated schema (defined-twice detection inside)
            applyDdl(file.entry, file.script, accumulated, definedAt.mapValues { it.value.entry }, problems)

            // whole-release collection for cross-file checks
            crossFile.collect(file.entry, result.analysis, pointInTime, problems)

            // environment policy guardrails
            PolicyChecks.apply(config, environment, file.script, result.analysis.liquibase.changesets, problems)

            reports += FileReport(
                file.entry, file.text, result.analysis.liquibase.changesets.size, problems, result.analysis,
            )
        }

        return Result(country, environment, manifest, reports)
    }

    // ---------------------------------------------------------------------------------------

    private fun referencedSchemaTables(script: SqlScript): List<Pair<String, SrcRange>> {
        val temps = script.statementsOf<CreateTableStatement>().filter { it.temporary }
            .map { it.tableNameLower }.toSet()
        val refs = mutableListOf<Pair<String, SrcRange>>()

        // FOREIGN KEY targets: an FK to a table whose CREATE runs later fails immediately
        fun foreignKeyRefs(owner: String, constraints: List<TableConstraintDef>) {
            for (constraint in constraints) {
                val target = constraint.refTable?.lowercase() ?: continue
                if (target != owner && target !in temps) refs += target to constraint.range
            }
        }

        for (statement in script.statements) {
            when (statement) {
                is InsertStatement ->
                    if (statement.tableNameLower !in temps) refs += statement.tableNameLower to statement.tableNameRange
                is MergeStatement -> {
                    refs += statement.targetTableLower to statement.targetTableRange
                    statement.sourceTableLower?.let {
                        if (it !in temps) refs += it to (statement.sourceTableRange ?: statement.range)
                    }
                }
                is UpdateStatement -> refs += statement.tableNameLower to statement.tableNameRange
                is DeleteStatement -> refs += statement.tableName.lowercase() to statement.tableNameRange
                is TruncateStatement -> refs += statement.tableName.lowercase() to statement.tableNameRange
                is CreateTableStatement -> if (!statement.temporary) {
                    foreignKeyRefs(statement.tableNameLower, statement.constraints)
                    for (column in statement.columns) {
                        val target = column.references?.table?.lowercase() ?: continue
                        if (target != statement.tableNameLower && target !in temps) refs += target to column.range
                    }
                }
                is AlterTableAddConstraint -> foreignKeyRefs(statement.tableNameLower, statement.constraints)
                else -> Unit
            }
        }
        return refs
    }

    private fun applyDdl(
        entry: ManifestEntry,
        script: SqlScript,
        accumulated: MutableMap<String, TableSchema>,
        firstDefinition: Map<String, ManifestEntry>,
        problems: MutableList<ValidationProblem>,
    ) {
        for (statement in script.statements) {
            when (statement) {
                is CreateTableStatement -> if (!statement.temporary) {
                    val existing = accumulated[statement.tableNameLower]
                    if (existing != null) {
                        val first = firstDefinition[statement.tableNameLower]
                        problems += ValidationProblem(
                            Severity.ERROR, ProblemCategory.SCHEMA,
                            "Liquibase: table '${statement.tableName}' is defined more than once in this " +
                                "release" + (first?.let { " (first in step ${it.order}, ${it.path.name})" } ?: "") +
                                " — the CREATE will fail at execution",
                            statement.tableNameRange,
                        )
                    } else {
                        accumulated[statement.tableNameLower] =
                            DdlSchemaBuilder.toTableSchema(statement, entry.path.toString())
                    }
                }
                is AlterTableAddConstraint -> {
                    val table = accumulated[statement.tableNameLower]
                    if (table == null) {
                        problems += ValidationProblem(
                            Severity.ERROR, ProblemCategory.SCHEMA,
                            "Liquibase: ALTER TABLE '${statement.tableName}' runs before the table exists " +
                                "at this point of the release",
                            statement.range,
                        )
                    } else {
                        accumulated[statement.tableNameLower] = table.copy(
                            constraints = table.constraints +
                                statement.constraints.mapNotNull(DdlSchemaBuilder::toConstraintSchema),
                        )
                    }
                }
                is CreateIndexStatement -> if (statement.unique) {
                    accumulated[statement.tableNameLower]?.let { table ->
                        accumulated[statement.tableNameLower] = table.copy(
                            constraints = table.constraints + com.company.liquibasevalidator.schema.ConstraintSchema(
                                com.company.liquibasevalidator.schema.ConstraintKind.UNIQUE,
                                statement.indexName, statement.columns,
                            ),
                        )
                    }
                }
                is DropStatement -> if (statement.objectKind == "TABLE") {
                    accumulated.remove(statement.nameLower)
                }
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Cross-file duplicate changeset ids and duplicate static data. */
    private inner class CrossFileState {
        private val changesetKeys = HashMap<String, ManifestEntry>()
        private val directKeys = HashMap<String, Triple<ManifestEntry, String, SrcRange>>() // table|value
        private val mergedKeys = HashMap<String, ManifestEntry>()

        fun collect(
            entry: ManifestEntry,
            analysis: com.company.liquibasevalidator.validation.FileAnalysis,
            schemaAtPoint: MapSchemaProvider,
            problems: MutableList<ValidationProblem>,
        ) {
            for (changeset in analysis.liquibase.changesets) {
                val previous = changesetKeys.putIfAbsent(changeset.key.lowercase(), entry)
                if (previous != null && previous.path != entry.path) {
                    problems += ValidationProblem(
                        Severity.ERROR, ProblemCategory.LIQUIBASE,
                        "Liquibase: changeset '${changeset.key}' is already declared in step " +
                            "${previous.order} (${previous.path.name}) — duplicate ids across the release " +
                            "abort the whole Liquibase run",
                        changeset.headerRange,
                    )
                }
            }

            // direct inserts: PK collisions across files are hard errors
            for (insert in analysis.directInserts) {
                val table = schemaAtPoint.findTable(insert.tableName) ?: continue
                val key = table.uniqueKeys().firstOrNull { it.size == 1 }?.first() ?: continue
                val resolved = insert.columns?.map { it.nameLower } ?: table.columns.map { it.nameLower }
                for (row in insert.rows) {
                    val value = InsertValidator.staticRowValues(resolved, row.values)[key] ?: continue
                    val mapKey = "${table.nameLower}|${value.first}"
                    val previous = directKeys.putIfAbsent(mapKey, Triple(entry, value.first, value.second))
                    if (previous != null && previous.first.path != entry.path) {
                        problems += ValidationProblem(
                            Severity.ERROR, ProblemCategory.DUPLICATE,
                            "Liquibase: key '$key'='${value.first}' of '${table.name}' is already inserted " +
                                "in step ${previous.first.order} (${previous.first.path.name}) — this INSERT " +
                                "will fail on the unique constraint",
                            value.second,
                        )
                    }
                }
            }

            // staged MERGE keys: overlap with an earlier stage is informational (upsert)
            for (flow in analysis.stagingFlows) {
                for (mapping in flow.mappings) {
                    val target = mapping.targetSchema ?: continue
                    val targetKey = target.uniqueKeys().firstOrNull { it.size == 1 }?.first() ?: continue
                    val inverse = mapping.columnMap.entries
                        .flatMap { (s, ts) -> ts.map { it to s } }.toMap()
                    val stagingKey = inverse[targetKey] ?: continue
                    val temp = flow.tempTable.columns.map { it.nameLower }
                    for (insert in flow.inserts) {
                        val resolved = insert.columns?.map { it.nameLower } ?: temp
                        for (row in insert.rows) {
                            val value = InsertValidator.staticRowValues(resolved, row.values)[stagingKey] ?: continue
                            val mapKey = "${target.nameLower}|${value.first}"
                            val earlier = mergedKeys.putIfAbsent(mapKey, entry)
                                ?: directKeys[mapKey]?.first
                            if (earlier != null && earlier.path != entry.path) {
                                problems += ValidationProblem(
                                    Severity.INFO, ProblemCategory.DUPLICATE,
                                    "Liquibase: '$targetKey'='${value.first}' of '${target.name}' also comes " +
                                        "from step ${earlier.order} (${earlier.path.name}) — this MERGE will " +
                                        "UPDATE that row (expected for country overrides)",
                                    value.second,
                                )
                            }
                        }
                    }
                }
            }
        }

    }
}

/** Environment policy guardrails (requirements R4) — the DBA's veto, in code. */
internal object PolicyChecks {

    fun apply(
        config: ReleaseConfig,
        environment: String,
        script: SqlScript,
        changesets: List<Changeset>,
        problems: MutableList<ValidationProblem>,
    ) {
        val prod = config.isProd(environment)
        val policies = config.policies
        val destructiveSeverity = if (prod) policies.prodDestructive else policies.nonprodDestructive
        val rollbackSeverity = if (prod) policies.prodMissingRollback else policies.nonprodMissingRollback

        val stagingTables = script.statementsOf<CreateTableStatement>()
            .filter { it.temporary }.map { it.tableNameLower }.toSet()

        fun changesetAt(offset: Int): Changeset? =
            changesets.firstOrNull { offset >= it.headerRange.start && offset < it.bodyRange.end }

        fun destructive(range: SrcRange, description: String) {
            val severity = destructiveSeverity ?: return
            val approved = changesetAt(range.start)?.approvedDestructive
            if (approved != null) {
                problems += ValidationProblem(
                    Severity.INFO, ProblemCategory.LIQUIBASE,
                    "Liquibase: destructive statement approved for $environment " +
                        "(--approved-destructive $approved): $description",
                    range,
                )
            } else {
                problems += ValidationProblem(
                    severity, ProblemCategory.LIQUIBASE,
                    "Liquibase [$environment policy]: $description — add " +
                        "'--approved-destructive <ticket>' inside the changeset once the DBA approves it",
                    range,
                )
            }
        }

        for (statement in script.statements) {
            when (statement) {
                is DropStatement ->
                    if (statement.objectKind == "TABLE" && statement.nameLower !in stagingTables &&
                        !looksLikeStaging(statement.nameLower)
                    ) {
                        destructive(statement.nameRange, "DROP TABLE '${statement.name}' is destructive")
                    }
                is TruncateStatement ->
                    destructive(statement.tableNameRange, "TRUNCATE '${statement.tableName}' is destructive")
                is DeleteStatement ->
                    if (!statement.hasWhere) {
                        destructive(statement.tableNameRange, "DELETE without WHERE empties '${statement.tableName}'")
                    }
                is UpdateStatement ->
                    if (!statement.hasWhere) {
                        destructive(statement.tableNameRange, "UPDATE without WHERE rewrites every row of '${statement.tableName}'")
                    }
                else -> Unit
            }
        }

        for (changeset in changesets) {
            val body = script.statements.filter {
                it.range.start >= changeset.bodyRange.start && it.range.start < changeset.bodyRange.end
            }
            val hasDml = body.any {
                it is InsertStatement || it is MergeStatement || it is UpdateStatement || it is DeleteStatement
            }
            if (changeset.rollbacks.isEmpty()) {
                rollbackSeverity?.let {
                    problems += ValidationProblem(
                        it, ProblemCategory.LIQUIBASE,
                        "Liquibase [$environment policy]: changeset '${changeset.key}' has no rollback",
                        changeset.headerRange,
                    )
                }
            }
            if (prod && hasDml && changeset.attributes["failonerror"].equals("false", true)) {
                policies.prodFailOnErrorFalse?.let {
                    problems += ValidationProblem(
                        it, ProblemCategory.LIQUIBASE,
                        "Liquibase [PROD policy]: failOnError:false on a DML changeset silently swallows " +
                            "data errors ('${changeset.key}')",
                        changeset.headerRange,
                    )
                }
            }
            if (hasDml && changeset.attributes["runalways"].equals("true", true)) {
                policies.runAlwaysDml?.let {
                    problems += ValidationProblem(
                        it, ProblemCategory.LIQUIBASE,
                        "Liquibase policy: runAlways:true on DML changeset '${changeset.key}' re-executes " +
                            "its data changes on every deployment",
                        changeset.headerRange,
                    )
                }
            }
        }
    }

    private fun looksLikeStaging(nameLower: String): Boolean =
        listOf("tmp_", "temp_", "stg_", "staging_").any { nameLower.startsWith(it) } ||
            listOf("_tmp", "_temp", "_stg", "_staging").any { nameLower.endsWith(it) }
}
