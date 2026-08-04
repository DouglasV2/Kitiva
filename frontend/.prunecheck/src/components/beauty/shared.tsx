/**
 * The pieces both beauty verticals need.
 *
 * <p>Extracted from NailLook rather than written fresh: the nail slice had already answered these
 * questions (how an empty product image should look, how an assumption must be printed, how a swap row
 * behaves) and answered them under review. Copying them into a second vertical would have forked those
 * decisions on day one — the assumption list was already triplicated inside NailLook alone, and a makeup
 * section would have made it six copies drifting apart.</p>
 *
 * <p>Nothing here knows about nails or makeup. Anything that does belongs in the vertical's own file.</p>
 */
import { useState, type ReactNode } from 'react';

/** Money, as Croatian writes it. One implementation, so two verticals cannot round differently. */
export const eur = (cents: number) => `${(cents / 100).toFixed(2).replace('.', ',')} €`;

/**
 * A signed delta, for swap rows. Uses a real minus sign (U+2212) rather than a hyphen, because at small
 * sizes a hyphen next to a digit reads as a dash in the product name.
 */
export const eurDelta = (cents: number) =>
  cents === 0 ? '±0,00 €' : `${cents > 0 ? '+' : '−'}${eur(Math.abs(cents))}`;

/** Shared assumption shape — the same record the backend sends from both verticals. */
export interface AssumptionLike {
  field: string;
  assumed: string;
  labelHr: string;
  reasonHr: string;
}

/**
 * What an assumption should print. `assumed` is the machine value, and rendering it is how "glossy",
 * "mirrored" and a bare "prstenjak" once ended up as headings in a Croatian consumer product.
 */
export const assumptionLabel = (a: AssumptionLike) => a.labelHr || a.field;

/**
 * The assumptions, listed open.
 *
 * <p>Never behind a count and never behind a disclosure triangle: an assumption the user does not read is
 * the same as one we never made. This is the product's central promise rendered as a list.</p>
 */
export function AssumptionList({ items, headingHr, testid }: {
  items: AssumptionLike[];
  headingHr: string;
  testid: string;
}) {
  if (!items.length) return null;
  return (
    <div className="nk-assume-list" data-testid={testid}>
      <p className="nk-section-label">{headingHr}</p>
      <ul className="nk-assume-items">
        {items.map((a, i) => (
          <li key={`${a.field}-${i}`}><strong>{assumptionLabel(a)}</strong> — {a.reasonHr}</li>
        ))}
      </ul>
    </div>
  );
}

/**
 * A product thumbnail, or an honest admission that there isn't one.
 *
 * <p>The rule the placeholder exists to keep: never show a picture of a DIFFERENT product where this one's
 * photo would go. A generic bottle beside a real name and a real price reads as "this is what you are
 * buying", and it would be the one fabricated thing in a catalog built on not fabricating anything.</p>
 *
 * <p>The copy says "not available" rather than "not published", because both are real and they are not the
 * same thing: some retailers publish no image, while beauty-shop.hr publishes image URLs and then answers
 * 415 for them from anywhere but its own pages.</p>
 */
export function ProductThumb({ src, alt, size = 'md' }: {
  src?: string | null;
  alt: string;
  size?: 'md' | 'sm' | 'lg';
}) {
  const [broken, setBroken] = useState(false);
  const cls = `nk-thumb nk-thumb-${size}`;
  if (!src || broken) {
    return (
      <span className={`${cls} is-empty`} role="img" aria-label={`${alt} — nema dostupne slike proizvoda`}
        title="Slika ovog proizvoda nije dostupna" data-testid="thumb-placeholder">
        <NoImageIcon />
        <em>bez slike</em>
      </span>
    );
  }
  return (
    <img className={cls} src={src} alt={alt} loading="lazy" decoding="async" referrerPolicy="no-referrer"
      onError={() => setBroken(true)} data-testid="kit-thumb" />
  );
}

function NoImageIcon() {
  return (
    <svg width={15} height={15} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.35}
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d="M2.4 3.4h11.2v9.2H2.4z" />
      <path d="M2.4 10.2l3.2-3 2.6 2.4 2.4-2.2 3 2.8" />
      <path d="M1.4 1.6l13.2 12.8" />
    </svg>
  );
}

/** The five kit outcomes, and which visual tone each one gets. Shared so both verticals agree. */
export function statusTone(status: string | null | undefined) {
  if (status === 'COMPLETE') return 'nk-status-ok';
  if (status === 'SAFETY_BLOCKED' || status === 'INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE') return 'nk-status-stop';
  return 'nk-status-warn';
}

