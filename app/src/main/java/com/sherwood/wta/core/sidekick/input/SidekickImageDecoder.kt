package com.sherwood.wta.core.sidekick.input

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.util.BoundedBitmaps
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns a picked image into something a vision model can accept.
 *
 * Two problems are solved here at once:
 *
 * 1. **Memory.** A modern phone photo is 12–50 MP; decoded at full size it is hundreds of
 *    megabytes. The decode is bounds-probed first and sampled down, so only a bounded
 *    bitmap is ever allocated.
 * 2. **Metadata.** Re-encoding to JPEG drops the EXIF block, which is where the GPS
 *    coordinates and the capture timestamp live. Sending the original bytes would leak the
 *    user's location to whichever provider the Sidekick is pointed at.
 *
 * The orientation has to be read *before* re-encoding, otherwise a portrait photo arrives
 * sideways — the EXIF flag that would have fixed it is stripped by the same re-encode.
 */
object SidekickImageDecoder {

    private const val TAG = "SidekickImageDecoder"

    /** Quality ladder; the first pass usually fits. */
    private val QUALITY_STEPS = intArrayOf(85, 75, 65)

    /** Scale ladder applied after the quality steps are exhausted. */
    private val SCALE_STEPS = floatArrayOf(1f, 0.75f, 0.5f)

    fun fromFile(file: File, name: String): SidekickAttachmentResult {
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.UNREADABLE)
        }

        val sample = BoundedBitmaps.calculateInSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            SidekickAttachmentLimits.MAX_IMAGE_SIDE
        )
        val options = BitmapFactory.Options().also {
            it.inSampleSize = sample
            it.inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
            .getOrNull()
            ?: return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.UNREADABLE)

        // Everything created below is recycled on the way out; a leak here would be a
        // bitmap the size of the photo held until the GC gets around to it.
        val owned = mutableListOf<Bitmap>()
        try {
            val rotated = orient(decoded, readRotation(file))
            owned += rotated
            // `downscaledToFit` is a member extension of the BoundedBitmaps object, so it is
            // called inside its scope rather than imported.
            val fitted = BoundedBitmaps.run {
                rotated.downscaledToFit(SidekickAttachmentLimits.MAX_IMAGE_SIDE)
            }
            if (fitted !== rotated) owned += fitted

            for (scale in SCALE_STEPS) {
                val candidate = if (scale >= 1f) fitted else fitted.scaledBy(scale) ?: continue
                if (candidate !== fitted) owned += candidate
                for (quality in QUALITY_STEPS) {
                    val encoded = compress(candidate, quality) ?: continue
                    if (encoded.size <= SidekickAttachmentLimits.MAX_IMAGE_BYTES) {
                        return SidekickAttachmentResult.Ok(
                            SidekickImageAttachment(
                                name = name,
                                bytes = encoded,
                                mimeType = "image/jpeg",
                                width = candidate.width,
                                height = candidate.height
                            )
                        )
                    }
                }
            }
            // Downscaled and still too large: refuse rather than send a payload the provider
            // will reject or that silently eats the request timeout.
            return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.TOO_LARGE)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Image encode failed: ${t.javaClass.simpleName}")
            return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.UNREADABLE)
        } finally {
            owned.asReversed().forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun readRotation(file: File): Int = try {
        when (
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (t: Throwable) {
        // A missing or malformed EXIF block is normal (screenshots have none).
        0
    }

    /** Returns [bitmap] itself when no rotation is needed; otherwise a new bitmap. */
    private fun orient(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Rotation failed, keeping original (${t.message})")
            bitmap
        }
    }

    private fun Bitmap.scaledBy(scale: Float): Bitmap? = try {
        val width = (this.width * scale).toInt().coerceAtLeast(1)
        val height = (this.height * scale).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(this, width, height, true)
    } catch (t: Throwable) {
        AppLogger.w(TAG, "Downscale failed (${t.message})")
        null
    }

    /**
     * JPEG has no alpha channel; a transparent PNG would otherwise come out with black
     * behind the content, which on a design mockup reads as "the screenshot is broken".
     */
    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? = try {
        val flattened = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        } else {
            null
        }
        val target = if (flattened != null) {
            val canvas = Canvas(flattened)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            flattened
        } else {
            bitmap
        }
        try {
            val out = ByteArrayOutputStream(bitmap.byteCount.coerceAtMost(1 shl 21))
            target.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        } finally {
            flattened?.recycle()
        }
    } catch (t: Throwable) {
        AppLogger.w(TAG, "Compress failed (${t.message})")
        null
    }
}
