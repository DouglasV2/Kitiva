type AnalyticsValue = string | number | boolean | null | undefined;
type AnalyticsParams = Record<string, AnalyticsValue>;

declare global {
  interface Window {
    dataLayer?: IArguments[];
    gtag?: (...args: unknown[]) => void;
  }
}

// Sprint 10.185 (privacy/consent hardening): Google Analytics is CONSENT-GATED. Nothing here runs until the
// visitor explicitly accepts analytics — `initAnalytics()` is the single entry point and is only called by the
// consent layer after a granted decision (see ConsentContext + consent.ts). Before that: no gtag.js request,
// no page view, no event. Advertising/remarketing is never enabled.

// Read the id at call time (not a load-time const) so a missing/changed env is honoured and the id is a single
// source of truth for both "is analytics configured?" and the script/consent wiring.
function measurementId(): string {
  return (import.meta.env.VITE_GA_MEASUREMENT_ID ?? '').trim();
}

let initialized = false;
// Set once the visitor WITHDRAWS analytics consent: every gtag path becomes a no-op and GA's own kill-switch
// (window['ga-disable-<ID>']) stops an already-loaded tag from sending — without reloading or breaking the app.
let disabled = false;
let lastTrackedPath = '';

function hasValidMeasurementId() {
  return /^G-[A-Z0-9]+$/i.test(measurementId());
}

/** True when a GA measurement id is configured. The consent banner only ever appears when this holds. */
export function analyticsConfigured(): boolean {
  return hasValidMeasurementId();
}

function currentPath() {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

function safeGtag(...args: unknown[]) {
  if (disabled || typeof window === 'undefined' || !window.gtag || !hasValidMeasurementId()) return;
  window.gtag(...args);
}

// Load GA and send the first page view. Called ONLY after an explicit analytics-consent grant. Idempotent: a
// second call (React remount, or a returning user who already accepted) neither re-injects gtag.js nor
// re-sends the initial page view.
export function initAnalytics() {
  if (typeof window === 'undefined' || initialized || disabled || !hasValidMeasurementId()) return;
  initialized = true;
  const id = measurementId();

  window.dataLayer = window.dataLayer || [];
  window.gtag = function gtag() {
    window.dataLayer?.push(arguments);
  };

  const script = document.createElement('script');
  script.async = true;
  script.src = `https://www.googletagmanager.com/gtag/js?id=${encodeURIComponent(id)}`;
  document.head.appendChild(script);

  safeGtag('js', new Date());
  // Basic Consent Mode: default every category to denied, then grant ONLY analytics. Advertising consent
  // (ad_storage / ad_user_data / ad_personalization) stays denied — BudgetSpace runs no ads, remarketing or
  // personalization. We only reach here post-consent, but stating the defaults keeps the model auditable.
  safeGtag('consent', 'default', {
    analytics_storage: 'denied',
    ad_storage: 'denied',
    ad_user_data: 'denied',
    ad_personalization: 'denied'
  });
  safeGtag('consent', 'update', { analytics_storage: 'granted' });
  // Disable the automatic first page_view so we can send consistent SPA page views ourselves.
  safeGtag('config', id, { send_page_view: false });
  trackPageView();

  const notifyRouteChange = () => window.setTimeout(() => trackPageView(), 0);
  const wrapHistoryMethod = (methodName: 'pushState' | 'replaceState') => {
    const original = window.history[methodName];
    window.history[methodName] = function patchedHistoryMethod(...args) {
      const result = original.apply(this, args);
      notifyRouteChange();
      return result;
    };
  };

  wrapHistoryMethod('pushState');
  wrapHistoryMethod('replaceState');
  window.addEventListener('popstate', notifyRouteChange);
}

// Grant/resume analytics. On the first grant this loads GA (via initAnalytics); if the user had withdrawn and
// re-accepts in the same session, it clears GA's kill-switch and re-sends a page view — never re-injecting the
// tag, so there is no duplicate script or double initialisation.
export function enableAnalytics() {
  const wasDisabled = disabled;
  disabled = false;
  if (typeof window !== 'undefined' && hasValidMeasurementId()) {
    (window as unknown as Record<string, unknown>)[`ga-disable-${measurementId()}`] = false;
  }
  if (!initialized) {
    initAnalytics();
  } else if (wasDisabled) {
    lastTrackedPath = '';
    trackPageView();
  }
}

// Withdraw consent at runtime: flip GA's kill-switch so an already-loaded tag stops sending, make every wrapper
// a no-op, and drop the same-origin GA cookies. Does NOT reload the page.
export function disableAnalytics() {
  disabled = true;
  if (typeof window !== 'undefined' && hasValidMeasurementId()) {
    (window as unknown as Record<string, unknown>)[`ga-disable-${measurementId()}`] = true;
  }
  clearGaCookies();
}

// Remove the cookies GA sets on OUR origin (_ga, _ga_<container>, _gid, _gat*, _gac_*). We only touch cookies on
// domains BudgetSpace controls; cookies on other domains are left untouched.
export function clearGaCookies() {
  if (typeof document === 'undefined') return;
  const gaCookie = /^(_ga|_ga_[^=]+|_gid|_gat.*|_gac_.*)$/;
  const expiry = 'expires=Thu, 01 Jan 1970 00:00:00 GMT';
  const host = typeof window !== 'undefined' ? window.location.hostname : '';
  for (const pair of document.cookie.split(';')) {
    const name = pair.split('=')[0].trim();
    if (!name || !gaCookie.test(name)) continue;
    document.cookie = `${name}=; ${expiry}; path=/`;
    // GA typically scopes _ga to the registrable domain, so also expire the domain-scoped variants.
    if (host.split('.').length > 1) {
      document.cookie = `${name}=; ${expiry}; path=/; domain=${host}`;
      document.cookie = `${name}=; ${expiry}; path=/; domain=.${host}`;
    }
  }
}

export function trackPageView(path = currentPath()) {
  if (disabled || !hasValidMeasurementId() || path === lastTrackedPath) return;
  lastTrackedPath = path;
  safeGtag('event', 'page_view', {
    page_title: document.title,
    page_location: window.location.href,
    page_path: path
  });
}

export function trackEvent(name: string, params: AnalyticsParams = {}) {
  if (disabled || !hasValidMeasurementId()) return;
  safeGtag('event', name, params);
}
