package hr.kitiva.beauty.makeup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The HR makeup pilot catalog — 194 rows machine-captured from Golden Rose HR's Shopify feed and dm's
 * published search service by {@code scripts/build-makeup-pilot-catalog.mjs}.
 *
 * <p>Deliberately a sibling of {@link hr.kitiva.beauty.nail.NailPilotCatalog} rather than a shared
 * generic: the two catalogs answer different questions (a nail row has a kit slot and a nail system, a
 * makeup row has a category, shades and a price band), and a common supertype would either be an empty
 * shell or would force one vertical's vocabulary onto the other. What IS shared is the discipline — load
 * once, degrade to empty on failure, filter to purchasable before the assembler ever sees a row.</p>
 *
 * <p><strong>Two fields are permanently null and that is the point.</strong> {@code rating} and
 * {@code ratingCount} exist because the shape of a product page wants them, and neither retailer publishes
 * a review score. An invented star rating would be the most persuasive lie this product could tell, so the
 * field stays empty and the UI shows nothing rather than something plausible.</p>
 */
@Component
public class MakeupPilotCatalog {

    private static final Logger log = LoggerFactory.getLogger(MakeupPilotCatalog.class);
    static final String RESOURCE = "/catalog/makeup-pilot-hr.json";

    private final Pilot pilot;

    public MakeupPilotCatalog() {
        this(RESOURCE);
    }

    MakeupPilotCatalog(String resource) {
        Pilot loaded = null;
        try (InputStream in = MakeupPilotCatalog.class.getResourceAsStream(resource)) {
            if (in != null) loaded = new ObjectMapper().readValue(in, Pilot.class);
        } catch (Exception ex) {
            log.warn("Makeup pilot catalog could not be loaded from {}: {}", resource, ex.getMessage());
        }
        this.pilot = loaded;
        log.info("Makeup pilot catalog: {} product(s) in {} categor(ies)",
                products().size(), categories().size());
    }

    public List<MakeupProduct> products() {
        return pilot == null ? List.of() : pilot.products();
    }

    /** The category vocabulary, in the order a face is actually made up. */
    public List<Category> categories() {
        return pilot == null ? List.of() : pilot.categories();
    }

    public List<Source> sources() {
        return pilot == null ? List.of() : pilot.sources();
    }

    public String capturedAt() {
        return pilot == null ? "" : pilot.capturedAt();
    }

    public boolean isLoaded() {
        return pilot != null && !products().isEmpty();
    }

    public Category category(String key) {
        return categories().stream().filter(c -> c.key().equalsIgnoreCase(key)).findFirst().orElse(null);
    }

    /**
     * Rows that may actually be offered for a category: in stock, and priced. Out-of-stock rows stay in
     * the catalog so the assembler has to reject them rather than be handed a pre-filtered world — the
     * same rule the nail catalog follows, and the reason an empty slot is reported instead of hidden.
     */
    public List<MakeupProduct> availableIn(String categoryKey) {
        return products().stream()
                .filter(p -> p.category().equalsIgnoreCase(categoryKey))
                .filter(MakeupProduct::inStock)
                .filter(p -> p.priceCents() > 0)
                .toList();
    }

    public List<MakeupProduct> forCategory(String categoryKey) {
        return products().stream().filter(p -> p.category().equalsIgnoreCase(categoryKey)).toList();
    }

    public MakeupProduct byId(String externalId) {
        if (externalId == null) return null;
        return products().stream().filter(p -> externalId.equals(p.externalId())).findFirst().orElse(null);
    }

    public List<String> retailers() {
        return products().stream().map(MakeupProduct::retailer).filter(java.util.Objects::nonNull)
                .distinct().sorted().toList();
    }

