package com.company.liquibasevalidator.sql

/**
 * Tolerant recursive-descent parser for the PostgreSQL subset used in Liquibase SQL
 * repositories: CREATE [TEMP] TABLE, INSERT ... VALUES, MERGE, ALTER TABLE ADD CONSTRAINT,
 * CREATE [UNIQUE] INDEX and DELETE. Anything else is skipped statement-wise (never throws),
 * so unknown syntax can never break validation of the rest of the file.
 *
 * Every AST node carries absolute source offsets for precise editor highlighting.
 */
class SqlParser private constructor(private val text: String) {

    private val tokens: List<SqlToken> = SqlLexer(text).tokenize()
    private var pos = 0
    private val notes = mutableListOf<ParseNote>()

    companion object {
        /** SQL value keywords that must not be mistaken for column references. */
        private val VALUE_KEYWORDS = setOf(
            "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME", "LOCALTIMESTAMP",
            "LOCALTIME", "CURRENT_USER", "SESSION_USER", "USER", "NOW",
            // Oracle
            "SYSDATE", "SYSTIMESTAMP", "ROWNUM",
        )

        /** Oracle sequence pseudo-columns: `my_seq.NEXTVAL` is a value, not a column ref. */
        private val SEQUENCE_PSEUDO_COLUMNS = setOf("NEXTVAL", "CURRVAL")

        fun parse(text: String): SqlScript = SqlParser(text).parseScript()
    }

    // -----------------------------------------------------------------------------------------
    // Token helpers
    // -----------------------------------------------------------------------------------------

    private fun peek(offset: Int = 0): SqlToken = tokens[minOf(pos + offset, tokens.lastIndex)]
    private fun advance(): SqlToken = tokens[pos].also { if (pos < tokens.lastIndex) pos++ }
    private fun atEof(): Boolean = peek().type == SqlTokenType.EOF
    private fun accept(vararg keywords: String): SqlToken? =
        if (peek().isKeyword(*keywords)) advance() else null

    private fun isNameToken(t: SqlToken) = t.type == SqlTokenType.IDENT || t.type == SqlTokenType.QUOTED_IDENT

    private fun nameOf(t: SqlToken): String = if (t.type == SqlTokenType.QUOTED_IDENT) t.value else t.text

    /** Parses a possibly schema-qualified name; returns the last segment plus the full range. */
    private fun parseQualifiedName(): Pair<String, SrcRange>? {
        if (!isNameToken(peek())) return null
        val first = advance()
        var name = nameOf(first)
        var last = first
        while (peek().type == SqlTokenType.DOT && isNameToken(peek(1))) {
            advance()
            last = advance()
            name = nameOf(last)
        }
        return name to SrcRange(first.start, last.end)
    }

    /**
     * Skips tokens (paren-balanced) until one of [stopKeywords] or [stopTypes] at depth 0.
     * Always stops at a top-level semicolon: intra-statement skipping must never cross a
     * statement boundary.
     */
    private fun skipBalancedUntil(stopKeywords: Set<String>, stopTypes: Set<SqlTokenType>): SqlToken? {
        var depth = 0
        var lastConsumed: SqlToken? = null
        while (!atEof()) {
            val t = peek()
            if (depth == 0 && (t.type == SqlTokenType.SEMICOLON || t.type in stopTypes ||
                    (t.type == SqlTokenType.IDENT && t.upper in stopKeywords))
            ) {
                return lastConsumed
            }
            if (t.type == SqlTokenType.LPAREN) depth++
            if (t.type == SqlTokenType.RPAREN) {
                if (depth == 0) return lastConsumed
                depth--
            }
            lastConsumed = advance()
        }
        return lastConsumed
    }

    private fun skipToStatementEnd(first: SqlToken): SrcRange {
        var last = first
        var depth = 0
        while (!atEof() && peek().type != SqlTokenType.SEMICOLON) {
            val t = peek()
            if (t.type == SqlTokenType.LPAREN) depth++
            if (t.type == SqlTokenType.RPAREN && depth > 0) depth--
            // A new statement beginning without a ';' in between: missing delimiter.
            // Stop WITHOUT consuming so the next statement still parses (and validates).
            if (depth == 0 && t.start > first.start && isStatementStart()) {
                notes += ParseNote(
                    "missing statement delimiter ';' — a new statement starts here without the " +
                        "previous one being terminated",
                    SrcRange.of(t),
                )
                return SrcRange(first.start, maxOf(last.end, first.end))
            }
            last = advance()
        }
        if (peek().type == SqlTokenType.SEMICOLON) last = advance()
        return SrcRange(first.start, maxOf(last.end, first.end))
    }

