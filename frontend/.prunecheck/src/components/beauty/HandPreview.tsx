/**
 * The hand.
 *
 * <h2>The one design decision everything else follows from</h2>
 *
 * The hand is drawn in FLAT MATTE PLANES — three tones, a hairline contour, no gradient anywhere — and the
 * nail is the only object in the picture with optical depth. That is deliberate and it is the whole idea:
 * a manicure preview exists so someone can judge a LACQUER, so the lacquer is the only thing allowed to
 * shine. Rendering the skin at the same fidelity as the polish is what makes most nail visualisers look
 * like waxwork; it also drags every shade toward whatever hue the skin was rendered in.
 *
 * It happens to be the honest answer too. This is an illustration, not a photograph, and a picture that
 * tried to pass for a photograph would be claiming a fidelity the app cannot back — the same reason the
 * diagram is called a preview and never an "inspiration image".
 *
 * <h2>Skin that does not fight the polish</h2>
 *
 * The three skin tones are one hue at three lightnesses, desaturated until they stopped competing. Skin
 * that leans pink turns every cool red muddy; skin that leans yellow kills every nude. Warm neutral is not
 * an aesthetic preference here, it is the only choice that lets all fourteen colour families read true.
 *
 * <h2>Per-nail control</h2>
 *
 * Every nail is its own group with a stable id — {@code thumb}, {@code index}, {@code middle},
 * {@code ring}, {@code pinky} — and its own clip path, so shape, length, colour, finish and effects are
 * independently addressable per finger. Pass {@code idPrefix} when two hands share a document, or the
 * duplicate ids will make the first hand's clip paths capture the second hand's nails.
 *
 * No external images, no fonts, no text nodes: labels belong in HTML next to the picture, at a real size.
 */
import { useId, forwardRef } from 'react';

export type NailShape = 'almond' | 'oval' | 'square' | 'squoval' | 'round' | 'coffin' | 'stiletto';
export type NailLength = 'short' | 'medium' | 'long' | 'extra-long';
export type NailFinish = 'glossy' | 'satin' | 'matte' | 'shimmer';
export type NailEffect = 'cat-eye' | 'chrome' | 'french' | 'glitter';
export type Finger = 'thumb' | 'index' | 'middle' | 'ring' | 'pinky';

export interface NailDesign {
  shape?: NailShape;
  length?: NailLength;
  /** Any CSS colour. Defaults to the app's burgundy. */
  color?: string;
  finish?: NailFinish;
  effects?: NailEffect[];
  /** Used by the accent band and by glitter flecks. */
  accentColor?: string;
  /** Draws the accent band along the free edge — the "second colour" a two-tone request needs. */
  accent?: boolean;
}

export interface HandPreviewProps {
  /** Applies to all five nails. */
  design?: NailDesign;
  /** Per-finger overrides, merged over `design`. */
  perNail?: Partial<Record<Finger, NailDesign>>;
  /** Left hand. The same component, flipped — a hand is its own mirror. */
  mirrored?: boolean;
  /** Required when more than one hand shares a document. */
  idPrefix?: string;
  className?: string;
  /** Screen-reader description. The SVG carries no text of its own. */
  ariaLabel?: string;
}

// ---------------------------------------------------------------------------------------------------
// PALETTE
//
// Warm neutral, low chroma, three steps. `LINE` is used at a hairline weight and low alpha — a contour
// heavy enough to see is a contour heavy enough to make the hand look like a colouring book.
// ---------------------------------------------------------------------------------------------------

const SKIN = '#EFD9CB';
const SKIN_LIGHT = '#FAEDE4';
const SKIN_SHADE = '#DCB6A2';
const CONTOUR = '#C08F79';
const CAST = '#2A1620';

/** The app's burgundy, and the gold it pairs with. */
const DEFAULT_COLOR = '#5C0A22';
const DEFAULT_ACCENT = '#B08D3F';

const VIEW_W = 300;
const VIEW_H = 430;

