import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Renders the Sherwood brand mark — a red "S" with a red lightning bolt on a
 * pure black tile — into every launcher, store, docs and in-app surface.
 *
 * <p>The letterform is a stroked Bezier centreline rather than a font glyph, so
 * the output is identical on any machine (this build host has no fonts
 * installed). Each density is drawn natively by Java2D at an integer
 * supersample factor and then area-averaged down, which keeps the small mdpi
 * tiles clean.
 *
 * <p>The mark is framed by its <em>measured</em> ink bounds rather than by an
 * analytic bounding box: the analytic box under-reports round caps and joins,
 * which silently pushed the mark outside the adaptive-icon safe circle.
 *
 * <p>Usage, from the repository root:
 * <pre>
 *   java scripts/GenerateBrandIcons.java            # write every asset
 *   java scripts/GenerateBrandIcons.java --verify   # check the assets on disk
 * </pre>
 *
 * <p>Design units: origin at the mark centre, +x right, +y down.
 */
public final class GenerateBrandIcons {

    /** Brand red, lifted slightly off pure red so it does not bloom on small tiles. */
    private static final int RED = 0xFF2222;
    private static final int BLACK = 0x000000;

    /** Thickness of the S stroke, design units. */
    private static final double S_STROKE = 0.33;
    /** Transparent gutter knocked out of the S so the bolt reads in front of it. */
    private static final double BOLT_GAP = 0.105;
    /** Bolt height, width/height ratio and centre, design units. */
    private static final double BOLT_H = 1.62;
    private static final double BOLT_W_RATIO = 0.55;
    private static final double BOLT_CX = 0.94;
    private static final double BOLT_CY = 0.0;

    /** Fraction of the tile the mark's ink occupies, per surface kind. */
    private static final double FRAC_LEGACY = 0.72;
    private static final double FRAC_ROUND = 0.62;
    private static final double FRAC_ADAPTIVE = 0.56;
    private static final double FRAC_AVATAR = 0.62;
    private static final double FRAC_STORE = 0.72;

