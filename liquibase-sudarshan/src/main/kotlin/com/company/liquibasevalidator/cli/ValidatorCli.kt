package com.company.liquibasevalidator.cli

import com.company.liquibasevalidator.database.DatabaseConfig
import com.company.liquibasevalidator.database.JdbcConnector
import com.company.liquibasevalidator.database.LiquibaseDryRun
import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.company.liquibasevalidator.validation.ValidationOptions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.toList

/**
 * Command-line runner for the validation engine, so repositories can be validated outside
 * IntelliJ (CI pipelines, VS Code tasks, pre-commit hooks). Pure static validation — no
 * database access, no SQL execution.
 *
 * Usage:
 *   ValidatorCli <repoRoot> [--country=XX] [--oracle] [--fail-on-warnings]
 *                [--ddl=<dir>] [--data=<dir>] (repeatable; default: auto-detect
 *                database/global/ddl, database/global/staticdatasetup, database/countries)
 *                [--db-url=<jdbc url> --db-user=<user> [--db-password=<pass>] [--db-schema=<schema>]]
 *
 * With --db-url a read-only DRY RUN also executes: pending changesets (DATABASECHANGELOG),
 * live SELECT precondition checks, FK/PK probes and the INSERT/UPDATE data preview.
 * The password may instead come from the LIQUIBASE_SUDARSHAN_DB_PASSWORD environment variable.
 *
 * Output (one finding per line, gcc-style so editors can parse it):
 *   <absolute path>:<line>:<column>: <error|warning|info>: <message>
 */
object ValidatorCli {

