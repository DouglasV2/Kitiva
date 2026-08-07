package hr.kitiva.beauty.nail;

import hr.kitiva.beauty.dto.NailDesignSpecDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Vertical slice — renders the resolved design as a deterministic SVG <strong>Design Diagram</strong>.
 *
 * <p>Called a Design Diagram everywhere, never an "inspiration image" and never implied photorealistic. It
 * exists to let a user verify that we understood her: shape, length, colour, finish, effects and which nail
 * carries the accent. A generated photo would look more impressive and prove less — and would risk depicting
 * an effect the kit cannot actually buy.</p>
 *
 * <p>Pure function of {@link NailDesignResolver} output: same spec in, byte-identical SVG out. No cache
 * table, because an SVG re-renders in microseconds; no image provider, because none is needed to satisfy
 * "the preview comes from the structured spec".</p>
 *
 * <h2>What this draws, and why it changed</h2>
 *
 * <p><strong>One hand, large.</strong> It used to draw two hands of five nails each, which halved the scale
 * of the thing the user actually came to see and made the picture read as a schematic table. A nail preview
 * shows a hand. Symmetry is a one-line fact ("isto na obje ruke"), so it is stated in words next to the
 * picture instead of costing half the picture. The hand drawn is the RIGHT one, because that is the hand the
 * resolver puts the accent on when the user says the hands deliberately differ — so the informative hand is
 * always the one on screen.</p>
 *
 * <p><strong>Real silhouettes.</strong> Every shape used to be a rounded rectangle: ALMOND was a rect whose
 * tip arched 6.75px over a 42px width, which is a square nail with a soft top; OVAL and ROUND emitted
 * byte-identical paths; COFFIN tapered 4px per side. Shape is the first thing a user checks and the thing a
 * technician actually files, so each of the seven now has its own outline — free-edge width, corner and
 * sidewall taper are what distinguish them — and all seven share a rounded cuticle so they read as nail
 * plates rather than tiles. The plates also fan and vary in width per finger, which is what makes five of
 * them read as a hand.</p>
 *
 * <p><strong>Finish is drawn.</strong> It used to appear only in a caption, so a matte request and a glossy
 * one produced the same picture. Gloss is what makes a flat shape read as lacquer, and it is a field the kit
 * has to buy a product for, so it belongs in the drawing.</p>
 *
 * <p><strong>No text inside the SVG.</strong> The old version carried ten finger labels, a caption and a
 * legend. At the width this renders on a phone those became 5px type. The picture now carries the accent as
 * a visible band plus a marker under the plate, and every name is set in real HTML next to it, at a real
 * size, by the component that owns the layout.</p>
 */
@Component
public class NailDesignDiagramRenderer {

    private final NailDesignResolver resolver;

    public NailDesignDiagramRenderer(NailDesignResolver resolver) {
        this.resolver = resolver;
    }

    /** The frame. Both hands are fitted into it, so the preview always fills its container. */
    private static final double FRAME_W = 380;
    private static final double FRAME_H = 430;
    private static final double PAD = 14;

    /** Room reserved above each row for its hand name, and the space between the two rows. */
    private static final double LABEL_H = 26;
    private static final double ROW_GAP = 14;
    private static final double LABEL_SIZE = 14;
    private static final String LABEL_FONT_FAMILY =
            "system-ui,-apple-system,Segoe UI,Roboto,Helvetica Neue,Arial,sans-serif";

    /** A bare nail, when no colour was stated. Warm keratin, not a pink placeholder. */
    private static final String BARE = "#E8DCD6";
    private static final String INK = "#231A1F";
    private static final String MUTED = "#9A8189";

    /**
     * Skin.
     *
     * <p>Chosen to be the most neutral warm mid-light the palette allows, and then desaturated until it
     * stopped competing: a nail preview exists so a user can judge a LACQUER, and skin that leans pink
     * makes every cool red look muddy while skin that leans yellow kills every nude. These three tones are
     * one hue at three lightnesses, so a finger reads as a cylinder without introducing a second colour
     * that the polish has to fight.</p>
     */
    private static final String SKIN_LIGHT = "#F3E2D8";
    private static final String SKIN_MID = "#E7CDBF";
    private static final String SKIN_SHADE = "#D2AE9C";

    /**
     * How far the fingertip extends below the cuticle, in hand units.
     *
     * <p>Fingertips, not whole hands. Two hands are stacked in one 380-wide frame, so a full hand each
     * would put ten nails at a size where none of them can be judged — which is the only reason the
     * picture exists. A fingertip that fades out carries the light, the shadow under the free edge and the
     * sense of a nail sitting in a nail bed, and costs almost no vertical room.</p>
     */
    private static final double FINGER_LEN = 78;

    /** Flesh is wider than the nail it carries, and by more on the thumb than on the pinky. */
    private static final double FINGER_W_FACTOR = 1.30;

    /** Right hand, palm down: thumb on the left, then index to pinky. Matches the finger chips' order. */
    private static final NailDesignSpecDto.Finger[] ORDER = {
            NailDesignSpecDto.Finger.THUMB, NailDesignSpecDto.Finger.INDEX,
            NailDesignSpecDto.Finger.MIDDLE, NailDesignSpecDto.Finger.RING,
            NailDesignSpecDto.Finger.PINKY };

