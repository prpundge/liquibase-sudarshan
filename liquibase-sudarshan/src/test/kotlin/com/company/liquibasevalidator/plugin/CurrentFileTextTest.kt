package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.validation.Severity
import com.company.liquibasevalidator.validation.ValidationEngine
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression: the repository report, the dry run and the pre-commit check used to read the
 * file from disk, so a file edited but not yet saved was validated in its PREVIOUS state and
 * reported clean while the editor already showed the error.
 */
class CurrentFileTextTest : BasePlatformTestCase() {

    private val clean = """
        --liquibase formatted sql
        --changeset team:001
        --comment: valid
        INSERT INTO t (code) VALUES ('A');
        --rollback DELETE FROM t;
    """.trimIndent()

    private val broken = clean.replace("INSERT INTO", "INS ERT INTO")

    fun `test unsaved editor edits are what gets validated`() {
        val file = myFixture.addFileToProject("database/data.sql", clean).virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!

        // edit in memory only — deliberately NOT saved to disk
        WriteCommandAction.runWriteCommandAction(project) { document.setText(broken) }

        val fromDisk = ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(file) }
        val current = ReadAction.compute<String, RuntimeException> { currentTextOf(file) }

        assertFalse("the saved file must still hold the old text", fromDisk.contains("INS ERT"))
        assertTrue("currentTextOf must see the unsaved edit", current.contains("INS ERT"))
    }

    fun `test the broken keyword in an unsaved buffer produces an error`() {
        val file = myFixture.addFileToProject("database/data2.sql", clean).virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(broken) }

        val text = ReadAction.compute<String, RuntimeException> { currentTextOf(file) }
        val problems = ValidationEngine().validate(text, MapSchemaProvider(emptyMap())).problems

        val syntax = problems.filter { it.severity == Severity.ERROR && it.message.contains("INS ERT") }
        assertEquals("expected exactly one split-keyword error, got: $problems", 1, syntax.size)
        assertTrue(syntax.single().message, syntax.single().message.contains("'INSERT' is split by a space"))
    }

    fun `test a saved file with no open document still validates`() {
        val file = myFixture.addFileToProject("database/data3.sql", broken).virtualFile
        // platform tests already run on the EDT, so no invokeAndWait wrapper (a lambda inside
        // a test-prefixed method compiles to a synthetic "test...$lambda$n" that JUnit3 scans)
        FileDocumentManager.getInstance().saveAllDocuments()

        val text = ReadAction.compute<String, RuntimeException> { currentTextOf(file) }

        assertTrue(text.contains("INS ERT"))
    }

    fun `test a valid statement is not reported`() {
        val file = myFixture.addFileToProject("database/data4.sql", clean).virtualFile
        val text = ReadAction.compute<String, RuntimeException> { currentTextOf(file) }

        val problems = ValidationEngine().validate(text, MapSchemaProvider(emptyMap())).problems

        assertTrue(
            "a valid INSERT must not raise a syntax error: $problems",
            problems.none { it.message.contains("is split by a space") || it.message.contains("does not start") },
        )
    }
}
