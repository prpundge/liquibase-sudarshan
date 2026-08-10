package com.company.liquibasevalidator.sql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Edge branches of statement parsing: recovery paths, rare syntax forms, missing ';'. */
class SqlParserBranchesTest {

    private fun parse(sql: String): SqlScript = SqlParser.parse(sql)

    private inline fun <reified T : SqlStatement> first(sql: String): T =
        parse(sql).statements.filterIsInstance<T>().first()

    // -------------------------------------------------------------------------------------
    // missing ';' detection — every unambiguous statement-start keyword pair
    // -------------------------------------------------------------------------------------

    @Test
    fun `missing semicolon detected before every statement keyword`() {
        val nextStatements = listOf(
            "CREATE TABLE b (y INTEGER);",
            "ALTER TABLE b ADD CONSTRAINT pk_b PRIMARY KEY (y);",
            "DELETE FROM b WHERE y = 1;",
            "DROP TABLE b;",
            "TRUNCATE TABLE b;",
            "UPDATE b SET y = 2 WHERE y = 1;",
            "INSERT INTO b (y) VALUES (1);",
            "COMMIT;",
            "ROLLBACK;",
        )
        for (next in nextStatements) {
            val script = parse("CREATE TABLE a (x INTEGER)\n$next")
            assertTrue(
                script.parseNotes.any { it.message.contains("missing statement delimiter") },
                "expected a missing-';' note before: $next",
            )
            assertEquals(2, script.statements.size, "both statements must still parse for: $next")
        }
    }

    // -------------------------------------------------------------------------------------
    // CREATE TABLE column/constraint recovery branches
    // -------------------------------------------------------------------------------------

    @Test
    fun `if not exists, inline check, named inline constraint, collate and generated columns`() {
        val create = first<CreateTableStatement>(
            """
            CREATE TABLE IF NOT EXISTS t (
                a INTEGER CHECK (a > 0),
                b INTEGER CONSTRAINT nn_b NOT NULL,
                c VARCHAR(5) COLLATE en_US,
                id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                d INTEGER REFERENCES parent (id) ON DELETE CASCADE
            );
            """.trimIndent(),
        )
        assertEquals("t", create.tableName)
        assertEquals(5, create.columns.size)
        assertTrue(create.findColumn("b")!!.notNull)
        assertTrue(create.findColumn("id")!!.primaryKey)
        assertEquals("parent", create.findColumn("d")!!.references?.table)
    }

    @Test
    fun `default with parenthesized expression and default cut short by a comma`() {
        val create = first<CreateTableStatement>(
            "CREATE TABLE t (a INTEGER DEFAULT (1 + 2) NOT NULL, b INTEGER DEFAULT , c INTEGER);",
        )
        assertTrue(create.findColumn("a")!!.hasDefault)
        assertTrue(create.findColumn("a")!!.notNull)
        assertTrue(create.findColumn("b")!!.hasDefault)
        assertEquals(3, create.columns.size)
    }

    @Test
    fun `generated keyword directly before a comma keeps the column list intact`() {
        val create = first<CreateTableStatement>("CREATE TABLE t (id INTEGER GENERATED, a INTEGER);")
        assertEquals(listOf("id", "a"), create.columns.map { it.name })
    }

    @Test
    fun `table-level check constraint and LIKE clause`() {
        val create = first<CreateTableStatement>(
            "CREATE TABLE t (a INTEGER, CONSTRAINT ck_a CHECK (a BETWEEN 0 AND 9), LIKE base_table);",
        )
        assertEquals(1, create.constraints.size)
        assertEquals(TableConstraintKind.CHECK, create.constraints[0].kind)
        assertEquals("ck_a", create.constraints[0].name)
    }

    @Test
    fun `garbage table element is skipped without losing the following column`() {
        val create = first<CreateTableStatement>("CREATE TABLE t (42, a INTEGER);")
        assertEquals(listOf("a"), create.columns.map { it.name })
    }

    @Test
    fun `create table without a name or without a column list is unknown`() {
        assertTrue(parse("CREATE TABLE (a INTEGER);").statements[0] is UnknownStatement)
        assertTrue(parse("CREATE TABLE t;").statements[0] is UnknownStatement)
    }

