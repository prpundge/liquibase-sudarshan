package com.company.liquibasevalidator.sql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SqlLexerTest {

    private fun lex(text: String) = SqlLexer(text).tokenize().dropLast(1) // drop EOF

    @Test
    fun `tokenizes identifiers keywords and punctuation with offsets`() {
        val tokens = lex("INSERT INTO tmp(code)")
        assertEquals(listOf("INSERT", "INTO", "tmp", "(", "code", ")"), tokens.map { it.text })
        assertEquals(0, tokens[0].start)
        assertEquals(6, tokens[0].end)
        assertEquals(12, tokens[2].start)
        assertEquals(15, tokens[2].end)
    }

    @Test
    fun `line comments are trivia and never break tokens`() {
        val tokens = lex("SELECT 1 -- comment with 'quotes' and ; semicolons\n, 2")
        assertEquals(listOf("SELECT", "1", ",", "2"), tokens.map { it.text })
    }

    @Test
    fun `block comments including nested are skipped`() {
        val tokens = lex("a /* outer /* inner */ still */ b")
        assertEquals(listOf("a", "b"), tokens.map { it.text })
    }

    @Test
    fun `string literals decode escaped quotes`() {
        val tokens = lex("'it''s'")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenType.STRING, tokens[0].type)
        assertEquals("it's", tokens[0].value)
        assertEquals(0, tokens[0].start)
        assertEquals(7, tokens[0].end)
    }

    @Test
    fun `dollar quoted strings are single tokens`() {
        val tokens = lex("\$tag\$ has ; and ' inside \$tag\$ next")
        assertEquals(SqlTokenType.STRING, tokens[0].type)
        assertTrue(tokens[0].value.contains("has ; and ' inside"))
        assertEquals("next", tokens[1].text)
    }

    @Test
    fun `cast operator and numbers`() {
        val tokens = lex("'x'::uuid, 12.5, -3")
        assertEquals(SqlTokenType.STRING, tokens[0].type)
        assertEquals(SqlTokenType.CAST, tokens[1].type)
        assertEquals("uuid", tokens[2].text)
        assertEquals("12.5", tokens[4].text)
        assertEquals(SqlTokenType.NUMBER, tokens[4].type)
        assertEquals("-", tokens[6].text)
        assertEquals("3", tokens[7].text)
    }

    @Test
    fun `quoted identifiers keep case and decode double quotes`() {
        val tokens = lex("\"My Table\"")
        assertEquals(SqlTokenType.QUOTED_IDENT, tokens[0].type)
        assertEquals("My Table", tokens[0].value)
    }
}
