package com.company.liquibasevalidator.liquibase

import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.sql.TextDistance

/**
 * Parser for Liquibase *formatted SQL* metadata: the `--liquibase formatted sql` header,
 * `--changeset`, `--rollback`, `--preconditions`, `--precondition-sql-check` and
 * `--comment` lines. Purely line/offset based; completely independent from SQL parsing.
 *
 * Also validates directive and attribute SPELLING: a mistyped `--changeset` header makes
 * Liquibase attach the following SQL to the previous changeset, silently corrupting
 * DATABASECHANGELOG execution tracking — so typos are reported with quick fixes.
 */
object LiquibaseParser {

    private val DIRECTIVES = listOf(
        "changeset", "rollback", "comment", "preconditions", "precondition-sql-check",
        "liquibase", "validCheckSum", "ignoreLines", "property", "approved-destructive",
    )

    /** Directives whose loss changes execution semantics: typo = ERROR, not warning. */
    private val CRITICAL_DIRECTIVES = setOf("changeset", "preconditions", "precondition-sql-check", "liquibase")

    private val CHANGESET_ATTRIBUTES = mapOf(
        "context" to "context", "contextfilter" to "contextFilter", "labels" to "labels",
        "label" to "labels", "logicalfilepath" to "logicalFilePath", "runalways" to "runAlways",
        "runonchange" to "runOnChange", "runintransaction" to "runInTransaction",
        "failonerror" to "failOnError", "dbms" to "dbms", "splitstatements" to "splitStatements",
        "stripcomments" to "stripComments", "enddelimiter" to "endDelimiter",
        "rollbackenddelimiter" to "rollbackEndDelimiter",
        "rollbacksplitstatements" to "rollbackSplitStatements", "runwith" to "runWith",
        "runorder" to "runOrder", "ignore" to "ignore", "created" to "created",
    )

    private val PRECONDITION_ATTRIBUTES = mapOf(
        "onfail" to "onFail", "onerror" to "onError", "onupdatesql" to "onUpdateSQL",
        "onsqloutput" to "onSqlOutput", "onfailmessage" to "onFailMessage",
        "onerrormessage" to "onErrorMessage",
    )

    private data class Line(val text: String, val start: Int) {
        val end: Int get() = start + text.length
    }

