package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.KitStatus;
import ai.budgetspace.beauty.dto.NailDesignSpecDto;
import ai.budgetspace.beauty.dto.NailLookBriefDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frozen MVP set: the prompts a real tester may type, and exactly what each one must do.
 *
 * <p><strong>Why a frozen list rather than the coverage matrix.</strong> The matrix measures how much the
 * catalog can do and is meant to move. This is the opposite instrument: a contract. Twelve prompts must
 * produce a buyable kit and four must refuse, <em>with the reason spelled out</em>, and any change to
 * either list has to be a deliberate edit rather than a side effect. Freezing the refusals matters most —
 * a gap that quietly becomes a "Complete" kit full of the wrong products is the failure this whole slice
 * is built to prevent, and it would otherwise look like progress.</p>
 *
 * <p>Kept in step with {@code docs/nail-mvp-test-set.md}, which is what a human runs by hand.</p>
 */
class NailMvpTestSetTest {

    private final NailIntentExtractor extractor = new NailIntentExtractor();
    private final NailDesignResolver resolver = new NailDesignResolver();
    private final NailDesignDiagramRenderer diagram = new NailDesignDiagramRenderer(resolver);
    private final NailSalonBriefBuilder salon = new NailSalonBriefBuilder(resolver);
    private final NailPilotCatalog catalog = new NailPilotCatalog();
    private final NailKitAssembler assembler = new NailKitAssembler(catalog);

    /** The demo case: the one prompt to put in front of a tester first. */
    static final String DEMO = "Kratki bordo almond nokti sa sjajnim završetkom i zlatnim detaljem na prstenjaku.";

    record Supported(String prompt, String system) { }

    /** A refusal, with the exact Croatian the user must be shown. Exact, so a change cannot slip through. */
    record Unsupported(String prompt, String system, List<String> expectedMissing) { }

    static final List<Supported> SUPPORTED = List.of(
            new Supported(DEMO, "regular-polish"),
            new Supported("Želim kratke burgundy nokte, sjajne.", "regular-polish"),
            new Supported("Želim kratke nude nokte, sjajne.", "regular-polish"),
            new Supported("Želim kratke nude nokte, mat.", "regular-polish"),
            new Supported("Želim kratke crvene nokte, sjajne.", "regular-polish"),
            new Supported("Želim kratke crne nokte, mat.", "regular-polish"),
            new Supported("Želim kratke bijele nokte, sjajne.", "regular-polish"),
            new Supported("Želim kratke roza nokte, sjajne.", "regular-polish"),
            new Supported("Želim kratke nude nokte sa zlatnim detaljem na prstenjaku.", "regular-polish"),
            new Supported("Želim kratke almond nokte, sjajne.", "press-on"),
            new Supported("Želim srednje duge almond nokte, sjajne.", "press-on"),
            new Supported("Želim duge coffin nokte, sjajne.", "press-on"));

    static final List<Unsupported> UNSUPPORTED = List.of(
            // The truthfulness test. Burgundy and gold are real; the cat-eye is not, and every cat-eye
            // product in Croatia is a UV/LED gel this pilot will not sell to a consumer.
            new Unsupported("Želim kratke almond burgundy cat-eye nokte s dva diskretna zlatna detalja.",
                    "regular-polish",
                    List.of("cat-eye efekt (postoji samo u setovima umjetnih noktiju, ne u sustavu koji je odabran)",
                            "magnet za cat-eye (nema nijednog provjerenog proizvoda u katalogu)")),
            new Unsupported("Želim kratke nokte s chrome efektom.", "regular-polish",
                    List.of("chrome efekt (nema nijednog provjerenog proizvoda u katalogu)")),
            new Unsupported("Želim kratke nokte s glitterom na prstenjaku.", "regular-polish",
                    List.of("glitter (nema nijednog provjerenog proizvoda u katalogu)")),
            new Unsupported("Želim kratke četvrtaste nokte, sjajne.", "press-on",
                    List.of("četvrtasti oblik (nema nijednog provjerenog proizvoda u katalogu)")));

    private NailLookBriefDto brief(String prompt) {
        return extractor.parse(prompt, NailLookBriefDto.ExecutionMode.AT_HOME, 0).brief();
    }

