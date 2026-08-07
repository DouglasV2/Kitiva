# Handoff prompt for the next session

Copy everything below the line into a fresh chat.

---

Continue work on **Kitiva** in `D:\bpusic\Downloads\beauty-kit-ai` (branch `kitiva-standalone`, pushed to
`origin/main` = https://github.com/DouglasV2/Kitiva.git — local and remote are identical at `b5022af`).

Croatian-language beauty product, HR market only, EUR. Read `README.md`, `docs/nail-catalog-coverage.md`
and `docs/nail-mvp-test-set.md` first.

## The one rule that overrides everything

**Never fabricate a product, price, URL, image, rating or capability.** Every catalog row is captured from
a PUBLISHED retailer endpoint (Shopify `/products.json`, dm's search service, WooCommerce Store API).
Honest user-agent, hard throttle, one long backoff on 429, then abandon the term. Retailers that block us
(Müller HR, Sephora HR, socap.hr, notino.hr — all 403; beauty-shop.hr — Imunify360) are recorded and left
alone, never worked around.

Enforced by tests, do not weaken:
- **No product may have a rating.** Neither source publishes one. `rating: null` everywhere.
- **No vegan / cruelty-free tags.** Regulated claims, no evidence in any feed.
- `budget`/`mid`/`premium` IS allowed — terciles of real prices per category — and every tag carries
  `provenance: "published" | "derived"`.
- A capability counts only when the retailer's own published title says so. A shade number ("ICE 2",
  "Silky 12") proves nothing.

## State: both verticals work, the repo is beauty-only

**Nail** — prompt → editable spec → salon brief (diagram, spec, Copy, PNG, zero prices) or at-home kit
(status, essential/optional/owned, retailer, URL, verified price, thumbnails, totals, Priprema, Skidanje,
assumptions, exact missing list). Refinements: Replace this / Make it cheaper / Use one store + undo.
Catalog **63 products**, coverage **20/56 cells** (`node scripts/nail-coverage-matrix.mjs`, backend must be up).

**Makeup** — 7 looks (natural-everyday, clean-girl, soft-glam, date-night, full-glam, bridal, bold-evening)
→ budget, finish, what you own → kit with per-row swap, totals, application order. Plus a server-filtered
catalog browser with facets and search. Catalog **194 products**, 16 categories.

**BudgetSpace (the furniture app) has been removed** — 583 files, ~332k lines. Backend is `beauty/*` +
`config/*` only; frontend is 22 files. No accounts, no payments, no router, no i18n (everything is
hardcoded Croatian).

## Test baseline — all green, keep it that way

`backend 127/127` · `Playwright 68/68` (desktop + Pixel 7) · `vitest 14/14`

There are **no known-failing tests any more**. The 7 furniture failures that used to be tolerated went with
the furniture code. If something fails, you broke it.

## Environment gotchas — each of these cost time

- **Maven is NOT on PATH**: `D:\bpusic\Downloads\apache-maven-3.9.12-bin\apache-maven-3.9.12\bin\mvn.cmd`
  with `JAVA_HOME=C:\Program Files\Java\jdk-21`, `-s C:\Users\bpusic\.m2\settings-central.xml`, `-o` works.
- **Ports**: backend 8080, frontend dev 5173, Postgres in docker on 5432 (`bk-demo-db`).
- **Playwright needs the dev server started manually** — `playwright.config.ts` has no `webServer` block.
  Start it, confirm `curl localhost:5173` returns 200, then run, or every test fails confusingly.
- **`mvn clean` is mandatory when migrations change.** Maven does not delete removed resources from
  `target/classes`, so Flyway runs the stale copies and fails with a misleading error.
- **`npm test` (vitest) intermittently dies** with a Windows temp-write error and reports fewer files.
  It's a flake — just re-run.
- **The Bash tool is Git Bash, not PowerShell.** PowerShell here-strings (`@'…'@`) leak literally into
  commit messages. Use a heredoc, or write a Python patch script to a file for multi-line edits.
- **Check `git status` before `git add -A`.** A subagent's sandbox directory once got swept into a commit
  and pushed.
- **dm.hr rate-limits hard**: 18s between queries, one 45s backoff, then abandon the term.
- **The frontend nginx image ships CSP `connect-src 'self' https:`.** An `http://` API is blocked by the
  browser and the UI says "ne možemo se spojiti" as if the backend were down. Staging must be HTTPS.

## Priority 1 — finish the package rename (approved, interrupted mid-way)

The Java package is still `ai.budgetspace` and the app class is `BudgetspaceApplication`. Rename to
`hr.kitiva` (or whatever you prefer) — mechanical but touches every file, every import, the Maven
`groupId`/`artifactId`, `application.yml`'s `spring.application.name`, the Dockerfile's jar name, and
`docker-compose.prod.yml`. **Nothing was started.** Verify with `mvn clean test` + a real boot + the e2e
suite, since a missed reference can compile and still fail at component-scan time.

## Priority 2 — things the separation audit found and left open

- **CI has never run on this code.** `.github/workflows/ci.yml` triggers only on `main`, and everything
  until the separation lived on `beauty-kit-pivot`. It should have fired on the recent pushes — check
  whether it passed, especially `ProdSchemaBootIT` (the prod-schema guard, runs against a real Postgres).
- **`ProdSchemaBootIT` silently skips** unless `BUDGETSPACE_BOOTTEST_DB_URL` / `_USERNAME` / `_PASSWORD`
  are set (`@EnabledIfEnvironmentVariable`). Rename those vars in `ci.yml` without editing the test and the
  guard disables itself while CI stays green.
- **Flyway V1–V6 must stay.** They build the furniture schema this app never reads. Proven against a live
  Postgres: `V6` ALTERs `public.products`, which only `V1` creates, and Flyway's validate-on-migrate fails
  on an applied-but-missing migration, so renumbering breaks every deployed DB. `nail_feedback` (V7) is the
  only table this app touches. **Do not "tidy" these.**
- **beauty-shop.hr blocks automated reads** (Imunify360, HTTP 200 with a refusal body). Its 6 gold-sticker
  rows are carried forward from the last successful capture with their original `lastVerifiedAt`, flagged
  in `honesty.carriedForward`. Either the shop whitelists us, a human re-verifies the 6 URLs, or they come
  out. Its product images also 415 from anywhere but its own pages, so those rows carry `imageUrl: null`.
- **Nothing in either catalog is hand-verified.** Every row is `verificationMethod: "automatic"` /
  `dataQuality: "pilot-unreviewed"`. Before real users see prices, a human should check at least the 7 rows
  of the demo case and flip those to `manual`.
- **No public URL yet** — needs a hosting account (owner action). `docs/nail-pilot-staging.md` has the
  exact minimal deploy; it was verified end to end over HTTPS on `localhost:8443` with the prod images.
- **Two nail renderers now exist.** `HandPreview.tsx` (React, what the browser shows) and
  `NailDesignDiagramRenderer.java` (server SVG, still returned by the API and still tested, but no longer
  displayed). Decide whether to retire the Java one — two renderers will drift.

## Not started, deliberately

DE + AT markets and EN/DE translations. The app is hardcoded Croatian throughout (the furniture i18n chain
was deleted with the planner). dm's search service follows the same path shape per market
(`/de/search/crawl`, `/at/search/crawl`) — cheap first check. Do this only after HR is genuinely finished.

## How to work

- Measure catalog changes with `node scripts/nail-coverage-matrix.mjs` **before and after**, and report the
  delta in **cells**, not product count.
- When a test fails after a catalog change, check whether the test encoded a **shelf limitation as a rule**
  before "fixing" the code — this has happened three times (`zlatni detalj` was asserted missing;
  `burgundy` fell back to the same lacquer as a colourless request; a Playwright test asserted gold was
  unbuyable).
- Rebuild catalogs with `node scripts/build-nail-pilot-catalog.mjs` /
  `node scripts/build-makeup-pilot-catalog.mjs`. Both re-fetch everything; **diff the result** — a rebuild
  can add rows while silently deleting a capability (dm's ranking churn once dropped the only Cat Eye and
  balerina press-on sets, which were the only proof of `CAT_EYE` and `SHAPE_COFFIN`).
- Verify UI work by screenshotting it. Several defects this session were only visible in a picture.
