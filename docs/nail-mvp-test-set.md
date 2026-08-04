# Nail MVP test set — the frozen contract

Sixteen prompts. Twelve must produce a kit someone can actually buy; four must refuse, **with the reason
written out**. This is not the coverage matrix — that one measures how much the catalog can do and is meant
to grow. This is the opposite instrument: a contract that only changes on purpose.

**The refusals matter more than the successes.** A gap that quietly turns into a "Complete" kit full of the
wrong products is the single worst thing this product can do, and it looks exactly like progress. So the
expected Croatian for each refusal is frozen character-for-character in
[`NailMvpTestSetTest`](../backend/src/test/java/ai/budgetspace/beauty/nail/NailMvpTestSetTest.java); if a
catalog change alters one, the build fails and this file has to be edited by hand.

Run it: `mvn test -Dtest=NailMvpTestSetTest`. No server, no network — it drives the real assembler against
the real catalog.

## The demo case

> **Kratki bordo almond nokti sa sjajnim završetkom i zlatnim detaljem na prstenjaku.**

The prompt to put in front of a tester first. Everything it asks for is backed by a verified product, and
it is the only supported prompt that exercises the **accent slot** — the newest and least-travelled part of
the kit graph. It parses to: almond / short / burgundy / glossy / gold accent on the ring finger, mirrored.

| Branch | Result |
|---|---|
| **Salon** | Diagram + 10-nail placement with the accent on both ring fingers + show-to-technician text. No price, no retailer, no product anywhere on the page. |
| **Classical polish** | `COMPLETE_WITH_ASSUMPTIONS`, **25,55 €**, 3 retailers, 7 rows |

```
file          1,25 €  dm.hr           Rašpica protiv listanja noktiju
cuticle-care  4,50 €  Golden Rose HR  Nail Expert Beauty Oil Nail & Cuticle   (optional)
base          4,50 €  Golden Rose HR  Nail Expert Smoothing Base Nail Foundation
color         9,95 €  dm.hr           Lak za nokte – 50 bordeaux, 13,5 ml
top           2,80 €  Golden Rose HR  Gel Look Top Coat
removal       0,65 €  dm.hr           Odstranjivač laka za nokte s acetonom
accent        1,90 €  beauty-shop.hr  Naljepnica za nokte Gold Glam 17
```

The colour is the shade whose **own title** says *bordeaux*, not the cheapest lacquer — so no "check the
swatch" guess is raised. The accent is the product that proves gold. One assumption is shown
(*isti dizajn na obje ruke*), because the prompt never said the hands differ.

## Supported — expect Complete or Complete with assumptions (12)

| # | Prompt | System |
|---|---|---|
| 1 | Kratki bordo almond nokti sa sjajnim završetkom i zlatnim detaljem na prstenjaku. | klasični lak |
| 2 | Želim kratke burgundy nokte, sjajne. | klasični lak |
| 3 | Želim kratke nude nokte, sjajne. | klasični lak |
| 4 | Želim kratke nude nokte, mat. | klasični lak |
| 5 | Želim kratke crvene nokte, sjajne. | klasični lak |
| 6 | Želim kratke crne nokte, mat. | klasični lak |
| 7 | Želim kratke bijele nokte, sjajne. | klasični lak |
| 8 | Želim kratke roza nokte, sjajne. | klasični lak |
| 9 | Želim kratke nude nokte sa zlatnim detaljem na prstenjaku. | klasični lak |
| 10 | Želim kratke almond nokte, sjajne. | press-on |
| 11 | Želim srednje duge almond nokte, sjajne. | press-on |
| 12 | Želim duge coffin nokte, sjajne. | press-on |

For each: no missing slot, the total equals the sum of the rows to the cent, every row links an `https://`
retailer page with a real price, and the freshness line is present.

## Intentionally unsupported — expect Incomplete (4)

The exact Croatian the user must see:

| Prompt | System | `missingRequiredSlots` — exact |
|---|---|---|
| **Želim kratke almond burgundy cat-eye nokte s dva diskretna zlatna detalja.** | klasični lak | `cat-eye efekt (postoji samo u setovima umjetnih noktiju, ne u sustavu koji je odabran)`<br>`magnet za cat-eye (nema nijednog provjerenog proizvoda u katalogu)` |
| Želim kratke nokte s chrome efektom. | klasični lak | `chrome efekt (nema nijednog provjerenog proizvoda u katalogu)` |
| Želim kratke nokte s glitterom na prstenjaku. | klasični lak | `glitter (nema nijednog provjerenog proizvoda u katalogu)` |
| Želim kratke četvrtaste nokte, sjajne. | press-on | `četvrtasti oblik (nema nijednog provjerenog proizvoda u katalogu)` |

**Why each one is genuinely impossible, not merely unsourced:**

- **cat-eye** — Croatia sells it, including in bordo (cicinails *379 Dangerous Little Secret*, beauty-shop
  *Cat Eye Satin 06 Burgundy*). Every single one publishes a UV/LED curing step, and this pilot does not
  sell gel systems to consumers. Magnets exist and are deliberately not catalogued: a magnet with no
  air-drying magnetic polish to drag through completes nothing. See
  [nail-catalog-coverage.md](nail-catalog-coverage.md).
- **chrome** — the only chrome on the HR shelf is dm's DEPEND *Gel iQ Chrome* and beauty-shop's chrome
  pigment, both lamp-cured.
- **glitter** — Golden Rose's *Extreme Glitter Shine* was excluded at 2 of 12 shades in stock; nothing else
  air-drying carries the claim.
- **četvrtasti press-on** — no square press-on set at dm at any capture so far.

**The burgundy cat-eye prompt is the truthfulness test and must stay Incomplete.** It is the one that used
to be answered with ordinary glossy lacquer and called Complete. What is now allowed to change is *which*
part it names: burgundy and gold are real products, so if either ever reappears in the missing list,
something has broken.

## What is deliberately *not* asserted here

- **Freshness never changes an outcome.** A stale or unconfirmable price is a disclosure, not a gap — see
  [`NailCatalogFreshness`](../backend/src/main/java/ai/budgetspace/beauty/nail/NailCatalogFreshness.java).
  Every expectation above is therefore stable no matter what day it is run.
- **A missing product image costs nothing.** beauty-shop.hr publishes image URLs and then refuses to serve
  them; the accent row shows the labelled "nema dostupne slike" placeholder and the kit is unaffected.
- **Exact prices**, apart from the demo case, are not frozen — they come from live retailer feeds and are
  supposed to move. What is frozen is that the arithmetic holds.
