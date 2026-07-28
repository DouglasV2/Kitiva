package ai.budgetspace.beauty.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Phase B — the structured nail request: a {@link NailDesignSpecDto} plus how the user intends to get it done.
 *
 * <p>{@code executionMode} is the one question the product must always ask and must never guess. The two
 * branches produce fundamentally different things — a salon brief describes a look to a professional and
 * recommends no chemicals at all, while an at-home kit sells a consumer a chemical system and therefore
 * carries every safety obligation in the product. Inferring the wrong branch means either withholding a kit
 * she wanted, or handing an untrained user products she should not have. {@link ExecutionMode#UNSPECIFIED}
 * exists so the parser can say "not stated" and the UI can ask, rather than defaulting to a guess.</p>
 *
 * <p><strong>No health data — see audit §6.6.</strong> {@code homeProfile} carries skill and equipment only.
 * There is deliberately no field for a prior gel or acrylate reaction, and none for damaged, painful,
 * inflamed or infected nails. When a prompt volunteers such a concern the safety gate returns a neutral
 * {@link KitStatus#SAFETY_BLOCKED} without diagnosing it, and the triggering text is not persisted.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NailLookBriefDto(
        String prompt,
        String market,
        String currency,
        int budgetCents,
        boolean budgetStrict,
        boolean includeShipping,
        ExecutionMode executionMode,
        NailDesignSpecDto design,
        HomeProfile homeProfile,
        boolean singleRetailerOnly,
        List<Assumption> assumptions
) {

    /** How the user intends to get these nails done. Never inferred — see the class note. */
    public enum ExecutionMode {
        /** Not stated yet. The UI must ask before either branch runs. */
        UNSPECIFIED("nije navedeno"),
        /** Inspiration image + nail-tech brief. No products recommended. */
        SALON("u salonu"),
        /** Design Diagram + a complete purchasable consumer kit, subject to the full safety gate. */
        AT_HOME("kod kuće");

        private final String hr;
        ExecutionMode(String hr) { this.hr = hr; }
        public String croatianLabel() { return hr; }
    }

    /**
     * Skill and equipment for the at-home branch. Equipment is expressed as {@link OwnedItemDto} so that
     * "nemam lampu" and "imam lampu" are the same mechanism used everywhere else in the product — and so a
     * stated absence is never confused with a missing answer.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HomeProfile(
            ExperienceLevel experienceLevel,
            NailDesignSpecDto.Length naturalNailLength,
            List<OwnedItemDto> equipment
    ) {
        public enum ExperienceLevel {
            FIRST_TIME("prvi put"), SOME("nešto iskustva"), ADVANCED("iskusna");

            private final String hr;
            ExperienceLevel(String hr) { this.hr = hr; }
            public String croatianLabel() { return hr; }
        }

        public HomeProfile {
            experienceLevel = experienceLevel == null ? ExperienceLevel.FIRST_TIME : experienceLevel;
            naturalNailLength = naturalNailLength == null ? NailDesignSpecDto.Length.SHORT : naturalNailLength;
            equipment = equipment == null ? List.of() : List.copyOf(equipment);
        }

        /** Beginner defaults: assume nothing is owned and the user has never done this before. */
        public static HomeProfile beginner() {
            return new HomeProfile(ExperienceLevel.FIRST_TIME, NailDesignSpecDto.Length.SHORT, List.of());
        }

        public List<String> ownedSlots() {
            return equipment.stream().filter(OwnedItemDto::satisfiesRequirement).map(OwnedItemDto::slot).toList();
        }

        /** Slots she explicitly said she lacks ("nemam lampu") — required even if otherwise optional. */
        public List<String> explicitlyMissingSlots() {
            return equipment.stream().filter(o -> !o.satisfiesRequirement()).map(OwnedItemDto::slot).toList();
        }
    }

    public NailLookBriefDto {
        prompt = prompt == null ? "" : prompt.trim();
        market = market == null || market.isBlank() ? "HR" : market.trim().toUpperCase();
        currency = currency == null || currency.isBlank() ? "EUR" : currency.trim().toUpperCase();
        budgetCents = Math.max(0, budgetCents);
        executionMode = executionMode == null ? ExecutionMode.UNSPECIFIED : executionMode;
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }

    /** True when the branch is still unknown, so the UI must ask before generating anything. */
    public boolean needsExecutionModeAnswer() {
        return executionMode == ExecutionMode.UNSPECIFIED;
    }

    public boolean hasBudget() {
        return budgetCents > 0;
    }
}
