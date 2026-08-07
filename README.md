# Kitiva

Croatian beauty kit planner. You describe the look you want; you get either a **complete, priced shopping
list of real products from real Croatian shops** — or an honest explanation of exactly which part cannot be
bought here.

Two verticals, one shell:

| | |
|---|---|
| **Nail Look / Nail Kit** | Free text → an editable specification → a salon brief (no shopping) or an at-home kit |
| **Makeup Look / Makeup Kit** | Pick a look → budget and what you own → a kit across up to 16 categories |

Market: **HR only**. Currency: **EUR**. No accounts, no payments, no saved state.

---

## The rule the whole codebase is built around

**Nothing is invented.** Every product name, price, shade, description, image and link is exactly what a
retailer publishes at a public endpoint. Where a retailer publishes nothing, the field is `null` and the app
copes — it never gets filled with a plausible guess.

Three consequences you will notice immediately, and they are all deliberate:

- **No product has a rating.** Neither source publishes a review score. An invented star rating would be the
  most persuasive lie this app could tell, so the field exists and stays empty. A test fails if one appears.
- **No product claims vegan or cruelty-free.** Those are regulated claims and no feed carries evidence for
  one. A test fails if such a tag appears.
- **A kit says Incomplete when it is.** "Burgundy cat-eye" stays unbuyable because every cat-eye product in
  Croatia is a UV/LED gel, which this pilot will not recommend to a consumer. The app names the missing
  capability rather than shipping ordinary lacquer under the same word.

`budget` / `mid` / `premium` **is** honest — terciles of the real captured prices within a category — and
every tag carries `provenance: "published" | "derived"` so the UI can say which is which.

## Catalog

Machine-captured from published endpoints, throttled, with an honest user-agent, nothing bypassed. Retailers
that block us are recorded and left alone.

| Source | Feed | Gives |
|---|---|---|
| Golden Rose HR | Shopify `/products.json` | makeup backbone — a real Croatian description on every row, per-shade variants |
| dm.hr | published search service | press-ons, polish, brand breadth |
| beauty-shop.hr | WooCommerce Store API | the gold nail detail |

```bash
node scripts/build-nail-pilot-catalog.mjs
```

```bash
node scripts/build-makeup-pilot-catalog.mjs
```

Both write into `backend/src/main/resources/catalog/` and are safe to re-run. A source that refuses a run
keeps its previously captured rows rather than silently deleting a capability — see `honesty.carriedForward`
in the artefact.

## Running it

Needs JDK 21, Node 20+, Docker.

```bash
docker compose up -d postgres
```

```bash
cd backend && ./mvnw spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

Frontend on <http://localhost:5173>, backend on `:8080`, Postgres on `:5432`.

## Tests

```bash
cd backend && ./mvnw test
```

```bash
cd frontend && npm test
```

```bash
cd frontend && npx playwright test
```

Backend **127**, Playwright **68** across desktop and Pixel 7, vitest **14**. Nothing is mocked: the tests
drive the real catalog, so a retailer changing its feed fails the build rather than the product.

## Working documents

Three files at the repo root are kept current and are the first thing to read before changing anything:

- [`architecture.md`](architecture.md) — what the system is made of and why each boundary sits where it does
- [`tasks.md`](tasks.md) — the live backlog, with the evidence behind each item
- [`memory.md`](memory.md) — decisions with their reasons, and the things that cost real time to discover

Two more are worth knowing about:

- [`docs/nail-mvp-test-set.md`](docs/nail-mvp-test-set.md) — a frozen contract. 12 prompts that must produce
  a buyable kit, 4 that must refuse, with the **exact Croatian** each refusal shows.
- [`docs/nail-catalog-coverage.md`](docs/nail-catalog-coverage.md) — what the Croatian shelf can and cannot
  do, with the retailer's own words quoted for every rejection.

## Layout

```
backend/src/main/java/hr/kitiva/
  beauty/nail/      prompt parsing, design spec, kit assembly, capability evidence
  beauty/makeup/    catalog, 7 looks, kit assembly
  beauty/safety/    consumer safety gates
  config/           CORS, rate limiting, security headers, error handling
frontend/src/
  components/NailLook.tsx      the nail vertical
  components/MakeupLook.tsx    the makeup vertical
  components/beauty/           HandPreview (the SVG hand) and the parts both verticals share
scripts/            the catalog capture scripts
```

## Deploying

[`docs/nail-pilot-staging.md`](docs/nail-pilot-staging.md) has the minimal deploy, verified end to end over
HTTPS against the production images. Three variables have no default on purpose: `POSTGRES_PASSWORD`,
`CORS_ALLOWED_ORIGINS`, `VITE_API_BASE_URL`.

**The API must be HTTPS.** The frontend image ships `connect-src 'self' https:`; point it at a plain `http://`
backend and every call is blocked before it leaves, and the UI looks like the backend is down.

## History

This repository began as BudgetSpace AI, a furniture planner, and Kitiva grew out of its shell. The furniture
app has been removed and the Java package is now `hr.kitiva`, but the history is still in the log, which is why
the earliest commits talk about sofas.

Some of the old product is still physically here and is tracked in [`tasks.md`](tasks.md): the three furniture
SEO pages under `frontend/public/hr/`, a `robots.txt` and `sitemap.xml` naming budgetspaceai.com, and a set of
dead Stripe/OAuth variables in the compose files.
