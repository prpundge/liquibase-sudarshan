package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.validation.Severity
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CopyOnWriteArrayList

/** One row of the repository validation report. */
data class ReportItem(
    val file: VirtualFile,
    /** Path shown in the report (project-relative when possible). */
    val displayPath: String,
    /** 1-based line. */
    val line: Int,
    val offset: Int,
    val severity: Severity,
    val message: String,
)

/** One dry-run data-preview row: what would happen to this value on the live database. */
data class PreviewItem(
    val file: VirtualFile,
    val displayPath: String,
    val line: Int,
    val offset: Int,
    /** Changeset the row belongs to (execution order), when inside one. */
    val changesetKey: String?,
    /** e.g. `INSERT account_type code='SAVINGS'`. */
    val label: String,
)

data class ValidationReport(
    val filesScanned: Int,
    val items: List<ReportItem>,
    val previews: List<PreviewItem> = emptyList(),
    /** Simulated liquibase-update execution order (dry run). */
    val plan: List<PreviewItem> = emptyList(),
    /** Release manifest (Simulate Release action): the exact ordered Jenkins run. */
    val manifest: List<PreviewItem> = emptyList(),
) {
    val errorCount: Int get() = items.count { it.severity == Severity.ERROR }
    val warningCount: Int get() =
        items.count { it.severity == Severity.WARNING || it.severity == Severity.WEAK_WARNING }
    val infoCount: Int get() = items.count { it.severity == Severity.INFO }
}

/** Holds the latest report; the tool window subscribes for updates. */
@Service(Service.Level.PROJECT)
class ValidationReportService {

    @Volatile
    var report: ValidationReport? = null
        private set

    private val listeners = CopyOnWriteArrayList<(ValidationReport) -> Unit>()

    fun setReport(report: ValidationReport) {
        this.report = report
        listeners.forEach { it(report) }
    }

    fun addListener(listener: (ValidationReport) -> Unit) {
        listeners += listener
    }

    companion object {
        // getService(Class), not the inline service<T>() helper — 2023.2 compatibility.
        fun getInstance(project: Project): ValidationReportService =
            project.getService(ValidationReportService::class.java)
    }
}
