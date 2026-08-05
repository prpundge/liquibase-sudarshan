package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.schema.SchemaProvider
import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.SqlValueExpr
import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.sql.TypeKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Coverage-focused tests for the literal-format edge branches in [LiteralValidators]
 * and the insert-validation branches in [InsertValidator].
 */
class LiteralInsertCoverageTest {

    private val options = ValidationOptions()
    private val range = SrcRange(0, 1)

    private fun str(value: String, cast: SqlDataType? = null) = SqlValueExpr.StringLit(value, cast, range)
    private fun num(text: String, negative: Boolean = false) = SqlValueExpr.NumberLit(text, negative, range)

    private fun check(value: SqlValueExpr, type: SqlDataType, opts: ValidationOptions = options) =
        LiteralValidators.check(value, type, "t.col", opts)

    // =====================================================================================
    // LiteralValidators — cast handling
    // =====================================================================================

    @Test
    fun `cast to a different family than the column is an error`() {
        val issue = check(str("2024-01-01", cast = SqlDataType(TypeKind.DATE)), SqlDataType(TypeKind.INTEGER))
        assertNotNull(issue)
        assertEquals(Severity.ERROR, issue!!.severity)
        assertEquals(ProblemCategory.DATATYPE, issue.category)
        assertTrue(issue.message.contains("cast to DATE"))
        assertTrue(issue.message.contains("not compatible"))
        assertTrue(issue.message.contains("INTEGER"))
    }