// ---------------------------------------------------------------------------------------------------
// ANATOMY
//
// ONE CONTINUOUS SILHOUETTE. This is the decision that makes it read as a hand.
//
// The obvious construction — five capsules laid over a palm blob — fails badly, and the reason is worth
// recording: each capsule leaves a hard seam where it crosses the palm, so the picture becomes a paddle
// with five sticks resting on it. A hand has no seams. So the outline is walked ONCE, up one side of
// every finger and down the other, dipping into a web between each pair, and the fingers are separated by
// the depth of those webs rather than by being separate objects.
//
// Proportions are from a real dorsal hand, not from what reads as hand-shaped. Middle longest, ring just
// behind it, index a little shorter than ring, pinky markedly shorter AND started lower down the palm.
// The web between two fingers sits far higher than the knuckle, which is why fingers look joined for the
// bottom third of their length; putting the webs at the knuckle line is what makes drawn hands look like
// forks.
// ---------------------------------------------------------------------------------------------------

interface Bone {
  finger: Finger;
  cx: number;
  /** Pivot for the splay, and the depth the sidewalls are measured from. */
  pivotY: number;
  tipY: number;
  wBase: number;
  wTip: number;
  tilt: number;
  /** Where the outline turns at each side. Higher y = deeper web = more separation. */
  leftBaseY: number;
  rightBaseY: number;
}

const THUMB: Bone = {
  finger: 'thumb', cx: 104, pivotY: 348, tipY: 250, wBase: 30, wTip: 23.5, tilt: -30,
  leftBaseY: 348, rightBaseY: 344,
};

/**
 * Index to pinky, radial to ulnar.
 *
 * Fingers shortened and the splay roughly halved from the first pass: at the original lengths and angles
 * they read as prongs, because a relaxed hand has fingers a little shorter than the palm and they barely
 * fan at all. The gaps still show - they come from how deep the webs sit, not from the angle.
 */
const FINGERS: Bone[] = [
  { finger: 'index', cx: 119, pivotY: 300, tipY: 128, wBase: 25, wTip: 19.5, tilt: -5, leftBaseY: 330, rightBaseY: 262 },
  { finger: 'middle', cx: 152, pivotY: 300, tipY: 108, wBase: 26, wTip: 20.5, tilt: 0, leftBaseY: 262, rightBaseY: 260 },
  { finger: 'ring', cx: 185, pivotY: 300, tipY: 124, wBase: 25, wTip: 19.5, tilt: 4, leftBaseY: 260, rightBaseY: 272 },
  { finger: 'pinky', cx: 214, pivotY: 300, tipY: 160, wBase: 21, wTip: 16.5, tilt: 9, leftBaseY: 272, rightBaseY: 308 },
];

const BONES: Bone[] = [THUMB, ...FINGERS];

/** Where the light comes from. One direction for the whole hand, or five fingers read as five objects. */
const LIT_SIDE = -1;

const round = (n: number) => Math.round(n * 10) / 10;

/** Rotate a point about a pivot. The silhouette is one path, so splay is baked in, not applied as a transform. */
function rot(x: number, y: number, ox: number, oy: number, deg: number): [number, number] {
  const r = (deg * Math.PI) / 180;
  const c = Math.cos(r);
  const s = Math.sin(r);
  const dx = x - ox;
  const dy = y - oy;
  return [ox + dx * c - dy * s, oy + dx * s + dy * c];
}

const pt = ([x, y]: [number, number]) => `${round(x)} ${round(y)}`;

/** The two sidewalls and the tip arc of one finger, already splayed. */
function fingerSides(b: Bone) {
  const hb = b.wBase / 2;
  const ht = b.wTip / 2;
  const ty = b.tipY + ht;
  const R = (x: number, y: number) => rot(x, y, b.cx, b.pivotY, b.tilt);
  const runL = b.leftBaseY - ty;
  const runR = b.rightBaseY - ty;
  return {
    leftBase: R(b.cx - hb, b.leftBaseY),
    leftC1: R(b.cx - hb, b.leftBaseY - runL * 0.44),
    leftC2: R(b.cx - ht, ty + runL * 0.3),
    leftTip: R(b.cx - ht, ty),
    rightTip: R(b.cx + ht, ty),
    rightC2: R(b.cx + ht, ty + runR * 0.3),
    rightC1: R(b.cx + hb, b.rightBaseY - runR * 0.44),
    rightBase: R(b.cx + hb, b.rightBaseY),
    tipR: ht,
  };
}

