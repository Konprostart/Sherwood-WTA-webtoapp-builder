package com.sherwood.wta.core.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.util.DownloadHelper
import com.sherwood.wta.util.MediaSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class LongPressHandler(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "LongPressHandler"
        private val DATA_URI_MIME_REGEX = Regex("data:([^;]+)")
        private val VIDEO_EXTENSIONS = setOf(".mp4", ".webm", ".ogg", ".mov", ".avi", ".mkv", ".m3u8")
    }

    sealed class LongPressResult {
        data class Image(val url: String) : LongPressResult()
        data class Video(val url: String) : LongPressResult()
        data class Link(val url: String, val title: String? = null) : LongPressResult()
        data class ImageLink(val imageUrl: String, val linkUrl: String) : LongPressResult()
        object Text : LongPressResult()
        object None : LongPressResult()
    }

    fun injectLongPressEnhancer(webView: WebView) {
        val js = """
            (function() {
                if (window.__wtaLongPressEnhanced) return;
                window.__wtaLongPressEnhanced = true;

                // Only block the contextmenu event; do not interfere with touch events (so page click handlers keep working)
                // This allows long-press image saving while keeping click behavior intact (e.g. center-tap settings in novel readers)
                document.addEventListener('contextmenu', function(e) {
                    // Check whether this is an image-related element
                    var target = e.target;
                    var isImageRelated = false;
                    var current = target;
                    var depth = 0;

                    while (current && depth < 10) {
                        var tagName = current.tagName ? current.tagName.toUpperCase() : '';
                        if (tagName === 'IMG' || tagName === 'CANVAS') {
                            isImageRelated = true;
                            break;
                        }
                        // Check background image
                        var style = window.getComputedStyle(current);
                        if (style.backgroundImage && style.backgroundImage !== 'none' &&
                            style.backgroundImage.includes('url(')) {
                            isImageRelated = true;
                            break;
                        }
                        // Check Xiaohongshu-specific container
                        if (current.className && (
                            current.className.includes('note-image') ||
                            current.className.includes('swiper') ||
                            current.className.includes('carousel') ||
                            current.className.includes('image')
                        )) {
                            isImageRelated = true;
                            break;
                        }
                        current = current.parentElement;
                        depth++;
                    }

                    if (isImageRelated) {
                        e.stopPropagation();
                        e.preventDefault();
                    }
                }, true);

                // Remove long-press blocking attributes from elements (image elements only)
                // Note: do not remove touch event handlers, so page click behavior is preserved
                function removeEventBlockers() {
                    var elements = document.querySelectorAll('img, canvas');
                    elements.forEach(function(el) {
                        // Only allow long-press on image elements
                        el.style.webkitTouchCallout = 'default';
                        el.removeAttribute('oncontextmenu');
                        // Do not remove touch events, so page click handling is not affected
                    });
                }

                removeEventBlockers();

                // Keep removing on DOM changes (listen for DOM mutations)
                var observer = new MutationObserver(function() {
                    removeEventBlockers();
                });
                var observerTarget = document.body || document.documentElement;
                if (observerTarget instanceof Node) {
                    observer.observe(observerTarget, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    fun parseLongPressResult(webView: WebView): LongPressResult {
        val hitResult = webView.hitTestResult
        val type = hitResult.type
        val extra = hitResult.extra ?: return LongPressResult.None

        return when (type) {
            WebView.HitTestResult.IMAGE_TYPE -> {
                LongPressResult.Image(extra)
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {

                LongPressResult.ImageLink(imageUrl = extra, linkUrl = "")
            }
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {

                if (isVideoUrl(extra)) {
                    LongPressResult.Video(extra)
                } else {
                    LongPressResult.Link(extra)
                }
            }
            WebView.HitTestResult.ANCHOR_TYPE -> {
                LongPressResult.Link(extra)
            }
            WebView.HitTestResult.EDIT_TEXT_TYPE -> {
                LongPressResult.Text
            }
            else -> LongPressResult.None
        }
    }

    fun getLongPressDetails(webView: WebView, x: Float, y: Float, callback: (LongPressResult) -> Unit) {

        val scale = webView.scale

        val pageX = x / scale
        val pageY = y / scale

        val js = """
            (function() {
                try {
                    // Use the given coordinates (already in CSS pixels)
                    var x = $pageX;
                    var y = $pageY;

                    var elem = document.elementFromPoint(x, y);
                    if (!elem) {
                        return JSON.stringify({type: 'none'});
                    }

                    var result = {type: 'none'};

                    // Helper: extract background image URL
                    function extractBgImageUrl(element) {
                        var style = window.getComputedStyle(element);
                        var bgImage = style.backgroundImage;
                        if (bgImage && bgImage !== 'none') {
                            // Support multiple url() values
                            var matches = bgImage.match(/url\(['"]?([^'")\s]+)['"]?\)/g);
                            if (matches && matches.length > 0) {
                                var url = matches[0].replace(/url\(['"]?/, '').replace(/['"]?\)/, '');
                                if (url && !url.startsWith('data:image/svg') && !url.includes('gradient')) {
                                    return url;
                                }
                            }
                        }
                        return null;
                    }

                    // Helper: detect special image containers on sites such as Xiaohongshu
                    function findXhsImage(element) {
                        // Xiaohongshu images are usually inside specific containers
                        var selectors = [
                            'img[src*="xhscdn"]',
                            'img[src*="xiaohongshu"]',
                            '[class*="note-image"] img',
                            '[class*="swiper-slide"] img',
                            '[class*="carousel"] img',
                            '[class*="image-container"] img',
                            '[class*="media"] img',
                            '[data-v-] img'  // Images in Vue components
                        ];

                        // First check the current element and its children
                        for (var i = 0; i < selectors.length; i++) {
                            var img = element.querySelector(selectors[i]);
                            if (img && img.src) return img.src;
                        }

                        // Search upward for images in parent containers
                        var parent = element;
                        var depth = 0;
                        while (parent && depth < 5) {
                            for (var i = 0; i < selectors.length; i++) {
                                var img = parent.querySelector(selectors[i]);
                                if (img && img.src) return img.src;
                            }
                            parent = parent.parentElement;
                            depth++;
                        }

                        return null;
                    }

                    // Helper: extract image from canvas
                    function extractCanvasImage(canvas) {
                        try {
                            if (canvas.width > 10 && canvas.height > 10) {
                                return canvas.toDataURL('image/png');
                            }
                        } catch (e) {
                            console.log('WebToApp: Canvas extraction failed (CORS)', e);
                        }
                        return null;
                    }

                    // Walk up to find interactive elements (up to 15 levels, deeper to handle complex DOM)
                    var current = elem;
                    var depth = 0;
                    var foundBgImage = null;

                    while (current && depth < 15) {
                        var tagName = current.tagName ? current.tagName.toUpperCase() : '';

                        // Check whether this is an image
                        if (tagName === 'IMG' && current.src) {
                            result = {
                                type: 'image',
                                url: current.src,
                                alt: current.alt || ''
                            };
                            // Search upward to see whether this is inside a link
                            var parent = current.parentElement;
                            while (parent) {
                                if (parent.tagName && parent.tagName.toUpperCase() === 'A' && parent.href) {
                                    result.type = 'imageLink';
                                    result.linkUrl = parent.href;
                                    break;
                                }
                                parent = parent.parentElement;
                            }
                            break;
                        }

                        // Check canvas element
                        if (tagName === 'CANVAS') {
                            var canvasUrl = extractCanvasImage(current);
                            if (canvasUrl) {
                                result = {type: 'image', url: canvasUrl, alt: 'canvas'};
                                break;
                            }
                        }

                        // Check whether this is a video
                        if (tagName === 'VIDEO') {
                            var videoUrl = current.src || current.currentSrc || '';
                            if (!videoUrl) {
                                var source = current.querySelector('source');
                                if (source) videoUrl = source.src;
                            }
                            if (videoUrl) {
                                result = {type: 'video', url: videoUrl};
                                break;
                            }
                        }

                        // Check whether this is a link
                        if (tagName === 'A' && current.href) {
                            var href = current.href;
                            if (href.match(/\.(mp4|webm|ogg|mov|avi|mkv|m3u8)(\?|#|$)/i)) {
                                result = {type: 'video', url: href};
                            } else {
                                result = {
                                    type: 'link',
                                    url: href,
                                    title: (current.textContent || '').trim().substring(0, 100)
                                };
                            }
                            break;
                        }

                        // Check background image (keep the first match, but keep searching upward for a better result)
                        if (!foundBgImage) {
                            var bgUrl = extractBgImageUrl(current);
                            if (bgUrl) {
                                foundBgImage = bgUrl;
                            }
                        }

                        // Check special image containers such as Xiaohongshu
                        if (result.type === 'none') {
                            var xhsImg = findXhsImage(current);
                            if (xhsImg) {
                                result = {type: 'image', url: xhsImg, alt: 'xhs'};
                                break;
                            }
                        }

                        current = current.parentElement;
                        depth++;
                    }

                    // If no standard image was found, use the background image
                    if (result.type === 'none' && foundBgImage) {
                        result = {type: 'image', url: foundBgImage, alt: 'background'};
                    }

                    return JSON.stringify(result);
                } catch (e) {
                    console.error('WebToApp: Error in getLongPressDetails', e);
                    return JSON.stringify({type: 'none', error: e.message});
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { jsonResult ->
            try {
                val json = jsonResult?.trim()?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                if (json.isNullOrBlank() || json == "null") {
                    callback(LongPressResult.None)
                    return@evaluateJavascript
                }

                val result = org.json.JSONObject(json)
                val type = result.optString("type", "none")

                when (type) {
                    "image" -> callback(LongPressResult.Image(result.getString("url")))
                    "video" -> callback(LongPressResult.Video(result.getString("url")))
                    "link" -> callback(LongPressResult.Link(
                        result.getString("url"),
                        result.optString("title")?.takeIf { it.isNotEmpty() }
                    ))
                    "imageLink" -> callback(LongPressResult.ImageLink(
                        result.getString("url"),
                        result.optString("linkUrl", "")
                    ))
                    else -> callback(LongPressResult.None)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "解析长按结果失败", e)
                callback(LongPressResult.None)
            }
        }
    }

    fun copyToClipboard(text: String, label: String = "Sherwood WTA") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, Strings.copiedToClipboard, Toast.LENGTH_SHORT).show()
    }

    fun saveImage(imageUrl: String, onResult: (Boolean, String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val result = when {
                    imageUrl.startsWith("data:") -> saveBase64Image(imageUrl)
                    imageUrl.startsWith("blob:") -> {
                        withContext(Dispatchers.Main) {
                            onResult(false, Strings.blobImageUsePageDownload)
                        }
                        return@launch
                    }
                    else -> saveUrlImage(imageUrl)
                }

                withContext(Dispatchers.Main) {
                    if (result != null) {
                        onResult(true, Strings.imageSavedToGallery)
                    } else {
                        onResult(false, Strings.saveFailed)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "保存图片失败", e)
                withContext(Dispatchers.Main) {
                    onResult(false, Strings.saveFailedWithReason.format(e.message))
                }
            }
        }
    }

    private fun saveBase64Image(dataUrl: String): Uri? {
        val parts = dataUrl.split(",")
        if (parts.size != 2) return null

        val meta = parts[0]
        val base64Data = parts[1]

        val mimeType = DATA_URI_MIME_REGEX.find(meta)?.groupValues?.get(1) ?: "image/png"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "png"

        val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)

        // Write bytes directly: no decode roundtrip (lossless, faster, and immune
        // to oversized-bitmap OOM on hostile data URLs, #779).
        return saveBytesToGallery(imageBytes, "IMG_${System.currentTimeMillis()}.$extension", mimeType)
    }

    private fun saveUrlImage(imageUrl: String): Uri? {
        val url = URL(imageUrl)
        val connection = url.openConnection()
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        val imageBytes = connection.getInputStream().use { it.readBytes() }

        val fileName = DownloadHelper.parseFileName(imageUrl, null, connection.contentType)
            .ifBlank { "IMG_${System.currentTimeMillis()}.jpg" }
        val mimeType = connection.contentType ?: "image/jpeg"

        return saveBytesToGallery(imageBytes, fileName, mimeType)
    }

    private fun saveBytesToGallery(bytes: ByteArray, fileName: String, mimeType: String): Uri? {
        if (bytes.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WebToApp")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(bytes)
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)

            uri
        } else {

            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val appDir = File(picturesDir, "WebToApp")
            if (!appDir.exists()) appDir.mkdirs()

            val file = File(appDir, fileName)
            FileOutputStream(file).use { outputStream ->
                outputStream.write(bytes)
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }
    }

    fun downloadVideo(videoUrl: String, onResult: (Boolean, String) -> Unit) {
        if (videoUrl.startsWith("blob:")) {
            onResult(false, Strings.blobVideoUsePageDownload)
            return
        }

        scope.launch(Dispatchers.Main) {

            Toast.makeText(context, Strings.downloadingVideo, Toast.LENGTH_SHORT).show()

            val fileName = DownloadHelper.parseFileName(videoUrl, null, "video/mp4")
                .ifBlank { "VIDEO_${System.currentTimeMillis()}.mp4" }

            val result = MediaSaver.saveFromUrl(
                context = context,
                url = videoUrl,
                fileName = fileName,
                mimeType = "video/mp4"
            )

            when (result) {
                is MediaSaver.SaveResult.Success -> {
                    onResult(true, Strings.videoSavedToGallery)
                }
                is MediaSaver.SaveResult.Error -> {
                    AppLogger.e(TAG, "下载视频失败: ${result.message}")
                    onResult(false, result.message)
                }
            }
        }
    }

    private fun isVideoUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return VIDEO_EXTENSIONS.any { lowerUrl.contains(it) }
    }
}
