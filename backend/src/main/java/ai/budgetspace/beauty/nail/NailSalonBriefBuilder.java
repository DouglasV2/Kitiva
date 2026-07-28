package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.NailDesignSpecDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical slice — the salon brief: what to show a nail technician.
 *
 * <p><strong>This class has no catalog access, by construction.</strong> Its constructor takes only the
 * resolver. A salon brief that recommended products would be answering a question nobody asked — she is
 * paying a professional who chooses their own materials — and would drag the whole safety surface into a
 * branch that does not need it. The absence of a {@code NailPilotCatalog} dependency here is the
 * enforcement; a test asserting "no product names in the output" would only catch it after the fact.</p>
 */
@Component
public class NailSalonBriefBuilder {

    private final NailDesignResolver resolver;

    public NailSalonBriefBuilder(NailDesignResolver resolver) {
        this.resolver = resolver;
    }

    public record SalonBrief(
            List<SpecLine> specification,
            List<String> placement,
            String showToTechnician,
            List<String> techniqueNotes,
            String simplerAlternative,
            String variabilityDisclaimer
    ) { }

    public record SpecLine(String labelHr, String valueHr) { }

    public SalonBrief build(NailDesignSpecDto design) {
        List<SpecLine> spec = new ArrayList<>();
        spec.add(new SpecLine("Oblik", design.shape().croatianLabel()));
        spec.add(new SpecLine("Duljina", design.length().croatianLabel()));
        spec.add(new SpecLine("Boja", colorLabel(design)));
        spec.add(new SpecLine("Završni sloj", design.finish().croatianLabel()));
        spec.add(new SpecLine("Efekt", design.activeEffects().isEmpty() ? "bez posebnog efekta"
                : String.join(", ", design.activeEffects().stream()
                        .map(NailDesignSpecDto.Effect::croatianLabel).toList())));
        spec.add(new SpecLine("Naglasak", resolver.accentSummaryHr(design)));
        spec.add(new SpecLine("Ruke", design.symmetry().croatianLabel()));

        List<String> placement = resolver.resolve(design).stream()
                .map(nail -> nail.label() + ": " + (nail.accent()
                        ? colorLabel(design) + " + naglasak" : colorLabel(design)))
                .toList();

        return new SalonBrief(spec, placement, showToTechnician(design), techniqueNotes(design),
                simplerAlternative(design),
                "Ovo je shematski prikaz željenog dizajna, ne fotografija rezultata. Konačan izgled ovisi o "
                + "obliku i stanju prirodnih noktiju, tehnici i proizvodima koje salon koristi.");
    }

    /** The paragraph she screenshots and shows. Written to be read aloud, not parsed. */
    private String showToTechnician(NailDesignSpecDto design) {
        StringBuilder sb = new StringBuilder();
        sb.append(capitalize(design.length().croatianLabel())).append(' ')
          .append(design.shape().croatianLabel()).append(" nokti u ").append(colorLabel(design)).append(" boji");
        if (!design.activeEffects().isEmpty()) {
            sb.append(", s ").append(String.join(" i ", design.activeEffects().stream()
                    .map(NailDesignSpecDto.Effect::croatianLabel).toList())).append(" efektom");
        }
        sb.append(". Završni sloj ").append(design.finish().croatianLabel()).append('.');
        if (design.hasAccent()) {
            sb.append(' ').append(resolver.accentSummaryHr(design));
            sb.append(" Neka naglasak bude diskretan i tanak, ne krupan.");
        }
        sb.append(" Bez krupnih šljokica osim ako to izričito ne dogovorimo.");
        return sb.toString();
    }

    private List<String> techniqueNotes(NailDesignSpecDto design) {
        List<String> notes = new ArrayList<>();
        for (NailDesignSpecDto.Effect effect : design.activeEffects()) {
            switch (effect) {
                case CAT_EYE -> notes.add("Cat-eye: smjer magneta odredi refleksiju — za elegantniji izgled "
                        + "traži usku, uzdužnu liniju umjesto široke.");
                case CHROME -> notes.add("Chrome: nanosi se preko no-wipe top coata; traži da se efekt "
                        + "zapečati dodatnim slojem da ne blijedi po rubovima.");
                case FRENCH -> notes.add("French: dogovori debljinu linije unaprijed — tanja linija izgleda "
                        + "prirodnije na kratkim noktima.");
                case GLITTER_ACCENT -> notes.add("Glitter: traži sitan glitter u sloju, ne posipanje preko "
                        + "cijelog nokta, ako želiš diskretan efekt.");
                case NONE -> { }
            }
        }
        if (design.requiresExtension()) {
            notes.add("Ova duljina najčešće traži nadogradnju — pitaj salon koji sustav predlaže i koliko "
                    + "traje održavanje.");
        }
        if (notes.isEmpty()) notes.add("Jednostavan dizajn — nije potrebna posebna tehnika.");
        return notes;
    }

    private String simplerAlternative(NailDesignSpecDto design) {
        if (!design.activeEffects().isEmpty()) {
            return "Ako salon ne može izvesti efekt, jednobojna varijanta u istoj boji s "
                    + design.finish().croatianLabel() + " završnim slojem daje vrlo sličan ukupni dojam.";
        }
        if (design.hasAccent()) {
            return "Ako želiš još jednostavnije, izostavi naglasni nokat — dizajn ostaje isti.";
        }
        return "Dizajn je već jednostavan i izvediv u većini salona.";
    }

    private String colorLabel(NailDesignSpecDto design) {
        if (!design.baseColorRawText().isBlank()) return design.baseColorRawText();
        if (!design.baseColorKey().isBlank()) return design.baseColorKey();
        return "boja po dogovoru";
    }

    private String capitalize(String s) {
        return s == null || s.isBlank() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
