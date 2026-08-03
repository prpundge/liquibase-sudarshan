package com.company.liquibasevalidator.liquibase

import com.company.liquibasevalidator.sql.SrcRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiquibaseParserTest {

    private val example = """
        --liquibase formatted sql

        --changeset banking-team:003-insert-account-types context:BD labels:Comments runOnChange:true
        --comment: Upsert account types using temporary staging table

        --preconditions onFail:HALT onError:HALT
        --precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM account_type WHERE code IS NULL

        CREATE TEMP TABLE tmp_account_type (
            code VARCHAR(50) NOT NULL
        ) ON COMMIT DROP;

        --rollback DELETE FROM account_type
        --rollback WHERE code IN ('SAVINGS', 'CURRENT');

        --changeset banking-team:004 context:IN
        INSERT INTO account_type (code) VALUES ('X');
        --rollback DELETE FROM account_type WHERE code = 'X';
    """.trimIndent()

    @Test
    fun `detects formatted sql and parses changesets`() {
        val file = LiquibaseParser.parse(example)
        assertTrue(file.formatted)
        assertNotNull(file.headerRange)
        assertEquals(2, file.changesets.size)

        val first = file.changesets[0]
        assertEquals("banking-team", first.author)
        assertEquals("003-insert-account-types", first.id)
        assertEquals(listOf("BD"), first.contexts)
        assertEquals(listOf("Comments"), first.labels)
        assertTrue(first.runOnChange)
        assertEquals("Upsert account types using temporary staging table", first.comment)

        val second = file.changesets[1]
        assertEquals(listOf("IN"), second.contexts)
        assertFalse(second.runOnChange)
    }

    @Test
    fun `parses preconditions and sql checks`() {
        val first = LiquibaseParser.parse(example).changesets[0]
        val preconditions = first.preconditions
        assertNotNull(preconditions)
        assertEquals("HALT", preconditions!!.onFail)
        assertEquals("HALT", preconditions.onError)
        val check = preconditions.sqlChecks.single()
        assertEquals("0", check.expectedResult)
        assertEquals("SELECT COUNT(*) FROM account_type WHERE code IS NULL", check.sql)
    }

    @Test
    fun `consecutive rollback lines merge into one rollback sql`() {
        val first = LiquibaseParser.parse(example).changesets[0]
        val rollback = first.rollbacks.single()
        assertEquals("DELETE FROM account_type\nWHERE code IN ('SAVINGS', 'CURRENT');", rollback.sql)
    }

    @Test
    fun `changeset body ranges cover their statements`() {
        val file = LiquibaseParser.parse(example)
        val createOffset = example.indexOf("CREATE TEMP TABLE")
        val insertOffset = example.indexOf("INSERT INTO account_type")
        assertEquals("003-insert-account-types", file.changesetAt(createOffset)!!.id)
        assertEquals("004", file.changesetAt(insertOffset)!!.id)
    }

    @Test
    fun `duplicate changeset ids are reported`() {
        val text = """
            --liquibase formatted sql
            --changeset a:1
            SELECT 1;
            --changeset a:1
            SELECT 2;
        """.trimIndent()
        val file = LiquibaseParser.parse(text)
        assertTrue(file.problems.any { it.isError && it.message.contains("duplicate changeset 'a:1'") })
    }

    @Test
    fun `sql before first changeset in formatted file is flagged once`() {
        val text = """
            --liquibase formatted sql
            SELECT 1;
            SELECT 2;
            --changeset a:1
            SELECT 3;
        """.trimIndent()
        val problems = LiquibaseParser.parse(text).problems
        assertEquals(1, problems.count { it.message.contains("before the first") })
    }

    @Test
    fun `invalid changeset header is reported`() {
        val text = """
            --liquibase formatted sql
            --changeset missing-colon-author-id
            SELECT 1;
        """.trimIndent()
        assertTrue(LiquibaseParser.parse(text).problems.any { it.isError })
    }

    @Test
    fun `mistyped changeset directive is an error with quick fix data`() {
        val text = """
            --liquibase formatted sql
            --changeset a:1
            SELECT 1;
            --changeasset banking-team:IN-001a-drop-tmp context:IN runAlways:true
            SELECT 2;
        """.trimIndent()
        val problem = LiquibaseParser.parse(text).problems.single()
        assertTrue(problem.isError)
        assertTrue(problem.message.contains("did you mean '--changeset'"))
        assertTrue(problem.message.contains("DATABASECHANGELOG"))
        assertEquals("changeasset", text.substring(problem.fixRange!!.start, problem.fixRange!!.end))
        assertEquals("changeset", problem.fixReplacement)
    }

    @Test
    fun `mistyped comment directive with colon is a warning`() {
        val text = """
            --liquibase formatted sql
            --changeset a:1
            --coasmment: GTT definitions persist
            SELECT 1;
        """.trimIndent()
        val problem = LiquibaseParser.parse(text).problems.single()
        assertFalse(problem.isError)
        assertTrue(problem.message.contains("did you mean '--comment'"))
        assertEquals("comment", problem.fixReplacement)
    }

    @Test
    fun `mistyped changeset attribute is an error with suggestion`() {
        val text = """
            --liquibase formatted sql
            --changeset a:1 context:IN runAlways:true failOnErasdror:false
            SELECT 1;
        """.trimIndent()
        val problem = LiquibaseParser.parse(text).problems.single()
        assertTrue(problem.isError)
        assertTrue(problem.message.contains("failOnErasdror"))
        assertTrue(problem.message.contains("did you mean 'failOnError'"))
        assertEquals("failOnErasdror", text.substring(problem.fixRange!!.start, problem.fixRange!!.end))
        assertEquals("failOnError", problem.fixReplacement)
    }

    @Test
    fun `mistyped precondition attribute is flagged`() {
        val text = """
            --liquibase formatted sql
            --changeset a:1
            --preconditions onFaisl:HALT onError:HALT
            SELECT 1;
        """.trimIndent()
        val problem = LiquibaseParser.parse(text).problems.single()
        assertTrue(problem.message.contains("did you mean 'onFail'"))
    }

    @Test
    fun `malformed liquibase header is an error with fix`() {
        val text = """
            --liquibase formated sql
            --changeset a:1
            SELECT 1;
        """.trimIndent()
        val problems = LiquibaseParser.parse(text).problems
        val header = problems.single { it.message.contains("malformed header") }
        assertTrue(header.isError)
        assertEquals("liquibase formatted sql", header.fixReplacement)
        // and because the header is broken, the file is not formatted: that is ALSO flagged
        assertTrue(problems.any { it.message.contains("no '--liquibase formatted sql' header") })
    }

    @Test
    fun `changesets without formatted header are an error with insert-header fix`() {
        val text = """
            --changeset a:1
            SELECT 1;
        """.trimIndent()
        val problem = LiquibaseParser.parse(text).problems.single()
        assertTrue(problem.isError)
        assertTrue(problem.message.contains("no '--liquibase formatted sql' header"))
        assertEquals(SrcRange(0, 0), problem.fixRange)
        assertEquals("--liquibase formatted sql\n", problem.fixReplacement)
    }

    @Test
    fun `invalid boolean attribute value is an error with suggestion`() {
        val text = """
            --liquibase formatted sql
            --changeset a:1 runOnChange:ture
            SELECT 1;
        """.trimIndent()
        val problem = LiquibaseParser.parse(text).problems.single()
        assertTrue(problem.isError)
        assertTrue(problem.message.contains("expected true or false"))
        assertTrue(problem.message.contains("did you mean 'true'"))
        assertEquals("ture", text.substring(problem.fixRange!!.start, problem.fixRange!!.end))
        assertEquals("true", problem.fixReplacement)
    }

    @Test
    fun `invalid onFail value is an error with suggestion`() {
        val text = """
            --liquibase formatted sql
            --changeset a:1
            --preconditions onFail:HALTT
            SELECT 1;
        """.trimIndent()
        val problem = LiquibaseParser.parse(text).problems.single()
        assertTrue(problem.message.contains("expected HALT or CONTINUE or MARK_RAN or WARN"))
        assertEquals("HALT", problem.fixReplacement)
    }

    @Test
    fun `prose comments and valid directives are never flagged as typos`() {
        val text = """
            --liquibase formatted sql
            --changeset a:1 context:IN labels:core runOnChange:true failOnError:false
            -- rollbacks are handled below by hand
            -- this section populates reference data
            --comment: a perfectly fine comment
            --validCheckSum: 8:abc123
            SELECT 1;
            --rollback DELETE FROM t;
        """.trimIndent()
        assertTrue(LiquibaseParser.parse(text).problems.isEmpty(),
            "unexpected: ${LiquibaseParser.parse(text).problems}")
    }

    @Test
    fun `non formatted file has no changesets and no problems`() {
        val file = LiquibaseParser.parse("SELECT 1;\nSELECT 2;")
        assertFalse(file.formatted)
        assertTrue(file.changesets.isEmpty())
        assertTrue(file.problems.isEmpty())
    }

    @Test
    fun `quoted attribute values and extra attributes are kept`() {
        val text = """
            --liquibase formatted sql
            --changeset team:9 labels:"one, two" dbms:postgresql splitStatements:false
            SELECT 1;
        """.trimIndent()
        val changeset = LiquibaseParser.parse(text).changesets.single()
        assertEquals(listOf("one", "two"), changeset.labels)
        assertEquals("postgresql", changeset.attributes["dbms"])
        assertEquals("false", changeset.attributes["splitstatements"])
    }
}
