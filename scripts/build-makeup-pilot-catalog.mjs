// Builds the HR MAKEUP pilot catalog from published, structured retailer endpoints.
//
// SAME RULE AS THE NAIL CATALOG, AND IT IS THE ONLY RULE THAT MATTERS HERE:
// nothing is invented. Every name, brand, price, shade, description, image and link below is exactly what
// the retailer publishes. Where a retailer publishes nothing, the field is null and the app has to cope —
// it never gets filled with a plausible guess.
//
// Two consequences worth stating up front, because they are the fields a "make it look full" instinct
// reaches for first:
//
//   * NO RATINGS. Neither source publishes a review score, so every product has `rating: null`. A star
//     rating we made up would be the most persuasive lie in the whole app.
//   * NO vegan / cruelty-free CLAIMS. Golden Rose's Shopify tags are category words ("lice", "maskara"),
//     not certifications, and dm's search service publishes no attributes at all. Those are regulated
//     marketing claims and we do not have evidence for them, so the tag simply does not exist.
//
// What IS honest: a price band. `budget` / `mid` / `premium` are computed from the real published prices
// of the real products in the same category — arithmetic over captured data, not an opinion — and each tag
// carries its provenance so the UI can say which is which.
//
// SOURCES
//   1. Golden Rose HR - Shopify /products.json. 191 products, and unusually generous: a real Croatian
//      description on every single one, product_type, tags, and per-shade variants with their own price,
//      stock flag and swatch image. This is the backbone.
//   2. dm.hr - dm's own published search API, throttled hard (18s between queries, one 45s backoff, then
//      the term is abandoned). Adds brand breadth (essence, catrice, Maybelline, L'Oreal...) in the
//      categories where Golden Rose is thin.
//
// Run: node scripts/build-makeup-pilot-catalog.mjs

