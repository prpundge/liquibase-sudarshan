package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.validation.Severity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Report counters and the listener fan-out the tool window subscribes to. */
class ValidationReportServiceTest : BasePlatformTestCase() {

    private fun item(severity: Severity, message: String = "m") = ReportItem(
        file = myFixture.addFileToProject("r/${severity.name}-$message.sql", "SELECT 1;").virtualFile,
        displayPath = "r/${severity.name}.sql",
        line = 1,
        offset = 0,
        severity = severity,
        message = message,
    )

    fun `test counters group weak warnings with warnings`() {
        val report = ValidationReport(
            filesScanned = 3,
            items = listOf(
                item(Severity.ERROR, "a"),
                item(Severity.ERROR, "b"),
                item(Severity.WARNING, "c"),
                item(Severity.WEAK_WARNING, "d"),
                item(Severity.INFO, "e"),
            ),
        )

        assertEquals(3, report.filesScanned)
        assertEquals(2, report.errorCount)
        assertEquals(2, report.warningCount)
        assertEquals(1, report.infoCount)
    }

    fun `test an empty report counts nothing`() {
        val report = ValidationReport(filesScanned = 0, items = emptyList())

        assertEquals(0, report.errorCount)
        assertEquals(0, report.warningCount)
        assertEquals(0, report.infoCount)
        assertTrue(report.previews.isEmpty())
        assertTrue(report.plan.isEmpty())
        assertTrue(report.manifest.isEmpty())
    }

    fun `test preview plan and manifest rows are carried through`() {
        val preview = PreviewItem(
            file = myFixture.addFileToProject("p/p.sql", "SELECT 1;").virtualFile,
            displayPath = "p/p.sql",
            line = 2,
            offset = 5,
            changesetKey = "team:001",
            label = "INSERT account_type code='A'",
        )
        val report = ValidationReport(1, emptyList(), listOf(preview), listOf(preview), listOf(preview))

        assertEquals("team:001", report.previews.single().changesetKey)
        assertEquals(2, report.plan.single().line)
        assertEquals("INSERT account_type code='A'", report.manifest.single().label)
    }

    fun `test the service stores the latest report and notifies every listener`() {
        val service = ValidationReportService.getInstance(project)
        assertNull(service.report)

        val seen = mutableListOf<Int>()
        service.addListener(testRootDisposable) { seen += it.filesScanned }
        service.addListener(testRootDisposable) { seen += it.filesScanned * 10 }

        service.setReport(ValidationReport(filesScanned = 4, items = emptyList()))

        assertEquals(4, service.report!!.filesScanned)
        assertEquals(listOf(4, 40), seen)

        service.setReport(ValidationReport(filesScanned = 7, items = emptyList()))

        assertEquals(7, service.report!!.filesScanned)
        assertEquals(listOf(4, 40, 7, 70), seen)
    }

    fun `test the same service instance is returned for a project`() {
        assertSame(ValidationReportService.getInstance(project), ValidationReportService.getInstance(project))
    }
}