/** One finger, from its left web to its right web. Used inside the silhouette and for the per-finger clip. */
function fingerRun(b: Bone): string {
  const s = fingerSides(b);
  return [
    `C ${pt(s.leftC1)} ${pt(s.leftC2)} ${pt(s.leftTip)}`,
    `A ${round(s.tipR)} ${round(s.tipR)} 0 0 1 ${pt(s.rightTip)}`,
    `C ${pt(s.rightC2)} ${pt(s.rightC1)} ${pt(s.rightBase)}`,
  ].join(' ');
}

/** A closed outline of one finger alone, for clipping its own shading. */
function fingerPath(b: Bone): string {
  const s = fingerSides(b);
  return `M ${pt(s.leftBase)} ${fingerRun(b)} Z`;
}

/**
 * The whole hand, as one path.
 *
 * Walks radial to ulnar: up the wrist, around the thumb, through the thenar web, up and down each finger,
 * then down the ulnar edge and back across the wrist.
 */
function handPath(): string {
  const t = fingerSides(THUMB);
  const d: string[] = [`M ${pt(t.leftBase)}`, fingerRun(THUMB)];

  // The thenar web: a soft crotch curving INTO the hand. Cut straight across it leaves a notch that
  // reads as a wound, so it has to arrive at the index tangentially.
  const idx = fingerSides(FINGERS[0]);
  d.push(`C 124 336 124 330 ${pt(idx.leftBase)}`);

  FINGERS.forEach((b, i) => {
    d.push(fingerRun(b));
    const next = FINGERS[i + 1];
    if (next) {
      // The valley floor between two fingers. Short, because the web is high on the hand.
      const from = fingerSides(b).rightBase;
      const to = fingerSides(next).leftBase;
      const midX = (from[0] + to[0]) / 2;
      const floor = Math.max(from[1], to[1]) + 7;
      d.push(`Q ${round(midX)} ${round(floor)} ${pt(to)}`);
    }
  });

  // Ulnar edge down to the wrist, across it, and back up the radial edge to the thumb.
  d.push('C 230 336 228 376 220 398');
  d.push('C 212 416 186 424 158 424');
  d.push('C 134 424 118 416 112 400');
  d.push(`C 106 384 102 366 ${pt(t.leftBase)}`);
  return `${d.join(' ')} Z`;
}

// ---------------------------------------------------------------------------------------------------
// THE NAIL
// ---------------------------------------------------------------------------------------------------

/** How far the free edge reaches past the fingertip, as a multiple of nail width. */
const OVERHANG: Record<NailLength, number> = {
  short: 0.10,
  medium: 0.52,
  long: 1.05,
  'extra-long': 1.6,
};

interface Plate {
  /** Left edge, free edge, width, total length. y grows downward: `y` is the tip, `y + h` the cuticle. */
  x: number;
  y: number;
  w: number;
  h: number;
}

function plateOf(b: Bone, length: NailLength): Plate {
  const w = b.wTip * 0.82;
  const bed = w * 1.06;
  const over = w * OVERHANG[length];
  const cuticleY = b.tipY + bed;
  return { x: b.cx - w / 2, y: cuticleY - (bed + over), w, h: bed + over };
}

const CUTICLE_R = 0.2;
const CUTICLE_ARC = 0.28;

/**
 * The nail outline.
 *
 * Every shape shares one cuticle — a shallow arc bulging into the finger, which is what makes the form
 * read as a plate sitting in a nail bed rather than a tile stuck on top. What differs, and what someone
 * is actually checking when she looks at this, is the free edge.
 */