import { writeFileSync, mkdirSync, readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..');
const outPath = join(repoRoot, 'backend', 'src', 'main', 'resources', 'catalog', 'makeup-pilot-hr.json');

const UA = 'BudgetSpaceCollector/0.1 (+dev; controlled product fetch)';
const GR = 'https://goldenrose.hr';
const DM_API = 'https://product-search.services.dmtech.com/hr/search/crawl';
const DM_SITE = 'https://www.dm.hr';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const capturedAt = new Date().toISOString();
const verifiedOn = capturedAt.slice(0, 10);

const fold = (s) => (s ?? '').toLowerCase().replace(/đ/g, 'd').normalize('NFD').replace(/[̀-ͯ]/g, '');

const stripHtml = (html) => (html ?? '')
  .replace(/<[^>]*>/g, ' ')
  .replace(/&nbsp;/g, ' ')
  .replace(/&amp;/g, '&')
  .replace(/&#8211;|&#8212;/g, '-')
  .replace(/&#8220;|&#8221;|&quot;/g, '"')
  .replace(/&[a-z]+;|&#\d+;/g, ' ')
  .replace(/\s+/g, ' ')
  .trim();

// ---------------------------------------------------------------------------------------------------
// THE CATEGORY MAP
//
// Ordered, first match wins, and deliberately explicit rather than clever. Golden Rose's own
// `product_type` is Croatian, inconsistent and occasionally a typo ("korekor olovka", "kontura,", "lic"),
// and 24 products carry no type at all — so a rule that trusted it alone would silently drop an eighth of
// the feed into "uncategorised". Each entry therefore matches on type OR title OR tag, and the ones that
// look like typos are matched on purpose, with the retailer's own spelling.
//
// `usedForHr` is OUR copy and is about the CATEGORY, never about a particular product — the same kind of
// claim as the nail kit's slot descriptions. Saying what a concealer is for is teaching; saying a specific
// concealer covers well would be a review we have no evidence for.
// ---------------------------------------------------------------------------------------------------

export const CATEGORIES = [
  {
    key: 'primer', labelHr: 'Prajmer', order: 1,
    usedForHr: 'Priprema kožu prije podloge: izglađuje teksturu i produljuje trajanje šminke.',
    type: /^prajmer za lice$/, title: /\bprimer\b|make up primer/i, notTitle: /lash|trepavic/i,
  },
  {
    key: 'foundation', labelHr: 'Podloga', order: 2,
    usedForHr: 'Ujednačava ton lica. Bira se prema podtonu kože i željenoj pokrivnosti.',
    type: /^(tekuci puder za lice|podloga|lic)$/, title: /foundation|\bcc\b.*cream|bb cream/i,
  },
  {
    key: 'concealer', labelHr: 'Korektor', order: 3,
    usedForHr: 'Prekriva podočnjake i pojedinačne nesavršenosti tamo gdje podloga nije dovoljna.',
    type: /^(korektor|korekor olovka)$/, title: /concealer|retouching face pen/i,
  },
  {
    key: 'powder', labelHr: 'Puder', order: 4,
    usedForHr: 'Fiksira podlogu i smanjuje sjaj. Nanosi se nakon tekućih proizvoda.',
    type: /^(puder|kameni puder)$/, title: /face powder|loose powder|compact powder/i,
    notTitle: /bronzer|contour|kontur/i,
  },
  {
    key: 'blush', labelHr: 'Rumenilo', order: 5,
    usedForHr: 'Vraća boju licu. Nanosi se na jabučice, u tankim slojevima.',
    type: /^(rumenilo|smart cheek)$/, title: /blush|rumenil/i,
  },
  {
    key: 'bronzer', labelHr: 'Bronzer i kontura', order: 6,
    usedForHr: 'Vraća toplinu i dubinu licu — bronzer grije ton, kontura naglašava strukturu.',
    type: /^(kontura,?|paleta za konturu)$/, title: /bronzer|contour/i, tag: /^bronzer$/i,
    notTitle: /brush|kist|pen$/i,
  },
  {
    key: 'highlighter', labelHr: 'Highlighter', order: 7,
    usedForHr: 'Naglašava točke na koje pada svjetlo — jagodice, luk usne, hrbat nosa.',
    type: /^(highlighter|highlighter olovka)$/, title: /highlight|illuminator|glow pen|glow stick|shimmering body/i,
    notTitle: /brush|kist/i,
  },
  {
    key: 'eyeshadow', labelHr: 'Sjenilo', order: 8,
    usedForHr: 'Boja i dubina na kapku. Paleta daje više tonova koji se međusobno slažu.',
    type: /^(sjenilo|paleta sjenila za oci i lice)$/, title: /eyeshadow|sjenil/i, notTitle: /brush|kist/i,
  },
  {
    key: 'eyeliner', labelHr: 'Tuš i olovka za oči', order: 9,
    usedForHr: 'Definira liniju trepavica. Olovka oprašta više od tuša, tuš daje oštriju liniju.',
    type: /^(tus za oci|olovka za oci|olovka za usne i oci)$/, title: /eyeliner|browliner|kohl|kajal|eye pencil/i,
    notTitle: /brush|kist|brow(?!liner)/i,
  },
  {
    key: 'mascara', labelHr: 'Maskara', order: 10,
    usedForHr: 'Produljuje i zgušnjava trepavice. Skida se svaki dan — nikad se ne spava u njoj.',
    type: /^(maskara|prajmer za trepavice)$/, title: /mascara|maskara|lash primer/i, notTitle: /obrv|brow/i,
  },
  {
    key: 'brow', labelHr: 'Obrve', order: 11,
    usedForHr: 'Popunjava i drži oblik obrve. Ton se bira svjetliji od kose, ne tamniji.',
    type: /^(maskara za obrve|olovka za obrve)$/, title: /brow(?!liner)|obrv/i, notTitle: /brush|kist/i,
  },
  {
    key: 'lipstick', labelHr: 'Ruž i sjajilo', order: 12,
    usedForHr: 'Boja na usnama. Mat traje dulje, sjajilo je udobnije i lakše se obnavlja.',
    type: /^(ruz za usne|sjaj za usne|labelo)$/, title: /lipstick|lip gloss|lipgloss|lip oil|lip balm|lipbalm|lip stain|lipcolor|lip butter|plumped lips|lip glaze|lip & cheek/i,
    notTitle: /liner|pencil|brush|kist/i,
  },
  {
    key: 'lipliner', labelHr: 'Olovka za usne', order: 13,
    usedForHr: 'Iscrtava rub usne i sprječava razlijevanje ruža.',
    type: /^olovka za usne$/, title: /lip\s?liner|lipliner/i,
  },
  {
    key: 'setting-spray', labelHr: 'Fiksator', order: 14,
    usedForHr: 'Zaključava šminku na kraju i topi prijelaze između pudera i kože.',
    type: /^fiksator$/, title: /fixing spray|setting spray|fiksator/i,
  },
  {
    key: 'tools', labelHr: 'Kistovi i pribor', order: 15,
    usedForHr: 'Alat odlučuje koliko dobro proizvod legne — više nego cijena proizvoda.',
    type: /^(kist|spuzvica|cetkica|siljilo|umjetne trepavice)$/, title: /brush|sponge|blender|sharpener|eyelash/i,
  },
  {
    key: 'remover', labelHr: 'Skidanje šminke', order: 16,
    usedForHr: 'Šminka se skida svaki dan. Bez toga ni najbolji proizvodi ne pomažu koži.',
    type: /^$/, title: /odstranjivac sminke|micellar|micelarn|make.?up remover|cleansing/i,
  },
];

/** Not makeup, whatever the feed calls it. Recorded so nobody re-adds them by accident. */
const NOT_MAKEUP = [
  { match: /lak za nokte|nail lacquer|nail color|nail foundation|top coat|cuticle|uv gel/i,
    why: 'nail product — belongs to the nail pilot catalog, not the makeup one' },
  { match: /parfem|toaletna voda|eau de parfum|body mist|edt\b/i, why: 'fragrance, not makeup' },
  { match: /grey hair touch-up|stik za kosu/i, why: 'hair product' },
  { match: /body glow|sjaj za tijelo/i, why: 'body product, not face makeup' },
];

/** The category a product belongs to, from the retailer's own type, title and tags. Null = not makeup. */
function categorise(title, productType, tags) {
  const t = fold(title);
  const type = fold(productType);
  const tagList = (tags ?? []).map(fold);
  if (NOT_MAKEUP.some((x) => x.match.test(title))) return null;

  for (const c of CATEGORIES) {
    if (c.notTitle && c.notTitle.test(t)) continue;
    if (c.type && c.type.test(type)) return c;
    if (c.title && c.title.test(t)) return c;
    if (c.tag && tagList.some((x) => c.tag.test(x))) return c;
  }
  return null;
}

// ---------------------------------------------------------------------------------------------------
// FINISH AND SHADE, read from the retailer's own words only
//
// A look can ask for "mat usne" or "topli ton". The kit may only answer that when the retailer NAMED it.
// This mirrors NailCapabilityEvidence exactly, and for the same reason: "Ice Chic 24" is not a colour, and
// a foundation whose shade is "04" is not a match for anyone's skin.
// ---------------------------------------------------------------------------------------------------

const FINISHES = {
  matte: /\bmatte?\b|\bmat\b/,
  satin: /satin|saten/,
  shimmer: /shimmer|simer|sparkle|glitter|metallic|pearl|biser/,
  gloss: /gloss|sjajilo|glaze|lip oil|\bglow\b|dewy/,
  cream: /\bcream\b|kremast/,
};

const SHADE_FAMILIES = {
  red: /\bred\b|crven|\bruby\b|scarlet/,
  burgundy: /burgund|bordo|bordeaux|\bwine\b|visnj|merlot|\bberry\b|plum/,
  pink: /\bpink\b|roza|ruzicast|\brose\b|fuchsia|fuksij/,
  nude: /\bnude\b|\bbeige\b|bez\b|\btaupe\b|caramel|karamel/,
  brown: /\bbrown\b|smed|\bmocha\b|\bcocoa\b|chocolate|cokolad|\btan\b/,
  peach: /peach|breskv|coral|koral|apricot/,
  // \bbronze\b, not `bronze`: the substring matches "bronzer", which is a PRODUCT TYPE and says nothing
  // about the shade. "Sun Bright Bronzer 101" was being recorded as a gold shade on that basis.
  gold: /\bgold\b|zlatn|champagne|sampanj|\bbronze\b|\bbronca\b/,
  silver: /\bsilver\b|srebrn|\bgrey\b|\bgray\b|siv/,
  black: /\bblack\b|crn|\bonyx\b/,
  white: /\bwhite\b|bijel|\bivory\b|\bsnow\b/,
  blue: /\bblue\b|plav|\bnavy\b|teal|tirkiz/,
  green: /\bgreen\b|zelen|olive|maslinast|\bemerald\b/,
  purple: /purple|ljubicast|lilac|lila|violet|lavender/,
  orange: /orange|naranc|\brust\b|\bamber\b|jantar/,
};

/** The finish the retailer named, or null. Read from title + shade name + published description. */
function namedFinish(text) {
  const t = fold(text);
  for (const [finish, re] of Object.entries(FINISHES)) if (re.test(t)) return finish;
  return null;
}

/**
 * The shade family the retailer named, or null. A shade NUMBER is not a colour and returns null.
 *
 * <p>`productName` is subtracted from the shade text first, and that is not a nicety. Shopify variant
 * titles routinely repeat the product name — "Sun Bright Bronzer 101" is the shade of "Sun Bright Bronzer
 * Powder" — so any colour word living in the PRODUCT name gets read as if it described the SHADE. What is
 * left after the subtraction is the only part the retailer used to tell one shade from another, which is
 * the only part that can carry colour evidence.</p>
 */
function namedShadeFamily(shadeName, productName = '') {
  if (!shadeName) return null;
  const productWords = new Set(fold(productName).split(/[^a-z0-9]+/).filter(Boolean));
  const t = fold(shadeName).split(/[^a-z0-9]+/)
    .filter((word) => word && !productWords.has(word))
    .join(' ');
  // "Ice 24", "04", "No. 7" — a number is a code, not a colour. Also catches a shade name that was
  // nothing but the product name plus a number, which is now an empty string.
  if (!t || /^(no|nijansa|shade)?\s*\d+$/.test(t)) return null;
  for (const [family, re] of Object.entries(SHADE_FAMILIES)) if (re.test(t)) return family;
  return null;
}

// ---------------------------------------------------------------------------------------------------
// Golden Rose (Shopify)
// ---------------------------------------------------------------------------------------------------

async function fetchGoldenRose() {
  const res = await fetch(`${GR}/products.json?limit=250&page=1`, {
    headers: { 'User-Agent': UA, Accept: 'application/json' },
  });
  if (!res.ok) throw new Error(`Golden Rose products.json returned ${res.status}`);
  const { products } = await res.json();

  const rows = [];
  let skipped = 0;
  for (const product of products) {
    const category = categorise(product.title, product.product_type, product.tags);
    if (!category) { skipped++; continue; }

    const description = stripHtml(product.body_html);
    // Shopify's "Default Title" means the product has no shades at all, not a shade called that.
    const variants = (product.variants ?? []).filter((v) => v.price && Number(v.price) > 0);
    const inStock = variants.some((v) => v.available);
    const cheapest = variants.slice().sort((a, b) => Number(a.price) - Number(b.price))[0];
    if (!cheapest) continue;

    // Shopify variant titles repeat the whole product name ("Skin Perfector CC Light Foundation 101"), so
    // a shade list renders as the same sentence eight times with a different number on the end. Strip the
    // product name and keep what the retailer actually distinguishes the shade by. Still the retailer's
    // own text — nothing is added, only the duplicated prefix removed. If stripping leaves nothing, the
    // full title is kept rather than showing an empty chip.
    const shadeLabel = (variantTitle) => {
      const full = String(variantTitle).trim();
      const stripped = full.toLowerCase().startsWith(product.title.toLowerCase())
        ? full.slice(product.title.length).replace(/^[\s\-–—:.]+/, '').trim()
        : full;
      return stripped || full;
    };

    const shades = variants
      .filter((v) => v.title && v.title !== 'Default Title')
      .map((v) => ({
        name: shadeLabel(v.title),
        fullTitle: String(v.title).trim(),
        // Shade colour is only "known" when the retailer NAMED it. "Keratin 42" names nothing.
        colorFamily: namedShadeFamily(shadeLabel(v.title), product.title),
        price: Number(v.price),
        inStock: Boolean(v.available),
        swatchImageUrl: v.featured_image?.src ?? null,
      }));

    rows.push({
      externalId: `goldenrose-hr-${product.id}`,
      name: product.title,
      brand: product.vendor || 'Golden Rose',
      category: category.key,
      subcategoryHr: product.product_type || null,
      description,
      price: Number(cheapest.price),
      currency: 'EUR',
      shades,
      shadeCount: shades.length,
      finish: namedFinish(`${product.title} ${description}`),
      imageUrl: product.images?.[0]?.src ?? null,
      inStock,
      stockKnown: true,
      productUrl: `${GR}/products/${product.handle}`,
      retailer: 'Golden Rose HR',
      retailerUrl: GR,
      market: 'HR',
      // No retailer in this pilot publishes a review score. Null, and it stays null.
      rating: null,
      ratingCount: null,
      publishedTags: (product.tags ?? []).filter(Boolean),
      sourceType: 'public-product-feed',
      sourceEndpoint: `${GR}/products.json`,
      verificationMethod: 'automatic',
      lastVerifiedAt: capturedAt,
      sourceStatus: 'reachable',
      verifiedAt: verifiedOn,
      dataQuality: 'pilot-unreviewed',
    });
  }
  process.stderr.write(`  ${rows.length} makeup rows kept, ${skipped} non-makeup rows skipped\n`);
  return rows;
}

// ---------------------------------------------------------------------------------------------------
// dm.hr — brand breadth where Golden Rose is thin
// ---------------------------------------------------------------------------------------------------

const DM_QUERIES = [
  { query: 'primer za lice', category: 'primer', take: 6 },
  { query: 'puder za lice tekuci', category: 'foundation', take: 8 },
  { query: 'korektor za lice', category: 'concealer', take: 8 },
  { query: 'fiksator sminke', category: 'setting-spray', take: 5 },
  { query: 'rumenilo za lice', category: 'blush', take: 6 },
  { query: 'bronzer za lice', category: 'bronzer', take: 6 },
  { query: 'micelarna voda', category: 'remover', take: 5 },
];

/** Anything cured, medical or plainly not makeup, whatever the search returns. */
const DM_EXCLUDE = /gel\s*iq|uv gel|trajni lak|akril|polygel|lak za nokte|sampon|dezodorans|krema za sunc/i;

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
    const url = `${DM_API}?query=${encodeURIComponent(q.query)}&pageSize=30&currentPage=0`;
    let res = null;
    for (let attempt = 0; attempt < 2; attempt++) {
      // dm rate-limits hard. Throttle, back off ONCE and long, then abandon the term — a rate limit is
      // the service asking us to slow down, not an obstacle to route around.
      await sleep(attempt === 0 ? 18000 : 45000);
      res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json' } });
      if (res.status !== 429) break;
      process.stderr.write(`  dm "${q.query}" -> 429, backing off 45s once\n`);
    }
    if (!res || !res.ok) {
      process.stderr.write(`  dm "${q.query}" -> HTTP ${res?.status}, abandoned\n`);
      continue;
    }
    const json = await res.json();
    let taken = 0;
    for (const p of json.products ?? []) {
      if (taken >= q.take) break;
      const title = p.title ?? p.tileData?.title?.tileHeadline ?? '';
      if (!title || DM_EXCLUDE.test(title)) continue;
      // The query says which category we were shopping for, but the TITLE has to agree. dm's search is
      // fuzzy and happily returns a nail polish for "rumenilo".
      const category = categorise(title, '', []);
      if (!category || category.key !== q.category) continue;

      const price = dmPrice(p);
      if (price == null) continue;
      const path = p.tileData?.self;
      if (!path) continue;

      // dm puts the shade in the title after an en dash: "Lak ... - 40 Classic Red, 8 ml".
      const shadeMatch = title.match(/[–-]\s*([^,]+?)(?:,\s*[\d,.]+\s*(?:ml|g|kom)\.?)?$/);
      const shadeName = shadeMatch ? shadeMatch[1].trim() : null;

      rows.push({
        externalId: `dm-hr-${p.dan}`,
        name: title,
        brand: p.brandName ?? p.tileData?.brand?.name ?? '',
        category: category.key,
        subcategoryHr: null,
        // dm's search service publishes no description. Null rather than a sentence we wrote for them.
        description: null,
        price,
        currency: 'EUR',
        shades: shadeName ? [{ name: shadeName, colorFamily: namedShadeFamily(shadeName, title), price, inStock: true, swatchImageUrl: null }] : [],
        shadeCount: shadeName ? 1 : 0,
        finish: namedFinish(title),
        imageUrl: p.tileData?.images?.[0]?.tileSrc ?? null,
        // dm's search API publishes NO stock field. Recorded explicitly; the UI must say so.
        inStock: true,
        stockKnown: false,
        productUrl: `${DM_SITE}${path}`,
        retailer: 'dm.hr',
        retailerUrl: DM_SITE,
        market: 'HR',
        rating: null,
        ratingCount: null,
        publishedTags: [],
        gtin: p.gtin ?? null,
        sourceType: 'public-product-feed',
        sourceEndpoint: DM_API,
        verificationMethod: 'automatic',
        lastVerifiedAt: capturedAt,
        sourceStatus: 'reachable',
        verifiedAt: verifiedOn,
        dataQuality: 'pilot-unreviewed',
      });
      taken++;
    }
    process.stderr.write(`  dm "${q.query}" -> ${taken} rows\n`);
  }
  const deduped = [...new Map(rows.map((r) => [r.externalId, r])).values()];
  if (deduped.length !== rows.length) {
    process.stderr.write(`  dm: ${rows.length - deduped.length} duplicate article(s) collapsed\n`);
  }
  return deduped;
}

// ---------------------------------------------------------------------------------------------------
// PRICE BANDS — the one derived tag, and it is arithmetic, not an opinion
//
// Within each category, terciles of the REAL captured prices. "Premium" here means "expensive for a
// mascara in this catalog", which is a fact about the catalog; it is not a quality claim, and the UI says
// so. Computed per category because a 3 EUR mascara and a 3 EUR brush are not the same kind of cheap.
// ---------------------------------------------------------------------------------------------------

function withPriceBands(products) {
  const byCategory = new Map();
  for (const p of products) {
    if (!byCategory.has(p.category)) byCategory.set(p.category, []);
    byCategory.get(p.category).push(p.price);
  }
  const cuts = new Map();
  for (const [category, prices] of byCategory) {
    const sorted = prices.slice().sort((a, b) => a - b);
    cuts.set(category, {
      low: sorted[Math.floor(sorted.length / 3)],
      high: sorted[Math.floor((sorted.length * 2) / 3)],
    });
  }
  return products.map((p) => {
    const c = cuts.get(p.category);
    const band = !c ? 'mid' : p.price <= c.low ? 'budget' : p.price >= c.high ? 'premium' : 'mid';
    return {
      ...p,
      priceBand: band,
      // Provenance on every tag. `published` = the retailer's own word; `derived` = computed by us from
      // published data. There is deliberately no third kind: we do not add editorial tags.
      tags: [
        { tag: band, provenance: 'derived', basisHr: 'izračunato iz stvarnih cijena u ovoj kategoriji' },
        ...(p.finish ? [{ tag: p.finish, provenance: 'published', basisHr: 'trgovac je naveo završnicu' }] : []),
        ...(p.shadeCount > 1
          ? [{ tag: 'multi-shade', provenance: 'published', basisHr: `trgovac objavljuje ${p.shadeCount} nijansi` }]
          : []),
      ],
    };
  });
}

// ---------------------------------------------------------------------------------------------------

process.stderr.write('fetching Golden Rose HR...\n');
const goldenRose = await fetchGoldenRose();

process.stderr.write('fetching dm.hr...\n');
let dm = [];
try {
  dm = await fetchDm();
  process.stderr.write(`  ${dm.length} rows\n`);
} catch (err) {
  // A source we could not read must not delete what it gave us last time. Same rule as the nail catalog.
  process.stderr.write(`  dm.hr failed: ${err.message}\n`);
  const previous = existsSync(outPath) ? JSON.parse(readFileSync(outPath, 'utf8')) : { products: [] };
  dm = (previous.products ?? []).filter((p) => p.retailer === 'dm.hr')
    .map((p) => ({ ...p, sourceStatus: 'unavailable' }));
  process.stderr.write(`  carrying forward ${dm.length} previously captured dm row(s)\n`);
}

const products = withPriceBands([...goldenRose, ...dm]);
const byCategory = products.reduce((a, p) => { a[p.category] = (a[p.category] ?? 0) + 1; return a; }, {});

const artefact = {
  pilotVersion: 1,
  capturedAt,
  market: 'HR',
  currency: 'EUR',
  categories: CATEGORIES.map((c) => ({
    key: c.key, labelHr: c.labelHr, order: c.order, usedForHr: c.usedForHr,
  })),
  sources: [
    { retailer: 'Golden Rose HR', endpoint: `${GR}/products.json`, platform: 'Shopify',
      method: 'published JSON product feed', verificationMethod: 'automatic',
      status: goldenRose.length ? 'reachable' : 'unavailable', lastVerifiedAt: capturedAt },
    { retailer: 'dm.hr', endpoint: DM_API, platform: 'dm search service',
      method: 'published JSON search API, throttled', verificationMethod: 'automatic',
      status: dm.some((p) => p.sourceStatus === 'reachable') ? 'reachable' : 'unavailable',
      lastVerifiedAt: dm.map((p) => p.lastVerifiedAt).sort().pop() ?? null },
  ],
  honesty: {
    handVerified: false,
    statement: 'Every value is as published by the retailer; nothing is invented. Rows are machine-captured '
      + 'from published endpoints, not hand-checked, so dataQuality is "pilot-unreviewed".',
    knownGaps: [
      'NO PRODUCT RATINGS. Neither source publishes a review score, so every rating is null. A star rating '
        + 'we invented would be the most persuasive lie in the app, so the field exists and stays empty.',
      'NO vegan / cruelty-free tags. Golden Rose publishes category tags ("lice", "maskara"), not '
        + 'certifications, and dm publishes no attributes. Those are regulated claims and we have no '
        + 'evidence for them.',
      'budget / mid / premium are DERIVED — terciles of the real captured prices within each category. '
        + 'They describe this catalog, not product quality, and each tag carries provenance:"derived".',
      'dm.hr publishes no description and no stock field. Those rows carry description:null and '
        + 'stockKnown:false, and the UI must say so.',
      'A shade is only matched to a colour when the retailer NAMED it. "Ice 24" is a code, not a colour, '
        + 'and resolves to colorFamily:null.',
      'No INCI list is published by either source, so no ingredient or allergen claim is possible.',
    ],
    excludedOnPurpose: NOT_MAKEUP.map((x) => ({ pattern: String(x.match), why: x.why })),
  },
  categoryCounts: byCategory,
  products,
};

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, `${JSON.stringify(artefact, null, 2)}\n`, 'utf8');

process.stderr.write(`\nwrote ${outPath}\n${products.length} products\n`);
for (const c of CATEGORIES) {
  process.stderr.write(`  ${String(byCategory[c.key] ?? 0).padStart(3)}  ${c.key}\n`);
}
