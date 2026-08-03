package com.company.liquibasevalidator.sql

import java.math.BigInteger

/** Logical datatype families used for compatibility checks. */
enum class TypeFamily { STRING, INTEGER, DECIMAL, FLOATING, BOOLEAN, DATE, TIMESTAMP, TIME, UUID, BYTEA, JSON, OTHER }

enum class TypeKind(val family: TypeFamily) {
    CHAR(TypeFamily.STRING),
    VARCHAR(TypeFamily.STRING),
    TEXT(TypeFamily.STRING),
    SMALLINT(TypeFamily.INTEGER),
    INTEGER(TypeFamily.INTEGER),
    BIGINT(TypeFamily.INTEGER),
    DECIMAL(TypeFamily.DECIMAL),
    REAL(TypeFamily.FLOATING),
    DOUBLE(TypeFamily.FLOATING),
    BOOLEAN(TypeFamily.BOOLEAN),
    DATE(TypeFamily.DATE),
    TIMESTAMP(TypeFamily.TIMESTAMP),
    TIMESTAMPTZ(TypeFamily.TIMESTAMP),
    TIME(TypeFamily.TIME),
    UUID(TypeFamily.UUID),
    BYTEA(TypeFamily.BYTEA),
    JSON(TypeFamily.JSON),
    JSONB(TypeFamily.JSON),
    OTHER(TypeFamily.OTHER),
}

/**
 * A resolved SQL datatype. [raw] preserves the source spelling (e.g. `CHARACTER VARYING(50)`),
 * used for messages and quick fixes.
 */
data class SqlDataType(
    val kind: TypeKind,
    val length: Int? = null,      // CHAR/VARCHAR
    val precision: Int? = null,   // DECIMAL/NUMERIC
    val scale: Int? = null,       // DECIMAL/NUMERIC
    val raw: String = kind.name,
) {
    val family: TypeFamily get() = kind.family

    /** Canonical display form, e.g. VARCHAR(15), DECIMAL(10,2). */
    fun display(): String = when {
        kind == TypeKind.CHAR && length != null -> "CHAR($length)"
        kind == TypeKind.VARCHAR && length != null -> "VARCHAR($length)"
        kind == TypeKind.DECIMAL && precision != null ->
            if (scale != null) "DECIMAL($precision,$scale)" else "DECIMAL($precision)"
        else -> kind.name
    }

    companion object {
        val INTEGER_RANGES: Map<TypeKind, Pair<BigInteger, BigInteger>> = mapOf(
            TypeKind.SMALLINT to (BigInteger.valueOf(-32768) to BigInteger.valueOf(32767)),
            TypeKind.INTEGER to (BigInteger.valueOf(-2147483648L) to BigInteger.valueOf(2147483647L)),
            TypeKind.BIGINT to (BigInteger("-9223372036854775808") to BigInteger("9223372036854775807")),
        )

        /**
         * Resolve a type from its source words, e.g. ["CHARACTER","VARYING"] with args [50].
         * Unknown types map to [TypeKind.OTHER] and are never validated (no false positives).
         */
        fun resolve(words: List<String>, args: List<Int>, raw: String): SqlDataType {
            val name = words.joinToString(" ") { it.uppercase() }
            val kind = when (name) {
                "CHAR", "CHARACTER", "BPCHAR", "NCHAR" -> TypeKind.CHAR
                "VARCHAR", "CHARACTER VARYING", "CHAR VARYING", "NVARCHAR", "VARCHAR2", "NVARCHAR2" -> TypeKind.VARCHAR
                "TEXT", "CLOB", "NCLOB", "LONGTEXT", "MEDIUMTEXT", "TINYTEXT", "CITEXT" -> TypeKind.TEXT
                "SMALLINT", "INT2", "SMALLSERIAL", "SERIAL2" -> TypeKind.SMALLINT
                "INT", "INTEGER", "INT4", "SERIAL", "SERIAL4" -> TypeKind.INTEGER
                "BIGINT", "INT8", "BIGSERIAL", "SERIAL8" -> TypeKind.BIGINT
                "DECIMAL", "NUMERIC", "DEC", "NUMBER" -> TypeKind.DECIMAL
                "REAL", "FLOAT4", "BINARY_FLOAT" -> TypeKind.REAL
                "DOUBLE PRECISION", "FLOAT8", "FLOAT", "BINARY_DOUBLE" -> TypeKind.DOUBLE
                "BOOLEAN", "BOOL" -> TypeKind.BOOLEAN
                "DATE" -> TypeKind.DATE
                "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE", "DATETIME" -> TypeKind.TIMESTAMP
                "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> TypeKind.TIMESTAMPTZ
                "TIME", "TIME WITHOUT TIME ZONE", "TIME WITH TIME ZONE", "TIMETZ" -> TypeKind.TIME
                "UUID" -> TypeKind.UUID
                "BYTEA", "BLOB" -> TypeKind.BYTEA
                "JSON" -> TypeKind.JSON
                "JSONB" -> TypeKind.JSONB
                else -> TypeKind.OTHER
            }
            return when (kind) {
                TypeKind.CHAR, TypeKind.VARCHAR ->
                    SqlDataType(kind, length = args.getOrNull(0), raw = raw)
                TypeKind.DECIMAL ->
                    SqlDataType(kind, precision = args.getOrNull(0), scale = args.getOrNull(1), raw = raw)
                else -> SqlDataType(kind, raw = raw)
            }
        }
    }
}
