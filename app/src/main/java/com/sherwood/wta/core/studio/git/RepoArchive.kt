package com.sherwood.wta.core.studio.git

import com.sherwood.wta.core.logging.AppLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Extraction of a repository archive (GitHub `zipball`, Gitea `/archive/<ref>.zip`).
 *
 * A downloaded archive is untrusted input, so every rule here is a hard limit rather than
 * a best effort:
 *
 * - an entry that escapes the target directory is skipped (canonical-path check, and the
 *   comparison includes the separator so a sibling prefix cannot match);
 * - entry and byte counts are capped, and the extraction directory is removed on any
 *   failure so a partial tree can never be mistaken for a working copy;
 * - an archive that yields no files is an error — a non-zip body reportedly succeeds and
 *   produces zero entries, which must never look like an empty repository;
 * - a requested [subPath] is resolved canonically inside the extracted tree and refused
 *   when it escapes or is missing.
 *
 * It is shared by every importer so the hardening is written once.
 */
object RepoArchive {

    private const val MAX_ENTRY_COUNT = 50_000
    private const val MAX_EXTRACTED_SIZE = 1024L * 1024 * 1024

    private val SKIP_PATTERNS = setOf("__MACOSX", ".DS_Store", "Thumbs.db")

    /**
     * Extracts [zipFile] into [targetDir] (created fresh) and returns the directory that
     * holds the project, which is the single top-level directory when the host wrapped
     * the tree in one — or the requested [subPath] inside it.
     */
    fun extract(zipFile: File, targetDir: File, subPath: String? = null): File {
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        var entryCount = 0
        var fileCount = 0
        var totalBytes = 0L

        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) {
                        throw IllegalStateException("Archive has too many entries (> $MAX_ENTRY_COUNT)")
                    }
                    if (shouldSkip(entry.name)) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }

                    val target = File(targetDir, entry.name).canonicalFile
                    // The separator matters: without it, "/a/bc" would match a "/a/b" root.
                    if (!target.path.startsWith(targetDir.canonicalPath + File.separator)) {
                        AppLogger.w(TAG, "Skip unsafe zip entry: ${entry.name}")
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        fileCount++
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                totalBytes += read
                                if (totalBytes > MAX_EXTRACTED_SIZE) {
                                    throw IllegalStateException(
                                        "Extracted content larger than " +
                                            "${MAX_EXTRACTED_SIZE / 1024 / 1024} MB"
                                    )
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (fileCount == 0) {
                throw IllegalStateException("Downloaded archive contains no files")
            }
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            throw e
        }

        val unwrapped = unwrapSingleRootDir(targetDir)

        if (subPath.isNullOrBlank()) return unwrapped

        val sub = File(unwrapped, subPath).canonicalFile
        if (!sub.path.startsWith(unwrapped.canonicalPath)) {
            targetDir.deleteRecursively()
            throw IllegalStateException("Invalid sub-path: $subPath")
        }
        if (!sub.exists() || !sub.isDirectory) {
            targetDir.deleteRecursively()
            throw IllegalStateException("Sub-path not found in repository: $subPath")
        }
        return sub
    }

    private fun unwrapSingleRootDir(dir: File): File {
        val children = dir.listFiles() ?: return dir
        return if (children.size == 1 && children[0].isDirectory) children[0] else dir
    }

    private fun shouldSkip(name: String): Boolean {
        val parts = name.split('/', '\\')
        return parts.any { part -> SKIP_PATTERNS.any { it.equals(part, ignoreCase = true) } }
    }

    /** Turns a provider base URL into the bare host recorded in provenance. */
    fun hostOf(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return if (url.port == 443) {
            url.host.lowercase()
        } else {
            "${url.host.lowercase()}:${url.port}"
        }
    }

    private const val TAG = "RepoArchive"
}

/**
 * Where imported working copies live under `filesDir`.
 *
 * [CURRENT] is used for every new import. [LEGACY] is the root releases before
 * multi-provider support wrote to; it is still scanned so an existing working copy does
 * not disappear from the Studio after an upgrade. Nothing is moved between the two: a
 * silent filesystem migration would be a second failure mode on top of the upgrade.
 */
object RepoImportStorage {

    const val CURRENT = "git_imports"
    const val LEGACY = "github_imports"

    /** Every root that may contain a working copy, newest convention first. */
    fun roots(filesDir: File): List<File> =
        listOf(File(filesDir, CURRENT), File(filesDir, LEGACY))
}