    fun parse(text: String): LiquibaseFile {
        val lines = splitLines(text)
        var formatted = false
        var headerRange: SrcRange? = null
        val problems = mutableListOf<LiquibaseProblem>()
        val changesets = mutableListOf<Changeset>()
        var sqlBeforeChangesetReported = false

        // builder state for the changeset currently being parsed
        var current: ChangesetBuilder? = null

        fun closeCurrent(bodyEnd: Int) {
            current?.let { changesets += it.build(bodyEnd) }
            current = null
        }

        for (line in lines) {
            val trimmed = line.text.trim()
            if (trimmed.startsWith("--")) {
                val content = trimmed.removePrefix("--").trim()
                val lower = content.lowercase()
                when {
                    lower.startsWith("liquibase formatted sql") -> {
                        formatted = true
                        if (headerRange == null) headerRange = SrcRange(line.start, line.end)
                    }
                    lower.startsWith("liquibase") -> {
                        // e.g. "--liquibase formated sql": without the exact header the whole
                        // file is executed as plain SQL and every changeset is ignored
                        val contentStart = line.start + line.text.indexOf(content.take(9))
                        problems += LiquibaseProblem(
                            message = "Liquibase: malformed header '--$content' — the exact header " +
                                "'--liquibase formatted sql' is required, otherwise every changeset " +
                                "in this file is ignored",
                            range = SrcRange(line.start, line.end),
                            isError = true,
                            fixRange = SrcRange(contentStart, line.end),
                            fixReplacement = "liquibase formatted sql",
                            fixName = "Change to '--liquibase formatted sql'",
                        )
                    }
                    lower.startsWith("changeset") -> {
                        closeCurrent(line.start)
                        current = parseChangesetHeader(content, line, problems, changesets)
                    }
                    lower.startsWith("rollback") -> {
                        val sql = content.substring("rollback".length).trim()
                        current?.addRollback(sql, SrcRange(line.start, line.end))
                    }
                    lower.startsWith("precondition-sql-check") -> {
                        val rest = content.substring("precondition-sql-check".length).trim()
                        current?.addSqlCheck(rest, SrcRange(line.start, line.end))
                    }
                    lower.startsWith("preconditions") -> {
                        val rest = content.substring("preconditions".length).trim()
                        validateAttributes(rest, line, PRECONDITION_ATTRIBUTES, problems)
                        current?.setPreconditions(parseAttributes(rest), SrcRange(line.start, line.end))
                    }
                    lower.startsWith("comment") -> {
                        val rest = content.substring("comment".length).removePrefix(":").trim()
                        current?.setComment(rest)
                    }
                    lower.startsWith("approved-destructive") -> {
                        current?.setApprovedDestructive(content.substring("approved-destructive".length).trim())
                    }
                    lower.startsWith("validchecksum") || lower.startsWith("ignorelines") ||
                        lower.startsWith("property") -> { /* recognized directives, no model impact */ }
                    else -> checkDirectiveTypo(content, line, problems)
                }
            } else if (trimmed.isNotEmpty()) {
                current?.markBodyLine()
                if (formatted && current == null && !sqlBeforeChangesetReported) {
                    problems += LiquibaseProblem(
                        "Liquibase: SQL statement appears before the first '--changeset' header",
                        SrcRange(line.start, line.end),
                        isError = false,
                    )
                    sqlBeforeChangesetReported = true
                }
            }
        }
        closeCurrent(text.length)
        if (!formatted && changesets.isNotEmpty()) {
            problems += LiquibaseProblem(
                message = "Liquibase: this file contains changesets but no '--liquibase formatted sql' " +
                    "header — Liquibase executes it as plain SQL and ignores every changeset, " +
                    "so nothing is tracked in DATABASECHANGELOG",
                range = changesets.first().headerRange,
                isError = true,
                fixRange = SrcRange(0, 0),
                fixReplacement = "--liquibase formatted sql\n",
                fixName = "Insert '--liquibase formatted sql' header",
            )
        }
        return LiquibaseFile(formatted, headerRange, changesets, problems)
    }

    /** Attributes whose only legal values are true/false. */
    private val BOOLEAN_ATTRIBUTES = setOf(
        "runalways", "runonchange", "runintransaction", "failonerror",
        "splitstatements", "stripcomments", "rollbacksplitstatements",
    )

    // ---------------------------------------------------------------------------------------

    private fun parseChangesetHeader(
        content: String,
        line: Line,
        problems: MutableList<LiquibaseProblem>,
        existing: List<Changeset>,
    ): ChangesetBuilder? {
        val rest = content.substring("changeset".length).trim()
        val tokens = tokenizeAttributes(rest)
        val idToken = tokens.firstOrNull()
        val headerRange = SrcRange(line.start, line.end)
        if (idToken == null || !idToken.contains(':')) {
            problems += LiquibaseProblem(
                "Liquibase: invalid changeset header, expected '--changeset author:id'",
                headerRange, isError = true,
            )
            return null
        }
        val author = idToken.substringBefore(':').trim()
        val id = idToken.substringAfter(':').trim()
        if (author.isEmpty() || id.isEmpty()) {
            problems += LiquibaseProblem(
                "Liquibase: invalid changeset header, author and id must both be present",
                headerRange, isError = true,
            )
            return null
        }
        if (existing.any { it.author == author && it.id == id }) {
            problems += LiquibaseProblem(
                "Liquibase: duplicate changeset '$author:$id'",
                headerRange, isError = true,
            )
        }
        validateAttributes(tokens.drop(1).joinToString(" "), line, CHANGESET_ATTRIBUTES, problems)
        val attributes = parseAttributes(tokens.drop(1).joinToString(" "))
        return ChangesetBuilder(author, id, attributes, headerRange)
    }

