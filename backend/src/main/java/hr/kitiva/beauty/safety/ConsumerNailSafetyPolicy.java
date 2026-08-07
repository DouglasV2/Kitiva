package hr.kitiva.beauty.safety;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase C — the gate that decides whether a product may enter a CONSUMER at-home nail kit.
 *
 * <p><strong>Why this is a Spring bean and not a static utility.</strong> The obvious place for this looked
 * like {@code CatalogSourcePolicy.isPlannerEligible}. It cannot go there: that class is
 * {@code public final} with a private constructor and only static methods (verified at
 * CatalogSourcePolicy.java:31 and :62), while this gate needs the injected {@link RegulatoryRuleset}. More
 * importantly it needs DIFFERENT semantics — {@code isPlannerEligible} deliberately lets stale rows through
 * so an aging furniture catalog never silently empties, which is right for a sofa and wrong for an
 * ingredient verdict. Safety eligibility fails closed on staleness; commerce eligibility hedges. Composing
 * them would have quietly inherited the hedge.</p>
 *
 * <p><strong>Where it must be called.</strong> At product selection AND again at response assembly. A gate
 * enforced only at selection leaves every later path open — the audit found two such doors already
 * (a public unfiltered {@code GET /api/products}, and saved kits rehydrating frozen JSON with no
 * revalidation). A verdict is a cache, never a truth: it is invalid unless it was computed under the current
 * ruleset version and inside the evidence freshness window.</p>
 *
 * <p>Nothing here consults an LLM, and nothing here is a score. Every outcome is a deterministic predicate
 * over recorded evidence.</p>
 */
@Component
public class ConsumerNailSafetyPolicy {

    /**
     * How long ingredient evidence stays good. Deliberately shorter than the furniture catalog's 14-day
     * price staleness: a reformulation changes what is in the bottle, and a stale INCI capture is not a
     * slightly-out-of-date price, it is a claim about someone's skin made from old information.
     */
    public static final int INCI_FRESHNESS_DAYS = 90;

    /** Nail systems we sell to consumers. Everything else is refused by name, not by omission. */
    public static final List<String> CONSUMER_SYSTEMS = List.of("regular-polish", "gel-polish", "press-on");

    /**
     * Systems never recommended to consumers, whatever the catalog says. Listed explicitly so a new snapshot
     * cannot introduce one by simply not being on an allowlist somebody forgot to update.
     */
    public static final List<String> FORBIDDEN_CONSUMER_SYSTEMS =
            List.of("builder-gel", "polygel", "acrygel", "acrylic", "hard-gel", "dip-powder", "monomer");

    private final RegulatoryRuleset ruleset;

    public ConsumerNailSafetyPolicy(RegulatoryRuleset ruleset) {
        this.ruleset = ruleset;
    }

    /**
     * Evaluates one product for consumer at-home use.
     *
     * @param candidate the product's safety-relevant attributes
     * @param today     evaluation date, injected so the freshness rule is testable
     */
    public SafetyDecision evaluate(NailProductSafetyView candidate, LocalDate today) {
        List<String> reasons = new ArrayList<>();

        // 0. Kill-switch first. A broken ruleset must never read as "nothing is prohibited".
        if (!ruleset.isUsable()) {
            return SafetyDecision.blocked(
                    "Sigurnosna pravila nisu učitana, pa se kod kuće ne može sigurno predložiti nijedan proizvod.",
                    List.of("regulatory ruleset unusable: " + String.join("; ", ruleset.loadErrors())),
                    ruleset.rulesetVersion());
        }

        if (candidate == null) {
            return SafetyDecision.blocked("Proizvod nije dostupan za provjeru.",
                    List.of("null candidate"), ruleset.rulesetVersion());
        }

        // 1. Professional-only marking is absolute and needs no ingredient data to apply.
        if (candidate.professionalOnly()) {
            reasons.add("product is marked professional-use-only");
        }

        // 2. Forbidden systems, matched by name so omission from an allowlist cannot admit one.
        String system = candidate.nailSystem() == null ? "" : candidate.nailSystem().trim().toLowerCase();
        if (FORBIDDEN_CONSUMER_SYSTEMS.contains(system)) {
            reasons.add("nail system '" + system + "' is never recommended to consumers");
        } else if (!system.isBlank() && !CONSUMER_SYSTEMS.contains(system)) {
            // Unknown system: refuse. Default-deny, matching the catalog policy's posture on retailers.
            reasons.add("nail system '" + system + "' is not a recognised consumer system");
        }

        // 3. Every substance any in-force rule restricts must be VERIFIED_ABSENT — not merely "not found".
        for (String substance : ruleset.consumerBlockingSubstances(today)) {
            SubstancePresence presence = candidate.presenceOf(substance);
            if (!presence.permitsConsumerUse()) {
                reasons.add("substance '" + substance + "' is " + presence.name()
                        + " (only VERIFIED_ABSENT may enter a consumer kit)");
            }
        }

        // 4. Evidence freshness. Checked even when every substance reads VERIFIED_ABSENT, because an old
        //    verification cannot speak for a reformulated product.
        if (!reasons.isEmpty() || !ruleset.consumerBlockingSubstances(today).isEmpty()) {
            LocalDate verifiedAt = candidate.inciVerifiedAt();
            if (verifiedAt == null) {
                reasons.add("no ingredient verification date recorded");
            } else if (verifiedAt.plusDays(INCI_FRESHNESS_DAYS).isBefore(today)) {
                reasons.add("ingredient evidence is older than " + INCI_FRESHNESS_DAYS + " days (verified " + verifiedAt + ")");
            }
        }

        if (reasons.isEmpty()) {
            return SafetyDecision.allowed(ruleset.rulesetVersion());
        }
        return SafetyDecision.blocked(
                "Ovaj proizvod ne možemo preporučiti za samostalnu primjenu kod kuće.",
                List.copyOf(reasons),
                ruleset.rulesetVersion());
    }

    /**
     * True when a stored verdict may still be trusted. A cached verdict computed under an older ruleset, or
     * outside the freshness window, is not merely stale — it is invalid and must be recomputed or blocked.
     */
    public boolean cachedVerdictStillValid(String cachedRulesetVersion, LocalDate verdictComputedAt, LocalDate today) {
        if (!ruleset.isUsable()) return false;
        if (cachedRulesetVersion == null || !cachedRulesetVersion.equals(ruleset.rulesetVersion())) return false;
        if (verdictComputedAt == null) return false;
        return !verdictComputedAt.plusDays(INCI_FRESHNESS_DAYS).isBefore(today);
    }

    /** True when consumer at-home nail kits can be generated at all right now. */
    public boolean atHomeNailKitsAvailable() {
        return ruleset.isUsable();
    }

    /** Why at-home nail kits are unavailable, for honest user-facing copy. Empty when they are available. */
    public List<String> unavailabilityReasons() {
        return ruleset.isUsable() ? List.of() : ruleset.loadErrors();
    }
}
