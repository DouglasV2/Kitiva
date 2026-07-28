# Beauty Kit + Nail Look — Phased Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Each phase below is expanded into bite-sized TDD steps **at the start of that phase**, not up front — the phase list is the contract, the step list is per-phase working material.

**Goal:** Pivot BudgetSpace's verified-catalog planning engine into an outcome-to-cart planner for makeup and nails in Croatia, where every kit is deterministically validated for completeness, compatibility, budget and consumer safety.

**Architecture:** Clone BudgetSpace into `beauty-kit-ai`, keep the honesty machinery (verified-sourcing gate, never-fabricate invariants, deterministic parser, budget repair, LLM containment), and add four net-new layers it never needed: a completeness graph, a compatibility graph, a safety gate, and a structured Nail Design Specification that drives preview + brief + kit from one object.

**Tech stack:** Spring Boot 3.3.5 / Java 21, React + Vite + TypeScript, PostgreSQL 16 + Flyway, no new runtime dependencies.

**Baseline:** `main` @ `55af77d`, 0 ahead / 0 behind `origin/main`.

---

## Global constraints

Every task's requirements implicitly include these.

- **Launch market: Croatia only.** EUR.
- **Two experiences:** Makeup Beauty Kit; Nail Look / Nail Kit.
- **Nail branches:** salon → inspiration image + nail-tech brief (no kit); at home → preview + purchasable kit.
- **Allowed consumer at-home nail systems:** regular polish, consumer gel polish (trajni lak), press-ons. **Nothing else.**
- **Never in a consumer kit:** builder gel, polygel/acrygel, acrylic monomer, professional-only SKUs, HEMA / Di-HEMA TMHDC, TPO.
- **Real catalog products only.** Never fabricate name, price, URL, shade, ingredient, compatibility, or a discount.
- **Budget, compatibility and completeness are deterministic and computed outside the LLM.** The LLM parses prompts and phrases explanations; nothing else.
- **One Nail Design Specification** drives the preview image, the salon brief and the kit.
- **No subscription-first UX.**
- **Not in MVP:** social feed, community, beauty diary, salon booking/discovery, skin or nail diagnosis, generic chatbot, AR try-on, auto-checkout, native apps, reviews, loyalty, haircare, broad skincare, multi-country.
- **Do not redesign reusable BudgetSpace infrastructure.** In particular: **do not refactor `PlannerService`** (2533 lines, 20 test classes in its package). Write new siblings.
- Each phase = **exactly one commit**, ending in something demonstrable, with the full backend suite green.
- **Catalog sources are replaceable adapters.** No architecture may couple to scraping one retailer.
- **Every public experience is feature-flagged by catalog readiness** (audit §9.2). Below threshold ⇒ flagged off.
- **No persistent health-data fields.** No prior-reaction, nail-condition or health answers in any profile, saved plan, analytics event or LLM history (audit §6.6).
- **Absence of an ingredient in incomplete retailer data is never "free from".**
- **Primary EU sources only** for regulatory rules; each rule carries id · jurisdiction · source reference · effective date · last-reviewed date · affected attributes · deterministic allow/restrict/block.

### Release order (audit §6.4)

**(a)** Salon Nail Brief → **(b)** at-home press-ons + regular polish → **(c)** consumer gel polish (only once §6.5 is satisfied) → **(d)** Makeup Kit (only once §9.2 coverage is met).

### Standing traps (verified — check against these every phase)

