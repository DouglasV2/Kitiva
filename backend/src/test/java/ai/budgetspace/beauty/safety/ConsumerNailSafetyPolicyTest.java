package ai.budgetspace.beauty.safety;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C — the consumer nail safety gate.
 *
 * <p>Every test here asserts a REFUSAL. That is deliberate: this class exists to say no, and the failure
 * mode that matters is a silent yes. Tests that a safe product is allowed are worth far less than tests that
 * an unsafe one is stopped, so the allow-path is checked once and the block-paths exhaustively.</p>
 */
class ConsumerNailSafetyPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 28);

    /** The ruleset as it actually ships today: empty, awaiting primary sources, therefore unusable. */
    private ConsumerNailSafetyPolicy shippedPolicy() {
        return new ConsumerNailSafetyPolicy(new RegulatoryRuleset());
    }

    private NailProductSafetyView product(String system, boolean professionalOnly,
                                          Map<String, SubstancePresence> substances, LocalDate inciVerifiedAt) {
        return new NailProductSafetyView("test-sku", system, professionalOnly, substances, inciVerifiedAt, "test");
    }

    @Test
    void anUnusableRulesetKillSwitchesEveryConsumerKit() {
        // The most important test in the subsystem. With no verified rules loaded, the gate must refuse
        // EVERYTHING - not wave products through because no rule happened to match. An empty rule list must
        // never read as "nothing is prohibited".
        ConsumerNailSafetyPolicy policy = shippedPolicy();

        assertThat(policy.atHomeNailKitsAvailable())
                .as("ruleset ships empty and awaiting primary sources, so at-home kits are disabled")
                .isFalse();
        assertThat(policy.unavailabilityReasons()).isNotEmpty();

        // Even a product with nothing wrong with it is refused while the ruleset is unusable.
        NailProductSafetyView spotless = product("press-on", false,
                Map.of("hema", SubstancePresence.VERIFIED_ABSENT, "tpo", SubstancePresence.VERIFIED_ABSENT), TODAY);

        SafetyDecision decision = policy.evaluate(spotless, TODAY);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.technicalReasons().toString()).contains("regulatory ruleset unusable");
        assertThat(decision.userMessageHr()).isNotBlank();
    }

    @Test
    void theShippedRulesetIsDeliberatelyEmptyAndSaysSo() {
        RegulatoryRuleset ruleset = new RegulatoryRuleset();

        assertThat(ruleset.isUsable()).isFalse();
        assertThat(ruleset.rules()).isEmpty();
        assertThat(ruleset.loadErrors().toString())
                .as("the reason is recorded, not silent")
                .contains("AWAITING_PRIMARY_SOURCES");
        assertThat(ruleset.rulesetVersion()).isEqualTo("UNUSABLE");
    }

    @Test
    void aMissingRulesetFileFailsClosedRatherThanCrashingBoot() {
        // A missing or corrupt ruleset must disable the feature, not take the application down - and must
        // certainly not default to permissive.
        RegulatoryRuleset missing = new RegulatoryRuleset("/safety/this-file-does-not-exist.json");

        assertThat(missing.isUsable()).isFalse();
        assertThat(missing.rules()).isEmpty();
        assertThat(missing.loadErrors().toString()).contains("not found");

        SafetyDecision decision = new ConsumerNailSafetyPolicy(missing)
                .evaluate(product("press-on", false, Map.of(), TODAY), TODAY);
        assertThat(decision.blocked()).isTrue();
    }

    @Test
    void missingIngredientDataIsUnknownAndUnknownIsNeverFreeFrom() {
        // The single rule that stops incomplete retailer data becoming a safety claim. A substance absent
        // from the map is UNKNOWN, not absent from the product.
        NailProductSafetyView noInciAtAll = product("gel-polish", false, Map.of(), null);

        assertThat(noInciAtAll.presenceOf("hema")).isEqualTo(SubstancePresence.UNKNOWN);
        assertThat(noInciAtAll.presenceOf("tpo")).isEqualTo(SubstancePresence.UNKNOWN);
        assertThat(SubstancePresence.UNKNOWN.permitsConsumerUse()).isFalse();
        assertThat(SubstancePresence.VERIFIED_PRESENT.permitsConsumerUse()).isFalse();
        assertThat(SubstancePresence.VERIFIED_ABSENT.permitsConsumerUse())
                .as("only a positive verification permits consumer use")
                .isTrue();
    }

    @Test
    void unparseableSubstanceValuesDegradeToUnknownRatherThanToSafe() {
        // A corrupt or newly-added column value must fail toward "block", never toward "allow".
        assertThat(SubstancePresence.parse(null)).isEqualTo(SubstancePresence.UNKNOWN);
        assertThat(SubstancePresence.parse("")).isEqualTo(SubstancePresence.UNKNOWN);
        assertThat(SubstancePresence.parse("probably fine")).isEqualTo(SubstancePresence.UNKNOWN);
        assertThat(SubstancePresence.parse("false")).isEqualTo(SubstancePresence.UNKNOWN);
        assertThat(SubstancePresence.parse("verified-absent")).isEqualTo(SubstancePresence.VERIFIED_ABSENT);
        assertThat(SubstancePresence.parse("  VERIFIED_PRESENT  ")).isEqualTo(SubstancePresence.VERIFIED_PRESENT);
    }

    @Test
    void forbiddenConsumerSystemsAreListedByNameNotByOmission() {
        // A new snapshot must not be able to introduce builder gel simply by not appearing on an allowlist
        // somebody forgot to extend.
        assertThat(ConsumerNailSafetyPolicy.FORBIDDEN_CONSUMER_SYSTEMS)
                .contains("builder-gel", "polygel", "acrygel", "acrylic", "hard-gel", "dip-powder", "monomer");
        assertThat(ConsumerNailSafetyPolicy.CONSUMER_SYSTEMS)
                .containsExactly("regular-polish", "gel-polish", "press-on");
        assertThat(ConsumerNailSafetyPolicy.CONSUMER_SYSTEMS)
                .doesNotContainAnyElementsOf(ConsumerNailSafetyPolicy.FORBIDDEN_CONSUMER_SYSTEMS);
    }

    @Test
    void aCachedVerdictIsInvalidatedByARulesetBumpOrByAge() {
        ConsumerNailSafetyPolicy policy = shippedPolicy();

        // While the ruleset is unusable NO cached verdict may be trusted, whatever it says.
        assertThat(policy.cachedVerdictStillValid("UNUSABLE", TODAY, TODAY)).isFalse();
        assertThat(policy.cachedVerdictStillValid("1:0", TODAY, TODAY)).isFalse();
        assertThat(policy.cachedVerdictStillValid(null, TODAY, TODAY)).isFalse();
        assertThat(policy.cachedVerdictStillValid("1:0", null, TODAY))
                .as("a verdict with no computation date cannot be validated")
                .isFalse();
    }

    @Test
    void safetyDecisionSeparatesUserCopyFromTheAuditTrail() {
        SafetyDecision blocked = SafetyDecision.blocked(
                "Ovaj proizvod ne možemo preporučiti za samostalnu primjenu kod kuće.",
                java.util.List.of("substance 'hema' is UNKNOWN"), "1:3");

        assertThat(blocked.blocked()).isTrue();
        assertThat(blocked.userMessageHr())
                .as("neutral, no diagnosis, says what we will not do rather than what is wrong with her")
                .doesNotContainIgnoringCase("alerg")
                .doesNotContainIgnoringCase("reakcij")
                .doesNotContainIgnoringCase("infekcij");
        assertThat(blocked.technicalReasons()).containsExactly("substance 'hema' is UNKNOWN");
        assertThat(blocked.rulesetVersion()).as("every verdict is stamped so a rules change invalidates it").isEqualTo("1:3");
    }

    @Test
    void anIncompleteRuleWouldFailTheBuildRatherThanShip() {
        // Guards the seven mandatory fields. A rule missing its citation or its review date is not a weaker
        // rule - it is an unauditable one, and it must not load.
        RegulatoryRule incomplete = new RegulatoryRule(
                "EU-SOMETHING", "EU", "", null, null, java.util.List.of(),
                RegulatoryRule.Behaviour.BLOCK, java.util.List.of(), "", "");

        assertThat(incomplete.isStructurallyComplete()).isFalse();
        assertThat(incomplete.missingMandatoryFields())
                .contains("officialSourceReference", "effectiveDate", "lastReviewedDate",
                        "affectedProductAttributes", "substanceKey");

        RegulatoryRule complete = new RegulatoryRule(
                "EU-1223-2009-III-313-HEMA", "EU", "OJ L 379, 13.11.2020, p. 34 (CELEX 32020R1682)",
                LocalDate.of(2021, 9, 3), LocalDate.of(2026, 7, 28),
                java.util.List.of("hemaStatus"), RegulatoryRule.Behaviour.RESTRICT,
                java.util.List.of("gel-polish"), "hema", "Samo za profesionalnu upotrebu.");

        assertThat(complete.isStructurallyComplete()).isTrue();
        assertThat(complete.blocksConsumerUse()).as("RESTRICT blocks CONSUMER use even though it is not a ban").isTrue();
        assertThat(complete.inForceOn(LocalDate.of(2021, 9, 2))).as("not in force the day before it applies").isFalse();
        assertThat(complete.inForceOn(LocalDate.of(2021, 9, 3))).isTrue();
    }
}