    /** Conservative lookahead: only unambiguous statement-start keyword pairs count, so
     *  tails like `ON COMMIT DELETE ROWS` or `DO UPDATE SET` never trigger false positives. */
    private fun isStatementStart(): Boolean {
        val t = peek()
        if (t.type != SqlTokenType.IDENT) return false
        return when (t.upper) {
            "INSERT", "MERGE" -> peek(1).isKeyword("INTO")
            "CREATE" -> peek(1).isKeyword(
                "TABLE", "GLOBAL", "TEMP", "TEMPORARY", "UNLOGGED", "UNIQUE", "INDEX", "SEQUENCE", "VIEW", "OR",
            )
            "ALTER" -> peek(1).isKeyword("TABLE", "SEQUENCE", "INDEX")
            "DELETE" -> peek(1).isKeyword("FROM")
            "DROP" -> peek(1).isKeyword("TABLE", "SEQUENCE", "INDEX", "VIEW")
            "TRUNCATE" -> peek(1).isKeyword("TABLE")
            "UPDATE" -> isNameToken(peek(1)) && peek(2).isKeyword("SET")
            else -> false
        }
    }

    // -----------------------------------------------------------------------------------------
    // Script / statements
    // -----------------------------------------------------------------------------------------

    private fun parseScript(): SqlScript {
        val statements = mutableListOf<SqlStatement>()
        while (!atEof()) {
            if (peek().type == SqlTokenType.SEMICOLON) { advance(); continue }
            statements += parseStatement()
        }
        return SqlScript(statements, notes)
    }

    private fun parseStatement(): SqlStatement {
        val first = peek()
        return when {
            first.isKeyword("CREATE") -> parseCreate()
            first.isKeyword("INSERT") -> parseInsert()
            first.isKeyword("MERGE") -> parseMerge()
            first.isKeyword("ALTER") -> parseAlter()
            first.isKeyword("DELETE") -> parseDelete()
            first.isKeyword("UPDATE") -> parseUpdate()
            first.isKeyword("TRUNCATE") -> parseTruncate()
            first.isKeyword("DROP") -> parseDrop()
            else -> unknownStatement(first)
        }
    }

    private fun unknownStatement(first: SqlToken): UnknownStatement =
        UnknownStatement(skipToStatementEnd(first), first.text, SrcRange.of(first))

    // -----------------------------------------------------------------------------------------
    // CREATE TABLE / CREATE INDEX
    // -----------------------------------------------------------------------------------------

    private fun parseCreate(): SqlStatement {
        val first = advance() // CREATE
        var temporary = false
        while (peek().isKeyword("GLOBAL", "LOCAL", "TEMP", "TEMPORARY", "UNLOGGED")) {
            if (peek().isKeyword("TEMP", "TEMPORARY")) temporary = true
            advance()
        }
        return when {
            peek().isKeyword("TABLE") -> parseCreateTable(first, temporary)
            peek().isKeyword("UNIQUE") && peek(1).isKeyword("INDEX") -> { advance(); parseCreateIndex(first, unique = true) }
            peek().isKeyword("INDEX") -> parseCreateIndex(first, unique = false)
            else -> unknownStatement(first)
        }
    }

    private fun parseCreateTable(first: SqlToken, temporary: Boolean): SqlStatement {
        advance() // TABLE
        if (peek().isKeyword("IF") && peek(1).isKeyword("NOT") && peek(2).isKeyword("EXISTS")) {
            advance(); advance(); advance()
        }
        val (tableName, tableRange) = parseQualifiedName()
            ?: return unknownStatement(first)
        if (peek().type != SqlTokenType.LPAREN) {
            return unknownStatement(first)
        }
        advance() // (

        val columns = mutableListOf<ColumnDef>()
        val constraints = mutableListOf<TableConstraintDef>()
        while (!atEof() && peek().type != SqlTokenType.RPAREN) {
            if (peek().isKeyword("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE", "LIKE")) {
                parseTableConstraint()?.let { constraints += it }
            } else if (isNameToken(peek())) {
                parseColumnDef()?.let { columns += it }
            } else {
                advance() // recovery
            }
            skipBalancedUntil(emptySet(), setOf(SqlTokenType.COMMA)) // recovery to element boundary
            if (peek().type == SqlTokenType.COMMA) advance()
        }
        if (peek().type == SqlTokenType.RPAREN) advance()
        val range = skipToStatementEnd(first) // consumes ON COMMIT DROP etc.
        return CreateTableStatement(tableName, tableRange, temporary, columns, constraints, range)
    }

