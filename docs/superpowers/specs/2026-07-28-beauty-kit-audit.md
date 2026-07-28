# Phase A — BudgetSpace → Beauty Kit: Repository & Domain Audit

**Date:** 2026-07-28
**Repo audited:** `budgetspace-ai-ux-sprint-2` @ `main` = `55af77d` ("copy: impersonal voice for the Italian overlay (10.191)"), 0 ahead / 0 behind `origin/main`, clean tree.
**Method:** 14 agents (6 subsystem readers, 4 domain researchers, 1 synthesiser, 3 adversarial critics), then hand-verification of every load-bearing claim. Claims below marked ✅ were re-checked directly against the files.

---

## 0. Executive finding

**BudgetSpace is a much better starting point than it looks, and a much worse one than the synthesis first claimed.**

The valuable asset is *not* the furniture catalog (21,129 rows, zero transfer). It is the **honesty machinery**: a verified-sourcing gate, a never-fabricate invariant enforced by ~100 tests, a deterministic prompt parser with negation scope, a 3-stage budget repair that fails honestly rather than lying, and an LLM layer that is structurally forbidden from inventing products. That is precisely the skeleton the beauty spec asks for, and it already exists.

The pivot's real cost is in four places the furniture product never needed:

1. a **compatibility graph** (zero exists — verified: no `compatib`/`goesWith` engine code),
2. a **safety gate** with tri-state ingredient evidence,
3. **canonical-product-vs-retailer-offer** separation (does not exist; one flat row per retailer-product),
4. a **real HR beauty catalog**, which is the single most likely cause of failure.

### Three things that must be corrected before any code is written

| # | Finding | Consequence |
|---|---|---|
| 1 | ✅ `CatalogSourcePolicy` is `public final class` (L31) with a `private` constructor (L62) and only `static` methods (`isPlannerEligible` L250, `isProductionVerified` L234). | The safety gate **cannot** be "composed into `isPlannerEligible`". It needs a repository (INCI records). It must be an **injected bean**, evaluated in the selection chain *and again* at response assembly. |
| 2 | ✅ Stripe is **subscription-only**: `BillingService.java:94` `field(form, "mode", "subscription")` against a single `plusPriceId`. | The spec mandates a **one-time unlock** (€5.99 / €2.99 / €19.99 pack). This is a real, unbudgeted change — and the mandated A–J phase list contains no billing phase. **Decision required (§6.3).** |
| 3 | ✅ The safety gate would be enforced at selection time only. `GET /api/products` (`ProductController.java:47-54`) is a public raw `findAll()` with no eligibility filter; `GET /api/saved-plans/{id}` (`SavedPlanController.java:55`) rehydrates frozen JSON with no session and no re-validation. | A blocked, recalled or expired-evidence product reaches the user through two doors that bypass the gate entirely. |

---

## 1. Reusable infrastructure

Ranked by value to the pivot.

### 1.1 Reuse as-is (no code change)

| Component | Path | Why it transfers |
|---|---|---|
| **LLM containment** | `ai/LlmClient.java`, `LlmClientFactory`, `LlmProperties`, `AiUsageTracker`, + 3 provider clients (OpenAi, Anthropic, Gemini) | Off by default, JSON mode, deterministic temperature, schema carries **no product fields**, output sanitised against the taxonomy, always falls back to the deterministic parser, monthly/daily/session USD caps. This is exactly "the LLM may only parse the prompt" enforced in code. |
| **Test architecture** | `*CatalogRuntimeTest` pattern (35 files), `prompt-matrix.json` data-driven parser tests, policy-as-executable-rule tests, `ProdSchemaBootIT` | Copy the *patterns*, don't edit the files. `HrDepthCatalogRuntimeTest` is the exact harness for "a beauty catalog file is complete, importable and policy-clean". |
| **Security filter chain** | `config/RateLimitFilter`, `RequestSizeLimitFilter`, `SecurityHeadersFilter`, `MalformedOriginGuardFilter`, `CorsConfig`, `GlobalExceptionHandler`, `AdminEndpointGuardFilter` | Domain-neutral. ⚠️ One trap: `RateLimitFilter.GUARDED_PREFIX` is `/api/plans/` — a new `/api/kits/**` endpoint is **completely unthrottled** unless the constant is extended. |
| **Auth / GDPR / consent** | `auth/**`, `ConsentContext.tsx`, `RetentionCleanupService` | Guest sessions via `X-BudgetSpace-Session`, Google sign-in, real erasure, 18-month retention sweep. No beauty-specific change. |

