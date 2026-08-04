package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.NailDesignSpecDto;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a catalog product can be PROVEN to do, and the retailer text that proves it.
 *
 * <p><strong>Why this exists.</strong> The kit assembler used to fill a "colour" slot with any polish and a
 * "colour" slot alone was treated as satisfying a burgundy cat-eye request. That is fabricated completeness:
 * a numbered lacquer is not burgundy, and no amount of glossy top coat is a cat-eye. The failure is
 * seductive because the kit looks full — every category has a product in it — while the thing the user
 * actually asked for is absent.</p>
 *
 * <p><strong>The rule.</strong> A capability counts only when the RETAILER'S OWN published product title
 * says so. "Nail Addict Colored umjetni nokti – Bordeaux" is evidence of a burgundy-family colour, because
 * the retailer named it. "Ice Chic Nail Lacquer — ICE 2" is evidence of nothing: a shade number is not a
 * colour. Nothing here infers, and nothing here guesses from a product code.</p>
 *
 * <p>Evidence is a substring of the title, retained so any claim can be traced back to the exact words that
 * justified it.</p>
 */
public final class NailCapabilityEvidence {

    private NailCapabilityEvidence() { }

    /** A capability a design can require and a product can prove. */
    public enum Capability {
        CAT_EYE("cat-eye efekt"),
        CHROME("chrome efekt"),
        FRENCH("french"),
        GLITTER("glitter"),
        MAGNET_TOOL("magnet za cat-eye"),
        GOLD_DETAIL("zlatni detalj"),
        SHAPE_ALMOND("almond oblik"),
        SHAPE_COFFIN("coffin/balerina oblik"),
        SHAPE_SQUARE("četvrtasti oblik"),
        COLOR_BURGUNDY("burgundy boja"),
        COLOR_RED("crvena boja"),
        COLOR_NUDE("nude boja"),
        COLOR_PINK("roza boja"),
        COLOR_BLACK("crna boja"),
        COLOR_WHITE("bijela boja"),
        COLOR_BROWN("smeđa boja");

        private final String hr;
        Capability(String hr) { this.hr = hr; }
        public String croatianLabel() { return hr; }
    }

    /**
     * Words that make a title about NAILS. A capability regex on its own is not enough: a sourcing probe of
     * dm.hr returned <em>"Poklon-paket Magnetic Man"</em> — a men's gift set — for the magnet pattern, and
     * on that evidence a kit would have reported itself able to do a cat-eye. A product only proves a nail
     * capability if the retailer's own words say it is a nail product.
     */
    private static final Pattern NAIL_CONTEXT = Pattern.compile(
            "nokt|nail|lak\\b|lakov|manikur|manicure|zanoktic|cuticle|press[- ]?on|turpij|rasp");

    /**
     * Capabilities that are only ever claimed by a nail product, so a bare match would be a false positive.
     * Colour words are the obvious case in the other direction — "crvena" alone proves nothing anywhere —
     * but those are already anchored by the slot the product is being considered for.
     */
    private static final java.util.Set<Capability> NEEDS_NAIL_CONTEXT = java.util.EnumSet.of(
            Capability.MAGNET_TOOL, Capability.CHROME, Capability.FRENCH, Capability.GLITTER,
            Capability.GOLD_DETAIL, Capability.CAT_EYE);

    /**
     * Tools, by the retailer's own noun. A 2026-08-03 sweep of cicinails.hr and beauty-shop.hr returned
     * <em>"ZLATNA KLIJEŠTA ZA UMJETNE NOKTE"</em> and <em>"Kist za nail art Aquarelle Gold"</em> for the
     * gold pattern: both are genuinely about nails and genuinely say gold, and neither puts a speck of
     * gold on a nail. Gold-coloured pliers are the same failure as the men's gift box one category up.
     *
     * <p>A tool can still prove {@link Capability#MAGNET_TOOL} — that capability <em>is</em> a tool. What
     * it cannot prove is a LOOK.</p>
     */
    private static final Pattern IS_TOOL = Pattern.compile(
            "klijest|skaric|\\bkist\\b|kistov|brusilic|stalak|pincet|nastavak|rucki|sablon|posudic|frez"
            + "|makaz|pogurivac|turpij|\\brasp\\w*\\b|\\blamp\\w*\\b|\\bpliers\\b|\\bbrush\\b|\\bfile\\b");

    /** Capabilities that describe how the NAIL looks, so a tool can never prove one. */
    private static final java.util.Set<Capability> DECORATIVE = java.util.EnumSet.of(
            Capability.CAT_EYE, Capability.CHROME, Capability.FRENCH, Capability.GLITTER,
            Capability.GOLD_DETAIL, Capability.COLOR_BURGUNDY, Capability.COLOR_RED, Capability.COLOR_NUDE,
            Capability.COLOR_PINK, Capability.COLOR_BLACK, Capability.COLOR_WHITE, Capability.COLOR_BROWN);

