package com.company.liquibasevalidator.sql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SqlParserTest {

    @Test
    fun `parses create temp table with columns constraints and on commit drop`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (
                code        VARCHAR(50)  NOT NULL,
                name        VARCHAR(255) NOT NULL,
                description VARCHAR(500),
                active      BOOLEAN      NOT NULL,
                amount      DECIMAL(10,2) DEFAULT 0.00,
                PRIMARY KEY (code)
            ) ON COMMIT DROP;
        """.trimIndent()
        val script = SqlParser.parse(sql)
        val create = script.statementsOf<CreateTableStatement>().single()

        assertEquals("tmp_account_type", create.tableName)
        assertTrue(create.temporary)
        assertEquals(5, create.columns.size)

        val code = create.columns[0]
        assertEquals("code", code.name)
        assertEquals(TypeKind.VARCHAR, code.dataType.kind)
        assertEquals(50, code.dataType.length)
        assertTrue(code.notNull)
        assertEquals("VARCHAR(50)", sql.substring(code.typeRange.start, code.typeRange.end))

        val description = create.columns[2]
        assertFalse(description.notNull)
        assertEquals(500, description.dataType.length)

        val active = create.columns[3]
        assertEquals(TypeKind.BOOLEAN, active.dataType.kind)

        val amount = create.columns[4]
        assertEquals(TypeKind.DECIMAL, amount.dataType.kind)
        assertEquals(10, amount.dataType.precision)
        assertEquals(2, amount.dataType.scale)
        assertTrue(amount.hasDefault)

        val pk = create.constraints.single()
        assertEquals(TableConstraintKind.PRIMARY_KEY, pk.kind)
        assertEquals(listOf("code"), pk.columns)
    }

    @Test
    fun `parses multiword types`() {
        val sql = "CREATE TABLE t (a CHARACTER VARYING(30), b TIMESTAMP WITH TIME ZONE, c DOUBLE PRECISION);"
        val create = SqlParser.parse(sql).statementsOf<CreateTableStatement>().single()
        assertEquals(TypeKind.VARCHAR, create.columns[0].dataType.kind)
        assertEquals(30, create.columns[0].dataType.length)
        assertEquals(TypeKind.TIMESTAMPTZ, create.columns[1].dataType.kind)
        assertEquals(TypeKind.DOUBLE, create.columns[2].dataType.kind)
    }

    @Test
    fun `parses insert with column list and multiple rows`() {
        val sql = """
            INSERT INTO tmp_account_type (code, name, active)
            VALUES
                ('SAVINGS', 'Savings Account', TRUE),
                ('CURRENT', NULL, FALSE);
        """.trimIndent()
        val insert = SqlParser.parse(sql).statementsOf<InsertStatement>().single()

        assertEquals("tmp_account_type", insert.tableName)
        assertEquals(listOf("code", "name", "active"), insert.columns!!.map { it.name })
        assertEquals(2, insert.rows.size)

        val row1 = insert.rows[0].values
        assertEquals("SAVINGS", (row1[0] as SqlValueExpr.StringLit).value)
        assertEquals("Savings Account", (row1[1] as SqlValueExpr.StringLit).value)
        assertTrue((row1[2] as SqlValueExpr.BoolLit).value)

        val row2 = insert.rows[1].values
        assertTrue(row2[1] is SqlValueExpr.NullLit)

        // offsets point at the literal, including quotes
        val lit = row1[0] as SqlValueExpr.StringLit
        assertEquals("'SAVINGS'", sql.substring(lit.range.start, lit.range.end))
    }

    @Test
    fun `liquibase comments do not break statement parsing`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:001 context:BD runOnChange:true
            --comment: hello
            INSERT INTO t (a) VALUES (1);
            --rollback DELETE FROM t;
        """.trimIndent()
        val script = SqlParser.parse(sql)
        val insert = script.statementsOf<InsertStatement>().single()
        assertEquals("t", insert.tableName)
        assertEquals("1", (insert.rows[0].values[0] as SqlValueExpr.NumberLit).text)
    }

    @Test
    fun `parses full merge statement`() {
        val sql = """
            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN
                UPDATE SET
                    name        = source.name,
                    description = source.description,
                    active      = TRUE
            WHEN NOT MATCHED THEN
                INSERT (code, name, description, active)
                VALUES (source.code, source.name, source.description, source.active);
        """.trimIndent()
        val merge = SqlParser.parse(sql).statementsOf<MergeStatement>().single()

        assertEquals("account_type", merge.targetTable)
        assertEquals("target", merge.targetAlias)
        assertEquals("tmp_account_type", merge.sourceTable)
        assertEquals("source", merge.sourceAlias)

        val on = merge.onConditions.single()
        assertEquals("code", (on.left as SqlValueExpr.ColumnRef).column)
        assertEquals("target", (on.left as SqlValueExpr.ColumnRef).qualifier)
        assertEquals("source", (on.right as SqlValueExpr.ColumnRef).qualifier)

        assertEquals(3, merge.updateAssignments.size)
        assertEquals("name", merge.updateAssignments[0].targetColumn.name)
        assertEquals("name", (merge.updateAssignments[0].value as SqlValueExpr.ColumnRef).column)
        assertTrue(merge.updateAssignments[2].value is SqlValueExpr.BoolLit)

        val insertClause = merge.insertClause!!
        assertEquals(listOf("code", "name", "description", "active"), insertClause.columns!!.map { it.name })
        assertEquals(4, insertClause.values.size)
        assertEquals("active", (insertClause.values[3] as SqlValueExpr.ColumnRef).column)
    }

    @Test
    fun `merge without alias uses table names as qualifiers`() {
        val sql = """
            MERGE INTO account_type
            USING tmp_account_type
            ON account_type.code = tmp_account_type.code
            WHEN MATCHED THEN UPDATE SET name = tmp_account_type.name;
        """.trimIndent()
        val merge = SqlParser.parse(sql).statementsOf<MergeStatement>().single()
        assertNull(merge.targetAlias)
        assertTrue(merge.isTargetQualifier("account_type"))
        assertTrue(merge.isSourceQualifier("tmp_account_type"))
    }

    @Test
    fun `parses alter table add constraint and unique index`() {
        val sql = """
            ALTER TABLE account ADD CONSTRAINT fk_ct FOREIGN KEY (customer_type_id) REFERENCES customer_type (id);
            CREATE UNIQUE INDEX ux_account_number ON account (account_number);
        """.trimIndent()
        val script = SqlParser.parse(sql)
        val alter = script.statementsOf<AlterTableAddConstraint>().single()
        assertEquals("account", alter.tableName)
        val fk = alter.constraints.single()
        assertEquals(TableConstraintKind.FOREIGN_KEY, fk.kind)
        assertEquals(listOf("customer_type_id"), fk.columns)
        assertEquals("customer_type", fk.refTable)
        assertEquals(listOf("id"), fk.refColumns)

        val index = script.statementsOf<CreateIndexStatement>().single()
        assertTrue(index.unique)
        assertEquals("account", index.tableName)
        assertEquals(listOf("account_number"), index.columns)
    }

    @Test
    fun `unknown statements are skipped without breaking the rest`() {
        val sql = """
            GRANT SELECT ON t TO role_x;
            INSERT INTO t (a) VALUES (1);
            VACUUM ANALYZE t;
        """.trimIndent()
        val script = SqlParser.parse(sql)
        assertEquals(1, script.statementsOf<InsertStatement>().size)
        assertEquals(2, script.statementsOf<UnknownStatement>().size)
    }

    @Test
    fun `insert without column list and with expressions`() {
        val sql = "INSERT INTO t VALUES (now(), 'a' || 'b', 5);"
        val insert = SqlParser.parse(sql).statementsOf<InsertStatement>().single()
        assertNull(insert.columns)
        val row = insert.rows.single().values
        assertTrue(row[0] is SqlValueExpr.Opaque)
        assertTrue(row[1] is SqlValueExpr.Opaque)
        assertTrue(row[2] is SqlValueExpr.NumberLit)
    }

    @Test
    fun `string cast is captured`() {
        val sql = "INSERT INTO t (id) VALUES ('0e37df36-f698-11e6-8dd4-cb9ced3df976'::uuid);"
        val insert = SqlParser.parse(sql).statementsOf<InsertStatement>().single()
        val value = insert.rows.single().values.single() as SqlValueExpr.StringLit
        assertNotNull(value.castType)
        assertEquals(TypeKind.UUID, value.castType!!.kind)
    }

    @Test
    fun `missing semicolon between statements is detected and both statements survive`() {
        val sql = """
            INSERT INTO t (a) VALUES (1)
            INSERT INTO t (a) VALUES (2);
        """.trimIndent()
        val script = SqlParser.parse(sql)
        assertEquals(2, script.statementsOf<InsertStatement>().size)
        val note = script.parseNotes.single()
        assertTrue(note.message.contains("missing statement delimiter"))
        assertEquals(sql.lastIndexOf("INSERT"), note.range.start)
    }

    @Test
    fun `statement tails do not trigger false missing-delimiter warnings`() {
        val sql = """
            CREATE GLOBAL TEMPORARY TABLE tmp_x (a VARCHAR2(5)) ON COMMIT DELETE ROWS;
            CREATE TEMP TABLE tmp_y (a INT) ON COMMIT DROP;
            INSERT INTO t (a) VALUES (1) ON CONFLICT (a) DO UPDATE SET a = 2;
            MERGE INTO x USING y ON (x.a = y.a) WHEN MATCHED THEN UPDATE SET x.b = y.b;
        """.trimIndent()
        assertTrue(SqlParser.parse(sql).parseNotes.isEmpty())
    }

    @Test
    fun `delete statement parsed for rollback validation`() {
        val delete = SqlParser.parse("DELETE FROM account_type WHERE code IN ('A');")
            .statementsOf<DeleteStatement>().single()
        assertEquals("account_type", delete.tableName)
    }
}
