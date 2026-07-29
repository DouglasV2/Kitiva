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
import './nailkit.css';
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
      {/* Beauty pivot vertical slice. The two experiences have different visual identities on purpose, so
          each renders its OWN chrome: the BudgetSpace header, hero, how-it-works and footer belong to the
          furniture product and would otherwise frame the nail page in another brand's colours — which is
          the single loudest way to make a beauty product look like a furniture tool. Both panes stay
          mounted so switching never discards a parsed brief or a generated kit. */}
      <ExperienceSwitch />
      {showGate && <AuthGate />}
      {/* Sprint 10.185: analytics-consent banner. Non-modal; only appears when a GA id is configured and no
          valid decision exists (or the user reopened it from the footer). Never blocks the app. */}
      <ConsentBanner />
    </main>
  );
}

function ExperienceSwitch() {
  const [experience, setExperience] = useState<'nails' | 'furniture'>('nails');
  const nails = experience === 'nails';
  return (
    <>
      <div className={nails ? 'nk-topbar' : 'scope-toggle navtrack scope-switch shell'} role="group" aria-label="Odaberi iskustvo">
        {nails && <span className="nk-wordmark">nokti<span>.</span></span>}
        <button
          type="button"
          className={nails ? 'nk-tab is-on' : 'scope-option'}
          aria-pressed={nails}
          onClick={() => setExperience('nails')}
          data-testid="tab-nails"
        >
          <span className="scope-text">Nail Look / Nail Kit</span>
        </button>
        <button
          type="button"
          className={nails ? 'nk-tab' : (experience === 'furniture' ? 'scope-option active' : 'scope-option')}
          aria-pressed={experience === 'furniture'}
          onClick={() => setExperience('furniture')}
          data-testid="tab-furniture"
        >
          <span className="scope-text">Prostor (postojeće)</span>
        </button>
      </div>
      <div hidden={!nails}>
        <NailLook />
        <NailFooter />
      </div>
      <div hidden={nails}>
        <Header />
        <LanguageSuggestion />
        <PlannerHero />
        <Planner />
        <HowItWorks />
        <Footer />
      </div>
    </>
  );
}

/** Quiet footer for the nail experience. Carries the legal links; nothing else. */
function NailFooter() {
  return (
    <footer className="nk-footer">
      <div className="nk-footer-inner">
        <span>Cijene i dostupnost provjeri kod trgovca prije kupnje. Nismo povezani ni s jednim trgovcem.</span>
        <nav aria-label="Pravno">
          <a href="#privacy" onClick={(e) => e.preventDefault()}>Privatnost</a>
          <a href="#terms" onClick={(e) => e.preventDefault()}>Uvjeti</a>
        </nav>
      </div>
    </footer>
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
