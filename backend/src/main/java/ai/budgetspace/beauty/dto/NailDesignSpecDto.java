package ai.budgetspace.beauty.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Set;

/**
 * Phase B — the structured nail design. <strong>This is the keystone of the nail experience.</strong>
 *
 * <p>One rule governs it: this object is the SINGLE source for all three outputs — the Design Diagram, the
 * salon nail-tech brief, and the at-home material kit. None of the three may be generated from free text,
 * and none may introduce an element that is not in here. That is what stops the picture promising an effect
 * the kit cannot buy.</p>
 *
 * <p>Every field therefore has to pass a three-way test: can it be <em>drawn</em>, can it be <em>said</em>
 * to a nail technician, and can it be <em>mapped to a product capability</em>? A field that fails any one
 * of the three does not belong here.</p>
 *
 * <p><strong>Scope.</strong> The full L1–R5 per-nail addressing scheme is deliberately out of MVP. What
 * survives is what a real request actually needs: a uniform design, named accent fingers, an accent count,
 * and symmetry stated explicitly. {@code symmetry} has no "unknown" value on purpose — asymmetry must be a
 * stated choice, never something inferred from silence, because a nail tech reading the brief needs to know
 * whether the hands differ deliberately.</p>
 *
 * <p>Resolution of this spec into ten per-nail placements lives in {@code NailDesignResolver} (Phase F),
 * which is its sole owner. This record defines and validates the shape; it does not resolve it.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NailDesignSpecDto(
        Shape shape,
        Length length,
        // Colour is three fields because the three outputs need different things: the diagram needs a hex,
        // the brief needs a name a technician recognises, and the user needs to see her own words echoed
        // back so she can tell we understood ("boja višnje" -> burgundy).
        String baseColorKey,
        String baseColorHex,
        String baseColorRawText,
        Finish finish,
        List<Effect> effects,
        // Accents: named fingers rather than indices, because "prstenjak" is what she says and what the
        // technician hears. Empty = a uniform design with no accent nail.
        List<Finger> accentFingers,
        String accentDescription,
        Symmetry symmetry,
        List<String> styleWords,
        List<Assumption> assumptions
) {

    /** Nail shapes we can draw, name and file to. */
    public enum Shape {
        ALMOND("almond"), OVAL("ovalni"), SQUARE("četvrtasti"), SQUOVAL("četvrtasto-ovalni"),
        ROUND("okrugli"), COFFIN("coffin"), STILETTO("stiletto");

        private final String hr;
        Shape(String hr) { this.hr = hr; }
        public String croatianLabel() { return hr; }
    }

    /**
     * Length bands, not millimetres. A band is what a user can state and a technician can execute;
     * a millimetre figure would be false precision. {@link #feasibleOnNaturalNail()} is what the at-home
     * branch uses to decide whether a look needs extensions — and therefore whether it must be downgraded
     * to press-ons rather than sold a builder gel we do not recommend to consumers.
     */
    public enum Length {
        SHORT("kratki", true), MEDIUM("srednji", true), LONG("dugi", false), EXTRA_LONG("vrlo dugi", false);

        private final String hr;
        private final boolean naturalNail;
        Length(String hr, boolean naturalNail) { this.hr = hr; this.naturalNail = naturalNail; }
        public String croatianLabel() { return hr; }
        /** False when this length generally requires an extension rather than the natural nail. */
        public boolean feasibleOnNaturalNail() { return naturalNail; }
    }

    /** Top-coat finish. Maps directly to a product capability. */
    public enum Finish {
        GLOSSY("sjajni"), MATTE("mat"), SATIN("saten"), SHIMMER("šimeran");

        private final String hr;
        Finish(String hr) { this.hr = hr; }
        public String croatianLabel() { return hr; }
    }

    /**
     * Visual effects, restricted to what the MVP consumer systems can actually achieve. Each one has a
     * material mapping in the kit graph (Phase C) — an effect with no mapping cannot be offered, which is
     * exactly why this is a closed vocabulary and not free text.
     */
    public enum Effect {
        NONE("bez efekta"), CAT_EYE("cat-eye"), CHROME("chrome"), FRENCH("french"), GLITTER_ACCENT("glitter detalj");

        private final String hr;
        Effect(String hr) { this.hr = hr; }
        public String croatianLabel() { return hr; }
    }

    /** Named fingers — the vocabulary the user and the technician both use. */
    public enum Finger {
        THUMB("palac"), INDEX("kažiprst"), MIDDLE("srednjak"), RING("prstenjak"), PINKY("mali prst");

        private final String hr;
        Finger(String hr) { this.hr = hr; }
        public String croatianLabel() { return hr; }
    }

    /** Whether both hands match. No "unknown" member — see the class note. */
    public enum Symmetry {
        /** Both hands identical; accent fingers apply to the same finger on each hand. */
        MIRRORED("zrcalno na obje ruke"),
        /** The hands deliberately differ, and the difference is described in {@code accentDescription}. */
        ASYMMETRIC_STATED("namjerno različito na rukama");

        private final String hr;
        Symmetry(String hr) { this.hr = hr; }
        public String croatianLabel() { return hr; }
    }

    public NailDesignSpecDto {
        effects = effects == null ? List.of() : List.copyOf(effects);
        accentFingers = accentFingers == null ? List.of() : List.copyOf(accentFingers);
        styleWords = styleWords == null ? List.of() : List.copyOf(styleWords);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        baseColorKey = baseColorKey == null ? "" : baseColorKey.trim().toLowerCase();
        baseColorHex = baseColorHex == null || baseColorHex.isBlank() ? null : baseColorHex.trim();
        baseColorRawText = baseColorRawText == null ? "" : baseColorRawText.trim();
        accentDescription = accentDescription == null ? "" : accentDescription.trim();
        // Defaults chosen so a half-parsed prompt still yields a drawable, sayable, buyable spec rather
        // than a null-riddled object every consumer has to defend against.
        shape = shape == null ? Shape.ALMOND : shape;
        length = length == null ? Length.SHORT : length;
        finish = finish == null ? Finish.GLOSSY : finish;
        symmetry = symmetry == null ? Symmetry.MIRRORED : symmetry;
    }

    /** Accent nails per hand. Zero for a uniform design. */
    public int accentCount() {
        return accentFingers.size();
    }

    /** True when an accent was requested at all. */
    public boolean hasAccent() {
        return !accentFingers.isEmpty();
    }

    /** Effects with a real visual consequence ({@link Effect#NONE} filtered out). */
    public Set<Effect> activeEffects() {
        return effects.stream().filter(e -> e != Effect.NONE).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * True when this design generally needs an extension. The at-home branch uses it to route a request
     * toward press-ons instead of a system we will not recommend to a consumer.
     */
    public boolean requiresExtension() {
        return !length.feasibleOnNaturalNail();
    }
}
