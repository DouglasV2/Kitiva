# Tasks

The live backlog. One line per item: what, why it matters, how you'll know it's done. Update the status in
the same commit that changes the state — a stale board is worse than no board.

Status key: `TODO` · `IN PROGRESS` · `BLOCKED` · `DONE` · `WON'T DO`
Last reviewed: **2026-08-07**

Companions: [`architecture.md`](architecture.md) · [`memory.md`](memory.md) · [`docs/handoff.md`](docs/handoff.md)

---

## P1 — Finish the separation from BudgetSpace

### 1. Rename the Java package `ai.budgetspace` → `hr.kitiva` — `DONE` *(2026-08-07)*

Done and verified. `hr.kitiva`, app class `KitivaApplication`, Maven `hr.kitiva:kitiva-backend`,
`spring.application.name: kitiva-backend`, jar `kitiva-backend-0.1.0.jar` (the `backend/Dockerfile` glob was
moved with it), npm package `kitiva-frontend`, plus the app shell — see #4b.

Evidence, because compiling is not evidence:

| Check | Result |
|---|---|
| `mvn clean test` | **127/127**, BUILD SUCCESS, exit 0 |
| `mvn package` | produces `kitiva-backend-0.1.0.jar`, matching the Dockerfile glob |
| Real boot | `Started KitivaApplication`, `/actuator/health` → `{"status":"UP"}` |
| `POST /api/nail/parse` | 200, 13 433-char SVG, editable brief |
| `POST /api/nail/generate` | 200, `COMPLETE_WITH_ASSUMPTIONS`, 6 real priced products |
| `GET /api/makeup/looks` · `/catalog` | 200, all 7 looks, catalog serving |
| `npm test` · `npm run build` | 14/14 · type-check + build green |
| `npx playwright test` | **68/68** desktop + Pixel 7 |