    // Anatomy, in hand-space units. Width, cuticle height, length and tilt all differ per finger, and that
    // variation is the whole reason five plates read as a hand instead of a row of tiles. The thumb is the
    // outlier on every axis: widest, shortest, lowest, and turned well out from the others.
    private static final double[] PLATE_W = { 56, 50, 52, 47, 39 };
    private static final double[] CUTICLE_Y = { 302, 249, 238, 248, 268 };
    private static final double[] LENGTH_FACTOR = { 0.88, 1.00, 1.10, 1.02, 0.80 };
    // The thumb turns much further out than it used to. When the plates floated on their own, -16° read as
    // "the outer nail"; once each one sat on a drawn finger, a barely-tilted thumb separated by a gap read
    // as a severed index finger. A thumb is recognisable by its angle before anything else, so it now
    // rotates like one and sits lower, and the gap after it shrinks because the angle is doing that work.
    private static final double[] TILT_DEG = { -34, -6, 0, 5, 11 };
    /** Space before each plate. Fingers sit close together; the thumb stands apart from them. */
    private static final double[] LEAD_GAP = { 0, 12, 8, 8, 8 };

    /** Cuticle corner radius and arc control, as fractions of plate width. Shared by all seven shapes. */
    private static final double CUTICLE_R = 0.20;
    private static final double CUTICLE_ARC = 0.30;

    /**
     * How far the cuticle actually dips below the plate box — the apex of the quadratic, not its control
     * point. Used for the marker offset and the bounding box, both of which need the real extent.
     */
    private static double cuticleDip(double w) {
        return w * (CUTICLE_ARC - CUTICLE_R) / 2;
    }

    /**
     * Ceiling on the fit scale. Without it a short-nail hand fills a 700x400 frame edge to edge, and every
     * feature on the plate — cuticle curve, highlight, cat-eye streak — scales up with it until five nails
     * read as five objects rather than as a hand. Air around the hand is what makes it legible.
     */
    private static final double MAX_FIT = 1.15;

    /**
     * Sparkle positions as fractions of the plate box, for SHIMMER and GLITTER_ACCENT. A fixed table rather
     * than a seeded generator, so "same spec in, byte-identical SVG out" is true by construction.
     */
    private static final double[][] SPARKS = {
            { 0.22, 0.16 }, { 0.63, 0.11 }, { 0.41, 0.29 }, { 0.79, 0.24 }, { 0.15, 0.41 },
            { 0.55, 0.45 }, { 0.86, 0.53 }, { 0.29, 0.57 }, { 0.68, 0.65 }, { 0.45, 0.73 },
            { 0.19, 0.79 }, { 0.75, 0.84 }, { 0.52, 0.90 }, { 0.34, 0.35 }, { 0.61, 0.30 } };

    /** One nail plate, positioned. y is the free edge (the tip); y + h is the cuticle. */
    private record Plate(double x, double y, double w, double h, double tilt, boolean accent) {
        double centerX() { return x + w / 2; }
        double cuticleY() { return y + h; }
    }

