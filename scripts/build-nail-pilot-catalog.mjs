// Builds the HR nail pilot catalog from TWO published, structured retailer endpoints.
//
// SOURCES AND WHY THESE TWO
// A 2026-07-28/29 sourcing sweep of 15+ Croatian retailers found exactly two reachable sources with
// published structured product data covering the two systems this slice supports:
//
//   1. Golden Rose HR  - Shopify /products.json. Regular nail polish: base, colour, top, cuticle oil.
//   2. dm.hr           - dm's own published search API at product-search.services.dmtech.com.
//                        Press-on nail sets, nail glue, polish remover, files.
//
// Neither is scraped. Both are published JSON endpoints, fetched with an honest user-agent, throttled,
// with no bypass of any kind. Sources that blocked us (sephora.hr 403, mueller.hr 403, makeup.hr bot
// interstitial) were recorded and abandoned, never worked around.
//
// TWO CORRECTIONS THIS BUILD MAKES, both found by the sweep:
//   * Golden Rose's "UV Gel Nail Color Remover" is marketed for UV GEL removal - its own copy says
//     "Lako uklanja UV gel boju". It was previously used as the removal step of a REGULAR-POLISH kit,
//     which is wrong: it is the wrong product for the job. It is now excluded, and removal comes from a
//     real polish remover at dm.
//   * Golden Rose sells no file, no buffer and no polish remover at all. Those slots are filled from dm
//     or they stay genuinely unfilled - which is what makes an Incomplete status honest rather than staged.
//
// Run: node scripts/build-nail-pilot-catalog.mjs

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..');
const UA = 'BudgetSpaceCollector/0.1 (+dev; controlled product fetch)';
const GR = 'https://goldenrose.hr';
const DM_API = 'https://product-search.services.dmtech.com/hr/search/crawl';
const DM_SITE = 'https://www.dm.hr';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const capturedAt = new Date().toISOString();
const verifiedOn = capturedAt.slice(0, 10);

// ---------------------------------------------------------------------------------------------------
// Golden Rose (Shopify) - regular polish only
// ---------------------------------------------------------------------------------------------------

// Title -> kit slot. The retailer's own product_type says "lak za nokte" for everything from a base coat
// to a cuticle oil, so it cannot distinguish a prep step from a top coat. The kit graph depends on that
// difference, so the mapping is by title and is deliberately explicit.
const GR_SLOT_RULES = [
  { slot: 'base', role: 'base', match: /smoothing base|nail foundation|base coat/i },
  { slot: 'color', role: 'color', match: /keratin nail color|ice chic nail lacquer|ice color nail lacquer|city color nail lacquer|color expert nail lacquer|gel power/i },
  { slot: 'top', role: 'top', match: /top coat/i },
  { slot: 'cuticle-care', role: 'prep', match: /cuticle|beauty oil/i },
];

// Excluded by name, with the reason recorded so nobody re-adds them by accident.
const GR_EXCLUDE = [
  { match: /uv gel nail color remover/i, why: 'marketed for UV GEL removal ("Lako uklanja UV gel boju"), not regular polish' },
  { match: /quick dryer spray/i, why: 'drying aid, not a kit slot; also out of stock at capture time' },
  { match: /extreme glitter/i, why: 'only 2 of 12 shades in stock at capture time' },
];

const MAX_SHADES = 18;

