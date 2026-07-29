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

async function parsePrompt(page: Page, prompt = PROMPT, budget?: string) {
  await page.locator('#nail-prompt').fill(prompt);
  if (budget !== undefined) await page.getByTestId('f-budget').fill(budget);
  await page.getByTestId('parse').click();
  await expect(page.getByTestId('nail-brief')).toBeVisible();
}

/** Budget lives below the brief, so it can only be typed after a parse. */
async function setBudget(page: Page, value: string) {
  await page.getByTestId('f-budget').fill(value);
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
    await setBudget(page, '30');
    await page.getByTestId('choose-home').click();
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
    await setBudget(page, '40');
    await page.getByTestId('f-system').selectOption('press-on');
    await page.getByTestId('choose-home').click();
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
    await setBudget(page, '30');
    await page.getByTestId('choose-home').click();
  await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    const before = await kit.getByTestId('kit-total').innerText();
    const beforeCents = Math.round(parseFloat(before.replace(/[^\d,]/g, '').replace(',', '.')) * 100);

    // Mark base and top as already owned, then rebuild.
    await page.getByTestId('owned-base').click();
    await page.getByTestId('owned-top').click();
    await page.getByTestId('choose-home').click();
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
    await setBudget(page, '3');
    await page.getByTestId('choose-home').click();
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
    // Reached through the REAL flow with the REAL catalog — no fixture, no demo toggle. The catalog has no
    // gold-detail product at all, and no press-on set that is simultaneously almond, burgundy and cat-eye.
    // The kit used to answer this with ordinary glossy lacquer and call itself Complete with assumptions.
    await openApp(page);
    await parsePrompt(page, PROMPT);
    await setBudget(page, '40');
    await page.getByTestId('choose-home').click();
    await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit.getByTestId('kit-status')).toContainText(/Nepotpun/);

    // The UI must say WHAT is missing, not just that something is.
    const missing = kit.getByTestId('kit-missing');
    await expect(missing).toBeVisible();
    await expect(missing).toContainText(/zlatni detalj/i);

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-12-incomplete-real.png`, fullPage: true });

    // Press-ons cannot reproduce it either.
    await page.getByTestId('f-system').selectOption('press-on');
    await page.getByTestId('fork-continue').click();
    await expect(kit.getByTestId('kit-status')).toContainText(/Nepotpun/);
    await expect(kit.getByTestId('kit-missing')).toContainText(/zlatni detalj/i);
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
    await page.getByTestId('choose-home').click();
  await page.getByTestId('fork-continue').click();

    const kit = page.getByTestId('nail-kit');
    await expect(kit.getByTestId('kit-status')).toContainText(/sigurnosnih razloga/i);
    await expect(kit.locator('.nk-kit-row')).toHaveCount(0);

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-08-safety-blocked.png`, fullPage: true });
  });

  test('8. safety blocked: a volunteered health concern is refused without diagnosing', async ({ page }, testInfo) => {
    await openApp(page);
    await parsePrompt(page, 'Nokat mi je upaljen i boli me, ali želim lakirati nokte kod kuće.');
    await page.getByTestId('choose-home').click();
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
    await setBudget(page, '30');
    await page.getByTestId('choose-home').click();
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
    await setBudget(page, '30');
    await page.getByTestId('choose-home').click();
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
    await setBudget(page, '30');

    await page.getByTestId('choose-home').click();
  await page.getByTestId('fork-continue').click();
    const kit = page.getByTestId('nail-kit');
    const first = await kit.getByTestId('kit-total').innerText();

    // Same brief, generated again: totals are derived, not accumulated, so nothing may drift.
    await page.getByTestId('choose-home').click();
  await page.getByTestId('fork-continue').click();
    await expect(kit.getByTestId('kit-total')).toHaveText(first);

    // And again after a full reload + re-parse of the same prompt.
    await openApp(page);
    await parsePrompt(page);
    await setBudget(page, '30');
    await page.getByTestId('choose-home').click();
  await page.getByTestId('fork-continue').click();
    await expect(page.getByTestId('nail-kit').getByTestId('kit-total')).toHaveText(first);
  });
});
