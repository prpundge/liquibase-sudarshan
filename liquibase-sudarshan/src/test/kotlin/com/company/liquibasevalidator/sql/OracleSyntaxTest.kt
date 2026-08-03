package com.company.liquibasevalidator.sql

import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.validation.ProblemCategory
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.company.liquibasevalidator.validation.ValidationOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Oracle dialect coverage: GTT staging tables, parenthesized MERGE ON, Oracle datatypes. */
class OracleSyntaxTest {

    private val oracleDdl = """
        CREATE TABLE account_type (
            code        VARCHAR2(15 CHAR)  NOT NULL,
            name        VARCHAR2(100 CHAR) NOT NULL,
            description VARCHAR2(255 CHAR),
            active_flag CHAR(1)            NOT NULL,
            sort_order  NUMBER(5),
            balance_cap NUMBER(10,2),
            created_at  DATE,
            notes       CLOB,
            CONSTRAINT pk_account_type PRIMARY KEY (code)
        );
    """.trimIndent()

    private val schema = MapSchemaProvider(
        DdlSchemaBuilder.build(listOf(DdlSchemaBuilder.DdlSource("oracle.sql", oracleDdl))),
    )

    private fun validate(sql: String, options: ValidationOptions = ValidationOptions()) =
        ValidationEngine(options).validate(sql, schema).problems

    @Test
    fun `oracle types map to the datatype model`() {
        val table = schema.findTable("account_type")!!
        assertEquals(TypeKind.VARCHAR, table.findColumn("code")!!.dataType.kind)
        assertEquals(15, table.findColumn("code")!!.dataType.length) // VARCHAR2(15 CHAR)
        assertEquals(TypeKind.CHAR, table.findColumn("active_flag")!!.dataType.kind)
        assertEquals(1, table.findColumn("active_flag")!!.dataType.length)
        assertEquals(TypeKind.DECIMAL, table.findColumn("sort_order")!!.dataType.kind)
        assertEquals(5, table.findColumn("sort_order")!!.dataType.precision)
        assertEquals(TypeKind.DECIMAL, table.findColumn("balance_cap")!!.dataType.kind)
        assertEquals(2, table.findColumn("balance_cap")!!.dataType.scale)
        assertEquals(TypeKind.TEXT, table.findColumn("notes")!!.dataType.kind)
        assertEquals(TypeKind.DATE, table.findColumn("created_at")!!.dataType.kind)
    }

    @Test
    fun `global temporary table is recognized as staging`() {
        val sql = """
            CREATE GLOBAL TEMPORARY TABLE tmp_account_type (
                code VARCHAR2(15 CHAR) NOT NULL
            ) ON COMMIT DELETE ROWS;
        """.trimIndent()
        val create = SqlParser.parse(sql).statementsOf<CreateTableStatement>().single()
        assertTrue(create.temporary)
        assertEquals("tmp_account_type", create.tableName)
    }

    @Test
    fun `parenthesized merge on clause builds the mapping`() {
        val sql = """
            MERGE INTO account_type t
            USING tmp_account_type s
            ON (t.code = s.code AND t.active_flag = s.active_flag)
            WHEN MATCHED THEN UPDATE SET t.name = s.name
            WHEN NOT MATCHED THEN INSERT (code, name) VALUES (s.code, s.name);
        """.trimIndent()
        val merge = SqlParser.parse(sql).statementsOf<MergeStatement>().single()
        assertEquals(2, merge.onConditions.size)
        assertEquals("code", (merge.onConditions[0].left as SqlValueExpr.ColumnRef).column)
        assertEquals(1, merge.updateAssignments.size)
        assertEquals("name", merge.updateAssignments[0].targetColumn.name)
        assertEquals(listOf("code", "name"), merge.insertClause!!.columns!!.map { it.name })
    }

    @Test
    fun `sysdate and sequence nextval are opaque values, never false positives`() {
        val sql = """
            CREATE GLOBAL TEMPORARY TABLE tmp_account_type (
                code       VARCHAR2(15 CHAR) NOT NULL,
                name       VARCHAR2(100 CHAR) NOT NULL,
                created_at DATE
            ) ON COMMIT DELETE ROWS;

            INSERT INTO tmp_account_type (code, name, created_at)
            VALUES ('SAVINGS', 'Savings', SYSDATE);

            MERGE INTO account_type t
            USING tmp_account_type s
            ON (t.code = s.code)
            WHEN NOT MATCHED THEN
                INSERT (code, name, sort_order, created_at)
                VALUES (s.code, s.name, account_type_seq.NEXTVAL, SYSTIMESTAMP);
        """.trimIndent()
        val problems = validate(sql)
        assertTrue(problems.none { it.severity == Severity.ERROR }, "unexpected: $problems")
        assertTrue(problems.none { it.message.contains("account_type_seq") }, "sequence flagged: $problems")
    }

