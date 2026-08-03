package com.company.liquibasevalidator.plugin

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Plugin icons: a Sudarshana-chakra disc, with red/amber/green status-dot variants. */
object LiquibaseIcons {
    @JvmField val ToolWindow: Icon = load("/icons/sudarshan.svg")
    @JvmField val ToolWindowError: Icon = load("/icons/sudarshanError.svg")
    @JvmField val ToolWindowWarning: Icon = load("/icons/sudarshanWarning.svg")
    @JvmField val ToolWindowOk: Icon = load("/icons/sudarshanOk.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, LiquibaseIcons::class.java)
}
