package com.company.liquibasevalidator.settings

import com.company.liquibasevalidator.validation.ValidationOptions
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-level settings: repository layout, country selection, validation toggles and the
 * (optional) database connection. The DB password is kept in PasswordSafe, never in XML.
 */
@Service(Service.Level.PROJECT)
@State(name = "LiquibaseSudarshanSettings", storages = [Storage("liquibaseSudarshan.xml")])
class LiquibaseSettings : PersistentStateComponent<LiquibaseSettings.State> {

    class State {
        var globalDdlPath: String = "database/global/ddl"
        var globalStaticPath: String = "database/global/staticdatasetup"
        var countryRootPath: String = "database/countries"
        var countryCode: String = ""

        var dbValidationEnabled: Boolean = false
        var dbUrl: String = ""
        var dbUser: String = ""
        // empty = driver default: PostgreSQL falls back to 'public', Oracle to the user
        var dbSchema: String = ""
        /** Optional custom JDBC driver JAR (air-gapped setups); empty = on-demand download. */
        var dbDriverJarPath: String = ""

        var validateVarcharLength: Boolean = true
        var validateNumericRanges: Boolean = true
        var validateNullConstraints: Boolean = true
        var validateDuplicates: Boolean = true
        var validateMergeMappings: Boolean = true
        var validateForeignKeys: Boolean = true
        var warnUnusedStagingColumns: Boolean = true
        var tempTableNameHeuristic: Boolean = true
        var validateLiquibaseStructure: Boolean = true
        var treatEmptyStringAsNull: Boolean = false

        var validateBeforeCommit: Boolean = true
        var validateBeforePush: Boolean = true
    }

    private var state = State()
    private val tracker = AtomicLong()

    /** Bumped on every settings change; part of the schema cache key. */
    val modificationCount: Long get() = tracker.get()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        tracker.incrementAndGet()
    }

    fun update(mutator: (State) -> Unit) {
        mutator(state)
        tracker.incrementAndGet()
    }

    fun toOptions(): ValidationOptions = ValidationOptions(
        validateVarcharLength = state.validateVarcharLength,
        validateNumericRanges = state.validateNumericRanges,
        validateNullConstraints = state.validateNullConstraints,
        validateDuplicates = state.validateDuplicates,
        validateMergeMappings = state.validateMergeMappings,
        validateForeignKeys = state.validateForeignKeys,
        warnUnusedStagingColumns = state.warnUnusedStagingColumns,
        tempTableNameHeuristic = state.tempTableNameHeuristic,
        validateLiquibaseStructure = state.validateLiquibaseStructure,
        treatEmptyStringAsNull = state.treatEmptyStringAsNull,
    )

    companion object {
        // getService(Class) instead of the inline service<T>() helper: the inline function
        // from the 2024.2 SDK references methods that do not exist in 2023.2 IDEs.
        fun getInstance(project: Project): LiquibaseSettings =
            project.getService(LiquibaseSettings::class.java)
    }
}

/** DB password storage via the IDE's PasswordSafe. */
/** Bitbucket connection remembered in the IDE credential store, keyed by host —
 *  entered once in the Review Bitbucket PR dialog, reused on every later review. */
object BitbucketTokenStore {

    private fun attributes(host: String): CredentialAttributes =
        CredentialAttributes(serviceName = generateServiceName("LiquibaseSudarshan.Bitbucket", host))

    /** user (empty for Server tokens) to token; nulls when nothing is stored yet. */
    fun load(host: String): Pair<String, String>? {
        val credentials = PasswordSafe.instance.get(attributes(host)) ?: return null
        val token = credentials.getPasswordAsString().orEmpty()
        if (token.isEmpty()) return null
        return (credentials.userName ?: "") to token
    }

    fun save(host: String, user: String, token: String) {
        PasswordSafe.instance.set(attributes(host), Credentials(user, token))
    }
}

object DbPasswordStore {

    // single-argument constructor: the two-argument form is deprecated in newer platforms
    private fun attributes(url: String, user: String): CredentialAttributes =
        CredentialAttributes(serviceName = generateServiceName("LiquibaseSudarshan", "$url|$user"))

    fun load(url: String, user: String): String =
        PasswordSafe.instance.getPassword(attributes(url, user)).orEmpty()

    fun save(url: String, user: String, password: String) {
        PasswordSafe.instance.set(attributes(url, user), Credentials(user, password))
    }
}
