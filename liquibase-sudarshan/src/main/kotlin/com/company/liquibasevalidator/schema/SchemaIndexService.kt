package com.company.liquibasevalidator.schema

import com.company.liquibasevalidator.database.DatabaseConfig
import com.company.liquibasevalidator.database.JdbcConnector
import com.company.liquibasevalidator.settings.DbPasswordStore
import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.company.liquibasevalidator.settings.ProjectPaths
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-level cache of the resolved schema. The snapshot is rebuilt lazily when settings
 * change or any `.sql` file changes (VFS listener); inspections only ever read the snapshot.
 * Optional live database metadata (fetched explicitly, in the background) overlays the
 * repository DDL — the database wins for tables it knows.
 */
@Service(Service.Level.PROJECT)
class SchemaIndexService(private val project: Project) : Disposable {

    private val log = logger<SchemaIndexService>()
    private val vfsCounter = AtomicLong()

    private data class Snapshot(val key: Pair<Long, Long>, val tables: Map<String, TableSchema>?, val resolvedDirs: Boolean)

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var databaseTables: Map<String, TableSchema>? = null

    /** Executed changesets from DATABASECHANGELOG; null = table absent (fresh database). */
    @Volatile private var executedChangesets: List<String>? = null

    /** Row counts per table (lowercase) at the last fetch; only for small schemas. */
    @Volatile private var rowCounts: Map<String, Long> = emptyMap()

    /** Datasource-browser extras from the last fetch. */
    @Volatile private var sequences: List<com.company.liquibasevalidator.database.SequenceInfo> = emptyList()
    @Volatile private var views: List<String> = emptyList()
    @Volatile private var indexes: Map<String, List<com.company.liquibasevalidator.database.IndexInfo>> = emptyMap()

    /** Bumped whenever the database overlay changes (refresh/clear). */
    private val dbVersion = AtomicLong()

