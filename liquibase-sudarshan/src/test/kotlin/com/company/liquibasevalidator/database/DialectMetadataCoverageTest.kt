package com.company.liquibasevalidator.database

import com.company.liquibasevalidator.schema.ColumnSchema
import com.company.liquibasevalidator.schema.ConstraintKind
import com.company.liquibasevalidator.schema.ConstraintSchema
import com.company.liquibasevalidator.schema.SchemaOrigin
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.TypeKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Covers the dialect metadata mappers in JdbcDatabaseAccess.kt (PostgresMetadata,
 * OracleMetadata, MetadataSupport) without any real JDBC.
 *
 * The three objects are file-private in Kotlin, but compile to package-private JVM classes;
 * this test lives in the same package and calls `fetchTables` reflectively. The JDBC surface
 * they touch (Connection -> PreparedStatement -> ResultSet) is faked with dynamic proxies
 * serving canned rows keyed by column label — exactly the access pattern of the mappers.
 */
class DialectMetadataCoverageTest {

    // ------------------------------------------------------------------ PostgreSQL

    @Test
    fun `postgres fetchTables maps columns constraints and defaults for the public schema`() {
        val columns = listOf(
            pgColumn("Account", "id", "integer", nullable = "NO", default = "nextval('account_id_seq'::regclass)"),
            pgColumn("Account", "tenant_id", "bigint", nullable = "NO"),
            pgColumn("Account", "name", "character varying", length = 255, nullable = "YES"),
            pgColumn("Account", "balance", "numeric", precision = 10, scale = 2, nullable = "NO"),
            pgColumn("Account", "created_at", "timestamp without time zone", nullable = "YES"),
            pgColumn("audit_log", "entry", "text", nullable = "YES"),
        )
        val constraints = listOf(
            pgConstraint("Account", "account_pkey", "PRIMARY KEY", "id"),
            pgConstraint("Account", "account_pkey", "PRIMARY KEY", "tenant_id"),
            pgConstraint("Account", "account_name_key", "UNIQUE", "name"),
            pgConstraint("Account", "account_customer_fk", "FOREIGN KEY", "customer_type_id", "customer_type", "id"),
        )
        val db = FakeDb(columns, constraints)
        val config = DatabaseConfig("jdbc:postgresql://ignored/db", "app", "secret", queryTimeoutSeconds = 7)

        val tables = fetchTablesVia("PostgresMetadata", db.connection(), config)

        // blank schemaName falls back to "public" and is bound on both statements
        assertEquals(listOf("public", "public"), db.boundParams)
        assertEquals(listOf(7, 7), db.timeouts)
        assertEquals(listOf("account", "audit_log"), tables.keys.toList())

        val account = tables.getValue("account")
        assertEquals("account", account.name)
        assertEquals(SchemaOrigin.DATABASE, account.origin)
        assertEquals(
            listOf(
                ColumnSchema("id", SqlDataType(TypeKind.INTEGER, raw = "INTEGER"), false, true, false, true),
                ColumnSchema("tenant_id", SqlDataType(TypeKind.BIGINT, raw = "BIGINT"), false, true, false, false),
                ColumnSchema(
                    "name", SqlDataType(TypeKind.VARCHAR, length = 255, raw = "CHARACTER VARYING"),
                    true, false, false, false,
                ),
                ColumnSchema(
                    "balance", SqlDataType(TypeKind.DECIMAL, precision = 10, scale = 2, raw = "NUMERIC"),
                    false, false, false, false,
                ),
                ColumnSchema(
                    "created_at", SqlDataType(TypeKind.TIMESTAMP, raw = "TIMESTAMP WITHOUT TIME ZONE"),
                    true, false, false, false,
                ),
            ),
            account.columns,
        )
        assertEquals(
            listOf(
                ConstraintSchema(ConstraintKind.PRIMARY_KEY, "account_pkey", listOf("id", "tenant_id")),
                ConstraintSchema(ConstraintKind.UNIQUE, "account_name_key", listOf("name")),
                ConstraintSchema(
                    ConstraintKind.FOREIGN_KEY, "account_customer_fk", listOf("customer_type_id"),
                    refTable = "customer_type", refColumns = listOf("id"),
                ),
            ),
            account.constraints,
        )

        // a table without any constraint: orEmpty() path, no primary-key promotion
        val auditLog = tables.getValue("audit_log")
        assertEquals(
            listOf(ColumnSchema("entry", SqlDataType(TypeKind.TEXT, raw = "TEXT"), true, false, false, false)),
            auditLog.columns,
        )
        assertTrue(auditLog.constraints.isEmpty())
    }

    @Test
    fun `postgres fetchTables binds an explicit schema and returns an empty map for empty metadata`() {
        val db = FakeDb(emptyList(), emptyList())
        val config = DatabaseConfig("jdbc:postgresql://ignored/db", "app", "secret", schemaName = "sales")

        val tables = fetchTablesVia("PostgresMetadata", db.connection(), config)

        assertTrue(tables.isEmpty())
        assertEquals(listOf("sales", "sales"), db.boundParams)
    }

