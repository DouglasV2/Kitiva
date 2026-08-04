# Nail pilot — shareable staging

The smallest deployment that lets a real person open a link on their phone and use the nail slice.

**What the nail pilot actually needs:** a Spring Boot container, a Postgres, and the static frontend bundle.
That is all. It uses **no Stripe, no Google OAuth, no Gemini and no eBay** — the prompt parser is deterministic
regex, the catalog is a JSON resource compiled into the backend jar, and the only table it writes is
`nail_feedback`. Every one of those integrations stays blank and dormant, so **there is no secret to expose**
beyond the database password.

**Cost:** free tiers on Railway/Render/Fly cover this; a €4/mo VPS covers it with room to spare. No payment is
required by the app itself, and billing is refused server-side while `BUDGETSPACE_BETA_MODE=true` (the
default).

---

## What has been verified locally

`docker compose -f docker-compose.prod.yml up -d --build` was run against this commit with the prod profile:
real images, `SPRING_PROFILES_ACTIVE=prod`, `ddl-auto=validate`, **Flyway owning the schema** (so
`V7__nail_feedback.sql` is what creates the feedback table), and the frontend served by nginx from a static
build. See "Verified" at the bottom for what was exercised.

## The three variables you must set

Everything else has a safe default. These three have none, on purpose — the app fails fast rather than
booting with a guessable password or an open CORS policy.

| Variable | Value | Why |
|---|---|---|
| `POSTGRES_PASSWORD` | a real generated password | no weak default; compose errors out if unset |
| `CORS_ALLOWED_ORIGINS` | `https://<your-frontend-host>` | prod refuses a localhost fallback |
| `VITE_API_BASE_URL` | `https://<your-backend-host>` | baked into the bundle at **build** time |

Put them in a `.env` beside `docker-compose.prod.yml` (never committed) or in the host's secret store.

## The API must be HTTPS — this bites on the first try

The frontend image ships a CSP with `connect-src 'self' https:` ([nginx.conf](../frontend/nginx.conf)). So:

- **`VITE_API_BASE_URL` must be `https://`.** Point it at a plain `http://host:port` backend and every API
  call is blocked by the browser before it leaves — the UI shows "Trenutno se ne mozemo spojiti" and the
  console says `violates the following Content Security Policy directive`. This is the CSP doing its job, not
  a misconfiguration; it just fails in a way that looks like the backend is down. Confirmed by testing.
- **Same origin is the better shape.** Terminate TLS once and route `/api/` to the backend and everything
  else to the frontend container. Then `VITE_API_BASE_URL` is that one origin, `connect-src 'self'` is
  satisfied without relying on the `https:` allowance, **and CORS stops mattering entirely**. On
  Railway/Render put both services behind one domain; on your own box, a ~10-line Caddy/nginx front:

```nginx
server {
    listen 443 ssl;
    server_name pilot.yourdomain;
    ssl_certificate     /etc/letsencrypt/live/pilot.yourdomain/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/pilot.yourdomain/privkey.pem;
    location /api/ { proxy_pass http://127.0.0.1:8090; proxy_set_header Host $host; }
    location /     { proxy_pass http://127.0.0.1:8099; proxy_set_header Host $host; }
}
```

This is exactly the topology the "Verified" section below was tested against.

## Minimum owner action

Deploying needs an account on a host and a git remote — **both are owner actions I cannot take.** Pick one:

### Option A — Railway / Render (fastest, no server to manage)
1. Push this branch to a GitHub repo you own.
2. Create a project → **Add PostgreSQL**.
3. **Add service → backend**, root `backend/`, Dockerfile build. Set `DATABASE_URL`, `DATABASE_USERNAME`,
   `DATABASE_PASSWORD` from the Postgres the host provisioned, plus `CORS_ALLOWED_ORIGINS` (fill in after
   step 4, then redeploy once).
4. **Add service → frontend**, root `frontend/`, Dockerfile build, build arg `VITE_API_BASE_URL` = the
   backend's public URL from step 3.
5. Open the frontend URL. Done — that link is what you share.

Put both services on the same registrable domain if you later want the furniture tab's sign-in to work; the
nail pilot itself does not use cookies at all, so a split host is fine for this test.

