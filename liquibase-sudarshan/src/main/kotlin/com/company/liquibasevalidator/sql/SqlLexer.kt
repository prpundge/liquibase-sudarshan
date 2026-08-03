package com.company.liquibasevalidator.sql

/**
 * Offset-tracking SQL tokenizer for the PostgreSQL dialect subset used by Liquibase
 * formatted SQL repositories.
 *
 * Line comments (`-- ...`, which carry all Liquibase metadata) and block comments are
 * treated as trivia and skipped: they never break statement parsing. Dollar-quoted
 * strings (`$tag$ ... $tag$`), standard `'...'` strings with `''` escapes and `E'...'`
 * strings are lexed as single STRING tokens so stray semicolons inside them cannot
 * confuse statement recovery.
 */
class SqlLexer(private val text: String) {

    fun tokenize(): List<SqlToken> {
        val tokens = ArrayList<SqlToken>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++

                // line comment
                c == '-' && i + 1 < n && text[i + 1] == '-' -> {
                    while (i < n && text[i] != '\n') i++
                }

                // block comment (nested per PostgreSQL rules)
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') { depth++; i += 2 }
                        else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') { depth--; i += 2 }
                        else i++
                    }
                }

                c == '\'' -> i = lexString(tokens, i, escapeString = false)

                (c == 'E' || c == 'e') && i + 1 < n && text[i + 1] == '\'' -> {
                    i = lexString(tokens, i + 1, escapeString = true, tokenStart = i)
                }

                c == '$' -> {
                    val dollarEnd = matchDollarTag(i)
                    if (dollarEnd > i) i = lexDollarString(tokens, i, dollarEnd) else i = lexOperator(tokens, i)
                }

                c == '"' -> i = lexQuotedIdent(tokens, i)

                c.isDigit() || (c == '.' && i + 1 < n && text[i + 1].isDigit()) -> i = lexNumber(tokens, i)

                c.isLetter() || c == '_' -> i = lexIdent(tokens, i)

                c == '(' -> { tokens += tok(SqlTokenType.LPAREN, i, i + 1); i++ }
                c == ')' -> { tokens += tok(SqlTokenType.RPAREN, i, i + 1); i++ }
                c == ',' -> { tokens += tok(SqlTokenType.COMMA, i, i + 1); i++ }
                c == ';' -> { tokens += tok(SqlTokenType.SEMICOLON, i, i + 1); i++ }
                c == '.' -> { tokens += tok(SqlTokenType.DOT, i, i + 1); i++ }
                c == ':' && i + 1 < n && text[i + 1] == ':' -> { tokens += tok(SqlTokenType.CAST, i, i + 2); i += 2 }

                else -> i = lexOperator(tokens, i)
            }
        }
        tokens += SqlToken(SqlTokenType.EOF, "", "", n, n)
        return tokens
    }

    private fun tok(type: SqlTokenType, start: Int, end: Int, value: String = text.substring(start, end)) =
        SqlToken(type, text.substring(start, end), value, start, end)

    private fun lexString(tokens: MutableList<SqlToken>, quoteAt: Int, escapeString: Boolean, tokenStart: Int = quoteAt): Int {
        val sb = StringBuilder()
        var i = quoteAt + 1
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '\'' && i + 1 < n && text[i + 1] == '\'' -> { sb.append('\''); i += 2 }
                c == '\'' -> {
                    i++
                    tokens += SqlToken(SqlTokenType.STRING, text.substring(tokenStart, i), sb.toString(), tokenStart, i)
                    return i
                }
                escapeString && c == '\\' && i + 1 < n -> { sb.append(text[i + 1]); i += 2 }
                else -> { sb.append(c); i++ }
            }
        }
        // unterminated string: emit what we have so downstream code still sees a value
        tokens += SqlToken(SqlTokenType.STRING, text.substring(tokenStart, n), sb.toString(), tokenStart, n)
        return n
    }

    /** Returns index just past `$tag$` if [at] starts a dollar-quote tag, else [at]. */
    private fun matchDollarTag(at: Int): Int {
        var i = at + 1
        val n = text.length
        while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        return if (i < n && text[i] == '$') i + 1 else at
    }

    private fun lexDollarString(tokens: MutableList<SqlToken>, start: Int, tagEnd: Int): Int {
        val tag = text.substring(start, tagEnd)
        val close = text.indexOf(tag, tagEnd)
        val end = if (close < 0) text.length else close + tag.length
        val value = if (close < 0) text.substring(tagEnd) else text.substring(tagEnd, close)
        tokens += SqlToken(SqlTokenType.STRING, text.substring(start, end), value, start, end)
        return end
    }

    private fun lexQuotedIdent(tokens: MutableList<SqlToken>, start: Int): Int {
        val sb = StringBuilder()
        var i = start + 1
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '"' && i + 1 < n && text[i + 1] == '"' -> { sb.append('"'); i += 2 }
                c == '"' -> {
                    i++
                    tokens += SqlToken(SqlTokenType.QUOTED_IDENT, text.substring(start, i), sb.toString(), start, i)
                    return i
                }
                else -> { sb.append(c); i++ }
            }
        }
        tokens += SqlToken(SqlTokenType.QUOTED_IDENT, text.substring(start, n), sb.toString(), start, n)
        return n
    }

    private fun lexNumber(tokens: MutableList<SqlToken>, start: Int): Int {
        var i = start
        val n = text.length
        while (i < n && text[i].isDigit()) i++
        if (i < n && text[i] == '.') {
            i++
            while (i < n && text[i].isDigit()) i++
        }
        if (i < n && (text[i] == 'e' || text[i] == 'E')) {
            var j = i + 1
            if (j < n && (text[j] == '+' || text[j] == '-')) j++
            if (j < n && text[j].isDigit()) {
                i = j
                while (i < n && text[i].isDigit()) i++
            }
        }
        tokens += tok(SqlTokenType.NUMBER, start, i)
        return i
    }

    private fun lexIdent(tokens: MutableList<SqlToken>, start: Int): Int {
        var i = start
        val n = text.length
        while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
        tokens += tok(SqlTokenType.IDENT, start, i)
        return i
    }

    private fun lexOperator(tokens: MutableList<SqlToken>, start: Int): Int {
        val two = if (start + 2 <= text.length) text.substring(start, start + 2) else ""
        val len = if (two in setOf("<=", ">=", "<>", "!=", "||")) 2 else 1
        tokens += tok(SqlTokenType.OPERATOR, start, start + len)
        return start + len
    }
}