### 1.2 Reuse with the engine kept, tables swapped

| Component | Path | What survives / what changes |
|---|---|---|
| **Prompt-parsing machinery** | `planner/PlannerIntentExtractor.java` | **Survives:** `normalize()` (L604, NFD + đ/æ/ø/å/ß folding), `affirmative()` (L596), `matchKeys()` (L529), the `CLAUSE_TRIGGER` have/need clause scan (L433-450), `reverseClauseCategories()` (L489, Croatian object-verb order — "bazu imam"). **Changes:** the pattern tables only. The engine knows nothing about furniture. |
| **Budget amount parser** | `planner/AmountParser.java` | Priority-ordered regex cascade with HR slang (`soma`, `soma i po`, `tisuću`), year/phone guards. **Two real defects for beauty:** ✅ the bare-`NUMBER` rule requires **3+ digits**, so `do 60 eura` / `imam 40` never parse; and the `UNIT_AFTER` guard lacks `ml|g|kom|nijans`, so "primer 100 ml" parses as a €100 budget. |
| **Verified-sourcing gate** | `product/CatalogSourcePolicy.java` | Provenance types, `sourceReference` requirement, unknown-retailer-defaults-to-`OFFICIAL_FEED_REQUIRED` (exactly right for beauty), staleness, `isProductionVerified`. ⚠️ `isPlannerEligible` **deliberately admits stale rows** (L237-247 javadoc) so an aging catalog never empties. Safe for a sofa; **not** safe for an ingredient verdict. |
| **Slot-fill + budget repair** | `planner/PlannerService.java` | The algorithm *shape* is exactly "one item per required slot under a total cap": `buildPlan` slot loop, `pickBest` filter chain, `spendTarget` weighted allocation, and the 3-stage `repairBudget` (down-tier optional → drop optional by priority band → down-tier protected, never drop protected). It already produces two of the five required kit states: **Complete** and **Over budget**. |
| **Import path** | `product/ProductImportService.java` | Per-row validation, taxonomy normalisation, upsert by `externalId`, newest-check-wins. ⚠️ Correction to the synthesis: it is **not** the only write path — `productRepository.save` also appears in `CatalogFreshnessService.java:111` and `RealCatalogSeeder.java:521`. |
| **Frontend shell** | `api/client.ts`, `LocaleContext`, `AuthContext`, `ConsentContext`, `planPdf.ts`, `Header`/`Footer`, `AuthGate` | Header merge order, 204 handling, `error.status` for the 402 upsell, session id — unchanged. |
| **Two-pane experience switch** | `components/Planner.tsx` | The existing `'single' \| 'apartment'` switch (aria-pressed rail, **both panes kept mounted under `hidden={}`** so switching never loses work) is the Beauty Kit \| Nail Look switch, already built and already accessible. |
| **Never-fabricate UI helpers** | `components/PlanResults.tsx` | `productImage`/`usesFallbackImage` (verified-image gate), `saleInfo`/`saleWindowEnded` (verified-discount-window gate), `isStaleProduct`, `productUrl` (returns `''` unless verified → dead links render disabled). Extract these **before** rewriting the results surface. |

### 1.3 The `imageVerified` precedent

`Product.imageVerified` is the single most important pattern in the repo for this pivot. It encodes: *a claim is false until positively verified on the source, and the UI degrades honestly when it isn't.* The entire safety tri-state (`hemaStatus`, `tpoStatus`, `professionalOnly`) is this pattern applied to ingredients. **Absence of evidence must never be encoded as a boolean `false`.**

---

## 2. Furniture-specific code

