package ai.budgetspace.beauty.nail;

import ai.budgetspace.beauty.dto.NailDesignSpecDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical slice — resolves a {@link NailDesignSpecDto} into ten concrete per-nail placements.
 *
 * <p>Sole owner of {@code resolve()}. The diagram renderer, the salon brief and the kit assembler all read
 * this output rather than re-deriving placement, so the three can never disagree about which nail carries
 * the accent — the failure that makes a picture promise something the brief does not say.</p>
 *
 * <p>Order is fixed: left thumb → left pinky, then right thumb → right pinky. Fixed order is what makes the
 * diagram deterministic, which is what makes it testable byte-for-byte.</p>
 */
@Component
public class NailDesignResolver {

    /** One nail: which hand, which finger, and what goes on it. */
    public record NailPlacement(
            Hand hand,
            NailDesignSpecDto.Finger finger,
            String baseColorKey,
            String baseColorHex,
            boolean accent,
            String accentDescription,
            List<NailDesignSpecDto.Effect> effects
    ) {
        public String label() {
            return hand.croatianLabel() + " " + finger.croatianLabel();
        }
    }

    public enum Hand {
        LEFT("lijeva"), RIGHT("desna");
        private final String hr;
        Hand(String hr) { this.hr = hr; }
        public String croatianLabel() { return hr; }
    }

    private static final List<NailDesignSpecDto.Finger> FINGER_ORDER = List.of(
            NailDesignSpecDto.Finger.THUMB, NailDesignSpecDto.Finger.INDEX,
            NailDesignSpecDto.Finger.MIDDLE, NailDesignSpecDto.Finger.RING,
            NailDesignSpecDto.Finger.PINKY);

    public List<NailPlacement> resolve(NailDesignSpecDto design) {
        List<NailPlacement> out = new ArrayList<>(10);
        for (Hand hand : Hand.values()) {
            for (NailDesignSpecDto.Finger finger : FINGER_ORDER) {
                boolean accent = isAccent(design, hand, finger);
                out.add(new NailPlacement(
                        hand, finger,
                        design.baseColorKey(), design.baseColorHex(),
                        accent,
                        accent ? design.accentDescription() : "",
                        List.copyOf(design.effects())));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Accent placement.
     *
     * <p>MIRRORED puts the accent on the same named finger of both hands — the overwhelmingly common
     * request ("zlatni detalj na prstenjacima"). ASYMMETRIC_STATED places it on the right hand only, and
     * only because the user explicitly said the hands differ; the spec carries no per-hand detail in MVP,
     * so the brief says plainly which hand it assumed rather than pretending to more precision.</p>
     */
    private boolean isAccent(NailDesignSpecDto design, Hand hand, NailDesignSpecDto.Finger finger) {
        if (!design.accentFingers().contains(finger)) return false;
        if (design.symmetry() == NailDesignSpecDto.Symmetry.MIRRORED) return true;
        return hand == Hand.RIGHT;
    }

    /** Human summary of where accents land, for the salon brief. */
    public String accentSummaryHr(NailDesignSpecDto design) {
        if (!design.hasAccent()) return "Bez naglasnog nokta — isti dizajn na svih deset.";
        String fingers = String.join(" i ", design.accentFingers().stream()
                .map(NailDesignSpecDto.Finger::croatianLabel).toList());
        return design.symmetry() == NailDesignSpecDto.Symmetry.MIRRORED
                ? "Naglasak na " + fingers + " — zrcalno na obje ruke."
                : "Naglasak na " + fingers + " — samo na desnoj ruci (navedeno je da se ruke razlikuju).";
    }
}
