package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.database.JdbcDrivers
import com.company.liquibasevalidator.inspection.ValidationResultCache
import com.company.liquibasevalidator.validation.Severity
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.sql.DriverManager

/**
 * Guards against the three ways this plugin used to hold memory for the whole IDE session:
 * cached parse trees, tool-window listeners that were never removed, and JDBC drivers left
 * registered in the JDK-global DriverManager.
 */
class MemoryRetentionTest : BasePlatformTestCase() {

    // -----------------------------------------------------------------------------------
    // 1. The per-file cache must retain findings only, never the parsed AST
    // -----------------------------------------------------------------------------------

    fun `test the cache stores findings and not the analysis`() {
        val cache = ValidationResultCache.getInstance(project)
        val problems = listOf(
            com.company.liquibasevalidator.validation.ValidationProblem(
                Severity.ERROR,
                com.company.liquibasevalidator.validation.ProblemCategory.SYNTAX,
                "boom",
                com.company.liquibasevalidator.sql.SrcRange(0, 1),
            ),
        )

        cache.put("file://a.sql", 1L, 2L, problems)

        val cached = cache.get("file://a.sql", 1L, 2L)
        assertNotNull(cached)
        assertEquals(1, cached!!.size)
        // the entry type is List<ValidationProblem>: there is no analysis/AST to retain
        assertEquals("boom", cached.single().message)
    }

    fun `test a stale stamp misses so nothing unbounded accumulates per edit`() {
        val cache = ValidationResultCache.getInstance(project)
        cache.put("file://b.sql", 1L, 1L, emptyList())

        assertNotNull(cache.get("file://b.sql", 1L, 1L))
        assertNull("a newer file stamp must miss", cache.get("file://b.sql", 2L, 1L))
        assertNull("a newer state stamp must miss", cache.get("file://b.sql", 1L, 2L))
    }

    fun `test disposing the cache frees every entry`() {
        val cache = ValidationResultCache.getInstance(project)
        cache.put("file://c.sql", 1L, 1L, emptyList())

        cache.dispose()

        assertNull(cache.get("file://c.sql", 1L, 1L))
    }

    // -----------------------------------------------------------------------------------
    // 2. Report listeners must be dropped with their owner
    // -----------------------------------------------------------------------------------

    fun `test a listener is removed when its owner is disposed`() {
        val service = ValidationReportService.getInstance(project)
        val owner = Disposer.newDisposable("test owner")
        var notifications = 0
        service.addListener(owner) { notifications++ }

        service.setReport(ValidationReport(1, emptyList()))
        assertEquals(1, notifications)

        Disposer.dispose(owner)
        service.setReport(ValidationReport(2, emptyList()))

        assertEquals("a disposed owner's listener must never fire again", 1, notifications)
    }

    fun `test many short-lived owners leave no listeners behind`() {
        val service = ValidationReportService.getInstance(project)
        var notifications = 0
        repeat(50) {
            val owner = Disposer.newDisposable("owner $it")
            service.addListener(owner) { notifications++ }
            Disposer.dispose(owner)
        }

        service.setReport(ValidationReport(3, emptyList()))

        assertEquals("every listener was deregistered", 0, notifications)
    }

    fun `test disposing the service drops the retained report`() {
        val service = ValidationReportService.getInstance(project)
        service.setReport(ValidationReport(4, emptyList()))
        assertNotNull(service.report)

        service.dispose()

        assertNull(service.report)
    }

    // -----------------------------------------------------------------------------------
    // 3. No driver of ours may stay registered in the JDK-global DriverManager
    // -----------------------------------------------------------------------------------

    fun `test loading a driver leaves nothing registered in DriverManager`() {
        // the PostgreSQL driver is on the test classpath, so ensureDriver takes the
        // classpath branch; the invariant asserted here is the one that matters either way
        val driver = JdbcDrivers.ensureDriver("jdbc:postgresql://localhost/db")
        assertNotNull(driver)

        val ours = java.util.Collections.list(DriverManager.getDrivers())
            .filter { it.javaClass.classLoader is java.net.URLClassLoader }

        assertTrue(
            "no plugin-loaded driver may remain registered in the JDK-global DriverManager: $ours",
            ours.isEmpty(),
        )
    }

    fun `test releasing drivers is safe to call when none were loaded from a jar`() {
        JdbcDrivers.releaseLoadedDrivers()
        JdbcDrivers.releaseLoadedDrivers()

        val stillOurs = java.util.Collections.list(DriverManager.getDrivers())
            .filter { it.javaClass.classLoader is java.net.URLClassLoader }
        assertTrue(stillOurs.isEmpty())
    }
}
