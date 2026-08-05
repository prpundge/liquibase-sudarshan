package com.company.liquibasevalidator.settings

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Coverage-focused platform tests for [ProjectPaths] (path resolution + layout auto-detection)
 * and [LiquibaseSettings] state loading.
 */
class ProjectPathsCoverageTest : BasePlatformTestCase() {

    fun `test blank path resolves to null`() {
        assertNull(ProjectPaths.resolveDirectory(project, ""))
        assertNull(ProjectPaths.resolveDirectory(project, "   "))
    }

    fun `test absolute paths resolve to directories only and missing paths to null`() {
        val tempDir = FileUtil.createTempDirectory("lqsAbs", null)
        try {
            val nested = File(tempDir, "sub")
            assertTrue(nested.mkdirs())
            val plainFile = File(tempDir, "note.sql")
            assertTrue(plainFile.createNewFile())
            // bring the freshly created disk entries into the VFS
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(nested))
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(plainFile))

            // native separators and a trailing separator are normalized away
            val resolved = ProjectPaths.resolveDirectory(project, nested.absolutePath + File.separator)
            assertNotNull(resolved)
            assertTrue(resolved!!.isDirectory)
            assertEquals("sub", resolved.name)

            // an absolute path pointing at a file (not a directory) is rejected
            assertNull(ProjectPaths.resolveDirectory(project, plainFile.absolutePath))
            // an absolute path that does not exist resolves to null
            assertNull(ProjectPaths.resolveDirectory(project, File(tempDir, "missing_dir_xyz").absolutePath))
        } finally {
            FileUtil.delete(tempDir)
        }
    }

    fun `test relative path resolves through content roots with separator normalization`() {
        myFixture.addFileToProject("database/global/ddl/account.sql", "CREATE TABLE a (id INT);")

        val viaSlash = ProjectPaths.resolveDirectory(project, "database/global/ddl/")
        assertNotNull(viaSlash)
        assertTrue(viaSlash!!.isDirectory)
        assertEquals("ddl", viaSlash.name)

        val viaBackslash = ProjectPaths.resolveDirectory(project, "database\\global\\ddl")
        assertEquals(viaSlash, viaBackslash)

        // a relative path that exists nowhere resolves to null
        assertNull(ProjectPaths.resolveDirectory(project, "database/global/no_such_dir"))
    }

    fun `test relative path directly under the project base path resolves`() {
        val base = project.basePath
        // light-project base path exists as a real disk location; the branch needs it
        assertNotNull(base)
        val dir = File(base!!, "lqsBaseDir")
        try {
            assertTrue(dir.mkdirs() || dir.isDirectory)
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir))

            val resolved = ProjectPaths.resolveDirectory(project, "lqsBaseDir")
            assertNotNull(resolved)
            assertEquals(FileUtil.toSystemIndependentName(dir.path), resolved!!.path)
        } finally {
            FileUtil.delete(dir)
        }
    }

    fun `test sql files under handles null input non-directories and recursion`() {
        assertEmpty(ProjectPaths.sqlFilesUnder(null))

        val leaf = myFixture.addFileToProject("scripts/a.sql", "SELECT 1;").virtualFile
        // a plain file (not a directory) yields no results
        assertEmpty(ProjectPaths.sqlFilesUnder(leaf))

        myFixture.addFileToProject("scripts/nested/deep/b.SQL", "SELECT 2;")
        myFixture.addFileToProject("scripts/nested/readme.txt", "not sql")
        val dir = ProjectPaths.resolveDirectory(project, "scripts")
        assertNotNull(dir)

        val files = ProjectPaths.sqlFilesUnder(dir)
        assertEquals(setOf("a.sql", "b.SQL"), files.map { it.name }.toSet())
    }

    fun `test auto detect reports nulls then finds layout dirs and ignores misplaced staticdatasetup`() {
        // nothing detectable yet: all three stay null
        val before = ProjectPaths.autoDetect(project)
        assertEquals(ProjectPaths.DetectedLayout(null, null, null), before)
        assertTrue(before.toString().contains("DetectedLayout"))

        // a staticdatasetup directory whose parent is NOT 'global' must not be picked up
        myFixture.addFileToProject("repo2/notglobal/staticdatasetup/x.sql", "SELECT 1;")
        val misplaced = ProjectPaths.autoDetect(project)
        assertNull(misplaced.globalDdl)
        assertNull(misplaced.globalStatic)
        assertNull(misplaced.countryRoot)

        myFixture.addFileToProject("repo/ddl/tables.sql", "CREATE TABLE t (id INT);")
        myFixture.addFileToProject("repo/global/staticdatasetup/data.sql", "SELECT 1;")
        myFixture.addFileToProject("repo/countries/bd/c.sql", "SELECT 1;")

        val detected = ProjectPaths.autoDetect(project)
        assertNotNull(detected.globalDdl)
        assertTrue(detected.globalDdl!!.endsWith("repo/ddl"))
        assertNotNull(detected.globalStatic)
        assertTrue(detected.globalStatic!!.endsWith("repo/global/staticdatasetup"))
        assertNotNull(detected.countryRoot)
        assertTrue(detected.countryRoot!!.endsWith("repo/countries"))
        assertFalse(detected == before)
    }

    fun `test load state replaces the state object and bumps the modification count`() {
        val settings = LiquibaseSettings.getInstance(project)
        val before = settings.modificationCount

        val loaded = LiquibaseSettings.State().apply {
            dbValidationEnabled = true
            countryCode = "BD"
        }
        settings.loadState(loaded)
        try {
            assertSame(loaded, settings.state)
            assertTrue(settings.state.dbValidationEnabled)
            assertEquals("BD", settings.state.countryCode)
            assertEquals(before + 1, settings.modificationCount)
        } finally {
            // restore defaults so the shared light project does not leak state into other tests
            settings.loadState(LiquibaseSettings.State())
        }
    }
}