    @Test
    fun `oracle staging violations are detected through parenthesized merge`() {
        val sql = """
            CREATE GLOBAL TEMPORARY TABLE tmp_account_type (
                code        VARCHAR2(50 CHAR) NOT NULL,
                name        VARCHAR2(100 CHAR) NOT NULL,
                active_flag CHAR(1) NOT NULL
            ) ON COMMIT DELETE ROWS;

            INSERT INTO tmp_account_type (code, name, active_flag)
            VALUES ('SAVINGS', 'Savings', 'YES');

            MERGE INTO account_type t
            USING tmp_account_type s
            ON (t.code = s.code)
            WHEN MATCHED THEN UPDATE SET t.name = s.name, t.active_flag = s.active_flag;
        """.trimIndent()
        val problems = validate(sql)
        // VARCHAR2(50) staging vs VARCHAR2(15) target
        assertTrue(problems.any { it.category == ProblemCategory.LENGTH && it.message.contains("VARCHAR(50)") })
        // 'YES' does not fit CHAR(1)
        assertTrue(problems.any { it.message.contains("length 3") && it.message.contains("active_flag") })
    }

    @Test
    fun `number precision checks apply to oracle number types`() {
        val sql = """
            INSERT INTO account_type (code, name, active_flag, sort_order, balance_cap)
            VALUES ('A', 'a', 'Y', 123456, 1234.567);
        """.trimIndent()
        val problems = validate(sql)
        // sort_order NUMBER(5): 123456 has 6 integer digits
        assertTrue(problems.any { it.message.contains("precision exceeded") && it.message.contains("sort_order") })
        // balance_cap NUMBER(10,2): 9 integer digits ok, but scale 3 > 2
        assertTrue(problems.any { it.message.contains("scale exceeded") && it.message.contains("balance_cap") })
    }

    @Test
    fun `empty string as null is flagged only in oracle mode`() {
        val sql = """
            INSERT INTO account_type (code, name, active_flag)
            VALUES ('A', '', 'Y');
        """.trimIndent()
        val defaultProblems = validate(sql)
        assertTrue(defaultProblems.none { it.category == ProblemCategory.NULLABILITY })

        val oracleProblems = validate(sql, ValidationOptions(treatEmptyStringAsNull = true))
        val issue = oracleProblems.single { it.category == ProblemCategory.NULLABILITY }
        assertTrue(issue.message.contains("empty string"))
        assertTrue(issue.message.contains("account_type.name"))
    }

    @Test
    fun `valid oracle staging script produces zero problems`() {
        val sql = """
            --liquibase formatted sql

            --changeset banking-team:ORA-001 context:GLOBAL labels:staticdata runOnChange:true
            --comment: Oracle upsert with global temporary table

            CREATE GLOBAL TEMPORARY TABLE tmp_account_type (
                code        VARCHAR2(15 CHAR)  NOT NULL,
                name        VARCHAR2(100 CHAR) NOT NULL,
                description VARCHAR2(255 CHAR),
                active_flag CHAR(1)            NOT NULL
            ) ON COMMIT DELETE ROWS;

            INSERT INTO tmp_account_type (code, name, description, active_flag)
            VALUES ('SAVINGS', 'Savings Account', 'Standard savings account', 'Y');

            INSERT INTO tmp_account_type (code, name, description, active_flag)
            VALUES ('CURRENT', 'Current Account', 'Standard current account', 'N');

            MERGE INTO account_type t
            USING tmp_account_type s
            ON (t.code = s.code)
            WHEN MATCHED THEN
                UPDATE SET
                    t.name        = s.name,
                    t.description = s.description,
                    t.active_flag = s.active_flag
            WHEN NOT MATCHED THEN
                INSERT (code, name, description, active_flag)
                VALUES (s.code, s.name, s.description, s.active_flag);

            --rollback DELETE FROM account_type WHERE code IN ('SAVINGS', 'CURRENT');
        """.trimIndent()
        val problems = validate(sql, ValidationOptions(treatEmptyStringAsNull = true))
        assertEquals(emptyList<Any>(), problems)
    }
}