**Left under their old names on purpose** — renaming them is a separate, test-first commit or nothing:
`BUDGETSPACE_BOOTTEST_DB_URL` / `_DB_USER` / `_DB_PASSWORD` (#5), `BUDGETSPACE_ADMIN_ENDPOINTS_ENABLED`, the
`budgetspace:` config prefix in `application.yml`, the Postgres database/user/volume names, and the
`budgetspace-*` container names in the compose files. The database ones are not cosmetic: changing them
orphans every existing volume, including CI's.

Found while doing it: the stale `spring-boot:run` on port 8080 was still serving
`ai.budgetspace.BudgetspaceApplication` — a class that no longer exists in the tree. It was stopped, and the
suites above ran against the renamed jar. **If you are verifying anything on 8080, check what is actually
listening first**; it is very easy to test deleted code and call it a pass.

Also fixed in passing: `KitivaApplication`'s comment claimed `@EnableScheduling` drove retention, catalog-audit
and billing crons. Those went with the furniture. One `@Scheduled` remains — `RateLimitFilter`'s bucket cleanup.

### 2. Confirm CI has ever run green — `TODO`

`.github/workflows/ci.yml` triggers only on `main`; all work until the separation lived on
`beauty-kit-pivot`, so **CI has never executed against this code**. The recent pushes to `main` should have
fired it.

Done when: you have looked at an actual run and can say whether `backend` and `frontend` both passed, and
specifically whether `ProdSchemaBootIT` **ran** rather than skipped.

**Blocked on tooling from this machine** *(2026-08-07)*: the `gh` CLI is not installed, so the run list cannot
be read from here. Either install it, or open
<https://github.com/DouglasV2/Kitiva/actions> in a browser. Note also that nothing on `kitiva-standalone` has
reached CI yet — the branch is ahead of `origin/main` and the workflow only triggers on `main`.

### 3. The compose files still configure deleted code — `TODO` *(scope widened 2026-08-07)*

Bigger than first recorded, and it is **both** `docker-compose.prod.yml` and `docker-compose.override.yml`:

- billing — `STRIPE_SECRET_KEY`, `BUDGETSPACE_BETA_MODE`, `BUDGETSPACE_PLUS_FREE_SAVED_LIMIT`
- sign-in — `BUDGETSPACE_GOOGLE_CLIENTID` / `_CLIENTSECRET` / `_REDIRECT_URI`,
  `BUDGETSPACE_AUTH_POST_LOGIN_REDIRECT`, `BUDGETSPACE_AUTH_COOKIE_SECURE` / `_SAMESITE`
- an LLM budget — `BUDGETSPACE_AI_ENABLED`, `_LLM_PROVIDER`, `_AI_MONTHLY_BUDGET_USD`,
  `_AI_MAX_REQUESTS_PER_DAY` / `_PER_SESSION`, `_AI_DAILY_GUEST` / `_DAILY_FREE`
- an eBay integration — `BUDGETSPACE_MARKETPLACEFEEDS_EBAY_CLIENTID` / `_CLIENTSECRET`

None of it has any code behind it: `backend/src/main/java` is `beauty/*` + `config/*` only. It reads as if the
app takes payments, signs people in and calls an LLM. It does none of those.

Done when: the dead env is gone from both files, `docker compose -f docker-compose.prod.yml config` still
resolves, and a prod-image boot still works. Leave the Postgres database/user/volume names alone (see #1).

### 4. Frontend furniture-era dead weight — `DONE` *(2026-08-07)*

Removed: the **12 locale files** in `src/messages/` (`da, de, es, fi, fr, it, nl, no, pt, sk, sl, sv`) and
`src/banner.png`, plus the `check-i18n.mjs`, `check-move-in.mjs` and `check-copy-refit.mjs` scripts and their
`package.json` entries.

The earlier note here said to archive the locale files rather than delete them, on the theory that they were
the only artefact of the translation work and might seed DE/AT (#12). **That was wrong** — they are 697 keys
of *furniture planner* UI (`nav.planner` → "Planer", `planner.eyebrow` → "Einkaufsplaner", room and furnishing
copy throughout). Nothing in them describes nails or makeup. They are in the git history if anyone wants them.

`check-legal.mjs` was **rewritten rather than deleted**, because its guards are worth more to Kitiva than they
were to the furniture app: operator identity, no invented OIB/VAT number, GA stays consent-gated, `ad_storage`
never granted, and no raw user prompt in an analytics event. It had been reading `src/i18n.ts`,
`Planner.tsx`, `PlannerForm.tsx` and `PlanResults.tsx` — all deleted — so it crashed with `ENOENT` and had
**never once reported a result on this codebase**. `npm run check` now runs it alone.

Verified: `npm run build` green, vitest 14/14, Playwright 68/68.

### 4c. The privacy policy describes a different product — `BLOCKED` (owner/legal decision) *(found 2026-08-07)*

**The sharpest contradiction in the repo.** This product's whole claim is that nothing is invented, and the one
document a user is legally entitled to rely on currently states facts about data processing that are untrue.

`src/legal.ts` is shown in the app through the footer → `LegalModal`. In the **Croatian** text a user actually
reads:

- *"Stripe — naplata Design Sessiona; trenutno neaktivno (besplatna beta), pa se sada ne obrađuju podaci o
  plaćanju."* — there is no Design Session, no billing, and no Stripe anywhere in the code.
- *"eBay nije primatelj tvojih podataka — dohvaćamo javne oglase po kategoriji i tržištu…"* — this app never
  touches eBay.
- *"Neki pružatelji (Google, Stripe) mogu obrađivati podatke izvan EU-a…"* — Stripe is not involved.
- **Gemini appears 14 times** as the AI processor. There is no LLM in this app; the nail parser is a
  deterministic Croatian extractor.
- It also documents *"dijeljeni planovi"* (public shared plan links), a furniture feature that no longer exists.

The file is 1781 lines covering 14 languages (`hr, en, de, it, sl, fi, fr, nl, sk, es, pt, no, sv, da`) while
the app is Croatian-only and `legalDoc()` falls back to English for anything else.

**Deliberately not fixed here.** Rewriting a GDPR document means asserting new facts about processors,
retention and transfers on the owner's behalf — exactly the kind of invention this codebase forbids, and wrong
to guess at. It needs the owner, and probably a lawyer.

`npm run check` **fails on this today, on purpose**, with the processor names printed. That is the guard doing
its job, not a broken script — the other 18 of 19 pass. CI does not run `check` (it runs `npm ci && npm run
build`), so nothing else goes red in the meantime.

Done when: `src/legal.ts` describes Kitiva's actual processing, and `npm run check` is 19/19.

### 4b. `frontend/public/` served the furniture product — `DONE` *(2026-08-07)*

It was the most visible item on this list and the one that would have done damage the day Kitiva got a URL:
public, static, shipped by the nginx image, reachable by any visitor or crawler without a line of app code
referencing it.

Removed: `public/hr/opremanje-prvog-stana/`, `public/hr/dnevni-boravak-do-1000-eura/`,
`public/hr/popis-stvari-za-useljenje/` (Croatian furniture content titled *BudgetSpaceAI*, each `index,follow`
with a canonical to budgetspaceai.com), `public/sitemap.xml` (four URLs on a domain this deploy does not
serve), `public/seo.css` (existed only for those pages) and `public/budgetspacelogo.png`.

`public/robots.txt` no longer points `Sitemap:` at another domain — it is now just `User-agent: * / Allow: /`.
The sitemap goes back, with the real host, on the day there is a public URL (#9).

Also fixed earlier the same day, in `frontend/index.html`: the tab said *BudgetSpaceAI – Furnish Your Space
Within Budget*, with `lang="en"`, furniture OG/Twitter cards, a canonical and `og:url` to budgetspaceai.com,
and that domain's Search Console token. Now Kitiva's own Croatian identity; the canonical and `og:url` were
**removed rather than invented**, because there is still no public URL. The head also fetched three Google font
families that the beauty CSS names none of — only `Inter` appears at all, deep in the `--nk-sans` fallback
chain behind Segoe UI. Three render-blocking third-party requests, fired before the consent banner had said a
word, for one occasional fallback. Now only Inter.

Verified: `dist/` after `npm run build` contains exactly `index.html`, `favicon.svg`, `robots.txt` and the
four hashed assets — nothing else. Playwright **68/68**. Nothing in `e2e/`, `scripts/` or `src/` referenced
any removed file.

**Still open — the favicon artwork.** `public/favicon.svg` was a settee, the furniture app's icon. It is now a
placeholder built only from things the app already ships: the tile and dot use the ink and wine tokens from
`nailkit.css`, the almond is the shape `HandPreview` draws, and the dot is the one the wordmark ends on
("nokti." / "šminka."). **This is not brand artwork** — replace it when there is real artwork, and do not let
anyone mistake it for a design decision that was made.

One trap worth keeping: an SVG served as `image/svg+xml` is parsed as **XML**, where `--` inside a comment is
illegal. A first version of this file mentioned `--nk-ink` in its comment and the whole icon failed to parse —
it does not degrade, it renders nothing. Caught by parsing it in the browser rather than by looking at it.

### 5. Protect the `ProdSchemaBootIT` guard — `TODO`

`ProdSchemaBootIT` is `@EnabledIfEnvironmentVariable(named = "BUDGETSPACE_BOOTTEST_DB_URL")` and reads
`BUDGETSPACE_BOOTTEST_DB_USER` / `_DB_PASSWORD` via `envOr(...)`. `ci.yml` sets all three and the names
match today — **the guard is wired correctly right now.** (The handoff note calling the second one
`_USERNAME` was wrong; the real name is `_DB_USER`.)

The risk is one-directional: rename the vars in `ci.yml` without editing the test and the guard silently
disables itself while CI still shows green.

Done when: there is a check that fails if the guard skips in CI — e.g. `-DfailIfNoTests` on the IT, or a CI
step that greps the surefire/failsafe report for the disabled reason.

---

## P2 — Truthfulness before anyone sees a price

### 6. Two nail renderers will drift — `TODO`

`HandPreview.tsx` (React, what the browser shows) and `NailDesignDiagramRenderer.java` (server SVG, still
returned by `/api/nail/parse` and `/generate`, still tested, **no longer displayed**). Decide: retire the
Java one, or state in `architecture.md` what it's for and keep the two in sync deliberately.

Note before retiring: the Java renderer is covered by tests that also assert design-spec behaviour. Removing
it must not quietly remove that coverage.

### 7. Nothing in either catalog is hand-verified — `TODO`

Every row is `verificationMethod: "automatic"` / `dataQuality: "pilot-unreviewed"`, and the nail artefact's
own `honesty.handVerified` is `false`.

Minimum before real users see prices: a human opens the 7 rows of the demo case, checks name / price / URL /
availability, and flips those to `manual`.

### 8. beauty-shop.hr: 6 rows carried forward, unverifiable — `BLOCKED` (needs an owner decision)

The shop blocks automated reads (Imunify360 — returns HTTP 200 with a refusal body, so a naive fetch looks
successful). Its 6 gold-sticker rows are carried forward from the last successful capture with their
**original** `lastVerifiedAt` and flagged in `honesty.carriedForward`. Its images 415 from anywhere but its
own pages, so those rows carry `imageUrl: null`.

Three ways out, all need a person: the shop whitelists us · a human re-verifies the 6 URLs by hand ·
the rows come out (and the gold capability goes with them — check the coverage matrix delta first).

### 9. No public URL — `BLOCKED` (owner action: hosting account)

`docs/nail-pilot-staging.md` has the exact minimal deploy, verified end to end over HTTPS on
`localhost:8443` against the production images.

Remember: **the API must be HTTPS.** The frontend image ships CSP `connect-src 'self' https:`; an `http://`
API is blocked in the browser and the UI looks like the backend is down.

---

## P3 — Housekeeping

### 10. README says JDK 21, the build targets 17 — `TODO` *(found 2026-08-06)*

`README.md` says "Needs JDK 21". `backend/pom.xml` has `<java.version>17</java.version>`, `ci.yml` sets up
JDK 17, and `backend/Dockerfile` builds on `maven:3.9-eclipse-temurin-17` then runs on `eclipse-temurin:21-jre`.

Nothing is broken — 17 bytecode runs on a 21 JRE — but the README, the local `JAVA_HOME` (21) and the build
target disagree. Pick one story: either raise the pom to 21 and bump CI + the build image, or correct the
README. Don't leave three answers.

### 11. Coverage: 20 of 56 cells — `TODO` (ongoing)

`node scripts/nail-coverage-matrix.mjs` (backend must be up). Measure **before and after** every catalog
change and report the delta in **cells**, not product count.

---

## Deliberately not started

### 12. DE + AT markets, EN/DE translations — `WON'T DO` (for now)

The app is hardcoded Croatian throughout; the i18n chain went with the furniture planner. dm's search
service follows the same path shape per market (`/de/search/crawl`, `/at/search/crawl`), which is a cheap
first probe. **Only after HR is genuinely finished.** See #4 before deleting the leftover message files.

---

## Working rules that keep biting

- **A failing test after a catalog change may be right.** Check whether the test encoded a *shelf limitation
  as a rule* before "fixing" the code. This has happened three times: `zlatni detalj` asserted missing;
  `burgundy` falling back to the same lacquer as a colourless request; a Playwright test asserting gold was
  unbuyable.
- **Diff every catalog rebuild.** A rebuild once added rows while silently deleting the only proof of
  `CAT_EYE` and `SHAPE_COFFIN` (dm ranking churn dropped the Cat Eye and the balerina press-on sets).
- **`mvn clean` whenever migrations change.** Maven leaves removed resources in `target/classes` and Flyway
  runs the stale copies, failing with a misleading error.
- **`git status` before `git add -A`.** A subagent's sandbox directory once got swept into a commit and pushed.
- **Screenshot UI work.** Several defects were only ever visible in a picture.
