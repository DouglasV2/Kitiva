// Builds the HR nail pilot catalog from THREE published, structured retailer endpoints.
//
// SOURCES AND WHY THESE THREE
// A 2026-07-28/29 sourcing sweep of 15+ Croatian retailers, extended on 2026-08-03 to the nail-supply
// shops Croatian salons actually order from, found three reachable sources with published structured
// product data covering the two systems this slice supports:
//
//   1. Golden Rose HR   - Shopify /products.json. Regular nail polish: base, colour, top, cuticle oil.
//   2. dm.hr            - dm's own published search API at product-search.services.dmtech.com.
//                         Press-on nail sets, nail glue, polish remover, files.
//   3. beauty-shop.hr   - WooCommerce Store API (/wp-json/wc/store/products). Self-adhesive gold nail
//                         art, the only gold detail on the HR shelf that needs no lamp. See below.
//
// None is scraped. All three are published JSON endpoints, fetched with an honest user-agent, throttled,
// with no bypass of any kind. Sources that blocked us (sephora.hr 403, mueller.hr 403, socap.hr 403,
// notino.hr 403, makeup.hr bot interstitial) were recorded and abandoned, never worked around.
// nokti.shop and naio-nails.com publish feeds but price in RSD/GBP for other markets, so they are out of
// scope for an HR/EUR pilot rather than converted - a converted price is an invented price.
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

