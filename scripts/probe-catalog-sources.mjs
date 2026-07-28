// Catalog-source probe — Phase C entry gate.
//
// WHY THIS EXISTS
// The audit's HR retailer findings were agent research with no artefact: unverifiable, unrepeatable, and
// load-bearing for whether the Makeup Kit has a catalog at all. A conclusion that big cannot rest on a
// screenshot of somebody's memory. This script produces a committed, re-runnable record instead.
//
// WHAT IT DOES AND DOES NOT DO
// It CLASSIFIES accessibility. It does not extract a catalog, does not crawl, does not paginate, does not
// follow links found on a page, and fetches only URLs listed below. It sends the same honest identifying
// user-agent the Java collector uses (HttpProductPageFetcher.USER_AGENT) and it NEVER attempts to get
// around a block: no proxy, no browser fingerprint, no cookie replay, no header spoofing, no retry storm.
// A 403 is a finding, not an obstacle. This mirrors CatalogSourcePolicy, which is the repo's single source
// of truth on sourcing ethics.
//
// STABILITY
// Each URL is probed REPEAT times with a polite delay. One 403 is not a verdict — a site that answers 200
// then 403 is rate-limiting, which is a different classification (and a different remedy) from a site that
// blocks outright. `stableAcrossRequests` records which one it is.
//
// OUTPUT
// docs/catalog-probes/hr-beauty-probe-<UTC date>.json — the artefact Phase C reads before any snapshot is
// authored. Retailer statuses (DIRECT_VERIFIED / MANUAL_VERIFIED_ONLY / OFFICIAL_FEED_REQUIRED) may be
// assigned ONLY from this file.
//
// Run: node scripts/probe-catalog-sources.mjs

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..');

const USER_AGENT = 'BudgetSpaceCollector/0.1 (+dev; controlled product fetch)';
const TIMEOUT_MS = 15_000;
const REPEAT = 2;          // enough to tell "blocked" from "rate-limited"
const DELAY_MS = 2_500;    // polite gap between requests to the same host

// Candidate HR beauty + nail sources. `probe` is a real product/category page where possible, because a
// homepage 200 says nothing about whether product data is reachable. `kind` records what we would use the
// source FOR, so a thin makeup catalog and a thin nail catalog are never averaged into one verdict.
const CANDIDATES = [
  { retailer: 'Notino HR',        kind: 'makeup',  homepage: 'https://www.notino.hr/',            probe: 'https://www.notino.hr/makeup/' },
  { retailer: 'Douglas HR',       kind: 'makeup',  homepage: 'https://www.douglas.hr/',           probe: 'https://www.douglas.hr/hr/c/make-up/01' },
  { retailer: 'dm HR',            kind: 'makeup',  homepage: 'https://www.dm.hr/',                probe: 'https://www.dm.hr/sminka' },
  { retailer: 'Muller HR',        kind: 'makeup',  homepage: 'https://www.mueller.hr/',           probe: 'https://www.mueller.hr/parfumerija/' },
  { retailer: 'Bipa HR',          kind: 'makeup',  homepage: 'https://www.bipa.hr/',              probe: 'https://www.bipa.hr/' },
  { retailer: 'Ljekarne HR',      kind: 'makeup',  homepage: 'https://www.ljekarne.hr/',          probe: 'https://www.ljekarne.hr/' },
  { retailer: 'Golden Rose HR',   kind: 'makeup',  homepage: 'https://goldenrose.hr/',            probe: 'https://goldenrose.hr/collections/all' },
  { retailer: 'Nani Nails HR',    kind: 'nail',    homepage: 'https://www.naninails.hr/',         probe: 'https://www.naninails.hr/gel-lak/' },
  { retailer: 'Cici Nails HR',    kind: 'nail',    homepage: 'https://www.cicinails.hr/',         probe: 'https://www.cicinails.hr/' },
  { retailer: 'NailUxe HR',       kind: 'nail',    homepage: 'https://nailuxe.hr/',               probe: 'https://nailuxe.hr/' },
  { retailer: 'Beauty Shop HR',   kind: 'nail',    homepage: 'https://www.beauty-shop.hr/',       probe: 'https://www.beauty-shop.hr/' },
  { retailer: 'Perfect Nails HR', kind: 'nail',    homepage: 'https://perfectnails.hr/',          probe: 'https://perfectnails.hr/' },
  { retailer: 'Juliana Nails HR', kind: 'nail',    homepage: 'https://www.juliana-nails.hr/',     probe: 'https://www.juliana-nails.hr/' },
  { retailer: 'Kozmo HR',         kind: 'makeup',  homepage: 'https://www.kozmo.hr/',             probe: 'https://www.kozmo.hr/' },
  { retailer: 'Bazzar HR',        kind: 'makeup',  homepage: 'https://www.bazzar.hr/',            probe: 'https://www.bazzar.hr/' },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** One request. Never throws — a failure IS data. Returns what the response tells us, nothing inferred. */
async function probeOnce(url) {
  const started = Date.now();
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    const res = await fetch(url, {
      method: 'GET',
      redirect: 'follow',
      signal: controller.signal,
      headers: { 'User-Agent': USER_AGENT, Accept: 'text/html,application/xhtml+xml' },
    });
    clearTimeout(timer);
    const body = await res.text().catch(() => '');
    return {
      status: res.status,
      ok: res.ok,
      finalUrl: res.url,
      redirected: res.url !== url,
      contentType: res.headers.get('content-type') ?? null,
      bytes: body.length,
      elapsedMs: Date.now() - started,
      body,
    };
  } catch (err) {
    return {
      status: null,
      ok: false,
      finalUrl: null,
      redirected: false,
      contentType: null,
      bytes: 0,
      elapsedMs: Date.now() - started,
      error: err?.name === 'AbortError' ? `timeout after ${TIMEOUT_MS}ms` : String(err?.message ?? err),
      body: '',
    };
  }
}

