package com.company.liquibasevalidator.database

import com.company.liquibasevalidator.schema.ColumnSchema
import com.company.liquibasevalidator.schema.ConstraintKind
import com.company.liquibasevalidator.schema.ConstraintSchema
import com.company.liquibasevalidator.schema.SchemaOrigin
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.TypeKind
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties

/**
 * JDBC implementation for PostgreSQL and Oracle. Drivers are instantiated directly
 * (DriverManager is unreliable across plugin classloaders) and every connection is opened
 * read-only. Only SELECT/metadata statements are ever issued.
 */
class JdbcConnector(private val config: DatabaseConfig) : DatabaseConnector {

    private val oracle = config.jdbcUrl.startsWith("jdbc:oracle", ignoreCase = true)

    override fun <T> withSession(block: (DatabaseSession) -> T): T {
        val props = Properties().apply {
            setProperty("user", config.user)
            setProperty("password", config.password)
            if (!oracle) {
                setProperty("readOnly", "true")
                // enforce READ ONLY on every transaction the driver opens, not just the first
                setProperty("readOnlyMode", "always")
                setProperty("connectTimeout", "10")
                setProperty("socketTimeout", "30")
            } else {
                setProperty("oracle.net.CONNECT_TIMEOUT", "10000")
                setProperty("oracle.jdbc.ReadTimeout", "30000")
            }
        }
        // drivers are resolved on demand (classpath → custom JAR → verified download cache);
        // they are intentionally NOT bundled with the plugin to keep the download small
        val driver = JdbcDrivers.ensureDriver(config.jdbcUrl, config.driverJarPath)
        val connection = driver.connect(config.jdbcUrl, props)
            ?: throw IllegalArgumentException("Unsupported JDBC URL: ${config.jdbcUrl}")
        connection.use { c ->
            // Server-enforced read-only: with autocommit off the session runs one READ ONLY
            // transaction (PostgreSQL: BEGIN READ ONLY; Oracle: SET TRANSACTION READ ONLY),
            // so even a hostile SELECT cannot write. Rolled back on close — nothing persists.
            c.autoCommit = false
            c.isReadOnly = true
            try {
                return block(JdbcSession(c, config, oracle))
            } finally {
                runCatching { c.rollback() }
            }
        }
    }
}