### Option B — one box you already have
```bash
cp backend/.env.example .env      # set POSTGRES_PASSWORD, CORS_ALLOWED_ORIGINS, VITE_API_BASE_URL
docker compose -f docker-compose.prod.yml up -d --build
```
Backend on `:8090`, frontend on `:8080` (override with `FRONTEND_PORT`). Front it with Caddy/nginx for HTTPS.

## The pilot requirements, and where each is enforced

| Requirement | Where it holds |
|---|---|
| Accessible without localhost | frontend container serves the static bundle; `VITE_API_BASE_URL` points the browser at the public backend |
| No payment required | `BUDGETSPACE_BETA_MODE=true` (default) → `BillingController` returns 503; no Stripe key is set |
| No secrets exposed | only `POSTGRES_PASSWORD` exists; Stripe/Gemini/Google/eBay stay blank and dormant. `VITE_*` carries a URL and nothing else |
| Safety rules active | `ConsumerNailSafetyPolicy` + the gel/extension/health-concern blocks run on **every** generate, re-checked against the brief's own prompt so an edited brief cannot smuggle past parse-time routing |
| Disclaimer visible | the gold prototype strip renders above the fold on every page load (`prototype-notice`), plus the per-kit `catalogProvenanceHr` line and the four-item trust bar |
| Product links work | every kit row links the retailer's own `productUrl` (`rel="noopener noreferrer nofollow"`, `target="_blank"`); Playwright test 3 asserts every link is `https://` |

Set `BUDGETSPACE_ADMIN_ENDPOINTS_ENABLED=false` as belt-and-braces — the prod profile already forces it.

## What to hand a tester

Give them the demo prompt first — it is the only supported prompt that exercises the accent slot, and it
proves the whole path in one go:

> **Kratki bordo almond nokti sa sjajnim završetkom i zlatnim detaljem na prstenjaku.**

Expect: salon result with a 10-nail diagram and no prices anywhere; classical-polish result **25,55 €**
across 7 rows and 3 retailers. The full frozen set — 12 prompts that must work, 4 that must honestly refuse
and the exact Croatian each refusal shows — is [nail-mvp-test-set.md](nail-mvp-test-set.md).

**Tell testers the prices are machine-read and unconfirmed.** The app says so itself on every result, and
one row (the gold sticker) currently shows *"Izvor trenutačno nije dostupan"* because beauty-shop.hr blocks
automated reads. That is the product working as designed, not a bug to report.

## Reviewing the pilot feedback

Two optional questions on each finished result write one row per answer to `nail_feedback`. Nothing reads
them back through the API, so there is no endpoint that could leak them. To review:

```sql
SELECT created_at, execution_mode, kit_status, matched_expectation, would_use, prompt
FROM nail_feedback ORDER BY created_at DESC LIMIT 100;
```

Counts, which is what you actually want after a test round:

```sql
SELECT execution_mode, matched_expectation, would_use, COUNT(*)
FROM nail_feedback GROUP BY 1, 2, 3 ORDER BY 4 DESC;
```

No session id, user id, IP or device string is stored — see `V7__nail_feedback.sql` for why.

## Verified

`docker compose -f docker-compose.prod.yml up -d --build` was run against this commit, fronted by a TLS
terminator putting the bundle and the API on one HTTPS origin, and driven through a real browser at desktop
1280×900 and Pixel 7:

- both images build from their own Dockerfiles; backend boots on the prod profile with `ddl-auto=validate`
- **Flyway applied `V1`–`V7`, all `success = t`,** on an empty database — `nail_feedback` is created by
  `V7__nail_feedback.sql`, not by `ddl-auto`
- the whole flow works through the production images: prompt → specification → salon result (copy, download,
  edit) → at-home result (regular polish and press-on) → feedback. **No console errors, no horizontal
  overflow on either viewport.**
- feedback answers reached the database — verified with the review query above, rows present with
  `execution_mode`, the answer, the prompt and a timestamp, and **no identifier of any kind**
- the prototype disclaimer renders on load, the safety blocks still fire, and product links resolve to the
  retailers' own `https://` URLs

What is **not** verified, because it needs an account I cannot create: a public URL. Everything above ran on
`localhost:8443` against the same images a host would run.
