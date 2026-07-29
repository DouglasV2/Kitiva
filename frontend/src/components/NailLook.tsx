import { useState } from 'react';
import '../nailkit.css';
import {
  parseNailPrompt, generateNailLook,
  type NailParseResponse, type NailGenerateResponse, type KitItem
} from '../api/nail';
import type { NailLookBrief, NailDesignSpec, OwnedItem } from '../types/beauty';

/**
 * Nail Look / Nail Kit.
 *
 * The flow is deliberately: prompt -> EDITABLE brief -> choose salon or at home -> result. The brief sits
 * in the middle because a parser reading Croatian free text will sometimes be wrong, and the cheapest place
 * to fix that is before anything is built. Every field the parser fills is a field the user can correct.
 *
 * Salon and at-home are asked, never inferred. They produce fundamentally different things, and one of them
 * recommends chemical products to a consumer.
 */

const SHAPES = [
  ['ALMOND', 'almond'], ['OVAL', 'ovalni'], ['SQUARE', 'četvrtasti'], ['SQUOVAL', 'četvrtasto-ovalni'],
  ['ROUND', 'okrugli'], ['COFFIN', 'coffin'], ['STILETTO', 'stiletto'],
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

/** Mirrors the backend colour table so the picker and the diagram agree on every hex. */
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

const eur = (cents: number) => `${(cents / 100).toFixed(2).replace('.', ',')} €`;

export function NailLook() {
  const [prompt, setPrompt] = useState(
    'Želim kratke almond burgundy cat-eye nokte s dva diskretna zlatna detalja.'
  );
  const [budget, setBudget] = useState('');
  const [system, setSystem] = useState<'regular-polish' | 'press-on'>('regular-polish');
  const [brief, setBrief] = useState<NailLookBrief | null>(null);
  const [diagram, setDiagram] = useState('');
  const [parseInfo, setParseInfo] = useState<NailParseResponse | null>(null);
  const [result, setResult] = useState<NailGenerateResponse | null>(null);
  const [pinned, setPinned] = useState<Record<string, string>>({});
  const [cheapest, setCheapest] = useState(false);
  const [oneStore, setOneStore] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const budgetCents = budget.trim() ? Math.round(Number(budget) * 100) : 0;

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

  const statusTone = (s?: string) =>
    s === 'COMPLETE' ? 'nk-status-ok'
    : s === 'SAFETY_BLOCKED' || s === 'INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE' ? 'nk-status-stop'
    : 'nk-status-warn';

  return (
    <section className="nk" id="nail-look">
      <div className="nk-shell">
        <header className="nk-masthead">
          <p className="nk-eyebrow">Nokti · Hrvatska</p>
          <h1 className="nk-title">Opiši nokte.<br />Dobij <em>specifikaciju</em> ili <em>popis za kupnju</em>.</h1>
          <p className="nk-standfirst">
            Napiši kako želiš da nokti izgledaju. Pretvorit ćemo to u specifikaciju koju možeš ispraviti — pa
            odaberi ideš li u salon ili radiš sama.
          </p>
        </header>

        <div className="nk-ask">
          <label className="nk-ask-label" htmlFor="nail-prompt">Kako želiš da izgledaju</label>
          <textarea
            id="nail-prompt" className="nk-ask-field" rows={2} value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            placeholder="npr. kratki almond nokti boje višnje s cat-eye efektom"
          />
          <div className="nk-ask-row">
            <button type="button" className="nk-btn" onClick={handleParse} disabled={busy || !prompt.trim()} data-testid="parse">
              {busy ? 'Čitam…' : 'Pročitaj opis'}
            </button>
          </div>
        </div>

        {error && <div className="nk-error" role="alert">{error}</div>}

        {brief && d && (
          <>
            <div className="nk-work">
              {/* ---- the editable worksheet ---- */}
              <div className="nk-panel" data-testid="nail-brief">
                <div className="nk-panel-head">
                  <h2 className="nk-panel-title">Specifikacija</h2>
                  <span className="nk-panel-meta">možeš ispraviti</span>
                </div>

                <Field label="Oblik">
                  <select className="nk-select" value={d.shape} data-testid="f-shape"
                    onChange={(e) => patchDesign({ shape: e.target.value as NailDesignSpec['shape'] })}>
                    {SHAPES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                  </select>
                </Field>

                <Field label="Duljina">
                  <select className="nk-select" value={d.length} data-testid="f-length"
                    onChange={(e) => patchDesign({ length: e.target.value as NailDesignSpec['length'] })}>
                    {LENGTHS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                  </select>
                </Field>

                <Field label="Glavna boja">
                  <span className="nk-swatch" style={{ background: d.baseColorHex ?? 'transparent' }} aria-hidden="true" />
                  <select className="nk-select" value={d.baseColorKey} data-testid="f-color"
                    onChange={(e) => {
                      const c = COLORS.find(([k]) => k === e.target.value);
                      patchDesign({ baseColorKey: e.target.value, baseColorHex: c?.[1] || null, baseColorRawText: c?.[2] ?? '' });
                    }}>
                    {COLORS.map(([k, , l]) => <option key={k || 'none'} value={k}>{l}</option>)}
                  </select>
                </Field>

                <Field label="Boja detalja">
                  <span className="nk-swatch" style={{ background: d.accentColorHex ?? 'transparent' }} aria-hidden="true" />
                  <select className="nk-select" value={d.accentColorKey} data-testid="f-accent-color"
                    onChange={(e) => {
                      const c = COLORS.find(([k]) => k === e.target.value);
                      patchDesign({ accentColorKey: e.target.value, accentColorHex: c?.[1] || null });
                    }}>
                    {COLORS.map(([k, , l]) => <option key={k || 'none'} value={k}>{l}</option>)}
                  </select>
                </Field>

                <Field label="Završni sloj">
                  <select className="nk-select" value={d.finish} data-testid="f-finish"
                    onChange={(e) => patchDesign({ finish: e.target.value as NailDesignSpec['finish'] })}>
                    {FINISHES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                  </select>
                </Field>

                <Field label="Ruke">
                  <select className="nk-select" value={d.symmetry} data-testid="f-symmetry"
                    onChange={(e) => patchDesign({ symmetry: e.target.value as NailDesignSpec['symmetry'] })}>
                    <option value="MIRRORED">isto na obje</option>
                    <option value="ASYMMETRIC_STATED">namjerno različito</option>
                  </select>
                </Field>

                <p className="nk-section-label">Efekt</p>
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

                <p className="nk-section-label">Gdje ide detalj</p>
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

                <p className="nk-section-label">Što već imaš</p>
                <div className="nk-chips" data-testid="f-owned">
                  {OWNABLE.map(([slot, l]) => (
                    <button key={slot} type="button" className="nk-chip" aria-pressed={ownedSlots.has(slot)}
                      onClick={() => toggleOwned(slot)} data-testid={`owned-${slot}`}>{l}</button>
                  ))}
                </div>
              </div>

              {/* ---- the signature: the live diagram ---- */}
              <div>
                <div className="nk-diagram" data-testid="nail-diagram" dangerouslySetInnerHTML={{ __html: diagram }} />
                <p className="nk-diagram-caption">
                  Shematski dijagram — pokazuje oblik, duljinu, boju i koji nokti nose detalj. Nije fotografija.
                </p>

                {brief.assumptions.length > 0 && (
                  <details className="nk-assumptions" data-testid="brief-assumptions">
                    <summary>Pretpostavili smo {brief.assumptions.length} — provjeri</summary>
                    <ul>{brief.assumptions.map((a, i) => <li key={i}><strong>{a.field}:</strong> {a.reasonHr}</li>)}</ul>
                  </details>
                )}
              </div>
            </div>

            {/* ---- the fork ---- */}
            <div className="nk-fork">
              <p className="nk-eyebrow">Kako radiš nokte?</p>
              <div className="nk-ask-row" style={{ marginTop: 4 }}>
                <label className="nk-field-label" htmlFor="nk-budget">Budžet za kupnju (€)</label>
                <input id="nk-budget" className="nk-input nk-input-num" type="number" min="0" value={budget}
                  onChange={(e) => setBudget(e.target.value)} data-testid="f-budget" placeholder="npr. 30" />
                <label className="nk-field-label" htmlFor="nk-system">Sustav</label>
                <select id="nk-system" className="nk-select" value={system} data-testid="f-system"
                  onChange={(e) => setSystem(e.target.value as typeof system)}>
                  <option value="regular-polish">klasični lak</option>
                  <option value="press-on">umjetni nokti (press-on)</option>
                </select>
              </div>

              <div className="nk-fork-options">
                <button type="button" className="nk-fork-btn" onClick={() => generate('SALON')} disabled={busy} data-testid="choose-salon">
                  <span className="nk-fork-name">Idem u salon</span>
                  <span className="nk-fork-sub">Dobiješ dijagram i tekst koji pokažeš svojoj tehničarki. Bez proizvoda.</span>
                </button>
                <button type="button" className="nk-fork-btn" onClick={() => generate('AT_HOME')} disabled={busy} data-testid="choose-home">
                  <span className="nk-fork-name">Radim sama</span>
                  <span className="nk-fork-sub">Dobiješ popis proizvoda s točnim iznosom i linkovima na trgovine.</span>
                </button>
              </div>
            </div>
          </>
        )}

        {/* ---- salon result ---- */}
        {salon && (
          <div style={{ marginTop: 32 }} data-testid="salon-brief">
            <div className="nk-brief-card">
              <div className="nk-panel-head">
                <h2 className="nk-panel-title">Za nail tehničarku</h2>
                <span className="nk-panel-meta">slikaj ekran</span>
              </div>
              <blockquote className="nk-brief-quote" data-testid="show-to-tech">{salon.showToTechnician}</blockquote>

              <p className="nk-section-label">Specifikacija</p>
              {salon.specification.map((l) => (
                <div className="nk-field" key={l.labelHr}>
                  <span className="nk-field-label">{l.labelHr}</span>
                  <span className="nk-field-leader" />
                  <span className="nk-field-value">{l.valueHr}</span>
                </div>
              ))}

              <p className="nk-section-label">Nokat po nokat</p>
              <ul className="nk-placement" data-testid="nail-placement">
                {salon.placement.map((p) => (
                  <li key={p} className={p.includes('naglasak') ? 'is-accent' : ''}>{p}</li>
                ))}
              </ul>

              <p className="nk-section-label">Napomene</p>
              <ul className="nk-notes">{salon.techniqueNotes.map((n) => <li key={n}>{n}</li>)}</ul>
              <p className="nk-fineprint"><em>{salon.simplerAlternative}</em></p>
              <p className="nk-fineprint">{salon.variabilityDisclaimer}</p>
            </div>
          </div>
        )}

        {/* ---- at-home result ---- */}
        {kit && (
          <div style={{ marginTop: 32 }} data-testid="nail-kit">
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
                <div className="nk-owned" data-testid="kit-missing" style={{ background: 'var(--nk-stop-soft)' }}>
                  <strong style={{ fontSize: 13 }}>Nedostaje u katalogu:</strong>
                  <ul className="nk-notes">{kit.missingRequiredSlots.map((m) => <li key={m}>{m}</li>)}</ul>
                </div>
              )}

              {kit.items.map((item) => (
                <KitRow key={item.slot + (item.externalId ?? '')} item={item} onSwap={swapTo} />
              ))}

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
                  <strong style={{ fontSize: 13 }}>Već imaš — nije u iznosu</strong>
                  {kit.ownedItems.map((o) => (
                    <div className="nk-owned-item" key={o.slot}><span>{o.slotLabelHr}</span><s>0,00 €</s></div>
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

              {kit.assumptions.length > 0 && (
                <details className="nk-assumptions" data-testid="kit-assumptions">
                  <summary>Pretpostavili smo {kit.assumptions.length} — provjeri prije kupnje</summary>
                  <ul>{kit.assumptions.map((a, i) => <li key={i}><strong>{a.field}:</strong> {a.reasonHr}</li>)}</ul>
                </details>
              )}

              <p className="nk-section-label">Sigurnost</p>
              <ul className="nk-notes">{kit.safetyNotesHr.map((n) => <li key={n}>{n}</li>)}</ul>
              {kit.catalogProvenanceHr && <p className="nk-fineprint">{kit.catalogProvenanceHr}</p>}
            </div>
          </div>
        )}

        {parseInfo?.forbiddenSystemRequested && !result && (
          <div className="nk-error" data-testid="forbidden-note">{parseInfo.forbiddenSystemNote}</div>
        )}
      </div>
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="nk-field">
      <span className="nk-field-label">{label}</span>
      <span className="nk-field-leader" />
      <span className="nk-field-value">{children}</span>
    </div>
  );
}

function KitRow({ item, onSwap }: { item: KitItem; onSwap: (slot: string, id: string) => void }) {
  return (
    <div className="nk-kit-row" data-testid={`kit-row-${item.slot}`}>
      <div className="nk-kit-slot">{item.slotLabelHr}</div>
      <div>
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
                <span>{alt.name}{alt.shadeName ? ` — ${alt.shadeName}` : ''}</span>
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
      <div className="nk-kit-price">
        <div>{eur(item.priceCents)}</div>
        <span className={`nk-tag ${item.essential ? 'nk-tag-essential' : 'nk-tag-optional'}`}>
          {item.essential ? 'obavezno' : 'opcionalno'}
        </span>
      </div>
    </div>
  );
}