import { writeFileSync, mkdirSync, readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..');
const outPath = join(repoRoot, 'backend', 'src', 'main', 'resources', 'catalog', 'nail-pilot-hr.json');
const UA = 'BudgetSpaceCollector/0.1 (+dev; controlled product fetch)';
const GR = 'https://goldenrose.hr';
const DM_API = 'https://product-search.services.dmtech.com/hr/search/crawl';
const DM_SITE = 'https://www.dm.hr';
const BS_SITE = 'https://beauty-shop.hr';
const BS_API = `${BS_SITE}/wp-json/wc/store/products`;

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

// ---------------------------------------------------------------------------------------------------
// NAMED-COLOUR POLISH (added 2026-07-31, capability-driven)
//
// The coverage matrix said 8/56 target looks could be completed honestly, and the top blockers were all
// colours: the only polish shades in the catalog were Golden Rose's numbered ones ("ICE 2"), which prove
// nothing. A 2026-07-31 probe of dm's published search API found polishes whose OWN TITLE names the colour
// - "Color & Care lak za nokte - 40 Classic Red". That is retailer evidence, so those shades can answer a
// colour request instead of being offered as a guess.
//
// This is a RULE, not a hand-picked list: keep any polish whose published title matches one of the colour
// patterns the backend's NailCapabilityEvidence uses. Keeping the two in sync matters - a row this script
// labels "red" that the backend cannot re-derive from the title would be a colour claim with no evidence
// behind it, which is the exact failure the evidence layer exists to prevent.
// ---------------------------------------------------------------------------------------------------

const COLOR_PATTERNS = {
  burgundy: /bordeaux|bordo|burgund|visnj|merlot|wine/,
  red: /\bred\b|crven/,
  nude: /\bnude\b/,
  pink: /\bpink\b|roza|rose\b/,
  black: /\bblack\b|crn/,
  white: /\bwhite\b|bijel|milky/,
  brown: /\bbrown\b|smed|\btan\b|mocha/,
};

const fold = (s) => (s ?? '').toLowerCase().replace(/đ/g, 'd')
  .normalize('NFD').replace(/[̀-ͯ]/g, '');

/** The colour the RETAILER named, or null. Never inferred from a swatch, a code or an image. */
function namedColour(title) {
  const t = fold(title);
  for (const [family, re] of Object.entries(COLOR_PATTERNS)) if (re.test(t)) return family;
  return null;
}

/** A polish, not a press-on set and not a tool — the colour slot only takes something you brush on. */
const IS_POLISH = /lak za nokte|nail (polish|lacquer|colour|color)/i;
const NOT_POLISH = /umjetni nokti|press[- ]?on|naljepnic|turpij|rasp|ljepilo|magnet|odstranjivac|remover/i;

const DM_QUERIES = [
  { query: 'umjetni nokti', slot: 'press-on-set', role: 'press-on-set', system: 'press-on', take: 12 },
  // CAPABILITY-PINNED press-on queries. A 2026-08-03 rebuild silently lost the Cat Eye set and the
  // balerina set: dm's relevance ranking had simply moved them past position 12 of the generic query, and
  // with them went the only proof of CAT_EYE and of SHAPE_COFFIN in the whole catalog. A capability must
  // not be able to fall out of the catalog because a search result reshuffled, so each one the matrix
  // depends on is asked for by name. The dedupe below collapses the heavy overlap with the generic query.
  { query: 'umjetni nokti cat eye', slot: 'press-on-set', role: 'press-on-set', system: 'press-on', take: 4 },
  { query: 'umjetni nokti badem', slot: 'press-on-set', role: 'press-on-set', system: 'press-on', take: 4 },
  { query: 'umjetni nokti balerina', slot: 'press-on-set', role: 'press-on-set', system: 'press-on', take: 4 },
  { query: 'umjetni nokti bordo', slot: 'press-on-set', role: 'press-on-set', system: 'press-on', take: 4 },
  { query: 'ljepilo za nokte', slot: 'adhesive', role: 'adhesive', system: 'press-on', take: 4 },
  // Tagged "both": the same remover is the consumer removal step for polish AND for soaking off a press-on
  // set. Restricting it to polish would leave the press-on graph unable to fill its required removal slot,
  // which would report Incomplete for a reason that is about our tagging rather than about the shelf.
  { query: 'odstranjivac laka za nokte', slot: 'removal', role: 'removal', system: 'both', take: 4 },
  { query: 'turpija za nokte', slot: 'file', role: 'tool', system: 'both', take: 3 },
  // Colour polish. `namedColourOnly` keeps only shades the retailer's own title identifies, so these can
  // answer "burgundy" instead of joining the pile of numbered shades that answer nothing.
  { query: 'lak za nokte', slot: 'color', role: 'color', system: 'regular-polish', take: 40,
    namedColourOnly: true },
  { query: 'essence lak za nokte', slot: 'color', role: 'color', system: 'regular-polish', take: 40,
    namedColourOnly: true },
  { query: 'catrice lak za nokte', slot: 'color', role: 'color', system: 'regular-polish', take: 40,
    namedColourOnly: true },
  { query: 'lak za nokte bordo', slot: 'color', role: 'color', system: 'regular-polish', take: 20,
    namedColourOnly: true },
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
      // Raised from 8s when the colour queries doubled the request count: a 2026-07-31 probe took a 429 on
      // 6 of 14 terms at 9s spacing. Slower is the correct answer to a rate limit.
      await sleep(attempt === 0 ? 18000 : 45000);
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

      // Colour queries: keep only a brush-on polish whose title NAMES its colour. Everything else the
      // search returns for "lak za nokte" is a numbered shade, a tool or a press-on set, and a numbered
      // shade in the colour slot is what made every colour request an assumption in the first place.
      let colourFamily = null;
      if (q.namedColourOnly) {
        if (!IS_POLISH.test(title) || NOT_POLISH.test(title)) continue;
        colourFamily = namedColour(title);
        if (!colourFamily) continue;
      }

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
        // A colour is "known" only when the retailer named it. That single flag is the difference between
        // "this is the burgundy you asked for" and "check the swatch, we are guessing".
        colorFamily: colourFamily,
        shadeColorKnown: q.namedColourOnly ? true : null,
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
  // The colour queries overlap heavily — "lak za nokte", "essence lak za nokte" and "catrice lak za nokte"
  // all return the same Gel Colour shades — so the same dm article arrives several times. Two rows with one
  // externalId would show the same polish twice in "Zamijeni" and make a pin ambiguous. First occurrence
  // wins; the query that found it first is the one whose slot mapping applies.
  const deduped = [...new Map(rows.map((r) => [r.externalId, r])).values()];
  if (deduped.length !== rows.length) {
    process.stderr.write(`  dm: ${rows.length - deduped.length} duplicate article(s) collapsed\n`);
  }
  return deduped;
}

// ---------------------------------------------------------------------------------------------------
// beauty-shop.hr (WooCommerce Store API) - the ZLATNI DETALJ, and why it took a third retailer
//
// The coverage matrix named "zlatni detalj" as a blocker with "nema nijednog provjerenog proizvoda u
// katalogu", and a 2026-08-03 sweep confirmed why: dm.hr sells no gold nail product at all, and Golden
// Rose numbers its shades. Croatia does sell gold nail art - but almost all of it is FOIL, and foil is
// not a classical-polish product: Victoria Vynn's own instructions say "postavite foliju ... na
// disperzijski sloj ... susiti u lampi 60 sekundi", and cicinails' foil line says "koristite Foil gel".
// A foil in a regular-polish kit would be a product the buyer cannot actually use.
//
// What DOES work over air-dried polish is a self-adhesive sticker, and beauty-shop.hr publishes both the
// gold claim and the application step in its own words: "Zlatno-crne klasicne naljepnice. Jednostavna
// uporaba, skinite naljepnicu s podloge, zalijepite na zeljeno mjesto i zastitite je sa sjajem." No
// lamp, no gel, sealed with the top coat the kit already buys.
//
// THE RULE, not a hand-picked list. Keep a row only when ALL of these hold:
//   * the retailer's own TITLE contains a nail word AND a gold word - the same two things
//     NailCapabilityEvidence re-derives, so no row claims gold that the backend cannot re-prove;
//   * the published application text names NO curing step (lamp, polymerisation, gel/tacky layer);
//   * it is not a gold-COLOURED TOOL. "ZLATNA KLIJESTA ZA UMJETNE NOKTE" and "Kist za nail art Gold"
//     both satisfy nail+gold and neither puts a speck of gold on a nail. This is the same false
//     positive as "Poklon-paket Magnetic Man", one category up.
// ---------------------------------------------------------------------------------------------------

const BS_QUERIES = ['gold', 'zlatn', 'naljepnica za nokte'];

/** Thrown when a retailer refuses us. Never caught to retry — only to stop deleting what it gave us. */
class BlockedSourceError extends Error {}

const DECOR_NAIL_WORD = /nokt|nail|manikur/;
const DECOR_GOLD_WORD = /\bgold\b|zlatn|zlato/;
/** Its own text says it needs a lamp, a gel, or a tacky layer - so it cannot go over air-dried polish. */
const NEEDS_CURING = /lamp|polimeriz|stvrdnjav|disperzijski|ljepljiv\w* sloj|sloj gela|gel lak|gel polish|trajni lak|trajnog laka|trajnim lak|hibridn|akril|foil gel/;
/** A tool that happens to be gold. Gold-coloured pliers decorate nothing. */
const IS_TOOL = /klijest|skaric|\bkist\b|kistov|brusilic|stalak|pincet|nastavak|rucki|motor|sablon|posudic|torbic|frez|makaz|pogurivac|turpij|\brasp\w*/;

const stripHtml = (html) => (html ?? '')
  .replace(/<[^>]*>/g, ' ')
  .replace(/&#8211;|&#8212;/g, '-')
  .replace(/&#8220;|&#8221;|&#822[01];/g, '"')
  .replace(/&[a-z]+;|&#\d+;/g, ' ')
  .replace(/\s+/g, ' ')
  .trim();

async function fetchBeautyShop() {
  const rows = new Map();
  for (const query of BS_QUERIES) {
    // Slower than dm's 18s is not needed here, but this shop fronts its API with Imunify360 bot
    // protection that answers HTTP 200 with a refusal body. Space the requests out and take the refusal
    // at its word - a bot protection saying no is the retailer saying no.
    await sleep(12000);
    const url = `${BS_API}?per_page=50&search=${encodeURIComponent(query)}`;
    const res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json' } });
    if (!res.ok) {
      process.stderr.write(`  beauty-shop "${query}" -> HTTP ${res.status}, skipped\n`);
      continue;
    }
    const body = await res.json();
    if (!Array.isArray(body)) {
      // Imunify360 answers 200 with {"message": "Access denied by ... bot-protection"}. Treated exactly
      // like Müller's and Sephora's 403: recorded, abandoned, never worked around. Do NOT retry-storm and
      // do NOT dress the request up as a browser.
      process.stderr.write(`  beauty-shop "${query}" -> BLOCKED: ${body?.message ?? 'non-list response'}\n`);
      throw new BlockedSourceError(`beauty-shop.hr: ${body?.message ?? 'non-list response'}`);
    }
    let kept = 0;
    for (const p of body) {
      const name = stripHtml(p.name);
      const title = fold(name);
      // The application text IS the evidence, so it is captured and stored, not just tested against.
      const applicationEvidence = stripHtml(`${p.short_description ?? ''} ${p.description ?? ''}`).slice(0, 600);

      if (!DECOR_NAIL_WORD.test(title) || !DECOR_GOLD_WORD.test(title)) continue;
      if (IS_TOOL.test(title)) continue;
      if (NEEDS_CURING.test(fold(applicationEvidence))) continue;
      if (!p.is_in_stock || !p.is_purchasable) continue;

      const price = p.prices ? Number(p.prices.price) / 10 ** Number(p.prices.currency_minor_unit) : null;
      if (!price) continue;

      rows.set(String(p.id), {
        externalId: `beauty-shop-hr-${p.id}`,
        name,
        brand: '',
        shadeName: null,
        shadeCode: null,
        colorFamily: 'gold',
        // The retailer's own title says "Gold". That is the same evidence class as essie's "bordeaux",
        // not a swatch guess, so the kit may state it rather than hedge it.
        shadeColorKnown: true,
        swatchImageUrl: null,
        retailer: 'beauty-shop.hr',
        retailerUrl: BS_SITE,
        market: 'HR',
        currency: p.prices?.currency_code ?? 'EUR',
        price,
        // WooCommerce publishes a real stock flag, unlike dm's search service.
        inStock: Boolean(p.is_in_stock),
        stockKnown: true,
        productUrl: p.permalink,
        // MEASURED, not assumed: this shop answers HTTP 415 with an HTML body for its own published image
        // URLs when they are loaded from anywhere but its own pages — verified with a browser user-agent
        // and with ours. Carrying the URL anyway would put a 58x58 hole in the kit that never resolves,
        // because a request that hangs never fires the <img> error handler that draws the "bez slike"
        // placeholder. A picture we cannot show is not a picture.
        imageUrl: null,
        imageUrlPublishedButBlocked: p.images?.[0]?.src ?? null,
        gtin: /^\d{8,14}$/.test(p.sku ?? '') ? p.sku : null,
        nailSystem: 'both',
        applicationRole: 'decoration',
        kitSlot: 'accent',
        curingRequired: false,
        lampRequired: false,
        professionalOnly: false,
        consumerEligible: true,
        sizeInfo: null,
        includesInKit: null,
        // Stored because a capability claim has to be traceable to the words that justify it - and
        // because the cat-eye gate reads this field to insist on a published magnet step.
        applicationEvidence,
        hemaStatus: 'UNKNOWN',
        tpoStatus: 'UNKNOWN',
        inciPublished: false,
        sourceType: 'public-product-feed',
        sourceEndpoint: BS_API,
        verifiedAt: verifiedOn,
        dataQuality: 'pilot-unreviewed',
      });
      kept++;
    }
    process.stderr.write(`  beauty-shop "${query}" -> ${kept} row(s) kept\n`);
  }
  return [...rows.values()];
}

// ---------------------------------------------------------------------------------------------------

process.stderr.write('fetching Golden Rose HR...\n');
const goldenRose = await fetchGoldenRose();
process.stderr.write(`  ${goldenRose.length} rows\n`);

process.stderr.write('fetching dm.hr...\n');
const dm = await fetchDm();

process.stderr.write('fetching beauty-shop.hr...\n');

// A blocked source must not be able to DELETE a capability. If beauty-shop refuses this run, the rows it
// already gave us on a run that it allowed are carried forward untouched - including their original
// `verifiedAt`, which is the honest record of when a human could last have checked them. The alternative,
// silently writing a catalog with no gold in it, is the same failure as dm's ranking churn dropping the
// only Cat Eye set: the app would tell the user Croatia sells no gold nail product, which is false.
let beautyShop = [];
let carriedForward = null;
try {
  beautyShop = await fetchBeautyShop();
  process.stderr.write(`  ${beautyShop.length} rows\n`);
} catch (err) {
  if (!(err instanceof BlockedSourceError)) throw err;
  const previous = existsSync(outPath) ? JSON.parse(readFileSync(outPath, 'utf8')) : { products: [] };
  beautyShop = (previous.products ?? []).filter((p) => p.retailer === 'beauty-shop.hr');
  carriedForward = err.message;
  process.stderr.write(`  ${err.message}\n`
    + `  carrying forward ${beautyShop.length} previously captured row(s), verifiedAt unchanged\n`);
}

// ---------------------------------------------------------------------------------------------------
// VERIFICATION METADATA
//
// Three facts per row, because "we captured this" and "this is true right now" are different claims and
// the app must never make the second one on the strength of the first:
//
//   verificationMethod  automatic = machine-read from the published feed; manual = a human opened the page
//                       and checked it. Everything here is `automatic`; nothing has been hand-checked yet,
//                       which is the same thing dataQuality:"pilot-unreviewed" says at the artefact level.
//   lastVerifiedAt      the instant the row was last actually READ from its source. Carried-forward rows
//                       keep their original one - a run that could not reach the source did not verify
//                       anything, and stamping it with today's date would be a lie of exactly one field.
//   sourceStatus        reachable | temporarily-blocked | unavailable, as of this run.
//
// The backend turns these into what the user is told (NailCatalogFreshness). Nothing here decides whether
// a kit is complete: a stale price is a disclosure, not a missing product.
// ---------------------------------------------------------------------------------------------------

/**
 * Stamp rows with this run's verification facts.
 *
 * <p>The one rule that matters here: <strong>only a run that actually READ the source may set
 * lastVerifiedAt to now.</strong> Carried-forward rows keep whatever timestamp they already had, and rows
 * that predate this field fall back to the date they were captured on — never to today. Stamping a row we
 * could not reach with today's date would be a single-field lie that silently makes every downstream
 * "verified" claim false, which is precisely the failure this metadata exists to prevent.</p>
 */
const stampVerification = (rows, sourceStatus) => {
  const reachable = sourceStatus === 'reachable';
  return rows.map((r) => ({
    ...r,
    verificationMethod: r.verificationMethod ?? 'automatic',
    lastVerifiedAt: reachable
      ? capturedAt
      : (r.lastVerifiedAt ?? (r.verifiedAt ? `${r.verifiedAt}T00:00:00Z` : null)),
    sourceStatus,
  }));
};

const sourceStatuses = {
  'Golden Rose HR': goldenRose.length > 0 ? 'reachable' : 'unavailable',
  'dm.hr': dm.length > 0 ? 'reachable' : 'unavailable',
  'beauty-shop.hr': carriedForward ? 'temporarily-blocked' : (beautyShop.length > 0 ? 'reachable' : 'unavailable'),
};

const products = [
  ...stampVerification(goldenRose, sourceStatuses['Golden Rose HR']),
  ...stampVerification(dm, sourceStatuses['dm.hr']),
  ...stampVerification(beautyShop, sourceStatuses['beauty-shop.hr']),
];
const bySlot = products.reduce((a, p) => { a[p.kitSlot] = (a[p.kitSlot] ?? 0) + 1; return a; }, {});
const bySystem = products.reduce((a, p) => { a[p.nailSystem] = (a[p.nailSystem] ?? 0) + 1; return a; }, {});

/** The newest instant any row from this source was actually read. Null when we have never reached it. */
const lastVerifiedFor = (retailer) => products
  .filter((p) => p.retailer === retailer)
  .map((p) => p.lastVerifiedAt)
  .sort()
  .pop() ?? null;

const artefact = {
  pilotVersion: 2,
  capturedAt,
  market: 'HR',
  currency: 'EUR',
  systems: ['regular-polish', 'press-on'],
  sources: [
    { retailer: 'Golden Rose HR', endpoint: `${GR}/products.json`, platform: 'Shopify',
      method: 'published JSON product feed', verificationMethod: 'automatic',
      status: sourceStatuses['Golden Rose HR'], lastVerifiedAt: lastVerifiedFor('Golden Rose HR') },
    { retailer: 'dm.hr', endpoint: DM_API, platform: 'dm search service',
      method: 'published JSON search API, throttled', verificationMethod: 'automatic',
      status: sourceStatuses['dm.hr'], lastVerifiedAt: lastVerifiedFor('dm.hr') },
    { retailer: 'beauty-shop.hr', endpoint: BS_API, platform: 'WooCommerce',
      method: 'published Store API, throttled', verificationMethod: 'automatic',
      status: sourceStatuses['beauty-shop.hr'], lastVerifiedAt: lastVerifiedFor('beauty-shop.hr'),
      note: carriedForward ?? undefined },
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
      'No air-drying magnetic (cat-eye) polish exists on any reachable HR feed. Every cat-eye colour '
        + 'product found - dm DEPEND Gel iQ Cat Eye, cicinails Soft/Come Closer, beauty-shop Cat Eye '
        + 'Satin and Aurora Veil - publishes a UV/LED curing step, so all are out of scope for this '
        + 'pilot. Cat-eye therefore stays honestly Incomplete for classical polish.',
      'Magnets for cat-eye DO exist in HR (cicinails 3,00 EUR; beauty-shop 3,00 EUR) but none is '
        + 'catalogued: their published text ties them to magnetic TRAJNI LAK, and a magnet without a '
        + 'compatible air-drying polish cannot complete anything. Catalogued, it would only add a tool '
        + 'to a kit that still cannot make the effect.',
      'The gold detail is a STICKER, not a foil. Every gold foil on the HR shelf publishes a lamp or '
        + 'foil-gel step, which a classical-polish kit cannot satisfy.',
    ],
    excludedOnPurpose: GR_EXCLUDE.map((x) => ({ pattern: String(x.match), why: x.why })),
    // Set when a retailer refused this run and its rows were carried over from the previous capture
    // rather than deleted. Their own `verifiedAt` still says when they were actually seen.
    carriedForward,
  },
  slotCounts: bySlot,
  systemCounts: bySystem,
  products,
};

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, `${JSON.stringify(artefact, null, 2)}\n`, 'utf8');

process.stderr.write(`\nwrote ${outPath}\n${products.length} products | slots ${JSON.stringify(bySlot)} | systems ${JSON.stringify(bySystem)}\n`);
