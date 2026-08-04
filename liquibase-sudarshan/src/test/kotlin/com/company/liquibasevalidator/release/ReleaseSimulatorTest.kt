package com.company.liquibasevalidator.release

import com.company.liquibasevalidator.liquibase.LiquibaseParser
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReleaseSimulatorTest {

    @TempDir
    lateinit var repo: Path

    // -------------------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------------------

    private fun file(relativePath: String, content: String) {
        val path = repo.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content.trimIndent())
    }

    private fun simulate(country: String = "IN", environment: String = "SIT"): ReleaseSimulator.Result =
        ReleaseSimulator().simulate(repo, country, environment)

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
    // ordering + configuration
    // -------------------------------------------------------------------------------------

    @Test
    fun `numeric prefixes sort numerically and before unnumbered files`() {
        val sorted = listOf("b.sql", "10_z.sql", "2_a.sql", "A.sql").sortedWith(SqlFileOrder)
        assertEquals(listOf("2_a.sql", "10_z.sql", "A.sql", "b.sql"), sorted)
    }

    @Test
    fun `missing config file falls back to full defaults`() {
        val loaded = ReleaseConfigLoader.load(repo)
        assertFalse(loaded.fromFile)
        assertEquals(ReleaseConfig(), loaded.config)
        assertTrue(loaded.warnings.isEmpty())
    }

    @Test
    fun `config file overrides layout and policies and warns on junk`() {
        file(
            ".liquibase-sudarshan.yml",
            """
            # comment line
            globalDdl: db/ddl
            environments: DEV, QA
            prodName: PRODUCTION
            policy.nonprod.destructive: error
            policy.runAlwaysDml: off
            policy.prod.destructive: bogus
            unknownKey: 42
            this line has no separator
            """,
        )
        val loaded = ReleaseConfigLoader.load(repo)
        assertTrue(loaded.fromFile)
        assertEquals("db/ddl", loaded.config.globalDdl)
        assertEquals(listOf("DEV", "QA", "PRODUCTION"), loaded.config.allEnvironments())
        assertTrue(loaded.config.isProd("production"))
        assertEquals(Severity.ERROR, loaded.config.policies.nonprodDestructive)
        assertNull(loaded.config.policies.runAlwaysDml)
        // bad severity value keeps the default
        assertEquals(Severity.ERROR, loaded.config.policies.prodDestructive)
        assertTrue(loaded.warnings.any { it.contains("unknown severity 'bogus'") })
        assertTrue(loaded.warnings.any { it.contains("unknown key 'unknownKey'") })
        assertTrue(loaded.warnings.any { it.contains("not a 'key: value' line") })
    }

    @Test
    fun `assembler runs the six stages in order and drops environment SQL for PROD`() {
        file("database/global/ddl/001_a.sql", "-- 1")
        file("database/global/staticdatasetup/s.sql", "-- 2")
        file("database/countries/IN/staticdatasetup/c.sql", "-- 3")
        file("database/global/update/u.sql", "-- 4")
        file("database/countries/IN/update/cu.sql", "-- 5")
        file("database/environments/SIT/e.sql", "-- 6")
        file("database/environments/SIT/countries/IN/ec.sql", "-- 7")
        file("database/environments/UAT/other.sql", "-- not selected")

        val assembler = ReleaseAssembler(repo, ReleaseConfig())
        assertEquals(listOf("IN"), assembler.availableCountries())

        val sit = assembler.assemble("IN", "SIT")
        assertEquals((1..7).toList(), sit.map { it.order })
        assertEquals(
            listOf(
                ReleaseStage.GLOBAL_DDL, ReleaseStage.GLOBAL_STATIC, ReleaseStage.COUNTRY_STATIC,
                ReleaseStage.GLOBAL_UPDATE, ReleaseStage.COUNTRY_UPDATE,
                ReleaseStage.ENVIRONMENT, ReleaseStage.ENVIRONMENT,
            ),
            sit.map { it.stage },
        )
        assertEquals(listOf("e.sql", "ec.sql"), sit.takeLast(2).map { it.path.fileName.toString() })

        val prod = assembler.assemble("IN", "PROD")
        assertTrue(prod.none { it.stage == ReleaseStage.ENVIRONMENT })
        assertEquals(5, prod.size)
    }

    // -------------------------------------------------------------------------------------
    // sequential schema accumulation
    // -------------------------------------------------------------------------------------

    @Test
    fun `table used before its DDL runs is an error`() {
        file(
            "database/global/staticdatasetup/seed.sql",
            """
            --liquibase formatted sql

            --changeset t:s-001
            INSERT INTO late_table (code) VALUES ('A');
            --rollback DELETE FROM late_table WHERE code = 'A';
            """,
        )
        file(
            "database/global/update/001_late.sql",
            """
            --liquibase formatted sql

            --changeset t:u-001
            CREATE TABLE late_table (
                code VARCHAR(10) NOT NULL,
                CONSTRAINT pk_late PRIMARY KEY (code)
            );
            --rollback DROP TABLE late_table;
            """,
        )
        assertProblem(simulate(), Severity.ERROR, "used before its DDL runs")
    }

    @Test
    fun `foreign key to a table created later in the run is an error`() {
        file(
            "database/global/ddl/001_child.sql",
            """
            --liquibase formatted sql

            --changeset t:ddl-child
            CREATE TABLE child (
                id INTEGER NOT NULL,
                parent_id INTEGER,
                CONSTRAINT pk_child PRIMARY KEY (id),
                CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES parent (id)
            );
            --rollback DROP TABLE child;
            """,
        )
        file(
            "database/global/ddl/002_parent.sql",
            """
            --liquibase formatted sql

            --changeset t:ddl-parent
            CREATE TABLE parent (
                id INTEGER NOT NULL,
                CONSTRAINT pk_parent PRIMARY KEY (id)
            );
            --rollback DROP TABLE parent;
            """,
        )
        val result = simulate()
        assertProblem(result, Severity.ERROR, "'parent' is used before its DDL runs")
        // the error is anchored in the child file (step 1), not the parent file
        assertTrue(
            result.reports.first { it.entry.path.fileName.toString() == "001_child.sql" }
                .problems.any { it.message.contains("used before its DDL runs") },
        )
    }

    @Test
    fun `defining the same table twice in one release is an error`() {
        file("database/global/ddl/001_ref.sql", refDataDdl)
        file(
            "database/global/ddl/002_ref_again.sql",
            """
            --liquibase formatted sql

            --changeset t:ddl-002
            CREATE TABLE ref_data (
                code VARCHAR(10) NOT NULL,
                CONSTRAINT pk_ref_data2 PRIMARY KEY (code)
            );
            --rollback DROP TABLE ref_data;
            """,
        )
        assertProblem(simulate(), Severity.ERROR, "defined more than once")
    }

    @Test
    fun `alter table before any create in the release is an error`() {
        file(
            "database/global/ddl/001_alter.sql",
            """
            --liquibase formatted sql

            --changeset t:ddl-alter
            ALTER TABLE ghost ADD CONSTRAINT pk_ghost PRIMARY KEY (id);
            --rollback SELECT 1;
            """,
        )
        assertProblem(simulate(), Severity.ERROR, "before the table exists")
    }

    @Test
    fun `drop removes the table so a re-create is not a duplicate definition`() {
        file("database/global/ddl/001_ref.sql", refDataDdl)
        file(
            "database/global/update/001_drop.sql",
            """
            --liquibase formatted sql

            --changeset t:u-001
            --approved-destructive DB-1
            DROP TABLE ref_data;
            --rollback SELECT 1;
            """,
        )
        file(
            "database/update/ignored.sql", // outside the configured layout: never picked up
            "not sql",
        )
        file(
            "database/countries/IN/update/001_recreate.sql",
            """
            --liquibase formatted sql

            --changeset t:u-002
            CREATE TABLE ref_data (
                code VARCHAR(10) NOT NULL,
                CONSTRAINT pk_ref_data PRIMARY KEY (code)
            );
            --rollback DROP TABLE ref_data;
            """,
        )
        assertNoProblem(simulate(), "defined more than once")
    }

    // -------------------------------------------------------------------------------------
    // cross-file checks
    // -------------------------------------------------------------------------------------

    @Test
    fun `duplicate changeset id across two files is an error`() {
        file("database/global/ddl/001_ref.sql", refDataDdl)
        file(
            "database/global/staticdatasetup/a.sql",
            """
            --liquibase formatted sql

            --changeset t:same-id
            INSERT INTO ref_data (code, name) VALUES ('A', 'a');
            --rollback DELETE FROM ref_data WHERE code = 'A';
            """,
        )
        file(
            "database/countries/IN/staticdatasetup/b.sql",
            """
            --liquibase formatted sql

            --changeset t:same-id
            INSERT INTO ref_data (code, name) VALUES ('B', 'b');
            --rollback DELETE FROM ref_data WHERE code = 'B';
            """,
        )
        assertProblem(simulate(), Severity.ERROR, "already declared in step")
    }

    @Test
    fun `same primary key inserted by two files is an error`() {
        file("database/global/ddl/001_ref.sql", refDataDdl)
        file(
            "database/global/staticdatasetup/global.sql",
            """
            --liquibase formatted sql

            --changeset t:g-001
            INSERT INTO ref_data (code, name) VALUES ('A', 'global');
            --rollback DELETE FROM ref_data WHERE code = 'A';
            """,
        )
        file(
            "database/countries/IN/staticdatasetup/country.sql",
            """
            --liquibase formatted sql

            --changeset t:c-001
            INSERT INTO ref_data (code, name) VALUES ('A', 'country');
            --rollback DELETE FROM ref_data WHERE code = 'A';
            """,
        )
        assertProblem(simulate(), Severity.ERROR, "already inserted in step")
    }

    // -------------------------------------------------------------------------------------
    // environment policies
    // -------------------------------------------------------------------------------------

    private fun policyFixture() {
        file("database/global/ddl/001_ref.sql", refDataDdl)
        file(
            "database/global/update/001_policies.sql",
            """
            --liquibase formatted sql

            --changeset t:pol-001
            TRUNCATE TABLE ref_data;
            --rollback SELECT 1;

            --changeset t:pol-002
            --approved-destructive DB-99
            DELETE FROM ref_data;
            --rollback SELECT 1;

            --changeset t:pol-003 runAlways:true
            INSERT INTO ref_data (code, name) VALUES ('Z', 'z');
            --rollback DELETE FROM ref_data WHERE code = 'Z';

            --changeset t:pol-004 failOnError:false
            UPDATE ref_data SET name = 'x' WHERE code = 'Z';
            --rollback SELECT 1;

            --changeset t:pol-005
            DROP TABLE tmp_ref_data;
            --rollback SELECT 1;

            --changeset t:pol-006
            INSERT INTO ref_data (code, name) VALUES ('Y', 'y');
            """,
        )
    }

    @Test
    fun `policies in SIT - destructive and missing rollback are warnings`() {
        policyFixture()
        val result = simulate(environment = "SIT")
        assertProblem(result, Severity.WARNING, "TRUNCATE 'ref_data' is destructive")
        assertProblem(result, Severity.INFO, "approved for SIT (--approved-destructive DB-99)")
        assertProblem(result, Severity.WARNING, "runAlways:true on DML changeset 't:pol-003'")
        assertProblem(result, Severity.WARNING, "changeset 't:pol-006' has no rollback")
        // staging-named drops are exempt; failOnError:false only matters in PROD
        assertNoProblem(result, "tmp_ref_data")
        assertNoProblem(result, "failOnError:false")
    }

    @Test
    fun `policies in PROD - destructive and missing rollback become errors`() {
        policyFixture()
        val result = simulate(environment = "PROD")
        assertProblem(result, Severity.ERROR, "TRUNCATE 'ref_data' is destructive")
        assertProblem(result, Severity.ERROR, "changeset 't:pol-006' has no rollback")
        assertProblem(result, Severity.WARNING, "failOnError:false")
        // the approved marker still downgrades to INFO even in PROD
        assertProblem(result, Severity.INFO, "approved for PROD (--approved-destructive DB-99)")
        assertNoProblem(result, "DELETE without WHERE empties 'ref_data' — add")
    }

    @Test
    fun `unfiltered update and delete are destructive`() {
        file("database/global/ddl/001_ref.sql", refDataDdl)
        file(
            "database/global/update/001_unfiltered.sql",
            """
            --liquibase formatted sql

            --changeset t:u-001
            UPDATE ref_data SET name = 'x';
            --rollback SELECT 1;

            --changeset t:u-002
            DELETE FROM ref_data;
            --rollback SELECT 1;
            """,
        )
        val result = simulate(environment = "UAT")
        assertProblem(result, Severity.WARNING, "UPDATE without WHERE rewrites every row")
        assertProblem(result, Severity.WARNING, "DELETE without WHERE empties")
    }

    // -------------------------------------------------------------------------------------
    // directive parsing used by the policies
    // -------------------------------------------------------------------------------------

    @Test
    fun `approved-destructive directive attaches its ticket to the changeset`() {
        val parsed = LiquibaseParser.parse(
            """
            --liquibase formatted sql

            --changeset t:x-001
            --approved-destructive DB-1234
            DROP TABLE old_stuff;
            --rollback SELECT 1;

            --changeset t:x-002
            SELECT 1;
            """.trimIndent(),
        )
        assertEquals("DB-1234", parsed.changesets[0].approvedDestructive)
        assertNull(parsed.changesets[1].approvedDestructive)
    }
}
