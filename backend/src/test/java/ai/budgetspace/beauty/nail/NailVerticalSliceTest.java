package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.KitStatus;
import ai.budgetspace.beauty.dto.NailDesignSpecDto;
import ai.budgetspace.beauty.dto.NailLookBriefDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vertical slice, exercised end to end against the REAL pilot catalog - no mocks, no stubs.
 *
 * <p>Every assertion here is about the user-visible promise: the prompt is understood, one spec drives all
 * three outputs, the kit is arithmetically honest, owned items really are removed, and the paths that must
 * refuse actually refuse.</p>
 */
class NailVerticalSliceTest {

    private final NailIntentExtractor extractor = new NailIntentExtractor();
    private final NailDesignResolver resolver = new NailDesignResolver();
    private final NailDesignDiagramRenderer diagram = new NailDesignDiagramRenderer(resolver);
    private final NailSalonBriefBuilder salon = new NailSalonBriefBuilder(resolver);
    private final NailPilotCatalog catalog = new NailPilotCatalog();
    private final NailKitAssembler assembler = new NailKitAssembler(catalog);

    private NailLookBriefDto brief(String prompt, NailLookBriefDto.ExecutionMode mode, int budgetCents) {
        return extractor.parse(prompt, mode, budgetCents).brief();
    }

    // ---------------------------------------------------------------------------------- parsing

    @Test
    void parsesTheBurgundyCatEyePromptFromTheSpec() {
        NailLookBriefDto b = brief(
                "Zelim kratke almond nokte u boji visnje, s cat-eye efektom i dva diskretna zlatna detalja na prstenjacima.",
                null, 0);

        assertThat(b.design().shape()).isEqualTo(NailDesignSpecDto.Shape.ALMOND);
        assertThat(b.design().length()).isEqualTo(NailDesignSpecDto.Length.SHORT);
        assertThat(b.design().baseColorKey()).isEqualTo("burgundy");
        assertThat(b.design().baseColorHex()).isEqualTo("#5C0A22");
        assertThat(b.design().activeEffects()).contains(NailDesignSpecDto.Effect.CAT_EYE);
        assertThat(b.design().accentFingers()).contains(NailDesignSpecDto.Finger.RING);
        assertThat(b.needsExecutionModeAnswer()).as("salon vs home is never guessed").isTrue();
    }

    @Test
    void negatedEffectsAreNotApplied() {
        NailLookBriefDto b = brief("Kratki crveni nokti, ne zelim cat-eye", null, 0);
        assertThat(b.design().activeEffects()).doesNotContain(NailDesignSpecDto.Effect.CAT_EYE);
        assertThat(b.design().baseColorKey()).isEqualTo("red");
    }

    // ------------------------------------------------------------------- the "nemam" requirement

    @Test
    void nemamMakesASlotMissingAndImamMakesItOwned() {
        var owned = extractor.parseOwnership(NailIntentExtractor.normalize("imam lampu"));
        assertThat(owned).singleElement().satisfies(i -> {
            assertThat(i.slot()).isEqualTo("lamp");
            assertThat(i.satisfiesRequirement()).isTrue();
        });

        var missing = extractor.parseOwnership(NailIntentExtractor.normalize("nemam lampu"));
        assertThat(missing).singleElement().satisfies(i -> {
            assertThat(i.slot()).isEqualTo("lamp");
            assertThat(i.satisfiesRequirement()).as("a stated absence is not ownership").isFalse();
        });
    }

    @Test
    void theMixedClauseKeepsOwnedAndMissingApart() {
        // The case that catches scope bugs: one negation must not leak across the contrast conjunction and
        // flip the owned lamp into a missing one.
        var items = extractor.parseOwnership(NailIntentExtractor.normalize("imam lampu, ali nemam bazu ni top"));

        assertThat(items.stream().filter(i -> i.satisfiesRequirement()).map(i -> i.slot()))
                .containsExactly("lamp");
        assertThat(items.stream().filter(i -> !i.satisfiesRequirement()).map(i -> i.slot()))
                .contains("base", "top");
    }

