package com.company.liquibasevalidator.database

import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.company.liquibasevalidator.validation.ValidationOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * Coverage-focused companion to [LiquibaseDryRunTest]: exercises error branches (SQLException
 * from the session), three-valued rowExists results, edge guards in preview/probe collection,
 * plan branches for runOnChange, preview truncation, and the DatabaseAccess default members.
 */
class LiquibaseDryRunCoverageTest {

    /** Same fake approach as LiquibaseDryRunTest, extended with failure modes. Deliberately
     *  does NOT override the interface's default members so tests can hit their defaults. */
    private class FakeSession(
        private val executed: Set<String>? = emptySet(),
        private val scalars: Map<String, String?> = emptyMap(),
        private val existingRows: Set<Triple<String, String, String>> = emptySet(),
        private val scalarThrows: Boolean = false,
        private val rowExistsThrows: Boolean = false,
    ) : DatabaseSession {
        override fun close() {}
        override fun fetchTables(): Map<String, TableSchema> = emptyMap()
        override fun executedChangesets(): Set<String>? = executed
        override fun scalarSelect(sql: String): String? {
            if (scalarThrows) throw SQLException("simulated query failure")
            SqlGuards.requireSingleSelect(sql)
            return scalars[sql.trim()]
        }

        override fun rowExists(table: String, column: String, value: String): Boolean? {
            if (rowExistsThrows) throw SQLException("simulated probe failure")
            return Triple(table.lowercase(), column.lowercase(), value) in existingRows
        }
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
                    CREATE TABLE order_line (
                        order_id INTEGER NOT NULL,
                        line_no INTEGER NOT NULL,
                        item VARCHAR(50),
                        PRIMARY KEY (order_id, line_no)
                    );
                    CREATE TABLE shipment (
                        id INTEGER NOT NULL PRIMARY KEY,
                        order_id INTEGER,
                        line_no INTEGER,
                        FOREIGN KEY (order_id, line_no) REFERENCES order_line (order_id, line_no)
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

    // ------------------------------------------------------------------------------------------
    // DryRunResult.errorCount (line 75)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `errorCount counts only ERROR findings`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --preconditions onFail:HALT
            --precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM customer_type
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val session = FakeSession(scalars = mapOf("SELECT COUNT(*) FROM customer_type" to "5"))
        val result = dryRun(session, input(sql))
        assertEquals(1, result.errorCount)
        assertTrue(result.findings.any { it.problem.severity == Severity.ERROR })

        val clean = dryRun(FakeSession(), input("INSERT INTO customer_type (id, code, name) VALUES (1, 'R', 'Retail');"))
        assertEquals(0, clean.errorCount)
    }

    // ------------------------------------------------------------------------------------------
    // Preview truncation (lines 104-105)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `data preview is truncated to 500 rows with a note`() {
        val sql = buildString {
            appendLine("--liquibase formatted sql")
            appendLine("--changeset team:bulk")
            for (i in 1..501) {
                appendLine("INSERT INTO customer_type (id, code, name) VALUES ($i, 'C$i', 'Name $i');")
            }
        }
        val result = dryRun(FakeSession(), input(sql))
        assertEquals(500, result.preview.size)
        assertTrue(result.notes.any { it.contains("truncated to 500 rows") && it.contains("501 total") })
    }

