package com.company.liquibasevalidator.plugin

import com.company.liquibasevalidator.settings.LiquibaseSettings
import com.company.liquibasevalidator.settings.ProjectPaths
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Finds the SQL files a repository-wide validation covers: global DDL, global static
 * dataset and the configured country's static dataset (all countries when none configured).
 */
object RepositoryScanner {

    data class ScanTarget(val files: List<VirtualFile>, val configuredRootsFound: Boolean)

    fun repositoryFiles(project: Project): ScanTarget {
        val state = LiquibaseSettings.getInstance(project).state
        val roots = mutableListOf<VirtualFile>()
        ProjectPaths.resolveDirectory(project, state.globalDdlPath)?.let { roots += it }
        ProjectPaths.resolveDirectory(project, state.globalStaticPath)?.let { roots += it }
        ProjectPaths.resolveDirectory(project, state.countryRootPath)?.let { countryRoot ->
            val code = state.countryCode.trim()
            if (code.isEmpty()) {
                roots += countryRoot
            } else {
                countryRoot.children
                    .filter { it.isDirectory && it.name.equals(code, ignoreCase = true) }
                    .forEach { roots += it }
            }
        }
        val files = roots.flatMap { ProjectPaths.sqlFilesUnder(it) }.distinctBy { it.path }
        return ScanTarget(files, roots.isNotEmpty())
    }

    fun filesUnder(directory: VirtualFile): List<VirtualFile> = ProjectPaths.sqlFilesUnder(directory)

    fun displayPath(project: Project, file: VirtualFile): String {
        val base = project.basePath
        return if (base != null && file.path.startsWith("$base/")) file.path.removePrefix("$base/") else file.path
    }
}