    private fun parseColumnDef(): ColumnDef? {
        val nameToken = advance()
        val name = nameOf(nameToken)
        val (dataType, typeRange) = parseDataType() ?: return null

        var notNull = false
        var primaryKey = false
        var unique = false
        var hasDefault = false
        var references: ColumnRefTarget? = null
        var last: SqlToken = tokens[pos - 1]

        loop@ while (!atEof()) {
            val t = peek()
            when {
                t.type == SqlTokenType.COMMA || t.type == SqlTokenType.RPAREN -> break@loop
                t.isKeyword("NOT") && peek(1).isKeyword("NULL") -> { advance(); last = advance(); notNull = true }
                t.isKeyword("NULL") -> { last = advance() }
                t.isKeyword("PRIMARY") && peek(1).isKeyword("KEY") -> { advance(); last = advance(); primaryKey = true; notNull = true }
                t.isKeyword("UNIQUE") -> { last = advance(); unique = true }
                t.isKeyword("DEFAULT") -> {
                    advance()
                    hasDefault = true
                    if (peek().isKeyword("NULL")) { last = advance() }
                    else last = skipBalancedUntil(
                        setOf("NOT", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK", "CONSTRAINT", "GENERATED", "COLLATE"),
                        setOf(SqlTokenType.COMMA, SqlTokenType.RPAREN),
                    ) ?: last
                }
                t.isKeyword("REFERENCES") -> {
                    advance()
                    val ref = parseQualifiedName()
                    var refColumn: String? = null
                    if (peek().type == SqlTokenType.LPAREN) {
                        advance()
                        if (isNameToken(peek())) refColumn = nameOf(advance())
                        skipBalancedUntil(emptySet(), setOf(SqlTokenType.RPAREN))
                        if (peek().type == SqlTokenType.RPAREN) last = advance()
                    }
                    if (ref != null) references = ColumnRefTarget(ref.first, refColumn)
                }
                t.isKeyword("CHECK") -> {
                    advance()
                    if (peek().type == SqlTokenType.LPAREN) {
                        advance()
                        skipBalancedUntil(emptySet(), setOf(SqlTokenType.RPAREN))
                        if (peek().type == SqlTokenType.RPAREN) last = advance()
                    }
                }
                t.isKeyword("CONSTRAINT") -> { advance(); if (isNameToken(peek())) last = advance() }
                t.isKeyword("COLLATE") -> { advance(); if (isNameToken(peek())) last = advance() }
                t.isKeyword("GENERATED") -> {
                    last = advance() // GENERATED; the identity/expression tail is skipped
                    last = skipBalancedUntil(
                        setOf("NOT", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK", "CONSTRAINT"),
                        setOf(SqlTokenType.COMMA, SqlTokenType.RPAREN),
                    ) ?: last
                }
                else -> { last = advance() } // recovery: ON DELETE CASCADE tail etc.
            }
        }
        return ColumnDef(
            name = name,
            nameRange = SrcRange.of(nameToken),
            dataType = dataType,
            typeRange = typeRange,
            notNull = notNull,
            primaryKey = primaryKey,
            unique = unique,
            hasDefault = hasDefault,
            references = references,
            range = SrcRange(nameToken.start, last.end),
        )
    }

    private fun parseDataType(): Pair<SqlDataType, SrcRange>? {
        if (peek().type != SqlTokenType.IDENT) return null
        val firstToken = advance()
        var lastToken = firstToken
        val words = mutableListOf(firstToken.text)

        val firstUpper = firstToken.upper
        if (firstUpper == "DOUBLE" && peek().isKeyword("PRECISION")) { lastToken = advance(); words += lastToken.text }
        if ((firstUpper == "CHARACTER" || firstUpper == "CHAR" || firstUpper == "NCHAR" || firstUpper == "BIT") &&
            peek().isKeyword("VARYING")
        ) { lastToken = advance(); words += lastToken.text }

        val args = mutableListOf<Int>()
        if (peek().type == SqlTokenType.LPAREN) {
            advance()
            while (!atEof() && peek().type != SqlTokenType.RPAREN) {
                val t = advance()
                if (t.type == SqlTokenType.NUMBER) t.text.toIntOrNull()?.let { args += it }
            }
            if (peek().type == SqlTokenType.RPAREN) lastToken = advance()
        }
        if ((firstUpper == "TIMESTAMP" || firstUpper == "TIME") && peek().isKeyword("WITH", "WITHOUT")) {
            words += advance().text
            if (peek().isKeyword("TIME")) words += advance().text
            if (peek().isKeyword("ZONE")) { lastToken = advance(); words += lastToken.text }
        }
        val range = SrcRange(firstToken.start, lastToken.end)
        return SqlDataType.resolve(words, args, text.substring(range.start, range.end)) to range
    }

    private fun parseTableConstraint(): TableConstraintDef? {
        val first = peek()
        var name: String? = null
        if (accept("CONSTRAINT") != null && isNameToken(peek())) name = nameOf(advance())

        fun columnList(): List<String> {
            val cols = mutableListOf<String>()
            if (peek().type == SqlTokenType.LPAREN) {
                advance()
                while (!atEof() && peek().type != SqlTokenType.RPAREN) {
                    val t = advance()
                    if (isNameToken(t)) cols += nameOf(t)
                }
                if (peek().type == SqlTokenType.RPAREN) advance()
            }
            return cols
        }

        var last: SqlToken
        return when {
            peek().isKeyword("PRIMARY") && peek(1).isKeyword("KEY") -> {
                advance(); last = advance()
                val cols = columnList()
                TableConstraintDef(TableConstraintKind.PRIMARY_KEY, name, cols, range = SrcRange(first.start, tokens[pos - 1].end.coerceAtLeast(last.end)))
            }
            peek().isKeyword("UNIQUE") -> {
                last = advance()
                val cols = columnList()
                TableConstraintDef(TableConstraintKind.UNIQUE, name, cols, range = SrcRange(first.start, tokens[pos - 1].end.coerceAtLeast(last.end)))
            }
            peek().isKeyword("FOREIGN") && peek(1).isKeyword("KEY") -> {
                advance(); advance()
                val cols = columnList()
                var refTable: String? = null
                var refCols: List<String> = emptyList()
                if (accept("REFERENCES") != null) {
                    refTable = parseQualifiedName()?.first
                    refCols = columnList()
                }
                skipBalancedUntil(emptySet(), setOf(SqlTokenType.COMMA, SqlTokenType.RPAREN))
                TableConstraintDef(
                    TableConstraintKind.FOREIGN_KEY, name, cols, refTable, refCols,
                    SrcRange(first.start, tokens[maxOf(pos - 1, 0)].end),
                )
            }
            peek().isKeyword("CHECK") -> {
                advance()
                if (peek().type == SqlTokenType.LPAREN) {
                    advance()
                    skipBalancedUntil(emptySet(), setOf(SqlTokenType.RPAREN))
                    if (peek().type == SqlTokenType.RPAREN) advance()
                }
                TableConstraintDef(TableConstraintKind.CHECK, name, emptyList(), range = SrcRange(first.start, tokens[maxOf(pos - 1, 0)].end))
            }
            else -> {
                skipBalancedUntil(emptySet(), setOf(SqlTokenType.COMMA, SqlTokenType.RPAREN))
                null
            }
        }
    }

    private fun parseCreateIndex(first: SqlToken, unique: Boolean): SqlStatement {
        advance() // INDEX
        accept("CONCURRENTLY")
        if (peek().isKeyword("IF") && peek(1).isKeyword("NOT") && peek(2).isKeyword("EXISTS")) {
            advance(); advance(); advance()
        }
        var indexName: String? = null
        if (isNameToken(peek()) && !peek().isKeyword("ON")) indexName = nameOf(advance())
        if (accept("ON") == null) return unknownStatement(first)
        val (tableName, _) = parseQualifiedName() ?: return unknownStatement(first)
        if (accept("USING") != null && isNameToken(peek())) advance()

        val columns = mutableListOf<String>()
        var plainColumns = true
        if (peek().type == SqlTokenType.LPAREN) {
            advance()
            while (!atEof() && peek().type != SqlTokenType.RPAREN) {
                if (isNameToken(peek()) &&
                    (peek(1).type == SqlTokenType.COMMA || peek(1).type == SqlTokenType.RPAREN ||
                        peek(1).isKeyword("ASC", "DESC", "NULLS"))
                ) {
                    columns += nameOf(advance())
                    while (peek().isKeyword("ASC", "DESC", "NULLS", "FIRST", "LAST")) advance()
                } else {
                    plainColumns = false
                    skipBalancedUntil(emptySet(), setOf(SqlTokenType.COMMA, SqlTokenType.RPAREN))
                }
                if (peek().type == SqlTokenType.COMMA) advance()
            }
            if (peek().type == SqlTokenType.RPAREN) advance()
        }
        val range = skipToStatementEnd(first)
        return if (plainColumns && columns.isNotEmpty()) {
            CreateIndexStatement(unique, indexName, tableName, columns, range)
        } else {
            UnknownStatement(range, first.text, SrcRange.of(first))
        }
    }

    // -----------------------------------------------------------------------------------------
    // ALTER TABLE
    // -----------------------------------------------------------------------------------------

    private fun parseAlter(): SqlStatement {
        val first = advance() // ALTER
        if (accept("TABLE") == null) return unknownStatement(first)
        if (peek().isKeyword("IF") && peek(1).isKeyword("EXISTS")) { advance(); advance() }
        accept("ONLY")
        val (tableName, _) = parseQualifiedName() ?: return unknownStatement(first)

        val constraints = mutableListOf<TableConstraintDef>()
        while (!atEof() && peek().type != SqlTokenType.SEMICOLON) {
            if (accept("ADD") != null &&
                peek().isKeyword("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK")
            ) {
                parseTableConstraint()?.let { constraints += it }
            } else {
                skipBalancedUntil(emptySet(), setOf(SqlTokenType.COMMA))
            }
            if (peek().type == SqlTokenType.COMMA) advance()
        }
        val range = skipToStatementEnd(first)
        return if (constraints.isNotEmpty()) AlterTableAddConstraint(tableName, constraints, range)
        else UnknownStatement(range, first.text, SrcRange.of(first))
    }

    // -----------------------------------------------------------------------------------------
    // DELETE (needed for rollback statement checks)
    // -----------------------------------------------------------------------------------------

    private fun parseDelete(): SqlStatement {
        val first = advance() // DELETE
        if (accept("FROM") == null) return unknownStatement(first)
        val name = parseQualifiedName() ?: return unknownStatement(first)
        val hasWhere = scanForWhere()
        val range = skipToStatementEnd(first)
        return DeleteStatement(name.first, name.second, hasWhere, range)
    }

    private fun parseUpdate(): SqlStatement {
        val first = advance() // UPDATE
        val name = parseQualifiedName() ?: return unknownStatement(first)
        if (isNameToken(peek()) && !peek().isKeyword("SET")) advance() // optional alias
        if (!peek().isKeyword("SET")) return unknownStatement(first)
        val hasWhere = scanForWhere()
        val range = skipToStatementEnd(first)
        return UpdateStatement(name.first, name.second, hasWhere, range)
    }

    private fun parseTruncate(): SqlStatement {
        val first = advance() // TRUNCATE
        accept("TABLE") // required in Oracle, optional in PostgreSQL
        val name = parseQualifiedName() ?: return unknownStatement(first)
        val range = skipToStatementEnd(first)
        return TruncateStatement(name.first, name.second, range)
    }

    private fun parseDrop(): SqlStatement {
        val first = advance() // DROP
        val kind = peek().takeIf { it.isKeyword("TABLE", "SEQUENCE", "INDEX", "VIEW") }
            ?: return unknownStatement(first)
        advance()
        if (peek().isKeyword("IF") && peek(1).isKeyword("EXISTS")) { advance(); advance() }
        val name = parseQualifiedName() ?: return unknownStatement(first)
        val range = skipToStatementEnd(first) // CASCADE / PURGE tails
        return DropStatement(kind.upper, name.first, name.second, range)
    }

    /** Peeks ahead (without consuming) for a top-level WHERE before the statement ends. */
    private fun scanForWhere(): Boolean {
        var i = pos
        var depth = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.type == SqlTokenType.SEMICOLON && depth == 0 -> return false
                t.type == SqlTokenType.LPAREN -> depth++
                t.type == SqlTokenType.RPAREN -> if (depth > 0) depth-- else return false
                depth == 0 && t.isKeyword("WHERE") -> return true
                depth == 0 && t.type == SqlTokenType.EOF -> return false
            }
            i++
        }
        return false
    }

