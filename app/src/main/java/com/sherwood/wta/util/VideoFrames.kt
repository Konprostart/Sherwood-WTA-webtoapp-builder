package com.sherwood.wta.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.sherwood.wta.core.logging.AppLogger
import kotlin.math.max

/**
 * Video frame extraction with a size bound.
 *
 * `MediaMetadataRetriever.getFrameAtTime` returns the frame at the video's native
 * resolution: an 8K clip is ~132 MB of ARGB_8888, which is enough to kill the process on
 * a phone. `getScaledFrameAtTime` exists from API 27 and decodes straight to a bounded
 * bitmap; older devices fall back to a full-size decode that is downscaled immediately
 * (and contained, because that allocation can still fail).
 */
object VideoFrames {

    /** Longest side of a preview/thumbnail frame; plenty for a phone screen. */
    const val MAX_DIMENSION = 1280

    /**
     * Returns one frame, or null when the retriever cannot produce it.
     *
     * Failure (including [OutOfMemoryError]) is reported as null: a missing thumbnail is
     * recoverable, a process death is not.
     */
    fun frameAt(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        option: Int,
        maxDimension: Int = MAX_DIMENSION
    ): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) {
                // getScaledFrameAtTime crops/scales to exactly the requested box, so the
                // aspect ratio has to be computed here rather than passing a square.
                val longest = max(width, height)
                val scale = if (longest > maxDimension) {
                    maxDimension.toFloat() / longest
                } else {
                    1f
                }
                val targetWidth = max(1, (width * scale).toInt())
                val targetHeight = max(1, (height * scale).toInt())
                retriever.getScaledFrameAtTime(timeUs, option, targetWidth, targetHeight)
            } else {
                retriever.getFrameAtTime(timeUs, option)?.let { bounded(it, maxDimension) }
            }
        } else {
            retriever.getFrameAtTime(timeUs, option)?.let { bounded(it, maxDimension) }
        }
    } catch (t: Throwable) {
        AppLogger.w(TAG, "Frame extraction failed: ${t.javaClass.simpleName}")
        null
    }

    private fun bounded(bitmap: Bitmap, maxDimension: Int): Bitmap =
        BoundedBitmaps.run { bitmap.downscaledToFit(maxDimension) }

    private const val TAG = "VideoFrames"
}
