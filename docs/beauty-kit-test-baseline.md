# Test baseline — Beauty Kit pivot

**Recorded:** 2026-07-28, Phase A
**Commit baselined:** `8a536db` ("catalog: 879 real JYSK discounts, 349 stale prices fixed, 17 dead rows retired (10.192)")
**Command:** `mvn -s C:\Users\bpusic\.m2\settings-central.xml -f backend/pom.xml test`

## Backend: 957 tests, 7 pre-existing failures

**These 7 failures are inherited, not introduced by the pivot.** They were present in the pristine clone
before any beauty work. They are pinned here so every later phase can prove it added no regression.

### The rule every phase B–K must satisfy

> Backend runs with **exactly the 7 failures below** and **zero new ones**.
> An 8th failure, or any change to this list that is not explained by a phase deliberately
> replacing that surface, is a regression and blocks the commit.

The **total** test count grows as phases add tests — that is expected and is not the thing being pinned.
The failure *set* is what is pinned. Progression so far:

| Phase | Total | Failures | Note |
|---|---|---|---|
| A (baseline) | 957 | 7 | inherited from `8a536db` |
| B | 971 | 7 | +14 brief/spec schema tests, zero new failures |

### The 7

| # | Test | Failing assertion | Cause |
|---|---|---|---|
| 1 | `CamifFranceCatalogRuntimeTest.camifCatalogImportsCleanlyWithVerifiedImagesAcrossCategories:49` | `planner-eligible` for `camif-fr-10036958`, `-10038469`, `-10038325` | retired rows |
| 2 | `NewRetailersCatalogRuntimeTest.newRetailerCatalogsImportCleanly:53` | `Expecting value to be true but was false` | retired rows |
| 3 | `RetailSweepCatalogRuntimeTest.retailSweepCatalogsImportCleanlyAndAreRegistered:62` | `planner-eligible` for 3 `interio-at-*` | retired rows |
| 4 | `SkRetailersCatalogRuntimeTest.slovakRetailerCatalogImportsCleanlyAcrossCategories:45` | `planner-eligible` for 2 `nabytok-sk-*`, 1 `asko-nabytok-sk-*` | retired rows |
| 5 | `ScandinaviaCatalogRuntimeTest.scandinavianCatalogsImportCleanlyInTheirOwnCurrency:60` | `sale has end date` for `jysk-no-spisebord-alsted-*`, `jysk-no-skrivebord-tamholt-*` | discount without verified window |
| 6 | `PlannerStateTransitionTest.retailerExclusionIsPerRequest_andRemovable:168` | expected `IKEA`, got `["JYSK","Emmezeta","Namjestaj.hr"]` | plan composition shifted |
| 7 | `AiFallbackAndPlannerApiTest.integration_deBedroom_currencyAndMarketCorrect:208 → assertHealthyPlan:293` | `[image for Tischlampe MARKUS Ø27xH36cm grau] Expecting not blank but was: ""` | plan composition shifted |

### Root cause (investigated 2026-07-28, Phase A)

Commit `8a536db` changed catalog **data** without updating the **guards** that pinned the old expectations:

1. **17 dead-link rows were retired** by setting `availabilityStatus: "unavailable"` across 7 snapshot files
   (`real-at-retailers-2` 3, `real-camif-fr` 3, `real-discovery-retailers-10-167` 5, `real-es-retailers-2` 2,
   `real-harvey-norman` 1, `real-sk-retailers` 2, `real-sk-retailers-2` 1).
   `ProductTaxonomy.canEnterPlanner` correctly rejects `unavailable`
   ([ProductTaxonomy.java:349](../backend/src/main/java/ai/budgetspace/product/ProductTaxonomy.java)), but the
   `*CatalogRuntimeTest` guards assert `allSatisfy(planner-eligible)` over **every** row in a snapshot, including
   rows that were deliberately retired. Failures 1–4.
2. **879 JYSK discounts were added**, of which ≥2 JYSK NO rows carry a sale price with no `priceValidUntil`,
   violating the repo's own "no discount without a verified promo window" invariant. Failure 5.
3. **Plan composition shifted** as a knock-on of 1 + 2, breaking two tests that pin a specific retailer mix and a
   specific product's verified image. Failures 6–7.

**Ruled out** (so nobody re-investigates these): it is **not** a character-encoding problem — `backend/pom.xml`
inherits `spring-boot-starter-parent` 3.3.5, which sets `project.build.sourceEncoding=UTF-8`; the mojibake seen
while reading files on this box is PowerShell 5.1 reading UTF-8 as Cp1250 (`native.encoding = Cp1250`), a display
artifact only. It is **not** time-drift staleness — `CatalogSourcePolicy.isPlannerEligible` has no date dependency
(freshness lives in `isProductionVerified`).

### Decision

Pinned as known-red rather than repaired (owner decision, 2026-07-28). Repairing them would expand Phase A into
~8 furniture test files that the pivot was explicitly told not to redesign, and Phase E replaces most of that
surface anyway. **This is a live defect in the upstream BudgetSpace `main`** (which is in sync with `origin/main`)
and should be fixed there, independently of this pivot.

## Frontend: green

`npm run build` ✅ · `npm test` 39/39 across 6 files ✅ · `check-i18n` ✅ (rescoped, 648 keys complete for hr/en)
· `check-legal` ✅ 25 guards · `check-move-in` ✅ 29 assertions · `check-copy` ✅

⚠️ None of the frontend guards or tests run in CI today — `.github/workflows/ci.yml` runs only `npm ci && npm run build`.
Phase J wires them in.
