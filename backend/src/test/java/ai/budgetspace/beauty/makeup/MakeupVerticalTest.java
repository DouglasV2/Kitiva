package ai.budgetspace.beauty.makeup;

import ai.budgetspace.beauty.dto.BeautyBriefDto;
import ai.budgetspace.beauty.dto.KitStatus;
import ai.budgetspace.beauty.dto.OwnedItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The makeup vertical against the REAL captured catalog — no mocks, no fixtures.
 *
 * <p>Same discipline as the nail slice's tests: every assertion is about a user-visible promise (the look
 * can actually be bought, the arithmetic is honest, owning something removes it from the bill, a swap
 * cannot break the kit) rather than about internal structure. And two of them exist specifically to keep
 * the catalog honest as it grows: no product may ever carry a rating, and no tag may claim to be published
 * unless a retailer published it.</p>
 */
class MakeupVerticalTest {

    private final MakeupPilotCatalog catalog = new MakeupPilotCatalog();
    private final MakeupKitAssembler assembler = new MakeupKitAssembler(catalog);

    private BeautyBriefDto brief(String look, int budgetCents, List<OwnedItemDto> owned) {
        return new BeautyBriefDto("", "HR", "EUR", budgetCents, false, false, look, "", List.of(),
                "", "", "", "", "", "", false, false, owned, List.of(), List.of(), List.of(), false,
                "beginner", List.of());
    }

    // ------------------------------------------------------------------------------------- catalog

    @Test
    void theCatalogIsLoadedAndCoversEveryCategoryItDeclares() {
        assertThat(catalog.isLoaded()).isTrue();
        assertThat(catalog.products()).hasSizeGreaterThan(100);
        assertThat(catalog.categories()).isNotEmpty();

        for (MakeupPilotCatalog.Category category : catalog.categories()) {
            assertThat(catalog.availableIn(category.key()))
                    .as("category '%s' is offered in the UI, so it must have a buyable product",
                            category.labelHr())
                    .isNotEmpty();
        }
    }

    /**
     * The rule that outranks every other requirement in this product: no invented data. Neither retailer
     * publishes a review score, so a rating anywhere in this catalog means somebody made one up.
     */
    @Test
    void noProductCarriesAnInventedRating() {
        assertThat(catalog.products()).allSatisfy(p -> {
            assertThat(p.rating()).as("%s must not carry a rating", p.name()).isNull();
            assertThat(p.ratingCount()).as("%s must not carry a review count", p.name()).isNull();
        });
    }

    /**
     * A tag may only say "published" when a retailer actually published it. The price band is ours — it is
     * arithmetic over real prices — and it has to admit that, because "premium" reads as a quality claim
     * and it is not one.
     */
    @Test
    void everyTagDeclaresWhereItCameFromAndPriceBandsAdmitBeingDerived() {
        assertThat(catalog.products()).allSatisfy(p -> assertThat(p.tags()).allSatisfy(t -> {
            assertThat(t.provenance()).isIn("published", "derived");
            assertThat(t.basisHr()).as("a tag has to say what it is based on").isNotBlank();
        }));

        assertThat(catalog.products()).allSatisfy(p -> {
            var band = p.tags().stream().filter(t -> t.tag().equals(p.priceBand())).findFirst();
            assertThat(band).as("%s should carry its price band as a tag", p.name()).isPresent();
            assertThat(band.get().provenance())
                    .as("a price band is computed by us, never asserted by the shop").isEqualTo("derived");
        });
    }

    /** No vegan or cruelty-free claim exists, because no feed carries evidence for one. */
    @Test
    void noRegulatedMarketingClaimIsAsserted() {
        assertThat(catalog.products()).allSatisfy(p -> assertThat(p.tags())
                .as("%s must not claim a certification we have no evidence for", p.name())
                .noneMatch(t -> t.tag().toLowerCase().matches(".*(vegan|cruelty|organic|hypoallerg).*")));
    }

    /** A colour is only claimed when the retailer NAMED it. "Silky 12" is a code, not a colour. */
    @Test
    void aNumberedShadeNeverClaimsAColour() {
        var numbered = catalog.products().stream()
                .flatMap(p -> p.shades().stream())
                .filter(s -> s.name().matches(".*\\b\\d+\\s*$"))
                .filter(s -> s.colorFamily() != null)
                .toList();
        assertThat(numbered)
                .as("these shades end in a number yet claim a colour family")
                .isEmpty();
    }

    // ----------------------------------------------------------------------------------------- looks

