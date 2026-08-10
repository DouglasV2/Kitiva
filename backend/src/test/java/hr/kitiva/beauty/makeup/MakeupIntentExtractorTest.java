package hr.kitiva.beauty.makeup;

import hr.kitiva.beauty.dto.OwnedItemDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the makeup parser must read out of ordinary Croatian, and — just as load-bearing — what it must
 * refuse to invent.
 *
 * <p>Several of these encode real defects found by running the endpoint rather than by reading the code:
 * a conjunction list losing its second half, and a Croatian declension slipping past a word-boundary
 * regex. Both produced a plausible-looking brief that was quietly wrong, which is the failure mode this
 * whole vertical is built to avoid.</p>
 */
class MakeupIntentExtractorTest {

    private final MakeupIntentExtractor extractor = new MakeupIntentExtractor();

    private static List<String> slots(List<OwnedItemDto> items) {
        return items.stream().map(OwnedItemDto::slot).toList();
    }

    @Test
    @DisplayName("a full sentence yields look, budget, finish, skin type and what she already owns")
    void readsAFullSentence() {
        var parsed = extractor.parse(
                "idem na vjenčanje, imam podlogu i maskaru, bez ruža, mješovita koža, do 40 eura, mat", 0);
        var brief = parsed.brief();

        assertThat(brief.look()).isEqualTo("bridal");
        assertThat(brief.budgetCents()).isEqualTo(4000);
        assertThat(brief.budgetStrict()).isTrue();
        assertThat(brief.finish()).isEqualTo("matte");
        assertThat(brief.skinType()).isEqualTo("combination");
        assertThat(parsed.needsLookAnswer()).isFalse();
    }

    @Test
    @DisplayName("a conjunction keeps BOTH items: 'imam podlogu i maskaru' is two things she owns")
    void conjunctionListKeepsBothItems() {
        var parsed = extractor.parse("imam podlogu i maskaru", 0);
        assertThat(slots(parsed.brief().ownedItems())).containsExactlyInAnyOrder("foundation", "mascara");
    }

    @Test
    @DisplayName("'bez ruža' excludes lipstick — the declined form must match")
    void declinedLipstickIsExcluded() {
        var parsed = extractor.parse("bez ruža", 0);
        assertThat(parsed.brief().excludedSlots()).containsExactly("lipstick");
        assertThat(parsed.brief().ownedItems()).isEmpty();
    }

    @Test
    @DisplayName("a colour word is not a product: 'ružičasto rumenilo' must not exclude lipstick")
    void colourWordIsNotLipstick() {
        var parsed = extractor.parse("ružičasto rumenilo, bez maskare", 0);
        assertThat(parsed.brief().excludedSlots()).containsExactly("mascara");
        assertThat(parsed.brief().excludedSlots()).doesNotContain("lipstick");
    }

    @Test
    @DisplayName("owning one thing and refusing another in the same clause keeps them apart")
    void ownedAndExcludedInOneClause() {
        var parsed = extractor.parse("imam podlogu i ne nosim maskaru", 0);
        assertThat(slots(parsed.brief().ownedItems())).containsExactly("foundation");
        assertThat(parsed.brief().excludedSlots()).containsExactly("mascara");
    }

    @Test
    @DisplayName("an exclusion outranks a later claim of ownership")
    void exclusionWins() {
        var parsed = extractor.parse("bez ruža, ali imam ruž", 0);
        assertThat(parsed.brief().excludedSlots()).containsExactly("lipstick");
        assertThat(slots(parsed.brief().ownedItems())).doesNotContain("lipstick");
    }

    @Test
    @DisplayName("an unrecognised occasion is assumed to be everyday, and the assumption is recorded")
    void unknownOccasionIsAssumedAndSaidOutLoud() {
        var parsed = extractor.parse("nešto lijepo", 0);
        assertThat(parsed.needsLookAnswer()).isTrue();
        assertThat(parsed.brief().look()).isEqualTo("natural-everyday");
        assertThat(parsed.brief().assumptions())
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.field()).isEqualTo("look");
                    assertThat(a.reasonHr()).isNotBlank();
                });
    }

    @Test
    @DisplayName("skin tone, undertone and shade are NEVER guessed")
    void neverGuessesShade() {
        var parsed = extractor.parse("puni glam za tulum, tamniji ten", 0);
        assertThat(parsed.brief().skinToneDepth()).isEmpty();
        assertThat(parsed.brief().undertone()).isEmpty();
        assertThat(parsed.brief().referenceShade()).isEmpty();
    }

    @Test
    @DisplayName("a budget typed into the form beats one mentioned in the sentence")
    void formBudgetWins() {
        var parsed = extractor.parse("do 40 eura", 6000);
        assertThat(parsed.brief().budgetCents()).isEqualTo(6000);
    }

    @Test
    @DisplayName("the specific occasion beats the general one: a wedding is not just an evening out")
    void mostSpecificLookWins() {
        assertThat(extractor.parse("vjenčanje navečer", 0).brief().look()).isEqualTo("bridal");
        assertThat(extractor.parse("izlazak navečer", 0).brief().look()).isEqualTo("date-night");
    }

    @Test
    @DisplayName("an empty prompt asks rather than assuming, and records nothing")
    void emptyPromptAssumesNothing() {
        var parsed = extractor.parse("   ", 0);
        assertThat(parsed.needsLookAnswer()).isTrue();
        assertThat(parsed.brief().assumptions()).isEmpty();
        assertThat(parsed.brief().look()).isEmpty();
    }

    @Test
    @DisplayName("the parser shows its work — every match it made is named in Croatian")
    void showsItsWork() {
        var parsed = extractor.parse("clean girl, masna koža, do 25 eura", 0);
        assertThat(parsed.recognisedHr()).isNotEmpty();
        assertThat(parsed.brief().look()).isEqualTo("clean-girl");
        assertThat(parsed.brief().skinType()).isEqualTo("oily");
    }
}