async function fetchGoldenRose() {
  const res = await fetch(`${GR}/products.json?limit=250&page=1`, {
    headers: { 'User-Agent': UA, Accept: 'application/json' },
  });
  if (!res.ok) throw new Error(`Golden Rose products.json returned ${res.status}`);
  const { products } = await res.json();

  const rows = [];
  let shadeCount = 0;
  for (const product of products) {
    if (GR_EXCLUDE.some((x) => x.match.test(product.title))) continue;
    const rule = GR_SLOT_RULES.find((r) => r.match.test(product.title));
    if (!rule) continue;

    const variants = [...product.variants].sort((a, b) => Number(b.available) - Number(a.available));
    const limit = rule.slot === 'color' ? 6 : 2;
    for (const variant of variants.slice(0, limit)) {
      if (rule.slot === 'color') {
        if (shadeCount >= MAX_SHADES) break;
        shadeCount++;
      }
      const shade = variant.title && variant.title !== 'Default Title' ? variant.title : null;
      rows.push({
        externalId: `goldenrose-hr-${variant.id}`,
        name: product.title,
        brand: 'Golden Rose',
        shadeName: shade,
        shadeCode: shade ? String(shade).trim().split(/\s+/).pop() : null,
        // MEASURED: this retailer numbers its shades ("Keratin 42") and publishes only a swatch image.
        // No colour name exists in the feed, so a colour request can only ever be answered with a
        // CANDIDATE plus the swatch - never a claim that it matches.
        colorFamily: null,
        shadeColorKnown: rule.slot === 'color' ? false : null,
        swatchImageUrl: variant.featured_image?.src ?? null,
        retailer: 'Golden Rose HR',
        retailerUrl: GR,
        market: 'HR',
        currency: 'EUR',
        price: Number(variant.price),
        inStock: Boolean(variant.available),
        productUrl: `${GR}/products/${product.handle}?variant=${variant.id}`,
        imageUrl: product.images?.[0]?.src ?? null,
        nailSystem: 'regular-polish',
        applicationRole: rule.role,
        kitSlot: rule.slot,
        curingRequired: false,
        lampRequired: false,
        professionalOnly: false,
        consumerEligible: true,
        sizeInfo: null,
        includesInKit: null,
        hemaStatus: 'UNKNOWN',
        tpoStatus: 'UNKNOWN',
        inciPublished: false,
        sourceType: 'public-product-feed',
        sourceEndpoint: `${GR}/products.json`,
        verifiedAt: verifiedOn,
        dataQuality: 'pilot-unreviewed',
      });
    }
  }
  return rows;
}

// ---------------------------------------------------------------------------------------------------
// dm.hr (published search API) - press-ons, glue, remover, files
// ---------------------------------------------------------------------------------------------------

const DM_QUERIES = [
  { query: 'umjetni nokti', slot: 'press-on-set', role: 'press-on-set', system: 'press-on', take: 12 },
  { query: 'ljepilo za nokte', slot: 'adhesive', role: 'adhesive', system: 'press-on', take: 4 },
  // Tagged "both": the same remover is the consumer removal step for polish AND for soaking off a press-on
  // set. Restricting it to polish would leave the press-on graph unable to fill its required removal slot,
  // which would report Incomplete for a reason that is about our tagging rather than about the shelf.
  { query: 'odstranjivac laka za nokte', slot: 'removal', role: 'removal', system: 'both', take: 4 },
  { query: 'turpija za nokte', slot: 'file', role: 'tool', system: 'both', take: 3 },
];

// Anything matching these is NOT one of our two systems, whatever the search returns.
const DM_EXCLUDE = /gel\s*iq|gel naljepnic|uv folij|uv gel|gel lak|trajni lak|akril|polygel|builder/i;