    // ------------------------------------------------------------------ Oracle

    @Test
    fun `oracle fetchTables maps NUMBER DATE and TIMESTAMP variants and constraints for the connected user`() {
        val columns = listOf(
            oraColumn("EMPLOYEE", "ID", "NUMBER", precision = 10, nullable = "N"),
            oraColumn("EMPLOYEE", "NAME", "VARCHAR2", charLength = 100, nullable = "Y"),
            oraColumn("EMPLOYEE", "SALARY", "NUMBER", precision = BigDecimal(8), scale = BigDecimal(2), nullable = "N", default = "0 "),
            oraColumn("EMPLOYEE", "FLAG", "NUMBER", nullable = "Y"),
            oraColumn("EMPLOYEE", "HIRED_AT", "DATE", nullable = "Y"),
            oraColumn("EMPLOYEE", "UPDATED_AT", "TIMESTAMP(6) WITH TIME ZONE", nullable = "Y"),
            oraColumn("EMPLOYEE", "NOTES", "CLOB", nullable = "Y"),
            oraColumn("DEPT", "NAME", "VARCHAR2", charLength = 30, nullable = "N"),
        )
        val constraints = listOf(
            oraConstraint("EMPLOYEE", "EMP_PK", "P", "ID"),
            oraConstraint("EMPLOYEE", "EMP_DEPT_FK", "R", "DEPT_ID", "DEPT", "ID"),
            oraConstraint("DEPT", "DEPT_NAME_UK", "U", "NAME"),
        )
        val db = FakeDb(columns, constraints)
        val config = DatabaseConfig("jdbc:oracle:thin:@//ignored/X", "app_user", "secret")

        val tables = fetchTablesVia("OracleMetadata", db.connection(), config)

        // blank schemaName falls back to the uppercased user on both statements
        assertEquals(listOf("APP_USER", "APP_USER"), db.boundParams)
        assertEquals(listOf("employee", "dept"), tables.keys.toList())

        val employee = tables.getValue("employee")
        assertEquals(SchemaOrigin.DATABASE, employee.origin)
        assertEquals(
            listOf(
                // NUMBER(10) with NULL scale defaults the scale to 0
                ColumnSchema("id", SqlDataType(TypeKind.DECIMAL, precision = 10, scale = 0, raw = "NUMBER"), false, true, false, false),
                ColumnSchema("name", SqlDataType(TypeKind.VARCHAR, length = 100, raw = "VARCHAR2"), true, false, false, false),
                ColumnSchema("salary", SqlDataType(TypeKind.DECIMAL, precision = 8, scale = 2, raw = "NUMBER"), false, false, false, true),
                // plain NUMBER keeps precision and scale unset
                ColumnSchema("flag", SqlDataType(TypeKind.DECIMAL, raw = "NUMBER"), true, false, false, false),
                // Oracle DATE carries a time component: validated with TIMESTAMP semantics
                ColumnSchema("hired_at", SqlDataType(TypeKind.TIMESTAMP, raw = "DATE"), true, false, false, false),
                // the "(6)" parameter is stripped before resolution, raw spelling is preserved
                ColumnSchema("updated_at", SqlDataType(TypeKind.TIMESTAMPTZ, raw = "TIMESTAMP(6) WITH TIME ZONE"), true, false, false, false),
                ColumnSchema("notes", SqlDataType(TypeKind.TEXT, raw = "CLOB"), true, false, false, false),
            ),
            employee.columns,
        )
        assertEquals(
            listOf(
                ConstraintSchema(ConstraintKind.PRIMARY_KEY, "EMP_PK", listOf("id")),
                ConstraintSchema(
                    ConstraintKind.FOREIGN_KEY, "EMP_DEPT_FK", listOf("dept_id"),
                    refTable = "dept", refColumns = listOf("id"),
                ),
            ),
            employee.constraints,
        )

        // a table whose only constraint is UNIQUE: constraints present, no primary-key promotion
        val dept = tables.getValue("dept")
        assertEquals(
            listOf(ColumnSchema("name", SqlDataType(TypeKind.VARCHAR, length = 30, raw = "VARCHAR2"), false, false, false, false)),
            dept.columns,
        )
        assertEquals(listOf(ConstraintSchema(ConstraintKind.UNIQUE, "DEPT_NAME_UK", listOf("name"))), dept.constraints)
    }

    @Test
    fun `oracle fetchTables uppercases an explicit owner and returns an empty map for empty metadata`() {
        val db = FakeDb(emptyList(), emptyList())
        val config = DatabaseConfig("jdbc:oracle:thin:@//ignored/X", "app_user", "secret", schemaName = "hr")

        val tables = fetchTablesVia("OracleMetadata", db.connection(), config)

        assertTrue(tables.isEmpty())
        assertEquals(listOf("HR", "HR"), db.boundParams)
    }

    // ------------------------------------------------------------------ canned-row builders

