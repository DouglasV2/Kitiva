import { useRef, useState } from 'react';
import '../nailkit.css';
import {
  parseNailPrompt, generateNailLook, sendNailFeedback,
  type NailParseResponse, type NailGenerateResponse, type KitItem,
  type SalonBrief, type FeedbackMatch, type FeedbackIntent
} from '../api/nail';
import type { Assumption, NailLookBrief, NailDesignSpec, OwnedItem } from '../types/beauty';

/**
 * Nail Look / Nail Kit.
 *
 * The flow is deliberately: prompt -> EDITABLE brief -> choose salon or at home -> result. The brief sits
 * in the middle because a parser reading Croatian free text will sometimes be wrong, and the cheapest place
 * to fix that is before anything is built. Every field the parser fills is a field the user can correct.
 *
 * Salon and at-home are asked, never inferred. They produce fundamentally different things, and one of them
 * recommends chemical products to a consumer.
 *
 * Layout follows the approved mockup: a numbered step rail, the prompt at a readable measure, then the work
 * (specification beside a live preview of both hands) with the execution decision in its own column. Effects,
 * accent placement and owned equipment stay multi-select chips rather than the mockup's single dropdowns,
 * because each of those fields genuinely holds a list and a dropdown would silently drop values.
 */

const SHAPES = [
  ['ALMOND', 'almond (bademasti)'], ['OVAL', 'ovalni'], ['SQUARE', 'četvrtasti'],
  ['SQUOVAL', 'četvrtasto-ovalni'], ['ROUND', 'okrugli'], ['COFFIN', 'coffin'], ['STILETTO', 'stiletto'],
] as const;

const LENGTHS = [
  ['SHORT', 'kratki'], ['MEDIUM', 'srednji'], ['LONG', 'dugi'], ['EXTRA_LONG', 'vrlo dugi'],
] as const;

const FINISHES = [
  ['GLOSSY', 'sjajni'], ['MATTE', 'mat'], ['SATIN', 'saten'], ['SHIMMER', 'šimeran'],
] as const;

const EFFECTS = [
  ['CAT_EYE', 'cat-eye'], ['CHROME', 'chrome'], ['FRENCH', 'french'], ['GLITTER_ACCENT', 'glitter'],
] as const;

const FINGERS = [
  ['THUMB', 'palac'], ['INDEX', 'kažiprst'], ['MIDDLE', 'srednjak'], ['RING', 'prstenjak'], ['PINKY', 'mali prst'],
] as const;

/** Mirrors the backend colour table so the picker, the legend and the diagram agree on every hex. */
const COLORS: Array<[string, string, string]> = [
  ['', '', 'nije navedena'],
  ['burgundy', '#5C0A22', 'burgundy / višnja'],
  ['red', '#C1121F', 'crvena'],
  ['nude', '#E4C9B6', 'nude'],
  ['pink', '#E8A0BF', 'roza'],
  ['black', '#1A1A1A', 'crna'],
  ['white', '#F5F5F0', 'bijela'],
  ['blue', '#2C4A7C', 'plava'],
  ['green', '#3E6B4F', 'zelena'],
  ['purple', '#6B3E7C', 'ljubičasta'],
  ['brown', '#6B4A32', 'smeđa'],
  ['grey', '#8A8A8A', 'siva'],
  ['gold', '#C9A227', 'zlatna'],
];

const OWNABLE = [
  ['file', 'turpija'], ['base', 'bazni lak'], ['top', 'nadlak'],
  ['color', 'lak u boji'], ['removal', 'sredstvo za skidanje'], ['cuticle-care', 'ulje za zanoktice'],
] as const;

const PROMPT_LIMIT = 500;

const eur = (cents: number) => `${(cents / 100).toFixed(2).replace('.', ',')} €`;

/** Croatian label for an enum value, from the same tables the pickers are built from. */
const labelOf = (pairs: ReadonlyArray<readonly [string, string]>, value: string) =>
  pairs.find(([key]) => key === value)?.[1] ?? value;

const colorName = (key: string) => COLORS.find(([k]) => k === key)?.[2] ?? key;

/** An unset colour is hatched rather than blank, so an empty circle never reads as a broken swatch. */
const NO_SHADE = 'repeating-linear-gradient(45deg, #fff 0 4px, #efe2e6 4px 8px)';
const swatch = (hex: string | null | undefined) => hex || NO_SHADE;

const STEPS = ['Opiši look', 'Provjeri specifikaciju', 'Odaberi salon ili kod kuće'];

/**
 * What an assumption should print. `assumed` is the machine value — rendering it is how "glossy", "mirrored"
 * and a bare "prstenjak" ended up as headings in a Croatian consumer product.
 */
const assumptionLabel = (a: Assumption) => a.labelHr || a.field;

/**
 * Line icons, drawn here rather than pulled in as a dependency. One 16-unit grid, one stroke weight, paths
 * separated by "|" — enough for the handful the layout needs and nothing to install.
 */
