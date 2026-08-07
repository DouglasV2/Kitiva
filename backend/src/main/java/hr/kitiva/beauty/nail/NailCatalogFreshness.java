package hr.kitiva.beauty.nail;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * How old a captured price is, and therefore what the app is allowed to SAY about it.
 *
 * <p><strong>Why this exists.</strong> "We read this from the retailer" and "this is true right now" are
 * different claims, and the catalog only ever supports the first. A machine-captured price is evidence of
 * what the shelf said on a particular day; a fortnight later it is a lead, not a fact. The kit was already
 * careful never to invent a product — this is the same care applied to time, so that nothing is presented
 * as currently verified when it was captured long ago or when the source cannot be reached to re-check.</p>
 *
 * <p><strong>What this deliberately does NOT do.</strong> Freshness never changes whether a kit is
 * complete. A stale price is a disclosure; a missing product is a gap. Conflating them would let the clock
 * quietly turn a working kit into a broken one, and would make completeness untestable — the same
 * behaviour would pass today and fail in a fortnight.</p>
 */
public final class NailCatalogFreshness {

    private NailCatalogFreshness() { }

    /**
     * How long a captured price may still be called current.
     *
     * <p>14 days is chosen against the shelf this catalog actually reads: dm runs fortnightly promotions
     * and several press-on rows were captured on clearance, so a price older than one promotion cycle can
     * no longer be asserted. It is a disclosure threshold, not a safety one — nothing breaks at day 15,
     * the app simply stops claiming the price is current.</p>
     */
    public static final int FRESH_FOR_DAYS = 14;

    /** What we may honestly say about a row's price and availability. */
    public enum State {
        /** Read from a reachable source, recently enough to still be asserted. */
        VERIFIED,
        /** Read from a reachable source, but too long ago to still be called current. */
        STALE,
        /** The source could not be read on the last run, so nothing about it can be re-confirmed at all. */
        UNVERIFIABLE
    }

    /** Source reachability, as recorded by the capture run. Anything unrecognised fails closed. */
    public static boolean sourceReachable(String sourceStatus) {
        return "reachable".equalsIgnoreCase(sourceStatus == null ? "" : sourceStatus.trim());
    }

    /**
     * The state of one row as of {@code now}.
     *
     * <p>Fails closed in both directions: a row with no {@code lastVerifiedAt} and a row whose source was
     * blocked are both {@link State#UNVERIFIABLE}, because "we don't know" is not "it's fine".</p>
     */
    public static State stateOf(NailPilotCatalog.PilotProduct product, Instant now) {
        if (product == null) return State.UNVERIFIABLE;
        if (!sourceReachable(product.sourceStatus())) return State.UNVERIFIABLE;

        Instant verified = verifiedAt(product);
        if (verified == null) return State.UNVERIFIABLE;
        // A capture timestamp in the future is a broken clock somewhere, not freshness. Treat it as unknown.
        if (verified.isAfter(now)) return State.UNVERIFIABLE;

        return Duration.between(verified, now).toDays() > FRESH_FOR_DAYS ? State.STALE : State.VERIFIED;
    }

    /**
     * When this row was last actually read from its source. Prefers the full instant; falls back to the
     * capture date the earlier pilot format recorded. Null when the row claims neither.
     */
    public static Instant verifiedAt(NailPilotCatalog.PilotProduct product) {
        if (product == null) return null;
        Instant fromInstant = parseInstant(product.lastVerifiedAt());
        if (fromInstant != null) return fromInstant;
        return parseDate(product.verifiedAt());
    }

    /**
     * The Croatian sentence the kit shows for a row that cannot be called current, or null when it can.
     *
     * <p>The date is always named. "Provjeri kod trgovca" on its own tells the user to do work without
     * telling her why, and a reader who can see the capture date can judge for herself how much the number
     * is worth.</p>
     */
    public static String noteHrFor(NailPilotCatalog.PilotProduct product, Instant now) {
        State state = stateOf(product, now);
        if (state == State.VERIFIED) return null;
        String when = formatHr(verifiedAt(product));
        if (state == State.UNVERIFIABLE) {
            // The Croatian date already ends in a full stop ("3. 8. 2026."), so the sentence has to break
            // there rather than add another mark next to it.
            return when == null
                    ? "Cijenu i dostupnost trenutačno ne možemo provjeriti kod ovog trgovca — provjeri ih sama."
                    : "Cijena je zadnji put provjerena " + when + " Izvor trenutačno nije dostupan pa je "
                      + "ne možemo potvrditi — provjeri kod trgovca.";
        }
        return "Cijena je zadnji put provjerena " + when + " i više nije svježa — provjeri kod trgovca.";
    }

    /** dd. MM. yyyy. — how a date is written in Croatian. Null in, null out. */
    public static String formatHr(Instant instant) {
        if (instant == null) return null;
        return DateTimeFormatter.ofPattern("d. M. yyyy.", new Locale("hr"))
                .format(instant.atZone(ZoneOffset.UTC).toLocalDate());
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static Instant parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
