package hr.kitiva.beauty.makeup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The named looks, each as a completeness graph over makeup categories.
 *
 * <p><strong>What a look is here, precisely.</strong> It is a list of categories a face needs for that
 * result, split into required and optional, with a reason per category. It is NOT a claim that a particular
 * product achieves the look. That distinction is the whole design: we can say with confidence that full
 * glam needs a setting spray and everyday does not, because that is craft knowledge about categories. We
 * cannot say that a specific 6-euro mascara delivers "soft glam", because nobody published that and we did
 * not test it.</p>
 *
 * <p><strong>Preferences are soft, and silent when unprovable.</strong> A look may prefer a finish
 * ("bridal wants long-wear, not dewy") or a lip family ("bold evening wants a red or a berry"). Those only
 * steer the ranking when the RETAILER named the finish or the shade; when nothing in the catalog carries
 * that evidence the preference is dropped and an assumption is raised, rather than the pick being dressed
 * up as a match. Same rule as the nail vertical: a shade number is not a colour.</p>
 *
 * <p>Ordered as a face is actually made up — skin, then cheeks, then eyes, then lips, then set — because
 * the kit doubles as the running order, and a list that put lipstick before foundation would be teaching
 * the wrong thing.</p>
 */
public final class MakeupLook {

    private MakeupLook() { }

    /**
     * One category in a look.
     *
     * @param required true = the look does not exist without it, so the kit is incomplete if it is missing
     * @param whyHr    why THIS look needs it, not what the category is in general
     */
    public record Slot(String category, boolean required, String whyHr) {
        public static Slot req(String category, String whyHr) { return new Slot(category, true, whyHr); }
        public static Slot opt(String category, String whyHr) { return new Slot(category, false, whyHr); }
    }

    /**
     * @param preferredFinish   applied only to the categories in {@code finishAppliesTo}, and only when the
     *                          retailer named a finish at all
     * @param preferredLipShades shade families that suit this look, best first; ignored unless the retailer
     *                          named the shade
     */
    public record Definition(
            String key,
            String labelHr,
            String taglineHr,
            String descriptionHr,
            List<Slot> slots,
            String preferredFinish,
            List<String> finishAppliesTo,
            List<String> preferredLipShades,
            int order
    ) {
        public Definition {
            slots = slots == null ? List.of() : List.copyOf(slots);
            finishAppliesTo = finishAppliesTo == null ? List.of() : List.copyOf(finishAppliesTo);
            preferredLipShades = preferredLipShades == null ? List.of() : List.copyOf(preferredLipShades);
        }

        public List<Slot> requiredSlots() {
            return slots.stream().filter(Slot::required).toList();
        }

        public boolean wantsFinishFor(String category) {
            return preferredFinish != null && finishAppliesTo.contains(category);
        }
    }

    // Reasons that recur verbatim across looks. Written once so seven graphs cannot drift into seven
    // slightly different explanations of the same step.
    private static final String WHY_TOOLS =
            "Alat odlučuje koliko dobro proizvod legne — često više nego cijena samog proizvoda.";
    private static final String WHY_REMOVER =
            "Šminka se skida svaki dan. Kit koji se ne može skinuti nije potpun.";
    private static final String WHY_CONCEALER =
            "Podočnjaci i pojedinačne nesavršenosti — ondje gdje podloga nije dovoljna.";
    private static final String WHY_BROW =
            "Uredna obrva drži cijelo lice; bez nje ostatak izgleda nedovršeno.";

    public static final Definition NATURAL_EVERYDAY = new Definition(
            "natural-everyday", "Prirodno svaki dan",
            "Kao ti, samo naspavana",
            "Najmanji broj proizvoda koji vidljivo popravi lice, a da se ne vidi da je nešto rađeno. "
            + "Sve u tankim slojevima.",
            List.of(
                    Slot.opt("primer", "Produljuje trajanje, ali za svakodnevni look nije nužan."),
                    Slot.req("foundation", "Ujednačava ton — za ovaj look nanesena tanko, ne za punu prekrivnost."),
                    Slot.req("concealer", WHY_CONCEALER),
                    Slot.opt("powder", "Samo na T-zonu ako se sjaji; cijelo lice ne treba puder."),
                    Slot.req("blush", "Bez rumenila prirodan look izgleda umorno — to je proizvod koji ga spašava."),
                    Slot.req("brow", WHY_BROW),
                    Slot.req("mascara", "Otvara oko bez ijednog drugog proizvoda na kapku."),
                    Slot.req("lipstick", "Ton blizu prirodne boje usne, da se ne čita kao šminka."),
                    Slot.req("tools", WHY_TOOLS),
                    Slot.req("remover", WHY_REMOVER)),
            "natural", List.of("foundation"), List.of("nude", "peach", "pink"), 1);

