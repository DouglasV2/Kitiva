import { test, expect, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';

/**
 * The Nail Look vertical slice, end to end in a real browser against the real backend and real catalog.
 *
 * Nothing here is stubbed. If dm.hr or Golden Rose data changed, or the assembler stopped filling a slot,
 * these tests fail — which is the point. A suite that mocked the API would pass while the product was
 * broken.
 */

const SHOTS = 'test-results/screens';
mkdirSync(SHOTS, { recursive: true });

const PROMPT = 'Želim kratke almond burgundy cat-eye nokte s dva diskretna zlatna detalja.';

// The MVP demo case: the first prompt a real tester is given. Everything it asks for is backed by a
// verified product, so it exercises the whole happy path — including the accent, which is the newest and
// least-travelled part of the kit. Kept identical to NailMvpTestSetTest.DEMO and docs/nail-mvp-test-set.md.
const DEMO_PROMPT = 'Kratki bordo almond nokti sa sjajnim završetkom i zlatnim detaljem na prstenjaku.';

// A prompt the verified catalog CAN honour: no named colour, no effect, so no capability is unprovable.
const SUPPORTED_PROMPT = 'Želim kratke almond nokte, sjajne.';

async function openApp(page: Page) {
  // The furniture app's auth gate is a modal that intercepts pointer events. Clicking through it is flaky
  // because it re-renders; setting the same sessionStorage flag its own "continue as guest" button sets is
  // the stable equivalent, and it keeps these tests about the nail slice rather than about a modal the
  // pivot will replace anyway.
  await page.addInitScript(() => window.sessionStorage.setItem('bs-guest-continue', '1'));
  await page.goto('/');
  await expect(page.getByTestId('parse')).toBeVisible();
}

async function parsePrompt(page: Page, prompt = PROMPT) {
  await page.locator('#nail-prompt').fill(prompt);
  await page.getByTestId('parse').click();
  await expect(page.getByTestId('nail-brief')).toBeVisible();
}

/**
 * Budget and system only exist once "Radim sama" is chosen — they belong to the shopping list and the salon
 * branch never uses them. So every at-home flow picks the branch first, then fills them in.
 */
async function chooseHome(page: Page, opts: { budget?: string; system?: 'regular-polish' | 'press-on' } = {}) {
  await page.getByTestId('choose-home').click();
  await expect(page.getByTestId('home-setup')).toBeVisible();
  if (opts.budget !== undefined) await page.getByTestId('f-budget').fill(opts.budget);
  if (opts.system !== undefined) await page.getByTestId('f-system').selectOption(opts.system);
}

test.describe('Nail Look slice', () => {
  test('1. brief is editable and the diagram follows every edit', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page);

    // The whole prompt, understood: shape, length, base colour, ACCENT colour, finish, effect, placement.
    await expect(page.getByTestId('f-shape')).toHaveValue('ALMOND');
    await expect(page.getByTestId('f-length')).toHaveValue('SHORT');
    await expect(page.getByTestId('f-color')).toHaveValue('burgundy');
    await expect(page.getByTestId('f-accent-color')).toHaveValue('gold');
    await expect(page.getByTestId('f-finish')).toHaveValue('GLOSSY');
    await expect(page.getByTestId('f-effects').getByRole('button', { name: 'cat-eye', pressed: true })).toBeVisible();
    await expect(page.getByTestId('f-accents').getByRole('button', { name: 'prstenjak', pressed: true })).toBeVisible();

    const diagram = page.getByTestId('nail-diagram');
    await expect(diagram.locator('svg')).toBeVisible();
    expect(await diagram.innerHTML()).toContain('#5C0A22'); // burgundy actually drawn

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-01-brief.png`, fullPage: true });

    // Edit the brief: the diagram must re-render from the corrected spec, not the original prompt.
    await page.getByTestId('f-color').selectOption('green');
    await expect.poll(async () => (await diagram.innerHTML()).includes('#3E6B4F'), { timeout: 15_000 }).toBe(true);
    expect(await diagram.innerHTML()).not.toContain('#5C0A22');

    await page.getByTestId('f-color').selectOption('burgundy');
    await expect.poll(async () => (await diagram.innerHTML()).includes('#5C0A22'), { timeout: 15_000 }).toBe(true);
    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-02-brief-edited.png`, fullPage: true });
  });

  test('2. salon path: diagram, spec, per-nail placement, show-to-tech brief', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page);
    await page.getByTestId('choose-salon').click();
    await page.getByTestId('fork-continue').click();

    const salon = page.getByTestId('salon-brief');
    await expect(salon).toBeVisible();

    // Ten nails, and the accent lands on both ring fingers.
    const placements = salon.getByTestId('nail-placement').locator('li');
    await expect(placements).toHaveCount(10);
    await expect(salon.getByTestId('nail-placement').locator('li.is-accent')).toHaveCount(2);

    const showToTech = await salon.getByTestId('show-to-tech').innerText();
    expect(showToTech.toLowerCase()).toContain('almond');
    expect(showToTech.toLowerCase()).toContain('prstenjak');

    // A salon brief must never turn into a shopping list.
    const salonText = await salon.innerText();
    expect(salonText).not.toMatch(/\d+,\d{2}\s*€/);
    expect(salonText).not.toContain('Golden Rose');

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-03-salon.png`, fullPage: true });
  });

  test('3. at-home regular polish: real products, exact total, essential vs optional', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit).toBeVisible();
    await expect(kit.getByTestId('kit-status')).toContainText(/Potpun/);

    // Every required slot of the regular-polish graph is present.
    for (const slot of ['file', 'base', 'color', 'top', 'removal']) {
      await expect(kit.getByTestId(`kit-row-${slot}`)).toBeVisible();
    }

    // Real buy links, not placeholders.
    const links = kit.locator('.nk-kit-name a');
    expect(await links.count()).toBeGreaterThan(3);
    for (const href of await links.evaluateAll((as) => as.map((a) => (a as HTMLAnchorElement).href))) {
      expect(href).toMatch(/^https:\/\//);
    }

    // The total is the sum of the rows — arithmetic, not an estimate.
    const prices = await kit.locator('.nk-kit-price > div').allInnerTexts();
    const summed = prices.reduce((n, p) => n + Math.round(parseFloat(p.replace(/[^\d,]/g, '').replace(',', '.')) * 100), 0);
    const totalText = await kit.getByTestId('kit-total').innerText();
    const total = Math.round(parseFloat(totalText.replace(/[^\d,]/g, '').replace(',', '.')) * 100);
    expect(total).toBe(summed);

    await expect(kit.locator('.nk-tag-essential').first()).toBeVisible();
    await expect(kit.locator('.nk-tag-optional').first()).toBeVisible();

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-04-kit-polish.png`, fullPage: true });
  });

  test('4. at-home press-on: its own graph, adhesive and removal required', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '40', system: 'press-on' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit).toBeVisible();
    for (const slot of ['press-on-set', 'adhesive', 'file', 'removal']) {
      await expect(kit.getByTestId(`kit-row-${slot}`)).toBeVisible();
    }
    await expect(kit.getByTestId('kit-row-adhesive').locator('.nk-tag-essential')).toBeVisible();
    await expect(kit.getByTestId('kit-row-removal').locator('.nk-tag-essential')).toBeVisible();

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-05-kit-presson.png`, fullPage: true });
  });

  test('5. owned products leave the total', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    const before = await kit.getByTestId('kit-total').innerText();
    const beforeCents = Math.round(parseFloat(before.replace(/[^\d,]/g, '').replace(',', '.')) * 100);

    // Mark base and top as already owned, then rebuild.
    await page.getByTestId('owned-base').click();
    await page.getByTestId('owned-top').click();
    await page.getByTestId('fork-continue').click();
    await expect(kit.getByTestId('kit-owned')).toBeVisible();

    const after = await kit.getByTestId('kit-total').innerText();
    const afterCents = Math.round(parseFloat(after.replace(/[^\d,]/g, '').replace(',', '.')) * 100);
    expect(afterCents).toBeLessThan(beforeCents);

    // Listed as owned, and genuinely gone from the priced rows.
    await expect(kit.getByTestId('kit-owned')).toContainText('Bazni lak');
    await expect(kit.getByTestId('kit-row-base')).toHaveCount(0);

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-06-owned.png`, fullPage: true });
  });

  test('6. over budget is reported, never trimmed below completeness', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '3' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit.getByTestId('kit-status')).toContainText(/Iznad budžeta/);
    await expect(kit.getByTestId('kit-remaining')).toContainText(/nedostaje/);
    // Every required slot is still there: the kit went over budget rather than dropping essentials.
    for (const slot of ['base', 'color', 'top', 'removal']) {
      await expect(kit.getByTestId(`kit-row-${slot}`)).toBeVisible();
    }

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-07-over-budget.png`, fullPage: true });
  });

  test('6b. the primary prompt is honestly reported as unreproducible', async ({ page }, testInfo) => {
    // Reached through the REAL flow with the REAL catalog — no fixture, no demo toggle. The kit used to
    // answer this with ordinary glossy lacquer and call itself Complete with assumptions.
    //
    // This used to assert "zlatni detalj" was missing, which was really asserting that Croatia sells no
    // gold nail product — a shelf limitation dressed up as a rule. It failed the moment beauty-shop.hr's
    // self-adhesive gold stickers closed that gap. The rule that must hold is the one below: the prompt
    // asks for a cat-eye, every cat-eye product in Croatia is a UV/LED gel, and the kit says so.
    await openApp(page);
    await parsePrompt(page, PROMPT);
    await chooseHome(page, { budget: '40' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit.getByTestId('kit-status')).toContainText(/Nepotpun/);

    // The UI must say WHAT is missing, not just that something is.
    const missing = kit.getByTestId('kit-missing');
    await expect(missing).toBeVisible();
    await expect(missing).toContainText(/cat-eye/i);
    // ...and must NOT still be claiming the half that sourcing closed.
    await expect(missing).not.toContainText(/zlatni detalj/i);
    // The gold detail is now a real row the user can buy.
    await expect(kit.getByTestId('kit-row-accent')).toBeVisible();

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-12-incomplete-real.png`, fullPage: true });

    // Press-ons cannot reproduce it either — no set is simultaneously almond, burgundy and cat-eye.
    await page.getByTestId('f-system').selectOption('press-on');
    await page.getByTestId('fork-continue').click();
    await expect(kit.getByTestId('kit-status')).toContainText(/Nepotpun/);
    await expect(kit.getByTestId('kit-missing')).toContainText(/cat-eye/i);
  });

  test('6c. assumptions are listed, not hidden behind a count', async ({ page }) => {
    await openApp(page);
    await parsePrompt(page, PROMPT);

    const assumptions = page.getByTestId('brief-assumptions');
    await expect(assumptions).toBeVisible();
    // Visible without opening anything, and showing the reason rather than a number.
    await expect(assumptions.locator('li').first()).toBeVisible();
    expect((await assumptions.innerText()).length).toBeGreaterThan(40);
  });

  test('7. safety blocked: a system we do not sell to consumers', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, 'Želim polygel nadogradnju kod kuće, jako duge nokte.');
    await chooseHome(page);
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit.getByTestId('kit-status')).toContainText(/sigurnosnih razloga/i);
    await expect(kit.locator('.nk-kit-row')).toHaveCount(0);

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-08-safety-blocked.png`, fullPage: true });
  });

  test('8. safety blocked: a volunteered health concern is refused without diagnosing', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, 'Nokat mi je upaljen i boli me, ali želim lakirati nokte kod kuće.');
    await chooseHome(page);
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit.getByTestId('kit-status')).toContainText(/sigurnosnih razloga/i);
    await expect(kit).toContainText(/nije dijagnoza/i);
    await expect(kit.locator('.nk-kit-row')).toHaveCount(0);

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-09-health-blocked.png`, fullPage: true });
  });

  test('9. replace this: swap a product, kit stays complete', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    const colorRow = kit.getByTestId('kit-row-color');
    const nameBefore = await colorRow.locator('.nk-kit-name').innerText();

    await colorRow.locator('.nk-alts summary').click();
    await colorRow.getByTestId('swap-color').first().click();

    await expect.poll(async () => (await kit.getByTestId('kit-row-color').locator('.nk-kit-name').innerText()) !== nameBefore,
      { timeout: 15_000 }).toBe(true);
    await expect(kit.getByTestId('kit-status')).toContainText(/Potpun/);

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-10-replace.png`, fullPage: true });
  });

  test('10. make it cheaper: total drops, essentials survive', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    const before = Math.round(parseFloat((await kit.getByTestId('kit-total').innerText()).replace(/[^\d,]/g, '').replace(',', '.')) * 100);

    await page.getByTestId('refine-cheaper').click();
    await expect(kit.getByTestId('kit-total')).toBeVisible();
    const after = Math.round(parseFloat((await kit.getByTestId('kit-total').innerText()).replace(/[^\d,]/g, '').replace(',', '.')) * 100);

    expect(after).toBeLessThanOrEqual(before);
    for (const slot of ['base', 'color', 'top', 'removal']) {
      await expect(kit.getByTestId(`kit-row-${slot}`)).toBeVisible();
    }

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-11-cheaper.png`, fullPage: true });
  });

  test('11. regenerating the same brief yields the same total', async ({ page }) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '30' });

    await page.getByTestId('fork-continue').click();
    const kit = page.getByTestId('nail-kit');
    const first = await kit.getByTestId('kit-total').innerText();

    // Same brief, generated again: totals are derived, not accumulated, so nothing may drift.
    await page.getByTestId('fork-continue').click();
    await expect(kit.getByTestId('kit-total')).toHaveText(first);

    // And again after a full reload + re-parse of the SAME prompt. This used to pass `parsePrompt(page)`,
    // which re-parses the default burgundy cat-eye prompt instead — it only matched because burgundy fell
    // back to the same numbered lacquer as a colourless request. Now that a real bordeaux backs burgundy,
    // the two totals differ correctly, so the test has to compare like with like.
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();
    await expect(page.getByTestId('nail-kit').getByTestId('kit-total')).toHaveText(first);
  });

  test('12. salon result: copy brief puts the whole brief on the clipboard', async ({ page, context }, testInfo) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await openApp(page);
    await parsePrompt(page);
    await page.getByTestId('choose-salon').click();
    await page.getByTestId('fork-continue').click();
    await expect(page.getByTestId('salon-brief')).toBeVisible();

    await page.getByTestId('copy-brief').click();
    await expect(page.getByTestId('copy-brief')).toContainText('Kopirano');

    const clip = await page.evaluate(() => navigator.clipboard.readText());
    // The pasted text is the brief, not a summary of it: spec, per-nail placement and the assumptions.
    expect(clip).toContain('SPECIFIKACIJA');
    expect(clip).toContain('NOKAT PO NOKAT');
    expect(clip).toContain('PRETPOSTAVKE');
    expect(clip.toLowerCase()).toContain('almond');
    // And never a price or a retailer, whichever branch built it.
    expect(clip).not.toMatch(/\d+,\d{2}\s*€/);
    expect(clip).not.toContain('Golden Rose');

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-13-salon-copy.png`, fullPage: true });
  });

  test('13. salon result: download produces a real PNG', async ({ page }) => {
    await openApp(page);
    await parsePrompt(page);
    await page.getByTestId('choose-salon').click();
    await page.getByTestId('fork-continue').click();

    const download = page.waitForEvent('download');
    await page.getByTestId('download-brief').click();
    const file = await download;
    expect(file.suggestedFilename()).toBe('nail-look-salon.png');

    // A PNG, and big enough to actually contain the diagram and the text rather than a blank canvas.
    const path = await file.path();
    const { readFileSync } = await import('node:fs');
    const bytes = readFileSync(path!);
    expect(bytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
    expect(bytes.length).toBeGreaterThan(20_000);
  });

  test('14. salon result: edit specification returns to the editable form', async ({ page }) => {
    await openApp(page);
    await parsePrompt(page);
    await page.getByTestId('choose-salon').click();
    await page.getByTestId('fork-continue').click();
    await expect(page.getByTestId('salon-brief')).toBeVisible();

    await page.getByTestId('edit-spec').click();
    await expect(page.getByTestId('salon-brief')).toHaveCount(0);
    await expect(page.getByTestId('nail-brief')).toBeVisible();

    // The chosen branch survives, so continuing again rebuilds the same result from the edited spec.
    await page.getByTestId('f-length').selectOption('MEDIUM');
    await page.getByTestId('fork-continue').click();
    await expect(page.getByTestId('salon-brief')).toBeVisible();
    // The brief opens with the length, capitalised as a sentence: "Srednji almond nokti…".
    await expect(page.getByTestId('show-to-tech')).toContainText(/srednji/i);
  });

  test('15. budget and system appear only after choosing Radim sama', async ({ page }) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);

    await expect(page.getByTestId('home-setup')).toHaveCount(0);
    await page.getByTestId('choose-salon').click();
    await expect(page.getByTestId('home-setup')).toHaveCount(0);

    await page.getByTestId('choose-home').click();
    await expect(page.getByTestId('home-setup')).toBeVisible();
    await expect(page.getByTestId('f-budget')).toBeVisible();
    await expect(page.getByTestId('f-system')).toBeVisible();
  });

  test('16. assumptions are natural Croatian, not enum names', async ({ page }) => {
    await openApp(page);
    await parsePrompt(page);

    const assumptions = page.getByTestId('brief-assumptions');
    await expect(assumptions).toBeVisible();
    const text = await assumptions.innerText();

    // The three the owner called out, plus the field names they came from.
    for (const leak of ['glossy', 'mirrored', 'GLOSSY', 'MIRRORED', 'accentFingers', 'symmetry']) {
      expect(text).not.toContain(leak);
    }
    expect(text).toContain('sjajni završni sloj');
    expect(text).toContain('isti dizajn na obje ruke');
    // A bare "prstenjak" heading is out; a Croatian phrase containing it is exactly right.
    expect(text).toContain('detalj na prstenjak');

    // Same rule on the kit's own assumptions.
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();
    const kitAssumptions = page.getByTestId('kit-assumptions');
    await expect(kitAssumptions).toBeVisible();
    const kitText = await kitAssumptions.innerText();
    for (const leak of ['glossy', 'mirrored', 'owned', 'shade:', 'removed']) {
      expect(kitText).not.toContain(leak);
    }
  });

  test('17. at-home result carries prep, removal and product photos', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit.getByTestId('kit-prep')).toBeVisible();
    await expect(kit.getByTestId('kit-removal')).toBeVisible();
    await expect(kit.getByTestId('kit-prep').locator('li').first()).toBeVisible();

    // Every row shows the retailer's own photo. They are lazy, so scroll them in before asking whether the
    // bytes arrived — an off-screen lazy image legitimately has naturalWidth 0.
    const thumbs = kit.getByTestId('kit-thumb');
    expect(await thumbs.count()).toBeGreaterThan(3);
    for (const src of await thumbs.evaluateAll((imgs) => imgs.map((i) => (i as HTMLImageElement).src))) {
      expect(src).toMatch(/^https:\/\//);
    }
    await thumbs.last().scrollIntoViewIfNeeded();
    await expect
      .poll(async () => thumbs.evaluateAll((imgs) =>
        imgs.filter((i) => (i as HTMLImageElement).naturalWidth > 0).length), { timeout: 20_000 })
      .toBeGreaterThan(2);

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-14-kit-prep-removal.png`, fullPage: true });
  });

  test('17b. every product surface carries a thumbnail, and the empty state is labelled', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);

    // Mark two slots owned so the owned list is populated in the same result.
    await page.getByTestId('owned-base').click();
    await page.getByTestId('owned-top').click();
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit.getByTestId('kit-owned')).toBeVisible();
    const totalBefore = await kit.getByTestId('kit-total').innerText();

    // Essential and optional rows: a real retailer photo on every one. Scoped to the row's OWN thumb —
    // `.nk-kit-main > *` — because the alternatives nested inside the row carry thumbnails as well.
    for (const slot of ['file', 'color', 'removal']) {
      const own = kit.getByTestId(`kit-row-${slot}`).locator('.nk-kit-main > [data-testid=kit-thumb]');
      await expect(own).toBeVisible();
      expect(await own.getAttribute('src')).toMatch(/^https:\/\//);
    }
    await expect(kit.locator('.nk-tag-optional').first()).toBeVisible();
    const optionalRow = kit.locator('.nk-kit-row').filter({ has: page.locator('.nk-tag-optional') }).first();
    await expect(optionalRow.locator('.nk-kit-main > [data-testid=kit-thumb]')).toBeVisible();

    // Owned rows: we know she has a base coat, not which one — so the labelled placeholder, never a photo.
    const ownedRow = kit.getByTestId('kit-owned-base');
    await expect(ownedRow.getByTestId('thumb-placeholder')).toBeVisible();
    await expect(ownedRow.getByTestId('kit-thumb')).toHaveCount(0);
    await expect(ownedRow.getByTestId('thumb-placeholder')).toContainText('bez slike');
    expect(await ownedRow.getByTestId('thumb-placeholder').getAttribute('aria-label'))
      .toContain('nema dostupne slike');

    // Replacement products inside "Zamijeni".
    const colorRow = kit.getByTestId('kit-row-color');
    await colorRow.locator('.nk-alts summary').click();
    const alt = colorRow.locator('.nk-alt').first();
    await expect(alt.getByTestId('kit-thumb').or(alt.getByTestId('thumb-placeholder'))).toBeVisible();
    // Name, price delta, and the action all still readable beside it.
    await expect(alt.locator('.nk-alt-name')).not.toBeEmpty();
    await expect(alt.locator('.nk-alt-delta')).toBeVisible();
    await expect(alt.getByTestId('swap-color')).toBeVisible();

    // Images are decoration: they must not move a single cent.
    await expect(kit.getByTestId('kit-total')).toHaveText(totalBefore);

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-15-thumbnails.png`, fullPage: true });
  });

  test('17c. a broken image URL degrades to the labelled placeholder, not a broken icon', async ({ page }) => {
    // Fail every image request for this run only — matched by resource type, because the CDN URLs carry
    // query strings and cache-busting paths that an extension glob does not catch. Nothing else changes.
    await page.route('**/*', (route) =>
      route.request().resourceType() === 'image' ? route.abort() : route.continue());
    await openApp(page);
    await parsePrompt(page, SUPPORTED_PROMPT);
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit.getByTestId('kit-status')).toContainText(/Potpun/);
    const row = kit.getByTestId('kit-row-color');
    await expect(row.getByTestId('thumb-placeholder')).toBeVisible();
    await expect(row.getByTestId('thumb-placeholder')).toContainText('bez slike');
    // The product itself is unaffected: name, retailer, price and link all still there.
    await expect(row.locator('.nk-kit-name a')).toHaveAttribute('href', /^https:\/\//);
    await expect(row.locator('.nk-kit-price > div')).not.toBeEmpty();
    await expect(kit.getByTestId('kit-total')).toBeVisible();
  });

  test('18. feedback is optional, answerable and recorded on both results', async ({ page }) => {
    await openApp(page);
    await parsePrompt(page);

    // Salon.
    await page.getByTestId('choose-salon').click();
    await page.getByTestId('fork-continue').click();
    const salonFeedback = page.getByTestId('feedback-salon');
    await expect(salonFeedback).toBeVisible();
    const stored = page.waitForResponse((r) => r.url().includes('/api/nail/feedback') && r.status() === 200);
    await page.getByTestId('feedback-salon-djelomicno').click();
    expect((await (await stored).json()).stored).toBe(true);
    await expect(salonFeedback).toContainText('zabilježen');

    // At home, second question, independently answerable.
    await page.getByTestId('edit-spec').click();
    await chooseHome(page, { budget: '30' });
    await page.getByTestId('fork-continue').click();
    const homeFeedback = page.getByTestId('feedback-home');
    await expect(homeFeedback).toBeVisible();
    const stored2 = page.waitForResponse((r) => r.url().includes('/api/nail/feedback') && r.status() === 200);
    await page.getByTestId('feedback-home-platila').click();
    expect((await (await stored2).json()).stored).toBe(true);
  });

  // ------------------------------------------------------------------------------- the MVP demo case

  test('19. demo case: salon result is a specification, not a shopping list', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, DEMO_PROMPT);

    // The prompt, understood — every field, including the second colour that becomes a second purchase.
    await expect(page.getByTestId('f-shape')).toHaveValue('ALMOND');
    await expect(page.getByTestId('f-length')).toHaveValue('SHORT');
    await expect(page.getByTestId('f-color')).toHaveValue('burgundy');
    await expect(page.getByTestId('f-finish')).toHaveValue('GLOSSY');
    await expect(page.getByTestId('f-accent-color')).toHaveValue('gold');

    await page.getByTestId('choose-salon').click();
    await page.getByTestId('fork-continue').click();

    const salon = page.getByTestId('salon-brief');
    await expect(salon).toBeVisible();
    await expect(salon.getByTestId('nail-placement').locator('li')).toHaveCount(10);
    await expect(salon.getByTestId('nail-placement').locator('li.is-accent')).toHaveCount(2);

    const salonText = await salon.innerText();
    expect(salonText).not.toMatch(/\d+,\d{2}\s*€/);
    expect(salonText).not.toContain('beauty-shop');

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-19-demo-salon.png`, fullPage: true });
  });

  test('20. demo case: classical-polish kit is complete, priced and honest about its numbers',
    async ({ page }, testInfo) => {
      await openApp(page);
      await parsePrompt(page, DEMO_PROMPT);
      await chooseHome(page, { system: 'regular-polish' });
      await page.getByTestId('fork-continue').click();

      const kit = page.getByTestId('nail-kit');
      await expect(kit.getByTestId('kit-status')).toContainText(/Potpun/);
      await expect(kit.getByTestId('kit-missing')).toHaveCount(0);

      // The whole graph, plus the accent the second colour created.
      for (const slot of ['file', 'base', 'color', 'top', 'removal', 'accent']) {
        await expect(kit.getByTestId(`kit-row-${slot}`)).toBeVisible();
      }
      await expect(kit.getByTestId('kit-row-color')).toContainText(/bordeaux|bordo/i);
      await expect(kit.getByTestId('kit-row-accent')).toContainText(/Gold/i);

      // The total is the sum of the rows, to the cent — not a rounded impression of one.
      const cents = (s: string) => Math.round(parseFloat(s.replace(/[^\d,]/g, '').replace(',', '.')) * 100);
      const rowTotal = (await kit.locator('.nk-kit-row .nk-kit-price').allInnerTexts())
        .reduce((sum, t) => sum + cents(t), 0);
      expect(cents(await kit.getByTestId('kit-total').innerText())).toBe(rowTotal);

      // Assumptions are visible without opening anything, and freshness is stated rather than implied.
      await expect(page.getByTestId('brief-assumptions').locator('li').first()).toBeVisible();
      await expect(kit.getByTestId('kit-freshness')).toBeVisible();
      await expect(kit.getByTestId('kit-freshness')).toContainText(/nije ručno provjerena/);

      // Every row is a real product at a real shop.
      for (const link of await kit.locator('.nk-kit-row a[href]').all()) {
        expect(await link.getAttribute('href')).toMatch(/^https:\/\//);
      }

      // Mobile layout: the page must not scroll sideways — at this project's viewport, and at 375px.
      //
      // 375 is checked explicitly because the projects here are 1280 and Pixel 7 (412), and the step rail
      // overflowed at exactly the widths in between: step 3's label is nowrap, the rail's flex items could
      // not shrink, and on an iPhone the whole page moved sideways instead. Nothing narrower than Pixel 7
      // was ever measured, so nothing caught it.
      const noSideScroll = async () => {
        const { scrollW, clientW } = await page.evaluate(() => ({
          scrollW: document.documentElement.scrollWidth,
          clientW: document.documentElement.clientWidth,
        }));
        expect(scrollW, `horizontal overflow at ${clientW}px`).toBeLessThanOrEqual(clientW);
      };
      await noSideScroll();

      const original = page.viewportSize();
      await page.setViewportSize({ width: 375, height: 812 });
      await noSideScroll();
      if (original) await page.setViewportSize(original);

      await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-20-demo-kit.png`, fullPage: true });
    });
});
