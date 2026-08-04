package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.NailLookBriefDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verification policy: what the app is allowed to SAY about a captured price, and what it must not.
 *
 * <p>Every assertion here is time-stable. A test that asserts "the kit says the price is current" would
 * pass today and fail in a fortnight, which is the same class of bug as a catalog that quietly rots — so
 * freshness is always evaluated against an explicit instant, never against the wall clock.</p>
 */
class NailCatalogVerificationTest {

    private final NailPilotCatalog catalog = new NailPilotCatalog();
    private final NailIntentExtractor extractor = new NailIntentExtractor();

    private static final Instant CAPTURED = Instant.parse("2026-08-03T08:00:00Z");

    private NailLookBriefDto brief(String prompt) {
        return extractor.parse(prompt, NailLookBriefDto.ExecutionMode.AT_HOME, 0).brief();
    }

    /** A row with exactly the verification facts a test cares about; everything else is inert. */
    private NailPilotCatalog.PilotProduct row(String sourceStatus, String lastVerifiedAt) {
        return new NailPilotCatalog.PilotProduct(
                "test-row", "Lak za nokte – 50 bordeaux", null, null, null, "Test", null,
                BigDecimal.ONE, null, true, true, null, null, null, null,
                "regular-polish", "color", "color", null, null, false, true,
                null, null, "UNKNOWN", "UNKNOWN", false, null,
                "automatic", lastVerifiedAt, sourceStatus, "pilot-unreviewed");
    }

    // ------------------------------------------------------------------ the three states, at the boundary

    @Test
    void aRecentlyReadRowFromAReachableSourceIsVerified() {
        var product = row("reachable", CAPTURED.toString());
        assertThat(NailCatalogFreshness.stateOf(product, CAPTURED.plus(Duration.ofDays(1))))
                .isEqualTo(NailCatalogFreshness.State.VERIFIED);
        assertThat(NailCatalogFreshness.noteHrFor(product, CAPTURED.plus(Duration.ofDays(1))))
                .as("nothing to disclose about a price we can still stand behind").isNull();
    }

    @Test
    void theFreshnessWindowIsInclusiveOnItsLastDayAndStaleTheDayAfter() {
        var product = row("reachable", CAPTURED.toString());
        int window = NailCatalogFreshness.FRESH_FOR_DAYS;

        assertThat(NailCatalogFreshness.stateOf(product, CAPTURED.plus(Duration.ofDays(window))))
                .as("day %d is still inside the window", window)
                .isEqualTo(NailCatalogFreshness.State.VERIFIED);
        assertThat(NailCatalogFreshness.stateOf(product, CAPTURED.plus(Duration.ofDays(window + 1))))
                .as("day %d is not", window + 1)
                .isEqualTo(NailCatalogFreshness.State.STALE);
        assertThat(NailCatalogFreshness.noteHrFor(product, CAPTURED.plus(Duration.ofDays(window + 1))))
                .contains("3. 8. 2026.").contains("nije svježa");
    }

    /**
     * The requirement this whole class exists for: a source we could not read cannot be re-confirmed, so
     * its rows are never called current — however recently they were captured.
     */
    @Test
    void aRowFromABlockedSourceIsNeverCalledVerifiedEvenWhenTheCaptureIsFresh() {
        for (String status : List.of("temporarily-blocked", "unavailable", "", "something-new")) {
            var product = row(status, CAPTURED.toString());
            assertThat(NailCatalogFreshness.stateOf(product, CAPTURED.plus(Duration.ofMinutes(1))))
                    .as("sourceStatus '%s' must fail closed", status)
                    .isEqualTo(NailCatalogFreshness.State.UNVERIFIABLE);
        }
        assertThat(NailCatalogFreshness.noteHrFor(row("temporarily-blocked", CAPTURED.toString()), CAPTURED))
                .containsIgnoringCase("izvor trenutačno nije dostupan");
    }

