import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.company.liquibasevalidator"
version = "0.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Kotlin stdlib for the command-line validator only: inside the IDE the platform provides
// the stdlib (kotlin.stdlib.default.dependency=false), but plain JavaExec needs it.
val cliRuntime: Configuration by configurations.creating

dependencies {
    cliRuntime("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")

    intellijPlatform {
        intellijIdeaCommunity("2024.2.4", useInstaller = false)
        // Compile-time access to VCS/Git APIs (CheckinHandler, PrePushHandler); at runtime
        // these are optional plugin dependencies — the plugin loads without them.
        bundledPlugins("Git4Idea")
        bundledModules("intellij.platform.vcs.dvcs.impl")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }

    // Read-only database metadata / dry-run support (optional feature at runtime).
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.oracle.database.jdbc:ojdbc11:23.5.0.24.07")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        id = "com.sudarshan.liquibase-validator"
        name = "Liquibase Sudarshan - SQL & Data Validator"
        version = project.version.toString()
        changeNotes = """
            <b>0.0.1</b> — Initial public release: editor inspections with quick fixes for
            Liquibase SQL (datatypes, lengths, NULLs, MERGE mappings, duplicates, delimiters,
            changeset header/attribute typos), pre-commit and pre-push validation, read-only
            database dry run for PostgreSQL and Oracle (execution plan, INSERT/UPDATE data
            preview, live precondition checks), datasource tool window with DATABASECHANGELOG
            browser, and a headless CLI for CI/VS Code. IntelliJ IDEA 2023.2+ (Community and
            Ultimate), no upper version bound. Full feature spec: specs/0.0.1/spec.md in the
            repository.
        """.trimIndent()
        description = """
            Catch Liquibase, PostgreSQL and Oracle errors before they reach the database.
            Liquibase Sudarshan validates Liquibase formatted SQL scripts against your repository's
            DDL schema right inside the editor: staging/temp-table datatype and length mismatches,
            INSERT value validation (VARCHAR length, numeric ranges, DECIMAL precision/scale,
            BOOLEAN, DATE, TIMESTAMP, UUID), NOT NULL violations, MERGE source/target column
            mapping, duplicate primary-key/unique data, unused staging columns, changeset metadata
            and rollback checks, and country-specific static dataset validation — with quick fixes,
            pre-commit and pre-push validation, a repository-wide validation report, and an
            optional read-only database dry run (pending changesets, precondition checks, foreign
            keys) for PostgreSQL and Oracle. No SQL is ever executed during validation.
        """.trimIndent()
        ideaVersion {
            // Floor: 2023.2 (build 232), Community and Ultimate (JVM 17 bytecode,
            // Kotlin 1.8 API — see compiler options below). 2023.1 is impossible:
            // PrePushHandler's 3-arg handle() only exists from 232 (verified with the
            // Plugin Verifier — 231 fails with AbstractMethodError on push).
            // No ceiling: only long-stable platform APIs are used, so the plugin stays
            // compatible with every future IDE build.
            sinceBuild = "232"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Pinned versions with downloadable ZIP artifacts: the floor, the compile
            // target, and a recent line ('recommended()' can pick not-yet-published ones).
            ides(listOf("IC-2023.2.7", "IC-2024.2.4", "IC-2025.1.3"))
        }
    }

    publishing {
        // NEVER put the token itself in this file — it is a public repository.
        // Provide it either as the JETBRAINS_MARKETPLACE_TOKEN environment variable or as
        // marketplaceToken=perm:... in %USERPROFILE%\.gradle\gradle.properties (untracked).
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
            .orElse(providers.gradleProperty("marketplaceToken"))
    }

    signing {
        // Optional but recommended; see README "Publishing" section.
        certificateChainFile = layout.projectDirectory.file(
            providers.environmentVariable("PLUGIN_CERTIFICATE_CHAIN_FILE").orElse("certificate/chain.crt"),
        )
        privateKeyFile = layout.projectDirectory.file(
            providers.environmentVariable("PLUGIN_PRIVATE_KEY_FILE").orElse("certificate/private.pem"),
        )
        password = providers.environmentVariable("PLUGIN_PRIVATE_KEY_PASSWORD")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    // Bytecode floor 17 so IDEs from 2023.2 (JBR 17) up to current can load the plugin.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // 2023.2 bundles Kotlin 1.8 — restrict stdlib API usage accordingly.
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8
    }
}

tasks {
    test {
        useJUnitPlatform()
        systemProperty("java.awt.headless", "true")
    }

    // Command-line validation of a Liquibase repository (used by VS Code tasks / CI):
    //   gradlew validateRepo -Prepo="C:\path\to\repo" [-PrepoArgs="--oracle --country=SG"]
    register<JavaExec>("validateRepo") {
        group = "verification"
        description = "Validate a Liquibase SQL repository from the command line (static, no DB)"
        mainClass = "com.company.liquibasevalidator.cli.ValidatorCli"
        classpath = sourceSets.main.get().runtimeClasspath + cliRuntime
        val repo = project.findProperty("repo") as String? ?: "sample-repository"
        val extra = (project.findProperty("repoArgs") as String?)
            ?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        args = listOf(repo) + extra
        // Findings exit with code 1 so CI and editors see the failure; pass
        // -PrepoIgnoreExit=true to keep the Gradle invocation green regardless.
        isIgnoreExitValue = (project.findProperty("repoIgnoreExit") as String?)?.toBoolean() ?: false
    }
}
