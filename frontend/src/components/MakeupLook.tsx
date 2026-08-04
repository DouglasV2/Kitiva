/**
 * The Makeup section: pick a look, set a budget, say what you own, get a kit you can actually buy — plus
 * a browsable catalog of everything behind it.
 *
 * <p>Deliberately the same shape as the Nail section (numbered step rail, editable setup, one result card
 * with assumptions always visible) and built from the same extracted parts in
 * {@code components/beauty/shared}. Two beauty verticals that behaved differently would read as two
 * products bolted together.</p>
 *
 * <p><strong>Two things this screen refuses to show.</strong> There are no star ratings, because neither
 * retailer publishes one and an invented score is the most persuasive lie available here. And there are no
 * "vegan"/"cruelty-free" badges, because those are regulated claims and the feeds carry no evidence for
 * them. Where a tag IS shown it says where it came from — a price band is marked as computed by us, not
 * asserted by the shop.</p>
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  buildKit, fetchCatalog, fetchLooks,
  type CatalogItem, type CatalogQuery, type CatalogResponse, type Facet,
  type LooksResponse, type LookSummary, type MakeupKit,
} from '../api/makeup';
import {
  AssumptionList, KitRow, KitTotals, ProductThumb, Section, eur, statusTone,
} from './beauty/shared';

const STEPS = ['Odaberi look', 'Podesi komplet', 'Kupovni popis'];

/** Sort options. Every one of them is implemented server-side — none is decorative. */
const SORTS: Array<[string, string]> = [
  ['relevance', 'Preporučeno'],
  ['price-asc', 'Cijena: niža prvo'],
  ['price-desc', 'Cijena: viša prvo'],
  ['name', 'Naziv (A–Ž)'],
];

