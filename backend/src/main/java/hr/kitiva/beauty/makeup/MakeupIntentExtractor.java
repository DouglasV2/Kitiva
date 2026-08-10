package hr.kitiva.beauty.makeup;

import hr.kitiva.beauty.dto.Assumption;
import hr.kitiva.beauty.dto.BeautyBriefDto;
import hr.kitiva.beauty.dto.OwnedItemDto;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Croatian free text → an editable {@link BeautyBriefDto} for the makeup vertical.
 *
 * <p>This is the makeup counterpart of {@code NailIntentExtractor}, and it inherits that class's one
 * governing habit: <strong>everything it infers, it says out loud.</strong> A guess that is not recorded as
 * an {@link Assumption} is indistinguishable to the user from something she told us, and the whole product
 * rests on her being able to tell those apart.</p>
 *
 * <p><strong>Why this is deterministic and not a language model.</strong> The kit it feeds recommends
 * cosmetics by name and price. A parser that occasionally hallucinates "matte" out of a prompt that never
 * said it produces a shopping list nobody can trace back to a sentence they wrote. Regexes are worse at
 * language and far better at being accountable, and accountability is the feature.</p>
 *
 * <p><strong>What it deliberately does NOT do.</strong> It never invents a skin tone, an undertone or a
 * shade. Those are the fields where a wrong guess costs real money — a foundation in the wrong depth is
 * unusable and often unreturnable once opened — so if the prompt does not say, they stay empty and the kit
 * assembler treats the shade as unresolved rather than picking the middle of the range.</p>
 */
@Component
public class MakeupIntentExtractor {

    /**
     * The outcome of reading one prompt.
     *
     * @param brief           the editable brief; whatever the user corrects in the UI is what gets built
     * @param needsLookAnswer true when the text gave no usable signal about the occasion, so the UI should
     *                        ask rather than let an assumed look quietly decide the whole kit
     * @param recognisedHr    the Croatian phrases we actually matched, so the UI can show its work
     */
    public record Parsed(BeautyBriefDto brief, boolean needsLookAnswer, List<String> recognisedHr) { }

    // ------------------------------------------------------------------------------------------------
    // LOOK INFERENCE
    //
    // Ordered, first match wins, most specific first. "vjenčanje" must beat "svečano" because a bride and
    // a wedding guest want different faces, and "smokey" must beat "izlazak" because it names the look
    // directly rather than the evening it is worn to.
    //
    // The phrases are what people actually type in Croatian, including the English loanwords that are in
    // ordinary use here ("clean girl", "full glam") — refusing to match those would be pedantry, not rigour.
    // ------------------------------------------------------------------------------------------------

    private record LookRule(String lookKey, String whyHr, Pattern pattern) { }

    private static final List<LookRule> LOOK_RULES = List.of(
            new LookRule("bridal", "vjenčanje",
                    p("vjencanj|svadb|mlada|mladenk|kuma\\b|vjencam se|udajem se")),
            new LookRule("bold-evening", "izražena večernja šminka",
                    p("smokey|smoki|bold|dramatic|dramatičn|dramaticn|jak[ao] sminka|crveni ruz|tamni ruz")),
            new LookRule("full-glam", "puni glam",
                    p("full glam|puni glam|glam\\b|tulum|party|proslav|rodendan|docek|nova godina|maturalac|matur")),
            new LookRule("date-night", "spoj ili večernji izlazak",
                    p("spoj\\b|date night|date\\b|vecer|izlazak|izlaz|restoran|koncert|kino")),
            new LookRule("soft-glam", "nježni glam",
                    p("soft glam|njezn|meko|blago svecan|lagano svecan")),
            new LookRule("clean-girl", "clean girl",
                    p("clean girl|klin girl|no makeup|nomakeup|bez sminke izgled|minimalist|cist izgled|prirodn[oa] blist")),
            new LookRule("natural-everyday", "svakodnevna prirodna šminka",
                    p("svaki dan|svakodnev|za posao|na posao|ured\\b|faks|skol|prirodn|jednostavn|diskretn|lagan"))
    );

    // ------------------------------------------------------------------------------------------------
    // CATEGORY WORDS — used only for "I already own X" and "leave X out".
    //
    // Matching a category name is NOT a request for that category on its own: "trebam nešto za usne" is a
    // request, "imam ruž" is the opposite of one, and telling them apart is what the owned/excluded
    // patterns below are for.
    // ------------------------------------------------------------------------------------------------

    private record CategoryWord(String key, Pattern pattern) { }

