package com.company.liquibasevalidator.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OracleOwnerTest {

    private fun config(schema: String) =
        DatabaseConfig(jdbcUrl = "jdbc:oracle:thin:@//h:1521/S", user = "app_user", password = "", schemaName = schema)

    @Test
    fun `empty and public fall back to the connecting user, explicit owners win`() {
        assertEquals("app_user", oracleOwnerOf(config("")))
        assertEquals("app_user", oracleOwnerOf(config("  ")))
        // 'public' is PostgreSQL's default and a reserved Oracle role — never a real owner
        assertEquals("app_user", oracleOwnerOf(config("public")))
        assertEquals("app_user", oracleOwnerOf(config("PUBLIC")))
        assertEquals("BANKING", oracleOwnerOf(config("BANKING")))
    }
}