export function MakeupLook() {
  const [looksData, setLooksData] = useState<LooksResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [lookKey, setLookKey] = useState<string | null>(null);
  const [budget, setBudget] = useState('');
  const [finish, setFinish] = useState('');
  const [owned, setOwned] = useState<string[]>([]);
  const [singleRetailer, setSingleRetailer] = useState<string | null>(null);
  const [preferCheapest, setPreferCheapest] = useState(false);
  const [pinned, setPinned] = useState<Record<string, string>>({});
  const [kit, setKit] = useState<MakeupKit | null>(null);
  const [storeOptions, setStoreOptions] = useState<string[]>([]);
  const [building, setBuilding] = useState(false);
  const [kitError, setKitError] = useState<string | null>(null);
  const resultRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    fetchLooks().then(setLooksData).catch(() => setLoadError(
      'Trenutačno se ne možemo spojiti na katalog. Osvježi stranicu za nekoliko trenutaka.'));
  }, []);

  const looks = looksData?.looks ?? [];
  const selected = looks.find((l) => l.key === lookKey) ?? null;
  const step = kit ? 3 : selected ? 2 : 1;

  /**
   * Build (or rebuild) the kit. Every refinement travels with the request rather than mutating a stored
   * kit, so what is on screen is always a pure function of the choices above it — the same decision the
   * nail vertical made.
   */
  const build = useCallback(async (overrides?: {
    pinnedBySlot?: Record<string, string>;
    preferCheapest?: boolean;
    singleRetailer?: string | null;
  }) => {
    if (!lookKey) return;
    setBuilding(true);
    setKitError(null);
    try {
      const parsedBudget = Number(budget.replace(',', '.'));
      const response = await buildKit({
        look: lookKey,
        budgetCents: Number.isFinite(parsedBudget) && parsedBudget > 0
          ? Math.round(parsedBudget * 100) : 0,
        finish: finish || undefined,
        ownedCategories: owned,
        pinnedBySlot: overrides?.pinnedBySlot ?? pinned,
        preferCheapest: overrides?.preferCheapest ?? preferCheapest,
        singleRetailer: overrides?.singleRetailer !== undefined
          ? overrides.singleRetailer : singleRetailer,
      });
      setKit(response.kit);
      setStoreOptions(response.singleStoreOptions);
      window.requestAnimationFrame(() =>
        resultRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
    } catch {
      setKitError('Nismo uspjeli složiti komplet. Pokušaj ponovno.');
    } finally {
      setBuilding(false);
    }
  }, [lookKey, budget, finish, owned, pinned, preferCheapest, singleRetailer]);

  const swap = (slot: string, externalId: string) => {
    const next = { ...pinned, [slot]: externalId };
    setPinned(next);
    void build({ pinnedBySlot: next });
  };

  const resetRefinements = () => {
    setPinned({});
    setPreferCheapest(false);
    setSingleRetailer(null);
    void build({ pinnedBySlot: {}, preferCheapest: false, singleRetailer: null });
  };

  const toggleOwned = (category: string) =>
    setOwned((prev) => prev.includes(category)
      ? prev.filter((c) => c !== category) : [...prev, category]);

  // The `nk` class is what carries the design tokens (nailkit.css scopes them to it), so the makeup
  // section inherits the approved palette, type scale and radii rather than redeclaring them and drifting.
  if (loadError) {
    return (
      <section className="nk mk">
        <div className="nk-shell"><p className="nk-error" role="alert">{loadError}</p></div>
      </section>
    );
  }

  return (
    <section className="nk mk" id="makeup-look">
    <div className="nk-shell mk-shell">
      <ol className="nk-steps" aria-label="Koraci">
        {STEPS.map((name, i) => (
          <li key={name} className={i + 1 === step ? 'is-now' : i + 1 < step ? 'is-done' : ''}>
            <span className="nk-step-dot">{i + 1}</span>
            <span className="nk-step-name">{name}</span>
          </li>
        ))}
      </ol>

      <header className="nk-hero">
        <h1>Složi komplet za svoj look.</h1>
        <p>Odaberi look, reci koliko želiš potrošiti i što već imaš — dobiješ točan popis proizvoda
          iz hrvatskih trgovina, s cijenama i poveznicama.</p>
      </header>

      {/* ------------------------------------------------------------------ 1. the looks */}
      <Section labelHr="Odaberi look" testid="look-picker">
        <div className="mk-looks">
          {looks.map((look) => (
            <LookCard key={look.key} look={look} selected={look.key === lookKey}
              onSelect={() => { setLookKey(look.key); setKit(null); setPinned({}); }} />
          ))}
        </div>
      </Section>

      {/* --------------------------------------------------------------- 2. the setup */}
      {selected && (
        <Section labelHr={`Podesi — ${selected.labelHr}`} testid="makeup-setup">
          <div className="mk-setup">
            <div className="mk-field">
              <label htmlFor="mk-budget">Budžet (€)</label>
              <input id="mk-budget" className="nk-input nk-input-num" inputMode="decimal"
                value={budget} onChange={(e) => setBudget(e.target.value)}
                placeholder="npr. 40" data-testid="mk-budget" />
              <span className="mk-hint">Ostavi prazno ako nemaš gornju granicu.</span>
            </div>

            <div className="mk-field">
              <label htmlFor="mk-finish">Završnica</label>
              <select id="mk-finish" className="nk-input" value={finish}
                onChange={(e) => setFinish(e.target.value)} data-testid="mk-finish">
                <option value="">bez posebne želje</option>
                <option value="matte">mat</option>
                <option value="satin">saten</option>
                <option value="gloss">sjajno</option>
                <option value="shimmer">šimer</option>
                <option value="cream">kremasto</option>
              </select>
              <span className="mk-hint">Primjenjuje se samo ondje gdje trgovac navodi završnicu.</span>
            </div>

            <div className="mk-field mk-field-wide">
              <span className="mk-field-label">Što već imaš?</span>
              <div className="nk-chips" data-testid="mk-owned">
                {selected.slots.map((slot) => (
                  <button key={slot.category} type="button"
                    className={`nk-chip${owned.includes(slot.category) ? ' is-on' : ''}`}
                    aria-pressed={owned.includes(slot.category)}
                    onClick={() => toggleOwned(slot.category)}
                    data-testid={`mk-own-${slot.category}`}>
                    {slot.labelHr}
                  </button>
                ))}
              </div>
              <span className="mk-hint">Označeno se ne kupuje i ne ulazi u iznos.</span>
            </div>
          </div>

          <button type="button" className="nk-btn" onClick={() => void build()}
            disabled={building} data-testid="mk-build">
            {building ? 'Slažem…' : 'Složi komplet'}
          </button>
          {kitError && <p className="nk-error" role="alert">{kitError}</p>}
        </Section>
      )}

      {/* -------------------------------------------------------------- 3. the kit */}
      <div ref={resultRef}>
        {kit && (
          <Section labelHr="Tvoj komplet" testid="makeup-kit">
            <div className={`nk-status ${statusTone(kit.status)}`} data-testid="mk-kit-status">
              <strong>{kit.statusLabelHr}</strong>
              <p>{kit.statusExplanationHr}</p>
            </div>

            {kit.missingRequiredSlots.length > 0 && (
              <div className="nk-owned nk-missing" data-testid="mk-kit-missing">
                <p className="nk-section-label">Nedostaje u katalogu:</p>
                <ul>{kit.missingRequiredSlots.map((m) => <li key={m}>{m}</li>)}</ul>
              </div>
            )}

            <div className="mk-refine" data-testid="mk-refine">
              <button type="button" className="nk-btn nk-btn-pill nk-btn-sm"
                aria-pressed={preferCheapest}
                onClick={() => { setPreferCheapest(!preferCheapest); void build({ preferCheapest: !preferCheapest }); }}
                data-testid="mk-cheaper">Najjeftinije</button>
              {storeOptions.map((store) => (
                <button key={store} type="button" className="nk-btn nk-btn-pill nk-btn-sm"
                  aria-pressed={singleRetailer === store}
                  onClick={() => {
                    const next = singleRetailer === store ? null : store;
                    setSingleRetailer(next);
                    void build({ singleRetailer: next });
                  }}>Samo {store}</button>
              ))}
              {(preferCheapest || singleRetailer || Object.keys(pinned).length > 0) && (
                <button type="button" className="nk-btn nk-btn-ghost nk-btn-sm"
                  onClick={resetRefinements} data-testid="mk-reset">Poništi izmjene</button>
              )}
            </div>

            <div className="nk-kit-list">
              {kit.items.map((item) => <KitRow key={item.slot} item={item} onSwap={swap} />)}
            </div>

            {kit.items.length > 0 && (
              <KitTotals essentialCents={kit.essentialTotalCents} optionalCents={kit.optionalTotalCents}
                totalCents={kit.totalCents} budgetCents={kit.budgetCents}
                remainingCents={kit.remainingCents} />
            )}

            {kit.ownedItems.length > 0 && (
              <div className="nk-owned" data-testid="mk-kit-owned">
                <p className="nk-section-label">Već imaš — nije uračunato</p>
                <ul>{kit.ownedItems.map((o) => <li key={o.slot}>{o.slotLabelHr}</li>)}</ul>
              </div>
            )}

            {kit.applicationStepsHr.length > 0 && (
              <div className="mk-steps-block" data-testid="mk-application-steps">
                <p className="nk-section-label">Redoslijed nanošenja</p>
                <ol className="nk-steps-list">
                  {kit.applicationStepsHr.map((s, i) => <li key={i}>{s}</li>)}
                </ol>
              </div>
            )}

            <AssumptionList items={kit.assumptions} headingHr="Pretpostavke — provjeri"
              testid="mk-kit-assumptions" />

            <div className="mk-steps-block">
              <p className="nk-section-label">Dobro je znati</p>
              <ul className="nk-steps-list">
                {kit.careNotesHr.map((n, i) => <li key={i}>{n}</li>)}
              </ul>
            </div>

            <p className="nk-fineprint">{kit.catalogProvenanceHr}</p>
          </Section>
        )}
      </div>

      {/* ------------------------------------------------------------ the catalog */}
      <CatalogBrowser provenanceHr={looksData?.catalogProvenanceHr ?? ''} />
    </div>
    </section>
  );
}

