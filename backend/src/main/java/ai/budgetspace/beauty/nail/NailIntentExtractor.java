package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.Assumption;
import ai.budgetspace.beauty.dto.NailDesignSpecDto;
import ai.budgetspace.beauty.dto.NailLookBriefDto;
import ai.budgetspace.beauty.dto.OwnedItemDto;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vertical slice - turns a Croatian nail prompt into a structured {@link NailLookBriefDto}.
 *
 * <p>Deterministic. No LLM is consulted: the parser produces the structure and the structure drives every
 * downstream output, so a model can never invent a colour, an effect or a product.</p>
 *
 * <p><strong>It owns its own normaliser and negation scoper.</strong> The planner's equivalents are shared
 * by 20 furniture test classes and {@code NegationScope} is package-private; widening or altering them to
 * serve nail vocabulary is exactly the "redesign the reusable infrastructure" change this pivot was told
 * not to make. Private copies cost a few lines and risk nothing.</p>
 *
 * <p><strong>Ownership parsing is the launch-critical part.</strong> "nemam lampu" must mean the lamp is
 * MISSING and therefore required, never that it was not mentioned. Absence of a statement is ambiguous;
 * a stated absence is not. HAVE and LACK are matched as separate verb patterns, LACK first, so the two can
 * never collapse into each other.</p>
 */
@Component
public class NailIntentExtractor {

    private static final Pattern LACK = Pattern.compile("\\b(?:nemam|nemamo|ne\\s+posjedujem)\\b([^,.;!?]*)");
    private static final Pattern NOT_NEEDED = Pattern.compile("\\bne\\s+(?:trebam|trebaju|zelim)\\b([^,.;!?]*)");
    private static final Pattern WITHOUT = Pattern.compile("\\bbez\\b([^,.;!?]*)");
    private static final Pattern HAVE = Pattern.compile("\\b(?:vec\\s+)?(?:imam|imamo|posjedujem)\\b([^,.;!?]*)");

    private static final Map<String, Pattern> SLOT_WORDS = new LinkedHashMap<>();
    static {
        SLOT_WORDS.put("lamp", Pattern.compile("lamp\\w*|uv\\s*led"));
        SLOT_WORDS.put("base", Pattern.compile("baz\\w*|podlog\\w*|base\\s*coat"));
        // Bare "top" matters: "nemam bazu ni top" is how people actually list what they lack. Requiring
        // "top coat" silently dropped the second half of that sentence.
        SLOT_WORDS.put("top", Pattern.compile("\\btop\\b|top\\s*coat|nadlak\\w*|zavrsn\\w*\\s*lak"));
        SLOT_WORDS.put("color", Pattern.compile("\\blak\\b|lakov\\w*|boj\\w*\\s*za\\s*nokt\\w*"));
        SLOT_WORDS.put("removal", Pattern.compile("aceton\\w*|odstranjiv\\w*|skidac\\w*|remover"));
        SLOT_WORDS.put("prep", Pattern.compile("ulj\\w*|kutikul\\w*|turpij\\w*|zanoktic\\w*"));
    }

    private static final Map<NailDesignSpecDto.Shape, Pattern> SHAPES = new LinkedHashMap<>();
    static {
        SHAPES.put(NailDesignSpecDto.Shape.ALMOND, Pattern.compile("almond|badem\\w*"));
        SHAPES.put(NailDesignSpecDto.Shape.COFFIN, Pattern.compile("coffin|balerin\\w*|ballerina"));
        SHAPES.put(NailDesignSpecDto.Shape.STILETTO, Pattern.compile("stiletto"));
        SHAPES.put(NailDesignSpecDto.Shape.SQUOVAL, Pattern.compile("squoval"));
        SHAPES.put(NailDesignSpecDto.Shape.SQUARE, Pattern.compile("cetvrtast\\w*|square"));
        SHAPES.put(NailDesignSpecDto.Shape.ROUND, Pattern.compile("okrugl\\w*|round"));
        SHAPES.put(NailDesignSpecDto.Shape.OVAL, Pattern.compile("oval\\w*"));
    }

    private static final Map<NailDesignSpecDto.Length, Pattern> LENGTHS = new LinkedHashMap<>();
    static {
        LENGTHS.put(NailDesignSpecDto.Length.EXTRA_LONG, Pattern.compile("jako\\s+dug\\w*|vrlo\\s+dug\\w*"));
        LENGTHS.put(NailDesignSpecDto.Length.SHORT, Pattern.compile("kratk\\w*|kratic\\w*"));
        LENGTHS.put(NailDesignSpecDto.Length.MEDIUM, Pattern.compile("srednj\\w*"));
        LENGTHS.put(NailDesignSpecDto.Length.LONG, Pattern.compile("dug\\w*"));
    }

