package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.plugin.navigation.LiquibaseDocumentationProvider
import com.company.liquibasevalidator.plugin.navigation.LiquibaseGotoDeclarationHandler
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Interactive editor features: Ctrl+Click navigation, hover docs, rollback intention. */
class InteractiveUiTest : BasePlatformTestCase() {

    private val ddl = """
        CREATE TABLE account_type (
            code VARCHAR(15) NOT NULL PRIMARY KEY,
            name VARCHAR(100) NOT NULL,
            active BOOLEAN NOT NULL
        );
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        LiquibaseSettings.getInstance(project).update {
            it.globalDdlPath = "database/global/ddl"
            it.globalStaticPath = "database/global/staticdatasetup"
            it.countryRootPath = "database/countries"
        }
        myFixture.addFileToProject("database/global/ddl/account_type.sql", ddl)
    }

    fun `test ctrl click on table name resolves to the ddl definition`() {
        val dml = "INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE);"
        myFixture.configureByText("data.sql", dml)
        val offset = dml.indexOf("account_type") + 3

        val targets = LiquibaseGotoDeclarationHandler().getGotoDeclarationTargets(
            myFixture.file.findElementAt(offset), offset, myFixture.editor,
        )
        assertNotNull(targets)
        assertEquals("account_type.sql", targets!!.single().containingFile.name)
    }

    fun `test ctrl click on staging table resolves within the same file`() {
        val dml = """
            CREATE TEMP TABLE tmp_account_type (code VARCHAR(15));
            INSERT INTO tmp_account_type (code) VALUES ('A');
        """.trimIndent()
        myFixture.configureByText("staging.sql", dml)
        val offset = dml.lastIndexOf("tmp_account_type") + 3

        val targets = LiquibaseGotoDeclarationHandler().getGotoDeclarationTargets(
            myFixture.file.findElementAt(offset), offset, myFixture.editor,
        )
        assertNotNull(targets)
        assertEquals("staging.sql", targets!!.single().containingFile.name)
    }

    fun `test hover documentation shows the table schema`() {
        val dml = "INSERT INTO account_type (code) VALUES ('A');"
        myFixture.configureByText("doc.sql", dml)
        val offset = dml.indexOf("account_type") + 3

        val provider = LiquibaseDocumentationProvider()
        val element = provider.getCustomDocumentationElement(
            myFixture.editor, myFixture.file, myFixture.file.findElementAt(offset), offset,
        )
        assertNotNull(element)
        val doc = provider.generateDoc(element, null)
        assertNotNull(doc)
        assertTrue(doc!!.contains("account_type"))
        assertTrue(doc.contains("VARCHAR(15)"))
        assertTrue(doc.contains("NOT NULL"))
        assertTrue(doc.contains("PK"))
    }

    fun `test generate rollback intention builds delete from inserted keys`() {
        val dml = """
            --liquibase formatted sql
            --changeset team:001
            INSERT INTO account_type (code, name, active) VALUES ('SAVINGS', 'Savings', TRUE);
            INSERT INTO account_type (code, name, active) VALUES ('CURRENT', 'Current', TRUE);
        """.trimIndent() + "\n"
        myFixture.configureByText("rollback.sql", dml)
        myFixture.editor.caretModel.moveToOffset(dml.indexOf("INSERT"))

        val intention = myFixture.availableIntentions
            .single { it.text == "Generate rollback from inserted data" }
        myFixture.launchAction(intention)

        val result = myFixture.editor.document.text
        assertTrue(result.contains("--rollback DELETE FROM account_type WHERE code IN ('SAVINGS', 'CURRENT');"))
    }

    fun `test intention is not offered when a rollback already exists`() {
        val dml = """
            --liquibase formatted sql
            --changeset team:001
            INSERT INTO account_type (code, name, active) VALUES ('X', 'x', TRUE);
            --rollback DELETE FROM account_type WHERE code = 'X';
        """.trimIndent()
        myFixture.configureByText("has_rollback.sql", dml)
        myFixture.editor.caretModel.moveToOffset(dml.indexOf("INSERT"))

        assertTrue(myFixture.availableIntentions.none { it.text == "Generate rollback from inserted data" })
    }

    fun `test changeset gutter markers appear on headers`() {
        val dml = """
            --liquibase formatted sql
            --changeset team:001
            INSERT INTO account_type (code, name, active) VALUES ('A', 'a', TRUE);
            --rollback DELETE FROM account_type WHERE code = 'A';
            --changeset team:002
            INSERT INTO account_type (code, name, active) VALUES ('B', 'b', TRUE);
        """.trimIndent()
        myFixture.configureByText("markers.sql", dml)
        myFixture.doHighlighting()

        val markers = com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
            .getLineMarkers(myFixture.editor.document, project)
        val changesetMarkers = markers.filter {
            it.lineMarkerTooltip?.contains("Changeset team:") == true
        }
        assertEquals(2, changesetMarkers.size)
        assertTrue(changesetMarkers.any { it.lineMarkerTooltip!!.contains("rollback: yes") })
        assertTrue(changesetMarkers.any { it.lineMarkerTooltip!!.contains("rollback: MISSING") })
    }
}
