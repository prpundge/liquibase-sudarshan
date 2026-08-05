package com.company.liquibasevalidator

import com.company.liquibasevalidator.database.DatabaseSession
import com.company.liquibasevalidator.liquibase.LiquibaseParser
import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.schema.TableSchema
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.company.liquibasevalidator.validation.ValidationOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Remaining engine/parser/schema edge branches. */
class EngineEdgeCoverageTest {

    private val engine = ValidationEngine(ValidationOptions())

    private val schema = MapSchemaProvider(
        DdlSchemaBuilder.build(
            listOf(
                DdlSchemaBuilder.DdlSource(
                    "ddl.sql",
                    "CREATE TABLE known_tab (code VARCHAR(10) NOT NULL, CONSTRAINT pk_k PRIMARY KEY (code));",
                ),
            ),
        ),
    )

    @Test
    fun `malformed liquibase header problem carries a replace quick fix`() {
        val problems = engine.validate("--liquibase formated sql\nSELECT 1;\n", schema).problems
        val header = problems.single { it.message.contains("malformed header") }
        assertEquals(Severity.ERROR, header.severity)
        assertTrue(header.fixes.isNotEmpty(), "expected the header problem to carry a quick fix")
    }

    @Test
    fun `unresolved schema is reported once via the first merge target`() {
        val problems = engine.validate(
            "MERGE INTO t USING s ON t.a = s.a WHEN MATCHED THEN UPDATE SET b = s.b;",
            MapSchemaProvider.UNRESOLVED,
        ).problems
        assertTrue(problems.any { it.message.contains("unable to resolve the target table schema") })
    }

    @Test
    fun `unknown statement keyword with no close suggestion has no quick fix`() {
        val problems = engine.validate("QWZXKJHT INTO known_tab VALUES ('X');", schema).problems
        val unknown = problems.single { it.message.contains("unknown SQL statement keyword 'QWZXKJHT'") }
        assertTrue(unknown.fixes.isEmpty())
        assertTrue(!unknown.message.contains("did you mean"))
    }

    @Test
    fun `alter-added foreign key to a table nobody defines`() {
        val problems = engine.validate(
            "ALTER TABLE known_tab ADD CONSTRAINT fk_g FOREIGN KEY (code) REFERENCES ghost_ref (id);",
            schema,
        ).problems
        assertTrue(
            problems.any { it.message.contains("ghost_ref") },
            "expected an unknown-FK-target finding; got: $problems",
        )
    }

    @Test
    fun `changeset header with missing author or id`() {
        for (header in listOf("--changeset badheader:", "--changeset :lonely-id")) {
            val parsed = LiquibaseParser.parse("--liquibase formatted sql\n$header\nSELECT 1;\n")
            assertTrue(
                parsed.problems.any { it.message.contains("author and id must both be present") },
                "expected an author/id error for '$header'",
            )
            assertTrue(parsed.changesets.isEmpty())
        }
    }

    @Test
    fun `map schema provider exposes all tables`() {
        val tables: Map<String, TableSchema> = schema.allTables()
        assertEquals(setOf("known_tab"), tables.keys)
    }

    @Test
    fun `database session row lookup defaults to null when not implemented`() {
        val minimal = object : DatabaseSession {
            override fun close() {}
            override fun fetchTables(): Map<String, TableSchema> = emptyMap()
            override fun executedChangesets(): Set<String>? = null
            override fun scalarSelect(sql: String): String? = null
            override fun rowExists(table: String, column: String, value: String): Boolean? = null
        }
        assertNull(minimal.selectRowByKey("t", "c", "v"))
    }
}