    private static final List<CategoryWord> CATEGORY_WORDS = List.of(
            new CategoryWord("primer", p("prajmer|primer\\b|podloga za sminku")),
            new CategoryWord("foundation", p("podlog|foundation|tekuci puder|bb krem|cc krem")),
            new CategoryWord("concealer", p("korektor|concealer|prekrivac")),
            new CategoryWord("powder", p("puder u prahu|kompaktni puder|transparentni puder|\\bpuder\\b")),
            new CategoryWord("blush", p("rumenil|blush|ruzic na obraz")),
            new CategoryWord("bronzer", p("bronzer|kontur|contour|bronz")),
            new CategoryWord("highlighter", p("highlight|hajlajter|osvjetljiv")),
            new CategoryWord("eyeshadow", p("sjenil|eyeshadow|paletu za oci|paleta za oci")),
            new CategoryWord("eyeliner", p("tus za oci|ajlajner|eyeliner|olovka za oci")),
            new CategoryWord("mascara", p("maskar|mascara|tus za trepavice")),
            new CategoryWord("brow", p("obrv|brow|gel za obrve")),
            // Croatian declines: ruž / ruža / ružu / ružem / ruževi. Stem + an explicit ending list, because
            // a bare "ruz" prefix would also swallow "ružičasta" (a colour) and "ružmarin".
            new CategoryWord("lipstick", p("\\bruz(?:a|u|om|em|evi|eva|eve)?\\b|lipstick|sjajil|\\bgloss\\b|karmin")),
            new CategoryWord("lipliner", p("olovka za usne|lip liner|lipliner|konturu usana")),
            new CategoryWord("setting-spray", p("fiksator|setting spray|sprej za fiksiranje")),
            new CategoryWord("tools", p("kist|kistov|brush|spuzvic|blender|pribor")),
            new CategoryWord("remover", p("skidanje sminke|micelarn|odstranjivac|demakijaz"))
    );

    /** "imam", "već imam", "posjedujem", "ne treba mi" — a claim of possession, not a request. */
    private static final Pattern OWNED_LEAD = p("(vec )?imam|posjedujem|ne treba mi|ne trebam|imamo");

    /** "bez", "ne nosim", "ne koristim", "ne želim" — an exclusion. */
    private static final Pattern EXCLUDED_LEAD = p("bez\\b|ne nosim|ne koristim|ne zelim|ne volim|preskoci|izbaci");

    // "do 50 eura", "50€", "budzet 40", "oko 30 eur", "max 25"
    private static final Pattern BUDGET = Pattern.compile(
            "(?:do|oko|max(?:imalno)?|budzet|budžet|imam)?\\s*(\\d{1,4})(?:[.,](\\d{1,2}))?\\s*(?:eur\\b|eura\\b|€)",
            Pattern.CASE_INSENSITIVE);

    /** A budget stated as a ceiling ("do 40 eura", "max 40") is a limit, not a target. */
    private static final Pattern BUDGET_STRICT = p("do \\d|max|maksimal|ne vise od|najvise");

    private static final List<String[]> FINISHES = List.of(
            new String[]{"matte", "mat\\b|matt|matiran", "mat završnica"},
            new String[]{"gloss", "gloss|sjajil|visoki sjaj", "sjajna završnica"},
            new String[]{"shimmer", "shimmer|simer|blistav|glitter|sjecic", "blistava završnica"},
            new String[]{"satin", "satin|saten|dewy|rosn|prirodni sjaj", "satenska završnica"},
            new String[]{"cream", "kremast|cream\\b|krem tekstur", "kremasta tekstura"}
    );

    private static final List<String[]> SKIN_TYPES = List.of(
            new String[]{"oily", "masn[aou]|masna koza|sjaji mi se|mastan", "masna koža"},
            new String[]{"dry", "such[aou] koz|suha koza|suha mi je koza|dehidrir", "suha koža"},
            new String[]{"combination", "mjesovit|mješovit|kombinira", "mješovita koža"},
            new String[]{"sensitive", "osjetljiv|reagira|crveni mi se", "osjetljiva koža"}
    );

