package com.company.liquibasevalidator.cli

/**
 * PR-review mode support: parses a unified diff (`.diff`/`.patch`, as produced by
 * `git diff`, GitHub PR downloads, etc.) into the set of NEW-file line numbers each file
 * touches, so validation findings can be limited to the lines a pull request actually
 * changes — one review round instead of trial-and-error PRs.
 */
class PatchFilter private constructor(
    /** normalized (forward-slash, lowercase) path suffix -> changed line numbers (1-based). */
    private val changedLines: Map<String, Set<Int>>,
) {

    /** Changed lines of the file at [absolutePath], or null when the patch does not touch it. */
    fun changedLinesFor(absolutePath: String): Set<Int>? {
        val normalized = normalize(absolutePath)
        // patch paths are repo-relative: match by longest suffix
        return changedLines.entries
            .filter { normalized.endsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    fun touches(absolutePath: String): Boolean = changedLinesFor(absolutePath) != null

    val fileCount: Int get() = changedLines.size

    companion object {

        fun parse(diffText: String): PatchFilter {
            val result = LinkedHashMap<String, MutableSet<Int>>()
            var currentFile: String? = null
            var newLine = 0
            var inHunk = false

            for (rawLine in diffText.lineSequence()) {
                when {
                    rawLine.startsWith("+++ ") -> {
                        val path = rawLine.removePrefix("+++ ").trim()
                        currentFile = if (path == "/dev/null") {
                            null // file deletion: nothing to validate
                        } else {
                            normalize(path.removePrefix("b/").removePrefix("./").substringBefore('\t'))
                        }
                        inHunk = false
                    }
                    rawLine.startsWith("@@") -> {
                        val match = HUNK_HEADER.find(rawLine)
                        newLine = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        inHunk = match != null && currentFile != null
                    }
                    inHunk && rawLine.startsWith("+") -> {
                        currentFile?.let { result.getOrPut(it) { LinkedHashSet() }.add(newLine) }
                        newLine++
                    }
                    inHunk && rawLine.startsWith("-") -> {
                        // removed from the old file: does not advance the new-file line counter
                    }
                    inHunk && (rawLine.startsWith(" ") || rawLine.isEmpty()) -> newLine++
                    else -> inHunk = false // diff headers, "diff --git", index lines, binary notices
                }
            }
            return PatchFilter(result)
        }

        private val HUNK_HEADER = Regex("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@")

        private fun normalize(path: String): String = path.replace('\\', '/').lowercase()
    }
}
