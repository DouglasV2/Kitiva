// i18n completeness guard.
//
// Beauty Kit Phase A rescope. The launch market is Croatia only, but the 12 `src/messages/<lang>.json`
// overlays are deliberately PRESERVED — an HR-only launch is a market decision, not a reason to delete
// working translation capability. The guard was therefore re-pointed:
//
//   ENFORCED (fails the build): every DICTIONARY key ships both `hr` and `en`. These are the two
//     languages actually served today — HR is the source, EN is the runtime fallback. A key missing
//     either one WILL render wrong for a real user.
//   WARNED (does not fail): gaps in the dormant non-HR overlays. Requiring every new beauty string in
//     12 languages for markets that have no beauty catalog would block every copy change for no user
//     benefit. When a market is re-enabled in markets.ts, promote its overlay back to enforced here.
//
// Zero-dependency (regex + JSON) — run via `npm run check:i18n`.
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const frontend = join(here, '..');

// Languages the app actually serves today. Keep in sync with MARKETS in src/markets.ts.
const SHIPPED_LANGS = ['hr', 'en'];

const i18nSrc = readFileSync(join(frontend, 'src', 'i18n.ts'), 'utf8');
// Capture each DICTIONARY entry with its (single-level) object body so we can check per-language coverage.
const keyRe = /^\s*'([^']+)'\s*:\s*(\{[^{}]*\})/gm;
const dictKeys = new Set();
const incompleteShipped = [];
let match;
while ((match = keyRe.exec(i18nSrc)) !== null) {
  const [, key, body] = match;
  if (!/\bhr\s*:/.test(body)) continue; // not a DICTIONARY entry
  dictKeys.add(key);
  const absent = SHIPPED_LANGS.filter((lang) => !new RegExp(`\\b${lang}\\s*:`).test(body));
  if (absent.length) incompleteShipped.push(`${key} (missing: ${absent.join(', ')})`);
}

if (dictKeys.size === 0) {
  console.error('check-i18n: could not extract any DICTIONARY keys from i18n.ts');
  process.exit(1);
}

// Pre-existing untranslated keys as of Sprint 10.183 — all from the DROPPED / unrendered subscription flow
// (Plus/Pro pricing, `plus.*` upsells) plus `header.menu`. They fall back to English and are out of this
// sprint's scope; excused here so the guard can still ENFORCE completeness for every new key going forward.
// Remove entries from this list as the owner translates them (or when the dead pricing keys are deleted).
const ALLOW_MISSING = new Set([
  'header.menu',
  'plus.aiUpsell', 'plus.saveLimitUpsell', 'plus.seePricing',
  'pricing.freeName', 'pricing.freeF4', 'pricing.plusName', 'pricing.plusPrice', 'pricing.plusTagline',
  'pricing.plusF1', 'pricing.plusF2', 'pricing.plusF3', 'pricing.plusF4', 'pricing.plusNote', 'pricing.plusActive',
  'pricing.waitlistEmail', 'pricing.waitlistCta', 'pricing.joined', 'pricing.upgradeCta', 'pricing.signInForPlus',
  'pricing.redirecting', 'pricing.checkoutError', 'pricing.welcome', 'pricing.proName', 'pricing.proPrice',
  'pricing.notifyCta', 'pricing.proNotified',
  // Sprint 10.183 "honest replace" (owner WIP, landed alongside the Move-In QoL sprint): these fall back to
  // English in non-EN markets until translated. Remove from this list once the owner adds their overlays.
  'results.noNicerFound', 'results.noCheaperFound', 'results.noOtherFound',
]);

const messagesDir = join(frontend, 'src', 'messages');
const overlays = readdirSync(messagesDir).filter((file) => file.endsWith('.json'));

let dormantGapTotal = 0;
let orphanTotal = 0;
for (const file of overlays) {
  const data = JSON.parse(readFileSync(join(messagesDir, file), 'utf8'));
  const overlayKeys = new Set(Object.keys(data));
  const missing = [...dictKeys].filter((key) => !overlayKeys.has(key) && !ALLOW_MISSING.has(key));
  const orphan = [...overlayKeys].filter((key) => !dictKeys.has(key));
  if (missing.length) dormantGapTotal += missing.length;
  if (orphan.length) orphanTotal += orphan.length;
}

// ENFORCED: the two languages we actually serve.
if (incompleteShipped.length) {
  console.error(
    `\ncheck-i18n: FAIL — ${incompleteShipped.length} DICTIONARY key(s) missing a shipped language ` +
      `(${SHIPPED_LANGS.join('/')}):\n  ${incompleteShipped.join('\n  ')}`
  );
  process.exit(1);
}

// WARNED: the dormant overlays for markets that are not currently served.
console.log(
  `check-i18n: OK — ${dictKeys.size} DICTIONARY keys complete for ${SHIPPED_LANGS.join('/')}.` +
    (dormantGapTotal ? ` ${dormantGapTotal} gap(s) across ${overlays.length} dormant overlays (warning only — HR-only launch).` : '') +
    (orphanTotal ? ` ${orphanTotal} orphan key(s) (warning).` : '')
);
process.exit(0);
