// Sprint 10.185 (privacy/consent hardening): the versioned record of the visitor's Google Analytics decision.
// This module is PURE localStorage state — it holds no DOM or GA side effects (those live in analytics.ts), so
// remembering the choice never itself loads or triggers analytics. Bump CONSENT_VERSION whenever the consent
// model materially changes (new processor, new purpose) so previously-decided users are asked again.
export const CONSENT_STORAGE_KEY = 'budgetspace.consent';
export const CONSENT_VERSION = 1;

export type ConsentStatus = 'granted' | 'denied';

export interface ConsentRecord {
  /** Consent-model version this decision was made against. */
  v: number;
  /** The analytics decision. Advertising is never granted, so it is not stored. */
  analytics: ConsentStatus;
  /** ISO timestamp of when the decision was made. */
  ts: string;
}

// The stored decision, or null when there is no VALID current-version decision — missing, malformed, or made
// against an older consent version. In every one of those cases the visitor must be asked again and analytics
// stays denied; we never infer consent from anything other than a valid record here.
export function readConsent(): ConsentRecord | null {
  if (typeof window === 'undefined') return null;
  let raw: string | null;
  try {
    raw = window.localStorage.getItem(CONSENT_STORAGE_KEY);
  } catch {
    return null; // storage blocked (private mode) → treat as undecided
  }
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<ConsentRecord>;
    if (parsed?.v !== CONSENT_VERSION) return null;
    if (parsed.analytics !== 'granted' && parsed.analytics !== 'denied') return null;
    return { v: CONSENT_VERSION, analytics: parsed.analytics, ts: typeof parsed.ts === 'string' ? parsed.ts : '' };
  } catch {
    return null;
  }
}

// Persist the choice. Storing this preference is functional (it stops us re-asking) and does not load analytics.
export function writeConsent(status: ConsentStatus): ConsentRecord {
  const record: ConsentRecord = { v: CONSENT_VERSION, analytics: status, ts: new Date().toISOString() };
  try {
    window.localStorage.setItem(CONSENT_STORAGE_KEY, JSON.stringify(record));
  } catch {
    /* private mode — the in-memory decision still applies for this page load */
  }
  return record;
}

export function analyticsGranted(): boolean {
  return readConsent()?.analytics === 'granted';
}

// True when no valid decision is on record → the consent banner must be shown and analytics kept denied.
export function needsConsentDecision(): boolean {
  return readConsent() === null;
}