| Component | Paths | Verdict | Effort |
|---|---|---|---|
| Frontend domain vocabulary | `frontend/src/types/index.ts` (`Retailer`/`RoomType`/`StyleType`/`ProductCategory` unions) | replace | L — hardest coupling in the seam; three `Record<ProductCategory,…>` total-mapped types depend on it (`icons.tsx`, `PlanResults.tsx`, `planner.ts`) |
| Furniture input form | `PlannerForm.tsx` (room/style/vibe/size-m² tables) | replace | L |
| Furniture results surface | `PlanResults.tsx` (room→category coverage, 3-tier compare, kitchen branch) | replace | L |
| Multi-room / move-in | `MoveInPlanner.tsx`, `moveInPlan.ts`, `multiRoom.ts`, `check-move-in.mjs`, 5 `MoveIn*`/`AdjustRoom` DTOs | retire | M |
| Dimension detector | `utils/dimensions.ts` | retire | S |
| Dead code / subscription ladder | `data/products.ts` (**contains fabricated demo products — an active trap**), `Monetization.tsx`, `Hero.tsx`, `StatsStrip.tsx`, `BetaNotice.tsx` | retire | S |
| Kitchen complete-mode | `KitchenIntentClassifier`, `CompleteKitchenDto` | keep dormant | S |
| Marketplace / second-hand | `feed/MarketplaceFeed*`, `MarketplaceListingFilter` | keep dormant | S — used cosmetics are a hygiene non-starter |
| Furniture catalog + guards | 110 JSON files, 21,129 rows, 35 `*CatalogRuntimeTest` | keep dormant | — zero rows transfer |

### 2.1 Retirement is a coupled edit — do it in one commit

✅ Verified couplings that make piecemeal deletion produce a broken tree:

- `Planner.tsx:7` imports `detectMultiRoom`, `:15` imports `MoveInPlanner`, `:952` renders it.
- `check-legal.mjs:14-17` does a **top-level** `readFileSync` on `PlannerForm.tsx` and `Planner.tsx` — deleting either makes `npm run check` throw `ENOENT` instead of reporting a guard failure.
- `package.json` `"check"` chains `check-i18n && check-move-in && check-legal`.
- `utils/promptRobustness.test.ts:5` imports `multiRoom`.
- `RealCatalogSeederConfigTest:65-68` and `RealProductRuntimeCatalogTest:61-65` pin `retireLegacyLivingRoomProducts` / `stripLivingRoomTag` — so "delete the legacy helpers" breaks two green tests.

---

## 3. Missing beauty/nail domain models

Nothing in the repo expresses any of these. All are net-new.

| Missing | Why it is critical |
|---|---|
| **`BeautyBrief`** — look, occasion, finish, coverage, skin type, sensitivities, owned items, budget, strictness | The furniture `PlannerInputDto` is room/style/m²-shaped; nothing maps. |
| **`NailDesignSpecification`** — shape, length, base colour, finish, effects, accents, placement, symmetry | **The keystone.** One structure must drive the preview image, the salon brief and the material kit. |
| **Completeness graph** | The single highest-value differentiator. Furniture has `CORE_CATEGORIES_BY_ROOM` (a flat required-list) — it cannot express "gel polish REQUIRES base + colour + top + compatible lamp + removal". |
| **Compatibility graph** | Zero engine code exists. For nails it is **safety-bearing** (lamp ↔ system), not taste. |
| **Safety verdict model** | Tri-state (`VERIFIED_ABSENT` / `VERIFIED_PRESENT` / `UNKNOWN`) per restricted substance, plus `professionalOnly` and a ruleset version. |
| **Validated Kit Object** | Five-value status. `FurnishingPlanDto` has no status field at all. |
| **Canonical product ↔ retailer offer** | One flat row per retailer-product today. The spec requires the split. |
| **Shade candidate model** | Depth + undertone + confidence band, with wording rules that forbid a guarantee. |
| **Owned-item model** | Furniture has excluded-category strings only, not "I own *this specific* product". |

---

## 4. Catalog & retailer-data requirements

### 4.1 Field gaps on `Product`

~80% of the existing commerce columns transfer. Net-new, all **nullable**:

- **Commerce:** `brand`, `productLine`, `shadeName`, `shadeCode`, `variantOf`, `sizeMl`, **shipping cost / free-shipping threshold** (✅ verified: *zero* shipping references exist in the backend today).
- **Makeup:** `coverage`, `finish`, `formulaFormat`, `shadeDepth`, `undertone`, `skinTypeSuitability`, `eyeAreaSafeClaim`, `requiredApplicator`.
- **Nail:** `nailSystem`, `applicationRole` (prep/base/colour/effect/top/adhesive/removal), `curingRequired`, `recommendedLamp`, `cureTimeSeconds`, `soakOffOrFileOff`, `effectType`, `magnetRequired`, `beginnerSuitability`.
- **Safety:** `hemaStatus`, `diHemaStatus`, `tpoStatus` (tri-state), `professionalOnly`, `inciSource`, `inciVerifiedAt`, `safetyRulesetVersion`.