    @Test
    void anUnknownOrFutureCaptureTimeIsTreatedAsUnverifiableRatherThanFine() {
        assertThat(NailCatalogFreshness.stateOf(row("reachable", null), CAPTURED))
                .as("no timestamp is not the same as a good one")
                .isEqualTo(NailCatalogFreshness.State.UNVERIFIABLE);
        assertThat(NailCatalogFreshness.stateOf(row("reachable", CAPTURED.plus(Duration.ofDays(3)).toString()),
                CAPTURED))
                .as("a capture in the future is a broken clock, not freshness")
                .isEqualTo(NailCatalogFreshness.State.UNVERIFIABLE);
    }

    // ------------------------------------------------------------------------- what the real catalog carries

    @Test
    void everySourceDeclaresHowAndWhenItWasVerified() {
        assertThat(catalog.sources()).isNotEmpty();
        assertThat(catalog.sources()).allSatisfy(source -> {
            assertThat(source.verificationMethod()).isIn("automatic", "manual");
            assertThat(source.status()).isIn("reachable", "temporarily-blocked", "unavailable");
        });
    }

    @Test
    void everyProductCarriesItsOwnVerificationFacts() {
        assertThat(catalog.products()).isNotEmpty();
        assertThat(catalog.products()).allSatisfy(product -> {
            assertThat(product.verificationMethod())
                    .as("%s must say how it was verified", product.externalId())
                    .isIn("automatic", "manual");
            assertThat(product.sourceStatus())
                    .as("%s must say whether its source could be read", product.externalId())
                    .isIn("reachable", "temporarily-blocked", "unavailable");
            assertThat(NailCatalogFreshness.verifiedAt(product))
                    .as("%s must say WHEN it was read", product.externalId())
                    .isNotNull();
        });
    }

    /**
     * The rule that keeps the metadata worth having: a run that could not reach a source did not verify
     * anything, so its rows must not carry a timestamp newer than the ones from sources we DID read.
     * Getting this wrong is a single-field lie that makes every "verified" claim downstream false.
     */
    @Test
    void aBlockedSourcesRowsAreNotStampedWithTheTimeOfTheRunThatCouldNotReadThem() {
        var reachableNewest = catalog.products().stream()
                .filter(p -> NailCatalogFreshness.sourceReachable(p.sourceStatus()))
                .map(NailCatalogFreshness::verifiedAt)
                .max(Instant::compareTo).orElse(null);
        var blocked = catalog.products().stream()
                .filter(p -> !NailCatalogFreshness.sourceReachable(p.sourceStatus()))
                .toList();

        org.junit.jupiter.api.Assumptions.assumeTrue(reachableNewest != null && !blocked.isEmpty(),
                "no blocked source in the current catalog — nothing to check");

        assertThat(blocked).allSatisfy(product -> assertThat(NailCatalogFreshness.verifiedAt(product))
                .as("%s came from a source this run could not read", product.externalId())
                .isBefore(reachableNewest));
    }

