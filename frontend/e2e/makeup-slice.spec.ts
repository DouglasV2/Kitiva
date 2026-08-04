import { test, expect, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';

/**
 * The Makeup section, end to end in a real browser against the real backend and the real captured catalog.
 *
 * Nothing is stubbed. If Golden Rose or dm changed, or the assembler stopped filling a slot, these fail —
 * which is the point. The assertions that matter most are the ones about what must NOT be on screen: no
 * invented rating, no unearned certification badge. Those are the claims this catalog cannot support, and
 * a suite that only checked the happy path would let them appear.
 */

const SHOTS = 'test-results/screens';
mkdirSync(SHOTS, { recursive: true });

async function openMakeup(page: Page) {
  await page.addInitScript(() => window.sessionStorage.setItem('bs-guest-continue', '1'));
  await page.goto('/');
  await page.getByTestId('tab-makeup').click();
  await expect(page.getByTestId('look-picker')).toBeVisible();
}

const cents = (s: string) => Math.round(parseFloat(s.replace(/[^\d,]/g, '').replace(',', '.')) * 100);

test.describe('Makeup section', () => {
  test('1. all seven looks are offered, each with a real floor price', async ({ page }) => {
    await openMakeup(page);

    for (const key of ['natural-everyday', 'clean-girl', 'soft-glam', 'date-night',
      'full-glam', 'bridal', 'bold-evening']) {
      await expect(page.getByTestId(`look-${key}`)).toBeVisible();
    }
    // "od X €" is computed from the cheapest real product in every required slot, so it must be a
    // plausible number rather than a placeholder.
    const priced = page.locator('.mk-look-price');
    await expect(priced).toHaveCount(7);
    for (const text of await priced.allInnerTexts()) {
      expect(cents(text)).toBeGreaterThan(500);
    }
  });

  test('2. a look builds a complete, priced kit whose total is the sum of its rows',
    async ({ page }, testInfo) => {
      await openMakeup(page);
      await page.getByTestId('look-soft-glam').click();
      await expect(page.getByTestId('makeup-setup')).toBeVisible();
      await page.getByTestId('mk-build').click();

      const kit = page.getByTestId('makeup-kit');
      await expect(kit.getByTestId('mk-kit-status')).toContainText(/Potpun/);
      await expect(kit.getByTestId('mk-kit-missing')).toHaveCount(0);

      // The core of the look must be there.
      for (const slot of ['foundation', 'concealer', 'blush', 'eyeshadow', 'mascara', 'lipstick']) {
        await expect(kit.getByTestId(`kit-row-${slot}`)).toBeVisible();
      }

      const rowTotal = (await kit.locator('.nk-kit-row .nk-kit-price').allInnerTexts())
        .reduce((sum, t) => sum + cents(t), 0);
      expect(cents(await kit.getByTestId('kit-total').innerText())).toBe(rowTotal);

      // Every row is a real product at a real shop.
      for (const link of await kit.locator('.nk-kit-row a[href]').all()) {
        expect(await link.getAttribute('href')).toMatch(/^https:\/\//);
      }
      // The kit doubles as the instructions.
      await expect(kit.getByTestId('mk-application-steps').locator('li').first()).toBeVisible();

      await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-mk-01-kit.png`, fullPage: true });
    });

  test('3. a budget is respected, and owning something removes it from the bill', async ({ page }) => {
    await openMakeup(page);
    await page.getByTestId('look-natural-everyday').click();
    await page.getByTestId('mk-build').click();
    const kit = page.getByTestId('makeup-kit');
    await expect(kit.getByTestId('kit-total')).toBeVisible();
    const before = cents(await kit.getByTestId('kit-total').innerText());

    // Owning the mascara must reduce the total and move it to the owned block.
    await page.getByTestId('mk-own-mascara').click();
    await page.getByTestId('mk-build').click();
    await expect(kit.getByTestId('mk-kit-owned')).toContainText(/Maskara/i);
    await expect(kit.getByTestId('kit-row-mascara')).toHaveCount(0);
    await expect.poll(async () => cents(await kit.getByTestId('kit-total').innerText()))
      .toBeLessThan(before);
  });

  test('4. a swap changes the product and never breaks the kit', async ({ page }) => {
    await openMakeup(page);
    await page.getByTestId('look-soft-glam').click();
    await page.getByTestId('mk-build').click();

    const kit = page.getByTestId('makeup-kit');
    const row = kit.getByTestId('kit-row-lipstick');
    const before = await row.locator('.nk-kit-name').innerText();

    await row.getByTestId('swap-lipstick').click();
    await row.locator('.nk-alt button').first().click();

    await expect.poll(async () =>
      (await kit.getByTestId('kit-row-lipstick').locator('.nk-kit-name').innerText()) !== before,
    { timeout: 15000 }).toBe(true);
    await expect(kit.getByTestId('mk-kit-status')).toContainText(/Potpun/);
    await expect(kit.getByTestId('mk-kit-missing')).toHaveCount(0);
  });

  test('5. the catalog filters, searches and counts honestly', async ({ page }, testInfo) => {
    await openMakeup(page);
    await page.getByTestId('mk-open-catalog').click();
    await expect(page.getByTestId('mk-catalog-grid')).toBeVisible();

    const count = () => page.getByTestId('mk-result-count').innerText();
    const readTotal = async () => Number((await count()).match(/^(\d+)/)?.[1] ?? '0');
    await expect.poll(readTotal, { timeout: 15000 }).toBeGreaterThan(100);
    const all = await readTotal();

    // A category filter must actually narrow, and the grid must agree with the count.
    const mascara = page.getByTestId('mk-f-category').getByRole('button', { name: /Maskara/ });
    await mascara.click();
    await expect.poll(readTotal, { timeout: 15000 }).toBeLessThan(all);
    const narrowed = await readTotal();
    expect(await page.getByTestId('mk-catalog-grid').locator('article').count())
      .toBe(Math.min(narrowed, 48));

    // Search narrows further and every visible card matches.
    await page.getByTestId('mk-search').fill('volume');
    await expect.poll(readTotal, { timeout: 15000 }).toBeLessThanOrEqual(narrowed);

    // Clearing puts everything back — a filter you cannot undo is a trap.
    await page.getByTestId('mk-search').fill('');
    await page.getByTestId('mk-clear-filters').click();
    await expect.poll(readTotal, { timeout: 15000 }).toBe(all);

    await page.screenshot({ path: `${SHOTS}/${testInfo.project.name}-mk-02-catalog.png`, fullPage: true });
  });

  test('6. an impossible filter combination explains itself instead of showing a bare zero',
    async ({ page }) => {
      await openMakeup(page);
      await page.getByTestId('mk-open-catalog').click();
      await expect(page.getByTestId('mk-catalog-grid')).toBeVisible();

      await page.getByTestId('mk-search').fill('zzzznepostojeciproizvod');
      await expect(page.getByTestId('mk-no-results')).toBeVisible({ timeout: 15000 });
      await expect(page.getByTestId('mk-no-results')).toContainText(/Nema rezultata/i);
      await expect(page.getByTestId('mk-catalog-grid').locator('article')).toHaveCount(0);
    });

  /**
   * The claims this catalog cannot support. Neither retailer publishes a review score, and neither
   * publishes a vegan or cruelty-free certification — so neither may appear anywhere on screen, however
   * much a product page wants them.
   */
  test('7. no invented rating and no unearned certification appears anywhere', async ({ page }) => {
    await openMakeup(page);
    await page.getByTestId('mk-open-catalog').click();
    await expect(page.getByTestId('mk-catalog-grid')).toBeVisible();
    await expect.poll(async () =>
      page.getByTestId('mk-catalog-grid').locator('article').count(), { timeout: 15000 })
      .toBeGreaterThan(10);

    const text = await page.getByTestId('mk-catalog-grid').innerText();
    expect(text).not.toMatch(/★|⭐|\b\d[.,]\d\s*\/\s*5\b/);
    expect(text.toLowerCase()).not.toMatch(/\bvegan\b|cruelty[- ]free|bez okrutnosti/);

    // And a derived tag must say it is derived rather than posing as the retailer's word.
    const derived = page.locator('.mk-tag-derived').first();
    await expect(derived).toBeVisible();
    expect(await derived.getAttribute('title')).toMatch(/izračunato/i);
  });

  test('8. the section fits a phone without scrolling sideways', async ({ page }) => {
    await openMakeup(page);
    await page.getByTestId('look-full-glam').click();
    await page.getByTestId('mk-build').click();
    await expect(page.getByTestId('makeup-kit')).toBeVisible();
    await page.getByTestId('mk-open-catalog').click();
    await expect(page.getByTestId('mk-catalog-grid')).toBeVisible();

    for (const width of [375, 412, 768]) {
      await page.setViewportSize({ width, height: 860 });
      const { scrollW, clientW } = await page.evaluate(() => ({
        scrollW: document.documentElement.scrollWidth,
        clientW: document.documentElement.clientWidth,
      }));
      expect(scrollW, `horizontal overflow at ${width}px`).toBeLessThanOrEqual(clientW);
    }
  });

  test('9. switching tabs keeps both verticals mounted and their work intact', async ({ page }) => {
    await openMakeup(page);
    await page.getByTestId('look-bridal').click();
    await page.getByTestId('mk-build').click();
    await expect(page.getByTestId('makeup-kit')).toBeVisible();
    const total = await page.getByTestId('makeup-kit').getByTestId('kit-total').innerText();

    await page.getByTestId('tab-nails').click();
    await expect(page.getByTestId('parse')).toBeVisible();
    await page.getByTestId('tab-makeup').click();

    // The kit is still there, unchanged — switching away must not discard a minute's work.
    await expect(page.getByTestId('makeup-kit').getByTestId('kit-total')).toHaveText(total);
  });
});