    public static final Definition CLEAN_GIRL = new Definition(
            "clean-girl", "Clean girl",
            "Njegovana koža, minimum proizvoda",
            "Koža se vidi. Naglasak je na sjaju i urednoj obrvi, a ne na prekrivanju.",
            List.of(
                    Slot.opt("foundation", "Neobavezno — clean girl look živi od toga da se koža vidi."),
                    Slot.req("concealer", "Točkasto, samo tamo gdje treba. Ovo zamjenjuje podlogu."),
                    Slot.req("blush", "Kremasto rumenilo daje zdrav ton bez pudrastog sloja."),
                    Slot.req("brow", "Očešljana i lagano popunjena obrva nosi cijeli look."),
                    Slot.req("mascara", "Jedan sloj, samo da se trepavica vidi."),
                    Slot.req("lipstick", "Balzam ili tinta — usna izgleda njegovano, ne našminkano."),
                    Slot.opt("highlighter", "Diskretno na jagodice ako želiš više sjaja."),
                    Slot.req("tools", WHY_TOOLS),
                    Slot.req("remover", WHY_REMOVER)),
            "dewy", List.of("foundation", "blush"), List.of("nude", "peach", "pink"), 2);

    public static final Definition SOFT_GLAM = new Definition(
            "soft-glam", "Soft glam",
            "Dotjerano, ali još uvijek ti",
            "Definirano oko i skulpturirano lice, bez oštrih rubova. Look za večeru, rođendan ili posao "
            + "gdje se želiš dotjerati.",
            List.of(
                    Slot.req("primer", "Look ima više slojeva, pa podloga mora imati na čemu stajati."),
                    Slot.req("foundation", "Ujednačen ton je baza za sve što dolazi poslije."),
                    Slot.req("concealer", WHY_CONCEALER),
                    Slot.req("powder", "Fiksira tekuće slojeve prije nego dođu pudrasti."),
                    Slot.opt("bronzer", "Vraća toplinu licu nakon podloge."),
                    Slot.req("blush", "Povezuje oko i usnu — bez njega lice izgleda plosnato."),
                    Slot.req("highlighter", "Točke svjetla su ono što ovaj look razlikuje od svakodnevnog."),
                    Slot.req("eyeshadow", "Toplo, stopljeno oko — srce soft glama."),
                    Slot.opt("eyeliner", "Tanka linija uz trepavice ako želiš više definicije."),
                    Slot.req("mascara", "Bez maskare stopljeno oko izgleda nedovršeno."),
                    Slot.req("brow", WHY_BROW),
                    Slot.req("lipstick", "Topli nude ili roza; jaka boja pomiče look u full glam."),
                    Slot.opt("setting-spray", "Topi prijelaze i drži look kroz večer."),
                    Slot.req("tools", WHY_TOOLS),
                    Slot.req("remover", WHY_REMOVER)),
            "satin", List.of("eyeshadow", "lipstick"), List.of("nude", "peach", "pink", "brown"), 3);

    public static final Definition DATE_NIGHT = new Definition(
            "date-night", "Date night",
            "Toplo svjetlo, naglašeno oko",
            "Građen za prigušeno svjetlo: topli tonovi, malo više sjaja i usna koja se vidi.",
            List.of(
                    Slot.req("foundation", "Ujednačen ton koji izdrži cijelu večer."),
                    Slot.req("concealer", WHY_CONCEALER),
                    Slot.req("bronzer", "Toplina je ono što ovaj look čini večernjim."),
                    Slot.req("blush", "Na jabučice, malo više nego danju."),
                    Slot.req("highlighter", "Hvata svjetlo u restoranu — zato je ovdje obavezan."),
                    Slot.req("eyeshadow", "Topli ton na cijelom kapku."),
                    Slot.req("eyeliner", "Definira oko na udaljenosti od pola stola."),
                    Slot.req("mascara", "Otvara oko u prigušenom svjetlu."),
                    Slot.req("brow", WHY_BROW),
                    Slot.req("lipstick", "Ton koji se vidi, ali se ne prenosi na sve."),
                    Slot.opt("setting-spray", "Ako večer traje dulje od nekoliko sati."),
                    Slot.req("tools", WHY_TOOLS),
                    Slot.req("remover", WHY_REMOVER)),
            "satin", List.of("eyeshadow"), List.of("brown", "peach", "burgundy", "red"), 4);

    public static final Definition FULL_GLAM = new Definition(
            "full-glam", "Full glam",
            "Sve na svom mjestu",
            "Puna prekrivnost, izgrađeno oko, iscrtana usna. Najviše proizvoda i najviše vremena — "
            + "look za koji se stvarno sjeda pred ogledalo.",
            List.of(
                    Slot.req("primer", "Puni slojevi bez prajmera se lome tijekom večeri."),
                    Slot.req("foundation", "Puna prekrivnost je definicija ovog looka."),
                    Slot.req("concealer", "I korekcija i posvjetljivanje ispod oka."),
                    Slot.req("powder", "Bez pudera se puna podloga premješta."),
                    Slot.req("bronzer", "Kontura vraća strukturu licu koje je podloga izravnala."),
                    Slot.req("blush", "Boja natrag na lice nakon konture."),
                    Slot.req("highlighter", "Vrhovi jagodica, luk usne, hrbat nosa."),
                    Slot.req("eyeshadow", "Izgrađeno oko s više tonova."),
                    Slot.req("eyeliner", "Oštra linija — jedan od dva potpisa ovog looka."),
                    Slot.req("mascara", "Ili maskara ili umjetne trepavice; ovdje je maskara minimum."),
                    Slot.req("brow", WHY_BROW),
                    Slot.req("lipliner", "Drugi potpis: iscrtan rub koji sprječava razlijevanje."),
                    Slot.req("lipstick", "Puna boja unutar iscrtanog ruba."),
                    Slot.req("setting-spray", "Ovoliko slojeva bez fiksatora ne preživi večer."),
                    Slot.req("tools", WHY_TOOLS),
                    Slot.req("remover", WHY_REMOVER)),
            "matte", List.of("foundation", "lipstick"), List.of("red", "burgundy", "nude", "brown"), 5);

