# Nail catalog — coverage, not volume

The catalog is the bottleneck, and the wrong way to fix it is to add products. 500 polishes that never combine
into a burgundy cat-eye is worse than 45 that do, because it looks like progress. This file records what is
measured, what the HR shelf can actually prove, and what is still genuinely impossible.

## The instruments

`node scripts/nail-coverage-matrix.mjs` (backend must be up). 28 realistic Croatian prompts x 2 systems,
driven through the **real** `/api/nail/parse` + `/api/nail/generate`, not a re-implementation. If it says
Complete, the product says Complete. `--json` for machine output. This one is meant to move.

[`docs/nail-mvp-test-set.md`](nail-mvp-test-set.md) is the opposite: 12 prompts frozen as buyable and 4
frozen as refusals, with the exact Croatian each refusal must show. `mvn test -Dtest=NailMvpTestSetTest`.

`COMPLETE` and `COMPLETE_WITH_ASSUMPTIONS` both count — a stated assumption is honest, a silent gap is not.

## Verification policy (2026-08-03)

Every row carries three facts, because *"we read this"* and *"this is true right now"* are different claims
and the catalog only ever supports the first:

| Field | Values | Meaning |
|---|---|---|
| `verificationMethod` | `automatic` \| `manual` | machine-read from a published feed, or opened and checked by a human. **Everything is `automatic` today.** |
| `lastVerifiedAt` | ISO instant | when the row was last actually READ. **Only a run that reached the source may set it to now.** |
| `sourceStatus` | `reachable` \| `temporarily-blocked` \| `unavailable` | as of the last capture run |

`NailCatalogFreshness` turns those into what the user is told:

- **VERIFIED** — reachable source, read within **14 days**. Nothing extra is said.
- **STALE** — older than the window. The row says *"Cijena je zadnji put provjerena 3. 8. 2026. i više nije
  svježa — provjeri kod trgovca."*
- **UNVERIFIABLE** — the source could not be read, so nothing can be re-confirmed however fresh the capture
  is. The row says *"Izvor trenutačno nije dostupan pa je ne možemo potvrditi."*

It fails closed in every direction: a missing timestamp, an unrecognised status and a capture dated in the
future are all UNVERIFIABLE, because "we don't know" is not "it's fine". Fourteen days is one dm promotion
cycle — long enough to be useful, short enough that a clearance price cannot still be asserted.

**Freshness never changes completeness.** A stale price is a disclosure; a missing product is a gap. Letting
the clock turn one into the other would make the kit's correctness depend on what day it is — and would make
it untestable, since the same behaviour would pass today and fail in a fortnight. Every kit also carries one
`catalogFreshnessHr` line stating when prices were read, how many cannot currently be confirmed, and that
**none has been checked by a human**.

**A blocked source never deletes what it already gave us.** If a retailer refuses a run, its previously
captured rows are carried forward with their **original** `lastVerifiedAt` — not restamped with the time of
the run that could not read them. That single-field lie would make every downstream "verified" claim false,
and it is the first thing `NailCatalogVerificationTest` checks.

**Images are never a gate.** A row with no usable image keeps its price, its link and its place in the kit;
only the picture is absent, and the UI labels it *"nema dostupne slike"*.

## Where it stands

| | cells honestly Complete |
|---|---|
| 2026-07-31, before capability sourcing | **8 / 56** |
| 2026-07-31, after | **19 / 56** |
| 2026-08-03, after gold sourcing | **20 / 56** |

54 -> 63 products. One cell, and that is the honest headline: **six** of the nine new rows are the gold
detail, and gold was blocking four cells but was only ever the *last* blocker in one of them. What the
work actually bought is that **"zlatni detalj" has disappeared from the blocking list entirely** — it went
from 4 mentions to 0 — so every cell it still touches now names its real obstacle instead of hiding behind
gold.

| Potreba | Klasični lak | Press-on |
|---|---|---|
| Burgundy | ✅ *essie "Lak za nokte – 50 bordeaux" 9,95 €* | ✅ *Nail Addict Bordeaux* |
| Sjajni / mat nude | ✅ | ❌ colour only in polish |
| Sjajni crveni / mat crveni | ✅ | ❌ |
| Mat crni | ✅ | ❌ |
| Sjajni roza / bijeli | ✅ | ❌ |
| Almond kratki / srednji | n/a (you file your own) | ✅ |
| Ovalni, četvrtasti kratki | n/a | ✅ / ❌ |
| Coffin dugi | ⛔ blocked (length needs press-ons) | ✅ |
| **Cat-eye** | ❌ **no air-drying magnetic polish, no magnet** | ✅ set exists, but not in the asked colour |
| **Zlatni detalj** | ✅ *"Naljepnica za nokte Gold Glam" 1,90 €* | ✅ same sticker, tagged `both` |
| French | ❌ not with the rest of the kit | ✅ 2 sets |
| Chrome, glitter | ❌ **nothing in catalog** | ❌ |
| Smeđi | ❌ | ✅ |