    /**
     * Flags mistyped `key:value` attribute names. A typo is not cosmetic: e.g. a mistyped
     * `failOnError:false` silently reverts to the default `failOnError:true` and a
     * deliberately-failing changeset halts the whole deployment.
     */
    private fun validateAttributes(
        text: String,
        line: Line,
        known: Map<String, String>,
        problems: MutableList<LiquibaseProblem>,
    ) {
        for (token in tokenizeAttributes(text)) {
            val idx = token.indexOf(':')
            if (idx <= 0) continue
            val key = token.substring(0, idx).trim()
            if (key.lowercase() in known) {
                validateAttributeValue(key, token.substring(idx + 1), token, line, known, problems)
                continue
            }
            // long attribute names are distinctive: allow a slightly larger typo distance
            val suggestion = TextDistance.nearest(key, known.values, if (key.length >= 10) 3 else 2)
            val keyStart = line.text.indexOf(token).takeIf { it >= 0 }?.plus(line.start)
            val fixRange = keyStart?.let { SrcRange(it, it + key.length) }
            problems += LiquibaseProblem(
                message = "Liquibase: unknown changeset attribute '$key'" +
                    (suggestion?.let { " — did you mean '$it'?" } ?: "") +
                    (if (suggestion != null) " (the mistyped attribute is ignored and its default applies)" else ""),
                range = fixRange ?: SrcRange(line.start, line.end),
                isError = suggestion != null,
                fixRange = if (suggestion != null) fixRange else null,
                fixReplacement = suggestion,
                fixName = suggestion?.let { "Change '$key' to '$it'" },
            )
        }
    }

    /** `runOnChange:ture`, `onFail:HALTT` etc.: a bad value silently reverts to the default. */
    private fun validateAttributeValue(
        key: String,
        value: String,
        token: String,
        line: Line,
        known: Map<String, String>,
        problems: MutableList<LiquibaseProblem>,
    ) {
        val keyLower = key.lowercase()
        val allowed: List<String> = when {
            keyLower in BOOLEAN_ATTRIBUTES -> listOf("true", "false")
            keyLower == "onfail" || keyLower == "onerror" -> listOf("HALT", "CONTINUE", "MARK_RAN", "WARN")
            else -> return
        }
        val cleaned = value.trim().removeSurrounding("\"")
        if (allowed.any { it.equals(cleaned, ignoreCase = true) }) return
        val canonical = known[keyLower] ?: key
        val suggestion = TextDistance.nearest(cleaned, allowed, 2)
        val tokenStart = line.text.indexOf(token).takeIf { it >= 0 }?.plus(line.start)
        val valueStart = tokenStart?.plus(token.indexOf(':') + 1)
        val fixRange = valueStart?.let { SrcRange(it, it + value.length) }
        problems += LiquibaseProblem(
            message = "Liquibase: invalid value '$cleaned' for '$canonical' — expected " +
                allowed.joinToString(" or ") +
                (suggestion?.let { "; did you mean '$it'?" } ?: "") +
                " (an unrecognized value falls back to the default)",
            range = fixRange ?: SrcRange(line.start, line.end),
            isError = true,
            fixRange = if (suggestion != null) fixRange else null,
            fixReplacement = suggestion,
            fixName = suggestion?.let { "Change '$cleaned' to '$it'" },
        )
    }

    /**
     * Flags comment lines whose first word is a near-miss of a Liquibase directive.
     * Conservative: only attribute-shaped lines (containing ':') or near-misses of
     * execution-critical directives are reported — prose comments never are.
     */
    private fun checkDirectiveTypo(content: String, line: Line, problems: MutableList<LiquibaseProblem>) {
        val firstWord = content.takeWhile { !it.isWhitespace() && it != ':' }
        if (firstWord.length < 6) return
        val suggestion = TextDistance.nearest(firstWord, DIRECTIVES, 2) ?: return
        if (suggestion.equals(firstWord, ignoreCase = true)) return
        val rest = content.substring(firstWord.length)
        val critical = suggestion in CRITICAL_DIRECTIVES
        if (!critical && !rest.contains(':')) return // plain prose, not a directive attempt

        val wordStart = line.text.indexOf(firstWord).takeIf { it >= 0 }?.plus(line.start)
        val fixRange = wordStart?.let { SrcRange(it, it + firstWord.length) }
        val consequence = if (suggestion == "changeset") {
            " — the SQL below would run under the PREVIOUS changeset and corrupt " +
                "DATABASECHANGELOG execution tracking"
        } else {
            ""
        }
        problems += LiquibaseProblem(
            message = "Liquibase: unknown directive '--$firstWord' — did you mean '--$suggestion'?$consequence",
            range = fixRange ?: SrcRange(line.start, line.end),
            isError = critical,
            fixRange = fixRange,
            fixReplacement = suggestion,
            fixName = "Change '$firstWord' to '$suggestion'",
        )
    }