    @Test
    fun `invalid literal for a same-family cast is reported from the cast check`() {
        val issue = check(str("2023-02-29", cast = SqlDataType(TypeKind.DATE)), SqlDataType(TypeKind.DATE))
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("not a valid DATE"))
    }

    @Test
    fun `cast check passing falls back to the column type check`() {
        // cast VARCHAR without length passes, but the column's VARCHAR(3) still enforces its limit
        val issue = check(
            str("abcdef", cast = SqlDataType(TypeKind.VARCHAR)),
            SqlDataType(TypeKind.VARCHAR, length = 3),
        )
        assertNotNull(issue)
        assertEquals(ProblemCategory.LENGTH, issue!!.category)
        assertTrue(issue.message.contains("at most 3"))
    }

    @Test
    fun `value valid for both cast and column yields no issue`() {
        assertNull(check(str("ab", cast = SqlDataType(TypeKind.VARCHAR)), SqlDataType(TypeKind.VARCHAR, length = 10)))
    }

    // =====================================================================================
    // LiteralValidators — string literals into numeric / floating / non-checkable columns
    // =====================================================================================

    @Test
    fun `decimal string literal is validated like a numeric literal`() {
        val decimal = SqlDataType(TypeKind.DECIMAL, precision = 10, scale = 2)
        assertNull(check(str(" 12.34 "), decimal))
        val issue = check(str("123.456"), decimal)
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("scale exceeded"))
    }

    @Test
    fun `non numeric string into floating column is an error`() {
        val issue = check(str("abc"), SqlDataType(TypeKind.REAL))
        assertNotNull(issue)
        assertEquals(ProblemCategory.DATATYPE, issue!!.category)
        assertTrue(issue.message.contains("'abc'"))
        assertTrue(issue.message.contains("REAL"))
    }

    @Test
    fun `parseable floating strings are accepted`() {
        assertNull(check(str("1.5e3"), SqlDataType(TypeKind.DOUBLE)))
        assertNull(check(str(" 2.5 "), SqlDataType(TypeKind.REAL)))
    }

    @Test
    fun `string literals into non checkable families are never flagged`() {
        assertNull(check(str("{\"a\":1}"), SqlDataType(TypeKind.JSON)))
        assertNull(check(str("25:99"), SqlDataType(TypeKind.TIME)))
        assertNull(check(str("deadbeef"), SqlDataType(TypeKind.BYTEA)))
    }

    @Test
    fun `numeric literal into floating column is always accepted`() {
        assertNull(check(num("3.14"), SqlDataType(TypeKind.DOUBLE)))
        assertNull(check(num("1"), SqlDataType(TypeKind.REAL)))
    }

    // =====================================================================================
    // LiteralValidators — integer / decimal range toggles and parse failures
    // =====================================================================================

    @Test
    fun `integer range validation can be disabled`() {
        val off = options.copy(validateNumericRanges = false)
        assertNull(check(num("99999999999999999999"), SqlDataType(TypeKind.INTEGER), off))
        assertNull(check(str("32768"), SqlDataType(TypeKind.SMALLINT), off))
    }

    @Test
    fun `integer boundaries are checked against the type range`() {
        assertNull(check(num("32768", negative = true), SqlDataType(TypeKind.SMALLINT)))
        val issue = check(num("32769", negative = true), SqlDataType(TypeKind.SMALLINT))
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("out of range"))
    }

    @Test
    fun `unparseable decimal string is an error with quoted value`() {
        val issue = check(str("12,50"), SqlDataType(TypeKind.DECIMAL, precision = 10, scale = 2))
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("'12,50'"))
        assertTrue(issue.message.contains("DECIMAL(10,2)"))
    }

    @Test
    fun `unparseable decimal numeric literal is an error without quotes`() {
        val issue = check(num("1.2.3"), SqlDataType(TypeKind.DECIMAL, precision = 10, scale = 2))
        assertNotNull(issue)
        assertTrue(issue!!.message.contains(" 1.2.3 "))
        assertFalse(issue.message.contains("'1.2.3'"))
    }

    @Test
    fun `decimal range validation can be disabled`() {
        val off = options.copy(validateNumericRanges = false)
        assertNull(check(num("12345678901234.999"), SqlDataType(TypeKind.DECIMAL, precision = 10, scale = 2), off))
    }

    @Test
    fun `decimal without declared precision is not range checked`() {
        assertNull(check(num("123456789.123456"), SqlDataType(TypeKind.DECIMAL)))
    }

    // =====================================================================================
    // LiteralValidators — timestamp edge formats
    // =====================================================================================

    @Test
    fun `timestamps with timezone offsets are accepted`() {
        val ts = SqlDataType(TypeKind.TIMESTAMPTZ)
        assertNull(check(str("2024-01-01 10:30:00+05:30"), ts))
        assertNull(check(str("2024-01-01 10:30:00-08"), ts))
        assertNull(check(str("2024-01-01T10:30:00Z"), ts))
    }

    @Test
    fun `invalid time still detected after stripping timezone offset`() {
        val issue = check(str("2024-01-01 10:70:00-08"), SqlDataType(TypeKind.TIMESTAMP))
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("invalid time part"))
    }

    @Test
    fun `slash dates are accepted in timestamps and invalid slash dates rejected`() {
        assertNull(check(str("2024/01/01 10:30"), SqlDataType(TypeKind.TIMESTAMP)))
        val issue = check(str("2024/02/30 10:30:00"), SqlDataType(TypeKind.TIMESTAMP))
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("invalid date part"))
    }

    @Test
    fun `non numeric timestamp date part is not statically checkable`() {
        assertNull(check(str("tomorrow 10:00"), SqlDataType(TypeKind.TIMESTAMP)))
    }

    @Test
    fun `garbage or malformed time part is an error`() {
        val issue = check(str("2024-01-01 nonsense"), SqlDataType(TypeKind.TIMESTAMP))
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("invalid time part"))
        assertNotNull(check(str("2024-01-01 1:2"), SqlDataType(TypeKind.TIMESTAMP)))
    }

    @Test
    fun `time without seconds and leap second are accepted but second 61 is not`() {
        assertNull(check(str("2024-01-01 10:30"), SqlDataType(TypeKind.TIMESTAMP)))
        assertNull(check(str("2024-01-01 23:59:60"), SqlDataType(TypeKind.TIMESTAMP)))
        assertNotNull(check(str("2024-01-01 10:30:61"), SqlDataType(TypeKind.TIMESTAMP)))
    }

    // =====================================================================================
    // LiteralValidators — calendar boundaries
    // =====================================================================================

    @Test
    fun `year zero month zero and day zero are invalid dates`() {
        val date = SqlDataType(TypeKind.DATE)
        assertNotNull(check(str("0000-01-01"), date))
        assertNotNull(check(str("2024-00-15"), date))
        assertNotNull(check(str("2024-01-00"), date))
    }

    @Test
    fun `thirty day months reject day 31`() {
        val date = SqlDataType(TypeKind.DATE)
        assertNotNull(check(str("2024-04-31"), date))
        assertNull(check(str("2024-06-30"), date))
        assertNotNull(check(str("2024-09-31"), date))
        assertNull(check(str("2024-11-30"), date))
    }

    @Test
    fun `century leap year rules are applied to february`() {
        val date = SqlDataType(TypeKind.DATE)
        assertNotNull(check(str("1900-02-29"), date))
        assertNull(check(str("2000-02-29"), date))
        assertNull(check(str("2023-02-28"), date))
        assertNotNull(check(str("2100-02-29"), date))
    }

    // =====================================================================================
    // InsertValidator — driven through ValidationEngine
    // =====================================================================================

    private val ddl = """
        CREATE TABLE account_type (
            code VARCHAR(15) NOT NULL PRIMARY KEY,
            name VARCHAR(100) NOT NULL,
            description VARCHAR(255),
            active BOOLEAN NOT NULL
        );
    """.trimIndent()

    private val schema: SchemaProvider =
        MapSchemaProvider(DdlSchemaBuilder.build(listOf(DdlSchemaBuilder.DdlSource("ddl.sql", ddl))))

    private fun validate(sql: String, opts: ValidationOptions = ValidationOptions()): List<ValidationProblem> =
        ValidationEngine(opts).validate(sql, schema).problems

    private fun List<ValidationProblem>.errors() = filter { it.severity == Severity.ERROR }

    @Test
    fun `staging insert with unknown column is a schema error`() {
        val sql = """
            CREATE TEMP TABLE tmp_x (a VARCHAR(5)) ON COMMIT DROP;
            INSERT INTO tmp_x (a, ghost) VALUES ('1', '2');
        """.trimIndent()
        val error = validate(sql).errors().single()
        assertEquals(ProblemCategory.SCHEMA, error.category)
        assertTrue(error.message.contains("column 'ghost' does not exist in table 'tmp_x'"))
        assertEquals(sql.indexOf("ghost"), error.range.start)
    }

    @Test
    fun `staging insert without column list maps positionally and ignores extra values`() {
        val sql = """
            CREATE TEMP TABLE tmp_x (a VARCHAR(5), b VARCHAR(5)) ON COMMIT DROP;
            INSERT INTO tmp_x VALUES ('123456', '2', '3');
        """.trimIndent()
        val problems = validate(sql)
        // no column list: the count check must not fire, values map by position
        assertTrue(problems.none { it.category == ProblemCategory.MAPPING })
        val lengthError = problems.errors().single()
        assertEquals(ProblemCategory.LENGTH, lengthError.category)
        assertTrue(lengthError.message.contains("tmp_x.a"))
        assertTrue(lengthError.message.contains("length 6"))
    }

    @Test
    fun `merge into unknown table still validates against the staging declaration`() {
        val sql = """
            CREATE TEMP TABLE tmp_q (a VARCHAR(5)) ON COMMIT DROP;
            INSERT INTO tmp_q (a) VALUES ('12345678901');
            MERGE INTO nowhere AS target USING tmp_q AS source ON target.a = source.a
            WHEN MATCHED THEN UPDATE SET a = source.a;
        """.trimIndent()
        val problems = validate(sql)
        val lengthError = problems.errors().single { it.category == ProblemCategory.LENGTH }
        assertTrue(lengthError.message.contains("tmp_q.a"))
        assertTrue(lengthError.message.contains("length 11"))
    }

    @Test
    fun `mapped target column that does not exist is skipped but real targets still checked`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (code VARCHAR(15) NOT NULL) ON COMMIT DROP;
            INSERT INTO tmp_account_type (code) VALUES ('THIS_IS_LONGER_THAN_FIFTEEN');
            MERGE INTO account_type AS target USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN UPDATE SET ghost_col = source.code;
        """.trimIndent()
        val problems = validate(sql)
        val valueError = problems.errors().single { it.category == ProblemCategory.LENGTH }
        // the nonexistent ghost_col mapping is skipped; the real target column still reports
        assertTrue(valueError.message.contains("account_type.code"))
        assertTrue(valueError.message.contains("length 27"))
    }

    @Test
    fun `direct insert with unknown column reports it and skips its value`() {
        val sql = "INSERT INTO account_type (code, ghost, name, active) " +
            "VALUES ('A', 'not-checkable-against-anything', 'n', TRUE);"
        val error = validate(sql).errors().single()
        assertEquals(ProblemCategory.SCHEMA, error.category)
        assertTrue(error.message.contains("column 'ghost' does not exist in table 'account_type'"))
        assertEquals(sql.indexOf("ghost"), error.range.start)
    }

    @Test
    fun `direct insert value count mismatch is a mapping error and later rows still validate`() {
        val sql = "INSERT INTO account_type (code, name, active) VALUES ('A', 'n'), ('B', 'x', TRUE);"
        val error = validate(sql).errors().single()
        assertEquals(ProblemCategory.MAPPING, error.category)
        assertTrue(error.message.contains("'account_type'"))
        assertTrue(error.message.contains("2 value(s)"))
        assertTrue(error.message.contains("3 column(s)"))
    }

    @Test
    fun `direct insert without column list validates positionally and ignores extra values`() {
        val sql = "INSERT INTO account_type VALUES ('THIS_IS_LONGER_THAN_FIFTEEN', 'n', 'd', TRUE, 'extra');"
        val problems = validate(sql)
        assertTrue(problems.none { it.category == ProblemCategory.MAPPING })
        val error = problems.errors().single()
        assertEquals(ProblemCategory.LENGTH, error.category)
        assertTrue(error.message.contains("account_type.code"))
    }

    @Test
    fun `null into nullable column is fine but null into not null column is an error`() {
        val ok = validate("INSERT INTO account_type (code, name, description, active) VALUES ('A', 'n', NULL, TRUE);")
        assertTrue(ok.errors().isEmpty())

        val bad = validate("INSERT INTO account_type (code, name, description, active) VALUES ('A', NULL, 'd', TRUE);")
        val error = bad.errors().single()
        assertEquals(ProblemCategory.NULLABILITY, error.category)
        assertTrue(error.message.contains("account_type.name"))
    }

    // =====================================================================================
    // InsertValidator.staticRowValues — literal normalization for duplicate detection
    // =====================================================================================

    @Test
    fun `staticRowValues normalizes literals and skips surplus values`() {
        val row = listOf(
            str("SAVINGS"),
            num("10.50"),
            num("1.2.3"),
            SqlValueExpr.BoolLit(true, range),
            SqlValueExpr.NullLit(range),
            str("beyond-columns"),
        )
        val columns = listOf("code", "amount", "weird", "active", "note")
        val values = InsertValidator.staticRowValues(columns, row)
        assertEquals("SAVINGS", values["code"]!!.first)
        assertEquals("10.5", values["amount"]!!.first) // trailing zero stripped
        assertEquals("1.2.3", values["weird"]!!.first) // unparseable numeric literal kept verbatim
        assertEquals("true", values["active"]!!.first)
        assertFalse("note" in values) // NULL is not a static literal
        assertEquals(setOf("code", "amount", "weird", "active"), values.keys)
    }
}
