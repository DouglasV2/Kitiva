# Memory

Hard-won facts that are **not** derivable from the code: decisions and the reasoning behind them, things that
cost time to discover, and the state of the world outside this repo. If you learn something here the hard
way, write it down before you fix it.

Three-file split:
[`architecture.md`](architecture.md) = *what it is* · [`tasks.md`](tasks.md) = *what's left* ·
`memory.md` = *why, and what bit us*.

> This file is the project's memory, committed and shared. It is separate from Claude's per-user memory in
> `~/.claude/projects/D--bpusic-Downloads-beauty-kit-ai/memory/`, which is machine-local recall. When a fact
> matters to anyone touching the repo, it belongs **here**.

---

## 1. The rule everything else follows

**Nothing is invented.** Every product name, price, shade, description, image and link is exactly what a
retailer publishes at a public endpoint. Where a retailer publishes nothing, the field is `null` and the app
copes — it never gets filled with a plausible guess.

This is not a coding-style preference; it is what the product is. Three consequences look like bugs and are
not:

- **No rating anywhere.** Neither source publishes a review score. An invented star rating would be the most
  persuasive lie this app could tell, so the field exists and stays empty. A test fails if one appears.
- **No vegan / cruelty-free tag.** Regulated claims; no feed carries evidence for one.
- **A kit says Incomplete when it is.** "Burgundy cat-eye" stays unbuyable because every cat-eye product in
  Croatia is a UV/LED gel, which this pilot will not recommend to a consumer. The app names the missing
  capability rather than shipping ordinary lacquer under the same word.

`budget` / `mid` / `premium` **is** honest — terciles of real captured prices within a category — and every
tag carries `provenance: "published" | "derived"` so the UI can say which is which.

A capability counts **only** when the retailer's own published title says so. A shade number ("ICE 2",
"Silky 12") proves nothing.

---

## 2. Decisions, with the reason

**Nail is two calls, not one.** `parse` stops and asks salon-or-at-home. Salon and at-home produce
fundamentally different artefacts and one of them recommends chemical products to a consumer — a regex must
not guess that branch.

**The edited brief is authoritative.** `generate` rebuilds from whatever the user corrected in the UI. That
is the entire point of making the brief editable.

**Refinements ride inside the generate request.** No mutate-the-kit endpoint. A refined kit is re-derived and
re-validated from scratch, so a swap can never leave the kit in a state nobody checked for completeness, and
a refresh recomputes the same totals instead of replaying a stored diff.

**The catalog is JSON in the jar, not database rows.** A catalog change is therefore a code change: it goes
through review, the test suite and a deploy. That friction is deliberate — it is what stops a price appearing
that no test ever saw.

**Both frontend panes stay mounted.** Switching tabs must never discard a parsed brief, a generated kit, or a
set of catalog filters someone spent a minute assembling.

**Two tabs, not two apps.** A user who wants a manicure and a user who wants a foundation are the same person
on different days.

**No accounts, no router, no state library.** Nothing here is worth signing in for yet, and a sign-in wall in
front of a pilot link is how you find out nobody wanted to sign in.

**The footer's "Postavke privatnosti" button is legally load-bearing.** It is the only way to reopen the
consent banner. Withdrawal has to be as easy as granting; dropping the button while keeping the consent
provider would leave the app able to load Google Analytics with no way out.

**Retailers that block us are recorded and left alone.** Müller HR, Sephora HR, socap.hr, notino.hr (403) and
beauty-shop.hr (Imunify360). Never worked around. Honest user-agent, hard throttle, one long backoff on 429,
then the term is abandoned.

---

## 3. Things that cost real time

**Flyway V1–V6 must stay.** They build a furniture schema this app never reads. Proven against a live
Postgres: `V6` ALTERs `public.products`, which only `V1` creates, and Flyway's validate-on-migrate fails on
an applied-but-missing migration — so renumbering breaks every deployed database. `nail_feedback` (V7) is the
only table this app touches. **Do not "tidy" these.**

**`mvn clean` is mandatory when migrations change.** Maven does not delete removed resources from
`target/classes`, so Flyway runs the stale copies and fails with a misleading error.

**The frontend nginx image ships CSP `connect-src 'self' https:`.** Point it at a plain `http://` API and
every call is blocked before it leaves the browser, and the UI says "ne možemo se spojiti" exactly as if the
backend were down. Staging and prod must be HTTPS.

**beauty-shop.hr returns HTTP 200 with a refusal body.** A naive fetch looks like a success. Its images also
415 from anywhere but its own pages, which is why those rows carry `imageUrl: null`.

