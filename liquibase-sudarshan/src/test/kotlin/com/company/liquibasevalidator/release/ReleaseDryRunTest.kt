package com.company.liquibasevalidator.release

import com.company.liquibasevalidator.database.DatabaseConnector
import com.company.liquibasevalidator.database.DatabaseSession
import com.company.liquibasevalidator.database.LiquibaseDryRun
import com.company.liquibasevalidator.schema.TableSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReleaseDryRunTest {

    @TempDir
    lateinit var repo: Path

    private class FakeSession(
        private val executed: Set<String>?,
        private val tables: Map<String, TableSchema> = emptyMap(),
        /** (lowercase table, key value) → row as column(lowercase) → value. */
        private val rows: Map<Pair<String, String>, Map<String, String?>> = emptyMap(),
        /** Explicit rowExists answers (may be null = cannot probe); falls back to [rows]. */
        private val existsAnswers: Map<Pair<String, String>, Boolean?> = emptyMap(),
    ) : DatabaseSession {
        override fun close() {}
        override fun fetchTables(): Map<String, TableSchema> = tables
        override fun executedChangesets(): Set<String>? = executed
        override fun scalarSelect(sql: String): String? = null
        override fun rowExists(table: String, column: String, value: String): Boolean? {
            val key = table.lowercase() to value
            return if (key in existsAnswers) existsAnswers[key] else key in rows
        }

        override fun selectRowByKey(table: String, keyColumn: String, keyValue: String): Map<String, String?>? =
            rows[table.lowercase() to keyValue]
    }

    private class FakeConnector(private val session: DatabaseSession) : DatabaseConnector {
        override fun <T> withSession(block: (DatabaseSession) -> T): T = block(session)
    }

    private fun file(relativePath: String, content: String) {
        val path = repo.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content.trimIndent())
    }

    private fun standardRepo() {
        file(
            "database/global/ddl/001_ref.sql",
            """
            --liquibase formatted sql

            --changeset team:ddl-1
            CREATE TABLE ref_data (
                code VARCHAR(10) NOT NULL,
                name VARCHAR(50) NOT NULL,
                CONSTRAINT pk_ref_data PRIMARY KEY (code)
            );
            --rollback DROP TABLE ref_data;
            """,
        )
        file(
            "database/global/staticdatasetup/ref.sql",
            """
            --liquibase formatted sql

            --changeset team:merge-ref
            CREATE TEMP TABLE tmp_ref_data (
                code VARCHAR(10) NOT NULL,
                name VARCHAR(50) NOT NULL
            ) ON COMMIT DROP;

            INSERT INTO tmp_ref_data (code, name)
            VALUES ('A', 'Alpha'), ('B', 'Beta'), ('C', 'Gamma');

            MERGE INTO ref_data AS target
            USING tmp_ref_data AS source
            ON target.code = source.code
            WHEN MATCHED THEN
                UPDATE SET name = source.name
            WHEN NOT MATCHED THEN
                INSERT (code, name) VALUES (source.code, source.name);

            --rollback DELETE FROM ref_data WHERE code IN ('A', 'B', 'C');
            """,
        )
        file(
            "database/environments/SIT/001_seed.sql",
            """
            --liquibase formatted sql

            --changeset team:sit-1 context:SIT
            INSERT INTO ref_data (code, name) VALUES ('S', 'Seed');
            --rollback DELETE FROM ref_data WHERE code = 'S';
            """,
        )
    }

    private fun run(session: DatabaseSession): ReleaseDryRun.Result =
        ReleaseDryRun().run(repo, "IN", "SIT", FakeConnector(session))

    private fun fate(result: ReleaseDryRun.Result, keyValue: String): ReleaseDryRun.RowFate =
        result.comparisons.single { it.keyValue == keyValue }.fate

    @Test
    fun `merged rows compare as UPDATE with column diffs, INSERT and SAME`() {
        standardRepo()
        val result = run(
            FakeSession(
                executed = emptySet(),
                rows = mapOf(
                    ("ref_data" to "A") to mapOf("code" to "A", "name" to "Old Alpha"),
                    ("ref_data" to "C") to mapOf("code" to "C", "name" to "Gamma"),
                ),
            ),
        )
        assertEquals(ReleaseDryRun.RowFate.UPDATE, fate(result, "A"))
        assertEquals(ReleaseDryRun.RowFate.INSERT, fate(result, "B"))
        assertEquals(ReleaseDryRun.RowFate.SAME, fate(result, "C"))

        val update = result.comparisons.single { it.keyValue == "A" }
        assertEquals(1, update.changedColumns)
        val nameDiff = update.diffs.single { it.column == "name" }
        assertEquals("Alpha", nameDiff.branchValue)
        assertEquals("Old Alpha", nameDiff.databaseValue)
        assertTrue(nameDiff.changed)
        assertTrue(update.diffs.single { it.column == "code" }.changed.not())
        assertEquals("team:merge-ref", update.changesetKey)
    }

    @Test
    fun `direct insert onto an existing key is a CONFLICT`() {
        standardRepo()
        val result = run(
            FakeSession(
                executed = emptySet(),
                rows = mapOf(("ref_data" to "S") to mapOf("code" to "S", "name" to "Seed")),
            ),
        )
        assertEquals(ReleaseDryRun.RowFate.CONFLICT, fate(result, "S"))
    }

    @Test
    fun `changesets from a previous release are SKIP in both the plan and the comparison`() {
        standardRepo()
        val result = run(FakeSession(executed = setOf("team:merge-ref")))
        // rows of the already-released changeset never execute again
        for (key in listOf("A", "B", "C")) {
            assertEquals(ReleaseDryRun.RowFate.SKIP, fate(result, key))
        }
        // the fresh SIT changeset still runs
        assertEquals(ReleaseDryRun.RowFate.INSERT, fate(result, "S"))
        val skipStep = result.plan.single { it.key == "team:merge-ref" }
        assertEquals(LiquibaseDryRun.RunAction.SKIP, skipStep.action)
        assertTrue(skipStep.reason.contains("already in DATABASECHANGELOG"))
        assertEquals(2, result.wouldExecute) // ddl-1 + sit-1
        assertEquals(1, result.wouldSkip)
        assertTrue(result.changelogTableFound)
    }

    @Test
    fun `unprobeable rows are UNKNOWN, fresh database has no changelog`() {
        standardRepo()
        val result = run(
            FakeSession(
                executed = null,
                existsAnswers = mapOf(
                    ("ref_data" to "A") to null,
                    ("ref_data" to "B") to null,
                    ("ref_data" to "C") to null,
                    ("ref_data" to "S") to null,
                ),
            ),
        )
        assertTrue(result.comparisons.isNotEmpty())
        assertTrue(result.comparisons.all { it.fate == ReleaseDryRun.RowFate.UNKNOWN })
        assertTrue(!result.changelogTableFound)
        assertTrue(result.notes.any { it.contains("fresh database") })
    }

    @Test
    fun `database errors while probing rows degrade to UNKNOWN`() {
        standardRepo()
        val throwing = object : DatabaseSession {
            override fun close() {}
            override fun fetchTables(): Map<String, TableSchema> = emptyMap()
            override fun executedChangesets(): Set<String>? = emptySet()
            override fun scalarSelect(sql: String): String? = null
            override fun rowExists(table: String, column: String, value: String): Boolean? =
                throw java.sql.SQLException("permission denied")

            override fun selectRowByKey(table: String, keyColumn: String, keyValue: String): Map<String, String?>? =
                throw java.sql.SQLException("permission denied")
        }
        val result = run(throwing)
        assertTrue(result.comparisons.isNotEmpty())
        assertTrue(result.comparisons.all { it.fate == ReleaseDryRun.RowFate.UNKNOWN })
    }

    @Test
    fun `manifest keeps the release stage order`() {
        standardRepo()
        val result = run(FakeSession(executed = emptySet()))
        assertEquals(
            listOf(ReleaseStage.GLOBAL_DDL, ReleaseStage.GLOBAL_STATIC, ReleaseStage.ENVIRONMENT),
            result.manifest.map { it.stage },
        )
        // fileIds are repo-relative with forward slashes
        assertTrue(result.comparisons.all { it.fileId.startsWith("database/") })
    }
}