    private static final Map<NailDesignSpecDto.Finish, Pattern> FINISHES = new LinkedHashMap<>();
    static {
        FINISHES.put(NailDesignSpecDto.Finish.MATTE, Pattern.compile("\\bmat\\b|matt\\w*"));
        FINISHES.put(NailDesignSpecDto.Finish.SHIMMER, Pattern.compile("simer\\w*|shimmer|sedef\\w*"));
        FINISHES.put(NailDesignSpecDto.Finish.SATIN, Pattern.compile("saten\\w*|satin"));
        FINISHES.put(NailDesignSpecDto.Finish.GLOSSY, Pattern.compile("sjajn\\w*|glossy|visoki\\s*sjaj"));
    }

    private static final Map<NailDesignSpecDto.Effect, Pattern> EFFECTS = new LinkedHashMap<>();
    static {
        EFFECTS.put(NailDesignSpecDto.Effect.CAT_EYE, Pattern.compile("cat[\\s-]?eye|macj\\w*\\s*ok\\w*|magnet\\w*"));
        EFFECTS.put(NailDesignSpecDto.Effect.CHROME, Pattern.compile("chrome|krom\\w*|aurora|ogledal\\w*"));
        EFFECTS.put(NailDesignSpecDto.Effect.FRENCH, Pattern.compile("french|francusk\\w*"));
        EFFECTS.put(NailDesignSpecDto.Effect.GLITTER_ACCENT, Pattern.compile("glitter|sljokic\\w*|iskric\\w*"));
    }

    /** Colour word -> canonical key + hex for the Design Diagram. */
    private static final Map<String, String[]> COLORS = new LinkedHashMap<>();
    static {
        COLORS.put("burgundy", new String[]{"#5C0A22", "burgund\\w*|bordo\\w*|visnj\\w*|tamnocrven\\w*|wine"});
        COLORS.put("nude", new String[]{"#E4C9B6", "nude|puder\\w*\\s*roz"});
        COLORS.put("red", new String[]{"#C1121F", "crven\\w*|\\bred\\b"});
        COLORS.put("pink", new String[]{"#E8A0BF", "roz\\w*|pink"});
        COLORS.put("black", new String[]{"#1A1A1A", "crn\\w*|black"});
        COLORS.put("white", new String[]{"#F5F5F0", "bijel\\w*|white|milky"});
        COLORS.put("blue", new String[]{"#2C4A7C", "plav\\w*|blue|teget"});
        COLORS.put("green", new String[]{"#3E6B4F", "zelen\\w*|green"});
        COLORS.put("purple", new String[]{"#6B3E7C", "ljubicast\\w*|lila|purple"});
        COLORS.put("brown", new String[]{"#6B4A32", "smed\\w*|brown|cokolad\\w*"});
        COLORS.put("grey", new String[]{"#8A8A8A", "siv\\w*|grey|gray"});
        COLORS.put("gold", new String[]{"#C9A227", "zlatn\\w*|gold"});
    }

    private static final Map<NailDesignSpecDto.Finger, Pattern> FINGERS = new LinkedHashMap<>();
    static {
        // Stems stop before the final consonant because Croatian palatalises it in the plural: the locative
        // of "prstenjak" is "prstenjacima", not "prstenjakima". Matching on "prstenjak" missed the exact
        // phrase people use for two accent nails - "zlatni detalji na prstenjacima".
        FINGERS.put(NailDesignSpecDto.Finger.RING, Pattern.compile("prstenj\\w*"));
        FINGERS.put(NailDesignSpecDto.Finger.THUMB, Pattern.compile("pal(?:ac|c\\w*)"));
        FINGERS.put(NailDesignSpecDto.Finger.INDEX, Pattern.compile("kaziprs\\w*"));
        FINGERS.put(NailDesignSpecDto.Finger.MIDDLE, Pattern.compile("srednja(?:k|c)\\w*"));
        FINGERS.put(NailDesignSpecDto.Finger.PINKY, Pattern.compile("mali\\s*prst\\w*"));
    }

