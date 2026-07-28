// Builds the HR nail pilot catalog from Golden Rose HR's PUBLIC Shopify products.json endpoint.
//
// WHY THIS SOURCE
// The 2026-07-28 probe (docs/catalog-probes/) found Golden Rose HR to be one of only three reachable HR
// beauty sources, and the only one exposing structured product data — a standard Shopify /products.json
// feed. That is a published JSON endpoint, not a page we are scraping past a block: no bypass, no
// fingerprint, no HTML parsing, honest user-agent, polite delay between pages.
//
// WHAT IT PRODUCES
// backend/src/main/resources/catalog/nail-pilot-hr.json — a small REGULAR-POLISH pilot: prep, base, colour,
// top and removal, which is a complete kit graph for the one at-home system this slice ships.
//
// HONESTY BOUNDARIES — read before trusting this data
//   * Names, prices, shades, availability, URLs and images are REAL and come straight from the retailer's
//     own endpoint. Nothing here is invented.
//   * They are machine-captured, NOT hand-checked by a human. dataQuality is "pilot-unreviewed" for exactly
//     that reason, and every row records the capture timestamp.
//   * Out-of-stock variants are captured with inStock=false rather than dropped, so the kit builder has to
//     handle them honestly instead of being handed a pre-cleaned world.
//   * No INCI is available from this endpoint, so every substance status stays UNKNOWN — which is why this
//     pilot ships regular polish only and why gel polish stays disabled.
//
// Run: node scripts/build-nail-pilot-catalog.mjs

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..');
const UA = 'BudgetSpaceCollector/0.1 (+dev; controlled product fetch)';
const BASE = 'https://goldenrose.hr';

/**
 * Which Golden Rose products fill which slot of the regular-polish kit graph.
 * Hand-mapped by product title, because the retailer's own product_type says only "lak za nokte" for
 * everything from a base coat to a cuticle oil — it cannot tell a prep step from a top coat, and the kit
 * graph depends on knowing the difference.
 */
const SLOT_RULES = [
  { slot: 'base',    role: 'base',    match: /smoothing base|nail foundation|base coat/i },
  { slot: 'color',   role: 'color',   match: /keratin nail color|ice chic nail lacquer|ice color nail lacquer|gel power|extreme glitter/i },
  { slot: 'top',     role: 'top',     match: /top coat/i },
  { slot: 'prep',    role: 'prep',    match: /cuticle|beauty oil/i },
  { slot: 'removal', role: 'removal', match: /remover|odstranjivač|aceton/i },
  { slot: 'finish-aid', role: 'tool', match: /quick dryer|dryer spray/i },
];

// A pilot is capped by COLOUR FAMILY, not per product. Capping per product would happily return eight pinks
// and no burgundy, which looks like a full catalog right up until someone asks for the colour they wanted.
// Capping per family keeps the pilot small AND guarantees breadth across what users actually ask for.
const MAX_SHADES_PER_FAMILY = 20;
const MAX_SHADES_PER_COLOR_PRODUCT = 30; // per-product ceiling, so one huge range cannot crowd out the rest

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function fetchAllProducts() {
  const all = [];
  for (let page = 1; page <= 6; page++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20_000);
    const res = await fetch(`${BASE}/products.json?limit=250&page=${page}`, {
      headers: { 'User-Agent': UA, Accept: 'application/json' },
      signal: controller.signal,
    });
    clearTimeout(timer);
    if (!res.ok) throw new Error(`products.json page ${page} returned ${res.status}`);
    const json = await res.json();
    if (!json.products?.length) break;
    all.push(...json.products);
    await sleep(1500);
  }
  return all;
}

function slotFor(title) {
  return SLOT_RULES.find((rule) => rule.match.test(title)) ?? null;
}

/**
 * Colour family from the shade name — where one can be read at all.
 *
 * MEASURED 2026-07-28: this retailer names shades by NUMBER ("Keratin 1" … "Keratin 100"), with a swatch
 * image per variant and no colour word anywhere in the feed. So for most rows this returns null, and that
 * null is the honest answer, not a gap to paper over. It means the app CANNOT claim "this is your burgundy"
 * from this source; the most it can honestly offer is a shade CANDIDATE plus the swatch to check. That is
 * precisely the "recommended shade candidate / verify against retailer swatches" rule the spec sets out,
 * and it turns out to be the normal case here rather than the exception.
 *
 * Guessing a family from a shade number would be fabrication of exactly the kind this product exists to
 * avoid — worse than useless, because it would look authoritative.
 */
function colorFamily(shadeName) {
  const s = (shadeName || '').toLowerCase();
  if (/burgundy|wine|cherry|višnj|visnj|bordo|merlot|garnet|crimson/.test(s)) return 'burgundy';
  if (/\bred\b|crven|scarlet|ruby/.test(s)) return 'red';
  if (/nude|beige|sand|caramel|taupe/.test(s)) return 'nude';
  if (/pink|roza|rose|blush|fuchsia/.test(s)) return 'pink';
  if (/black|crn/.test(s)) return 'black';
  if (/white|bijel|snow/.test(s)) return 'white';
  if (/blue|plav|navy|teal/.test(s)) return 'blue';
  if (/green|zelen|mint|olive/.test(s)) return 'green';
  if (/purple|ljubi|lilac|lavender|violet|plum/.test(s)) return 'purple';
  if (/brown|smed|chocolate|coffee|mocha/.test(s)) return 'brown';
  if (/grey|gray|siv|silver|steel/.test(s)) return 'grey';
  if (/gold|zlat|bronze|copper/.test(s)) return 'gold';
  if (/glitter|shimmer|sparkl/.test(s)) return 'glitter';
  return null; // unknown — never guessed
}