    public String render(NailDesignSpecDto design) {
        String fill = design.baseColorHex() == null || design.baseColorHex().isBlank()
                ? BARE : design.baseColorHex();
        String accentColor = design.accentColorForDiagram();
        Set<NailDesignSpecDto.Effect> effects = design.activeEffects();
        List<List<Plate>> hands = List.of(
                layOutHand(design, NailDesignResolver.Hand.LEFT),
                layOutHand(design, NailDesignResolver.Hand.RIGHT));
        boolean anyAccent = hands.stream().flatMap(List::stream).anyMatch(Plate::accent);
        // Element ids must not collide when two diagrams share a document, and they must not be random or
        // the "byte-identical SVG out" promise breaks. Derived from the spec: same spec, same ids.
        String uid = fingerprint(design);
        String edge = edgeColor(fill);
        double edgeAlpha = isDark(fill) ? 0.30 : 0.26;

        // Both rows reserve the same box whether or not they carry an accent, so the two hands stay aligned
        // and an asymmetric design does not make one row taller than the other.
        double[] box = boundingBox(hands.get(1), anyAccent);
        double handW = box[2] - box[0];
        double handH = box[3] - box[1];
        double contentW = handW;
        double contentH = 2 * (LABEL_H + handH) + ROW_GAP;

        double availW = FRAME_W - 2 * PAD;
        double availH = FRAME_H - 2 * PAD;
        // Short nails and extra-long nails have very different proportions; a fixed scale would leave the
        // common case floating in an empty box, so the hands are fitted to the frame — up to a ceiling.
        double s = Math.min(MAX_FIT, Math.min(availW / contentW, availH / contentH));
        double tx = PAD + (availW - contentW * s) / 2 - box[0] * s;
        double ty = PAD + (availH - contentH * s) / 2;

        StringBuilder svg = new StringBuilder(9216);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
           .append(n(FRAME_W)).append(' ').append(n(FRAME_H)).append("\" width=\"").append(n(FRAME_W))
           .append("\" height=\"").append(n(FRAME_H)).append("\" role=\"img\" aria-label=\"")
           .append(esc(ariaLabel(design))).append("\">");

        svg.append("<defs>");
        appendSkinGradients(svg, uid);
        appendFinishGradient(svg, uid, design.finish());
        if (effects.contains(NailDesignSpecDto.Effect.CAT_EYE)) appendCatEyeGradient(svg, uid);
        // A clip per plate, so gloss, finish and effects stay inside the nail whatever its outline. Plate
        // geometry is identical between the hands, so one set of five clips serves both rows.
        for (int i = 0; i < 5; i++) {
            svg.append("<clipPath id=\"nk").append(uid).append('-').append(i).append("\"><path d=\"")
               .append(platePath(hands.get(1).get(i), design.shape())).append("\"/></clipPath>");
        }
        svg.append("</defs>");

        for (int hand = 0; hand < hands.size(); hand++) {
            // Where this row's hand box starts in content space. A plate's own y is in hand-local space, so
            // the group shifts local -> content by (rowTop - box[1]).
            double rowTop = hand * (LABEL_H + handH + ROW_GAP) + LABEL_H;

            svg.append("<g transform=\"translate(").append(n(tx)).append(' ').append(n(ty))
               .append(") scale(").append(n4(s)).append(") translate(0 ").append(n(rowTop - box[1]))
               .append(")\">");
            // Skin first, all five fingers, so a nail always sits ON a fingertip rather than beside one —
            // and so one finger's soft edge can overlap its neighbour the way fingers actually do.
            for (int i = 0; i < 5; i++) {
                appendFinger(svg, hands.get(hand).get(i), design, uid, i);
            }
            for (int i = 0; i < 5; i++) {
                appendPlate(svg, hands.get(hand).get(i), design, uid, i, fill, edge, edgeAlpha,
                        effects, accentColor);
            }
            svg.append("</g>");

            // The hand names sit OUTSIDE the fitted group, in frame units, so they stay the same size
            // whatever the nail length does to the fit scale. Two labels, both legible on a phone — the
            // version this replaces carried ten finger names, a caption and a legend at 5px.
            svg.append("<text x=\"").append(n(FRAME_W / 2)).append("\" y=\"")
               .append(n(ty + rowTop * s - 8)).append("\" text-anchor=\"middle\" font-family=\"")
               .append(LABEL_FONT_FAMILY).append("\" font-size=\"").append(n(LABEL_SIZE))
               .append("\" letter-spacing=\"0.04em\" fill=\"").append(MUTED).append("\">")
               .append(esc(hand == 0 ? "lijeva ruka" : "desna ruka")).append("</text>");
        }

        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * The skin palette, as gradients.
     *
     * <p>Two of them, doing two different jobs. {@code nks} shades a finger ACROSS its width, which is
     * what makes a flat path read as a cylinder — light catching one side, the turn into shadow on the
     * other. {@code nkfade} runs DOWN the finger and takes it to fully transparent at the bottom, so the
     * fingertip dissolves instead of ending in a cut edge. That fade is the whole reason this can be a
     * fingertip rather than a hand: nothing has to be drawn beyond where the light stops.</p>
     */
    private void appendSkinGradients(StringBuilder svg, String uid) {
        svg.append("<linearGradient id=\"nks").append(uid).append("\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\">");
        stops(svg,
                new double[] { 0, 0.16, 0.46, 0.78, 1 },
                new String[] { SKIN_SHADE, SKIN_MID, SKIN_LIGHT, SKIN_MID, SKIN_SHADE },
                new double[] { 1, 1, 1, 1, 1 });
        svg.append("</linearGradient>");

        // y1/y2 are flipped relative to the finger path because a plate's y grows DOWNWARD from the tip:
        // offset 0 is the fingertip, offset 1 is where the finger leaves the frame.
        svg.append("<linearGradient id=\"nkfade").append(uid).append("\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">");
        svg.append("<stop offset=\"0\" stop-color=\"#FFFFFF\" stop-opacity=\"1\"/>")
           .append("<stop offset=\"0.62\" stop-color=\"#FFFFFF\" stop-opacity=\"1\"/>")
           .append("<stop offset=\"1\" stop-color=\"#FFFFFF\" stop-opacity=\"0\"/>");
        svg.append("</linearGradient>");

        // maskContentUnits matters as much as maskUnits: without it the 1x1 rect below is one USER unit,
        // a single pixel, and the entire fingertip is masked away. The first version of this drew perfect
        // fingers that were invisible.
        svg.append("<mask id=\"nkm").append(uid)
           .append("\" maskUnits=\"objectBoundingBox\" maskContentUnits=\"objectBoundingBox\"")
           .append(" x=\"0\" y=\"0\" width=\"1\" height=\"1\">")
           .append("<rect x=\"0\" y=\"0\" width=\"1\" height=\"1\" fill=\"url(#nkfade").append(uid)
           .append(")\"/></mask>");
    }

    /**
     * One fingertip, drawn under its nail.
     *
     * <p>Three things earn their place here, and nothing else does. The flesh is <em>wider</em> than the
     * plate, because a nail sits in a bed rather than on top of a stick. It carries a cross-gradient, so
     * it turns like a cylinder instead of reading as a beige rectangle. And there is a soft dark arc right
     * under the cuticle, which is the single cue that makes a nail look attached: without it the plate
     * floats a millimetre above the finger no matter how well the plate itself is drawn.</p>
     *
     * <p>Where the nail overhangs — a long or extra-long free edge — the flesh stops short of the tip and
     * a shadow is cast onto the finger below it, which is what actually communicates length.</p>
     */
    private void appendFinger(StringBuilder svg, Plate p, NailDesignSpecDto design, String uid, int index) {
        double w = p.w() * FINGER_W_FACTOR;
        double cx = p.centerX();
        double x = cx - w / 2;
        double cuticleY = p.cuticleY();
        // How much of the nail is actually ON the finger. A short nail is nearly all nail bed; an
        // extra-long one is mostly free edge hanging past the fingertip.
        double bedFraction = switch (design.length()) {
            case SHORT -> 0.90;
            case MEDIUM -> 0.70;
            case LONG -> 0.55;
            case EXTRA_LONG -> 0.44;
        };
        double tipY = cuticleY - p.h() * bedFraction;
        double bottomY = cuticleY + FINGER_LEN;
        double r = w / 2;

        svg.append("<g transform=\"rotate(").append(n(p.tilt())).append(' ')
           .append(n(cx)).append(' ').append(n(cuticleY)).append(")\" mask=\"url(#nkm").append(uid)
           .append(")\">");

        // The finger: a column with a rounded end, drawn from the fingertip down.
        svg.append("<path d=\"M").append(n(x)).append(' ').append(n(tipY + r))
           .append("C").append(n(x)).append(' ').append(n(tipY + r * 0.34))
           .append(' ').append(n(x + w * 0.22)).append(' ').append(n(tipY))
           .append(' ').append(n(cx)).append(' ').append(n(tipY))
           .append("C").append(n(x + w * 0.78)).append(' ').append(n(tipY))
           .append(' ').append(n(x + w)).append(' ').append(n(tipY + r * 0.34))
           .append(' ').append(n(x + w)).append(' ').append(n(tipY + r))
           .append("L").append(n(x + w)).append(' ').append(n(bottomY))
           .append("L").append(n(x)).append(' ').append(n(bottomY))
           .append("Z\" fill=\"url(#nks").append(uid).append(")\"/>");

        // The turn into shadow along the trailing sidewall. Every finger is lit from the same side, which
        // is what stops five of them reading as five unrelated objects.
        svg.append("<path d=\"M").append(n(x + w * 0.80)).append(' ').append(n(tipY + r * 0.6))
           .append("L").append(n(x + w)).append(' ').append(n(tipY + r))
           .append("L").append(n(x + w)).append(' ').append(n(bottomY))
           .append("L").append(n(x + w * 0.80)).append(' ').append(n(bottomY))
           .append("Z\" fill=\"").append(SKIN_SHADE).append("\" fill-opacity=\"0.34\"/>");

        // The cuticle shadow: a shallow arc hugging the base of the plate. This is the attachment cue.
        double dip = cuticleDip(p.w());
        svg.append("<path d=\"M").append(n(cx - p.w() * 0.52)).append(' ').append(n(cuticleY - 1))
           .append("Q").append(n(cx)).append(' ').append(n(cuticleY + dip + 5.5))
           .append(' ').append(n(cx + p.w() * 0.52)).append(' ').append(n(cuticleY - 1))
           .append("Q").append(n(cx)).append(' ').append(n(cuticleY + dip + 1.5))
           .append(' ').append(n(cx - p.w() * 0.52)).append(' ').append(n(cuticleY - 1))
           .append("Z\" fill=\"").append(SKIN_SHADE).append("\" fill-opacity=\"0.55\"/>");

        // A free edge that overhangs casts a shadow. Only drawn when there is something to overhang.
        if (bedFraction < 0.86) {
            svg.append("<ellipse cx=\"").append(n(cx)).append("\" cy=\"").append(n(tipY + 3.5))
               .append("\" rx=\"").append(n(p.w() * 0.46)).append("\" ry=\"").append(n(5.5))
               .append("\" fill=\"").append(INK).append("\" fill-opacity=\"0.13\"/>");
        }

        svg.append("</g>");
    }

    private void appendPlate(StringBuilder svg, Plate p, NailDesignSpecDto design, String uid, int index,
                             String fill, String edge, double edgeAlpha,
                             Set<NailDesignSpecDto.Effect> effects, String accentColor) {
        String d = platePath(p, design.shape());
        String clip = "url(#nk" + uid + '-' + index + ")";

        svg.append("<g transform=\"rotate(").append(n(p.tilt())).append(' ')
           .append(n(p.centerX())).append(' ').append(n(p.cuticleY())).append(")\">");

        // A soft contact shadow, so the plate sits on the page rather than floating over it.
        svg.append("<path d=\"").append(d).append("\" transform=\"translate(0 2.4)\" fill=\"").append(INK)
           .append("\" fill-opacity=\"0.06\"/>");
        svg.append("<path d=\"").append(d).append("\" fill=\"").append(fill).append("\"/>");

        svg.append("<g clip-path=\"").append(clip).append("\">");
        appendFinish(svg, design.finish(), p, uid);
        appendEffects(svg, effects, p, accentColor, uid);
        if (p.accent()) appendAccentBand(svg, p, accentColor);
        svg.append("</g>");

        svg.append("<path d=\"").append(d).append("\" fill=\"none\" stroke=\"").append(edge)
           .append("\" stroke-opacity=\"").append(n2(edgeAlpha)).append("\" stroke-width=\"1.4\"/>");
        svg.append("</g>");

        // The accent marker. It names nothing, so it needs no type: it says "this nail, in this colour", and
        // the legend under the picture says which finger that is. Drawn outside the tilt, because the tilt
        // pivots on the cuticle and the marker belongs to the hand, not to the plate.
        if (p.accent()) {
            double mx = p.centerX();
            double my = p.cuticleY() + cuticleDip(p.w()) + 13;
            svg.append("<path d=\"M").append(n(mx)).append(' ').append(n(my - 6))
               .append("L").append(n(mx + 5.2)).append(' ').append(n(my))
               .append("L").append(n(mx)).append(' ').append(n(my + 6))
               .append("L").append(n(mx - 5.2)).append(' ').append(n(my))
               .append("Z\" fill=\"").append(accentColor).append("\"/>");
        }
    }

    /** Positions the five plates of one hand for this design's length. */
    private List<Plate> layOutHand(NailDesignSpecDto design, NailDesignResolver.Hand hand) {
        Set<NailDesignSpecDto.Finger> accents = resolver.resolve(design).stream()
                .filter(placement -> placement.hand() == hand && placement.accent())
                .map(NailDesignResolver.NailPlacement::finger)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        double base = switch (design.length()) {
            case SHORT -> 78;
            case MEDIUM -> 98;
            case LONG -> 122;
            case EXTRA_LONG -> 146;
        };

        List<Plate> plates = new ArrayList<>(5);
        double x = 0;
        for (int i = 0; i < ORDER.length; i++) {
            x += LEAD_GAP[i];
            double w = PLATE_W[i];
            double h = base * LENGTH_FACTOR[i];
            plates.add(new Plate(x, CUTICLE_Y[i] - h, w, h, TILT_DEG[i], accents.contains(ORDER[i])));
            x += w;
        }
        return List.copyOf(plates);
    }

    /**
     * {minX, minY, maxX, maxY} of the drawn hand — tilt, cuticle bulge, accent markers AND the fingertips.
     *
     * <p>The fingers have to be in here. They are wider than their plates and reach a long way below the
     * cuticle, so a box measured from the plates alone would fit the nails to the frame and then let the
     * skin run off the bottom and sides — which is exactly what happened the first time.</p>
     */
    private double[] boundingBox(List<Plate> plates, boolean anyAccent) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Plate p : plates) {
            double rad = Math.toRadians(p.tilt());
            double cos = Math.cos(rad), sin = Math.sin(rad);
            double px = p.centerX(), py = p.cuticleY();
            double bulge = cuticleDip(p.w());
            double fingerHalf = p.w() * FINGER_W_FACTOR / 2;
            // Corners of the plate box AND of the fingertip column, all rotated about the same pivot.
            double[][] corners = {
                    { p.x() - px, p.y() - py }, { p.x() + p.w() - px, p.y() - py },
                    { p.x() - px, bulge }, { p.x() + p.w() - px, bulge },
                    { -fingerHalf, FINGER_LEN }, { fingerHalf, FINGER_LEN },
            };
            for (double[] c : corners) {
                double ox = c[0], oy = c[1];
                minX = Math.min(minX, px + ox * cos - oy * sin);
                maxX = Math.max(maxX, px + ox * cos - oy * sin);
                minY = Math.min(minY, py + ox * sin + oy * cos);
                maxY = Math.max(maxY, py + ox * sin + oy * cos);
            }
            if (p.accent()) maxY = Math.max(maxY, py + bulge + 24);
        }
        // The fade takes the last stretch of finger to nothing, so the box does not need to reserve room
        // for ink that is no longer there — trimming it back is what keeps the nails large in the frame.
        maxY -= FINGER_LEN * 0.30;
        return new double[] { minX - 3, minY - 4, maxX + 3, maxY + 4 };
    }