    private static final Pattern SALON = Pattern.compile("u\\s*salon\\w*|kod\\s*majstoric\\w*|nail\\s*tech");
    private static final Pattern AT_HOME = Pattern.compile("kod\\s*kuc\\w*|\\bdoma\\b|samostalno|sama\\s*sebi");
    private static final Pattern ASYMMETRIC = Pattern.compile("razlicit\\w*\\s*(?:na\\s*)?ruk\\w*|asimetric\\w*");
    /** Words that mark a colour as belonging to the ACCENT rather than the base. */
    private static final Pattern ACCENT_CONTEXT =
            Pattern.compile("detalj\\w*|akcent\\w*|naglas\\w*|linij\\w*|polumjesec\\w*|vrh\\w*|prstenj\\w*");
    /** Croatian number words for accent counts, so "dva detalja" resolves to two nails. */
    private static final Map<String, Integer> COUNT_WORDS = Map.of(
            "jedan", 1, "jedno", 1, "jednim", 1, "dva", 2, "dvije", 2, "dvama", 2, "tri", 3, "cetiri", 4);

    private static final Pattern FORBIDDEN_SYSTEM = Pattern.compile(
            "builder\\s*gel|polygel|poly\\s*gel|acrygel|akrilgel|akril\\w*|nadogradn\\w*|hard\\s*gel|dip\\s*powder");
    private static final Pattern GEL_SYSTEM = Pattern.compile("gel\\s*lak|trajn\\w*\\s*lak|gel[\\s-]?polish|shellac");

    /** Health concerns volunteered in a prompt. Detected only to REFUSE safely - never to diagnose or store. */
    private static final Pattern HEALTH_CONCERN = Pattern.compile(
            "gljivic\\w*|infekcij\\w*|upal\\w*|gnoj\\w*|krvari|boli\\s*me|bolan\\w*|ozlijed\\w*|ozljed\\w*"
            + "|alergij\\w*|reakcij\\w*|\\bosip\\b|zelen\\w*\\s*nokt|odignut\\w*\\s*nokt");

    public record Parsed(
            NailLookBriefDto brief,
            boolean healthConcernDetected,
            boolean forbiddenSystemRequested,
            String forbiddenSystemNote,
            boolean gelRequested
    ) { }

    public Parsed parse(String rawPrompt, NailLookBriefDto.ExecutionMode requestedMode, int budgetCents) {
        String text = normalize(rawPrompt);
        Negation scope = Negation.of(text);
        List<Assumption> assumptions = new ArrayList<>();

        NailDesignSpecDto.Shape shape = firstMatch(SHAPES, text, scope);
        NailDesignSpecDto.Length length = firstMatch(LENGTHS, text, scope);
        NailDesignSpecDto.Finish finish = firstMatch(FINISHES, text, scope);

        List<NailDesignSpecDto.Effect> effects = new ArrayList<>();
        EFFECTS.forEach((effect, pattern) -> {
            if (matchesOutsideNegation(pattern, text, scope)) effects.add(effect);
        });

        // Colours, in the order they appear. "burgundy nokti s dva zlatna detalja" names TWO colours in one
        // sentence: the first is the base, a later one attached to an accent word is the accent. Taking only
        // the first match would silently drop the gold the kit has to buy.
        record Hit(String key, String hex, String raw, int at) { }
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, String[]> e : COLORS.entrySet()) {
            Matcher m = Pattern.compile(e.getValue()[1]).matcher(text);
            while (m.find()) {
                if (!scope.isNegated(m.start())) hits.add(new Hit(e.getKey(), e.getValue()[0], m.group(), m.start()));
            }
        }
        hits.sort((a, b) -> Integer.compare(a.at(), b.at()));

        String colorKey = "";
        String colorHex = null;
        String colorRaw = "";
        String accentColorKey = "";
        String accentColorHex = null;
        if (!hits.isEmpty()) {
            Hit base = hits.get(0);
            colorKey = base.key();
            colorHex = base.hex();
            colorRaw = base.raw();
            // A later, different colour sitting near an accent word ("detalj", "akcent", "naglasak") is the
            // accent colour. Without that proximity check, "crveni ili roza" would invent an accent.
            for (int i = 1; i < hits.size(); i++) {
                Hit h = hits.get(i);
                if (h.key().equals(colorKey)) continue;
                int from = Math.max(0, h.at() - 30);
                int to = Math.min(text.length(), h.at() + 40);
                if (ACCENT_CONTEXT.matcher(text.substring(from, to)).find()) {
                    accentColorKey = h.key();
                    accentColorHex = h.hex();
                    break;
                }
            }
        }

