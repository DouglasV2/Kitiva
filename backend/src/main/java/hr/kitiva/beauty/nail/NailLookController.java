package hr.kitiva.beauty.nail;

import hr.kitiva.beauty.dto.KitStatus;
import hr.kitiva.beauty.dto.NailDesignSpecDto;
import hr.kitiva.beauty.dto.NailLookBriefDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Vertical slice - the two nail endpoints.
 *
 * <p>{@code POST /api/nail/parse} turns a Croatian prompt into an editable brief and stops. It does not
 * pick a branch: salon and at-home produce fundamentally different things, and one of them recommends
 * chemical products to a consumer, so the user answers that question rather than a regex guessing it.</p>
 *
 * <p>{@code POST /api/nail/generate} takes the (possibly EDITED) brief plus an explicit execution mode and
 * returns the salon brief or the at-home kit. The brief it receives is authoritative: whatever the user
 * corrected in the UI is what gets built, which is the whole point of making the brief editable.</p>
 */
@RestController
public class NailLookController {

    private final NailIntentExtractor extractor;
    private final NailDesignDiagramRenderer diagramRenderer;
    private final NailSalonBriefBuilder salonBriefBuilder;
    private final NailKitAssembler kitAssembler;
    private final NailFeedbackRepository feedbackRepository;

    public NailLookController(NailIntentExtractor extractor,
                              NailDesignDiagramRenderer diagramRenderer,
                              NailSalonBriefBuilder salonBriefBuilder,
                              NailKitAssembler kitAssembler,
                              NailFeedbackRepository feedbackRepository) {
        this.extractor = extractor;
        this.diagramRenderer = diagramRenderer;
        this.salonBriefBuilder = salonBriefBuilder;
        this.kitAssembler = kitAssembler;
        this.feedbackRepository = feedbackRepository;
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

    /**
     * Refinements travel WITH the generate request rather than on a separate mutate-the-kit endpoint. That
     * keeps one code path: a refined kit is re-derived from the brief and re-validated from scratch, so a
     * swap can never leave the kit in a state nobody checked for completeness. It also means revisiting or
     * refreshing a result recomputes the same totals instead of replaying a stored diff.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerateRequest(
            NailLookBriefDto brief,
            String executionMode,
            Map<String, String> pinnedBySlot,
            boolean preferCheapest,
            String singleRetailer,
            String system
    ) { }

    public record GenerateResponse(
            String executionMode,
            NailLookBriefDto brief,
            String designDiagramSvg,
            NailSalonBriefBuilder.SalonBrief salonBrief,
            NailKitAssembler.ValidatedKit kit,
            String blockedReasonHr,
            /** Retailers that can fill every required slot alone. Empty = "use one store" is not offerable. */
            List<String> singleStoreOptions
    ) { }

    @PostMapping("/api/nail/parse")
    public ParseResponse parse(@RequestBody ParseRequest request) {
        NailIntentExtractor.Parsed parsed = extractor.parse(
                request.prompt(), parseMode(request.executionMode()),
                request.budgetCents() == null ? 0 : request.budgetCents());

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
            // The salon brief carries the assumptions so the copy text names what we guessed. She is about to
            // hand this to a professional; the guesses are the part that most needs saying out loud.
            var salon = salonBriefBuilder.build(design, brief == null ? List.of() : brief.assumptions());
            return new GenerateResponse("SALON", brief, svg, salon, null, null, List.of());
        }