| Trap | Rule |
|---|---|
| `CatalogHealthCountTest:38` asserts `EXPECTED_TOTAL = 21_129` exactly | Any snapshot registration must update it (split furniture total vs beauty floor in Phase A) |
| `RateLimitFilter.GUARDED_PREFIX = "/api/plans/"` | New `/api/kits/**` routes are unthrottled until this is extended |
| Flyway is **off** in dev (`application.yml:36`), `ddl-auto: create`; prod is Flyway + `validate` | Any phase adding a migration must run `ProdSchemaBootIT` against a throwaway Postgres **in that phase** |
| `check-legal.mjs:14-17` top-level-reads `PlannerForm.tsx` / `Planner.tsx` | Deleting either throws `ENOENT` — rewire in the same commit |
| CI runs only `npm ci && npm run build` for frontend | Frontend guards are local discipline; Phase J adds them to CI |
| `CatalogSourcePolicy` is a `final` all-static utility | The safety gate is an **injected bean**, never composed into it |

---

## Phase A — Audit + foundation

**Status: audit complete** — see [`docs/superpowers/specs/2026-07-28-beauty-kit-audit.md`](../specs/2026-07-28-beauty-kit-audit.md). This phase commits the audit plus the mechanical de-scoping that four later phases depend on. Deferring this work is what makes builds red for phases E–H.

**Files:**
- Create: `docs/superpowers/specs/2026-07-28-beauty-kit-audit.md`, `docs/beauty-sourcing-policy.md` (skeleton), this plan
- Modify: `ARCHITECTURE.md` (correct the stale `data.sql` and price-watch claims), `README.md`
- Modify: `frontend/src/markets.ts` → HR only; `frontend/scripts/check-i18n.mjs` → rescope to HR+EN (**the 12 overlay files are PRESERVED** — audit §6.2); `frontend/package.json` → drop `check-move-in` from the aggregate
- Delete (**verified zero importers**): `frontend/src/data/products.ts` (**fabricated demo products — active trap**), `Monetization.tsx`, `Hero.tsx`, `StatsStrip.tsx`, `BetaNotice.tsx`

**Migrations:** none.

**Tests:** record the green baseline (`mvn -s <settings> -f backend/pom.xml test`) **before** any edit and again after; `npm run build` + `npm run check` + `npm test` green.

**Sequencing correction (made during execution).** Two items originally listed here were moved, because doing them now would split one coherent edit across two commits:

- **The move-in / multi-room retirement moves to Phase E.** `MoveInPlanner`, `multiRoom`, `dimensions` and `moveInSeed` are one unit with the `scope` toggle in `Planner.tsx` — and Phase E reworks that exact toggle into the Beauty | Nail switch. Removing them now would mean editing `Planner.tsx`'s pane structure twice and leaving `check-move-in.mjs` inconsistent with code that still exists. Phase E deletes `MoveInPlanner.tsx`, `moveInPlan.ts`, `multiRoom.ts` + `.test.ts`, `dimensions.ts`, `scripts/check-move-in.mjs`, and in the **same commit** rewires `Planner.tsx`, `promptRobustness.test.ts`, `package.json` and `check-legal.mjs`.
- **The `CatalogHealthCountTest` split moves to Phase C.** The pinned `EXPECTED_TOTAL = 21_129` only breaks when a beauty snapshot is registered in `RealCatalogSeeder`, which happens in Phase C. Splitting it earlier is prep for a break that cannot yet occur.

Nothing is dropped — both are still owned, by the phase where the edit is coherent.

**Risks:** deleting a file that is still imported would break the build; every deletion here was verified to have zero importers first. `PlannerForm.tsx` and `PlanResults.tsx` are deliberately **kept** so `check-legal.mjs` (which top-level-reads both) stays valid.

**Demoable:** the app builds and runs, offers HR only, the furniture planner still works end-to-end, the dead subscription-pricing surface and the fabricated demo catalog are gone, and the backend suite is green with a recorded baseline number.

---

## Phase B — `BeautyBrief` and `NailLookBrief` schemas

**Files:**
- Create: `backend/src/main/java/ai/budgetspace/beauty/dto/BeautyBriefDto.java`, `NailLookBriefDto.java`, `NailDesignSpecDto.java`, `OwnedItemDto.java`, `KitStatus.java` (the five-value enum), `Assumption.java`
- Create: `frontend/src/types/beauty.ts`
- Create: `backend/src/test/java/ai/budgetspace/beauty/dto/BeautyBriefDtoTest.java`, `NailDesignSpecDtoTest.java`

