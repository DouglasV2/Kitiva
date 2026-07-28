import { useEffect } from 'react';
import { AuthGate } from './components/AuthGate';
import { ConsentBanner } from './components/ConsentBanner';
import { Footer } from './components/Footer';
import { Header } from './components/Header';
import { LanguageSuggestion } from './components/LanguageSuggestion';
import { HowItWorks } from './components/HowItWorks';
import { PlannerHero } from './components/PlannerHero';
import { Planner } from './components/Planner';
import { useState } from 'react';
import { NailLook } from './components/NailLook';
import { AuthProvider, useAuth } from './AuthContext';
import { ConsentProvider } from './ConsentContext';
import { LocaleProvider } from './LocaleContext';

// A shared plan link (/plan/<id>) must open without a sign-in wall — that recipient may be a logged-out
// visitor, and the share growth loop depends on it. So the front door never gates these.
function isSharedPlanLink() {
  return /^\/plan\/[^/]+$/.test(window.location.pathname);
}

function AppShell() {
  const { user, loading, guestContinued } = useAuth();
  const shared = isSharedPlanLink();
  // SEO sprint: shared plans stay reachable by anyone with the link but must not be indexed. The authoritative
  // control is the nginx `X-Robots-Tag: noindex` header on /plan/:id; this flips the document's robots meta to
  // noindex as a client-side defence for a JS-rendering crawler. The homepage keeps index,follow from index.html.
  useEffect(() => {
    if (!shared) return;
    document.querySelector('meta[name="robots"]')?.setAttribute('content', 'noindex, nofollow');
  }, [shared]);
  // Returning guests and shared-link recipients are decided synchronously (sessionStorage / pathname), so they
  // render immediately. Only a truly-undecided first visit waits for the /me round-trip — showing a neutral
  // splash rather than flashing the whole app and then slamming the front door over it.
  const decided = !loading || guestContinued || shared;
  const showGate = decided && !user && !guestContinued && !shared;

  if (!decided) {
    return <div className="auth-splash" aria-hidden="true" />;
  }

  return (
    <main>
      <Header />
      {/* Sprint 10.188: one-time "your browser is English — switch?" prompt (self-gates; no-op for most). */}
      <LanguageSuggestion />
      {/* Sprint 10.182: header hero band (owner mockup) — three-beat headline + subtitle + new-plan / my-plans
          actions + a drop-in visual. Replaces the slim PlannerSubnav strip; beta state is read inside. */}
      <PlannerHero />
      {/* Beauty pivot vertical slice: the Nail Look experience runs alongside the furniture planner while
          the pivot lands phase by phase. Both stay mounted so switching never loses work. */}
      <ExperienceSwitch />
      <HowItWorks />
      <Footer />
      {showGate && <AuthGate />}
      {/* Sprint 10.185: analytics-consent banner. Non-modal; only appears when a GA id is configured and no
          valid decision exists (or the user reopened it from the footer). Never blocks the app. */}
      <ConsentBanner />
    </main>
  );
}

/**
 * Two-experience switch. Both panes stay MOUNTED and are toggled with `hidden`, reusing the pattern the
 * furniture planner already uses for its scope switch - so flipping tabs never discards a parsed brief or
 * a generated kit.
 */
function ExperienceSwitch() {
  const [experience, setExperience] = useState<'nails' | 'furniture'>('nails');
  return (
    <>
      <div className="scope-toggle navtrack scope-switch shell" role="group" aria-label="Odaberi iskustvo">
        <button
          type="button"
          className={experience === 'nails' ? 'scope-option active' : 'scope-option'}
          aria-pressed={experience === 'nails'}
          onClick={() => setExperience('nails')}
          data-testid="tab-nails"
        >
          <span className="scope-text">Nail Look / Nail Kit</span>
        </button>
        <button
          type="button"
          className={experience === 'furniture' ? 'scope-option active' : 'scope-option'}
          aria-pressed={experience === 'furniture'}
          onClick={() => setExperience('furniture')}
          data-testid="tab-furniture"
        >
          <span className="scope-text">Prostor (postojeće)</span>
        </button>
      </div>
      <div hidden={experience !== 'nails'}><NailLook /></div>
      <div hidden={experience !== 'furniture'}><Planner /></div>
    </>
  );
}

export default function App() {
  return (
    <LocaleProvider>
      <AuthProvider>
        <ConsentProvider>
          <AppShell />
        </ConsentProvider>
      </AuthProvider>
    </LocaleProvider>
  );
}
