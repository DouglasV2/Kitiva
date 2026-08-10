# Architecture

What Kitiva is made of and why each piece is shaped the way it is. Kept current — if you change a boundary,
change this file in the same commit.

Companion files: [`tasks.md`](tasks.md) (what still has to happen), [`memory.md`](memory.md) (decisions and
the reasons behind them), [`README.md`](README.md) (the outward-facing description).

---

## 1. Shape in one paragraph

A Spring Boot 3.3 REST backend with **no database reads on the hot path** and a React 19 + Vite SPA with **no
router and no state library**. Every product the app can show is a row in a JSON file compiled into the jar.
Postgres exists for exactly one table (`nail_feedback`). There are no accounts, no sessions, no payments.
A request comes in as free Croatian text or a form selection, is turned into a specification, matched against
the in-memory catalog, validated for completeness and safety, and returned in one response.

```
browser ──HTTP/JSON──> Spring Boot :8080 ──> in-memory catalog (JSON in the jar)
                              │
                              └──JPA──> Postgres :5432   (nail_feedback ONLY)
```

## 2. Backend

Root package is `hr.kitiva`. Two packages carry the product, one carries cross-cutting HTTP concerns.

### 2.1 `beauty/nail` — the nail vertical

The pipeline is deliberately **two calls, not one**, because the user has to answer a question in between.

```
POST /api/nail/parse
  prompt (Croatian free text) + optional budget
    → NailIntentExtractor.parse()          intent → NailLookBriefDto (editable)
    → NailDesignDiagramRenderer.render()   brief.design → SVG string
  ← brief, designDiagramSvg, needsExecutionModeAnswer, healthConcernDetected,
    forbiddenSystemRequested, gelRequested

            ── user edits the brief, picks SALON or AT_HOME ──

POST /api/nail/generate
  brief (possibly EDITED — this is authoritative) + executionMode + refinements
    SALON   → NailSalonBriefBuilder.build()   text brief, NO prices, NO shopping
    AT_HOME → NailKitAssembler.assemble()     priced kit, validated for completeness
  ← executionMode, brief, svg, salonBrief | kit, blockedReasonHr, singleStoreOptions

POST /api/nail/feedback   → nail_feedback (the only DB write in the app)
```

Why parse and generate are separate: salon and at-home produce fundamentally different artefacts, and one of
them recommends chemical products to a consumer. A regex must not guess that branch — the user answers it.

Why refinements ("Replace this", "Make it cheaper", "Use one store") travel **inside** the generate request
rather than on a mutate-the-kit endpoint: a refined kit is re-derived from the brief and re-validated from
scratch, so a swap can never leave the kit in a state nobody checked for completeness. Refreshing recomputes
the same totals instead of replaying a stored diff. The undo in the UI is client-side history over these
stateless calls.

| Class | Job |
|---|---|
| `NailIntentExtractor` | Croatian prompt → `NailLookBriefDto` + flags (health concern, forbidden system, gel requested) |
| `NailDesignResolver` | fills the design spec's gaps, records each guess as an `Assumption` |
| `NailDesignDiagramRenderer` | server-side SVG of the design. **Still tested, no longer displayed** — see `tasks.md` #6 |
| `NailKitAssembler` | slot → product matching, completeness validation, totals, `ValidatedKit` |
| `NailSalonBriefBuilder` | the no-prices professional brief, carrying the assumptions as text |
| `NailPilotCatalog` | loads `catalog/nail-pilot-hr.json` once at startup, serves it from memory |
| `NailCapabilityEvidence` | proves a capability only from the retailer's own published title |
| `NailCatalogFreshness` | how old the capture is, surfaced to the UI |
| `NailFeedback` / `Repository` | the single JPA entity |

### 2.2 `beauty/makeup` — the makeup vertical

One controller, three endpoints, no parsing stage — the user picks from 7 fixed looks instead of typing.

```
GET  /api/makeup/looks     the 7 looks + their metadata
GET  /api/makeup/catalog   server-side filtering: facets, search, paging (194 products, 16 categories)
POST /api/makeup/kit       look + budget + finish + owned → kit, per-row swaps, totals, application order
```

Filtering lives on the **server**, not in the browser, so the browser never has to hold 194 rows with
descriptions and shade lists. The response is gzipped by Tomcat above 1 KB.

### 2.3 `beauty/safety` — the consumer gate

`ConsumerNailSafetyPolicy` reads `safety/eu-substance-rules-v1.json` (`RegulatoryRuleset` → `RegulatoryRule`,
`SubstancePresence`) and returns a `SafetyDecision` per product. This is what makes "burgundy cat-eye" stay
unbuyable: every cat-eye product on the Croatian shelf is a UV/LED gel, and the pilot will not recommend one
to a consumer. The app then **names the missing capability** rather than shipping ordinary lacquer under the
same word.

### 2.4 `config` — six servlet filters and an error policy

Ordered filters, all `OncePerRequestFilter`: `MalformedOriginGuardFilter`, `RequestSizeLimitFilter`,
`RateLimitFilter` (`ClientIp` resolves the caller behind N trusted proxies), `SecurityHeadersFilter`,
`AdminEndpointGuardFilter` (admin surface off by default, pinned off in prod).
`GlobalExceptionHandler` + `TomcatErrorHardeningConfig` + the `server.error.include-*: never` block in
`application.yml` exist together because DevTools on the dev classpath flips stack traces to ALWAYS, and a
500 thrown *inside a filter* bypasses both `@RestControllerAdvice` and the Tomcat ErrorReportValve.

### 2.5 Data

