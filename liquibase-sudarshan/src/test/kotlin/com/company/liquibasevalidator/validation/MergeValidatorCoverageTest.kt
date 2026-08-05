package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** MERGE validation against a REAL (repository-DDL) source table — the non-staging paths. */
class MergeValidatorCoverageTest {

    private val schema = MapSchemaProvider(
        DdlSchemaBuilder.build(
            listOf(
                DdlSchemaBuilder.DdlSource(
                    "ddl.sql",
                    """
                    CREATE TABLE target_tab (
                        code   VARCHAR(10) NOT NULL,
                        name   VARCHAR(10) NOT NULL,
                        qty    SMALLINT,
                        amount NUMERIC(5,2),
                        CONSTRAINT pk_target PRIMARY KEY (code)
                    );
                    CREATE TABLE source_tab (
                        code   VARCHAR(10) NOT NULL,
                        name   VARCHAR(10),
                        qty    INTEGER,
                        amount NUMERIC
                    );
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun problems(sql: String, options: ValidationOptions = ValidationOptions()): List<ValidationProblem> =
        ValidationEngine(options).validate(sql, schema).problems

    private fun merge(body: String): String =
        "MERGE INTO target_tab t\nUSING source_tab s\nON t.code = s.code\n$body"

    private fun assertProblem(problems: List<ValidationProblem>, fragment: String) {
        assertTrue(
            problems.any { it.message.contains(fragment) },
            "expected a problem containing '$fragment'; got:\n" +
                problems.joinToString("\n") { "  ${it.severity} ${it.message}" },
        )
    }

    @Test
    fun `insert clause column that is not in the target table`() {
        val problems = problems(merge("WHEN NOT MATCHED THEN INSERT (code, ghost) VALUES (s.code, s.name);"))
        assertProblem(problems, "column 'ghost' does not exist in target table 'target_tab'")
    }

    @Test
    fun `insert clause value count mismatch`() {
        val problems = problems(merge("WHEN NOT MATCHED THEN INSERT (code, name) VALUES (s.code);"))
        assertProblem(problems, "MERGE INSERT has 1 value(s) but 2 column(s)")
    }

    @Test
    fun `target-qualified reference to a missing column`() {
        val problems = problems(merge("WHEN NOT MATCHED THEN INSERT (code) VALUES (t.ghost);"))
        assertProblem(problems, "column 'ghost' does not exist in target table")
    }

    @Test
    fun `unqualified reference found in neither source nor target`() {
        val problems = problems(merge("WHEN NOT MATCHED THEN INSERT (code) VALUES (mystery_col);"))
        assertProblem(problems, "does not exist in source table 'source_tab' or target table 'target_tab'")
    }

    @Test
    fun `unknown table qualifier in a merge value`() {
        val problems = problems(merge("WHEN NOT MATCHED THEN INSERT (code) VALUES (x.code);"))
        assertProblem(problems, "unknown table qualifier 'x'")
    }

    @Test
    fun `null assigned to a not-null target column`() {
        val problems = problems(merge("WHEN MATCHED THEN UPDATE SET name = NULL;"))
        assertProblem(problems, "NULL is not allowed for column 'target_tab.name'")
    }

    @Test
    fun `real-table source datatype range mismatch`() {
        val problems = problems(merge("WHEN MATCHED THEN UPDATE SET qty = s.qty;"))
        assertProblem(problems, "MERGE source/target datatype mismatch for 'target_tab.qty'")
    }

    @Test
    fun `unconstrained numeric source against a constrained target`() {
        val problems = problems(merge("WHEN MATCHED THEN UPDATE SET amount = s.amount;"))
        assertProblem(problems, "unconstrained but target")
    }

    @Test
    fun `oracle empty string into a not-null column`() {
        val problems = problems(
            merge("WHEN MATCHED THEN UPDATE SET name = '';"),
            ValidationOptions(treatEmptyStringAsNull = true),
        )
        assertProblem(problems, "empty string '' is NULL in Oracle")
    }

    @Test
    fun `oversized literal assigned in the update clause`() {
        val problems = problems(merge("WHEN MATCHED THEN UPDATE SET name = 'far too long for ten chars';"))
        assertProblem(problems, "target_tab.name")
    }

    @Test
    fun `staged merge insert without a column list maps by target column order`() {
        val problems = problems(
            """
            CREATE TEMP TABLE tmp_target_tab (
                code   VARCHAR(10) NOT NULL,
                name   VARCHAR(10) NOT NULL,
                qty    SMALLINT,
                amount NUMERIC(5,2)
            ) ON COMMIT DROP;

            INSERT INTO tmp_target_tab (code, name, qty, amount)
            VALUES ('A', 'Alpha', 1, 9.99);

            MERGE INTO target_tab t
            USING tmp_target_tab s
            ON t.code = s.code
            WHEN MATCHED THEN
                UPDATE SET name = s.name
            WHEN NOT MATCHED THEN
                INSERT VALUES (s.code, s.name, s.qty, s.amount);
            """.trimIndent(),
        )
        // maps cleanly by position: no mapping errors expected from the insert clause
        assertTrue(problems.none { it.message.contains("MERGE INSERT has") }, problems.toString())
    }
}
