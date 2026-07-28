-- Beauty Kit Phase C2b: beauty/nail attributes on products.
--
-- EVERY column is NULLABLE with no default, so the 21,129 existing furniture rows migrate untouched and a
-- beauty row simply populates more of them. Adding a NOT NULL column here would rewrite the whole table and
-- force a meaningless value onto every sofa.
--
-- Each column is consumed by a stated MVP rule (the consumer is named in Product.java). A column no rule
-- reads is a column nobody maintains, and an unmaintained safety column is worse than a missing one.
--
-- IF NOT EXISTS throughout, so a database that already has one of these (added by hand during development)
-- migrates cleanly. Dev never executes this file — spring.flyway.enabled=false and ddl-auto=create there —
-- which is exactly why it must be validated against a real Postgres via ProdSchemaBootIT before shipping.

-- Identity / variant. A beauty SKU is identified by brand + line + shade, not by name:
-- "Ruby Woo" means nothing without "MAC", and two brands' "01 Ivory" are different products.
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS brand VARCHAR(120);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS product_line VARCHAR(160);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS shade_name VARCHAR(160);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS shade_code VARCHAR(60);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS size_ml NUMERIC(8,2);

-- Makeup attributes — MakeupKitGraph slot filling + compatibility (e.g. a liquid formula requires a sponge).
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS coverage VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS finish_tag VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS formula_format VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS shade_depth VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS undertone VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS required_applicator VARCHAR(80);

-- Manufacturer CLAIMS, not our assessment. We repeat what the manufacturer states and never assert it
-- ourselves — we have tested nothing, and "suitable for sensitive eyes" is their sentence, not ours.
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS eye_area_safe_claim VARCHAR(200);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS fragrance_free_claim VARCHAR(200);

-- Nail system + compatibility — NailKitGraph and BeautyCompatibilityPolicy. recommended_lamp is the
-- manufacturer's own pairing: lamp/gel compatibility is a curing question, never a wattage guess.
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS nail_system VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS application_role VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS curing_required BOOLEAN;
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS recommended_lamp VARCHAR(200);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS cure_time_seconds INTEGER;
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS removal_method VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS effect_type VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS magnet_required BOOLEAN;
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS beginner_suitability VARCHAR(40);

-- Safety — ConsumerNailSafetyPolicy.
--
-- The substance columns are VARCHAR holding a SubstancePresence name (VERIFIED_ABSENT / VERIFIED_PRESENT /
-- UNKNOWN), deliberately NOT boolean. A boolean forces every unknown to false, and false reads as "does not
-- contain" — which is precisely how incomplete retailer data turns into a safety claim about someone's skin.
-- NULL parses to UNKNOWN, which blocks. There is no default, because a default would be an assertion.
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS hema_status VARCHAR(24);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS di_hema_status VARCHAR(24);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS tpo_status VARCHAR(24);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS professional_only BOOLEAN;
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS inci_source VARCHAR(300);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS inci_verified_at VARCHAR(40);

-- Cached verdict — a CACHE, never a truth. Invalid unless safety_ruleset_version equals the ruleset in force
-- AND safety_verdict_at is inside the freshness window. On mismatch it is recomputed or the product is
-- blocked; it is never trusted merely because it is present.
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS safety_verdict VARCHAR(24);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS safety_verdict_at VARCHAR(40);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS safety_ruleset_version VARCHAR(40);

-- The gate reads nail_system and professional_only on every candidate, so index the pair it filters by.
-- Partial index: beauty rows are a small minority of the table for the foreseeable future.
CREATE INDEX IF NOT EXISTS idx_products_nail_system ON public.products (nail_system) WHERE nail_system IS NOT NULL;