function platePath(p: Plate, shape: NailShape): string {
  const { x, y, w, h } = p;
  const cx = x + w / 2;
  const cy = y + h;
  const cr = w * CUTICLE_R;
  const d: string[] = [
    `M ${round(x)} ${round(cy - cr)}`,
    `Q ${round(cx)} ${round(cy + w * CUTICLE_ARC)} ${round(x + w)} ${round(cy - cr)}`,
  ];

  const flat = (tipFrac: number, corner: number) => {
    const t = (w * tipFrac) / 2;
    const shoulder = y + h * 0.56;
    d.push(
      `L ${round(x + w)} ${round(shoulder)}`,
      `L ${round(cx + t)} ${round(y + corner)}`,
      `Q ${round(cx + t)} ${round(y)} ${round(cx + t - corner)} ${round(y)}`,
      `L ${round(cx - t + corner)} ${round(y)}`,
      `Q ${round(cx - t)} ${round(y)} ${round(cx - t)} ${round(y + corner)}`,
      `L ${round(x)} ${round(shoulder)}`,
    );
  };

  const tapered = (tipHalf: number, drop: number, belly: number, pointed: boolean) => {
    const t = w * tipHalf;
    const edgeY = y + h * drop;
    const shoulderX = t + (w / 2 - t) * belly;
    d.push(
      `C ${round(x + w)} ${round(y + h * 0.5)} ${round(cx + shoulderX)} ${round(y + h * 0.16)} ${round(cx + t)} ${round(edgeY)}`,
    );
    d.push(pointed
      ? `Q ${round(cx)} ${round(y - h * 0.012)} ${round(cx - t)} ${round(edgeY)}`
      : `C ${round(cx + t * 0.62)} ${round(y)} ${round(cx - t * 0.62)} ${round(y)} ${round(cx - t)} ${round(edgeY)}`);
    d.push(
      `C ${round(cx - shoulderX)} ${round(y + h * 0.16)} ${round(x)} ${round(y + h * 0.5)} ${round(x)} ${round(cy - cr)}`,
    );
  };

  switch (shape) {
    case 'square': flat(0.97, w * 0.06); break;
    case 'squoval': flat(0.94, w * 0.26); break;
    case 'round': {
      const r = w / 2;
      const k = w * 0.276;
      d.push(
        `L ${round(x + w)} ${round(y + r)}`,
        `C ${round(x + w)} ${round(y + r - k)} ${round(cx + k)} ${round(y)} ${round(cx)} ${round(y)}`,
        `C ${round(cx - k)} ${round(y)} ${round(x)} ${round(y + r - k)} ${round(x)} ${round(y + r)}`,
        `L ${round(x)} ${round(cy - cr)}`,
      );
      break;
    }
    case 'coffin': {
      const tip = w * 0.24;
      d.push(
        `L ${round(x + w)} ${round(y + h * 0.62)}`,
        `L ${round(cx + tip)} ${round(y)}`,
        `L ${round(cx - tip)} ${round(y)}`,
        `L ${round(x)} ${round(y + h * 0.62)}`,
      );
      break;
    }
    case 'stiletto':
      d.push(
        `C ${round(x + w)} ${round(y + h * 0.55)} ${round(cx + w * 0.17)} ${round(y + h * 0.1)} ${round(cx)} ${round(y)}`,
        `C ${round(cx - w * 0.17)} ${round(y + h * 0.1)} ${round(x)} ${round(y + h * 0.55)} ${round(x)} ${round(cy - cr)}`,
      );
      break;
    case 'oval': tapered(0.29, 0.055, 0.56, false); break;
    case 'almond':
    default: tapered(0.13, 0.035, 0.66, true); break;
  }
  return `${d.join(' ')} Z`;
}

/** Deterministic sparkle positions. A table, not a generator, so the same design always draws the same. */
const SPARKS: Array<[number, number]> = [
  [0.24, 0.18], [0.62, 0.12], [0.42, 0.31], [0.78, 0.26], [0.17, 0.44],
  [0.55, 0.47], [0.84, 0.55], [0.31, 0.6], [0.69, 0.68], [0.46, 0.76],
];