    // -----------------------------------------------------------------------------------------
    // INSERT
    // -----------------------------------------------------------------------------------------

    private fun parseInsert(): SqlStatement {
        val first = advance() // INSERT
        if (accept("INTO") == null) return unknownStatement(first)
        val (tableName, tableRange) = parseQualifiedName()
            ?: return unknownStatement(first)

        var columns: List<ColumnNameRef>? = null
        if (peek().type == SqlTokenType.LPAREN) {
            advance()
            val cols = mutableListOf<ColumnNameRef>()
            while (!atEof() && peek().type != SqlTokenType.RPAREN) {
                val t = advance()
                if (isNameToken(t)) cols += ColumnNameRef(nameOf(t), SrcRange.of(t))
            }
            if (peek().type == SqlTokenType.RPAREN) advance()
            columns = cols
        }

        var fromSelect = false
        val rows = mutableListOf<ValuesRow>()
        when {
            peek().isKeyword("VALUES") -> {
                advance()
                while (peek().type == SqlTokenType.LPAREN) {
                    rows += parseValuesRow()
                    if (peek().type == SqlTokenType.COMMA) advance() else break
                }
            }
            peek().isKeyword("SELECT", "WITH") -> fromSelect = true
            peek().isKeyword("DEFAULT") -> { /* DEFAULT VALUES */ }
        }
        val range = skipToStatementEnd(first) // ON CONFLICT / RETURNING tails
        return InsertStatement(tableName, tableRange, columns, rows, fromSelect, range)
    }