/** Enough of a kit row for the shared renderer. Both verticals' KitItem structurally satisfy this. */
export interface KitRowItem {
  slot: string;
  slotLabelHr: string;
  essential: boolean;
  externalId: string | null;
  name: string;
  brand?: string | null;
  shadeName: string | null;
  retailer: string | null;
  priceCents: number;
  productUrl: string | null;
  imageUrl: string | null;
  swatchImageUrl: string | null;
  whyHr: string;
  noteHr: string | null;
  ownedAlready: boolean;
  alternatives: KitRowAlternative[];
}

export interface KitRowAlternative {
  externalId: string;
  name: string;
  brand?: string | null;
  shadeName: string | null;
  retailer: string;
  priceCents: number;
  priceDeltaCents: number;
  productUrl: string;
  imageUrl?: string | null;
  swatchImageUrl?: string | null;
}

/**
 * One purchasable line, with its swaps.
 *
 * <p>The swap list is a `<details>` rather than a modal so it costs nothing when unused and needs no
 * focus management; and it only ever contains products from the SAME slot, which is what makes "Uzmi"
 * safe — a swap can change the price but can never break completeness.</p>
 */
export function KitRow({ item, onSwap }: {
  item: KitRowItem;
  onSwap: (slot: string, externalId: string) => void;
}) {
  return (
    <div className="nk-kit-row" data-testid={`kit-row-${item.slot}`}>
      <div className="nk-kit-slot">{item.slotLabelHr}</div>
      <div className="nk-kit-main">
        <ProductThumb src={item.imageUrl || item.swatchImageUrl} alt={item.name} />
        <div className="nk-kit-body">
          <div className="nk-kit-name">
            {item.productUrl ? (
              <a href={item.productUrl} target="_blank" rel="noopener noreferrer nofollow">{item.name}</a>
            ) : item.name}
            {item.shadeName && <span className="nk-kit-shade"> · {item.shadeName}</span>}
          </div>
          {item.retailer && <div className="nk-kit-retailer">{item.retailer}</div>}
          <div className="nk-kit-why">{item.whyHr}</div>
          {item.noteHr && <div className="nk-kit-note">{item.noteHr}</div>}
          {item.alternatives.length > 0 && (
            <details className="nk-alts">
              <summary data-testid={`swap-${item.slot}`}>Zamijeni ({item.alternatives.length})</summary>
              <ul>
                {item.alternatives.map((alt) => (
                  <li key={alt.externalId} className="nk-alt">
                    <ProductThumb src={alt.imageUrl || alt.swatchImageUrl} alt={alt.name} size="sm" />
                    <span className="nk-alt-name">
                      {alt.name}{alt.shadeName ? ` · ${alt.shadeName}` : ''}
                      <em> {alt.retailer}</em>
                    </span>
                    <span className="nk-alt-delta">{eurDelta(alt.priceDeltaCents)}</span>
                    <button type="button" className="nk-btn nk-btn-pill nk-btn-sm"
                      onClick={() => onSwap(item.slot, alt.externalId)}>Uzmi</button>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      </div>
      <div className="nk-kit-price">
        <span>{eur(item.priceCents)}</span>
        <span className={item.essential ? 'nk-tag-essential' : 'nk-tag-optional'}>
          {item.essential ? 'obavezno' : 'opcionalno'}
        </span>
      </div>
    </div>
  );
}

/** Essential / optional / total, plus the budget line when one was given. */
export function KitTotals({ essentialCents, optionalCents, totalCents, budgetCents, remainingCents }: {
  essentialCents: number;
  optionalCents: number;
  totalCents: number;
  budgetCents: number | null;
  remainingCents: number | null;
}) {
  // Class names match the nail vertical's existing totals block exactly, so both verticals inherit the
  // same typography, tabular numerals and grand-total rule rather than growing a second set of styles.
  return (
    <div className="nk-totals" data-testid="kit-totals">
      <div className="nk-total-line"><span>Obavezno</span><span>{eur(essentialCents)}</span></div>
      <div className="nk-total-line"><span>Opcionalno</span><span>{eur(optionalCents)}</span></div>
      <div className="nk-total-line nk-total-grand">
        <span>Ukupno</span><span data-testid="kit-total">{eur(totalCents)}</span>
      </div>
      {budgetCents != null && (
        <div className="nk-total-line">
          <span>Budžet {eur(budgetCents)}</span>
          <span className={(remainingCents ?? 0) < 0 ? 'nk-over' : 'nk-under'} data-testid="kit-remaining">
            {(remainingCents ?? 0) < 0 ? 'nedostaje ' : 'ostaje '}{eur(Math.abs(remainingCents ?? 0))}
          </span>
        </div>
      )}
    </div>
  );
}

/** A labelled block with a section heading — the shape both verticals lay result sections out in. */
export function Section({ labelHr, children, testid }: {
  labelHr: string;
  children: ReactNode;
  testid?: string;
}) {
  return (
    <section className="mk-block" data-testid={testid}>
      <p className="nk-section-label">{labelHr}</p>
      {children}
    </section>
  );
}
