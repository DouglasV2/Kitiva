// Sprint 10.185 (privacy/consent hardening): unit + integration tests for the analytics-consent layer.
// The two collaborators under test:
//   consent.ts   — pure, versioned localStorage record of the analytics decision (no DOM/GA effects).
//   analytics.ts — the Google Analytics side effects (script injection, gtag, cookie cleanup, opt-out).
// The React banner/context wiring is exercised in the browser (Part 7); here we pin the behaviour that must
// hold regardless of UI: GA cannot load or send before consent, loads exactly once after acceptance, and
// stops cleanly on withdrawal.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const GA_ID = 'G-TEST1234';

// analytics.ts keeps module-level init/disabled state, so each test gets a fresh instance.
async function freshAnalytics() {
  vi.resetModules();
  return import('./analytics');
}

function gaScripts() {
  return Array.from(document.querySelectorAll('script[src*="googletagmanager.com/gtag/js"]'));
}
function dataLayerCalls(): unknown[][] {
  return (window.dataLayer ?? []).map((args) => Array.from(args as ArrayLike<unknown>));
}
function setCookie(name: string, value: string) {
  document.cookie = `${name}=${value}`;
}
function getCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return match ? match[1] : null;
}

beforeEach(() => {
  localStorage.clear();
  // Wipe cookies between tests.
  for (const pair of document.cookie.split(';')) {
    const key = pair.split('=')[0].trim();
    if (key) document.cookie = `${key}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  }
  // Wipe any GA globals the shared jsdom window carries between tests.
  gaScripts().forEach((script) => script.remove());
  delete (window as unknown as Record<string, unknown>).gtag;
  delete (window as unknown as Record<string, unknown>).dataLayer;
  delete (window as unknown as Record<string, unknown>)[`ga-disable-${GA_ID}`];
  vi.stubEnv('VITE_GA_MEASUREMENT_ID', GA_ID);
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('consent store', () => {
  it('returns null when no decision has been recorded', async () => {
    const { readConsent } = await import('./consent');
    expect(readConsent()).toBeNull();
  });

  it('persists a granted decision with the current version and a timestamp', async () => {
    const { writeConsent, readConsent, CONSENT_VERSION, CONSENT_STORAGE_KEY } = await import('./consent');
    writeConsent('granted');
    const record = readConsent();
    expect(record).toMatchObject({ v: CONSENT_VERSION, analytics: 'granted' });
    expect(typeof record?.ts).toBe('string');
    expect(localStorage.getItem(CONSENT_STORAGE_KEY)).toContain('granted');
  });

  it('persists a denied decision', async () => {
    const { writeConsent, analyticsGranted, needsConsentDecision } = await import('./consent');
    writeConsent('denied');
    expect(analyticsGranted()).toBe(false);
    expect(needsConsentDecision()).toBe(false);
  });

  it('treats an outdated consent version as no decision (asks again)', async () => {
    const { CONSENT_STORAGE_KEY, readConsent, needsConsentDecision } = await import('./consent');
    localStorage.setItem(CONSENT_STORAGE_KEY, JSON.stringify({ v: 0, analytics: 'granted', ts: '2020-01-01' }));
    expect(readConsent()).toBeNull();
    expect(needsConsentDecision()).toBe(true);
  });

  it('treats a malformed consent record as no decision', async () => {
    const { CONSENT_STORAGE_KEY, readConsent } = await import('./consent');
    localStorage.setItem(CONSENT_STORAGE_KEY, 'not-json{');
    expect(readConsent()).toBeNull();
  });
});

describe('analytics consent gating', () => {
  it('does not load GA or fire events before a decision (fresh visitor)', async () => {
    const analytics = await freshAnalytics();
    expect(analytics.analyticsConfigured()).toBe(true);
    expect(gaScripts()).toHaveLength(0);
    expect(window.gtag).toBeUndefined();
    // Events attempted before init must no-op, not queue.
    analytics.trackEvent('plan_generate_start', { room_type: 'bedroom' });
    analytics.trackPageView('/planner');
    expect(window.dataLayer ?? []).toHaveLength(0);
  });

  it('loads GA exactly once and sends the first page view after acceptance', async () => {
    const analytics = await freshAnalytics();
    analytics.initAnalytics();
    expect(gaScripts()).toHaveLength(1);
    expect(typeof window.gtag).toBe('function');
    const pageViews = dataLayerCalls().filter((c) => c[0] === 'event' && c[1] === 'page_view');
    expect(pageViews).toHaveLength(1);
  });

  it('does not duplicate the script or page view on a repeat init (remount / reload path)', async () => {
    const analytics = await freshAnalytics();
    analytics.initAnalytics();
    analytics.initAnalytics();
    analytics.initAnalytics();
    expect(gaScripts()).toHaveLength(1);
    expect(dataLayerCalls().filter((c) => c[0] === 'event' && c[1] === 'page_view')).toHaveLength(1);
  });

  it('grants only analytics consent and keeps advertising consent denied', async () => {
    const analytics = await freshAnalytics();
    analytics.initAnalytics();
    const calls = dataLayerCalls();
    const consentDefault = calls.find((c) => c[0] === 'consent' && c[1] === 'default');
    expect(consentDefault?.[2]).toMatchObject({
      analytics_storage: 'denied',
      ad_storage: 'denied',
      ad_user_data: 'denied',
      ad_personalization: 'denied'
    });
    const consentUpdate = calls.find((c) => c[0] === 'consent' && c[1] === 'update');
    expect(consentUpdate?.[2]).toMatchObject({ analytics_storage: 'granted' });
    // Advertising is never granted anywhere.
    expect(calls.some((c) => JSON.stringify(c[2] ?? '').includes('"ad_storage":"granted"'))).toBe(false);
  });

  it('does not track raw prompt text in events (categorical params only)', async () => {
    const analytics = await freshAnalytics();
    analytics.initAnalytics();
    analytics.trackEvent('plan_generate_start', { room_type: 'bedroom', budget: 1500 });
    const evt = dataLayerCalls().find((c) => c[0] === 'event' && c[1] === 'plan_generate_start');
    expect(evt?.[2]).not.toHaveProperty('prompt');
    expect(JSON.stringify(evt?.[2])).not.toContain('prompt');
  });

  it('does not load GA when the measurement ID is missing (no throw, no banner)', async () => {
    vi.stubEnv('VITE_GA_MEASUREMENT_ID', '');
    const analytics = await freshAnalytics();
    expect(analytics.analyticsConfigured()).toBe(false);
    expect(() => analytics.initAnalytics()).not.toThrow();
    expect(gaScripts()).toHaveLength(0);
    expect(window.gtag).toBeUndefined();
  });

  it('stops sending events and removes same-origin GA cookies after withdrawal', async () => {
    const analytics = await freshAnalytics();
    analytics.initAnalytics();
    setCookie('_ga', 'GA1.1.123.456');
    setCookie('_ga_ABC123', 'GS1.1.789');
    setCookie('_gid', 'GA1.1.987');
    setCookie('bs-session-id', 'keep-me'); // a non-GA cookie must survive
    const queuedBefore = (window.dataLayer ?? []).length;

    analytics.disableAnalytics();

    expect((window as unknown as Record<string, unknown>)[`ga-disable-${GA_ID}`]).toBe(true);
    analytics.trackEvent('product_click', { retailer: 'IKEA' });
    analytics.trackPageView('/a-new-path');
    expect((window.dataLayer ?? []).length).toBe(queuedBefore);
    expect(getCookie('_ga')).toBeNull();
    expect(getCookie('_ga_ABC123')).toBeNull();
    expect(getCookie('_gid')).toBeNull();
    expect(getCookie('bs-session-id')).toBe('keep-me');
  });

  it('re-initializes cleanly after a reject-then-accept without duplicating the script', async () => {
    const analytics = await freshAnalytics();
    // Rejecting simply never calls init.
    expect(gaScripts()).toHaveLength(0);
    // Later acceptance.
    analytics.initAnalytics();
    expect(gaScripts()).toHaveLength(1);
    expect(dataLayerCalls().filter((c) => c[0] === 'event' && c[1] === 'page_view')).toHaveLength(1);
  });

  it('resumes analytics after a withdraw-then-accept in the same session (no second script)', async () => {
    const analytics = await freshAnalytics();
    analytics.enableAnalytics(); // accept → first load
    expect(gaScripts()).toHaveLength(1);

    analytics.disableAnalytics(); // withdraw
    expect((window as unknown as Record<string, unknown>)[`ga-disable-${GA_ID}`]).toBe(true);

    analytics.enableAnalytics(); // re-accept in the same session
    expect((window as unknown as Record<string, unknown>)[`ga-disable-${GA_ID}`]).toBe(false);
    expect(gaScripts()).toHaveLength(1); // no duplicate tag
    // Events flow again, and a page view is re-sent on resume.
    analytics.trackEvent('product_click', { retailer: 'JYSK' });
    expect(dataLayerCalls().some((c) => c[1] === 'product_click')).toBe(true);
    expect(dataLayerCalls().filter((c) => c[0] === 'event' && c[1] === 'page_view').length).toBeGreaterThanOrEqual(2);
  });
});
