package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.schema.SchemaProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationEngineTest {

    private val ddl = """
        CREATE TABLE account_type (
            code VARCHAR(15) NOT NULL PRIMARY KEY,
            name VARCHAR(100) NOT NULL,
            description VARCHAR(255),
            active BOOLEAN NOT NULL
        );
        CREATE TABLE account (
            id BIGINT NOT NULL PRIMARY KEY,
            account_number VARCHAR(30) NOT NULL,
            balance DECIMAL(10,2),
            customer_type_id INTEGER,
            created_at TIMESTAMP,
            external_ref UUID
        );
    """.trimIndent()

    private val schema: SchemaProvider =
        MapSchemaProvider(DdlSchemaBuilder.build(listOf(DdlSchemaBuilder.DdlSource("ddl.sql", ddl))))

    private fun validate(sql: String, options: ValidationOptions = ValidationOptions()): List<ValidationProblem> =
        ValidationEngine(options).validate(sql, schema).problems

    private fun List<ValidationProblem>.errors() = filter { it.severity == Severity.ERROR }

    // -------------------------------------------------------------------------------------
    // Temp table declaration vs target
    // -------------------------------------------------------------------------------------

    @Test
    fun `temp column wider than target is an error with quick fix, highlighted on the type`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(50) NOT NULL
            ) ON COMMIT DROP;

            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN NOT MATCHED THEN INSERT (code) VALUES (source.code);
        """.trimIndent()
        val problems = validate(sql)
        val declProblem = problems.errors().single { it.category == ProblemCategory.LENGTH }
        assertTrue(declProblem.message.contains("'code'"))
        assertTrue(declProblem.message.contains("VARCHAR(50)"))
        assertTrue(declProblem.message.contains("account_type.code"))
        assertTrue(declProblem.message.contains("VARCHAR(15)"))
        assertEquals("VARCHAR(50)", sql.substring(declProblem.range.start, declProblem.range.end))

        val fix = declProblem.fixes.filterIsInstance<QuickFixData.ReplaceText>().single()
        assertEquals("VARCHAR(15)", fix.newText)
        assertTrue(fix.name.contains("Change VARCHAR(50) to VARCHAR(15)"))
    }

    @Test
    fun `spec example produces staging mismatch errors via heuristic without merge`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(50) NOT NULL,
                name VARCHAR(255) NOT NULL,
                description VARCHAR(500),
                active BOOLEAN NOT NULL
            ) ON COMMIT DROP;
        """.trimIndent()
        val errors = validate(sql).errors()
        assertEquals(3, errors.size) // code 50>15, name 255>100, description 500>255
    }

    // -------------------------------------------------------------------------------------
    // INSERT value validation (through the mapping)
    // -------------------------------------------------------------------------------------

    @Test
    fun `staged insert value exceeding target length is an error naming the target column`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(15) NOT NULL,
                name VARCHAR(100) NOT NULL,
                description VARCHAR(255),
                active BOOLEAN NOT NULL
            ) ON COMMIT DROP;

            INSERT INTO tmp_account_type (code, name, description, active)
            VALUES ('THIS_CODE_IS_LONGER_THAN_15', 'Name', 'Desc', TRUE);

            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN UPDATE SET name = source.name, description = source.description, active = source.active
            WHEN NOT MATCHED THEN INSERT (code, name, description, active)
            VALUES (source.code, source.name, source.description, source.active);
        """.trimIndent()
        val problems = validate(sql)
        val valueError = problems.errors().single { it.category == ProblemCategory.LENGTH }
        assertTrue(valueError.message.contains("length 27"))
        assertTrue(valueError.message.contains("account_type.code"))
        assertEquals("'THIS_CODE_IS_LONGER_THAN_15'", sql.substring(valueError.range.start, valueError.range.end))
        assertTrue(valueError.fixes.any { it is QuickFixData.SelectValue })
    }

    @Test
    fun `null into not null target column is an error`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(15) NOT NULL,
                name VARCHAR(100)
            ) ON COMMIT DROP;
            INSERT INTO tmp_account_type (code, name) VALUES ('A', NULL);
            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN UPDATE SET name = source.name;
        """.trimIndent()
        val problems = validate(sql)
        val nullError = problems.errors().single { it.category == ProblemCategory.NULLABILITY }
        assertTrue(nullError.message.contains("account_type.name"))
        assertEquals("NULL", sql.substring(nullError.range.start, nullError.range.end))
    }

    @Test
    fun `unknown column in insert is an error`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (code VARCHAR(15)) ON COMMIT DROP;
            INSERT INTO tmp_account_type (code, invalid_column) VALUES ('A', 'B');
        """.trimIndent()
        val problems = validate(sql, ValidationOptions(tempTableNameHeuristic = false))
        val error = problems.errors().single { it.message.contains("invalid_column") }
        assertTrue(error.message.contains("does not exist in table 'tmp_account_type'"))
        assertEquals(sql.indexOf("invalid_column"), error.range.start)
    }

    @Test
    fun `value count mismatch is an error`() {
        val sql = """
            CREATE TEMP TABLE tmp_x (a VARCHAR(5), b VARCHAR(5)) ON COMMIT DROP;
            INSERT INTO tmp_x (a, b) VALUES ('1');
        """.trimIndent()
        val error = validate(sql).errors().single()
        assertTrue(error.message.contains("1 value(s)"))
        assertTrue(error.message.contains("2 column(s)"))
    }

    @Test
    fun `direct insert into schema table is validated`() {
        val sql = """
            INSERT INTO account (id, account_number, balance, created_at, external_ref)
            VALUES (1, 'ACC-1', 123456789.99, '2024-01-01 10:00:00', 'not-a-uuid');
        """.trimIndent()
        val problems = validate(sql)
        assertTrue(problems.errors().any { it.message.contains("precision exceeded") })
        assertTrue(problems.errors().any { it.message.contains("invalid UUID") })
    }

    @Test
    fun `missing not null column without default in insert list is an error`() {
        val sql = "INSERT INTO account_type (code) VALUES ('A');"
        val problems = validate(sql)
        assertTrue(problems.errors().any { it.message.contains("'account_type.name'") && it.message.contains("missing") })
    }

    @Test
    fun `integer overflow in direct insert`() {
        val sql = "INSERT INTO account (id, account_number, customer_type_id) VALUES (1, 'A', 999999999999);"
        val problems = validate(sql)
        assertTrue(problems.errors().any { it.message.contains("out of range") })
    }

    // -------------------------------------------------------------------------------------
    // MERGE validation
    // -------------------------------------------------------------------------------------

    @Test
    fun `merge with unknown source column is an error`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (code VARCHAR(15) NOT NULL) ON COMMIT DROP;
            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN UPDATE SET name = source.missing_col;
        """.trimIndent()
        val error = validate(sql).errors().single { it.message.contains("missing_col") }
        assertTrue(error.message.contains("does not exist in source table"))
    }

    @Test
    fun `merge with unknown target column is an error`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (code VARCHAR(15), wrong VARCHAR(5)) ON COMMIT DROP;
            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN UPDATE SET no_such_column = source.wrong;
        """.trimIndent()
        val error = validate(sql, ValidationOptions(tempTableNameHeuristic = false, warnUnusedStagingColumns = false))
            .errors().single { it.message.contains("no_such_column") }
        assertTrue(error.message.contains("does not exist in target table 'account_type'"))
    }

    @Test
    fun `merge source target datatype mismatch is reported on schema table source`() {
        val sql = """
            MERGE INTO account AS target
            USING account_type AS source
            ON target.account_number = source.code
            WHEN MATCHED THEN UPDATE SET account_number = source.name;
        """.trimIndent()
        val problems = validate(sql)
        // account_type.name VARCHAR(100) does not fit account.account_number VARCHAR(30)
        assertTrue(problems.errors().any { it.message.contains("MERGE source/target datatype mismatch") })
    }

    @Test
    fun `temp table source mismatch is reported once on the staging declaration`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (code VARCHAR(15) NOT NULL, name VARCHAR(500) NOT NULL) ON COMMIT DROP;
            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN UPDATE SET name = source.name;
        """.trimIndent()
        val lengthErrors = validate(sql).errors().filter { it.category == ProblemCategory.LENGTH }
        assertEquals(1, lengthErrors.size)
        assertEquals("VARCHAR(500)", sql.substring(lengthErrors[0].range.start, lengthErrors[0].range.end))
    }

    @Test
    fun `merge into unknown table is a warning and validation is skipped`() {
        val sql = """
            CREATE TEMP TABLE tmp_zzz (a VARCHAR(5)) ON COMMIT DROP;
            MERGE INTO zzz AS target USING tmp_zzz AS source ON target.a = source.a
            WHEN MATCHED THEN UPDATE SET a = source.a;
        """.trimIndent()
        val problems = validate(sql)
        assertTrue(problems.any { it.severity == Severity.WARNING && it.message.contains("unknown table 'zzz'") })
        assertTrue(problems.errors().isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // Duplicates
    // -------------------------------------------------------------------------------------

    @Test
    fun `duplicate primary key values in staging data are detected`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (code VARCHAR(15) NOT NULL, name VARCHAR(100) NOT NULL) ON COMMIT DROP;
            INSERT INTO tmp_account_type (code, name)
            VALUES
                ('SAVINGS', 'Savings'),
                ('CURRENT', 'Current'),
                ('SAVINGS', 'Savings Account');
            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN UPDATE SET name = source.name;
        """.trimIndent()
        val duplicate = validate(sql).errors().single { it.category == ProblemCategory.DUPLICATE }
        assertTrue(duplicate.message.contains("SAVINGS"))
        assertTrue(duplicate.message.contains("row 1"))
        assertTrue(duplicate.message.contains("row 3"))
        // highlighted on the duplicated value
        assertEquals(sql.lastIndexOf("'SAVINGS'"), duplicate.range.start)
    }

    @Test
    fun `duplicates across multiple insert statements are detected`() {
        val sql = """
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE);
            INSERT INTO account_type (code, name, active) VALUES ('A', 'b', TRUE);
        """.trimIndent()
        val duplicate = validate(sql).errors().single { it.category == ProblemCategory.DUPLICATE }
        assertTrue(duplicate.message.contains("row 1"))
        assertTrue(duplicate.message.contains("row 2"))
    }

    @Test
    fun `different values are not duplicates`() {
        val sql = """
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE), ('B', 'a', TRUE);
        """.trimIndent()
        assertTrue(validate(sql).none { it.category == ProblemCategory.DUPLICATE })
    }

    // -------------------------------------------------------------------------------------
    // Unused staging columns
    // -------------------------------------------------------------------------------------

    @Test
    fun `unused staging column is a warning and configurable`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (code VARCHAR(15) NOT NULL, extra_column VARCHAR(10)) ON COMMIT DROP;
            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN UPDATE SET active = TRUE;
        """.trimIndent()
        val problems = validate(sql)
        val warning = problems.single { it.category == ProblemCategory.UNUSED }
        assertTrue(warning.message.contains("extra_column"))
        assertEquals(Severity.WARNING, warning.severity)

        val off = validate(sql, ValidationOptions(warnUnusedStagingColumns = false))
        assertTrue(off.none { it.category == ProblemCategory.UNUSED })
    }

    // -------------------------------------------------------------------------------------
    // Unresolved schema behavior (requirement 31: no false positives)
    // -------------------------------------------------------------------------------------

    @Test
    fun `unresolved schema produces a single weak warning and nothing else`() {
        val sql = """
            INSERT INTO account_type (code, name, active) VALUES ('WHATEVER_LONG_VALUE_HERE', 'x', TRUE);
        """.trimIndent()
        val problems = ValidationEngine(ValidationOptions()).validate(sql, MapSchemaProvider.UNRESOLVED).problems
        assertEquals(1, problems.size)
        assertEquals(Severity.WEAK_WARNING, problems.single().severity)
        assertTrue(problems.single().message.contains("unable to resolve"))
    }

    // -------------------------------------------------------------------------------------
    // Liquibase structure + rollback
    // -------------------------------------------------------------------------------------

    @Test
    fun `rollback referencing unknown table is a warning`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:1
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE);
            --rollback DELETE FROM no_such_table WHERE code = 'A';
        """.trimIndent()
        val problems = validate(sql)
        assertTrue(problems.any { it.message.contains("rollback") && it.message.contains("no_such_table") })
    }

    @Test
    fun `duplicate changesets are errors`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:1
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE);
            --changeset team:1
            INSERT INTO account_type (code, name, active) VALUES ('B', 'b', TRUE);
        """.trimIndent()
        assertTrue(validate(sql).errors().any { it.message.contains("duplicate changeset") })
    }

    // -------------------------------------------------------------------------------------
    // Unknown statement keywords (typos like INSEffRT must not silently skip validation)
    // -------------------------------------------------------------------------------------

    @Test
    fun `typoed statement keyword is an error with a did-you-mean quick fix`() {
        val sql = """
            CREATE GLOBAL TEMPORARY TABLE tmp_account_type (
                code VARCHAR(15) NOT NULL
            ) ON COMMIT DELETE ROWS;

            INSEffRT INTO tmp_account_type (code)
            VALUES ('PassssssssssssssssssssasdasdasdasdasdsssssssPF');
        """.trimIndent()
        val error = validate(sql).errors().single { it.category == ProblemCategory.SYNTAX }
        assertTrue(error.message.contains("INSEffRT"))
        assertTrue(error.message.contains("did you mean INSERT?"))
        assertEquals("INSEffRT", sql.substring(error.range.start, error.range.end))
        val fix = error.fixes.filterIsInstance<QuickFixData.ReplaceText>().single()
        assertEquals("INSERT", fix.newText)
    }

    @Test
    fun `statements after a typoed one still validate`() {
        val sql = """
            INSEffRT INTO account_type (code) VALUES ('X');
            INSERT INTO account_type (code, name, active) VALUES ('THIS_IS_LONGER_THAN_FIFTEEN', 'n', TRUE);
        """.trimIndent()
        val problems = validate(sql)
        assertTrue(problems.errors().any { it.category == ProblemCategory.SYNTAX })
        assertTrue(problems.errors().any { it.message.contains("length 27") })
    }

    @Test
    fun `recognized but unparsed statements are never flagged as unknown keywords`() {
        val sql = """
            GRANT SELECT ON account_type TO app_role;
            DROP TABLE tmp_account_type;
            CREATE SEQUENCE account_type_seq START WITH 1;
            VACUUM ANALYZE account_type;
            COMMENT ON TABLE account_type IS 'reference data';
        """.trimIndent()
        assertTrue(validate(sql).none { it.category == ProblemCategory.SYNTAX })
    }

    // -------------------------------------------------------------------------------------
    // Delimiter validation
    // -------------------------------------------------------------------------------------

    @Test
    fun `missing statement delimiter is a syntax ERROR because execution fails there`() {
        val sql = """
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE)
            INSERT INTO account_type (code, name, active) VALUES ('THIS_IS_WAY_TOO_LONG_FOR_15', 'b', TRUE);
        """.trimIndent()
        val problems = validate(sql)
        val delimiter = problems.single { it.category == ProblemCategory.SYNTAX && it.message.contains("missing statement delimiter") }
        assertEquals(Severity.ERROR, delimiter.severity)
        assertTrue(delimiter.message.contains("release FAILS"))
        // the second statement was still parsed and its oversized value still detected
        assertTrue(problems.errors().any { it.message.contains("length 27") })
    }

    @Test
    fun `delimiter and syntax checks stay on even with every configurable rule off`() {
        val strictOnly = ValidationOptions(
            validateVarcharLength = false, validateNumericRanges = false,
            validateNullConstraints = false, validateDuplicates = false,
            validateMergeMappings = false, validateForeignKeys = false,
            warnUnusedStagingColumns = false, tempTableNameHeuristic = false,
            validateLiquibaseStructure = false,
        )
        val sql = """
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE)
            INSERT INTO account_type (code, name, active) VALUES ('B', 'b', TRUE);
        """.trimIndent()
        val problems = ValidationEngine(strictOnly).validate(sql, schema).problems
        assertTrue(
            problems.any {
                it.severity == Severity.ERROR && it.category == ProblemCategory.SYNTAX &&
                    it.message.contains("missing statement delimiter")
            },
            "strict syntax checking must not be disableable; got: $problems",
        )
    }

    @Test
    fun `splitStatements false with multiple statements is a warning`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:1 splitStatements:false
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE);
            INSERT INTO account_type (code, name, active) VALUES ('B', 'b', TRUE);
        """.trimIndent()
        val warning = validate(sql).single { it.category == ProblemCategory.SYNTAX }
        assertEquals(Severity.WARNING, warning.severity)
        assertTrue(warning.message.contains("splitStatements:false"))
        assertTrue(warning.message.contains("2 statements"))
    }

    @Test
    fun `custom endDelimiter with semicolon-terminated statements is a weak warning`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:1 endDelimiter:/
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE);
            INSERT INTO account_type (code, name, active) VALUES ('B', 'b', TRUE);
        """.trimIndent()
        val warning = validate(sql).single { it.category == ProblemCategory.SYNTAX }
        assertEquals(Severity.WEAK_WARNING, warning.severity)
        assertTrue(warning.message.contains("endDelimiter:'/'"))
    }

    @Test
    fun `single statement changesets raise no delimiter warnings`() {
        val sql = """
            --liquibase formatted sql
            --changeset team:1 splitStatements:false
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE);
        """.trimIndent()
        assertTrue(validate(sql).none { it.category == ProblemCategory.SYNTAX })
    }

    // -------------------------------------------------------------------------------------
    // No false positives on a fully valid realistic script
    // -------------------------------------------------------------------------------------

    @Test
    fun `valid realistic liquibase script produces zero problems`() {
        val sql = """
            --liquibase formatted sql

            --changeset banking-team:003-insert-account-types context:BD labels:Comments runOnChange:true
            --comment: Upsert account types using temporary staging table

            --preconditions onFail:HALT onError:HALT
            --precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM account_type WHERE code IS NULL

            CREATE TEMP TABLE tmp_account_type (
                code        VARCHAR(15)  NOT NULL,
                name        VARCHAR(100) NOT NULL,
                description VARCHAR(255),
                active      BOOLEAN      NOT NULL
            ) ON COMMIT DROP;

            INSERT INTO tmp_account_type (code, name, description, active)
            VALUES
                ('SAVINGS', 'Savings Account', 'Standard savings account', TRUE),
                ('CURRENT', 'Current Account', 'Standard current account', TRUE);

            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN
                UPDATE SET
                    name        = source.name,
                    description = source.description,
                    active      = source.active
            WHEN NOT MATCHED THEN
                INSERT (code, name, description, active)
                VALUES (source.code, source.name, source.description, source.active);

            --rollback DELETE FROM account_type
            --rollback WHERE code IN ('SAVINGS', 'CURRENT');
        """.trimIndent()
        val problems = validate(sql)
        assertEquals(emptyList<ValidationProblem>(), problems)
    }
}
