package com.company.liquibasevalidator.schema

import com.company.liquibasevalidator.sql.SqlDataType
import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.sql.TableConstraintDef
import com.company.liquibasevalidator.sql.TableConstraintKind
import com.company.liquibasevalidator.sql.TypeKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Edge-branch coverage for [DdlSchemaBuilder] and the schema model. */
class SchemaEdgeCoverageTest {

    @Test
    fun `alter table constraint targeting an unknown table is skipped`() {
        val ddl = """
            CREATE TABLE known_t (id BIGINT NOT NULL);
            ALTER TABLE known_t ADD CONSTRAINT pk_known PRIMARY KEY (id);
            ALTER TABLE ghost_t ADD CONSTRAINT pk_ghost PRIMARY KEY (id);
        """.trimIndent()
        val tables = DdlSchemaBuilder.build(listOf(DdlSchemaBuilder.DdlSource("a.sql", ddl)))

        assertNull(tables["ghost_t"], "constraint on an undefined table must not create the table")
        val known = tables["known_t"]
        assertNotNull(known)
        assertTrue(known!!.isColumnInPrimaryKey("id"))
    }

    @Test
    fun `unique table constraint maps to a unique schema constraint`() {
        val def = TableConstraintDef(
            kind = TableConstraintKind.UNIQUE,
            name = "ux_ab",
            columns = listOf("A", "B"),
            range = SrcRange(0, 1),
        )
        val schema = DdlSchemaBuilder.toConstraintSchema(def)

        assertNotNull(schema)
        assertEquals(ConstraintKind.UNIQUE, schema!!.kind)
        assertEquals("ux_ab", schema.name)
        assertEquals(listOf("a", "b"), schema.columnsLower)
    }

    @Test
    fun `check constraints are dropped from the schema model`() {
        val def = TableConstraintDef(
            kind = TableConstraintKind.CHECK,
            name = "chk_positive",
            columns = emptyList(),
            range = SrcRange(0, 1),
        )
        assertNull(DdlSchemaBuilder.toConstraintSchema(def))
    }

    @Test
    fun `unique keys include constraint columns but skip empty column lists`() {
        fun column(name: String, unique: Boolean) = ColumnSchema(
            name = name,
            dataType = SqlDataType(TypeKind.INTEGER),
            nullable = true,
            primaryKey = false,
            unique = unique,
            hasDefault = false,
        )
        val table = TableSchema(
            name = "T",
            columns = listOf(column("plain", unique = false), column("Uniq", unique = true)),
            constraints = listOf(
                ConstraintSchema(ConstraintKind.PRIMARY_KEY, "pk", listOf("ID", "SUB")),
                ConstraintSchema(ConstraintKind.UNIQUE, "empty_u", emptyList()),
                ConstraintSchema(ConstraintKind.FOREIGN_KEY, "fk", listOf("ref_id"), "other", listOf("id")),
            ),
            origin = SchemaOrigin("a.sql", 0),
        )

        // PK columns (lowercased) + the single unique-flagged column; the empty UNIQUE
        // constraint and the FK contribute nothing
        assertEquals(listOf(listOf("id", "sub"), listOf("uniq")), table.uniqueKeys())
    }

    @Test
    fun `map schema provider strips qualifiers and lowercases lookups`() {
        val table = TableSchema("Account", emptyList(), emptyList(), SchemaOrigin.DATABASE)
        val provider = MapSchemaProvider(mapOf("account" to table))

        assertSame(table, provider.findTable("Public.ACCOUNT"))
        assertSame(table, provider.findTable("account"))
        assertNull(provider.findTable("public.missing"))
    }
}
