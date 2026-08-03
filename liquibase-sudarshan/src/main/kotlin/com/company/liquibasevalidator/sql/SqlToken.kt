package com.company.liquibasevalidator.sql

/** Token categories produced by [SqlLexer]. */
enum class SqlTokenType {
    IDENT,          // unquoted identifier or keyword (SQL keywords are identified at parse time)
    QUOTED_IDENT,   // "Quoted Identifier"
    STRING,         // 'string literal' (including E'..' and $tag$..$tag$)
    NUMBER,         // 123, 12.5, 1e4
    LPAREN, RPAREN, COMMA, SEMICOLON, DOT,
    CAST,           // ::
    OPERATOR,       // = < > <= >= <> != + - * / etc.
    EOF,
}

/**
 * A lexical token with absolute offsets into the original file text.
 * [text] is the raw source text; [value] is the decoded value for strings
 * (quote characters stripped, escaped quotes resolved).
 */
data class SqlToken(
    val type: SqlTokenType,
    val text: String,
    val value: String,
    val start: Int,
    val end: Int,
    /** Uppercased [text], precomputed once at lexing — the parser reads it in hot loops. */
    val upper: String = text.uppercase(),
) {
    /** Case-insensitive keyword/identifier comparison for unquoted identifiers. */
    fun isKeyword(vararg keywords: String): Boolean =
        type == SqlTokenType.IDENT && keywords.any { it.equals(text, ignoreCase = true) }
}

/** A simple text range with absolute offsets (start inclusive, end exclusive). */
data class SrcRange(val start: Int, val end: Int) {
    companion object {
        fun of(token: SqlToken) = SrcRange(token.start, token.end)
        fun between(first: SqlToken, last: SqlToken) = SrcRange(first.start, last.end)
    }
}
