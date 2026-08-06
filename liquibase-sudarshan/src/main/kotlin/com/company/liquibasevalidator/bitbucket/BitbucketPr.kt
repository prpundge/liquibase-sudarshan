package com.company.liquibasevalidator.bitbucket

import java.util.Base64

/**
 * Pure Bitbucket pull-request model: PR-URL parsing (both bitbucket.org Cloud and
 * self-hosted Bitbucket Server / Data Center), REST endpoints and comment payloads.
 * All network I/O lives in [BitbucketClient] so this stays fully unit-testable.
 */
object BitbucketPr {

    enum class Kind { CLOUD, SERVER }

    data class PrRef(
        val kind: Kind,
        /** Cloud: workspace; Server: project key. */
        val owner: String,
        val repo: String,
        val id: Long,
        /** Server only: scheme://host[:port][/context]; empty for Cloud. */
        val serverBase: String = "",
    ) {
        val apiBase: String
            get() = when (kind) {
                Kind.CLOUD -> "https://api.bitbucket.org/2.0/repositories/$owner/$repo/pullrequests/$id"
                Kind.SERVER -> "$serverBase/rest/api/1.0/projects/$owner/repos/$repo/pull-requests/$id"
            }

        /** Cloud returns the raw unified diff here; Server needs Accept: text/plain. */
        val diffUrl: String get() = "$apiBase/diff"
        val commentsUrl: String get() = "$apiBase/comments"
        val display: String get() = "$owner/$repo #$id"
    }

    private val cloudUrl =
        Regex("^https?://bitbucket\\.org/([^/]+)/([^/]+)/pull-requests/(\\d+)(?:[/?#].*)?$")
    private val serverUrl =
        Regex("^(https?://[^/?#]+(?:/[^/?#]+)*?)/projects/([^/]+)/repos/([^/]+)/pull-requests/(\\d+)(?:[/?#].*)?$")

    /** Parses a PR web URL; null when it is not a recognizable Bitbucket pull-request link. */
    fun parse(url: String): PrRef? {
        val trimmed = url.trim()
        cloudUrl.matchEntire(trimmed)?.let { m ->
            return PrRef(Kind.CLOUD, m.groupValues[1], m.groupValues[2], m.groupValues[3].toLong())
        }
        serverUrl.matchEntire(trimmed)?.let { m ->
            return PrRef(
                Kind.SERVER, m.groupValues[2], m.groupValues[3], m.groupValues[4].toLong(),
                serverBase = m.groupValues[1],
            )
        }
        return null
    }

    /** `Basic user:token` when a user is given (Cloud app passwords), else `Bearer token`. */
    fun authHeader(user: String?, token: String): String =
        if (!user.isNullOrBlank()) {
            "Basic " + Base64.getEncoder().encodeToString("$user:$token".toByteArray(Charsets.UTF_8))
        } else {
            "Bearer $token"
        }

    /** True when the text looks like a unified diff (what PatchFilter can consume). */
    fun looksLikeUnifiedDiff(text: String): Boolean =
        text.lineSequence().any { it.startsWith("+++ ") || it.startsWith("diff --git ") }

    // ---------------------------------------------------------------------------------------
    // Comment payloads
    // ---------------------------------------------------------------------------------------

    /** Inline comment pinned to [line] (1-based, in the new file) of [path]. */
    fun inlineCommentJson(kind: Kind, path: String, line: Int, text: String): String = when (kind) {
        Kind.CLOUD ->
            """{"content":{"raw":"${jsonEscape(text)}"},"inline":{"to":$line,"path":"${jsonEscape(path)}"}}"""
        Kind.SERVER ->
            """{"text":"${jsonEscape(text)}","anchor":{"diffType":"EFFECTIVE","path":"${jsonEscape(path)}",""" +
                """"line":$line,"lineType":"ADDED","fileType":"TO"}}"""
    }

    /** PR-level summary comment. */
    fun summaryCommentJson(kind: Kind, text: String): String = when (kind) {
        Kind.CLOUD -> """{"content":{"raw":"${jsonEscape(text)}"}}"""
        Kind.SERVER -> """{"text":"${jsonEscape(text)}"}"""
    }

    /** Review verdict text posted as the PR-level comment. */
    fun summaryText(errors: Int, warnings: Int, filesChanged: Int): String {
        val verdict = if (errors == 0) {
            "no blocking findings — safe to merge from the database point of view"
        } else {
            "release-blocking findings — this PR would fail when executed against the database"
        }
        return "Liquibase Sudarshan review: $errors error(s), $warnings warning(s) " +
            "on $filesChanged changed file(s) — $verdict."
    }

    fun jsonEscape(text: String): String = buildString(text.length + 8) {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // "Already connected": the stored token is keyed by Bitbucket host — derived from the
    // project's git remote (for prefilling) or from the pasted PR URL. The review itself
    // always runs on exactly the PR the user provides.
    // ---------------------------------------------------------------------------------------

    /** A Bitbucket repository derived from a git remote URL. */
    data class RemoteRepo(val kind: Kind, val owner: String, val repo: String, val serverBase: String = "") {
        /** Host the credentials are stored under (bitbucket.org or the server host). */
        val host: String
            get() = when (kind) {
                Kind.CLOUD -> "bitbucket.org"
                Kind.SERVER -> serverBase.substringAfter("://").substringBefore('/')
            }
    }

    /** Host a PR reference's credentials are stored under. */
    fun hostOf(ref: PrRef): String = when (ref.kind) {
        Kind.CLOUD -> "bitbucket.org"
        Kind.SERVER -> ref.serverBase.substringAfter("://").substringBefore('/')
    }

    private val cloudSsh = Regex("^git@bitbucket\\.org:([^/]+)/(.+?)(?:\\.git)?$")
    private val cloudHttp = Regex("^https?://(?:[^@/]+@)?bitbucket\\.org/([^/]+)/([^/]+?)(?:\\.git)?/?$")
    private val serverHttp = Regex("^(https?://[^/?#]+(?:/[^/?#]+)*?)/scm/([^/]+)/([^/]+?)(?:\\.git)?/?$")
    private val serverSsh = Regex("^ssh://git@([^:/]+)(?::\\d+)?/([^/]+)/([^/]+?)(?:\\.git)?$")

    /** Parses a git remote URL (https or ssh, Cloud or Server); null when not Bitbucket. */
    fun parseRemote(url: String): RemoteRepo? {
        val trimmed = url.trim()
        cloudSsh.matchEntire(trimmed)?.let { return RemoteRepo(Kind.CLOUD, it.groupValues[1], it.groupValues[2]) }
        cloudHttp.matchEntire(trimmed)?.let { return RemoteRepo(Kind.CLOUD, it.groupValues[1], it.groupValues[2]) }
        serverHttp.matchEntire(trimmed)?.let {
            return RemoteRepo(Kind.SERVER, it.groupValues[2], it.groupValues[3], serverBase = it.groupValues[1])
        }
        serverSsh.matchEntire(trimmed)?.let {
            // ssh tells us the host but not the web scheme/context — https://host is the
            // standard Bitbucket Server setup
            return RemoteRepo(Kind.SERVER, it.groupValues[2], it.groupValues[3], serverBase = "https://${it.groupValues[1]}")
        }
        return null
    }

    /** First `url = …` under a Bitbucket remote in a plain `.git/config` text. */
    fun remoteFromGitConfig(gitConfigText: String): RemoteRepo? =
        Regex("""url\s*=\s*(\S+)""").findAll(gitConfigText)
            .mapNotNull { parseRemote(it.groupValues[1]) }
            .firstOrNull()
}