    /** Path of the resolved DDL directory of the last snapshot; used to scope invalidation. */
    @Volatile private var ddlDirPath: String? = null
    private val buildLock = Any()

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // Only DDL-directory changes invalidate the schema: editing/saving DML
                    // files must not throw away the snapshot (or the per-file result cache).
                    val ddlPath = ddlDirPath
                    val relevant = events.any { event ->
                        event.path.endsWith(".sql", ignoreCase = true) &&
                            (ddlPath == null || event.path.startsWith(ddlPath, ignoreCase = true))
                    }
                    if (relevant) vfsCounter.incrementAndGet()
                }
            },
        )
    }

    /** Combined state stamp for per-file validation caching: changes whenever settings,
     *  the DDL schema, or the database overlay change. */
    fun validationStamp(): Long {
        val settings = LiquibaseSettings.getInstance(project)
        return settings.modificationCount * 1_000_003L + vfsCounter.get() * 31L + dbVersion.get()
    }

    /** Current schema snapshot; cheap when nothing changed. Safe from any background thread. */
    fun schemaProvider(): SchemaProvider {
        val settings = LiquibaseSettings.getInstance(project)
        val key = settings.modificationCount to vfsCounter.get()

        snapshot?.takeIf { it.key == key }?.let { return toProvider(it) }
        synchronized(buildLock) {
            snapshot?.takeIf { it.key == key }?.let { return toProvider(it) }
            val built = build(key)
            snapshot = built
            return toProvider(built)
        }
    }

    private fun toProvider(snapshot: Snapshot): SchemaProvider {
        val repoTables = snapshot.tables
        if (repoTables == null) return MapSchemaProvider.UNRESOLVED
        val db = databaseTables
        val merged = if (db.isNullOrEmpty()) repoTables else repoTables + db
        return MapSchemaProvider(merged)
    }

    private fun build(key: Pair<Long, Long>): Snapshot {
        val settings = LiquibaseSettings.getInstance(project).state
        return try {
            ReadAction.compute<Snapshot, RuntimeException> {
                val ddlDir = ProjectPaths.resolveDirectory(project, settings.globalDdlPath)
                ddlDirPath = ddlDir?.path
                if (ddlDir == null) {
                    Snapshot(key, tables = null, resolvedDirs = false)
                } else {
                    // fileId = VFS URL so navigation (Ctrl+Click, hover docs) can resolve it
                    val sources = ProjectPaths.sqlFilesUnder(ddlDir).mapNotNull { file ->
                        try {
                            DdlSchemaBuilder.DdlSource(file.url, VfsUtilCore.loadText(file))
                        } catch (e: Exception) {
                            log.warn("Cannot read DDL file ${file.path}", e)
                            null
                        }
                    }
                    Snapshot(key, DdlSchemaBuilder.build(sources), resolvedDirs = true)
                }
            }
        } catch (e: Exception) {
            // never break highlighting because schema resolution failed
            log.warn("Schema resolution failed", e)
            Snapshot(key, tables = null, resolvedDirs = false)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Optional database overlay
    // -----------------------------------------------------------------------------------------

    fun hasDatabaseOverlay(): Boolean = !databaseTables.isNullOrEmpty()

    /** Live database tables last fetched, or null when never connected. */
    fun currentDatabaseTables(): Map<String, TableSchema>? = databaseTables

    /** Executed changesets (`author:id`) from DATABASECHANGELOG at the last fetch;
     *  null = the table does not exist (Liquibase has never run) or never connected. */
    fun currentExecutedChangesets(): List<String>? = executedChangesets

    fun currentRowCounts(): Map<String, Long> = rowCounts
    fun currentSequences(): List<com.company.liquibasevalidator.database.SequenceInfo> = sequences
    fun currentViews(): List<String> = views
    fun currentIndexes(): Map<String, List<com.company.liquibasevalidator.database.IndexInfo>> = indexes

    /** Read-only data preview for the datasource browser; call from a background thread. */
    fun fetchDataPreview(table: String, limit: Int): com.company.liquibasevalidator.database.TableData? {
        val settings = LiquibaseSettings.getInstance(project).state
        if (!settings.dbValidationEnabled || settings.dbUrl.isBlank()) return null
        val config = DatabaseConfig(
            jdbcUrl = settings.dbUrl,
            user = settings.dbUser,
            password = DbPasswordStore.load(settings.dbUrl, settings.dbUser),
            schemaName = settings.dbSchema,
            driverJarPath = settings.dbDriverJarPath,
        )
        return JdbcConnector(config).withSession { it.dataPreview(table, limit) }
    }

    fun clearDatabaseOverlay() {
        databaseTables = null
        executedChangesets = null
        rowCounts = emptyMap()
        sequences = emptyList()
        views = emptyList()
        indexes = emptyMap()
        dbVersion.incrementAndGet()
    }


    /** Fetches DB metadata in a background task; never on the EDT, never during inspections. */
    fun refreshDatabaseMetadata(onFinished: (Result<Int>) -> Unit = {}) {
        val settings = LiquibaseSettings.getInstance(project).state
        if (!settings.dbValidationEnabled || settings.dbUrl.isBlank()) {
            databaseTables = null
            onFinished(Result.success(0))
            return
        }
        object : Task.Backgroundable(project, "Liquibase Sudarshan: loading database metadata", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // PasswordSafe access can block (native keychain / KeePass unlock):
                    // it must happen here on the pooled thread, never on the EDT.
                    val config = DatabaseConfig(
                        jdbcUrl = settings.dbUrl,
                        user = settings.dbUser,
                        password = DbPasswordStore.load(settings.dbUrl, settings.dbUser),
                        schemaName = settings.dbSchema,
                        driverJarPath = settings.dbDriverJarPath,
                    )
                    val tableCount = JdbcConnector(config).withSession { session ->
                        val tables = session.fetchTables()
                        databaseTables = tables
                        executedChangesets = session.executedChangesets()?.sorted()
                        sequences = session.sequences()
                        views = session.views()
                        indexes = session.indexes()
                        // row counts give the datasource browser a real DB-navigator feel;
                        // only for small schemas so big databases never slow the fetch down
                        rowCounts = if (tables.size <= MAX_TABLES_FOR_ROW_COUNTS) {
                            tables.keys.mapNotNull { name ->
                                session.rowCount(name)?.let { name to it }
                            }.toMap()
                        } else {
                            emptyMap()
                        }
                        tables.size
                    }
                    dbVersion.incrementAndGet()
                    onFinished(Result.success(tableCount))
                } catch (e: Exception) {
                    log.warn("Database metadata fetch failed", e)
                    onFinished(Result.failure(e))
                }
            }
        }.queue()
    }

    override fun dispose() {
        snapshot = null
        databaseTables = null
    }

    companion object {
        private const val MAX_TABLES_FOR_ROW_COUNTS = 50

        // getService(Class), not the inline service<T>() helper — 2023.2 compatibility.
        fun getInstance(project: Project): SchemaIndexService =
            project.getService(SchemaIndexService::class.java)
    }
}