    private NailKitAssembler.ValidatedKit kitFor(String prompt, String system) {
        return assembler.assemble(brief(prompt),
                new NailKitAssembler.Preferences(java.util.Map.of(), false, null, system));
    }

    // ------------------------------------------------------------------------------------- supported

    @Test
    @DisplayName("every supported prompt yields a buyable kit whose arithmetic and links hold up")
    void supportedPromptsProduceABuyableKit() {
        assertThat(SUPPORTED).as("the MVP set must stay 8-12 prompts wide").hasSizeBetween(8, 12);

        for (Supported look : SUPPORTED) {
            var kit = kitFor(look.prompt(), look.system());

            assertThat(kit.status())
                    .as("%s [%s]", look.prompt(), look.system())
                    .isIn(KitStatus.COMPLETE, KitStatus.COMPLETE_WITH_ASSUMPTIONS);
            assertThat(kit.missingRequiredSlots()).as("%s", look.prompt()).isEmpty();

            int summed = kit.items().stream().mapToInt(NailKitAssembler.KitItem::priceCents).sum();
            assertThat(kit.totalCents()).as("%s: the total is the sum, not an estimate", look.prompt())
                    .isEqualTo(summed).isPositive();

            assertThat(kit.items()).as("%s", look.prompt()).allSatisfy(item -> {
                assertThat(item.productUrl()).startsWith("https://");
                assertThat(item.retailer()).isNotBlank();
                assertThat(item.priceCents()).isPositive();
            });
            // Freshness is disclosure, so a buyable kit still has to say how old its numbers are.
            assertThat(kit.catalogFreshnessHr()).as("%s", look.prompt()).isNotBlank();
        }
    }

    // ----------------------------------------------------------------------------------- unsupported

    @Test
    @DisplayName("every unsupported prompt refuses, and names the exact capability it cannot reproduce")
    void unsupportedPromptsRefuseWithTheDocumentedReason() {
        assertThat(UNSUPPORTED).as("the MVP set must keep 3-4 honest refusals").hasSizeBetween(3, 4);

        for (Unsupported look : UNSUPPORTED) {
            var kit = kitFor(look.prompt(), look.system());

            assertThat(kit.status()).as("%s [%s]", look.prompt(), look.system())
                    .isEqualTo(KitStatus.INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE);
            assertThat(kit.status().isPurchasable())
                    .as("%s: an incomplete kit is never presented as buyable", look.prompt()).isFalse();
            assertThat(kit.missingRequiredSlots())
                    .as("%s: the reason shown to the user is frozen", look.prompt())
                    .containsExactlyInAnyOrderElementsOf(look.expectedMissing());
            // The explanation is the sentence she actually reads, so it has to carry the reason too.
            assertThat(kit.statusExplanationHr()).as("%s", look.prompt())
                    .contains(look.expectedMissing().get(0));
        }
    }

    @Test
    @DisplayName("the burgundy cat-eye + gold prompt stays Incomplete for the cat-eye, not for the gold")
    void theTruthfulnessTestStaysIncompleteForTheRightReason() {
        var kit = kitFor(UNSUPPORTED.get(0).prompt(), "regular-polish");

        assertThat(kit.missingRequiredSlots()).anySatisfy(m -> assertThat(m).contains("cat-eye"));
        assertThat(kit.missingRequiredSlots())
                .as("burgundy and gold are both real products now")
                .noneSatisfy(m -> assertThat(m).contains("zlatni detalj"))
                .noneSatisfy(m -> assertThat(m).contains("burgundy"));
        // And it still shows how far it got, so the refusal is informative rather than a dead end.
        assertThat(kit.items()).extracting(NailKitAssembler.KitItem::slot)
                .contains("base", "color", "top", "removal", "accent");
    }

    // ------------------------------------------------------------------------------------ the demo case

