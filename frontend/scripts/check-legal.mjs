// Guards that keep the legal pages and the analytics consent layer HONEST as the code evolves. They assert
// PROPERTIES (the operator is identified, no registry number is invented, GA stays consent-gated, no raw
// prompt reaches analytics, no processor is named that this app does not use) rather than whole paragraphs,
// so they are not brittle. Zero-dependency — run via `npm run check`.
//
// Rewritten 2026-08-07 for Kitiva. The previous version read src/i18n.ts, src/components/Planner.tsx,
// PlannerForm.tsx and PlanResults.tsx — all deleted with the furniture app — so it crashed with ENOENT on
// every run and had never once reported a result on this codebase.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const frontend = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (rel) => readFileSync(join(frontend, rel), 'utf8');

const legal = read('src/legal.ts');
const main = read('src/main.tsx');
const app = read('src/App.tsx');
const analytics = read('src/utils/analytics.ts');
const nail = read('src/components/NailLook.tsx');
const makeup = read('src/components/MakeupLook.tsx');

const failures = [];
const ok = [];
const check = (desc, condition) => (condition ? ok.push(desc) : failures.push(desc));

// Every trackEvent(...) call, tolerating one level of nested parentheses.
const trackEventCalls = (source) => source.match(/trackEvent\((?:[^()]|\([^()]*\))*\)/gs) ?? [];

// ── Operator identity ─────────────────────────────────────────────────────────────────────────────
check('Impressum shows the operator name (Bruno Pušić)', legal.includes('Bruno Pušić'));
check('Impressum shows the street address', legal.includes('Ulica Emanuela Vidovića 28'));
check('Impressum shows the postal city', legal.includes('10360 Sesvete'));
check('No "address added later" placeholder remains',
  !/(address|adresa|Anschrift)[^.\n]{0,60}(will appear|added later|dodati|bit će|ergänzt|später)/i.test(legal));
// The words may appear only to state that no such number exists — never with a number after them.
check('No fabricated OIB number', !/OIB[^.\n]{0,20}\d{6,}/i.test(legal));
check('No fabricated VAT / USt-IdNr number',
  !/(VAT|USt-?IdNr|PDV)[^.\n]{0,20}[A-Z]{0,2}\d{6,}/i.test(legal));

// ── The privacy policy must describe THIS app ─────────────────────────────────────────────────────
// A privacy policy is a statement of fact about data processing. Naming a processor the app does not use is
// not harmless boilerplate — it is the same class of invention the catalog rules forbid, in the one document
// a user is entitled to rely on. Each name below is checked against whether any code actually reaches it.
const namedButUnused = [];
for (const [name, pattern] of [
  ['Stripe', /stripe/i],
  ['Gemini', /gemini/i],
  ['eBay', /ebay/i],
]) {
  const claimed = legal.includes(name);
  const used = [main, app, analytics, nail, makeup].some((src) => pattern.test(src));
  if (claimed && !used) namedButUnused.push(name);
}
check('Privacy Policy names no processor this app does not use', namedButUnused.length === 0);

check('Privacy Policy has a Google Analytics section', legal.includes('Google Analytics'));
check('Privacy Policy ties Analytics to consent (only after acceptance)',
  /(samo ako|only after you|only runs after|nur, nachdem|nur mit)/i.test(legal) && /Google Analytics/.test(legal));
// GA IS wired, so "no tracking cookies" would be a false claim.
check('Privacy Policy does NOT claim "no tracking cookies"',
  !/no tracking cookies/i.test(legal) && !/kolačiće za praćenje/i.test(legal) && !/Tracking-Cookies/i.test(legal));
check('Privacy Policy does NOT claim it never stores/handles IP',
  !/we do not store your ip/i.test(legal) && !/ne spremamo tvoju ip/i.test(legal)
  && !/speichern ihre ip-adresse nicht/i.test(legal));
check('Privacy Policy covers temporary IP processing for rate limiting/security',
  /IP/.test(legal) && /(privremeno|temporarily|vorübergehend)/i.test(legal)
  && /(ograničavanj|rate limit|Ratenbegrenz)/i.test(legal));
check('Privacy Policy mentions consent withdrawal via Postavke privatnosti / Privacy settings',
  /(Postavke privatnosti|Privacy settings|Datenschutz-Einstellungen)/i.test(legal));
check('Privacy Policy names the DPA (AZOP)', legal.includes('AZOP'));

// ── Analytics consent gating ──────────────────────────────────────────────────────────────────────
check('GA is NOT started unconditionally in main.tsx', !/initAnalytics\s*\(/.test(main));
check('Consent layer gates analytics (ConsentProvider present)', app.includes('ConsentProvider'));
check('analytics.ts exposes the consent controls (init/enable/disable/configured)',
  /export function initAnalytics/.test(analytics) && /export function enableAnalytics/.test(analytics)
  && /export function disableAnalytics/.test(analytics) && /export function analyticsConfigured/.test(analytics));
check('Advertising consent is never granted (ad_storage stays denied)',
  !/ad_storage['"]?\s*:\s*['"]granted/.test(analytics));

// ── No user text in analytics ─────────────────────────────────────────────────────────────────────
// The nail vertical takes free Croatian text describing what someone wants on their body. It must never
// become an analytics parameter. This guard is dormant today (the verticals send no events at all) and is
// here precisely for the commit that adds the first one.
const trackCalls = [...trackEventCalls(nail), ...trackEventCalls(makeup)];
const leaky = trackCalls.filter((c) => /\b(prompt|freeText|query|brief)\b/i.test(c));
check('No raw user prompt is passed into any analytics event', leaky.length === 0);
if (leaky.length) console.error('  Leaky trackEvent call(s):\n   ' + leaky.join('\n   '));

// ── Report ────────────────────────────────────────────────────────────────────────────────────────
if (failures.length === 0) {
  console.log(`check-legal: OK — ${ok.length} legal/analytics guards passed`);
  process.exit(0);
}
console.error(`\ncheck-legal: FAIL — ${failures.length} of ${ok.length + failures.length} guard(s) failed:`);
for (const f of failures) console.error(`  ✗ ${f}`);
if (namedButUnused.length) {
  console.error(`\n  src/legal.ts names ${namedButUnused.join(', ')} as processing user data, and no code in`);
  console.error('  this app reaches any of them. That text belongs to the furniture product. It is a factual');
  console.error('  claim in a GDPR document, so it needs an owner/legal decision, not a find-and-replace.');
  console.error('  Tracked as tasks.md #4c.');
}
process.exit(1);
