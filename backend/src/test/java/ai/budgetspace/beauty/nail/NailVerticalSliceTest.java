package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.KitStatus;
import ai.budgetspace.beauty.dto.NailDesignSpecDto;
import ai.budgetspace.beauty.dto.NailLookBriefDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        // A lower bound only. An upper bound here was asserting "the catalog has not grown", which is the
        // opposite of what we want from it — it failed the moment capability-driven sourcing added shades.
        assertThat(catalog.products()).hasSizeGreaterThan(20);
        for (var slot : NailKitAssembler.REGULAR_POLISH_GRAPH) {
            if (slot.required()) {
                assertThat(catalog.availableForSlot(slot.key()))
                        .as("required slot %s must have an in-stock product", slot.key()).isNotEmpty();
            }
        }
    }

    @Test
    void aCompleteKitTotalsExactlyTheSumOfItsItems() {
        var kit = assembler.assemble(brief("kratki nokti kod kuce",
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
        // No colour was NAMED, so no colour capability is required and the kit can complete. The shade it
        // picks is still only a candidate: this retailer numbers its shades and publishes no colour name.
        var kit = assembler.assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.status()).isEqualTo(KitStatus.COMPLETE_WITH_ASSUMPTIONS);
        assertThat(kit.assumptions()).extracting(a -> a.field()).contains("shade");
        assertThat(kit.items()).filteredOn(i -> "color".equals(i.slot()))
                .allSatisfy(i -> assertThat(i.noteHr()).contains("swatch"));
    }

    /**
     * The gate, tested as a RULE rather than against one colour.
     *
     * <p>This used to hardcode burgundy as the example of an unprovable colour, which meant the test was
     * really asserting "the catalog still cannot do burgundy". Capability-driven sourcing then found essie
     * "Lak za nokte – 50 bordeaux" and the test failed on good news. It now finds a colour the shelf cannot
     * currently prove and checks the gate fires for that one, so it keeps testing the rule as the catalog
     * grows underneath it.</p>
     */
    @Test
    void aNamedColourWithNoRetailerEvidenceCannotBeCalledComplete() {
        record Colour(String key, String promptWord) { }
        List<Colour> colours = List.of(
                new Colour("burgundy", "visnje"), new Colour("red", "crvene"), new Colour("nude", "nude"),
                new Colour("pink", "roza"), new Colour("black", "crne"), new Colour("white", "bijele"),
                new Colour("brown", "smede"));

        NailCapabilityEvidence.Capability unprovable = null;
        Colour picked = null;
        for (Colour c : colours) {
            var required = NailCapabilityEvidence.requiredBy(
                    brief("kratki " + c.promptWord() + " nokti", NailLookBriefDto.ExecutionMode.AT_HOME, 0)
                            .design(), "regular-polish");
            var gap = required.stream()
                    .filter(cap -> cap.name().startsWith("COLOR_"))
                    .filter(cap -> catalog.availableForSlot("color", "regular-polish").stream()
                            .noneMatch(p -> NailCapabilityEvidence.proves(p, cap)))
                    .findFirst();
            if (gap.isPresent()) { unprovable = gap.get(); picked = c; break; }
        }

        assumeTrue(unprovable != null,
                "every colour the parser understands is now backed by a real product — nothing left to gate");

        var kit = assembler.assemble(brief("kratki " + picked.promptWord() + " nokti kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.status())
                .as("'%s' has no retailer evidence, so the kit cannot be purchasable", picked.key())
                .isEqualTo(KitStatus.INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE);
        assertThat(kit.status().isPurchasable()).isFalse();
        // The message the USER sees is Croatian, so that is what the gate has to name.
        String expected = unprovable.croatianLabel();
        assertThat(kit.missingRequiredSlots()).anySatisfy(m -> assertThat(m).contains(expected));
    }

    /**
     * The other half of the same rule, and the thing capability-driven sourcing bought: a colour the
     * RETAILER names now completes, with the real product in the colour slot and no shade guess attached.
     */
    @Test
    void aColourTheRetailerNamesCompletesWithThatProduct() {
        var kit = assembler.assemble(brief("kratki nokti boje visnje kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.status().isPurchasable())
                .as("burgundy is backed by a product whose own title says bordeaux").isTrue();
        assertThat(kit.missingRequiredSlots()).isEmpty();

        var colour = kit.items().stream().filter(i -> "color".equals(i.slot())).findFirst().orElseThrow();
        var product = catalog.products().stream()
                .filter(p -> colour.externalId().equals(p.externalId())).findFirst().orElseThrow();
        assertThat(NailCapabilityEvidence.proves(product, NailCapabilityEvidence.Capability.COLOR_BURGUNDY))
                .as("the picked shade must be the one that proves the colour, not merely the cheapest")
                .isTrue();
        assertThat(kit.assumptions())
                .as("a named colour raises no 'check the swatch' assumption")
                .noneMatch(a -> "shade".equals(a.field()));
    }

    @Test
    void aGenericPolishCannotSatisfyACatEyeRequest() {
        // No polish in this catalog proves a cat-eye and no magnet exists at all, so a cat-eye request must
        // NOT be quietly answered with ordinary glossy lacquer.
        var kit = assembler.assemble(brief("kratki nokti s cat-eye efektom kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.status()).isEqualTo(KitStatus.INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE);
        assertThat(kit.missingRequiredSlots()).anySatisfy(m -> assertThat(m).contains("cat-eye"));
        assertThat(kit.missingRequiredSlots()).anySatisfy(m -> assertThat(m).contains("magnet"));
    }

    /**
     * The demo prompt, end to end. It used to assert the gold detail was missing, which was really
     * asserting "the catalog still has no gold product" — a shelf limitation dressed up as a rule, and it
     * failed the moment beauty-shop.hr's self-adhesive gold stickers closed that gap. What must hold
     * forever is the RULE: the prompt asks for a cat-eye, no air-drying magnetic polish exists in
     * Croatia, so the kit says so instead of shipping ordinary lacquer under the same name.
     */
    @Test
    void theWholePrimaryPromptIsHonestlyReportedAsUnreproducible() {
        var prompt = "Zelim kratke almond burgundy cat-eye nokte s dva diskretna zlatna detalja.";

        for (String system : List.of("regular-polish", "press-on")) {
            var kit = assembler.assemble(brief(prompt, NailLookBriefDto.ExecutionMode.AT_HOME, 0),
                    new NailKitAssembler.Preferences(java.util.Map.of(), false, null, system));

            assertThat(kit.status())
                    .as("%s cannot reproduce the primary prompt", system)
                    .isEqualTo(KitStatus.INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE);
            assertThat(kit.missingRequiredSlots())
                    .as("%s must name the cat-eye as the thing it cannot do", system)
                    .anySatisfy(m -> assertThat(m).contains("cat-eye"));
        }

        // And the half that sourcing DID close is no longer reported as missing: a gold detail is now a
        // real product in a real slot, so claiming otherwise would be its own kind of dishonesty.
        var polish = assembler.assemble(brief(prompt, NailLookBriefDto.ExecutionMode.AT_HOME, 0),
                new NailKitAssembler.Preferences(java.util.Map.of(), false, null, "regular-polish"));
        assertThat(polish.missingRequiredSlots())
                .as("gold is backed by a product whose own title says Gold")
                .noneSatisfy(m -> assertThat(m).contains("zlatni detalj"));
    }

    @Test
    void capabilitiesComeFromRetailerTextNeverFromAShadeNumber() {
        var catEyeSet = catalog.products().stream()
                .filter(p -> p.name().toLowerCase().contains("cat eye")).findFirst().orElseThrow();
        assertThat(NailCapabilityEvidence.proves(catEyeSet, NailCapabilityEvidence.Capability.CAT_EYE))
                .as("the retailer's own title says Cat Eye").isTrue();

        var numberedShade = catalog.products().stream()
                .filter(p -> "color".equals(p.kitSlot()) && p.shadeName() != null).findFirst().orElseThrow();
        assertThat(NailCapabilityEvidence.proofsFor(numberedShade))
                .as("a numbered shade like '%s' proves no colour", numberedShade.shadeName())
                .noneMatch(pr -> pr.capability().name().startsWith("COLOR_"));
    }

    /**
     * A real false positive from the 2026-07-31 dm.hr sourcing probe: the magnet pattern matched
     * <em>"Poklon-paket Magnetic Man"</em>, a men's gift set. On that evidence a kit would have declared
     * itself able to do a cat-eye. A capability claim now needs the retailer's own words to be about nails.
     */
    @Test
    void aCapabilityClaimNeedsTheProductToBeAboutNails() {
        var giftSet = productNamed("Poklon-paket Magnetic Man");
        assertThat(NailCapabilityEvidence.proves(giftSet, NailCapabilityEvidence.Capability.MAGNET_TOOL))
                .as("a men's gift set is not a cat-eye magnet").isFalse();
        assertThat(NailCapabilityEvidence.proofsFor(giftSet)).isEmpty();

        var chromeShampoo = productNamed("Chrome Shine šampon za kosu");
        assertThat(NailCapabilityEvidence.proves(chromeShampoo, NailCapabilityEvidence.Capability.CHROME))
                .as("a shampoo cannot prove a chrome nail effect").isFalse();

        // And the fix must not cost us a real one.
        var realMagnet = productNamed("Magnet za nokte, cat eye efekt, 1 kom.");
        assertThat(NailCapabilityEvidence.proves(realMagnet, NailCapabilityEvidence.Capability.MAGNET_TOOL))
                .as("a nail magnet still proves the tool").isTrue();
        var realChrome = productNamed("Chrome lak za nokte – 01 Silver");
        assertThat(NailCapabilityEvidence.proves(realChrome, NailCapabilityEvidence.Capability.CHROME))
                .as("a chrome nail polish still proves the effect").isTrue();
    }

    /**
     * The gate requirement 3 asks for, as a rule: <strong>a word is not a technique.</strong>
     *
     * <p>"Magnetic", "magnetni" and "captivating" are marketing adjectives that appear on perfume, on
     * mascara and on perfectly ordinary lacquer. A cat-eye is made by dragging a MAGNET through a wet
     * coat of iron-flake polish, so the only thing that can prove a brush-on product does it is the
     * retailer's own application instructions saying a magnet is used. Without that step the claim is
     * a description of the copywriting, not of the bottle.</p>
     */
    @Test
    void figurativeMagneticWordsCannotProveACatEye() {
        var marketing = productNamed("Magnetic Attraction lak za nokte – 04 Captivating",
                "Nanesite dva tanka sloja i ostavite da se osuši. Za dulji sjaj nadlakirajte nadlakom.");
        assertThat(NailCapabilityEvidence.proofsFor(marketing))
                .as("a captivating, magnetic-sounding lacquer is still just lacquer")
                .noneMatch(p -> p.capability() == NailCapabilityEvidence.Capability.CAT_EYE
                        || p.capability() == NailCapabilityEvidence.Capability.MAGNET_TOOL);

        // The Croatian half of the same trap, and the harder one: "magnetni lak" reads like a claim.
        var croatian = productNamed("Magnetni lak za nokte – 12 Magnetna Elegancija", null);
        assertThat(NailCapabilityEvidence.proofsFor(croatian))
                .as("'magnetni' on a bottle describes the paint, and proves neither effect nor tool")
                .noneMatch(p -> p.capability() == NailCapabilityEvidence.Capability.CAT_EYE
                        || p.capability() == NailCapabilityEvidence.Capability.MAGNET_TOOL);

        // The sharpest case: the title says the words, the instructions say nothing about a magnet.
        var namedButUninstructed = productNamed("Cat Eye lak za nokte – 03",
                "Nanesite na pripremljen nokat u dva sloja. Ostaviti da se osuši na zraku.");
        assertThat(NailCapabilityEvidence.proves(namedButUninstructed,
                NailCapabilityEvidence.Capability.CAT_EYE))
                .as("a brush-on product with no published magnet step cannot make a cat-eye")
                .isFalse();

        // ...and the gate is not simply always-false in both directions.
        var withMagnetStep = productNamed("Cat Eye lak za nokte – 03",
                "Nanesite sloj laka i dok je još mokar približite magnet noktu 5-10 sekundi.");
        assertThat(NailCapabilityEvidence.proves(withMagnetStep, NailCapabilityEvidence.Capability.CAT_EYE))
                .as("published instructions that name the magnet step DO prove it").isTrue();

        // A press-on plate needs no instructions: the effect is printed on it before it is sold.
        var pressOn = productNamed("what the fake! umjetni nokti – 02 Cat Eye", null);
        assertThat(NailCapabilityEvidence.proves(pressOn, NailCapabilityEvidence.Capability.CAT_EYE))
                .as("a pre-made plate carries the effect already").isTrue();
    }

    /**
     * A cat-eye needs a magnetic polish AND a magnet, and cataloguing only the tool must never look like
     * progress. Croatia sells the magnet (cicinails 3,00 €, beauty-shop 3,00 €) and sells no air-drying
     * magnetic polish at all, so a magnet in the catalog would be a €3 tool in a kit that still cannot
     * make the effect.
     */
    @Test
    void aMagnetOnItsOwnCannotSatisfyACatEyeRequest() {
        var required = NailCapabilityEvidence.requiredBy(
                brief("kratki nokti s cat-eye efektom", NailLookBriefDto.ExecutionMode.AT_HOME, 0).design(),
                "regular-polish");

        assertThat(required)
                .as("the polish that makes the effect is required alongside the tool that shapes it")
                .contains(NailCapabilityEvidence.Capability.CAT_EYE,
                        NailCapabilityEvidence.Capability.MAGNET_TOOL);

        var magnetOnly = productNamed("Magnet za nokte, cat eye efekt, 1 kom.", null);
        assertThat(NailCapabilityEvidence.proves(magnetOnly, NailCapabilityEvidence.Capability.CAT_EYE))
                .as("the tool is not the effect").isFalse();
    }

    /**
     * A gold-coloured TOOL is not a gold detail. Both of these came back from the 2026-08-03 sweep of
     * Croatian nail-supply shops under the gold pattern, both are genuinely about nails, and neither puts
     * gold on a nail.
     */
    @Test
    void aGoldColouredToolCannotProveAGoldDetail() {
        for (String toolName : List.of("ZLATNA KLIJEŠTA ZA UMJETNE NOKTE", "Kist za nail art Aquarelle Gold #2",
                "ROSE GOLD ŠABLONE ZA NOKTE", "SMART GOLD BRUSILICA ZA NOKTE")) {
            assertThat(NailCapabilityEvidence.proves(productNamed(toolName),
                    NailCapabilityEvidence.Capability.GOLD_DETAIL))
                    .as("'%s' is a gold tool, not a gold nail", toolName).isFalse();
        }

        assertThat(NailCapabilityEvidence.proves(productNamed("Naljepnica za nokte Gold Glam 17"),
                NailCapabilityEvidence.Capability.GOLD_DETAIL))
                .as("the sticker that actually goes on the nail still proves it").isTrue();
    }

    // ------------------------------------------------------------------------ the accent (nail art) slot

    @Test
    void aGoldAccentIsBoughtAsItsOwnProductAndCompletesTheLook() {
        var kit = assembler.assemble(brief("kratki nude nokti sa zlatnim detaljem na prstenjaku kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.status().isPurchasable())
                .as("nude is named by a retailer and gold is now a real product").isTrue();
        assertThat(kit.missingRequiredSlots()).isEmpty();

        var accent = kit.items().stream().filter(i -> "accent".equals(i.slot())).findFirst().orElseThrow();
        var product = catalog.products().stream()
                .filter(p -> accent.externalId().equals(p.externalId())).findFirst().orElseThrow();
        assertThat(NailCapabilityEvidence.proves(product, NailCapabilityEvidence.Capability.GOLD_DETAIL))
                .as("the accent item must be the thing that proves the gold").isTrue();
        assertThat(accent.essential()).as("she asked for the detail, so it is not optional").isTrue();
    }

    @Test
    void aDesignWithNoSecondColourIsNeverBilledForNailArt() {
        var kit = assembler.assemble(brief("kratki nude nokti kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.items()).extracting(NailKitAssembler.KitItem::slot)
                .as("a one-colour manicure buys no decoration").doesNotContain("accent");
    }

    @Test
    void anAccentColourWeCannotProveLeavesTheSlotEmptyRatherThanSubstituting() {
        // The only decoration this catalog stocks is gold. A request for a different accent colour must
        // report the gap - offering the gold sticker instead would be answering a question nobody asked.
        var kit = assembler.assemble(brief("kratki nude nokti s crvenim detaljem na prstenjaku kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.items()).filteredOn(i -> "accent".equals(i.slot()))
                .as("no red decoration exists, so nothing may fill the slot").isEmpty();
        assertThat(kit.missingRequiredSlots()).contains("Detalj (nail art)");
        assertThat(kit.status()).isEqualTo(KitStatus.INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE);
    }

    /** A bare product carrying only a name — enough for the evidence layer, which reads retailer text. */
    private NailPilotCatalog.PilotProduct productNamed(String name) {
        return productNamed(name, null);
    }

    /** A product carrying a name AND the retailer's own published application instructions. */
    private NailPilotCatalog.PilotProduct productNamed(String name, String applicationEvidence) {
        return new NailPilotCatalog.PilotProduct(
                "test-" + name.hashCode(), name, null, null, null, "Test", null,
                java.math.BigDecimal.ONE, null, true, true, null, null, null, null,
                "regular-polish", "color", "color", null, null, false, true,
                null, applicationEvidence, "UNKNOWN", "UNKNOWN", false, "2026-07-31",
                "automatic", "2026-07-31T00:00:00Z", "reachable", "pilot-unreviewed");
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
        // Two retailers: colour and base live at Golden Rose, removal and the file at dm. That split is
        // exactly why "use one store" has to report a real trade-off instead of pretending it is free.
        assertThat(kit.retailerCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------ press-ons

    @Test
    void aPressOnKitFillsItsOwnGraph() {
        var kit = assembler.assemble(brief("kratki almond nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0),
                new NailKitAssembler.Preferences(java.util.Map.of(), false, null, "press-on"));

        assertThat(kit.status().isComplete()).isTrue();
        assertThat(kit.items()).extracting(NailKitAssembler.KitItem::slot)
                .contains("press-on-set", "adhesive", "file", "removal");
        // Adhesive and removal are REQUIRED for press-ons: that is where a bad at-home job damages a nail.
        assertThat(kit.items()).filteredOn(i -> "adhesive".equals(i.slot()) || "removal".equals(i.slot()))
                .allSatisfy(i -> assertThat(i.essential()).isTrue());
    }

    @Test
    void aLengthTheNaturalNailCannotCarryRoutesToPressOnsNotAnExtensionSystem() {
        var kit = assembler.assemble(brief("jako duge nokte kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.items()).extracting(NailKitAssembler.KitItem::slot).contains("press-on-set");
        assertThat(kit.assumptions()).extracting(a -> a.field()).contains("system");
    }

    @Test
    void aPressOnSetWithNoPublishedSizingRaisesAFitAssumption() {
        var kit = assembler.assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0),
                new NailKitAssembler.Preferences(java.util.Map.of(), false, null, "press-on"));

        assertThat(kit.assumptions()).extracting(a -> a.field()).contains("fit");
    }

    // ---------------------------------------------------------------------------------- refinements

    @Test
    void replaceThisPinsTheChosenProductAndKeepsTheKitComplete() {
        var before = assembler.assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0));
        var colorItem = before.items().stream().filter(i -> "color".equals(i.slot())).findFirst().orElseThrow();
        assertThat(colorItem.alternatives()).as("a swap needs somewhere to go").isNotEmpty();

        String swapTo = colorItem.alternatives().get(0).externalId();
        var after = assembler.assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0),
                new NailKitAssembler.Preferences(java.util.Map.of("color", swapTo), false, null, null));

        assertThat(after.items()).filteredOn(i -> "color".equals(i.slot()))
                .singleElement().satisfies(i -> assertThat(i.externalId()).isEqualTo(swapTo));
        assertThat(after.status().isComplete()).as("a swap must never break completeness").isTrue();
        assertThat(after.missingRequiredSlots()).isEmpty();
    }

    @Test
    void alternativesAreAlwaysFromTheSameSlotSoASwapCannotBreakTheKit() {
        var kit = assembler.assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0));
        for (var item : kit.items()) {
            for (var alt : item.alternatives()) {
                assertThat(catalog.forSlot(item.slot())).extracting(p -> p.externalId())
                        .as("alternative for %s must belong to that slot", item.slot())
                        .contains(alt.externalId());
            }
        }
    }

    @Test
    void makeItCheaperNeverDropsARequiredSlot() {
        var normal = assembler.assemble(brief("kratki nokti kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0));
        var cheaper = assembler.assemble(brief("kratki nokti kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0),
                new NailKitAssembler.Preferences(java.util.Map.of(), true, null, null));

        assertThat(cheaper.totalCents()).isLessThanOrEqualTo(normal.totalCents());
        assertThat(cheaper.missingRequiredSlots()).isEmpty();
        assertThat(cheaper.items()).extracting(NailKitAssembler.KitItem::slot)
                .contains("base", "color", "top", "removal");
    }

    @Test
    void useOneStoreIsOnlyOfferedWhenAStoreCanActuallyCompleteTheKit() {
        // In this pilot no single retailer stocks every required slot, so the honest answer is "none".
        // Offering a store that cannot finish the kit would trade completeness for convenience.
        List<String> options = assembler.singleStoreOptions("regular-polish");
        for (String retailer : options) {
            var kit = assembler.assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0),
                    new NailKitAssembler.Preferences(java.util.Map.of(), false, retailer, null));
            assertThat(kit.missingRequiredSlots())
                    .as("%s was offered as a one-store option, so it must complete the kit", retailer).isEmpty();
        }
    }

    /**
     * The design changes the answer. Only one retailer in this pilot sells a gold decoration, so a store
     * that stocks every base slot still cannot finish a kit that needs an accent — and it must not be
     * offered as if it could, because the assembler would then quietly source the accent somewhere else.
     */
    @Test
    void useOneStoreAccountsForTheSlotsTheDesignItselfAdds() {
        var plain = brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0);
        var withAccent = brief("kratki nude nokti sa zlatnim detaljem na prstenjaku kod kuce",
                NailLookBriefDto.ExecutionMode.AT_HOME, 0);

        // Non-vacuous by construction: dm.hr CAN finish a plain press-on kit alone, which is exactly why
        // the accent design has to take it off the list rather than leave the offer standing.
        assertThat(assembler.singleStoreOptions("press-on", plain.design()))
                .as("a plain press-on kit really is completable at one store").contains("dm.hr");
        assertThat(assembler.singleStoreOptions("press-on", withAccent.design()))
                .as("dm.hr sells no gold decoration, so it cannot be the ONE store for this design")
                .doesNotContain("dm.hr");

        // And whatever survives the filter must genuinely need no second retailer.
        for (String system : List.of("regular-polish", "press-on")) {
            for (String retailer : assembler.singleStoreOptions(system, withAccent.design())) {
                var kit = assembler.assemble(withAccent,
                        new NailKitAssembler.Preferences(java.util.Map.of(), false, retailer, system));
                assertThat(kit.retailerCount())
                        .as("%s was offered as the ONE store for an accent design", retailer).isEqualTo(1);
            }
        }
    }

    // ------------------------------------------------------------- incomplete: a genuine catalog gap

    @Test
    void aRequiredSlotWithNoEligibleProductYieldsIncompleteAndNamesTheGap() {
        // The real pilot happens to cover every slot, so this state is unreachable with it. The fixture has
        // base/colour/top but its only remover is PROFESSIONAL-ONLY and its only file is OUT OF STOCK -
        // two different reasons a shelf can be empty, both of which must produce an honest Incomplete
        // rather than a kit the user cannot actually take off again.
        var gapCatalog = new NailPilotCatalog("/catalog/nail-pilot-gap-fixture.json");
        var gapAssembler = new NailKitAssembler(gapCatalog);

        var kit = gapAssembler.assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.status()).isEqualTo(KitStatus.INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE);
        assertThat(kit.status().isPurchasable()).as("an incomplete kit is never presented as buyable").isFalse();
        assertThat(kit.missingRequiredSlots()).contains("Turpija", "Skidanje laka");
        assertThat(kit.statusExplanationHr()).contains("Turpija").contains("Skidanje laka");
        // The slots it COULD fill are still shown, so the user can see how far the catalog got.
        assertThat(kit.items()).extracting(NailKitAssembler.KitItem::slot).contains("base", "color", "top");
    }

    @Test
    void aProfessionalOnlyProductIsNeverOfferedToAConsumer() {
        var gapCatalog = new NailPilotCatalog("/catalog/nail-pilot-gap-fixture.json");
        var kit = new NailKitAssembler(gapCatalog)
                .assemble(brief("kratki nokti kod kuce", NailLookBriefDto.ExecutionMode.AT_HOME, 0));

        assertThat(kit.items()).extracting(NailKitAssembler.KitItem::externalId)
                .doesNotContain("fixture-pro-removal");
        assertThat(kit.items()).flatExtracting(NailKitAssembler.KitItem::alternatives)
                .extracting(NailKitAssembler.Alternative::externalId)
                .as("a professional-only product must not appear even as a swap")
                .doesNotContain("fixture-pro-removal");
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
