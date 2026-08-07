package hr.kitiva.beauty.safety;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase C — one regulatory rule, carrying its own provenance.
 *
 * <p>Every field below is mandatory by owner decision (audit §6.5), and the reason is that a safety rule
 * without a citation is an opinion. A rule that blocks a product from a consumer's hands has to be able to
 * answer "who says so, since when, and when did a human last check?" — otherwise nobody can audit it, nobody
 * can update it when the law moves, and nobody can tell a hardcoded guess from a sourced fact.</p>
 *
 * <p><strong>Primary sources only.</strong> {@code officialSourceReference} must point at an official
 * publication (an OJ L reference, a CELEX id, or a national authority's own text). Secondary sources —
 * summaries, blog posts, supplier claims, an LLM's recollection — are not admissible, no matter how
 * confident. {@link RegulatoryRuleset} validates that this field is present and non-trivial; it cannot
 * validate that the citation is honest, which is why {@code lastReviewedDate} names a human's check.</p>
 *
 * @param ruleId                  stable id, e.g. {@code "EU-1223-2009-ANNEX-III-313-HEMA"}
 * @param jurisdiction            e.g. {@code "EU"}, {@code "HR"}
 * @param officialSourceReference primary citation — OJ/CELEX reference or authority document id
 * @param effectiveDate           when the restriction takes legal effect
 * @param lastReviewedDate        when a human last verified this entry against the source
 * @param affectedProductAttributes product attributes this rule reads, e.g. {@code ["hemaStatus"]}
 * @param behaviour               deterministic outcome — see {@link Behaviour}
 * @param appliesToNailSystems    nail systems in scope; empty = all
 * @param substanceKey            the substance attribute this rule governs, e.g. {@code "hema"}, {@code "tpo"}
 * @param noteHr                  short Croatian explanation surfaced when this rule blocks something
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegulatoryRule(
        String ruleId,
        String jurisdiction,
        String officialSourceReference,
        LocalDate effectiveDate,
        LocalDate lastReviewedDate,
        List<String> affectedProductAttributes,
        Behaviour behaviour,
        List<String> appliesToNailSystems,
        String substanceKey,
        String noteHr
) {

    /** What the rule does when it matches. Deterministic — never a score, never a suggestion. */
    public enum Behaviour {
        /** Explicitly permitted. Present so a rule can record a permission, not only a prohibition. */
        ALLOW,
        /** Permitted only under stated conditions — e.g. professional use only. Blocks CONSUMER kits. */
        RESTRICT,
        /** Prohibited outright. Blocks everywhere. */
        BLOCK
    }

    public RegulatoryRule {
        ruleId = ruleId == null ? "" : ruleId.trim();
        jurisdiction = jurisdiction == null ? "" : jurisdiction.trim().toUpperCase();
        officialSourceReference = officialSourceReference == null ? "" : officialSourceReference.trim();
        substanceKey = substanceKey == null ? "" : substanceKey.trim().toLowerCase();
        noteHr = noteHr == null ? "" : noteHr.trim();
        affectedProductAttributes = affectedProductAttributes == null ? List.of() : List.copyOf(affectedProductAttributes);
        appliesToNailSystems = appliesToNailSystems == null ? List.of() : List.copyOf(appliesToNailSystems);
    }

    /** True when this rule is in force on the given date. A rule not yet effective must not block anything. */
    public boolean inForceOn(LocalDate date) {
        return effectiveDate != null && !date.isBefore(effectiveDate);
    }

    /** True when a consumer at-home kit may never contain a product this rule matches. */
    public boolean blocksConsumerUse() {
        return behaviour == Behaviour.BLOCK || behaviour == Behaviour.RESTRICT;
    }

    /**
     * Structural completeness — every mandated field present. Says nothing about whether the citation is
     * truthful; only a human review can establish that, which is what {@code lastReviewedDate} records.
     */
    public List<String> missingMandatoryFields() {
        List<String> missing = new java.util.ArrayList<>();
        if (ruleId.isBlank()) missing.add("ruleId");
        if (jurisdiction.isBlank()) missing.add("jurisdiction");
        if (officialSourceReference.isBlank()) missing.add("officialSourceReference");
        if (effectiveDate == null) missing.add("effectiveDate");
        if (lastReviewedDate == null) missing.add("lastReviewedDate");
        if (affectedProductAttributes.isEmpty()) missing.add("affectedProductAttributes");
        if (behaviour == null) missing.add("behaviour");
        if (substanceKey.isBlank()) missing.add("substanceKey");
        return List.copyOf(missing);
    }

    public boolean isStructurallyComplete() {
        return missingMandatoryFields().isEmpty();
    }
}
