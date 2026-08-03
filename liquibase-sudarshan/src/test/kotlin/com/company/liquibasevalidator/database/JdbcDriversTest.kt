package com.company.liquibasevalidator.database

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class JdbcDriversTest {

    @AfterEach
    fun cleanup() {
        System.clearProperty("liquibase.sudarshan.driver.dir")
    }

    @Test
    fun `spec is selected by jdbc url prefix`() {
        assertEquals(JdbcDrivers.ORACLE, JdbcDrivers.specFor("jdbc:oracle:thin:@//localhost:1521/FREEPDB1"))
        assertEquals(JdbcDrivers.POSTGRESQL, JdbcDrivers.specFor("jdbc:postgresql://localhost:5432/db"))
        assertNull(JdbcDrivers.specFor("jdbc:mysql://localhost/db"))
    }

    @Test
    fun `classpath driver is used when available`() {
        // the PostgreSQL driver is on the TEST classpath (testImplementation)
        val driver = JdbcDrivers.ensureDriver("jdbc:postgresql://localhost:5432/db")
        assertEquals("org.postgresql.Driver", driver.javaClass.name)
    }

    @Test
    fun `missing driver throws with the spec for consented download`() {
        // Oracle is NOT on the test classpath and the cache dir is empty
        val temp = Files.createTempDirectory("drivers-test")
        System.setProperty("liquibase.sudarshan.driver.dir", temp.toString())
        val exception = assertThrows(DriverMissingException::class.java) {
            JdbcDrivers.ensureDriver("jdbc:oracle:thin:@//localhost:1521/X")
        }
        assertEquals(JdbcDrivers.ORACLE, exception.spec)
        assertTrue(exception.message!!.contains("MB"))
    }

    @Test
    fun `unsupported url is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            JdbcDrivers.ensureDriver("jdbc:mysql://localhost/db")
        }
    }

    @Test
    fun `installed jar requires a matching checksum`() {
        val temp = Files.createTempDirectory("drivers-test")
        System.setProperty("liquibase.sudarshan.driver.dir", temp.toString())
        // a corrupt/incomplete file with the right name must NOT count as installed
        Files.write(temp.resolve(JdbcDrivers.ORACLE.fileName), byteArrayOf(1, 2, 3))
        assertNull(JdbcDrivers.installedJar(JdbcDrivers.ORACLE))
    }
}
