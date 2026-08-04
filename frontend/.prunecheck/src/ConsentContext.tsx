// Sprint 10.185 (privacy/consent hardening): ties the pure consent record (consent.ts) to the Google Analytics
// runtime (analytics.ts). It is the ONLY place that decides to load GA, so analytics can never start before an
// explicit grant. On mount it reconciles a returning visitor's stored choice; accept()/reject()/openSettings()
// drive the banner + GA at runtime.
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { analyticsConfigured, clearGaCookies, disableAnalytics, enableAnalytics } from './utils/analytics';
import { analyticsGranted, needsConsentDecision, readConsent, writeConsent } from './utils/consent';

type AnalyticsChoice = 'granted' | 'denied' | 'unset';

interface ConsentContextValue {
  /** Whether a GA measurement id is configured at all. When false, no consent UI is shown and GA never loads. */
  configured: boolean;
  /** True while the banner should be visible (no valid decision yet, or the user reopened Privacy settings). */
  bannerOpen: boolean;
  /** The current analytics decision. */
  status: AnalyticsChoice;
  accept: () => void;
  reject: () => void;
  /** Reopen the consent panel from the footer ("Postavke privatnosti"). */
  openSettings: () => void;
  closeSettings: () => void;
}

const ConsentContext = createContext<ConsentContextValue | null>(null);

export function ConsentProvider({ children }: { children: ReactNode }) {
  const configured = analyticsConfigured();
  const [reopened, setReopened] = useState(false);
  const [status, setStatus] = useState<AnalyticsChoice>(() => readConsent()?.analytics ?? 'unset');

  // Reconcile the stored decision with the analytics runtime once per load.
  useEffect(() => {
    if (!configured) return;
    if (analyticsGranted()) {
      // Returning visitor who already accepted → start GA (idempotent).
      enableAnalytics();
    } else if (needsConsentDecision()) {
      // No valid decision on record: never infer consent from a stray _ga cookie left by an earlier version —
      // drop the same-origin GA cookies BudgetSpace can safely remove, and let the banner ask again.
      clearGaCookies();
    }
    // 'denied' → do nothing; GA is never initialised this load.
  }, [configured]);

  const bannerOpen = configured && (status === 'unset' || reopened);

  const accept = useCallback(() => {
    writeConsent('granted');
    setStatus('granted');
    setReopened(false);
    enableAnalytics();
  }, []);

  const reject = useCallback(() => {
    writeConsent('denied');
    setStatus('denied');
    setReopened(false);
    disableAnalytics(); // no-op if never initialised; also drops removable same-origin GA cookies
  }, []);

  const openSettings = useCallback(() => setReopened(true), []);
  const closeSettings = useCallback(() => setReopened(false), []);

  const value = useMemo<ConsentContextValue>(
    () => ({ configured, bannerOpen, status, accept, reject, openSettings, closeSettings }),
    [configured, bannerOpen, status, accept, reject, openSettings, closeSettings]
  );

  return <ConsentContext.Provider value={value}>{children}</ConsentContext.Provider>;
}

export function useConsent(): ConsentContextValue {
  const ctx = useContext(ConsentContext);
  if (!ctx) throw new Error('useConsent must be used within a ConsentProvider');
  return ctx;
}