    public List<String> brands() {
        return products().stream().map(MakeupProduct::brand)
                .filter(b -> b != null && !b.isBlank()).distinct()
                .sorted(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT))).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Pilot(String capturedAt, String market, String currency, List<Category> categories,
                 List<Source> sources, List<MakeupProduct> products) {
        Pilot {
            products = products == null ? List.of() : List.copyOf(products);
            categories = categories == null ? List.of() : List.copyOf(categories);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /**
     * One makeup category.
     *
     * @param usedForHr what this category is FOR. Our own copy, and deliberately about the category rather
     *                  than any product in it: explaining what a concealer does is teaching, while saying a
     *                  particular concealer covers well would be a review we have no evidence for.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(String key, String labelHr, int order, String usedForHr) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String retailer, String endpoint, String platform, String method,
                         String verificationMethod, String status, String lastVerifiedAt) { }

    /** One published shade. {@code colorFamily} is null unless the retailer NAMED the colour. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Shade(String name, String fullTitle, String colorFamily, BigDecimal price,
                        boolean inStock, String swatchImageUrl) {
        public Shade {
            price = price == null ? BigDecimal.ZERO : price;
        }
        public int priceCents() {
            return price.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        }
        /** True when the retailer published a colour NAME, not just a number. "102" names nothing. */
        public boolean colourNamed() {
            return colorFamily != null && !colorFamily.isBlank();
        }
    }

    /** One tag, with where it came from. There is no third provenance: we do not add editorial tags. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(String tag, String provenance, String basisHr) {
        public boolean isPublished() { return "published".equalsIgnoreCase(provenance); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MakeupProduct(
            String externalId,
            String name,
            String brand,
            String category,
            /** The retailer's own sub-type, verbatim, typos included. Null at dm, which publishes none. */
            String subcategoryHr,
            /** The retailer's own description. Null at dm, whose search service publishes none. */
            String description,
            BigDecimal price,
            String currency,
            List<Shade> shades,
            int shadeCount,
            /** matte | satin | shimmer | gloss | cream, and only when the retailer's own words say so. */
            String finish,
            String imageUrl,
            boolean inStock,
            /** False when the retailer publishes no stock field at all — dm does not. */
            Boolean stockKnown,
            String productUrl,
            String retailer,
            String retailerUrl,
            /** Always null. Neither source publishes a review score, and we will not invent one. */
            Double rating,
            Integer ratingCount,
            List<String> publishedTags,
            /** budget | mid | premium — terciles of the REAL prices in this category. Not a quality claim. */
            String priceBand,
            List<Tag> tags,
            String verificationMethod,
            String lastVerifiedAt,
            String sourceStatus,
            String verifiedAt,
            String dataQuality
    ) {
        public MakeupProduct {
            category = category == null ? "" : category.trim().toLowerCase();
            price = price == null ? BigDecimal.ZERO : price;
            shades = shades == null ? List.of() : List.copyOf(shades);
            publishedTags = publishedTags == null ? List.of() : List.copyOf(publishedTags);
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        public int priceCents() {
            return price.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        }

        /** True when the retailer publishes no stock field, so the UI must tell the user to check. */
        public boolean stockUnverified() {
            return !Boolean.TRUE.equals(stockKnown);
        }

        /**
         * True when this product comes in shades but the retailer names none of them by colour — so it can
         * only ever be offered as a candidate with "check the swatch", never as a matched shade. Foundation
         * is the case that matters: "Skin Perfector 102" tells a buyer nothing about her own skin.
         */
        public boolean shadeNeedsSwatchCheck() {
            return shadeCount > 0 && shades.stream().noneMatch(Shade::colourNamed);
        }

        public boolean hasFinish(String wanted) {
            return finish != null && finish.equalsIgnoreCase(wanted);
        }

        /** Shades whose colour the retailer actually named, for a look that asks for one. */
        public List<Shade> namedShades() {
            return shades.stream().filter(Shade::colourNamed).toList();
        }

        public boolean hasShadeFamily(String family) {
            return family != null && shades.stream()
                    .anyMatch(s -> family.equalsIgnoreCase(s.colorFamily()));
        }

        public String displayName() {
            return brand == null || brand.isBlank() || name.toLowerCase(Locale.ROOT)
                    .startsWith(brand.toLowerCase(Locale.ROOT)) ? name : brand + " " + name;
        }
    }
}
