package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.KitStatus;
import ai.budgetspace.beauty.dto.NailDesignSpecDto;
import ai.budgetspace.beauty.dto.NailLookBriefDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Vertical slice — the two nail endpoints.
 *
 * <p>{@code POST /api/nail/parse} turns a Croatian prompt into an editable brief and stops. It does not
 * pick a branch: salon and at-home produce fundamentally different things, and one of them sells a consumer
 * a chemical system, so the user answers that question rather than a regex guessing it.</p>
 *
 * <p>{@code POST /api/nail/generate} takes the (possibly edited) brief plus an explicit execution mode and
 * returns the salon brief or the at-home kit.</p>
 */
@RestController
public class NailLookController {

    private final NailIntentExtractor extractor;
    private final NailDesignResolver resolver;
    private final NailDesignDiagramRenderer diagramRenderer;
    private final NailSalonBriefBuilder salonBriefBuilder;
    private final NailKitAssembler kitAssembler;

    public NailLookController(NailIntentExtractor extractor,
                              NailDesignResolver resolver,
                              NailDesignDiagramRenderer diagramRenderer,
                              NailSalonBriefBuilder salonBriefBuilder,
                              NailKitAssembler kitAssembler) {
        this.extractor = extractor;
        this.resolver = resolver;
        this.diagramRenderer = diagramRenderer;
        this.salonBriefBuilder = salonBriefBuilder;
        this.kitAssembler = kitAssembler;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParseRequest(String prompt, Integer budgetCents, String executionMode) { }

    public record ParseResponse(
            NailLookBriefDto brief,
            String designDiagramSvg,
            boolean needsExecutionModeAnswer,
            boolean healthConcernDetected,
            boolean forbiddenSystemRequested,
            String forbiddenSystemNote,
            boolean gelRequested
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerateRequest(NailLookBriefDto brief, String executionMode) { }

    public record GenerateResponse(
            String executionMode,
            NailLookBriefDto brief,
            String designDiagramSvg,
            NailSalonBriefBuilder.SalonBrief salonBrief,
            NailKitAssembler.ValidatedKit kit,
            String blockedReasonHr
    ) { }

    @PostMapping("/api/nail/parse")
    public ParseResponse parse(@RequestBody ParseRequest request) {
        NailLookBriefDto.ExecutionMode mode = parseMode(request.executionMode());
        NailIntentExtractor.Parsed parsed = extractor.parse(
                request.prompt(), mode, request.budgetCents() == null ? 0 : request.budgetCents());

        return new ParseResponse(
                parsed.brief(),
                diagramRenderer.render(parsed.brief().design()),
                parsed.brief().needsExecutionModeAnswer(),
                parsed.healthConcernDetected(),
                parsed.forbiddenSystemRequested(),
                parsed.forbiddenSystemNote(),
                parsed.gelRequested());
    }

    @PostMapping("/api/nail/generate")
    public GenerateResponse generate(@RequestBody GenerateRequest request) {
        NailLookBriefDto brief = request.brief();
        NailLookBriefDto.ExecutionMode mode = parseMode(request.executionMode());
        if (mode == NailLookBriefDto.ExecutionMode.UNSPECIFIED && brief != null) mode = brief.executionMode();

        NailDesignSpecDto design = brief == null ? null : brief.design();
        String svg = design == null ? "" : diagramRenderer.render(design);

        if (mode == NailLookBriefDto.ExecutionMode.SALON) {
            return new GenerateResponse("SALON", brief, svg, salonBriefBuilder.build(design), null, null);
        }

        if (mode == NailLookBriefDto.ExecutionMode.AT_HOME) {
            // Re-run the safety routing on the brief's own prompt, so an edited brief cannot smuggle a
            // request past the check that was made at parse time.
            NailIntentExtractor.Parsed recheck = extractor.parse(
                    brief == null ? "" : brief.prompt(), mode, brief == null ? 0 : brief.budgetCents());

            if (recheck.healthConcernDetected()) {
                return blocked(brief, svg,
                        "Opis spominje moguću ozljedu, infekciju ili reakciju. U tom slučaju ne predlažemo "
                        + "samostalnu primjenu proizvoda na noktima. Obrati se dermatologu ili licenciranom "
                        + "nail tehničaru. Ovo nije dijagnoza.");
            }
            if (recheck.forbiddenSystemRequested()) {
                return blocked(brief, svg, recheck.forbiddenSystemNote());
            }
            if (recheck.gelRequested() || design != null && design.requiresExtension()) {
                return blocked(brief, svg,
                        "Trajni (gel) lak i nadogradnja zasad nisu uključeni u kućne prijedloge — nedostaju "
                        + "provjereni podaci o sastojcima i kompatibilnosti lampe. Za ovaj izgled predlažemo "
                        + "salon, ili klasični lak u sličnoj boji.");
            }
            return new GenerateResponse("AT_HOME", brief, svg, null, kitAssembler.assemble(brief), null);
        }

        return new GenerateResponse("UNSPECIFIED", brief, svg, null, null,
                "Odaberi izvedbu: u salonu ili kod kuće.");
    }

    private GenerateResponse blocked(NailLookBriefDto brief, String svg, String reason) {
        NailKitAssembler.ValidatedKit blockedKit = new NailKitAssembler.ValidatedKit(
                KitStatus.SAFETY_BLOCKED, KitStatus.SAFETY_BLOCKED.croatianLabel(), reason,
                List.of(), List.of(), List.of(), 0, 0, 0, null, null, 0,
                brief == null ? List.of() : brief.assumptions(),
                List.of("Ovo nije medicinski savjet ni dijagnoza."), "");
        return new GenerateResponse("AT_HOME", brief, svg, null, blockedKit, reason);
    }

    private NailLookBriefDto.ExecutionMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) return NailLookBriefDto.ExecutionMode.UNSPECIFIED;
        try {
            return NailLookBriefDto.ExecutionMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NailLookBriefDto.ExecutionMode.UNSPECIFIED;
        }
    }
}
