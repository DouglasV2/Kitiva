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
            new Slot("prep", "Priprema", false, "Njega zanoktice — poboljšava rezultat, ali nije nužna."),
            new Slot("base", "Bazni lak", true, "Štiti nokat i sprječava žućenje; bez njega boja se brže ljušti."),
            new Slot("color", "Lak u boji", true, "Sama boja."),
            new Slot("top", "Nadlak", true, "Daje sjaj i drži boju — bez njega manikura traje bitno kraće."),
            new Slot("removal", "Skidanje", true, "Kit bez sredstva za skidanje nije potpun."),
            new Slot("finish-aid", "Brzo sušenje", false, "Ubrzava sušenje; praktično, ali opcionalno."));

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
            boolean ownedAlready
    ) { }

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
        List<Assumption> assumptions = new ArrayList<>(brief.assumptions());
        List<KitItem> items = new ArrayList<>();
        List<KitItem> owned = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        List<String> ownedSlots = brief.homeProfile() == null ? List.of() : brief.homeProfile().ownedSlots();
        List<String> explicitlyMissing = brief.homeProfile() == null
                ? List.of() : brief.homeProfile().explicitlyMissingSlots();

        for (Slot slot : REGULAR_POLISH_GRAPH) {
            boolean userOwnsIt = ownedSlots.contains(slot.key());
            // A stated absence overrides ownership AND makes an optional slot required — she told us she
            // needs one, and that is a stronger signal than our default.
            boolean statedMissing = explicitlyMissing.contains(slot.key());
            boolean required = slot.required() || statedMissing;

            if (userOwnsIt && !statedMissing) {
                owned.add(new KitItem(slot.key(), slot.labelHr(), required, null,
                        slot.labelHr(), null, null, 0, null, null, null,
                        slot.whyHr(), "Već imaš — izuzeto iz ukupnog iznosa.", true));
                assumptions.add(Assumption.of(slot.key(), "owned",
                        "Rečeno je da već imaš ovo, pa nije uračunato. Ako nije kompatibilno, zamijeni ga."));
                continue;
            }

            NailPilotCatalog.PilotProduct pick = pickFor(slot, brief);
            if (pick == null) {
                if (required) missing.add(slot.labelHr());
                continue;
            }
            String note = null;
            if (pick.shadeNeedsSwatchCheck()) {
                note = "Nijansa nije potvrđena — trgovac ne objavljuje naziv boje, samo broj i swatch. "
                     + "Provjeri swatch prije kupnje.";
                assumptions.add(Assumption.of("shade", pick.shadeName() == null ? "?" : pick.shadeName(),
                        "Odabrana je nijansa kao prijedlog; boja nije strojno provjerljiva kod ovog trgovca."));
            }
            items.add(toItem(slot, required, pick, note));
        }

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
        if (!missing.isEmpty()) {
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

    private KitItem toItem(Slot slot, boolean required, NailPilotCatalog.PilotProduct p, String note) {
        return new KitItem(slot.key(), slot.labelHr(), required, p.externalId(), p.name(), p.shadeName(),
                p.retailer(), p.priceCents(), p.productUrl(), p.imageUrl(), p.swatchImageUrl(),
                slot.whyHr(), note, false);
    }

    /** Cheapest in-stock product for a slot. Out-of-stock rows are never picked, only reported as absent. */
    private NailPilotCatalog.PilotProduct pickFor(Slot slot, NailLookBriefDto brief) {
        List<NailPilotCatalog.PilotProduct> candidates = catalog.availableForSlot(slot.key());
        if (candidates.isEmpty()) return null;
        if (!"color".equals(slot.key())) {
            return candidates.stream().min((a, b) -> Integer.compare(a.priceCents(), b.priceCents())).orElse(null);
        }
        String wanted = brief.design() == null ? "" : brief.design().baseColorKey();
        // Prefer a shade whose colour the retailer actually names and which matches the request. In this
        // pilot no shade qualifies (all numbered), so this falls through to the cheapest candidate — and
        // the caller records a shade assumption rather than claiming a match.
        return candidates.stream()
                .filter(p -> Boolean.TRUE.equals(p.shadeColorKnown()) && wanted.equalsIgnoreCase(p.colorFamily()))
                .findFirst()
                .orElseGet(() -> candidates.stream()
                        .min((a, b) -> Integer.compare(a.priceCents(), b.priceCents())).orElse(null));
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