    // ------------------------------------------------------------------------------------------
    // Plan: runOnChange re-execution branch (lines 139-140)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `already executed runOnChange changeset plans as RUN with runOnChange reason`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001 runOnChange:true
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val result = dryRun(FakeSession(executed = setOf("team:001")), input(sql))
        val step = result.plan.single()
        assertEquals(LiquibaseDryRun.RunAction.RUN, step.action)
        assertTrue(step.reason.contains("runOnChange:true"))
        // the gate still applies checks to a runOnChange changeset: its row is previewed
        assertEquals("team:001", result.preview.single().changesetKey)
    }

    // ------------------------------------------------------------------------------------------
    // Precondition branches (lines 222-227, 232, 233, 235-241, 250, 265)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `precondition query failure is reported as a warning`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --preconditions onFail:HALT
            --precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM customer_type
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val result = dryRun(FakeSession(scalarThrows = true), input(sql))
        val warning = result.findings.single { it.problem.message.contains("precondition query failed") }
        assertEquals(Severity.WARNING, warning.problem.severity)
        assertTrue(warning.problem.message.contains("simulated query failure"))
        // a failed probe never halts the plan
        assertEquals(LiquibaseDryRun.RunAction.RUN, result.plan.single().action)
    }

    @Test
    fun `sql check without expectedResult is not evaluated`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --preconditions onFail:HALT
            --precondition-sql-check SELECT COUNT(*) FROM customer_type
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val session = FakeSession(scalars = mapOf("SELECT COUNT(*) FROM customer_type" to "5"))
        val result = dryRun(session, input(sql))
        assertTrue(result.findings.isEmpty(), "no expectedResult means nothing to compare: ${result.findings}")
    }

    @Test
    fun `precondition returning no rows warns and defaults onFail to HALT`() {
        // no --preconditions line: the sql-check alone builds Preconditions(onFail = null)
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --precondition-sql-check expectedResult:0 SELECT id FROM customer_type WHERE code = 'NOPE'
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val result = dryRun(FakeSession(), input(sql))
        val warning = result.findings.single { it.problem.message.contains("returned no rows") }
        assertEquals(Severity.WARNING, warning.problem.severity)
        assertTrue(warning.problem.message.contains("onFail: HALT"))
        assertTrue(warning.problem.message.contains("team:001"))
    }

    @Test
    fun `precondition mismatch with onFail WARN warns and does not halt the plan`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --preconditions onFail:WARN
            --precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM customer_type
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val session = FakeSession(scalars = mapOf("SELECT COUNT(*) FROM customer_type" to "3"))
        val result = dryRun(session, input(sql))
        val finding = result.findings.single { it.problem.message.contains("expects '0'") }
        assertEquals(Severity.WARNING, finding.problem.severity)
        assertTrue(finding.problem.message.contains("would WARN on update"))
        assertEquals(0, result.errorCount)
        assertEquals(LiquibaseDryRun.RunAction.RUN, result.plan.single().action)
    }

    @Test
    fun `precondition matches on case-insensitive string equality`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            --preconditions onFail:HALT
            --precondition-sql-check expectedResult:YES SELECT enabled FROM customer_type WHERE id = 1
            INSERT INTO customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
        """.trimIndent()
        val session = FakeSession(scalars = mapOf("SELECT enabled FROM customer_type WHERE id = 1" to "yes"))
        val result = dryRun(session, input(sql))
        assertTrue(result.findings.isEmpty(), "case-insensitive match must satisfy the check: ${result.findings}")
        assertEquals(LiquibaseDryRun.RunAction.RUN, result.plan.single().action)
    }

    // ------------------------------------------------------------------------------------------
    // rowExists throwing: FK probes stay silent, preview rows become UNKNOWN
    // (lines 285, 321, 328, 371, 379)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `probe failure keeps foreign keys silent and marks direct insert preview unknown`() {
        val sql = "INSERT INTO account (id, customer_type_id) VALUES (10, 42);"
        val result = dryRun(FakeSession(rowExistsThrows = true), input(sql))
        assertTrue(result.findings.isEmpty(), "unprobable rows must stay silent: ${result.findings}")
        val row = result.preview.single()
        assertEquals(LiquibaseDryRun.RowAction.UNKNOWN, row.action)
        assertEquals("10", row.keyValue)
        assertNull(row.changesetKey)
    }

    @Test
    fun `staging preview marks rows unknown when the probe fails`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            CREATE TEMP TABLE tmp_customer_type (
                id INTEGER NOT NULL,
                code VARCHAR(10) NOT NULL,
                name VARCHAR(100) NOT NULL
            );
            INSERT INTO tmp_customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
            MERGE INTO customer_type t
            USING tmp_customer_type s
            ON (t.id = s.id)
            WHEN MATCHED THEN UPDATE SET t.name = s.name
            WHEN NOT MATCHED THEN INSERT (id, code, name) VALUES (s.id, s.code, s.name);
        """.trimIndent()
        val result = dryRun(FakeSession(rowExistsThrows = true), input(sql))
        val row = result.preview.single()
        assertEquals(LiquibaseDryRun.RowAction.UNKNOWN, row.action)
        assertEquals("customer_type", row.tableName)
        assertEquals("1", row.keyValue)
    }

    // ------------------------------------------------------------------------------------------
    // Staging FK probes through the merge column mapping (lines 425, 439, 443-445)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `staged rows probe foreign keys through the merge mapping`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            CREATE TEMP TABLE tmp_account (
                id BIGINT NOT NULL,
                customer_type_id INTEGER
            );
            INSERT INTO tmp_account (id, customer_type_id) VALUES (10, 77);
            MERGE INTO account t
            USING tmp_account s
            ON (t.id = s.id)
            WHEN MATCHED THEN UPDATE SET t.customer_type_id = s.customer_type_id
            WHEN NOT MATCHED THEN INSERT (id, customer_type_id) VALUES (s.id, s.customer_type_id);
        """.trimIndent()
        val result = dryRun(FakeSession(), input(sql))
        val fk = result.findings.single { it.problem.message.contains("foreign key") }
        assertTrue(fk.problem.message.contains("'77'"))
        assertTrue(fk.problem.message.contains("account.customer_type_id"))
        assertTrue(fk.problem.message.contains("customer_type.id"))
        // the staged row itself previews as a plain INSERT
        assertEquals(LiquibaseDryRun.RowAction.INSERT, result.preview.single().action)
    }

    @Test
    fun `staging foreign key column not mapped by the merge is never probed`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            CREATE TEMP TABLE tmp_account (
                id BIGINT NOT NULL,
                customer_type_id INTEGER
            );
            INSERT INTO tmp_account (id, customer_type_id) VALUES (10, 77);
            MERGE INTO account t
            USING tmp_account s
            ON (t.id = s.id)
            WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id);
        """.trimIndent()
        val result = dryRun(FakeSession(), input(sql))
        assertTrue(
            result.findings.none { it.problem.message.contains("foreign key") },
            "unmapped FK column has no target value to verify: ${result.findings}",
        )
        assertEquals(LiquibaseDryRun.RowAction.INSERT, result.preview.single().action)
    }

    // ------------------------------------------------------------------------------------------
    // Direct-insert guard branches (lines 312, 313, 314, 316, 404, 405, 424)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `unknown table composite key and missing pk value produce no preview`() {
        val sql = """
            INSERT INTO mystery_table (id) VALUES (1);
            INSERT INTO order_line (order_id, line_no, item) VALUES (1, 2, 'X');
            INSERT INTO customer_type (code, name) VALUES ('RETAIL', 'Retail');
        """.trimIndent()
        val result = dryRun(FakeSession(), input(sql))
        assertTrue(result.preview.isEmpty(), "no single-column key value is statically known: ${result.preview}")
        assertTrue(result.findings.isEmpty(), "nothing verifiable, nothing reported: ${result.findings}")
    }

    @Test
    fun `insert without a column list maps positionally for preview and fk probes`() {
        val sql = "INSERT INTO account VALUES (10, 42);"
        val result = dryRun(FakeSession(), input(sql))
        val fk = result.findings.single { it.problem.message.contains("foreign key") }
        assertTrue(fk.problem.message.contains("'42'"))
        val row = result.preview.single()
        assertEquals("account", row.tableName)
        assertEquals("id", row.keyColumn)
        assertEquals("10", row.keyValue)
        assertEquals(LiquibaseDryRun.RowAction.INSERT, row.action)
    }

    @Test
    fun `composite foreign keys are not probed`() {
        val sql = "INSERT INTO shipment (id, order_id, line_no) VALUES (1, 2, 3);"
        val result = dryRun(FakeSession(), input(sql))
        assertTrue(
            result.findings.none { it.problem.message.contains("foreign key") },
            "multi-column FKs cannot be probed row-wise: ${result.findings}",
        )
        // the single-column PK of shipment still previews normally
        assertEquals(LiquibaseDryRun.RowAction.INSERT, result.preview.single().action)
    }

    // ------------------------------------------------------------------------------------------
    // Staging preview guard branches (lines 357, 358, 362, 364, 366)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `merge into unknown target yields no staging preview`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            CREATE TEMP TABLE tmp_widget (
                id INTEGER NOT NULL,
                name VARCHAR(50)
            );
            INSERT INTO tmp_widget (id, name) VALUES (1, 'W');
            MERGE INTO widget t
            USING tmp_widget s
            ON (t.id = s.id)
            WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name);
        """.trimIndent()
        val result = dryRun(FakeSession(), input(sql))
        assertTrue(result.preview.isEmpty(), "unknown merge target cannot be previewed: ${result.preview}")
    }

    @Test
    fun `merge into composite key target yields no staging preview`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            CREATE TEMP TABLE tmp_order_line (
                order_id INTEGER NOT NULL,
                line_no INTEGER NOT NULL,
                item VARCHAR(50)
            );
            INSERT INTO tmp_order_line (order_id, line_no, item) VALUES (1, 1, 'A');
            MERGE INTO order_line t
            USING tmp_order_line s
            ON (t.order_id = s.order_id AND t.line_no = s.line_no)
            WHEN NOT MATCHED THEN INSERT (order_id, line_no, item) VALUES (s.order_id, s.line_no, s.item);
        """.trimIndent()
        val result = dryRun(FakeSession(), input(sql))
        assertTrue(result.preview.isEmpty(), "composite-key targets have no single probe column: ${result.preview}")
    }

    @Test
    fun `merge that does not map the target key yields no staging preview`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            CREATE TEMP TABLE tmp_customer_type (
                id INTEGER NOT NULL,
                code VARCHAR(10) NOT NULL,
                name VARCHAR(100) NOT NULL
            );
            INSERT INTO tmp_customer_type (id, code, name) VALUES (1, 'RETAIL', 'Retail');
            MERGE INTO customer_type t
            USING tmp_customer_type s
            ON (t.code = s.code)
            WHEN MATCHED THEN UPDATE SET t.name = s.name;
        """.trimIndent()
        val result = dryRun(FakeSession(), input(sql))
        assertTrue(result.preview.isEmpty(), "target key 'id' is not mapped from staging: ${result.preview}")
    }

    @Test
    fun `staged insert without columns maps positionally and rows without the key are skipped`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001
            CREATE TEMP TABLE tmp_customer_type (
                id INTEGER NOT NULL,
                code VARCHAR(10) NOT NULL,
                name VARCHAR(100) NOT NULL
            );
            INSERT INTO tmp_customer_type VALUES (5, 'GOV', 'Government');
            INSERT INTO tmp_customer_type (code, name) VALUES ('EDU', 'Education');
            MERGE INTO customer_type t
            USING tmp_customer_type s
            ON (t.id = s.id)
            WHEN MATCHED THEN UPDATE SET t.name = s.name
            WHEN NOT MATCHED THEN INSERT (id, code, name) VALUES (s.id, s.code, s.name);
        """.trimIndent()
        val result = dryRun(FakeSession(), input(sql))
        val row = result.preview.single()
        assertEquals("5", row.keyValue)
        assertEquals(LiquibaseDryRun.RowAction.INSERT, row.action)
    }

    // ------------------------------------------------------------------------------------------
    // DatabaseAccess.kt: DatabaseConfig, interface defaults, metadata value classes
    // ------------------------------------------------------------------------------------------

    @Test
    fun `database config defaults and copy semantics`() {
        val defaults = DatabaseConfig("jdbc:h2:mem:x", "app", "secret")
        assertEquals("jdbc:h2:mem:x", defaults.jdbcUrl)
        assertEquals("app", defaults.user)
        assertEquals("secret", defaults.password)
        assertEquals("", defaults.schemaName)
        assertEquals("", defaults.driverJarPath)
        assertEquals(10, defaults.queryTimeoutSeconds)

        val custom = defaults.copy(schemaName = "hr", driverJarPath = "C:/drivers/ojdbc.jar", queryTimeoutSeconds = 30)
        assertEquals("hr", custom.schemaName)
        assertEquals("C:/drivers/ojdbc.jar", custom.driverJarPath)
        assertEquals(30, custom.queryTimeoutSeconds)
        assertNotEquals(defaults, custom)
        assertEquals(defaults, DatabaseConfig("jdbc:h2:mem:x", "app", "secret"))
        assertEquals(defaults.hashCode(), DatabaseConfig("jdbc:h2:mem:x", "app", "secret").hashCode())
        assertTrue(defaults.toString().contains("jdbc:h2:mem:x"))
    }

    @Test
    fun `database session default members return safe read-only fallbacks`() {
        FakeSession().use { session ->
            assertNull(session.rowCount("customer_type"))
            assertEquals(emptyList<SequenceInfo>(), session.sequences())
            assertEquals(emptyList<String>(), session.views())
            assertEquals(emptyMap<String, List<IndexInfo>>(), session.indexes())
            assertNull(session.dataPreview("customer_type", 5))
        }
    }

    @Test
    fun `metadata value classes expose their fields`() {
        val seq = SequenceInfo("customer_seq", 20L, 4200L)
        assertEquals("customer_seq", seq.name)
        assertEquals(20L, seq.incrementBy)
        assertEquals(4200L, seq.lastNumber)
        assertEquals(seq, SequenceInfo("customer_seq", 20L, 4200L))
        assertTrue(seq.toString().contains("customer_seq"))

        val index = IndexInfo("ux_customer_code", true, "code")
        assertEquals("ux_customer_code", index.name)
        assertTrue(index.unique)
        assertEquals("code", index.columns)
        assertNotEquals(index, index.copy(unique = false))

        val data = TableData(listOf("ID", "CODE"), listOf(listOf("1", null)))
        assertEquals(listOf("ID", "CODE"), data.columnNames)
        assertEquals(listOf(listOf("1", null)), data.rows)
        assertEquals(data, TableData(listOf("ID", "CODE"), listOf(listOf("1", null))))
    }
}