    /**
     * A product that arrives with the design already on it — a press-on plate or a printed sticker. It
     * needs no technique to reproduce an effect, because the effect is manufactured into it.
     */
    private static final Pattern PRE_MADE = Pattern.compile(
            "umjetni\\s*nokt|press[- ]?on|samoljepljiv|naljepnic|\\bsticker|\\btips?\\b|umjetne\\s*nokt");

    /**
     * A published application step that actually names the magnet being moved over the coat. This is the
     * ONLY thing that proves a brush-on product can make a cat-eye: the effect is iron particles dragged
     * into a line by a magnet, so a polish whose instructions never mention one cannot make it.
     */
    private static final Pattern MAGNET_STEP = Pattern.compile(
            "magnet\\w*\\s+(pribliz|priblez|drz|prov|povuc|postav|prislon|iznad|preko|uz\\s*nokt)"
            + "|(pribliz|drz|povuc|postav|prislon|prijedi|prijedite)\\w*\\s+(jak\\w*\\s+)?magnet"
            + "|(use|hold|move|drag|place)\\s+(the\\s+)?magnet"
            + "|magnet\\w*\\s+(se\\s+)?(priblizava|drzi|povlaci)");

    /** Ordered so the most specific claim wins when a title mentions several things. */
    private static final Map<Capability, Pattern> CLAIMS = new LinkedHashMap<>();
    static {
        CLAIMS.put(Capability.CAT_EYE, Pattern.compile("cat\\s*-?\\s*eye|macje\\s*oko"));
        // "magnetic" alone is a marketing word on everything from mascara to a gift box, and "magnetni"
        // on a POLISH describes the paint, not a tool. So the noun has to stand on its own — `magnet\b`
        // does not match "magnetni" or "magnetic" — and it has to be a magnet FOR something naily.
        CLAIMS.put(Capability.MAGNET_TOOL, Pattern.compile(
                "magnet\\b\\s*(za|for)\\s*(nokt|nail|cat\\s*-?\\s*eye|macje)"
                + "|(nokt|nail)\\w*\\s+magnet\\b"
                + "|cat\\s*-?\\s*eye\\s*magnet\\b"
                + "|magnetic\\s*(stick|pen|tool|plate)"));
        CLAIMS.put(Capability.CHROME, Pattern.compile("\\bchrome\\b|\\bkrom\\b|aurora"));
        CLAIMS.put(Capability.FRENCH, Pattern.compile("\\bfrench\\b"));
        CLAIMS.put(Capability.GLITTER, Pattern.compile("glitter|sljokic"));
        CLAIMS.put(Capability.GOLD_DETAIL, Pattern.compile("\\bgold\\b|zlatn|zlato|\\bfoil\\b|folij"));
        CLAIMS.put(Capability.SHAPE_ALMOND, Pattern.compile("badem|almond"));
        CLAIMS.put(Capability.SHAPE_COFFIN, Pattern.compile("balerin|coffin|ballerina"));
        CLAIMS.put(Capability.SHAPE_SQUARE, Pattern.compile("cetvrtast|square"));
        CLAIMS.put(Capability.COLOR_BURGUNDY, Pattern.compile("bordeaux|bordo|burgund|visnj|merlot|wine"));
        CLAIMS.put(Capability.COLOR_RED, Pattern.compile("\\bred\\b|crven"));
        CLAIMS.put(Capability.COLOR_NUDE, Pattern.compile("\\bnude\\b"));
        CLAIMS.put(Capability.COLOR_PINK, Pattern.compile("\\bpink\\b|roza|rose\\b"));
        CLAIMS.put(Capability.COLOR_BLACK, Pattern.compile("\\bblack\\b|crn"));
        CLAIMS.put(Capability.COLOR_WHITE, Pattern.compile("\\bwhite\\b|bijel|milky"));
        CLAIMS.put(Capability.COLOR_BROWN, Pattern.compile("\\bbrown\\b|smed|\\btan\\b|mocha"));
    }

    /** One proven capability plus the retailer words that prove it. */
    public record Proof(Capability capability, String evidence) { }