        List<NailDesignSpecDto.Finger> accents = new ArrayList<>();
        FINGERS.forEach((finger, pattern) -> {
            if (matchesOutsideNegation(pattern, text, scope)) accents.add(finger);
        });

        // "dva diskretna zlatna detalja" names a COUNT but no finger. That is how people actually ask, so
        // resolve it rather than dropping the accent: with mirrored hands, the ring finger gives exactly two
        // accent nails, which is what she asked for. Recorded as an assumption so she can move it.
        Integer statedCount = null;
        if (accents.isEmpty() && ACCENT_CONTEXT.matcher(text).find()) {
            for (Map.Entry<String, Integer> e : COUNT_WORDS.entrySet()) {
                Matcher m = Pattern.compile("\\b" + e.getKey() + "\\b").matcher(text);
                if (m.find() && !scope.isNegated(m.start())) {
                    statedCount = e.getValue();
                    break;
                }
            }
            if (statedCount != null) {
                accents.add(statedCount >= 4 ? NailDesignSpecDto.Finger.INDEX : NailDesignSpecDto.Finger.RING);
                if (statedCount >= 4) accents.add(NailDesignSpecDto.Finger.RING);
            }
        }

        NailDesignSpecDto.Symmetry symmetry = ASYMMETRIC.matcher(text).find()
                ? NailDesignSpecDto.Symmetry.ASYMMETRIC_STATED
                : NailDesignSpecDto.Symmetry.MIRRORED;

        // Every assumption reads as a Croatian sentence about the look, not as a field name and a value.
        if (shape == null) assumptions.add(Assumption.of("shape", "ALMOND", "almond oblik",
                "Oblik nije bio naveden u opisu, pa je pretpostavljen almond."));
        if (length == null) assumptions.add(Assumption.of("length", "SHORT", "kratki nokti",
                "Duljina nije bila navedena, pa su pretpostavljeni kratki nokti."));
        if (finish == null) assumptions.add(Assumption.of("finish", "GLOSSY", "sjajni završni sloj",
                "Završni sloj nije bio naveden, pa je pretpostavljen sjajni."));
        if (!accents.isEmpty() && symmetry == NailDesignSpecDto.Symmetry.MIRRORED) {
            assumptions.add(Assumption.of("symmetry", "MIRRORED", "isti dizajn na obje ruke",
                    "Nije bilo rečeno da se ruke razlikuju, pa je dizajn zrcalan na obje ruke."));
        }
        if (statedCount != null) {
            String fingers = accents.stream().map(NailDesignSpecDto.Finger::croatianLocative)
                    .reduce((a, b) -> a + " i " + b).orElse("");
            assumptions.add(Assumption.of("accentFingers", accents.toString(),
                    "detalj na " + fingers,
                    "Naveden je broj detalja (" + statedCount + "), ali ne i na kojem noktu. Stavljen je na "
                    + fingers + " — uz isti dizajn na obje ruke to daje točno toliko naglašenih noktiju."));
        }

        NailDesignSpecDto design = new NailDesignSpecDto(
                shape, length, colorKey, colorHex, colorRaw, finish, effects, accents,
                accentColorKey, accentColorHex,
                accents.isEmpty() ? "" : "diskretan detalj", symmetry, List.of(), List.of());

        NailLookBriefDto.ExecutionMode mode = requestedMode;
        if (mode == null || mode == NailLookBriefDto.ExecutionMode.UNSPECIFIED) {
            if (SALON.matcher(text).find()) mode = NailLookBriefDto.ExecutionMode.SALON;
            else if (AT_HOME.matcher(text).find()) mode = NailLookBriefDto.ExecutionMode.AT_HOME;
            else mode = NailLookBriefDto.ExecutionMode.UNSPECIFIED;
        }

        NailLookBriefDto.HomeProfile profile = new NailLookBriefDto.HomeProfile(
                NailLookBriefDto.HomeProfile.ExperienceLevel.FIRST_TIME,
                length == null ? NailDesignSpecDto.Length.SHORT : length,
                parseOwnership(text));

        NailLookBriefDto brief = new NailLookBriefDto(
                rawPrompt == null ? "" : rawPrompt.trim(), "HR", "EUR", Math.max(0, budgetCents),
                budgetCents > 0, false, mode, design, profile, false, assumptions);