/** One look, with a real floor price computed from the real cheapest product in every required slot. */
function LookCard({ look, selected, onSelect }: {
  look: LookSummary;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button type="button" className={`mk-look${selected ? ' is-on' : ''}`} onClick={onSelect}
      aria-pressed={selected} data-testid={`look-${look.key}`}>
      <span className="mk-look-name">{look.labelHr}</span>
      <span className="mk-look-tagline">{look.taglineHr}</span>
      <span className="mk-look-desc">{look.descriptionHr}</span>
      <span className="mk-look-meta">
        <span>{look.requiredCount} obavezno{look.optionalCount > 0 ? ` · ${look.optionalCount} opcionalno` : ''}</span>
        {look.fromCents != null && <span className="mk-look-price">od {eur(look.fromCents)}</span>}
      </span>
    </button>
  );
}

// -------------------------------------------------------------------------------- catalog browser

const EMPTY_QUERY: CatalogQuery = { sort: 'relevance', limit: 48 };

/**
 * The browsable catalog. Filtering and faceting happen on the server so the counts on the chips and the
 * rows in the list can never disagree — a facet that says "mat (28)" and then returns 11 results is how a
 * filter starts lying.
 */
function CatalogBrowser({ provenanceHr }: { provenanceHr: string }) {
  const [query, setQuery] = useState<CatalogQuery>(EMPTY_QUERY);
  const [text, setText] = useState('');
  const [data, setData] = useState<CatalogResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);

  // Debounced so typing does not fire a request per keystroke, and guarded so a slow earlier response
  // cannot overwrite a newer one — type "mat", then "matte", and without the guard the shorter query's
  // larger result set can land last and silently contradict what is in the box.
  useEffect(() => {
    if (!open) return undefined;
    let live = true;
    const handle = window.setTimeout(() => {
      setLoading(true);
      fetchCatalog({ ...query, q: text })
        .then((r) => { if (live) setData(r); })
        .catch(() => { if (live) setData(null); })
        .finally(() => { if (live) setLoading(false); });
    }, 220);
    return () => { live = false; window.clearTimeout(handle); };
  }, [query, text, open]);

  const toggle = (key: keyof CatalogQuery, value: string) => setQuery((prev) => {
    const current = (prev[key] as string[] | undefined) ?? [];
    const next = current.includes(value) ? current.filter((v) => v !== value) : [...current, value];
    return { ...prev, [key]: next.length ? next : undefined };
  });

  const activeCount = useMemo(() => (['category', 'brand', 'finish', 'priceBand', 'shade', 'retailer'] as const)
    .reduce((n, key) => n + ((query[key] as string[] | undefined)?.length ?? 0), 0)
    + (query.maxPriceCents != null ? 1 : 0) + (query.inStockOnly ? 1 : 0), [query]);

  return (
    <Section labelHr="Cijeli katalog" testid="makeup-catalog">
      {!open ? (
        <button type="button" className="nk-btn nk-btn-ghost" onClick={() => setOpen(true)}
          data-testid="mk-open-catalog">Pretraži sve proizvode</button>
      ) : (
        <>
          <div className="mk-search">
            <input className="nk-input" type="search" value={text} placeholder="Traži: maskara, bordo, mat…"
              onChange={(e) => setText(e.target.value)} aria-label="Pretraži katalog"
              data-testid="mk-search" />
            <select className="nk-input" value={query.sort ?? 'relevance'} aria-label="Poredaj"
              onChange={(e) => setQuery((p) => ({ ...p, sort: e.target.value }))} data-testid="mk-sort">
              {SORTS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>

          {data && (
            <div className="mk-filters" data-testid="mk-filters">
              <FacetGroup title="Kategorija" facets={data.categoryFacets}
                selected={query.category} onToggle={(v) => toggle('category', v)} testid="mk-f-category" />
              <FacetGroup title="Cjenovni razred" facets={data.priceBandFacets}
                selected={query.priceBand} onToggle={(v) => toggle('priceBand', v)} testid="mk-f-band"
                noteHr="Izračunato iz stvarnih cijena u kategoriji — nije ocjena kvalitete." />
              <FacetGroup title="Završnica" facets={data.finishFacets}
                selected={query.finish} onToggle={(v) => toggle('finish', v)} testid="mk-f-finish"
                noteHr="Samo ondje gdje ju je trgovac naveo." />
              <FacetGroup title="Nijansa" facets={data.shadeFacets}
                selected={query.shade} onToggle={(v) => toggle('shade', v)} testid="mk-f-shade"
                noteHr="Samo imenovane nijanse; brojevi nijansi ne govore boju." />
              <FacetGroup title="Brend" facets={data.brandFacets.slice(0, 10)}
                selected={query.brand} onToggle={(v) => toggle('brand', v)} testid="mk-f-brand" />
              <FacetGroup title="Trgovina" facets={data.retailerFacets}
                selected={query.retailer} onToggle={(v) => toggle('retailer', v)} testid="mk-f-retailer" />

              <div className="mk-facet">
                <p className="mk-facet-title">Najviše {eur(query.maxPriceCents ?? data.maxPriceCents)}</p>
                <input type="range" min={data.minPriceCents} max={data.maxPriceCents} step={50}
                  value={query.maxPriceCents ?? data.maxPriceCents}
                  onChange={(e) => setQuery((p) => ({ ...p, maxPriceCents: Number(e.target.value) }))}
                  aria-label="Najviša cijena" data-testid="mk-f-price" />
              </div>
            </div>
          )}

          <div className="mk-result-bar">
            <span data-testid="mk-result-count">
              {loading ? 'Tražim…' : `${data?.total ?? 0} proizvoda`}
              {data && data.shown < data.total ? ` · prikazano ${data.shown}` : ''}
            </span>
            {activeCount > 0 && (
              <button type="button" className="nk-btn nk-btn-ghost nk-btn-sm"
                onClick={() => { setQuery(EMPTY_QUERY); setText(''); }}
                data-testid="mk-clear-filters">Poništi filtre ({activeCount})</button>
            )}
          </div>

          {data?.noResultsHintHr && (
            <p className="mk-no-results" data-testid="mk-no-results">{data.noResultsHintHr}</p>
          )}

          <div className="mk-grid" data-testid="mk-catalog-grid">
            {(data?.items ?? []).map((item) => <CatalogCard key={item.externalId} item={item} />)}
          </div>

          <p className="nk-fineprint">{provenanceHr}</p>
        </>
      )}
    </Section>
  );
}