    private fun pgColumn(
        table: String,
        column: String,
        dataType: String,
        length: Int? = null,
        precision: Int? = null,
        scale: Int? = null,
        nullable: String,
        default: String? = null,
    ): Map<String, Any?> = mapOf(
        "table_name" to table,
        "column_name" to column,
        "data_type" to dataType,
        "character_maximum_length" to length,
        "numeric_precision" to precision,
        "numeric_scale" to scale,
        "is_nullable" to nullable,
        "column_default" to default,
    )

    private fun pgConstraint(
        table: String,
        name: String,
        type: String,
        column: String,
        refTable: String? = null,
        refColumn: String? = null,
    ): Map<String, Any?> = mapOf(
        "table_name" to table,
        "constraint_name" to name,
        "constraint_type" to type,
        "column_name" to column,
        "ref_table" to refTable,
        "ref_column" to refColumn,
    )

    private fun oraColumn(
        table: String,
        column: String,
        dataType: String,
        charLength: Int? = null,
        precision: Number? = null,
        scale: Number? = null,
        nullable: String,
        default: String? = null,
    ): Map<String, Any?> = mapOf(
        "table_name" to table,
        "column_name" to column,
        "data_type" to dataType,
        "char_length" to charLength,
        "data_precision" to precision,
        "data_scale" to scale,
        "nullable" to nullable,
        "data_default" to default,
    )

    private fun oraConstraint(
        table: String,
        name: String,
        type: String,
        column: String,
        refTable: String? = null,
        refColumn: String? = null,
    ): Map<String, Any?> = mapOf(
        "table_name" to table,
        "constraint_name" to name,
        "constraint_type" to type,
        "column_name" to column,
        "ref_table" to refTable,
        "ref_column" to refColumn,
    )
}

// ---------------------------------------------------------------------- JDBC fakes

/**
 * Serves canned rows for the two statements each dialect prepares: the column query
 * (information_schema.columns / all_tab_columns) and the constraint query
 * (information_schema.table_constraints / all_constraints). Records every bound string
 * parameter and query timeout so tests can assert schema/owner resolution.
 */
private class FakeDb(
    private val columnRows: List<Map<String, Any?>>,
    private val constraintRows: List<Map<String, Any?>>,
) {
    val boundParams = mutableListOf<String>()
    val timeouts = mutableListOf<Int>()

    fun connection(): Connection = proxy(Connection::class.java) { method, args ->
        when (method.name) {
            "prepareStatement" -> statement(rowsFor(args!![0] as String))
            "close" -> null
            else -> error("Unexpected Connection call: ${method.name}")
        }
    }

    private fun rowsFor(sql: String): List<Map<String, Any?>> = when {
        sql.contains("information_schema.columns") || sql.contains("all_tab_columns") -> columnRows
        sql.contains("information_schema.table_constraints") || sql.contains("FROM all_constraints") -> constraintRows
        else -> error("Unexpected SQL routed to fake connection: $sql")
    }

    private fun statement(rows: List<Map<String, Any?>>): PreparedStatement =
        proxy(PreparedStatement::class.java) { method, args ->
            when (method.name) {
                "setQueryTimeout" -> { timeouts += args!![0] as Int; null }
                "setString" -> { boundParams += args!![1] as String; null }
                "executeQuery" -> resultSet(rows)
                "close" -> null
                else -> error("Unexpected PreparedStatement call: ${method.name}")
            }
        }

    private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
        var cursor = -1
        return proxy(ResultSet::class.java) { method, args ->
            when (method.name) {
                "next" -> { cursor++; cursor < rows.size }
                "getString" -> rows[cursor].resolve(args!![0] as String) as String?
                "getObject" -> rows[cursor].resolve(args!![0] as String)
                "close" -> null
                else -> error("Unexpected ResultSet call: ${method.name}")
            }
        }
    }

    private fun Map<String, Any?>.resolve(label: String): Any? {
        check(containsKey(label)) { "Canned row has no column labeled '$label' (row: $this)" }
        return get(label)
    }
}

private fun <T : Any> proxy(iface: Class<T>, handler: (Method, Array<out Any?>?) -> Any?): T =
    iface.cast(
        Proxy.newProxyInstance(FakeDb::class.java.classLoader, arrayOf(iface)) { self, method, args ->
            when (method.name) {
                "toString" -> "fake-${iface.simpleName}"
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                else -> handler(method, args)
            }
        },
    )

/**
 * Invokes `fetchTables(Connection, DatabaseConfig)` on one of the file-private metadata
 * objects. They compile to package-private JVM classes in this very package, so reflective
 * access is legal at the bytecode level; only Kotlin's file-privacy prevents a direct call.
 */
private fun fetchTablesVia(objectName: String, connection: Connection, config: DatabaseConfig): Map<String, TableSchema> {
    val clazz = Class.forName("com.company.liquibasevalidator.database.$objectName")
    val instance = clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    val method = clazz.getDeclaredMethod("fetchTables", Connection::class.java, DatabaseConfig::class.java)
        .apply { isAccessible = true }
    return try {
        @Suppress("UNCHECKED_CAST")
        method.invoke(instance, connection, config) as Map<String, TableSchema>
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}