**dm.hr rate-limits hard.** 18 s between queries, one 45 s backoff, then abandon the term.

**A rebuild can add rows and silently delete a capability.** dm's ranking churn once dropped the only Cat Eye
and the balerina press-on sets — the sole proof of `CAT_EYE` and `SHAPE_COFFIN`. Always diff a rebuild, and
report the delta in **cells** (`node scripts/nail-coverage-matrix.mjs`), not product count.

**A failing test after a catalog change may be right.** Three times a test had encoded a *shelf limitation as
a rule*: `zlatni detalj` asserted missing; `burgundy` falling back to the same lacquer as a colourless
request; a Playwright test asserting gold was unbuyable. Check which it is before "fixing" the code.

**`ProdSchemaBootIT` skips silently** without `BUDGETSPACE_BOOTTEST_DB_URL`. A green CI does not mean the
prod-schema guard ran. (The vars are `_DB_URL` / `_DB_USER` / `_DB_PASSWORD`, and `ci.yml` matches them today.)

**DevTools flips error responses to include stack traces.** A 500 thrown inside a servlet filter bypasses both
`@RestControllerAdvice` and the Tomcat ErrorReportValve — which once leaked a full filter-chain stack trace on
a malformed CORS `Origin`. Hence the `server.error.include-*: never` block plus `TomcatErrorHardeningConfig`.

**Verify UI work by screenshotting it.** Several defects were only ever visible in a picture.

**`git status` before `git add -A`.** A subagent's sandbox directory once got swept into a commit and pushed.

---

## 4. Environment (this machine)

| | |
|---|---|
| Repo | `D:\bpusic\Downloads\beauty-kit-ai`, branch `kitiva-standalone` → `origin/main` (`DouglasV2/Kitiva`) |
| Maven | **not on PATH**: `D:\bpusic\Downloads\apache-maven-3.9.12-bin\apache-maven-3.9.12\bin\mvn.cmd` |
| Java | `JAVA_HOME=C:\Program Files\Java\jdk-21` (the pom targets 17 — see `tasks.md` #10) |
| Maven flags that work | `-s C:\Users\bpusic\.m2\settings-central.xml -o` |
| Ports | backend 8080 · frontend dev 5173 · Postgres 5432 (docker `bk-demo-db`) |
| Shell | the Bash tool is **Git Bash**, not PowerShell. PowerShell here-strings (`@'…'@`) leak literally into commit messages — use a heredoc or a Python patch script |

**Playwright has no `webServer` block.** Start the dev server yourself, confirm `curl localhost:5173`
returns 200, *then* run — otherwise every test fails confusingly.

**`npm test` (vitest) intermittently dies** with a Windows temp-write error and reports fewer files. It's a
flake. Re-run.

---

## 5. Baseline

`backend 127/127` · `Playwright 68/68` (desktop + Pixel 7) · `vitest 14/14`

There are **no known-failing tests**. The 7 furniture failures that used to be tolerated went with the
furniture code. If something fails, you broke it.

Catalogs: nail **63 products**, coverage **20/56 cells** · makeup **194 products**, 16 categories.

---

## 6. Log

Newest first. One entry per session that changed something worth remembering.

### 2026-08-06 — working docs added, four new findings
Created `tasks.md`, `architecture.md`, `memory.md`. While mapping the repo, found and recorded (all verified,
none fixed yet): `npm run check` crashes because `check-i18n.mjs` reads a deleted `src/i18n.ts`;
12 unreferenced locale JSONs and an apparently unused `banner.png` in `frontend/src`;
`docker-compose.prod.yml` still configures Stripe and Google OAuth for code that no longer exists;
README says JDK 21 while the pom targets 17 and CI installs 17. Also corrected the handoff's claim that the
boot-guard var is `BUDGETSPACE_BOOTTEST_DB_USERNAME` — it is `_DB_USER`, and `ci.yml` already matches.

### 2026-08-06 — separation from BudgetSpace (`eea41e9`, `b5022af`)
The furniture app removed: 583 files, ~332k lines. Backend is `beauty/*` + `config/*`; frontend is 22 files.
The Java package rename to `hr.kitiva` was approved and **not started**.

### earlier — the pivot
`c7c6aef` the browser-drawn hand preview · `8a6fdc0` the makeup vertical (194 products, 7 looks) ·
`23ad73d` gold detail sourced, capability gates hardened · `d1f8491` a kit is Complete only if it can
reproduce the look.
