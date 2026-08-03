package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.SqlValueExpr
import com.company.liquibasevalidator.sql.TypeFamily
import com.company.liquibasevalidator.sql.TypeKind
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Static validation of literal values against a declared column type.
 * Conservative by design: a value is only reported when it is provably invalid —
 * opaque expressions and ambiguous literals are never flagged (no false positives).
 */
object LiteralValidators {

    data class LiteralIssue(val message: String, val severity: Severity, val category: ProblemCategory)

    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val BOOLEAN_STRINGS = setOf("true", "false", "t", "f", "yes", "no", "y", "n", "on", "off", "1", "0")
    private val ISO_DATE = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
    private val SLASH_DATE = Regex("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$")
    private val TIME_PART = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.\\d{1,9})?)?$")
    private val INTEGER_LITERAL = Regex("^[+-]?\\d+$")

    /**
     * Checks a value against the declared [type] of [columnDisplay] (e.g. `account_type.code`).
     * Returns null when the value is valid or not statically checkable.
     */
    fun check(value: SqlValueExpr, type: SqlDataType, columnDisplay: String, options: ValidationOptions): LiteralIssue? {
        if (type.kind == TypeKind.OTHER) return null
        return when (value) {
            is SqlValueExpr.StringLit -> checkString(value, type, columnDisplay, options)
            is SqlValueExpr.NumberLit -> checkNumber(value.literal, type, columnDisplay, options)
            is SqlValueExpr.BoolLit -> checkBool(type, columnDisplay)
            else -> null // NULL/DEFAULT handled by nullability checks; refs/opaque not checkable
        }
    }

    private fun checkString(
        lit: SqlValueExpr.StringLit,
        columnType: SqlDataType,
        columnDisplay: String,
        options: ValidationOptions,
    ): LiteralIssue? {
        // 'value'::cast — validate the literal against the cast type, then the cast type
        // against the column family.
        val cast = lit.castType
        if (cast != null && cast.kind != TypeKind.OTHER) {
            if (cast.family != columnType.family) {
                return LiteralIssue(
                    "Liquibase: value cast to ${cast.display()} is not compatible with column " +
                        "'$columnDisplay' of type ${columnType.display()}",
                    Severity.ERROR, ProblemCategory.DATATYPE,
                )
            }
            return checkStringValue(lit.value, cast, columnDisplay, options)
                ?: checkStringValue(lit.value, columnType, columnDisplay, options)
        }
        return checkStringValue(lit.value, columnType, columnDisplay, options)
    }

    private fun checkStringValue(
        value: String,
        type: SqlDataType,
        columnDisplay: String,
        options: ValidationOptions,
    ): LiteralIssue? = when (type.family) {
        TypeFamily.STRING -> {
            val max = type.length
            val len = value.codePointCount(0, value.length)
            if (options.validateVarcharLength && max != null && len > max) {
                LiteralIssue(
                    "Liquibase: value length $len exceeds ${type.display()} — column '$columnDisplay' " +
                        "allows at most $max characters",
                    Severity.ERROR, ProblemCategory.LENGTH,
                )
            } else null
        }
        TypeFamily.INTEGER -> checkIntegerText(value.trim(), type, columnDisplay, options, fromString = true)
        TypeFamily.DECIMAL -> checkDecimalText(value.trim(), type, columnDisplay, options, fromString = true)
        TypeFamily.FLOATING ->
            if (value.trim().toDoubleOrNull() == null) {
                LiteralIssue(
                    "Liquibase: '$value' is not a valid ${type.display()} value for column '$columnDisplay'",
                    Severity.ERROR, ProblemCategory.DATATYPE,
                )
            } else null
        TypeFamily.BOOLEAN ->
            if (value.trim().lowercase() !in BOOLEAN_STRINGS) {
                LiteralIssue(
                    "Liquibase: '$value' is not a valid BOOLEAN value for column '$columnDisplay' " +
                        "(expected TRUE/FALSE or a PostgreSQL boolean string)",
                    Severity.ERROR, ProblemCategory.DATATYPE,
                )
            } else null
        TypeFamily.DATE -> checkDateText(value.trim(), columnDisplay)
        TypeFamily.TIMESTAMP -> checkTimestampText(value.trim(), columnDisplay)
        TypeFamily.UUID ->
            if (!UUID_REGEX.matches(value.trim())) {
                LiteralIssue(
                    "Liquibase: invalid UUID '$value' for column '$columnDisplay' — expected format " +
                        "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
                    Severity.ERROR, ProblemCategory.DATATYPE,
                )
            } else null
        else -> null
    }

    private fun checkNumber(
        literal: String,
        type: SqlDataType,
        columnDisplay: String,
        options: ValidationOptions,
    ): LiteralIssue? = when (type.family) {
        TypeFamily.INTEGER -> checkIntegerText(literal, type, columnDisplay, options, fromString = false)
        TypeFamily.DECIMAL -> checkDecimalText(literal, type, columnDisplay, options, fromString = false)
        TypeFamily.FLOATING -> null
        else -> LiteralIssue(
            "Liquibase: numeric literal $literal cannot be assigned to column '$columnDisplay' " +
                "of type ${type.display()}",
            Severity.ERROR, ProblemCategory.DATATYPE,
        )
    }

    private fun checkBool(type: SqlDataType, columnDisplay: String): LiteralIssue? =
        if (type.family == TypeFamily.BOOLEAN) null
        else LiteralIssue(
            "Liquibase: boolean literal cannot be assigned to column '$columnDisplay' of type ${type.display()}",
            Severity.ERROR, ProblemCategory.DATATYPE,
        )

    private fun checkIntegerText(
        text: String,
        type: SqlDataType,
        columnDisplay: String,
        options: ValidationOptions,
        fromString: Boolean,
    ): LiteralIssue? {
        if (!INTEGER_LITERAL.matches(text)) {
            return LiteralIssue(
                "Liquibase: ${if (fromString) "'$text'" else text} is not a valid ${type.kind} value " +
                    "for column '$columnDisplay'",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
        }
        if (!options.validateNumericRanges) return null
        val value = BigInteger(text)
        val range = SqlDataType.INTEGER_RANGES[type.kind] ?: return null
        return if (value < range.first || value > range.second) {
            LiteralIssue(
                "Liquibase: value $text is out of range for ${type.kind} " +
                    "(${range.first}..${range.second}) — column '$columnDisplay'",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
        } else null
    }

    private fun checkDecimalText(
        text: String,
        type: SqlDataType,
        columnDisplay: String,
        options: ValidationOptions,
        fromString: Boolean,
    ): LiteralIssue? {
        val decimal = try {
            BigDecimal(text)
        } catch (_: NumberFormatException) {
            return LiteralIssue(
                "Liquibase: ${if (fromString) "'$text'" else text} is not a valid ${type.display()} value " +
                    "for column '$columnDisplay'",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
        }
        if (!options.validateNumericRanges) return null
        val precision = type.precision ?: return null
        val scale = type.scale ?: 0
        val normalized = decimal.stripTrailingZeros()
        val actualScale = maxOf(normalized.scale(), 0)
        val integerDigits = normalized.precision() - normalized.scale()
        val maxIntegerDigits = precision - scale
        if (integerDigits > maxIntegerDigits) {
            return LiteralIssue(
                "Liquibase: decimal precision exceeded for column '$columnDisplay' ${type.display()} — " +
                    "$text has $integerDigits integer digits, maximum is $maxIntegerDigits",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
        }
        if (actualScale > scale) {
            return LiteralIssue(
                "Liquibase: decimal scale exceeded for column '$columnDisplay' ${type.display()} — " +
                    "$text has $actualScale fractional digits, maximum is $scale",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
        }
        return null
    }

    private fun checkDateText(text: String, columnDisplay: String): LiteralIssue? {
        // Formats with month names / special values (e.g. 'Jan 1 2020', 'today') are legal in
        // PostgreSQL and not statically checkable — stay silent for anything non-numeric.
        val match = ISO_DATE.matchEntire(text) ?: SLASH_DATE.matchEntire(text) ?: return null
        return if (!isValidDate(match)) {
            LiteralIssue(
                "Liquibase: '$text' is not a valid DATE for column '$columnDisplay'",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
        } else null
    }

    private fun checkTimestampText(text: String, columnDisplay: String): LiteralIssue? {
        val normalized = text.removeSuffix("Z").removeSuffix("z")
        val separator = normalized.indexOfFirst { it == ' ' || it == 'T' || it == 't' }
        val datePart = if (separator >= 0) normalized.substring(0, separator) else normalized
        var timePart = if (separator >= 0) normalized.substring(separator + 1).trim() else ""
        // strip numeric timezone offset (+05:30 / -08)
        val tzIdx = timePart.indexOfFirst { it == '+' }.let { if (it >= 0) it else timePart.lastIndexOf('-') }
        if (tzIdx > 0) timePart = timePart.substring(0, tzIdx).trim()

        val dateMatch = ISO_DATE.matchEntire(datePart) ?: SLASH_DATE.matchEntire(datePart) ?: return null
        if (!isValidDate(dateMatch)) {
            return LiteralIssue(
                "Liquibase: '$text' is not a valid TIMESTAMP for column '$columnDisplay' (invalid date part)",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
        }
        if (timePart.isNotEmpty()) {
            val timeMatch = TIME_PART.matchEntire(timePart)
                ?: return LiteralIssue(
                    "Liquibase: '$text' is not a valid TIMESTAMP for column '$columnDisplay' (invalid time part)",
                    Severity.ERROR, ProblemCategory.DATATYPE,
                )
            val hours = timeMatch.groupValues[1].toInt()
            val minutes = timeMatch.groupValues[2].toInt()
            val seconds = timeMatch.groupValues[3].toIntOrNull() ?: 0
            if (hours > 24 || minutes > 59 || seconds > 60) {
                return LiteralIssue(
                    "Liquibase: '$text' is not a valid TIMESTAMP for column '$columnDisplay' (invalid time part)",
                    Severity.ERROR, ProblemCategory.DATATYPE,
                )
            }
        }
        return null
    }

    private fun isValidDate(match: MatchResult): Boolean {
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (year < 1 || month !in 1..12 || day < 1) return false
        val daysInMonth = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        }
        return day <= daysInMonth
    }
}