    private fun parseValuesRow(): ValuesRow {
        val open = advance() // (
        val values = mutableListOf<SqlValueExpr>()
        while (!atEof() && peek().type != SqlTokenType.RPAREN) {
            val before = pos
            values += parseValueExpr(setOf(SqlTokenType.COMMA, SqlTokenType.RPAREN), emptySet())
            if (peek().type == SqlTokenType.COMMA) advance()
            // No progress: a token the expression parser refuses (stray ';' in an unclosed
            // row) — bail out instead of looping forever on the malformed input.
            if (pos == before) break
        }
        val close = if (peek().type == SqlTokenType.RPAREN) advance() else tokens[maxOf(pos - 1, 0)]
        return ValuesRow(values, SrcRange(open.start, close.end))
    }

    /**
     * Parses one value expression, stopping at [stopTypes]/[stopKeywords] at paren depth 0.
     * Single literals and (qualified) column references are modeled precisely; everything
     * else becomes [SqlValueExpr.Opaque] and is skipped by validators.
     */
    private fun parseValueExpr(
        stopTypes: Set<SqlTokenType>,
        stopKeywords: Set<String>,
        stopAtEquals: Boolean = false,
    ): SqlValueExpr {
        val startToken = peek()
        val collected = mutableListOf<SqlToken>()
        var depth = 0
        while (!atEof()) {
            val t = peek()
            if (depth == 0 && (t.type in stopTypes || (t.type == SqlTokenType.IDENT && t.upper in stopKeywords))) break
            if (depth == 0 && stopAtEquals && t.type == SqlTokenType.OPERATOR && t.text == "=") break
            if (t.type == SqlTokenType.SEMICOLON && depth == 0) break
            if (t.type == SqlTokenType.LPAREN) depth++
            if (t.type == SqlTokenType.RPAREN) {
                if (depth == 0) break
                depth--
            }
            collected += advance()
        }
        if (collected.isEmpty()) {
            return SqlValueExpr.Opaque("", SrcRange(startToken.start, startToken.start))
        }
        val range = SrcRange(collected.first().start, collected.last().end)
        return classifyExpr(collected, range)
    }

