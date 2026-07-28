package ai.budgetspace.beauty.safety;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase C — loads and validates the regulatory rules, and owns the safety kill-switch.
 *
 * <p><strong>Fail closed, always.</strong> If the ruleset file is missing, unparseable, structurally invalid,
 * or carries a version this build does not expect, the ruleset reports {@link #isUsable()} false and every
 * consumer at-home nail kit is refused. It does not degrade to "no rules loaded, so nothing is prohibited" —
 * that is the failure mode that ships a banned substance to someone's hands, and it is precisely what an
 * empty-list default would silently do.</p>
 *
 * <p><strong>Ships empty by design (2026-07-28).</strong> The rules themselves are NOT encoded yet. EUR-Lex
 * serves an AWS WAF bot challenge to this environment, so the primary texts for HEMA / Di-HEMA TMHDC
 * (Reg. (EU) 2020/1682) and TPO could not be retrieved, and the owner's standing constraint is that only
 * primary EU sources may back a rule. Secondary sources were available and were deliberately not used.
 * So the machinery ships complete and the ruleset ships empty, which — by the fail-closed rule above —
 * means consumer at-home nail kits are hard-blocked until a human loads verified rules. That is the correct
 * behaviour, not a temporary hack: a safety subsystem with no verified rules genuinely does not know whether
 * anything is safe.</p>
 *
 * <p>To activate: place the primary texts under {@code docs/regulatory/}, encode them into
 * {@code resources/safety/eu-substance-rules-v1.json} with all seven mandatory fields per
 * {@link RegulatoryRule}, and set {@code status} to {@code "ACTIVE"}.</p>
 */
@Component
public class RegulatoryRuleset {

    private static final Logger log = LoggerFactory.getLogger(RegulatoryRuleset.class);

    /** The ruleset version this build understands. A mismatch is a hard failure, never a warning. */
    public static final int SUPPORTED_VERSION = 1;

    static final String RESOURCE = "/safety/eu-substance-rules-v1.json";

    private final RulesetDocument document;
    private final List<String> loadErrors;

    public RegulatoryRuleset() {
        this(RESOURCE);
    }

    RegulatoryRuleset(String resource) {
        List<String> errors = new ArrayList<>();
        RulesetDocument loaded = null;
        try (InputStream in = RegulatoryRuleset.class.getResourceAsStream(resource)) {
            if (in == null) {
                errors.add("ruleset resource not found: " + resource);
            } else {
                ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
                loaded = mapper.readValue(in, RulesetDocument.class);
            }
        } catch (Exception ex) {
            // A corrupt ruleset must not crash boot — it must disable the feature that depends on it.
            errors.add("ruleset could not be parsed: " + ex.getMessage());
        }

        if (loaded != null) {
            if (loaded.version() != SUPPORTED_VERSION) {
                errors.add("ruleset version " + loaded.version() + " is not the supported version " + SUPPORTED_VERSION);
            }
            for (RegulatoryRule rule : loaded.rules()) {
                List<String> missing = rule.missingMandatoryFields();
                if (!missing.isEmpty()) {
                    errors.add("rule '" + rule.ruleId() + "' is missing mandatory field(s): " + String.join(", ", missing));
                }
            }
            if (!"ACTIVE".equalsIgnoreCase(loaded.status())) {
                errors.add("ruleset status is '" + loaded.status() + "', not ACTIVE — no rules have been verified against primary sources yet");
            }
            if (loaded.rules().isEmpty() && "ACTIVE".equalsIgnoreCase(loaded.status())) {
                errors.add("ruleset is marked ACTIVE but contains no rules");
            }
        }

        this.document = loaded;
        this.loadErrors = List.copyOf(errors);

        if (loadErrors.isEmpty()) {
            log.info("Regulatory ruleset loaded: version {}, {} rule(s)", SUPPORTED_VERSION, rules().size());
        } else {
            log.warn("Regulatory ruleset NOT usable — consumer at-home nail kits are disabled. Reasons: {}", loadErrors);
        }
    }

    /**
     * The kill-switch. False ⇒ no consumer at-home nail kit may be generated, anywhere, for any product.
     * Callers must check this BEFORE evaluating individual products, so a broken ruleset cannot be mistaken
     * for a clean bill of health.
     */
    public boolean isUsable() {
        return loadErrors.isEmpty() && document != null;
    }

    /** Why the ruleset is unusable. Empty when it is usable. Surfaced in the blocked-kit reason. */
    public List<String> loadErrors() {
        return loadErrors;
    }

    public List<RegulatoryRule> rules() {
        return document == null ? List.of() : document.rules();
    }

    /**
     * Ruleset version stamped onto any cached verdict, so a bump invalidates every cache.
     *
     * <p>Reports {@code "UNUSABLE"} whenever {@link #isUsable()} is false — not only when the file failed to
     * parse. A ruleset can parse perfectly and still be unusable (wrong version, a structurally incomplete
     * rule, or the status this build ships with: awaiting primary sources). Returning a real-looking version
     * in that state would stamp block decisions with a version that implies rules were loaded, and would let
     * a verdict cached during an outage pass {@code cachedVerdictStillValid} once the ruleset was fixed at
     * the same revision. An unusable ruleset has no version worth recording.</p>
     */
    public String rulesetVersion() {
        return isUsable() ? SUPPORTED_VERSION + ":" + document.revision() : "UNUSABLE";
    }

    /** Rules governing a substance that are in force on the given date. */
    public List<RegulatoryRule> rulesFor(String substanceKey, LocalDate on) {
        if (substanceKey == null || substanceKey.isBlank()) return List.of();
        String key = substanceKey.trim().toLowerCase();
        return rules().stream()
                .filter(r -> key.equals(r.substanceKey()))
                .filter(r -> r.inForceOn(on))
                .toList();
    }

    /** Substances that must be verified absent before a product may enter a consumer at-home kit. */
    public List<String> consumerBlockingSubstances(LocalDate on) {
        return rules().stream()
                .filter(r -> r.inForceOn(on))
                .filter(RegulatoryRule::blocksConsumerUse)
                .map(RegulatoryRule::substanceKey)
                .distinct()
                .toList();
    }

    public Optional<RegulatoryRule> byId(String ruleId) {
        return rules().stream().filter(r -> r.ruleId().equals(ruleId)).findFirst();
    }

    /** On-disk shape of {@code eu-substance-rules-v1.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RulesetDocument(
            int version,
            String revision,
            String status,
            String provenanceNote,
            Map<String, String> primarySources,
            List<RegulatoryRule> rules
    ) {
        RulesetDocument {
            revision = revision == null ? "0" : revision.trim();
            status = status == null ? "DRAFT" : status.trim();
            provenanceNote = provenanceNote == null ? "" : provenanceNote;
            primarySources = primarySources == null ? Map.of() : Map.copyOf(primarySources);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
}
