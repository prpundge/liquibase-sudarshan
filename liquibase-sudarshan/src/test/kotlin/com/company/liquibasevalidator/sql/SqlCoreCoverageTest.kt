package com.company.liquibasevalidator.sql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Coverage-focused tests for the sql package core classes: lexer edge branches,
 * secondary properties of token/AST data classes, datatype resolution arms and
 * TextDistance early exits.
 */
class SqlCoreCoverageTest {

    private fun lex(text: String) = SqlLexer(text).tokenize().dropLast(1) // drop EOF

    // -----------------------------------------------------------------------------------------
    // SqlLexer — string forms
    // -----------------------------------------------------------------------------------------

    @Test
    fun `escape string E-quote decodes backslash escapes into a single STRING token`() {
        val tokens = lex("E'a\\'b'")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenType.STRING, tokens[0].type)
        assertEquals("E'a\\'b'", tokens[0].text)
        assertEquals("a'b", tokens[0].value)
        assertEquals(0, tokens[0].start)
        assertEquals(7, tokens[0].end)
    }

    @Test
    fun `lowercase e-quote string is also an escape string`() {
        val tokens = lex("e'x\\ny'")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenType.STRING, tokens[0].type)
        assertEquals("xny", tokens[0].value) // lexer resolves \n to the literal next char
    }

    @Test
    fun `escape string with backslash as the last char of text is unterminated`() {
        val tokens = lex("E'a\\")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenType.STRING, tokens[0].type)
        assertEquals("a\\", tokens[0].value) // backslash kept literally: no next char to escape
        assertEquals(0, tokens[0].start)
        assertEquals(4, tokens[0].end)
    }

    @Test
    fun `unterminated string literal still emits a STRING token spanning to end of text`() {
        val tokens = lex("'abc")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenType.STRING, tokens[0].type)
        assertEquals("abc", tokens[0].value)
        assertEquals(0, tokens[0].start)
        assertEquals(4, tokens[0].end)
    }

    @Test
    fun `unterminated dollar quoted string runs to end of text`() {
        val tokens = lex("\$\$abc")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenType.STRING, tokens[0].type)
        assertEquals("abc", tokens[0].value)
        assertEquals(5, tokens[0].end)
    }

    @Test
    fun `terminated tagged dollar string keeps tag out of value`() {
        val tokens = lex("\$fn\$body ; text\$fn\$ rest")
        assertEquals(SqlTokenType.STRING, tokens[0].type)
        assertEquals("body ; text", tokens[0].value)
        assertEquals(19, tokens[0].end)
        assertEquals("rest", tokens[1].text)
    }

    @Test
    fun `dollar not forming a quote tag is lexed as a single-char operator`() {
        // "$5" — digit stops the tag scan and no closing $ follows, so it is not a tag
        val tokens = lex("\$5")
        assertEquals(SqlTokenType.OPERATOR, tokens[0].type)
        assertEquals("$", tokens[0].text)
        assertEquals(SqlTokenType.NUMBER, tokens[1].type)
        assertEquals("5", tokens[1].text)
    }

    @Test
    fun `dollar followed by tag chars but no closing dollar falls back to operator`() {
        val tokens = lex("\$tag rest")
        assertEquals(SqlTokenType.OPERATOR, tokens[0].type)
        assertEquals("$", tokens[0].text)
        assertEquals("tag", tokens[1].text)
        assertEquals("rest", tokens[2].text)
    }

    @Test
    fun `operator at last position of text is lexed as one char`() {
        // '$' as the very last char: matchDollarTag hits end of text, lexOperator cannot read 2 chars
        val tokens = lex("+\$")
        assertEquals(listOf("+", "$"), tokens.map { it.text })
        assertEquals(SqlTokenType.OPERATOR, tokens[0].type)
        assertEquals(SqlTokenType.OPERATOR, tokens[1].type)
    }

    // -----------------------------------------------------------------------------------------
    // SqlLexer — quoted identifiers
    // -----------------------------------------------------------------------------------------

    @Test
    fun `quoted identifier decodes doubled double-quotes`() {
        val tokens = lex("\"a\"\"b\"")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenType.QUOTED_IDENT, tokens[0].type)
        assertEquals("a\"b", tokens[0].value)
        assertEquals(6, tokens[0].end)
    }

    @Test
    fun `unterminated quoted identifier emits token spanning to end of text`() {
        val tokens = lex("\"abc")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenType.QUOTED_IDENT, tokens[0].type)
        assertEquals("abc", tokens[0].value)
        assertEquals(4, tokens[0].end)
    }

    // -----------------------------------------------------------------------------------------
    // SqlLexer — numbers
    // -----------------------------------------------------------------------------------------

    @Test
    fun `numbers with exponent forms are single NUMBER tokens`() {
        assertEquals(listOf("1e5"), lex("1e5").map { it.text })
        assertEquals(listOf("2E+10"), lex("2E+10").map { it.text })
        assertEquals(listOf("3.5e-2"), lex("3.5e-2").map { it.text })
        assertEquals(SqlTokenType.NUMBER, lex("1e5")[0].type)
    }

    @Test
    fun `trailing e without exponent digits is not part of the number`() {
        val tokens = lex("7e")
        assertEquals(SqlTokenType.NUMBER, tokens[0].type)
        assertEquals("7", tokens[0].text)
        assertEquals(SqlTokenType.IDENT, tokens[1].type)
        assertEquals("e", tokens[1].text)
    }

    @Test
    fun `e with sign but no digits is not an exponent`() {
        val tokens = lex("1e+x")
        assertEquals(listOf("1", "e", "+", "x"), tokens.map { it.text })
        assertEquals(SqlTokenType.NUMBER, tokens[0].type)
    }

    @Test
    fun `leading-dot decimal is a NUMBER token`() {
        val tokens = lex(".5")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenType.NUMBER, tokens[0].type)
        assertEquals(".5", tokens[0].text)
    }

    // -----------------------------------------------------------------------------------------
    // SqlLexer — operators and whitespace
    // -----------------------------------------------------------------------------------------

    @Test
    fun `two-char comparison operators are single tokens`() {
        val tokens = lex("a <= b >= c <> d != e || f")
        val ops = tokens.filter { it.type == SqlTokenType.OPERATOR }.map { it.text }
        assertEquals(listOf("<=", ">=", "<>", "!=", "||"), ops)
    }

    @Test
    fun `single-char operator when next char does not extend it`() {
        val tokens = lex("a < b")
        assertEquals(SqlTokenType.OPERATOR, tokens[1].type)
        assertEquals("<", tokens[1].text)
    }

    @Test
    fun `carriage-return-only separators are treated as whitespace`() {
        val tokens = lex("SELECT\r1\r,\r2")
        assertEquals(listOf("SELECT", "1", ",", "2"), tokens.map { it.text })
    }

    // -----------------------------------------------------------------------------------------
    // SqlToken / SrcRange secondary API
    // -----------------------------------------------------------------------------------------

    @Test
    fun `SrcRange between spans from first token start to last token end`() {
        val tokens = lex("INSERT INTO tmp")
        val range = SrcRange.between(tokens.first(), tokens.last())
        assertEquals(0, range.start)
        assertEquals(15, range.end)
        assertEquals(SrcRange(12, 15), SrcRange.of(tokens.last()))
    }

    // -----------------------------------------------------------------------------------------
    // SqlAst secondary properties
    // -----------------------------------------------------------------------------------------

    @Test
    fun `DefaultKw value expression exposes its range and value equality`() {
        val kw = SqlValueExpr.DefaultKw(SrcRange(3, 10))
        assertEquals(SrcRange(3, 10), kw.range)
        assertEquals(SqlValueExpr.DefaultKw(SrcRange(3, 10)), kw)
    }

    @Test
    fun `ColumnRef lowercases qualifier and column, null qualifier stays null`() {
        val qualified = SqlValueExpr.ColumnRef("Source", "Code", SrcRange(0, 11))
        assertEquals("code", qualified.columnLower)
        assertEquals("source", qualified.qualifierLower)

        val bare = SqlValueExpr.ColumnRef(null, "AMOUNT", SrcRange(0, 6))
        assertNull(bare.qualifierLower)
        assertEquals("amount", bare.columnLower)
    }

    @Test
    fun `MergeStatement lowercases target and source table names`() {
        val merge = MergeStatement(
            targetTable = "Accounts",
            targetTableRange = SrcRange(11, 19),
            targetAlias = "t",
            sourceTable = "Staging",
            sourceTableRange = SrcRange(26, 33),
            sourceAlias = "s",
            onConditions = emptyList(),
            updateAssignments = emptyList(),
            insertClause = null,
            hasDelete = false,
            range = SrcRange(0, 40),
        )
        assertEquals("accounts", merge.targetTableLower)
        assertEquals("staging", merge.sourceTableLower)
    }

    @Test
    fun `MergeStatement sourceTableLower is null when source is a subquery`() {
        val merge = MergeStatement(
            targetTable = "accounts",
            targetTableRange = SrcRange(11, 19),
            targetAlias = null,
            sourceTable = null,
            sourceTableRange = null,
            sourceAlias = "s",
            onConditions = emptyList(),
            updateAssignments = emptyList(),
            insertClause = null,
            hasDelete = false,
            range = SrcRange(0, 40),
        )
        assertNull(merge.sourceTableLower)
        assertEquals("accounts", merge.targetTableLower)
    }

    // -----------------------------------------------------------------------------------------
    // SqlDataType.resolve arms
    // -----------------------------------------------------------------------------------------

    @Test
    fun `resolve maps smallint aliases to SMALLINT`() {
        assertEquals(TypeKind.SMALLINT, SqlDataType.resolve(listOf("smallint"), emptyList(), "smallint").kind)
        assertEquals(TypeKind.SMALLINT, SqlDataType.resolve(listOf("INT2"), emptyList(), "INT2").kind)
        assertEquals(TypeFamily.INTEGER, SqlDataType.resolve(listOf("SMALLSERIAL"), emptyList(), "SMALLSERIAL").family)
    }

    @Test
    fun `resolve maps real aliases to REAL floating family`() {
        val real = SqlDataType.resolve(listOf("REAL"), emptyList(), "REAL")
        assertEquals(TypeKind.REAL, real.kind)
        assertEquals(TypeFamily.FLOATING, real.family)
        assertEquals(TypeKind.REAL, SqlDataType.resolve(listOf("float4"), emptyList(), "float4").kind)
    }

    @Test
    fun `resolve maps time variants to TIME`() {
        assertEquals(TypeKind.TIME, SqlDataType.resolve(listOf("TIME"), emptyList(), "TIME").kind)
        val timeTz = SqlDataType.resolve(listOf("time", "with", "time", "zone"), emptyList(), "time with time zone")
        assertEquals(TypeKind.TIME, timeTz.kind)
        assertEquals(TypeFamily.TIME, timeTz.family)
    }

    @Test
    fun `resolve maps bytea and blob to BYTEA`() {
        assertEquals(TypeKind.BYTEA, SqlDataType.resolve(listOf("BYTEA"), emptyList(), "BYTEA").kind)
        assertEquals(TypeKind.BYTEA, SqlDataType.resolve(listOf("blob"), emptyList(), "blob").kind)
    }

    @Test
    fun `resolve distinguishes JSON and JSONB but same family`() {
        val json = SqlDataType.resolve(listOf("json"), emptyList(), "json")
        val jsonb = SqlDataType.resolve(listOf("JSONB"), emptyList(), "JSONB")
        assertEquals(TypeKind.JSON, json.kind)
        assertEquals(TypeKind.JSONB, jsonb.kind)
        assertEquals(TypeFamily.JSON, json.family)
        assertEquals(TypeFamily.JSON, jsonb.family)
    }

    @Test
    fun `resolve maps unknown types like INTERVAL to OTHER preserving raw spelling`() {
        val interval = SqlDataType.resolve(listOf("INTERVAL"), emptyList(), "INTERVAL DAY")
        assertEquals(TypeKind.OTHER, interval.kind)
        assertEquals(TypeFamily.OTHER, interval.family)
        assertEquals("INTERVAL DAY", interval.raw)
        assertEquals("OTHER", interval.display())
    }

    @Test
    fun `decimal without precision displays bare DECIMAL`() {
        val numeric = SqlDataType.resolve(listOf("NUMERIC"), emptyList(), "NUMERIC")
        assertEquals(TypeKind.DECIMAL, numeric.kind)
        assertNull(numeric.precision)
        assertNull(numeric.scale)
        assertEquals("DECIMAL", numeric.display())
    }

    // -----------------------------------------------------------------------------------------
    // TextDistance
    // -----------------------------------------------------------------------------------------

    @Test
    fun `nearest returns closest candidate within the distance cutoff`() {
        assertEquals("CREATE", TextDistance.nearest("CREAT", listOf("CREATE", "DROP")))
        assertEquals("DROP", TextDistance.nearest("drpo", listOf("CREATE", "DROP")))
    }

    @Test
    fun `nearest returns null when best candidate exceeds the cutoff`() {
        assertNull(TextDistance.nearest("zzzzzz", listOf("CREATE", "DROP")))
    }

    @Test
    fun `nearest returns null for empty candidate collection`() {
        assertNull(TextDistance.nearest("CREATE", emptyList()))
    }

    @Test
    fun `editDistance handles empty strings`() {
        assertEquals(3, TextDistance.editDistance("", "abc"))
        assertEquals(2, TextDistance.editDistance("ab", ""))
        assertEquals(0, TextDistance.editDistance("same", "same"))
    }
}
