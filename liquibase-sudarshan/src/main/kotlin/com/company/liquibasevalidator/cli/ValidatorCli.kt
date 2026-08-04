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
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.toList

/**
 * Command-line runner for the validation engine, so repositories can be validated outside
 * IntelliJ (CI pipelines, VS Code, pre-commit hooks). Pure static validation — no database
 * access unless --db-url is given, and then strictly read-only.
 *
 * Usage:
 *   ValidatorCli <repoRoot> [--country=XX] [--oracle] [--fail-on-warnings]
 *                [--ddl=<dir>] [--data=<dir>]   (repeatable; default: auto-detect)
 *                [--db-url=<jdbc> --db-user=<u> [--db-password=<p>] [--db-schema=<s>]]
 *                [--patch=<file.diff>]   PR-review mode: only findings on changed lines
 *                [--github]              emit GitHub Actions annotations (inline PR comments)
 *
 * Output (default): <absolute path>:<line>:<column>: <error|warning|info>: <message>
 * Output (--github): ::error file=<relative>,line=<l>,col=<c>::<message>
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

        val patch = args.optionValue("--patch")?.let { patchPath ->
            val file = Paths.get(patchPath).let { if (it.isAbsolute) it else root.resolve(it) }
            if (!file.isRegularFile()) {
                System.err.println("error: patch file not found: $file")
                kotlin.system.exitProcess(2)
            }
            val parsed = PatchFilter.parse(decodeTextAuto(Files.readAllBytes(file)))
            if (parsed.fileCount == 0) {
                System.err.println(
                    "warning: patch file contains no file changes — every finding will be suppressed " +
                        "(generate it with: git diff --output=changes.diff, not a shell '>' redirect)",
                )
            }
            parsed
        }
        val reporter = Reporter(github = args.contains("--github"), repoRoot = root, patch = patch)

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
                reporter.finding(file.absolutePathString(), line, column, problem.severity, problem.message)
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
                driverJarPath = args.optionValue("--db-driver-jar").orEmpty(),
            )
            val byId = analyzed.associateBy { it.path }
            try {
                val dryRun = LiquibaseDryRun(JdbcConnector(config), options)
                    .run(analyzed.map { it.input }, schema)
                dryRun.notes.forEach { reporter.note("dry-run: $it") }
                for (finding in dryRun.findings) {
                    val file = byId[finding.fileId] ?: continue
                    val (line, column) = file.lineIndex.locate(finding.problem.range.start)
                    reporter.finding(file.path, line, column, finding.problem.severity, finding.problem.message)
                }
                for (pending in dryRun.pending) {
                    val file = byId[pending.fileId] ?: continue
                    val (line, column) = file.lineIndex.locate(pending.headerRange.start)
                    reporter.info(
                        file.path, line, column,
                        "dry run — changeset '${pending.key}' is pending (${pending.reason})",
                    )
                }
                for (step in dryRun.plan) {
                    val file = byId[step.fileId] ?: continue
                    val (line, column) = file.lineIndex.locate(step.headerRange.start)
                    reporter.info(
                        file.path, line, column,
                        "plan ${step.order}. ${step.action} '${step.key}' " +
                            "(${step.statementCount} statement(s)) — ${step.reason}",
                    )
                }
                for (row in dryRun.preview) {
                    val file = byId[row.fileId] ?: continue
                    val (line, column) = file.lineIndex.locate(row.range.start)
                    val changeset = row.changesetKey?.let { " [changeset $it]" } ?: ""
                    reporter.info(
                        file.path, line, column,
                        "preview: ${row.action} ${row.tableName} ${row.keyColumn}='${row.keyValue}'$changeset",
                    )
                }
            } catch (e: Exception) {
                System.err.println("error: dry run failed: ${e.message ?: e.javaClass.simpleName}")
                kotlin.system.exitProcess(2)
            }
        }

        println()
        if (patch != null && reporter.suppressed > 0) {
            println(
                "PR-review mode: ${reporter.suppressed} finding(s) outside the patch's changed " +
                    "lines were suppressed (patch touches ${patch.fileCount} file(s))",
            )
        }
        println(
            "Liquibase Sudarshan: ${files.size} file(s) scanned, " +
                "${reporter.errors} error(s), ${reporter.warnings} warning(s)",
        )
        if (reporter.errors > 0 || (failOnWarnings && reporter.warnings > 0)) kotlin.system.exitProcess(1)
    }

    // ---------------------------------------------------------------------------------------

    /** Single output gate: patch-filtering, error/warning counting, gcc or GitHub format. */
    private class Reporter(val github: Boolean, val repoRoot: Path, val patch: PatchFilter?) {
        var errors = 0
        var warnings = 0
        var suppressed = 0

        fun finding(path: String, line: Int, column: Int, severity: Severity, message: String) {
            val label = when (severity) {
                Severity.ERROR -> "error"
                Severity.WARNING, Severity.WEAK_WARNING -> "warning"
                Severity.INFO -> "info"
            }
            if (patch != null) {
                val changed = patch.changedLinesFor(path)
                if (changed == null || line !in changed) {
                    if (label != "info") suppressed++
                    return
                }
            }
            when (label) {
                "error" -> errors++
                "warning" -> warnings++
            }
            emit(path, line, column, label, message)
        }

        /** Informational lines (plan/pending/preview): shown for patch-touched files only. */
        fun info(path: String, line: Int, column: Int, message: String) {
            if (patch != null && !patch.touches(path)) return
            emit(path, line, column, "info", message)
        }

        fun note(message: String) {
            if (github) println("::notice::${escape(message)}") else println(message)
        }

        private fun emit(path: String, line: Int, column: Int, label: String, message: String) {
            if (github) {
                val level = if (label == "info") "notice" else label
                println("::$level file=${relative(path)},line=$line,col=$column::${escape(message)}")
            } else {
                println("$path:$line:$column: $label: $message")
            }
        }

        private fun relative(path: String): String = try {
            repoRoot.relativize(Paths.get(path)).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            path.replace('\\', '/')
        }

        private fun escape(message: String): String =
            message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    }

    /** PowerShell's `>` redirect writes UTF-16LE with a BOM; decode patch files robustly. */
    private fun decodeTextAuto(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> String(bytes, Charsets.UTF_8)
    }

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