    @Test
    @DisplayName("the demo prompt is understood: bordo, almond, short, glossy, gold on the ring finger")
    void theDemoPromptParsesIntoTheDesignItDescribes() {
        NailDesignSpecDto design = brief(DEMO).design();

        assertThat(design.shape()).isEqualTo(NailDesignSpecDto.Shape.ALMOND);
        assertThat(design.length()).isEqualTo(NailDesignSpecDto.Length.SHORT);
        assertThat(design.baseColorKey()).isEqualTo("burgundy");
        assertThat(design.finish()).isEqualTo(NailDesignSpecDto.Finish.GLOSSY);
        assertThat(design.accentColorKey()).isEqualTo("gold");
        assertThat(design.accentFingers()).contains(NailDesignSpecDto.Finger.RING);
        assertThat(design.hasDistinctAccentColor())
                .as("a second colour is a second product").isTrue();
        assertThat(design.activeEffects())
                .as("nothing was asked for that the catalog cannot do").isEmpty();
    }

    @Test
    @DisplayName("the demo prompt's salon result is a specification, with no prices and no retailers")
    void theDemoPromptProducesASalonBriefWithNoShopping() {
        NailDesignSpecDto design = brief(DEMO).design();

        String svg = diagram.render(design);
        assertThat(svg).startsWith("<svg").contains(design.baseColorHex());
        assertThat(diagram.render(design)).as("deterministic").isEqualTo(svg);

        var salonBrief = salon.build(design);
        assertThat(salonBrief.placement()).hasSize(10);
        assertThat(salonBrief.specification()).extracting(NailSalonBriefBuilder.SpecLine::labelHr)
                .contains("Oblik", "Duljina", "Boja", "Naglasak");
        assertThat(salonBrief.showToTechnician()).contains("almond").contains("prstenjak");
        // Structural, not a string check: the builder has no catalog to reach for, so a salon result
        // cannot start quoting prices however the copy changes.
        assertThat(NailSalonBriefBuilder.class.getDeclaredConstructors()[0].getParameterTypes())
                .containsExactly(NailDesignResolver.class);
    }

    @Test
    @DisplayName("the demo prompt's classical-polish kit buys the bordo AND the gold, and totals correctly")
    void theDemoPromptProducesACompleteClassicalPolishKit() {
        var kit = kitFor(DEMO, "regular-polish");

        assertThat(kit.status()).isEqualTo(KitStatus.COMPLETE_WITH_ASSUMPTIONS);
        assertThat(kit.status().isPurchasable()).isTrue();
        assertThat(kit.missingRequiredSlots()).isEmpty();

        assertThat(kit.items()).extracting(NailKitAssembler.KitItem::slot)
                .contains("file", "base", "color", "top", "removal", "accent");

        // The colour must be the shade whose own title says bordeaux, not merely the cheapest lacquer.
        var colour = product(kit, "color");
        assertThat(NailCapabilityEvidence.proves(colour, NailCapabilityEvidence.Capability.COLOR_BURGUNDY))
                .as("the picked shade is the one that proves burgundy").isTrue();
        // The accent must be the product that proves gold, not just any decoration.
        var accent = product(kit, "accent");
        assertThat(NailCapabilityEvidence.proves(accent, NailCapabilityEvidence.Capability.GOLD_DETAIL))
                .as("the accent is the product that proves gold").isTrue();

        assertThat(kit.totalCents())
                .isEqualTo(kit.essentialTotalCents() + kit.optionalTotalCents())
                .isEqualTo(kit.items().stream().mapToInt(NailKitAssembler.KitItem::priceCents).sum());

        // A named colour must not raise a "check the swatch" guess, and the visible assumptions must be
        // about things the prompt genuinely left open.
        assertThat(kit.assumptions()).noneMatch(a -> "shade".equals(a.field()));
        assertThat(kit.assumptions()).allSatisfy(a -> {
            assertThat(a.labelHr()).isNotBlank();
            assertThat(a.reasonHr()).as("an assumption without a reason is just a claim").isNotBlank();
        });
        assertThat(kit.prepStepsHr()).isNotEmpty();
        assertThat(kit.removalStepsHr()).isNotEmpty();
    }

    private NailPilotCatalog.PilotProduct product(NailKitAssembler.ValidatedKit kit, String slot) {
        String id = kit.items().stream().filter(i -> slot.equals(i.slot())).findFirst().orElseThrow()
                .externalId();
        return catalog.products().stream().filter(p -> id.equals(p.externalId())).findFirst().orElseThrow();
    }
}
