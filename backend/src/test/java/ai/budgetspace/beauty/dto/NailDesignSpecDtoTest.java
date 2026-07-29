package ai.budgetspace.beauty.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B — the nail design spec and the nail brief.
 *
 * <p>This tests the SHAPE of the spec only. Resolving it into ten per-nail placements belongs to
 * {@code NailDesignResolver} (Phase F), which owns that logic and its tests — deliberately not duplicated
 * here, so one behaviour has exactly one owner.</p>
 */
class NailDesignSpecDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialisesTheBurgundyCatEyeRequestFromTheSpec() throws Exception {
        String json = """
                {
                  "shape": "ALMOND",
                  "length": "SHORT",
                  "baseColorKey": "burgundy",
                  "baseColorHex": "#5C0A22",
                  "baseColorRawText": "boja višnje",
                  "finish": "GLOSSY",
                  "effects": ["CAT_EYE"],
                  "accentFingers": ["RING"],
                  "accentDescription": "tanki zlatni polumjesec",
                  "symmetry": "MIRRORED",
                  "styleWords": ["elegantno", "diskretno"]
                }
                """;

        NailDesignSpecDto design = mapper.readValue(json, NailDesignSpecDto.class);

        assertThat(design.shape()).isEqualTo(NailDesignSpecDto.Shape.ALMOND);
        assertThat(design.length()).isEqualTo(NailDesignSpecDto.Length.SHORT);
        assertThat(design.baseColorKey()).isEqualTo("burgundy");
        assertThat(design.baseColorHex()).isEqualTo("#5C0A22");
        assertThat(design.baseColorRawText()).as("her own words are echoed back").isEqualTo("boja višnje");
        assertThat(design.activeEffects()).containsExactly(NailDesignSpecDto.Effect.CAT_EYE);
        assertThat(design.accentFingers()).containsExactly(NailDesignSpecDto.Finger.RING);
        assertThat(design.accentCount()).isEqualTo(1);
        assertThat(design.hasAccent()).isTrue();
        assertThat(design.requiresExtension()).as("short almond needs no extension").isFalse();
    }

    @Test
    void namedAccentFingersAndCountSurviveTheRoundTrip() throws Exception {
        // Approved MVP minimum (audit §6.7): mirrored design, NAMED accent fingers, accent count, and
        // symmetry stated explicitly. The full L1..R5 scheme is out of scope, but these four are not.
        NailDesignSpecDto design = new NailDesignSpecDto(
                NailDesignSpecDto.Shape.OVAL, NailDesignSpecDto.Length.MEDIUM,
                "nude", "#E4C9B6", "nude roza", NailDesignSpecDto.Finish.MATTE,
                List.of(NailDesignSpecDto.Effect.FRENCH),
                List.of(NailDesignSpecDto.Finger.RING, NailDesignSpecDto.Finger.THUMB),
                "gold", "#B08D3F", "zlatni detalj", NailDesignSpecDto.Symmetry.MIRRORED,
                List.of("minimalistički"), List.of());

        NailDesignSpecDto reparsed = mapper.readValue(mapper.writeValueAsString(design), NailDesignSpecDto.class);

        assertThat(reparsed).isEqualTo(design);
        assertThat(reparsed.accentFingers())
                .containsExactly(NailDesignSpecDto.Finger.RING, NailDesignSpecDto.Finger.THUMB);
        assertThat(reparsed.accentCount()).isEqualTo(2);
    }

    @Test
    void asymmetryIsAlwaysStatedNeverInferredFromSilence() throws Exception {
        // A missing symmetry field must default to MIRRORED, not to "unknown". A nail tech reading the brief
        // has to know whether the hands differ deliberately; there is no third state to hide in.
        NailDesignSpecDto silent = mapper.readValue("{\"baseColorKey\":\"red\"}", NailDesignSpecDto.class);
        assertThat(silent.symmetry()).isEqualTo(NailDesignSpecDto.Symmetry.MIRRORED);

        assertThat(NailDesignSpecDto.Symmetry.values())
                .as("no UNSPECIFIED member may be added — asymmetry must be a stated choice")
                .containsExactly(NailDesignSpecDto.Symmetry.MIRRORED,
                        NailDesignSpecDto.Symmetry.ASYMMETRIC_STATED);
    }

    @Test
    void longLengthsAreFlaggedAsNeedingAnExtension() {
        // This is what routes an at-home request toward press-ons instead of a builder gel we will not
        // recommend to a consumer.
        assertThat(NailDesignSpecDto.Length.SHORT.feasibleOnNaturalNail()).isTrue();
        assertThat(NailDesignSpecDto.Length.MEDIUM.feasibleOnNaturalNail()).isTrue();
        assertThat(NailDesignSpecDto.Length.LONG.feasibleOnNaturalNail()).isFalse();
        assertThat(NailDesignSpecDto.Length.EXTRA_LONG.feasibleOnNaturalNail()).isFalse();
    }

    @Test
    void aHalfParsedPromptStillYieldsADrawableSayableSpec() throws Exception {
        // Every consumer (diagram, brief, kit) reads the same object; none of them should have to defend
        // against nulls when the parser only understood the colour.
        NailDesignSpecDto sparse = mapper.readValue("{\"baseColorRawText\":\"crvena\"}", NailDesignSpecDto.class);

        assertThat(sparse.shape()).isNotNull();
        assertThat(sparse.length()).isNotNull();
        assertThat(sparse.finish()).isNotNull();
        assertThat(sparse.symmetry()).isNotNull();
        assertThat(sparse.effects()).isNotNull().isEmpty();
        assertThat(sparse.accentFingers()).isNotNull().isEmpty();
        assertThat(sparse.assumptions()).isNotNull().isEmpty();
        assertThat(sparse.hasAccent()).isFalse();
    }

    @Test
    void executionModeIsNeverGuessed() throws Exception {
        // Salon vs at-home is the one question the product must always ask. A brief that omits it must
        // report that it needs an answer, not silently pick a branch — one branch sells chemicals.
        NailLookBriefDto brief = mapper.readValue(
                "{\"prompt\":\"kratki almond nokti boje višnje\"}", NailLookBriefDto.class);

        assertThat(brief.executionMode()).isEqualTo(NailLookBriefDto.ExecutionMode.UNSPECIFIED);
        assertThat(brief.needsExecutionModeAnswer()).isTrue();
        assertThat(brief.market()).isEqualTo("HR");
    }

    @Test
    void homeProfileCarriesSkillAndEquipmentButNoHealthData() {
        // Audit §6.6 — no field for a prior gel/acrylate reaction or any nail/skin condition.
        List<String> componentNames = java.util.Arrays.stream(NailLookBriefDto.HomeProfile.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(componentNames).containsExactlyInAnyOrder("experienceLevel", "naturalNailLength", "equipment");
        assertThat(componentNames)
                .as("no health-condition field may be added to the home profile (audit §6.6)")
                .doesNotContain("knownAcrylateReaction", "priorReaction", "allergy", "allergies",
                        "nailCondition", "skinCondition", "damagedNails", "infection");
    }

    @Test
    void nemamLampuMakesTheSlotRequiredRatherThanOwned() throws Exception {
        // The launch-critical Croatian case (audit §6.10), at the DTO level: a stated absence must read as
        // "must be in the kit", never as "she has one".
        String json = """
                {
                  "executionMode": "AT_HOME",
                  "homeProfile": {
                    "experienceLevel": "FIRST_TIME",
                    "equipment": [
                      {"slot": "lamp", "rawText": "nemam lampu", "satisfiesRequirement": false},
                      {"slot": "file", "rawText": "imam turpiju", "satisfiesRequirement": true}
                    ]
                  }
                }
                """;

        NailLookBriefDto brief = mapper.readValue(json, NailLookBriefDto.class);

        assertThat(brief.homeProfile().ownedSlots()).containsExactly("file");
        assertThat(brief.homeProfile().explicitlyMissingSlots()).containsExactly("lamp");
        assertThat(brief.homeProfile().ownedSlots()).doesNotContain("lamp");
    }
}
