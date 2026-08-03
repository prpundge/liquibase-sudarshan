package com.company.liquibasevalidator.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DdlSchemaBuilderTest {

    @Test
    fun `builds table schema from create table`() {
        val ddl = """
            CREATE TABLE account_type (
                code VARCHAR(15) NOT NULL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                description VARCHAR(255),
                active BOOLEAN NOT NULL DEFAULT TRUE
            );
        """.trimIndent()
        val tables = DdlSchemaBuilder.build(listOf(DdlSchemaBuilder.DdlSource("a.sql", ddl)))
        val table = tables["account_type"]
        assertNotNull(table)
        table!!

        assertEquals(4, table.columns.size)
        val code = table.findColumn("CODE")!!
        assertFalse(code.nullable)
        assertTrue(code.primaryKey)
        assertEquals(15, code.dataType.length)

        val active = table.findColumn("active")!!
        assertTrue(active.hasDefault)

        assertTrue(table.isColumnInPrimaryKey("code"))
        assertEquals(listOf(listOf("code")), table.uniqueKeys())
        assertEquals("a.sql", table.origin.fileId)
    }

    @Test
    fun `alter table and unique index add constraints`() {
        val ddl = """
            CREATE TABLE account (
                id BIGINT NOT NULL,
                account_number VARCHAR(30) NOT NULL,
                customer_type_id INTEGER
            );
            ALTER TABLE account ADD CONSTRAINT pk_account PRIMARY KEY (id);
            ALTER TABLE account ADD CONSTRAINT fk_account_ct FOREIGN KEY (customer_type_id)
                REFERENCES customer_type (id);
            CREATE UNIQUE INDEX ux_account_number ON account (account_number);
        """.trimIndent()
        val table = DdlSchemaBuilder.build(listOf(DdlSchemaBuilder.DdlSource("a.sql", ddl)))["account"]!!

        assertTrue(table.isColumnInPrimaryKey("id"))
        assertTrue(table.uniqueKeys().contains(listOf("account_number")))
        val fk = table.foreignKeys().single()
        assertEquals("customer_type", fk.refTable)
        assertEquals(listOf("id"), fk.refColumns)
    }

    @Test
    fun `temporary tables are not part of the schema`() {
        val ddl = "CREATE TEMP TABLE tmp_x (a INT); CREATE TABLE real_x (a INT);"
        val tables = DdlSchemaBuilder.build(listOf(DdlSchemaBuilder.DdlSource("a.sql", ddl)))
        assertNull(tables["tmp_x"])
        assertNotNull(tables["real_x"])
    }

    @Test
    fun `inline references become foreign keys`() {
        val ddl = "CREATE TABLE t (ref_id INTEGER REFERENCES other (id));"
        val table = DdlSchemaBuilder.build(listOf(DdlSchemaBuilder.DdlSource("a.sql", ddl)))["t"]!!
        val fk = table.foreignKeys().single()
        assertEquals("other", fk.refTable)
        assertEquals(listOf("ref_id"), fk.columns)
    }

    @Test
    fun `map schema provider ignores schema qualifiers and case`() {
        val ddl = "CREATE TABLE account_type (code VARCHAR(15));"
        val provider = MapSchemaProvider(DdlSchemaBuilder.build(listOf(DdlSchemaBuilder.DdlSource("a.sql", ddl))))
        assertNotNull(provider.findTable("ACCOUNT_TYPE"))
        assertNotNull(provider.findTable("public.account_type"))
        assertNull(provider.findTable("missing"))
        assertTrue(provider.isResolved())
        assertFalse(MapSchemaProvider.UNRESOLVED.isResolved())
    }
}
