import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Renders the Sherwood brand mark — a centred red "S" with a large red lightning
 * bolt driven diagonally through its middle, on a pure black tile — into every
 * launcher, store, docs and in-app surface.
 *
 * <p>The letterform is a stroked Bezier centreline rather than a font glyph, so
 * the output is identical on any machine (this build host has no fonts
 * installed). Each density is drawn natively by Java2D at an integer
 * supersample factor and then area-averaged down, which keeps the small mdpi
 * tiles clean.
 *
 * <p>Composition: the S sits centred on the canvas and the bolt sits on the same
 * origin, rotated clockwise so its long axis runs "/" from upper-right to
 * lower-left — the opposite diagonal to the S's own "\" spine, which makes the
 * pair read as a cross rather than as one merged blob. A transparent gutter is
 * knocked out of the S along the bolt outline and the bolt is then filled back
 * in, so both shapes stay legible when the tile is only 48 px wide.
 *
 * <p>Fitting is measured, not asserted analytically: the mark is framed by its
 * <em>measured</em> ink bounds and by its <em>measured</em> maximum ink radius,
 * and the smaller of the two wins. Bounds alone used to under-report round caps
 * and joins; a diagonal lockup is also roughly square, so bounding-box fitting
 * pushed its corners outside the 66% adaptive-icon safe circle on the
 * transparent foreground. Radius alone would shrink a tall mark needlessly, so
 * both constraints apply and each surface caps them separately.
 *
 * <p>Usage, from the repository root:
 * <pre>
 *   java scripts/GenerateBrandIcons.java            # write every asset
 *   java scripts/GenerateBrandIcons.java --verify   # check the assets on disk
 * </pre>
 *
 * <p>The social preview card (the 1280x640 image GitHub, Discord, Telegram and
 * WhatsApp show when the repository or the docs site is linked) is written by
 * the same run. Its wordmark needs a font, which this build host does not ship,
 * so the card takes one explicitly:
 * <pre>
 *   java scripts/GenerateBrandIcons.java --font /path/to/Inter.ttf
 * </pre>
 * Any TrueType/OpenType face works; the committed card was rendered from Inter
 * (SIL Open Font License). Without {@code --font} the card is written as the
 * mark alone on black, which is still valid — it just carries no wordmark. The
 * font file itself is never committed: only the rendered PNG is.
 *
 * <p>Design units: origin at the mark centre, +x right, +y down.
 */
public final class GenerateBrandIcons {

    /** Brand red, lifted slightly off pure red so it does not bloom on small tiles. */
    private static final int RED = 0xFF2222;
    private static final int BLACK = 0x000000;

    /** Thickness of the S stroke, design units. */
    private static final double S_STROKE = 0.36;
    /** Transparent gutter knocked out of the S so the bolt reads in front of it. */
    private static final double BOLT_GAP = 0.115;
    /** Bolt length, width/length ratio and clockwise rotation, design units. */
    private static final double BOLT_H = 2.30;
    private static final double BOLT_W_RATIO = 0.44;
    private static final double BOLT_ROTATION = Math.toRadians(34);

    /**
     * How much of the canvas each surface may use: the first entry is the larger
     * ink axis as a fraction of the edge, the second the largest ink radius from
     * the centre as a fraction of the half-edge. The smaller fit wins. A radius
     * of 0.667 is exactly the adaptive-icon safe circle; entries below that are
     * deliberate margins.
     */
    private static final double[] FIT_LEGACY = {0.72, 0.90};
    private static final double[] FIT_ROUND = {0.62, 0.90};
    private static final double[] FIT_ADAPTIVE = {0.62, 0.62};
    private static final double[] FIT_AVATAR = {0.62, 0.90};
    private static final double[] FIT_STORE = {0.72, 0.90};

