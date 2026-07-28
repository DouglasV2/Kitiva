import { useState } from 'react';
import { parseNailPrompt, generateNailLook, type NailParseResponse, type NailGenerateResponse } from '../api/nail';
import type { NailLookBrief } from '../types/beauty';

/**
 * Vertical slice - the Nail Look / Nail Kit experience.
 *
 * Flow: prompt -> editable structured brief -> choose salon or at-home -> result.
 *
 * The salon/at-home choice is a deliberate, unavoidable step rather than something inferred from the
 * prompt. The two branches produce fundamentally different things and one of them recommends chemical
 * products to a consumer, so the user answers that question herself.
 */
export function NailLook() {
  const [prompt, setPrompt] = useState(
    'Zelim kratke almond nokte u boji visnje, s cat-eye efektom i zlatnim detaljem na prstenjacima.'
  );
  const [budget, setBudget] = useState('');
  const [parsed, setParsed] = useState<NailParseResponse | null>(null);
  const [result, setResult] = useState<NailGenerateResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const budgetCents = budget.trim() ? Math.round(Number(budget) * 100) : 0;

  async function handleParse() {
    setBusy(true);
    setError('');
    setResult(null);
    try {
      setParsed(await parseNailPrompt(prompt, budgetCents));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Nesto je poslo po zlu.');
    } finally {
      setBusy(false);
    }
  }

  async function handleGenerate(mode: 'SALON' | 'AT_HOME') {
    if (!parsed) return;
    setBusy(true);
    setError('');
    try {
      const brief: NailLookBrief = { ...parsed.brief, budgetCents };
      setResult(await generateNailLook(brief, mode));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Nesto je poslo po zlu.');
    } finally {
      setBusy(false);
    }
  }

  const design = parsed?.brief.design;
  const kit = result?.kit;
  const eur = (cents: number) => `${(cents / 100).toFixed(2).replace('.', ',')} €`;

  return (
    <section className="planner-section shell" id="nail-look">
      <h2 style={{ marginBottom: 4 }}>Nail Look / Nail Kit</h2>
      <p style={{ color: '#6b625c', marginTop: 0 }}>
        Opisi kakve nokte zelis. Slozit cemo strukturiranu specifikaciju, dijagram dizajna i - ako radis kod
        kuce - kompletan popis za kupnju.
      </p>

      {/* 1-2. Prompt */}
      <div className="nail-prompt-block">
        <label htmlFor="nail-prompt"><strong>Opisi zeljeni izgled</strong></label>
        <textarea
          id="nail-prompt"
          rows={3}
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          style={{ width: '100%', padding: 10, fontFamily: 'inherit', fontSize: 15 }}
        />
        <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 8, flexWrap: 'wrap' }}>
          <label htmlFor="nail-budget">Budzet (€, neobavezno)</label>
          <input
            id="nail-budget"
            type="number"
            min="0"
            value={budget}
            onChange={(e) => setBudget(e.target.value)}
            style={{ width: 110, padding: 8 }}
          />
          <button type="button" onClick={handleParse} disabled={busy || !prompt.trim()} className="primary-cta">
            {busy ? 'Obradujem...' : 'Razumij moj opis'}
          </button>
        </div>
      </div>

      {error && <div className="planner-notice" role="alert">{error}</div>}

      {/* 3. Editable structured brief */}
      {parsed && design && (
        <div className="nail-brief-block" data-testid="nail-brief" style={{ marginTop: 24 }}>
          <h3>Ovako smo te razumjeli</h3>
          <div className="nail-chips" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
            <Chip label="Oblik" value={design.shape} />
            <Chip label="Duljina" value={design.length} />
            <Chip label="Boja" value={design.baseColorRawText || design.baseColorKey || 'nije navedena'} />
            <Chip label="Zavrsni sloj" value={design.finish} />
            {design.effects.filter((e) => e !== 'NONE').map((e) => <Chip key={e} label="Efekt" value={e} />)}
            {design.accentFingers.map((f) => <Chip key={f} label="Naglasak" value={f} />)}
            <Chip label="Ruke" value={design.symmetry === 'MIRRORED' ? 'zrcalno' : 'razlicito'} />
          </div>

          {parsed.brief.assumptions.length > 0 && (
            <details style={{ marginBottom: 12 }}>
              <summary>Pretpostavke ({parsed.brief.assumptions.length}) - provjeri i ispravi</summary>
              <ul>
                {parsed.brief.assumptions.map((a, i) => (
                  <li key={i}><strong>{a.field}:</strong> {a.assumed} - {a.reasonHr}</li>
                ))}
              </ul>
            </details>
          )}

          {/* Diagram: same structured spec that drives the brief and the kit */}
          <div
            className="nail-diagram"
            data-testid="nail-diagram"
            aria-label="Dijagram dizajna"
            dangerouslySetInnerHTML={{ __html: parsed.designDiagramSvg }}
          />

          {/* 4. The unavoidable choice */}
          <h3 style={{ marginTop: 20 }}>Kako radis nokte?</h3>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <button type="button" onClick={() => handleGenerate('SALON')} disabled={busy} data-testid="choose-salon">
              U salonu
            </button>
            <button type="button" onClick={() => handleGenerate('AT_HOME')} disabled={busy} data-testid="choose-home">
              Kod kuce
            </button>
          </div>
        </div>
      )}

      {/* 5. Salon brief */}
      {result?.salonBrief && (
        <div className="nail-salon-block" data-testid="salon-brief" style={{ marginTop: 24 }}>
          <h3>Specifikacija za salon</h3>
          <table className="nail-spec-table">
            <tbody>
              {result.salonBrief.specification.map((line) => (
                <tr key={line.labelHr}>
                  <th style={{ textAlign: 'left', paddingRight: 12 }}>{line.labelHr}</th>
                  <td>{line.valueHr}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <h4>Raspored po noktima</h4>
          <ul className="nail-placement" data-testid="nail-placement">
            {result.salonBrief.placement.map((p) => <li key={p}>{p}</li>)}
          </ul>

          <blockquote data-testid="show-to-tech" style={{ background: '#FBF7F4', padding: 14, borderLeft: '3px solid #C9A227' }}>
            <strong>Pokazi ovo svojoj nail tehnicarki:</strong>
            <p style={{ marginBottom: 0 }}>{result.salonBrief.showToTechnician}</p>
          </blockquote>

          <h4>Tehnicke napomene</h4>
          <ul>{result.salonBrief.techniqueNotes.map((n) => <li key={n}>{n}</li>)}</ul>
          <p><em>{result.salonBrief.simplerAlternative}</em></p>
          <p style={{ color: '#7A6E66', fontSize: 13 }}>{result.salonBrief.variabilityDisclaimer}</p>
        </div>
      )}

      {/* 6. At-home kit */}
      {kit && (
        <div className="nail-kit-block" data-testid="nail-kit" style={{ marginTop: 24 }}>
          <h3>Kit za kod kuce</h3>

          <div
            data-testid="kit-status"
            className={`kit-status kit-status-${kit.status}`}
            style={{
              padding: 12,
              borderRadius: 8,
              marginBottom: 16,
              background: kit.status === 'SAFETY_BLOCKED' ? '#FDECEC'
                : kit.status === 'OVER_BUDGET' || kit.status === 'INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE' ? '#FFF6E5'
                : '#EDF7EE'
            }}
          >
            <strong>{kit.statusLabelHr}</strong>
            <div>{kit.statusExplanationHr}</div>
          </div>

          {kit.items.length > 0 && (
            <>
              <table className="kit-table" data-testid="kit-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th style={{ textAlign: 'left' }}>Korak</th>
                    <th style={{ textAlign: 'left' }}>Proizvod</th>
                    <th style={{ textAlign: 'left' }}>Obavezno</th>
                    <th style={{ textAlign: 'right' }}>Cijena</th>
                  </tr>
                </thead>
                <tbody>
                  {kit.items.map((item) => (
                    <tr key={item.externalId ?? item.slot}>
                      <td>{item.slotLabelHr}</td>
                      <td>
                        <a href={item.productUrl ?? '#'} target="_blank" rel="noopener noreferrer nofollow">
                          {item.name}{item.shadeName ? ` - ${item.shadeName}` : ''}
                        </a>
                        <div style={{ fontSize: 12, color: '#7A6E66' }}>{item.retailer}</div>
                        {item.noteHr && <div style={{ fontSize: 12, color: '#8A6D3B' }}>{item.noteHr}</div>}
                      </td>
                      <td>{item.essential ? 'obavezno' : 'opcionalno'}</td>
                      <td style={{ textAlign: 'right' }}>{eur(item.priceCents)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className="kit-totals" data-testid="kit-totals" style={{ marginTop: 12 }}>
                <div>Obavezno: <strong>{eur(kit.essentialTotalCents)}</strong></div>
                <div>Opcionalno: <strong>{eur(kit.optionalTotalCents)}</strong></div>
                <div style={{ fontSize: 18 }}>Ukupno: <strong data-testid="kit-total">{eur(kit.totalCents)}</strong></div>
                {kit.budgetCents != null && (
                  <div>
                    Budzet {eur(kit.budgetCents)} - preostaje{' '}
                    <strong style={{ color: (kit.remainingCents ?? 0) < 0 ? '#B3261E' : '#2E6B34' }}>
                      {eur(kit.remainingCents ?? 0)}
                    </strong>
                  </div>
                )}
                <div style={{ fontSize: 13, color: '#7A6E66' }}>Trgovaca: {kit.retailerCount}</div>
              </div>
            </>
          )}

          {kit.ownedItems.length > 0 && (
            <div data-testid="kit-owned" style={{ marginTop: 12 }}>
              <h4>Vec imas - izuzeto iz iznosa</h4>
              <ul>{kit.ownedItems.map((o) => <li key={o.slot}>{o.slotLabelHr} - {o.noteHr}</li>)}</ul>
            </div>
          )}

          {kit.missingRequiredSlots.length > 0 && (
            <div style={{ marginTop: 12 }}>
              <h4>Nedostaje</h4>
              <ul>{kit.missingRequiredSlots.map((m) => <li key={m}>{m}</li>)}</ul>
            </div>
          )}

          {kit.assumptions.length > 0 && (
            <details style={{ marginTop: 12 }} data-testid="kit-assumptions">
              <summary>Pretpostavke ({kit.assumptions.length})</summary>
              <ul>{kit.assumptions.map((a, i) => <li key={i}><strong>{a.field}:</strong> {a.reasonHr}</li>)}</ul>
            </details>
          )}

          <div style={{ marginTop: 16, fontSize: 13, color: '#5c5450' }}>
            <h4 style={{ marginBottom: 4 }}>Sigurnosne napomene</h4>
            <ul>{kit.safetyNotesHr.map((n) => <li key={n}>{n}</li>)}</ul>
            {kit.catalogProvenanceHr && <p style={{ color: '#7A6E66' }}>{kit.catalogProvenanceHr}</p>}
          </div>
        </div>
      )}
    </section>
  );
}

function Chip({ label, value }: { label: string; value: string }) {
  return (
    <span
      className="nail-chip"
      style={{ background: '#F1EAE4', borderRadius: 999, padding: '4px 12px', fontSize: 13 }}
    >
      <span style={{ color: '#7A6E66' }}>{label}:</span> <strong>{value}</strong>
    </span>
  );
}