    // -------------------------------------------------------------------------------------
    // CREATE INDEX branches
    // -------------------------------------------------------------------------------------

    @Test
    fun `index with if not exists, using clause and sort direction`() {
        val index = first<CreateIndexStatement>(
            "CREATE INDEX IF NOT EXISTS idx_t ON t USING btree (a ASC, b);",
        )
        assertEquals("idx_t", index.indexName)
        assertEquals(listOf("a", "b"), index.columns)
        assertFalse(index.unique)
    }

    @Test
    fun `expression index is not modeled as a plain index`() {
        assertTrue(parse("CREATE INDEX i ON t (LOWER(a));").statements[0] is UnknownStatement)
    }

    // -------------------------------------------------------------------------------------
    // ALTER / DROP / UPDATE tails
    // -------------------------------------------------------------------------------------

    @Test
    fun `alter without add constraint is unknown, even unterminated at EOF`() {
        assertTrue(parse("ALTER TABLE t DROP COLUMN x;").statements[0] is UnknownStatement)
        // no ';', runs into EOF — the balanced skip must stop at the end of input
        assertTrue(parse("ALTER TABLE t DROP COLUMN x").statements[0] is UnknownStatement)
    }

    @Test
    fun `drop of an unsupported object kind is unknown`() {
        assertTrue(parse("DROP USER bob;").statements[0] is UnknownStatement)
    }

    @Test
    fun `unbalanced parenthesis before EOF means no top-level where`() {
        val update = first<UpdateStatement>("UPDATE t SET a = (1")
        assertFalse(update.hasWhere)
    }

    // -------------------------------------------------------------------------------------
    // INSERT value-expression branches
    // -------------------------------------------------------------------------------------

    @Test
    fun `insert without a table name is unknown`() {
        assertTrue(parse("INSERT INTO (a) VALUES (1);").statements[0] is UnknownStatement)
    }

    @Test
    fun `insert from select and insert default values`() {
        assertTrue(first<InsertStatement>("INSERT INTO t SELECT * FROM u;").fromSelect)
        val defaults = first<InsertStatement>("INSERT INTO t DEFAULT VALUES;")
        assertFalse(defaults.fromSelect)
        assertTrue(defaults.rows.isEmpty())
    }

    @Test
    fun `unterminated values row and stray semicolon inside a row`() {
        val unterminated = first<InsertStatement>("INSERT INTO t (a) VALUES (1")
        assertEquals(1, unterminated.rows.size)
        val strayS = first<InsertStatement>("INSERT INTO t (a) VALUES (1;")
        assertEquals(1, strayS.rows.size)
    }

    @Test
    fun `empty, arithmetic and bare-identifier values`() {
        val insert = first<InsertStatement>("INSERT INTO t (a, b, c) VALUES (, 1 + 2, bare_ref);")
        val row = insert.rows.single()
        assertTrue(row.values[0] is SqlValueExpr.Opaque)
        assertEquals("", (row.values[0] as SqlValueExpr.Opaque).text)
        assertTrue(row.values[1] is SqlValueExpr.Opaque)
        val ref = row.values[2] as SqlValueExpr.ColumnRef
        assertEquals("bare_ref", ref.column)
        assertNull(ref.qualifier)
    }

    // -------------------------------------------------------------------------------------
    // MERGE clause branches
    // -------------------------------------------------------------------------------------

    @Test
    fun `when-matched AND condition, insert default values and do nothing clauses`() {
        val merge = first<MergeStatement>(
            """
            MERGE INTO target_tab t
            USING source_tab s
            ON t.code = s.code
            WHEN MATCHED AND t.locked = 'N' THEN
                UPDATE SET name = s.name
            WHEN NOT MATCHED THEN
                INSERT DEFAULT VALUES;
            """.trimIndent(),
        )
        assertEquals(1, merge.updateAssignments.size)
        assertTrue(merge.insertClause!!.values.isEmpty())

        val nothing = first<MergeStatement>(
            "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DO NOTHING;",
        )
        assertTrue(nothing.updateAssignments.isEmpty())
    }
}
