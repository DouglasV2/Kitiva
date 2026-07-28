package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.NailDesignSpecDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Vertical slice — renders the resolved design as a deterministic SVG <strong>Design Diagram</strong>.
 *
 * <p>Called a Design Diagram everywhere, never an "inspiration image" and never implied photorealistic. It
 * exists to let a user verify that we understood her: shape, length, colour, effects and which nail carries
 * the accent. A generated photo would look more impressive and prove less — and would risk depicting an
 * effect the kit cannot actually buy.</p>
 *
 * <p>Pure function of {@link NailDesignResolver} output: same spec in, byte-identical SVG out. No cache
 * table, because an SVG re-renders in microseconds; no image provider, because none is needed to satisfy
 * "the preview comes from the structured spec".</p>
 */
@Component
public class NailDesignDiagramRenderer {

    private final NailDesignResolver resolver;

    public NailDesignDiagramRenderer(NailDesignResolver resolver) {
        this.resolver = resolver;
    }

    private static final String NEUTRAL_NAIL = "#E8DED6";
    private static final String OUTLINE = "#9A8C82";
    private static final String TEXT = "#3A3330";

    public String render(NailDesignSpecDto design) {
        List<NailDesignResolver.NailPlacement> nails = resolver.resolve(design);
        String fill = design.baseColorHex() == null || design.baseColorHex().isBlank()
                ? NEUTRAL_NAIL : design.baseColorHex();

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 640 300\" width=\"640\" height=\"300\" ")
           .append("role=\"img\" aria-label=\"").append(esc(ariaLabel(design))).append("\">");
        svg.append("<rect width=\"640\" height=\"300\" fill=\"#FBF7F4\"/>");

        // Two hands, five nails each, in the resolver's fixed order — that order is what makes this
        // deterministic and therefore testable byte-for-byte.
        for (int i = 0; i < nails.size(); i++) {
            NailDesignResolver.NailPlacement nail = nails.get(i);
            int handIndex = i / 5;
            int fingerIndex = i % 5;
            int x = 60 + handIndex * 320 + fingerIndex * 52;
            int baseY = 190;
            int height = nailHeight(design.length(), fingerIndex);
            int width = 34;
            int y = baseY - height;

            svg.append(nailShape(x, y, width, height, design.shape(), fill));
            appendEffects(svg, design, x, y, width, height);
            if (nail.accent()) {
                // A thin gold crescent — the one accent form this MVP draws, matching what the kit can buy.
                svg.append("<path d=\"M").append(x + 4).append(' ').append(y + 10)
                   .append(" Q").append(x + width / 2).append(' ').append(y - 4)
                   .append(' ').append(x + width - 4).append(' ').append(y + 10)
                   .append("\" fill=\"none\" stroke=\"#C9A227\" stroke-width=\"2.4\" stroke-linecap=\"round\"/>");
            }
            svg.append("<text x=\"").append(x + width / 2).append("\" y=\"").append(baseY + 18)
               .append("\" font-family=\"system-ui,sans-serif\" font-size=\"9\" fill=\"").append(TEXT)
               .append("\" text-anchor=\"middle\">").append(esc(shortFinger(nail.finger()))).append("</text>");
        }