    @Test
    void everyLookCanBeBuiltFromTheCatalog() {
        assertThat(MakeupLook.all()).hasSize(7);

        for (MakeupLook.Definition look : MakeupLook.all()) {
            var kit = assembler.assemble(brief(look.key(), 0, List.of()));

            assertThat(kit.status())
                    .as("%s must be buyable from the real catalog", look.labelHr())
                    .isIn(KitStatus.COMPLETE, KitStatus.COMPLETE_WITH_ASSUMPTIONS);
            assertThat(kit.missingRequiredSlots()).as("%s", look.labelHr()).isEmpty();
            assertThat(kit.lookKey()).isEqualTo(look.key());

            int summed = kit.items().stream().mapToInt(MakeupKitAssembler.KitItem::priceCents).sum();
            assertThat(kit.totalCents()).as("%s: the total is the sum, not an estimate", look.labelHr())
                    .isEqualTo(summed).isPositive();
            assertThat(kit.essentialTotalCents() + kit.optionalTotalCents()).isEqualTo(kit.totalCents());

            assertThat(kit.items()).as("%s", look.labelHr()).allSatisfy(item -> {
                assertThat(item.productUrl()).startsWith("https://");
                assertThat(item.retailer()).isNotBlank();
                assertThat(item.priceCents()).isPositive();
            });
            // The kit doubles as the instructions, so a look with products must say how to apply them.
            assertThat(kit.applicationStepsHr()).as("%s", look.labelHr()).isNotEmpty();
        }
    }

    /** The running order is the thing a beginner gets wrong. Powder must never precede foundation. */
    @Test
    void theApplicationOrderPutsBaseBeforePowderAndSettingSprayLast() {
        var kit = assembler.assemble(brief("full-glam", 0, List.of()));
        List<String> steps = kit.applicationStepsHr();

        int foundation = indexOfContaining(steps, "Podlogu");
        int powder = indexOfContaining(steps, "Puder");
        int spray = indexOfContaining(steps, "Fiksator");

        assertThat(foundation).isGreaterThanOrEqualTo(0);
        assertThat(powder).as("powder sets the base, so it comes after it").isGreaterThan(foundation);
        assertThat(spray).as("a setting spray applied early sets nothing").isGreaterThan(powder);
    }

