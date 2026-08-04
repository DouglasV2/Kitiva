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
import './makeupkit.css';
import { NailLook, NailTrustBar } from './components/NailLook';
import { MakeupLook } from './components/MakeupLook';
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
      <ExperienceSwitch showGate={showGate} />
      {/* Sprint 10.185: analytics-consent banner. Non-modal; only appears when a GA id is configured and no
          valid decision exists (or the user reopened it from the footer). Never blocks the app. */}
      <ConsentBanner />
    </main>
  );
}

type Experience = 'nails' | 'makeup' | 'furniture';

function ExperienceSwitch({ showGate }: { showGate: boolean }) {
  const [experience, setExperience] = useState<Experience>('nails');
  // The two beauty verticals share one identity and one chrome; the furniture planner has its own. So the
  // top bar switches shape once, at the beauty/furniture boundary, rather than once per tab.
  const beauty = experience === 'nails' || experience === 'makeup';
  const tab = (key: Experience, label: string, testid: string) => (
    <button
      type="button"
      className={beauty
        ? `nk-tab${experience === key ? ' is-on' : ''}`
        : `scope-option${experience === key ? ' active' : ''}`}
      aria-pressed={experience === key}
      onClick={() => setExperience(key)}
      data-testid={testid}
    >
      <span className="scope-text">{label}</span>
    </button>
  );

  return (
    <>
      <div className={beauty ? 'nk-topbar' : 'scope-toggle navtrack scope-switch shell'} role="group" aria-label="Odaberi iskustvo">
        {beauty && <span className="nk-wordmark">{experience === 'makeup' ? 'šminka' : 'nokti'}<span>.</span></span>}
        {tab('nails', 'Nail Look / Nail Kit', 'tab-nails')}
        {tab('makeup', 'Makeup Look / Makeup Kit', 'tab-makeup')}
        {tab('furniture', 'Prostor (postojeće)', 'tab-furniture')}
        {/* Which catalog answers, and in which currency. Both beauty pilots are Croatian retail only. */}
        {beauty && <span className="nk-locale">Hrvatska · EUR</span>}
      </div>
      {/* Every pane stays mounted so switching never discards a parsed brief, a generated kit or a set of
          catalog filters someone spent a minute assembling. */}
      <div className="nk-pane" hidden={experience !== 'nails'}>
        <NailLook />
        <NailFooter />
      </div>
      <div className="nk-pane" hidden={experience !== 'makeup'}>
        <MakeupLook />
        <NailFooter />
      </div>
      <div hidden={beauty}>
        {/* The sign-in gate belongs to the furniture planner, which saves plans to an account. The beauty
            pilots have no accounts and no saved state, so gating them would put another product's brand and
            another product's sign-in wall in front of a stranger opening a pilot link. Auth itself is
            untouched: same session, same guest flag, same gate the moment you switch to Prostor. */}
        {showGate && <AuthGate />}
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

/**
 * Footer for the nail experience: the four things this product can stand behind, then the legal line. The
 * trust row states only what is true — the catalog is real retailer rows, the prices come from a feed and
 * are not hand-checked, and the assumptions are always on screen.
 */
function NailFooter() {
  return (
    <footer>
      <NailTrustBar />
      <div className="nk-legal">
        <div className="nk-legal-inner">
          <span>Cijene i dostupnost provjeri kod trgovca prije kupnje. Nismo povezani ni s jednim trgovcem.</span>
          <nav aria-label="Pravno">
            <a href="#privacy" onClick={(e) => e.preventDefault()}>Privatnost</a>
            <a href="#terms" onClick={(e) => e.preventDefault()}>Uvjeti</a>
          </nav>
        </div>
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
