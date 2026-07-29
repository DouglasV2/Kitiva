# Staging the Nail slice for user testing

**Status: BLOCKED on account ownership.** Everything that can be prepared without an account is prepared.
The remaining steps need a hosting account and a database, which only the owner can create.

This uses the deployment path the repo already has (`docker-compose.prod.yml` + Railway/Render, per
[`DEPLOY.md`](../DEPLOY.md)). No new platform, no new architecture.

---

## What is already true

- The app builds to a static frontend bundle + one Spring Boot container. No extra services.
- **No secrets are required for the nail slice.** It uses no LLM, no Stripe, no Google sign-in, no analytics.
  Every one of those is off by default. A staging instance needs exactly one secret: the database password.
- The catalog ships **inside the jar** (`nail-pilot-hr.json`), so staging needs no catalog import, no
  scraping and no scheduled job.
- The safety rules travel with the code: `eu-substance-rules-v1.json` ships empty and fails closed, so gel
  polish is refused on staging exactly as it is locally.
- The app labels itself an internal prototype in the UI (see §4 of the truthfulness pass), so a tester is
  told prices and shades are unverified before they see a single product.

## Minimal owner steps (about 20 minutes)

**1. Create the hosting project.** Railway is the shortest path in `DEPLOY.md`.
   - New project → **Add PostgreSQL**. Copy the connection values it generates.
   - New service → **Deploy from GitHub repo**, root `backend/`, it will detect the Dockerfile.

**2. Set these backend variables — this is the complete list for the nail slice:**

```
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=<from the Railway Postgres plugin>
DATABASE_USERNAME=<from the plugin>
DATABASE_PASSWORD=<from the plugin>
BUDGETSPACE_REAL_CATALOG_SEED_ENABLED=false
BUDGETSPACE_ADMIN_ENDPOINTS_ENABLED=false
BUDGETSPACE_AI_ENABLED=false
APP_FRONTEND_ORIGIN=<the frontend URL from step 3>
```

`BUDGETSPACE_REAL_CATALOG_SEED_ENABLED=false` keeps the 21k furniture rows out of staging — the nail pilot
does not use them and seeding them wastes minutes of boot time. **Do not set any OpenAI, Anthropic, Google
or Stripe variable.** The slice needs none, and an unset key is one that cannot leak.

**3. Deploy the frontend.** New service → root `frontend/`, build `npm ci && npm run build`, publish `dist/`.
   Set `VITE_API_BASE_URL=<backend URL from step 2>`.

**4. Point them at each other.** Put the frontend URL into `APP_FRONTEND_ORIGIN` on the backend and redeploy.
   Both must be HTTPS on the **same registrable domain** or the session cookie is dropped — `DEPLOY.md` §3
   explains the split-host trap.

**5. Smoke-test before sharing**, in this order:
   - `POST /api/nail/parse` with the demo prompt returns a brief.
   - The at-home kit renders with real prices and working retailer links.
   - A gel request returns **Safety blocked** (proves the fail-closed ruleset shipped).
   - The primary burgundy cat-eye prompt returns **Incomplete** naming the missing gold detail.

## What I could not do, and why

Creating the account, provisioning the database and choosing the domain are ownership decisions. I have no
credentials for any hosting provider and will not create accounts on your behalf. Everything downstream of
step 1 is mechanical.

## Before this is a PUBLIC test rather than a private one

The catalog is machine-captured and **not human-approved** —
[`catalog-review-nail-pilot.json`](catalog-review-nail-pilot.json) lists all 13 demo products as
`REVIEW_REQUIRED`. Either:

- **approve them first** (open each URL, confirm price and stock, flip to `HUMAN_APPROVED`), or
- **keep the prototype banner visible**, which is what the app does today.

A moderated test with the banner up is fine and is what I recommend. An unmoderated public link with
unverified prices is not.
