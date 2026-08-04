// Sprint 10.185 (privacy/consent hardening): the Google Analytics consent banner. It is NON-modal — it never
// blocks the app, and it only appears when a GA id is configured AND there is no valid decision yet (or the user
// reopened it from "Postavke privatnosti"). Odbij and Prihvati are given equal visual weight; silence/scrolling
// is never treated as consent. "Saznaj više" opens the Privacy Policy.
import { useEffect, useRef, useState } from 'react';
import { useConsent } from '../ConsentContext';


export function ConsentBanner() {
  const { bannerOpen, status, accept, reject, closeSettings } = useConsent();
  const [showPrivacy, setShowPrivacy] = useState(false);
  const regionRef = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<HTMLElement | null>(null);
  const showPrivacyRef = useRef(false);
  showPrivacyRef.current = showPrivacy;
  // A decision already exists → this is the reopened Privacy-settings panel (dismissible), not the first ask.
  const reopened = status !== 'unset';

  // Publish the banner's height so the sticky generate bar can sit above it (nothing gets covered on mobile).
  useEffect(() => {
    const root = document.documentElement;
    const el = regionRef.current;
    if (!bannerOpen || !el) {
      root.style.removeProperty('--consent-banner-h');
      return;
    }
    const apply = () => root.style.setProperty('--consent-banner-h', `${el.offsetHeight}px`);
    apply();
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(apply) : null;
    observer?.observe(el);
    return () => {
      observer?.disconnect();
      root.style.removeProperty('--consent-banner-h');
    };
  }, [bannerOpen]);

  // When the user REOPENS settings, move focus into the panel and restore it on close (keyboard + SR users).
  // The first-time banner does not steal focus (it is non-modal and the app stays usable), but stays until a
  // choice is made.
  useEffect(() => {
    if (!bannerOpen || !reopened) return;
    restoreRef.current = document.activeElement as HTMLElement | null;
    regionRef.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !showPrivacyRef.current) closeSettings();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      restoreRef.current?.focus?.();
    };
  }, [bannerOpen, reopened, closeSettings]);

  if (!bannerOpen) return null;

  return (
    <>
      <div
        ref={regionRef}
        className="consent-banner"
        role="dialog"
        aria-modal="false"
        aria-labelledby="consent-title"
        aria-describedby="consent-desc"
        tabIndex={-1}
      >
        <div className="consent-banner-inner">
          <div className="consent-copy">
            <strong id="consent-title" className="consent-title">{'Za bolji Nail Kit'}</strong>
            <p id="consent-desc" className="consent-desc">
              {'Google Analytics se koristi samo uz pristanak.'}{' '}
              <button type="button" className="consent-learn-more" onClick={() => setShowPrivacy(true)}>
                {'Vise informacija'}
              </button>
            </p>
          </div>
          <div className="consent-actions">
            {/* Equal weight, equal size — Odbij is never hidden or de-emphasised. */}
            <button type="button" className="consent-btn consent-reject" onClick={reject}>
              {'Odbij'}
            </button>
            <button type="button" className="consent-btn consent-accept" onClick={accept}>
              {'Prihvati'}
            </button>
          </div>
          {reopened && (
            <button type="button" className="consent-close" aria-label={'Zatvori'} onClick={closeSettings}>
              ×
            </button>
          )}
        </div>
      </div>
    </>
  );
}
