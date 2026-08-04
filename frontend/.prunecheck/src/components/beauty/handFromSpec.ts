/**
 * Turns the backend's NailDesignSpec into HandPreview props.
 *
 * <p>Kept apart from both sides on purpose. HandPreview knows nothing about this app's DTOs — it is a
 * drawing component with a drawing-shaped API, so it stays usable for a swatch, a share card or a future
 * vertical. The spec knows nothing about SVG. This file is the only place the two vocabularies meet, which
 * is also the only place a mismatch can hide.</p>
 */
import type { NailDesignSpec, NailEffect as SpecEffect, NailFinger } from '../../types/beauty';
import type { Finger, NailDesign, NailEffect, NailFinish, NailLength, NailShape } from './HandPreview';

const SHAPES: Record<string, NailShape> = {
  ALMOND: 'almond', OVAL: 'oval', SQUARE: 'square', SQUOVAL: 'squoval',
  ROUND: 'round', COFFIN: 'coffin', STILETTO: 'stiletto',
};

const LENGTHS: Record<string, NailLength> = {
  SHORT: 'short', MEDIUM: 'medium', LONG: 'long', EXTRA_LONG: 'extra-long',
};

const FINISHES: Record<string, NailFinish> = {
  GLOSSY: 'glossy', MATTE: 'matte', SATIN: 'satin', SHIMMER: 'shimmer',
};

/** NONE is not an effect, it is the absence of one, so it maps to nothing rather than to a no-op. */
const EFFECTS: Partial<Record<SpecEffect, NailEffect>> = {
  CAT_EYE: 'cat-eye', CHROME: 'chrome', FRENCH: 'french', GLITTER_ACCENT: 'glitter',
};

const FINGERS: Record<NailFinger, Finger> = {
  THUMB: 'thumb', INDEX: 'index', MIDDLE: 'middle', RING: 'ring', PINKY: 'pinky',
};

/** A bare nail, when no colour was stated. Warm keratin, not a pink placeholder. */
const BARE = '#E8DCD6';

export interface HandPropsFromSpec {
  design: NailDesign;
  perNail: Partial<Record<Finger, NailDesign>>;
  ariaLabel: string;
}

export function handPropsFromSpec(spec: NailDesignSpec | null | undefined): HandPropsFromSpec {
  if (!spec) {
    return { design: { shape: 'almond', length: 'short', color: BARE }, perNail: {}, ariaLabel: 'Pregled noktiju' };
  }

  const design: NailDesign = {
    shape: SHAPES[spec.shape] ?? 'almond',
    length: LENGTHS[spec.length] ?? 'short',
    color: spec.baseColorHex || BARE,
    finish: FINISHES[spec.finish] ?? 'glossy',
    effects: (spec.effects ?? []).map((e) => EFFECTS[e]).filter((e): e is NailEffect => Boolean(e)),
    accentColor: spec.accentColorHex || undefined,
  };

  // The accent is per-finger, which is the whole reason the component takes per-nail overrides: a two-tone
  // request is only honest if the picture puts the second colour on the fingers she actually named.
  const perNail: Partial<Record<Finger, NailDesign>> = {};
  if (spec.accentColorHex) {
    for (const f of spec.accentFingers ?? []) {
      const key = FINGERS[f];
      if (key) perNail[key] = { accent: true, accentColor: spec.accentColorHex };
    }
  }

  return { design, perNail, ariaLabel: describe(spec) };
}

/** The picture carries no text, so everything it says has to be said here instead. */
function describe(spec: NailDesignSpec): string {
  const bits = [
    shapeHr(spec.shape),
    lengthHr(spec.length),
    spec.baseColorRawText || spec.baseColorKey || 'boja nije navedena',
    `${finishHr(spec.finish)} završni sloj`,
  ];
  const effects = (spec.effects ?? []).filter((e) => e !== 'NONE');
  if (effects.length) bits.push(`efekt ${effects.map(effectHr).join(' i ')}`);
  if (spec.accentFingers?.length) bits.push(`detalj na ${spec.accentFingers.length} nokta`);
  return `Prikaz manikure na ruci: ${bits.join(', ')}.`;
}

const shapeHr = (s: string) => ({
  ALMOND: 'almond', OVAL: 'ovalni', SQUARE: 'četvrtasti', SQUOVAL: 'četvrtasto-ovalni',
  ROUND: 'okrugli', COFFIN: 'coffin', STILETTO: 'stiletto',
}[s] ?? s.toLowerCase());

const lengthHr = (l: string) => ({
  SHORT: 'kratki', MEDIUM: 'srednji', LONG: 'dugi', EXTRA_LONG: 'vrlo dugi',
}[l] ?? l.toLowerCase());

const finishHr = (f: string) => ({
  GLOSSY: 'sjajni', MATTE: 'mat', SATIN: 'saten', SHIMMER: 'šimeran',
}[f] ?? f.toLowerCase());

const effectHr = (e: string) => ({
  CAT_EYE: 'cat-eye', CHROME: 'chrome', FRENCH: 'french', GLITTER_ACCENT: 'glitter',
}[e] ?? e.toLowerCase());
