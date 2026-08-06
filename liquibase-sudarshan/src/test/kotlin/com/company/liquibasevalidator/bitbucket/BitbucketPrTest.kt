package com.company.liquibasevalidator.bitbucket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BitbucketPrTest {

    @Test
    fun `parses a cloud pull-request url`() {
        val ref = BitbucketPr.parse("https://bitbucket.org/myws/db-repo/pull-requests/42")!!
        assertEquals(BitbucketPr.Kind.CLOUD, ref.kind)
        assertEquals("myws", ref.owner)
        assertEquals("db-repo", ref.repo)
        assertEquals(42, ref.id)
        assertEquals("https://api.bitbucket.org/2.0/repositories/myws/db-repo/pullrequests/42", ref.apiBase)
        assertEquals("${ref.apiBase}/diff", ref.diffUrl)
        assertEquals("${ref.apiBase}/comments", ref.commentsUrl)
        assertEquals("myws/db-repo #42", ref.display)
    }

    @Test
    fun `cloud url tolerates trailing segments and query`() {
        val ref = BitbucketPr.parse("http://bitbucket.org/w/r/pull-requests/7/overview?tab=diff#c1")
        assertEquals(7, ref!!.id)
    }

    @Test
    fun `parses a bitbucket server url with and without a context path`() {
        val withContext =
            BitbucketPr.parse("https://git.corp.com/stash/projects/DB/repos/scripts/pull-requests/7/overview")!!
        assertEquals(BitbucketPr.Kind.SERVER, withContext.kind)
        assertEquals("DB", withContext.owner)
        assertEquals("scripts", withContext.repo)
        assertEquals(
            "https://git.corp.com/stash/rest/api/1.0/projects/DB/repos/scripts/pull-requests/7",
            withContext.apiBase,
        )

        val bare = BitbucketPr.parse("https://git.corp.com/projects/DB/repos/scripts/pull-requests/7")!!
        assertEquals("https://git.corp.com/rest/api/1.0/projects/DB/repos/scripts/pull-requests/7", bare.apiBase)
    }

    @Test
    fun `rejects everything that is not a bitbucket pr link`() {
        assertNull(BitbucketPr.parse("https://github.com/owner/repo/pull/5"))
        assertNull(BitbucketPr.parse("https://bitbucket.org/onlyworkspace"))
        assertNull(BitbucketPr.parse("https://bitbucket.org/w/r/pull-requests/notanumber"))
        assertNull(BitbucketPr.parse("not a url at all"))
    }

    @Test
    fun `auth header is Basic with a user and Bearer without`() {
        assertEquals("Bearer secret", BitbucketPr.authHeader(null, "secret"))
        assertEquals("Bearer secret", BitbucketPr.authHeader("", "secret"))
        // base64("bob:app-pass") = Ym9iOmFwcC1wYXNz
        assertEquals("Basic Ym9iOmFwcC1wYXNz", BitbucketPr.authHeader("bob", "app-pass"))
    }

    @Test
    fun `unified diff detection`() {
        assertTrue(BitbucketPr.looksLikeUnifiedDiff("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@"))
        assertTrue(BitbucketPr.looksLikeUnifiedDiff("--- a/x\n+++ b/x\n@@ -1 +1 @@"))
        assertFalse(BitbucketPr.looksLikeUnifiedDiff("""{"values":[{"path":"x"}]}"""))
    }

    @Test
    fun `cloud comment payloads`() {
        assertEquals(
            """{"content":{"raw":"line \"one\"\nline two"},"inline":{"to":12,"path":"db/x.sql"}}""",
            BitbucketPr.inlineCommentJson(BitbucketPr.Kind.CLOUD, "db/x.sql", 12, "line \"one\"\nline two"),
        )
        assertEquals(
            """{"content":{"raw":"summary"}}""",
            BitbucketPr.summaryCommentJson(BitbucketPr.Kind.CLOUD, "summary"),
        )
    }

    @Test
    fun `server comment payloads anchor to the added line`() {
        val inline = BitbucketPr.inlineCommentJson(BitbucketPr.Kind.SERVER, "db/x.sql", 3, "msg")
        assertTrue(inline.contains(""""text":"msg""""))
        assertTrue(inline.contains(""""path":"db/x.sql""""))
        assertTrue(inline.contains(""""line":3"""))
        assertTrue(inline.contains(""""lineType":"ADDED""""))
        assertEquals("""{"text":"s"}""", BitbucketPr.summaryCommentJson(BitbucketPr.Kind.SERVER, "s"))
    }

    @Test
    fun `summary text carries the verdict`() {
        val clean = BitbucketPr.summaryText(0, 2, 3)
        assertTrue(clean.contains("0 error(s), 2 warning(s) on 3 changed file(s)"))
        assertTrue(clean.contains("safe to merge"))
        val blocked = BitbucketPr.summaryText(4, 0, 1)
        assertTrue(blocked.contains("would fail"))
    }

    @Test
    fun `parses cloud remotes in ssh and https form`() {
        for (url in listOf(
            "git@bitbucket.org:myws/db-repo.git",
            "git@bitbucket.org:myws/db-repo",
            "https://bitbucket.org/myws/db-repo.git",
            "https://pravin@bitbucket.org/myws/db-repo/",
        )) {
            val remote = BitbucketPr.parseRemote(url)!!
            assertEquals(BitbucketPr.Kind.CLOUD, remote.kind, url)
            assertEquals("myws", remote.owner, url)
            assertEquals("db-repo", remote.repo, url)
            assertEquals("bitbucket.org", remote.host)
        }
    }

    @Test
    fun `parses server remotes with scm path and ssh port`() {
        val http = BitbucketPr.parseRemote("https://git.corp.com/stash/scm/db/scripts.git")!!
        assertEquals(BitbucketPr.Kind.SERVER, http.kind)
        assertEquals("db", http.owner)
        assertEquals("scripts", http.repo)
        assertEquals("https://git.corp.com/stash", http.serverBase)
        assertEquals("git.corp.com", http.host)

        val ssh = BitbucketPr.parseRemote("ssh://git@git.corp.com:7999/db/scripts.git")!!
        assertEquals("https://git.corp.com", ssh.serverBase)
        assertEquals("db", ssh.owner)

        assertNull(BitbucketPr.parseRemote("git@github.com:owner/repo.git"))
        assertNull(BitbucketPr.parseRemote("https://github.com/owner/repo.git"))
    }

    @Test
    fun `credential host of a pr ref`() {
        assertEquals("bitbucket.org", BitbucketPr.hostOf(BitbucketPr.parse("https://bitbucket.org/w/r/pull-requests/1")!!))
        assertEquals(
            "git.corp.com",
            BitbucketPr.hostOf(BitbucketPr.parse("https://git.corp.com/stash/projects/DB/repos/s/pull-requests/2")!!),
        )
    }

    @Test
    fun `reads the first bitbucket remote from a git config`() {
        val config = """
            [core]
                bare = false
            [remote "origin"]
                url = https://github.com/other/mirror.git
                fetch = +refs/heads/*:refs/remotes/origin/*
            [remote "work"]
                url = https://git.corp.com/scm/db/scripts.git
        """.trimIndent()
        val remote = BitbucketPr.remoteFromGitConfig(config)!!
        assertEquals("scripts", remote.repo)
        assertNull(BitbucketPr.remoteFromGitConfig("[core]\n bare = false"))
    }

    @Test
    fun `json escaping covers control characters, tabs and backslashes`() {
        assertEquals("a\\\\b\\\"c\\n\\r\\t\\u0001", BitbucketPr.jsonEscape("a\\b\"c\n\r\t"))
    }
}