    /**
     * The nail plate outline.
     *
     * <p>Every shape shares one cuticle — a shallow arc bulging away from the tip, which is what makes the
     * form read as a nail sitting in a nail bed. What differs, and what a user is checking when she looks at
     * this, is the free edge: how wide it is, whether it is flat, round or pointed, and how the sidewalls
     * get there.</p>
     */
    private String platePath(Plate p, NailDesignSpecDto.Shape shape) {
        double x = p.x(), y = p.y(), w = p.w(), h = p.h();
        double cx = x + w / 2;
        double cr = w * CUTICLE_R;
        double cy = y + h;

        StringBuilder d = new StringBuilder(240);
        d.append("M").append(n(x)).append(' ').append(n(cy - cr))
         .append("Q").append(n(cx)).append(' ').append(n(cy + w * CUTICLE_ARC))
         .append(' ').append(n(x + w)).append(' ').append(n(cy - cr));

        switch (shape) {
            // Flat free edge. The corner radius is the whole difference between these two, and it is the
            // difference a technician files.
            case SQUARE -> flatEdge(d, p, 0.97, w * 0.06);
            case SQUOVAL -> flatEdge(d, p, 0.94, w * 0.26);
            // Ballerina: straight taper to a narrow flat edge, corners left sharp.
            case COFFIN -> {
                double tip = w * 0.24;
                d.append("L").append(n(x + w)).append(' ').append(n(y + h * 0.62))
                 .append("L").append(n(cx + tip)).append(' ').append(n(y))
                 .append("L").append(n(cx - tip)).append(' ').append(n(y))
                 .append("L").append(n(x)).append(' ').append(n(y + h * 0.62));
            }
            // Full-width semicircular edge.
            case ROUND -> {
                double r = w / 2, k = w * 0.276;
                d.append("L").append(n(x + w)).append(' ').append(n(y + r))
                 .append("C").append(n(x + w)).append(' ').append(n(y + r - k))
                 .append(' ').append(n(cx + k)).append(' ').append(n(y))
                 .append(' ').append(n(cx)).append(' ').append(n(y))
                 .append("C").append(n(cx - k)).append(' ').append(n(y))
                 .append(' ').append(n(x)).append(' ').append(n(y + r - k))
                 .append(' ').append(n(x)).append(' ').append(n(y + r))
                 .append("L").append(n(x)).append(' ').append(n(cy - cr));
            }
            // Broad rounded edge on a gently tapered wall — an egg, not a point.
            case OVAL -> taperedEdge(d, p, 0.29, 0.055, 0.56, false);
            // Narrow soft point on a strongly tapered wall. This is the shape the old renderer drew as a
            // square, and the reason this method exists.
            case ALMOND -> taperedEdge(d, p, 0.13, 0.035, 0.66, true);
            // A needle.
            case STILETTO -> {
                d.append("C").append(n(x + w)).append(' ').append(n(y + h * 0.55))
                 .append(' ').append(n(cx + w * 0.17)).append(' ').append(n(y + h * 0.10))
                 .append(' ').append(n(cx)).append(' ').append(n(y))
                 .append("C").append(n(cx - w * 0.17)).append(' ').append(n(y + h * 0.10))
                 .append(' ').append(n(x)).append(' ').append(n(y + h * 0.55))
                 .append(' ').append(n(x)).append(' ').append(n(cy - cr));
            }
        }
        return d.append("Z").toString();
    }