    /**
     * Capabilities this product proves, read from the retailer's published title (and shade name, which is
     * also retailer text). Never from a product code, a shade number, or our own inference.
     */
    public static List<Proof> proofsFor(NailPilotCatalog.PilotProduct product) {
        if (product == null) return List.of();
        String title = fold(product.name() + " " + (product.shadeName() == null ? "" : product.shadeName()));
        String instructions = fold(product.applicationEvidence() == null ? "" : product.applicationEvidence());
        boolean aboutNails = NAIL_CONTEXT.matcher(title).find();
        boolean isTool = IS_TOOL.matcher(title).find();
        boolean preMade = PRE_MADE.matcher(title).find();

        List<Proof> proofs = new ArrayList<>();
        for (Map.Entry<Capability, Pattern> e : CLAIMS.entrySet()) {
            Capability capability = e.getKey();
            if (NEEDS_NAIL_CONTEXT.contains(capability) && !aboutNails) continue;
            // A gold-coloured tool is not a gold detail, and a "French" brush is not a french manicure.
            if (isTool && DECORATIVE.contains(capability)) continue;

            Matcher m = e.getValue().matcher(title);
            if (!m.find()) continue;

            // The cat-eye gate. A pre-made plate or sticker carries the effect already, so its title is
            // enough. Anything you BRUSH ON has to be dragged into that line with a magnet, so the
            // retailer's own instructions must say a magnet is used — a name, a shade or a paragraph of
            // "captivating, magnetic shine" proves nothing about what the bottle can do.
            if (capability == Capability.CAT_EYE && !preMade && !MAGNET_STEP.matcher(instructions).find()) {
                continue;
            }

            proofs.add(new Proof(capability, m.group().trim()));
        }
        return List.copyOf(proofs);
    }

    public static boolean proves(NailPilotCatalog.PilotProduct product, Capability capability) {
        return proofsFor(product).stream().anyMatch(p -> p.capability() == capability);
    }

    /**
     * The capabilities a design REQUIRES a product to prove.
     *
     * <p>Shape is required only for press-ons: a press-on set arrives pre-shaped, so an almond request needs
     * an almond set. With polish the user files her own nail, so shape is hers to make and the catalog owes
     * nothing. Colour is required for polish and for press-ons alike — a nail has to be the colour asked for
     * whichever way it got there.</p>
     */
    public static List<Capability> requiredBy(NailDesignSpecDto design, String system) {
        if (design == null) return List.of();
        List<Capability> required = new ArrayList<>();

        for (NailDesignSpecDto.Effect effect : design.activeEffects()) {
            switch (effect) {
                case CAT_EYE -> {
                    required.add(Capability.CAT_EYE);
                    // A magnetic polish is inert without a magnet. A press-on set arrives with the effect
                    // already printed on it, so no tool is needed.
                    if (!"press-on".equalsIgnoreCase(system)) required.add(Capability.MAGNET_TOOL);
                }
                case CHROME -> required.add(Capability.CHROME);
                case FRENCH -> required.add(Capability.FRENCH);
                case GLITTER_ACCENT -> required.add(Capability.GLITTER);
                case NONE -> { }
            }
        }

        if (design.hasAccent() && "gold".equals(design.accentColorKey())) required.add(Capability.GOLD_DETAIL);

        Capability colour = colourCapability(design.baseColorKey());
        if (colour != null) required.add(colour);

        if ("press-on".equalsIgnoreCase(system)) {
            switch (design.shape()) {
                case ALMOND -> required.add(Capability.SHAPE_ALMOND);
                case COFFIN -> required.add(Capability.SHAPE_COFFIN);
                case SQUARE -> required.add(Capability.SHAPE_SQUARE);
                default -> { }
            }
        }
        return List.copyOf(required);
    }

    /**
     * The capability an ACCENT of this colour needs a product to prove. Gold is its own capability rather
     * than a colour, because the thing that makes a nail gold is a decoration — a sticker, a foil, a
     * pigment — and not a bottle of gold-coloured lacquer sitting in the base-colour slot.
     */
    public static Capability accentCapability(String accentColorKey) {
        if (accentColorKey == null || accentColorKey.isBlank()) return null;
        return "gold".equals(accentColorKey) ? Capability.GOLD_DETAIL : colourCapability(accentColorKey);
    }

    private static Capability colourCapability(String key) {
        if (key == null) return null;
        return switch (key) {
            case "burgundy" -> Capability.COLOR_BURGUNDY;
            case "red" -> Capability.COLOR_RED;
            case "nude" -> Capability.COLOR_NUDE;
            case "pink" -> Capability.COLOR_PINK;
            case "black" -> Capability.COLOR_BLACK;
            case "white" -> Capability.COLOR_WHITE;
            case "brown" -> Capability.COLOR_BROWN;
            // Colours nobody in this pilot names are deliberately NOT required: requiring a capability no
            // retailer ever publishes would make every request Incomplete for a reason the user cannot act
            // on. Those fall back to the existing "shade candidate, check the swatch" assumption.
            default -> null;
        };
    }

    private static String fold(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