    @Test
    void vecImamAndNeTrebamAndBezAreAllUnderstood() {
        assertThat(extractor.parseOwnership(NailIntentExtractor.normalize("vec imam bazu")))
                .singleElement().satisfies(i -> assertThat(i.satisfiesRequirement()).isTrue());
        // "ne trebam" is an exclusion, not an absence - the slot is satisfied, not required.
        assertThat(extractor.parseOwnership(NailIntentExtractor.normalize("ne trebam lampu")))
                .singleElement().satisfies(i -> assertThat(i.satisfiesRequirement()).isTrue());
        assertThat(extractor.parseOwnership(NailIntentExtractor.normalize("bez lampe")))
                .singleElement().satisfies(i -> assertThat(i.satisfiesRequirement()).isTrue());
        assertThat(extractor.parseOwnership(NailIntentExtractor.normalize("nemam nista")))
                .as("nemam nista means every slot is missing").hasSizeGreaterThan(4);
    }

    // ------------------------------------------------------------------------------ one spec, three outputs

    @Test
    void theSameSpecDrivesDiagramBriefAndKit() {
        NailLookBriefDto b = brief("kratki almond nokti boje visnje, zlatni detalj na prstenjaku",
                NailLookBriefDto.ExecutionMode.SALON, 0);

        String svg = diagram.render(b.design());
        var salonBrief = salon.build(b.design());

        assertThat(svg).startsWith("<svg").contains("#5C0A22");
        assertThat(salonBrief.placement()).hasSize(10);
        assertThat(salonBrief.specification()).extracting(NailSalonBriefBuilder.SpecLine::labelHr)
                .contains("Oblik", "Duljina", "Boja", "Naglasak");
        assertThat(salonBrief.showToTechnician()).contains("almond").contains("prstenjak");
        assertThat(resolver.resolve(b.design())).hasSize(10);
    }

    @Test
    void theDiagramIsDeterministic() {
        NailLookBriefDto b = brief("kratki almond nokti boje visnje", null, 0);
        assertThat(diagram.render(b.design())).isEqualTo(diagram.render(b.design()));
    }

    @Test
    void theSalonBriefCannotReachTheCatalogByConstruction() {
        // Structural, not a string check: the builder has no catalog collaborator to reach for.
        assertThat(NailSalonBriefBuilder.class.getDeclaredConstructors()).hasSize(1);
        assertThat(NailSalonBriefBuilder.class.getDeclaredConstructors()[0].getParameterTypes())
                .containsExactly(NailDesignResolver.class);
    }

    // --------------------------------------------------------------------------------- the at-home kit

    @Test
    void thePilotCatalogIsLoadedAndCoversTheWholeGraph() {
        assertThat(catalog.isLoaded()).isTrue();
        assertThat(catalog.products()).hasSizeBetween(20, 50);
        for (var slot : NailKitAssembler.REGULAR_POLISH_GRAPH) {
            if (slot.required()) {
                assertThat(catalog.availableForSlot(slot.key()))
                        .as("required slot %s must have an in-stock product", slot.key()).isNotEmpty();
            }
        }
    }

