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

    /** Ordered so the most specific claim wins when a title mentions several things. */
    private static final Map<Capability, Pattern> CLAIMS = new LinkedHashMap<>();
    static {
        CLAIMS.put(Capability.CAT_EYE, Pattern.compile("cat\\s*-?\\s*eye|macje\\s*oko"));
        CLAIMS.put(Capability.MAGNET_TOOL, Pattern.compile("magnet"));
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
        List<Proof> proofs = new ArrayList<>();
        for (Map.Entry<Capability, Pattern> e : CLAIMS.entrySet()) {
            Matcher m = e.getValue().matcher(title);
            if (m.find()) proofs.add(new Proof(e.getKey(), m.group().trim()));
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