        svg.append("<text x=\"120\" y=\"246\" font-family=\"system-ui,sans-serif\" font-size=\"13\" fill=\"")
           .append(TEXT).append("\" text-anchor=\"middle\">").append(esc("lijeva ruka")).append("</text>");
        svg.append("<text x=\"440\" y=\"246\" font-family=\"system-ui,sans-serif\" font-size=\"13\" fill=\"")
           .append(TEXT).append("\" text-anchor=\"middle\">").append(esc("desna ruka")).append("</text>");
        svg.append("<text x=\"320\" y=\"278\" font-family=\"system-ui,sans-serif\" font-size=\"11\" fill=\"#7A6E66\" ")
           .append("text-anchor=\"middle\">").append(esc(caption(design))).append("</text>");
        svg.append("</svg>");
        return svg.toString();
    }

    /** Nail plate outline. Shape changes the tip geometry, which is the part a technician actually files. */
    private String nailShape(int x, int y, int w, int h, NailDesignSpecDto.Shape shape, String fill) {
        int r = switch (shape) {
            case SQUARE -> 3;
            case SQUOVAL -> 8;
            case ROUND, OVAL -> w / 2;
            case ALMOND, STILETTO, COFFIN -> 6;
        };
        StringBuilder sb = new StringBuilder();
        if (shape == NailDesignSpecDto.Shape.ALMOND || shape == NailDesignSpecDto.Shape.STILETTO) {
            int point = shape == NailDesignSpecDto.Shape.STILETTO ? 16 : 9;
            sb.append("<path d=\"M").append(x).append(' ').append(y + h)
              .append(" L").append(x).append(' ').append(y + point)
              .append(" Q").append(x + w / 2).append(' ').append(y - point / 2)
              .append(' ').append(x + w).append(' ').append(y + point)
              .append(" L").append(x + w).append(' ').append(y + h).append(" Z\"");
        } else if (shape == NailDesignSpecDto.Shape.COFFIN) {
            sb.append("<path d=\"M").append(x).append(' ').append(y + h)
              .append(" L").append(x + 4).append(' ').append(y)
              .append(" L").append(x + w - 4).append(' ').append(y)
              .append(" L").append(x + w).append(' ').append(y + h).append(" Z\"");
        } else {
            sb.append("<rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"").append(w)
              .append("\" height=\"").append(h).append("\" rx=\"").append(r).append("\"");
        }
        sb.append(" fill=\"").append(fill).append("\" stroke=\"").append(OUTLINE).append("\" stroke-width=\"1.2\"/>");
        return sb.toString();
    }

    private void appendEffects(StringBuilder svg, NailDesignSpecDto design, int x, int y, int w, int h) {
        for (NailDesignSpecDto.Effect effect : design.activeEffects()) {
            switch (effect) {
                case CAT_EYE -> svg.append("<rect x=\"").append(x + 3).append("\" y=\"").append(y + h / 3)
                        .append("\" width=\"").append(w - 6).append("\" height=\"5\" rx=\"2.5\" fill=\"#FFFFFF\" opacity=\"0.55\"/>");
                case CHROME -> svg.append("<rect x=\"").append(x + 2).append("\" y=\"").append(y + 4)
                        .append("\" width=\"").append(w - 4).append("\" height=\"").append(Math.max(6, h / 3))
                        .append("\" rx=\"4\" fill=\"#FFFFFF\" opacity=\"0.38\"/>");
                case FRENCH -> svg.append("<path d=\"M").append(x).append(' ').append(y + 12)
                        .append(" Q").append(x + w / 2).append(' ').append(y + 2).append(' ')
                        .append(x + w).append(' ').append(y + 12)
                        .append(" L").append(x + w).append(' ').append(y).append(" L").append(x).append(' ')
                        .append(y).append(" Z\" fill=\"#FFFFFF\" opacity=\"0.9\"/>");
                case GLITTER_ACCENT -> {
                    for (int g = 0; g < 5; g++) {
                        svg.append("<circle cx=\"").append(x + 7 + (g * 5) % (w - 12))
                           .append("\" cy=\"").append(y + 10 + (g * 9) % Math.max(10, h - 14))
                           .append("\" r=\"1.4\" fill=\"#FFFFFF\" opacity=\"0.85\"/>");
                    }
                }
                case NONE -> { }
            }
        }
    }

    private int nailHeight(NailDesignSpecDto.Length length, int fingerIndex) {
        int base = switch (length) {
            case SHORT -> 42;
            case MEDIUM -> 56;
            case LONG -> 72;
            case EXTRA_LONG -> 88;
        };
        // Thumb slightly wider/shorter, pinky shorter — so the diagram reads as a hand, not a row of tiles.
        return switch (fingerIndex) {
            case 0 -> base - 6;
            case 4 -> base - 10;
            case 2 -> base + 4;
            default -> base;
        };
    }

    private String shortFinger(ai.budgetspace.beauty.dto.NailDesignSpecDto.Finger finger) {
        return switch (finger) {
            case THUMB -> "palac";
            case INDEX -> "kaž.";
            case MIDDLE -> "sred.";
            case RING -> "prst.";
            case PINKY -> "mali";
        };
    }

    private String caption(NailDesignSpecDto design) {
        String color = design.baseColorRawText().isBlank() ? design.baseColorKey() : design.baseColorRawText();
        String effects = design.activeEffects().isEmpty() ? "bez efekta"
                : String.join(", ", design.activeEffects().stream()
                        .map(NailDesignSpecDto.Effect::croatianLabel).toList());
        return String.format(Locale.ROOT, "%s • %s • %s • %s — shematski prikaz, nije fotografija",
                design.shape().croatianLabel(), design.length().croatianLabel(),
                color.isBlank() ? "boja nije navedena" : color, effects);
    }

    private String ariaLabel(NailDesignSpecDto design) {
        return "Shematski dijagram dizajna noktiju: " + design.shape().croatianLabel() + ", "
                + design.length().croatianLabel() + ", " + design.accentFingers().size() + " naglasnih noktiju.";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
