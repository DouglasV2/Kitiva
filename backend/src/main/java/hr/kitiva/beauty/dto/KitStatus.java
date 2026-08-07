package hr.kitiva.beauty.dto;

/**
 * Phase B — the five honest outcomes of building a kit. This is the single most important field in the
 * product: it is what separates a validated kit from a list of suggestions.
 *
 * <p>The rule the whole engine exists to protect: <strong>a kit may only claim {@link #COMPLETE} when
 * every required slot in its completeness graph is filled by a real, eligible, compatible product inside
 * budget.</strong> Anything less must degrade to one of the other four — never to a quiet "here are some
 * products". Dropping a required item and still reporting COMPLETE is the worst failure this product can
 * have, worse than finding nothing at all.</p>
 *
 * <p>Status is computed deterministically, outside the LLM. The LLM may phrase the explanation of a
 * status; it may never decide one.</p>
 */
public enum KitStatus {

    /** Every required slot filled, inside budget, all compatibility rules satisfied, nothing assumed. */
    COMPLETE("Potpun"),

    /**
     * Complete, but at least one decision was inferred rather than stated — e.g. the finish was guessed
     * from "prirodno", or an owned item was matched by category rather than by exact product. Every such
     * inference is listed in the kit's assumptions so the user can correct it and re-optimise.
     */
    COMPLETE_WITH_ASSUMPTIONS("Potpun uz pretpostavke"),

    /**
     * A required slot could not be filled — no eligible product exists, or the only candidates are out of
     * stock or blocked. The kit names the missing slot and why. It must NOT silently ship without it.
     */
    INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE("Nepotpun — nedostaje obavezan proizvod"),

    /**
     * Every required slot can be filled, but not within the stated budget — after the cheapest eligible
     * option was chosen for every required slot and every optional item was dropped. The kit is shown
     * honestly over budget rather than quietly trimmed below completeness.
     */
    OVER_BUDGET("Iznad budžeta"),

    /**
     * Generation was stopped by the safety gate. Reached when a look requires a system we do not sell to
     * consumers (builder gel, polygel, acrylic), when no compatible consumer-safe system can be assembled,
     * when required ingredient evidence is missing or stale, or when the prompt itself describes a possible
     * reaction, injury or infection. Carries a neutral reason — never a diagnosis.
     */
    SAFETY_BLOCKED("Zaustavljeno iz sigurnosnih razloga");

    private final String croatianLabel;

    KitStatus(String croatianLabel) {
        this.croatianLabel = croatianLabel;
    }

    /** User-facing Croatian label. HR is the launch market and the source language. */
    public String croatianLabel() {
        return croatianLabel;
    }

    /** True when the kit is safe to present as a buyable shopping list. */
    public boolean isPurchasable() {
        return this == COMPLETE || this == COMPLETE_WITH_ASSUMPTIONS;
    }

    /** True when the kit may claim every required slot is filled. */
    public boolean isComplete() {
        return this == COMPLETE || this == COMPLETE_WITH_ASSUMPTIONS || this == OVER_BUDGET;
    }
}
