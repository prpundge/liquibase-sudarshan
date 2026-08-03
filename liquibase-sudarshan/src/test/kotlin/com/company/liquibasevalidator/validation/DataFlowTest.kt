package com.company.liquibasevalidator.validation

import com.company.liquibasevalidator.schema.DdlSchemaBuilder
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.sql.SqlParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataFlowTest {

    private val schema = MapSchemaProvider(
        DdlSchemaBuilder.build(
            listOf(
                DdlSchemaBuilder.DdlSource(
                    "ddl.sql",
                    """
                    CREATE TABLE account_type (
                        code VARCHAR(15) NOT NULL PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        description VARCHAR(255),
                        active BOOLEAN NOT NULL
                    );
                    """.trimIndent(),
                ),
            ),
        ),
    )

    @Test
    fun `merge builds complete staging to target mapping`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(50) NOT NULL,
                name VARCHAR(255) NOT NULL,
                description VARCHAR(500),
                active BOOLEAN NOT NULL,
                extra_column VARCHAR(10)
            ) ON COMMIT DROP;

            MERGE INTO account_type AS target
            USING tmp_account_type AS source
            ON target.code = source.code
            WHEN MATCHED THEN
                UPDATE SET name = source.name, description = source.description, active = source.active
            WHEN NOT MATCHED THEN
                INSERT (code, name, description, active)
                VALUES (source.code, source.name, source.description, source.active);
        """.trimIndent()

        val flow = DataFlow.analyze(SqlParser.parse(sql), schema, ValidationOptions()).single()
        val mapping = flow.mappings.single()

        assertFalse(mapping.heuristic)
        assertEquals("account_type", mapping.targetTableName)
        assertEquals(setOf("code"), mapping.columnMap["code"])
        assertEquals(setOf("name"), mapping.columnMap["name"])
        assertEquals(setOf("description"), mapping.columnMap["description"])
        assertEquals(setOf("active"), mapping.columnMap["active"])
        assertTrue("extra_column" !in mapping.usedSourceColumns)
        assertEquals(setOf("code", "name", "description", "active"), mapping.usedSourceColumns)
    }

    @Test
    fun `heuristic maps tmp_ prefixed table when no merge exists`() {
        val sql = "CREATE TEMP TABLE tmp_account_type (code VARCHAR(50), unrelated INT);"
        val flow = DataFlow.analyze(SqlParser.parse(sql), schema, ValidationOptions()).single()
        val mapping = flow.mappings.single()

        assertTrue(mapping.heuristic)
        assertEquals("account_type", mapping.targetTableName)
        assertEquals(setOf("code"), mapping.columnMap["code"])
        assertTrue("unrelated" !in mapping.columnMap)
    }

    @Test
    fun `heuristic can be disabled`() {
        val sql = "CREATE TEMP TABLE tmp_account_type (code VARCHAR(50));"
        val options = ValidationOptions(tempTableNameHeuristic = false)
        val flow = DataFlow.analyze(SqlParser.parse(sql), schema, options).single()
        assertTrue(flow.mappings.isEmpty())
    }

    @Test
    fun `heuristic target name variants`() {
        assertEquals("account", DataFlow.heuristicTargetName("tmp_account"))
        assertEquals("account", DataFlow.heuristicTargetName("stg_account"))
        assertEquals("account", DataFlow.heuristicTargetName("account_tmp"))
        assertEquals(null, DataFlow.heuristicTargetName("account"))
    }

    @Test
    fun `inserts are associated with their staging table`() {
        val sql = """
            CREATE TEMP TABLE tmp_account_type (code VARCHAR(15));
            INSERT INTO tmp_account_type (code) VALUES ('A');
            INSERT INTO tmp_account_type (code) VALUES ('B');
            INSERT INTO other_table (code) VALUES ('C');
        """.trimIndent()
        val flow = DataFlow.analyze(SqlParser.parse(sql), schema, ValidationOptions()).single()
        assertEquals(2, flow.inserts.size)
    }
}