    @Test
    void aCompleteKitTotalsExactlyTheSumOfItsItems() {
        var kit = assembler.assemble(brief("kratki nokti, boja visnje, kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.status().isComplete()).isTrue();
        assertThat(kit.missingRequiredSlots()).isEmpty();
        int summed = kit.items().stream().mapToInt(NailKitAssembler.KitItem::priceCents).sum();
        assertThat(kit.totalCents()).as("the total is the sum, not an estimate").isEqualTo(summed);
        assertThat(kit.essentialTotalCents() + kit.optionalTotalCents()).isEqualTo(kit.totalCents());
        assertThat(kit.items()).allSatisfy(i -> assertThat(i.priceCents()).isPositive());
    }

    @Test
    void everyRequiredSlotIsFilledAndEssentialsAreMarked() {
        var kit = assembler.assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        List<String> essentialSlots = kit.items().stream()
                .filter(NailKitAssembler.KitItem::essential)
                .map(NailKitAssembler.KitItem::slot).toList();
        assertThat(essentialSlots).contains("base", "color", "top", "removal");
    }

    @Test
    void ownedItemsAreRemovedFromTheTotalNotJustLabelled() {
        var withoutOwned = assembler.assemble(brief("kratki nokti kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));
        var withOwned = assembler.assemble(brief("kratki nokti kod kuce, vec imam bazu i nadlak",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(withOwned.ownedItems()).extracting(NailKitAssembler.KitItem::slot)
                .contains("base", "top");
        assertThat(withOwned.totalCents())
                .as("owning two items must actually reduce the total")
                .isLessThan(withoutOwned.totalCents());
        assertThat(withOwned.items()).extracting(NailKitAssembler.KitItem::slot)
                .doesNotContain("base", "top");
    }

    @Test
    void anUnmatchableShadeBecomesAnAssumptionNotAClaim() {
        // This retailer numbers its shades and publishes no colour name, so the honest answer is a candidate
        // plus the swatch - never "this is your burgundy".
        var kit = assembler.assemble(brief("kratki nokti boje visnje kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.status()).isEqualTo(KitStatus.COMPLETE_WITH_ASSUMPTIONS);
        assertThat(kit.assumptions()).extracting(a -> a.field()).contains("shade");
        assertThat(kit.items()).filteredOn(i -> "color".equals(i.slot()))
                .allSatisfy(i -> assertThat(i.noteHr()).contains("swatch"));
    }

    @Test
    void anImpossibleBudgetIsReportedOverBudgetRatherThanTrimmedBelowCompleteness() {
        var kit = assembler.assemble(brief("kratki nokti kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 100)); // EUR 1.00 - cannot buy a full kit

        assertThat(kit.status()).isEqualTo(KitStatus.OVER_BUDGET);
        assertThat(kit.missingRequiredSlots()).as("required items are never dropped to fit a budget").isEmpty();
        List<String> slots = kit.items().stream().map(NailKitAssembler.KitItem::slot).toList();
        assertThat(slots).contains("base", "color", "top", "removal");
        assertThat(kit.remainingCents()).isNegative();
    }

    @Test
    void aGenerousBudgetLeavesRemainingPositive() {
        var kit = assembler.assemble(brief("kratki nokti kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 5000));
        assertThat(kit.remainingCents()).isNotNegative();
        assertThat(kit.totalCents()).isLessThanOrEqualTo(5000);
    }

    @Test
    void everyKitItemCarriesARealBuyLinkAndProvenance() {
        var kit = assembler.assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.items()).allSatisfy(i -> {
            assertThat(i.productUrl()).startsWith("https://");
            assertThat(i.retailer()).isNotBlank();
            assertThat(i.name()).isNotBlank();
        });
        assertThat(kit.catalogProvenanceHr()).contains("Golden Rose");
        assertThat(kit.retailerCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------ refusals

    @Test
    void forbiddenSystemsAreRefusedByName() {
        var parsed = extractor.parse("zelim polygel nadogradnju kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0);
        assertThat(parsed.forbiddenSystemRequested()).isTrue();
        assertThat(parsed.forbiddenSystemNote()).isNotBlank();
    }

    @Test
    void negatingAForbiddenSystemIsNotARequestForIt() {
        var parsed = extractor.parse("ne zelim polygel, samo obicni lak",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0);
        assertThat(parsed.forbiddenSystemRequested())
                .as("'ne zelim polygel' must not trip the forbidden-system block").isFalse();
    }

    @Test
    void gelIsRecognisedSoItCanBeRoutedAwayFromTheHomeKit() {
        assertThat(extractor.parse("trajni lak kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0)
                .gelRequested()).isTrue();
    }

    @Test
    void aVolunteeredHealthConcernIsDetectedForRefusalNotDiagnosis() {
        var parsed = extractor.parse("nokat mi je upaljen i boli me, zelim lak kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0);
        assertThat(parsed.healthConcernDetected()).isTrue();
    }
}
