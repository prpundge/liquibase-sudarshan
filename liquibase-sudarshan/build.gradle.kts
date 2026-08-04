import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.company.liquibasevalidator"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Extras for the command-line validator only (NOT bundled into the plugin ZIP): the Kotlin
// stdlib (inside the IDE the platform provides it) and the JDBC drivers (inside the IDE
// they are downloaded on demand — keeping the plugin download ~20x smaller).
val cliRuntime: Configuration by configurations.creating

dependencies {
    cliRuntime("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    cliRuntime("org.postgresql:postgresql:42.7.4")
    cliRuntime("com.oracle.database.jdbc:ojdbc11:23.5.0.24.07")

    intellijPlatform {
        intellijIdeaCommunity("2024.2.4", useInstaller = false)
        // Compile-time access to VCS/Git APIs (CheckinHandler, PrePushHandler); at runtime
        // these are optional plugin dependencies — the plugin loads without them.
        bundledPlugins("Git4Idea")
        bundledModules("intellij.platform.vcs.dvcs.impl")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // classpath-resolution path of JdbcDrivers.ensureDriver is exercised in tests
    testImplementation("org.postgresql:postgresql:42.7.4")
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
            <b>0.1.0</b> — Release execution simulation: <i>Tools | Liquibase Sudarshan |
            Simulate Release…</i> (and CLI <code>--simulate --country=CC --env=ENV</code>)
            validates the exact ordered country/environment run Jenkins would execute —
            sequential schema build-up (tables or FK targets used before their DDL runs,
            duplicate definitions), cross-file checks (duplicate changeset ids, unique-key
            collisions), and per-environment policy guardrails (destructive SQL needs an
            <code>--approved-destructive &lt;ticket&gt;</code> marker, PROD requires rollbacks,
            PROD never gets environment SQL). Configurable via a repository-root
            <code>.liquibase-sudarshan.yml</code>; ships a Jenkinsfile gate template.<br/>
            <b>0.0.2</b> — Performance and size release: plugin download is ~20x smaller
            (JDBC drivers are no longer bundled — they load from the classpath, a custom JAR,
            or a one-click SHA-256-verified download from Maven Central); per-file validation
            results are cached so unchanged files re-highlight instantly; editing data files
            no longer invalidates the schema cache (only DDL changes do); lexer fast-path
            optimizations.<br/>
            <b>0.0.1</b> — Initial public release: editor inspections with quick fixes for
            Liquibase SQL (datatypes, lengths, NULLs, MERGE mappings, duplicates, delimiters,
            changeset header/attribute typos), pre-commit and pre-push validation, read-only
            database dry run for PostgreSQL and Oracle (execution plan, INSERT/UPDATE data
            preview, live precondition checks), datasource tool window with DATABASECHANGELOG
            browser, and a headless CLI for CI/VS Code. IntelliJ IDEA 2023.2+ (Community and
            Ultimate), no upper version bound.
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
            <br/><br/>
            <b>Release execution simulation (Simulate Release…):</b> validates the exact ordered
            country/environment run your CI/CD pipeline (e.g. Jenkins) would execute — global DDL,
            static datasets, country datasets, update scripts, then environment-specific SQL —
            with sequential schema build-up (tables or foreign-key targets used before their DDL
            runs), cross-file checks (duplicate changeset ids, unique-key collisions) and
            per-environment policy guardrails (destructive SQL approval markers, PROD rollback
            requirements). One optional .liquibase-sudarshan.yml configures the IDE, the bundled
            headless CLI and your pipeline identically, so a release that passes validation does
            not fail in SIT/UAT/PROD.
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

    // Standalone CLI fat-jar (used by the VS Code extension and CI): includes the core,
    // the Kotlin stdlib and both JDBC drivers. Never part of the plugin ZIP.
    register<Jar>("cliJar") {
        group = "build"
        description = "Build the standalone command-line validator jar"
        archiveBaseName = "liquibase-sudarshan-cli"
        manifest { attributes["Main-Class"] = "com.company.liquibasevalidator.cli.ValidatorCli" }
        from(sourceSets.main.get().output)
        from(cliRuntime.map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
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