function FacetGroup({ title, facets, selected, onToggle, testid, noteHr }: {
  title: string;
  facets: Facet[];
  selected?: string[];
  onToggle: (value: string) => void;
  testid: string;
  noteHr?: string;
}) {
  if (!facets.length) return null;
  return (
    <div className="mk-facet" data-testid={testid}>
      <p className="mk-facet-title">{title}</p>
      <div className="nk-chips">
        {facets.map((f) => (
          <button key={f.value} type="button"
            className={`nk-chip${selected?.includes(f.value) ? ' is-on' : ''}`}
            aria-pressed={Boolean(selected?.includes(f.value))}
            onClick={() => onToggle(f.value)}>
            {f.labelHr} <em>{f.count}</em>
          </button>
        ))}
      </div>
      {noteHr && <span className="mk-hint">{noteHr}</span>}
    </div>
  );
}

/** One catalog product. Shows only what the retailer published, and says which tags we computed. */
function CatalogCard({ item }: { item: CatalogItem }) {
  const named = item.shades.filter((s) => s.colorFamily);
  return (
    <article className="mk-card" data-testid={`mk-product-${item.externalId}`}>
      <ProductThumb src={item.imageUrl} alt={item.name} size="lg" />
      <div className="mk-card-body">
        <p className="mk-card-cat">{item.categoryLabelHr}</p>
        <h3 className="mk-card-name">
          <a href={item.productUrl} target="_blank" rel="noopener noreferrer nofollow">{item.name}</a>
        </h3>
        {item.brand && <p className="mk-card-brand">{item.brand}</p>}
        {item.description && <p className="mk-card-desc">{item.description}</p>}

        {item.shadeCount > 0 && (
          <p className="mk-card-shades">
            {item.shadeCount} {item.shadeCount === 1 ? 'nijansa' : 'nijansi'}
            {named.length > 0 && <>: {named.slice(0, 4).map((s) => s.name).join(', ')}</>}
            {item.shadeNeedsSwatchCheck && <em> — trgovac ih numerira, provjeri swatch</em>}
          </p>
        )}

        <div className="mk-card-tags">
          {item.tags.map((t) => (
            <span key={t.tag} className={`mk-tag mk-tag-${t.provenance}`} title={t.basisHr}>
              {t.tag}{t.provenance === 'derived' ? ' *' : ''}
            </span>
          ))}
        </div>

        {item.usedForHr && <p className="mk-card-usedfor">{item.usedForHr}</p>}

        <div className="mk-card-foot">
          <span className="mk-card-price">{eur(item.priceCents)}</span>
          <span className="mk-card-shop">{item.retailer}</span>
        </div>
        {item.stockUnverified && (
          <p className="mk-card-note">Trgovac ne objavljuje zalihu — provjeri prije narudžbe.</p>
        )}
      </div>
    </article>
  );
}
