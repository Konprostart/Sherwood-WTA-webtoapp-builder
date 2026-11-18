package com.sherwood.wta.core.agent.files

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Small-file writes that a reader can never observe half-applied.
 *
 * Every write lands in a sibling `.wta-tmp-<name>` file that is flushed and fsynced
 * before a single [File.renameTo] publishes it. A crash therefore leaves either the
 * previous complete file or the new complete file — never a truncated one. The same
 * ordering the incremental APK cache uses, for the same reason: a truncated write that
 * looks like a success is worse than a write that visibly failed.
 *
 * The temp file sits in the target directory so the rename stays on one filesystem
 * (rename is only atomic within a mount). [TEMP_PREFIX] is filtered out of
 * [ProjectFileManager.listAll] so an abandoned temp from a killed process cannot show
 * up in the agent's project view.
 */
internal object AtomicFiles {

    const val TEMP_PREFIX = ".wta-tmp-"

    fun tempFor(target: File): File = File(target.parentFile, TEMP_PREFIX + target.name)

    fun isTempName(name: String): Boolean = name.startsWith(TEMP_PREFIX)

    fun writeText(target: File, text: String) {
        writeBytes(target, text.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = tempFor(target)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                // Durable before the rename: otherwise a power loss can leave the
                // published name pointing at blocks that were never flushed.
                out.fd.sync()
            }
            publish(temp, target)
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw t
        }
    }

    /**
     * Streamed copy — the source bytes never land fully in memory, so a
     * multi-hundred-MB attachment cannot OOM the app. The caller keeps ownership of
     * [input] and is expected to wrap it in `use`.
     */
    fun writeStream(target: File, input: InputStream) {
        target.parentFile?.mkdirs()
        val temp = tempFor(target)
        try {
            FileOutputStream(temp).use { fos ->
                val out = BufferedOutputStream(fos)
                input.copyTo(out)
                out.flush()
                fos.fd.sync()
            }
            publish(temp, target)
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw t
        }
    }

    fun publish(temp: File, target: File) {
        if (temp.renameTo(target)) return
        // Same-directory renames do not fail on a healthy filesystem; if the platform
        // refuses (e.g. the target was replaced concurrently), fall back to
        // delete-then-rename rather than leaving the caller with a stale file.
        target.delete()
        if (!temp.renameTo(target)) {
            runCatching { temp.delete() }
            throw IOException("Unable to publish ${target.name}")
        }
    }
}
