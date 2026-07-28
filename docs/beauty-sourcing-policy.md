# Beauty & nail sourcing policy (HR)

Companion to [`sourcing-policy.md`](sourcing-policy.md), which remains the source of truth for the furniture
catalog and for the sourcing *ethics* that apply to both. This file covers what is different about beauty.

**Evidence:** [`catalog-probes/hr-beauty-probe-2026-07-28.json`](catalog-probes/hr-beauty-probe-2026-07-28.json),
reproducible via `node scripts/probe-catalog-sources.mjs`.

## The rule that does not change

A 403 is never bypassed. No proxy, no browser fingerprint, no cookie replay, no header spoofing, no retry
storm. The probe script classifies accessibility and extracts nothing. Unknown retailer ⇒
`OFFICIAL_FEED_REQUIRED` by default.

## What the probe found (2026-07-28, 15 sources)

| Source | Kind | Verdict | Notes |
|---|---|---|---|
| **Golden Rose HR** | makeup | `MANUAL_VERIFIED_ONLY` | reachable, price + variant signal |
| **NailUxe HR** | nail | `MANUAL_VERIFIED_ONLY` | reachable, price + variant + **ingredient text** |
| **Beauty Shop HR** | nail | `MANUAL_VERIFIED_ONLY` | reachable, price + variant |
| Notino HR, Douglas HR, Ljekarne HR, Cici Nails HR | both | `OFFICIAL_FEED_REQUIRED` | 403 on every attempt, stable |
| dm HR | makeup | `OFFICIAL_FEED_REQUIRED` | 200, but a client-rendered shell — no price in the server HTML |
| Müller HR, Nani Nails HR | both | `OFFICIAL_FEED_REQUIRED` | homepage-only verdict (our category URL 404'd) — **says nothing about product pages** |
| Bipa, Kozmo, Bazzar, Perfect Nails, Juliana Nails | both | `OFFICIAL_FEED_REQUIRED` | no price signal, or connection failure |

### Three conclusions that drive the roadmap

1. **The Makeup Kit has one reachable source.** Golden Rose alone cannot fill an everyday-makeup
   completeness graph at ≥3 eligible SKUs per required slot (audit §9.2 threshold 1). Makeup therefore
   stays flagged off and ships **last**, gated on an affiliate/product feed — exactly the release order
   already agreed. This is now evidenced rather than asserted.
2. **The nail branch has two reachable sources, one with ingredient text.** Enough to begin authoring a
   press-on and regular-polish pilot. **Not** enough for consumer gel polish, which requires verified INCI
   for *every* eligible SKU (audit §6.5) — one source with ingredient text somewhere on the page is a
   starting point, not coverage.
3. **No source exposes structured product data.** `structuredData` is false for all 15 — no JSON-LD
   `Product`/`Offer` anywhere. Every row will need hand verification, and the automated freshness path the
   furniture catalog relies on (JSON-LD price re-reads) will not work here. Budget for manual verification
   as the normal cost of a beauty row, not the exception.

## Release gating

Order fixed by the owner, each vertical flagged off until its §9.2 thresholds are met:

**(a)** Salon Nail Brief — no catalog needed, so no gate · **(b)** at-home press-ons + regular polish ·
**(c)** consumer gel polish, only once every eligible SKU satisfies all eight §6.5 conditions ·
**(d)** Makeup Kit, only once coverage exists.

## What the probe deliberately does not tell you

- **`feedAvailable` and `affiliateProgramme` are `null` — meaning UNCHECKED, not "none".** Both require a
  human to check a partner portal. Recorded as null so the gap stays visible.
- **`legalReviewRequired` is `true` for every source.** Terms of use and robots.txt were not assessed. No
  row from any source ships before that review.
- **`retrievable.*` are positive signal tests.** `false` means "could not confirm", never "absent" — the
  same tri-state discipline the safety gate uses.
- **A homepage-only verdict** (`fellBackToHomepage: true`) must never be promoted past
  `OFFICIAL_FEED_REQUIRED` without probing a real product URL first.
- **One network, one moment in time.** A CDN edge with different bot rules can produce different results.
  Re-run before relying on these classifications.
