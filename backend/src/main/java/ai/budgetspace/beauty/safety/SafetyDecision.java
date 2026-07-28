package ai.budgetspace.beauty.safety;

import java.util.List;

/**
 * Phase C — the outcome of a safety evaluation.
 *
 * <p>Two audiences, deliberately kept apart. {@code userMessageHr} is what a person reads: neutral, brief,
 * and never a diagnosis — it says what we will not do, not what is wrong with her. {@code technicalReasons}
 * is what an auditor reads: the exact predicates that fired, retained so a block can be explained and
 * challenged months later.</p>
 *
 * <p>{@code rulesetVersion} is stamped on every decision so a stored verdict can be invalidated the moment
 * the rules change. A verdict without its ruleset version cannot be trusted, because there is no way to
 * tell which rules produced it.</p>
 */
public record SafetyDecision(
        boolean allowed,
        String userMessageHr,
        List<String> technicalReasons,
        String rulesetVersion
) {
    public SafetyDecision {
        userMessageHr = userMessageHr == null ? "" : userMessageHr;
        technicalReasons = technicalReasons == null ? List.of() : List.copyOf(technicalReasons);
        rulesetVersion = rulesetVersion == null ? "UNKNOWN" : rulesetVersion;
    }

    public static SafetyDecision allowed(String rulesetVersion) {
        return new SafetyDecision(true, "", List.of(), rulesetVersion);
    }

    public static SafetyDecision blocked(String userMessageHr, List<String> technicalReasons, String rulesetVersion) {
        return new SafetyDecision(false, userMessageHr, technicalReasons, rulesetVersion);
    }

    public boolean blocked() {
        return !allowed;
    }
}