    /** Straight sidewalls to a flat free edge, with a per-shape corner radius. */
    private void flatEdge(StringBuilder d, Plate p, double tipFraction, double corner) {
        double x = p.x(), y = p.y(), w = p.w(), h = p.h();
        double cx = x + w / 2;
        double tip = w * tipFraction / 2;
        double shoulder = y + h * 0.55;
        d.append("L").append(n(x + w)).append(' ').append(n(shoulder))
         .append("L").append(n(cx + tip)).append(' ').append(n(y + corner))
         .append("Q").append(n(cx + tip)).append(' ').append(n(y))
         .append(' ').append(n(cx + tip - corner)).append(' ').append(n(y))
         .append("L").append(n(cx - tip + corner)).append(' ').append(n(y))
         .append("Q").append(n(cx - tip)).append(' ').append(n(y))
         .append(' ').append(n(cx - tip)).append(' ').append(n(y + corner))
         .append("L").append(n(x)).append(' ').append(n(shoulder));
    }

    /**
     * Tapered sidewalls to a narrow free edge.
     *
     * @param tipHalf half the free-edge width, as a fraction of plate width — the almond/oval difference
     * @param tipDrop how far below the frame's top the edge corners sit, as a fraction of height
     * @param belly   how full the sidewall stays before it turns in (0 = straight to the tip, 1 = square)
     * @param pointed true draws a soft point, false a broad rounded edge
     */
    private void taperedEdge(StringBuilder d, Plate p, double tipHalf, double tipDrop, double belly,
                             boolean pointed) {
        double x = p.x(), y = p.y(), w = p.w(), h = p.h();
        double cx = x + w / 2;
        double t = w * tipHalf;
        double edgeY = y + h * tipDrop;
        // Where the wall is still carrying width on its way in — interpolated from the tip out to the
        // sidewall, so a wide free edge (oval) cannot bulge past the plate.
        double shoulderX = t + (w / 2 - t) * belly;

        d.append("C").append(n(x + w)).append(' ').append(n(y + h * 0.50))
         .append(' ').append(n(cx + shoulderX)).append(' ').append(n(y + h * 0.16))
         .append(' ').append(n(cx + t)).append(' ').append(n(edgeY));
        if (pointed) {
            d.append("Q").append(n(cx)).append(' ').append(n(y - h * 0.012))
             .append(' ').append(n(cx - t)).append(' ').append(n(edgeY));
        } else {
            d.append("C").append(n(cx + t * 0.62)).append(' ').append(n(y))
             .append(' ').append(n(cx - t * 0.62)).append(' ').append(n(y))
             .append(' ').append(n(cx - t)).append(' ').append(n(edgeY));
        }
        d.append("C").append(n(cx - shoulderX)).append(' ').append(n(y + h * 0.16))
         .append(' ').append(n(x)).append(' ').append(n(y + h * 0.50))
         .append(' ').append(n(x)).append(' ').append(n(y + h - w * CUTICLE_R));
    }