**Scope discipline:** a **single `assumed` marker per field** — not a numeric confidence calculus. The spec asks for a kit with a five-value status and a "Complete with assumptions" state; that is what an assumption marker delivers. `OwnedItemDto` is defined **here**, not in Phase H, because Phase D consumes it.

**Nail spec minimum (audit §6.7).** The full L1–R5 addressing scheme is out of MVP, but `NailDesignSpecDto` **must** support: `mirrored` (boolean); `accentFingers` (named fingers where supplied — thumb/index/middle/ring/pinky); `accentCount`; and `symmetry` as an explicit enum (`MIRRORED` / `ASYMMETRIC_STATED`) so asymmetry is always *stated*, never inferred from silence.

**Migrations:** none — DTOs only.

**Tests:** Jackson round-trip incl. unknown-property tolerance (the repo's back-compat convention); every enum value has a Croatian label; budget is integer cents with an explicit euro→cent boundary test (`AmountParser` returns whole euros — this is where the conversion is pinned).

**Risks:** over-designing the nail spec. If a field cannot be rendered to *all three* outputs (image, brief, kit), it does not belong in MVP.

**Demoable:** a unit test deserialises a realistic Croatian brief ("kratki almond nokti boje višnje, cat-eye, dva zlatna detalja, do 90 €") into a fully-populated spec and re-serialises it losslessly.

---

## Phase C — Catalog, safety and compatibility architecture

The largest phase. **If it cannot be one honest commit, split at the marked seams — do not compress.**

**Entry gate — the probe artefact (audit §9.1).** Before any catalog code, commit `docs/catalog-probes/hr-beauty-probe-<date>.json` plus the script that reproduces it, recording per retailer: request method and runtime environment, HTTP result, per-field retrievability (name / exact variant / price / stock / canonical URL / image / ingredients), stability across repeated requests, feed availability, timestamp, and legal-review flag. Retailer statuses are assigned **only** from this artefact. A single 403 is not a verdict.

**Files:**
- Create: `docs/catalog-probes/hr-beauty-probe-<date>.json`, `scripts/probe-catalog-sources.mjs`
- Create: `backend/src/main/java/ai/budgetspace/beauty/catalog/BeautyCatalogAdapter.java` (**the replaceable-source seam — no logic may couple to one retailer**), `CatalogReadinessService.java`, `CatalogReadinessReport.java`
- Create: `backend/src/main/java/ai/budgetspace/beauty/safety/ConsumerNailSafetyPolicy.java` (**a Spring `@Component`, not a static utility**), `SafetyVerdict.java` (tri-state), `EuSubstanceRuleset.java`, `SafetyRulesetVersion.java`, `RegulatoryRule.java`
- Create: `backend/src/main/java/ai/budgetspace/beauty/catalog/BeautyTaxonomy.java`, `MakeupKitGraph.java`, `NailKitGraph.java`, `BeautyCompatibilityPolicy.java`
- Create: `backend/src/main/resources/safety/eu-substance-rules-v1.json`
- Modify: `backend/src/main/java/ai/budgetspace/product/Product.java` (nullable beauty columns), `ProductImportService.java` (delegate to the safety package so the write path stays single-gated), `RetailerCatalogAdapter.java`, `dto/RetailerProductSnapshotDto.java`, `CatalogSourcePolicy.java` (HR beauty retailer statuses **only** — no structural change), `ProductController.java` (**close the public `findAll` hole — route through eligibility + safety**), `config/AdminEndpointGuardFilter.java`
- Create: `backend/src/main/resources/db/migration/V6__beauty_product_columns.sql`, `V7__beauty_ingredient_records.sql`
- Create: matching tests (below)

**Seams if split:** C1 schema + migrations + taxonomy · C2 safety ruleset + policy + tests · C3 kit graphs + compatibility · C4 pilot snapshots.

**Migrations:** `V6` (nullable beauty columns on `products` — safety columns are a **cache**, invalid unless `safety_ruleset_version` is current and `inci_verified_at` is in-window), `V7` (`beauty_ingredient_records`). Both must be exercised via `ProdSchemaBootIT` against a throwaway Postgres **in this phase** — Flyway is off in dev, so a broken migration is otherwise invisible until prod.

**Regulatory rule schema (audit §6.5).** Every entry in `eu-substance-rules-v1.json` carries **all seven** fields — `ruleId`, `jurisdiction`, `officialSourceReference`, `effectiveDate`, `lastReviewedDate`, `affectedProductAttributes`, `behaviour` (`ALLOW` / `RESTRICT` / `BLOCK`) — validated by a schema test. **Rules are sourced from primary EU texts only.** The secondary-source citations gathered during the audit do not meet this bar and must be re-sourced before they enter the file.

**Gel polish is built but disabled.** The data model, kit graph and rules for consumer gel polish ship here. `beauty.nail.gel-polish-enabled` defaults **false** and cannot flip true until every eligible SKU satisfies all eight §6.5 fields, asserted by `GelPolishReadinessTest`.

**Tests:** `ConsumerNailSafetyPolicyTest` (a planted TPO fixture, a HEMA fixture, a professional-only fixture and an **`UNKNOWN`-INCI fixture** are each BLOCKED; a stale-`inciVerifiedAt` fixture is BLOCKED, not hedged); `RegulatoryRuleSchemaTest` (all seven fields present on every rule); `GelPolishReadinessTest`; `BeautyCompatibilityPolicyTest` (lamp↔system, no-wipe top for chrome); `NailKitGraphTest` / `MakeupKitGraphTest`; `CatalogReadinessServiceTest` (the seven §9.2 thresholds); **`PublicProductListingSafetyTest`** — a planted blocked fixture is absent from `GET /api/products`; `CatalogHealthCountTest` updated.

**Risks:** authoring a pilot catalog before the probe artefact exists means re-authoring it. Use **synthetic fixtures** for the policy tests here; real SKUs land only after the artefact classifies their source.

**Demoable:** the probe artefact is committed and reproducible; an executable rule set blocks planted forbidden fixtures at import, at selection and on the public listing; `GET /api/products` no longer leaks unvetted rows; the readiness report prints per-vertical pass/fail against the seven thresholds.

---

## Phase D — Prompt parsing and clarification flow

**Files:**
- Create: `backend/src/main/java/ai/budgetspace/beauty/BeautyIntentExtractor.java`, `BeautyVocabulary.java`, `ForbiddenSystemDetector.java`, `BeautyPromptIntelligenceService.java`, `BeautyCopyValidator.java`, `dto/ClarifyingQuestionDto.java`
- Create: `frontend/src/utils/beautyOutOfScope.ts` + `.test.ts`, `frontend/src/components/ClarifyingQuestions.tsx`
- Modify: `backend/src/main/java/ai/budgetspace/planner/AmountParser.java` (**additive only**: allow 2-digit budgets behind a new overload; extend the `UNIT_AFTER` guard with `ml|g|kom|nijans` so "primer 100 ml" is not read as €100)
- Modify: `backend/src/main/java/ai/budgetspace/planner/NegationScope.java` + `frontend/src/utils/negationScope.ts` — add `\bnemam\b|\bnemamo\b` to `CUE_SRC`
- Modify: `backend/src/test/java/ai/budgetspace/planner/NegationScopeTest.java:92` — re-express the pinned case
- Create: `backend/src/test/resources/prompts/beauty-prompt-matrix.json` (**a sibling file, never an edit of the furniture matrix**)

**Scope discipline:** the beauty extractor **owns its own normalizer**. Do not add a đ-fold to the shared `PlannerIntentExtractor.normalize()` — `ProductTaxonomy:387` aliases both spellings precisely because they currently differ, and 20 furniture test classes sit downstream.

**Migrations:** none.

**Croatian ownership parsing is launch-critical (audit §6.10).** `NemamRegressionTest` must pin **all** of these, each asserting the owned-vs-missing distinction explicitly:

| Prompt | Must resolve to |
|---|---|
| `nemam lampu` | lamp **missing** |
| `nemam bazu` | base **missing** |
| `nemam ništa` | all slots **missing** |
| `imam lampu` | lamp **owned** |
| `već imam lampu` | lamp **owned** |
| `ne trebam lampu` | lamp **not required** (distinct from missing) |
| `bez lampe` | lamp **excluded**, where semantically valid |
| `imam lampu, ali nemam bazu ni top` | lamp **owned**; base **and** top **missing** |

The mixed-clause case is the one that catches scope bugs — a single negation cue must not leak across the contrast conjunction and flip the owned lamp into a missing one.

**Tests:** `BeautyIntentExtractorTest` driven by the HR prompt matrix; `NemamRegressionTest` (above); `ForbiddenSystemDetectorTest` ("hoću polygel doma" routes to the safe alternative, "**ne** želim polygel" does not); a **clarification cap test** — never more than two clarification screens.

**Risks:** touching `NegationScope` and `AmountParser` is shared-file surgery. Both edits are additive and each is pinned by an existing test that must be re-expressed, not deleted.

**Demoable:** `POST` a Croatian prompt, get back a fully-parsed brief with owned items removed, out-of-scope requests flagged, forbidden systems rerouted, and at most two clarifying questions.

---

## Phase E — Makeup Kit generation

**Files:**
- Create: `backend/src/main/java/ai/budgetspace/beauty/kit/KitSelectionEngine.java` (**a NEW class reading `ProductRepository` directly — `PlannerService` is not touched**), `MakeupKitValidator.java`, `BeautyKitService.java`, `BeautyKitController.java`, `dto/ValidatedKitDto.java`, `KitItemDto.java`
- Create: `frontend/src/components/BeautyKitForm.tsx`, `KitResults.tsx`, `ProductHonesty.tsx`
- Modify: `frontend/src/components/PlanResults.tsx` (**extract** the honesty helpers into `ProductHonesty.tsx` — this is the source-file edit), `Planner.tsx` (two-experience switch, reusing the existing `hidden={}` dual-pane pattern), `i18n.ts`, `styles.css`
- Modify: `backend/src/main/java/ai/budgetspace/config/RateLimitFilter.java` (add `/api/kits/`)
- **Move-in / multi-room retirement, deferred here from Phase A** (one coherent edit with the `scope`→Beauty|Nail toggle rework): delete `MoveInPlanner.tsx`, `utils/moveInPlan.ts`, `utils/multiRoom.ts` + `.test.ts`, `utils/dimensions.ts`, `scripts/check-move-in.mjs`; in the **same commit** rewire `Planner.tsx` (imports L6/L7/L15, the `multiRoom` banner, `moveInSeed`, the render), `utils/promptRobustness.test.ts` (L5 import), `frontend/package.json` (drop `check-move-in` from the aggregate `check`), and `frontend/scripts/check-legal.mjs`
- Delete: `frontend/src/components/PlannerForm.tsx`; modify `frontend/scripts/check-legal.mjs` in the same commit

**Design point:** the free preview and the paid kit are **one deterministic computation with two projections**, not two endpoints with duplicated rules. A `Complete` preview that becomes `Incomplete` after payment is both a truthfulness failure and a consumer-law exposure.

**Migrations:** none.

**Tests:** `KitSelectionEngineTest` (one item per required slot; hard-exclusion predicates); `MakeupKitValidatorTest` (all five statuses reachable); **`PreviewParityTest`** — preview status ≡ unlocked status for the same brief; budget arithmetic in integer cents.

**Risks:** ⚠️ **this phase's demo depends on a makeup catalog that may not be sourceable** (audit §6.4). If the affiliate decision is unresolved, reorder — ship the nail branch first and gate makeup.

**Demoable:** "Složi mi kompletan svakodnevni makeup do 100 €. Već imam maskaru i kistove." → a complete kit of real HR SKUs, essential/optional split, total + remaining, honest status.

---

## Phase F — Salon Nail Brief

**Files:**
- Create: `backend/src/main/java/ai/budgetspace/beauty/nail/NailDesignResolver.java` (**sole owner of `resolve()`** — Phase B tests the DTO shape, this phase tests resolution), `NailSalonBriefBuilder.java`, `NailBriefController.java`
- Create: `frontend/src/components/NailLookForm.tsx`, `SalonBriefView.tsx`
- Modify: `frontend/src/utils/planPdf.ts`, `i18n.ts`

**Migrations:** none.

**Tests:** `NailDesignResolverTest`; **`NailSalonBriefBuilder` must not have a `ProductRepository` in its constructor** — enforce structurally (constructor signature), not only by asserting zero product names in the output; a PDF/print test that the brief carries its variability disclaimer.

**Risks:** the salon brief must not drift into recommending products.

**Demoable:** a nail look → a structured spec → a per-nail placement brief and a "show this to your nail tech" paragraph in Croatian, printable, with the variability disclaimer.

---

## Phase G — At-home Nail Kit

The phase carrying the regulatory exposure. **Ships press-ons and regular polish only** — gel polish stays behind the disabled flag from Phase C.

**Files:**
- Create: `backend/src/main/java/ai/budgetspace/beauty/nail/AtHomeFeasibilityService.java`, `NailKitAssembler.java`, `NailSafetyGate.java`, `HealthConcernDetector.java`
- Create: `frontend/src/components/SafetyDisclosure.tsx` (**general exclusions shown before at-home results — no questions asked, nothing collected**)
- Modify: `frontend/src/legal.ts`, `backend/src/main/java/ai/budgetspace/beauty/kit/BeautyKitService.java`

**No health data is collected (audit §6.6).** There is **no** `HomeProfileForm`, **no** `KitSafetyAudit` entity and **no** migration in this phase. The audit trail records only the *derived* block reason plus catalog and ruleset versions — never the user's words or health state.

`HealthConcernDetector` handles the case where a **prompt** volunteers a reaction, injury or infection. It must: not diagnose; refuse to generate an at-home chemical kit; return a neutral **`Safety blocked`** result; recommend professional advice; **not persist the triggering text** (not in a saved plan, not in an analytics event, not in LLM history); and the phase must document what processing still occurs.

**Migrations:** none.

**Tests:** `BeautySafetyAcceptanceTest` — professional-only, HEMA, TPO, `UNKNOWN`-INCI, incompatible lamp, builder-gel/polygel/acrylic request, and a **gel-polish request while the flag is off** each produce `Safety blocked` or a safe downgrade; `HealthConcernDetectorTest` — a prompt mentioning infection returns a neutral block **and a persistence assertion that the text was not stored**; a saved-kit re-validation test; a kill-switch test (an unloadable ruleset stops nail-kit generation globally).

**Risks:** the highest-consequence phase in the plan. The gel-polish flag is the control that keeps it safe — any change to its default is a product decision, not an implementation detail.

**Demoable:** "Želim elegantne press-on nokte koji izgledaju kao milky French." → a complete purchasable kit with prep, application, cleanup and removal, the safety disclosure rendered, and every forbidden path — including gel while disabled — provably refused.

---

## Phase H — Refinement, budget repair, owned items

**Files:**
- Create: `backend/src/main/java/ai/budgetspace/beauty/kit/KitRefinementService.java`, `BeautyBudgetRepair.java` (**a new sibling — do not edit `PlannerService.repairBudget`**), `OwnedItemResolver.java`, `dto/RefineKitRequest.java`
- Modify: `frontend/src/components/KitResults.tsx`, `backend/.../beauty/kit/BeautyKitController.java`, `backend/src/main/java/ai/budgetspace/saved/SavedPlanService.java` (**re-validate on read** — see the Phase G test)

**Migrations:** none.

**Tests:** `BeautyBudgetRepairTest` — a required slot is **never** dropped while the kit still claims `Complete`; "use one store" reports its trade-off in euros; "remove what I already own" recomputes total *and* status; every refinement re-runs the full validation rather than patching the response.

**Risks:** the classic failure is dropping a required item and still reporting `Complete`. That is the single assertion this phase exists to guarantee.

**Demoable:** every refinement action from the spec — cheaper, one-store, replace item, remove owned, beginner-friendly, simplify — each triggering a real re-optimisation.

---

## Phase I — Design Diagram + optional image-provider interface

**Files:**
- Create: `backend/src/main/java/ai/budgetspace/beauty/nail/NailDesignDiagramRenderer.java` (**deterministic SVG from `NailDesignSpecDto`**)
- Create: `backend/src/main/java/ai/budgetspace/beauty/image/InspirationImageProvider.java` (**interface only — no implementation, no provider credentials, no cache table**)
- Modify: `SalonBriefView.tsx`, `KitResults.tsx`, `i18n.ts`

**Naming is binding (audit §6.8).** The SVG output is a **"Design Diagram"** in code, copy and i18n keys — never "inspiration image" and never implied photorealistic. It serves structured design verification, shape and length, colour mapping, per-nail or mirrored accent placement, and a screenshot-friendly salon specification.

**The provider interface stays; the implementation does not.** Generative imagery remains on the roadmap behind `InspirationImageProvider`. When one is added later it must: be labelled inspiration; not promise the exact final result; **never introduce design elements absent from the structured specification**; never drive product selection; and **fail gracefully** — a provider outage must not block the salon brief or the shopping kit. Those five constraints are encoded as interface contract tests now, so any future implementation inherits them.

**Migrations:** none.

**Tests:** `NailDesignDiagramRendererTest` — same spec ⇒ byte-identical SVG; mirrored vs explicitly-asymmetric placement renders correctly; named accent fingers land on the right nails; **`SingleSpecInvariantTest`** — one spec drives diagram, brief and kit, and a spec change propagates to all three; `InspirationImageProviderContractTest` — a null/failing provider degrades without blocking either downstream output.

**Risks:** the diagram must not depict an effect the kit cannot achieve. It draws **only** from spec fields the material mapping can satisfy.

**Demoable:** the same burgundy cat-eye spec renders a Design Diagram, the salon brief and the at-home kit — visibly consistent, provably from one object — and the app behaves identically with no image provider configured.

---

## Phase J — Tests, browser validation, final report

**Files:**
- Create: `backend/src/test/java/ai/budgetspace/beauty/BeautyEndToEndTest.java`, `docs/superpowers/BEAUTY-KIT-FINAL-REPORT.md`
- Modify: `.github/workflows/ci.yml` (**run `npm test` and `npm run check` — they run in neither job today**), `frontend/scripts/check-legal.mjs`, `README.md`, `ARCHITECTURE.md`

**Migrations:** none.

**Tests:** full backend suite; frontend vitest + guards **in CI**; `ProdSchemaBootIT` against real Postgres for V6/V7/V8; browser validation of all five journeys (makeup kit, salon brief, at-home kit, refinement, blocked-safety path) via the Browser tool.

**Risks:** the honest risk is declaring readiness. Per your instruction, the report states what is **verified** and what is **not** — and if the HR catalog is still thin or unverified, the report says so plainly rather than claiming launch-readiness.

**Demoable:** a green CI run, five recorded browser journeys, and a final report with a truthful readiness assessment.

---

## Phase K — One-time payments and entitlements

Not folded into Phase J. **Internal and closed-beta testing may run behind the existing `beta-mode` flag; a public paid launch must not occur without this phase.**

**Files:**
- Create: `backend/src/main/java/ai/budgetspace/billing/OneTimeCheckoutService.java`, `Entitlement.java`, `EntitlementRepository.java`, `EntitlementService.java`, `BeautyProductCatalog.java` (configurable one-time products)
- Modify: `billing/BillingService.java` (**`mode=payment`, not a rewrite** — the subscription path is retained, not deleted), `BillingController.java`, `StripeProperties.java` (per-product price ids), `dto/BillingConfirmRequest.java`, `AuthContext.tsx`, `KitResults.tsx` (unlock surface — **no subscription language anywhere in the new UX**)
- Create: `backend/src/main/resources/db/migration/V6__entitlements.sql` *(number reflects actual order at implementation time)*

**Reuse, don't replace (audit §6.3).** `StripeProcessedEvent` + `StripeProcessedEventRepository` (idempotency), `BillingReconciliationService`, the webhook signature plumbing and the 402-upsell client path are all retained. What changes is the Checkout **mode** and the entitlement model — subscription status becomes a durable per-product entitlement.

**Products:** configurable one-time Beauty Kit, At-home Nail Kit and Salon Brief. Prices come from configuration, never hardcoded.

**Migrations:** `V6__entitlements.sql` — purchase → entitlement mapping, unique on (user/session, product, stripe event) so replay cannot double-grant. Exercised via `ProdSchemaBootIT` against a throwaway Postgres in this phase.

**Tests:** webhook signature verification (a forged signature is rejected); **idempotent handling** (the same event twice grants once); **duplicate-event handling** across distinct event ids for one session; failed checkout grants nothing; expired checkout grants nothing; refund **revokes** the entitlement; entitlement survives session→account upgrade; **Stripe test-mode coverage** for each product; a UX assertion that no subscription copy exists in the beauty surfaces.

**Risks:** double-granting on webhook replay, and granting on an unpaid-but-completed session. Both are pinned by tests above. The existing `beta-mode` flag must not silently grant entitlements once this ships.

**Demoable:** a Stripe test-mode purchase of each of the three products, granting exactly the right entitlement; a replayed webhook granting nothing extra; a refund revoking access.

---

## Deliberately cut (and why)

| Cut | Reason |
|---|---|
| Extracting a shared core out of `PlannerService` | 2533 lines, 20 test classes. Directly against "do not redesign reusable infrastructure". `KitSelectionEngine` is new. |
| Generative image *implementation* | The interface stays (Phase I) with its five contract constraints; only the paid provider and its cache are deferred. |
| Per-field provenance confidence calculus | The spec asks for a five-value status; one `assumed` marker delivers "Complete with assumptions". |
| Full L1–R5 per-nail addressing | Narrowed, not dropped — mirrored, named accent fingers, accent count and explicit symmetry are all in MVP (audit §6.7). |
| `RetailerFeedProperties` refactor, SSRF lift, `inferPriceTier` re-tiering | Refactors of working furniture infrastructure with no MVP consumer. |
| Deleting the 12 translation overlays | **Preserved by decision** (audit §6.2). Rescope `check-i18n.mjs` instead; a cleanup needs separate justification. |
| Canonical↔offer *implementation* | Cross-retailer comparison is *later*. The **decision** is still needed before catalog authoring (§6.4). |
| Health-data collection of any kind | **Removed entirely from MVP** (audit §6.6) — no fields, no form, no audit entity, no migration. |
| Consumer gel polish as a public recommendation | Built in Phase C, flag-disabled until all eight §6.5 data conditions are met per SKU. |