function isDark(hex: string): boolean {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return false;
  const n = parseInt(m[1], 16);
  const r = (n >> 16) & 255;
  const g = (n >> 8) & 255;
  const b = n & 255;
  return 0.299 * r + 0.587 * g + 0.114 * b < 96;
}

// ---------------------------------------------------------------------------------------------------

const HAND = handPath();

export const HandPreview = forwardRef<SVGSVGElement, HandPreviewProps>(function HandPreview({
  design,
  perNail,
  mirrored = false,
  idPrefix = '',
  className,
  ariaLabel = 'Prikaz manikure na ruci',
}, ref) {
  const uid = useId().replace(/:/g, '');
  const ns = (name: string) => `${idPrefix}${name}`;
  const gid = (name: string) => `h${uid}-${name}`;

  return (
    <svg
      ref={ref}
      className={className}
      viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
      xmlns="http://www.w3.org/2000/svg"
      role="img"
      aria-label={ariaLabel}
      preserveAspectRatio="xMidYMid meet"
    >
      <defs>
        {BONES.map((b) => {
          const merged = { ...design, ...(perNail?.[b.finger] ?? {}) };
          const plate = plateOf(b, merged.length ?? 'short');
          return (
            <g key={b.finger}>
              <clipPath id={gid(`clip-${b.finger}`)}>
                <path d={platePath(plate, merged.shape ?? 'almond')} />
              </clipPath>
              <clipPath id={gid(`skin-${b.finger}`)}>
                <path d={fingerPath(b)} />
              </clipPath>
            </g>
          );
        })}

        {/*
          The only gradients in the file, and both live inside a nail. `catEye` is a reflection: pigment
          thinned at both ends where the magnet pulled it away, one soft band of light across the middle
          with no edge you can point at. Drawing a hard line there makes it a painted stripe.
        */}
        {/* One small blur, shared by every soft mass on the skin. stdDeviation is deliberately low:
            enough to kill the edge, not enough to turn the hand into fog. */}
        <filter id={gid('soften')} x="-25%" y="-25%" width="150%" height="150%">
          <feGaussianBlur stdDeviation="11" />
        </filter>

        <clipPath id={gid('hand')}>
          <path d={HAND} />
        </clipPath>

        <linearGradient id={gid('cateye')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={CAST} stopOpacity="0.34" />
          <stop offset="0.22" stopColor={CAST} stopOpacity="0.14" />
          <stop offset="0.42" stopColor="#FFFFFF" stopOpacity="0.1" />
          <stop offset="0.5" stopColor="#FFFFFF" stopOpacity="0.4" />
          <stop offset="0.58" stopColor="#FFFFFF" stopOpacity="0.1" />
          <stop offset="0.78" stopColor={CAST} stopOpacity="0.14" />
          <stop offset="1" stopColor={CAST} stopOpacity="0.32" />
        </linearGradient>

        <linearGradient id={gid('gloss')} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#FFFFFF" stopOpacity="0.1" />
          <stop offset="0.14" stopColor="#FFFFFF" stopOpacity="0.42" />
          <stop offset="0.3" stopColor="#FFFFFF" stopOpacity="0.06" />
          <stop offset="0.72" stopColor={CAST} stopOpacity="0.04" />
          <stop offset="1" stopColor={CAST} stopOpacity="0.16" />
        </linearGradient>

        <linearGradient id={gid('satin')} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#FFFFFF" stopOpacity="0.08" />
          <stop offset="0.34" stopColor="#FFFFFF" stopOpacity="0.18" />
          <stop offset="0.68" stopColor="#FFFFFF" stopOpacity="0.02" />
          <stop offset="1" stopColor={CAST} stopOpacity="0.12" />
        </linearGradient>
      </defs>

      <g transform={mirrored ? `translate(${VIEW_W} 0) scale(-1 1)` : undefined}>
        {/* Contact shadow. One soft ellipse, so the hand rests on the page instead of floating over it. */}
        <ellipse cx={164} cy={420} rx={88} ry={18} fill={CAST} opacity={0.05} />

        {/*
          The hand: one silhouette, then its tonal planes clipped inside it. Every plane is a flat shape at
          low opacity — no gradient touches the skin, which is what keeps the polish the only thing in the
          picture with depth.
        */}
        <path d={HAND} fill={SKIN} />
        <g clipPath={`url(#${gid('hand')})`} filter={`url(#${gid('soften')})`}>
          {/*
            Form, as BLURRED MASSES rather than translated copies of the silhouette.

            The copies were the first attempt and they were plainly wrong: an offset silhouette keeps the
            same hard outline as the original, so the shading arrived as two crisp vertical bands down the
            hand. Soft shadow needs a soft edge, so these are loose shapes pushed through one small blur.
            Still no gradient anywhere on the skin.
          */}
          <ellipse cx={120} cy={318} rx={46} ry={96} fill={SKIN_LIGHT} opacity={0.85} />
          <ellipse cx={216} cy={330} rx={34} ry={86} fill={SKIN_SHADE} opacity={0.5} />
          {/* The knuckle swell: where a dorsal hand actually catches light. Without it the back of the
              hand reads as a flat paddle however good the outline is. */}
          <ellipse cx={162} cy={286} rx={64} ry={26} fill={SKIN_LIGHT} opacity={0.6} />
          {/* The hollow below it, which is what makes the swell read as a swell. */}
          <ellipse cx={166} cy={372} rx={54} ry={30} fill={SKIN_SHADE} opacity={0.28} />
        </g>
        <path d={HAND} fill="none" stroke={CONTOUR} strokeOpacity={0.22} strokeWidth={1.1} />

        {BONES.map((b) => {
          const merged: NailDesign = { ...design, ...(perNail?.[b.finger] ?? {}) };
          return (
            <FingerGroup
              key={b.finger}
              bone={b}
              design={merged}
              clipId={gid(`clip-${b.finger}`)}
              skinClipId={gid(`skin-${b.finger}`)}
              softenId={gid('soften')}
              gradients={{ cateye: gid('cateye'), gloss: gid('gloss'), satin: gid('satin') }}
              nailId={ns(b.finger)}
            />
          );
        })}
      </g>
    </svg>
  );
});

function FingerGroup({ bone, design, clipId, skinClipId, softenId, gradients, nailId }: {
  bone: Bone;
  design: NailDesign;
  clipId: string;
  skinClipId: string;
  softenId: string;
  gradients: { cateye: string; gloss: string; satin: string };
  nailId: string;
}) {
  const shape = design.shape ?? 'almond';
  const length = design.length ?? 'short';
  const color = design.color ?? DEFAULT_COLOR;
  const finish = design.finish ?? 'glossy';
  const effects = design.effects ?? [];
  const accentColor = design.accentColor ?? DEFAULT_ACCENT;

  const plate = plateOf(bone, length);
  const d = platePath(plate, shape);
  const skin = fingerPath(bone);
  const { x, y, w, h } = plate;
  const cx = x + w / 2;
  // Black is a real lacquer, so the rim has to survive it: light on dark polish, ink on light.
  const rim = isDark(color) ? '#FFFFFF' : CAST;

  return (
    <g>
      {/*
        The finger's own rounding, clipped to itself. The silhouette already filled the skin; this only
        adds the cylinder — a lit edge and a turned edge, same light direction as the hand. Drawn per
        finger rather than once for the whole hand because each finger is a separate cylinder and a single
        global shade would flatten the ones it crossed.
      */}
      <g clipPath={`url(#${skinClipId})`} filter={`url(#${softenId})`}>
        <path d={skin} fill={SKIN_LIGHT} opacity={0.75}
          transform={`translate(${round(LIT_SIDE * bone.wTip * 0.36)} 0)`} />
        <path d={skin} fill={SKIN_SHADE} opacity={0.45}
          transform={`translate(${round(-LIT_SIDE * bone.wTip * 0.52)} 0)`} />
      </g>

      {/* The cuticle shadow. One shallow arc, and the single cue that makes a nail look attached rather
          than printed on: without it the plate floats however well it is drawn. */}
      <g transform={`rotate(${bone.tilt} ${round(bone.cx)} ${round(bone.pivotY)})`}>
      <path
        d={`M ${round(cx - w * 0.54)} ${round(y + h - 1)} Q ${round(cx)} ${round(y + h + w * CUTICLE_ARC + 5)} ${round(cx + w * 0.54)} ${round(y + h - 1)} Q ${round(cx)} ${round(y + h + w * CUTICLE_ARC + 1)} ${round(cx - w * 0.54)} ${round(y + h - 1)} Z`}
        fill={SKIN_SHADE}
        opacity={0.6}
      />

      {/* A free edge that overhangs casts a shadow on the fingertip. Only when there is an overhang. */}
      {length !== 'short' && (
        <ellipse cx={round(cx)} cy={round(y + h * 0.22)} rx={round(w * 0.44)} ry={4.5}
          fill={CAST} opacity={0.1} />
      )}
      </g>

      {/*
        The nail. Its own id and its own clip, so shape, length, colour, finish and effects can all be
        driven per finger from outside.
      */}
      <g id={nailId} data-nail={nailId} className="nk-nail"
        transform={`rotate(${bone.tilt} ${round(bone.cx)} ${round(bone.pivotY)})`}>
        <path d={d} fill={color} />
        <g clipPath={`url(#${clipId})`}>
          {effects.includes('cat-eye') && (
            <rect x={round(x)} y={round(y)} width={round(w)} height={round(h)}
              fill={`url(#${gradients.cateye})`} />
          )}
          {effects.includes('chrome') && (
            <>
              <rect x={round(x)} y={round(y)} width={round(w)} height={round(h * 0.44)}
                fill="#FFFFFF" opacity={0.32} />
              <rect x={round(x)} y={round(y + h * 0.44)} width={round(w)} height={round(h * 0.05)}
                fill="#FFFFFF" opacity={0.66} />
              <rect x={round(x)} y={round(y + h * 0.49)} width={round(w)} height={round(h * 0.51)}
                fill={CAST} opacity={0.16} />
            </>
          )}
          {effects.includes('french') && (
            <ellipse cx={round(cx)} cy={round(y - h * 0.26)} rx={round(w * 0.84)} ry={round(h * 0.42)}
              fill="#FFFFFF" opacity={0.94} />
          )}
          {design.accent && (
            <ellipse cx={round(cx)} cy={round(y - h * 0.3)} rx={round(w * 0.82)} ry={round(h * 0.44)}
              fill={accentColor} />
          )}
          {finish === 'glossy' && (
            <rect x={round(x)} y={round(y - h * 0.05)} width={round(w)} height={round(h * 1.12)}
              fill={`url(#${gradients.gloss})`} />
          )}
          {(finish === 'satin' || finish === 'shimmer') && (
            <rect x={round(x)} y={round(y - h * 0.05)} width={round(w)} height={round(h * 1.12)}
              fill={`url(#${gradients.satin})`} />
          )}
          {finish === 'shimmer' && SPARKS.map(([sx, sy], i) => (
            <circle key={i} cx={round(x + w * sx)} cy={round(y + h * sy)}
              r={round(w * (i % 3 === 0 ? 0.05 : 0.032))} fill="#FFFFFF" opacity={0.62} />
          ))}
          {effects.includes('glitter') && SPARKS.map(([sx, sy], i) => (
            <circle key={`g${i}`} cx={round(x + w * sy)} cy={round(y + h * sx)}
              r={round(w * (i % 4 === 0 ? 0.085 : 0.05))}
              fill={i % 3 === 0 ? accentColor : '#FFFFFF'} opacity={0.85} />
          ))}
        </g>
        <path d={d} fill="none" stroke={rim} strokeOpacity={isDark(color) ? 0.26 : 0.2} strokeWidth={1} />
      </g>
    </g>
  );
}

export default HandPreview;