private class JdbcSession(
    private val connection: Connection,
    private val config: DatabaseConfig,
    private val oracle: Boolean,
) : DatabaseSession {

    private val identifier = Regex("^[A-Za-z_][A-Za-z0-9_$#]*$")

    /** Validated schema/owner prefix; blank config → the connection's default namespace. */
    private val schemaPrefix: String? =
        config.schemaName.trim().takeIf { it.isNotBlank() && identifier.matches(it) }

    private fun qualify(table: String): String = schemaPrefix?.let { "$it.$table" } ?: table

    override fun close() { /* the connector owns the connection */ }

    override fun fetchTables(): Map<String, TableSchema> =
        if (oracle) OracleMetadata.fetchTables(connection, config)
        else PostgresMetadata.fetchTables(connection, config)

    override fun executedChangesets(): Set<String>? = try {
        val result = LinkedHashSet<String>()
        connection.prepareStatement("SELECT id, author FROM ${qualify("DATABASECHANGELOG")}").use { statement ->
            statement.queryTimeout = config.queryTimeoutSeconds
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    result += "${rs.getString("author")}:${rs.getString("id")}".lowercase()
                }
            }
        }
        result
    } catch (_: SQLException) {
        null // table not found in the configured schema: treat as never-deployed
    }

    override fun scalarSelect(sql: String): String? {
        val normalized = SqlGuards.requireSingleSelect(sql)
        connection.prepareStatement(normalized).use { statement ->
            statement.queryTimeout = config.queryTimeoutSeconds
            statement.executeQuery().use { rs ->
                return if (rs.next()) rs.getString(1) else null
            }
        }
    }

    override fun rowCount(table: String): Long? {
        if (!identifier.matches(table)) return null
        return try {
            connection.prepareStatement("SELECT COUNT(*) FROM ${qualify(table)}").use { statement ->
                statement.queryTimeout = config.queryTimeoutSeconds
                statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
        } catch (_: SQLException) {
            null
        }
    }

    override fun sequences(): List<SequenceInfo> = try {
        val result = mutableListOf<SequenceInfo>()
        if (oracle) {
            val owner = config.schemaName.ifBlank { config.user }.uppercase()
            connection.prepareStatement(
                "SELECT sequence_name, increment_by, last_number FROM all_sequences WHERE sequence_owner = ? ORDER BY sequence_name",
            ).use { statement ->
                statement.queryTimeout = config.queryTimeoutSeconds
                statement.setString(1, owner)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        result += SequenceInfo(
                            rs.getString(1).lowercase(), rs.getLong(2),
                            rs.getLong(3),
                        )
                    }
                }
            }
        } else {
            val schemaName = config.schemaName.ifBlank { "public" }
            connection.prepareStatement(
                "SELECT sequence_name, increment FROM information_schema.sequences WHERE sequence_schema = ? ORDER BY sequence_name",
            ).use { statement ->
                statement.queryTimeout = config.queryTimeoutSeconds
                statement.setString(1, schemaName)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        result += SequenceInfo(rs.getString(1).lowercase(), rs.getString(2).toLongOrNull(), null)
                    }
                }
            }
        }
        result
    } catch (_: SQLException) {
        emptyList()
    }

    override fun views(): List<String> = try {
        val result = mutableListOf<String>()
        val sql = if (oracle) {
            "SELECT view_name FROM all_views WHERE owner = ? ORDER BY view_name"
        } else {
            "SELECT table_name FROM information_schema.views WHERE table_schema = ? ORDER BY table_name"
        }
        val scope = if (oracle) config.schemaName.ifBlank { config.user }.uppercase() else config.schemaName.ifBlank { "public" }
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = config.queryTimeoutSeconds
            statement.setString(1, scope)
            statement.executeQuery().use { rs -> while (rs.next()) result += rs.getString(1).lowercase() }
        }
        result
    } catch (_: SQLException) {
        emptyList()
    }

    override fun indexes(): Map<String, List<IndexInfo>> = try {
        val result = LinkedHashMap<String, MutableList<IndexInfo>>()
        if (oracle) {
            val owner = config.schemaName.ifBlank { config.user }.uppercase()
            connection.prepareStatement(
                """
                SELECT i.table_name, i.index_name, i.uniqueness, ic.column_name
                FROM all_indexes i
                JOIN all_ind_columns ic
                  ON i.owner = ic.index_owner AND i.index_name = ic.index_name
                WHERE i.owner = ?
                ORDER BY i.table_name, i.index_name, ic.column_position
                """.trimIndent(),
            ).use { statement ->
                statement.queryTimeout = config.queryTimeoutSeconds
                statement.setString(1, owner)
                statement.executeQuery().use { rs ->
                    data class Row(val table: String, val index: String, val unique: Boolean, val column: String)
                    val rows = mutableListOf<Row>()
                    while (rs.next()) {
                        rows += Row(
                            rs.getString(1).lowercase(), rs.getString(2).lowercase(),
                            rs.getString(3).equals("UNIQUE", true), rs.getString(4).lowercase(),
                        )
                    }
                    for ((key, group) in rows.groupBy { it.table to it.index }) {
                        result.getOrPut(key.first) { mutableListOf() } +=
                            IndexInfo(key.second, group.first().unique, group.joinToString(", ") { it.column })
                    }
                }
            }
        } else {
            val schemaName = config.schemaName.ifBlank { "public" }
            connection.prepareStatement(
                "SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = ? ORDER BY tablename, indexname",
            ).use { statement ->
                statement.queryTimeout = config.queryTimeoutSeconds
                statement.setString(1, schemaName)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        val definition = rs.getString(3).orEmpty()
                        result.getOrPut(rs.getString(1).lowercase()) { mutableListOf() } += IndexInfo(
                            rs.getString(2).lowercase(),
                            definition.contains("UNIQUE", ignoreCase = true),
                            definition.substringAfter('(', "").substringBeforeLast(')', ""),
                        )
                    }
                }
            }
        }
        result
    } catch (_: SQLException) {
        emptyMap()
    }

    override fun dataPreview(table: String, limit: Int): TableData? {
        if (!identifier.matches(table)) return null
        val bounded = limit.coerceIn(1, 500)
        val sql = "SELECT * FROM ${qualify(table)} " +
            (if (oracle) "FETCH FIRST $bounded ROWS ONLY" else "LIMIT $bounded")
        return try {
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = config.queryTimeoutSeconds
                statement.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                    val rows = mutableListOf<List<String?>>()
                    while (rs.next() && rows.size < bounded) {
                        rows += (1..meta.columnCount).map { rs.getString(it) }
                    }
                    TableData(columns, rows)
                }
            }
        } catch (_: SQLException) {
            null
        }
    }

    override fun rowExists(table: String, column: String, value: String): Boolean? {
        // identifiers are strictly validated, then interpolated UNQUOTED so each database
        // applies its own case folding (Oracle upper, PostgreSQL lower)
        if (!identifier.matches(table) || !identifier.matches(column)) return null // cannot probe safely
        // TRIM both sides: Oracle CHAR(n) pads with blanks; comparison is by text value
        val textExpr = if (oracle) "TRIM(TO_CHAR($column))" else "TRIM(CAST($column AS TEXT))"
        val sql = "SELECT 1 FROM ${qualify(table)} WHERE $textExpr = ? " +
            (if (oracle) "AND ROWNUM = 1" else "LIMIT 1")
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = config.queryTimeoutSeconds
            statement.setString(1, value.trim())
            statement.executeQuery().use { rs -> return rs.next() }
        }
    }
}

