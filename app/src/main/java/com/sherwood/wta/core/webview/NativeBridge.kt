package com.sherwood.wta.core.webview

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import com.sherwood.wta.core.logging.AppLogger
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sherwood.wta.core.background.BackgroundRunService
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.notification.BridgeAlarmReceiver
import com.sherwood.wta.util.MediaSaver
import com.sherwood.wta.util.getUrlScheme
import com.sherwood.wta.util.isAllowedUrlScheme
import com.sherwood.wta.util.normalizeExternalIntentUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class GoogleSignInDelivery { DELIVER, WAIT, DROP }

class NativeBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val webViewProvider: () -> WebView? = { null },

    private val capabilities: com.sherwood.wta.data.model.NativeBridgeCapabilities =
        com.sherwood.wta.data.model.NativeBridgeCapabilities(),

    private val corsBypass: Boolean = false,

    /**
     * Used when [corsBypass] is off. CORS bypass forces `CORS_BYPASS`.
     * `LOCAL_ONLY` bridges private hosts only; `ALL` bridges every other host.
     */
    private val networkScope: String = "LOCAL_ONLY",

    private val downloadLocationMode: com.sherwood.wta.data.model.DownloadLocationMode =
        com.sherwood.wta.data.model.DownloadLocationMode.SYSTEM_DOWNLOAD,

    private val customDownloadDirUri: String = "",

    /** The app's configured origin (target URL / local base) for CORS-bypass caller checks. */
    private val appOriginUrl: String = "",

    /**
     * Page-URL source for engines without a WebView (GeckoView). Caller gates read the current
     * page URL from [webViewProvider] on the System WebView path; GeckoView has no WebView
     * instance, so its engine supplies the live navigation URL here — without it every caller
     * check sees an empty URL and rejects, killing the CORS-bypass / private-network bridge on
     * the Gecko engine.
     */
    private val callerPageUrlProvider: (() -> String?)? = null,

    /**
     * Isolates persisted folder grants. The host preview passes the app id so
     * two projects do not share a folder; a generated APK passes its package
     * name, which is already unique.
     */
    private val directoryGrantKey: String = ""
) {
    /** Scope the Gecko content script should apply. CORS bypass wins. */
    fun networkBridgeScope(): String =
        if (corsBypass) "CORS_BYPASS" else networkScope

    private val directoryStore by lazy {
        LocalDirectoryStore(
            context = context,
            grantKey = directoryGrantKey.ifBlank { context.packageName ?: "default" }
        )
    }

    /**
     * Installs [WtaDirectory] for pages that opt into the folder capability.
     * Document-start covers later navigations; the immediate evaluation covers
     * the page that is already open.
     */
    fun installDirectoryHelper(webView: WebView) {
        if (!capabilities.directory) return
        val script = LocalDirectoryScript.SOURCE
        try {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        } catch (e: Exception) {
            AppLogger.w("NativeBridge", "Directory helper document-start failed", e)
        }
        try {
            webView.evaluateJavascript(script, null)
        } catch (e: Exception) {
            AppLogger.w("NativeBridge", "Directory helper injection failed", e)
        }
    }

    /** The URL of the page currently calling into the bridge, across both engines. */
    private fun resolveCallerPageUrl(): String {
        val webView = webViewProvider() ?: return callerPageUrlProvider?.invoke().orEmpty()
        // @JavascriptInterface entry points run on the JavaBridge thread, where
        // any WebView method — getUrl() included — throws a Throwable. Read the
        // URL on the view's own thread and block briefly; a timeout just
        // degrades to the Gecko-side provider result.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return webView.url ?: callerPageUrlProvider?.invoke().orEmpty()
        }
        val latch = CountDownLatch(1)
        var pageUrl: String? = null
        if (webView.post {
                pageUrl = runCatching { webView.url }.getOrNull()
                latch.countDown()
            }) {
            latch.await(2, TimeUnit.SECONDS)
        }
        return pageUrl ?: callerPageUrlProvider?.invoke().orEmpty()
    }

    companion object {
        const val JS_INTERFACE_NAME = "NativeBridge"
        private const val PRIVATE_NETWORK_MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        private val SKIP_PROXY_HEADERS = setOf(
            "host",
            "connection",
            "content-length",
            "accept-encoding"
        )

        fun getApiDocumentation(): String = """
## NativeBridge API documentation

Plugins can call the following native capabilities via `window.NativeBridge`:

### Basic functions

#### showToast(message, duration?)
Show a Toast message
- `message`: string - Message content
- `duration`: string - Optional, "short" (default) or "long"
```javascript
NativeBridge.showToast('Operation succeeded');
NativeBridge.showToast('Please wait...', 'long');
```

#### vibrate(milliseconds?)
Trigger haptic feedback
- `milliseconds`: number - Vibration duration, default 100ms
```javascript
NativeBridge.vibrate(); // Short vibration
NativeBridge.vibrate(500); // Vibrate for 500ms
```

#### vibratePattern(pattern, repeat?)
Trigger pattern vibration
- `pattern`: string - Vibration pattern, comma-separated milliseconds, e.g. "100,200,100"
- `repeat`: number - Repeat count, -1 means no repeat
```javascript
NativeBridge.vibratePattern('100,200,100,200'); // Vibrate-pause-vibrate-pause
```

### Clipboard

#### copyToClipboard(text)
Copy text to the clipboard
- `text`: string - Text to copy
- Returns: boolean - Whether it succeeded
```javascript
const success = NativeBridge.copyToClipboard('Content to copy');
if (success) NativeBridge.showToast('Copied');
```

#### getClipboardText()
Get clipboard text (requires user authorization)
- Returns: string - Clipboard content; empty string on failure
```javascript
const text = NativeBridge.getClipboardText();
```

### Sharing

#### share(title, text, url?)
Invoke the system share sheet
- `title`: string - Share title
- `text`: string - Share content
- `url`: string - Optional, share link
```javascript
NativeBridge.share('Share title', 'Share content', 'https://example.com');
```

#### shareImage(imageUrl, title?)
Share an image
- `imageUrl`: string - Image URL
- `title`: string - Optional, share title
```javascript
NativeBridge.shareImage('https://example.com/image.jpg', 'Share image');
```

### External actions

#### openUrl(url)
Open a link in the system browser
- `url`: string - URL to open
```javascript
NativeBridge.openUrl('https://www.google.com');
```

#### openApp(packageName)
Open another app
- `packageName`: string - App package name
- Returns: boolean - Whether it succeeded
```javascript
NativeBridge.openApp('com.tencent.mm'); // Open WeChat
```

### Media saving

#### saveImageToGallery(imageUrl, filename?)
Save an image to the gallery
- `imageUrl`: string - Image URL
- `filename`: string - Optional, file name
```javascript
NativeBridge.saveImageToGallery('https://example.com/image.jpg', 'my_image.jpg');
```

#### saveVideoToGallery(videoUrl, filename?)
Save a video to the gallery
- `videoUrl`: string - Video URL
- `filename`: string - Optional, file name
```javascript
NativeBridge.saveVideoToGallery('https://example.com/video.mp4', 'my_video.mp4');
```

### Downloads

#### downloadVideo(url, filename)
Download a video file
- `url`: string - Video URL
- `filename`: string - File name
```javascript
NativeBridge.downloadVideo('https://example.com/video.mp4', 'my_video.mp4');
```

#### downloadAudio(url, filename)
Download an audio file (saved to the music directory)
- `url`: string - Audio URL
- `filename`: string - File name (recommended extension: .m4a / .mp3 / .aac)
```javascript
NativeBridge.downloadAudio('https://example.com/audio.m4a', 'my_audio.m4a');
```

#### downloadWithHeaders(url, filename, headersJson)
Download a file with custom headers (for resources that require Referer, etc.)
- `url`: string - File URL
- `filename`: string - File name
- `headersJson`: string - Headers in JSON format
```javascript
NativeBridge.downloadWithHeaders(
    'https://example.com/video.mp4',
    'video.mp4',
    JSON.stringify({ 'Referer': 'https://example.com' })
);
```

### Device info

#### getDeviceInfo()
Get device info
- Returns: string - Device info in JSON format
```javascript
const info = JSON.parse(NativeBridge.getDeviceInfo());
console.log(info.model, info.sdkVersion, info.screenWidth);
```

#### getAppInfo()
Get app info
- Returns: string - App info in JSON format
```javascript
const info = JSON.parse(NativeBridge.getAppInfo());
console.log(info.packageName, info.versionName);
```

### Security & Developer Options

#### isDeveloperOptionsEnabled()
Check if Android Developer Options is enabled in device settings
- Returns: boolean - true if enabled, false otherwise
```javascript
if (NativeBridge.isDeveloperOptionsEnabled()) {
    console.log('Developer Options is ON');
}
```

#### isAdbEnabled()
Check if USB Debugging (ADB) is enabled
- Returns: boolean - true if enabled, false otherwise
```javascript
if (NativeBridge.isAdbEnabled()) {
    console.log('USB Debugging is ON');
}
```

#### isDebuggable()
Check if the APK was built with the debuggable flag
- Returns: boolean - true if debuggable build
```javascript
if (NativeBridge.isDebuggable()) {
    console.log('This is a debug build');
}
```

#### getSecurityInfo()
Get combined security status as JSON
- Returns: string - JSON with developerOptionsEnabled, adbEnabled, isDebuggable
```javascript
const security = JSON.parse(NativeBridge.getSecurityInfo());
console.log(security.developerOptionsEnabled, security.adbEnabled, security.isDebuggable);
```

### Network status

#### isNetworkAvailable()
Check whether the network is available
- Returns: boolean
```javascript
if (NativeBridge.isNetworkAvailable()) {
    // Network is available
}
```

#### getNetworkType()
Get network type
- Returns: string - "wifi", "mobile", "none", "unknown"
```javascript
const type = NativeBridge.getNetworkType();
```

### Storage

#### saveToFile(content, filename, mimeType?)
Save content to a file
- `content`: string - File content
- `filename`: string - File name
- `mimeType`: string - Optional, MIME type
```javascript
NativeBridge.saveToFile('File content', 'note.txt', 'text/plain');
```

### Logging

#### log(message)
Output a log to Android Logcat
- `message`: string - Log content
```javascript
NativeBridge.log('Debug info');
```

### In-page find

#### findInPage(query)
Search the current page using Android WebView's native find capability
- `query`: string - Search keyword
- Returns: string - JSON status containing `numberOfMatches`, `activeMatchOrdinal`, `doneCounting`
```javascript
const state = JSON.parse(NativeBridge.findInPage('keyword'));
```

#### findNextInPage(forward)
Jump to the next/previous match
- `forward`: boolean - true for next, false for previous
```javascript
NativeBridge.findNextInPage(true);
NativeBridge.findNextInPage(false);
```

#### clearFindInPage()
Clear current find highlights
```javascript
NativeBridge.clearFindInPage();
```

### Screen orientation control

#### setOrientation(orientation)
Set screen orientation
- `orientation`: string - "portrait", "landscape", "auto" (follows sensor)
```javascript
NativeBridge.setOrientation('landscape'); // Switch to landscape
NativeBridge.setOrientation('portrait');  // Switch back to portrait
NativeBridge.setOrientation('auto');      // Follow sensor
```

#### getOrientation()
Get current screen orientation
- Returns: string - "portrait", "landscape", "unknown"
```javascript
const orientation = NativeBridge.getOrientation();
```

#### lockOrientation()
Lock current screen orientation
```javascript
NativeBridge.lockOrientation();
```

#### unlockOrientation()
Unlock screen orientation
```javascript
NativeBridge.unlockOrientation();
```

### Screen control

#### setScreenBrightness(brightness)
Set screen brightness
- `brightness`: number - Brightness value 0.0-1.0; -1 means follow system
```javascript
NativeBridge.setScreenBrightness(0.8); // Set to 80% brightness
NativeBridge.setScreenBrightness(-1);  // Follow system
```

#### setKeepScreenOn(keepOn)
Keep the screen on
- `keepOn`: boolean - true keeps it on, false restores normal
```javascript
NativeBridge.setKeepScreenOn(true);  // Keep screen on
NativeBridge.setKeepScreenOn(false); // Restore normal
```

### Fullscreen control

#### enterFullscreen()
Enter fullscreen mode (hides status bar and navigation bar)
```javascript
NativeBridge.enterFullscreen();
```

#### exitFullscreen()
Exit fullscreen mode
```javascript
NativeBridge.exitFullscreen();
```

#### isFullscreen()
Check whether fullscreen mode is active
- Returns: boolean
```javascript
if (NativeBridge.isFullscreen()) {
    // Currently in fullscreen mode
}
```

#### googleSignIn(requestId)
Sign in with an existing Google account on the device (requires enabling the "Google native sign-in" capability in the Sherwood WTA editor and configuring a Web Client ID)
- `requestId`: string - Page-defined request identifier
- Async result callback: `window.NativeBridgeGoogleSignInResult(requestId, payload)`
- payload: `{ ok: true, idToken, displayName, profilePictureUri, googleUserId }` or `{ ok: false, error, message }`
- Error codes: `DISABLED` (capability not enabled), `NO_CLIENT_ID`, `NO_ACTIVITY` (floating window or similar context), `CALLER_NOT_ALLOWED` (called from a page not belonging to the app itself), `CANCELLED` (user cancelled), `CREDENTIAL_ERROR` (device has no Google services or configuration mismatch), `UNAVAILABLE`
- **The idToken must be verified by the website backend** (audience = configured Web Client ID, signature, expiry) before a session may be established
```javascript
window.NativeBridgeGoogleSignInResult = function(requestId, payload) {
    if (payload.ok) {
        // Send payload.idToken to your own backend for verification, then establish a session
        fetch('/auth/google', { method: 'POST', body: JSON.stringify({ idToken: payload.idToken }) });
    } else {
        // Fall back to web Google sign-in
    }
};
NativeBridge.googleSignIn('sign-in-' + Date.now());
```

### Local folder

Disabled by default. After enabling "Folder" in the editor's native bridge, the system WebView injects `window.WtaDirectory`. The page calls `pick()` from a user click, which opens the system directory picker. The grant is saved within this app, so it does not need to be selected again next time. Exported apps have a different package name and need to be picked again. GeckoView does not provide this set of methods.

#### directoryInfo()
Returns `{ ok, granted, name?, writable? }`. When not granted, `granted` is false.

#### pickDirectory(requestId)
Opens the system directory picker. Returns `{ ok: true, pending: true }` immediately; the result is delivered via `window.NativeBridgeDirectoryResult(requestId, payload)`. The payload is the same as `directoryInfo()`; if the user cancels, it is `{ ok: false, error: "CANCELLED" }`.
Error codes: `DISABLED`, `CALLER_NOT_ALLOWED`, `NO_ACTIVITY` (floating window), `PERSIST_FAILED`.

#### listDirectory(relativePath)
Lists entries in the granted directory. An empty `relativePath` means the root. Returns `{ ok, entries: [{ name, directory, size }], truncated }`.

#### readDirectoryFile(relativePath, offset, length)
Reads in chunks. `length` is at most 256 KiB. Returns `{ ok, size, offset, length, data, eof }`, where `data` is base64.

#### writeDirectoryFile(relativePath, base64, append)
Writes in chunks. When `append` is false, the file is created or overwritten. Intermediate directories are created automatically. Each chunk is at most 256 KiB.

#### deleteDirectoryEntry(relativePath)
Deletes a file or subdirectory. The granted root directory itself cannot be deleted.

#### releaseDirectory()
Revokes the grant.

```javascript
const grant = await WtaDirectory.pick();
if (!grant.granted) return;
const entries = WtaDirectory.list('').entries;
const font = await WtaDirectory.read('fonts/MyFont.ttf');
await WtaDirectory.write('exports/image.png', pngBytes);
```
        """.trimIndent()

        internal fun isPrivateNetworkHost(host: String?): Boolean {
            val normalized = host
                ?.trim()
                ?.trim('[', ']')
                ?.lowercase(Locale.ROOT)
                ?: return false

            if (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "10.0.2.2") {
                return true
            }
            // 0.0.0.0 routes to the loopback interface on Linux/Android.
            if (normalized == "0.0.0.0") return true
            if (normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
            if (normalized.endsWith(".local")) return true

            if (normalized.contains(":")) {
                // IPv6 literal (HttpUrl yields bracketed IPv6 hosts without brackets).
                // Cover ULA fc00::/7 (fd00::/8 in practice) and link-local fe80::/10;
                // the first hextet decides both. A colon only occurs in IPv6 literals,
                // so a hostname like "a:b" never reaches this branch.
                val hextet = normalized.substringBefore(":").toIntOrNull(16)
                return hextet != null && (
                    hextet in 0xfc00..0xfdff ||   // fc00::/7 unique-local
                        hextet in 0xfe80..0xfebf    // fe80::/10 link-local
                    )
            }

            val parts = normalized.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false

            return when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 127 -> true
                // Link-local: cloud metadata endpoints (169.254.169.254 et al.).
                octets[0] == 169 && octets[1] == 254 -> true
                else -> false
            }
        }

        internal fun isPrivateNetworkUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme != "http" && scheme != "https") return false
            return isPrivateNetworkHost(uri.host)
        }

        internal fun isHttpUrl(url: String): Boolean {
            val scheme = runCatching { URI(url).scheme }.getOrNull()?.lowercase(Locale.ROOT)
            return scheme == "http" || scheme == "https"
        }

        /**
         * Whether a resolved [InetAddress] lands in private/loopback/link-local
         * territory — the DNS-rebinding counterpart to [isPrivateNetworkUrl],
         * which can only classify the hostname string. `InetAddress` covers
         * loopback/any-local/link-local/site-local/multicast; [isPrivateNetworkHost]
         * adds the IPv6 ULA range (fc00::/7) that `isSiteLocalAddress` misses.
         */
        internal fun isRebindingBlockedAddress(addr: java.net.InetAddress): Boolean {
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress
            ) {
                return true
            }
            // Scoped literal suffix ("fe80::1%eth0") must not reach the classifier.
            return isPrivateNetworkHost(addr.hostAddress?.substringBefore('%'))
        }

        /**
         * Whether [pageUrl] belongs to the app's configured origin ([appOriginUrl] — the
         * target URL, or the local server base for packaged/server app types). Same host
         * or a subdomain counts; anything else is a foreign page riding the WebView.
         */
        internal fun isSameSiteOrSubdomain(pageHost: String, originHost: String): Boolean {
            if (originHost.isBlank() || pageHost.isBlank()) return false
            val p = pageHost.trim('[', ']').lowercase(Locale.ROOT).trimEnd('.')
            val o = originHost.trim('[', ']').lowercase(Locale.ROOT).trimEnd('.')
            if (p == o) return true
            return p.endsWith(".$o")
        }
    }

    private val privateNetworkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .connectionSpecs(
                listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                        .build(),
                    ConnectionSpec.CLEARTEXT
                )
            )
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Fail-closed DNS for remote-page callers: a hostname that resolves to a
     * private/loopback/link-local address is refused before any byte leaves the
     * device — the URL-string gate cannot see resolutions (DNS rebinding would
     * otherwise launder a public-looking name into a local target).
     */
    private val publicOnlyDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val resolved = okhttp3.Dns.SYSTEM.lookup(hostname)
            resolved.firstOrNull { isRebindingBlockedAddress(it) }?.let { blocked ->
                throw java.net.UnknownHostException(
                    "Blocked DNS rebinding: $hostname resolves to private address ${blocked.hostAddress}"
                )
            }
            return resolved
        }
    }

    private fun isAppOriginCallerPage(pageUrl: String): Boolean {
        val origin = appOriginUrl.takeIf { it.isNotBlank() } ?: return false
        val pageHost = runCatching { URI(pageUrl).host }.getOrNull() ?: return false
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        return isSameSiteOrSubdomain(pageHost, originHost)
    }

    @JavascriptInterface
    fun showToast(message: String, duration: String = "short") {
        if (!capabilities.toast) return
        scope.launch(Dispatchers.Main) {
            val length = if (duration == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context, message, length).show()
        }
    }

    @JavascriptInterface
    fun vibrate(milliseconds: Long = 100) {
        if (!capabilities.vibration) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(milliseconds)
            }
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Vibration failed", e)
        }
    }

    @JavascriptInterface
    fun vibratePattern(pattern: String, repeat: Int = -1) {
        if (!capabilities.vibration) return
        try {
            val timings = pattern.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
            if (timings.isEmpty()) return

            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, repeat))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, repeat)
            }
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "mode vibration failed", e)
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String): Boolean {
        if (!capabilities.clipboard) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("text", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Duplication failed", e)
            false
        }
    }

    @JavascriptInterface
    fun getClipboardText(): String {
        if (!capabilities.clipboard) return ""
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to read clipboard", e)
            ""
        }
    }

    @JavascriptInterface
    fun share(title: String, text: String, url: String = "") {
        if (!capabilities.openExternal) return
        scope.launch(Dispatchers.Main) {
            try {
                val shareText = if (url.isNotBlank()) "$text\n$url" else text
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, title).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Share failed", e)
                Toast.makeText(context, Strings.shareFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun shareImage(imageUrl: String, title: String = Strings.shareImage) {
        if (!capabilities.openExternal) return
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, Strings.preparingShare, Toast.LENGTH_SHORT).show()
        }

        share(title, imageUrl)
    }

    private val ALLOWED_SCHEMES = setOf("http", "https", "tel", "mailto", "sms", "geo")

    @JavascriptInterface
    fun openUrl(url: String) {
        if (!capabilities.openExternal) return
        scope.launch(Dispatchers.Main) {
            try {
                val safeUrl = normalizeExternalIntentUrl(url)
                if (safeUrl.isEmpty()) {
                    AppLogger.w("NativeBridge", "Blocked invalid or dangerous URL in openUrl: $url")
                    Toast.makeText(context, Strings.cannotOpenLink, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val scheme = getUrlScheme(safeUrl)
                if (!isAllowedUrlScheme(safeUrl, ALLOWED_SCHEMES)) {
                    AppLogger.w("NativeBridge", "Blocked openUrl with disallowed scheme: $scheme")
                    Toast.makeText(context, Strings.cannotOpenLink, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to open link", e)
                Toast.makeText(context, Strings.cannotOpenLink, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun openApp(packageName: String): Boolean {
        if (!capabilities.openExternal) return false
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to open app", e)
            false
        }
    }

    @JavascriptInterface
    fun getNotificationPermissionState(): String {
        if (!capabilities.notification) return "denied"
        return try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                return "denied"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) "granted" else "default"
            } else {
                "granted"
            }
        } catch (e: Exception) {
            AppLogger.w("NativeBridge", "Failed to read notification-permission state", e)
            "default"
        }
    }

    @JavascriptInterface
    fun requestNotificationPermission(): String {
        if (!capabilities.notification) return "denied"
        val current = getNotificationPermissionState()
        if (current == "granted" || current == "denied") return current

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activity = context as? Activity
            if (activity != null) {
                scope.launch(Dispatchers.Main) {
                    try {
                        ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                            9001
                        )
                    } catch (e: Exception) {
                        AppLogger.w("NativeBridge", "Notification-permission request failed", e)
                    }
                }
            }
        }
        return getNotificationPermissionState()
    }

    @JavascriptInterface
    fun showWebNotification(title: String, body: String = "", tag: String = ""): Boolean {
        if (!capabilities.notification) return false
        return try {
            if (getNotificationPermissionState() != "granted") return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false

            val channelId = "webapp_notifications"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    channelId,
                    Strings.webAppNotificationChannelName,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(channel)
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
            val pendingIntent = PendingIntent.getActivity(
                context,
                (tag.ifBlank { title }).hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(title.ifBlank { context.applicationInfo.loadLabel(context.packageManager).toString() })
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            try {
                NotificationManagerCompat.from(context).notify((tag.ifBlank { "$title|$body" }).hashCode(), notification)
            } catch (e: SecurityException) {
                AppLogger.e("NativeBridge", "Notification permission unavailable", e)
                return false
            }
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to show web notification", e)
            false
        }
    }

    @JavascriptInterface
    fun saveImageToGallery(imageUrl: String, filename: String = "") {
        if (!capabilities.download) return
        val finalFilename = filename.ifBlank { "IMG_${System.currentTimeMillis()}.jpg" }

        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, Strings.savingImage, Toast.LENGTH_SHORT).show()
            }

            val result = MediaSaver.saveFromUrl(context, imageUrl, finalFilename, "image/jpeg")

            withContext(Dispatchers.Main) {
                when (result) {
                    is MediaSaver.SaveResult.Success -> {
                        Toast.makeText(context, Strings.imageSavedToGallery, Toast.LENGTH_SHORT).show()
                    }
                    is MediaSaver.SaveResult.Error -> {
                        Toast.makeText(context, Strings.saveFailedWithReason.replace("%s", result.message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun saveVideoToGallery(videoUrl: String, filename: String = "") {
        if (!capabilities.download) return
        val finalFilename = filename.ifBlank { "VID_${System.currentTimeMillis()}.mp4" }

        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, Strings.savingVideo, Toast.LENGTH_SHORT).show()
            }

            val result = MediaSaver.saveFromUrl(context, videoUrl, finalFilename, "video/mp4")

            withContext(Dispatchers.Main) {
                when (result) {
                    is MediaSaver.SaveResult.Success -> {
                        Toast.makeText(context, Strings.videoSavedToGallery, Toast.LENGTH_SHORT).show()
                    }
                    is MediaSaver.SaveResult.Error -> {
                        Toast.makeText(context, Strings.saveFailedWithReason.replace("%s", result.message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        if (!capabilities.deviceInfo) return "{}"
        return try {
            val displayMetrics = context.resources.displayMetrics

            val json = org.json.JSONObject()
            json.put("model", Build.MODEL)
            json.put("manufacturer", Build.MANUFACTURER)
            json.put("brand", Build.BRAND)
            json.put("sdkVersion", Build.VERSION.SDK_INT)
            json.put("androidVersion", Build.VERSION.RELEASE)
            json.put("screenWidth", displayMetrics.widthPixels)
            json.put("screenHeight", displayMetrics.heightPixels)
            json.put("density", displayMetrics.density.toDouble())
            json.put("language", java.util.Locale.getDefault().language)
            json.toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        if (!capabilities.deviceInfo) return "{}"
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val json = org.json.JSONObject()
            json.put("packageName", context.packageName)
            json.put("versionName", packageInfo.versionName ?: "")
            json.put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong())
            json.toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    @JavascriptInterface
    fun isDeveloperOptionsEnabled(): Boolean {
        if (!capabilities.securityInfo) return false
        return try {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to read developer options status", e)
            false
        }
    }

    @JavascriptInterface
    fun isAdbEnabled(): Boolean {
        if (!capabilities.securityInfo) return false
        return try {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED, 0
            ) == 1
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to read ADB status", e)
            false
        }
    }

    @JavascriptInterface
    fun isDebuggable(): Boolean {
        if (!capabilities.securityInfo) return false
        return try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun getSecurityInfo(): String {
        if (!capabilities.securityInfo) return "{}"
        return try {
            val devOptions = try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
                ) == 1
            } catch (e: Exception) { false }

            val adbEnabled = try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Global.ADB_ENABLED, 0
                ) == 1
            } catch (e: Exception) { false }

            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

            val json = org.json.JSONObject()
            json.put("developerOptionsEnabled", devOptions)
            json.put("adbEnabled", adbEnabled)
            json.put("isDebuggable", debuggable)
            json.toString()
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to get security info", e)
            "{}"
        }
    }

    @JavascriptInterface
    fun areNotificationsEnabled(): Boolean {
        if (!capabilities.notification) return false
        return try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to check notification state", e)
            false
        }
    }

    @JavascriptInterface
    fun openNotificationSettings(): Boolean {
        if (!capabilities.notification) return false
        return try {
            val intent = Intent().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    action = "android.settings.APP_NOTIFICATION_SETTINGS"
                    putExtra("app_package", context.packageName)
                    putExtra("app_uid", context.applicationInfo.uid)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to open notification settings", e)
            false
        }
    }

    @JavascriptInterface
    fun scheduleNotification(jsonPayload: String): Boolean {
        if (!capabilities.notificationScheduled) return false
        return try {
            val json = org.json.JSONObject(jsonPayload)
            val title = json.optString("title").ifBlank { getAppLabel() }
            val body = json.optString("body").ifBlank { "Notification" }
            val tag = json.optString("tag").ifBlank { "bridge_${System.currentTimeMillis()}" }
            val channelId = json.optString("channelId").ifBlank { "webapp_bridge_notifications" }
            val deepLink = json.optString("deepLink", "")
            val playSound = json.optBoolean("playSound", true)
            val delaySec = json.optLong("delaySec", 0L)
            val triggerAt = json.optLong("triggerAt", 0L)

            val triggerAtMillis = when {
                triggerAt > 10_000_000_000L -> triggerAt
                triggerAt > 0 -> triggerAt * 1000L
                delaySec > 0 -> System.currentTimeMillis() + (delaySec * 1000L)
                else -> System.currentTimeMillis() + 1000L
            }

            scheduleBridgeAlarm(triggerAtMillis, title, body, tag, channelId, deepLink, playSound)
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to schedule notification", e)
            false
        }
    }

    @JavascriptInterface
    fun cancelNotification(tag: String): Boolean {
        if (!capabilities.notification) return false
        return try {
            NotificationManagerCompat.from(context).cancel(tag.hashCode())
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to cancel notification", e)
            false
        }
    }

    @JavascriptInterface
    fun cancelAllNotifications(): Boolean {
        if (!capabilities.notification) return false
        return try {
            NotificationManagerCompat.from(context).cancelAll()
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to cancel all notifications", e)
            false
        }
    }

    @JavascriptInterface
    fun updateNotificationProgress(tag: String, progress: Int, max: Int): Boolean {
        if (!capabilities.notificationPersistent) return false
        return try {
            val channelId = "webapp_bridge_notifications"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Web App Notifications",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(getAppLabel())
                .setProgress(max, progress, false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            NotificationManagerCompat.from(context).notify(tag.hashCode(), builder.build())
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to update notification progress", e)
            false
        }
    }

    @JavascriptInterface
    fun canScheduleExactAlarms(): Boolean {
        if (!capabilities.notificationScheduled) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.canScheduleExactAlarms() ?: false
            } else {
                true
            }
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to check exact alarm capability", e)
            false
        }
    }

    @JavascriptInterface
    fun startBackgroundService(jsonPayload: String): Boolean {
        if (!capabilities.notificationPersistent) return false
        return try {
            val json = org.json.JSONObject(jsonPayload)
            val title = json.optString("title").ifBlank { getAppLabel() }
            val body = json.optString("body").ifBlank { "Running in background" }
            BackgroundRunService.start(
                context = context,
                appName = getAppLabel(),
                notificationTitle = title,
                notificationContent = body,
                keepCpuAwake = true
            )
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to start background service", e)
            false
        }
    }

    @JavascriptInterface
    fun stopBackgroundService(): Boolean {
        if (!capabilities.notificationPersistent) return false
        return try {
            BackgroundRunService.stop(context)
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to stop background service", e)
            false
        }
    }

    @JavascriptInterface
    fun isBackgroundServiceRunning(): Boolean {
        if (!capabilities.notificationPersistent) return false
        return try {
            BackgroundRunService.isServiceRunning()
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun isDozeMode(): Boolean {
        if (!capabilities.networkInfo) return false
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isDeviceIdleMode ?: false
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to check doze mode", e)
            false
        }
    }

    @JavascriptInterface
    fun isIgnoringBatteryOptimizations(): Boolean {
        if (!capabilities.networkInfo) return false
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to check battery optimization state", e)
            false
        }
    }

    @JavascriptInterface
    fun openBatteryOptimizationSettings(): Boolean {
        if (!capabilities.networkInfo) return false
        return try {
            val intent = Intent().apply {
                action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                } else {
                    Settings.ACTION_SETTINGS
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to open battery optimization settings", e)
            false
        }
    }

    @JavascriptInterface
    fun getAppState(): String {
        if (!capabilities.networkInfo) return "unknown"
        return try {
            if (isAppInForeground()) "foreground" else "background"
        } catch (e: Exception) {
            "unknown"
        }
    }

    @JavascriptInterface
    fun isAppInForeground(): Boolean {
        if (!capabilities.networkInfo) return false
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val process = activityManager.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() && it.processName == context.packageName }
                ?: return false
            process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to check foreground state", e)
            false
        }
    }

    private fun scheduleBridgeAlarm(
        triggerAtMillis: Long,
        title: String,
        body: String,
        tag: String,
        channelId: String,
        deepLink: String,
        playSound: Boolean
    ): Boolean {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false
            val safeTriggerAt = triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 1000L)

            val receiverIntent = Intent(context, BridgeAlarmReceiver::class.java).apply {
                action = BridgeAlarmReceiver.ACTION_SCHEDULED_NOTIFICATION
                putExtra(BridgeAlarmReceiver.EXTRA_TITLE, title)
                putExtra(BridgeAlarmReceiver.EXTRA_BODY, body)
                putExtra(BridgeAlarmReceiver.EXTRA_CHANNEL_ID, channelId)
                putExtra(BridgeAlarmReceiver.EXTRA_TAG, tag)
                putExtra(BridgeAlarmReceiver.EXTRA_DEEP_LINK, deepLink)
                putExtra(BridgeAlarmReceiver.EXTRA_PLAY_SOUND, playSound)
            }

            val requestCode = "$tag|$safeTriggerAt".hashCode()
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                receiverIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {

                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTriggerAt, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTriggerAt, pendingIntent)
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, safeTriggerAt, pendingIntent)
            }
            AppLogger.d("NativeBridge", "Scheduled alarm: tag=$tag, triggerAt=$safeTriggerAt")
            true
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Failed to schedule bridge alarm", e)
            false
        }
    }

    private fun getAppLabel(): String {
        return try {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        } catch (_: Exception) {
            "Sherwood WTA"
        }
    }

    @JavascriptInterface
    fun isNetworkAvailable(): Boolean {
        if (!capabilities.networkInfo) return false
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun getNetworkType(): String {
        if (!capabilities.networkInfo) return "unknown"
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return "none"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "none"
            when {
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private val downloadBridge by lazy { DownloadBridge(context, scope, downloadLocationMode, customDownloadDirUri) }

    @JavascriptInterface
    fun saveToFile(content: String, filename: String, mimeType: String = "text/plain") {
        if (!capabilities.download) return
        scope.launch(Dispatchers.IO) {
            try {
                val base64Data = android.util.Base64.encodeToString(
                    content.toByteArray(Charsets.UTF_8),
                    android.util.Base64.DEFAULT
                )
                downloadBridge.saveBase64File(base64Data, filename, mimeType)
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to save file", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, Strings.saveFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun log(message: String) {
        if (!capabilities.logging) return
        AppLogger.d("NativeBridge", "[JS] $message")
    }

    @JavascriptInterface
    fun httpRequest(requestJson: String): String {
        if (!capabilities.privateNetwork && !corsBypass) {
            return privateNetworkBridgeError(
                "disabled",
                "Native private network capability disabled. " +
                    "Enable the private network (or CORS bypass) capability for the native bridge in the Sherwood WTA editor and rebuild."
            )
        }
        return try {
            val request = org.json.JSONObject(requestJson)
            val url = request.optString("url").trim()
            // Redirect policy: set by the caller/target gates below, applied to every
            // follow-up request by a network interceptor so a redirect chain cannot
            // cross the private/public boundary the initial URL was classified against.
            var redirectGate: ((okhttp3.HttpUrl) -> Boolean)? = null
            // DNS-rebinding guard for remote-page callers: the URL gate classifies
            // the hostname string, but a public-looking name can resolve to a
            // private address (127.0.0.1, LAN, cloud metadata). When set, the
            // request's Dns rejects any resolution landing on a private address.
            var requirePublicResolution = false
            if (corsBypass) {
                if (!isHttpUrl(url)) {
                    AppLogger.w("NativeBridge", "Blocked CORS-bypass request to non-HTTP(S) URL: $url")
                    return privateNetworkBridgeError("URL_NOT_ALLOWED", "Only HTTP(S) URLs are allowed")
                }
                // The CORS-bypass bridge is a full cross-origin reader — gate it on the
                // calling page. Local pages (packaged files, local server apps) may reach
                // anything; the app's own remote origin may bypass CORS for internet APIs
                // but must not probe the phone's local services; anything else (random
                // iframes / navigations) is rejected outright.
                val pageUrl = resolveCallerPageUrl()
                // about:blank counts as local only for packaged/local-origin apps: a
                // remote app's window.open("about:blank") popup inherits the opener's
                // remote origin, so granting it the blank-page classification would hand
                // the opener private-network reach it was just denied above.
                val appOriginIsLocal = appOriginUrl.isNotBlank() && (
                    isPrivateNetworkUrl(appOriginUrl) ||
                        appOriginUrl.startsWith("file:") ||
                        appOriginUrl.startsWith("content://")
                    )
                val pageIsLocal = isPrivateNetworkUrl(pageUrl) ||
                    pageUrl.startsWith("file:") ||
                    pageUrl.startsWith("content://") ||
                    (pageUrl.startsWith("about:blank") && appOriginIsLocal)
                val pageIsAppOrigin = isAppOriginCallerPage(pageUrl)
                if (!pageIsLocal && !pageIsAppOrigin) {
                    AppLogger.w("NativeBridge", "Blocked CORS-bypass request from non-app page: $pageUrl -> $url")
                    return privateNetworkBridgeError(
                        "CALLER_NOT_ALLOWED",
                        "Only the app's own pages can use the CORS-bypass bridge"
                    )
                }
                if (!pageIsLocal && isPrivateNetworkUrl(url)) {
                    AppLogger.w("NativeBridge", "Blocked CORS-bypass request to local network from remote page: $pageUrl -> $url")
                    return privateNetworkBridgeError(
                        "URL_NOT_ALLOWED",
                        "CORS-bypass requests to local network addresses require a local page"
                    )
                }
                // The gate above classifies the initial URL only; OkHttp follows redirects
                // silently, and a public URL 302-ing into 127.0.0.1 (or a private URL
                // redirecting out) would bypass it. The network interceptor re-applies
                // the same rule to every follow-up request.
                if (!pageIsLocal) {
                    redirectGate = { target -> !isPrivateNetworkUrl(target.toString()) }
                    requirePublicResolution = true
                }
            } else if (!isPrivateNetworkUrl(url)) {
                AppLogger.w("NativeBridge", "Blocked private-network bridge request to non-private URL: $url")
                return privateNetworkBridgeError("URL_NOT_ALLOWED", "Only private network HTTP(S) URLs are allowed")
            }
            if (!corsBypass) {
                val pageUrl = resolveCallerPageUrl()
                if (!isPrivateNetworkUrl(pageUrl)) {
                    AppLogger.w("NativeBridge", "Blocked private-network bridge request from non-local page: $pageUrl -> $url")
                    return privateNetworkBridgeError("CALLER_NOT_ALLOWED", "Only packaged local pages can use the private network bridge")
                }
                // Every hop must stay inside the private network: the bridge exists to
                // let local server pages reach other local services, never to launder a
                // private URL into an arbitrary public read.
                redirectGate = { target -> isPrivateNetworkUrl(target.toString()) }
            }

            val method = request.optString("method", "GET").uppercase(Locale.ROOT)
            if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
                return privateNetworkBridgeError("METHOD_NOT_ALLOWED", "Unsupported HTTP method: $method")
            }

            val bodyBase64 = request.optString("bodyBase64", "")
            val bodyBytes = if (bodyBase64.isBlank()) {
                ByteArray(0)
            } else {
                Base64.decode(bodyBase64, Base64.NO_WRAP)
            }

            val headers = request.optJSONObject("headers")
            val contentType = headers?.optString("content-type")
                ?.takeIf { it.isNotBlank() }
                ?: headers?.optString("Content-Type")?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"
            val requestBody = if (method in setOf("POST", "PUT", "PATCH") || bodyBytes.isNotEmpty()) {
                bodyBytes.toRequestBody(contentType.toMediaTypeOrNull())
            } else {
                null
            }

            val builder = Request.Builder().url(url)
            headers?.keys()?.forEach { key ->
                val normalizedKey = key.lowercase(Locale.ROOT)
                if (normalizedKey !in SKIP_PROXY_HEADERS) {
                    val value = headers.optString(key)
                    if (value.isNotBlank()) builder.header(key, value)
                }
            }
            builder.header("X-WebToApp-Private-Network-Bridge", "1")
            builder.method(method, requestBody)

            val client = if (redirectGate != null || requirePublicResolution) {
                privateNetworkHttpClient.newBuilder().apply {
                    if (requirePublicResolution) dns(publicOnlyDns)
                    redirectGate?.let { gate ->
                        addNetworkInterceptor { chain ->
                            val followUp = chain.request()
                            if (!gate(followUp.url)) {
                                throw RedirectBlockedByGateException(
                                    "Blocked redirect to ${followUp.url.host}: target class changed mid-chain"
                                )
                            }
                            chain.proceed(followUp)
                        }
                    }
                }.build()
            } else {
                privateNetworkHttpClient
            }

            client.newCall(builder.build()).execute().use { response ->
                val responseBody = response.body
                val bytes = responseBody?.byteStream()?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > PRIVATE_NETWORK_MAX_RESPONSE_BYTES) {
                            return privateNetworkBridgeError(
                                "RESPONSE_TOO_LARGE",
                                "Private network response exceeds ${PRIVATE_NETWORK_MAX_RESPONSE_BYTES / 1024 / 1024}MB"
                            )
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)

                val responseHeaders = org.json.JSONObject()
                response.headers.names().forEach { name ->
                    responseHeaders.put(name, response.headers.values(name).joinToString(", "))
                }

                org.json.JSONObject().apply {
                    put("ok", true)
                    put("status", response.code)
                    put("statusText", response.message)
                    put("url", response.request.url.toString())
                    put("headers", responseHeaders)
                    put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }.toString()
            }
        } catch (e: RedirectBlockedByGateException) {
            AppLogger.w("NativeBridge", "Private network bridge redirect blocked", e)
            privateNetworkBridgeError("REDIRECT_NOT_ALLOWED", e.message ?: "Redirect crossed the private/public boundary")
        } catch (e: Exception) {
            AppLogger.e("NativeBridge", "Private network HTTP bridge request failed", e)
            privateNetworkBridgeError("REQUEST_FAILED", e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Async variant of [httpRequest]: identical semantics, but the network call runs on
     * the IO dispatcher and the result is delivered back into the page through
     * `window.__wtaNativeHttpResponse(callbackId, json)`. The synchronous variant blocks
     * the calling JS thread for the entire HTTP round-trip — pages polling APIs through
     * the bridge froze for the duration of every request.
     */
    @JavascriptInterface
    fun httpRequestAsync(requestJson: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            val result = httpRequest(requestJson)
            val quotedId = com.sherwood.wta.util.JsStrings.quote(callbackId)
            val quotedResult = com.sherwood.wta.util.JsStrings.quote(result)
            withContext(Dispatchers.Main) {
                try {
                    webViewProvider()?.evaluateJavascript(
                        "window.__wtaNativeHttpResponse && window.__wtaNativeHttpResponse($quotedId, $quotedResult);",
                        null
                    )
                } catch (e: Exception) {
                    AppLogger.w("NativeBridge", "Failed to deliver async bridge response", e)
                }
            }
        }
    }

    /** Raised by the per-call redirect gate interceptor: a redirect tried to cross the private/public boundary. */
    private class RedirectBlockedByGateException(message: String) : java.io.IOException(message)

    private fun privateNetworkBridgeError(code: String, message: String): String {
        return org.json.JSONObject().apply {
            put("ok", false)
            put("error", code)
            put("message", message)
        }.toString()
    }

    /**
     * Native Google sign-in via the Jetpack Credential Manager: shows the system account
     * picker with the device's Google accounts and returns a Google-signed ID token.
     * The page receives the result asynchronously through
     * `window.NativeBridgeGoogleSignInResult(requestId, payload)`; the ID token must be
     * validated by the site's backend (audience = the configured Web client ID).
     *
     * Fail-soft on every path the feature cannot work on: disabled capability, missing
     * client ID, non-Activity context (floating window / Gecko bridge), devices without
     * Google Play Services, F-Droid builds that omit the Play Services client, and user
     * cancellation — the page always gets a JSON reply.
     */
    @JavascriptInterface
    fun googleSignIn(requestId: String) {
        if (!capabilities.googleSignIn) {
            dispatchGoogleSignInResult(requestId, googleSignInError("DISABLED", "Native Google sign-in capability is disabled"))
            return
        }
        if (capabilities.googleSignInClientId.isBlank()) {
            dispatchGoogleSignInResult(requestId, googleSignInError("NO_CLIENT_ID", "No Google Web client ID configured in Sherwood WTA"))
            return
        }
        val activity = context as? Activity
        if (activity == null) {
            dispatchGoogleSignInResult(
                requestId,
                googleSignInError("NO_ACTIVITY", "Google sign-in is unavailable in this window context")
            )
            return
        }

        // Same caller-page gate as httpRequest: only the app's own pages (or a
        // packaged/local origin) may drive the Credential Manager flow — a foreign
        // iframe embedded in the page must not be able to trigger it.
        val callerPageUrl = resolveCallerPageUrl()
        val callerIsLocal = isPrivateNetworkUrl(callerPageUrl) ||
            callerPageUrl.startsWith("file:") ||
            callerPageUrl.startsWith("content://")
        val callerIsAppOrigin = isAppOriginCallerPage(callerPageUrl)
        if (!callerIsLocal && !callerIsAppOrigin) {
            dispatchGoogleSignInResult(
                requestId,
                googleSignInError("CALLER_NOT_ALLOWED", "Only the app's own pages can start native Google sign-in")
            )
            return
        }
        // The account picker is async: by the time it returns, the WebView may have
        // navigated elsewhere. Only deliver into the origin that started the flow
        // (or another local packaged page).
        val callerOrigin = runCatching {
            URI(callerPageUrl).let { u ->
                "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
            }
        }.getOrNull()

        scope.launch(Dispatchers.Main) {
            when (val attempt = GoogleSignInRuntime.signIn(activity, capabilities.googleSignInClientId)) {
                is GoogleSignInAttempt.Ok -> {
                    val payload = org.json.JSONObject().apply {
                        put("ok", true)
                        put("idToken", attempt.idToken)
                        put("displayName", attempt.displayName)
                        put("profilePictureUri", attempt.profilePictureUri)
                        put("googleUserId", attempt.googleUserId)
                    }
                    dispatchGoogleSignInResult(requestId, payload, callerOrigin)
                }
                is GoogleSignInAttempt.Err -> {
                    dispatchGoogleSignInResult(
                        requestId,
                        googleSignInError(attempt.code, attempt.message),
                        callerOrigin
                    )
                }
            }
        }
    }

    private fun googleSignInError(code: String, message: String): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("ok", false)
            put("error", code)
            put("message", message)
        }

    private fun dispatchGoogleSignInResult(
        requestId: String,
        payload: org.json.JSONObject,
        callerOrigin: String? = null
    ) {
        val quotedRequestId = com.sherwood.wta.util.JsStrings.quote(requestId)
        // JSONObject.toString() is a valid JS object literal; the requestId is quoted
        // so a crafted id cannot break out of the call expression.
        val js = "window.NativeBridgeGoogleSignInResult && window.NativeBridgeGoogleSignInResult($quotedRequestId, $payload);"
        scope.launch(Dispatchers.Main) {
            try {
                deliverGoogleSignInResult(js, payload, callerOrigin, attemptsLeft = 20)
            } catch (e: Exception) {
                AppLogger.w("NativeBridge", "Failed to deliver Google sign-in result", e)
            }
        }
    }

    /**
     * True while the host has paused the WebView for the account picker.
     * [android.webkit.WebView.getUrl] is often blank until resume, and
     * [android.webkit.WebView.evaluateJavascript] during the pause is dropped.
     * Hold the result and deliver it from [onWebViewResume]. A local page whose
     * URL never comes back is still delivered once the WebView is running.
     */
    @Volatile
    private var webViewPaused = false

    private var heldSignIn: HeldGoogleSignIn? = null

    private data class HeldGoogleSignIn(
        val js: String,
        val payload: org.json.JSONObject,
        val callerOrigin: String?,
    )

    /** Call only when this bridge's WebView was actually paused. */
    fun onWebViewPause() {
        webViewPaused = true
    }

    /** Call after [android.webkit.WebView.onResume], from the main thread. */
    fun onWebViewResume() {
        webViewPaused = false
        val held = heldSignIn ?: return
        heldSignIn = null
        deliverGoogleSignInResult(held.js, held.payload, held.callerOrigin, attemptsLeft = 20)
    }

    private fun deliverGoogleSignInResult(
        js: String,
        payload: org.json.JSONObject,
        callerOrigin: String?,
        attemptsLeft: Int,
    ) {
        val webView = webViewProvider()
        if (webView == null) {
            logDroppedGoogleSignIn(payload)
            return
        }
        when (googleSignInDispatchAction(
            webView.url,
            callerOrigin,
            giveUpWaiting = attemptsLeft <= 0,
            webViewPaused = webViewPaused,
        )) {
            GoogleSignInDelivery.DELIVER -> webView.post {
                if (webViewPaused) {
                    heldSignIn = HeldGoogleSignIn(js, payload, callerOrigin)
                    return@post
                }
                webView.evaluateJavascript(js, null)
            }
            GoogleSignInDelivery.WAIT -> if (webViewPaused) {
                heldSignIn = HeldGoogleSignIn(js, payload, callerOrigin)
            } else {
                webView.postDelayed({
                    deliverGoogleSignInResult(js, payload, callerOrigin, attemptsLeft - 1)
                }, 50)
            }
            GoogleSignInDelivery.DROP -> logDroppedGoogleSignIn(payload)
        }
    }

    private fun logDroppedGoogleSignIn(payload: org.json.JSONObject) {
        if (payload.optBoolean("ok", false)) {
            AppLogger.w("NativeBridge", "Google sign-in result dropped: page origin changed during the flow")
        }
    }

    internal fun googleSignInDispatchAction(
        currentPageUrl: String?,
        callerOrigin: String?,
        giveUpWaiting: Boolean,
        webViewPaused: Boolean,
    ): GoogleSignInDelivery {
        val decision = googleSignInDeliveryDecision(currentPageUrl, callerOrigin, giveUpWaiting)
        return if (webViewPaused && decision != GoogleSignInDelivery.DROP) {
            GoogleSignInDelivery.WAIT
        } else {
            decision
        }
    }

    internal fun googleSignInDeliveryDecision(
        currentPageUrl: String?,
        callerOrigin: String?,
        giveUpWaiting: Boolean,
    ): GoogleSignInDelivery {
        val current = currentPageUrl.orEmpty()
        if (current.isBlank()) {
            if (!giveUpWaiting) return GoogleSignInDelivery.WAIT
            val caller = callerOrigin.orEmpty()
            return if (caller.isNotEmpty() && isLocalSignInPage(caller)) {
                GoogleSignInDelivery.DELIVER
            } else {
                GoogleSignInDelivery.DROP
            }
        }
        if (isLocalSignInPage(current)) return GoogleSignInDelivery.DELIVER
        val caller = callerOrigin ?: return GoogleSignInDelivery.DROP
        val currentHost = runCatching { URI(current).host }.getOrNull() ?: return GoogleSignInDelivery.DROP
        val callerHost = runCatching { URI(caller).host }.getOrNull() ?: return GoogleSignInDelivery.DROP
        return if (isSameSiteOrSubdomain(currentHost, callerHost)) {
            GoogleSignInDelivery.DELIVER
        } else {
            GoogleSignInDelivery.DROP
        }
    }

    private fun isLocalSignInPage(url: String): Boolean {
        if (url.startsWith("file:") || url.startsWith("content://")) return true
        return isPrivateNetworkUrl(url)
    }

    @Volatile
    private var findQuery: String = ""
    @Volatile
    private var findActiveMatchOrdinal: Int = -1
    @Volatile
    private var findNumberOfMatches: Int = 0
    @Volatile
    private var findDoneCounting: Boolean = true

    private fun buildFindStateJson(supported: Boolean = webViewProvider() != null): String {
        return try {
            org.json.JSONObject().apply {
                put("supported", supported)
                put("query", findQuery)
                put("activeMatchOrdinal", findActiveMatchOrdinal)
                put("numberOfMatches", findNumberOfMatches)
                put("doneCounting", findDoneCounting)
                put("displayIndex", if (findNumberOfMatches > 0 && findActiveMatchOrdinal >= 0) findActiveMatchOrdinal + 1 else 0)
            }.toString()
        } catch (_: Exception) {
            """{"supported":false,"query":"","activeMatchOrdinal":-1,"numberOfMatches":0,"doneCounting":true,"displayIndex":0}"""
        }
    }

    private fun publishFindState(webView: WebView?) {
        val target = webView ?: return
        val payload = buildFindStateJson(supported = true)
        target.evaluateJavascript(
            """
            (function(){
                if (typeof window.__wtaFindInPageNativeUpdate === 'function') {
                    window.__wtaFindInPageNativeUpdate($payload);
                }
            })();
            """.trimIndent(),
            null
        )
    }

    @JavascriptInterface
    fun findInPage(query: String): String {
        if (!capabilities.findInPage) return buildFindStateJson(supported = false)
        val safeQuery = query.trim().take(200)
        if (safeQuery.isBlank()) return clearFindInPage()

        findQuery = safeQuery
        findActiveMatchOrdinal = -1
        findNumberOfMatches = 0
        findDoneCounting = false

        scope.launch(Dispatchers.Main) {
            try {
                val webView = webViewProvider()
                if (webView == null) {
                    findDoneCounting = true
                    return@launch
                }

                webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                    findActiveMatchOrdinal = if (numberOfMatches > 0) activeMatchOrdinal else -1
                    findNumberOfMatches = numberOfMatches
                    findDoneCounting = isDoneCounting
                    publishFindState(webView)
                }
                webView.findAllAsync(safeQuery)
                publishFindState(webView)
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "In-page find failed", e)
                findDoneCounting = true
            }
        }

        return buildFindStateJson()
    }

    @JavascriptInterface
    fun findNextInPage(forward: Boolean): String {
        if (!capabilities.findInPage) return buildFindStateJson(supported = false)
        scope.launch(Dispatchers.Main) {
            try {
                val webView = webViewProvider() ?: return@launch
                if (findQuery.isNotBlank()) {
                    webView.findNext(forward)
                    publishFindState(webView)
                }
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "In-page find navigation failed", e)
            }
        }
        return buildFindStateJson()
    }

    @JavascriptInterface
    fun clearFindInPage(): String {
        if (!capabilities.findInPage) return buildFindStateJson(supported = false)
        findQuery = ""
        findActiveMatchOrdinal = -1
        findNumberOfMatches = 0
        findDoneCounting = true

        scope.launch(Dispatchers.Main) {
            try {
                val webView = webViewProvider() ?: return@launch
                webView.clearMatches()
                publishFindState(webView)
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to clear in-page find", e)
            }
        }

        return buildFindStateJson()
    }

    @JavascriptInterface
    fun setOrientation(orientation: String) {
        if (!capabilities.orientation) return
        scope.launch(Dispatchers.Main) {
            try {
                val activity = context as? Activity ?: return@launch
                activity.requestedOrientation = when (orientation.lowercase()) {
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "auto" -> ActivityInfo.SCREEN_ORIENTATION_USER
                    "sensor" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    "reverse_landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    "reverse_portrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                AppLogger.d("NativeBridge", "Screen orientation set to: $orientation")
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to set screen orientation", e)
            }
        }
    }

    @JavascriptInterface
    fun getOrientation(): String {
        if (!capabilities.orientation) return "unknown"
        return try {
            val displayMetrics = context.resources.displayMetrics
            if (displayMetrics.widthPixels > displayMetrics.heightPixels) {
                "landscape"
            } else {
                "portrait"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    @JavascriptInterface
    fun lockOrientation() {
        if (!capabilities.orientation) return
        scope.launch(Dispatchers.Main) {
            try {
                val activity = context as? Activity ?: return@launch
                val currentOrientation = context.resources.configuration.orientation
                activity.requestedOrientation = if (currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                AppLogger.d("NativeBridge", "Screen orientation locked")
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to lock screen orientation", e)
            }
        }
    }

    @JavascriptInterface
    fun unlockOrientation() {
        if (!capabilities.orientation) return
        scope.launch(Dispatchers.Main) {
            try {
                val activity = context as? Activity ?: return@launch
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
                AppLogger.d("NativeBridge", "Screen orientation unlocked")
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to unlock screen orientation", e)
            }
        }
    }

    @JavascriptInterface
    fun downloadVideo(url: String, filename: String) {
        if (!capabilities.download) return
        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, Strings.startDownload.replace("%s", filename), Toast.LENGTH_SHORT).show()
            }

            try {
                val result = MediaSaver.saveFromUrl(context, url, filename, "video/mp4")
                withContext(Dispatchers.Main) {
                    when (result) {
                        is MediaSaver.SaveResult.Success -> {
                            Toast.makeText(context, Strings.downloadComplete.replace("%s", filename), Toast.LENGTH_SHORT).show()
                        }
                        is MediaSaver.SaveResult.Error -> {
                            Toast.makeText(context, Strings.downloadFailedWithReason.replace("%s", result.message), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to download video", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, Strings.downloadFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun downloadAudio(url: String, filename: String) {
        if (!capabilities.download) return
        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, Strings.startDownload.replace("%s", filename), Toast.LENGTH_SHORT).show()
            }

            try {
                val mimeType = if (filename.endsWith(".m4a")) "audio/mp4"
                    else if (filename.endsWith(".mp3")) "audio/mpeg"
                    else if (filename.endsWith(".aac")) "audio/aac"
                    else if (filename.endsWith(".ogg")) "audio/ogg"
                    else if (filename.endsWith(".opus")) "audio/ogg"
                    else "audio/mpeg"
                val result = MediaSaver.saveFromUrl(context, url, filename, mimeType)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is MediaSaver.SaveResult.Success -> {
                            Toast.makeText(context, Strings.downloadComplete.replace("%s", filename), Toast.LENGTH_SHORT).show()
                        }
                        is MediaSaver.SaveResult.Error -> {
                            Toast.makeText(context, Strings.downloadFailedWithReason.replace("%s", result.message), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to download audio", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, Strings.downloadFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun downloadWithHeaders(url: String, filename: String, headersJson: String) {
        if (!capabilities.download) return
        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, Strings.startDownload.replace("%s", filename), Toast.LENGTH_SHORT).show()
            }

            try {

                val headers = try {
                    val map = mutableMapOf<String, String>()
                    val json = org.json.JSONObject(headersJson)
                    json.keys().forEach { key ->
                        map[key] = json.getString(key)
                    }
                    map
                } catch (e: Exception) {
                    emptyMap()
                }

                val result = MediaSaver.saveFromUrlWithHeaders(context, url, filename, headers)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is MediaSaver.SaveResult.Success -> {
                            Toast.makeText(context, Strings.downloadComplete.replace("%s", filename), Toast.LENGTH_SHORT).show()
                        }
                        is MediaSaver.SaveResult.Error -> {
                            Toast.makeText(context, Strings.downloadFailedWithReason.replace("%s", result.message), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Download failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, Strings.downloadFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun setScreenBrightness(brightness: Float) {
        if (!capabilities.brightness) return
        scope.launch(Dispatchers.Main) {
            try {
                val activity = context as? Activity ?: return@launch
                val layoutParams = activity.window.attributes
                layoutParams.screenBrightness = if (brightness < 0) {
                    android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                } else {
                    brightness.coerceIn(0f, 1f)
                }
                activity.window.attributes = layoutParams
                AppLogger.d("NativeBridge", "Screen brightness set to: $brightness")
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to set screen brightness", e)
            }
        }
    }

    @JavascriptInterface
    fun setKeepScreenOn(keepOn: Boolean) {
        if (!capabilities.screenWake) return
        scope.launch(Dispatchers.Main) {
            try {
                val activity = context as? Activity ?: return@launch
                if (keepOn) {
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                AppLogger.d("NativeBridge", "Keep-screen-on: $keepOn")
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Failed to enable keep-screen-on", e)
            }
        }
    }

    @JavascriptInterface
    fun enterFullscreen() {
        if (!capabilities.fullscreen) return
        scope.launch(Dispatchers.Main) {
            try {
                val activity = context as? Activity ?: return@launch
                val decorView = activity.window.decorView

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.insetsController?.hide(
                        android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
                }
                AppLogger.d("NativeBridge", "Entered fullscreen mode")
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Entering fullscreenfailed", e)
            }
        }
    }

    @JavascriptInterface
    fun exitFullscreen() {
        if (!capabilities.fullscreen) return
        scope.launch(Dispatchers.Main) {
            try {
                val activity = context as? Activity ?: return@launch
                val decorView = activity.window.decorView

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.insetsController?.show(
                        android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
                AppLogger.d("NativeBridge", "Exited fullscreen mode")
            } catch (e: Exception) {
                AppLogger.e("NativeBridge", "Exiting fullscreenfailed", e)
            }
        }
    }

    @JavascriptInterface
    fun isFullscreen(): Boolean {
        if (!capabilities.fullscreen) return false
        return try {
            val activity = context as? Activity ?: return false
            val decorView = activity.window.decorView

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = decorView.rootWindowInsets
                insets?.isVisible(android.view.WindowInsets.Type.statusBars()) == false
            } else {
                @Suppress("DEPRECATION")
                (decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
            }
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun directoryInfo(): String {
        if (!capabilities.directory) return directoryError("DISABLED", "The folder capability is off")
        if (!directoryCallerAllowed()) return directoryError("CALLER_NOT_ALLOWED", "Only the app's own pages can use the folder")
        return directoryStore.info()
    }

    /**
     * Opens the system folder picker. The picker itself is asynchronous; a
     * synchronous JSON reply reports whether it started. The chosen grant
     * arrives through `window.NativeBridgeDirectoryResult(requestId, payload)`.
     */
    @JavascriptInterface
    fun pickDirectory(requestId: String): String {
        if (!capabilities.directory) return directoryError("DISABLED", "The folder capability is off")
        if (!directoryCallerAllowed()) return directoryError("CALLER_NOT_ALLOWED", "Only the app's own pages can use the folder")
        val host = DirectoryPickerRegistry.host
            ?: return directoryError("NO_ACTIVITY", "The folder picker is unavailable in this window")
        val callerPageUrl = resolveCallerPageUrl()
        val callerOrigin = directoryCallerOrigin(callerPageUrl)
        host.pick { uri ->
            val payload = if (uri == null) {
                directoryErrorObject("CANCELLED", "The folder picker was dismissed")
            } else {
                org.json.JSONObject(directoryStore.remember(uri))
            }
            dispatchDirectoryResult(requestId, payload, callerOrigin, callerPageUrl)
        }
        return org.json.JSONObject().apply {
            put("ok", true)
            put("pending", true)
        }.toString()
    }

    @JavascriptInterface
    fun releaseDirectory(): String {
        if (!capabilities.directory) return directoryError("DISABLED", "The folder capability is off")
        if (!directoryCallerAllowed()) return directoryError("CALLER_NOT_ALLOWED", "Only the app's own pages can use the folder")
        return directoryStore.release()
    }

    @JavascriptInterface
    fun listDirectory(relativePath: String): String {
        if (!capabilities.directory) return directoryError("DISABLED", "The folder capability is off")
        if (!directoryCallerAllowed()) return directoryError("CALLER_NOT_ALLOWED", "Only the app's own pages can use the folder")
        return directoryStore.list(relativePath)
    }

    @JavascriptInterface
    fun readDirectoryFile(relativePath: String, offset: Long, length: Int): String {
        if (!capabilities.directory) return directoryError("DISABLED", "The folder capability is off")
        if (!directoryCallerAllowed()) return directoryError("CALLER_NOT_ALLOWED", "Only the app's own pages can use the folder")
        return directoryStore.read(relativePath, offset, length)
    }

    @JavascriptInterface
    fun writeDirectoryFile(relativePath: String, base64: String, append: Boolean): String {
        if (!capabilities.directory) return directoryError("DISABLED", "The folder capability is off")
        if (!directoryCallerAllowed()) return directoryError("CALLER_NOT_ALLOWED", "Only the app's own pages can use the folder")
        return directoryStore.write(relativePath, base64, append)
    }

    @JavascriptInterface
    fun deleteDirectoryEntry(relativePath: String): String {
        if (!capabilities.directory) return directoryError("DISABLED", "The folder capability is off")
        if (!directoryCallerAllowed()) return directoryError("CALLER_NOT_ALLOWED", "Only the app's own pages can use the folder")
        return directoryStore.delete(relativePath)
    }

    private fun directoryCallerAllowed(): Boolean {
        val callerPageUrl = resolveCallerPageUrl()
        if (callerPageUrl.isBlank()) return false
        if (callerPageUrl.startsWith("file:") || callerPageUrl.startsWith("content:")) return true
        if (isPrivateNetworkUrl(callerPageUrl)) return true
        return isAppOriginCallerPage(callerPageUrl)
    }

    private fun directoryCallerOrigin(pageUrl: String): String? {
        if (pageUrl.startsWith("file:") || pageUrl.startsWith("content:") || isPrivateNetworkUrl(pageUrl)) {
            return pageUrl
        }
        return runCatching {
            URI(pageUrl).let { u ->
                "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
            }
        }.getOrNull()
    }

    private fun directoryError(code: String, message: String): String =
        directoryErrorObject(code, message).toString()

    private fun directoryErrorObject(code: String, message: String): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("ok", false)
            put("error", code)
            put("message", message)
        }

    private fun dispatchDirectoryResult(
        requestId: String,
        payload: org.json.JSONObject,
        callerOrigin: String?,
        callerPageUrl: String
    ) {
        val quotedRequestId = com.sherwood.wta.util.JsStrings.quote(requestId)
        val js = "window.NativeBridgeDirectoryResult && window.NativeBridgeDirectoryResult($quotedRequestId, $payload);"
        scope.launch(Dispatchers.Main) {
            try {
                val webView = webViewProvider() ?: return@launch
                val current = webView.url.orEmpty()
                if (!directoryDeliveryAllowed(current, callerOrigin, callerPageUrl)) {
                    AppLogger.w("NativeBridge", "Directory picker result dropped: page origin changed")
                    return@launch
                }
                webView.evaluateJavascript(js, null)
            } catch (e: Exception) {
                AppLogger.w("NativeBridge", "Failed to deliver directory picker result", e)
            }
        }
    }

    private fun directoryDeliveryAllowed(current: String, callerOrigin: String?, callerPageUrl: String): Boolean {
        if (current.isBlank()) return false
        val local = { url: String ->
            url.startsWith("file:") || url.startsWith("content:") || isPrivateNetworkUrl(url)
        }
        if (local(callerPageUrl)) return local(current)
        val callerHost = callerOrigin?.let { runCatching { URI(it).host }.getOrNull() } ?: return false
        val currentHost = runCatching { URI(current).host }.getOrNull() ?: return false
        return isSameSiteOrSubdomain(currentHost, callerHost)
    }
}

private fun privateNetworkOnlyCapabilities(): com.sherwood.wta.data.model.NativeBridgeCapabilities {
    return com.sherwood.wta.data.model.NativeBridgeCapabilities(
        clipboard = false,
        vibration = false,
        geolocation = false,
        brightness = false,
        notification = false,
        notificationScheduled = false,
        notificationPersistent = false,
        download = false,
        privateNetwork = true,
        screenWake = false,
        openExternal = false,
        deviceInfo = false,
        securityInfo = false,
        networkInfo = false,
        toast = false,
        logging = false,
        findInPage = false,
        orientation = false,
        fullscreen = false,
        print = false,
        directory = false
    )
}

class PrivateNetworkNativeBridgeAdapter(
    context: Context,
    scope: CoroutineScope,
    webViewProvider: () -> WebView? = { null },
    corsBypass: Boolean = false,
    appOriginUrl: String = ""
) {
    private val delegate = NativeBridge(
        context = context,
        scope = scope,
        webViewProvider = webViewProvider,
        capabilities = privateNetworkOnlyCapabilities(),
        corsBypass = corsBypass,
        appOriginUrl = appOriginUrl
    )

    @JavascriptInterface
    fun httpRequest(requestJson: String): String {
        return delegate.httpRequest(requestJson)
    }

    @JavascriptInterface
    fun httpRequestAsync(requestJson: String, callbackId: String) {
        delegate.httpRequestAsync(requestJson, callbackId)
    }
}
