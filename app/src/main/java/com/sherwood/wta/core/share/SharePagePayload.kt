package com.sherwood.wta.core.share

import android.util.Base64
import com.sherwood.wta.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Serialisation of inbox items into the shape the page sees (issue #943).
 *
 * Two artifacts:
 *
 * * [SHARE_INBOX_BOOTSTRAP_JS] — installed once, at document-start, so a page that wants the
 *   share content can start listening before its own scripts run. Listener registration is the
 *   only thing the page has to do; nothing fires unless a share actually arrives.
 * * [buildShareBatch] — the per-delivery `__WTA_SHARE_PUSH__(…)` call, built when native has
 *   items and the page is ready.
 *
 * Payloads that fit [ShareReceiveContract.INLINE_MAX_BYTES] carry a base64 `dataUrl` so the
 * page can use the bytes with no further round-trip. Larger ones are persisted and offered to
 * the file chooser; the push payload is metadata only (`inline: false`) plus `fileUrl`.
 * Pages that cannot open `file://` (typical for HTML apps on a local http origin) call
 * `WTAShareInbox.readBlob(id)` / `item.readBlob()`, which pull the bytes in
 * [ShareReceiveContract.READ_CHUNK_MAX_BYTES] slices through the JS bridge.
 */
private const val TAG = "SharePagePayload"

/**
 * Document-start bootstrap for the page-facing share API.
 *
 * Native re-announces the queue on every document load (so a reload or an in-page navigation
 * does not lose a share that has not been used yet), which means the same payload can be
 * pushed more than once into the *same* document — e.g. a share that lands while the page is
 * still loading, then again on `onPageFinished`. `seen` makes that idempotent: an id is
 * delivered to a document exactly once, and a fresh document starts with an empty `seen`.
 */
val SHARE_INBOX_BOOTSTRAP_JS: String = """
    (function() {
        'use strict';
        if (window.${ShareReceiveContract.JS_NAMESPACE} && window.${ShareReceiveContract.JS_NAMESPACE}.__wta__) return;

        var queue = [];
        var listeners = [];
        var seen = {};
        var seenCount = 0;
        var SEEN_LIMIT = 128;

        function remember(id) {
            if (!id || seen[id]) return false;
            if (seenCount >= SEEN_LIMIT) { seen = {}; seenCount = 0; }
            seen[id] = true;
            seenCount++;
            return true;
        }

        function emit(batch) {
            var detail = { items: batch };
            try {
                window.dispatchEvent(new CustomEvent('${ShareReceiveContract.JS_EVENT_NAME}', { detail: detail }));
            } catch (e) { }
            try {
                if (document && document.dispatchEvent) {
                    document.dispatchEvent(new CustomEvent('${ShareReceiveContract.JS_EVENT_NAME}', { detail: detail }));
                }
            } catch (e) { }
            for (var i = 0; i < listeners.length; i++) {
                try { listeners[i](batch.slice()); } catch (e) { }
            }
        }

        function idsOf(items) {
            var ids = [];
            for (var i = 0; i < items.length; i++) {
                var id = items[i] && items[i].id;
                if (id) ids.push(id);
            }
            return ids;
        }

        // Acknowledge items to the native inbox (#1038): consumed items are never
        // re-announced to a later document or app launch. The bridge object only exists
        // while the feature is on, so every call is guarded.
        function consumeNative(ids) {
            if (!ids || !ids.length) return;
            try {
                var bridge = window.${ShareReceiveContract.JS_BRIDGE_NAME};
                if (bridge && typeof bridge.consume === 'function') {
                    bridge.consume(JSON.stringify(ids));
                }
            } catch (e) { }
        }

        function b64ToBytes(b64) {
            var bin = atob(b64);
            var out = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
            return out;
        }

        function readBlob(id) {
            return new Promise(function(resolve, reject) {
                var bridge = window.${ShareReceiveContract.JS_BRIDGE_NAME};
                if (!id || !bridge || typeof bridge.payloadSize !== 'function' ||
                    typeof bridge.readChunk !== 'function') {
                    reject(new Error('share inbox bridge unavailable'));
                    return;
                }
                var size = bridge.payloadSize(id);
                if (size < 0) {
                    reject(new Error('shared item not found'));
                    return;
                }
                var mime = (typeof bridge.payloadMime === 'function' && bridge.payloadMime(id)) ||
                    'application/octet-stream';
                var chunk = ${ShareReceiveContract.READ_CHUNK_MAX_BYTES};
                var parts = [];
                var offset = 0;
                function step() {
                    try {
                        if (offset >= size) {
                            resolve(new Blob(parts, { type: mime }));
                            return;
                        }
                        var b64 = bridge.readChunk(id, offset, chunk);
                        if (!b64) {
                            resolve(new Blob(parts, { type: mime }));
                            return;
                        }
                        var bytes = b64ToBytes(b64);
                        parts.push(bytes);
                        offset += bytes.length;
                        setTimeout(step, 0);
                    } catch (e) { reject(e); }
                }
                step();
            });
        }

        function readDataUrl(id) {
            return readBlob(id).then(function(blob) {
                return new Promise(function(resolve, reject) {
                    var reader = new FileReader();
                    reader.onload = function() { resolve(reader.result); };
                    reader.onerror = function() { reject(reader.error); };
                    reader.readAsDataURL(blob);
                });
            });
        }

        function decorate(item) {
            if (!item || item.kind === 'text') return item;
            item.readBlob = function() { return readBlob(item.id); };
            item.readDataUrl = function() { return readDataUrl(item.id); };
            return item;
        }

        window.${ShareReceiveContract.JS_NAMESPACE} = {
            __wta__: true,
            version: 2,
            /** Subscribe to share batches. Returns an unsubscribe function. */
            onShare: function(fn) {
                if (typeof fn !== 'function') return function() {};
                listeners.push(fn);
                return function() {
                    var i = listeners.indexOf(fn);
                    if (i >= 0) listeners.splice(i, 1);
                };
            },
            /** Items received in this document and not yet taken, without draining. */
            peek: function() { return queue.slice(); },
            /** Drain the items received in this document — and mark them consumed natively,
                so they are never re-announced. */
            take: function() {
                var items = queue.slice();
                queue = [];
                consumeNative(idsOf(items));
                return items;
            },
            /** Mark item(s) consumed natively without taking them — for pages that only
                listen to the event. Accepts an id, an item, or an array of either. */
            consume: function(ids) {
                var list = (ids == null) ? [] : (ids instanceof Array ? ids : [ids]);
                var drop = {};
                var out = [];
                for (var i = 0; i < list.length; i++) {
                    var id = (list[i] && list[i].id) ? list[i].id : list[i];
                    if (id && !drop[id]) { drop[id] = true; out.push(id); }
                }
                if (out.length) {
                    queue = queue.filter(function(item) { return !(item && drop[item.id]); });
                    consumeNative(out);
                }
            },
            /** Forget everything received in this document (does not affect native storage). */
            clear: function() { queue = []; },
            /**
             * Read a file item as a Blob, including payloads too large to inline as dataUrl
             * (issue #1296). Text items have item.text instead.
             */
            readBlob: readBlob,
            /** Same bytes as readBlob, as a data: URL. */
            readDataUrl: readDataUrl
        };

        window.__WTA_SHARE_PUSH__ = function(items) {
            if (!items || !items.length) return;
            var fresh = [];
            for (var i = 0; i < items.length; i++) {
                if (remember(items[i] && items[i].id)) fresh.push(decorate(items[i]));
            }
            if (!fresh.length) return;
            queue = queue.concat(fresh);
            emit(fresh);
        };
    })();
""".trimIndent()

/** JSON is a subset of JS literals, except the two Unicode line terminators. */
private fun jsSafe(json: String): String =
    json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

private fun coarseTypeOf(mimeType: String): String = when {
    mimeType.startsWith("image/") -> "image"
    mimeType.startsWith("video/") -> "video"
    mimeType.startsWith("audio/") -> "audio"
    mimeType == ShareReceiveContract.MIME_TEXT -> "text"
    else -> "file"
}

/**
 * Build the `__WTA_SHARE_PUSH__(…)` argument for [items]: a JSON array of page-facing items,
 * escaped for direct interpolation into a JS expression.
 */
suspend fun buildShareBatch(items: List<SharedItem>): String =
    withContext(Dispatchers.IO) {
        val array = JSONArray()

        for (item in items) {
            val json = JSONObject().apply {
                put("id", item.id)
                put("kind", item.kind)
                put("type", coarseTypeOf(item.mimeType))
                put("mimeType", item.mimeType)
                put("name", item.name)
                put("size", item.size)
                put("receivedAt", item.receivedAt)
            }

            if (item.isText) {
                // Text has no byte payload to inline — the string itself is the content. A
                // `data:` URL would need percent-encoding to stay valid and buys nothing.
                json.put("inline", true)
                json.put("text", item.text.orEmpty())
                array.put(json)
                continue
            }

            val file = item.path?.let(::File)
            if (file == null || !file.exists()) continue

            json.put("fileUrl", "file://" + file.absolutePath)

            if (file.length() <= ShareReceiveContract.INLINE_MAX_BYTES) {
                try {
                    val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    json.put("inline", true)
                    json.put("dataUrl", "data:" + item.mimeType + ";base64," + base64)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to inline ${item.name}: ${e.message}")
                    json.put("inline", false)
                }
            } else {
                json.put("inline", false)
            }

            array.put(json)
        }

        jsSafe(array.toString())
    }
