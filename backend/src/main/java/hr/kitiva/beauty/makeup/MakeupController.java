package hr.kitiva.beauty.makeup;

import hr.kitiva.beauty.dto.BeautyBriefDto;
import hr.kitiva.beauty.dto.OwnedItemDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The makeup vertical's HTTP surface. Three endpoints, deliberately shaped like the nail ones so the two
 * halves of the product behave the same way:
 *
 * <ul>
 *   <li>{@code GET  /api/makeup/looks}   — the looks and the category vocabulary, for the picker</li>
 *   <li>{@code GET  /api/makeup/catalog} — the browsable catalog, filtered and searched server-side</li>
 *   <li>{@code POST /api/makeup/kit}     — brief + refinements in, a validated kit out</li>
 * </ul>
 *
 * <p>Filtering lives on the server rather than in the browser on purpose. 194 rows would fit in a client
 * filter today, and the moment the catalog grows the two implementations drift — a facet count computed
 * one way and a result set computed another is how a filter starts lying about how many things it found.
 * One implementation, one source of truth, and the counts are computed from the same predicate as the
 * results.</p>
 */
@RestController
@RequestMapping("/api/makeup")
public class MakeupController {

    private final MakeupPilotCatalog catalog;
    private final MakeupKitAssembler assembler;

    public MakeupController(MakeupPilotCatalog catalog, MakeupKitAssembler assembler) {
        this.catalog = catalog;
        this.assembler = assembler;
    }

    // ------------------------------------------------------------------------------------ looks

    public record LookSummary(String key, String labelHr, String taglineHr, String descriptionHr,
                              int requiredCount, int optionalCount, List<SlotSummary> slots,
                              Integer fromCents) { }

    public record SlotSummary(String category, String labelHr, boolean required, String whyHr,
                              int availableCount) { }

    public record LooksResponse(List<LookSummary> looks,
                                List<MakeupPilotCatalog.Category> categories,
                                List<String> retailers,
                                List<String> brands,
                                String catalogProvenanceHr) { }

    @GetMapping("/looks")
    public LooksResponse looks() {
        List<LookSummary> looks = MakeupLook.all().stream().map(look -> {
            List<SlotSummary> slots = look.slots().stream().map(s -> {
                var category = catalog.category(s.category());
                return new SlotSummary(s.category(),
                        category == null ? s.category() : category.labelHr(),
                        s.required(), s.whyHr(), catalog.availableIn(s.category()).size());
            }).toList();
            // "From" price: the cheapest way to buy every REQUIRED slot. A real floor computed from real
            // prices, so the picker can be honest about what a look costs before she commits to it.
            Integer from = look.requiredSlots().stream()
                    .map(s -> catalog.availableIn(s.category()).stream()
                            .mapToInt(MakeupPilotCatalog.MakeupProduct::priceCents).min().orElse(-1))
                    .reduce(0, (a, b) -> a < 0 || b < 0 ? -1 : a + b);
            return new LookSummary(look.key(), look.labelHr(), look.taglineHr(), look.descriptionHr(),
                    (int) look.slots().stream().filter(MakeupLook.Slot::required).count(),
                    (int) look.slots().stream().filter(s -> !s.required()).count(),
                    slots, from != null && from >= 0 ? from : null);
        }).toList();

        return new LooksResponse(looks, catalog.categories(), catalog.retailers(), catalog.brands(),
                provenance());
    }

    // ---------------------------------------------------------------------------------- catalog

    public record CatalogItem(String externalId, String name, String brand, String category,
                              String categoryLabelHr, String subcategoryHr, String description,
                              int priceCents, String priceBand, List<MakeupPilotCatalog.Shade> shades,
                              int shadeCount, String finish, String imageUrl, String productUrl,
                              String retailer, boolean inStock, boolean stockUnverified,
                              boolean shadeNeedsSwatchCheck, Double rating,
                              List<MakeupPilotCatalog.Tag> tags, String usedForHr) { }

    /** A filter value plus how many products it would return, computed from the same predicate. */
    public record Facet(String value, String labelHr, int count) { }

    public record CatalogResponse(List<CatalogItem> items, int total, int shown,
                                  List<Facet> categoryFacets, List<Facet> brandFacets,
                                  List<Facet> finishFacets, List<Facet> priceBandFacets,
                                  List<Facet> shadeFacets, List<Facet> retailerFacets,
                                  int minPriceCents, int maxPriceCents, String noResultsHintHr) { }