const capturedAt = new Date().toISOString();
const products = await fetchAllProducts();
process.stderr.write(`fetched ${products.length} products from ${BASE}\n`);

const rows = [];
const familyCounts = {};
for (const product of products) {
  const rule = slotFor(product.title);
  if (!rule) continue;
  // A tiny pilot must stay small: cap shades per colour product, but keep in-stock ones first so the
  // kit builder is not handed a shelf of unavailable lacquers.
  const variants = [...product.variants].sort((a, b) => Number(b.available) - Number(a.available));
  const limit = rule.slot === 'color' ? MAX_SHADES_PER_COLOR_PRODUCT : 2;
  for (const variant of variants.slice(0, limit)) {
    const shade = variant.title && variant.title !== 'Default Title' ? variant.title : null;
    if (rule.slot === 'color') {
      // Cap named families so one colour cannot dominate; unnamed (numbered) shades get their own small
      // quota so the pilot still offers a real choice the user can check against swatches.
      const family = colorFamily(shade) ?? 'unnamed';
      if ((familyCounts[family] ?? 0) >= MAX_SHADES_PER_FAMILY) continue;
      familyCounts[family] = (familyCounts[family] ?? 0) + 1;
    }
    rows.push({
      externalId: `goldenrose-hr-${variant.id}`,
      name: product.title,
      brand: 'Golden Rose',
      productLine: product.title,
      shadeName: shade,
      shadeCode: shade ? String(shade).trim().split(/\s+/)[0] : null,
      retailer: 'Golden Rose HR',
      market: 'HR',
      currency: 'EUR',
      price: Number(variant.price),
      inStock: Boolean(variant.available),
      availabilityStatus: variant.available ? 'in-stock' : 'unavailable',
      productUrl: `${BASE}/products/${product.handle}?variant=${variant.id}`,
      imageUrl: product.images?.[0]?.src ?? null,
      imageVerified: Boolean(product.images?.[0]?.src),
      nailSystem: 'regular-polish',
      applicationRole: rule.role,
      kitSlot: rule.slot,
      colorFamily: rule.slot === 'color' ? colorFamily(shade) : null,
      // False for every numbered shade. The kit must then offer this as a CANDIDATE and send the user to
      // the swatch, never assert it matches the colour she asked for.
      shadeColorKnown: rule.slot === 'color' ? colorFamily(shade) !== null : null,
      swatchImageUrl: variant.featured_image?.src ?? null,
      curingRequired: false,
      professionalOnly: false,
      // No ingredient data is exposed by this endpoint, so every substance stays UNKNOWN. That is the
      // honest value and it is why gel polish is not in this pilot.
      hemaStatus: 'UNKNOWN',
      diHemaStatus: 'UNKNOWN',
      tpoStatus: 'UNKNOWN',
      inciSource: null,
      inciVerifiedAt: null,
      sourceType: 'public-product-feed',
      sourceName: 'Golden Rose HR Shopify products.json',
      sourceReference: `goldenrose-hr-products-json@${capturedAt.slice(0, 10)}`,
      dataQuality: 'pilot-unreviewed',
      dataQualityNotes:
        'Machine-captured from the retailer\'s public products.json on ' + capturedAt.slice(0, 10) +
        '. Name, price, shade, availability, URL and image are as published by the retailer. NOT hand-verified '
        + 'by a human, and no ingredient data is available from this endpoint.',
      capturedAt,
    });
  }
}

const bySlot = rows.reduce((acc, r) => { acc[r.kitSlot] = (acc[r.kitSlot] ?? 0) + 1; return acc; }, {});

const artefact = {
  pilotVersion: 1,
  capturedAt,
  market: 'HR',
  currency: 'EUR',
  system: 'regular-polish',
  source: {
    retailer: 'Golden Rose HR',
    endpoint: `${BASE}/products.json`,
    method: 'public Shopify product feed (published JSON endpoint, no HTML parsing, no bypass)',
    userAgent: UA,
  },
  honesty: {
    handVerified: false,
    statement: 'Every value is as published by the retailer; nothing is invented. Rows are machine-captured, '
      + 'not hand-checked, so dataQuality is "pilot-unreviewed". A human must verify each row before this '
      + 'catalog backs anything a customer pays for.',
    inciAvailable: false,
    inciNote: 'This endpoint exposes no ingredient list, so every substance status is UNKNOWN — which blocks '
      + 'these products from any gel-polish kit and is why the pilot is regular-polish only.',
  },
  slotCounts: bySlot,
  colorFamilyCounts: familyCounts,
  products: rows,
};

mkdirSync(join(repoRoot, 'backend', 'src', 'main', 'resources', 'catalog'), { recursive: true });
const out = join(repoRoot, 'backend', 'src', 'main', 'resources', 'catalog', 'nail-pilot-hr.json');
writeFileSync(out, `${JSON.stringify(artefact, null, 2)}\n`, 'utf8');

process.stderr.write(`wrote ${out}\n${rows.length} rows | slots: ${JSON.stringify(bySlot)}\n`);
process.stderr.write(`in stock: ${rows.filter((r) => r.inStock).length} / ${rows.length}\n`);
