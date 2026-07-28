# BudgetSpace AI — Architecture

> **⚠️ This repository is being pivoted.** It is the `beauty-kit-pivot` branch of a clone of BudgetSpace,
> being turned into an **outcome-to-cart planner for makeup and nails** (Croatia only). This document still
> describes the **furniture** product, which remains the working baseline while the pivot lands phase by phase.
> For the new direction read, in order:
> [`docs/superpowers/specs/2026-07-28-beauty-kit-audit.md`](docs/superpowers/specs/2026-07-28-beauty-kit-audit.md)
> then [`docs/superpowers/plans/2026-07-28-beauty-kit-pivot.md`](docs/superpowers/plans/2026-07-28-beauty-kit-pivot.md).
> Sections below are corrected where the audit found them factually stale; they are **not** rewritten for beauty yet.

Prompt-first furniture & room budget planner. The user writes a free-text wish ("Imam 1500 € za
dnevni boravak, moderno, već imam TV") and gets 3 concrete, priced shopping plans built from a
**local, web-verified product catalog**.

## Stack & dev ports
- **Frontend**: React + Vite + TypeScript (`frontend/`). Dev served on **http://localhost:5180**. Fourteen
  markets are exposed in the picker (`markets.ts`) — eleven EUR + **Scandinavia NO/SE/DK (Sprint 10.46), which
  are non-EUR**: each `MarketConfig` carries its own `currency`+`locale` and `formatCurrency` renders via
  `Intl.NumberFormat`, so NOK/SEK/DKK show in the national currency (no FX — a plan is built from one market's
  catalog and compared to a budget in the same currency). UI is localised per market via `i18n.ts` (`t('key',
  params)`) — **HR Croatian, SI Slovenian, AT/DE German, IT Italian, FI Finnish, FR French, NL Dutch, SK Slovak,
  ES Spanish, PT Portuguese, NO Norwegian, SE Swedish, DK Danish**, English as the fallback (market `SK`/lang
  `sk` = Slovakia ≠ market `SI`/lang `sl` = Slovenia). HR+EN are the inline dictionary; the rest live in
  `src/messages/{lang}.json`, **lazy-loaded** (Sprint 10.40): `import.meta.glob` code-splits each into its own
  chunk that `LocaleProvider` fetches on demand, so the main bundle (~77 kB gzip) no longer grows per market. `translate()` is a plain key lookup (English fallback until
  the chunk loads). Keys that map backend `plan.name` tiers or feed the rule-based prompt parser stay Croatian.
- **Backend**: Spring Boot 3.3.5, Java 17/21 (`backend/`). REST API on **http://localhost:8090**.
- **DB**: PostgreSQL 16 (docker) on 5432. **Dev**: `ddl-auto=create` rebuilds the schema each start and
  Flyway is **off** (`application.yml` `spring.flyway.enabled: false`); `RealCatalogSeeder` then imports the
  verified catalog snapshots. **Prod**: `application-prod.yml` turns Flyway **on** with `ddl-auto=validate`,
  so migrations own the schema. Consequence worth knowing: a broken migration is **invisible in dev** — the
  only guard is `ProdSchemaBootIT`, which silently skips unless `BUDGETSPACE_BOOTTEST_DB_URL` is set.
  *(Audit correction 2026-07-28: this previously claimed `data.sql` seeds samples — that file does not exist.)*
- **Run dev**: `docker compose up` (compose + override). Backend boots ~30-40s; wait for
  `Started BudgetspaceApplication` before using the app. DevTools auto-restart is **disabled** in the
  override (it loop-restarted under the Windows bind mount); restart the backend container manually to
  pick up backend code changes.

## Request flow (plan generation)
1. Browser `POST /api/plans/generate` (sends `Content-Type: application/json` + `X-BudgetSpace-Session`).
2. `PlanController` → `PlannerService.generate()`.
3. Prompt understanding: **AI is OFF by default** → deterministic `PlannerIntentExtractor` parses the
   prompt into `PlannerInputDto`. When AI is enabled, `PromptIntelligenceService` (provider-agnostic
   `LlmClient`, OpenAI-first) does it, with rule-based fallback on every failure. The LLM never invents
   products — it only structures the prompt.
4. `PlannerService` builds 3 plans (value / budget / stretch) by picking **real catalog products**
   from `marketCatalog(input)` (products filtered to the request's `market`), scored by style, room,
   price, colour/material, store-count, etc. Never fabricates.

## Backend packages (`backend/src/main/java/ai/budgetspace/`)
- **planner/** — `PlannerService` (core selection + budget repair + plan copy), `PlannerIntentExtractor`
  (rule-based prompt parsing), `PromptIntelligenceService` (LLM layer), `DesignAssistantService`.
- **product/** — `Product` (JPA entity), `ProductImportService` (validation + taxonomy + dedup by
  externalId), `RetailerSnapshotImportService` + `RetailerCatalogAdapter` (snapshot → import),
  `RealCatalogSeeder` (loads `/catalog/*.json` on startup; **register new catalog files here**),
  `ProductTaxonomy` (canonical categories/styles/rooms + aliases + freshness), `Markets` (HR/SI/AT/DE/…
  currency+locale), `CatalogSourcePolicy` (sourcing rule — see below), `CatalogHealthService`.
- **collector/** — `RetailerCollectorService` (controlled URL-pack fetch for IKEA/JYSK; **refuses
  feed-required retailers**), `HttpProductPageFetcher` (plain JDK HTTP, honest UA, 403 = clean fail).
- **feed/** — official/partner feed seam: `RetailerFeed` interface, `ConfigBackedRetailerFeed`
  (unconfigured by default), `RetailerFeedProperties` (env-backed, blank), `RetailerFeedImporter`
  (startup; cleanly skips unconfigured feeds, never crashes). No credentials committed. **Sprint 10.49:
  second-hand marketplace seam** — `MarketplaceFeed` (extends `RetailerFeed`; `sourceType=marketplace-listing`,
  must run rows through `MarketplaceListingFilter`, never scrape) + `ConfigBackedMarketplaceFeed` +
  `MarketplaceFeedProperties` + `MarketplaceFeedConfig` (a placeholder bean per market's classifieds — Njuškalo,
  Willhaben, Leboncoin, Wallapop, Finn, … + eBay/FB — all `OFFICIAL_FEED_REQUIRED`, feed/affiliate-ready, no data).
- **ai/** — `LlmClient` (+ `OpenAiLlmClient`/`AnthropicLlmClient`), `LlmClientFactory`, `LlmProperties`,
  `AiUsageTracker` (monthly-USD + per-day + per-session caps). Off by default.
- **saved/**, **tracking/** — saved plans; product-click / plan-feedback events.
- **pricewatch/** — *(Audit correction 2026-07-28: the Price Watch feature was **retired** in Sprint 10.188.
  `V5__drop_price_watches.sql` drops the table, and there is no `PriceWatch` entity, no `POST /api/price-watch`
  endpoint and no notifier any more.)* What remains is the deterministic price probe only: `LivePriceProbe` +
  `HttpLivePriceProbe` (raw HTTP + JSON-LD price), still used by the catalog freshness path.
- **geo/** — geo-IP market hint (Sprint 10.42): `GET /api/geo` returns the visitor's 2-letter country read
  from a CDN/proxy header (`CF-IPCountry`, CloudFront, Vercel, …) — no IP stored, no third-party call;
  `null` when no header (local/no-CDN). The frontend prefers it over the browser-locale guess on a fresh visit.
- **config/** — `CorsConfig` (allows the frontend origin + `X-BudgetSpace-Session` header),
  `AdminEndpointGuardFilter` (hides admin/collector/import endpoints in prod), `GlobalExceptionHandler`.

## Catalog & sourcing (the core domain rule)
- Verified catalog snapshots live in `backend/src/main/resources/catalog/*.json` (shape =
  `RetailerProductSnapshotDto`). `data.sql` still seeds legacy sample rows for not-yet-sourced rooms.
- **`CatalogSourcePolicy` is the single source of truth.** A 403 is never bypassed (no proxy / stealth
  / fingerprint / cookies / private endpoints). Per-retailer status:
  - `DIRECT_VERIFIED` (pages fetchable + hand-verified, in collector allowlist): IKEA, JYSK
  - `MANUAL_VERIFIED_ONLY` (verified, link-out, have products): Emmezeta, Harvey Norman, Namjestaj.hr,
    Otto, Segmüller, Poco, **Camif** (FR, 10.36), **Kenay Home + Banak Importa** (ES, 10.43), **Leen Bakker +
    Kwantum** (NL, 10.44), **Moviflor** (PT, 10.45), **Nábytok** (SK, 10.45), **+13 from the 10.48 retail
    re-sweep** (Svijetnamještaja HR, Svetpohištva SI, Conforama IT, Interio AT, Masku FI, Lovely Meubles FR,
    JOM + Sítio do Móvel PT, Miroytengo + Merkamueble + Muebles BOOM ES, Pronto Wonen NL, Drevona + ASKO Nábytok SK)
  - `OFFICIAL_FEED_REQUIRED` (403/anti-bot/JS-only/out-of-scope → only an official/partner feed, no
    products yet): Decathlon, Pevex, Lesnina, Momax, Prima Namještaj, Perfecta Dreams, Bauhaus,
    FeroTerm, Merkur, Dipo, Wayfair, Home24, Roller, Kika, Leiner, XXXLutz, **Conforama, But, Maisons du
    Monde, La Redoute, Fly, Habitat, Cdiscount, Vente-unique** (FR chains — DataDome/Cloudflare, 10.36),
    **Muebles La Fábrica** (ES — product pages reset the connection, 10.43), **Sotka** (FI — JS-only prices, 10.45)
  - Adding a retailer = add to `ProductTaxonomy.SUPPORTED_RETAILERS` + `CatalogSourcePolicy` status
    (+ `PlannerService.RETAILERS` if it has products). Most big chains are bot-blocked (confirmed by
    probing) → they wait for a feed.
- Import-source provenance (`Product.sourceType`): `manual-verified`, `public-product-page`,
  `official-feed`, `affiliate-feed` (+ legacy `manual`/`retailer-snapshot`/`future-scraper`).
- `CatalogSourcePolicy.isProductionVerified(Product)` = the verified gate (canEnterPlanner AND not
  stale AND has sourceReference AND, if feed-required, came from a feed) → excludes NEEDS_REVIEW /
  STALE / sample. Full rules: [docs/sourcing-policy.md](docs/sourcing-policy.md).
- **Markets with data**: HR (deep — every room); SI/AT/DE/IT/FI (IKEA: living-room/bedroom/home-office +
  bathroom/hallway/kitchen, SI/AT/DE also dining); **FR (IKEA all core rooms — 10.35; + Camif breadth — 10.36)**;
  **NL (IKEA + JYSK — 10.37; + Leen Bakker/Kwantum — 10.44)**; **SK (IKEA + JYSK — 10.38; + Nábytok — 10.45)**; **ES (IKEA — 10.39; + Kenay Home/Banak Importa — 10.43)**; **PT (IKEA — 10.41; + Moviflor — 10.45)**;
  **NO/SE/DK (IKEA + JYSK — 10.46, non-EUR NOK/SEK/DKK)**; JYSK adds hallway/kitchen
  for SI/DE/FI + the full NL/SK/NO/SE/DK catalogs (JYSK AT pending — jysk.at gates stock behind JS; no JYSK in
  FR/ES; jysk.nl/jysk.sk/jysk.no/jysk.se/jysk.dk static-priced — JYSK price = JSON-LD current, `priceAmount` =
  regular, sale only with a `priceValidUntil` window).
  PL/CZ/HU/RO exist in `Markets` but have **no catalog** → empty plan (non-EUR — deferred until a verified,
  currency-correct catalog exists, same recipe as NO/SE/DK). IKEA/JYSK verified per market (prices differ per
  country — never copied across markets). Emmezeta = HR only.
- **Second-hand marketplaces** (Njuškalo, FB Marketplace) are a designed-but-unbuilt future source —
  feed/API only (never scraped), with a sold/expired guard and a separate "Rabljeno" section. Design:
  [docs/marketplace-sourcing.md](docs/marketplace-sourcing.md).

## Invariants (do not break)
- **Never fabricate** product name/price/URL/image/review **or a discount** (regular price / sale %, / sale
  window). Unverifiable → `needs-review`, image null, no sale shown. A fake "−40%" is worse than none.
- **Sale tracking (Sprint 10.33).** `Product.originalPrice` = verified regular price, `Product.saleEndsAt` =
  verified promo-window end (ISO date, e.g. JYSK `priceValidUntil`); a row is "on sale" only when
  `price < originalPrice`. Both flow through `ProductDto` to the UI, which shows the dual %/€ saving + struck
  price + "On sale" badge — and hides the discount once `saleEndsAt` passes. Populated from live pages only
  (JYSK: `priceAmount`=regular, JSON-LD `price`=promo). Unverifiable → omit.
- **Images: `imageVerified` gates the real photo.** `Product.imageVerified` is true only when `imageUrl` was
  confirmed on the retailer's live product page (`og:image`, identity-checked, resolves). The UI shows the
  real photo only then; otherwise it keeps the labelled "ilustracija" category placeholder. `inferDataQuality`
  treats only a *verified* image as `complete`. HR images sourced in 10.24 (236 rows); never fabricate a URL.
- AI **off by default**; API keys **backend-only** (never `VITE_*`, README, logs, fixtures, or real
  values in `.env.example`). `.env` git-ignored.
- `reviewRating`/`reviewCount` are **display-only**, separate from the planner's internal `rating`.
- Value-first monetization: core plan free; `affiliateUrl` never replaces `originalProductUrl`;
  sponsored is labelled and never displaces the best organic pick.

## Build & test
- Backend (this Windows box): no `mvn` on PATH — use `C:\Program Files\Java\jdk-21` +
  re-downloaded `apache-maven-3.9.9` + credential-free `C:\Users\bpusic\.m2\settings-central.xml`
  (Central only). `mvn -s <settings> -f backend/pom.xml test` (no DB needed — plain JUnit/Mockito).
- Frontend: `cd frontend && npm run build` (`tsc -b && vite build`).
- See [backend/README.md](backend/README.md) and `TASKS.md`.
