package com.company.liquibasevalidator.inspection

import com.company.liquibasevalidator.quickfix.ReplaceTextQuickFix
import com.company.liquibasevalidator.quickfix.SelectValueQuickFix
import com.company.liquibasevalidator.schema.MapSchemaProvider
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.company.liquibasevalidator.sql.SrcRange
import com.company.liquibasevalidator.validation.ValidationEngine
import com.company.liquibasevalidator.validation.ValidationOptions
import com.company.liquibasevalidator.validation.ValidationResult
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.lang.reflect.Proxy

/**
 * Coverage-focused platform tests for the inspection's early-exit branches, the per-file
 * validation result cache, and both quick fixes.
 */
class InspectionCoverageTest : BasePlatformTestCase() {

    private val ddl = """
        CREATE TABLE account_type (
            code VARCHAR(15) NOT NULL PRIMARY KEY,
            name VARCHAR(100) NOT NULL,
            active BOOLEAN NOT NULL
        );
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(LiquibaseSqlInspection())
        LiquibaseSettings.getInstance(project).update {
            it.globalDdlPath = "database/global/ddl"
            it.globalStaticPath = "database/global/staticdatasetup"
            it.countryRootPath = "database/countries"
            it.countryCode = ""
        }
        myFixture.addFileToProject("database/global/ddl/account_type.sql", ddl)
    }

    private fun manager(): InspectionManager = InspectionManager.getInstance(project)

    private fun sampleResult(): ValidationResult =
        ValidationEngine(ValidationOptions())
            .validate("INSERT INTO t (a) VALUES (1);", MapSchemaProvider.UNRESOLVED)

    /** Minimal [PsiFile] stub: only the members the inspection touches before its early exits. */
    private fun psiFileStub(name: String, text: String?, failOnProject: Boolean = false): PsiFile =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PsiFile::class.java)) { proxy, method, args ->
            when (method.name) {
                "getName" -> name
                "getText" -> text
                "getProject" ->
                    if (failOnProject) throw IllegalStateException("stub failure for coverage")
                    else project
                "toString" -> "PsiFileStub($name)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as PsiFile

    // --- ValidationResultCache ---------------------------------------------------------------

    fun `test cache returns the result only when both stamps match`() {
        val cache = ValidationResultCache()
        val result = sampleResult()

        assertNull(cache.get("file://a.sql", 1L, 1L))
        cache.put("file://a.sql", 1L, 1L, result)

        assertSame(result, cache.get("file://a.sql", 1L, 1L))
        assertNull(cache.get("file://a.sql", 2L, 1L)) // file changed
        assertNull(cache.get("file://a.sql", 1L, 2L)) // schema/settings changed
        assertNull(cache.get("file://other.sql", 1L, 1L))
    }

    fun `test cache evicts everything once the entry limit is exceeded`() {
        val cache = ValidationResultCache()
        val result = sampleResult()

        // MAX_ENTRIES is 128: fill to 129 entries without triggering the eviction
        for (i in 0..128) cache.put("file://u$i.sql", 1L, 1L, result)
        assertSame(result, cache.get("file://u0.sql", 1L, 1L))

        // the next put sees size > 128, clears the map and inserts only the new entry
        cache.put("file://u129.sql", 1L, 1L, result)
        assertNull(cache.get("file://u0.sql", 1L, 1L))
        assertNull(cache.get("file://u128.sql", 1L, 1L))
        assertSame(result, cache.get("file://u129.sql", 1L, 1L))
    }

    // --- LiquibaseSqlInspection early exits ---------------------------------------------------

    fun `test inspection runs for whole file and ignores non sql files`() {
        val inspection = LiquibaseSqlInspection()
        assertTrue(inspection.runForWholeFile())

        myFixture.configureByText("notes.txt", "INSERT INTO account_type (nope) VALUES ('x');")
        assertNull(inspection.checkFile(myFixture.file, manager(), false))
    }

    fun `test blank sql file is skipped`() {
        myFixture.configureByText("blank.sql", "   \n \t ")
        assertNull(LiquibaseSqlInspection().checkFile(myFixture.file, manager(), false))
    }

    fun `test sql file above the size limit is skipped`() {
        val big = psiFileStub("big.sql", "x".repeat(2_000_001))
        assertNull(LiquibaseSqlInspection().checkFile(big, manager(), false))
    }

    fun `test sql file with null text is skipped`() {
        val nullText = psiFileStub("nulltext.sql", null)
        assertNull(LiquibaseSqlInspection().checkFile(nullText, manager(), false))
    }

    fun `test exception during validation is swallowed and yields no problems`() {
        val failing = psiFileStub("boom.sql", "INSERT INTO t (a) VALUES (1);", failOnProject = true)
        assertNull(LiquibaseSqlInspection().checkFile(failing, manager(), false))
    }

    fun `test second inspection pass on an unchanged file is served from the cache`() {
        val dml = """
            INSERT INTO account_type (code, name, active)
            VALUES ('X', NULL, TRUE);
        """.trimIndent()
        myFixture.configureByText("cached.sql", dml)

        val inspection = LiquibaseSqlInspection()
        val first = inspection.checkFile(myFixture.file, manager(), false)
        assertNotNull(first)
        assertTrue(first!!.isNotEmpty())

        // the first pass stored the result under (file stamp, state stamp)
        val stamp = SchemaIndexService.getInstance(project).validationStamp()
        val cached = ValidationResultCache.getInstance(project)
            .get(myFixture.file.virtualFile.url, myFixture.file.modificationStamp, stamp)
        assertNotNull(cached)
        assertEquals(first.size, cached!!.problems.size)

        // the second pass produces the same descriptors from the cached result
        val second = inspection.checkFile(myFixture.file, manager(), false)
        assertNotNull(second)
        assertEquals(first.size, second!!.size)
        assertEquals(
            first.map { it.descriptionTemplate },
            second.map { it.descriptionTemplate },
        )
    }

    // --- Quick fixes --------------------------------------------------------------------------

    fun `test replace quick fix rewrites only the datatype`() {
        val dml = """
            CREATE TEMP TABLE tmp_account_type (
                code VARCHAR(50)
            );
        """.trimIndent()
        myFixture.configureByText("fix_replace.sql", dml)
        myFixture.doHighlighting()

        val fix = myFixture.getAllQuickFixes().single { it.text.contains("Change VARCHAR(50) to VARCHAR(15)") }
        myFixture.launchAction(fix)

        myFixture.checkResult(dml.replace("VARCHAR(50)", "VARCHAR(15)"))
    }

    fun `test replace quick fix with inverted range leaves the document unchanged`() {
        myFixture.configureByText("noop.sql", "SELECT 1;")
        val descriptor = manager().createProblemDescriptor(
            myFixture.file, TextRange(0, 1), "m", ProblemHighlightType.WARNING, true,
        )

        // start >= end: the guard must bail out before touching the document
        ReplaceTextQuickFix("n", SrcRange(5, 3), "x").applyFix(project, descriptor)

        myFixture.checkResult("SELECT 1;")
    }

    fun `test select value quick fix selects the oversized literal`() {
        val dml = """
            INSERT INTO account_type (code, name, active)
            VALUES ('THIS_CODE_IS_TOO_LONG', 'n', TRUE);
        """.trimIndent()
        myFixture.configureByText("select_fix.sql", dml)
        myFixture.doHighlighting()

        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text.contains("Select value for editing") }
        assertNotNull(fix)
        myFixture.launchAction(fix!!)
        UIUtil.dispatchAllInvocationEvents() // run the fix's invokeLater

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull(editor)
        assertEquals("'THIS_CODE_IS_TOO_LONG'", editor!!.selectionModel.selectedText)
    }

    fun `test select value quick fix clamps the selection to the document length`() {
        myFixture.configureByText("clamp.sql", "SELECT 1;")
        val descriptor = manager().createProblemDescriptor(
            myFixture.file, TextRange(0, 1), "m", ProblemHighlightType.WARNING, true,
        )

        val fix = SelectValueQuickFix(SrcRange(0, 999))
        assertEquals("Select value for editing", fix.familyName)
        assertFalse(fix.startInWriteAction())

        fix.applyFix(project, descriptor)
        UIUtil.dispatchAllInvocationEvents()

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull(editor)
        assertEquals("SELECT 1;", editor!!.selectionModel.selectedText)
    }
}