### 4.2 The freshness bug that breaks a "Complete" claim

✅ `CatalogFreshnessService` — when it *cannot* read a page (403/anti-bot) it correctly refuses to fabricate a price, but then does `setAvailabilityStatus("check-store"); setInStock(true);` and unconditionally `setLastCheckedAt(today)`. For the probed HR beauty retailers, **unreadable is the majority case**. Every freshness and safety window must read a new `lastVerifiedAt` (success), not `lastCheckedAt` (attempt).

### 4.3 The build-breaking guard nobody planned for

✅ `CatalogHealthCountTest.java:38` `EXPECTED_TOTAL = 21_129`, asserted **exactly** three times. Registering any new snapshot in `RealCatalogSeeder` turns the build red. This must be split into a furniture total + a separate beauty floor.

### 4.4 Sourcing reality (⚠️ external research — see §6.4)

Agent research reports the major HR beauty retailers (notino.hr, douglas.hr, and the pharmacy chains) return **403**, and dm.hr is a JS-only shell — meaning **no verified makeup catalog exists without an affiliate feed**. Several smaller nail specialists were reported statically fetchable with published cure specs. **None of this is committed to the repo and none of it was verifiable from here.** It must be re-probed and the results committed as an artefact before Phase C authors a snapshot.

---

## 5. Safety-critical functionality

The gate is the product. Design constraints, in priority order:

1. **It is a Spring bean, not a static utility.** (`CatalogSourcePolicy` cannot host it — §0.)
2. **Fail closed on unknown.** A missing INCI list is `UNKNOWN`, and `UNKNOWN` never enters a consumer at-home nail kit. Absence of a listed ingredient is *not* proof of absence.
3. **Fail closed on stale.** Safety eligibility uses `isProductionVerified` semantics, decoupled from the commerce staleness policy that deliberately hedges.
4. **Enforce at the last possible moment.** Selection time **and** response assembly **and** saved-kit read **and** the public product listing.
5. **Verdicts are caches, not truth.** A stored verdict is invalid unless `safetyRulesetVersion == current` and `inciVerifiedAt` is inside the window.
6. **A ruleset that cannot be loaded kills nail-kit generation globally** rather than degrading open.
7. **The LLM never touches it.** Safety, compatibility, completeness and price are computed before any prose is generated, and the prose is validated against a denylist on the way out.

### 5.1 Regulatory content — treat as unverified

The research agent's citations (Reg. (EU) 2020/1682 for HEMA/Di-HEMA; a TPO prohibition effective 2025-09-01; CAS numbers; mandatory warning strings) come from **secondary sources — EUR-Lex was unreachable during research**. They are directionally consistent with what the spec asserts, but they are load-bearing for a safety gate and must not be hardcoded into a ruleset file on this basis. **Blocking decision — §6.5.**

---

## 6. Decisions — RESOLVED 2026-07-28

All six open questions were decided by the product owner. These are now binding constraints, not recommendations.

### 6.1 Furniture frontend — ✅ delete dead code, keep replaceable surfaces
Phase A deletes the dead code and the move-in surface in one commit with all couplings rewired. `PlannerForm`/`PlanResults` survive until Phase E replaces them, so `check-legal.mjs` never points at a missing file.

### 6.2 Non-HR translation overlays — ✅ PRESERVE
**The 12 overlays stay.** Deleting working capability is not justified by an HR-only launch. `check-i18n.mjs` is rescoped in Phase A; a separate cleanup needs its own justification.

### 6.3 Billing — ✅ dedicated Phase K, not folded into Phase J
One-time Stripe Checkout (`mode=payment`), configurable products for Beauty Kit / At-home Nail Kit / Salon Brief, purchase→entitlement mapping, webhook signature verification, idempotent + duplicate-event handling, failed/expired checkout behaviour, refunds and entitlement revocation, test-mode coverage, **no subscription assumptions in the new UX**.

Internal and closed-beta testing may run behind the existing `beta-mode` flag. **A public paid launch must not occur without Phase K.** Existing reusable Stripe infrastructure (`StripeProcessedEvent`, `BillingReconciliationService`, `StripeProperties`, the webhook plumbing) is **retained** — the change is the purchase mode and the entitlement model, not a rewrite.