    /**
     * The browsable catalog.
     *
     * <p>Every parameter is optional and every one of them actually filters — there is no decorative
     * control here. {@code q} searches the retailer's own name, brand, description and shade names, which
     * is why a search for "bordo" finds a lipstick whose shade is named that.</p>
     */
    @GetMapping("/catalog")
    public CatalogResponse browse(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) List<String> brand,
            @RequestParam(required = false) List<String> finish,
            @RequestParam(required = false) List<String> priceBand,
            @RequestParam(required = false) List<String> shade,
            @RequestParam(required = false) List<String> retailer,
            @RequestParam(required = false) Integer maxPriceCents,
            @RequestParam(required = false) Integer minPriceCents,
            @RequestParam(required = false, defaultValue = "false") boolean inStockOnly,
            @RequestParam(required = false, defaultValue = "relevance") String sort,
            @RequestParam(required = false, defaultValue = "60") int limit) {

        List<MakeupPilotCatalog.MakeupProduct> all = catalog.products();

        // Facets are computed against everything EXCEPT their own dimension, so unticking is always
        // possible: a count that vanished the moment you filtered by it would strand the user.
        List<Facet> categoryFacets = facets(all, brand, finish, priceBand, shade, retailer, q, null,
                p -> List.of(p.category()), this::categoryLabel);
        List<Facet> brandFacets = facets(all, null, finish, priceBand, shade, retailer, q, category,
                p -> p.brand() == null || p.brand().isBlank() ? List.of() : List.of(p.brand()), b -> b);
        List<Facet> finishFacets = facets(all, brand, null, priceBand, shade, retailer, q, category,
                p -> p.finish() == null ? List.of() : List.of(p.finish()), MakeupController::finishLabel);
        List<Facet> bandFacets = facets(all, brand, finish, null, shade, retailer, q, category,
                p -> p.priceBand() == null ? List.of() : List.of(p.priceBand()), MakeupController::bandLabel);
        List<Facet> shadeFacets = facets(all, brand, finish, priceBand, null, retailer, q, category,
                MakeupController::shadeFamilies, MakeupController::shadeLabel);
        List<Facet> retailerFacets = facets(all, brand, finish, priceBand, shade, null, q, category,
                p -> p.retailer() == null ? List.of() : List.of(p.retailer()), r -> r);

        List<MakeupPilotCatalog.MakeupProduct> matched = all.stream()
                .filter(p -> matches(p, q, category, brand, finish, priceBand, shade, retailer,
                        minPriceCents, maxPriceCents, inStockOnly))
                .toList();

        Comparator<MakeupPilotCatalog.MakeupProduct> order = switch (sort) {
            case "price-asc" -> Comparator.comparingInt(MakeupPilotCatalog.MakeupProduct::priceCents);
            case "price-desc" -> Comparator.comparingInt(MakeupPilotCatalog.MakeupProduct::priceCents).reversed();
            case "name" -> Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT));
            // Relevance: in stock first, then the ones whose shades are actually named (a buyer can choose),
            // then price. Nothing here is a quality ranking, because we have no quality data.
            default -> Comparator
                    .comparing((MakeupPilotCatalog.MakeupProduct p) -> !p.inStock())
                    .thenComparing(p -> p.shadeCount() > 0 && p.shadeNeedsSwatchCheck())
                    .thenComparing(p -> p.description() == null || p.description().isBlank())
                    .thenComparingInt(MakeupPilotCatalog.MakeupProduct::priceCents);
        };

        List<CatalogItem> items = matched.stream().sorted(order)
                .limit(Math.max(1, Math.min(limit, 200)))
                .map(this::toCatalogItem).toList();

        int min = all.stream().mapToInt(MakeupPilotCatalog.MakeupProduct::priceCents).min().orElse(0);
        int max = all.stream().mapToInt(MakeupPilotCatalog.MakeupProduct::priceCents).max().orElse(0);

        return new CatalogResponse(items, matched.size(), items.size(), categoryFacets, brandFacets,
                finishFacets, bandFacets, shadeFacets, retailerFacets, min, max,
                matched.isEmpty() ? noResultsHint(q, category, finish, shade) : null);
    }

    /**
     * What to say when a filter combination finds nothing. Naming the likely culprit beats "0 rezultata",
     * which leaves the user to guess which of six controls to undo.
     */
    private String noResultsHint(String q, List<String> category, List<String> finish, List<String> shade) {
        if (shade != null && !shade.isEmpty()) {
            return "Nema proizvoda s tom nijansom. Većina trgovaca numerira nijanse umjesto da ih imenuje, "
                    + "pa filtar po boji pokriva samo one koje su stvarno imenovane.";
        }
        if (finish != null && !finish.isEmpty()) {
            return "Nema proizvoda s tom završnicom. Završnicu bilježimo samo kad je trgovac sam navede.";
        }
        if (q != null && !q.isBlank()) return "Nema rezultata za \"" + q + "\". Probaj kraći pojam.";
        if (category != null && !category.isEmpty()) return "U ovoj kategoriji nema proizvoda uz odabrane filtre.";
        return "Nema rezultata uz odabrane filtre.";
    }

    private boolean matches(MakeupPilotCatalog.MakeupProduct p, String q, List<String> category,
                            List<String> brand, List<String> finish, List<String> priceBand,
                            List<String> shade, List<String> retailer, Integer minCents, Integer maxCents,
                            boolean inStockOnly) {
        if (!in(category, p.category())) return false;
        if (!in(brand, p.brand())) return false;
        if (!in(finish, p.finish())) return false;
        if (!in(priceBand, p.priceBand())) return false;
        if (!in(retailer, p.retailer())) return false;
        if (shade != null && !shade.isEmpty()
                && shade.stream().noneMatch(p::hasShadeFamily)) return false;
        if (minCents != null && p.priceCents() < minCents) return false;
        if (maxCents != null && p.priceCents() > maxCents) return false;
        if (inStockOnly && !p.inStock()) return false;
        return matchesQuery(p, q);
    }

    /** Free text over everything the RETAILER published about the product, shade names included. */
    private boolean matchesQuery(MakeupPilotCatalog.MakeupProduct p, String q) {
        if (q == null || q.isBlank()) return true;
        String needle = fold(q.trim());
        StringBuilder hay = new StringBuilder()
                .append(fold(p.name())).append(' ')
                .append(fold(p.brand())).append(' ')
                .append(fold(p.description())).append(' ')
                .append(fold(p.subcategoryHr())).append(' ')
                .append(fold(categoryLabel(p.category())));
        for (MakeupPilotCatalog.Shade s : p.shades()) {
            hay.append(' ').append(fold(s.name()));
            // The Croatian colour word too, or the search box and the colour filter disagree: the facet
            // offers "bordo (3)" while typing "bordo" returns nothing, because the retailer named the
            // shade "Scarlet Red" and only our family mapping knows those are the same colour.
            if (s.colourNamed()) hay.append(' ').append(fold(shadeLabel(s.colorFamily())))
                    .append(' ').append(fold(s.colorFamily()));
        }
        if (p.finish() != null) hay.append(' ').append(fold(finishLabel(p.finish())))
                .append(' ').append(fold(p.finish()));
        for (String t : p.publishedTags()) hay.append(' ').append(fold(t));
        // Every whitespace-separated term must appear, so a second word narrows instead of widening.
        for (String term : needle.split("\\s+")) {
            if (!term.isEmpty() && hay.indexOf(term) < 0) return false;
        }
        return true;
    }

    private boolean in(List<String> selected, String value) {
        if (selected == null || selected.isEmpty()) return true;
        return value != null && selected.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }

    private List<Facet> facets(List<MakeupPilotCatalog.MakeupProduct> all,
                               List<String> brand, List<String> finish, List<String> priceBand,
                               List<String> shade, List<String> retailer, String q, List<String> category,
                               java.util.function.Function<MakeupPilotCatalog.MakeupProduct, List<String>> values,
                               java.util.function.Function<String, String> label) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (MakeupPilotCatalog.MakeupProduct p : all) {
            if (!matches(p, q, category, brand, finish, priceBand, shade, retailer, null, null, false)) continue;
            for (String v : values.apply(p)) {
                if (v == null || v.isBlank()) continue;
                counts.merge(v, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new Facet(e.getKey(), label.apply(e.getKey()), e.getValue()))
                .toList();
    }

    private static List<String> shadeFamilies(MakeupPilotCatalog.MakeupProduct p) {
        return p.shades().stream().map(MakeupPilotCatalog.Shade::colorFamily)
                .filter(f -> f != null && !f.isBlank()).distinct().toList();
    }

    private CatalogItem toCatalogItem(MakeupPilotCatalog.MakeupProduct p) {
        var category = catalog.category(p.category());
        return new CatalogItem(p.externalId(), p.name(), p.brand(), p.category(),
                category == null ? p.category() : category.labelHr(), p.subcategoryHr(), p.description(),
                p.priceCents(), p.priceBand(), p.shades(), p.shadeCount(), p.finish(), p.imageUrl(),
                p.productUrl(), p.retailer(), p.inStock(), p.stockUnverified(), p.shadeNeedsSwatchCheck(),
                p.rating(), p.tags(), category == null ? null : category.usedForHr());
    }

    private String categoryLabel(String key) {
        var c = catalog.category(key);
        return c == null ? key : c.labelHr();
    }

    private static String finishLabel(String finish) {
        return switch (finish == null ? "" : finish) {
            case "matte" -> "mat";
            case "satin" -> "saten";
            case "shimmer" -> "šimer";
            case "gloss" -> "sjajno";
            case "cream" -> "kremasto";
            default -> finish;
        };
    }

    private static String bandLabel(String band) {
        return switch (band == null ? "" : band) {
            case "budget" -> "povoljno";
            case "mid" -> "srednje";
            case "premium" -> "skuplje";
            default -> band;
        };
    }

    private static String shadeLabel(String family) {
        return switch (family == null ? "" : family) {
            case "red" -> "crvena";
            case "burgundy" -> "bordo";
            case "pink" -> "roza";
            case "nude" -> "nude";
            case "brown" -> "smeđa";
            case "peach" -> "breskva";
            case "gold" -> "zlatna";
            case "silver" -> "srebrna";
            case "black" -> "crna";
            case "white" -> "bijela";
            case "blue" -> "plava";
            case "green" -> "zelena";
            case "purple" -> "ljubičasta";
            case "orange" -> "narančasta";
            default -> family;
        };
    }

    // -------------------------------------------------------------------------------------- kit

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KitRequest(
            String look,
            Integer budgetCents,
            String finish,
            String skinType,
            List<String> ownedCategories,
            List<String> missingCategories,
            List<String> excludedCategories,
            List<String> brandPreferences,
            Map<String, String> pinnedBySlot,
            boolean preferCheapest,
            String singleRetailer) { }

    public record KitResponse(MakeupKitAssembler.ValidatedKit kit, BeautyBriefDto brief,
                              List<String> singleStoreOptions) { }

    /**
     * Build a kit. Refinements travel WITH the request rather than mutating a stored kit — the same
     * decision the nail vertical made, and for the same reason: a kit is a pure function of (brief +
     * refinements), so there is no server state to get out of step with what the user is looking at.
     */
    @PostMapping("/kit")
    public KitResponse kit(@RequestBody KitRequest request) {
        List<OwnedItemDto> owned = new ArrayList<>();
        if (request.ownedCategories() != null) {
            for (String c : request.ownedCategories()) owned.add(OwnedItemDto.owned(c, c));
        }
        if (request.missingCategories() != null) {
            for (String c : request.missingCategories()) owned.add(OwnedItemDto.missing(c, c));
        }

        BeautyBriefDto brief = new BeautyBriefDto(
                "", "HR", "EUR",
                request.budgetCents() == null ? 0 : Math.max(0, request.budgetCents()),
                false, false,
                MakeupLook.isKnown(request.look()) ? request.look() : "",
                "", List.of(), "", request.finish() == null ? "" : request.finish(),
                request.skinType() == null ? "" : request.skinType(),
                "", "", "", false, false,
                owned,
                List.of(),
                request.excludedCategories() == null ? List.of() : request.excludedCategories(),
                request.brandPreferences() == null ? List.of() : request.brandPreferences(),
                request.singleRetailer() != null && !request.singleRetailer().isBlank(),
                "beginner", List.of());

        var prefs = new MakeupKitAssembler.Preferences(
                request.pinnedBySlot(), request.preferCheapest(), request.singleRetailer(),
                MakeupLook.isKnown(request.look()) ? request.look() : null);

        var kit = assembler.assemble(brief, prefs);
        return new KitResponse(kit, brief, assembler.singleStoreOptions(kit.lookKey()));
    }

    private String provenance() {
        return "Pilot katalog: " + String.join(" + ", catalog.retailers()) + ", "
                + catalog.products().size() + " proizvoda strojno preuzetih s javnih product feedova "
                + catalog.capturedAt().substring(0, Math.min(10, catalog.capturedAt().length()))
                + ". Nijedan nije ručno provjeren i nijedan nema ocjenu — trgovci ih ne objavljuju.";
    }

    private static String fold(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('đ', 'd'),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