    /**
     * A blocked source must not silently delete what it already gave us. The gold accent is the live case:
     * beauty-shop.hr began refusing automated reads after the capture, and if the build had simply dropped
     * its rows the app would have gone back to telling users Croatia sells no gold nail product.
     */
    @Test
    void aBlockedSourceKeepsItsPreviouslyVerifiedProducts() {
        var blockedSources = catalog.sources().stream()
                .filter(s -> !"reachable".equals(s.status()))
                .toList();
        org.junit.jupiter.api.Assumptions.assumeTrue(!blockedSources.isEmpty(),
                "every source was reachable at capture — nothing to carry forward");

        for (var source : blockedSources) {
            assertThat(catalog.products()).filteredOn(p -> source.retailer().equals(p.retailer()))
                    .as("%s is blocked, so its previously captured rows must still be here", source.retailer())
                    .isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ freshness discloses, never gates

    @Test
    void aStalePriceIsDisclosedOnTheItemAndInOneLineForTheKit() {
        // Ten years on, every row in the catalog is past the window. Nothing about the kit's completeness
        // may change — only what it says about the numbers.
        Instant longAfter = Instant.parse("2036-08-03T08:00:00Z");
        var fresh = new NailKitAssembler(catalog, Clock.fixed(CAPTURED, ZoneOffset.UTC));
        var aged = new NailKitAssembler(catalog, Clock.fixed(longAfter, ZoneOffset.UTC));

        var freshKit = fresh.assemble(brief("kratki nude nokti kod kuce"));
        var agedKit = aged.assemble(brief("kratki nude nokti kod kuce"));

        assertThat(agedKit.status())
                .as("the clock must never change whether a kit can be built").isEqualTo(freshKit.status());
        assertThat(agedKit.missingRequiredSlots()).isEqualTo(freshKit.missingRequiredSlots());
        assertThat(agedKit.totalCents()).isEqualTo(freshKit.totalCents());

        assertThat(agedKit.catalogFreshnessHr()).contains("provjera je starija od");
        assertThat(agedKit.items()).anySatisfy(i -> assertThat(i.noteHr()).contains("nije svježa"));
    }

    @Test
    void aProductFromABlockedSourceSaysSoOnItsOwnRow() {
        var assembler = new NailKitAssembler(catalog, Clock.fixed(CAPTURED, ZoneOffset.UTC));
        var kit = assembler.assemble(brief("kratki nude nokti sa zlatnim detaljem na prstenjaku kod kuce"));

        var accent = kit.items().stream().filter(i -> "accent".equals(i.slot())).findFirst().orElseThrow();
        var product = catalog.products().stream()
                .filter(p -> accent.externalId().equals(p.externalId())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !NailCatalogFreshness.sourceReachable(product.sourceStatus()),
                "the accent's source is reachable again — nothing to disclose");

        assertThat(accent.noteHr()).containsIgnoringCase("izvor trenutačno nije dostupan");
        assertThat(kit.catalogFreshnessHr()).contains("ne možemo potvrditi");
        assertThat(kit.status().isPurchasable())
                .as("an unconfirmable price is a disclosure, not a missing product").isTrue();
    }

    @Test
    void theFreshnessLineNeverClaimsAPriceWasCheckedByAHuman() {
        var kit = new NailKitAssembler(catalog, Clock.fixed(CAPTURED, ZoneOffset.UTC))
                .assemble(brief("kratki nude nokti kod kuce"));
        assertThat(kit.catalogFreshnessHr()).contains("Nijedna cijena nije ručno provjerena");
        assertThat(catalog.products()).allSatisfy(p ->
                assertThat(p.verificationMethod())
                        .as("no row may claim manual review until a human has actually done it")
                        .isEqualTo("automatic"));
    }

    /**
     * The test seam must not cost us the application.
     *
     * <p>Adding the {@code Clock} constructor gave {@link NailKitAssembler} two of them, Spring refused to
     * choose, and the whole app failed to start with "No default constructor found" — while every unit
     * test in this package stayed green, because none of them boots a context. Cheap structural guard for
     * a mistake that is otherwise only visible by launching the thing.</p>
     */
    @Test
    void theAssemblerStaysConstructibleTheWaySpringWillConstructIt() {
        var constructors = NailKitAssembler.class.getConstructors();
        if (constructors.length == 1) return;
        assertThat(constructors)
                .as("%d public constructors, so exactly one must be @Autowired", constructors.length)
                .filteredOn(c -> c.isAnnotationPresent(
                        org.springframework.beans.factory.annotation.Autowired.class))
                .hasSize(1);
    }

    // ------------------------------------------------------------------------- images are never a gate

    @Test
    void aMissingProductImageChangesNothingAboutTheKit() {
        // beauty-shop.hr publishes image URLs and then refuses to serve them, so its rows carry no image.
        // That must cost the user nothing: the kit still completes, still totals the same, still buys the
        // same products. Only the picture is absent, and the UI labels it.
        var assembler = new NailKitAssembler(catalog, Clock.fixed(CAPTURED, ZoneOffset.UTC));
        var kit = assembler.assemble(brief("kratki nude nokti sa zlatnim detaljem na prstenjaku kod kuce"));

        assertThat(kit.status().isPurchasable()).isTrue();
        assertThat(kit.missingRequiredSlots()).isEmpty();

        var withoutImages = kit.items().stream().filter(i -> i.imageUrl() == null).toList();
        assertThat(withoutImages).as("this kit is meant to contain an imageless row").isNotEmpty();
        assertThat(withoutImages).allSatisfy(i -> {
            assertThat(i.priceCents()).as("an imageless product still has a real price").isPositive();
            assertThat(i.productUrl()).as("and a real link to go and look at it").startsWith("https://");
        });
    }
}
