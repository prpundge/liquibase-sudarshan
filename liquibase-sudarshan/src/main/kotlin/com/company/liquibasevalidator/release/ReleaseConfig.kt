package com.company.liquibasevalidator.release

import com.company.liquibasevalidator.validation.Severity
import java.nio.file.Files
import java.nio.file.Path

/** Execution stages, in the exact order Jenkins runs them (requirements §1). */
enum class ReleaseStage(val display: String) {
    GLOBAL_DDL("global DDL"),
    GLOBAL_STATIC("global staticdatasetup"),
    COUNTRY_STATIC("country staticdatasetup"),
    GLOBAL_UPDATE("global update"),
    COUNTRY_UPDATE("country update"),
    ENVIRONMENT("environment SQL"),
}

/** Per-environment policy severities; null = check disabled ("off"). */
data class ReleasePolicies(
    val prodDestructive: Severity? = Severity.ERROR,
    val prodMissingRollback: Severity? = Severity.ERROR,
    val prodFailOnErrorFalse: Severity? = Severity.WARNING,
    val nonprodDestructive: Severity? = Severity.WARNING,
    val nonprodMissingRollback: Severity? = Severity.WARNING,
    val runAlwaysDml: Severity? = Severity.WARNING,
)

/**
 * Release layout + policies. Defaults match the confirmed repository convention; every
 * value is overridable via `.liquibase-sudarshan.yml` at the repository root so the IDE,
 * the CLI and Jenkins all read the same source of truth.
 */
data class ReleaseConfig(
    val globalDdl: String = "database/global/ddl",
    val globalStatic: String = "database/global/staticdatasetup",
    val countryRoot: String = "database/countries",
    val countryStaticSub: String = "staticdatasetup",
    val globalUpdate: String = "database/global/update",
    val countryUpdateSub: String = "update",
    val environmentsRoot: String = "database/environments",
    val environments: List<String> = listOf("SIT", "UAT"),
    val prodName: String = "PROD",
    val policies: ReleasePolicies = ReleasePolicies(),
) {
    fun isProd(environment: String): Boolean = environment.equals(prodName, ignoreCase = true)

    /** All selectable environments, PROD last. */
    fun allEnvironments(): List<String> = environments + prodName
}

object ReleaseConfigLoader {

    const val FILE_NAME = ".liquibase-sudarshan.yml"

    data class Loaded(val config: ReleaseConfig, val warnings: List<String>, val fromFile: Boolean)

    fun load(repositoryRoot: Path): Loaded {
        val file = repositoryRoot.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return Loaded(ReleaseConfig(), emptyList(), fromFile = false)

        val warnings = mutableListOf<String>()
        val values = HashMap<String, String>()
        for ((index, raw) in Files.readAllLines(file).withIndex()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) {
                warnings += "$FILE_NAME:${index + 1}: not a 'key: value' line — ignored"
                continue
            }
            values[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }

        fun str(key: String, default: String) = values.remove(key) ?: default
        fun list(key: String, default: List<String>) =
            values.remove(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default

        fun severity(key: String, default: Severity?): Severity? {
            val raw = values.remove(key) ?: return default
            return when (raw.lowercase()) {
                "error" -> Severity.ERROR
                "warning" -> Severity.WARNING
                "info" -> Severity.INFO
                "off" -> null
                else -> {
                    warnings += "$FILE_NAME: unknown severity '$raw' for '$key' — using default"
                    default
                }
            }
        }

        val defaults = ReleasePolicies()
        val config = ReleaseConfig(
            globalDdl = str("globalDdl", "database/global/ddl"),
            globalStatic = str("globalStatic", "database/global/staticdatasetup"),
            countryRoot = str("countryRoot", "database/countries"),
            countryStaticSub = str("countryStaticSub", "staticdatasetup"),
            globalUpdate = str("globalUpdate", "database/global/update"),
            countryUpdateSub = str("countryUpdateSub", "update"),
            environmentsRoot = str("environmentsRoot", "database/environments"),
            environments = list("environments", listOf("SIT", "UAT")),
            prodName = str("prodName", "PROD"),
            policies = ReleasePolicies(
                prodDestructive = severity("policy.prod.destructive", defaults.prodDestructive),
                prodMissingRollback = severity("policy.prod.missingRollback", defaults.prodMissingRollback),
                prodFailOnErrorFalse = severity("policy.prod.failOnErrorFalse", defaults.prodFailOnErrorFalse),
                nonprodDestructive = severity("policy.nonprod.destructive", defaults.nonprodDestructive),
                nonprodMissingRollback = severity("policy.nonprod.missingRollback", defaults.nonprodMissingRollback),
                runAlwaysDml = severity("policy.runAlwaysDml", defaults.runAlwaysDml),
            ),
        )
        values.keys.forEach { warnings += "$FILE_NAME: unknown key '$it' — ignored" }
        return Loaded(config, warnings, fromFile = true)
    }
}

/**
 * Deterministic file order within a stage: numeric prefix compared numerically first,
 * then case-insensitive name. MUST match the Jenkins sort (requirements A3).
 */
object SqlFileOrder : Comparator<String> {
    private val prefix = Regex("^(\\d+)")

    override fun compare(a: String, b: String): Int {
        val pa = prefix.find(a)?.groupValues?.get(1)?.toLongOrNull()
        val pb = prefix.find(b)?.groupValues?.get(1)?.toLongOrNull()
        return when {
            pa != null && pb != null && pa != pb -> pa.compareTo(pb)
            pa != null && pb == null -> -1 // numbered files run before unnumbered ones
            pa == null && pb != null -> 1
            else -> a.compareTo(b, ignoreCase = true)
        }
    }
}
