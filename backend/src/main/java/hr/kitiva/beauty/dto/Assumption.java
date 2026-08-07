package hr.kitiva.beauty.dto;

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
 * <p><strong>{@code labelHr} exists because the UI showed {@code assumed}.</strong> That put
 * "glossy", "mirrored" and a bare "prstenjak" on screen as headings — our internal vocabulary leaking into a
 * Croatian consumer product. {@code field} and {@code assumed} stay machine-readable for whoever needs to
 * branch on them; {@code labelHr} is the only one anything renders.</p>
 *
 * @param field    the brief field that was inferred, e.g. {@code "finish"}, {@code "accentFingers"}
 * @param assumed  the value that was chosen, in the same vocabulary the field uses — never displayed
 * @param labelHr  natural Croatian noun phrase for what was assumed, e.g. "sjajni završni sloj"
 * @param reasonHr short Croatian explanation shown to the user, e.g. "Završni sloj nije bio naveden u opisu."
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Assumption(
        String field,
        String assumed,
        String labelHr,
        String reasonHr
) {
    public Assumption {
        field = field == null ? "" : field.trim();
        assumed = assumed == null ? "" : assumed.trim();
        labelHr = labelHr == null || labelHr.isBlank() ? "" : labelHr.trim();
        reasonHr = reasonHr == null ? "" : reasonHr.trim();
    }

    /** The full form: a machine value plus the Croatian phrase that is actually shown. */
    public static Assumption of(String field, String assumed, String labelHr, String reasonHr) {
        return new Assumption(field, assumed, labelHr, reasonHr);
    }

    /** What the UI should print. Falls back through assumed to field so nothing ever renders empty. */
    public String displayHr() {
        if (!labelHr.isBlank()) return labelHr;
        if (!assumed.isBlank()) return assumed;
        return field;
    }
}
