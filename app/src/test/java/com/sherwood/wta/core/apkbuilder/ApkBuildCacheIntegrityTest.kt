package com.sherwood.wta.core.apkbuilder

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.data.model.WebApp
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The incremental cache is the input of every REUSE_UNSIGNED / CONTENT_OVERLAY build, so a
 * cached APK that does not match its own metadata would be repackaged and signed as if it
 * were complete. These tests pin the two properties that make reuse safe: every read proves
 * the bytes, and every write publishes atomically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApkBuildCacheIntegrityTest {

    private lateinit var context: Context
    private lateinit var cache: ApkBuildCache
    private lateinit var app: WebApp
    private lateinit var config: ApkConfig
    private lateinit var template: File
    private lateinit var unsigned: File

    private val packageName = "com.demo.integrity"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        cache = ApkBuildCache(context)
        cache.clearAll()
        app = WebApp(id = 9001, name = "Integrity", url = "https://example.com")
        config = ApkConfig(
            meta = MetaBlock(
                appName = "Integrity",
                packageName = packageName,
                targetUrl = "https://example.com",
                versionCode = 1,
                versionName = "1.0",
                appType = "WEB"
            )
        )
        template = File(context.cacheDir, "integrity-template.apk").apply {
            writeBytes(ByteArray(32) { 7 })
        }
        unsigned = File(context.cacheDir, "integrity-unsigned.apk").apply {
            writeBytes(ByteArray(256) { 3 })
        }
    }

    @After
    fun tearDown() {
        cache.clearAll()
    }

    private fun plan(cancellation: CoroutineContext = EmptyCoroutineContext) = cache.plan(
        webApp = app,
        packageName = packageName,
        config = config,
        templateApk = template,
        encryptionEnabled = false,
        abiFilters = emptyList(),
        projectDirs = emptyList(),
        mediaContentPath = null,
        splashMediaPath = null,
        bgmPlaylistPaths = emptyList(),
        htmlFiles = emptyList(),
        galleryItems = emptyList(),
        errorPageMediaPath = null,
        forceFullRebuild = false,
        cancellation = cancellation
    )

    private fun save(cancellation: CoroutineContext = EmptyCoroutineContext): IncrementalPlan {
        val first = plan()
        cache.saveUnsigned(
            webApp = app,
            packageName = packageName,
            unsignedApk = unsigned,
            identityFingerprint = first.identityFingerprint,
            contentFingerprint = first.contentFingerprint,
            shellTemplateId = first.shellTemplateId,
            cancellation = cancellation
        )
        return first
    }

    private fun entryDir(): File = File(context.filesDir, "apk_build_cache/app_9001")
    private fun cachedApk(): File = File(entryDir(), "base_unsigned.apk")
    private fun metaFile(): File = File(entryDir(), "meta.json")

    @Test
    fun `a cached APK whose bytes changed is evicted instead of reused`() {
        save()
        assertThat(plan().mode).isEqualTo(IncrementalBuildMode.REUSE_UNSIGNED)

        // Same length, different content: only the stored hash can catch this, which is
        // exactly the truncation/corruption a killed process would leave behind.
        cachedApk().writeBytes(ByteArray(256) { 9 })

        val replanned = plan()
        assertThat(replanned.mode).isEqualTo(IncrementalBuildMode.FULL)
        assertThat(replanned.reason).isEqualTo("cachedUnsignedCorrupt")
        // The unusable entry is removed, not kept to be re-hashed on every future build.
        assertThat(entryDir().exists()).isFalse()
    }

    @Test
    fun `a truncated cached APK is evicted instead of reused`() {
        save()
        val complete = cachedApk().length()
        cachedApk().writeBytes(ByteArray((complete / 2).toInt()) { 3 })

        val replanned = plan()
        assertThat(replanned.mode).isEqualTo(IncrementalBuildMode.FULL)
        assertThat(replanned.reason).isEqualTo("cachedUnsignedCorrupt")
        assertThat(entryDir().exists()).isFalse()
    }

    @Test
    fun `an entry without a stored hash is never reused`() {
        save()
        val meta = JSONObject(metaFile().readText()).apply { remove("unsignedSha256") }
        metaFile().writeText(meta.toString())

        val replanned = plan()
        assertThat(replanned.mode).isEqualTo(IncrementalBuildMode.FULL)
        assertThat(replanned.reason).isEqualTo("cachedUnsignedUnverifiable")
        assertThat(entryDir().exists()).isFalse()
    }

    @Test
    fun `save records the hash of the bytes it wrote and leaves no temp files`() {
        save()

        val stored = cachedApk()
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(stored.readBytes())
            .joinToString("") { "%02x".format(it) }
        val meta = JSONObject(metaFile().readText())
        assertThat(meta.getString("unsignedSha256")).isEqualTo(expectedSha)
        assertThat(meta.getLong("unsignedSize")).isEqualTo(stored.length())
        // A leftover *.tmp would mean a previous publish did not complete atomically.
        assertThat(entryDir().listFiles()!!.map { it.name })
            .containsExactly("base_unsigned.apk", "meta.json")
    }

    @Test
    fun `planning honours cancellation of the calling job`() {
        val cancelled = Job().apply { cancel() }
        val failure = runCatching { plan(cancellation = cancelled) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `a cancelled save leaves the previous entry intact`() {
        save()
        val before = cachedApk().readBytes()
        val beforePlan = plan()
        assertThat(beforePlan.mode).isEqualTo(IncrementalBuildMode.REUSE_UNSIGNED)

        // Different bytes with the same fingerprints: if the cancelled copy landed, the
        // next plan would evict the entry instead of reusing it.
        unsigned.writeBytes(ByteArray(256) { 42 })
        val cancelled = Job().apply { cancel() }
        val second = plan()
        val failure = runCatching {
            cache.saveUnsigned(
                webApp = app,
                packageName = packageName,
                unsignedApk = unsigned,
                identityFingerprint = second.identityFingerprint,
                contentFingerprint = second.contentFingerprint,
                shellTemplateId = second.shellTemplateId,
                cancellation = cancelled
            )
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)

        assertThat(cachedApk().readBytes().toList()).isEqualTo(before.toList())
        assertThat(File(entryDir(), "base_unsigned.apk.tmp").exists()).isFalse()
        assertThat(File(entryDir(), "meta.json.tmp").exists()).isFalse()
        assertThat(plan().mode).isEqualTo(IncrementalBuildMode.REUSE_UNSIGNED)
    }
}
