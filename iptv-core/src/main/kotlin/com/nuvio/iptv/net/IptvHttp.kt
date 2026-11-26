package com.nuvio.iptv.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class IptvHttpException(val code: Int, message: String) : IOException(message)

/**
 * Thin HTTP layer so the engine can reuse the app's OkHttpClient (proxies, DNS, logging).
 */
class IptvHttp(
    private val client: OkHttpClient = defaultClient(),
    private val defaultUserAgent: String = DEFAULT_USER_AGENT,
) {
    /** Opens [url] and passes the body stream to [block]; the response is closed afterwards. */
    suspend fun <T> get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        block: (InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            header("User-Agent", headers["User-Agent"] ?: defaultUserAgent)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IptvHttpException(response.code, "HTTP ${response.code} for ${redact(url)}")
            val body = response.body ?: throw IptvHttpException(response.code, "Empty body for ${redact(url)}")
            body.byteStream().use(block)
        }
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        get(url, headers) { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        // Many panels block unknown agents; this matches what common IPTV players send.
        const val DEFAULT_USER_AGENT = "Nuvio/IPTV (Linux; Android) ExoPlayer"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private val CREDENTIALS = Regex("(username|password)=[^&]*", RegexOption.IGNORE_CASE)

        /** Strips credentials before a URL reaches logs or error messages. */
        fun redact(url: String): String = url.replace(CREDENTIALS, "$1=***")
    }
}
