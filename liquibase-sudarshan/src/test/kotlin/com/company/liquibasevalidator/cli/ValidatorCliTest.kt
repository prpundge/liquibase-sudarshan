package com.company.liquibasevalidator.cli

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The command-line validator end to end, in-process: [ValidatorCli.execute] returns the
 * exit code (0 clean / 1 findings / 2 usage) instead of killing the JVM, so every branch
 * — including the failure paths — is exercised here.
 */
class ValidatorCliTest {

    @TempDir
    lateinit var repo: Path

    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream
    private lateinit var out: ByteArrayOutputStream
    private lateinit var err: ByteArrayOutputStream

    private val stdout: String get() = out.toString(StandardCharsets.UTF_8)
    private val stderr: String get() = err.toString(StandardCharsets.UTF_8)

    @BeforeEach
    fun captureConsole() {
        originalOut = System.out
        originalErr = System.err
        out = ByteArrayOutputStream()
        err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(err, true, StandardCharsets.UTF_8))
    }

    @AfterEach
    fun restoreConsole() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    // -----------------------------------------------------------------------------------

    private fun write(relative: String, text: String): Path {
        val file = repo.resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray(StandardCharsets.UTF_8))
        return file
    }

    private fun validRepo() {
        write(
            "database/global/ddl/account_type.sql",
            """
            --liquibase formatted sql
            --changeset team:001-ddl
            CREATE TABLE account_type (
                code VARCHAR(15) NOT NULL PRIMARY KEY,
                name VARCHAR(100) NOT NULL
            );
            --rollback DROP TABLE account_type;
            """.trimIndent(),
        )
        write(
            "database/global/staticdatasetup/seed.sql",
            """
            --liquibase formatted sql
            --changeset team:002-seed
            --comment: valid seed
            INSERT INTO account_type (code, name) VALUES ('SAVINGS', 'Savings');
            --rollback DELETE FROM account_type;
            """.trimIndent(),
        )
    }

    /** A data file whose value is too long for the column: one guaranteed ERROR. */
    private fun brokenData() {
        write(
            "database/global/staticdatasetup/broken.sql",
            """
            --liquibase formatted sql
            --changeset team:003-broken
            --comment: value exceeds VARCHAR(15)
            INSERT INTO account_type (code, name) VALUES ('WAY_TOO_LONG_ACCOUNT_CODE_VALUE', 'x');
            --rollback DELETE FROM account_type;
            """.trimIndent(),
        )
    }

    /**
     * A unified diff marking the offending INSERT (line 4 of broken.sql) as an added line,
     * so the patch filter keeps its finding instead of suppressing it.
     */
    private fun brokenFileDiff(): String = listOf(
        "diff --git a/database/global/staticdatasetup/broken.sql b/database/global/staticdatasetup/broken.sql",
        "--- a/database/global/staticdatasetup/broken.sql",
        "+++ b/database/global/staticdatasetup/broken.sql",
        "@@ -1,4 +1,5 @@",
        " --liquibase formatted sql",
        " --changeset team:003-broken",
        " --comment: value exceeds VARCHAR(15)",
        "+INSERT INTO account_type (code, name) VALUES ('WAY_TOO_LONG_ACCOUNT_CODE_VALUE', 'x');",
        " --rollback DELETE FROM account_type;",
    ).joinToString("\n")

    // -----------------------------------------------------------------------------------
    // Exit codes
    // -----------------------------------------------------------------------------------

    @Test
    fun `a clean repository exits zero`() {
        validRepo()

        assertEquals(0, ValidatorCli.execute(arrayOf(repo.toString())))
        assertTrue(stdout.contains("2 file(s) scanned, 0 error(s)"), stdout)
    }

    @Test
    fun `findings exit with code one`() {
        validRepo()
        brokenData()

        assertEquals(1, ValidatorCli.execute(arrayOf(repo.toString())))
        assertTrue(stdout.contains("error(s)"), stdout)
    }

    @Test
    fun `a missing repository root exits two`() {
        assertEquals(2, ValidatorCli.execute(arrayOf(repo.resolve("nope").toString())))
        assertTrue(stderr.contains("repository root not found"), stderr)
    }

    @Test
    fun `a repository without a ddl directory exits two`() {
        write("notes/readme.txt", "nothing to validate")

        assertEquals(2, ValidatorCli.execute(arrayOf(repo.toString())))
        assertTrue(stderr.contains("no DDL directory found"), stderr)
    }

    @Test
    fun `an explicit ddl directory overrides detection`() {
        write("custom/ddl/a.sql", "CREATE TABLE a (id INT);")

        assertEquals(0, ValidatorCli.execute(arrayOf(repo.toString(), "--ddl=custom/ddl")))
    }

    @Test
    fun `fail-on-warnings turns a warning-only run into exit one`() {
        validRepo()
        // unknown directive: a warning, never an error
        write(
            "database/global/staticdatasetup/warn.sql",
            """
            --liquibase formatted sql
            --changeset team:004-warn
            --commnt: typo in the comment directive
            INSERT INTO account_type (code, name) VALUES ('A', 'a');
            --rollback DELETE FROM account_type;
            """.trimIndent(),
        )

        val plain = ValidatorCli.execute(arrayOf(repo.toString()))
        val strict = ValidatorCli.execute(arrayOf(repo.toString(), "--fail-on-warnings"))

        assertEquals(0, plain)
        assertEquals(1, strict)
    }

    @Test
    fun `the oracle flag treats an empty string as null`() {
        write(
            "database/global/ddl/t.sql",
            "CREATE TABLE t (code VARCHAR(10) NOT NULL PRIMARY KEY, name VARCHAR(10) NOT NULL);",
        )
        write(
            "database/global/staticdatasetup/d.sql",
            """
            --liquibase formatted sql
            --changeset team:005
            --comment: empty string into a NOT NULL column
            INSERT INTO t (code, name) VALUES ('A', '');
            --rollback DELETE FROM t;
            """.trimIndent(),
        )

        assertEquals(0, ValidatorCli.execute(arrayOf(repo.toString())))
        assertEquals(1, ValidatorCli.execute(arrayOf(repo.toString(), "--oracle")))
    }

    // -----------------------------------------------------------------------------------
    // Output formats
    // -----------------------------------------------------------------------------------

    @Test
    fun `github mode emits workflow command annotations`() {
        validRepo()
        brokenData()

        ValidatorCli.execute(arrayOf(repo.toString(), "--github"))

        assertTrue(stdout.contains("::error file="), stdout)
        assertTrue(stdout.contains("line="), stdout)
    }

    // -----------------------------------------------------------------------------------
    // Patch filtering
    // -----------------------------------------------------------------------------------

    @Test
    fun `a missing patch file exits two`() {
        validRepo()

        assertEquals(2, ValidatorCli.execute(arrayOf(repo.toString(), "--patch=absent.diff")))
        assertTrue(stderr.contains("patch file not found"), stderr)
    }

    @Test
    fun `an empty patch suppresses every finding and warns`() {
        validRepo()
        brokenData()
        write("empty.diff", "")

        assertEquals(0, ValidatorCli.execute(arrayOf(repo.toString(), "--patch=empty.diff")))
        assertTrue(stderr.contains("no file changes"), stderr)
    }

    @Test
    fun `a patch touching the broken file keeps its findings`() {
        validRepo()
        brokenData()
        write("changes.diff", brokenFileDiff())

        assertEquals(1, ValidatorCli.execute(arrayOf(repo.toString(), "--patch=changes.diff")))
    }

    // -----------------------------------------------------------------------------------
    // Simulation
    // -----------------------------------------------------------------------------------

    @Test
    fun `simulate without country and env exits two`() {
        validRepo()

        assertEquals(2, ValidatorCli.execute(arrayOf(repo.toString(), "--simulate")))
        assertTrue(stderr.contains("--simulate requires"), stderr)
    }

    @Test
    fun `simulate reports the ordered manifest`() {
        validRepo()
        write(
            "database/countries/IN/staticdatasetup/in.sql",
            """
            --liquibase formatted sql
            --changeset team:in-001
            --comment: india seed
            INSERT INTO account_type (code, name) VALUES ('NRE', 'NRE');
            --rollback DELETE FROM account_type WHERE code = 'NRE';
            """.trimIndent(),
        )

        val code = ValidatorCli.execute(arrayOf(repo.toString(), "--simulate", "--country=IN", "--env=SIT"))

        assertEquals(0, code)
        assertTrue(stdout.contains("simulation [IN/SIT]"), stdout)
        assertTrue(stdout.contains("release would EXECUTE"), stdout)
    }

    @Test
    fun `simulate exits one when the release would fail`() {
        validRepo()
        brokenData()

        val code = ValidatorCli.execute(arrayOf(repo.toString(), "--simulate", "--country=IN", "--env=SIT"))

        assertEquals(1, code)
        assertTrue(stdout.contains("release would FAIL"), stdout)
    }

    // -----------------------------------------------------------------------------------
    // Database options
    // -----------------------------------------------------------------------------------

    @Test
    fun `an unreachable datasource exits two`() {
        validRepo()

        val code = ValidatorCli.execute(
            arrayOf(
                repo.toString(),
                "--db-url=jdbc:postgresql://127.0.0.1:1/nope",
                "--db-user=none",
                "--db-password=none",
            ),
        )

        assertEquals(2, code)
        assertTrue(stderr.contains("dry run failed"), stderr)
    }

    @Test
    fun `simulate with an unreachable datasource exits two`() {
        validRepo()

        val code = ValidatorCli.execute(
            arrayOf(
                repo.toString(), "--simulate", "--country=IN", "--env=SIT",
                "--db-url=jdbc:postgresql://127.0.0.1:1/nope", "--db-user=none",
            ),
        )

        assertEquals(2, code)
        assertTrue(stderr.contains("cannot read live schema"), stderr)
    }

    // -----------------------------------------------------------------------------------
    // Bitbucket review
    // -----------------------------------------------------------------------------------

    @Test
    fun `an unparsable bitbucket url exits two`() {
        validRepo()

        assertEquals(2, ValidatorCli.execute(arrayOf(repo.toString(), "--bitbucket-pr=https://example.com/x")))
        assertTrue(stderr.contains("not a recognizable Bitbucket"), stderr)
    }

    @Test
    fun `a bitbucket server that returns a non-diff exits two`() {
        validRepo()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "<html>login</html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/projects/KEY/repos/slug/pull-requests/1"
            val code = ValidatorCli.execute(arrayOf(repo.toString(), "--bitbucket-pr=$url"))

            assertEquals(2, code)
            assertTrue(stderr.contains("did not return a unified diff"), stderr)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a bitbucket review previews the collected findings without posting`() {
        validRepo()
        brokenData()
        val diff = brokenFileDiff()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = diff.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/projects/KEY/repos/slug/pull-requests/7"
            val code = ValidatorCli.execute(arrayOf(repo.toString(), "--bitbucket-pr=$url"))

            assertEquals(1, code)
            assertTrue(stdout.contains("Bitbucket review for KEY/slug #7"), stdout)
            assertTrue(stdout.contains("preview only"), stdout)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `posting a review without a token exits two`() {
        validRepo()
        val diff = "diff --git a/x.sql b/x.sql\n--- a/x.sql\n+++ b/x.sql\n@@ -1 +1 @@\n+SELECT 1;"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = diff.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/projects/KEY/repos/slug/pull-requests/9"
            val code = ValidatorCli.execute(
                arrayOf(repo.toString(), "--bitbucket-pr=$url", "--bitbucket-post"),
            )

            assertEquals(2, code)
            assertTrue(stderr.contains("needs --bitbucket-token"), stderr)
        } finally {
            server.stop(0)
        }
    }
}
