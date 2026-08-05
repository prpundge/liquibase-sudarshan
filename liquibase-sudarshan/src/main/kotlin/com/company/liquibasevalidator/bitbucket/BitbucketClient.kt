package com.company.liquibasevalidator.bitbucket

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Minimal Bitbucket REST client (network I/O only — excluded from coverage; the pure
 * request/payload logic lives in [BitbucketPr]). Reads use GET; the only writes ever
 * performed are posting review comments, and only when explicitly requested.
 */
class BitbucketClient(private val authHeader: String?) {

    fun getText(url: String): String = request("GET", url, body = null, accept = "text/plain")

    fun postJson(url: String, json: String): String =
        request("POST", url, body = json, accept = "application/json")

    private fun request(method: String, url: String, body: String?, accept: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", accept)
            authHeader?.let { connection.setRequestProperty("Authorization", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) {
                throw IOException("Bitbucket $method $url failed: HTTP $status ${text.take(300)}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
