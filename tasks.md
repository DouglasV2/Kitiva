# Tasks

The live backlog. One line per item: what, why it matters, how you'll know it's done. Update the status in
the same commit that changes the state — a stale board is worse than no board.

Status key: `TODO` · `IN PROGRESS` · `BLOCKED` · `DONE` · `WON'T DO`
Last reviewed: **2026-08-06**

Companions: [`architecture.md`](architecture.md) · [`memory.md`](memory.md) · [`docs/handoff.md`](docs/handoff.md)

---

## P1 — Finish the separation from BudgetSpace

### 1. Rename the Java package `ai.budgetspace` → `hr.kitiva` — `TODO`

Approved, interrupted, **nothing started**. Mechanical but wide.

Touches: every `.java` file and import · `backend/pom.xml` `groupId` / `artifactId` / `<name>` ·
`spring.application.name` in `application.yml` · the `budgetspace-backend-*.jar` glob in `backend/Dockerfile`
line 27 · image and container names in `docker-compose.prod.yml` · `README.md` §Layout · this file and
`architecture.md`.

**Do not rename these while you're in there** (they are load-bearing under their current names):
`BUDGETSPACE_BOOTTEST_DB_URL` / `_DB_USER` / `_DB_PASSWORD` (see #5), `BUDGETSPACE_ADMIN_ENDPOINTS_ENABLED`,
the `budgetspace:` config prefix in `application.yml`. Rename them in a **separate** commit, test-first, or
not at all.

Done when: `mvn clean test` green, `mvn verify` green, the app actually boots, Playwright 68/68. A missed
reference compiles fine and fails at component-scan time, so compiling is not evidence.

### 2. Confirm CI has ever run green — `TODO`

`.github/workflows/ci.yml` triggers only on `main`; all work until the separation lived on
`beauty-kit-pivot`, so **CI has never executed against this code**. The recent pushes to `main` should have
fired it.

Done when: you have looked at an actual run and can say whether `backend` and `frontend` both passed, and
specifically whether `ProdSchemaBootIT` **ran** rather than skipped.

### 3. `docker-compose.prod.yml` still configures deleted code — `TODO` *(found 2026-08-06)*

It sets `STRIPE_SECRET_KEY`, `BUDGETSPACE_GOOGLE_CLIENTID` / `_CLIENTSECRET` / `_REDIRECT_URI`,
`BUDGETSPACE_AUTH_POST_LOGIN_REDIRECT`, `BUDGETSPACE_AUTH_COOKIE_SECURE`. No auth, OAuth or billing code
exists in the backend any more (`backend/src/main/java` is `beauty/*` + `config/*` only). Container and
volume names are still `budgetspace-*`.

Why it matters: it reads as if the app takes payments and signs people in. It doesn't. Also a deploy
checklist item nobody can satisfy.

Done when: the dead env is gone, `docker compose -f docker-compose.prod.yml config` still resolves, and a
prod-image boot still works. Coordinate with #1 so the names change once.

### 4. Frontend carries furniture-era dead weight — `TODO` *(found 2026-08-06)*

Three separate things, one cleanup:

- **`npm run check` is broken.** `frontend/scripts/check-i18n.mjs` reads `frontend/src/i18n.ts`, which no
  longer exists — verified, it crashes with `ENOENT`. `check-move-in.mjs` and `check-copy-refit.mjs` are
  furniture-era too. Either delete the scripts and their `package.json` entries, or rewrite them for
  Kitiva. A `check` script that has always failed teaches everyone to ignore it.
- **12 unreferenced locale files** in `frontend/src/messages/` (`da, de, es, fi, fr, it, nl, no, pt, sk, sl,
  sv`). No application code imports them — the app is hardcoded Croatian. Their **only** reader is
  `check-i18n.mjs`, the broken script above. Keeping them implies an i18n chain that was deleted with the
  planner. Note: they are also the only artefact of the translation work, so if DE/AT (#12) is real, archive
  them rather than deleting them outright.
- **`frontend/src/banner.png` is unreferenced** — confirmed, no hit in any `.ts/.tsx/.css/.html/.json`. (The
  "banner" hits in `App.tsx` / `base.css` / `ConsentBanner.tsx` are all the consent banner.) Remove it.

Done when: `npm run check` either passes or is gone, `npm run build` green, vitest 14/14, Playwright 68/68.

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
