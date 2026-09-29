package com.company.liquibasevalidator.bitbucket

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Network layer of the Bitbucket review, exercised against a real loopback HTTP server —
 * no mocking of HttpURLConnection, so the actual request/response handling is covered.
 */
class BitbucketClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** Captured from the last request the server handled. */
    private var lastMethod: String? = null
    private var lastAuth: String? = null
    private var lastAccept: String? = null
    private var lastContentType: String? = null
    private var lastBody: String? = null

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun respond(path: String, status: Int, body: String) {
        server.createContext(path) { exchange: HttpExchange ->
            lastMethod = exchange.requestMethod
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            lastAccept = exchange.requestHeaders.getFirst("Accept")
            lastContentType = exchange.requestHeaders.getFirst("Content-Type")
            lastBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `getText returns the body and sends the auth header when one is given`() {
        respond("/diff", 200, "diff --git a/x.sql b/x.sql")

        val text = BitbucketClient("Bearer tok").getText("$baseUrl/diff")

        assertEquals("diff --git a/x.sql b/x.sql", text)
        assertEquals("GET", lastMethod)
        assertEquals("Bearer tok", lastAuth)
        assertEquals("text/plain", lastAccept)
    }

    @Test
    fun `getText omits the auth header when no credentials are configured`() {
        respond("/anon", 200, "public")

        assertEquals("public", BitbucketClient(null).getText("$baseUrl/anon"))
        assertNull(lastAuth)
    }

    @Test
    fun `postJson sends the body as json and returns the response`() {
        respond("/comments", 200, """{"id":7}""")

        val response = BitbucketClient("Basic abc").postJson("$baseUrl/comments", """{"text":"hi"}""")

        assertEquals("""{"id":7}""", response)
        assertEquals("POST", lastMethod)
        assertEquals("application/json", lastContentType)
        assertEquals("application/json", lastAccept)
        assertEquals("""{"text":"hi"}""", lastBody)
    }

    @Test
    fun `a non-2xx response raises IOException carrying the status and the error body`() {
        respond("/missing", 404, "no such pull request")

        val failure = assertThrows<IOException> {
            BitbucketClient(null).getText("$baseUrl/missing")
        }

        assertTrue(failure.message!!.contains("HTTP 404"), failure.message)
        assertTrue(failure.message!!.contains("no such pull request"), failure.message)
    }

    @Test
    fun `a failing POST also reports the status`() {
        respond("/denied", 401, "unauthorized")

        val failure = assertThrows<IOException> {
            BitbucketClient("Bearer bad").postJson("$baseUrl/denied", "{}")
        }

        assertTrue(failure.message!!.contains("HTTP 401"), failure.message)
    }

    @Test
    fun `a long error body is truncated in the exception message`() {
        respond("/long", 500, "E".repeat(1000))

        val failure = assertThrows<IOException> {
            BitbucketClient(null).getText("$baseUrl/long")
        }

        // 300 chars of body kept, plus the "... failed: HTTP 500 " prefix
        assertTrue(failure.message!!.length < 400, "message was ${failure.message!!.length} chars")
    }

    @Test
    fun `an unreachable host propagates the connection failure`() {
        val port = server.address.port
        server.stop(0)

        assertThrows<IOException> {
            BitbucketClient(null).getText("http://127.0.0.1:$port/gone")
        }

        // restart so @AfterEach has a server to stop
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }
}
