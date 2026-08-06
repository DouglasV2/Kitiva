import { lazy, Suspense, useState } from 'react';
import './nailkit.css';
import './makeupkit.css';
import { ConsentBanner } from './components/ConsentBanner';
import { ConsentProvider, useConsent } from './ConsentContext';
import { NailLook, NailTrustBar } from './components/NailLook';
import { MakeupLook } from './components/MakeupLook';
import type { LegalKey } from './legal';

const LegalModal = lazy(() => import('./components/LegalModal').then((m) => ({ default: m.LegalModal })));

/**
 * Kitiva — two beauty verticals over one shell.
 *
 * <p>Nail Look and Makeup Look share an identity, a catalog discipline and a set of components; they are two
 * tabs rather than two apps because a user who wants a manicure and a user who wants a foundation are the
 * same person on different days. Both panes stay mounted, so switching never discards a parsed brief, a
 * generated kit or a set of catalog filters someone spent a minute assembling.</p>
 *
 * <p>No accounts, no saved state, no router. Nothing here is worth signing in for yet, and a sign-in wall in
 * front of a pilot link is how you find out nobody wanted to sign in.</p>
 */

type Experience = 'nails' | 'makeup';

function AppShell() {
  const [experience, setExperience] = useState<Experience>('nails');
  const [legal, setLegal] = useState<LegalKey | null>(null);

  const tab = (key: Experience, label: string, testid: string) => (
    <button
      type="button"
      className={`nk-tab${experience === key ? ' is-on' : ''}`}
      aria-pressed={experience === key}
      onClick={() => setExperience(key)}
      data-testid={testid}
    >
      <span className="scope-text">{label}</span>
    </button>
  );

  return (
    <main>
      <div className="nk-topbar" role="group" aria-label="Odaberi iskustvo">
        <span className="nk-wordmark">{experience === 'makeup' ? 'šminka' : 'nokti'}<span>.</span></span>
        {tab('nails', 'Nail Look / Nail Kit', 'tab-nails')}
        {tab('makeup', 'Makeup Look / Makeup Kit', 'tab-makeup')}
        {/* Which catalog answers, and in which currency. Both pilots are Croatian retail only. */}
        <span className="nk-locale">Hrvatska · EUR</span>
      </div>

      <div className="nk-pane" hidden={experience !== 'nails'}>
        <NailLook />
        <BeautyFooter onLegal={setLegal} />
      </div>
      <div className="nk-pane" hidden={experience !== 'makeup'}>
        <MakeupLook />
        <BeautyFooter onLegal={setLegal} />
      </div>

      {/* Non-modal, only when a GA id is configured and no decision exists yet. Never blocks the app. */}
      <ConsentBanner />
      {legal && (
        <Suspense fallback={null}>
          <LegalModal docKey={legal} onClose={() => setLegal(null)} />
        </Suspense>
      )}
    </main>
  );
}

/**
 * The footer for both verticals: the four things this product can stand behind, then the legal line.
 *
 * <p><strong>The privacy settings link is load-bearing, not decoration.</strong> The furniture footer used
 * to be the only thing in the app that could reopen the consent banner. Dropping it while keeping the
 * consent provider would have left the app able to load Google Analytics with no way to withdraw — and
 * withdrawal has to be as easy as granting. The legal links open the real documents rather than the
 * preventDefault stubs that stood here before.</p>
 */
function BeautyFooter({ onLegal }: { onLegal: (key: LegalKey) => void }) {
  const { configured, openSettings } = useConsent();
  return (
    <footer>
      <NailTrustBar />
      <div className="nk-legal">
        <div className="nk-legal-inner">
          <span>Cijene i dostupnost provjeri kod trgovca prije kupnje. Nismo povezani ni s jednim trgovcem.</span>
          <nav aria-label="Pravno">
            <button type="button" onClick={() => onLegal('privacy')}>Privatnost</button>
            <button type="button" onClick={() => onLegal('terms')}>Uvjeti</button>
            {configured && (
              <button type="button" onClick={openSettings} data-testid="privacy-settings">
                Postavke privatnosti
              </button>
            )}
          </nav>
        </div>
      </div>
    </footer>
  );
}

export default function App() {
  return (
    <ConsentProvider>
      <AppShell />
    </ConsentProvider>
  );
}