## The gold detail: a sticker, not a foil (2026-08-03)

Croatia sells plenty of gold nail art. Almost none of it works in this pilot, and the reason is published
in the retailers' own instructions rather than inferred:

| Product | Retailer | Verdict |
|---|---|---|
| **Naljepnica za nokte Gold Glam 12–17**, 1,90 € | beauty-shop.hr | **accepted, 6 SKUs.** *"Zlatno-crne klasične naljepnice. Jednostavna uporaba, skinite naljepnicu s podloge, zalijepite na željeno mjesto i zaštitite je sa sjajem."* Self-adhesive, sealed with the top coat the kit already buys. No lamp, no gel. |
| TRANSFER FOIL / BIT FOIL – GOLD, 2,50 € | victoriavynn.hr | rejected: *"postavite foliju … na disperzijski sloj hibridnog, gel ili gel-akrilnog stila … sušiti u lampi 60 sekundi"* |
| Ukras za nokte-folije Light Gold, 1,70 € | beauty-shop.hr | rejected: *"lako se nanosi na ljepljivi sloj gela ili trajnog laka"* |
| Gold Foil / Old Gold Foil, 1,30 € | cicinails.hr | rejected: the line's own instructions say *"koristite Foil gel, sušite 60 sec"* |
| Chrome pigment Diamond-holo gold, 11,50 € | beauty-shop.hr | rejected: *"nanesite color gel ili trajni lak … polimerizirajte … u lampi"* |
| Luxury Gold / Platinum Gold / Golden panther 8 ml | cicinails.hr | rejected: gel polish, *"zahtijevaju kombinirane UV/LED lampe snage 48 W"* |
| Naljepnice za nokte **Golden Glam**, 72 kom., 1,85 € | dm.hr | rejected: dm's search service publishes no application text at all, and `Golden` is a product-line name, not the retailer stating the colour |
| Set naljepnica gold, 3,99 € | cicinails.hr | rejected: genuinely self-adhesive and genuinely gold, but its **title** carries no nail word, so the backend cannot re-derive the claim from published text |

### Two false positives this pass killed

**A gold-coloured tool is not a gold nail.** The gold pattern matched *"ZLATNA KLIJEŠTA ZA UMJETNE NOKTE"*
(gold nail pliers) and *"Kist za nail art Aquarelle Gold"*. Both are unambiguously about nails and both
unambiguously say gold, and neither puts gold on a nail. `NailCapabilityEvidence.IS_TOOL` now blocks a
tool from proving any capability that describes how the nail *looks* — `MAGNET_TOOL` excepted, because
that capability *is* a tool. Same failure as *"Poklon-paket Magnetic Man"*, one category up.

**"Magnetni" on a bottle is not a magnet.** `MAGNET_TOOL` used to match `magnet\w*`, so *"lak za nokte
magnetni"* proved a magnet *tool*. The noun now has to stand alone (`magnet\b`), which no longer matches
`magnetni` or `magnetic`.

## Why cat-eye is still Incomplete, and it is not for lack of looking

Croatia **does** sell burgundy cat-eye. Every single one needs a lamp:

| Product | Retailer | Published curing step |
|---|---|---|
| 379 Dangerous Little Secret 8 ml (bordo cat eye), 9,99 € | cicinails.hr | *"zahtijevaju kombinirane UV/LED lampe snage 48 W"* |
| Cat Eye Satin 06 **Burgundy** 4 ml, 12,34 € | beauty-shop.hr | *"vrijeme sušenja u UV lampi 2-3 min, LED lampa 1-2 min"* |
| Trajni lak CAT EYE AURORA VEIL 9 ml, 9,00 € | beauty-shop.hr | *"osušite nokat u LED lampi 30–60 s ili u UV lampi 120 s"* |
| Hibridni trajni lak CAT EYE 7,3 ml, 7,50 € | beauty-shop.hr | *"UV/LED stvrdnjavanje"* |
| DEPEND Gel iQ Cat Eye – Angel / Fairy Dust, 5,50 € | dm.hr | Gel iQ is Depend's UV/LED system |

**Zero air-drying magnetic polishes exist on any reachable Croatian feed.** So the exact evidence gap is
not "no cat-eye product in Croatia" — it is **"no cat-eye product in a system this pilot supports."**

