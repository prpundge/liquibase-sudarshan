package com.company.liquibasevalidator.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Resolves configured repository paths (absolute or project-relative) to VFS directories. */
object ProjectPaths {

    fun resolveDirectory(project: Project, path: String): VirtualFile? {
        if (path.isBlank()) return null
        val normalized = path.replace('\\', '/').trimEnd('/')
        if (File(normalized).isAbsolute) {
            return LocalFileSystem.getInstance().findFileByPath(normalized)?.takeIf { it.isDirectory }
        }
        project.basePath?.let { base ->
            LocalFileSystem.getInstance().findFileByPath("$base/$normalized")
                ?.takeIf { it.isDirectory }?.let { return it }
        }
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            root.findFileByRelativePath(normalized)?.takeIf { it.isDirectory }?.let { return it }
        }
        return null
    }

    /** All `.sql` files under [directory], recursively. */
    fun sqlFilesUnder(directory: VirtualFile?): List<VirtualFile> {
        if (directory == null || !directory.isDirectory) return emptyList()
        val files = mutableListOf<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(directory, null) { file ->
            if (!file.isDirectory && file.extension.equals("sql", ignoreCase = true)) files += file
            true
        }
        return files
    }

    /**
     * Best-effort auto-detection of the repository layout: finds directories named
     * `ddl`, `staticdatasetup` and `countries` within a bounded depth.
     */
    data class DetectedLayout(
        val globalDdl: String?,
        val globalStatic: String?,
        val countryRoot: String?,
    )

    fun autoDetect(project: Project): DetectedLayout {
        var ddl: VirtualFile? = null
        var globalStatic: VirtualFile? = null
        var countries: VirtualFile? = null
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            walk(root, 0) { dir ->
                when {
                    dir.name.equals("ddl", true) && ddl == null -> ddl = dir
                    dir.name.equals("staticdatasetup", true) && globalStatic == null &&
                        dir.parent?.name?.equals("global", true) == true -> globalStatic = dir
                    dir.name.equals("countries", true) && countries == null -> countries = dir
                }
            }
        }
        val base = project.basePath
        fun relativize(file: VirtualFile?): String? {
            file ?: return null
            val path = file.path
            return if (base != null && path.startsWith("$base/")) path.removePrefix("$base/") else path
        }
        return DetectedLayout(relativize(ddl), relativize(globalStatic), relativize(countries))
    }

    private fun walk(dir: VirtualFile, depth: Int, visit: (VirtualFile) -> Unit) {
        if (depth > 6 || !dir.isDirectory) return
        visit(dir)
        dir.children.filter { it.isDirectory && !it.name.startsWith(".") }
            .forEach { walk(it, depth + 1, visit) }
    }
}