    /**
     * The finish, as light on a curved surface — one gradient across the plate, because a nail is a cylinder
     * and hard-edged highlight bands read as painted stripes rather than as shine.
     */
    private void appendFinishGradient(StringBuilder svg, String uid, NailDesignSpecDto.Finish finish) {
        svg.append("<linearGradient id=\"nkf").append(uid)
           .append("\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\">");
        switch (finish) {
            // Two crisp highlights and a dark turn at the far edge: wet lacquer.
            case GLOSSY -> stops(svg,
                    new double[] { 0, 0.09, 0.20, 0.38, 0.52, 0.84, 1 },
                    new String[] { "#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF", INK, INK },
                    new double[] { 0.10, 0.52, 0.10, 0.26, 0.02, 0.06, 0.20 });
            // One broad, soft highlight.
            case SATIN -> stops(svg,
                    new double[] { 0, 0.28, 0.60, 1 },
                    new String[] { "#FFFFFF", "#FFFFFF", "#FFFFFF", INK },
                    new double[] { 0.10, 0.22, 0.03, 0.14 });
            // Matte scatters light instead of reflecting it: no highlight, just enough turn at the edge to
            // keep the plate from reading as a flat sticker.
            case MATTE -> stops(svg,
                    new double[] { 0, 0.45, 1 },
                    new String[] { "#FFFFFF", "#FFFFFF", INK },
                    new double[] { 0.07, 0.03, 0.13 });
            // Satin, with the speckle drawn on top.
            case SHIMMER -> stops(svg,
                    new double[] { 0, 0.30, 0.62, 1 },
                    new String[] { "#FFFFFF", "#FFFFFF", "#FFFFFF", INK },
                    new double[] { 0.09, 0.17, 0.03, 0.14 });
        }
        svg.append("</linearGradient>");
    }