**Catalog — JSON in the jar, not database rows.**
`backend/src/main/resources/catalog/nail-pilot-hr.json` (63 products) and `makeup-pilot-hr.json`
(194 products, 16 categories). Each artefact carries `pilotVersion`, `capturedAt`, `sources`, `slotCounts`,
`systemCounts` and an `honesty` block (`handVerified`, `statement`, `knownGaps`, `carriedForward`). Loaded
once at startup. A catalog change is a **code change** and goes through review and the test suite — which is
the point.

**Postgres — one table.** `nail_feedback`, created by `V7__nail_feedback.sql`.
Dev: `ddl-auto=create`, Flyway **off**. Prod: `ddl-auto=validate`, Flyway **on** and owning the schema.

**Flyway V1–V6 build a furniture schema this app never reads, and must stay.** Proven against a live
Postgres: `V6` ALTERs `public.products`, which only `V1` creates, and validate-on-migrate fails on an
applied-but-missing migration — so renumbering breaks every deployed database. Do not tidy them.

## 3. Frontend

React 19, Vite, TypeScript. **No router, no Redux/Zustand, no i18n runtime** — the UI is hardcoded Croatian.
(`src/legal.ts` is the one multilingual file left, and it is a problem rather than a feature — see `tasks.md`
#4c.)

```
main.tsx → App.tsx
             ConsentProvider
               AppShell            two tabs, BOTH PANES STAY MOUNTED
                 NailLook.tsx      the nail vertical, calls api/nail.ts
                 MakeupLook.tsx    the makeup vertical, calls api/makeup.ts
                 BeautyFooter      trust bar + legal links + privacy settings
               ConsentBanner       non-modal, only when a GA id is configured
               LegalModal          lazy-loaded
components/beauty/
  HandPreview.tsx      the SVG hand actually shown in the browser
  handFromSpec.ts      design spec → the geometry HandPreview draws
  HandPreviewGallery.tsx
  shared.tsx           the parts both verticals use
```

Both panes stay mounted (`hidden` rather than unmounted) so switching tabs never discards a parsed brief, a
generated kit, or a set of catalog filters someone spent a minute assembling.

The consent chain is legally load-bearing: `ConsentContext` gates analytics, and the footer's "Postavke
privatnosti" button is the **only** way to reopen the banner. Withdrawal has to be as easy as granting —
do not drop that button while keeping the provider.

Styling: `base.css` (tokens + the consent/legal chrome) + `nailkit.css` + `makeupkit.css`. No framework.

## 4. Catalog capture (`scripts/`)

| Script | Does |
|---|---|
| `build-nail-pilot-catalog.mjs` | re-captures the nail catalog from published endpoints |
| `build-makeup-pilot-catalog.mjs` | same for makeup |
| `nail-coverage-matrix.mjs` | prints the 56-cell capability grid (**backend must be running**) |

Sources: Golden Rose HR (Shopify `/products.json`), dm.hr (published search service),
beauty-shop.hr (WooCommerce Store API). Honest user-agent, hard throttle, one long backoff on 429, then the
term is abandoned. Retailers that block us (Müller HR, Sephora HR, socap.hr, notino.hr — 403;
beauty-shop.hr — Imunify360) are recorded and left alone, never worked around.

A rebuild re-fetches **everything** and can add rows while silently deleting a capability. Always diff, and
report the delta in **cells**, not product count.

## 5. Invariants the tests enforce

These are not style preferences. A test fails if any of them breaks.

1. **No product has a rating.** Neither source publishes one. `rating: null` everywhere. An invented star
   rating would be the most persuasive lie this app could tell.
2. **No vegan / cruelty-free tag.** Regulated claims, no evidence in any feed.
3. `budget` / `mid` / `premium` **is** allowed — terciles of real captured prices per category — and every
   tag carries `provenance: "published" | "derived"`.
4. **A capability counts only when the retailer's own published title says so.** A shade number ("ICE 2",
   "Silky 12") proves nothing.
5. **A kit says Incomplete when it is**, and names the missing capability.

`docs/nail-mvp-test-set.md` is a frozen contract: 12 prompts that must produce a buyable kit, 4 that must
refuse, with the exact Croatian each refusal shows.

## 6. Test topology

| Suite | Count | Runs |
|---|---|---|
| Backend JUnit | 127 | `./mvnw test` |
| `ProdSchemaBootIT` | 1 IT | `./mvnw verify` + `BUDGETSPACE_BOOTTEST_DB_URL` set, else **silently skips** |
| Playwright e2e | 68 | desktop + Pixel 7; **dev server must be started manually** |
| vitest | 14 | `npm test` |

Nothing is mocked — the tests drive the real catalog, so a retailer changing its feed fails the build rather
than the product.

## 7. Deploy

`docker-compose.yml` + `.override.yml` = the dev loop. `docker-compose.prod.yml` builds real images and runs
the `prod` Spring profile. Three variables have **no default on purpose**: `POSTGRES_PASSWORD`,
`CORS_ALLOWED_ORIGINS`, `VITE_API_BASE_URL`.

**The API must be HTTPS.** The frontend nginx image ships `connect-src 'self' https:`; point it at a plain
`http://` backend and every call is blocked before it leaves the browser, and the UI looks exactly as if the
backend were down. `docs/nail-pilot-staging.md` has the verified minimal deploy.

## 8. Known architectural debt

Tracked with owners and evidence in [`tasks.md`](tasks.md). In short: `src/legal.ts` still tells users their
data may go to Stripe, Gemini and eBay, none of which this app touches (#4c, and `npm run check` fails on it
deliberately); two nail renderers exist and will drift (#6); the compose files still carry billing, OAuth, LLM
and eBay env for code that no longer exists (#3); and nothing in either catalog is hand-verified (#7).