        if (mode == NailLookBriefDto.ExecutionMode.AT_HOME) {
            String requestedSystem = request.system() == null ? "" : request.system().trim().toLowerCase();

            // Re-run safety routing on the brief's OWN prompt, so an edited brief cannot smuggle a request
            // past the check made at parse time.
            NailIntentExtractor.Parsed recheck = extractor.parse(
                    brief == null ? "" : brief.prompt(), mode, brief == null ? 0 : brief.budgetCents());

            if (recheck.healthConcernDetected()) {
                return blocked(brief, svg,
                        "Opis spominje mogucu ozljedu, infekciju ili reakciju. U tom slucaju ne predlazemo "
                        + "samostalnu primjenu proizvoda na noktima. Obrati se dermatologu ili licenciranom "
                        + "nail tehnicaru. Ovo nije dijagnoza.");
            }
            if (recheck.forbiddenSystemRequested()) {
                return blocked(brief, svg, recheck.forbiddenSystemNote());
            }
            if (recheck.gelRequested()) {
                return blocked(brief, svg,
                        "Trajni (gel) lak zasad nije ukljucen u kucne prijedloge. Nijedan trgovac u ovom "
                        + "katalogu ne objavljuje popis sastojaka, pa se ne moze provjeriti sadrzi li "
                        + "ogranicene tvari ni koja je lampa ispravna. Za ovaj izgled predlazemo salon, "
                        + "klasicni lak u slicnoj boji, ili set umjetnih noktiju.");
            }
            // A length the natural nail cannot carry needs press-ons, not an extension system we do not
            // sell to consumers. Only block if the user insisted on regular polish for such a length.
            if (design != null && design.requiresExtension() && "regular-polish".equals(requestedSystem)) {
                return blocked(brief, svg,
                        "Tražena duljina se ne postize klasicnim lakom na prirodnom noktu. Prebaci na set "
                        + "umjetnih noktiju ili skrati duljinu u specifikaciji.");
            }

            NailKitAssembler.Preferences prefs = new NailKitAssembler.Preferences(
                    request.pinnedBySlot(), request.preferCheapest(), request.singleRetailer(),
                    requestedSystem.isBlank() ? null : requestedSystem);
            return new GenerateResponse("AT_HOME", brief, svg, null,
                    kitAssembler.assemble(brief, prefs), null,
                    kitAssembler.singleStoreOptions(prefs.system(), brief.design()));
        }

        return new GenerateResponse("UNSPECIFIED", brief, svg, null, null,
                "Odaberi izvedbu: u salonu ili kod kuce.", List.of());
    }

    private GenerateResponse blocked(NailLookBriefDto brief, String svg, String reason) {
        // A blocked kit carries no prep and no removal steps on purpose: those describe applying a product,
        // and the whole point of the block is that we are not recommending one.
        NailKitAssembler.ValidatedKit blockedKit = new NailKitAssembler.ValidatedKit(
                KitStatus.SAFETY_BLOCKED, KitStatus.SAFETY_BLOCKED.croatianLabel(), reason,
                List.of(), List.of(), List.of(), 0, 0, 0, null, null, 0,
                brief == null ? List.of() : brief.assumptions(),
                List.of("Ovo nije medicinski savjet ni dijagnoza."), List.of(), List.of(), "",
                // No products, so no prices, so nothing to say about how fresh they are.
                null);
        return new GenerateResponse("AT_HOME", brief, svg, null, blockedKit, reason, List.of());
    }

    /**
     * The two pilot questions. Both optional, so a request may carry one answer and not the other; a request
     * with neither is accepted and stored as nothing, because a validation error on a feedback form is worse
     * than a dropped row.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FeedbackRequest(String matchedExpectation, String wouldUse, String executionMode,
                                  String kitStatus, String prompt) { }

    public record FeedbackResponse(boolean stored) { }

    @PostMapping("/api/nail/feedback")
    public FeedbackResponse feedback(@RequestBody FeedbackRequest request) {
        NailFeedback.Match match = enumOrNull(NailFeedback.Match.class, request.matchedExpectation());
        NailFeedback.Intent intent = enumOrNull(NailFeedback.Intent.class, request.wouldUse());
        if (match == null && intent == null) return new FeedbackResponse(false);

        feedbackRepository.save(NailFeedback.of(match, intent, request.executionMode(),
                request.kitStatus(), request.prompt(), java.time.Instant.now()));
        return new FeedbackResponse(true);
    }

    private <E extends Enum<E>> E enumOrNull(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
