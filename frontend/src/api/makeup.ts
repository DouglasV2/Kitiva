// Makeup endpoints. Same conventions as api/nail.ts and api/client.ts — one base URL, JSON in and out —
// so the two beauty verticals talk to the backend the same way.
import type { Assumption } from '../types/beauty';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

// ------------------------------------------------------------------------------------------- looks

export interface LookSlot {
  category: string;
  labelHr: string;
  required: boolean;
  whyHr: string;
  /** How many products the catalog can actually offer for this step. 0 = the look cannot be completed. */
  availableCount: number;
}

export interface LookSummary {
  key: string;
  labelHr: string;
  taglineHr: string;
  descriptionHr: string;
  requiredCount: number;
  optionalCount: number;
  slots: LookSlot[];
  /** Cheapest possible total for the REQUIRED slots, from real prices. Null when a slot is unfillable. */
  fromCents: number | null;
}

export interface MakeupCategory {
  key: string;
  labelHr: string;
  order: number;
  usedForHr: string;
}

export interface LooksResponse {
  looks: LookSummary[];
  categories: MakeupCategory[];
  retailers: string[];
  brands: string[];
  catalogProvenanceHr: string;
}

// ----------------------------------------------------------------------------------------- catalog

export interface Shade {
  name: string;
  fullTitle: string | null;
  /** Null unless the RETAILER named the colour. A shade number resolves to null on purpose. */
  colorFamily: string | null;
  price: number;
  inStock: boolean;
  swatchImageUrl: string | null;
}

/** A tag plus where it came from. `derived` is computed by us from published data; there is no third kind. */
export interface ProductTag {
  tag: string;
  provenance: 'published' | 'derived';
  basisHr: string;
}

export interface CatalogItem {
  externalId: string;
  name: string;
  brand: string;
  category: string;
  categoryLabelHr: string;
  subcategoryHr: string | null;
  description: string | null;
  priceCents: number;
  priceBand: string;
  shades: Shade[];
  shadeCount: number;
  finish: string | null;
  imageUrl: string | null;
  productUrl: string;
  retailer: string;
  inStock: boolean;
  stockUnverified: boolean;
  shadeNeedsSwatchCheck: boolean;
  /** Always null — no retailer in this catalog publishes a review score, and we do not invent one. */
  rating: number | null;
  tags: ProductTag[];
  usedForHr: string | null;
}

export interface Facet { value: string; labelHr: string; count: number }

export interface CatalogResponse {
  items: CatalogItem[];
  total: number;
  shown: number;
  categoryFacets: Facet[];
  brandFacets: Facet[];
  finishFacets: Facet[];
  priceBandFacets: Facet[];
  shadeFacets: Facet[];
  retailerFacets: Facet[];
  minPriceCents: number;
  maxPriceCents: number;
  /** What to say when nothing matched — names the likely culprit instead of just "0". */
  noResultsHintHr: string | null;
}

export interface CatalogQuery {
  q?: string;
  category?: string[];
  brand?: string[];
  finish?: string[];
  priceBand?: string[];
  shade?: string[];
  retailer?: string[];
  minPriceCents?: number;
  maxPriceCents?: number;
  inStockOnly?: boolean;
  sort?: string;
  limit?: number;
}

// --------------------------------------------------------------------------------------------- kit

export interface MakeupAlternative {
  externalId: string;
  name: string;
  brand: string | null;
  shadeName: string | null;
  retailer: string;
  priceCents: number;
  priceDeltaCents: number;
  productUrl: string;
  imageUrl: string | null;
}

export interface MakeupKitItem {
  slot: string;
  slotLabelHr: string;
  essential: boolean;
  externalId: string | null;
  name: string;
  brand: string | null;
  shadeName: string | null;
  retailer: string | null;
  priceCents: number;
  productUrl: string | null;
  imageUrl: string | null;
  swatchImageUrl: string | null;
  whyHr: string;
  noteHr: string | null;
  ownedAlready: boolean;
  alternatives: MakeupAlternative[];
}

export interface MakeupKit {
  lookKey: string;
  lookLabelHr: string;
  status: string;
  statusLabelHr: string;
  statusExplanationHr: string;
  items: MakeupKitItem[];
  ownedItems: MakeupKitItem[];
  missingRequiredSlots: string[];
  essentialTotalCents: number;
  optionalTotalCents: number;
  totalCents: number;
  budgetCents: number | null;
  remainingCents: number | null;
  retailerCount: number;
  assumptions: Assumption[];
  /** The running order — the kit doubles as the instructions. */
  applicationStepsHr: string[];
  careNotesHr: string[];
  catalogProvenanceHr: string;
}

export interface KitRequest {
  look: string;
  budgetCents?: number;
  finish?: string;
  skinType?: string;
  ownedCategories?: string[];
  missingCategories?: string[];
  excludedCategories?: string[];
  brandPreferences?: string[];
  pinnedBySlot?: Record<string, string>;
  preferCheapest?: boolean;
  singleRetailer?: string | null;
}

export interface KitResponse {
  kit: MakeupKit;
  singleStoreOptions: string[];
}

// ------------------------------------------------------------------------------------------ calls

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`${path} -> HTTP ${res.status}`);
  return res.json() as Promise<T>;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${path} -> HTTP ${res.status}`);
  return res.json() as Promise<T>;
}

export const fetchLooks = () => getJson<LooksResponse>('/api/makeup/looks');

/** Builds the query string from only the filters that are actually set, so the URL stays readable. */
export function catalogQueryString(query: CatalogQuery): string {
  const params = new URLSearchParams();
  const addAll = (key: string, values?: string[]) =>
    (values ?? []).forEach((v) => params.append(key, v));
  if (query.q?.trim()) params.set('q', query.q.trim());
  addAll('category', query.category);
  addAll('brand', query.brand);
  addAll('finish', query.finish);
  addAll('priceBand', query.priceBand);
  addAll('shade', query.shade);
  addAll('retailer', query.retailer);
  if (query.minPriceCents != null) params.set('minPriceCents', String(query.minPriceCents));
  if (query.maxPriceCents != null) params.set('maxPriceCents', String(query.maxPriceCents));
  if (query.inStockOnly) params.set('inStockOnly', 'true');
  if (query.sort) params.set('sort', query.sort);
  if (query.limit) params.set('limit', String(query.limit));
  return params.toString();
}

export const fetchCatalog = (query: CatalogQuery) =>
  getJson<CatalogResponse>(`/api/makeup/catalog?${catalogQueryString(query)}`);

export const buildKit = (request: KitRequest) => postJson<KitResponse>('/api/makeup/kit', request);
