package ai.budgetspace.beauty.makeup;

import ai.budgetspace.beauty.dto.Assumption;
import ai.budgetspace.beauty.dto.BeautyBriefDto;
import ai.budgetspace.beauty.dto.KitStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a makeup kit for a named look from the pilot catalog.
 *
 * <p>Deterministic end to end, exactly like the nail assembler: slots come from the look's graph, products
 * come from the catalog, arithmetic is integer cents, nothing asks a model anything.</p>
 *
 * <p><strong>The rule this class protects,</strong> inherited from
 * {@link ai.budgetspace.beauty.nail.NailKitAssembler} because it cost a rewrite to learn there: a kit may
 * only report {@link KitStatus#COMPLETE} when every REQUIRED slot is filled by an in-stock product.
 * Budget trimming removes OPTIONAL items only, and a kit that still overruns is reported honestly as
 * {@link KitStatus#OVER_BUDGET} rather than quietly trimmed below completeness.</p>
 *
 * <p><strong>What is deliberately NOT gated.</strong> The nail vertical refuses to call a kit complete
 * unless a product can be PROVEN to make the requested effect — a cat-eye needs a magnetic polish, and no
 * amount of ordinary lacquer substitutes. Makeup has no equivalent hard capability: "soft glam" is a set
 * of categories and a technique, not a property a bottle either has or lacks. So look fit is expressed as
 * ranking preferences and as assumptions, never as a completeness claim. The one place that discipline
 * does bite is shade: a foundation whose shades the retailer numbers rather than names is offered as a
 * candidate with a stated assumption, never as a match for anyone's skin.</p>
 */
@Component
public class MakeupKitAssembler {

    /** One line in the kit. Mirrors the nail vertical's KitItem so the frontend row renders both. */
    public record KitItem(
            String slot,
            String slotLabelHr,
            boolean essential,
            String externalId,
            String name,
            String brand,
            String shadeName,
            String retailer,
            int priceCents,
            String productUrl,
            String imageUrl,
            String swatchImageUrl,
            String whyHr,
            String noteHr,
            boolean ownedAlready,
            /** Compatible swaps from the SAME category, so a swap can never break completeness. */
            List<Alternative> alternatives
    ) {
        public KitItem {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }
    }

    public record Alternative(
            String externalId,
            String name,
            String brand,
            String shadeName,
            String retailer,
            int priceCents,
            int priceDeltaCents,
            String productUrl,
            String imageUrl
    ) { }

    /** Named refinements, same three the nail kit offers, for the same reason: each preserves the kit. */
    public record Preferences(
            Map<String, String> pinnedBySlot,
            boolean preferCheapest,
            String singleRetailer,
            /** Look key; null = read it from the brief. */
            String look
    ) {
        public Preferences {
            pinnedBySlot = pinnedBySlot == null ? Map.of() : Map.copyOf(pinnedBySlot);
        }
        public static Preferences none() {
            return new Preferences(Map.of(), false, null, null);
        }
    }

    public record ValidatedKit(
            String lookKey,
            String lookLabelHr,
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
            /** The order to actually apply them in — the kit doubles as the instructions. */
            List<String> applicationStepsHr,
            List<String> careNotesHr,
            String catalogProvenanceHr
    ) { }

    private final MakeupPilotCatalog catalog;

    public MakeupKitAssembler(MakeupPilotCatalog catalog) {
        this.catalog = catalog;
    }

    public ValidatedKit assemble(BeautyBriefDto brief) {
        return assemble(brief, Preferences.none());
    }

    public ValidatedKit assemble(BeautyBriefDto brief, Preferences prefs) {
        MakeupLook.Definition look = MakeupLook.byKeyOrDefault(
                prefs.look() != null && !prefs.look().isBlank() ? prefs.look() : brief.look());

        List<Assumption> assumptions = new ArrayList<>(brief.assumptions());
        if (brief.look() == null || brief.look().isBlank()) {
            assumptions.add(Assumption.of("look", look.key(), look.labelHr(),
                    "Look nije bio naveden, pa je pretpostavljen " + look.labelHr().toLowerCase()
                    + ". Promijeni ga gore ako želiš drugi."));
        }

        List<String> ownedSlots = brief.ownedSlots();
        List<String> explicitlyMissing = brief.explicitlyMissingSlots();
        List<KitItem> items = new ArrayList<>();
        List<KitItem> owned = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean shadeAssumptionRaised = false;

        for (MakeupLook.Slot slot : look.slots()) {
            if (brief.excludedSlots().contains(slot.category())) continue;

            MakeupPilotCatalog.Category category = catalog.category(slot.category());
            String label = category == null ? slot.category() : category.labelHr();

            // A stated absence overrides ownership AND promotes an optional slot to required — she told us
            // she needs one, which is a stronger signal than our default.
            boolean statedMissing = explicitlyMissing.contains(slot.category());
            boolean userOwnsIt = ownedSlots.contains(slot.category()) && !statedMissing;
            boolean required = slot.required() || statedMissing
                    || brief.requiredSlots().contains(slot.category());

            if (userOwnsIt) {
                owned.add(new KitItem(slot.category(), label, required, null, label, null, null, null, 0,
                        null, null, null, slot.whyHr(), "Već imaš — izuzeto iz ukupnog iznosa.", true, List.of()));
                assumptions.add(Assumption.of(slot.category(), "owned", label.toLowerCase() + " — već imaš",
                        "Označila si da ovo već imaš, pa nije uračunato u iznos. Ako ne odgovara ostatku "
                        + "kompleta, zamijeni ga."));
                continue;
            }

            MakeupPilotCatalog.MakeupProduct pick = pickFor(slot, look, brief, prefs);
            if (pick == null) {
                if (required) missing.add(label);
                continue;
            }

            List<String> notes = new ArrayList<>();
            if (pick.shadeNeedsSwatchCheck()) {
                notes.add("Trgovac numerira nijanse i ne objavljuje naziv boje — provjeri swatch prije kupnje.");
                if (!shadeAssumptionRaised) {
                    assumptions.add(Assumption.of("shade", "nepotvrđeno", "nijanse nisu potvrđene",
                            "Za dio proizvoda trgovac objavljuje samo broj nijanse i swatch, ne i naziv boje. "
                            + "Odabrane su kao prijedlog — usporedi swatch sa svojim tonom prije kupnje."));
                    shadeAssumptionRaised = true;
                }
            }
            if (pick.stockUnverified()) {
                notes.add("Trgovac ne objavljuje zalihu — provjeri dostupnost prije narudžbe.");
            }
            if (look.wantsFinishFor(slot.category()) && pick.finish() == null) {
                notes.add("Trgovac ne navodi završnicu za ovaj proizvod, pa nije potvrđeno da je "
                        + look.preferredFinish() + ".");
            }

            items.add(toItem(slot, label, required, pick, notes.isEmpty() ? null : String.join(" ", notes),
                    "lipstick".equals(slot.category()) ? look.preferredLipShades() : List.of()));
        }

        int essentialTotal = items.stream().filter(KitItem::essential).mapToInt(KitItem::priceCents).sum();
        int optionalTotal = items.stream().filter(i -> !i.essential()).mapToInt(KitItem::priceCents).sum();
        int total = essentialTotal + optionalTotal;
        Integer budget = brief.hasBudget() ? brief.budgetCents() : null;

        // Budget repair: OPTIONAL items only, most expensive first. A required item is never dropped,
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
                        "Izostavljen je da komplet stane u zadani budžet. Nije obavezan za ovaj look."));
            }
        }

        KitStatus status;
        String explanation;
        if (!missing.isEmpty()) {
            status = KitStatus.INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE;
            explanation = "Nedostaje obavezan dio kompleta: " + String.join(", ", missing)
                    + ". U pilot katalogu trenutačno nema dostupnog proizvoda za taj korak.";
        } else if (budget != null && total > budget) {
            status = KitStatus.OVER_BUDGET;
            explanation = "Svi obavezni dijelovi su pronađeni, ali ni s najjeftinijim izborom komplet ne "
                    + "stane u zadani budžet.";
        } else if (!assumptions.isEmpty()) {
            status = KitStatus.COMPLETE_WITH_ASSUMPTIONS;
            explanation = "Komplet za " + look.labelHr().toLowerCase() + " je potpun. Neke stvari su "
                    + "pretpostavljene — provjeri ih ispod i po potrebi izmijeni.";
        } else {
            status = KitStatus.COMPLETE;
            explanation = "Komplet za " + look.labelHr().toLowerCase() + " je potpun.";
        }

        long retailers = items.stream().map(KitItem::retailer).filter(java.util.Objects::nonNull)
                .distinct().count();

        return new ValidatedKit(look.key(), look.labelHr(), status, status.croatianLabel(), explanation,
                List.copyOf(items), List.copyOf(owned), List.copyOf(missing),
                essentialTotal, optionalTotal, total, budget, budget == null ? null : budget - total,
                (int) retailers, List.copyOf(assumptions), applicationSteps(items), careNotes(),
                "Pilot katalog: " + String.join(" + ", catalog.retailers()) + ", strojno preuzet "
                + shortDate(catalog.capturedAt()) + " s javnih product feedova. Cijene i dostupnost "
                + "provjeri kod trgovca prije kupnje. Nijedan proizvod nije ručno provjeren i nijedan "
                + "nema ocjenu — trgovci ih ne objavljuju.");
    }

    /**
     * Picks one product for one slot.
     *
     * <p>Precedence, copied from the nail assembler because the order is the part that took work to get
     * right: an explicit pin wins outright; then a single-retailer constraint, applied only if it leaves
     * the slot fillable; then the look's finish and shade preferences, but only where the retailer's own
     * words support them; then price.</p>
     */
    private MakeupPilotCatalog.MakeupProduct pickFor(MakeupLook.Slot slot, MakeupLook.Definition look,
                                                     BeautyBriefDto brief, Preferences prefs) {
        List<MakeupPilotCatalog.MakeupProduct> candidates = catalog.availableIn(slot.category());
        if (candidates.isEmpty()) return null;

        // "Use one store": narrow ONLY if that retailer can still fill the slot. Narrowing a slot to
        // nothing would turn a complete kit into an incomplete one, which is not what the user asked for.
        String singleRetailer = prefs.singleRetailer() != null && !prefs.singleRetailer().isBlank()
                ? prefs.singleRetailer()
                : (brief.singleRetailerOnly() && !catalog.retailers().isEmpty() ? null : null);
        if (singleRetailer != null) {
            List<MakeupPilotCatalog.MakeupProduct> narrowed = candidates.stream()
                    .filter(c -> singleRetailer.equalsIgnoreCase(c.retailer())).toList();
            if (!narrowed.isEmpty()) candidates = narrowed;
        }

        // An explicit pin always wins, provided it is still a valid product for this slot.
        String pinned = prefs.pinnedBySlot().get(slot.category());
        if (pinned != null) {
            var match = candidates.stream().filter(c -> c.externalId().equals(pinned)).findFirst();
            if (match.isPresent()) return match.get();
        }

        if (prefs.preferCheapest()) return cheapest(candidates);

        // Rank: look fit, then PRICE, then data quality.
        //
        // The order matters more than it looks. An earlier version folded "the retailer names its shades"
        // into the fit score, and a 27,95 EUR blush with named shades beat a 4,50 EUR one with numbered
        // shades — a two-point tidiness bonus outweighing a 23 EUR difference, on a slot where the look
        // expresses no preference at all. Nobody shopping for a blush would make that trade. So fit only
        // counts things the LOOK or the BRIEF actually asked for; everything else is a tie-break below
        // price, where it can help without ever costing money.
        Comparator<MakeupPilotCatalog.MakeupProduct> byFit = Comparator
                .comparingInt((MakeupPilotCatalog.MakeupProduct p) -> lookFitScore(p, slot, look, brief))
                .reversed()
                .thenComparingInt(MakeupPilotCatalog.MakeupProduct::priceCents)
                .thenComparing(p -> p.shadeCount() > 0 && p.shadeNeedsSwatchCheck())
                .thenComparing(p -> p.description() == null || p.description().isBlank())
                .thenComparing(MakeupPilotCatalog.MakeupProduct::externalId);
        return candidates.stream().min(byFit).orElse(null);
    }

    /**
     * How well the retailer's OWN published words match what this look or this brief ASKED FOR. Never a
     * quality judgement, and never a tidiness one: every point here traces to a string the retailer wrote
     * answering something the user or the look actually specified. When nothing was asked for, every
     * candidate scores zero and the slot is decided on price.
     */
    private int lookFitScore(MakeupPilotCatalog.MakeupProduct p, MakeupLook.Slot slot,
                             MakeupLook.Definition look, BeautyBriefDto brief) {
        int score = 0;
        if (look.wantsFinishFor(slot.category()) && p.hasFinish(look.preferredFinish())) score += 4;
        // The brief's own finish beats the look's default: she said it, we only guessed.
        if (!brief.finish().isBlank() && p.hasFinish(brief.finish())) score += 6;

        if ("lipstick".equals(slot.category())) {
            List<String> wanted = look.preferredLipShades();
            for (int i = 0; i < wanted.size(); i++) {
                if (p.hasShadeFamily(wanted.get(i))) { score += 5 - Math.min(i, 3); break; }
            }
        }
        if (!brief.brandPreferences().isEmpty() && p.brand() != null
                && brief.brandPreferences().stream().anyMatch(b -> p.brand().toLowerCase().contains(b.toLowerCase()))) {
            score += 3;
        }

        // The tools category holds brushes, sponges, false lashes and a pencil sharpener, and on price
        // alone the sharpener wins every time — which produced a kit whose single "tool" could not apply
        // anything. An applicator is what the other fifteen slots need in order to be usable, so it
        // outranks an accessory. This reads the retailer's own product title, not our opinion of it.
        if ("tools".equals(slot.category())) {
            String name = p.name().toLowerCase(Locale.ROOT);
            // Tiered, because "an applicator" is not specific enough: the cheapest applicator in the feed
            // is a retractable LIP brush, which is a fine thing to own and a poor only-tool. A blender or
            // a face brush is the one that can rescue every other slot in the kit.
            if (FACE_APPLICATOR.matcher(name).find()) score += 10;
            else if (APPLICATOR.matcher(name).find()) score += 6;
            else if (ACCESSORY.matcher(name).find()) score -= 4;
        }
        return score;
    }

    /** The tool that does the most work: it lays down and blends base products over the whole face. */
    private static final java.util.regex.Pattern FACE_APPLICATOR = java.util.regex.Pattern.compile(
            "sponge|spuzv|spužv|blender|blending|foundation brush|powder brush|face brush|kist za lice");
    /** Something you put product on the face with. */
    private static final java.util.regex.Pattern APPLICATOR = java.util.regex.Pattern.compile(
            "brush|kist|applicator|aplikator");
    /** Something in the same category that applies nothing. */
    private static final java.util.regex.Pattern ACCESSORY = java.util.regex.Pattern.compile(
            "sharpener|siljilo|šiljilo|eyelash|trepavic|pinceta|tweezer");

    private KitItem toItem(MakeupLook.Slot slot, String label, boolean required,
                           MakeupPilotCatalog.MakeupProduct p, String note, List<String> wantedFamilies) {
        // Alternatives come from the SAME category, so any swap still fills the same job and completeness
        // cannot silently break. Capped at four so the choice stays a choice, not a dump.
        List<Alternative> alts = catalog.availableIn(slot.category()).stream()
                .filter(c -> !c.externalId().equals(p.externalId()))
                .sorted(Comparator.comparingInt(MakeupPilotCatalog.MakeupProduct::priceCents))
                .limit(4)
                .map(c -> new Alternative(c.externalId(), c.name(), c.brand(), shadeToShow(c, wantedFamilies), c.retailer(),
                        c.priceCents(), c.priceCents() - p.priceCents(), c.productUrl(), c.imageUrl()))
                .toList();
        return new KitItem(slot.category(), label, required, p.externalId(), p.name(), p.brand(),
                shadeToShow(p, wantedFamilies), p.retailer(), p.priceCents(), p.productUrl(), p.imageUrl(),
                p.shades().isEmpty() ? null : p.shades().get(0).swatchImageUrl(),
                slot.whyHr(), note, false, alts);
    }

    /**
     * Which shade to put on the row.
     *
     * <p>It has to be one that answers the look, not merely the first one the retailer listed. Bold
     * evening picked a lipstick <em>because</em> it carries a red, then displayed "Warm Nude" from the same
     * product — a row that recommended the right bottle and named the wrong reason for it. Falls back to
     * any named shade, then to the first published one.</p>
     */
    private String shadeToShow(MakeupPilotCatalog.MakeupProduct p, List<String> wantedFamilies) {
        if (p.shades().isEmpty()) return null;
        for (String family : wantedFamilies) {
            var hit = p.shades().stream().filter(s -> family.equalsIgnoreCase(s.colorFamily())).findFirst();
            if (hit.isPresent()) return hit.get().name();
        }
        return p.namedShades().stream().findFirst()
                .map(MakeupPilotCatalog.Shade::name)
                .orElse(p.shades().get(0).name());
    }

    private MakeupPilotCatalog.MakeupProduct cheapest(List<MakeupPilotCatalog.MakeupProduct> candidates) {
        return candidates.stream()
                .min(Comparator.comparingInt(MakeupPilotCatalog.MakeupProduct::priceCents)).orElse(null);
    }

    /**
     * Retailers that can fill EVERY required slot of this look on their own — the only honest "use one
     * store" options. Offering a store that cannot finish the kit would trade completeness for convenience.
     */
    public List<String> singleStoreOptions(String lookKey) {
        MakeupLook.Definition look = MakeupLook.byKeyOrDefault(lookKey);
        List<String> requiredCategories = look.requiredSlots().stream()
                .map(MakeupLook.Slot::category).toList();
        return catalog.retailers().stream()
                .filter(r -> requiredCategories.stream().allMatch(c -> catalog.availableIn(c).stream()
                        .anyMatch(p -> r.equalsIgnoreCase(p.retailer()))))
                .toList();
    }

    /**
     * The running order. The kit is also the instructions, and the order is the part a beginner gets wrong:
     * powder before cream sets the cream in the wrong place, and setting spray before anything else does
     * nothing at all. Built from the items actually in THIS kit, so it never lists a step she cannot do.
     */
    private List<String> applicationSteps(List<KitItem> items) {
        List<String> order = List.of("primer", "foundation", "concealer", "powder", "bronzer", "blush",
                "highlighter", "eyeshadow", "eyeliner", "mascara", "brow", "lipliner", "lipstick",
                "setting-spray");
        Map<String, String> howHr = Map.ofEntries(
                Map.entry("primer", "Nanesi prajmer na čistu, hidratiziranu kožu i pričekaj minutu."),
                Map.entry("foundation", "Podlogu nanosi od sredine lica prema van, u tankim slojevima."),
                Map.entry("concealer", "Korektor samo ondje gdje treba, pa stopi rubove."),
                Map.entry("powder", "Puder na T-zonu i ispod oka; ostatak lica najčešće ne treba."),
                Map.entry("bronzer", "Bronzer u obliku slova 3 uz rub lica, pa dobro rastopi."),
                Map.entry("blush", "Rumenilo na jabučice, prema sljepoočnicama."),
                Map.entry("highlighter", "Highlighter na vrhove jagodica, luk usne i hrbat nosa."),
                Map.entry("eyeshadow", "Sjenilo: prvo prijelazni ton u pregib, pa tamniji uz trepavice."),
                Map.entry("eyeliner", "Liniju vuci uz sam korijen trepavica, u kratkim potezima."),
                Map.entry("mascara", "Maskaru nanosi cik-cak pokretom od korijena prema vrhu."),
                Map.entry("brow", "Obrvu popuni kratkim potezima u smjeru dlačica, pa je očešljaj."),
                Map.entry("lipliner", "Iscrtaj rub usne prije ruža — tako boja ostaje unutra."),
                Map.entry("lipstick", "Ruž nanesi od sredine prema kutovima."),
                Map.entry("setting-spray", "Fiksator na kraju, s udaljenosti od otprilike 30 cm."));

        List<String> steps = new ArrayList<>();
        for (String category : order) {
            boolean inKit = items.stream().anyMatch(i -> category.equals(i.slot()));
            if (inKit && howHr.containsKey(category)) steps.add(howHr.get(category));
        }
        if (items.stream().anyMatch(i -> "remover".equals(i.slot()))) {
            steps.add("Navečer skini sve — prvo oči, pa ostatak lica.");
        }
        return List.copyOf(steps);
    }

    private List<String> careNotes() {
        return List.of(
                "Ovo je preporuka kategorija, ne dijagnoza kože ni recenzija proizvoda.",
                "Nijansu podloge i korektora usporedi sa svojim tonom prije kupnje — swatch na ekranu nije "
                + "isto što i tvoja koža.",
                "Nijedan proizvod nema ocjenu jer je trgovci ne objavljuju; ne izmišljamo je.",
                "Ne nanosi šminku na oštećenu ili nadraženu kožu.",
                "Maskaru zamijeni otprilike svaka tri mjeseca — to je jedini proizvod s kratkim rokom nakon "
                + "otvaranja.");
    }

    private String shortDate(String iso) {
        return iso == null || iso.length() < 10 ? "" : iso.substring(0, 10);
    }
}
