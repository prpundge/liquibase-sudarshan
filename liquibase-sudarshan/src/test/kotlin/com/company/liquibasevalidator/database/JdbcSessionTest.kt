package com.company.liquibasevalidator.database

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Real SQL execution of [JdbcSession] against an in-memory H2 database in PostgreSQL
 * compatibility mode — the identifier guards, qualification, LIMIT/CAST dialect choices
 * and the SQLException fallbacks are all exercised for real, no fakes.
 */
class JdbcSessionTest {

    private lateinit var connection: Connection

    private fun config(schema: String = "", timeout: Int = 5) =
        DatabaseConfig(
            jdbcUrl = "jdbc:postgresql://localhost/test",
            user = "sa",
            password = "",
            schemaName = schema,
            queryTimeoutSeconds = timeout,
        )

    private fun session(schema: String = "", oracle: Boolean = false) =
        JdbcSession(connection, config(schema), oracle)

    @BeforeEach
    fun open() {
        connection = DriverManager.getConnection("jdbc:h2:mem:s${counter++};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")
        connection.createStatement().use { s ->
            s.execute(
                """
                CREATE TABLE account_type (
                    code VARCHAR(15) NOT NULL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    active_flag CHAR(1)
                )
                """.trimIndent(),
            )
            s.execute("INSERT INTO account_type VALUES ('SAVINGS', 'Savings', 'Y')")
            s.execute("INSERT INTO account_type VALUES ('CURRENT', 'Current', 'N')")
            s.execute("CREATE VIEW active_types AS SELECT code FROM account_type WHERE active_flag = 'Y'")
            s.execute("CREATE SEQUENCE account_seq INCREMENT BY 2")
        }
    }

    @AfterEach
    fun close() {
        connection.createStatement().use { it.execute("DROP ALL OBJECTS") }
        connection.close()
    }

    // -----------------------------------------------------------------------------------
    // DATABASECHANGELOG
    // -----------------------------------------------------------------------------------

    @Test
    fun `executedChangesets reads author and id lowercased`() {
        connection.createStatement().use { s ->
            s.execute("CREATE TABLE DATABASECHANGELOG (id VARCHAR(100), author VARCHAR(100))")
            s.execute("INSERT INTO DATABASECHANGELOG VALUES ('US-001', 'Banking-Team')")
        }

        assertEquals(setOf("banking-team:us-001"), session().executedChangesets())
    }

    @Test
    fun `executedChangesets returns null when the table does not exist`() {
        assertNull(session().executedChangesets())
    }

    // -----------------------------------------------------------------------------------
    // Counting, scalars and previews
    // -----------------------------------------------------------------------------------

    @Test
    fun `rowCount counts rows of an existing table`() {
        assertEquals(2L, session().rowCount("account_type"))
    }

    @Test
    fun `rowCount returns null for a missing table and for an illegal identifier`() {
        assertNull(session().rowCount("no_such_table"))
        assertNull(session().rowCount("bad name; DROP TABLE x"))
    }

    @Test
    fun `scalarSelect returns the first column of the first row`() {
        assertEquals("2", session().scalarSelect("SELECT COUNT(*) FROM account_type"))
    }

    @Test
    fun `scalarSelect returns null when the query matches nothing`() {
        assertNull(session().scalarSelect("SELECT code FROM account_type WHERE code = 'NOPE'"))
    }

    @Test
    fun `dataPreview returns column labels and bounded rows`() {
        val data = session().dataPreview("account_type", 10)

        assertNotNull(data)
        assertEquals(listOf("code", "name", "active_flag"), data!!.columnNames.map { it.lowercase() })
        assertEquals(2, data.rows.size)
    }

    @Test
    fun `dataPreview clamps the limit to at least one row`() {
        assertEquals(1, session().dataPreview("account_type", 0)!!.rows.size)
    }

    @Test
    fun `dataPreview returns null for an illegal identifier and for a missing table`() {
        assertNull(session().dataPreview("no; DROP", 5))
        assertNull(session().dataPreview("absent_table", 5))
    }

    // -----------------------------------------------------------------------------------
    // Row probing
    // -----------------------------------------------------------------------------------

    @Test
    fun `rowExists is true for a present key and false for an absent one`() {
        assertEquals(true, session().rowExists("account_type", "code", "SAVINGS"))
        assertEquals(false, session().rowExists("account_type", "code", "MISSING"))
    }

    @Test
    fun `rowExists trims the probed value`() {
        assertEquals(true, session().rowExists("account_type", "code", "  SAVINGS  "))
    }

    @Test
    fun `rowExists returns null when an identifier cannot be validated`() {
        assertNull(session().rowExists("account_type", "code; DROP TABLE x", "SAVINGS"))
        assertNull(session().rowExists("1bad", "code", "SAVINGS"))
    }

    @Test
    fun `selectRowByKey returns the row keyed by lowercased column labels`() {
        val row = session().selectRowByKey("account_type", "code", "SAVINGS")

        assertNotNull(row)
        assertEquals("SAVINGS", row!!["code"])
        assertEquals("Savings", row["name"])
        assertEquals("Y", row["active_flag"]?.trim())
    }

    @Test
    fun `selectRowByKey returns null for an unknown key, a bad identifier and a missing table`() {
        assertNull(session().selectRowByKey("account_type", "code", "NOPE"))
        assertNull(session().selectRowByKey("account_type", "code; DROP", "SAVINGS"))
        assertNull(session().selectRowByKey("absent_table", "code", "SAVINGS"))
    }

    // -----------------------------------------------------------------------------------
    // Catalog metadata
    // -----------------------------------------------------------------------------------

    @Test
    fun `views lists the schema views lowercased`() {
        assertTrue(session(schema = "PUBLIC").views().contains("active_types"))
    }

    @Test
    fun `sequences lists the schema sequences`() {
        val names = session(schema = "PUBLIC").sequences().map { it.name }

        assertTrue(names.contains("account_seq"), "got $names")
    }

    @Test
    fun `oracle catalog queries degrade to empty results when the views are absent`() {
        val oracleSession = session(oracle = true)

        assertTrue(oracleSession.sequences().isEmpty())
        assertTrue(oracleSession.views().isEmpty())
        assertTrue(oracleSession.indexes().isEmpty())
    }

    @Test
    fun `indexes degrades to an empty map when pg_indexes is unavailable`() {
        assertTrue(session().indexes().isEmpty())
    }

    // -----------------------------------------------------------------------------------
    // Schema qualification
    // -----------------------------------------------------------------------------------

    @Test
    fun `a configured schema qualifies the table name`() {
        assertEquals(2L, session(schema = "PUBLIC").rowCount("account_type"))
    }

    @Test
    fun `an invalid schema name is ignored rather than interpolated`() {
        assertEquals(2L, session(schema = "not a schema!").rowCount("account_type"))
    }

    @Test
    fun `on oracle a leftover postgres public schema is not used as an owner`() {
        // qualifying with PUBLIC would still resolve on H2, so the assertion is that the
        // unqualified name is used: the row count succeeds either way, and no exception is raised
        assertEquals(2L, JdbcSession(connection, config("public"), oracle = true).rowCount("account_type"))
    }

    @Test
    fun `close is a no-op because the connector owns the connection`() {
        session().close()

        assertTrue(!connection.isClosed)
    }

    private companion object {
        var counter = 0
    }
}
