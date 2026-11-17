package com.sherwood.wta.core.brand

import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlin.math.hypot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the shipped brand mark: the red "S" with the lightning bolt driven through its
 * middle, on the black tile.
 *
 * <p>Two properties here are easy to break silently and expensive to notice late:
 *
 * <ul>
 *   <li><b>The adaptive safe circle.</b> Launchers may crop an adaptive icon to the
 *       central 66% circle. The forearm is diagonal, so a bounding-box fit puts its
 *       corners outside that circle and the tips get clipped on a real launcher —
 *       visible only on a device, never in a compile.
 *   <li><b>One foreground drawable, reused by the monochrome layer.</b> The export
 *       pipeline replaces the ARSC-discovered launcher foreground with the generated
 *       app's own icon. If the themed-icon layer pointed at a separate asset, that
 *       asset would survive into every generated APK and every user would see the
 *       Sherwood silhouette behind their own icon. Sharing the one drawable is what
 *       keeps the template a placeholder.
 * </ul>
 *
 * <p>Pixel content itself is verified by `scripts/GenerateBrandIcons.java --verify`,
 * which renders the assets; this test guards what ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandIconAssetsTest {

    /** Launcher buckets: resource directory to expected pixel size. */
    private val densities = listOf(
        "mdpi" to 48,
        "hdpi" to 72,
        "xhdpi" to 96,
        "xxhdpi" to 144,
        "xxxhdpi" to 192
    )

    /** Launcher safe circle: 66% of the canvas, per the adaptive-icon guidelines. */
    private val safeCircle = 0.6667

    private val red = 0xFFFF2222.toInt()

    private fun res(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("cannot locate app resources from " + File(".").absolutePath)
    }

    private fun decode(vararg parts: String) =
        BitmapFactory.decodeFile(File(res(), parts.joinToString("/")).path)

    @Test
    fun `every density ships a square and round launcher at its own size`() {
        densities.forEach { (dir, px) ->
            listOf("ic_launcher.png", "ic_launcher_round.png").forEach { name ->
                val file = File(res(), "mipmap-$dir/$name")
                assertWithMessage("$dir/$name is missing").that(file.isFile).isTrue()
                val bitmap = BitmapFactory.decodeFile(file.path)
                assertWithMessage("$dir/$name does not decode").that(bitmap).isNotNull()
                assertWithMessage("$dir/$name size")
                    .that(bitmap!!.width to bitmap.height).isEqualTo(px to px)
            }
        }
    }

    @Test
    fun `square tiles are opaque black plates with an unclipped mark`() {
        densities.forEach { (dir, px) ->
            val bitmap = decode("mipmap-$dir", "ic_launcher.png")!!
            // Asserted on pixels, not hasAlpha(): the decoder's chosen config decides
            // hasAlpha(), while "the plate is opaque" is a property of the artwork.
            for (y in 0 until px step 3) {
                for (x in 0 until px step 3) {
                    assertWithMessage("$dir tile is not opaque at $x,$y")
                        .that(bitmap.getPixel(x, y) ushr 24).isEqualTo(255)
                }
            }
            assertWithMessage("$dir tile corner")
                .that(bitmap.getPixel(1, 1)).isEqualTo(Color.BLACK)
            // Nothing may bleed into the outermost ring: a clipped mark looks broken
            // on every launcher shape, and the fit is what keeps it inside.
            for (i in 0 until px) {
                listOf(i to 0, i to px - 1, 0 to i, px - 1 to i).forEach { (x, y) ->
                    assertWithMessage("$dir tile has ink on its border at $x,$y")
                        .that(bitmap.getPixel(x, y)).isEqualTo(Color.BLACK)
                }
            }
        }
    }

    @Test
    fun `adaptive foreground fits the safe circle and keeps the brand red`() {
        val size = 432
        val bitmap = decode("drawable", "ic_launcher_foreground.png")!!
        assertWithMessage("adaptive foreground size")
            .that(bitmap.width to bitmap.height).isEqualTo(size to size)
        // The adaptive foreground is the transparent layer; a background plate here
        // would be drawn on top of the launcher's own background layer.
        assertWithMessage("adaptive foreground must be transparent outside the mark")
            .that(bitmap.getPixel(0, 0) ushr 24).isEqualTo(0)

        val mid = size / 2.0
        var maxRadius = 0.0
        var branded = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val pixel = bitmap.getPixel(x, y)
                if ((pixel ushr 24) <= 8) continue
                maxRadius = maxOf(maxRadius, hypot(x + 0.5 - mid, y + 0.5 - mid))
                if (pixel == red) branded++
            }
        }
        val limit = size * safeCircle / 2.0
        assertWithMessage("adaptive foreground ink radius %.1f exceeds safe circle %.1f"
            .format(maxRadius, limit)).that(maxRadius <= limit).isTrue()
        assertWithMessage("adaptive foreground is not brand red").that(branded > 0).isTrue()
    }

    @Test
    fun `adaptive layers share the one foreground drawable the exporter replaces`() {
        val drawableRef = Regex("android:drawable=\"@drawable/([^\"]+)\"")
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val xml = File(res(), "mipmap-anydpi-v26/$name").readText()

            val drawables = drawableRef.findAll(xml).map { it.groupValues[1] }.toList()
            assertWithMessage("$name layers").that(drawables)
                .containsExactly("ic_launcher_foreground", "ic_launcher_foreground")

            // Themed icons read the monochrome layer. It must be the same drawable the
            // export pipeline rewrites, or the Sherwood silhouette leaks into every
            // generated APK that keeps themed icons switched on.
            assertWithMessage("$name foreground and monochrome must both be the shared drawable")
                .that(xml).contains("<monochrome android:drawable=\"@drawable/ic_launcher_foreground\"/>")
            assertWithMessage("$name background layer")
                .that(xml).contains("android:drawable=\"@color/ic_launcher_background\"")
                .isTrue()
        }

        val background = File(res(), "values/ic_launcher_background.xml").readText()
        assertWithMessage("launcher background must stay the brand black")
            .that(background).contains("#000000")
    }
}
