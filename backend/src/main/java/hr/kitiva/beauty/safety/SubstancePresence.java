package hr.kitiva.beauty.safety;

/**
 * Phase C — whether a restricted substance is present in a product. <strong>Tri-state on purpose.</strong>
 *
 * <p>This is the single most important type in the safety subsystem, and the reason it is not a boolean.
 * A boolean forces every unknown into {@code false}, and {@code false} reads as "does not contain". Retailer
 * data is routinely incomplete — a page with no ingredient list is the normal case for HR beauty sources, not
 * the exception (see docs/beauty-sourcing-policy.md: only one of fifteen probed sources exposed ingredient
 * text at all). Encoding "we did not find HEMA" as "this product is HEMA-free" would turn missing data into a
 * safety claim about someone's skin.</p>
 *
 * <p>The rule, stated once here and enforced by {@link ConsumerNailSafetyPolicy}:
 * <strong>only {@link #VERIFIED_ABSENT} may enter a consumer at-home kit.</strong> {@link #UNKNOWN} is
 * treated exactly as unsafely as {@link #VERIFIED_PRESENT} — not because it is equally dangerous, but
 * because we cannot tell the difference, and the user cannot either.</p>
 */
public enum SubstancePresence {

    /**
     * A full, untruncated ingredient list was captured from an authoritative source inside the freshness
     * window, and the substance is not in it. This is the ONLY value that permits a consumer kit.
     */
    VERIFIED_ABSENT("provjereno — ne sadrži"),

    /** The substance is listed. Blocks the product from any consumer at-home kit. */
    VERIFIED_PRESENT("sadrži"),

    /**
     * No ingredient list, a truncated one, an unverifiable source, or evidence older than the freshness
     * window. Blocks — absence of evidence is not evidence of absence.
     */
    UNKNOWN("nepoznato");

    private final String croatianLabel;

    SubstancePresence(String croatianLabel) {
        this.croatianLabel = croatianLabel;
    }

    public String croatianLabel() {
        return croatianLabel;
    }

    /** True only for {@link #VERIFIED_ABSENT}. The one predicate the consumer gate is allowed to trust. */
    public boolean permitsConsumerUse() {
        return this == VERIFIED_ABSENT;
    }

    /**
     * Parses a stored value, defaulting to {@link #UNKNOWN} for null, blank or unrecognised input.
     *
     * <p>Failing to {@code UNKNOWN} rather than throwing is deliberate: a corrupt or newly-added column
     * value must degrade to "block it" rather than crash the kit path, and must never fall through to
     * "allow it". Every unparseable input is therefore an unsafe input.</p>
     */
    public static SubstancePresence parse(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        try {
            return valueOf(raw.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
