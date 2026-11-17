package com.sherwood.wta.core.sidekick.input

import android.graphics.Bitmap
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

    /**
     * The eight EXIF orientation values, as the transform they describe.
     *
     * Rotations alone are not enough: a front-camera selfie carries a mirror flag, and a
     * transposed/transverse value swaps the axes. Mapping only 90/180/270 left those
     * images mirrored or sideways.
     */
    enum class Orientation {
        NORMAL, ROTATE_90, ROTATE_180, ROTATE_270,
        FLIP_HORIZONTAL, FLIP_VERTICAL, TRANSPOSE, TRANSVERSE
    }

    /**
     * @param flattenAlpha true when the source format can carry transparency (PNG/WebP/
     *   GIF). JPEG cannot, so flattening it would allocate a second full-size bitmap for
     *   nothing.
     */
    fun fromFile(file: File, name: String, flattenAlpha: Boolean = false): SidekickAttachmentResult {
        // BoundedBitmaps bounds-probes first and samples down to the cap, which is the only
        // sanctioned way to decode user content in this app (`NoUnboundedBitmapDecodeTest`
        // gates raw BitmapFactory calls because an oversized bitmap kills the process at
        // DRAW time, past any try/catch).
        val decoded = BoundedBitmaps.decodeBoundedBitmapFile(
            file.absolutePath,
            SidekickAttachmentLimits.MAX_IMAGE_SIDE
        ) ?: return SidekickAttachmentResult.Rejected(SidekickAttachmentRejection.UNREADABLE)

        // Everything created below is recycled on the way out; a leak here would be a
        // bitmap the size of the photo held until the GC gets around to it.
        val owned = mutableListOf<Bitmap>()
        try {
            val rotated = orient(decoded, readOrientation(file))
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
                    val encoded = compress(candidate, quality, flattenAlpha) ?: continue
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

    /**
     * EXIF orientation tag → transform. Values follow the EXIF specification; they are
     * written as literals so the mapping is testable on a plain JVM.
     */
    internal fun orientationFor(tag: Int): Orientation = when (tag) {
        2 -> Orientation.FLIP_HORIZONTAL
        3 -> Orientation.ROTATE_180
        4 -> Orientation.FLIP_VERTICAL
        5 -> Orientation.TRANSPOSE
        6 -> Orientation.ROTATE_90
        7 -> Orientation.TRANSVERSE
        8 -> Orientation.ROTATE_270
        else -> Orientation.NORMAL
    }

    private fun readOrientation(file: File): Orientation = try {
        orientationFor(
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        )
    } catch (t: Throwable) {
        // A missing or malformed EXIF block is normal (screenshots have none).
        Orientation.NORMAL
    }

    /** Returns [bitmap] itself when no transform is needed; otherwise a new bitmap. */
    private fun orient(bitmap: Bitmap, orientation: Orientation): Bitmap {
        if (orientation == Orientation.NORMAL) return bitmap
        return try {
            val matrix = Matrix()
            when (orientation) {
                Orientation.NORMAL -> Unit
                Orientation.ROTATE_90 -> matrix.postRotate(90f)
                Orientation.ROTATE_180 -> matrix.postRotate(180f)
                Orientation.ROTATE_270 -> matrix.postRotate(270f)
                Orientation.FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                Orientation.FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                // Transpose is "rotate 90° then mirror horizontally"; transverse is
                // "rotate 90° then mirror vertically".
                Orientation.TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                Orientation.TRANSVERSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(1f, -1f)
                }
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Orientation failed, keeping original (${t.message})")
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
     * JPEG has no alpha channel, so a transparent PNG would otherwise come out black
     * behind the content, which on a design mockup reads as "the screenshot is broken".
     */
    private fun compress(bitmap: Bitmap, quality: Int, flattenAlpha: Boolean): ByteArray? = try {
        // Flattening is only needed for a source format that can carry transparency; the
        // extra full-size bitmap is not allocated for a JPEG.
        val flattened = if (flattenAlpha && bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { target ->
                try {
                    val canvas = Canvas(target)
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                } catch (t: Throwable) {
                    target.recycle()
                    throw t
                }
            }
        } else {
            null
        }
        try {
            val out = ByteArrayOutputStream(bitmap.byteCount.coerceAtMost(1 shl 21))
            (flattened ?: bitmap).compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        } finally {
            flattened?.recycle()
        }
    } catch (t: Throwable) {
        AppLogger.w(TAG, "Compress failed (${t.message})")
        null
    }
}
