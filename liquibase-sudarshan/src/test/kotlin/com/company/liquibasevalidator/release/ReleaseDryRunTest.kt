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
        private val rowCounts: Map<String, Long?> = emptyMap(),
    ) : DatabaseSession {
        override fun close() {}
        override fun fetchTables(): Map<String, TableSchema> = tables
        override fun executedChangesets(): Set<String>? = executed
        override fun scalarSelect(sql: String): String? = null
        override fun rowExists(table: String, column: String, value: String): Boolean? {
            val key = table.lowercase() to value
            return if (key in existsAnswers) existsAnswers[key] else key in rows
        }

        override fun rowCount(table: String): Long? = rowCounts[table]

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
    fun `table differences cover every table on either side`() {
        standardRepo()
        file(
            "database/global/ddl/002_match.sql",
            """
            --liquibase formatted sql

            --changeset team:ddl-2
            CREATE TABLE match_tab (
                id INTEGER NOT NULL,
                CONSTRAINT pk_match PRIMARY KEY (id)
            );
            --rollback DROP TABLE match_tab;
            """,
        )
        file(
            "database/global/ddl/003_new.sql",
            """
            --liquibase formatted sql

            --changeset team:ddl-3
            CREATE TABLE brand_new (
                id INTEGER NOT NULL,
                CONSTRAINT pk_new PRIMARY KEY (id)
            );
            --rollback DROP TABLE brand_new;
            """,
        )
        val live = com.company.liquibasevalidator.schema.DdlSchemaBuilder.build(
            listOf(
                com.company.liquibasevalidator.schema.DdlSchemaBuilder.DdlSource(
                    "live.sql",
                    """
                    CREATE TABLE ref_data (
                        code        VARCHAR(15) NOT NULL PRIMARY KEY,
                        name        VARCHAR(50),
                        legacy_flag CHAR(1)
                    );
                    CREATE TABLE match_tab (id INTEGER NOT NULL PRIMARY KEY);
                    CREATE TABLE audit_log (entry VARCHAR(100));
                    """.trimIndent(),
                ),
            ),
        )
        val result = run(
            FakeSession(
                executed = emptySet(), tables = live,
                rowCounts = mapOf("ref_data" to 7L, "match_tab" to 0L, "audit_log" to 999L),
            ),
        )
        assertEquals(
            listOf("audit_log", "brand_new", "match_tab", "ref_data"),
            result.tableDiffs.map { it.tableName },
        )

        val audit = result.tableDiffs[0]
        assertEquals(ReleaseDryRun.TableStatus.DATABASE_ONLY, audit.status)
        assertEquals(999L, audit.databaseRowCount)
        assertEquals(0, audit.rowImpact.total)

        assertEquals(ReleaseDryRun.TableStatus.NEW, result.tableDiffs[1].status)
        assertEquals(ReleaseDryRun.TableStatus.MATCH, result.tableDiffs[2].status)

        val refData = result.tableDiffs[3]
        assertEquals(ReleaseDryRun.TableStatus.DIFFERENT, refData.status)
        assertEquals(7L, refData.databaseRowCount)
        val byKind = refData.deltas.associateBy { it.kind }
        assertEquals("VARCHAR(10)", byKind.getValue(ReleaseDryRun.DeltaKind.TYPE).branchValue)
        assertEquals("VARCHAR(15)", byKind.getValue(ReleaseDryRun.DeltaKind.TYPE).databaseValue)
        assertEquals("NOT NULL", byKind.getValue(ReleaseDryRun.DeltaKind.NULLABILITY).branchValue)
        assertEquals("NULL", byKind.getValue(ReleaseDryRun.DeltaKind.NULLABILITY).databaseValue)
        assertEquals("legacy_flag", byKind.getValue(ReleaseDryRun.DeltaKind.DATABASE_ONLY).column)
        // all branch rows (A, B, C merged + S seeded) are new against the empty live data
        assertEquals(4, refData.rowImpact.inserts)
    }

    @Test
    fun `branch column missing in the database is reported`() {
        file(
            "database/global/ddl/001_t.sql",
            """
            --liquibase formatted sql

            --changeset t:ddl-only
            CREATE TABLE t1 (
                a INTEGER NOT NULL,
                b VARCHAR(5),
                CONSTRAINT pk_t1 PRIMARY KEY (a)
            );
            --rollback DROP TABLE t1;
            """,
        )
        val live = com.company.liquibasevalidator.schema.DdlSchemaBuilder.build(
            listOf(
                com.company.liquibasevalidator.schema.DdlSchemaBuilder.DdlSource(
                    "live.sql", "CREATE TABLE t1 (a INTEGER NOT NULL PRIMARY KEY);",
                ),
            ),
        )
        val diff = run(FakeSession(executed = emptySet(), tables = live)).tableDiffs.single()
        assertEquals(ReleaseDryRun.TableStatus.DIFFERENT, diff.status)
        val delta = diff.deltas.single()
        assertEquals(ReleaseDryRun.DeltaKind.MISSING_IN_DATABASE, delta.kind)
        assertEquals("b", delta.column)
        assertEquals("VARCHAR(5)", delta.branchValue)
        assertEquals(null, delta.databaseValue)
    }

    @Test
    fun `database errors while probing rows degrade to UNKNOWN`() {
        standardRepo()
        val liveRef = com.company.liquibasevalidator.schema.DdlSchemaBuilder.build(
            listOf(
                com.company.liquibasevalidator.schema.DdlSchemaBuilder.DdlSource(
                    "live.sql",
                    "CREATE TABLE ref_data (code VARCHAR(10) NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL);",
                ),
            ),
        )
        val throwing = object : DatabaseSession {
            override fun close() {}
            override fun fetchTables(): Map<String, TableSchema> = liveRef
            override fun executedChangesets(): Set<String>? = emptySet()
            override fun scalarSelect(sql: String): String? = null
            override fun rowExists(table: String, column: String, value: String): Boolean? =
                throw java.sql.SQLException("permission denied")

            override fun rowCount(table: String): Long? =
                throw java.sql.SQLException("permission denied")

            override fun selectRowByKey(table: String, keyColumn: String, keyValue: String): Map<String, String?>? =
                throw java.sql.SQLException("permission denied")
        }
        val result = run(throwing)
        assertTrue(result.comparisons.isNotEmpty())
        assertTrue(result.comparisons.all { it.fate == ReleaseDryRun.RowFate.UNKNOWN })
        // uncountable table: the diff still lists it, with an unknown row count
        assertEquals(null, result.tableDiffs.single { it.tableName == "ref_data" }.databaseRowCount)
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
