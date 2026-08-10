package com.company.liquibasevalidator.settings

import com.company.liquibasevalidator.database.DatabaseConfig
import com.company.liquibasevalidator.database.JdbcConnector
import com.company.liquibasevalidator.database.JdbcDrivers
import com.company.liquibasevalidator.schema.SchemaIndexService
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings | Tools | Liquibase Sudarshan. */
class LiquibaseSettingsConfigurable(private val project: Project) : Configurable {

    private val globalDdl = TextFieldWithBrowseButton()
    private val globalStatic = TextFieldWithBrowseButton()
    private val countryRoot = TextFieldWithBrowseButton()
    private val countryCode = JBTextField(8)

    private val dbEnabled = JBCheckBox("Enable database validation (read-only metadata and lookups)")
    private val dbUrl = JBTextField()
    private val dbUser = JBTextField()
    private val dbPassword = JBPasswordField()
    private val dbSchema = JBTextField("", 12)
    private val dbDriverJar = TextFieldWithBrowseButton()
    private val driverStatus = com.intellij.ui.components.JBLabel()

    // STRICT rules — shown so the full rule set is visible in one place, but not
    // uncheckable: execution fails on these, so validation must always fail too.
    private val strictSyntax = JBCheckBox(
        "SQL syntax & statement delimiters (missing ';' fails the release) — always enforced", true,
    ).apply { isEnabled = false }
    private val strictDdl = JBCheckBox(
        "DDL / schema validation (unknown tables/columns, datatypes, lengths) — always enforced", true,
    ).apply { isEnabled = false }

    private val varcharLength = JBCheckBox("Validate VARCHAR/CHAR length", true)
    private val numericRanges = JBCheckBox("Validate numeric ranges and DECIMAL precision/scale", true)
    private val nullConstraints = JBCheckBox("Validate NULL constraints", true)
    private val duplicates = JBCheckBox("Validate duplicate static data (primary/unique keys)", true)
    private val mergeMappings = JBCheckBox("Validate MERGE mappings", true)
    private val foreignKeys = JBCheckBox("Validate foreign keys (database mode)", true)
    private val unusedColumns = JBCheckBox("Warn about unused staging columns", true)
    private val nameHeuristic = JBCheckBox("Map tmp_/temp_/stg_ staging tables by name when no MERGE exists", true)
    private val liquibaseStructure = JBCheckBox("Validate Liquibase changeset structure and rollbacks", true)
    private val emptyStringIsNull = JBCheckBox("Oracle: treat empty string '' as NULL (NOT NULL violations)", false)
    private val beforeCommit = JBCheckBox("Validate SQL files before commit", true)
    private val beforePush = JBCheckBox("Validate SQL files before push (includes database dry run when enabled)", true)

    private var component: JComponent? = null

    override fun getDisplayName(): String = "Liquibase Sudarshan"

    override fun createComponent(): JComponent {
        // Plain FileChooser callback instead of addBrowseFolderListener: those overloads are
        // deprecated since 2024.3, and this must load on every IDE version, old and new.
        listOf(globalDdl, globalStatic, countryRoot).forEach { field ->
            field.addActionListener {
                FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null,
                ) { chosen -> field.text = chosen.path }
            }
        }