### 6.4 Catalog sourcing — ✅ probe artefact first, adapters, readiness flags
The uncommitted 403/JS-only probes are **not** sufficient to conclude the Makeup Kit is impossible. Before Phase C, a **reproducible probe artefact** must be committed (§9.1). Catalog sources are **replaceable adapters**; the architecture must not couple to scraping any single retailer. Public experiences are **feature-flagged by catalog readiness** against the measurable thresholds in §9.2.

**Release order:** (a) Salon Nail Brief → (b) at-home press-ons + regular polish → (c) consumer gel polish, only after §6.5 is satisfied → (d) Makeup Kit, only after regional coverage thresholds are met.

### 6.5 INCI and at-home gel — ✅ conservative fallback
Phase C **builds the data model and rules** for gel polish. Public at-home gel recommendations stay **disabled** until every eligible SKU carries verified: exact ingredients or an authoritative INCI source; professional-vs-consumer status; restricted/banned-ingredient evaluation; manufacturer instructions; compatible base/colour/top/curing system; required prep and removal; market eligibility; source and last-reviewed date.

**Absence of an ingredient in incomplete retailer data is never "free from".** Until the bar is met, at-home nails launch with **press-ons and regular polish only**. Salon briefs may describe gel looks — they recommend no consumer chemical kit.

**Primary EU sources before encoding any regulatory rule.** Every rule carries: rule id · jurisdiction · official source reference · effective date · last-reviewed date · affected product attributes · deterministic allow/restrict/block behaviour. The secondary-source citations in §5.1 do **not** meet this bar and must be re-sourced.

### 6.6 Health data — ✅ collect nothing in MVP
**No persistent fields** for prior gel/acrylate reactions, damaged/painful/inflamed/infected nails, or any other health condition. Not in a user profile, not in a saved plan, not in an analytics event, not in LLM conversation history.

Instead: **visible general safety exclusions** shown before at-home results. If a *prompt* contains a possible reaction, injury or infection, the system must — without diagnosing — refuse to generate an at-home chemical kit, return a neutral **`Safety blocked`** result, recommend professional advice, minimise or avoid persisting that content, and document what processing still occurs.

Any future health-data collection requires its own privacy design, lawful-basis decision, explicit-consent surface, retention policy, deletion behaviour, access restrictions and legal review. **A checkbox does not solve this.**

**Consequence:** the `KitSafetyAudit` entity and its migration are **removed from Phase G**. The audit trail records the *derived* block reason and catalog/ruleset versions only — never the user's words or health state.

### 6.7 Nail specification minimum — ✅ narrowed, not dropped
The full L1–R5 addressing scheme is out of MVP, but the spec **must still support**: mirrored design; named accent fingers where supplied; accent count; and left/right symmetry or **explicitly stated asymmetry**.

### 6.8 Image preview — ✅ Design Diagram now, provider interface retained
The deterministic SVG is a **"Design Diagram"** — never called a photorealistic inspiration image. It serves structured design verification, shape and length, colour mapping, per-nail or mirrored accent placement, and a screenshot-friendly salon specification.

Phase I **retains a clean optional image-provider interface**; generative imagery is not removed from the roadmap. Any generated image must be labelled inspiration, must not promise the exact result, must **never introduce elements absent from the structured specification**, must never drive product selection, and must **fail gracefully** without blocking the brief or the kit.

### 6.9 Leakage paths — ✅ release blockers
Both are blockers, with regression tests. `GET /api/products` must never expose products failing current eligibility or safety rules. `GET /api/saved-plans/{id}` must require authorization or an explicit secure share-token flow, revalidate frozen products against current safety/recall/eligibility rules, visibly mark unavailable or newly-blocked items, and never silently recommend a recalled or ineligible frozen item. Eligibility is enforced **at selection time and again at final response assembly**.

### 6.10 Croatian "nemam" — ✅ launch-critical
Verified as broken today (§8). These must parse deterministically, with explicit negation-scope regression tests so an owned item is never confused with a missing one:

`nemam lampu` · `nemam bazu` · `nemam ništa` · `imam lampu` · `već imam lampu` · `ne trebam lampu` · `bez lampe` (where semantically valid) · and mixed statements such as **`imam lampu, ali nemam bazu ni top`**.

---

## 7. MVP vs later

