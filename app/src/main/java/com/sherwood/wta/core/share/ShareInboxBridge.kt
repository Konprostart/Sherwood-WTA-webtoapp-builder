package com.sherwood.wta.core.share

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import com.sherwood.wta.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/**
 * The JS→native leg of the inbound share channel (issue #1038 / #1296).
 *
 * The `WTAShareInbox` bootstrap calls [consume] when the page takes items or explicitly
 * acknowledges them; the ids are then marked consumed in the inbox index so a later
 * document load or app relaunch no longer re-announces content the page already handled.
 *
 * [payloadSize] / [payloadMime] / [readChunk] let the page pull bytes for items that
 * were too large to inline as a `data:` URL (over [ShareReceiveContract.INLINE_MAX_BYTES]).
 * `file://` paths in the push payload are not readable from an http(s) page origin.
 *
 * Registered under [ShareReceiveContract.JS_BRIDGE_NAME] only while the share-receive
 * feature is on. Item ids are unguessable random tokens, so a page can only ever read
 * or consume what it was actually given.
 */
class ShareInboxBridge(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param idsJson a JSON array of item ids. Runs on the WebView's JavaBridge thread —
     *   the actual index rewrite is handed to [scope] (IO); a failure must never
     *   propagate back into the page.
     */
    @JavascriptInterface
    fun consume(idsJson: String?) {
        val ids = try {
            val array = JSONArray(idsJson ?: return)
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            emptyList()
        }
        if (ids.isEmpty()) return

        scope.launch {
            try {
                SharedContentInbox.consume(context, ids)
            } catch (e: Exception) {
                AppLogger.w("ShareInboxBridge", "Failed to consume share items: ${e.message}")
            }
        }
    }

    /**
     * Byte length of a file item, or `-1` when the id is unknown / text / missing.
     * Called synchronously from JS.
     */
    @JavascriptInterface
    fun payloadSize(id: String?): Long {
        return try {
            val item = SharedContentInbox.lookup(context, id ?: return -1L) ?: return -1L
            if (item.isText) return -1L
            val file = item.path?.let(::File) ?: return -1L
            if (!file.isFile) return -1L
            file.length()
        } catch (e: Exception) {
            AppLogger.w("ShareInboxBridge", "payloadSize failed: ${e.message}")
            -1L
        }
    }

    /** MIME type of a file item, or empty when unknown. */
    @JavascriptInterface
    fun payloadMime(id: String?): String {
        return try {
            val item = SharedContentInbox.lookup(context, id ?: return "") ?: return ""
            item.mimeType
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Base64 of one slice of a file item. Empty string means EOF or unknown id —
     * JS treats both as "stop reading". Length is clamped to
     * [ShareReceiveContract.READ_CHUNK_MAX_BYTES].
     */
    @JavascriptInterface
    fun readChunk(id: String?, offset: Int, length: Int): String {
        return try {
            val bytes = SharedContentInbox.readChunk(
                context,
                id ?: return "",
                offset,
                length
            ) ?: return ""
            if (bytes.isEmpty()) return ""
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLogger.w("ShareInboxBridge", "readChunk failed: ${e.message}")
            ""
        }
    }
}
