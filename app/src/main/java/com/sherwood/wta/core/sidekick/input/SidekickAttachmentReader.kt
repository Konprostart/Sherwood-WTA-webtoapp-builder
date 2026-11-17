package com.sherwood.wta.core.sidekick.input

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sherwood.wta.core.logging.AppLogger
import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException

/**
 * Reads a picked `content://` document into a [SidekickAttachment].
 *
 * The provider is untrusted input: its reported size is not believed, its stream is
 * copied with a hard byte counter, and the copy lands in a temp file so a 24 MB photo is
 * never held in the heap whole. Only then is anything decoded.
 *
 * Everything here runs off the main thread; [SidekickViewModel] wraps the calls in
 * `Dispatchers.IO`.
 */
class SidekickAttachmentReader(private val context: Context) {

    private val resolver: ContentResolver
        get() = context.applicationContext.contentResolver

    private val tempDir: File
        get() = File(context.applicationContext.cacheDir, TEMP_DIR_NAME).apply { mkdirs() }

    /** Reads [uri] and classifies it as an image or a text file. */
    fun read(uri: Uri): SidekickAttachmentResult {
        pruneTemp()

        var displayName: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                    }
                }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        // The provider's MIME type is the first signal, but it is not always usable: some
        // photo pickers and every file manager serving a download report `null` or
        // `application/octet-stream`. Deciding by MIME alone dropped those images into the
        // text branch and refused them as "not a text file", so the extension decides
        // whenever the type itself says nothing.
        val rawName = SidekickAttachmentLimits.sanitizeName(displayName, fallback = "")
        val kind = SidekickAttachmentLimits.kindFor(mime, rawName)
        val name = SidekickAttachmentLimits.sanitizeName(
            displayName,
            fallback = when (kind) {
                SidekickAttachmentKind.IMAGE -> "image"
                else -> "file"
            }
        )

        return when (kind) {
            SidekickAttachmentKind.IMAGE ->
                readImage(
                    uri,
                    name,
                    mayHaveAlpha = SidekickAttachmentLimits.mayHaveAlpha(mime, name)
                )
            SidekickAttachmentKind.TEXT -> readText(uri, name)
            // A provider that cannot name the type is common for downloads; if the name has
            // a text extension we already took that branch, so anything left is refused
            // rather than pasted in as mojibake.
            SidekickAttachmentKind.UNSUPPORTED ->
                SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.NOT_TEXT)
        }
    }

    // ------------------------------------------------------------------ text

    private fun readText(uri: Uri, name: String): SidekickAttachmentResult {
        val temp = File(tempDir, "import-${System.nanoTime()}.bin")
        try {
            val copied = copyBounded(uri, temp, SidekickAttachmentLimits.MAX_TEXT_FILE_BYTES)
                ?: return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.UNREADABLE)
            if (copied == 0L) {
                return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.EMPTY)
            }
            if (copied > SidekickAttachmentLimits.MAX_TEXT_FILE_BYTES) {
                return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.TOO_LARGE)
            }

            val raw = temp.readBytes()
            if (looksBinary(raw)) {
                return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.NOT_TEXT)
            }
            val decoded = decodeUtf8(raw).trim()
            if (decoded.isEmpty()) {
                return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.EMPTY)
            }
            val clipped = decoded.length > SidekickAttachmentLimits.MAX_TEXT_CHARS_PER_FILE
            return SidekickAttachmentResult.Ok(
                SidekickTextAttachment(
                    name = name,
                    text = if (clipped) {
                        safeTake(decoded, SidekickAttachmentLimits.MAX_TEXT_CHARS_PER_FILE).trimEnd()
                    } else {
                        decoded
                    },
                    truncated = clipped
                )
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLogger.w(TAG, "Text read failed: ${t.javaClass.simpleName}")
            return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.UNREADABLE)
        } finally {
            temp.delete()
        }
    }

    /** A NUL byte in the head is the reliable signal that this is not a text file. */
    internal fun looksBinary(bytes: ByteArray): Boolean {
        val probe = minOf(bytes.size, SidekickAttachmentLimits.BINARY_PROBE_BYTES)
        for (i in 0 until probe) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    /**
     * UTF-8 with replacement instead of throwing: a log with one bad byte is still worth
     * reading, and a hard failure would look like the file is broken.
     */
    internal fun decodeUtf8(bytes: ByteArray): String {
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { String(bytes, Charsets.UTF_8) }
        // Editors on Windows and some exporters prepend a BOM; it renders as a stray glyph.
        return text.removePrefix("\uFEFF")
    }

    // ------------------------------------------------------------------ image

    private fun readImage(uri: Uri, name: String, mayHaveAlpha: Boolean): SidekickAttachmentResult {
        val temp = File(tempDir, "image-${System.nanoTime()}.bin")
        try {
            val copied = copyBounded(uri, temp, SidekickAttachmentLimits.MAX_IMAGE_INPUT_BYTES)
                ?: return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.UNREADABLE)
            if (copied == 0L) {
                return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.EMPTY)
            }
            if (copied > SidekickAttachmentLimits.MAX_IMAGE_INPUT_BYTES) {
                return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.TOO_LARGE)
            }
            return SidekickImageDecoder.fromFile(temp, name, flattenAlpha = mayHaveAlpha)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLogger.w(TAG, "Image read failed: ${t.javaClass.simpleName}")
            return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.UNREADABLE)
        } finally {
            temp.delete()
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Copies [uri] into [target], stopping just past [maxBytes].
     *
     * Returns the byte count, or `null` when the stream could not be opened. The caller
     * compares the count with its cap: over the cap means "too large", not "partial file",
     * so a truncated file is never mistaken for a complete one.
     */
    private fun copyBounded(uri: Uri, target: File, maxBytes: Long): Long? {
        var stream: InputStream? = null
        return try {
            stream = resolver.openInputStream(uri) ?: return null
            var total = 0L
            var zeroReads = 0
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    // A provider stream that returns 0 forever would spin here holding a
                    // temp file; a handful of consecutive zero reads means it is stuck.
                    if (read == 0) {
                        zeroReads++
                        if (zeroReads >= MAX_ZERO_READS) return null
                        continue
                    }
                    zeroReads = 0
                    total += read
                    out.write(buffer, 0, read)
                    // One buffer past the cap is enough to prove it is too large.
                    if (total > maxBytes) return total
                }
            }
            total
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLogger.w(TAG, "Copy failed: ${t.javaClass.simpleName}")
            null
        } finally {
            runCatching { stream?.close() }
            if (target.length() == 0L) target.delete()
        }
    }

    /**
     * Drops temp copies left behind by a killed process.
     *
     * Each read deletes its own file in a `finally`, so this only matters after a crash
     * mid-read; without it a crash loop could leave dozens of 20 MB files in the cache.
     */
    private fun pruneTemp() {
        runCatching {
            val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
            tempDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    companion object {
        private const val TAG = "SidekickAttachmentReader"
        private const val TEMP_DIR_NAME = "sidekick"
        private const val TEMP_MAX_AGE_MS = 60L * 60 * 1000

        /** Consecutive zero-byte reads tolerated before the stream is treated as stuck. */
        private const val MAX_ZERO_READS = 8
    }
}