    private fun classifyExpr(ts: List<SqlToken>, range: SrcRange): SqlValueExpr {
        val first = ts[0]
        // 'literal' with optional ::cast
        if (first.type == SqlTokenType.STRING) {
            if (ts.size == 1) return SqlValueExpr.StringLit(first.value, null, range)
            if (ts.size >= 3 && ts[1].type == SqlTokenType.CAST && ts[2].type == SqlTokenType.IDENT) {
                val words = ts.drop(2).filter { it.type == SqlTokenType.IDENT }.map { it.text }
                val args = extractParenInts(ts.drop(2))
                val raw = ts.drop(2).joinToString(" ") { it.text }
                return SqlValueExpr.StringLit(first.value, SqlDataType.resolve(words, args, raw), range)
            }
            return SqlValueExpr.Opaque(text.substring(range.start, range.end), range)
        }
        // [+-] number with optional ::cast
        val negative = first.type == SqlTokenType.OPERATOR && first.text == "-"
        val positiveSign = first.type == SqlTokenType.OPERATOR && first.text == "+"
        val numIdx = if (negative || positiveSign) 1 else 0
        if (ts.size > numIdx && ts[numIdx].type == SqlTokenType.NUMBER) {
            val rest = ts.drop(numIdx + 1)
            if (rest.isEmpty() || (rest.firstOrNull()?.type == SqlTokenType.CAST)) {
                return SqlValueExpr.NumberLit(ts[numIdx].text, negative, range)
            }
            return SqlValueExpr.Opaque(text.substring(range.start, range.end), range)
        }
        if (first.type == SqlTokenType.IDENT && ts.size == 1) {
            when {
                first.isKeyword("TRUE") -> return SqlValueExpr.BoolLit(true, range)
                first.isKeyword("FALSE") -> return SqlValueExpr.BoolLit(false, range)
                first.isKeyword("NULL") -> return SqlValueExpr.NullLit(range)
                first.isKeyword("DEFAULT") -> return SqlValueExpr.DefaultKw(range)
                first.upper in VALUE_KEYWORDS -> return SqlValueExpr.Opaque(first.text, range)
                else -> return SqlValueExpr.ColumnRef(null, first.text, range)
            }
        }
        // qualified reference: ident.ident
        if (ts.size == 3 && isNameToken(ts[0]) && ts[1].type == SqlTokenType.DOT && isNameToken(ts[2])) {
            if (nameOf(ts[2]).uppercase() in SEQUENCE_PSEUDO_COLUMNS) {
                return SqlValueExpr.Opaque(text.substring(range.start, range.end), range)
            }
            return SqlValueExpr.ColumnRef(nameOf(ts[0]), nameOf(ts[2]), range)
        }
        return SqlValueExpr.Opaque(text.substring(range.start, range.end), range)
    }

