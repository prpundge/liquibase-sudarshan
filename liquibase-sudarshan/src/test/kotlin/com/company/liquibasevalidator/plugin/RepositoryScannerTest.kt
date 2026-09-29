package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Which files a repository-wide validation picks up: configured roots, country filtering
 * and the project-relative display path.
 */
class RepositoryScannerTest : BasePlatformTestCase() {

    private fun configure(country: String = "") {
        LiquibaseSettings.getInstance(project).update {
            it.globalDdlPath = "database/global/ddl"
            it.globalStaticPath = "database/global/staticdatasetup"
            it.countryRootPath = "database/countries"
            it.countryCode = country
        }
    }

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("database/global/ddl/account_type.sql", "CREATE TABLE a (id INT);")
        myFixture.addFileToProject("database/global/staticdatasetup/seed.sql", "INSERT INTO a (id) VALUES (1);")
        myFixture.addFileToProject("database/countries/IN/staticdatasetup/in.sql", "INSERT INTO a (id) VALUES (2);")
        myFixture.addFileToProject("database/countries/SG/staticdatasetup/sg.sql", "INSERT INTO a (id) VALUES (3);")
        myFixture.addFileToProject("database/countries/IN/staticdatasetup/notes.txt", "not sql")
    }

    fun `test with no country configured every country is scanned`() {
        configure()

        val target = RepositoryScanner.repositoryFiles(project)

        assertTrue(target.configuredRootsFound)
        val names = target.files.map { it.name }.sorted()
        assertEquals(listOf("account_type.sql", "in.sql", "seed.sql", "sg.sql"), names)
    }

    fun `test a configured country restricts the scan to that country`() {
        configure(country = "IN")

        val names = RepositoryScanner.repositoryFiles(project).files.map { it.name }.sorted()

        assertEquals(listOf("account_type.sql", "in.sql", "seed.sql"), names)
    }

    fun `test the country match is case insensitive`() {
        configure(country = "in")

        assertTrue(RepositoryScanner.repositoryFiles(project).files.any { it.name == "in.sql" })
    }

    fun `test an unknown country contributes no country files`() {
        configure(country = "ZZ")

        val names = RepositoryScanner.repositoryFiles(project).files.map { it.name }.sorted()

        assertEquals(listOf("account_type.sql", "seed.sql"), names)
    }

    fun `test unconfigured paths report no roots found`() {
        LiquibaseSettings.getInstance(project).update {
            it.globalDdlPath = "nowhere/ddl"
            it.globalStaticPath = "nowhere/static"
            it.countryRootPath = "nowhere/countries"
            it.countryCode = ""
        }

        val target = RepositoryScanner.repositoryFiles(project)

        assertFalse(target.configuredRootsFound)
        assertTrue(target.files.isEmpty())
    }

    fun `test filesUnder returns only sql files of a directory`() {
        configure()
        val dir = myFixture.findFileInTempDir("database/countries/IN/staticdatasetup")

        val files = RepositoryScanner.filesUnder(dir)

        assertEquals(listOf("in.sql"), files.map { it.name })
    }

    fun `test displayPath is project relative for files under the project base path`() {
        val base = project.basePath!!
        // deliberately outside every configured scan root, so it cannot perturb the scans above
        val real = File(base, "docs/on_disk.sql")
        real.parentFile.mkdirs()
        real.writeText("CREATE TABLE b (id INT);")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(real)!!

        assertEquals("docs/on_disk.sql", RepositoryScanner.displayPath(project, file))
    }

    fun `test displayPath falls back to the absolute path outside the project`() {
        // in-memory fixture files live under /src, never under the on-disk base path
        val outside = myFixture.addFileToProject("outside.sql", "SELECT 1;").virtualFile

        assertEquals(outside.path, RepositoryScanner.displayPath(project, outside))
    }
}