    /** Launcher density buckets: output px paired with the resource directory. */
    private static final int[] DENSITY_PX = {48, 72, 96, 144, 192};
    private static final String[] DENSITY_DIR = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};

    /** Adaptive-icon safe circle: launchers may crop to 66% of the canvas. */
    private static final double SAFE_CIRCLE_FRACTION = 0.6667;

    /** Memoised measurement of the whole mark, in design units. */
    private static Ink ink;

    /** Measured ink box plus the largest distance of any inked pixel from its centre. */
    private record Ink(Rectangle2D.Double bounds, double radius) {
    }

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

    /**
     * Classic lightning bolt polygon, scaled to {@link #BOLT_H} and rotated
     * {@link #BOLT_ROTATION} clockwise about the origin. Clockwise on a +y-down
     * canvas sends the long axis from upper-right to lower-left, so it crosses
     * the S spine — which runs the other way — at close to a right angle.
     */
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
        double cos = Math.cos(BOLT_ROTATION);
        double sin = Math.sin(BOLT_ROTATION);
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < unit.length; i++) {
            double x = (unit[i][0] - 0.5) * w;
            double y = (unit[i][1] - 0.5) * BOLT_H;
            double rx = x * cos - y * sin;
            double ry = x * sin + y * cos;
            if (i == 0) {
                p.moveTo(rx, ry);
            } else {
                p.lineTo(rx, ry);
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

        // 1. the S, centred on the origin so the letterform holds the middle
        g.setColor(new Color(RED));
        g.setStroke(new BasicStroke((float) S_STROKE,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(sCenterline());

        // 2. knock a transparent gutter out of the S along the bolt outline, so the
        //    two shapes stay separate everywhere the bolt crosses the letterform
        g.setComposite(AlphaComposite.Clear);
        g.setStroke(new BasicStroke((float) (BOLT_GAP * 2),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(bolt);
        g.fill(bolt);

        // 3. the bolt, back in brand red on top of the gutter
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
     * Measured ink box and ink radius of the whole mark, in design units, taken
     * once from a high-resolution render so round caps and joins are included.
     * The radius is measured about the ink box centre because that is the point
     * the mark is later translated onto.
     */
    private static Ink ink() {
        if (ink != null) {
            return ink;
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
        double cx = (minX + maxX + 1) / 2.0;
        double cy = (minY + maxY + 1) / 2.0;
        double r = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if ((img.getRGB(x, y) >>> 24) > 0) {
                    r = Math.max(r, Math.hypot(x + 0.5 - cx, y + 0.5 - cy));
                }
            }
        }
        ink = new Ink(
                new Rectangle2D.Double(
                        (minX - big / 2.0) / unitPx, (minY - big / 2.0) / unitPx,
                        (maxX - minX + 1) / unitPx, (maxY - minY + 1) / unitPx),
                r / unitPx);
        return ink;
    }

    /**
     * Renders the transparent mark onto a square canvas.
     *
     * @param size        output edge in px
     * @param fit         {@code {maxBoundsFrac, maxRadiusFrac}}; the smaller fit wins
     * @param superSample integer supersampling factor (&gt;= 1)
     */
    private static BufferedImage renderMark(int size, double[] fit, int superSample) {
        int big = size * superSample;
        BufferedImage img = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Ink m = ink();
        Rectangle2D.Double b = m.bounds();

        double byBounds = (big * fit[0]) / Math.max(b.getWidth(), b.getHeight());
        double byRadius = (big * 0.5 * fit[1]) / m.radius();
        double scale = Math.min(byBounds, byRadius);

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
    private record Asset(String path, int size, boolean opaque, double[] fit, char shape) {
    }

    private static java.util.List<Asset> assets(String root) {
        String res = root + "/app/src/main/res";
        java.util.List<Asset> list = new java.util.ArrayList<>();
        for (int i = 0; i < DENSITY_PX.length; i++) {
            int px = DENSITY_PX[i];
            list.add(new Asset(res + "/mipmap-" + DENSITY_DIR[i] + "/ic_launcher.png",
                    px, true, FIT_LEGACY, 'q'));
            list.add(new Asset(res + "/mipmap-" + DENSITY_DIR[i] + "/ic_launcher_round.png",
                    px, false, FIT_ROUND, 'o'));
        }
        list.add(new Asset(res + "/drawable/ic_launcher_foreground.png",
                432, false, FIT_ADAPTIVE, 't'));
        list.add(new Asset(res + "/drawable-nodpi/about_avatar.png",
                432, true, FIT_AVATAR, 'q'));
        list.add(new Asset(root + "/docs/public/logo.png", 192, true, FIT_LEGACY, 'q'));
        list.add(new Asset(root + "/fastlane/metadata/android/en-US/images/icon.png",
                512, true, FIT_STORE, 'q'));
        return list;
    }

    private static BufferedImage render(Asset a) {
        int ss = a.size() <= 192 ? 4 : 3;
        BufferedImage mark = renderMark(a.size(), a.fit(), ss);
        return switch (a.shape()) {
            case 'o' -> roundTile(mark);
            case 't' -> mark;
            default -> overBlack(mark);
        };
    }

    private static void writeAll(String root, File fontFile) throws Exception {
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
        String card = root + "/docs/public/social-preview.png";
        BufferedImage social = socialCard(fontFile);
        ImageIO.write(social, "png", new File(card));
        System.out.printf("wrote  %-68s %dx%d%s%n", card, social.getWidth(), social.getHeight(),
                fontFile == null ? "  (mark only: no --font given)" : "");
    }

    // ------------------------------------------------------------ social preview

    /** Link-preview canvas. GitHub's card and most chat clients use 1.91:1 - 2:1. */
    private static final int CARD_W = 1280;
    private static final int CARD_H = 640;

    private static final String WORDMARK = "Sherwood Studio";
    private static final String TAGLINE = "Build Android APKs on your phone.";

    /** Ink of the card has to stay this far from the border so nothing is clipped. */
    private static final int CARD_MARGIN = 48;

    /**
     * The brand card: mark on the left, wordmark and tagline on the right, all on
     * the same black field as the icon so the two read as one identity.
     *
     * <p>Nothing is placed at a hardcoded offset. The mark is rendered first, its
     * real ink box is measured, and the mark/type pair is then centred as one
     * block — the mark's ink is much narrower than its square canvas, so padding
     * the canvas instead would push the lockup visibly off-centre. The wordmark
     * also shrinks until it fits the space right of the mark, so a wider face than
     * Inter cannot overflow the canvas.
     *
     * <p>Weight comes from stroking the glyph outline: a variable font exposes one
     * default instance, so {@code deriveFont(BOLD)} would silently render the
     * regular weight.
     */
    private static BufferedImage socialCard(File fontFile)
            throws IOException, java.awt.FontFormatException {
        BufferedImage img = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = quality(img.createGraphics());
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, CARD_W, CARD_H);

        // 1. the mark, measured rather than assumed
        int markSize = 300;
        BufferedImage mark = renderMark(markSize, new double[]{0.92, 0.92}, 3);
        Rectangle markInk = inkBox(mark);
        int markW = markInk.width;
        int markH = markInk.height;

        if (fontFile == null) {
            g.drawImage(mark, (CARD_W - markSize) / 2, (CARD_H - markSize) / 2, null);
            g.dispose();
            return img;
        }

        Font base = Font.createFont(Font.TRUETYPE_FONT, fontFile);
        Font tagFont = base.deriveFont(38f);
        FontMetrics gm = g.getFontMetrics(tagFont);
        int tagW = gm.stringWidth(TAGLINE);

        // 2. the type, shrunk until the whole lockup fits with its margins
        int gap = 96;
        Font titleFont = base.deriveFont(84f);
        FontMetrics tm = g.getFontMetrics(titleFont);
        int textW = Math.max(tm.stringWidth(WORDMARK), tagW);
        int budget = CARD_W - 2 * CARD_MARGIN - markW - gap;
        while (titleFont.getSize2D() > 24f && Math.max(tm.stringWidth(WORDMARK), tagW) > budget) {
            titleFont = titleFont.deriveFont(titleFont.getSize2D() - 2f);
            tm = g.getFontMetrics(titleFont);
            textW = Math.max(tm.stringWidth(WORDMARK), tagW);
        }

        // 3. one block, centred on both axes
        int totalW = markW + gap + textW;
        int blockLeft = (CARD_W - totalW) / 2;
        g.drawImage(mark,
                blockLeft - markInk.x,
                (CARD_H - markSize) / 2 + (markSize - markH) / 2 - markInk.y,
                null);

        int textLeft = blockLeft + markW + gap;

        int ruleH = 6;
        int ruleGap = 34;
        int titleGap = 26;
        int blockH = ruleH + ruleGap + tm.getAscent() + tm.getDescent()
                + titleGap + gm.getAscent() + gm.getDescent();
        int blockTop = (CARD_H - blockH) / 2;
        int titleBaseline = blockTop + ruleH + ruleGap + tm.getAscent();
        int tagBaseline = titleBaseline + tm.getDescent() + titleGap + gm.getAscent();

        // Brand rule above the wordmark, the same red as the mark.
        g.setColor(new Color(RED));
        g.fillRect(textLeft, blockTop, 84, ruleH);

        drawOutlinedText(g, WORDMARK, titleFont, textLeft, titleBaseline,
                new Color(0xFFFFFF), 2.6f);
        drawOutlinedText(g, TAGLINE, tagFont, textLeft, tagBaseline,
                new Color(0x9CA3AF), 0.6f);

        g.dispose();
        return img;
    }

    /** Bounding box of every non-black pixel, or an empty box for a blank image. */
    private static Rectangle inkBox(BufferedImage img) {
        int minX = img.getWidth();
        int minY = img.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) > 8) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? new Rectangle() : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** Fills a glyph outline and strokes it by {@code weightPx} to set the weight. */
    private static void drawOutlinedText(Graphics2D g, String text, Font font,
                                         int x, int baseline, Color color, float weightPx) {
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), text);
        Shape outline = gv.getOutline(x, baseline);
        g.setColor(color);
        g.fill(outline);
        g.setStroke(new BasicStroke(weightPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
        g.setStroke(new BasicStroke(1f));
    }

    private static void verifySocialCard(String root) throws IOException {
        String path = root + "/docs/public/social-preview.png";
        File f = new File(path);
        if (!f.isFile()) {
            check(false, path + " is missing");
            return;
        }
        BufferedImage img = ImageIO.read(f);
        if (img == null) {
            check(false, path + " is not readable");
            return;
        }
        check(img.getWidth() == CARD_W && img.getHeight() == CARD_H,
                path + " is " + img.getWidth() + "x" + img.getHeight()
                        + ", expected " + CARD_W + "x" + CARD_H);
        check(!img.getColorModel().hasAlpha(), path + " must be opaque");

        long black = 0;
        long red = 0;
        long light = 0;
        int minX = CARD_W;
        int minY = CARD_H;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < CARD_H; y++) {
            for (int x = 0; x < CARD_W; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                int r = (rgb >> 16) & 0xFF;
                int gr = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (rgb == BLACK) {
                    black++;
                    continue;
                }
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (r > 150 && gr < 90 && b < 90) {
                    red++;
                }
                if (r > 200 && gr > 200 && b > 200) {
                    light++;
                }
            }
        }
        check(black > (long) CARD_W * CARD_H / 2,
                path + " is not mostly the black brand field (" + black + " px)");
        check(red > 3000, path + " carries no brand mark (" + red + " red px)");
        check(light > 2000,
                path + " carries no wordmark (" + light + " light px) - was --font passed?");
        check(minX >= CARD_MARGIN && minY >= CARD_MARGIN && maxX < CARD_W - CARD_MARGIN
                        && maxY < CARD_H - CARD_MARGIN,
                path + " ink is clipped or touches the margin: "
                        + minX + "," + minY + " - " + maxX + "," + maxY);
        // The mark sits left of the type; red beyond the text column would mean the
        // rule or the mark drifted out of its own half of the card.
        check(minX < CARD_W / 2, path + " has no mark on the left: ink starts at x=" + minX);
        // The lockup is centred from measured ink, so the ink box must sit on the
        // canvas centre; a drift here means the padding was counted as ink again.
        int inkCentreX = (minX + maxX) / 2;
        int inkCentreY = (minY + maxY) / 2;
        check(Math.abs(inkCentreX - CARD_W / 2) <= 20,
                path + " is " + (inkCentreX - CARD_W / 2) + " px off the horizontal centre");
        check(Math.abs(inkCentreY - CARD_H / 2) <= 20,
                path + " is " + (inkCentreY - CARD_H / 2) + " px off the vertical centre");
        System.out.printf("ok     %-68s %dx%d  ink %d,%d-%d,%d%n",
                path, img.getWidth(), img.getHeight(), minX, minY, maxX, maxY);
    }

    // ------------------------------------------------------------ verification

    private static int failures;

    private static void check(boolean ok, String message) {
        if (!ok) {
            failures++;
            System.out.println("FAIL   " + message);
        }
    }

    /**
     * True when the pixel carries the mark: alpha on the transparent adaptive
     * foreground, non-black on a tile. Round tiles are black plates with an alpha
     * channel, so testing alpha there would count the whole disc as ink.
     */
    private static boolean inked(int argb, boolean plate) {
        if (plate) {
            return Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF)) > 24;
        }
        return (argb >>> 24) > 8;
    }

    /**
     * Number of 4-connected components of inked pixels. A plain S is one piece;
     * once the bolt cuts through it there are three or more (upper S, lower S,
     * bolt). This proves the gutter still separates the shapes at the delivered
     * resolution instead of trusting the design intent.
     */
    private static int inkComponents(BufferedImage img, boolean plate) {
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int components = 0;
        for (int start = 0; start < w * h; start++) {
            int sx = start % w;
            int sy = start / w;
            if (seen[start] || !inked(img.getRGB(sx, sy), plate)) {
                continue;
            }
            components++;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int p = stack[--top];
                int x = p % w;
                int y = p / w;
                int[] nx = {x - 1, x + 1, x, x};
                int[] ny = {y, y, y - 1, y + 1};
                for (int i = 0; i < 4; i++) {
                    int qx = nx[i];
                    int qy = ny[i];
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) {
                        continue;
                    }
                    int q = qy * w + qx;
                    if (!seen[q] && inked(img.getRGB(qx, qy), plate)) {
                        seen[q] = true;
                        stack[top++] = q;
                    }
                }
            }
        }
        return components;
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
            boolean plate = a.shape() != 't';
            double maxR = 0;
            long inkedPixels = 0;
            long exactRed = 0;
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    int p = img.getRGB(x, y);
                    if (!inked(p, plate)) {
                        continue;
                    }
                    inkedPixels++;
                    maxR = Math.max(maxR, Math.hypot(x + 0.5 - mid, y + 0.5 - mid));
                    int rgb = p & 0xFFFFFF;
                    if (rgb == RED) {
                        exactRed++;
                    }
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
            // The gutter is transparent, so the mark's own pixels stay brand red;
            // this catches a wrong palette or an accidental shadow layer.
            check(exactRed > inkedPixels / 2,
                    a.path() + " is mostly not brand red (" + exactRed + "/" + inkedPixels
                            + " exact #" + String.format("%06X", RED) + ")");

            if (a.shape() == 't') {
                check(maxR <= safeR,
                        a.path() + " exceeds the adaptive safe circle: "
                                + String.format("%.1f > %.1f", maxR, safeR));
                // The bolt must visibly cut the S: a single blob would mean the
                // gutter vanished at this resolution and the mark turned into a smudge.
                int parts = inkComponents(img, plate);
                check(parts >= 3,
                        a.path() + " has " + parts + " ink component(s); the bolt gutter must "
                                + "separate the S from the bolt (expected >= 3)");
            }

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
            System.out.printf("ok     %-68s %dx%d  ink radius %.1f%s%n",
                    a.path(), w, w, maxR,
                    a.shape() == 't' ? String.format(" / safe circle %.1f", safeR) : "");
        }

        verifySocialCard(root);

        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("\nall brand assets verified");
    }

    public static void main(String[] args) throws Exception {
        String root = ".";
        boolean verify = false;
        File font = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--verify")) {
                verify = true;
            } else if (args[i].equals("--font") && i + 1 < args.length) {
                font = new File(args[++i]);
                if (!font.isFile()) {
                    System.err.println("font not found: " + font);
                    System.exit(2);
                }
            } else {
                root = args[i];
            }
        }
        if (verify) {
            verifyAll(root);
        } else {
            writeAll(root, font);
        }
    }
}