    private void stops(StringBuilder svg, double[] offsets, String[] colors, double[] alphas) {
        for (int i = 0; i < offsets.length; i++) {
            svg.append("<stop offset=\"").append(n2(offsets[i])).append("\" stop-color=\"").append(colors[i])
               .append("\" stop-opacity=\"").append(n2(alphas[i])).append("\"/>");
        }
    }

    private void appendFinish(StringBuilder svg, NailDesignSpecDto.Finish finish, Plate p, String uid) {
        double x = p.x(), y = p.y(), w = p.w(), h = p.h();
        svg.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(y - h * 0.05))
           .append("\" width=\"").append(n(w)).append("\" height=\"").append(n(h * 1.15))
           .append("\" fill=\"url(#nkf").append(uid).append(")\"/>");
        // No discrete specular blob: at preview scale a bright ellipse on the plate reads as a painted
        // highlight on a tin can. The gradient carries the shine on its own.
        if (finish == NailDesignSpecDto.Finish.SHIMMER) {
            for (int i = 0; i < SPARKS.length; i++) {
                svg.append("<circle cx=\"").append(n(x + w * SPARKS[i][0]))
                   .append("\" cy=\"").append(n(y + h * SPARKS[i][1]))
                   .append("\" r=\"").append(n(w * (i % 3 == 0 ? 0.026 : 0.017)))
                   .append("\" fill=\"#FFFFFF\" fill-opacity=\"0.7\"/>");
            }
        }
    }

    /**
     * A magnetic cat-eye, as a gradient down the plate: dark where the pigment thinned out at both ends, one
     * soft bright band where the magnet gathered it. Soft stops on both sides of the band are the whole
     * point — a cat-eye has no hard edge, and drawing one makes it a painted stripe instead of a reflection.
     */
    private void appendCatEyeGradient(StringBuilder svg, String uid) {
        svg.append("<linearGradient id=\"nkc").append(uid)
           .append("\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">");
        stops(svg,
                new double[] { 0, 0.20, 0.36, 0.46, 0.52, 0.58, 0.72, 1 },
                new String[] { INK, INK, "#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF", INK, INK },
                new double[] { 0.34, 0.16, 0.10, 0.42, 0.46, 0.30, 0.16, 0.32 });
        svg.append("</linearGradient>");
    }

    /** Effects, always in enum order so the output really is byte-identical for a given spec. */
    private void appendEffects(StringBuilder svg, Set<NailDesignSpecDto.Effect> active, Plate p,
                               String accentColor, String uid) {
        double x = p.x(), y = p.y(), w = p.w(), h = p.h();
        double cx = x + w / 2;
        for (NailDesignSpecDto.Effect effect : NailDesignSpecDto.Effect.values()) {
            if (!active.contains(effect)) continue;
            switch (effect) {
                // A magnetic cat-eye is a REFLECTION, not a drawn line: the pigment gathers where the magnet
                // pulled it, so the plate darkens toward both ends and one soft band of light sits across
                // the middle with no edge you can point at. The version this replaces stroked a hard white
                // curve, which read as a French line in the wrong place.
                case CAT_EYE -> svg.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(y))
                        .append("\" width=\"").append(n(w)).append("\" height=\"").append(n(h))
                        .append("\" fill=\"url(#nkc").append(uid).append(")\"/>");
                // A mirror: a hard horizon with a bright line sitting on it.
                case CHROME -> {
                    box(svg, x, y, w, h * 0.44, "#FFFFFF", 0.34);
                    box(svg, x, y + h * 0.44, w, h * 0.06, "#FFFFFF", 0.7);
                    box(svg, x, y + h * 0.50, w, h * 0.50, INK, 0.18);
                }
                // The smile line: an ellipse whose lower arc is thickest at the centre and thins toward the
                // sidewalls, which is how the free edge is actually painted.
                case FRENCH -> svg.append("<ellipse cx=\"").append(n(cx))
                        .append("\" cy=\"").append(n(y - h * 0.27))
                        .append("\" rx=\"").append(n(w * 0.86)).append("\" ry=\"").append(n(h * 0.43))
                        .append("\" fill=\"#FFFFFF\" fill-opacity=\"0.94\" stroke=\"").append(INK)
                        .append("\" stroke-opacity=\"0.16\" stroke-width=\"1.2\"/>");
                case GLITTER_ACCENT -> {
                    for (int i = 0; i < SPARKS.length; i++) {
                        svg.append("<circle cx=\"").append(n(x + w * SPARKS[i][1]))
                           .append("\" cy=\"").append(n(y + h * SPARKS[i][0]))
                           .append("\" r=\"").append(n(w * (i % 4 == 0 ? 0.044 : 0.027)))
                           .append("\" fill=\"").append(i % 3 == 0 ? accentColor : "#FFFFFF")
                           .append("\" fill-opacity=\"0.88\"/>");
                    }
                }
                case NONE -> { }
            }
        }
    }

    /**
     * The accent, as a band along the free edge in the accent's own colour. A two-colour request has to read
     * as two colours, and at the size this renders on a phone a hairline crescent did not.
     */
    private void appendAccentBand(StringBuilder svg, Plate p, String accentColor) {
        svg.append("<ellipse cx=\"").append(n(p.centerX()))
           .append("\" cy=\"").append(n(p.y() - p.h() * 0.32))
           .append("\" rx=\"").append(n(p.w() * 0.84)).append("\" ry=\"").append(n(p.h() * 0.45))
           .append("\" fill=\"").append(accentColor).append("\"/>");
    }

    private void box(StringBuilder svg, double x, double y, double w, double h, String color, double alpha) {
        svg.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(y))
           .append("\" width=\"").append(n(w)).append("\" height=\"").append(n(h))
           .append("\" fill=\"").append(color).append("\" fill-opacity=\"").append(n2(alpha)).append("\"/>");
    }

    /** Black is a real nail colour, so the outline has to survive it: light rim on dark lacquer, ink on light. */
    private String edgeColor(String fill) {
        return isDark(fill) ? "#FFFFFF" : INK;
    }

    private boolean isDark(String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') return false;
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return (0.299 * r + 0.587 * g + 0.114 * b) < 96;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * A stable id suffix for this spec's SVG elements. Uses enum and string names rather than
     * {@code hashCode()}, because enum hash codes are identity-based and would differ between JVM runs —
     * which would quietly break the byte-identical guarantee this class is built on.
     */
    private String fingerprint(NailDesignSpecDto design) {
        String key = design.shape() + "|" + design.length() + "|" + design.baseColorHex() + "|"
                + design.finish() + "|" + design.effects() + "|" + design.accentFingers() + "|"
                + design.accentColorHex() + "|" + design.symmetry();
        int hash = 0x811c9dc5;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x01000193;
        }
        return Integer.toHexString(hash & 0xffffff);
    }

    private String ariaLabel(NailDesignSpecDto design) {
        String color = design.baseColorRawText().isBlank() ? design.baseColorKey() : design.baseColorRawText();
        StringBuilder label = new StringBuilder("Shematski dijagram dizajna noktiju, desna ruka: ")
                .append(design.shape().croatianLabel()).append(", ")
                .append(design.length().croatianLabel()).append(", ")
                .append(color.isBlank() ? "boja nije navedena" : color).append(", ")
                .append(design.finish().croatianLabel()).append(" završni sloj");
        if (!design.activeEffects().isEmpty()) {
            label.append(", efekt ").append(String.join(" i ", design.activeEffects().stream()
                    .map(NailDesignSpecDto.Effect::croatianLabel).sorted().toList()));
        }
        if (design.hasAccent()) {
            label.append(", detalj na ").append(String.join(" i ", design.accentFingers().stream()
                    .map(NailDesignSpecDto.Finger::croatianLocative).toList()));
        }
        return label.append('.').toString();
    }

    /** One decimal, trailing zero dropped — the SVG stays readable and diffable. */
    private static String n(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.1f", r);
    }

    private static String n2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** The fit scale needs more precision than geometry does, or the hand drifts inside the frame. */
    private static String n4(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
