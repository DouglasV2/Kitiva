package hr.kitiva.beauty.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Phase B — something the user already owns, so the kit does not make her buy it twice.
 *
 * <p>Defined here rather than in the refinement phase because the prompt parser produces these the moment
 * it reads "već imam maskaru i kistove" / "nemam lampu" — two phases before refinement can consume them.</p>
 *
 * <p>Two levels of precision, and the difference matters for honesty:</p>
 * <ul>
 *   <li><strong>Slot only</strong> ({@code productId} null) — "I own a mascara". The engine can skip the
 *       slot, but it cannot verify compatibility with what she actually has, so skipping produces an
 *       {@link Assumption}. This is the common case from free text.</li>
 *   <li><strong>Exact product</strong> ({@code productId} set) — chosen from the catalog in the UI. The
 *       engine can check compatibility properly and no assumption is recorded.</li>
 * </ul>
 *
 * <p>{@code satisfiesRequirement} is what makes "nemam lampu" work as the mirror of "imam lampu": a parsed
 * ownership statement can be NEGATIVE, and a negative must never be mistaken for a positive. A missing
 * item is recorded explicitly rather than by absence, because absence is ambiguous — it could equally mean
 * the user never mentioned it.</p>
 *
 * @param slot                 canonical kit slot, e.g. {@code "mascara"}, {@code "lamp"}, {@code "base-coat"}
 * @param productId            catalog {@code externalId} when the user picked a specific product, else null
 * @param rawText              the phrase this came from, for explaining the decision back to her
 * @param satisfiesRequirement true = she has it (skip the slot); false = she explicitly does NOT have it
 *                             ("nemam lampu"), so the slot is REQUIRED even if it would otherwise be optional
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OwnedItemDto(
        String slot,
        String productId,
        String rawText,
        boolean satisfiesRequirement
) {
    public OwnedItemDto {
        slot = slot == null ? "" : slot.trim().toLowerCase();
        rawText = rawText == null ? "" : rawText.trim();
    }

    /** "već imam maskaru" — owned by category, no specific product known. */
    public static OwnedItemDto owned(String slot, String rawText) {
        return new OwnedItemDto(slot, null, rawText, true);
    }

    /** The user picked an exact catalog product she already owns. */
    public static OwnedItemDto ownedProduct(String slot, String productId, String rawText) {
        return new OwnedItemDto(slot, productId, rawText, true);
    }

    /** "nemam lampu" — explicitly NOT owned, so the kit must include it. */
    public static OwnedItemDto missing(String slot, String rawText) {
        return new OwnedItemDto(slot, null, rawText, false);
    }

    /** True when this ownership is known only by category, so skipping the slot is an assumption. */
    public boolean isCategoryLevel() {
        return satisfiesRequirement && (productId == null || productId.isBlank());
    }
}