// -------------------------------------------------------------------------------------------
// PostgreSQL metadata (information_schema)
// -------------------------------------------------------------------------------------------

private object PostgresMetadata {

    fun fetchTables(connection: Connection, config: DatabaseConfig): Map<String, TableSchema> {
        val schemaName = config.schemaName.ifBlank { "public" }
        val columnsByTable = LinkedHashMap<String, MutableList<ColumnSchema>>()
        connection.prepareStatement(
            """
            SELECT table_name, column_name, data_type, character_maximum_length,
                   numeric_precision, numeric_scale, is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = ?
            ORDER BY table_name, ordinal_position
            """.trimIndent(),
        ).use { statement ->
            statement.queryTimeout = config.queryTimeoutSeconds
            statement.setString(1, schemaName)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    val table = rs.getString("table_name").lowercase()
                    columnsByTable.getOrPut(table) { mutableListOf() } += ColumnSchema(
                        name = rs.getString("column_name"),
                        dataType = mapType(
                            rs.getString("data_type"),
                            (rs.getObject("character_maximum_length") as? Number)?.toInt(),
                            (rs.getObject("numeric_precision") as? Number)?.toInt(),
                            (rs.getObject("numeric_scale") as? Number)?.toInt(),
                        ),
                        nullable = rs.getString("is_nullable").equals("YES", ignoreCase = true),
                        primaryKey = false,
                        unique = false,
                        hasDefault = rs.getString("column_default") != null,
                    )
                }
            }
        }
        val constraints = fetchConstraints(connection, config, schemaName)
        return MetadataSupport.assemble(columnsByTable, constraints)
    }

    private fun fetchConstraints(
        connection: Connection,
        config: DatabaseConfig,
        schemaName: String,
    ): Map<String, List<ConstraintSchema>> {
        data class Row(val table: String, val name: String, val type: String, val column: String, val refTable: String?, val refColumn: String?)
        val rows = mutableListOf<Row>()
        connection.prepareStatement(
            """
            SELECT tc.table_name, tc.constraint_name, tc.constraint_type,
                   kcu.column_name, ccu.table_name AS ref_table, ccu.column_name AS ref_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
              AND tc.table_name = kcu.table_name
            LEFT JOIN information_schema.constraint_column_usage ccu
              ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema
              AND tc.constraint_type = 'FOREIGN KEY'
            WHERE tc.table_schema = ?
              AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')
            ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position
            """.trimIndent(),
        ).use { statement ->
            statement.queryTimeout = config.queryTimeoutSeconds
            statement.setString(1, schemaName)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += Row(
                        rs.getString("table_name").lowercase(),
                        rs.getString("constraint_name"),
                        rs.getString("constraint_type"),
                        rs.getString("column_name"),
                        rs.getString("ref_table"),
                        rs.getString("ref_column"),
                    )
                }
            }
        }
        return rows.groupBy { it.table to it.name }.entries
            .groupBy({ it.key.first }, { (key, group) ->
                ConstraintSchema(
                    kind = when (group.first().type) {
                        "PRIMARY KEY" -> ConstraintKind.PRIMARY_KEY
                        "UNIQUE" -> ConstraintKind.UNIQUE
                        else -> ConstraintKind.FOREIGN_KEY
                    },
                    name = key.second,
                    columns = group.map { it.column }.distinct(),
                    refTable = group.firstNotNullOfOrNull { it.refTable },
                    refColumns = group.mapNotNull { it.refColumn }.distinct(),
                )
            })
    }

    private fun mapType(dataType: String, length: Int?, precision: Int?, scale: Int?): SqlDataType {
        val words = dataType.trim().split(Regex("\\s+"))
        val args = when (words.first().lowercase()) {
            "character", "char", "varchar" -> listOfNotNull(length)
            "numeric", "decimal" -> listOfNotNull(precision, scale)
            else -> emptyList()
        }
        return SqlDataType.resolve(words, args, dataType.uppercase())
    }
}

