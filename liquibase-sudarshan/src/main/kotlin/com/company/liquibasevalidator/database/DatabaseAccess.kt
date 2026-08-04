package com.company.liquibasevalidator.database

import com.company.liquibasevalidator.schema.TableSchema

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    /** PostgreSQL schema (default `public`) or Oracle owner (default = user). */
    val schemaName: String = "",
    /** Optional path to a user-provided JDBC driver JAR (air-gapped environments). */
    val driverJarPath: String = "",
    val queryTimeoutSeconds: Int = 10,
)

/**
 * One read-only database session. Implementations must never execute anything but SELECTs
 * and metadata queries — the plugin's hard safety rule is that validation cannot write.
 */
interface DatabaseSession : AutoCloseable {
    /** Live schema of the configured database schema/owner, keyed by lowercase table name. */
    fun fetchTables(): Map<String, TableSchema>

    /** Executed changesets from DATABASECHANGELOG as lowercase `author:id`, or null when the
     *  table does not exist (fresh database, Liquibase has never run). */
    fun executedChangesets(): Set<String>?

    /** Executes a single SELECT and returns the first column of the first row (null if no
     *  rows). Throws [IllegalArgumentException] for anything that is not a lone SELECT. */
    fun scalarSelect(sql: String): String?

    /** True/false when a row where [column] = [value] (compared as trimmed text) provably
     *  exists / does not exist; null when the identifiers cannot be probed safely. */
    fun rowExists(table: String, column: String, value: String): Boolean?

    /** Row count of [table], or null when it cannot be counted safely. */
    fun rowCount(table: String): Long? = null

    /** Sequences of the configured schema/owner (for the datasource browser). */
    fun sequences(): List<SequenceInfo> = emptyList()

    /** View names of the configured schema/owner. */
    fun views(): List<String> = emptyList()

    /** Indexes per table (lowercase table name), including non-unique ones. */
    fun indexes(): Map<String, List<IndexInfo>> = emptyMap()

    /** First [limit] rows of [table] — read-only data preview; null when not fetchable. */
    fun dataPreview(table: String, limit: Int): TableData? = null
}

data class SequenceInfo(val name: String, val incrementBy: Long?, val lastNumber: Long?)

data class IndexInfo(val name: String, val unique: Boolean, val columns: String)

/** A read-only slice of table data for the browser's Data preview. */
data class TableData(val columnNames: List<String>, val rows: List<List<String?>>)

/** Shared guard: the only statement shape the plugin is ever allowed to evaluate. */
object SqlGuards {
    /** Returns the normalized statement or throws [IllegalArgumentException]. */
    fun requireSingleSelect(sql: String): String {
        val normalized = sql.trim().trimEnd(';').trim()
        require(normalized.lowercase().startsWith("select") && !normalized.contains(';')) {
            "Only a single SELECT statement can be evaluated in a dry run"
        }
        return normalized
    }
}

/** Opens read-only sessions; the only entry point to a live database. */
interface DatabaseConnector {
    fun <T> withSession(block: (DatabaseSession) -> T): T
}
