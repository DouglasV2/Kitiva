package ai.budgetspace.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {
    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String retailer;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "original_price", precision = 10, scale = 2)
    private BigDecimal originalPrice;

    // --- Sprint 10.33: discount / sale tracking. A product is "on sale" only when a verified
    // originalPrice (the regular price) is strictly greater than the current price. saleEndsAt is the
    // verified end of the promo window (e.g. JYSK `priceValidUntil`, ISO date/datetime) so we can show
    // the shopper how long the deal lasts and never imply a stale discount. Null = no known sale window.
    // We never fabricate a discount: both fields are populated only from a live, web-verified page.
    @Column(name = "sale_ends_at", length = 40)
    private String saleEndsAt;

    @Column(name = "style_tags", nullable = false)
    private String styleTags;

    @Column(name = "room_tags", nullable = false)
    private String roomTags;

    @Column(name = "image_url", length = 700)
    private String imageUrl;

    @Column(name = "product_url", length = 700)
    private String productUrl;

    @Column(name = "availability_status", length = 80)
    private String availabilityStatus;

    @Column(name = "delivery_note", length = 300)
    private String deliveryNote;

    @Column(name = "last_checked_at", length = 40)
    private String lastCheckedAt;

    @Column(name = "external_id", length = 120)
    private String externalId;

    @Column(name = "price_tier", length = 40)
    private String priceTier;

    // Catalog source metadata (Sprint 9.0). Backend/dev/docs only — not shown to the user.
    @Column(name = "source_type", length = 40)
    private String sourceType;

    @Column(name = "source_name", length = 80)
    private String sourceName;

    @Column(name = "source_reference", length = 300)
    private String sourceReference;

    @Column(name = "imported_at", length = 40)
    private String importedAt;

    @Column(name = "data_quality", length = 40)
    private String dataQuality;

    @Column(name = "data_quality_notes", length = 500)
    private String dataQualityNotes;

    @Column(nullable = false, length = 700)
    private String image;

    @Column(nullable = false, length = 700)
    private String url;

    @Column(nullable = false)
    private double rating;

    @Column(name = "in_stock", nullable = false)
    private boolean inStock;

    @Column(nullable = false, length = 700)
    private String note;

    // --- Sprint 10.7: smart product matching ---
    // Optional comma-separated colour tags using the canonical keys produced by
    // ProductTaxonomy.deriveColorTags / matched against PlannerInputDto.colorPreferences
    // (e.g. "white,grey,green"). Nullable: a product without colour tags is treated as
    // having no colour preference and simply earns no colour bonus.
    @Column(name = "color_tags", length = 200)
    private String colorTags;

    // Optional comma-separated material tags using the canonical keys produced by
    // ProductTaxonomy.deriveMaterialTags (e.g. "wood,metal,glass"). Nullable, same as above.
    @Column(name = "material_tags", length = 200)
    private String materialTags;

    // --- Sprint 10.10: affiliate / sponsored groundwork (no UI ad treatment yet) ---
    // The plain retailer product page, kept separate from any affiliate redirect.
    @Column(name = "original_product_url", length = 700)
    private String originalProductUrl;
    // Optional affiliate/partner redirect URL; when present the UI may use it for the outbound link.
    @Column(name = "affiliate_url", length = 700)
    private String affiliateUrl;
    // A sponsored product must be clearly labelled and never replace the best organic recommendation.
    // ColumnDefault so the generated DDL has `default false`: legacy data.sql sample rows omit this
    // column, and without a default PostgreSQL rejects the NOT NULL insert on startup.
    @Column(name = "is_sponsored", nullable = false)
    @ColumnDefault("false")
    private boolean sponsored;
    @Column(name = "sponsor_label", length = 120)
    private String sponsorLabel;

    // --- Sprint 10.13 (#2): reviews. We never fabricate review text; we store the retailer's
    // aggregate (count + average star) when a feed/page provides it and always link out to the
    // product page where the real reviews live. reviewCount/reviewRating null = unknown.
    // NOTE: reviewRating is display-only and intentionally separate from `rating` (the planner's
    // internal heuristic), so showing verified stars never changes plan ranking. ---
    @Column(name = "review_count")
    private Integer reviewCount;
    @Column(name = "review_rating")
    private Double reviewRating;
    @Column(name = "reviews_url", length = 700)
    private String reviewsUrl;

    // --- Sprint 10.13 (#3): market/country this product belongs to (e.g. HR, SI, AT, DE).
    // Null/blank is treated as global (matches any market) so legacy/sample data still works. ---
    @Column(name = "market", length = 8)
    private String market;

    // --- Sprint 10.21: second-hand marketplace groundwork (data model only; no feed wired yet). ---
    // True for a used item from a consumer marketplace (Njuškalo/FB) delivered via a compliant feed
    // (sourceType=marketplace-listing). Drives the separate "Rabljeno" UI section and must never be
    // mixed silently into the new-retail plan total. ColumnDefault so legacy/sample inserts stay valid.
    @Column(name = "second_hand", nullable = false)
    @ColumnDefault("false")
    private boolean secondHand;
    // The used item's stated condition (e.g. like-new, used-good); never guessed. Null = unknown.
    @Column(name = "condition_label", length = 40)
    private String conditionLabel;
    // City/region of the seller (e.g. "Zagreb"); helps the buyer judge pickup distance. No precise address.
    @Column(name = "seller_location", length = 120)
    private String sellerLocation;

    // --- Sprint 10.23 (road-to-production step 4): verified product image. True only when the image URL
    // was confirmed on the retailer's live product page (og:image / main gallery image). The UI shows the
    // real photo only when this is true; otherwise it keeps the labelled "ilustracija" category placeholder.
    // We never fabricate an image URL, so imageUrl is populated only when verified. ColumnDefault so legacy
    // /sample inserts (which omit this column) stay valid, exactly like sponsored/secondHand.
    @Column(name = "image_verified", nullable = false)
    @ColumnDefault("false")
    private boolean imageVerified;

    // ===============================================================================================
    // Beauty Kit — Phase C2b. Every column below is NULLABLE, so the 21k furniture rows migrate
    // untouched and a beauty row simply fills more of them. Each one exists because a stated MVP rule
    // reads it; the consumer is named. A column no rule consumes is a column nobody maintains, and an
    // unmaintained safety column is worse than a missing one.
    // ===============================================================================================

    // --- Identity / variant. Consumed by owned-item matching and shade candidates. A beauty SKU is
    // identified by brand + line + shade, not by name alone: "Ruby Woo" means nothing without "MAC".
    @Column(name = "brand", length = 120)
    private String brand;

    @Column(name = "product_line", length = 160)
    private String productLine;

    @Column(name = "shade_name", length = 160)
    private String shadeName;

    @Column(name = "shade_code", length = 60)
    private String shadeCode;

    @Column(name = "size_ml", precision = 8, scale = 2)
    private BigDecimal sizeMl;

    // --- Makeup attributes. Consumed by MakeupKitGraph (slot filling) and the compatibility policy.
    @Column(name = "coverage", length = 40)
    private String coverage;

    @Column(name = "finish_tag", length = 40)
    private String finishTag;

    /** liquid | cream | powder | stick | balm — decides which applicator the kit must also include. */
    @Column(name = "formula_format", length = 40)
    private String formulaFormat;

    @Column(name = "shade_depth", length = 40)
    private String shadeDepth;

    @Column(name = "undertone", length = 40)
    private String undertone;

    @Column(name = "required_applicator", length = 80)
    private String requiredApplicator;

    /**
     * Manufacturer CLAIMS, not our assessment — hence the name. BeautyBrief.gentleEyeAreaPreferred and
     * fragranceFreePreferred steer selection toward these; we repeat the manufacturer's claim and never
     * assert it ourselves, because we have not tested anything.
     */
    @Column(name = "eye_area_safe_claim", length = 200)
    private String eyeAreaSafeClaim;

    @Column(name = "fragrance_free_claim", length = 200)
    private String fragranceFreeClaim;

    // --- Nail system + compatibility. Consumed by NailKitGraph and BeautyCompatibilityPolicy.
    /** regular-polish | gel-polish | press-on, or a forbidden system this catalog must never sell to consumers. */
    @Column(name = "nail_system", length = 40)
    private String nailSystem;

    /** prep | base | color | effect | top | adhesive | removal | tool | lamp — the completeness-graph slot. */
    @Column(name = "application_role", length = 40)
    private String applicationRole;

    @Column(name = "curing_required")
    private Boolean curingRequired;

    @Column(name = "recommended_lamp", length = 200)
    private String recommendedLamp;

    @Column(name = "cure_time_seconds")
    private Integer cureTimeSeconds;

    /** soak-off | file-off — decides which removal product the kit must include. */
    @Column(name = "removal_method", length = 40)
    private String removalMethod;

    @Column(name = "effect_type", length = 40)
    private String effectType;

    @Column(name = "magnet_required")
    private Boolean magnetRequired;

    @Column(name = "beginner_suitability", length = 40)
    private String beginnerSuitability;

    // --- Safety. Consumed by ConsumerNailSafetyPolicy.
    /**
     * Substance status columns are STRINGS holding a {@code SubstancePresence} name, never booleans.
     * A boolean would force every unknown to false, and false reads as "does not contain" — which is how
     * missing retailer data silently becomes a safety claim. NULL parses to UNKNOWN, which blocks.
     */
    @Column(name = "hema_status", length = 24)
    private String hemaStatus;

    @Column(name = "di_hema_status", length = 24)
    private String diHemaStatus;

    @Column(name = "tpo_status", length = 24)
    private String tpoStatus;

    @Column(name = "professional_only")
    private Boolean professionalOnly;

    @Column(name = "inci_source", length = 300)
    private String inciSource;

    /** When a FULL ingredient list was last captured. Outside the freshness window the verdict is void. */
    @Column(name = "inci_verified_at", length = 40)
    private String inciVerifiedAt;

    /**
     * Cached safety verdict — a CACHE, never a truth. Invalid unless safety_ruleset_version equals the
     * ruleset in force AND safety_verdict_at is inside the freshness window. On any mismatch the verdict
     * is recomputed or the product is blocked; it is never trusted because it happens to be present.
     */
    @Column(name = "safety_verdict", length = 24)
    private String safetyVerdict;

    @Column(name = "safety_verdict_at", length = 40)
    private String safetyVerdictAt;

    @Column(name = "safety_ruleset_version", length = 40)
    private String safetyRulesetVersion;

    public Product() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRetailer() { return retailer; }
    public void setRetailer(String retailer) { this.retailer = retailer; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    public String getSaleEndsAt() { return saleEndsAt; }
    public void setSaleEndsAt(String saleEndsAt) { this.saleEndsAt = saleEndsAt; }
    public String getStyleTags() { return styleTags; }
    public void setStyleTags(String styleTags) { this.styleTags = styleTags; }
    public String getRoomTags() { return roomTags; }
    public void setRoomTags(String roomTags) { this.roomTags = roomTags; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getProductUrl() { return productUrl; }
    public void setProductUrl(String productUrl) { this.productUrl = productUrl; }
    public String getAvailabilityStatus() { return availabilityStatus; }
    public void setAvailabilityStatus(String availabilityStatus) { this.availabilityStatus = availabilityStatus; }
    public String getDeliveryNote() { return deliveryNote; }
    public void setDeliveryNote(String deliveryNote) { this.deliveryNote = deliveryNote; }
    public String getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(String lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getPriceTier() { return priceTier; }
    public void setPriceTier(String priceTier) { this.priceTier = priceTier; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String sourceReference) { this.sourceReference = sourceReference; }
    public String getImportedAt() { return importedAt; }
    public void setImportedAt(String importedAt) { this.importedAt = importedAt; }
    public String getDataQuality() { return dataQuality; }
    public void setDataQuality(String dataQuality) { this.dataQuality = dataQuality; }
    public String getDataQualityNotes() { return dataQualityNotes; }
    public void setDataQualityNotes(String dataQualityNotes) { this.dataQualityNotes = dataQualityNotes; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }
    public boolean isInStock() { return inStock; }
    public void setInStock(boolean inStock) { this.inStock = inStock; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getColorTags() { return colorTags; }
    public void setColorTags(String colorTags) { this.colorTags = colorTags; }
    public String getMaterialTags() { return materialTags; }
    public void setMaterialTags(String materialTags) { this.materialTags = materialTags; }
    public String getOriginalProductUrl() { return originalProductUrl; }
    public void setOriginalProductUrl(String originalProductUrl) { this.originalProductUrl = originalProductUrl; }
    public String getAffiliateUrl() { return affiliateUrl; }
    public void setAffiliateUrl(String affiliateUrl) { this.affiliateUrl = affiliateUrl; }
    public boolean isSponsored() { return sponsored; }
    public void setSponsored(boolean sponsored) { this.sponsored = sponsored; }
    public String getSponsorLabel() { return sponsorLabel; }
    public void setSponsorLabel(String sponsorLabel) { this.sponsorLabel = sponsorLabel; }
    public Integer getReviewCount() { return reviewCount; }
    public void setReviewCount(Integer reviewCount) { this.reviewCount = reviewCount; }
    public Double getReviewRating() { return reviewRating; }
    public void setReviewRating(Double reviewRating) { this.reviewRating = reviewRating; }
    public String getReviewsUrl() { return reviewsUrl; }
    public void setReviewsUrl(String reviewsUrl) { this.reviewsUrl = reviewsUrl; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public boolean isSecondHand() { return secondHand; }
    public void setSecondHand(boolean secondHand) { this.secondHand = secondHand; }
    public String getConditionLabel() { return conditionLabel; }
    public void setConditionLabel(String conditionLabel) { this.conditionLabel = conditionLabel; }
    public String getSellerLocation() { return sellerLocation; }
    public void setSellerLocation(String sellerLocation) { this.sellerLocation = sellerLocation; }
    public boolean isImageVerified() { return imageVerified; }
    public void setImageVerified(boolean imageVerified) { this.imageVerified = imageVerified; }

    // --- Beauty Kit (Phase C2b). Boxed types on the nullable flags on purpose: null means "not recorded",
    // which is a different fact from false and must not collapse into it.
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getProductLine() { return productLine; }
    public void setProductLine(String productLine) { this.productLine = productLine; }
    public String getShadeName() { return shadeName; }
    public void setShadeName(String shadeName) { this.shadeName = shadeName; }
    public String getShadeCode() { return shadeCode; }
    public void setShadeCode(String shadeCode) { this.shadeCode = shadeCode; }
    public BigDecimal getSizeMl() { return sizeMl; }
    public void setSizeMl(BigDecimal sizeMl) { this.sizeMl = sizeMl; }
    public String getCoverage() { return coverage; }
    public void setCoverage(String coverage) { this.coverage = coverage; }
    public String getFinishTag() { return finishTag; }
    public void setFinishTag(String finishTag) { this.finishTag = finishTag; }
    public String getFormulaFormat() { return formulaFormat; }
    public void setFormulaFormat(String formulaFormat) { this.formulaFormat = formulaFormat; }
    public String getShadeDepth() { return shadeDepth; }
    public void setShadeDepth(String shadeDepth) { this.shadeDepth = shadeDepth; }
    public String getUndertone() { return undertone; }
    public void setUndertone(String undertone) { this.undertone = undertone; }
    public String getRequiredApplicator() { return requiredApplicator; }
    public void setRequiredApplicator(String requiredApplicator) { this.requiredApplicator = requiredApplicator; }
    public String getEyeAreaSafeClaim() { return eyeAreaSafeClaim; }
    public void setEyeAreaSafeClaim(String eyeAreaSafeClaim) { this.eyeAreaSafeClaim = eyeAreaSafeClaim; }
    public String getFragranceFreeClaim() { return fragranceFreeClaim; }
    public void setFragranceFreeClaim(String fragranceFreeClaim) { this.fragranceFreeClaim = fragranceFreeClaim; }
    public String getNailSystem() { return nailSystem; }
    public void setNailSystem(String nailSystem) { this.nailSystem = nailSystem; }
    public String getApplicationRole() { return applicationRole; }
    public void setApplicationRole(String applicationRole) { this.applicationRole = applicationRole; }
    public Boolean getCuringRequired() { return curingRequired; }
    public void setCuringRequired(Boolean curingRequired) { this.curingRequired = curingRequired; }
    public String getRecommendedLamp() { return recommendedLamp; }
    public void setRecommendedLamp(String recommendedLamp) { this.recommendedLamp = recommendedLamp; }
    public Integer getCureTimeSeconds() { return cureTimeSeconds; }
    public void setCureTimeSeconds(Integer cureTimeSeconds) { this.cureTimeSeconds = cureTimeSeconds; }
    public String getRemovalMethod() { return removalMethod; }
    public void setRemovalMethod(String removalMethod) { this.removalMethod = removalMethod; }
    public String getEffectType() { return effectType; }
    public void setEffectType(String effectType) { this.effectType = effectType; }
    public Boolean getMagnetRequired() { return magnetRequired; }
    public void setMagnetRequired(Boolean magnetRequired) { this.magnetRequired = magnetRequired; }
    public String getBeginnerSuitability() { return beginnerSuitability; }
    public void setBeginnerSuitability(String beginnerSuitability) { this.beginnerSuitability = beginnerSuitability; }
    public String getHemaStatus() { return hemaStatus; }
    public void setHemaStatus(String hemaStatus) { this.hemaStatus = hemaStatus; }
    public String getDiHemaStatus() { return diHemaStatus; }
    public void setDiHemaStatus(String diHemaStatus) { this.diHemaStatus = diHemaStatus; }
    public String getTpoStatus() { return tpoStatus; }
    public void setTpoStatus(String tpoStatus) { this.tpoStatus = tpoStatus; }
    public Boolean getProfessionalOnly() { return professionalOnly; }
    public void setProfessionalOnly(Boolean professionalOnly) { this.professionalOnly = professionalOnly; }
    public String getInciSource() { return inciSource; }
    public void setInciSource(String inciSource) { this.inciSource = inciSource; }
    public String getInciVerifiedAt() { return inciVerifiedAt; }
    public void setInciVerifiedAt(String inciVerifiedAt) { this.inciVerifiedAt = inciVerifiedAt; }
    public String getSafetyVerdict() { return safetyVerdict; }
    public void setSafetyVerdict(String safetyVerdict) { this.safetyVerdict = safetyVerdict; }
    public String getSafetyVerdictAt() { return safetyVerdictAt; }
    public void setSafetyVerdictAt(String safetyVerdictAt) { this.safetyVerdictAt = safetyVerdictAt; }
    public String getSafetyRulesetVersion() { return safetyRulesetVersion; }
    public void setSafetyRulesetVersion(String safetyRulesetVersion) { this.safetyRulesetVersion = safetyRulesetVersion; }
}