const ICONS: Record<string, string> = {
  sparkle: 'M8 1.7l1.5 4.1 4.1 1.5-4.1 1.5L8 13l-1.5-4.2L2.4 7.3l4.1-1.5z|M13 11.2l.5 1.4 1.4.5-1.4.5-.5 1.4-.5-1.4-1.4-.5 1.4-.5z',
  bulb: 'M8 1.9a4.1 4.1 0 00-2.5 7.4v1.5h5V9.3A4.1 4.1 0 008 1.9z|M6.3 13h3.4|M6.9 14.6h2.2',
  pen: 'M11.3 1.9l2.8 2.8-8.4 8.4-3.6.8.8-3.6z|M9.8 3.4l2.8 2.8',
  info: 'M8 1.7a6.3 6.3 0 100 12.6A6.3 6.3 0 008 1.7z|M8 7.2v4|M8 4.8h.01',
  check: 'M2.7 8.4l3.5 3.5L13.4 4.7',
  seal: 'M8 1.5l1.9 1.2 2.2.3.7 2.1 1.4 1.8-1.4 1.8-.7 2.1-2.2.3L8 14.5l-1.9-1.4-2.2-.3-.7-2.1L1.8 8.7l1.4-1.8.7-2.1 2.2-.3z|M5.9 8.2l1.6 1.6 2.7-3',
  lock: 'M4.4 7.2V5.5a3.6 3.6 0 017.2 0v1.7|M3.3 7.2h9.4v6.4H3.3z',
  bottle: 'M6.7 1.3h2.6v2.4l1.5 2.1v6.9H5.2V5.8l1.5-2.1z|M5.2 8.6h5.6',
  bag: 'M2.9 5.6h10.2l.7 8H2.2z|M5.9 5.6V4.4a2.1 2.1 0 014.2 0v1.2',
  box: 'M2.5 5.2L8 2.4l5.5 2.8v5.6L8 13.6l-5.5-2.8z|M2.5 5.2L8 8l5.5-2.8|M8 8v5.6',
  tag: 'M8.7 1.9H14v5.3L7.6 13.6 2.3 8.3z|M11.3 4.5h.01',
  shield: 'M8 1.6l5.1 1.8v4.1c0 3.4-2.3 5.6-5.1 6.9-2.8-1.3-5.1-3.5-5.1-6.9V3.4z',
  eye: 'M1.6 8S4 4.1 8 4.1 14.4 8 14.4 8 12 11.9 8 11.9 1.6 8 1.6 8z|M8 6.1a1.9 1.9 0 100 3.8 1.9 1.9 0 000-3.8z',
  nail: 'M8 1.8c2.6 0 4.1 1.9 4.1 4.6 0 4-1.3 7.8-4.1 7.8S3.9 10.4 3.9 6.4C3.9 3.7 5.4 1.8 8 1.8z',
  arrow: 'M2.6 8h10.6|M9.4 4.2L13.2 8l-3.8 3.8',
  copy: 'M5.6 5.6h7.2v8.8H5.6z|M10.4 3.2H3.2v8.4',
  noimage: 'M2.4 3.4h11.2v9.2H2.4z|M2.4 10.2l3.2-3 2.6 2.4 2.4-2.2 3 2.8|M1.4 1.6l13.2 12.8',
  download: 'M8 2.4v7.8|M4.8 7.4L8 10.6l3.2-3.2|M2.8 13.2h10.4',
};

function Ico({ name, size = 16 }: { name: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor"
      strokeWidth={1.35} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {ICONS[name].split('|').map((d, i) => <path key={i} d={d} />)}
    </svg>
  );
}

