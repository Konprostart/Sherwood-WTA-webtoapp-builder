package com.sherwood.wta.core.studio

import com.android.aapt.Resources
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads package identity out of an Android App Bundle.
 *
 * `PackageManager.getPackageArchiveInfo()` only understands APKs, so an imported
 * AAB used to reach the vault with no package name at all — which is exactly the
 * datum the Studio needs in order to pick the correct signing identity. An AAB
 * stores its manifest as a protobuf `Resources.XmlNode` at
 * `base/manifest/AndroidManifest.xml`; those schema classes are already on the
 * classpath (the AAB export path generates them), so no dependency is added here.
 *
 * Parsing is deliberately forgiving: a malformed or unrelated archive yields null
 * and the caller reports "package name unknown" instead of guessing. A version
 * field encoded as a resource reference is reported as unknown rather than
 * resolved, because resolving it would require the bundle's resource table.
 */
object AabManifestInfo {

    data class PackageInfo(
        val packageName: String?,
        val versionCode: Long?,
        val versionName: String?,
        val minSdk: Int?,
        val targetSdk: Int?
    )

    private const val MANIFEST_ENTRY = "base/manifest/AndroidManifest.xml"

    /**
     * A real manifest is a few kilobytes. The cap is what stops a crafted bundle from
     * making this read hundreds of megabytes into the heap before the protobuf parser
     * even sees it.
     */
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024

    fun read(file: File): PackageInfo? {
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(MANIFEST_ENTRY) ?: return@use null
                // Reject on the declared size first, then read with a hard cap: the
                // declared size is untrusted too, so the read must not trust it.
                if (entry.size > MAX_MANIFEST_BYTES) return@use null
                val bytes = zip.getInputStream(entry).use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        if (buffer.size() + read > MAX_MANIFEST_BYTES) return@use null
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
                val node = Resources.XmlNode.parseFrom(bytes)
                if (!node.hasElement()) return@use null
                parse(node.element)
            }
        }.getOrNull()
    }

    private fun parse(manifest: Resources.XmlElement): PackageInfo? {
        if (!manifest.name.endsWith("manifest")) return null
        var packageName: String? = null
        var versionCode: Long? = null
        var versionName: String? = null
        for (attribute in manifest.attributeList) {
            when (attribute.name) {
                "package" -> packageName = attribute.value.ifBlank { null }
                "versionCode" -> versionCode = attribute.value.toLongOrNull()
                    ?: compiledInt(attribute)
                "versionName" -> versionName = attribute.value.ifBlank { null }
            }
        }
        var minSdk: Int? = null
        var targetSdk: Int? = null
        for (child in manifest.childList) {
            if (!child.hasElement()) continue
            val element = child.element
            if (!element.name.endsWith("uses-sdk")) continue
            for (attribute in element.attributeList) {
                when (attribute.name) {
                    "minSdkVersion" -> minSdk = attribute.value.toIntOrNull()
                    "targetSdkVersion" -> targetSdk = attribute.value.toIntOrNull()
                }
            }
        }
        if (packageName == null && versionCode == null && versionName == null) return null
        return PackageInfo(packageName, versionCode, versionName, minSdk, targetSdk)
    }

    /** Fallback for tools that only populate the compiled item of a numeric attribute. */
    private fun compiledInt(attribute: Resources.XmlAttribute): Long? {
        if (!attribute.hasCompiledItem()) return null
        val item = attribute.compiledItem
        if (!item.hasPrim()) return null
        val prim = item.prim
        return when {
            prim.hasIntDecimalValue() -> prim.intDecimalValue.toLong() and 0xFFFFFFFFL
            prim.hasIntHexadecimalValue() -> prim.intHexadecimalValue.toLong() and 0xFFFFFFFFL
            else -> null
        }
    }
}