    private int indexOfContaining(List<String> steps, String needle) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).contains(needle)) return i;
        return -1;
    }

    // ------------------------------------------------------------------------------------- the kit

    @Test
    void owningSomethingRemovesItFromTheBillRatherThanJustLabellingIt() {
        var without = assembler.assemble(brief("soft-glam", 0, List.of()));
        var with = assembler.assemble(brief("soft-glam", 0,
                List.of(OwnedItemDto.owned("mascara", "mascara"), OwnedItemDto.owned("brow", "brow"))));

        assertThat(with.ownedItems()).extracting(MakeupKitAssembler.KitItem::slot)
                .contains("mascara", "brow");
        assertThat(with.items()).extracting(MakeupKitAssembler.KitItem::slot)
                .doesNotContain("mascara", "brow");
        assertThat(with.totalCents())
                .as("owning two products must actually reduce the total")
                .isLessThan(without.totalCents());
    }

    /** "nemam" is stronger than our default: it makes an optional slot required. */
    @Test
    void aStatedAbsenceMakesAnOptionalSlotRequired() {
        // Bronzer is optional in soft glam. Saying she does not have one makes it something the kit owes.
        var kit = assembler.assemble(brief("soft-glam", 0,
                List.of(OwnedItemDto.missing("bronzer", "bronzer"))));

        var bronzer = kit.items().stream().filter(i -> "bronzer".equals(i.slot())).findFirst();
        assertThat(bronzer).isPresent();
        assertThat(bronzer.get().essential())
                .as("she told us she needs one, which outranks our default").isTrue();
    }

    @Test
    void anImpossibleBudgetIsReportedOverBudgetRatherThanTrimmedBelowCompleteness() {
        var kit = assembler.assemble(brief("full-glam", 500, List.of())); // 5 EUR

        assertThat(kit.status()).isEqualTo(KitStatus.OVER_BUDGET);
        assertThat(kit.missingRequiredSlots())
                .as("a required product is never dropped to fit a budget").isEmpty();
        assertThat(kit.remainingCents()).isNegative();
    }

    @Test
    void aWorkableBudgetTrimsOptionalItemsOnly() {
        var full = assembler.assemble(brief("soft-glam", 0, List.of()));
        int tight = full.totalCents() - full.optionalTotalCents();
        var trimmed = assembler.assemble(brief("soft-glam", tight, List.of()));

        assertThat(trimmed.totalCents()).isLessThanOrEqualTo(full.totalCents());
        assertThat(trimmed.missingRequiredSlots()).isEmpty();
        assertThat(trimmed.items()).filteredOn(MakeupKitAssembler.KitItem::essential)
                .as("essentials survive every trim").isNotEmpty();
    }

    @Test
    void swappingAProductKeepsTheKitCompleteAndOnlyEverOffersTheSameCategory() {
        var before = assembler.assemble(brief("soft-glam", 0, List.of()));
        var lipstick = before.items().stream().filter(i -> "lipstick".equals(i.slot()))
                .findFirst().orElseThrow();
        assertThat(lipstick.alternatives()).as("a swap needs somewhere to go").isNotEmpty();

        // Every alternative must belong to the same category, or a swap could break the kit.
        for (var alt : lipstick.alternatives()) {
            assertThat(catalog.byId(alt.externalId())).isNotNull()
                    .extracting(MakeupPilotCatalog.MakeupProduct::category).isEqualTo("lipstick");
        }

        String swapTo = lipstick.alternatives().get(0).externalId();
        var after = assembler.assemble(brief("soft-glam", 0, List.of()),
                new MakeupKitAssembler.Preferences(Map.of("lipstick", swapTo), false, null, "soft-glam"));

        assertThat(after.items()).filteredOn(i -> "lipstick".equals(i.slot()))
                .singleElement()
                .satisfies(i -> assertThat(i.externalId()).isEqualTo(swapTo));
        assertThat(after.status().isPurchasable()).as("a swap must never break completeness").isTrue();
        assertThat(after.missingRequiredSlots()).isEmpty();
    }

    @Test
    void makeItCheaperActuallyCostsLessAndStaysComplete() {
        var normal = assembler.assemble(brief("soft-glam", 0, List.of()));
        var cheaper = assembler.assemble(brief("soft-glam", 0, List.of()),
                new MakeupKitAssembler.Preferences(Map.of(), true, null, "soft-glam"));

        assertThat(cheaper.totalCents()).isLessThanOrEqualTo(normal.totalCents());
        assertThat(cheaper.missingRequiredSlots()).isEmpty();
    }

    /**
     * A look that prefers a bold lip must show a bold shade when the catalog has one, not merely pick a
     * product that happens to contain it. The row names the reason it was chosen.
     */
    @Test
    void aLookWithALipPreferenceShowsAShadeThatMatchesIt() {
        var kit = assembler.assemble(brief("bold-evening", 0, List.of()));
        var lipstick = kit.items().stream().filter(i -> "lipstick".equals(i.slot()))
                .findFirst().orElseThrow();
        var product = catalog.byId(lipstick.externalId());

        boolean catalogHasABoldLip = catalog.availableIn("lipstick").stream()
                .anyMatch(p -> MakeupLook.BOLD_EVENING.preferredLipShades().stream().anyMatch(p::hasShadeFamily));
        org.junit.jupiter.api.Assumptions.assumeTrue(catalogHasABoldLip,
                "no named bold lip shade in the catalog — nothing to prefer");

        assertThat(MakeupLook.BOLD_EVENING.preferredLipShades())
                .as("the chosen lipstick must actually carry a shade this look wants")
                .anySatisfy(family -> assertThat(product.hasShadeFamily(family)).isTrue());
        // And the shade shown on the row is one of those, not merely the first the retailer listed.
        var shown = product.shades().stream()
                .filter(s -> s.name().equals(lipstick.shadeName())).findFirst().orElseThrow();
        assertThat(shown.colorFamily())
                .as("the row must name the shade that justifies the pick")
                .isIn(MakeupLook.BOLD_EVENING.preferredLipShades());
    }

    /** The tool a kit buys has to be able to apply something. */
    @Test
    void theToolsSlotBuysAnApplicatorRatherThanASharpener() {
        var kit = assembler.assemble(brief("natural-everyday", 0, List.of()));
        var tool = kit.items().stream().filter(i -> "tools".equals(i.slot())).findFirst().orElseThrow();

        assertThat(tool.name().toLowerCase())
                .as("the single tool in a kit must be something you apply makeup with, not a sharpener")
                .matches(".*(brush|kist|sponge|spuzv|spužv|blender|applicator|aplikator).*");
    }

    /** Offering a one-store option that cannot finish the kit trades completeness for convenience. */
    @Test
    void aSingleStoreOptionIsOnlyOfferedWhenThatStoreCanFinishTheKit() {
        for (String look : List.of("natural-everyday", "soft-glam", "full-glam")) {
            for (String store : assembler.singleStoreOptions(look)) {
                var kit = assembler.assemble(brief(look, 0, List.of()),
                        new MakeupKitAssembler.Preferences(Map.of(), false, store, look));
                assertThat(kit.missingRequiredSlots())
                        .as("%s was offered as a one-store option for %s", store, look).isEmpty();
            }
        }
    }

    @Test
    void anUnknownLookFallsBackToTheEverydayOneRatherThanFailing() {
        var kit = assembler.assemble(brief("nepostojeci-look", 0, List.of()));
        assertThat(kit.lookKey()).isEqualTo("natural-everyday");
        assertThat(kit.status().isPurchasable()).isTrue();
    }
}