export function NailLook() {
  const [prompt, setPrompt] = useState(
    'Želim kratke almond burgundy cat-eye nokte s dva diskretna zlatna detalja.'
  );
  const [budget, setBudget] = useState('');
  const [system, setSystem] = useState<'regular-polish' | 'press-on'>('regular-polish');
  const [mode, setMode] = useState<'SALON' | 'AT_HOME' | null>(null);
  const [brief, setBrief] = useState<NailLookBrief | null>(null);
  const [diagram, setDiagram] = useState('');
  const [parseInfo, setParseInfo] = useState<NailParseResponse | null>(null);
  const [result, setResult] = useState<NailGenerateResponse | null>(null);
  const [pinned, setPinned] = useState<Record<string, string>>({});
  const [cheapest, setCheapest] = useState(false);
  const [oneStore, setOneStore] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const specRef = useRef<HTMLDivElement>(null);

  const budgetCents = budget.trim() ? Math.round(Number(budget) * 100) : 0;

  /** "Uredi specifikaciju": drop the result, keep the chosen branch, and put the form back on screen. */
  function editSpecification() {
    setResult(null);
    specRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  async function handleParse() {
    setBusy(true); setError(''); setResult(null); setPinned({}); setCheapest(false); setOneStore(null);
    try {
      const res = await parseNailPrompt(prompt, budgetCents);
      setParseInfo(res);
      setBrief(res.brief);
      setDiagram(res.designDiagramSvg);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Nešto je pošlo po zlu.');
    } finally { setBusy(false); }
  }

  /** Any brief edit re-renders the diagram from the server, so the picture always matches the spec. */
  async function patchDesign(patch: Partial<NailDesignSpec>) {
    if (!brief) return;
    const next: NailLookBrief = { ...brief, design: { ...brief.design, ...patch } };
    setBrief(next);
    setResult(null);
    try {
      const res = await generateNailLook(next, 'UNSPECIFIED', {});
      setDiagram(res.designDiagramSvg);
    } catch { /* the diagram simply keeps its previous frame */ }
  }

  function toggleOwned(slot: string) {
    if (!brief) return;
    const equipment: OwnedItem[] = brief.homeProfile?.equipment ?? [];
    const has = equipment.some((e) => e.slot === slot && e.satisfiesRequirement);
    const next = has
      ? equipment.filter((e) => e.slot !== slot)
      : [...equipment.filter((e) => e.slot !== slot),
         { slot, productId: null, rawText: 'označeno u specifikaciji', satisfiesRequirement: true }];
    setBrief({
      ...brief,
      homeProfile: {
        experienceLevel: brief.homeProfile?.experienceLevel ?? 'FIRST_TIME',
        naturalNailLength: brief.homeProfile?.naturalNailLength ?? 'SHORT',
        equipment: next,
      },
    });
    setResult(null);
  }

  async function generate(
    mode: 'SALON' | 'AT_HOME',
    opts: { pinnedBySlot?: Record<string, string>; preferCheapest?: boolean; singleRetailer?: string | null } = {}
  ) {
    if (!brief) return;
    setBusy(true); setError('');
    try {
      const withBudget: NailLookBrief = { ...brief, budgetCents, budgetStrict: budgetCents > 0 };
      const res = await generateNailLook(withBudget, mode, {
        pinnedBySlot: opts.pinnedBySlot ?? pinned,
        preferCheapest: opts.preferCheapest ?? cheapest,
        singleRetailer: opts.singleRetailer !== undefined ? opts.singleRetailer : oneStore,
        system: mode === 'AT_HOME' ? system : undefined,
      });
      setResult(res);
      if (res.designDiagramSvg) setDiagram(res.designDiagramSvg);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Nešto je pošlo po zlu.');
    } finally { setBusy(false); }
  }

  function swapTo(slot: string, externalId: string) {
    const next = { ...pinned, [slot]: externalId };
    setPinned(next);
    generate('AT_HOME', { pinnedBySlot: next });
  }

  const d = brief?.design;
  const kit = result?.kit;
  const salon = result?.salonBrief;
  const ownedSlots = new Set((brief?.homeProfile?.equipment ?? []).filter((e) => e.satisfiesRequirement).map((e) => e.slot));
  // Derived, not stored: there is nothing to check before a parse, nothing to execute before a choice.
  const step = !brief ? 1 : mode ? 3 : 2;
  const namedEffects = (d?.effects ?? []).filter((e) => e !== 'NONE');

  const statusTone = (s?: string) =>
    s === 'COMPLETE' ? 'nk-status-ok'
    : s === 'SAFETY_BLOCKED' || s === 'INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE' ? 'nk-status-stop'
    : 'nk-status-warn';

  return (
    <section className="nk" id="nail-look">
      {/* The catalog is machine-captured from published retailer feeds and NOT human-approved, so the app
          says so rather than implying a level of verification nobody has done yet. */}
      <p className="nk-prototype" data-testid="prototype-notice">
        <Ico name="info" />
        <span>Interni prototip — cijene, dostupnost i nijanse nisu ručno provjerene. Provjeri kod trgovca prije kupnje.</span>
      </p>

      <div className="nk-shell">
        <ol className="nk-steps">
          {STEPS.map((label, i) => (
            <li key={label} className={step === i + 1 ? 'is-now' : step > i + 1 ? 'is-done' : undefined}>
              <span className="nk-step-dot" aria-hidden="true">{step > i + 1 ? <Ico name="check" size={13} /> : i + 1}</span>
              <span className="nk-step-name">{label}</span>
            </li>
          ))}
        </ol>

        <div className="nk-lede">
          <div className="nk-hero">
            <div>
              <h1 className="nk-title">Opiši svoj nail look.</h1>
              <p className="nk-deck">Pretvorit ćemo tvoj opis u točnu specifikaciju ili komplet proizvoda.</p>
            </div>
            <div className="nk-badge">
              <span style={{ color: 'var(--nk-gold)', flex: 'none', marginTop: 1 }}><Ico name="seal" /></span>
              <span>
                {/* "Stvarni proizvodi" claimed more than the pilot can back: the rows are real retailer
                    rows, but they are machine-captured and not hand-checked. This says exactly that. */}
                <strong>Pilot katalog</strong>
                <span>Proizvodi iz hrvatskih trgovina</span>
              </span>
            </div>
          </div>

          {/* Once a specification exists, the prompt is no longer the point of the page — it stays fully
              editable but stops shouting, so the specification and the execution choice lead. */}
          <div className={brief ? 'nk-ask is-quiet' : 'nk-ask'}>
            <label className="nk-ask-label" htmlFor="nail-prompt">
              <Ico name="sparkle" /> {brief ? 'Tvoj opis' : 'Napiši kako želiš da nokti izgledaju'}
            </label>
            <div className="nk-ask-field-wrap">
              <textarea
                id="nail-prompt" className="nk-ask-field" rows={brief ? 2 : 3} value={prompt}
                onChange={(e) => setPrompt(e.target.value)} maxLength={PROMPT_LIMIT}
                spellCheck={false} autoCorrect="off" autoCapitalize="sentences"
                placeholder="npr. kratki almond nokti boje višnje s cat-eye efektom"
              />
              <span className="nk-ask-count" aria-hidden="true">{prompt.length} / {PROMPT_LIMIT}</span>
            </div>
            <div className="nk-ask-row">
              <button type="button" className="nk-btn nk-btn-ask" onClick={handleParse}
                disabled={busy || !prompt.trim()} data-testid="parse">
                <Ico name="pen" /> {busy ? 'Pretvaram…' : brief ? 'Osvježi specifikaciju' : 'Pretvori u specifikaciju'}
              </button>
              {!brief && (
                <p className="nk-ask-example">
                  <Ico name="bulb" />
                  <span>Primjer: „Kratki ovalni nude nokti s francuskim vrhom”</span>
                </p>
              )}
            </div>
          </div>
        </div>

        {error && <div className="nk-error" role="alert">{error}</div>}

        {brief && d && (
          <div className="nk-main">
            <div className="nk-card" ref={specRef}>
              <div className="nk-card-head">
                <span className="nk-card-label"><Ico name="pen" size={14} /> Specifikacija</span>
                <span className="nk-card-note">možeš ispraviti <Ico name="check" size={13} /></span>
              </div>

              <div className="nk-cols">
                {/* ---- the editable worksheet ---- */}
                <div className="nk-form" data-testid="nail-brief">
                  <label className="nk-row">
                    <span className="nk-control-label">Oblik</span>
                    {/* No shape glyph beside this control: a static almond icon would contradict the select
                        the moment you pick square, and the preview two columns over already draws it. */}
                    <select className="nk-select" value={d.shape} data-testid="f-shape"
                      onChange={(e) => patchDesign({ shape: e.target.value as NailDesignSpec['shape'] })}>
                      {SHAPES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                    </select>
                  </label>

                  <label className="nk-row">
                    <span className="nk-control-label">Duljina</span>
                    <select className="nk-select" value={d.length} data-testid="f-length"
                      onChange={(e) => patchDesign({ length: e.target.value as NailDesignSpec['length'] })}>
                      {LENGTHS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                    </select>
                  </label>

                  <label className="nk-row">
                    <span className="nk-control-label">Glavna boja</span>
                    <span className="nk-control-line">
                      <span className="nk-swatch" style={{ background: swatch(d.baseColorHex) }} aria-hidden="true" />
                      <select className="nk-select" value={d.baseColorKey} data-testid="f-color"
                        onChange={(e) => {
                          const c = COLORS.find(([k]) => k === e.target.value);
                          patchDesign({ baseColorKey: e.target.value, baseColorHex: c?.[1] || null, baseColorRawText: c?.[2] ?? '' });
                        }}>
                        {COLORS.map(([k, , l]) => <option key={k || 'none'} value={k}>{l}</option>)}
                      </select>
                    </span>
                  </label>

                  <div className="nk-row is-stacked">
                    <span className="nk-control-label">Efekt</span>
                    <div className="nk-chips" data-testid="f-effects">
                      {EFFECTS.map(([v, l]) => {
                        const on = d.effects.includes(v as NailDesignSpec['effects'][number]);
                        return (
                          <button key={v} type="button" className="nk-chip" aria-pressed={on}
                            onClick={() => patchDesign({
                              effects: on ? d.effects.filter((x) => x !== v) : [...d.effects.filter((x) => x !== 'NONE'), v as never]
                            })}>{l}</button>
                        );
                      })}
                    </div>
                  </div>

                  <label className="nk-row">
                    <span className="nk-control-label">Boja detalja</span>
                    <span className="nk-control-line">
                      <span className="nk-swatch" style={{ background: swatch(d.accentColorHex) }} aria-hidden="true" />
                      <select className="nk-select" value={d.accentColorKey} data-testid="f-accent-color"
                        onChange={(e) => {
                          const c = COLORS.find(([k]) => k === e.target.value);
                          patchDesign({ accentColorKey: e.target.value, accentColorHex: c?.[1] || null });
                        }}>
                        {COLORS.map(([k, , l]) => <option key={k || 'none'} value={k}>{l}</option>)}
                      </select>
                    </span>
                  </label>

                  <label className="nk-row">
                    <span className="nk-control-label">Završni sloj</span>
                    <select className="nk-select" value={d.finish} data-testid="f-finish"
                      onChange={(e) => patchDesign({ finish: e.target.value as NailDesignSpec['finish'] })}>
                      {FINISHES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                    </select>
                  </label>

                  <label className="nk-row">
                    <span className="nk-control-label">Ruke</span>
                    <select className="nk-select" value={d.symmetry} data-testid="f-symmetry"
                      onChange={(e) => patchDesign({ symmetry: e.target.value as NailDesignSpec['symmetry'] })}>
                      <option value="MIRRORED">isto na obje ruke</option>
                      <option value="ASYMMETRIC_STATED">namjerno različito</option>
                    </select>
                  </label>

                  <div className="nk-row is-stacked">
                    <span className="nk-control-label">Gdje ide detalj</span>
                    <div className="nk-chips" data-testid="f-accents">
                      {FINGERS.map(([v, l]) => {
                        const on = d.accentFingers.includes(v as NailDesignSpec['accentFingers'][number]);
                        return (
                          <button key={v} type="button" className="nk-chip" aria-pressed={on}
                            onClick={() => patchDesign({
                              accentFingers: on ? d.accentFingers.filter((x) => x !== v) : [...d.accentFingers, v as never]
                            })}>{l}</button>
                        );
                      })}
                    </div>
                  </div>

                  <div className="nk-row is-stacked">
                    <span className="nk-control-label">Što već imaš</span>
                    <div className="nk-chips" data-testid="f-owned">
                      {OWNABLE.map(([slot, l]) => (
                        <button key={slot} type="button" className="nk-chip" aria-pressed={ownedSlots.has(slot)}
                          onClick={() => toggleOwned(slot)} data-testid={`owned-${slot}`}>{l}</button>
                      ))}
                    </div>
                  </div>
                </div>

                {/* ---- the signature: the live preview, and the labels that used to be 5px type inside it ---- */}
                <div className="nk-preview">
                  <div className="nk-preview-head">
                    <span className="nk-card-label">Pregled izgleda</span>
                    <span className="nk-glyph" title="Shematski prikaz iz specifikacije, nije fotografija.">
                      <Ico name="info" size={14} />
                    </span>
                  </div>

                  <div className="nk-stage" data-testid="nail-diagram" dangerouslySetInnerHTML={{ __html: diagram }} />

                  <div className="nk-legend">
                    <span>
                      <i style={{ background: swatch(d.baseColorHex) }} aria-hidden="true" />
                      {[namedEffects.map((e) => labelOf(EFFECTS, e)).join(' + '), d.baseColorRawText || colorName(d.baseColorKey)]
                        .filter(Boolean).join(' ')}
                    </span>
                    {d.accentFingers.length > 0 && (
                      <span>
                        <i style={{ background: swatch(d.accentColorHex) }} aria-hidden="true" />
                        {/* "detalj: zlatna" rather than "zlatni detalji" — the colour table holds bare
                            adjectives, and declining them per gender would invent grammar we do not have. */}
                        {d.accentColorKey ? `detalj: ${colorName(d.accentColorKey)}` : 'detalj'}
                        {` (${d.symmetry === 'MIRRORED' ? '2x ' : ''}${d.accentFingers.map((f) => labelOf(FINGERS, f)).join(', ')})`}
                      </span>
                    )}
                  </div>

                  {/* Assumptions are listed OPEN, not hidden behind a count. "Pretpostavili smo 3" tells the
                      user a number and hides the only part that matters — what we guessed and why. */}
                  {brief.assumptions.length > 0 && (
                    <div className="nk-assume-list" data-testid="brief-assumptions">
                      <span className="nk-section-label">Pretpostavke — provjeri</span>
                      <ul className="nk-assume-items">
                        {brief.assumptions.map((a, i) => (
                          <li key={i}><strong>{assumptionLabel(a)}</strong> — {a.reasonHr}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* ---- the fork ---- */}
            <div className="nk-decide">
              <h2 className="nk-decide-title">Odaberi kako želiš ostvariti look</h2>
              <p className="nk-decide-sub">Dobiješ točno ono što ti treba.</p>

              {/* The main decision: pick one, see it selected, then continue. Two buttons that each fired
                  immediately gave no selected state and no sense that this was THE fork in the flow. */}
              <div className="nk-fork-options" role="radiogroup" aria-label="Kako radiš nokte">
                <button
                  type="button" role="radio" aria-checked={mode === 'SALON'}
                  className={mode === 'SALON' ? 'nk-fork-btn is-chosen' : 'nk-fork-btn'}
                  onClick={() => { setMode('SALON'); setResult(null); }} data-testid="choose-salon"
                >
                  <span className="nk-fork-icon"><Ico name="bottle" size={24} /></span>
                  <span className="nk-fork-body">
                    <span className="nk-fork-top">
                      <span className="nk-fork-name">Idem u salon</span>
                      <span className="nk-fork-pill">bez kupnje</span>
                    </span>
                    <ul className="nk-fork-list">
                      <li><Ico name="check" size={13} /> Specifikacija za tvoju nail tehničarku</li>
                      <li><Ico name="check" size={13} /> Dijagram i raspored detalja</li>
                      <li><Ico name="check" size={13} /> Točan opis boja, efekata i oblika</li>
                    </ul>
                  </span>
                </button>
                <button
                  type="button" role="radio" aria-checked={mode === 'AT_HOME'}
                  className={mode === 'AT_HOME' ? 'nk-fork-btn is-chosen' : 'nk-fork-btn'}
                  onClick={() => { setMode('AT_HOME'); setResult(null); }} data-testid="choose-home"
                >
                  <span className="nk-fork-icon"><Ico name="bag" size={24} /></span>
                  <span className="nk-fork-body">
                    <span className="nk-fork-top">
                      <span className="nk-fork-name">Radim sama</span>
                    </span>
                    <ul className="nk-fork-list">
                      <li><Ico name="check" size={13} /> Komplet proizvoda s točnim iznosom</li>
                      <li><Ico name="check" size={13} /> Poveznice i trgovine u Hrvatskoj</li>
                      <li><Ico name="check" size={13} /> Priprema, skidanje i sigurnosne napomene</li>
                    </ul>
                  </span>
                </button>
              </div>

              {/* Budget and system belong to the shopping list only. Asking for a budget before she has said
                  she is buying anything is asking a question the salon branch never uses. */}
              {mode === 'AT_HOME' && (
                <div className="nk-setup" data-testid="home-setup">
                  <label className="nk-control" htmlFor="nk-budget">
                    <span>Budžet (€)</span>
                    <input id="nk-budget" className="nk-input nk-input-num" type="number" min="0" value={budget}
                      onChange={(e) => setBudget(e.target.value)} data-testid="f-budget" placeholder="npr. 30" />
                  </label>
                  <label className="nk-control" htmlFor="nk-system">
                    <span>Sustav</span>
                    <select id="nk-system" className="nk-select" value={system} data-testid="f-system"
                      onChange={(e) => setSystem(e.target.value as typeof system)}>
                      <option value="regular-polish">klasični lak</option>
                      <option value="press-on">umjetni nokti (press-on)</option>
                    </select>
                  </label>
                </div>
              )}

              <div className="nk-fork-go">
                <button
                  type="button" className="nk-btn nk-btn-block" disabled={busy || !mode} data-testid="fork-continue"
                  onClick={() => mode && generate(mode)}
                >
                  {busy ? 'Slažem…' : mode === 'SALON' ? 'Složi specifikaciju za salon'
                    : mode === 'AT_HOME' ? 'Složi popis za kupnju' : 'Nastavi'}
                  <Ico name="arrow" />
                </button>
                {!mode && <span className="nk-fork-hint">Odaberi salon ili kod kuće da nastaviš.</span>}
              </div>

              <p className="nk-fineprint-lock">
                <Ico name="lock" size={14} />
                <span>Cijene i dostupnost provjeri na stranici trgovine.</span>
              </p>
            </div>
          </div>
        )}

        {/* ---- salon result ---- */}
        {salon && (
          <SalonResult
            salon={salon}
            diagramSvg={result?.designDiagramSvg || diagram}
            assumptions={result?.brief?.assumptions ?? brief?.assumptions ?? []}
            prompt={prompt}
            onEditSpec={editSpecification}
          />
        )}

        {/* ---- at-home result ---- */}
        {kit && (
          <div className="nk-result" data-testid="nail-kit">
            <div className="nk-panel">
              <div className="nk-panel-head">
                <h2 className="nk-panel-title">Popis za kupnju</h2>
                <span className="nk-panel-meta">{kit.retailerCount} {kit.retailerCount === 1 ? 'trgovina' : 'trgovine'}</span>
              </div>

              <div className={`nk-status ${statusTone(kit.status)}`} data-testid="kit-status" style={{ marginTop: 16 }}>
                <div className="nk-status-name">{kit.statusLabelHr}</div>
                <p className="nk-status-why">{kit.statusExplanationHr}</p>
              </div>

              {kit.missingRequiredSlots.length > 0 && (
                <div className="nk-owned nk-missing" data-testid="kit-missing">
                  <strong>Nedostaje u katalogu:</strong>
                  <ul className="nk-notes">{kit.missingRequiredSlots.map((m) => <li key={m}>{m}</li>)}</ul>
                </div>
              )}

              {/* Grouped, because "what do I have to buy" and "what would be nice" are different questions
                  and a mixed list with a tag on each row makes you answer them one row at a time. */}
              {([[true, 'Obavezno'], [false, 'Opcionalno']] as Array<[boolean, string]>).map(
                ([essential, heading]) => {
                  const rows = kit.items.filter((i) => i.essential === essential);
                  if (rows.length === 0) return null;
                  return (
                    <div key={heading}>
                      <span className="nk-section-label">{heading}</span>
                      {rows.map((item) => (
                        <KitRow key={item.slot + (item.externalId ?? '')} item={item} onSwap={swapTo} />
                      ))}
                    </div>
                  );
                })}

              {kit.items.length > 0 && (
                <div className="nk-totals" data-testid="kit-totals">
                  <div className="nk-total-line"><span>Obavezno</span><span>{eur(kit.essentialTotalCents)}</span></div>
                  <div className="nk-total-line"><span>Opcionalno</span><span>{eur(kit.optionalTotalCents)}</span></div>
                  <div className="nk-total-line nk-total-grand">
                    <span>Ukupno</span><span data-testid="kit-total">{eur(kit.totalCents)}</span>
                  </div>
                  {kit.budgetCents != null && (
                    <div className="nk-total-line">
                      <span>Budžet {eur(kit.budgetCents)}</span>
                      <span className={(kit.remainingCents ?? 0) < 0 ? 'nk-over' : 'nk-under'} data-testid="kit-remaining">
                        {(kit.remainingCents ?? 0) < 0 ? 'nedostaje ' : 'ostaje '}{eur(Math.abs(kit.remainingCents ?? 0))}
                      </span>
                    </div>
                  )}
                </div>
              )}

              {kit.ownedItems.length > 0 && (
                <div className="nk-owned" data-testid="kit-owned">
                  <strong>Već imaš — nije u iznosu</strong>
                  {/* No photo here, ever. She told us she owns a base coat, not WHICH base coat — putting a
                      catalog product's picture beside it would claim we know what is in her drawer. */}
                  {kit.ownedItems.map((o) => (
                    <div className="nk-owned-item" key={o.slot} data-testid={`kit-owned-${o.slot}`}>
                      <ProductThumb src={o.imageUrl || o.swatchImageUrl} alt={o.slotLabelHr} size="sm" />
                      <span className="nk-owned-name">{o.slotLabelHr}</span>
                      <s>0,00 €</s>
                    </div>
                  ))}
                </div>
              )}

              {kit.items.length > 0 && (
                <div className="nk-refine" data-testid="kit-refine">
                  <button type="button" className="nk-btn nk-btn-ghost nk-btn-sm" data-testid="refine-cheaper"
                    onClick={() => { setCheapest(true); generate('AT_HOME', { preferCheapest: true }); }}>
                    Može jeftinije
                  </button>
                  {result?.singleStoreOptions?.map((r) => (
                    <button key={r} type="button" className="nk-btn nk-btn-ghost nk-btn-sm" data-testid="refine-onestore"
                      onClick={() => { setOneStore(r); generate('AT_HOME', { singleRetailer: r }); }}>
                      Sve iz {r}
                    </button>
                  ))}
                  {(cheapest || oneStore || Object.keys(pinned).length > 0) && (
                    <button type="button" className="nk-btn nk-btn-ghost nk-btn-sm" data-testid="refine-reset"
                      onClick={() => { setCheapest(false); setOneStore(null); setPinned({});
                        generate('AT_HOME', { preferCheapest: false, singleRetailer: null, pinnedBySlot: {} }); }}>
                      Poništi izmjene
                    </button>
                  )}
                </div>
              )}

              {/* Open, not behind a count. A number tells you how many guesses there were and hides the
                  only part that matters — which ones, and why. */}
              {kit.assumptions.length > 0 && (
                <div className="nk-assume-list" data-testid="kit-assumptions">
                  <span className="nk-section-label">Pretpostavke — provjeri prije kupnje</span>
                  <ul className="nk-assume-items">
                    {kit.assumptions.map((a, i) => (
                      <li key={i}><strong>{assumptionLabel(a)}</strong> — {a.reasonHr}</li>
                    ))}
                  </ul>
                </div>
              )}

              {kit.prepStepsHr?.length > 0 && (
                <div data-testid="kit-prep">
                  <span className="nk-section-label">Priprema</span>
                  <ol className="nk-steps-list">{kit.prepStepsHr.map((s) => <li key={s}>{s}</li>)}</ol>
                </div>
              )}

              {kit.removalStepsHr?.length > 0 && (
                <div data-testid="kit-removal">
                  <span className="nk-section-label">Skidanje</span>
                  <ol className="nk-steps-list">{kit.removalStepsHr.map((s) => <li key={s}>{s}</li>)}</ol>
                </div>
              )}

              <span className="nk-section-label">Sigurnost</span>
              <ul className="nk-notes">{kit.safetyNotesHr.map((n) => <li key={n}>{n}</li>)}</ul>
              {kit.catalogFreshnessHr && (
                <p className="nk-fineprint" data-testid="kit-freshness">{kit.catalogFreshnessHr}</p>
              )}
              {kit.catalogProvenanceHr && <p className="nk-fineprint">{kit.catalogProvenanceHr}</p>}

              <div className="nk-result-foot">
                <button type="button" className="nk-btn nk-btn-ghost nk-btn-sm" data-testid="edit-spec-home"
                  onClick={editSpecification}>
                  <Ico name="pen" size={14} /> Uredi specifikaciju
                </button>
              </div>
            </div>

            <FeedbackBlock mode="AT_HOME" kitStatus={kit.status} prompt={prompt} testid="feedback-home" />
          </div>
        )}

        {parseInfo?.forbiddenSystemRequested && !result && (
          <div className="nk-error" data-testid="forbidden-note">{parseInfo.forbiddenSystemNote}</div>
        )}
      </div>
    </section>
  );
}

/**
 * The salon result: everything she needs to hand a professional, and nothing about buying anything.
 *
 * <p>No prices, no retailer links and no product names reach this component — it is not given the kit. That
 * is structural rather than a rule someone has to remember: she is paying a technician who chooses their own
 * materials, so a shopping list here would be answering a question nobody asked.</p>
 */
function SalonResult({ salon, diagramSvg, assumptions, prompt, onEditSpec }: {
  salon: SalonBrief;
  diagramSvg: string;
  assumptions: Assumption[];
  prompt: string;
  onEditSpec: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const shareRef = useRef<HTMLDivElement>(null);

  async function copyBrief() {
    const text = salon.copyTextHr;
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // Clipboard API needs a secure context and a permission; a hidden textarea + execCommand works
      // everywhere else, and failing silently on a Copy button is worse than a deprecated call.
      const scratch = document.createElement('textarea');
      scratch.value = text;
      scratch.setAttribute('readonly', '');
      scratch.style.position = 'fixed';
      scratch.style.opacity = '0';
      document.body.appendChild(scratch);
      scratch.select();
      document.execCommand('copy');
      document.body.removeChild(scratch);
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 2200);
  }

  /**
   * The brief as a PNG she can send in a chat. Composed on a canvas from our OWN diagram plus our own text —
   * no generated imagery and no third-party renderer, so what downloads is exactly what is on screen.
   */
  async function downloadPng() {
    setDownloading(true);
    try {
      const W = 900;
      const scale = 2;
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      const img = new Image();
      img.decoding = 'sync';
      await new Promise<void>((resolve, reject) => {
        img.onload = () => resolve();
        img.onerror = () => reject(new Error('svg'));
        img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(diagramSvg);
      });

      const sans = (weight: number, size: number) =>
        `${weight} ${size}px "Segoe UI", system-ui, -apple-system, Arial, sans-serif`;
      const serif = (size: number) => `${size}px Georgia, "Times New Roman", serif`;

      // Measure first so the canvas is exactly as tall as the content, then draw once at 2x.
      const pad = 44;
      const colGap = 34;
      const artW = 330;
      const artH = Math.round(artW * (img.height / img.width));
      const textX = pad + artW + colGap;
      const textW = W - textX - pad;

      const wrap = (text: string, font: string, maxWidth: number) => {
        ctx.font = font;
        const out: string[] = [];
        let line = '';
        for (const word of text.split(/\s+/)) {
          const next = line ? `${line} ${word}` : word;
          if (ctx.measureText(next).width > maxWidth && line) { out.push(line); line = word; }
          else line = next;
        }
        if (line) out.push(line);
        return out;
      };

      const briefLines = wrap(salon.showToTechnician, sans(400, 16), textW);
      const specRows = salon.specification.length;
      const rightH = 30 + briefLines.length * 24 + 26 + specRows * 26;
      const placementH = 34 + Math.ceil(salon.placement.length / 2) * 24;
      const notesLines = salon.techniqueNotes.flatMap((n) => wrap('• ' + n, sans(400, 15), W - 2 * pad));
      const notesH = 34 + notesLines.length * 23;
      const assumeLines = assumptions.flatMap((a) =>
        wrap(`• ${assumptionLabel(a)} — ${a.reasonHr}`, sans(400, 14), W - 2 * pad));
      const assumeH = assumptions.length ? 34 + assumeLines.length * 21 : 0;
      const discLines = wrap(salon.variabilityDisclaimer, sans(400, 13), W - 2 * pad);
      const H = pad + 46 + Math.max(artH, rightH) + placementH + notesH + assumeH + 26 + discLines.length * 19 + pad;

      canvas.width = W * scale;
      canvas.height = H * scale;
      ctx.scale(scale, scale);

      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, W, H);

      let y = pad + 4;
      ctx.fillStyle = '#8e1b3a';
      ctx.font = sans(700, 11);
      ctx.fillText('NAIL LOOK · SPECIFIKACIJA ZA SALON', pad, y);
      y += 26;
      ctx.fillStyle = '#2a1620';
      ctx.font = serif(26);
      ctx.fillText('Pokaži svojoj nail tehničarki', pad, y);

      const bodyTop = y + 26;
      ctx.drawImage(img, pad, bodyTop, artW, artH);

      let ty = bodyTop + 18;
      ctx.fillStyle = '#2a1620';
      ctx.font = sans(400, 16);
      for (const line of briefLines) { ctx.fillText(line, textX, ty); ty += 24; }
      ty += 18;
      ctx.font = sans(700, 10);
      ctx.fillStyle = '#9a8189';
      ctx.fillText('SPECIFIKACIJA', textX, ty);
      ty += 20;
      for (const line of salon.specification) {
        ctx.font = sans(400, 14);
        ctx.fillStyle = '#6b5560';
        ctx.fillText(line.labelHr, textX, ty);
        ctx.font = sans(600, 14);
        ctx.fillStyle = '#2a1620';
        const v = line.valueHr;
        ctx.fillText(v, W - pad - ctx.measureText(v).width, ty);
        ty += 26;
      }

      let sy = bodyTop + Math.max(artH, rightH) + 30;
      const section = (title: string) => {
        ctx.font = sans(700, 10);
        ctx.fillStyle = '#9a8189';
        ctx.fillText(title, pad, sy);
        sy += 22;
      };
      section('NOKAT PO NOKAT');
      ctx.font = sans(400, 14);
      for (let i = 0; i < salon.placement.length; i += 2) {
        ctx.fillStyle = '#2a1620';
        ctx.fillText(salon.placement[i], pad, sy);
        if (salon.placement[i + 1]) ctx.fillText(salon.placement[i + 1], pad + (W - 2 * pad) / 2, sy);
        sy += 24;
      }
      sy += 12;
      section('NAPOMENE');
      ctx.font = sans(400, 15);
      ctx.fillStyle = '#6b5560';
      for (const line of notesLines) { ctx.fillText(line, pad, sy); sy += 23; }
      if (assumptions.length) {
        sy += 12;
        section('PRETPOSTAVKE — NIJE BILO NAVEDENO U OPISU');
        ctx.font = sans(400, 14);
        ctx.fillStyle = '#6b5560';
        for (const line of assumeLines) { ctx.fillText(line, pad, sy); sy += 21; }
      }
      sy += 18;
      ctx.font = sans(400, 13);
      ctx.fillStyle = '#9a8189';
      for (const line of discLines) { ctx.fillText(line, pad, sy); sy += 19; }

      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
      if (!blob) return;
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'nail-look-salon.png';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.setTimeout(() => URL.revokeObjectURL(url), 4000);
    } finally {
      setDownloading(false);
    }
  }

  return (
    <div className="nk-result" data-testid="salon-brief">
      <div className="nk-result-head">
        <div>
          <span className="nk-card-label">Rezultat · salon</span>
          <h2 className="nk-result-title">Pokaži svojoj nail tehničarki</h2>
        </div>
        <div className="nk-result-actions">
          <button type="button" className="nk-btn nk-btn-ghost nk-btn-sm" onClick={copyBrief} data-testid="copy-brief">
            <Ico name="copy" size={14} /> {copied ? 'Kopirano' : 'Kopiraj tekst'}
          </button>
          <button type="button" className="nk-btn nk-btn-ghost nk-btn-sm" onClick={downloadPng}
            disabled={downloading} data-testid="download-brief">
            <Ico name="download" size={14} /> {downloading ? 'Spremam…' : 'Preuzmi sliku'}
          </button>
          <button type="button" className="nk-btn nk-btn-ghost nk-btn-sm" onClick={onEditSpec} data-testid="edit-spec">
            <Ico name="pen" size={14} /> Uredi specifikaciju
          </button>
        </div>
      </div>

      {/* One self-contained panel, so a phone screenshot of it is a complete brief with no page chrome. */}
      <div className="nk-brief-card nk-share" ref={shareRef}>
        <div className="nk-share-cols">
          <div className="nk-share-visual" data-testid="salon-diagram"
            dangerouslySetInnerHTML={{ __html: diagramSvg }} />
          <div>
            <blockquote className="nk-brief-quote" data-testid="show-to-tech">{salon.showToTechnician}</blockquote>
            <span className="nk-section-label">Specifikacija</span>
            {salon.specification.map((l) => (
              <div className="nk-field" key={l.labelHr}>
                <span className="nk-field-label">{l.labelHr}</span>
                <span className="nk-field-leader" />
                <span className="nk-field-value">{l.valueHr}</span>
              </div>
            ))}
          </div>
        </div>

        <span className="nk-section-label">Nokat po nokat</span>
        <ul className="nk-placement" data-testid="nail-placement">
          {salon.placement.map((p) => (
            <li key={p} className={p.includes('naglasak') ? 'is-accent' : ''}>{p}</li>
          ))}
        </ul>

        <span className="nk-section-label">Napomene</span>
        <ul className="nk-notes">{salon.techniqueNotes.map((n) => <li key={n}>{n}</li>)}</ul>

        {assumptions.length > 0 && (
          <div className="nk-assume-list" data-testid="salon-assumptions">
            <span className="nk-section-label">Pretpostavke — nije bilo navedeno u opisu</span>
            <ul className="nk-assume-items">
              {assumptions.map((a, i) => (
                <li key={i}><strong>{assumptionLabel(a)}</strong> — {a.reasonHr}</li>
              ))}
            </ul>
          </div>
        )}

        <p className="nk-fineprint">{salon.simplerAlternative}</p>
        <p className="nk-fineprint">{salon.variabilityDisclaimer}</p>
      </div>

      <FeedbackBlock mode="SALON" kitStatus={null} prompt={prompt} testid="feedback-salon" />
    </div>
  );
}

/**
 * The two pilot questions. Optional by construction — no submit button, no required field, and no thank-you
 * modal blocking the result. Each answer posts on click and the block says so; nothing else happens.
 */
function FeedbackBlock({ mode, kitStatus, prompt, testid }: {
  mode: 'SALON' | 'AT_HOME';
  kitStatus: string | null;
  prompt: string;
  testid: string;
}) {
  const [match, setMatch] = useState<FeedbackMatch | null>(null);
  const [intent, setIntent] = useState<FeedbackIntent | null>(null);
  const [failed, setFailed] = useState(false);

  function answer(next: { matchedExpectation?: FeedbackMatch; wouldUse?: FeedbackIntent }) {
    if (next.matchedExpectation) setMatch(next.matchedExpectation);
    if (next.wouldUse) setIntent(next.wouldUse);
    setFailed(false);
    sendNailFeedback({ ...next, executionMode: mode, kitStatus, prompt }).catch(() => setFailed(true));
  }

  const QUESTIONS: Array<[string, Array<[string, string]>, string | null, (v: string) => void]> = [
    ['Je li ovo ono što si zamislila?',
      [['DA', 'Da'], ['DJELOMICNO', 'Djelomično'], ['NE', 'Ne']],
      match, (v) => answer({ matchedExpectation: v as FeedbackMatch })],
    ['Bi li koristila ili platila ovakav rezultat?',
      [['KORISTILA', 'Koristila bih'], ['PLATILA', 'Platila bih'], ['NE_BIH', 'Ne bih']],
      intent, (v) => answer({ wouldUse: v as FeedbackIntent })],
  ];

  return (
    <div className="nk-feedback" data-testid={testid}>
      <span className="nk-card-label">Pomozi nam — nije obavezno</span>
      {QUESTIONS.map(([question, options, chosen, pick]) => (
        <div className="nk-feedback-q" key={question}>
          <p>{question}</p>
          <div className="nk-chips">
            {options.map(([value, label]) => (
              <button key={value} type="button" className="nk-chip" aria-pressed={chosen === value}
                data-testid={`${testid}-${value.toLowerCase()}`} onClick={() => pick(value)}>
                {label}
              </button>
            ))}
          </div>
        </div>
      ))}
      {(match || intent) && !failed && <p className="nk-feedback-note">Hvala — odgovor je zabilježen.</p>}
      {failed && <p className="nk-feedback-note">Odgovor nije zabilježen. Nije važno, možeš nastaviti.</p>}
    </div>
  );
}

/** The four claims this product can actually stand behind, and nothing it cannot. */
export function NailTrustBar() {
  const items: Array<[string, string, string]> = [
    ['box', 'Pilot katalog HR trgovina', 'Strojno preuzet s javnih feedova'],
    ['tag', 'Cijene iz feeda', 'Provjeri kod trgovca prije kupnje'],
    ['shield', 'Sigurnost na prvom mjestu', 'Ne preporučujemo profesionalne sustave'],
    ['eye', 'Pošteni rezultati', 'Pretpostavke su uvijek vidljive'],
  ];
  return (
    <div className="nk-trust">
      <div className="nk-trust-inner">
        {items.map(([icon, title, sub]) => (
          <div className="nk-trust-item" key={title}>
            <span className="nk-trust-ico"><Ico name={icon} size={17} /></span>
            <span>
              <strong>{title}</strong>
              <span>{sub}</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * A product thumbnail, or an honest admission that there isn't one.
 *
 * <p>The rule the placeholder exists to keep: never show a picture of a DIFFERENT product where this one's
 * photo would go. A generic bottle beside a real name and a real price reads as "this is what you are
 * buying", and it would be the one fabricated thing in a catalog built on not fabricating anything. So the
 * empty state is visibly empty — dashed, tinted, captioned "bez slike" — and a URL that fails to load falls
 * into exactly the same state rather than leaving a broken-image glyph behind.</p>
 *
 * <p>Owned items always land here: the user told us she already has a base coat, not WHICH base coat, so
 * there is no product to picture.</p>
 *
 * <p>The copy says "not available" rather than "not published", because both are real and they are not the
 * same thing: Golden Rose publishes no image for some rows, while beauty-shop.hr publishes image URLs and
 * then answers 415 for them from anywhere but its own pages. Saying the retailer publishes nothing would
 * be false in the second case, and this is not a product that gets to be casually false about a retailer.</p>
 */
function ProductThumb({ src, alt, size = 'md' }: { src?: string | null; alt: string; size?: 'md' | 'sm' }) {
  const [broken, setBroken] = useState(false);
  const cls = `nk-thumb nk-thumb-${size}`;
  if (!src || broken) {
    return (
      <span className={`${cls} is-empty`} role="img" aria-label={`${alt} — nema dostupne slike proizvoda`}
        title="Slika ovog proizvoda nije dostupna" data-testid="thumb-placeholder">
        <Ico name="noimage" size={15} />
        <em>bez slike</em>
      </span>
    );
  }
  return (
    <img className={cls} src={src} alt={alt} loading="lazy" decoding="async" referrerPolicy="no-referrer"
      onError={() => setBroken(true)} data-testid="kit-thumb" />
  );
}

function KitRow({ item, onSwap }: { item: KitItem; onSwap: (slot: string, id: string) => void }) {
  return (
    <div className="nk-kit-row" data-testid={`kit-row-${item.slot}`}>
      <div className="nk-kit-slot">{item.slotLabelHr}</div>
      <div className="nk-kit-main">
        <ProductThumb src={item.imageUrl || item.swatchImageUrl} alt={item.name} />
      <div className="nk-kit-body">
        <div className="nk-kit-name">
          <a href={item.productUrl ?? '#'} target="_blank" rel="noopener noreferrer nofollow">
            {item.name}{item.shadeName ? ` — ${item.shadeName}` : ''}
          </a>
        </div>
        <div className="nk-kit-sub">{item.retailer}</div>
        <div className="nk-kit-why">{item.whyHr}</div>
        {item.noteHr && <div className="nk-kit-note">{item.noteHr}</div>}
        {item.alternatives.length > 0 && (
          <details className="nk-alts">
            <summary>Zamijeni ({item.alternatives.length})</summary>
            {item.alternatives.map((alt) => (
              <div className="nk-alt" key={alt.externalId}>
                <ProductThumb src={alt.imageUrl || alt.swatchImageUrl} alt={alt.name} size="sm" />
                <span className="nk-alt-name">{alt.name}{alt.shadeName ? ` — ${alt.shadeName}` : ''}</span>
                <span className="nk-alt-delta">
                  {alt.priceDeltaCents === 0 ? '=' : alt.priceDeltaCents > 0 ? `+${eur(alt.priceDeltaCents)}` : `−${eur(-alt.priceDeltaCents)}`}
                </span>
                <button type="button" className="nk-btn nk-btn-ghost nk-btn-sm"
                  data-testid={`swap-${item.slot}`} onClick={() => onSwap(item.slot, alt.externalId)}>
                  Uzmi
                </button>
              </div>
            ))}
          </details>
        )}
        </div>
      </div>
      <div className="nk-kit-price">
        <div>{eur(item.priceCents)}</div>
        <span className={`nk-tag ${item.essential ? 'nk-tag-essential' : 'nk-tag-optional'}`}>
          {item.essential ? 'obavezno' : 'opcionalno'}
        </span>
      </div>
    </div>
  );
}
