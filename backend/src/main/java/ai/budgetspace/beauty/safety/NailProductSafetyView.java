package ai.budgetspace.beauty.safety;

import java.time.LocalDate;
import java.util.Map;

/**
 * Phase C — the safety-relevant facts about one nail product, and nothing else.
 *
 * <p>A narrow view rather than the JPA {@code Product} entity, for two reasons. It keeps the gate testable
 * without a database or a 40-column fixture, and it makes the gate's inputs explicit: everything the safety
 * decision is allowed to depend on is listed right here, so nobody can quietly widen it to price, retailer
 * or popularity. A safety verdict must not be influenced by commerce.</p>
 *
 * @param externalId      catalog id, for the audit trail
 * @param nailSystem      canonical system key, e.g. {@code "gel-polish"}, {@code "press-on"}
 * @param professionalOnly true when the product is marked professional-use-only
 * @param substances      substance key → presence. A key that is ABSENT FROM THIS MAP is treated as
 *                        {@link SubstancePresence#UNKNOWN}, never as absent from the product — see
 *                        {@link #presenceOf}. That default is the whole point of the tri-state.
 * @param inciVerifiedAt  when a full ingredient list was last captured from an authoritative source
 * @param inciSource      where that list came from, for the audit trail
 */
public record NailProductSafetyView(
        String externalId,
        String nailSystem,
        boolean professionalOnly,
        Map<String, SubstancePresence> substances,
        LocalDate inciVerifiedAt,
        String inciSource
) {
    public NailProductSafetyView {
        externalId = externalId == null ? "" : externalId.trim();
        nailSystem = nailSystem == null ? "" : nailSystem.trim().toLowerCase();
        substances = substances == null ? Map.of() : Map.copyOf(substances);
        inciSource = inciSource == null ? "" : inciSource.trim();
    }

    /**
     * Presence of a substance, defaulting to {@link SubstancePresence#UNKNOWN}.
     *
     * <p>This default is the single line that stops incomplete retailer data from becoming a safety claim.
     * "We have no record of HEMA in this product" and "this product does not contain HEMA" are different
     * statements, and only the second one is safe to act on.</p>
     */
    public SubstancePresence presenceOf(String substanceKey) {
        if (substanceKey == null || substanceKey.isBlank()) return SubstancePresence.UNKNOWN;
        return substances.getOrDefault(substanceKey.trim().toLowerCase(), SubstancePresence.UNKNOWN);
    }
}