    /** Splits `key:value key:"quoted value"` pairs; keys lowercased. */
    private fun parseAttributes(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (token in tokenizeAttributes(text)) {
            val idx = token.indexOf(':')
            if (idx > 0) {
                val key = token.substring(0, idx).trim().lowercase()
                val value = token.substring(idx + 1).trim().removeSurrounding("\"")
                map[key] = value
            }
        }
        return map
    }

    /** Whitespace tokenizer that keeps `key:"value with spaces"` together. */
    private fun tokenizeAttributes(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in text) {
            when {
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                c.isWhitespace() && !inQuotes -> {
                    if (sb.isNotEmpty()) { tokens += sb.toString(); sb.clear() }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) tokens += sb.toString()
        return tokens
    }

    private fun splitLines(text: String): List<Line> {
        val lines = mutableListOf<Line>()
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) {
                lines += Line(text.substring(start), start)
                break
            }
            lines += Line(text.substring(start, nl).removeSuffix("\r"), start)
            start = nl + 1
        }
        return lines
    }

    // ---------------------------------------------------------------------------------------

    private class ChangesetBuilder(
        val author: String,
        val id: String,
        val attributes: Map<String, String>,
        val headerRange: SrcRange,
    ) {
        private var comment: String? = null
        private var approvedDestructive: String? = null
        private var preconditionAttrs: Map<String, String>? = null
        private var preconditionRange: SrcRange? = null
        private val sqlChecks = mutableListOf<SqlCheck>()
        private val rollbacks = mutableListOf<RollbackSql>()
        private var lastRollbackWasPrevLine = false

        fun setComment(value: String) { comment = value }

        fun setApprovedDestructive(ticket: String) { approvedDestructive = ticket.ifBlank { "(no ticket)" } }

        fun setPreconditions(attrs: Map<String, String>, range: SrcRange) {
            preconditionAttrs = attrs
            preconditionRange = range
        }

        fun addSqlCheck(rest: String, range: SrcRange) {
            // format: expectedResult:N SELECT ...
            var expected: String? = null
            var sql = rest
            if (rest.lowercase().startsWith("expectedresult:")) {
                val afterKey = rest.substring("expectedresult:".length)
                expected = afterKey.takeWhile { !it.isWhitespace() }
                sql = afterKey.dropWhile { !it.isWhitespace() }.trim()
            }
            sqlChecks += SqlCheck(expected, sql, range)
        }

        fun addRollback(sql: String, range: SrcRange) {
            // consecutive --rollback lines form one rollback SQL block
            val last = rollbacks.lastOrNull()
            if (last != null && lastRollbackWasPrevLine) {
                rollbacks[rollbacks.size - 1] = RollbackSql(last.sql + "\n" + sql, SrcRange(last.range.start, range.end))
            } else {
                rollbacks += RollbackSql(sql, range)
            }
            lastRollbackWasPrevLine = true
        }

        fun markBodyLine() {
            lastRollbackWasPrevLine = false
        }

        fun build(bodyEnd: Int): Changeset {
            val contexts = (attributes["context"] ?: attributes["contextfilter"] ?: "")
                .split(',', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.lowercase() !in setOf("and", "or", "not") }
            val labels = (attributes["labels"] ?: attributes["label"] ?: "")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val preconditions = preconditionAttrs?.let {
                Preconditions(
                    onFail = it["onfail"],
                    onError = it["onerror"],
                    sqlChecks = sqlChecks.toList(),
                    range = preconditionRange ?: headerRange,
                )
            } ?: if (sqlChecks.isNotEmpty()) Preconditions(null, null, sqlChecks.toList(), preconditionRange ?: headerRange) else null
            return Changeset(
                author = author,
                id = id,
                attributes = attributes,
                contexts = contexts,
                labels = labels,
                comment = comment,
                preconditions = preconditions,
                rollbacks = rollbacks.toList(),
                approvedDestructive = approvedDestructive,
                headerRange = headerRange,
                bodyRange = SrcRange(headerRange.end, maxOf(bodyEnd, headerRange.end)),
            )
        }
    }
}