        val autoDetect = JButton("Auto-detect repository layout").apply {
            addActionListener {
                val detected = ProjectPaths.autoDetect(project)
                detected.globalDdl?.let { globalDdl.text = it }
                detected.globalStatic?.let { globalStatic.text = it }
                detected.countryRoot?.let { countryRoot.text = it }
                if (detected.globalDdl == null && detected.globalStatic == null && detected.countryRoot == null) {
                    Messages.showInfoMessage(project, "No ddl/staticdatasetup/countries directories found.", "Liquibase Sudarshan")
                }
            }
        }
        val testConnection = JButton("Test connection").apply {
            addActionListener { testDatabaseConnection() }
        }
        dbDriverJar.addActionListener {
            FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor("jar"), project, null,
            ) { chosen -> dbDriverJar.text = chosen.path }
        }
        val downloadDriver = JButton("Download driver").apply {
            toolTipText = "Download the JDBC driver for the URL above from Maven Central (SHA-256 verified)"
            addActionListener { downloadDriverForUrl() }
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(sectionLabel("Repository layout"))
            .addLabeledComponent("Global DDL directory:", globalDdl, true)
            .addLabeledComponent("Global static dataset directory:", globalStatic, true)
            .addLabeledComponent("Country root directory:", countryRoot, true)
            .addLabeledComponent("Country code (empty = all):", countryCode)
            .addComponent(autoDetect)
            .addComponent(sectionLabel("Database validation"))
            .addComponent(dbEnabled)
            .addLabeledComponent("JDBC URL:", dbUrl, true)
            .addLabeledComponent("User:", dbUser, true)
            .addLabeledComponent("Password:", dbPassword, true)
            .addLabeledComponent("Schema:", dbSchema)
            .addLabeledComponent("Driver JAR (optional):", dbDriverJar, true)
            .addComponent(driverStatus)
            .addComponent(
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
                    add(testConnection)
                    add(downloadDriver)
                },
            )
            .addComponent(sectionLabel("Validation rules"))
            .addComponent(strictSyntax)
            .addComponent(strictDdl)
            .addComponent(varcharLength)
            .addComponent(numericRanges)
            .addComponent(nullConstraints)
            .addComponent(duplicates)
            .addComponent(mergeMappings)
            .addComponent(foreignKeys)
            .addComponent(unusedColumns)
            .addComponent(nameHeuristic)
            .addComponent(liquibaseStructure)
            .addComponent(emptyStringIsNull)
            .addComponent(sectionLabel("Version control"))
            .addComponent(beforeCommit)
            .addComponent(beforePush)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        component = form
        reset()
        return form
    }

    private fun sectionLabel(text: String): JComponent =
        com.intellij.ui.components.JBLabel("<html><b>$text</b></html>").apply {
            border = JBUI.Borders.empty(10, 0, 4, 0)
        }

    /** Refreshes the driver status line for the current URL. */
    private fun updateDriverStatus() {
        val spec = JdbcDrivers.specFor(dbUrl.text.trim())
        driverStatus.text = when {
            spec == null -> "Driver: enter a PostgreSQL or Oracle JDBC URL"
            dbDriverJar.text.isNotBlank() -> "Driver: custom JAR (${dbDriverJar.text})"
            JdbcDrivers.installedJar(spec) != null -> "Driver: ${spec.displayName} — downloaded ✓"
            else -> "Driver: ${spec.displayName} (${spec.sizeMb} MB) not downloaded yet — " +
                "use Download driver (drivers are not bundled to keep the plugin small)"
        }
    }

    private fun downloadDriverForUrl() {
        val spec = JdbcDrivers.specFor(dbUrl.text.trim())
        if (spec == null) {
            Messages.showWarningDialog(project, "Enter a PostgreSQL or Oracle JDBC URL first.", "Liquibase Sudarshan")
            return
        }
        object : Task.Modal(project, "Downloading ${spec.displayName}", true) {
            var error: String? = null
            override fun run(indicator: ProgressIndicator) {
                try {
                    JdbcDrivers.download(spec) { read, total ->
                        indicator.checkCanceled()
                        indicator.fraction = read.toDouble() / total
                        indicator.text2 = "%,d / %,d bytes".format(read, total)
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    error?.let { Messages.showErrorDialog(project, "Download failed: $it", "Liquibase Sudarshan") }
                    updateDriverStatus()
                }
            }
        }.queue()
    }

    private fun testDatabaseConnection() {
        val config = DatabaseConfig(
            jdbcUrl = dbUrl.text.trim(),
            user = dbUser.text.trim(),
            password = String(dbPassword.password),
            schemaName = dbSchema.text.trim(),
            driverJarPath = dbDriverJar.text.trim(),
        )
        object : Task.Modal(project, "Testing database connection", true) {
            var error: String? = null
            var tableCount = 0
            override fun run(indicator: ProgressIndicator) {
                try {
                    tableCount = JdbcConnector(config).withSession { it.fetchTables().size }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    val message = error?.let { "Connection failed: $it" }
                        ?: "Connection successful. Tables visible: $tableCount"
                    if (error != null) Messages.showErrorDialog(project, message, "Liquibase Sudarshan")
                    else Messages.showInfoMessage(project, message, "Liquibase Sudarshan")
                }
            }
        }.queue()
    }

    override fun isModified(): Boolean {
        val s = LiquibaseSettings.getInstance(project).state
        return globalDdl.text != s.globalDdlPath ||
            globalStatic.text != s.globalStaticPath ||
            countryRoot.text != s.countryRootPath ||
            countryCode.text != s.countryCode ||
            dbEnabled.isSelected != s.dbValidationEnabled ||
            dbUrl.text != s.dbUrl ||
            dbUser.text != s.dbUser ||
            dbSchema.text != s.dbSchema ||
            dbDriverJar.text != s.dbDriverJarPath ||
            String(dbPassword.password) != DbPasswordStore.load(s.dbUrl, s.dbUser) ||
            varcharLength.isSelected != s.validateVarcharLength ||
            numericRanges.isSelected != s.validateNumericRanges ||
            nullConstraints.isSelected != s.validateNullConstraints ||
            duplicates.isSelected != s.validateDuplicates ||
            mergeMappings.isSelected != s.validateMergeMappings ||
            foreignKeys.isSelected != s.validateForeignKeys ||
            unusedColumns.isSelected != s.warnUnusedStagingColumns ||
            nameHeuristic.isSelected != s.tempTableNameHeuristic ||
            liquibaseStructure.isSelected != s.validateLiquibaseStructure ||
            emptyStringIsNull.isSelected != s.treatEmptyStringAsNull ||
            beforeCommit.isSelected != s.validateBeforeCommit ||
            beforePush.isSelected != s.validateBeforePush
    }

    override fun apply() {
        val settings = LiquibaseSettings.getInstance(project)
        settings.update { s ->
            s.globalDdlPath = globalDdl.text.trim()
            s.globalStaticPath = globalStatic.text.trim()
            s.countryRootPath = countryRoot.text.trim()
            s.countryCode = countryCode.text.trim()
            s.dbValidationEnabled = dbEnabled.isSelected
            s.dbUrl = dbUrl.text.trim()
            s.dbUser = dbUser.text.trim()
            s.dbSchema = dbSchema.text.trim()
            s.dbDriverJarPath = dbDriverJar.text.trim()
            s.validateVarcharLength = varcharLength.isSelected
            s.validateNumericRanges = numericRanges.isSelected
            s.validateNullConstraints = nullConstraints.isSelected
            s.validateDuplicates = duplicates.isSelected
            s.validateMergeMappings = mergeMappings.isSelected
            s.validateForeignKeys = foreignKeys.isSelected
            s.warnUnusedStagingColumns = unusedColumns.isSelected
            s.tempTableNameHeuristic = nameHeuristic.isSelected
            s.validateLiquibaseStructure = liquibaseStructure.isSelected
            s.treatEmptyStringAsNull = emptyStringIsNull.isSelected
            s.validateBeforeCommit = beforeCommit.isSelected
            s.validateBeforePush = beforePush.isSelected
        }
        DbPasswordStore.save(settings.state.dbUrl, settings.state.dbUser, String(dbPassword.password))

        val index = SchemaIndexService.getInstance(project)
        if (settings.state.dbValidationEnabled) index.refreshDatabaseMetadata() else index.clearDatabaseOverlay()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    override fun reset() {
        val s = LiquibaseSettings.getInstance(project).state
        globalDdl.text = s.globalDdlPath
        globalStatic.text = s.globalStaticPath
        countryRoot.text = s.countryRootPath
        countryCode.text = s.countryCode
        dbEnabled.isSelected = s.dbValidationEnabled
        dbUrl.text = s.dbUrl
        dbUser.text = s.dbUser
        dbSchema.text = s.dbSchema
        dbDriverJar.text = s.dbDriverJarPath
        dbPassword.text = DbPasswordStore.load(s.dbUrl, s.dbUser)
        updateDriverStatus()
        varcharLength.isSelected = s.validateVarcharLength
        numericRanges.isSelected = s.validateNumericRanges
        nullConstraints.isSelected = s.validateNullConstraints
        duplicates.isSelected = s.validateDuplicates
        mergeMappings.isSelected = s.validateMergeMappings
        foreignKeys.isSelected = s.validateForeignKeys
        unusedColumns.isSelected = s.warnUnusedStagingColumns
        nameHeuristic.isSelected = s.tempTableNameHeuristic
        liquibaseStructure.isSelected = s.validateLiquibaseStructure
        emptyStringIsNull.isSelected = s.treatEmptyStringAsNull
        beforeCommit.isSelected = s.validateBeforeCommit
        beforePush.isSelected = s.validateBeforePush
    }

    override fun disposeUIResources() {
        component = null
    }
}