Magnets are real and were deliberately **not** catalogued: cicinails 3,00 €, beauty-shop 3,00 €,
victoriavynn 3,90 €. beauty-shop's own text ties its magnet to *"bilo kojim magnetskim trajnim lakom"* —
gel polish — and a magnet with nothing to drag through completes nothing. Cataloguing it would put a €3
tool in a kit that still cannot make the effect, which is padding dressed as progress.

**A cat-eye claim now needs published instructions.** `PilotProduct.applicationEvidence` carries the
retailer's own application text, and a *brush-on* product proves `CAT_EYE` only when that text names a
magnet step. A pre-made press-on plate or printed sticker is exempt — the effect is manufactured into it.
`figurativeMagneticWordsCannotProveACatEye` locks both directions.

## What the HR shelf genuinely cannot do

A 2026-07-31 probe of dm.hr's published search API — 28 search terms, 227 distinct products, throttled with
backoff, nothing bypassed — found **zero** of: a magnet, any magnetic/cat-eye polish, any gold nail product,
any chrome or glitter polish, any square press-on set.

A 2026-08-03 sweep widened that to the shops Croatian **salons** order from, which is where the gold turned
out to be. Which hosts publish a machine-readable feed at all:

| Host | Feed | Outcome |
|---|---|---|
| beauty-shop.hr | WooCommerce Store API | **added.** The gold sticker, plus full application text |
| cicinails.hr | WooCommerce Store API | reachable, nothing qualified: gold is either gel polish or foil-needing-gel |
| victoriavynn.hr | WooCommerce Store API | reachable, nothing qualified: foils cure in a lamp |
| nailuxe.hr, beautyart.hr | WooCommerce Store API | reachable, no qualifying row |
| nokti.shop, naio-nails.com | Shopify `/products.json` | **out of market** — RSD and GBP. A converted price is an invented price |
| socap.hr, notino.hr | — | **403, abandoned** |
| müller.hr, sephora.hr | — | 403, abandoned (2026-07-28) |
| makeup.hr | — | bot interstitial, abandoned |
| nailpro.hr, kozmo.hr, semilac.hr, neonail.hr | — | no DNS / no reachable host |

So the first target, *burgundy cat-eye + zlatni detalj*, is now **three-quarters closed**: burgundy is real,
gold is real, and cat-eye is not — for the specific, documented reason that every cat-eye product in Croatia
is a UV/LED gel.

### beauty-shop.hr blocked us after the capture — and the rows were kept

