package hr.kitiva.beauty.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B — the makeup brief contract.
 *
 * <p>Mirrors the repo's existing {@code *BackCompatTest} convention: unknown JSON properties must be
 * tolerated (a stale frontend must not 400 a request), and null collections must never reach a consumer.</p>
 */
class BeautyBriefDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialisesARealCroatianEverydayRequest() throws Exception {
        String json = """
                {
                  "prompt": "Složi mi kompletan svakodnevni makeup do 100 €. Već imam maskaru i kistove.",
                  "budgetCents": 10000,
                  "budgetStrict": true,
                  "look": "everyday",
                  "coverage": "light-medium",
                  "finish": "natural",
                  "skinType": "dry",
                  "ownedItems": [
                    {"slot": "mascara", "rawText": "već imam maskaru", "satisfiesRequirement": true},
                    {"slot": "brushes", "rawText": "kistove", "satisfiesRequirement": true}
                  ]
                }
                """;

        BeautyBriefDto brief = mapper.readValue(json, BeautyBriefDto.class);

        assertThat(brief.budgetCents()).isEqualTo(10_000);
        assertThat(brief.hasBudget()).isTrue();
        assertThat(brief.budgetStrict()).isTrue();
        assertThat(brief.look()).isEqualTo("everyday");
        assertThat(brief.skinType()).isEqualTo("dry");
        assertThat(brief.ownedSlots()).containsExactlyInAnyOrder("mascara", "brushes");
        assertThat(brief.explicitlyMissingSlots()).isEmpty();
        // Defaults applied for an HR-only launch even though the JSON omitted them.
        assertThat(brief.market()).isEqualTo("HR");
        assertThat(brief.currency()).isEqualTo("EUR");
    }

    @Test
    void ownedAndExplicitlyMissingAreNeverConfused() throws Exception {
        // The distinction the whole owned-items feature rests on: "nemam korektor" is NOT the absence of a
        // statement about concealer — it is a statement that the kit MUST include one.
        String json = """
                {
                  "prompt": "imam puder, ali nemam korektor",
                  "ownedItems": [
                    {"slot": "foundation", "rawText": "imam puder", "satisfiesRequirement": true},
                    {"slot": "concealer", "rawText": "nemam korektor", "satisfiesRequirement": false}
                  ]
                }
                """;

        BeautyBriefDto brief = mapper.readValue(json, BeautyBriefDto.class);

        assertThat(brief.ownedSlots()).containsExactly("foundation");
        assertThat(brief.explicitlyMissingSlots()).containsExactly("concealer");
        assertThat(brief.ownedSlots()).doesNotContain("concealer");
    }

    @Test
    void unknownPropertiesAreToleratedAndNullCollectionsNeverEscape() throws Exception {
        // A stale client sending a field we removed must not break the request.
        BeautyBriefDto brief = mapper.readValue(
                "{\"prompt\":\"x\",\"someFieldWeRemovedLastSprint\":\"whatever\"}", BeautyBriefDto.class);

        assertThat(brief.styleWords()).isNotNull().isEmpty();
        assertThat(brief.ownedItems()).isNotNull().isEmpty();
        assertThat(brief.requiredSlots()).isNotNull().isEmpty();
        assertThat(brief.excludedSlots()).isNotNull().isEmpty();
        assertThat(brief.brandPreferences()).isNotNull().isEmpty();
        assertThat(brief.assumptions()).isNotNull().isEmpty();
        assertThat(brief.skillLevel()).isEqualTo("beginner");
    }

    @Test
    void roundTripsLosslessly() throws Exception {
        BeautyBriefDto original = new BeautyBriefDto(
                "soft glam za vjenčanje do 160 €, suha koža", "HR", "EUR", 16_000, true, false,
                "soft-glam", "vjenčanje", List.of("elegantno"), "medium", "dewy",
                "dry", "medium", "neutral", "MAC NC25", true, true,
                List.of(OwnedItemDto.owned("mascara", "imam maskaru"),
                        OwnedItemDto.missing("lip-liner", "nemam olovku za usne")),
                List.of("foundation"), List.of("false-lashes"), List.of("Catrice"),
                false, "some",
                List.of(Assumption.of("finish", "dewy", "dewy finiš",
                        "Nije navedeno; suha koža ide bolje uz dewy finiš.")));

        BeautyBriefDto reparsed = mapper.readValue(mapper.writeValueAsString(original), BeautyBriefDto.class);

        assertThat(reparsed).isEqualTo(original);
    }

    @Test
    void eurosConvertToCentsAtExactlyOneBoundary() {
        // AmountParser returns whole euros; everything downstream is cents. Mixing the two would silently
        // move a total by 100x, so the conversion lives in one method and is pinned here.
        assertThat(BeautyBriefDto.eurosToCents(100)).isEqualTo(10_000);
        assertThat(BeautyBriefDto.eurosToCents(0)).isZero();
        assertThat(BeautyBriefDto.eurosToCents(-5)).as("a negative budget clamps, never goes negative").isZero();
        assertThat(BeautyBriefDto.CENTS_PER_EURO).isEqualTo(100);
    }

    @Test
    void carriesNoHealthDataFields() {
        // Audit §6.6: no persistent field may exist for a prior reaction, allergy, or skin/nail condition.
        // A reflection check, because the guarantee is about the SHAPE of the record — a future edit that
        // adds such a field should fail here rather than in a privacy review after launch.
        List<String> componentNames = java.util.Arrays.stream(BeautyBriefDto.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(componentNames)
                .as("no health-condition field may be added to the persistent brief (audit §6.6)")
                .doesNotContain("knownSensitivities", "allergies", "allergy", "priorReaction",
                        "acrylateReaction", "skinCondition", "nailCondition", "medicalNotes", "healthNotes");
    }
}
