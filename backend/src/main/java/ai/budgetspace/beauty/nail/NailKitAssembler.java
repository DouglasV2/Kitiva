package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.Assumption;
import ai.budgetspace.beauty.dto.KitStatus;
import ai.budgetspace.beauty.dto.NailLookBriefDto;
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

    /** Graph for a system key. */
    public static List<Slot> graphFor(String system) {
        return "press-on".equalsIgnoreCase(system) ? PRESS_ON_GRAPH : REGULAR_POLISH_GRAPH;
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
            String catalogProvenanceHr
    ) { }

    private final NailPilotCatalog catalog;

    public NailKitAssembler(NailPilotCatalog catalog) {
        this.catalog = catalog;
    }

    public ValidatedKit assemble(NailLookBriefDto brief) {
        return assemble(brief, Preferences.none());
    }

    public ValidatedKit assemble(NailLookBriefDto brief, Preferences prefs) {
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
            assumptions.add(Assumption.of("system", "press-on",
                    "Tražena duljina se ne postiže na prirodnom noktu, pa je predložen set umjetnih noktiju "
                    + "umjesto sustava za nadogradnju."));
        }

        List<String> ownedSlots = brief.homeProfile() == null ? List.of() : brief.homeProfile().ownedSlots();
        List<String> explicitlyMissing = brief.homeProfile() == null
                ? List.of() : brief.homeProfile().explicitlyMissingSlots();

        for (Slot slot : graphFor(system)) {
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
                        "Rečeno je da već imaš ovo, pa nije uračunato. Ako nije kompatibilno, zamijeni ga."));
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
                        "Odabrana je nijansa kao prijedlog; boja nije strojno provjerljiva kod ovog trgovca."));
            }
            if (pick.stockUnverified()) {
                notes.add("Trgovac ne objavljuje zalihu — provjeri dostupnost prije narudžbe.");
            }
            if ("press-on-set".equals(slot.key())) {
                if (pick.sizeInfo() == null || pick.sizeInfo().isBlank()) {
                    notes.add("Trgovac ne objavljuje broj noktiju ni raspon veličina za ovaj set.");
                    assumptions.add(Assumption.of("fit", "nepoznato",
                            "Veličine nisu objavljene za odabrani set. Provjeri sadržaj pakiranja — ako ne "
                            + "odgovara, zamijeni set."));
                } else {
                    assumptions.add(Assumption.of("fit", "objavljeno",
                            "Set objavljuje broj noktiju i raspon veličina, ali fit ovisi o tvom nokatu."));
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
            if (!covered) {
                // Is it missing from the KIT, or absent from the catalog entirely? The user can act on the
                // difference, so the message says which.
                boolean anywhere = catalog.products().stream()
                        .filter(NailPilotCatalog.PilotProduct::inStock)
                        .filter(NailPilotCatalog.PilotProduct::consumerSafe)
                        .anyMatch(p -> NailCapabilityEvidence.proves(p, capability));
                unreproducible.add(capability.croatianLabel()
                        + (anywhere ? " (postoji u katalogu, ali ne uz ostale odabrane proizvode)"
                                    : " (nema nijednog provjerenog proizvoda u katalogu)"));
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
                        "Izostavljeno da se uklopi u budžet — nije obavezno za rezultat."));
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
                List.copyOf(assumptions), safetyNotes(),
                "Pilot katalog: " + catalog.retailer() + ", strojno preuzet " + shortDate(catalog.capturedAt())
                + " s javnog product feeda. Cijene i dostupnost provjeri kod trgovca prije kupnje.");
    }

    private KitItem toItem(Slot slot, boolean required, NailPilotCatalog.PilotProduct p, String note, String system) {
        // Alternatives are drawn from the SAME slot and SAME system, so any swap still fills the same job
        // and completeness cannot silently break. Capped at three so the choice stays a choice, not a dump.
        List<Alternative> alts = catalog.availableForSlot(slot.key(), system).stream()
                .filter(c -> !c.externalId().equals(p.externalId()))
                .sorted((a, b) -> Integer.compare(a.priceCents(), b.priceCents()))
                .limit(3)
                .map(c -> new Alternative(c.externalId(), c.name(), c.shadeName(), c.retailer(),
                        c.priceCents(), c.priceCents() - p.priceCents(), c.productUrl(), c.swatchImageUrl()))
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
        String sys = system == null || system.isBlank() ? "regular-polish" : system;
        List<String> requiredSlots = graphFor(sys).stream().filter(Slot::required).map(Slot::key).toList();
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