Partway through the 2026-08-03 session beauty-shop.hr's Imunify360 began answering the Store API with
HTTP 200 and `{"message": "Access denied by ... bot-protection"}`. It has not been worked around: no
disguised user-agent, no retry storm, no second IP. The build now recognises that body, **stops**, and
carries the rows it was already served forward untouched, `verifiedAt` included, rather than writing a
catalog with the gold silently deleted — the same failure mode as dm's ranking churn below. `honesty.
carriedForward` in the artefact records it.

**This is an owner decision, not a settled one.** Either the shop whitelists us, or a human re-verifies the
six rows by opening their URLs, or they come out. Until then the catalog is honest about their provenance
but they have not been re-confirmed since 2026-08-03.

The same shop answers **HTTP 415 with an HTML body** for its own published image URLs when they are loaded
from anywhere but its own pages — verified with a real browser user-agent and with ours. In the browser the
request simply hangs, so `<img onError>` never fires and the row showed a 58×58 hole that never resolved.
Those rows now carry `imageUrl: null`, the UI draws its labelled "bez slike" placeholder immediately, and
the published URL is kept in `imageUrlPublishedButBlocked` so the decision is reversible. The placeholder
copy changed from *"nema objavljene slike"* to *"nema dostupne slike"*: the first would have been a false
statement about this retailer, which published the image and then refused to serve it.

## Bugs this work found

**A capability could fall out of the catalog because a search result reshuffled.** The first 2026-08-03
rebuild silently lost *"what the fake! umjetni nokti – 02 Cat Eye"* and *"Umjetni nokti – balerina oblik"*:
dm's relevance ranking had simply moved them past position 12 of the generic `umjetni nokti` query, and with
them went the **only** proof of `CAT_EYE` and of `SHAPE_COFFIN` in the whole catalog. Nothing failed; the
build reported more products than before. Each capability the matrix depends on is now asked for by name
(`umjetni nokti cat eye|badem|balerina|bordo`), and the dedupe collapses the overlap. A rebuild that adds
rows while deleting a capability is the most expensive kind of green.

**The gold detail had nowhere to go.** `NailDesignSpecDto.hasDistinctAccentColor()` has always documented
"the kit must buy a second product for it", and no slot could hold that product. So a gold accent was
unreproducible *by construction* — with the sticker sitting in the catalog, nothing in the kit could ever
prove `GOLD_DETAIL`, and the user was told Croatia has no gold nail product when the truth was that the kit
had nowhere to put one. `NailKitAssembler.ACCENT_SLOT` is added only when the design actually asks for a
second colour, so a one-colour manicure is never billed for nail art, and a slot with no product that
proves the *asked-for* accent colour stays empty and is reported.

**"Use one store" started promising something it could not keep.** Adding that slot immediately broke a
neighbouring promise: `singleStoreOptions` still answered from the base graph, so it offered dm.hr as a
store that could finish a press-on kit on its own — and then the accent came from beauty-shop.hr, two
retailers deep. It now takes the design, so a store that cannot supply the accent is not offered for a
design that needs one. Found by checking the API response, not by a test, which is why there is now a test.

**A gift set claimed to be a cat-eye magnet.** The probe matched `magnet` against
*"Poklon-paket Magnetic Man"*, a men's gift box. On that evidence a kit would have declared itself able to do
a cat-eye. A capability claim now requires the retailer's own words to be about nails
(`NailCapabilityEvidence.NAIL_CONTEXT`), and a test locks both directions: the gift set proves nothing, a real
nail magnet still does.

**A determinism test passing for the wrong reason.** `11. regenerating the same brief` re-parsed the *default*
burgundy prompt on its third leg while comparing against a colourless prompt's total. It matched only because
burgundy fell back to the same numbered lacquer as a request with no colour at all — precisely the bug the
capability gate exists to kill. Once burgundy resolved to a real bordeaux the totals correctly diverged and
the test failed. Fixed to compare like with like.

## Rules the sourcing follows

- **Published endpoints only.** Golden Rose HR's Shopify `/products.json`, dm's own search service,
  beauty-shop.hr's WooCommerce Store API. Honest user-agent, hard throttle, one long backoff on 429, then
  abandon the term. A bot-protection refusal is a refusal, whatever status code carries it.
- **A capability claim is re-derivable from the retailer's title.** A row is only kept if
  `NailCapabilityEvidence` can independently prove the same thing from the published title. That is why
  cicinails' genuinely-gold, genuinely-self-adhesive "Set naljepnica gold" is rejected: nothing in its
  title says *nail*, so the backend could not re-prove it and the claim would rest on our tagging alone.
- **Compatibility is proven, not assumed.** A decoration enters the catalog only when the retailer's own
  application text names no lamp, no gel and no tacky layer. Every gold foil in Croatia fails this.
- **A tool is not a look.** A gold-coloured clipper proves nothing about a nail.
- **A colour counts only when the retailer names it.** `shadeColorKnown: true` + `colorFamily` are set only
  for titles matching the same patterns `NailCapabilityEvidence` uses. A row this script labels "red" that the
  backend cannot re-derive from the title would be a colour claim with no evidence behind it.
- **A shade number is not a colour.** "ICE 2" proves nothing and still raises the swatch assumption.
- **Deduplicate by article.** The colour queries overlap heavily; the same dm article arriving under three
  search terms would show the same polish three times in "Zamijeni" and make a pin ambiguous.
- **Scope stays regular-polish and press-on.** No gel, no trajni lak, no acrylic — they would grow the safety
  surface and the chance of a wrong recommendation faster than they grow coverage.

## Not done

**DE and AT markets, and EN/DE translations.** Sized, not started. The nail slice is hardcoded HR: **88
Croatian strings across 10 backend files and 25 in the component**, none externalized, while the furniture app
already has 12 locale bundles. dm's search service follows the same path shape for other markets
(`/de/search/crawl`, `/at/search/crawl`), which is the cheap first check.

Sequencing recommendation: finish HR to an honest Complete on the colour/finish looks that are reachable,
settle the magnetic-polish source question, and only then open a second market. A second language over a
catalog that still cannot do cat-eye multiplies the gap instead of closing it.

**Cat-eye for classical polish may simply not exist in Croatia.** Every reachable retailer sells it only as
UV/LED gel. The three ways out, in order of honesty rather than convenience: an air-drying magnetic polish
arrives on an HR feed (none today); the pilot's scope widens to gel, which the safety model deliberately
refuses while no retailer publishes an INCI list; or the app keeps saying it cannot do this look, which is
what it does now. Sourcing more products cannot fix it — the shelf, not the catalog, is the constraint.

**The next cells worth buying, by what blocks them.** `cat-eye efekt` blocks 8 and is shelf-limited above.
After that: `french` (5 — the sets exist but never alongside the rest of the kit), `glitter` (4, nothing in
catalog), `četvrtasti oblik` (3, no square press-on set at dm), `chrome` (2, nothing air-drying). Press-on
colour is the other big seam: `nude`, `crna`, `bijela` and `roza` are each Complete in polish and absent in
press-on, which is 10 cells behind one kind of product — a coloured press-on set in a plain colour.
