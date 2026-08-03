package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.SqlValueExpr
import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.sql.TypeKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiteralValidatorsTest {

    private val options = ValidationOptions()
    private val range = SrcRange(0, 1)

    private fun str(value: String, cast: SqlDataType? = null) = SqlValueExpr.StringLit(value, cast, range)
    private fun num(text: String, negative: Boolean = false) = SqlValueExpr.NumberLit(text, negative, range)
    private fun bool(value: Boolean) = SqlValueExpr.BoolLit(value, range)

    private fun check(value: SqlValueExpr, type: SqlDataType) =
        LiteralValidators.check(value, type, "t.col", options)

    // ------------------------------------------------------------------ VARCHAR

    @Test
    fun `varchar length at limit is valid`() {
        assertNull(check(str("a".repeat(15)), SqlDataType(TypeKind.VARCHAR, length = 15)))
    }

    @Test
    fun `varchar length one over limit is an error`() {
        val issue = check(str("a".repeat(16)), SqlDataType(TypeKind.VARCHAR, length = 15))
        assertNotNull(issue)
        assertEquals(Severity.ERROR, issue!!.severity)
        assertEquals(ProblemCategory.LENGTH, issue.category)
        assertTrue(issue.message.contains("length 16"))
        assertTrue(issue.message.contains("VARCHAR(15)"))
    }

    @Test
    fun `text type has no length limit`() {
        assertNull(check(str("a".repeat(100000)), SqlDataType(TypeKind.TEXT)))
    }

    // ------------------------------------------------------------------ INTEGER

    @Test
    fun `valid integer is accepted`() {
        assertNull(check(num("123"), SqlDataType(TypeKind.INTEGER)))
        assertNull(check(num("2147483647"), SqlDataType(TypeKind.INTEGER)))
    }

    @Test
    fun `integer overflow is an error`() {
        val issue = check(num("2147483648"), SqlDataType(TypeKind.INTEGER))
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("out of range"))
    }

    @Test
    fun `smallint rejects 999999`() {
        assertNotNull(check(num("999999"), SqlDataType(TypeKind.SMALLINT)))
        assertNull(check(num("32767"), SqlDataType(TypeKind.SMALLINT)))
        assertNotNull(check(num("32768", negative = false), SqlDataType(TypeKind.SMALLINT)))
        assertNull(check(num("32768", negative = true), SqlDataType(TypeKind.SMALLINT)))
    }

    @Test
    fun `bigint range enforced`() {
        assertNull(check(num("9223372036854775807"), SqlDataType(TypeKind.BIGINT)))
        assertNotNull(check(num("9223372036854775808"), SqlDataType(TypeKind.BIGINT)))
    }

    @Test
    fun `non integer literal into integer column is an error`() {
        assertNotNull(check(num("12.5"), SqlDataType(TypeKind.INTEGER)))
        assertNotNull(check(str("abc"), SqlDataType(TypeKind.INTEGER)))
        assertNull(check(str("123"), SqlDataType(TypeKind.INTEGER)))
    }

    // ------------------------------------------------------------------ DECIMAL

    private val decimal102 = SqlDataType(TypeKind.DECIMAL, precision = 10, scale = 2)

    @Test
    fun `decimal within precision and scale is valid`() {
        assertNull(check(num("12345678.12"), decimal102))
        assertNull(check(num("123456.7"), decimal102))
        assertNull(check(num("0.10"), decimal102))
    }

    @Test
    fun `decimal precision exceeded is an error`() {
        val issue = check(num("123456789.12"), decimal102)
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("precision exceeded"))
    }

    @Test
    fun `decimal scale exceeded is an error`() {
        val issue = check(num("123.123"), decimal102)
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("scale exceeded"))
    }

    @Test
    fun `spec example 123456789 point 999 exceeds DECIMAL 10 2`() {
        assertNotNull(check(num("123456789.999"), decimal102))
    }

    // ------------------------------------------------------------------ BOOLEAN

    @Test
    fun `boolean literals are valid`() {
        val boolType = SqlDataType(TypeKind.BOOLEAN)
        assertNull(check(bool(true), boolType))
        assertNull(check(bool(false), boolType))
        assertNull(check(str("true"), boolType))
        assertNull(check(str("f"), boolType))
        assertNull(check(str("yes"), boolType))
    }

    @Test
    fun `invalid boolean string is an error`() {
        assertNotNull(check(str("maybe"), SqlDataType(TypeKind.BOOLEAN)))
    }

    @Test
    fun `boolean literal into varchar is an error`() {
        assertNotNull(check(bool(true), SqlDataType(TypeKind.VARCHAR, length = 5)))
    }

    // ------------------------------------------------------------------ DATE / TIMESTAMP

    @Test
    fun `valid iso dates pass`() {
        val date = SqlDataType(TypeKind.DATE)
        assertNull(check(str("2024-02-29"), date))
        assertNull(check(str("2024/12/31"), date))
    }

    @Test
    fun `invalid dates fail`() {
        val date = SqlDataType(TypeKind.DATE)
        assertNotNull(check(str("2023-02-29"), date))
        assertNotNull(check(str("2024-13-01"), date))
        assertNotNull(check(str("2024-01-32"), date))
    }

    @Test
    fun `nondeterminable date formats are not flagged`() {
        assertNull(check(str("Jan 1 2020"), SqlDataType(TypeKind.DATE)))
        assertNull(check(str("today"), SqlDataType(TypeKind.DATE)))
    }

    @Test
    fun `valid timestamps pass`() {
        val ts = SqlDataType(TypeKind.TIMESTAMP)
        assertNull(check(str("2024-01-01 10:30:00"), ts))
        assertNull(check(str("2024-01-01T10:30:00.123"), ts))
        assertNull(check(str("2024-01-01"), ts))
    }

    @Test
    fun `invalid timestamps fail`() {
        val ts = SqlDataType(TypeKind.TIMESTAMP)
        assertNotNull(check(str("2024-01-01 25:61:00"), ts))
        assertNotNull(check(str("2024-13-01 10:00:00"), ts))
    }

    // ------------------------------------------------------------------ UUID

    @Test
    fun `valid uuid passes`() {
        assertNull(check(str("0e37df36-f698-11e6-8dd4-cb9ced3df976"), SqlDataType(TypeKind.UUID)))
    }

    @Test
    fun `invalid uuid fails with expected format hint`() {
        val issue = check(str("12345"), SqlDataType(TypeKind.UUID))
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("invalid UUID"))
        assertTrue(issue.message.contains("xxxxxxxx-"))
    }

    // ------------------------------------------------------------------ casts, numbers into strings

    @Test
    fun `cast literal validated against cast type`() {
        val issue = check(str("12345", cast = SqlDataType(TypeKind.UUID)), SqlDataType(TypeKind.UUID))
        assertNotNull(issue)
    }

    @Test
    fun `numeric literal into varchar is an error`() {
        assertNotNull(check(num("123"), SqlDataType(TypeKind.VARCHAR, length = 10)))
    }

    @Test
    fun `unknown types are never validated`() {
        assertNull(check(str("anything"), SqlDataType(TypeKind.OTHER)))
    }

    @Test
    fun `length validation can be disabled`() {
        val off = options.copy(validateVarcharLength = false)
        val issue = LiteralValidators.check(
            str("a".repeat(100)), SqlDataType(TypeKind.VARCHAR, length = 5), "t.col", off,
        )
        assertNull(issue)
    }
}