    private fun extractParenInts(ts: List<SqlToken>): List<Int> =
        ts.filter { it.type == SqlTokenType.NUMBER }.mapNotNull { it.text.toIntOrNull() }

    // -----------------------------------------------------------------------------------------
    // MERGE
    // -----------------------------------------------------------------------------------------

    private fun parseMerge(): SqlStatement {
        val first = advance() // MERGE
        if (accept("INTO") == null) return unknownStatement(first)
        val target = parseQualifiedName() ?: return unknownStatement(first)
        val targetAlias = parseOptionalAlias(setOf("USING"))

        if (accept("USING") == null) return unknownStatement(first)
        var sourceTable: String? = null
        var sourceTableRange: SrcRange? = null
        if (peek().type == SqlTokenType.LPAREN) {
            advance()
            skipBalancedUntil(emptySet(), setOf(SqlTokenType.RPAREN))
            if (peek().type == SqlTokenType.RPAREN) advance()
        } else {
            val src = parseQualifiedName() ?: return unknownStatement(first)
            sourceTable = src.first
            sourceTableRange = src.second
        }
        val sourceAlias = parseOptionalAlias(setOf("ON"))

        val onConditions = mutableListOf<OnEquality>()
        if (accept("ON") != null) {
            // Oracle requires the ON condition in parentheses: ON (t.code = s.code)
            val parenthesized = peek().type == SqlTokenType.LPAREN
            if (parenthesized) advance()
            while (!atEof()) {
                val left = parseValueExpr(
                    setOf(SqlTokenType.SEMICOLON), setOf("WHEN", "AND", "OR"), stopAtEquals = true,
                )
                if (peek().type == SqlTokenType.OPERATOR && peek().text == "=") {
                    advance()
                    val right = parseValueExpr(
                        setOf(SqlTokenType.SEMICOLON), setOf("WHEN", "AND", "OR"), stopAtEquals = true,
                    )
                    onConditions += OnEquality(left, right)
                }
                if (accept("AND", "OR") != null) continue
                break
            }
            if (parenthesized && peek().type == SqlTokenType.RPAREN) advance()
        }

        val updateAssignments = mutableListOf<MergeAssignment>()
        var insertClause: MergeInsertClause? = null
        var hasDelete = false

        while (peek().isKeyword("WHEN")) {
            advance()
            accept("NOT")
            accept("MATCHED")
            if (peek().isKeyword("AND")) {
                skipBalancedUntil(setOf("THEN"), setOf(SqlTokenType.SEMICOLON))
            }
            if (accept("THEN") == null) break
            when {
                peek().isKeyword("UPDATE") -> {
                    advance()
                    accept("SET")
                    while (!atEof()) {
                        val colToken = if (isNameToken(peek())) peek() else break
                        var colName = nameOf(colToken)
                        var colRange = SrcRange.of(colToken)
                        advance()
                        if (peek().type == SqlTokenType.DOT && isNameToken(peek(1))) {
                            advance()
                            val c = advance()
                            colName = nameOf(c)
                            colRange = SrcRange.of(c)
                        }
                        if (!(peek().type == SqlTokenType.OPERATOR && peek().text == "=")) break
                        advance()
                        val value = parseValueExpr(setOf(SqlTokenType.COMMA, SqlTokenType.SEMICOLON), setOf("WHEN"))
                        updateAssignments += MergeAssignment(ColumnNameRef(colName, colRange), value)
                        if (peek().type == SqlTokenType.COMMA) advance() else break
                    }
                }
                peek().isKeyword("DELETE") -> { advance(); hasDelete = true }
                peek().isKeyword("INSERT") -> {
                    val insFirst = advance()
                    var cols: List<ColumnNameRef>? = null
                    if (peek().type == SqlTokenType.LPAREN) {
                        advance()
                        val list = mutableListOf<ColumnNameRef>()
                        while (!atEof() && peek().type != SqlTokenType.RPAREN) {
                            val t = advance()
                            if (isNameToken(t)) list += ColumnNameRef(nameOf(t), SrcRange.of(t))
                        }
                        if (peek().type == SqlTokenType.RPAREN) advance()
                        cols = list
                    }
                    val values = mutableListOf<SqlValueExpr>()
                    if (accept("VALUES") != null && peek().type == SqlTokenType.LPAREN) {
                        advance()
                        while (!atEof() && peek().type != SqlTokenType.RPAREN) {
                            val before = pos
                            values += parseValueExpr(setOf(SqlTokenType.COMMA, SqlTokenType.RPAREN), setOf("WHEN"))
                            if (peek().type == SqlTokenType.COMMA) advance()
                            if (pos == before) break // malformed list: never spin in place
                        }
                        if (peek().type == SqlTokenType.RPAREN) advance()
                    } else if (peek().isKeyword("DEFAULT")) {
                        advance(); accept("VALUES")
                    }
                    insertClause = MergeInsertClause(cols, values, SrcRange(insFirst.start, tokens[maxOf(pos - 1, 0)].end))
                }
                peek().isKeyword("DO") -> { advance(); accept("NOTHING") }
                else -> break
            }
        }
        val range = skipToStatementEnd(first)
        return MergeStatement(
            targetTable = target.first,
            targetTableRange = target.second,
            targetAlias = targetAlias,
            sourceTable = sourceTable,
            sourceTableRange = sourceTableRange,
            sourceAlias = sourceAlias,
            onConditions = onConditions,
            updateAssignments = updateAssignments,
            insertClause = insertClause,
            hasDelete = hasDelete,
            range = range,
        )
    }

    private fun parseOptionalAlias(stopKeywords: Set<String>): String? {
        if (accept("AS") != null && isNameToken(peek())) return nameOf(advance())
        if (isNameToken(peek()) && peek().upper !in stopKeywords) return nameOf(advance())
        return null
    }
}