    @JvmStatic
    fun main(args: Array<String>) {
        // Findings contain non-ASCII punctuation; emit UTF-8 regardless of platform console.
        System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8))
        val positional = args.filterNot { it.startsWith("--") }
        val root = Paths.get(positional.firstOrNull() ?: ".").toAbsolutePath().normalize()
        if (!root.isDirectory()) {
            System.err.println("error: repository root not found: $root")
            kotlin.system.exitProcess(2)
        }
        val country = args.optionValue("--country")
        val failOnWarnings = args.contains("--fail-on-warnings")
        val options = ValidationOptions(treatEmptyStringAsNull = args.contains("--oracle"))

        val ddlDirs = args.optionValues("--ddl").map { root.resolve(it) }.ifEmpty { detectDdlDirs(root) }
        val dataDirs = args.optionValues("--data").map { root.resolve(it) }.ifEmpty { detectDataDirs(root, country) }

        if (ddlDirs.isEmpty()) {
            System.err.println("error: no DDL directory found under $root (expected */global/ddl or --ddl=<dir>)")
            kotlin.system.exitProcess(2)
        }

        val ddlFiles = ddlDirs.flatMap(::sqlFilesUnder)
        val schema = MapSchemaProvider(
            DdlSchemaBuilder.build(ddlFiles.map { DdlSchemaBuilder.DdlSource(it.absolutePathString(), it.readText()) }),
        )

        val engine = ValidationEngine(options)
        val files = (ddlFiles + dataDirs.flatMap(::sqlFilesUnder)).distinct()
        var errors = 0
        var warnings = 0

        data class AnalyzedFile(val path: String, val lineIndex: LineIndex, val input: LiquibaseDryRun.FileInput)
        val analyzed = mutableListOf<AnalyzedFile>()

        for (file in files) {
            val text = file.readText()
            val lineIndex = LineIndex(text)
            val result = engine.validate(text, schema)
            analyzed += AnalyzedFile(
                file.absolutePathString(), lineIndex,
                LiquibaseDryRun.FileInput(file.absolutePathString(), result.analysis),
            )
            for (problem in result.problems) {
                val (line, column) = lineIndex.locate(problem.range.start)
                val severity = when (problem.severity) {
                    Severity.ERROR -> { errors++; "error" }
                    Severity.WARNING -> { warnings++; "warning" }
                    Severity.WEAK_WARNING -> { warnings++; "warning" }
                    Severity.INFO -> "info"
                }
                println("${file.absolutePathString()}:$line:$column: $severity: ${problem.message}")
            }
        }

        // Optional read-only dry run against a configured datasource.
        val dbUrl = args.optionValue("--db-url")
        if (!dbUrl.isNullOrBlank()) {
            val config = DatabaseConfig(
                jdbcUrl = dbUrl,
                user = args.optionValue("--db-user").orEmpty(),
                password = args.optionValue("--db-password")
                    ?: System.getenv("LIQUIBASE_SUDARSHAN_DB_PASSWORD").orEmpty(),
                schemaName = args.optionValue("--db-schema").orEmpty(),
            )
            val byId = analyzed.associateBy { it.path }
            try {
                val dryRun = LiquibaseDryRun(JdbcConnector(config), options)
                    .run(analyzed.map { it.input }, schema)
                dryRun.notes.forEach { println("dry-run: $it") }
                for (finding in dryRun.findings) {
                    val file = byId[finding.fileId] ?: continue
                    val (line, column) = file.lineIndex.locate(finding.problem.range.start)
                    val severity = when (finding.problem.severity) {
                        Severity.ERROR -> { errors++; "error" }
                        else -> { warnings++; "warning" }
                    }
                    println("${file.path}:$line:$column: $severity: ${finding.problem.message}")
                }
                for (pending in dryRun.pending) {
                    val file = byId[pending.fileId] ?: continue
                    val (line, column) = file.lineIndex.locate(pending.headerRange.start)
                    println("${file.path}:$line:$column: info: dry run — changeset '${pending.key}' is pending (${pending.reason})")
                }
                for (step in dryRun.plan) {
                    val file = byId[step.fileId] ?: continue
                    val (line, column) = file.lineIndex.locate(step.headerRange.start)
                    println(
                        "${file.path}:$line:$column: info: plan ${step.order}. ${step.action} '${step.key}' " +
                            "(${step.statementCount} statement(s)) — ${step.reason}",
                    )
                }
                for (row in dryRun.preview) {
                    val file = byId[row.fileId] ?: continue
                    val (line, column) = file.lineIndex.locate(row.range.start)
                    val changeset = row.changesetKey?.let { " [changeset $it]" } ?: ""
                    println("${file.path}:$line:$column: info: preview: ${row.action} ${row.tableName} ${row.keyColumn}='${row.keyValue}'$changeset")
                }
            } catch (e: Exception) {
                System.err.println("error: dry run failed: ${e.message ?: e.javaClass.simpleName}")
                kotlin.system.exitProcess(2)
            }
        }

        println()
        println("Liquibase Sudarshan: ${files.size} file(s) scanned, $errors error(s), $warnings warning(s)")
        if (errors > 0 || (failOnWarnings && warnings > 0)) kotlin.system.exitProcess(1)
    }

    // ---------------------------------------------------------------------------------------

    private fun Array<String>.optionValue(name: String): String? =
        firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun Array<String>.optionValues(name: String): List<String> =
        filter { it.startsWith("$name=") }.map { it.substringAfter('=') }

    private fun detectDdlDirs(root: Path): List<Path> =
        findDirs(root) { it.name.equals("ddl", true) }

    private fun detectDataDirs(root: Path, country: String?): List<Path> {
        val static = findDirs(root) { it.name.equals("staticdatasetup", true) }
        if (country.isNullOrBlank()) return static
        return static.filter { dir ->
            val parent = dir.parent?.name ?: return@filter true
            // keep global datasets and the selected country's datasets
            !isUnderCountries(dir) || parent.equals(country, true)
        }
    }

    private fun isUnderCountries(dir: Path): Boolean =
        generateSequence(dir.parent) { it.parent }.any { it.name.equals("countries", true) }

    private fun findDirs(root: Path, match: (Path) -> Boolean): List<Path> =
        Files.walk(root, 8).use { stream ->
            stream.filter { it.isDirectory() && match(it) }.toList()
        }

    private fun sqlFilesUnder(dir: Path): List<Path> {
        if (!dir.isDirectory()) return emptyList()
        return Files.walk(dir).use { stream ->
            stream.filter { !it.isDirectory() && it.extension.equals("sql", true) }.sorted().toList()
        }
    }

    /** Maps absolute offsets to 1-based line/column. */
    private class LineIndex(text: String) {
        private val starts: IntArray

        init {
            val list = ArrayList<Int>()
            list += 0
            text.forEachIndexed { i, c -> if (c == '\n') list += i + 1 }
            starts = list.toIntArray()
        }

        fun locate(offset: Int): Pair<Int, Int> {
            var low = 0
            var high = starts.size - 1
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (starts[mid] <= offset) low = mid else high = mid - 1
            }
            return (low + 1) to (offset - starts[low] + 1)
        }
    }
}
