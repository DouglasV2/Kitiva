package ai.budgetspace.product;

import ai.budgetspace.dto.ProductDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the second door onto the catalog.
 *
 * <p>{@code GET /api/products} projected {@code findAll()} straight to DTOs with no gate, so rows the
 * planner would never pick — needs-review, unavailable, legacy samples with no {@code sourceReference},
 * feed-required retailers that never came from a feed — were publicly listable with a name, a price and a
 * buy link. Under the beauty pivot the same hole would publish professional-only and unclassified SKUs
 * outside the safety gate entirely.</p>
 *
 * <p>The invariant this pins: <strong>the public listing shows exactly what the planner is willing to
 * pick.</strong> If the two ever disagree, one of them is lying to somebody.</p>
 */
class PublicProductListingEligibilityTest {

    private ProductController controllerOver(List<Product> catalog) {
        ProductRepository repository = mock(ProductRepository.class);
        when(repository.findAll()).thenReturn(catalog);
        // products() touches only the repository; the remaining collaborators are unused on this path.
        return new ProductController(repository, null, null, null, null, null, null, null);
    }

    /** A row that satisfies every eligibility condition. */
    private Product eligible(String externalId) {
        Product p = new Product();
        p.setExternalId(externalId);
        p.setName("Real product " + externalId);
        p.setRetailer("IKEA");                 // DIRECT_VERIFIED, so no feed requirement
        p.setCategory("sofa");
        p.setPrice(new BigDecimal("299.00"));
        p.setInStock(true);
        p.setAvailabilityStatus("in-stock");
        p.setDataQuality("complete");
        p.setRoomTags("living-room");
        p.setStyleTags("modern");
        p.setSourceType("public-product-page");
        p.setSourceReference("ikea-hr-test");
        p.setMarket("HR");
        p.setLastCheckedAt(LocalDate.now().toString());
        return p;
    }

    private List<String> listedIds(ProductController controller) {
        return controller.products(null, null, null, null).stream().map(ProductDto::externalId).toList();
    }

    @Test
    void anEligibleProductIsListed() {
        assertThat(listedIds(controllerOver(List.of(eligible("ok-1"))))).containsExactly("ok-1");
    }

    @Test
    void aRetiredUnavailableRowIsNeverListed() {
        // Exactly the shape of the 17 dead-link rows retired upstream in 8a536db.
        Product retired = eligible("retired-1");
        retired.setAvailabilityStatus("unavailable");

        assertThat(listedIds(controllerOver(List.of(eligible("ok-1"), retired))))
                .containsExactly("ok-1")
                .doesNotContain("retired-1");
    }

    @Test
    void aNeedsReviewRowIsNeverListed() {
        Product weak = eligible("needs-review-1");
        weak.setDataQuality("needs-review");

        assertThat(listedIds(controllerOver(List.of(weak)))).isEmpty();
    }

    @Test
    void aLegacySampleRowWithNoSourceReferenceIsNeverListed() {
        Product sample = eligible("sample-1");
        sample.setSourceReference(null);

        assertThat(listedIds(controllerOver(List.of(sample)))).isEmpty();
    }

    @Test
    void aFeedRequiredRetailerThatNeverCameFromAFeedIsNeverListed() {
        // The rule that stops a blocked retailer's data appearing because somebody scraped past its 403.
        Product scraped = eligible("wayfair-1");
        scraped.setRetailer("Wayfair");                    // OFFICIAL_FEED_REQUIRED
        scraped.setSourceType("public-product-page");      // not a feed

        assertThat(listedIds(controllerOver(List.of(scraped)))).isEmpty();

        Product fromFeed = eligible("wayfair-2");
        fromFeed.setRetailer("Wayfair");
        fromFeed.setSourceType("official-feed");
        assertThat(listedIds(controllerOver(List.of(fromFeed)))).containsExactly("wayfair-2");
    }

    @Test
    void anOutOfStockOrFreeRowIsNeverListed() {
        Product outOfStock = eligible("oos-1");
        outOfStock.setInStock(false);

        Product free = eligible("free-1");
        free.setPrice(BigDecimal.ZERO);

        assertThat(listedIds(controllerOver(List.of(outOfStock, free)))).isEmpty();
    }

    @Test
    void theListingAgreesWithThePlannerGateForEveryRow() {
        // The invariant itself, stated directly: nothing is listed that isPlannerEligible would refuse.
        Product ok = eligible("ok-1");
        Product retired = eligible("retired-1");
        retired.setAvailabilityStatus("unavailable");
        Product weak = eligible("weak-1");
        weak.setDataQuality("needs-review");
        Product sample = eligible("sample-1");
        sample.setSourceReference("  ");

        List<Product> catalog = List.of(ok, retired, weak, sample);
        List<String> listed = listedIds(controllerOver(catalog));
        List<String> plannerWouldAccept = catalog.stream()
                .filter(CatalogSourcePolicy::isPlannerEligible)
                .map(Product::getExternalId)
                .toList();

        assertThat(listed).isEqualTo(plannerWouldAccept);
    }
}
