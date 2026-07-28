package ai.budgetspace.beauty.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Phase B — one inferred decision, recorded so the user can see and correct it.
 *
 * <p><strong>Deliberately not a confidence calculus.</strong> An earlier design carried per-field
 * {@code {value, source, confidence, constraint_type}} provenance with numeric confidence bands. That was
 * cut: the product needs to answer "what did you assume?" — a question a plain list answers — not "how
 * sure are you?", which invites a false precision we cannot honestly compute. One marker per inference is
 * what {@link KitStatus#COMPLETE_WITH_ASSUMPTIONS} means.</p>
 *
 * <p>An assumption exists only when the system CHOSE something the user did not state. A value the user
 * stated is not an assumption, and a value left at a neutral default that does not change the kit is not
 * an assumption either — recording those would bury the two or three that actually matter.</p>
 *
 * @param field    the brief field that was inferred, e.g. {@code "finish"}, {@code "accentFingers"}
 * @param assumed  the value that was chosen, in the same vocabulary the field uses
 * @param reasonHr short Croatian explanation shown to the user, e.g. "Nije navedeno, pretpostavljen
 *                 prirodan finiš jer je opisan svakodnevni izgled."
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Assumption(
        String field,
        String assumed,
        String reasonHr
) {
    public Assumption {
        field = field == null ? "" : field.trim();
        assumed = assumed == null ? "" : assumed.trim();
        reasonHr = reasonHr == null ? "" : reasonHr.trim();
    }

    public static Assumption of(String field, String assumed, String reasonHr) {
        return new Assumption(field, assumed, reasonHr);
    }
}
