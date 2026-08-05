import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

group = "com.company.liquibasevalidator"
version = "0.2.2"

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

// 100% line coverage is measured and enforced (koverVerify) for every class that can run
// headless. Excluded — with the reason why they cannot execute in a unit-test JVM:
//   plugin.*                     Swing/EDT tool windows, dialogs, actions, VCS handlers,
//                                navigation — need a running IDE with a UI
//   settings Configurable/store  Swing settings form and the IDE credential store
//   schema.SchemaIndexService    project service: VFS listeners + live DB metadata overlay
//   cli.ValidatorCli             process entry point — calls exitProcess()
//   database.Jdbc*               open real JDBC connections / download drivers over the
//                                network (dialect SQL in Oracle/PostgresMetadata and the
//                                dry-run engine ARE measured, via fake sessions)
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.company.liquibasevalidator.plugin.*",
                    "com.company.liquibasevalidator.settings.LiquibaseSettingsConfigurable*",
                    "com.company.liquibasevalidator.settings.DbPasswordStore",
                    "com.company.liquibasevalidator.settings.BitbucketTokenStore",
                    "com.company.liquibasevalidator.schema.SchemaIndexService*",
                    "com.company.liquibasevalidator.cli.ValidatorCli*",
                    "com.company.liquibasevalidator.database.JdbcConnector*",
                    "com.company.liquibasevalidator.database.JdbcSession*",
                    "com.company.liquibasevalidator.database.JdbcDrivers*",
                    "com.company.liquibasevalidator.bitbucket.BitbucketClient*",
                )
            }
        }
        verify {
            rule("line coverage of the headless-testable core") {
                minBound(100)
            }
        }
    }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        id = "com.sudarshan.liquibase-validator"
        name = "Liquibase Sudarshan - SQL & Data Validator"
        version = project.version.toString()
        changeNotes = """
            <b>0.2.2</b> — Bitbucket review is now connected end to end: the open PR is
            auto-detected from the checked-out branch's Bitbucket remote (<i>Detect Open PR</i>),
            the token is remembered in the IDE credential store after the first use, and when a
            datasource is configured the review also runs the read-only dry run and shows, for
            every table the PR touches, whether it already exists in the connected database and
            its row count.<br/>
            <b>0.2.1</b> — Compatibility widened: now installs on IntelliJ IDEA <b>2022.3 and
            every newer version</b> (was 2023.2+; builds before 2022.2 run on Java 11 and cannot
            load modern plugins). Verified with the JetBrains Plugin Verifier against 2022.3,
            2023.2, 2024.2 and 2025.1. Also fixes a progress-indicator exception during
            repository validation on 2024.2+.<br/>
            <b>0.2.0</b> — <i>Release Dry Run…</i>: pick a country, environment and server and
            compare — strictly read-only — the data the branch would write against the data in
            the live database, per column (INSERT / UPDATE with diffs / SAME / CONFLICT);
            changesets already in DATABASECHANGELOG from previous releases are shown as SKIP
            and never re-execute. <i>Review Bitbucket PR…</i> (and CLI
            <code>--bitbucket-pr</code>): fetch a pull request's diff from Bitbucket Cloud or
            Server/Data Center, validate only its changed lines, and optionally post the review
            to the PR as inline comments plus a summary. Also: parser robustness fix
            (malformed <code>VALUES</code> rows could hang the editor) and 100% enforced line
            coverage of the validation core.<br/>
            <b>0.1.1</b> — Marketplace listing polish: point-based description; no functional
            changes.<br/>
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
            <p><b>Catch Liquibase, PostgreSQL and Oracle errors before they reach the database.</b>
            Liquibase Sudarshan validates Liquibase formatted SQL scripts against your repository's
            DDL schema right inside the editor — no SQL is ever executed during validation.</p>
            <ul>
                <li>Staging/temp-table datatype and length mismatches against the real target table</li>
                <li>INSERT value validation: VARCHAR length, numeric ranges, DECIMAL precision/scale,
                    BOOLEAN, DATE, TIMESTAMP, UUID</li>
                <li>NULL values in NOT NULL columns, missing mandatory columns</li>
                <li>MERGE source/target table and column-mapping validation</li>
                <li>Duplicate primary-key/unique values in static datasets, unused staging columns</li>
                <li>Changeset metadata, header/attribute typo detection and rollback checks</li>
                <li>Country-specific static dataset validation against the global schema</li>
                <li>Quick fixes (Alt+Enter), pre-commit and pre-push validation, repository-wide
                    validation report with navigation</li>
                <li>Optional <i>read-only</i> database dry run: pending changesets, live precondition
                    checks, foreign keys, INSERT/UPDATE data preview (PostgreSQL and Oracle)</li>
                <li>Release dry run window: per-column comparison of the data the branch would write
                    vs the data in a live server — INSERT / UPDATE with diffs / SAME / CONFLICT;
                    changesets already released are shown as SKIP (DATABASECHANGELOG-aware)</li>
                <li>Bitbucket pull-request review (Cloud and Server/Data Center): validate only the
                    PR's changed lines and optionally post inline review comments</li>
            </ul>
            <p><b>Release execution simulation (Simulate Release…):</b> validates the exact ordered
            country/environment run your CI/CD pipeline (e.g. Jenkins) would execute:</p>
            <ul>
                <li>Six stages in order: global DDL → static datasets → country datasets →
                    update scripts → environment-specific SQL (never for PROD)</li>
                <li>Sequential schema build-up: tables or foreign-key targets used before their
                    DDL runs, duplicate definitions, ALTER before CREATE</li>
                <li>Cross-file checks: duplicate changeset ids, unique-key INSERT collisions</li>
                <li>Per-environment policy guardrails: destructive SQL needs an
                    <code>--approved-destructive &lt;ticket&gt;</code> marker, PROD requires rollbacks</li>
                <li>One optional <code>.liquibase-sudarshan.yml</code> configures the IDE, the bundled
                    headless CLI and your pipeline identically — a release that passes validation
                    does not fail in SIT/UAT/PROD</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            // Floor: 2022.3 (build 223), Community and Ultimate. This is the hard floor
            // for a single artifact: 2022.2 was the first release running on JBR 17
            // (2020.x-2022.1 run Java 11 and cannot load JVM-17 bytecode at all), and
            // ActionUpdateThread arrived in 2022.3. Kotlin API is pinned to 1.7 — what
            // 2022.3 bundles. Pre-232 the PrePushHandler signature differs — both
            // signatures are implemented (see PrePushValidationHandler).
            // No ceiling: only long-stable platform APIs are used, so the plugin stays
            // compatible with every future IDE build.
            sinceBuild = "223"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Pinned versions with downloadable ZIP artifacts: the floor, the previous
            // floor, the compile target, and a recent line.
            ides(listOf("IC-2022.3.3", "IC-2023.2.7", "IC-2024.2.4", "IC-2025.1.3"))
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
    // Bytecode floor 17 so IDEs from 2022.2 (first JBR 17 release) up to current can
    // load the plugin. Older IDEs (2020.x-2022.1) run Java 11 and cannot.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // 2022.3 (the sinceBuild floor) bundles Kotlin 1.7 — restrict stdlib API usage
        // accordingly so no call resolves to a function newer IDEs have but 223 lacks.
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_7
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9
    }
}

tasks {
    test {
        useJUnitPlatform()
        systemProperty("java.awt.headless", "true")
        // Platform-test fixtures + coverage instrumentation exceed the 512m worker default.
        maxHeapSize = "2g"
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