        Matcher forbiddenMatcher = FORBIDDEN_SYSTEM.matcher(text);
        boolean forbidden = forbiddenMatcher.find() && !scope.isNegated(forbiddenMatcher.start());

        return new Parsed(brief,
                HEALTH_CONCERN.matcher(text).find(),
                forbidden,
                forbidden ? "Trazeni sustav (builder gel, polygel ili akril) ne preporucujemo za samostalnu "
                        + "primjenu kod kuce. Za tu duljinu i izgled sigurnija je press-on varijanta ili salon." : "",
                GEL_SYSTEM.matcher(text).find());
    }

    /**
     * Ownership. LACK is matched first and its span blanked out, so "nemam bazu" can never also register as
     * a HAVE. Each pattern captures only to the next separator, which is why "imam lampu, ali nemam bazu ni
     * top" keeps the lamp owned and the base and top missing - the claim cannot leak across the comma.
     */
    List<OwnedItemDto> parseOwnership(String text) {
        List<OwnedItemDto> items = new ArrayList<>();
        StringBuilder remaining = new StringBuilder(text);

        consume(remaining, LACK, (slot, raw) -> items.add(OwnedItemDto.missing(slot, raw)));
        // "ne trebam lampu" / "bez lampe" are exclusions, not absences: the slot is satisfied, not required.
        consume(remaining, NOT_NEEDED, (slot, raw) -> items.add(OwnedItemDto.owned(slot, raw)));
        consume(remaining, WITHOUT, (slot, raw) -> items.add(OwnedItemDto.owned(slot, raw)));
        consume(remaining, HAVE, (slot, raw) -> items.add(OwnedItemDto.owned(slot, raw)));
        return items;
    }

    private void consume(StringBuilder text, Pattern pattern, java.util.function.BiConsumer<String, String> sink) {
        Matcher m = pattern.matcher(text.toString());
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            for (String slot : slotsIn(m.group(1))) sink.accept(slot, m.group().trim());
            spans.add(new int[]{m.start(), m.end()});
        }
        for (int[] span : spans) {
            for (int i = span[0]; i < span[1] && i < text.length(); i++) text.setCharAt(i, ' ');
        }
    }

    private List<String> slotsIn(String fragment) {
        List<String> found = new ArrayList<>();
        if (fragment == null) return found;
        SLOT_WORDS.forEach((slot, pattern) -> {
            if (pattern.matcher(fragment).find()) found.add(slot);
        });
        if (found.isEmpty() && Pattern.compile("\\bnista\\b|nicega").matcher(fragment).find()) {
            found.addAll(SLOT_WORDS.keySet());
        }
        return found;
    }

    private <T> T firstMatch(Map<T, Pattern> table, String text, Negation scope) {
        for (Map.Entry<T, Pattern> e : table.entrySet()) {
            if (matchesOutsideNegation(e.getValue(), text, scope)) return e.getKey();
        }
        return null;
    }

    private boolean matchesOutsideNegation(Pattern pattern, String text, Negation scope) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (!scope.isNegated(m.start())) return true;
        }
        return false;
    }

    /**
     * Minimal negation scoper, private to this extractor. Marks a clause negated from its first cue to the
     * clause end, so "ne zelim cat-eye" suppresses the effect while "kratki nokti, ne preduga" leaves the
     * shape stated before the comma alone.
     */
    static final class Negation {
        private static final Pattern CUE =
                Pattern.compile("\\bne\\b|\\bbez\\b|\\bnemoj\\w*|\\bnecu\\b|\\bnikako\\b|\\bnije\\b");
        private final boolean[] negated;

        private Negation(boolean[] negated) {
            this.negated = negated;
        }

        static Negation of(String text) {
            boolean[] flags = new boolean[text.length() + 1];
            int clauseStart = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i != text.length() && ",;.!?".indexOf(text.charAt(i)) < 0) continue;
                Matcher m = CUE.matcher(text.substring(clauseStart, i));
                if (m.find()) {
                    for (int j = clauseStart + m.start(); j < i; j++) flags[j] = true;
                }
                clauseStart = i + 1;
            }
            return new Negation(flags);
        }

        boolean isNegated(int index) {
            return index >= 0 && index < negated.length && negated[index];
        }
    }

    /** Lowercase, fold d-stroke explicitly (NFD does not decompose it), then strip remaining diacritics. */
    static String normalize(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(Locale.ROOT).replace('đ', 'd').replace('Đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