// -------------------------------------------------------------------------------------------
// Oracle metadata (ALL_* dictionary views)
// -------------------------------------------------------------------------------------------

private object OracleMetadata {

    fun fetchTables(connection: Connection, config: DatabaseConfig): Map<String, TableSchema> {
        val owner = config.schemaName.ifBlank { config.user }.uppercase()
        val columnsByTable = LinkedHashMap<String, MutableList<ColumnSchema>>()
        connection.prepareStatement(
            """
            SELECT table_name, column_name, data_type, char_length, data_precision, data_scale,
                   nullable, data_default
            FROM all_tab_columns
            WHERE owner = ?
            ORDER BY table_name, column_id
            """.trimIndent(),
        ).use { statement ->
            statement.queryTimeout = config.queryTimeoutSeconds
            statement.setString(1, owner)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    val table = rs.getString("table_name").lowercase()
                    columnsByTable.getOrPut(table) { mutableListOf() } += ColumnSchema(
                        name = rs.getString("column_name").lowercase(),
                        dataType = mapType(
                            rs.getString("data_type"),
                            (rs.getObject("char_length") as? Number)?.toInt(),
                            (rs.getObject("data_precision") as? Number)?.toInt(),
                            (rs.getObject("data_scale") as? Number)?.toInt(),
                        ),
                        nullable = rs.getString("nullable").equals("Y", ignoreCase = true),
                        primaryKey = false,
                        unique = false,
                        hasDefault = rs.getString("data_default") != null,
                    )
                }
            }
        }
        val constraints = fetchConstraints(connection, config, owner)
        return MetadataSupport.assemble(columnsByTable, constraints)
    }

    private fun fetchConstraints(
        connection: Connection,
        config: DatabaseConfig,
        owner: String,
    ): Map<String, List<ConstraintSchema>> {
        data class Row(val table: String, val name: String, val type: String, val column: String, val refTable: String?, val refColumn: String?)
        val rows = mutableListOf<Row>()
        connection.prepareStatement(
            """
            SELECT c.table_name, c.constraint_name, c.constraint_type, cc.column_name,
                   r.table_name AS ref_table, rc.column_name AS ref_column
            FROM all_constraints c
            JOIN all_cons_columns cc
              ON c.owner = cc.owner AND c.constraint_name = cc.constraint_name
            LEFT JOIN all_constraints r
              ON c.r_owner = r.owner AND c.r_constraint_name = r.constraint_name
            LEFT JOIN all_cons_columns rc
              ON r.owner = rc.owner AND r.constraint_name = rc.constraint_name
              AND rc.position = cc.position
            WHERE c.owner = ? AND c.constraint_type IN ('P', 'U', 'R')
            ORDER BY c.table_name, c.constraint_name, cc.position
            """.trimIndent(),
        ).use { statement ->
            statement.queryTimeout = config.queryTimeoutSeconds
            statement.setString(1, owner)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += Row(
                        rs.getString("table_name").lowercase(),
                        rs.getString("constraint_name"),
                        rs.getString("constraint_type"),
                        rs.getString("column_name").lowercase(),
                        rs.getString("ref_table")?.lowercase(),
                        rs.getString("ref_column")?.lowercase(),
                    )
                }
            }
        }
        return rows.groupBy { it.table to it.name }.entries
            .groupBy({ it.key.first }, { (key, group) ->
                ConstraintSchema(
                    kind = when (group.first().type) {
                        "P" -> ConstraintKind.PRIMARY_KEY
                        "U" -> ConstraintKind.UNIQUE
                        else -> ConstraintKind.FOREIGN_KEY
                    },
                    name = key.second,
                    columns = group.map { it.column }.distinct(),
                    refTable = group.firstNotNullOfOrNull { it.refTable },
                    refColumns = group.mapNotNull { it.refColumn }.distinct(),
                )
            })
    }

    private fun mapType(rawType: String, charLength: Int?, precision: Int?, scale: Int?): SqlDataType {
        // strip parameter suffixes: TIMESTAMP(6) WITH TIME ZONE -> TIMESTAMP WITH TIME ZONE
        val cleaned = rawType.trim().replace(Regex("\\(\\d+\\)"), "")
        val words = cleaned.split(Regex("\\s+"))
        // Oracle DATE contains a time component: validate literals with TIMESTAMP semantics
        if (words.size == 1 && words[0].equals("DATE", true)) {
            return SqlDataType(TypeKind.TIMESTAMP, raw = "DATE")
        }
        val args = when (words.first().uppercase()) {
            "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR" -> listOfNotNull(charLength)
            "NUMBER", "DECIMAL", "NUMERIC" -> listOfNotNull(precision, scale ?: precision?.let { 0 })
            else -> emptyList()
        }
        return SqlDataType.resolve(words, args, rawType.uppercase())
    }
}

private object MetadataSupport {
    fun assemble(
        columnsByTable: Map<String, List<ColumnSchema>>,
        constraintsByTable: Map<String, List<ConstraintSchema>>,
    ): Map<String, TableSchema> = columnsByTable.mapValues { (table, columns) ->
        val constraints = constraintsByTable[table].orEmpty()
        val pkColumns = constraints.filter { it.kind == ConstraintKind.PRIMARY_KEY }
            .flatMap { it.columnsLower }.toSet()
        TableSchema(
            name = table,
            columns = columns.map { if (it.nameLower in pkColumns) it.copy(primaryKey = true) else it },
            constraints = constraints,
            origin = SchemaOrigin.DATABASE,
        )
    }
}
