package com.company.liquibasevalidator.sql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DELETE/UPDATE/TRUNCATE/DROP parsing — the statements the release policies act on. */
class DmlStatementsTest {

    private inline fun <reified T : SqlStatement> single(sql: String): T {
        val statements = SqlParser.parse(sql).statements
        assertEquals(1, statements.size, "expected one statement for: $sql")
        assertTrue(statements[0] is T, "expected ${T::class.simpleName}, got ${statements[0]::class.simpleName}")
        return statements[0] as T
    }

    @Test
    fun `delete with and without where`() {
        assertTrue(single<DeleteStatement>("DELETE FROM account_type WHERE code = 'X';").hasWhere)
        val bare = single<DeleteStatement>("DELETE FROM account_type;")
        assertFalse(bare.hasWhere)
        assertEquals("account_type", bare.tableName)
    }

    @Test
    fun `update with optional alias`() {
        val statement = single<UpdateStatement>("UPDATE account_type t SET name = 'x' WHERE t.code = 'A';")
        assertEquals("account_type", statement.tableName)
        assertTrue(statement.hasWhere)
    }

    @Test
    fun `update without where is flagged as unfiltered`() {
        assertFalse(single<UpdateStatement>("UPDATE account_type SET sort_order = 0;").hasWhere)
    }

    @Test
    fun `where inside a subquery does not count as a top-level filter`() {
        val statement = single<UpdateStatement>(
            "UPDATE account_type SET sort_order = (SELECT MAX(x) FROM t WHERE t.b = 1);",
        )
        assertFalse(statement.hasWhere)
    }

    @Test
    fun `truncate with and without the TABLE keyword`() {
        assertEquals("account_type", single<TruncateStatement>("TRUNCATE TABLE account_type;").tableName)
        assertEquals("account_type", single<TruncateStatement>("TRUNCATE account_type;").tableName)
    }

    @Test
    fun `drop table with if exists and cascade tail`() {
        val statement = single<DropStatement>("DROP TABLE IF EXISTS legacy_rates CASCADE;")
        assertEquals("TABLE", statement.objectKind)
        assertEquals("legacy_rates", statement.name)
    }

    @Test
    fun `drop sequence records the object kind`() {
        assertEquals("SEQUENCE", single<DropStatement>("DROP SEQUENCE seq_customer;").objectKind)
    }

    @Test
    fun `schema-qualified drop keeps the simple name lowercased`() {
        assertEquals("legacy_rates", single<DropStatement>("DROP TABLE app.LEGACY_RATES PURGE;").nameLower)
    }
}