    public static final Definition BRIDAL = new Definition(
            "bridal", "Bridal",
            "Izdrži dvanaest sati i fotoaparat",
            "Sve je birano po trajnosti i po tome kako izgleda na fotografiji. Manje sjaja nego kod glam "
            + "lookova, jer bljeskalica pretvara highlighter u masnoću.",
            List.of(
                    Slot.req("primer", "Dvanaest sati počinje ovdje."),
                    Slot.req("foundation", "Ujednačen ton koji ne oksidira do večere."),
                    Slot.req("concealer", "Fotografija je nemilosrdna prema podočnjacima."),
                    Slot.req("powder", "Fiksira sve tekuće slojeve; ključno za bljeskalicu."),
                    Slot.opt("bronzer", "Diskretno — na fotografiji kontura brzo postane mrlja."),
                    Slot.req("blush", "Bez rumenila lice na fotografiji izgleda blijedo."),
                    Slot.req("highlighter", "Vrlo malo i vrlo precizno, zbog bljeskalice."),
                    Slot.req("eyeshadow", "Neutralni tonovi koji dobro stare na fotografijama."),
                    Slot.req("eyeliner", "Definira oko bez oštrog krila."),
                    Slot.req("mascara", "Otvara oko na svakoj fotografiji."),
                    Slot.req("brow", WHY_BROW),
                    Slot.req("lipliner", "Sprječava razlijevanje kroz cijeli dan."),
                    Slot.req("lipstick", "Ton koji izdrži jelo, piće i sto poljubaca."),
                    Slot.req("setting-spray", "Posljednji korak i razlog zašto sve ostaje na mjestu."),
                    Slot.req("tools", WHY_TOOLS),
                    Slot.req("remover", WHY_REMOVER)),
            "matte", List.of("foundation", "lipstick"), List.of("nude", "pink", "peach"), 6);

    public static final Definition BOLD_EVENING = new Definition(
            "bold-evening", "Bold evening",
            "Jedan element glasan, ostalo tiho",
            "Tamno oko ili jaka usna — jedno od to dvoje nosi look, a ostatak lica je namjerno smiren.",
            List.of(
                    Slot.req("foundation", "Smiren, ujednačen ton koji pušta naglasak da radi."),
                    Slot.req("concealer", "Čisti rub ispod oka, posebno uz tamno sjenilo."),
                    Slot.req("powder", "Drži podlogu mirnom ispod jakog oka."),
                    Slot.opt("bronzer", "Malo topline da lice ne ostane plosnato."),
                    Slot.req("eyeshadow", "Tamni ili zasićeni ton — prvi kandidat za naglasak."),
                    Slot.req("eyeliner", "Bez čiste linije jako oko izgleda razmazano."),
                    Slot.req("mascara", "Povezuje sjenilo i liniju trepavica."),
                    Slot.req("brow", WHY_BROW),
                    Slot.req("lipstick", "Drugi kandidat za naglasak: crvena, bordo ili duboka boja."),
                    Slot.req("setting-spray", "Jaki pigmenti se lakše pomiču — fiksator ih drži."),
                    Slot.req("tools", WHY_TOOLS),
                    Slot.req("remover", WHY_REMOVER)),
            "matte", List.of("lipstick"), List.of("red", "burgundy", "purple", "brown"), 7);

    private static final Map<String, Definition> BY_KEY = new LinkedHashMap<>();
    static {
        for (Definition d : List.of(NATURAL_EVERYDAY, CLEAN_GIRL, SOFT_GLAM, DATE_NIGHT,
                FULL_GLAM, BRIDAL, BOLD_EVENING)) {
            BY_KEY.put(d.key(), d);
        }
    }

    public static List<Definition> all() {
        return List.copyOf(BY_KEY.values());
    }

    /** The look for this key, or the everyday one — the safest default for someone who stated nothing. */
    public static Definition byKeyOrDefault(String key) {
        if (key == null || key.isBlank()) return NATURAL_EVERYDAY;
        return BY_KEY.getOrDefault(key.trim().toLowerCase(Locale.ROOT), NATURAL_EVERYDAY);
    }

    public static boolean isKnown(String key) {
        return key != null && BY_KEY.containsKey(key.trim().toLowerCase(Locale.ROOT));
    }
}
