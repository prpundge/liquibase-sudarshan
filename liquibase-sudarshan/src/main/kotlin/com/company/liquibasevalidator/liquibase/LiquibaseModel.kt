package com.company.liquibasevalidator.liquibase

import com.company.liquibasevalidator.sql.SrcRange

/** Metadata of one `--changeset author:id ...` header and its body. */
data class Changeset(
    val author: String,
    val id: String,
    val attributes: Map<String, String>,
    val contexts: List<String>,
    val labels: List<String>,
    val comment: String?,
    val preconditions: Preconditions?,
    val rollbacks: List<RollbackSql>,
    /** Range of the `--changeset` header line. */
    val headerRange: SrcRange,
    /** Range of the SQL body: from end of header to start of the next changeset (or EOF). */
    val bodyRange: SrcRange,
) {
    val runOnChange: Boolean get() = attributes["runonchange"].equals("true", ignoreCase = true)
    val key: String get() = "$author:$id"
}

data class Preconditions(
    val onFail: String?,
    val onError: String?,
    val sqlChecks: List<SqlCheck>,
    val range: SrcRange,
)

data class SqlCheck(val expectedResult: String?, val sql: String, val range: SrcRange)

data class RollbackSql(val sql: String, val range: SrcRange)

data class LiquibaseProblem(
    val message: String,
    val range: SrcRange,
    val isError: Boolean,
    /** Optional quick fix: replace [fixRange] with [fixReplacement]. */
    val fixRange: SrcRange? = null,
    val fixReplacement: String? = null,
    val fixName: String? = null,
)

/** Parse result for one file. */
data class LiquibaseFile(
    val formatted: Boolean,
    /** Range of the `--liquibase formatted sql` header, when present. */
    val headerRange: SrcRange?,
    val changesets: List<Changeset>,
    val problems: List<LiquibaseProblem>,
) {
    fun changesetAt(offset: Int): Changeset? =
        changesets.firstOrNull { offset >= it.headerRange.start && offset < it.bodyRange.end }
}