    /** Launcher density buckets: output px paired with the resource directory. */
    private static final int[] DENSITY_PX = {48, 72, 96, 144, 192};
    private static final String[] DENSITY_DIR = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};

    /** Adaptive-icon safe circle: launchers may crop to 66% of the canvas. */
    private static final double SAFE_CIRCLE_FRACTION = 0.6667;

    /** Memoised inked bounds of the whole mark, in design units. */
    private static Rectangle2D.Double inkBounds;

    private GenerateBrandIcons() {
    }

    // ---------------------------------------------------------------- geometry

    /** Centreline of the S: six cubics, centrally symmetric about the origin. */
    private static Path2D.Double sCenterline() {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0.55, -0.80);
        p.curveTo(0.42, -1.00, 0.08, -1.04, -0.24, -0.92);
        p.curveTo(-0.48, -0.83, -0.58, -0.60, -0.50, -0.36);
        p.curveTo(-0.44, -0.16, -0.22, -0.06, 0.0, 0.0);
        p.curveTo(0.22, 0.06, 0.44, 0.16, 0.50, 0.36);
        p.curveTo(0.58, 0.60, 0.48, 0.83, 0.24, 0.92);
        p.curveTo(-0.08, 1.04, -0.42, 1.00, -0.55, 0.80);
        return p;
    }

    /** Classic lightning bolt polygon scaled to {@link #BOLT_H}. */
    private static Path2D.Double boltPath() {
        double[][] unit = {
                {0.58, 0.00},
                {0.06, 0.56},
                {0.40, 0.56},
                {0.28, 1.00},
                {0.94, 0.44},
                {0.60, 0.44},
        };
        double w = BOLT_H * BOLT_W_RATIO;
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < unit.length; i++) {
            double x = BOLT_CX + (unit[i][0] - 0.5) * w;
            double y = BOLT_CY + (unit[i][1] - 0.5) * BOLT_H;
            if (i == 0) {
                p.moveTo(x, y);
            } else {
                p.lineTo(x, y);
            }
        }
        p.closePath();
        return p;
    }

    /** Draws the mark into {@code g}, mapping design unit 1.0 to {@code unitPx}. */
    private static void drawMark(Graphics2D g, double unitPx, double originX, double originY) {
        g.translate(originX, originY);
        g.scale(unitPx, unitPx);

        Shape bolt = boltPath();

        // 1. the S
        g.setColor(new Color(RED));
        g.setStroke(new BasicStroke((float) S_STROKE,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(sCenterline());

        // 2. knock a transparent gutter out of the S so the bolt sits in front of it
        g.setComposite(AlphaComposite.Clear);
        g.setStroke(new BasicStroke((float) (BOLT_GAP * 2),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(bolt);
        g.fill(bolt);

        // 3. the bolt
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(RED));
        g.fill(bolt);
    }

    private static Graphics2D quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    // --------------------------------------------------------------- rendering

    /**
     * Measured bounds of every inked pixel, in design units. Measured once, from
     * a high-resolution render, so round caps and joins are included.
     */
    private static Rectangle2D.Double inkBounds() {
        if (inkBounds != null) {
            return inkBounds;
        }
        int big = 1600;
        double unitPx = 400.0;
        BufferedImage img = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(img.createGraphics());
        drawMark(g, unitPx, big / 2.0, big / 2.0);
        g.dispose();

        int minX = big, minY = big, maxX = -1, maxY = -1;
        for (int y = 0; y < big; y++) {
            for (int x = 0; x < big; x++) {
                if ((img.getRGB(x, y) >>> 24) > 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        inkBounds = new Rectangle2D.Double(
                (minX - big / 2.0) / unitPx, (minY - big / 2.0) / unitPx,
                (maxX - minX + 1) / unitPx, (maxY - minY + 1) / unitPx);
        return inkBounds;
    }

    /**
     * Renders the transparent mark onto a square canvas.
     *
     * @param size        output edge in px
     * @param contentFrac fraction of the canvas the mark's ink occupies
     * @param superSample integer supersampling factor (&gt;= 1)
     */
    private static BufferedImage renderMark(int size, double contentFrac, int superSample) {
        int big = size * superSample;
        BufferedImage img = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Rectangle2D.Double b = inkBounds();
        double scale = (big * contentFrac) / Math.max(b.getWidth(), b.getHeight());

        Graphics2D g = quality(img.createGraphics());
        drawMark(g, scale,
                big / 2.0 - scale * b.getCenterX(),
                big / 2.0 - scale * b.getCenterY());
        g.dispose();

        return superSample == 1 ? img : boxDownscale(img, superSample);
    }

    /** Area-average downscale by an integer factor, honouring premultiplication. */
    private static BufferedImage boxDownscale(BufferedImage src, int f) {
        int w = src.getWidth() / f;
        int h = src.getHeight() / f;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] srcPx = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] dstPx = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        int sw = src.getWidth();
        int area = f * f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < f; dy++) {
                    int row = (y * f + dy) * sw + x * f;
                    for (int dx = 0; dx < f; dx++) {
                        int p = srcPx[row + dx];
                        int pa = p >>> 24;
                        a += pa;
                        r += ((p >> 16) & 0xFF) * pa / 255;
                        g += ((p >> 8) & 0xFF) * pa / 255;
                        b += (p & 0xFF) * pa / 255;
                    }
                }
                int aOut = a / area;
                int rOut = a == 0 ? 0 : Math.min(255, r * 255 / a);
                int gOut = a == 0 ? 0 : Math.min(255, g * 255 / a);
                int bOut = a == 0 ? 0 : Math.min(255, b * 255 / a);
                dstPx[y * w + x] = (aOut << 24) | (rOut << 16) | (gOut << 8) | bOut;
            }
        }
        return out;
    }

    /** Composites a transparent mark over an opaque black tile. */
    private static BufferedImage overBlack(BufferedImage mark) {
        int w = mark.getWidth();
        int h = mark.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.drawImage(mark, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Opaque black tile with the mark, clipped to a centred circle. */
    private static BufferedImage roundTile(BufferedImage mark) {
        int w = mark.getWidth();
        BufferedImage out = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(out.createGraphics());
        g.setColor(Color.BLACK);
        g.fillOval(0, 0, w, w);
        g.drawImage(mark, 0, 0, null);
        g.dispose();
        return out;
    }

    // ----------------------------------------------------------------- outputs

    /**
     * One output asset.
     *
     * @param shape {@code q} opaque square tile, {@code o} round tile,
     *              {@code t} transparent adaptive foreground
     */
    private record Asset(String path, int size, boolean opaque, double frac, char shape) {
    }

    private static java.util.List<Asset> assets(String root) {
        String res = root + "/app/src/main/res";
        java.util.List<Asset> list = new java.util.ArrayList<>();
        for (int i = 0; i < DENSITY_PX.length; i++) {
            int px = DENSITY_PX[i];
            list.add(new Asset(res + "/mipmap-" + DENSITY_DIR[i] + "/ic_launcher.png",
                    px, true, FRAC_LEGACY, 'q'));
            list.add(new Asset(res + "/mipmap-" + DENSITY_DIR[i] + "/ic_launcher_round.png",
                    px, false, FRAC_ROUND, 'o'));
        }
        list.add(new Asset(res + "/drawable/ic_launcher_foreground.png",
                432, false, FRAC_ADAPTIVE, 't'));
        list.add(new Asset(res + "/drawable-nodpi/about_avatar.png",
                432, true, FRAC_AVATAR, 'q'));
        list.add(new Asset(root + "/docs/public/logo.png", 192, true, FRAC_LEGACY, 'q'));
        list.add(new Asset(root + "/fastlane/metadata/android/en-US/images/icon.png",
                512, true, FRAC_STORE, 'q'));
        return list;
    }

    private static BufferedImage render(Asset a) {
        int ss = a.size() <= 192 ? 4 : 3;
        BufferedImage mark = renderMark(a.size(), a.frac(), ss);
        return switch (a.shape()) {
            case 'o' -> roundTile(mark);
            case 't' -> mark;
            default -> overBlack(mark);
        };
    }

    private static void writeAll(String root) throws IOException {
        for (Asset a : assets(root)) {
            BufferedImage img = render(a);
            File f = new File(a.path());
            File dir = f.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            ImageIO.write(img, "png", f);
            System.out.printf("wrote  %-68s %dx%d%n", a.path(), img.getWidth(), img.getHeight());
        }
    }

    // ------------------------------------------------------------ verification

    private static int failures;

    private static void check(boolean ok, String message) {
        if (!ok) {
            failures++;
            System.out.println("FAIL   " + message);
        }
    }

    /** True when the pixel carries ink: opaque on RGBA, non-black on an opaque tile. */
    private static boolean inked(int argb, boolean opaque) {
        if (opaque) {
            return Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF)) > 24;
        }
        return (argb >>> 24) > 8;
    }

    private static void verifyAll(String root) throws IOException {
        for (Asset a : assets(root)) {
            File f = new File(a.path());
            if (!f.isFile()) {
                check(false, a.path() + " is missing");
                continue;
            }
            BufferedImage img = ImageIO.read(f);
            check(img != null, a.path() + " is not readable");
            if (img == null) {
                continue;
            }
            int w = img.getWidth();
            check(img.getWidth() == a.size() && img.getHeight() == a.size(),
                    a.path() + " is " + img.getWidth() + "x" + img.getHeight()
                            + ", expected " + a.size());
            check(img.getColorModel().hasAlpha() != a.opaque(),
                    a.path() + " has the wrong alpha channel");

            double mid = w / 2.0;
            double safeR = w * SAFE_CIRCLE_FRACTION / 2.0;
            double maxR = 0;
            long inkedPixels = 0;
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    int p = img.getRGB(x, y);
                    if (!inked(p, a.opaque())) {
                        continue;
                    }
                    inkedPixels++;
                    maxR = Math.max(maxR, Math.hypot(x + 0.5 - mid, y + 0.5 - mid));
                    if (a.opaque() && (p >>> 24) < 250) {
                        check(false, a.path() + " has a translucent pixel at " + x + "," + y);
                    }
                    if (a.shape() == 'o') {
                        check(Math.hypot(x + 0.5 - mid, y + 0.5 - mid) <= mid + 1.0,
                                a.path() + " inks outside its circle at " + x + "," + y);
                    }
                }
            }
            check(inkedPixels > w * w / 20, a.path() + " looks empty");

            if (a.opaque()) {
                int corner = img.getRGB(1, 1) & 0xFFFFFF;
                check(corner == BLACK,
                        a.path() + " corner is #" + String.format("%06X", corner)
                                + ", expected black");
                // nothing may be clipped: the outermost pixel ring stays pure black
                for (int i = 0; i < w; i++) {
                    for (int[] edge : new int[][]{{i, 0}, {i, w - 1}, {0, i}, {w - 1, i}}) {
                        if ((img.getRGB(edge[0], edge[1]) & 0xFFFFFF) != BLACK) {
                            check(false, a.path() + " ink is clipped at the border "
                                    + edge[0] + "," + edge[1]);
                        }
                    }
                }
            }
            if (a.shape() == 't') {
                check(maxR <= safeR,
                        a.path() + " exceeds the adaptive safe circle: "
                                + String.format("%.1f > %.1f", maxR, safeR));
            }
            System.out.printf("ok     %-68s %dx%d  ink radius %.1f%s%n",
                    a.path(), w, w, maxR,
                    a.shape() == 't' ? String.format(" / safe circle %.1f", safeR) : "");
        }

        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("\nall brand assets verified");
    }

    public static void main(String[] args) throws Exception {
        String root = ".";
        boolean verify = false;
        for (String arg : args) {
            if (arg.equals("--verify")) {
                verify = true;
            } else {
                root = arg;
            }
        }
        if (verify) {
            verifyAll(root);
        } else {
            writeAll(root);
        }
    }
}
