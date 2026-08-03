package com.company.liquibasevalidator.database

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Driver
import java.util.concurrent.ConcurrentHashMap

/**
 * On-demand JDBC driver management. The drivers are NOT bundled with the plugin (they are
 * ~8 MB — 95% of the plugin's download size); instead they are resolved in this order:
 *
 *  1. the current classpath (CLI runs and tests provide them there),
 *  2. a user-supplied driver JAR path (air-gapped environments),
 *  3. a SHA-256-verified copy downloaded once from Maven Central into a local cache.
 *
 * When none is available, [DriverMissingException] carries the spec so the UI can offer a
 * consented download.
 */
object JdbcDrivers {

    data class DriverSpec(
        val id: String,
        val displayName: String,
        val driverClass: String,
        val downloadUrl: String,
        val sha256: String,
        val fileName: String,
        val sizeBytes: Long,
    ) {
        val sizeMb: String get() = "%.1f".format(sizeBytes / 1024.0 / 1024.0)
    }

    val POSTGRESQL = DriverSpec(
        id = "postgresql",
        displayName = "PostgreSQL JDBC Driver 42.7.4",
        driverClass = "org.postgresql.Driver",
        downloadUrl = "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar",
        sha256 = "188976721ead8e8627eb6d8389d500dccc0c9bebd885268a3047180274a6031e",
        fileName = "postgresql-42.7.4.jar",
        sizeBytes = 1_086_687,
    )

    val ORACLE = DriverSpec(
        id = "oracle",
        displayName = "Oracle JDBC Driver (ojdbc11) 23.5",
        driverClass = "oracle.jdbc.OracleDriver",
        downloadUrl = "https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.5.0.24.07/ojdbc11-23.5.0.24.07.jar",
        sha256 = "2cc69b821da842c5f5be8877444631cf9fa89561f1f6f5ad7bf1293eb669fc27",
        fileName = "ojdbc11-23.5.0.24.07.jar",
        sizeBytes = 7_199_197,
    )

    fun specFor(jdbcUrl: String): DriverSpec? = when {
        jdbcUrl.startsWith("jdbc:oracle", ignoreCase = true) -> ORACLE
        jdbcUrl.startsWith("jdbc:postgresql", ignoreCase = true) -> POSTGRESQL
        else -> null
    }

    /** Overridable for tests; defaults to `~/.liquibase-sudarshan/drivers`. */
    fun cacheDir(): Path =
        System.getProperty("liquibase.sudarshan.driver.dir")?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".liquibase-sudarshan", "drivers")

    /** The cached download when present and hash-verified. */
    fun installedJar(spec: DriverSpec): Path? {
        val jar = cacheDir().resolve(spec.fileName)
        if (!Files.isRegularFile(jar)) return null
        val key = jar.toAbsolutePath().toString()
        val ok = verifiedJars.getOrPut(key) { sha256Of(jar) == spec.sha256 }
        return if (ok) jar else null
    }

    /**
     * Downloads the driver from Maven Central into the cache, verifying the pinned SHA-256.
     * Call from a background thread only.
     */
    fun download(spec: DriverSpec, onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }): Path {
        val dir = cacheDir()
        Files.createDirectories(dir)
        val target = dir.resolve(spec.fileName)
        val temp = Files.createTempFile(dir, spec.fileName, ".part")
        try {
            val connection = URI(spec.downloadUrl).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.inputStream.use { input ->
                Files.newOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total, spec.sizeBytes)
                    }
                }
            }
            val actual = sha256Of(temp)
            if (actual != spec.sha256) {
                throw IOException(
                    "Checksum mismatch for ${spec.fileName}: expected ${spec.sha256}, got $actual — " +
                        "download aborted",
                )
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            verifiedJars[target.toAbsolutePath().toString()] = true
            return target
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Resolves the driver for [jdbcUrl]: classpath first (CLI/tests), then [customJarPath],
     * then the downloaded cache. Throws [DriverMissingException] when unavailable.
     */
    fun ensureDriver(jdbcUrl: String, customJarPath: String? = null): Driver {
        val spec = specFor(jdbcUrl)
            ?: throw IllegalArgumentException(
                "Unsupported JDBC URL (only PostgreSQL and Oracle are supported): $jdbcUrl",
            )
        try {
            return Class.forName(spec.driverClass).getDeclaredConstructor().newInstance() as Driver
        } catch (_: Throwable) {
            // not on the classpath: the normal case inside the IDE
        }
        customJarPath?.takeIf { it.isNotBlank() }?.let { return loadFromJar(spec, Paths.get(it.trim())) }
        installedJar(spec)?.let { return loadFromJar(spec, it) }
        throw DriverMissingException(spec)
    }

    // ---------------------------------------------------------------------------------------

    private val loadedDrivers = ConcurrentHashMap<String, Driver>()
    private val verifiedJars = ConcurrentHashMap<String, Boolean>()

    private fun loadFromJar(spec: DriverSpec, jar: Path): Driver {
        if (!Files.isRegularFile(jar)) {
            throw IOException("Driver JAR not found: $jar")
        }
        val key = "${spec.driverClass}|${jar.toAbsolutePath()}"
        return loadedDrivers.getOrPut(key) {
            val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), JdbcDrivers::class.java.classLoader)
            Class.forName(spec.driverClass, true, loader).getDeclaredConstructor().newInstance() as Driver
        }
    }

    private fun sha256Of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The driver is not available locally; [spec] tells the UI what to offer for download. */
class DriverMissingException(val spec: JdbcDrivers.DriverSpec) : RuntimeException(
    "${spec.displayName} is not available (${spec.sizeMb} MB). Download it from " +
        "Settings | Tools | Liquibase Sudarshan, or provide a driver JAR path there.",
)