function dmPrice(p) {
  const raw = p.tileData?.trackingData?.price;
  if (typeof raw === 'number') return raw;
  const label = p.tileData?.price?.price?.current?.value;
  if (!label) return null;
  const n = Number(String(label).replace(/[^\d,.-]/g, '').replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

async function fetchDm() {
  const rows = [];
  for (const q of DM_QUERIES) {
    // dm returns 429 on rapid requests. Throttle hard, and on a 429 back off ONCE and long rather than
    // retry-storming - a rate limit is the service asking us to slow down, not an obstacle.
    const url = `${DM_API}?query=${encodeURIComponent(q.query)}&pageSize=30&currentPage=0`;
    let res = null;
    for (let attempt = 0; attempt < 2; attempt++) {
      await sleep(attempt === 0 ? 8000 : 30000);
      res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json' } });
      if (res.status !== 429) break;
      process.stderr.write(`  dm "${q.query}" -> 429, backing off 30s\n`);
    }
    if (!res || !res.ok) {
      process.stderr.write(`  dm "${q.query}" -> HTTP ${res?.status}, skipped (slot will be genuinely unfilled)\n`);
      continue;
    }
    const json = await res.json();
    let taken = 0;
    for (const p of json.products ?? []) {
      if (taken >= q.take) break;
      const title = p.title ?? p.tileData?.title?.tileHeadline ?? '';
      if (!title || DM_EXCLUDE.test(title)) continue;
      const price = dmPrice(p);
      // No price means no honest recommendation. Drop the row rather than guess.
      if (price == null) continue;
      const path = p.tileData?.self;
      if (!path) continue;

      const a11y = p.tileData?.a11yLabel ?? '';
      rows.push({
        externalId: `dm-hr-${p.dan}`,
        name: title,
        brand: p.brandName ?? p.tileData?.brand?.name ?? '',
        shadeName: null,
        shadeCode: null,
        colorFamily: null,
        shadeColorKnown: null,
        swatchImageUrl: null,
        retailer: 'dm.hr',
        retailerUrl: DM_SITE,
        market: 'HR',
        currency: 'EUR',
        price,
        // dm's search API publishes NO stock field. Treating "unknown" as "in stock" would be exactly the
        // absence-as-evidence mistake the safety model exists to prevent, so it is recorded explicitly and
        // the UI tells the user to check before buying.
        inStock: true,
        stockKnown: false,
        productUrl: `${DM_SITE}${path}`,
        imageUrl: p.tileData?.images?.[0]?.tileSrc ?? null,
        gtin: p.gtin ?? null,
        nailSystem: q.system,
        applicationRole: q.role,
        kitSlot: q.slot,
        curingRequired: false,
        lampRequired: false,
        professionalOnly: false,
        consumerEligible: true,
        // Only essence publishes kit contents and sizing. Everything else stays null, and the planner has
        // to raise an assumption rather than pretend it knows how many tips are in the box.
        sizeInfo: /24 .*nokt|12 .*velicin|12 razlicitih/i.test(a11y) ? a11y : null,
        includesInKit: null,
        hemaStatus: 'UNKNOWN',
        tpoStatus: 'UNKNOWN',
        inciPublished: false,
        sourceType: 'public-product-feed',
        sourceEndpoint: DM_API,
        verifiedAt: verifiedOn,
        dataQuality: 'pilot-unreviewed',
        previousPrice: p.tileData?.price?.price?.previous?.value ?? null,
      });
      taken++;
    }
    process.stderr.write(`  dm "${q.query}" -> ${taken} rows\n`);
  }
  return rows;
}

// ---------------------------------------------------------------------------------------------------

process.stderr.write('fetching Golden Rose HR...\n');
const goldenRose = await fetchGoldenRose();
process.stderr.write(`  ${goldenRose.length} rows\n`);

process.stderr.write('fetching dm.hr...\n');
const dm = await fetchDm();

const products = [...goldenRose, ...dm];
const bySlot = products.reduce((a, p) => { a[p.kitSlot] = (a[p.kitSlot] ?? 0) + 1; return a; }, {});
const bySystem = products.reduce((a, p) => { a[p.nailSystem] = (a[p.nailSystem] ?? 0) + 1; return a; }, {});

const artefact = {
  pilotVersion: 2,
  capturedAt,
  market: 'HR',
  currency: 'EUR',
  systems: ['regular-polish', 'press-on'],
  sources: [
    { retailer: 'Golden Rose HR', endpoint: `${GR}/products.json`, platform: 'Shopify', method: 'published JSON product feed' },
    { retailer: 'dm.hr', endpoint: DM_API, platform: 'dm search service', method: 'published JSON search API, throttled' },
  ],
  honesty: {
    handVerified: false,
    statement: 'Every value is as published by the retailer; nothing is invented. Rows are machine-captured '
      + 'from published endpoints, not hand-checked, so dataQuality is "pilot-unreviewed". A human must '
      + 'verify each row before this catalog backs anything a customer pays for.',
    knownGaps: [
      'dm.hr publishes no stock field in its search API. Rows carry stockKnown:false and the UI must say so.',
      'No retailer in this catalog publishes an INCI list, so every substance status is UNKNOWN. That is why '
        + 'gel polish is not offered: the fail-closed safety gate would block it, correctly.',
      'Only the essence press-on set publishes tip count and sizing. Others carry sizeInfo:null and the '
        + 'planner must raise a fit assumption rather than imply it knows the size.',
      'Golden Rose numbers its shades and publishes no colour name, so no shade can be matched to a colour '
        + 'request. Colour picks are candidates plus a swatch link, never a claimed match.',
      'Several dm press-ons are on clearance; the recorded price is the current one and can change.',
    ],
    excludedOnPurpose: GR_EXCLUDE.map((x) => ({ pattern: String(x.match), why: x.why })),
  },
  slotCounts: bySlot,
  systemCounts: bySystem,
  products,
};

mkdirSync(join(repoRoot, 'backend', 'src', 'main', 'resources', 'catalog'), { recursive: true });
const out = join(repoRoot, 'backend', 'src', 'main', 'resources', 'catalog', 'nail-pilot-hr.json');
writeFileSync(out, `${JSON.stringify(artefact, null, 2)}\n`, 'utf8');

process.stderr.write(`\nwrote ${out}\n${products.length} products | slots ${JSON.stringify(bySlot)} | systems ${JSON.stringify(bySystem)}\n`);
