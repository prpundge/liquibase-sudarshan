package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.TypeFamily
import com.company.liquibasevalidator.sql.TypeKind

/**
 * Compatibility of a *declared* staging/source column type against a *declared* target
 * column type — the staging definition must never be able to hold data the target cannot.
 */
object TypeCompatibility {

    data class CompatIssue(val detail: String, val severity: Severity, val category: ProblemCategory)

    private val INTEGER_RANK = mapOf(TypeKind.SMALLINT to 1, TypeKind.INTEGER to 2, TypeKind.BIGINT to 3)
    private val INTEGER_DIGITS = mapOf(TypeKind.SMALLINT to 5, TypeKind.INTEGER to 10, TypeKind.BIGINT to 19)

    /** Returns null when [source] can always be assigned to [target] without loss/failure. */
    fun check(source: SqlDataType, target: SqlDataType): CompatIssue? {
        if (source.kind == TypeKind.OTHER || target.kind == TypeKind.OTHER) return null
        if (source.family != target.family) return crossFamily(source, target)

        return when (target.family) {
            TypeFamily.STRING -> checkString(source, target)
            TypeFamily.INTEGER -> checkInteger(source, target)
            TypeFamily.DECIMAL -> checkDecimal(source, target)
            else -> null // identical family, no parameters to compare
        }
    }

    private fun crossFamily(source: SqlDataType, target: SqlDataType): CompatIssue? = when {
        // integer values always fit a sufficiently wide DECIMAL
        source.family == TypeFamily.INTEGER && target.family == TypeFamily.DECIMAL -> {
            val needed = INTEGER_DIGITS[source.kind] ?: 19
            val available = target.precision?.minus(target.scale ?: 0)
            if (available != null && available < needed) {
                CompatIssue(
                    "${source.display()} values may exceed ${target.display()} " +
                        "(needs up to $needed integer digits, target allows $available)",
                    Severity.WARNING, ProblemCategory.DATATYPE,
                )
            } else null
        }
        source.family == TypeFamily.INTEGER && target.family == TypeFamily.FLOATING -> null
        source.family == TypeFamily.DECIMAL && target.family == TypeFamily.FLOATING -> null
        // date → timestamp widens losslessly
        source.family == TypeFamily.DATE && target.family == TypeFamily.TIMESTAMP -> null
        else -> CompatIssue(
            "${source.display()} is not compatible with ${target.display()}",
            Severity.ERROR, ProblemCategory.DATATYPE,
        )
    }

    private fun checkString(source: SqlDataType, target: SqlDataType): CompatIssue? {
        val targetMax = target.length ?: return null // TEXT target holds anything
        val sourceMax = source.length
        return when {
            sourceMax == null -> CompatIssue(
                "${source.display()} is unbounded but target is ${target.display()} — " +
                    "values longer than $targetMax characters will fail",
                Severity.WARNING, ProblemCategory.LENGTH,
            )
            sourceMax > targetMax -> CompatIssue(
                "${source.display()} exceeds ${target.display()} — maximum allowed length is $targetMax, " +
                    "staging length is $sourceMax; the staging column must not exceed the target column",
                Severity.ERROR, ProblemCategory.LENGTH,
            )
            else -> null
        }
    }

    private fun checkInteger(source: SqlDataType, target: SqlDataType): CompatIssue? {
        val sourceRank = INTEGER_RANK[source.kind] ?: return null
        val targetRank = INTEGER_RANK[target.kind] ?: return null
        return if (sourceRank > targetRank) {
            CompatIssue(
                "${source.display()} range exceeds ${target.display()} — staging values may overflow the target",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
        } else null
    }

    private fun checkDecimal(source: SqlDataType, target: SqlDataType): CompatIssue? {
        val targetPrecision = target.precision ?: return null // unconstrained NUMERIC target
        val targetScale = target.scale ?: 0
        val sourcePrecision = source.precision
            ?: return CompatIssue(
                "${source.display()} is unconstrained but target is ${target.display()}",
                Severity.WARNING, ProblemCategory.DATATYPE,
            )
        val sourceScale = source.scale ?: 0
        val sourceIntegerDigits = sourcePrecision - sourceScale
        val targetIntegerDigits = targetPrecision - targetScale
        return when {
            sourceIntegerDigits > targetIntegerDigits -> CompatIssue(
                "${source.display()} allows $sourceIntegerDigits integer digits but ${target.display()} " +
                    "allows only $targetIntegerDigits — decimal precision exceeded",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
            sourceScale > targetScale -> CompatIssue(
                "${source.display()} allows $sourceScale fractional digits but ${target.display()} " +
                    "allows only $targetScale — decimal scale exceeded",
                Severity.ERROR, ProblemCategory.DATATYPE,
            )
            else -> null
        }
    }
}
