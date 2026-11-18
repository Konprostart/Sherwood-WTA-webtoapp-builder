package com.sherwood.wta.core.sidekick.research

import com.sherwood.wta.core.logging.AppLogger
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** A bounded response body plus the URL that actually served it. */
data class SidekickHttpResponse(
    val code: Int,
    val contentType: String?,
    val body: String,
    val finalUrl: String
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Minimal GET client for the Sidekick's web research.
 *
 * Three properties matter more than features here:
 *
 * 1. **No redirects followed blindly.** A page can answer `302` to a LAN address; OkHttp's
 *    automatic redirect handling would happily fetch it. Redirects are handled here, one
 *    hop at a time, each re-checked by [SidekickWebPolicy].
 * 2. **Bounded bodies.** A page with a 40 MB body must not become a 40 MB string; the read
 *    stops at the cap and the caller sees a truncated document.
 * 3. **Cancellation reaches the socket.** Cancelling the calling coroutine cancels the HTTP
 *    call, so closing the panel stops an in-flight request instead of letting it finish.
 */
class SidekickHttp(
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun get(
        url: String,
        accept: String = "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.5",
        maxBytes: Long = MAX_BODY_BYTES
    ): SidekickHttpResponse {
        var current = url
        var hop = 0
        while (true) {
            val verdict = SidekickWebPolicy.check(current)
            val allowed = verdict as? SidekickWebPolicy.Verdict.Allowed
                ?: return SidekickHttpResponse(0, null, "", current)

            val request = Request.Builder()
                .url(allowed.url)
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                // No cookies, no auth: this fetch is anonymous by design.
                .build()

            val response = execute(request)
            response.use { result ->
                val location = result.header("Location")
                if (result.code in 300..399 && location != null && hop < MAX_REDIRECTS) {
                    // Drain so the connection is released before the next hop.
                    runCatching { result.body?.source()?.skip(64 * 1024) }
                    val next = runCatching {
                        URI(allowed.url).resolve(location.trim()).toString()
                    }.getOrNull() ?: return SidekickHttpResponse(
                        result.code, null, "", allowed.url
                    )
                    val nextVerdict = SidekickWebPolicy.check(next)
                    if (nextVerdict is SidekickWebPolicy.Verdict.Denied) {
                        // A redirect into a private host is a refusal, not a page.
                        return SidekickHttpResponse(0, null, "", allowed.url)
                    }
                    current = next
                    hop++
                    return@use
                }
                val body = readBounded(result, maxBytes)
                return SidekickHttpResponse(
                    code = result.code,
                    contentType = result.header("Content-Type"),
                    body = body,
                    finalUrl = allowed.url
                )
            }
        }
    }

    /**
     * Runs the call and suspends, so cancelling the caller's coroutine cancels the HTTP call
     * instead of leaving the request running to its timeout.
     */
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isCancelled) {
                        response.close()
                    } else {
                        continuation.resume(response)
                    }
                }
            })
        }

    private fun readBounded(response: Response, maxBytes: Long): String {
        val body = response.body ?: return ""
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        try {
            while (total < maxBytes) {
                val read = source.read(buffer, maxBytes - total)
                if (read == -1L) break
                total += read
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Research body read failed: ${e.javaClass.simpleName}")
        }
        return buffer.readUtf8()
    }

    companion object {
        private const val TAG = "SidekickHttp"

        /**
         * Some sites reject a request without a browser-ish user agent; this one is honest
         * about what it is rather than impersonating Chrome.
         */
        const val USER_AGENT =
            "SherwoodStudio-Sidekick/1.0 (Android; +https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder)"

        const val MAX_BODY_BYTES = 1_500_000L
        private const val MAX_REDIRECTS = 3

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
