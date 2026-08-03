package com.company.liquibasevalidator.inspection

import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * IntelliJ Platform integration tests: the inspection must highlight the right ranges with
 * the right messages, offer working quick fixes and stay silent on valid scripts.
 */
class LiquibaseInspectionTest : BasePlatformTestCase() {

    private val ddl = """
        CREATE TABLE account_type (
            code VARCHAR(15) NOT NULL PRIMARY KEY,
            name VARCHAR(100) NOT NULL,
            description VARCHAR(255),
            active BOOLEAN NOT NULL
        );
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(LiquibaseSqlInspection())
        LiquibaseSettings.getInstance(project).update {
            it.globalDdlPath = "database/global/ddl"
            it.globalStaticPath = "database/global/staticdatasetup"
            it.countryRootPath = "database/countries"
            it.countryCode = ""
        }
        myFixture.addFileToProject("database/global/ddl/account_type.sql", ddl)
    }

    private fun errorHighlights(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }

    fun `test staging mismatch and oversized value produce two errors on exact ranges`() {
        val dml = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(50)
            );

            INSERT INTO tmp_account_type(code)
            VALUES ('THIS_CODE_IS_TOO_LONG');
        """.trimIndent()
        myFixture.configureByText("account_type_data.sql", dml)

        val errors = errorHighlights()
        assertEquals(2, errors.size)

        val declError = errors.single { it.description.contains("VARCHAR(50)") }
        assertTrue(declError.description.contains("account_type.code"))
        assertTrue(declError.description.contains("VARCHAR(15)"))
        assertEquals(dml.indexOf("VARCHAR(50)"), declError.startOffset)
        assertEquals(dml.indexOf("VARCHAR(50)") + "VARCHAR(50)".length, declError.endOffset)

        val valueError = errors.single { it.description.contains("length 21") }
        assertEquals(dml.indexOf("'THIS_CODE_IS_TOO_LONG'"), valueError.startOffset)
        assertTrue(valueError.description.contains("account_type.code"))
    }

    fun `test quick fix changes only the datatype`() {
        val dml = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(50)
            );
        """.trimIndent()
        myFixture.configureByText("fixme.sql", dml)
        myFixture.doHighlighting()

        val fix = myFixture.getAllQuickFixes().single { it.text.contains("Change VARCHAR(50) to VARCHAR(15)") }
        myFixture.launchAction(fix)

        myFixture.checkResult(dml.replace("VARCHAR(50)", "VARCHAR(15)"))
    }

    fun `test unknown column is highlighted at the column name`() {
        val dml = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(15)
            );
            INSERT INTO tmp_account_type (code, invalid_column) VALUES ('A', 'B');
        """.trimIndent()
        myFixture.configureByText("unknown_column.sql", dml)

        val error = errorHighlights().single { it.description.contains("invalid_column") }
        assertTrue(error.description.contains("does not exist in table 'tmp_account_type'"))
        assertEquals(dml.indexOf("invalid_column"), error.startOffset)
    }

    fun `test valid script has no errors or warnings`() {
        val dml = """
            --liquibase formatted sql

            --changeset banking-team:001 context:BD runOnChange:true
            --comment: Upsert account types

            CREATE TEMP TABLE tmp_account_type (
                code        VARCHAR(15)  NOT NULL,
                name        VARCHAR(100) NOT NULL,
                description VARCHAR(255),
                active      BOOLEAN      NOT NULL
            ) ON COMMIT DROP;

            INSERT INTO tmp_account_type (code, name, description, active)
            VALUES
                ('SAVINGS', 'Savings Account', 'Standard savings account', TRUE),
                ('CURRENT', 'Current Account', 'Standard current account', TRUE);

            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN
                UPDATE SET name = source.name, description = source.description, active = source.active
            WHEN NOT MATCHED THEN
                INSERT (code, name, description, active)
                VALUES (source.code, source.name, source.description, source.active);

            --rollback DELETE FROM account_type
            --rollback WHERE code IN ('SAVINGS', 'CURRENT');
        """.trimIndent()
        myFixture.configureByText("valid.sql", dml)

        val problems = myFixture.doHighlighting().filter {
            it.severity == HighlightSeverity.ERROR ||
                it.severity == HighlightSeverity.WARNING ||
                it.severity == HighlightSeverity.WEAK_WARNING
        }
        assertEmpty(problems)
    }

    fun `test null into not null column is an error`() {
        val dml = """
            INSERT INTO account_type (code, name, description, active)
            VALUES ('X', NULL, 'd', TRUE);
        """.trimIndent()
        myFixture.configureByText("null_value.sql", dml)

        val error = errorHighlights().single()
        assertTrue(error.description.contains("NULL is not allowed"))
        assertTrue(error.description.contains("account_type.name"))
        assertEquals(dml.indexOf("NULL,"), error.startOffset)
    }

    fun `test duplicate primary key values are detected`() {
        val dml = """
            INSERT INTO account_type (code, name, active)
            VALUES ('SAVINGS', 'a', TRUE), ('SAVINGS', 'b', TRUE);
        """.trimIndent()
        myFixture.configureByText("dupes.sql", dml)

        val error = errorHighlights().single { it.description.contains("duplicate value") }
        assertEquals(dml.lastIndexOf("'SAVINGS'"), error.startOffset)
    }

    fun `test warnings appear inline with exact range`() {
        val dml = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(15) NOT NULL,
                extra_column VARCHAR(10)
            );
            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN UPDATE SET active = TRUE;
        """.trimIndent()
        myFixture.configureByText("unused_column.sql", dml)

        val warning = myFixture.doHighlighting()
            .single { it.severity == HighlightSeverity.WARNING && it.description?.contains("extra_column") == true }
        assertTrue(warning.description.contains("not used by the MERGE"))
        assertEquals(dml.indexOf("extra_column"), warning.startOffset)
    }

    fun `test ddl problems appear inline in ddl files`() {
        val ddl2 = """
            CREATE TABLE customer (
                id BIGINT NOT NULL,
                name VARCHAR(50),
                name VARCHAR(80),
                type_ref INTEGER REFERENCES no_such_table (id)
            );
        """.trimIndent()
        myFixture.configureByText("customer_ddl.sql", ddl2)

        val highlights = myFixture.doHighlighting()
        val duplicate = highlights.single { it.severity == HighlightSeverity.ERROR }
        assertTrue(duplicate.description.contains("duplicate column 'name'"))
        assertEquals(ddl2.lastIndexOf("name VARCHAR"), duplicate.startOffset)
        assertTrue(highlights.any {
            it.severity == HighlightSeverity.WARNING && it.description?.contains("no_such_table") == true
        })
    }

    fun `test typoed keyword shows inline error with quick fix`() {
        val dml = """
            INSEffRT INTO account_type (code, name, active)
            VALUES ('X', 'x', TRUE);
        """.trimIndent()
        myFixture.configureByText("typo.sql", dml)

        val error = errorHighlights().single()
        assertTrue(error.description.contains("did you mean INSERT?"))
        assertEquals(0, error.startOffset)
        assertEquals("INSEffRT".length, error.endOffset)

        val fix = myFixture.getAllQuickFixes().single { it.text.contains("Change 'INSEffRT' to INSERT") }
        myFixture.launchAction(fix)
        myFixture.checkResult(dml.replace("INSEffRT", "INSERT"))
    }

    fun `test non sql files are ignored`() {
        myFixture.configureByText("readme.txt", "INSERT INTO account_type (nope) VALUES ('x');")
        assertEmpty(errorHighlights())
    }
}
