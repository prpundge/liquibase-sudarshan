package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.TypeKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeCompatibilityTest {

    private fun varchar(n: Int) = SqlDataType(TypeKind.VARCHAR, length = n)
    private fun decimal(p: Int, s: Int) = SqlDataType(TypeKind.DECIMAL, precision = p, scale = s)

    @Test
    fun `varchar 50 into varchar 15 is an error`() {
        val issue = TypeCompatibility.check(varchar(50), varchar(15))
        assertNotNull(issue)
        assertEquals(Severity.ERROR, issue!!.severity)
        assertTrue(issue.detail.contains("VARCHAR(50) exceeds VARCHAR(15)"))
        assertTrue(issue.detail.contains("maximum allowed length is 15"))
    }

    @Test
    fun `equal or smaller varchar is fine`() {
        assertNull(TypeCompatibility.check(varchar(15), varchar(15)))
        assertNull(TypeCompatibility.check(varchar(10), varchar(15)))
    }

    @Test
    fun `text into bounded varchar is a warning`() {
        val issue = TypeCompatibility.check(SqlDataType(TypeKind.TEXT), varchar(15))
        assertNotNull(issue)
        assertEquals(Severity.WARNING, issue!!.severity)
    }

    @Test
    fun `varchar into text is fine`() {
        assertNull(TypeCompatibility.check(varchar(500), SqlDataType(TypeKind.TEXT)))
    }

    @Test
    fun `integer widths are ranked`() {
        assertNull(TypeCompatibility.check(SqlDataType(TypeKind.SMALLINT), SqlDataType(TypeKind.INTEGER)))
        assertNull(TypeCompatibility.check(SqlDataType(TypeKind.INTEGER), SqlDataType(TypeKind.BIGINT)))
        val issue = TypeCompatibility.check(SqlDataType(TypeKind.BIGINT), SqlDataType(TypeKind.INTEGER))
        assertNotNull(issue)
        assertEquals(Severity.ERROR, issue!!.severity)
    }

    @Test
    fun `decimal precision and scale must fit`() {
        assertNull(TypeCompatibility.check(decimal(8, 2), decimal(10, 2)))
        assertNotNull(TypeCompatibility.check(decimal(12, 2), decimal(10, 2)))
        assertNotNull(TypeCompatibility.check(decimal(10, 4), decimal(10, 2)))
    }

    @Test
    fun `cross family mismatch is an error`() {
        val issue = TypeCompatibility.check(varchar(50), SqlDataType(TypeKind.UUID))
        assertNotNull(issue)
        assertEquals(Severity.ERROR, issue!!.severity)
    }

    @Test
    fun `integer into wide decimal is fine, into narrow decimal warns`() {
        assertNull(TypeCompatibility.check(SqlDataType(TypeKind.INTEGER), decimal(12, 2)))
        val issue = TypeCompatibility.check(SqlDataType(TypeKind.BIGINT), decimal(10, 2))
        assertNotNull(issue)
        assertEquals(Severity.WARNING, issue!!.severity)
    }

    @Test
    fun `date into timestamp is fine`() {
        assertNull(TypeCompatibility.check(SqlDataType(TypeKind.DATE), SqlDataType(TypeKind.TIMESTAMP)))
    }

    @Test
    fun `unknown types are never flagged`() {
        assertNull(TypeCompatibility.check(SqlDataType(TypeKind.OTHER), varchar(15)))
        assertNull(TypeCompatibility.check(varchar(15), SqlDataType(TypeKind.OTHER)))
    }
}
