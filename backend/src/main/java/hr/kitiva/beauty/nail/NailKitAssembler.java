package hr.kitiva.beauty.nail;

import hr.kitiva.beauty.dto.Assumption;
import hr.kitiva.beauty.dto.KitStatus;
import hr.kitiva.beauty.dto.NailDesignSpecDto;
import hr.kitiva.beauty.dto.NailLookBriefDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vertical slice — builds the at-home nail kit from the pilot catalog.
 *
 * <p>Deterministic end to end: slots come from a fixed completeness graph, products come from the pilot
 * catalog, arithmetic is integer cents. Nothing here asks a model anything.</p>
 *
 * <p><strong>The rule this class exists to protect:</strong> a kit may only report {@link KitStatus#COMPLETE}
 * when every REQUIRED slot is filled by an in-stock product. Dropping a required item to fit a budget and
 * still calling the kit complete is the worst failure available to this product, so budget trimming removes
 * OPTIONAL items only, and a kit that still overruns is reported honestly as
 * {@link KitStatus#OVER_BUDGET} rather than quietly trimmed below completeness.</p>
 */
@Component
public class NailKitAssembler {

    /** One slot of the regular-polish completeness graph. */
    public record Slot(String key, String labelHr, boolean required, String whyHr) { }

    /**
     * The regular-polish graph. Prep is optional (a cuticle oil improves the result but no one is stuck
     * without it); base, colour and top are required because skipping any one of them is what makes a
     * home manicure chip in two days; removal is required because selling someone a product they cannot
     * take off again is not a complete kit.
     */
    public static final List<Slot> REGULAR_POLISH_GRAPH = List.of(
            new Slot("file", "Turpija", true, "Oblikovanje ruba nokta prije lakiranja."),
            new Slot("cuticle-care", "Njega zanoktice", false, "Poboljšava izgled, ali nije nužna za rezultat."),
            new Slot("base", "Bazni lak", true, "Štiti nokat i sprječava žućenje; bez njega boja se brže ljušti."),
            new Slot("color", "Lak u boji", true, "Sama boja."),
            new Slot("top", "Nadlak", true, "Daje sjaj i drži boju — bez njega manikura traje bitno kraće."),
            new Slot("removal", "Skidanje laka", true, "Kit bez sredstva za skidanje nije potpun."));

    /**
     * Press-on graph. Shorter than the polish graph because a press-on set is mostly self-contained — but
     * NOT because press-ons need less care: the adhesive and the removal step are exactly where a bad
     * at-home job damages a nail plate, so both are required.
     */
    public static final List<Slot> PRESS_ON_GRAPH = List.of(
            new Slot("press-on-set", "Set umjetnih noktiju", true, "Sami nokti, u odgovarajućem obliku i duljini."),
            new Slot("adhesive", "Ljepilo ili ljepljive pločice", true,
                    "Set ne mora sadržavati ljepilo; bez njega nokti ne drže."),
            new Slot("file", "Turpija", true, "Za prilagodbu duljine i oblika prije lijepljenja."),
            new Slot("removal", "Skidanje", true,
                    "Skidanje bez otapala kida površinu nokta — zato je sredstvo obavezno, ne opcionalno."),
            new Slot("cuticle-care", "Njega zanoktice", false, "Njega nakon skidanja; nije nužna za rezultat."));

    /**
     * The accent slot. Not part of either base graph, because most designs are one colour and a kit that
     * always billed for nail art would be padding the total.
     *
     * <p><strong>Why it has to exist at all.</strong> {@code NailDesignSpecDto.hasDistinctAccentColor()}
     * has always said "the kit must buy a second product for it" — and until now no slot could hold that
     * product. So "dva diskretna zlatna detalja" was unreproducible by construction: even with a gold
     * sticker sitting in the catalog, nothing in the kit could ever prove {@code GOLD_DETAIL}, and the
     * user was told the catalog had no gold product when the truth was that the kit had nowhere to put
     * one. Adding the slot does not weaken the capability gate — it gives the gate something to pass.</p>
     */
    public static final Slot ACCENT_SLOT = new Slot("accent", "Detalj (nail art)", true,
            "Naglasak u drugoj boji je zaseban proizvod — bez njega je manikura jednobojna.");

    /** Graph for a system key, with no design-driven slots. */
    public static List<Slot> graphFor(String system) {
        return graphFor(system, null);
    }

    /**
     * Graph for a system, plus the slots the DESIGN itself creates. A second accent colour is a second
     * purchase, so it is a slot only when the user actually asked for one.
     */
    public static List<Slot> graphFor(String system, NailDesignSpecDto design) {
        List<Slot> base = "press-on".equalsIgnoreCase(system) ? PRESS_ON_GRAPH : REGULAR_POLISH_GRAPH;
        if (design == null || !design.hasDistinctAccentColor()) return base;
        List<Slot> withAccent = new ArrayList<>(base);
        withAccent.add(ACCENT_SLOT);
        return List.copyOf(withAccent);
    }

    public record KitItem(
            String slot,
            String slotLabelHr,
            boolean essential,
            String externalId,
            String name,
            String shadeName,
            String retailer,
            int priceCents,
            String productUrl,
            String imageUrl,
            String swatchImageUrl,
            String whyHr,
            String noteHr,
            boolean ownedAlready,
            /**
             * Compatible swaps for THIS slot only. Same slot means same job in the kit, so swapping one in
             * can never break completeness - which is the difference between a real alternative and just a
             * cheaper unrelated product.
             */
            List<Alternative> alternatives
    ) {
        public KitItem {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }
    }

    /** One compatible swap a user can choose instead of the picked product. */
    public record Alternative(
            String externalId,
            String name,
            String shadeName,
            String retailer,
            int priceCents,
            int priceDeltaCents,
            String productUrl,
            /** The retailer's own product photo, so a swap is a picture-to-picture comparison. */
            String imageUrl,
            String swatchImageUrl
    ) { }

    /**
     * User refinements. Deliberately three narrow, named operations rather than a general framework - each
     * one has to preserve completeness and compatibility, and a generic "change anything" surface cannot
     * promise that.
     */
    public record Preferences(
            Map<String, String> pinnedBySlot,
            boolean preferCheapest,
            String singleRetailer,
            /** "regular-polish" | "press-on"; null = choose from the design. */
            String system
    ) {
        public Preferences {
            pinnedBySlot = pinnedBySlot == null ? Map.of() : Map.copyOf(pinnedBySlot);
        }

        public static Preferences none() {
            return new Preferences(Map.of(), false, null, null);
        }
    }

    public record ValidatedKit(
            KitStatus status,
            String statusLabelHr,
            String statusExplanationHr,
            List<KitItem> items,
            List<KitItem> ownedItems,
            List<String> missingRequiredSlots,
            int essentialTotalCents,
            int optionalTotalCents,
            int totalCents,
            Integer budgetCents,
            Integer remainingCents,
            int retailerCount,
            List<Assumption> assumptions,
            List<String> safetyNotesHr,
            /** Ordered prep steps for the chosen system. Named because a kit without prep damages a nail. */
            List<String> prepStepsHr,
            /** Ordered removal steps. Selling something a consumer cannot take off again is not a kit. */
            List<String> removalStepsHr,
            String catalogProvenanceHr,
            /**
             * One sentence about how current these prices are. Separate from {@link #assumptions()} on
             * purpose: assumptions are things about the DESIGN we guessed, and mixing "we assumed almond"
             * with "this price is a fortnight old" would blur two different kinds of doubt. Separate from
             * the status for the same reason — see {@link NailCatalogFreshness}.
             */
            String catalogFreshnessHr
    ) { }

    private final NailPilotCatalog catalog;
    private final java.time.Clock clock;

    // Explicit, because the Clock overload below makes two constructors and Spring will not guess between
    // them — it looks for a no-arg one instead and fails at startup, which no unit test would ever notice.
    @org.springframework.beans.factory.annotation.Autowired
    public NailKitAssembler(NailPilotCatalog catalog) {
        this(catalog, java.time.Clock.systemUTC());
    }

    /** Test seam. Freshness copy depends on today's date, and a test that depends on today rots. */
    NailKitAssembler(NailPilotCatalog catalog, java.time.Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    public ValidatedKit assemble(NailLookBriefDto brief) {
        return assemble(brief, Preferences.none());
    }

    public ValidatedKit assemble(NailLookBriefDto brief, Preferences prefs) {
        java.time.Instant now = clock.instant();
        List<Assumption> assumptions = new ArrayList<>(brief.assumptions());
        List<KitItem> items = new ArrayList<>();
        List<KitItem> owned = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        // System choice. Press-ons are the honest answer for a length the natural nail cannot carry: the
        // alternative would be an extension system we do not sell to consumers.
        String system = prefs.system() != null && !prefs.system().isBlank()
                ? prefs.system().trim().toLowerCase()
                : (brief.design() != null && brief.design().requiresExtension() ? "press-on" : "regular-polish");
        if (prefs.system() == null && "press-on".equals(system)) {
            assumptions.add(Assumption.of("system", "press-on", "set umjetnih noktiju",
                    "Tražena duljina se ne postiže na prirodnom noktu, pa je predložen set umjetnih noktiju "
                    + "umjesto sustava za nadogradnju."));
        }

        List<String> ownedSlots = brief.homeProfile() == null ? List.of() : brief.homeProfile().ownedSlots();
        List<String> explicitlyMissing = brief.homeProfile() == null
                ? List.of() : brief.homeProfile().explicitlyMissingSlots();

        for (Slot slot : graphFor(system, brief.design())) {
            boolean userOwnsIt = ownedSlots.contains(slot.key());
            // A stated absence overrides ownership AND makes an optional slot required — she told us she
            // needs one, and that is a stronger signal than our default.
            boolean statedMissing = explicitlyMissing.contains(slot.key());
            boolean required = slot.required() || statedMissing;

            if (userOwnsIt && !statedMissing) {
                owned.add(new KitItem(slot.key(), slot.labelHr(), required, null,
                        slot.labelHr(), null, null, 0, null, null, null,
                        slot.whyHr(), "Već imaš — izuzeto iz ukupnog iznosa.", true, List.of()));
                assumptions.add(Assumption.of(slot.key(), "owned",
                        slot.labelHr().toLowerCase() + " — već imaš",
                        "Označila si da ovo već imaš, pa nije uračunato u iznos. Ako nije kompatibilno s "
                        + "ostatkom kita, zamijeni ga."));
                continue;
            }

            NailPilotCatalog.PilotProduct pick = pickFor(slot, brief, prefs, system);
            if (pick == null) {
                if (required) missing.add(slot.labelHr());
                continue;
            }
            List<String> notes = new ArrayList<>();
            if (pick.shadeNeedsSwatchCheck()) {
                notes.add("Nijansa nije potvrđena — trgovac ne objavljuje naziv boje, samo broj i swatch. "
                        + "Provjeri swatch prije kupnje.");
                assumptions.add(Assumption.of("shade", pick.shadeName() == null ? "?" : pick.shadeName(),
                        "nijansa nije potvrđena",
                        "Trgovac ne objavljuje naziv boje, samo broj nijanse i swatch. Ova je odabrana kao "
                        + "prijedlog — pogledaj swatch prije kupnje."));
            }
            if (pick.stockUnverified()) {
                notes.add("Trgovac ne objavljuje zalihu — provjeri dostupnost prije narudžbe.");
            }
            // Age of the capture, said out loud. Never a reason to call the kit incomplete: a price we
            // cannot re-confirm is a disclosure, a product we do not have is a gap, and letting the clock
            // turn one into the other would make completeness depend on what day it is.
            String freshness = NailCatalogFreshness.noteHrFor(pick, now);
            if (freshness != null) notes.add(freshness);
            if ("press-on-set".equals(slot.key())) {
                if (pick.sizeInfo() == null || pick.sizeInfo().isBlank()) {
                    notes.add("Trgovac ne objavljuje broj noktiju ni raspon veličina za ovaj set.");
                    assumptions.add(Assumption.of("fit", "nepoznato", "veličine seta nisu objavljene",
                            "Trgovac ne objavljuje broj noktiju ni raspon veličina. Provjeri sadržaj "
                            + "pakiranja — ako ne odgovara tvojim noktima, zamijeni set."));
                } else {
                    assumptions.add(Assumption.of("fit", "objavljeno", "veličine seta su objavljene",
                            "Set objavljuje broj noktiju i raspon veličina, ali konačan fit ovisi o obliku "
                            + "tvojih noktiju."));
                }
            }
            items.add(toItem(slot, required, pick, notes.isEmpty() ? null : String.join(" ", notes), system));
        }

        // ---- Can this kit actually reproduce the LOOK, not just fill the categories? ----
        // Filling prep/colour/top/removal is necessary and nowhere near sufficient. A burgundy cat-eye
        // needs something that can make a cat-eye; without it the kit is a different manicure wearing the
        // right slot names. Every required capability must be PROVEN by a product in the kit.
        List<NailCapabilityEvidence.Capability> required = NailCapabilityEvidence.requiredBy(brief.design(), system);
        List<String> unreproducible = new ArrayList<>();
        for (NailCapabilityEvidence.Capability capability : required) {
            boolean covered = items.stream()
                    .map(i -> catalogProduct(i.externalId()))
                    .anyMatch(p -> NailCapabilityEvidence.proves(p, capability));
            if (covered) continue;

            // Three distinct situations, three different things the user can do about them. Saying only
            // "missing" would leave her guessing which — switch system, swap a product, or give up on it.
            List<NailPilotCatalog.PilotProduct> offerable = catalog.products().stream()
                    .filter(NailPilotCatalog.PilotProduct::inStock)
                    .filter(NailPilotCatalog.PilotProduct::consumerSafe)
                    .filter(p -> NailCapabilityEvidence.proves(p, capability))
                    .toList();
            boolean inThisSystem = offerable.stream().anyMatch(p -> compatibleSystem(p, system));
            boolean inOtherSystem = offerable.stream().anyMatch(p -> !compatibleSystem(p, system));

            if (inThisSystem) {
                unreproducible.add(capability.croatianLabel()
                        + " (postoji u katalogu, ali ne uz ostale odabrane proizvode)");
            } else if (inOtherSystem) {
                unreproducible.add(capability.croatianLabel() + " (postoji samo u "
                        + otherSystemLabel(system) + ", ne u sustavu koji je odabran)");
            } else {
                unreproducible.add(capability.croatianLabel()
                        + " (nema nijednog provjerenog proizvoda u katalogu)");
            }
        }
        missing.addAll(unreproducible);

        int essentialTotal = items.stream().filter(KitItem::essential).mapToInt(KitItem::priceCents).sum();
        int optionalTotal = items.stream().filter(i -> !i.essential()).mapToInt(KitItem::priceCents).sum();
        int total = essentialTotal + optionalTotal;

        Integer budget = brief.hasBudget() ? brief.budgetCents() : null;

        // Budget repair: drop OPTIONAL items only, most expensive first. A required item is never dropped,
        // because a kit missing one is not a cheaper kit — it is an incomplete one.
        if (budget != null && total > budget) {
            List<KitItem> optional = new ArrayList<>(items.stream().filter(i -> !i.essential()).toList());
            optional.sort((a, b) -> Integer.compare(b.priceCents(), a.priceCents()));
            for (KitItem drop : optional) {
                if (total <= budget) break;
                items.remove(drop);
                total -= drop.priceCents();
                optionalTotal -= drop.priceCents();
                assumptions.add(Assumption.of(drop.slot(), "removed",
                        drop.slotLabelHr().toLowerCase() + " — izostavljen zbog budžeta",
                        "Izostavljen je da se kit uklopi u zadani budžet. Nije obavezan za rezultat."));
            }
        }

        KitStatus status;
        String explanation;
        if (!unreproducible.isEmpty()) {
            status = KitStatus.INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE;
            explanation = "Ovaj izgled ne možemo vjerno složiti od provjerenih proizvoda. Nedostaje: "
                    + String.join("; ", unreproducible)
                    + ". Umjesto da ponudimo obični lak i to nazovemo istim izgledom, radije kažemo što fali.";
        } else if (!missing.isEmpty()) {
            status = KitStatus.INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE;
            explanation = "Nedostaje obavezan dio kita: " + String.join(", ", missing)
                    + ". U pilot katalogu trenutačno nema dostupnog proizvoda za taj korak.";
        } else if (budget != null && total > budget) {
            status = KitStatus.OVER_BUDGET;
            explanation = "Svi obavezni dijelovi su pronađeni, ali ni s najjeftinijim izborom kit ne stane "
                    + "u zadani budžet.";
        } else if (!assumptions.isEmpty()) {
            status = KitStatus.COMPLETE_WITH_ASSUMPTIONS;
            explanation = "Kit je potpun za regular-polish manikuru. Neke stvari su pretpostavljene — "
                    + "provjeri ih ispod i po potrebi izmijeni.";
        } else {
            status = KitStatus.COMPLETE;
            explanation = "Kit je potpun za regular-polish manikuru.";
        }

        long retailers = items.stream().map(KitItem::retailer).filter(java.util.Objects::nonNull).distinct().count();

        return new ValidatedKit(status, status.croatianLabel(), explanation, List.copyOf(items),
                List.copyOf(owned), List.copyOf(missing), essentialTotal, optionalTotal, total,
                budget, budget == null ? null : budget - total, (int) retailers,
                List.copyOf(assumptions), safetyNotes(), prepSteps(system), removalSteps(system),
                "Pilot katalog: " + catalog.retailer() + ", strojno preuzet " + shortDate(catalog.capturedAt())
                + " s javnog product feeda. Cijene i dostupnost provjeri kod trgovca prije kupnje.",
                freshnessLine(items, now));
    }

    /**
     * The freshness sentence for the whole kit, counting rows rather than generalising from one.
     *
     * <p>Says "prices were read on X" rather than "prices are correct", and names how many of them we
     * currently cannot stand behind. Reporting only the best case would be the flattering summary; naming
     * the count is what lets someone decide whether to check before ordering.</p>
     */
    private String freshnessLine(List<KitItem> items, java.time.Instant now) {
        if (items.isEmpty()) return null;
        List<NailPilotCatalog.PilotProduct> picked = items.stream()
                .map(i -> catalogProduct(i.externalId()))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (picked.isEmpty()) return null;

        long unverifiable = picked.stream()
                .filter(p -> NailCatalogFreshness.stateOf(p, now) == NailCatalogFreshness.State.UNVERIFIABLE)
                .count();
        long stale = picked.stream()
                .filter(p -> NailCatalogFreshness.stateOf(p, now) == NailCatalogFreshness.State.STALE)
                .count();

        String newest = NailCatalogFreshness.formatHr(picked.stream()
                .map(NailCatalogFreshness::verifiedAt)
                .filter(java.util.Objects::nonNull)
                .max(java.time.Instant::compareTo).orElse(null));

        StringBuilder line = new StringBuilder();
        // `newest` already ends in a full stop — Croatian dates are written "3. 8. 2026." — so nothing is
        // appended after it.
        line.append(newest == null
                ? "Cijene su strojno preuzete s javnih feedova trgovaca."
                : "Cijene su strojno preuzete s javnih feedova trgovaca, zadnji put " + newest);
        if (unverifiable > 0) {
            line.append(" Za ").append(unverifiable).append(unverifiable == 1 ? " proizvod" : " proizvoda")
                .append(" izvor trenutačno nije dostupan, pa cijenu ne možemo potvrditi.");
        }
        if (stale > 0) {
            line.append(" Za ").append(stale).append(stale == 1 ? " proizvod" : " proizvoda")
                .append(" provjera je starija od ").append(NailCatalogFreshness.FRESH_FOR_DAYS)
                .append(" dana.");
        }
        line.append(" Nijedna cijena nije ručno provjerena — potvrdi je kod trgovca prije kupnje.");
        return line.toString();
    }

    /** True when this product may be used in the chosen system ("both" rows always qualify). */
    private boolean compatibleSystem(NailPilotCatalog.PilotProduct product, String system) {
        String rowSystem = product.nailSystem();
        return rowSystem == null || rowSystem.isBlank() || "both".equalsIgnoreCase(rowSystem)
                || rowSystem.equalsIgnoreCase(system);
    }

    private String otherSystemLabel(String system) {
        return "press-on".equalsIgnoreCase(system) ? "klasičnom laku" : "setovima umjetnih noktiju";
    }

    /**
     * Prep, spelled out. The kit lists a file; it does not tell anyone what to do with it, and "prep" is
     * exactly where an at-home manicure either lasts a week or lifts in two days.
     */
    private List<String> prepSteps(String system) {
        if ("press-on".equalsIgnoreCase(system)) {
            return List.of(
                    "Skrati i oblikuj prirodni nokat turpijom; ostavi ga kratkim da ploča dobro sjedne.",
                    "Odmakni zanoktice, ne režite ih.",
                    "Odmasti nokat (aceton ili alkohol) i pusti da se posve osuši.",
                    "Isprobaj veličine na suho prije lijepljenja i rasporedi ploče po prstima.");
        }
        return List.of(
                "Skini stari lak i oblikuj rub turpijom u jednom smjeru.",
                "Odmakni zanoktice, ne režite ih.",
                "Odmasti nokat i pusti da se posve osuši — lak na masnom noktu se ljušti.",
                "Nanesi bazni lak u jednom tankom sloju i pusti da se sasuši.");
    }

    /** Removal, spelled out. This is the step at which a bad at-home job damages the nail plate. */
    private List<String> removalSteps(String system) {
        if ("press-on".equalsIgnoreCase(system)) {
            return List.of(
                    "Nikad ne skidaj ploče na silu — time se kida površina prirodnog nokta.",
                    "Namoči nokte u toploj vodi s malo sapuna ili primijeni sredstvo za skidanje.",
                    "Kad se ljepilo otpusti, ploču nježno podigni s ruba prema vrhu.",
                    "Nakon skidanja nanesi ulje za zanoktice i ostavi nokte bez laka barem dan.");
        }
        return List.of(
                "Namoči vatu sredstvom za skidanje i pritisni na nokat 10–15 sekundi.",
                "Obriši u jednom potezu od zanoktice prema vrhu; ne trljaj naprijed-nazad.",
                "Ispiri ruke i nanesi ulje za zanoktice — aceton isušuje.",
                "Ako je nokat mekan ili osjetljiv, ostavi ga bez laka nekoliko dana.");
    }

    private KitItem toItem(Slot slot, boolean required, NailPilotCatalog.PilotProduct p, String note, String system) {
        // Alternatives are drawn from the SAME slot and SAME system, so any swap still fills the same job
        // and completeness cannot silently break. Capped at three so the choice stays a choice, not a dump.
        List<Alternative> alts = catalog.availableForSlot(slot.key(), system).stream()
                .filter(c -> !c.externalId().equals(p.externalId()))
                .sorted((a, b) -> Integer.compare(a.priceCents(), b.priceCents()))
                .limit(3)
                .map(c -> new Alternative(c.externalId(), c.name(), c.shadeName(), c.retailer(),
                        c.priceCents(), c.priceCents() - p.priceCents(), c.productUrl(),
                        c.imageUrl(), c.swatchImageUrl()))
                .toList();
        return new KitItem(slot.key(), slot.labelHr(), required, p.externalId(), p.name(), p.shadeName(),
                p.retailer(), p.priceCents(), p.productUrl(), p.imageUrl(), p.swatchImageUrl(),
                slot.whyHr(), note, false, alts);
    }

    /**
     * Picks a product for one slot. Out-of-stock rows are never picked, only reported as absent.
     *
     * <p>Preference order: a product the user pinned via "Replace this" wins outright; then a
     * single-retailer constraint filters the pool; then colour matching for the colour slot; then price.</p>
     */
    private NailPilotCatalog.PilotProduct pickFor(Slot slot, NailLookBriefDto brief, Preferences prefs, String system) {
        List<NailPilotCatalog.PilotProduct> candidates = catalog.availableForSlot(slot.key(), system);

        // "Use one store": narrow to that retailer ONLY if it can still fill the slot. Narrowing a slot to
        // nothing would turn a complete kit into an incomplete one, which is not what the user asked for.
        if (prefs.singleRetailer() != null && !prefs.singleRetailer().isBlank()) {
            List<NailPilotCatalog.PilotProduct> narrowed = candidates.stream()
                    .filter(c -> prefs.singleRetailer().equalsIgnoreCase(c.retailer())).toList();
            if (!narrowed.isEmpty()) candidates = narrowed;
        }
        // The accent slot answers ONE question: what goes on the accent nail, in the accent's own colour.
        // A gold sticker is not an answer to "a red detail", so a candidate that cannot prove the colour
        // asked for is not a candidate at all. Leaving the slot empty and reporting it is the honest
        // outcome; filling it with the only decoration we happen to stock would be the fabrication this
        // whole layer exists to prevent.
        if ("accent".equals(slot.key())) {
            NailCapabilityEvidence.Capability wanted = brief.design() == null ? null
                    : NailCapabilityEvidence.accentCapability(brief.design().accentColorKey());
            if (wanted == null) return null;
            candidates = candidates.stream().filter(c -> NailCapabilityEvidence.proves(c, wanted)).toList();
        }

        if (candidates.isEmpty()) return null;

        // "Replace this": an explicit pin always wins, provided it is still a valid product for this slot.
        String pinned = prefs.pinnedBySlot().get(slot.key());
        if (pinned != null) {
            var match = candidates.stream().filter(c -> c.externalId().equals(pinned)).findFirst();
            if (match.isPresent()) return match.get();
        }

        // Prefer a product that PROVES the most of what the design needs. Price only breaks ties. Picking
        // the cheapest set first would have skipped the one whose retailer title actually says "Cat Eye",
        // and then reported the look as unreproducible when the catalog could in fact partly cover it.
        List<NailCapabilityEvidence.Capability> required =
                NailCapabilityEvidence.requiredBy(brief.design(), system);
        if (!required.isEmpty()) {
            var best = candidates.stream()
                    .max(java.util.Comparator
                            .<NailPilotCatalog.PilotProduct>comparingLong(
                                    p -> required.stream().filter(c -> NailCapabilityEvidence.proves(p, c)).count())
                            .thenComparing(p -> -p.priceCents()));
            if (best.isPresent()
                    && required.stream().anyMatch(c -> NailCapabilityEvidence.proves(best.get(), c))) {
                return best.get();
            }
        }

        if (!"color".equals(slot.key())) {
            return cheapest(candidates);
        }

        // "Make it cheaper" skips colour matching for the colour slot; otherwise prefer a shade whose colour
        // the retailer actually NAMES and which matches the request. In this pilot no shade qualifies (all
        // numbered), so this falls through to price — and the caller records a shade assumption rather than
        // claiming a match.
        if (!prefs.preferCheapest()) {
            String wanted = brief.design() == null ? "" : brief.design().baseColorKey();
            var matched = candidates.stream()
                    .filter(p -> Boolean.TRUE.equals(p.shadeColorKnown()) && wanted.equalsIgnoreCase(p.colorFamily()))
                    .findFirst();
            if (matched.isPresent()) return matched.get();
        }
        return cheapest(candidates);
    }

    /** Looks a picked item back up in the catalog so its retailer-proven capabilities can be read. */
    private NailPilotCatalog.PilotProduct catalogProduct(String externalId) {
        if (externalId == null) return null;
        return catalog.products().stream()
                .filter(p -> externalId.equals(p.externalId())).findFirst().orElse(null);
    }

    private NailPilotCatalog.PilotProduct cheapest(List<NailPilotCatalog.PilotProduct> candidates) {
        return candidates.stream().min((a, b) -> Integer.compare(a.priceCents(), b.priceCents())).orElse(null);
    }

    /**
     * Retailers that can fill EVERY required slot on their own — the only honest "use one store" options.
     *
     * <p>Offering a store that cannot complete the kit would turn a working kit into an incomplete one,
     * which is not what "fewer deliveries" means to anyone. In this pilot the answer is usually empty:
     * polish colour lives at one retailer and removal at another, and saying so is better than pretending.</p>
     */
    public List<String> singleStoreOptions(String system) {
        return singleStoreOptions(system, null);
    }

    /**
     * As above, for a specific design. The design matters: a gold accent adds a slot that only one retailer
     * in the pilot can fill, so a store that stocks every base slot still cannot finish THAT kit on its own.
     * Offering it anyway would be a promise the kit then quietly breaks by sourcing the accent elsewhere.
     */
    public List<String> singleStoreOptions(String system, NailDesignSpecDto design) {
        String sys = system == null || system.isBlank() ? "regular-polish" : system;
        List<String> requiredSlots = graphFor(sys, design).stream()
                .filter(Slot::required).map(Slot::key).toList();
        return catalog.products().stream()
                .filter(NailPilotCatalog.PilotProduct::inStock)
                .map(NailPilotCatalog.PilotProduct::retailer)
                .distinct()
                .filter(r -> requiredSlots.stream().allMatch(s -> catalog.availableForSlot(s, sys).stream()
                        .anyMatch(p -> r.equalsIgnoreCase(p.retailer()))))
                .toList();
    }

    private List<String> safetyNotes() {
        return List.of(
                "Ovo je klasični lak za nokte — ne zahtijeva lampu ni stvrdnjavanje.",
                "Ne nanosi lak na oštećen, upaljen ili bolan nokat.",
                "Radi u prozračenoj prostoriji i izbjegavaj dodir laka s kožom oko nokta.",
                "Aplikacija nije zamjena za savjet stručne osobe.");
    }

    private static Map<String, String> monthCache = new LinkedHashMap<>();

    private String shortDate(String iso) {
        return iso == null || iso.length() < 10 ? "" : iso.substring(0, 10);
    }
}
