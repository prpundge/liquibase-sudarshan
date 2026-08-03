package com.company.liquibasevalidator.database

import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.company.liquibasevalidator.validation.ValidationOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiquibaseDryRunTest {

    private class FakeSession(
        private val executed: Set<String>?,
        private val scalars: Map<String, String?> = emptyMap(),
        private val existingRows: Set<Triple<String, String, String>> = emptySet(),
    ) : DatabaseSession {
        override fun close() {}
        override fun fetchTables(): Map<String, TableSchema> = emptyMap()
        override fun executedChangesets(): Set<String>? = executed
        override fun scalarSelect(sql: String): String? {
            SqlGuards.requireSingleSelect(sql)
            return scalars[sql.trim()]
        }

        override fun rowExists(table: String, column: String, value: String): Boolean? =
            Triple(table.lowercase(), column.lowercase(), value) in existingRows
    }

    private class FakeConnector(private val session: FakeSession) : DatabaseConnector {
        override fun <T> withSession(block: (DatabaseSession) -> T): T = block(session)
    }

    private val schema = MapSchemaProvider(
        DdlSchemaBuilder.build(
            listOf(
                DdlSchemaBuilder.DdlSource(
                    "ddl.sql",
                    """
                    CREATE TABLE customer_type (
                        id INTEGER NOT NULL PRIMARY KEY,
                        code VARCHAR(10) NOT NULL,
                        name VARCHAR(100) NOT NULL
                    );
                    CREATE TABLE account (
                        id BIGINT NOT NULL PRIMARY KEY,
                        customer_type_id INTEGER REFERENCES customer_type (id)
                    );
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun input(sql: String, fileId: String = "test.sql"): LiquibaseDryRun.FileInput =
        LiquibaseDryRun.FileInput(fileId, ValidationEngine(ValidationOptions()).validate(sql, schema).analysis)

    private fun dryRun(session: FakeSession, vararg inputs: LiquibaseDryRun.FileInput): LiquibaseDryRun.DryRunResult =
        LiquibaseDryRun(FakeConnector(session), ValidationOptions()).run(inputs.toList(), schema)

    private val changesetSql = """
        --liquibase formatted sql
        --changeset team:001
        INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        --changeset team:002 runAlways:true
        INSERT INTO customer_type (id, code, name) VALUES (2, 'CORP', 'Corporate');
    """.trimIndent()

    @Test
    fun `fresh database marks every changeset pending`() {
        val result = dryRun(FakeSession(executed = null), input(changesetSql))
        assertFalse(result.changelogTableFound)
        assertEquals(listOf("team:001", "team:002"), result.pending.map { it.key })
        assertTrue(result.pending.all { it.reason == "fresh database" })
        assertTrue(result.notes.any { it.contains("fresh database") })
    }

    @Test
    fun `executed changesets are not pending, runAlways always is`() {
        val result = dryRun(FakeSession(executed = setOf("team:001", "team:002")), input(changesetSql))
        assertTrue(result.changelogTableFound)
        val pending = result.pending.single()
        assertEquals("team:002", pending.key)
        assertTrue(pending.reason.contains("runAlways"))
    }

    @Test
    fun `new changeset is pending with reason`() {
        val result = dryRun(FakeSession(executed = setOf("team:001")), input(changesetSql))
        // 002 was never executed: "not yet" wins over its runAlways attribute
        assertTrue(result.pending.any { it.key == "team:002" && it.reason.contains("not in DATABASECHANGELOG") })
        assertTrue(result.pending.none { it.key == "team:001" })
    }

    @Test
    fun `precondition mismatch with onFail HALT is an error`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --preconditions onFail:HALT onError:HALT
            --precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM customer_type WHERE code IS NULL
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val session = FakeSession(
            executed = emptySet(),
            scalars = mapOf("SELECT COUNT(*) FROM customer_type WHERE code IS NULL" to "5"),
        )
        val result = dryRun(session, input(sql))
        val finding = result.findings.single { it.problem.category.name == "DATABASE" && it.problem.severity == Severity.ERROR }
        assertTrue(finding.problem.message.contains("expects '0'"))
        assertTrue(finding.problem.message.contains("returned '5'"))
        assertTrue(finding.problem.message.contains("HALT"))
    }

    @Test
    fun `precondition of an already executed changeset is not evaluated`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --preconditions onFail:HALT
            --precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM customer_type
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val session = FakeSession(
            executed = setOf("team:001"),
            scalars = mapOf("SELECT COUNT(*) FROM customer_type" to "999"),
        )
        val result = dryRun(session, input(sql))
        assertTrue(result.findings.isEmpty(), "executed changeset must be skipped: ${result.findings}")
    }

    @Test
    fun `matching precondition produces no finding, numeric equivalence counts`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --preconditions onFail:HALT
            --precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM customer_type WHERE code IS NULL
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val session = FakeSession(
            executed = emptySet(),
            scalars = mapOf("SELECT COUNT(*) FROM customer_type WHERE code IS NULL" to "0.0"),
        )
        val result = dryRun(session, input(sql))
        assertTrue(result.findings.none { it.problem.severity == Severity.ERROR })
    }

    @Test
    fun `non select precondition is skipped with a warning`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --preconditions onFail:HALT
            --precondition-sql-check expectedResult:0 CALL some_procedure()
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val result = dryRun(FakeSession(executed = emptySet()), input(sql))
        val warning = result.findings.single()
        assertEquals(Severity.WARNING, warning.problem.severity)
        assertTrue(warning.problem.message.contains("only evaluate SELECT"))
    }

    @Test
    fun `missing foreign key reference is reported`() {
        val sql = "INSERT INTO account (id, customer_type_id) VALUES (10, 42);"
        val result = dryRun(FakeSession(executed = emptySet()), input(sql))
        val finding = result.findings.single { it.problem.message.contains("foreign key") }
        assertTrue(finding.problem.message.contains("'42'"))
        assertTrue(finding.problem.message.contains("customer_type.id"))
    }

    @Test
    fun `satisfied foreign key produces no finding`() {
        val sql = "INSERT INTO account (id, customer_type_id) VALUES (10, 42);"
        val session = FakeSession(
            executed = emptySet(),
            existingRows = setOf(Triple("customer_type", "id", "42")),
        )
        val result = dryRun(session, input(sql))
        assertTrue(result.findings.none { it.problem.message.contains("foreign key") })
    }

    @Test
    fun `existing primary key row makes a direct insert a conflict`() {
        val sql = "INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');"
        val session = FakeSession(
            executed = emptySet(),
            existingRows = setOf(Triple("customer_type", "id", "1")),
        )
        val result = dryRun(session, input(sql))
        val finding = result.findings.single { it.problem.message.contains("already exists") }
        assertEquals(Severity.WARNING, finding.problem.severity)
        assertTrue(finding.problem.message.contains("MERGE"))
        val preview = result.preview.single()
        assertEquals(LiquibaseDryRun.RowAction.CONFLICT, preview.action)
    }

    @Test
    fun `checks of executed changesets are gated off, pending ones still run`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
            --changeset team:002
            INSERT INTO customer_type (id, code, name) VALUES (2, 'CORP', 'Corporate');
        """.trimIndent()
        val session = FakeSession(
            executed = setOf("team:001"),
            existingRows = setOf(
                Triple("customer_type", "id", "1"), // would be a conflict, but 001 already ran
                Triple("customer_type", "id", "2"), // 002 is pending: reported
            ),
        )
        val result = dryRun(session, input(sql))
        val conflicts = result.findings.filter { it.problem.message.contains("already exists") }
        assertEquals(1, conflicts.size)
        assertTrue(conflicts.single().problem.message.contains("id='2'"))
        // preview only contains the pending changeset's row
        assertEquals(listOf("team:002"), result.preview.map { it.changesetKey })
    }

    @Test
    fun `staged merge rows preview as insert or update`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            CREATE TEMP TABLE tmp_customer_type (
                id INTEGER NOT NULL,
                code VARCHAR(10) NOT NULL,
                name VARCHAR(100) NOT NULL
            );
            INSERT INTO tmp_customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
            INSERT INTO tmp_customer_type (id, code, name) VALUES (2, 'CORP', 'Corporate');
            MERGE INTO customer_type t
            USING tmp_customer_type s
            ON (t.id = s.id)
            WHEN MATCHED THEN UPDATE SET t.name = s.name
            WHEN NOT MATCHED THEN INSERT (id, code, name) VALUES (s.id, s.code, s.name);
        """.trimIndent()
        val session = FakeSession(
            executed = emptySet(),
            existingRows = setOf(Triple("customer_type", "id", "1")),
        )
        val result = dryRun(session, input(sql))
        val byKey = result.preview.associate { it.keyValue to it.action }
        assertEquals(LiquibaseDryRun.RowAction.UPDATE, byKey["1"])
        assertEquals(LiquibaseDryRun.RowAction.INSERT, byKey["2"])
        assertTrue(result.preview.all { it.changesetKey == "team:001" })
        assertTrue(result.preview.all { it.tableName == "customer_type" })
    }

    @Test
    fun `sql guard rejects everything but a single select`() {
        assertEquals("SELECT 1", SqlGuards.requireSingleSelect("  SELECT 1; "))
        assertThrows(IllegalArgumentException::class.java) { SqlGuards.requireSingleSelect("DELETE FROM t") }
        assertThrows(IllegalArgumentException::class.java) { SqlGuards.requireSingleSelect("SELECT 1; DROP TABLE t") }
        assertThrows(IllegalArgumentException::class.java) { SqlGuards.requireSingleSelect("WITH x AS (SELECT 1) SELECT * FROM x") }
        assertThrows(IllegalArgumentException::class.java) { SqlGuards.requireSingleSelect("CALL proc()") }
    }
}
