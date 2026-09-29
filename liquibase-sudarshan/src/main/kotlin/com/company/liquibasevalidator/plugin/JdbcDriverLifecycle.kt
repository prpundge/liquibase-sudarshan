package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.database.JdbcDrivers
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Releases the JDBC driver class loaders when the plugin is unloaded (update, disable,
 * uninstall). Without this the loaders — and the driver JARs' classes — stay in memory for
 * the rest of the IDE session.
 *
 * The service is disposed by the platform on plugin unload, but only if it was ever
 * instantiated, so [Touch] instantiates it on project open.
 */
@Service(Service.Level.APP)
class JdbcDriverLifecycle : Disposable {

    override fun dispose() {
        JdbcDrivers.releaseLoadedDrivers()
    }

    /** Ensures the service exists, so its [dispose] actually runs on plugin unload. */
    class Touch : StartupActivity.DumbAware {
        override fun runActivity(project: Project) {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(JdbcDriverLifecycle::class.java)
        }
    }
}
