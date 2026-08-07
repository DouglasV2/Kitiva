package hr.kitiva.beauty.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Phase B — the structured makeup request. The deterministic parser (Phase D) fills this from Croatian free
 * text; the kit engine (Phase E) consumes it. The LLM may help populate it, but never decides completeness,
 * compatibility, budget or safety from it.
 *
 * <p><strong>Money is integer cents.</strong> The furniture planner works in whole euros, which is fine when
 * a sofa costs 899. A makeup kit is eight items between 4 and 25 euros, where repeated rounding can move a
 * total by enough to flip an over-budget verdict — and an over-budget verdict that is wrong is the product
 * lying about the one number the user cares most about. {@code AmountParser} returns whole euros, so the
 * euro-to-cent conversion happens once, at the parser boundary, and is pinned by a test.</p>
 *
 * <p><strong>No health data is collected — see audit §6.6.</strong> There is deliberately no field for a
 * prior reaction, an allergy, or any skin or nail condition. {@code skinType} is the ordinary cosmetic
 * attribute every beauty retailer filters on (dry/oily/combination/normal), and {@code gentleEyeAreaPreferred}
 * / {@code fragranceFreePreferred} are stated as PRODUCT PREFERENCES, not declared conditions: they steer
 * selection toward products whose manufacturer makes that claim, and nothing about the user is stored or
 * inferred. If a prompt volunteers a health concern, the safety gate handles it as a neutral block and the
 * text is not persisted — it never becomes a field here.</p>
 *
 * @param prompt                 the user's own words, kept so the result can be explained back to her
 * @param market                 always {@code "HR"} in MVP
 * @param currency               always {@code "EUR"} in MVP
 * @param budgetCents            total budget in cents; 0 = not stated
 * @param budgetStrict           true = a hard ceiling; false = a target we may exceed with an explanation
 * @param includeShipping        true = shipping counts against the budget
 * @param look                   canonical look archetype, e.g. {@code "everyday"}, {@code "soft-glam"}
 * @param occasion               free text, e.g. {@code "vjenčanje"}
 * @param styleWords             adjectives from the prompt, e.g. {@code ["prirodno", "uredno"]}
 * @param coverage               {@code "light"} | {@code "medium"} | {@code "full"}; empty = infer
 * @param finish                 {@code "natural"} | {@code "matte"} | {@code "dewy"}; empty = infer
 * @param skinType               cosmetic attribute: {@code "dry"} | {@code "oily"} | {@code "combination"} | {@code "normal"}
 * @param skinToneDepth          {@code "fair"} … {@code "deep"}; drives shade CANDIDATES, never a guarantee
 * @param undertone              {@code "cool"} | {@code "neutral"} | {@code "warm"} | {@code "olive"}
 * @param referenceShade         an existing foundation/concealer shade she already wears — by far the most
 *                               reliable shade signal, and the only one that justifies a close match
 * @param gentleEyeAreaPreferred product preference: prefer products claiming eye-area gentleness
 * @param fragranceFreePreferred product preference: prefer products claiming fragrance-free
 * @param ownedItems             what she has and explicitly does not have
 * @param requiredSlots          slots she insisted on
 * @param excludedSlots          slots she does not want
 * @param brandPreferences       preferred brands; a soft score, never a hard filter
 * @param singleRetailerOnly     true = build the kit from one store even if it costs more
 * @param skillLevel             {@code "beginner"} | {@code "some"} | {@code "advanced"}
 * @param assumptions            everything inferred rather than stated
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BeautyBriefDto(
        String prompt,
        String market,
        String currency,
        int budgetCents,
        boolean budgetStrict,
        boolean includeShipping,
        String look,
        String occasion,
        List<String> styleWords,
        String coverage,
        String finish,
        String skinType,
        String skinToneDepth,
        String undertone,
        String referenceShade,
        boolean gentleEyeAreaPreferred,
        boolean fragranceFreePreferred,
        List<OwnedItemDto> ownedItems,
        List<String> requiredSlots,
        List<String> excludedSlots,
        List<String> brandPreferences,
        boolean singleRetailerOnly,
        String skillLevel,
        List<Assumption> assumptions
) {
    /** Cents per euro — the one place the conversion constant lives. */
    public static final int CENTS_PER_EURO = 100;

    public BeautyBriefDto {
        prompt = prompt == null ? "" : prompt.trim();
        market = market == null || market.isBlank() ? "HR" : market.trim().toUpperCase();
        currency = currency == null || currency.isBlank() ? "EUR" : currency.trim().toUpperCase();
        budgetCents = Math.max(0, budgetCents);
        look = look == null ? "" : look.trim().toLowerCase();
        occasion = occasion == null ? "" : occasion.trim();
        coverage = coverage == null ? "" : coverage.trim().toLowerCase();
        finish = finish == null ? "" : finish.trim().toLowerCase();
        skinType = skinType == null ? "" : skinType.trim().toLowerCase();
        skinToneDepth = skinToneDepth == null ? "" : skinToneDepth.trim().toLowerCase();
        undertone = undertone == null ? "" : undertone.trim().toLowerCase();
        referenceShade = referenceShade == null ? "" : referenceShade.trim();
        skillLevel = skillLevel == null || skillLevel.isBlank() ? "beginner" : skillLevel.trim().toLowerCase();
        styleWords = styleWords == null ? List.of() : List.copyOf(styleWords);
        ownedItems = ownedItems == null ? List.of() : List.copyOf(ownedItems);
        requiredSlots = requiredSlots == null ? List.of() : List.copyOf(requiredSlots);
        excludedSlots = excludedSlots == null ? List.of() : List.copyOf(excludedSlots);
        brandPreferences = brandPreferences == null ? List.of() : List.copyOf(brandPreferences);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }

    /**
     * The single euro-to-cent boundary. {@code AmountParser.parseBudget} returns whole euros; every other
     * money value in the beauty engine is cents. Converting in exactly one place is what keeps the two
     * units from being mixed by accident.
     */
    public static int eurosToCents(int euros) {
        return Math.max(0, euros) * CENTS_PER_EURO;
    }

    /** True when the user stated a budget at all. */
    public boolean hasBudget() {
        return budgetCents > 0;
    }

    /** Slots she owns, so the engine can skip them. Excludes explicit "nemam" entries. */
    public List<String> ownedSlots() {
        return ownedItems.stream().filter(OwnedItemDto::satisfiesRequirement).map(OwnedItemDto::slot).toList();
    }

    /**
     * Slots she explicitly said she does NOT have ("nemam maskaru"). These are required even when the
     * completeness graph would otherwise treat them as optional — she told us she needs one.
     */
    public List<String> explicitlyMissingSlots() {
        return ownedItems.stream().filter(o -> !o.satisfiesRequirement()).map(OwnedItemDto::slot).toList();
    }
}