**MVP:** two experiences; HR only; the five-value kit status; deterministic completeness + compatibility + budget; the consumer nail safety gate; owned-items; refinement actions (cheaper / one-store / replace / simplify); salon brief; the deterministic Design Diagram; free feasibility preview; one-time payment (Phase K).

**Gated on catalog readiness (§9.2), in this order:** salon brief → press-ons + regular polish → consumer gel polish → Makeup Kit.

**Later:** canonical↔offer split *implementation*; shade matching beyond a labelled candidate; full per-nail placement UI and advanced effect vocabulary; generative imagery behind the Phase I interface; multi-country; second-hand; price-drop alerts; reviews; any health-data collection.

**Never (in this product):** builder gel / polygel / acrylic consumer kits; professional-only SKUs in consumer kits; diagnosis; "allergy-safe" or "perfect shade" claims.

---

## 9. Catalog readiness

### 9.1 Probe artefact — required before Phase C

Committed as `docs/catalog-probes/hr-beauty-probe-<date>.json`, reproducible via a committed script. Per candidate retailer:

| Field | Notes |
|---|---|
| `requestMethod`, `runtimeEnvironment` | how it was fetched, from where |
| `httpResult` | status, redirect chain |
| `retrievable` | per-field booleans: product name · exact variant · price · stock · canonical URL · image · ingredient/INCI data |
| `stableAcrossRequests` | repeated-request result — a single 403 is not a verdict |
| `feedAvailable` | affiliate feed, product feed, or approved integration |
| `checkedAt` | ISO timestamp |
| `legalReviewRequired` | terms/robots uncertainty needing manual review |

A retailer is classified into the repo's existing vocabulary (`DIRECT_VERIFIED` / `MANUAL_VERIFIED_ONLY` / `OFFICIAL_FEED_REQUIRED`) **only** from this artefact.

### 9.2 Readiness thresholds — a vertical ships only when all are met

Measured by an automated readiness report, not judgement:

1. **Slot coverage** — every required slot in that vertical's completeness graph has **≥3 eligible SKUs** (enough for the closest-match / cheaper / beginner-friendly substitute triad).
2. **Kit assemblability** — a complete kit assembles at **every supported budget tier** for **every supported use case**, with status `Complete`.
3. **Price freshness** — **≥95%** of eligible rows have a price verified inside the beauty freshness window.
4. **Link integrity** — **≥95%** of eligible product URLs resolve (no 404, no redirect-to-home).
5. **Stock truth** — **≥90%** of required-slot SKUs report a real stock status; `check-store` fallbacks do not count toward this.
6. **Variant exactness** — **100%** of shade-bearing SKUs carry both `shadeName` and `shadeCode`.
7. **Safety completeness (nail chemical SKUs only)** — **100%** of consumer gel SKUs satisfy every §6.5 field. Anything less keeps gel disabled.

Partial coverage is reported as partial. A vertical below threshold stays feature-flagged off.

---

## 8. Corrections to figures quoted during the audit

| Claim | Truth |
|---|---|
| "21,129 rows across 128 files" | ✅ 21,129 rows across **110** files; `RealCatalogSeeder` holds **109** `/catalog/` references |
| "`PlannerService` is 2779 lines" | ✅ **2533** lines |
| "`frontend/src/messages/en.json`" | ✅ Does not exist. 12 overlays (da, de, es, fi, fr, it, nl, no, pt, sk, sl, sv); EN is inline in `i18n.ts` |
| "all three LLM clients" | ✅ Three *provider* clients (OpenAi, Anthropic, Gemini) — the count was right, `AnthropicLlmClient` was just omitted from the matrix |
| "`NegationScope` reusable as-is" | ✅ **Wrong.** `CUE_SRC` (L34-35) has no `nemam`; `NegationScopeTest:92` pins that behaviour. "nemam bazu" currently yields *no* negation and *no* have/need clause |
| "the check scripts fail the build" | ✅ **No.** `ci.yml` frontend job runs only `npm ci && npm run build` — no vitest, no check scripts |
| "`ProductImportService` is the only write path" | ✅ Three `save` call sites (also `CatalogFreshnessService:111`, `RealCatalogSeeder:521`) |
| "there is no jakarta.validation" | ✅ `spring-boot-starter-validation` is declared in `pom.xml`; the annotations are simply unused |
| `ARCHITECTURE.md` "data.sql seeds samples" | ✅ Stale — `data.sql` does not exist |