/**
 * What is actually retrievable from the HTML we received.
 *
 * Every check is a POSITIVE signal test. A false means "we could not confirm this", never "this is absent"
 * — the same tri-state discipline the safety gate uses. That distinction is the whole point: a JS-only
 * shell returns 200 with no price in the HTML, which is a completely different situation from a page that
 * genuinely has no price, and conflating them is how a catalog ends up with fabricated data.
 */
function assessRetrievability(body) {
  if (!body) {
    return {
      productName: false, exactVariant: false, price: false, stock: false,
      canonicalUrl: false, image: false, ingredients: false,
      structuredData: false, likelyJsOnlyShell: false,
    };
  }
  const hasJsonLd = /application\/ld\+json/i.test(body);
  const productJsonLd = /"@type"\s*:\s*"Product"/i.test(body);
  const offerJsonLd = /"@type"\s*:\s*"Offer"/i.test(body);
  const hasPriceSignal = /"price"\s*:|itemprop=["']price["']|property=["']product:price:amount["']/i.test(body);
  const hasStockSignal = /availability|InStock|OutOfStock|itemprop=["']availability["']/i.test(body);
  const hasCanonical = /<link[^>]+rel=["']canonical["']/i.test(body);
  const hasOgImage = /property=["']og:image["']/i.test(body);
  const hasOgTitle = /property=["']og:title["']/i.test(body) || /<title[^>]*>/i.test(body);
  // INCI is the field the consumer nail safety gate depends on; without it, gel polish stays disabled.
  const hasIngredients = /\bINCI\b|sastojci|ingredients|sastav\s*:/i.test(body);
  const hasVariant = /variant|nijans|shade|odtenek|boja\s*:/i.test(body);
  // A near-empty <body> plus heavy script tags is the classic client-rendered shell.
  const scriptCount = (body.match(/<script/gi) ?? []).length;
  const bodyText = body.replace(/<script[\s\S]*?<\/script>/gi, '').replace(/<[^>]+>/g, ' ').trim();
  const likelyJsOnlyShell = scriptCount > 5 && bodyText.length < 1500;

  return {
    productName: hasOgTitle,
    exactVariant: hasVariant,
    price: hasPriceSignal,
    stock: hasStockSignal,
    canonicalUrl: hasCanonical,
    image: hasOgImage,
    ingredients: hasIngredients,
    structuredData: hasJsonLd && (productJsonLd || offerJsonLd),
    likelyJsOnlyShell,
  };
}

/**
 * Suggested sourcing status, in the repo's existing vocabulary. Deliberately conservative and deliberately
 * NOT authoritative: a human assigns the real status in CatalogSourcePolicy after reading this file. The
 * default for anything unclear is OFFICIAL_FEED_REQUIRED, matching the repo's default-deny posture.
 */
function suggestStatus(attempts, retrievability) {
  const statuses = attempts.map((a) => a.status);
  const allBlocked = statuses.every((s) => s === 403 || s === 401 || s === 429);
  const anyBlocked = statuses.some((s) => s === 403 || s === 401 || s === 429);
  const allOk = statuses.every((s) => s === 200);

  if (allBlocked) return { suggested: 'OFFICIAL_FEED_REQUIRED', because: 'every request was blocked (403/401/429); a block is never bypassed' };
  if (anyBlocked) return { suggested: 'OFFICIAL_FEED_REQUIRED', because: 'intermittently blocked or rate-limited — unstable access cannot back a price claim' };
  if (!allOk) return { suggested: 'OFFICIAL_FEED_REQUIRED', because: 'non-200 or transport failure on at least one attempt' };
  if (retrievability.likelyJsOnlyShell) return { suggested: 'OFFICIAL_FEED_REQUIRED', because: 'server HTML is a client-rendered shell — no price in the response' };
  if (retrievability.structuredData && retrievability.price) return { suggested: 'MANUAL_VERIFIED_ONLY', because: 'reachable with structured product data; needs hand verification per SKU before any row ships' };
  if (retrievability.price) return { suggested: 'MANUAL_VERIFIED_ONLY', because: 'reachable with a price signal but no structured data; hand verification required' };
  return { suggested: 'OFFICIAL_FEED_REQUIRED', because: 'reachable but no price signal found in the server HTML' };
}

async function probeCandidate(candidate) {
  // The deep probe URL is our GUESS at a category path. A 404 there says our guess was wrong, which is a
  // fact about us, not about the retailer — and misreading it as "unreachable" would wrongly shrink the
  // nail catalog, the very vertical shipping first. So a 404 falls back to the homepage and the artefact
  // records which URL the verdict actually came from.
  let probedUrl = candidate.probe;
  let attempts = [];
  let lastBody = '';
  let fellBackToHomepage = false;

  for (let pass = 0; pass < 2; pass++) {
    attempts = [];
    lastBody = '';
    for (let i = 0; i < REPEAT; i++) {
      if (i > 0) await sleep(DELAY_MS);
      const result = await probeOnce(probedUrl);
      const { body, ...rest } = result;
      if (body) lastBody = body;
      attempts.push(rest);
    }
    const allNotFound = attempts.every((a) => a.status === 404);
    if (!allNotFound || probedUrl === candidate.homepage) break;
    probedUrl = candidate.homepage;
    fellBackToHomepage = true;
    await sleep(DELAY_MS);
  }

  const retrievability = assessRetrievability(lastBody);
  const statuses = attempts.map((a) => a.status);
  const stableAcrossRequests = statuses.every((s) => s === statuses[0]);
  const { suggested, because } = suggestStatus(attempts, retrievability);

  return {
    retailer: candidate.retailer,
    kind: candidate.kind,
    homepage: candidate.homepage,
    probedUrl,
    guessedCategoryUrl: candidate.probe,
    fellBackToHomepage,
    requestMethod: 'GET',
    attempts,
    stableAcrossRequests,
    retrievable: retrievability,
    // Not machine-detectable. A human fills these in; recorded as null so the gap is visible rather than
    // silently absent, and so no phase can mistake "unchecked" for "checked and none".
    feedAvailable: null,
    affiliateProgramme: null,
    legalReviewRequired: true,
    legalReviewNote: 'Terms of use and robots.txt not assessed by this script — manual review required before any row from this source ships.',
    suggestedStatus: suggested,
    suggestedBecause: because,
  };
}

const results = [];
for (const candidate of CANDIDATES) {
  process.stderr.write(`probing ${candidate.retailer} … `);
  const result = await probeCandidate(candidate);
  results.push(result);
  process.stderr.write(`${result.attempts.map((a) => a.status ?? 'ERR').join('/')} → ${result.suggestedStatus}\n`);
  await sleep(DELAY_MS);
}

const now = new Date();
const artefact = {
  probeVersion: 1,
  checkedAtUtc: now.toISOString(),
  runtimeEnvironment: {
    node: process.version,
    platform: `${process.platform} ${process.arch}`,
    userAgent: USER_AGENT,
    timeoutMs: TIMEOUT_MS,
    repeatsPerUrl: REPEAT,
    delayMsBetweenRequests: DELAY_MS,
    note: 'Probed from a single residential HR-adjacent connection. A different network, or a CDN edge with '
        + 'different bot rules, can produce different results — re-run before relying on these classifications.',
  },
  ethics: {
    bypassAttempted: false,
    statement: 'No proxy, browser fingerprint, cookie replay, header spoofing or retry storm was used. '
             + 'A 403 is recorded as a finding and never worked around. Classification only — no catalog '
             + 'was extracted, no link was crawled, no page was paginated.',
  },
  caveats: [
    'retrievable.* are POSITIVE signal tests over the server HTML. false means "could not confirm", never "absent".',
    'feedAvailable and affiliateProgramme are null because they require a human to check a partner portal — null means UNCHECKED, not none.',
    'suggestedStatus is advisory. A human assigns the real status in CatalogSourcePolicy after reading this file.',
    'A 200 with likelyJsOnlyShell=true means the price is rendered client-side and is NOT in the response we received.',
    'guessedCategoryUrl is our guess at a category path. When it 404s the probe falls back to the homepage and fellBackToHomepage=true — such a verdict reflects the HOMEPAGE only and says nothing about product-page data.',
    'A homepage-only verdict must never be promoted past OFFICIAL_FEED_REQUIRED without probing a real product URL first.',
  ],
  results,
};

const outDir = join(repoRoot, 'docs', 'catalog-probes');
mkdirSync(outDir, { recursive: true });
const stamp = now.toISOString().slice(0, 10);
const outFile = join(outDir, `hr-beauty-probe-${stamp}.json`);
writeFileSync(outFile, `${JSON.stringify(artefact, null, 2)}\n`, 'utf8');

const summary = results.reduce((acc, r) => { acc[r.suggestedStatus] = (acc[r.suggestedStatus] ?? 0) + 1; return acc; }, {});
process.stderr.write(`\nwrote ${outFile}\n`);
process.stderr.write(`${results.length} sources probed: ${JSON.stringify(summary)}\n`);
