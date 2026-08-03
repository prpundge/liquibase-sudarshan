package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DdlValidatorTest {

    private val schema = MapSchemaProvider(
        DdlSchemaBuilder.build(
            listOf(
                DdlSchemaBuilder.DdlSource(
                    "existing.sql",
                    "CREATE TABLE customer_type (id INTEGER NOT NULL PRIMARY KEY, code VARCHAR(10));",
                ),
            ),
        ),
    )

    private fun validate(sql: String, options: ValidationOptions = ValidationOptions()) =
        ValidationEngine(options).validate(sql, schema).problems

    @Test
    fun `duplicate column in create table is an error on the second occurrence`() {
        val sql = """
            CREATE TABLE account (
                id BIGINT NOT NULL,
                name VARCHAR(50),
                name VARCHAR(80)
            );
        """.trimIndent()
        val error = validate(sql).single { it.severity == Severity.ERROR }
        assertTrue(error.message.contains("duplicate column 'name'"))
        assertEquals(sql.lastIndexOf("name"), error.range.start)
    }

    @Test
    fun `table constraint referencing a missing column is an error`() {
        val sql = """
            CREATE TABLE account (
                id BIGINT NOT NULL,
                CONSTRAINT pk_account PRIMARY KEY (missing_id)
            );
        """.trimIndent()
        val error = validate(sql).single { it.severity == Severity.ERROR }
        assertTrue(error.message.contains("PRIMARY KEY"))
        assertTrue(error.message.contains("missing_id"))
    }

    @Test
    fun `alter table constraint on unknown column is an error`() {
        val sql = """
            CREATE TABLE account (id BIGINT NOT NULL);
            ALTER TABLE account ADD CONSTRAINT ux UNIQUE (no_such_col);
        """.trimIndent()
        val error = validate(sql).single { it.severity == Severity.ERROR }
        assertTrue(error.message.contains("no_such_col"))
    }

    @Test
    fun `foreign key to an unknown table is a warning`() {
        val sql = """
            CREATE TABLE account (
                id BIGINT NOT NULL,
                ghost_id INTEGER REFERENCES ghost_table (id)
            );
        """.trimIndent()
        val warning = validate(sql).single { it.category == ProblemCategory.SCHEMA }
        assertEquals(Severity.WARNING, warning.severity)
        assertTrue(warning.message.contains("ghost_table"))
    }

    @Test
    fun `foreign key to an unknown column of a known table is an error`() {
        val sql = """
            CREATE TABLE account (
                id BIGINT NOT NULL,
                customer_type_id INTEGER,
                CONSTRAINT fk FOREIGN KEY (customer_type_id) REFERENCES customer_type (no_such_pk)
            );
        """.trimIndent()
        val error = validate(sql).single { it.severity == Severity.ERROR }
        assertTrue(error.message.contains("customer_type.no_such_pk"))
    }

    @Test
    fun `foreign key resolving to a table in the same file is clean`() {
        val sql = """
            CREATE TABLE parent_ref (id INTEGER NOT NULL PRIMARY KEY);
            CREATE TABLE child_ref (
                id INTEGER NOT NULL,
                parent_id INTEGER REFERENCES parent_ref (id)
            );
        """.trimIndent()
        assertTrue(validate(sql).none { it.category == ProblemCategory.SCHEMA })
    }

    @Test
    fun `unparseable create table is flagged instead of silently vanishing`() {
        val sql = "CREATE TABLE broken_table WITHOUT parens;"
        val warning = validate(sql).single { it.category == ProblemCategory.SCHEMA }
        assertEquals(Severity.WARNING, warning.severity)
        assertTrue(warning.message.contains("could not be parsed"))
    }

    @Test
    fun `valid ddl produces no findings`() {
        val sql = """
            CREATE TABLE account (
                id BIGINT NOT NULL,
                customer_type_id INTEGER,
                CONSTRAINT pk_account PRIMARY KEY (id),
                CONSTRAINT fk_ct FOREIGN KEY (customer_type_id) REFERENCES customer_type (id)
            );
            CREATE UNIQUE INDEX ux_account_id ON account (id);
        """.trimIndent()
        assertEquals(emptyList<ValidationProblem>(), validate(sql))
    }

    @Test
    fun `fk checks can be disabled`() {
        val sql = "CREATE TABLE t (ghost_id INTEGER REFERENCES ghost_table (id));"
        val off = validate(sql, ValidationOptions(validateForeignKeys = false))
        assertTrue(off.none { it.message.contains("ghost_table") })
    }
}
