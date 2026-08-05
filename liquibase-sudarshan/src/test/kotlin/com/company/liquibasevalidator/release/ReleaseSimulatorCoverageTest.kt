package com.company.liquibasevalidator.release

import com.company.liquibasevalidator.liquibase.LiquibaseParser
import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.sql.CreateTableStatement
import com.company.liquibasevalidator.sql.SqlParser
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coverage-focused companion to [ReleaseSimulatorTest]: exercises the error branches,
 * elvis fall-throughs and policy toggles the main test leaves untouched.
 */
class ReleaseSimulatorCoverageTest {

    @TempDir
    lateinit var repo: Path

    // -------------------------------------------------------------------------------------
    // helpers (same conventions as ReleaseSimulatorTest)
    // -------------------------------------------------------------------------------------

    private fun file(relativePath: String, content: String) {
        val path = repo.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content.trimIndent())
    }

    private fun simulate(
        country: String = "IN",
        environment: String = "SIT",
        config: ReleaseConfig = ReleaseConfig(),
        baseSchema: Map<String, TableSchema> = emptyMap(),
    ): ReleaseSimulator.Result =
        ReleaseSimulator(config = config).simulate(repo, country, environment, baseSchema)

    private fun problems(result: ReleaseSimulator.Result): List<ValidationProblem> =
        result.reports.flatMap { it.problems }

    private fun assertProblem(result: ReleaseSimulator.Result, severity: Severity, fragment: String) {
        val match = problems(result).filter { it.severity == severity && it.message.contains(fragment) }
        assertTrue(
            match.isNotEmpty(),
            "expected a $severity containing '$fragment'; got:\n" +
                problems(result).joinToString("\n") { "  ${it.severity} ${it.message}" },
        )
    }

    private fun assertNoProblem(result: ReleaseSimulator.Result, fragment: String) {
        val match = problems(result).filter { it.message.contains(fragment) }
        assertTrue(match.isEmpty(), "expected NO finding containing '$fragment'; got: $match")
    }

    /** Builds a [TableSchema] for the baseSchema parameter from a single CREATE TABLE. */
    private fun tableSchema(ddl: String): TableSchema {
        val create = SqlParser.parse(ddl.trimIndent()).statementsOf<CreateTableStatement>().single()
        return DdlSchemaBuilder.toTableSchema(create, "<base>")
    }

    /** Runs the policy guardrails directly on one parsed SQL text. */
    private fun policyProblems(config: ReleaseConfig, environment: String, sql: String): List<ValidationProblem> {
        val text = sql.trimIndent()
        val problems = mutableListOf<ValidationProblem>()
        PolicyChecks.apply(config, environment, SqlParser.parse(text), LiquibaseParser.parse(text).changesets, problems)
        return problems
    }

    private val refDataDdl = """
        --liquibase formatted sql

        --changeset t:ddl-001 labels:ddl
        CREATE TABLE ref_data (
            code VARCHAR(10) NOT NULL,
            name VARCHAR(50) NOT NULL,
            CONSTRAINT pk_ref_data PRIMARY KEY (code)
        );
        --rollback DROP TABLE ref_data;
    """

    // -------------------------------------------------------------------------------------
    // assembler edge cases
    // -------------------------------------------------------------------------------------

    @Test
    fun `availableCountries is empty when the country root directory does not exist`() {
        val assembler = ReleaseAssembler(repo, ReleaseConfig())
        assertEquals(emptyList<String>(), assembler.availableCountries())
    }

    // -------------------------------------------------------------------------------------
    // schema accumulation edge cases
    // -------------------------------------------------------------------------------------

    @Test
    fun `insert into a table never defined anywhere warns unknown table but never used-before`() {
        file(
            "database/global/staticdatasetup/ghost.sql",
            """
            --liquibase formatted sql

            --changeset t:g-001
            INSERT INTO ghost_table (code) VALUES ('A');
            --rollback DELETE FROM ghost_table WHERE code = 'A';
            """,
        )
        val result = simulate()
        assertProblem(result, Severity.WARNING, "unknown table 'ghost_table'")
        assertNoProblem(result, "used before its DDL runs")
        assertEquals(0, result.errorCount)
    }

    @Test
    fun `baseSchema tables are known from the very first file of the run`() {
        file(
            "database/global/staticdatasetup/seed.sql",
            """
            --liquibase formatted sql

            --changeset t:s-001
            INSERT INTO ref_data (code, name) VALUES ('A', 'a');
            --rollback DELETE FROM ref_data WHERE code = 'A';
            """,
        )
        val base = mapOf(
            "ref_data" to tableSchema(
                """
                CREATE TABLE ref_data (
                    code VARCHAR(10) NOT NULL,
                    name VARCHAR(50) NOT NULL,
                    CONSTRAINT pk_ref_data PRIMARY KEY (code)
                );
                """,
            ),
        )
        val result = simulate(baseSchema = base)
        assertNoProblem(result, "unknown table")
        assertNoProblem(result, "used before its DDL runs")
        assertEquals(0, result.errorCount)
    }

    @Test
    fun `creating a table that already exists in the base schema is defined-more-than-once`() {
        file("database/global/ddl/001_ref.sql", refDataDdl)
        val base = mapOf(
            "ref_data" to tableSchema(
                """
                CREATE TABLE ref_data (
                    code VARCHAR(10) NOT NULL,
                    CONSTRAINT pk_ref_data PRIMARY KEY (code)
                );
                """,
            ),
        )
        val result = simulate(baseSchema = base)
        assertProblem(result, Severity.ERROR, "defined more than once")
        assertProblem(result, Severity.ERROR, "(first in step 1")
        assertTrue(result.errorCount >= 1)
    }

    @Test
    fun `merge target and named source created by a later file are used-before errors`() {
        file(
            "database/global/staticdatasetup/merge_first.sql",
            """
            --liquibase formatted sql

            --changeset t:m-001
            MERGE INTO tgt t USING src s ON (t.id = s.id)
            WHEN MATCHED THEN UPDATE SET t.name = s.name;
            --rollback SELECT 1;

            --changeset t:m-002
            MERGE INTO tgt t2 USING (SELECT 1 AS id) s2 ON (t2.id = s2.id)
            WHEN MATCHED THEN UPDATE SET t2.name = 'x';
            --rollback SELECT 1;
            """,
        )
        file(
            "database/global/update/001_late_tables.sql",
            """
            --liquibase formatted sql

            --changeset t:m-010
            CREATE TABLE tgt (
                id INTEGER NOT NULL,
                name VARCHAR(20),
                CONSTRAINT pk_tgt PRIMARY KEY (id)
            );
            --rollback DROP TABLE tgt;

            --changeset t:m-011
            CREATE TABLE src (
                id INTEGER NOT NULL,
                name VARCHAR(20),
                CONSTRAINT pk_src PRIMARY KEY (id)
            );
            --rollback DROP TABLE src;
            """,
        )
        val result = simulate()
        assertProblem(result, Severity.ERROR, "table 'tgt' is used before its DDL runs")
        assertProblem(result, Severity.ERROR, "table 'src' is used before its DDL runs")
    }

    @Test
    fun `inline column REFERENCES to a later table is a used-before error but self-reference is not`() {
        file(
            "database/global/ddl/001_child.sql",
            """
            --liquibase formatted sql

            --changeset t:c-001
            CREATE TABLE child (
                id INTEGER NOT NULL,
                parent_id INTEGER REFERENCES parent (id),
                boss_id INTEGER REFERENCES child (id),
                CONSTRAINT pk_child PRIMARY KEY (id)
            );
            --rollback DROP TABLE child;
            """,
        )
        file(
            "database/global/ddl/002_parent.sql",
            """
            --liquibase formatted sql

            --changeset t:c-002
            CREATE TABLE parent (
                id INTEGER NOT NULL,
                CONSTRAINT pk_parent PRIMARY KEY (id)
            );
            --rollback DROP TABLE parent;
            """,
        )
        val result = simulate()
        assertProblem(result, Severity.ERROR, "table 'parent' is used before its DDL runs")
        assertNoProblem(result, "table 'child' is used before")
    }

    @Test
    fun `alter table add constraint on an existing table feeds the cross-file unique key check`() {
        file(
            "database/global/ddl/001_audit.sql",
            """
            --liquibase formatted sql

            --changeset t:a-001
            CREATE TABLE audit_log (
                id INTEGER NOT NULL,
                ref VARCHAR(20) NOT NULL,
                CONSTRAINT pk_audit PRIMARY KEY (id, ref)
            );
            --rollback DROP TABLE audit_log;
            """,
        )
        file(
            "database/global/ddl/002_alter.sql",
            """
            --liquibase formatted sql

            --changeset t:a-002
            ALTER TABLE audit_log ADD CONSTRAINT uq_audit_ref UNIQUE (ref);
            --rollback SELECT 1;
            """,
        )
        file(
            "database/global/staticdatasetup/a.sql",
            """
            --liquibase formatted sql

            --changeset t:a-003
            INSERT INTO audit_log (id, ref) VALUES (1, 'R1');
            --rollback DELETE FROM audit_log WHERE id = 1;
            """,
        )
        file(
            "database/countries/IN/staticdatasetup/b.sql",
            """
            --liquibase formatted sql

            --changeset t:a-004
            INSERT INTO audit_log (id, ref) VALUES (2, 'R1');
            --rollback DELETE FROM audit_log WHERE id = 2;
            """,
        )
        val result = simulate()
        // the ALTER extended the accumulated schema: 'ref' became a single-column unique key
        assertProblem(result, Severity.ERROR, "key 'ref'='R1' of 'audit_log' is already inserted")
        assertNoProblem(result, "before the table exists")
    }

    @Test
    fun `create unique index adds a unique key while plain or ghost-table indexes are ignored`() {
        file(
            "database/global/ddl/001_colors.sql",
            """
            --liquibase formatted sql

            --changeset t:i-001
            CREATE TABLE colors (
                id INTEGER NOT NULL,
                name VARCHAR(30) NOT NULL
            );
            --rollback DROP TABLE colors;
            """,
        )
        file(
            "database/global/ddl/002_indexes.sql",
            """
            --liquibase formatted sql

            --changeset t:i-002
            CREATE UNIQUE INDEX ux_colors_name ON colors (name);
            --rollback DROP INDEX ux_colors_name;

            --changeset t:i-003
            CREATE INDEX ix_colors_id ON colors (id);
            --rollback DROP INDEX ix_colors_id;

            --changeset t:i-004
            CREATE UNIQUE INDEX ux_ghost ON ghost_table (x);
            --rollback DROP INDEX ux_ghost;
            """,
        )
        file(
            "database/global/staticdatasetup/a.sql",
            """
            --liquibase formatted sql

            --changeset t:i-005
            INSERT INTO colors (id, name) VALUES (1, 'red');
            --rollback DELETE FROM colors WHERE id = 1;
            """,
        )
        file(
            "database/countries/IN/staticdatasetup/b.sql",
            """
            --liquibase formatted sql

            --changeset t:i-006
            INSERT INTO colors (id, name) VALUES (2, 'red');
            --rollback DELETE FROM colors WHERE id = 2;
            """,
        )
        val result = simulate()
        // the unique index became a key of 'colors', so the duplicate 'red' is caught
        assertProblem(result, Severity.ERROR, "key 'name'='red' of 'colors' is already inserted")
        // errorCount aggregates exactly the ERROR problems of every report
        assertEquals(problems(result).count { it.severity == Severity.ERROR }, result.errorCount)
        assertTrue(result.errorCount >= 1)
    }

    // -------------------------------------------------------------------------------------
    // cross-file direct-insert dedupe branches
    // -------------------------------------------------------------------------------------

    @Test
    fun `tables with only a composite unique key are skipped by the cross-file dedupe`() {
        file(
            "database/global/ddl/001_pairs.sql",
            """
            --liquibase formatted sql

            --changeset t:p-001
            CREATE TABLE pairs (
                a INTEGER NOT NULL,
                b INTEGER NOT NULL,
                CONSTRAINT pk_pairs PRIMARY KEY (a, b)
            );
            --rollback DROP TABLE pairs;
            """,
        )
        file(
            "database/global/staticdatasetup/a.sql",
            """
            --liquibase formatted sql

            --changeset t:p-002
            INSERT INTO pairs (a, b) VALUES (1, 2);
            --rollback DELETE FROM pairs WHERE a = 1;
            """,
        )
        file(
            "database/countries/IN/staticdatasetup/b.sql",
            """
            --liquibase formatted sql

            --changeset t:p-003
            INSERT INTO pairs (a, b) VALUES (1, 2);
            --rollback DELETE FROM pairs WHERE a = 1;
            """,
        )
        // only single-column keys participate; a composite PK collision is not reported
        assertNoProblem(simulate(), "already inserted in step")
    }

    @Test
    fun `positional insert resolves against table columns and keyless rows are skipped`() {
        file("database/global/ddl/001_ref.sql", refDataDdl)
        file(
            "database/global/staticdatasetup/a.sql",
            """
            --liquibase formatted sql

            --changeset t:q-001
            INSERT INTO ref_data VALUES ('A', 'global');
            --rollback DELETE FROM ref_data WHERE code = 'A';

            --changeset t:q-002
            INSERT INTO ref_data (name) VALUES ('keyless');
            --rollback SELECT 1;
            """,
        )
        file(
            "database/countries/IN/staticdatasetup/b.sql",
            """
            --liquibase formatted sql

            --changeset t:q-003
            INSERT INTO ref_data (code, name) VALUES ('A', 'country');
            --rollback DELETE FROM ref_data WHERE code = 'A';
            """,
        )
        val result = simulate()
        // the column-less insert was mapped positionally, so 'A' collides across files
        assertProblem(result, Severity.ERROR, "key 'code'='A' of 'ref_data' is already inserted")
        assertNoProblem(result, "='keyless'")
    }

    // -------------------------------------------------------------------------------------
    // staged MERGE key overlap (informational upsert path)
    // -------------------------------------------------------------------------------------

    @Test
    fun `staged merge keys overlapping an earlier stage or a direct insert are informational`() {
        file(
            "database/global/ddl/001_tables.sql",
            """
            --liquibase formatted sql

            --changeset t:st-001
            CREATE TABLE ref_data (
                code VARCHAR(10) NOT NULL,
                name VARCHAR(50) NOT NULL,
                CONSTRAINT pk_ref_data PRIMARY KEY (code)
            );
            --rollback DROP TABLE ref_data;

            --changeset t:st-002
            CREATE TABLE pairs (
                a INTEGER NOT NULL,
                b INTEGER NOT NULL,
                CONSTRAINT pk_pairs PRIMARY KEY (a, b)
            );
            --rollback DROP TABLE pairs;
            """,
        )
        file(
            "database/global/staticdatasetup/stage_a.sql",
            """
            --liquibase formatted sql

            --changeset t:st-010
            CREATE TEMP TABLE tmp_ref_data (
                code VARCHAR(10),
                name VARCHAR(50)
            );
            INSERT INTO tmp_ref_data (code, name) VALUES ('A', 'global');
            INSERT INTO tmp_ref_data VALUES ('B', 'global-b');
            INSERT INTO ref_data (code, name) VALUES ('D', 'direct');
            MERGE INTO ref_data t USING tmp_ref_data s ON (t.code = s.code)
            WHEN MATCHED THEN UPDATE SET t.name = s.name
            WHEN NOT MATCHED THEN INSERT (code, name) VALUES (s.code, s.name);
            --rollback DELETE FROM ref_data WHERE code IN ('A', 'B', 'D');
            """,
        )
        file(
            "database/countries/IN/staticdatasetup/stage_b.sql",
            """
            --liquibase formatted sql

            --changeset t:st-020
            CREATE TEMP TABLE tmp_ref_data (
                code VARCHAR(10),
                name VARCHAR(50)
            );
            INSERT INTO tmp_ref_data (code, name) VALUES ('A', 'india');
            INSERT INTO tmp_ref_data (code, name) VALUES (UPPER('X'), 'opaque');
            INSERT INTO tmp_ref_data (code, name) VALUES ('D', 'india-d');
            MERGE INTO ref_data t USING tmp_ref_data s ON (t.code = s.code)
            WHEN MATCHED THEN UPDATE SET t.name = s.name
            WHEN NOT MATCHED THEN INSERT (code, name) VALUES (s.code, s.name);
            MERGE INTO ghost_target g USING tmp_ref_data s2 ON (g.code = s2.code)
            WHEN MATCHED THEN UPDATE SET g.name = s2.name;
            MERGE INTO pairs p USING tmp_ref_data s3 ON (p.a = s3.code)
            WHEN MATCHED THEN UPDATE SET p.b = s3.name;
            MERGE INTO ref_data t2 USING tmp_ref_data s4 ON (t2.code = 'Z')
            WHEN MATCHED THEN UPDATE SET t2.name = s4.name;
            --rollback DELETE FROM ref_data WHERE code = 'A';
            """,
        )
        val result = simulate()

        // 'A' was staged by the global file first: MERGE-overlap is INFO, not an error
        assertProblem(result, Severity.INFO, "'code'='A' of 'ref_data' also comes from step")
        // 'D' was DIRECT-inserted by the global file: the fallback lookup also reports INFO
        assertProblem(result, Severity.INFO, "'code'='D' of 'ref_data' also comes from step")
        // 'B' is only staged once — no overlap
        assertNoProblem(result, "'code'='B' of 'ref_data' also comes")

        val countryReport = result.reports.first { it.entry.path.fileName.toString() == "stage_b.sql" }
        assertTrue(
            countryReport.problems.any {
                it.severity == Severity.INFO && it.message.contains("also comes from step")
            },
            "expected the overlap INFO to be anchored in the country file",
        )
        // ghost target, composite-key target and literal-ON merges are silently skipped
        assertNoProblem(result, "of 'ghost_target' also comes")
        assertNoProblem(result, "of 'pairs' also comes")
    }

    // -------------------------------------------------------------------------------------
    // policy guardrail branches
    // -------------------------------------------------------------------------------------

    @Test
    fun `destructive statement outside any changeset is still flagged`() {
        val problems = policyProblems(ReleaseConfig(), "SIT", "TRUNCATE TABLE audit;")
        assertTrue(
            problems.any {
                it.severity == Severity.WARNING && it.message.contains("TRUNCATE 'audit' is destructive")
            },
            "got: $problems",
        )
    }

    @Test
    fun `disabled nonprod destructive and rollback policies stay silent`() {
        val config = ReleaseConfig(
            policies = ReleasePolicies(nonprodDestructive = null, nonprodMissingRollback = null),
        )
        val problems = policyProblems(
            config, "SIT",
            """
            --liquibase formatted sql

            --changeset t:off-001
            TRUNCATE TABLE ref_data;
            """,
        )
        assertTrue(problems.isEmpty(), "expected no policy findings; got: $problems")
    }

    @Test
    fun `failOnError false and runAlways true on DDL-only changesets are not flagged in PROD`() {
        val problems = policyProblems(
            ReleaseConfig(), "PROD",
            """
            --liquibase formatted sql

            --changeset t:np-001 failOnError:false
            CREATE TABLE cfg_only (
                id INTEGER NOT NULL,
                CONSTRAINT pk_cfg PRIMARY KEY (id)
            );
            --rollback DROP TABLE cfg_only;

            --changeset t:np-002 runAlways:true
            CREATE TABLE cfg_two (
                id INTEGER NOT NULL,
                CONSTRAINT pk_cfg2 PRIMARY KEY (id)
            );
            --rollback DROP TABLE cfg_two;
            """,
        )
        assertTrue(problems.isEmpty(), "expected no policy findings for non-DML changesets; got: $problems")
    }

    @Test
    fun `disabled failOnError and runAlways policies stay silent on DML changesets`() {
        val config = ReleaseConfig(
            policies = ReleasePolicies(prodFailOnErrorFalse = null, runAlwaysDml = null),
        )
        val problems = policyProblems(
            config, "PROD",
            """
            --liquibase formatted sql

            --changeset t:off-101 failOnError:false runAlways:true
            INSERT INTO t1 (a) VALUES (1);
            --rollback DELETE FROM t1 WHERE a = 1;
            """,
        )
        assertTrue(problems.isEmpty(), "expected no policy findings; got: $problems")
    }

    // -------------------------------------------------------------------------------------
    // config loader edge parsing
    // -------------------------------------------------------------------------------------

    @Test
    fun `severity values warning and info parse and absent list keys keep defaults`() {
        file(
            ".liquibase-sudarshan.yml",
            """
            policy.prod.destructive: warning
            policy.prod.missingRollback: info
            """,
        )
        val loaded = ReleaseConfigLoader.load(repo)
        assertTrue(loaded.fromFile)
        assertEquals(Severity.WARNING, loaded.config.policies.prodDestructive)
        assertEquals(Severity.INFO, loaded.config.policies.prodMissingRollback)
        // untouched severities and the environments list keep their defaults
        assertEquals(Severity.WARNING, loaded.config.policies.prodFailOnErrorFalse)
        assertEquals(Severity.WARNING, loaded.config.policies.nonprodDestructive)
        assertEquals(listOf("SIT", "UAT"), loaded.config.environments)
        assertTrue(loaded.warnings.isEmpty(), "got: ${loaded.warnings}")
    }

    @Test
    fun `environments value of only commas yields an empty list leaving PROD alone`() {
        file(
            ".liquibase-sudarshan.yml",
            """
            environments: ,
            """,
        )
        val loaded = ReleaseConfigLoader.load(repo)
        assertEquals(emptyList<String>(), loaded.config.environments)
        assertEquals(listOf("PROD"), loaded.config.allEnvironments())
    }

    // -------------------------------------------------------------------------------------
    // SqlFileOrder comparator branches
    // -------------------------------------------------------------------------------------

    @Test
    fun `equal numeric prefixes fall back to case-insensitive name comparison`() {
        assertTrue(SqlFileOrder.compare("1_A.sql", "1_b.sql") < 0)
        assertTrue(SqlFileOrder.compare("1_b.sql", "1_A.sql") > 0)
        assertEquals(0, SqlFileOrder.compare("A.sql", "a.sql"))
        assertEquals(
            listOf("1_A.sql", "1_b.sql", "1_C.sql"),
            listOf("1_C.sql", "1_b.sql", "1_A.sql").sortedWith(SqlFileOrder),
        )
    }

    @Test
    fun `numeric prefix overflowing Long is treated as unnumbered`() {
        // 20 digits do not fit in a Long, so toLongOrNull() is null and the file sorts last
        assertTrue(SqlFileOrder.compare("99999999999999999999_late.sql", "2_early.sql") > 0)
        assertTrue(SqlFileOrder.compare("2_early.sql", "99999999999999999999_late.sql") < 0)
        // two overflowing prefixes fall back to plain case-insensitive comparison
        assertTrue(SqlFileOrder.compare("88888888888888888888_a.sql", "99999999999999999999_b.sql") < 0)
    }
}