    public Parsed parse(String rawPrompt, int budgetCentsFromForm) {
        String prompt = rawPrompt == null ? "" : rawPrompt.trim();
        String text = fold(prompt);
        List<Assumption> assumptions = new ArrayList<>();
        List<String> recognised = new ArrayList<>();

        // ---- look -----------------------------------------------------------------------------------
        String look = "";
        for (LookRule rule : LOOK_RULES) {
            if (rule.pattern().matcher(text).find()) {
                look = rule.lookKey();
                recognised.add(rule.whyHr());
                break;
            }
        }
        boolean needsLookAnswer = look.isEmpty();
        if (needsLookAnswer && !text.isBlank()) {
            // Assume the least committal look rather than the prettiest one. Everyday is the only choice
            // that cannot embarrass someone who wanted something quiet, and the UI still asks.
            look = "natural-everyday";
            assumptions.add(Assumption.of("look", look, "svakodnevna prirodna šminka",
                    "Iz opisa nismo prepoznali prigodu, pa smo pretpostavili najtiši izbor. Promijeni ga ako griješimo."));
        }

        // ---- budget ---------------------------------------------------------------------------------
        int budgetCents = Math.max(0, budgetCentsFromForm);
        boolean budgetStrict = false;
        Matcher bm = BUDGET.matcher(text);
        if (bm.find()) {
            int euros = Integer.parseInt(bm.group(1));
            int cents = bm.group(2) == null ? 0 : Integer.parseInt((bm.group(2) + "0").substring(0, 2));
            int fromText = euros * BeautyBriefDto.CENTS_PER_EURO + cents;
            // The form field wins if the user set one — it is the more deliberate of the two statements.
            if (budgetCents == 0) {
                budgetCents = fromText;
                recognised.add("budžet " + euros + " EUR");
            }
            budgetStrict = BUDGET_STRICT.matcher(text).find();
        }

        // ---- finish ---------------------------------------------------------------------------------
        String finish = "";
        for (String[] f : FINISHES) {
            if (p(f[1]).matcher(text).find()) {
                finish = f[0];
                recognised.add(f[2]);
                break;
            }
        }

        // ---- skin type ------------------------------------------------------------------------------
        String skinType = "";
        for (String[] s : SKIN_TYPES) {
            if (p(s[1]).matcher(text).find()) {
                skinType = s[0];
                recognised.add(s[2]);
                break;
            }
        }

        // ---- owned / excluded categories ------------------------------------------------------------
        Set<String> owned = new LinkedHashSet<>();
        Set<String> excluded = new LinkedHashSet<>();
        for (String clause : text.split("[,.;]|\\bali\\b")) {
            // Attribute each category to the NEAREST PRECEDING marker rather than to the clause as a whole.
            // Splitting on "i" instead would lose the second half of "imam podlogu i maskaru", and treating
            // the clause as one bucket would mis-read "imam podlogu i ne nosim maskaru" as excluding both.
            String c = clause.trim();
            if (c.isEmpty()) continue;
            List<int[]> marks = new ArrayList<>();   // {position, 0 = owned, 1 = excluded}
            for (Matcher m = OWNED_LEAD.matcher(c); m.find(); ) marks.add(new int[]{m.start(), 0});
            for (Matcher m = EXCLUDED_LEAD.matcher(c); m.find(); ) marks.add(new int[]{m.start(), 1});
            if (marks.isEmpty()) continue;
            marks.sort((a, b) -> Integer.compare(a[0], b[0]));

            for (CategoryWord cw : CATEGORY_WORDS) {
                for (Matcher m = cw.pattern().matcher(c); m.find(); ) {
                    int kind = -1;
                    for (int[] mark : marks) {
                        if (mark[0] >= m.start()) break;
                        kind = mark[1];
                    }
                    if (kind < 0) continue;   // the category was named before any marker: not a claim either way
                    // An exclusion is the stronger instruction — it says do not buy this at all — so once a
                    // category is excluded, a later "imam" cannot quietly put it back in the kit.
                    if (kind == 1) { excluded.add(cw.key()); owned.remove(cw.key()); }
                    else if (!excluded.contains(cw.key())) owned.add(cw.key());
                    break;
                }
            }
        }

        List<OwnedItemDto> ownedItems = new ArrayList<>();
        for (String key : owned) ownedItems.add(OwnedItemDto.owned(key, key));

        BeautyBriefDto brief = new BeautyBriefDto(
                prompt, "HR", "EUR",
                budgetCents, budgetStrict, false,
                look, "", List.of(), "",
                finish, skinType,
                // Skin depth, undertone and reference shade are never guessed. A wrong foundation depth is
                // the one mistake here that costs real money and cannot be returned once opened.
                "", "", "",
                false, false,
                ownedItems,
                List.of(),
                List.copyOf(excluded),
                List.of(),
                false,
                "beginner",
                List.copyOf(assumptions));

        return new Parsed(brief, needsLookAnswer, List.copyOf(recognised));
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /** Lowercase and strip Croatian diacritics, so "mješovita" and "mjesovita" are the same word. */
    private static String fold(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
