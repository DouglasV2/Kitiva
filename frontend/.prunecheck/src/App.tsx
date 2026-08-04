import { useState } from 'react';
import './nailkit.css';
import './makeupkit.css';
import { NailLook, NailTrustBar } from './components/NailLook';
import { MakeupLook } from './components/MakeupLook';
import { ConsentBanner } from './components/ConsentBanner';
import { ConsentProvider } from './ConsentContext';

type Experience = 'nails' | 'makeup';

function ExperienceSwitch() {
  const [experience, setExperience] = useState<Experience>('nails');
  const tab = (key: Experience, label: string, testid: string) => (
    <button type="button" className={`nk-tab${experience === key ? ' is-on' : ''}`}
      aria-pressed={experience === key} onClick={() => setExperience(key)} data-testid={testid}>
      {label}
    </button>
  );
  return (
    <>
      <div className="nk-topbar" role="group" aria-label="Odaberi iskustvo">
        <span className="nk-wordmark">{experience === 'makeup' ? 'šminka' : 'nokti'}<span>.</span></span>
        {tab('nails', 'Nail Look / Nail Kit', 'tab-nails')}
        {tab('makeup', 'Makeup Look / Makeup Kit', 'tab-makeup')}
        <span className="nk-locale">Hrvatska · EUR</span>
      </div>
      <div className="nk-pane" hidden={experience !== 'nails'}><NailLook /><BeautyFooter /></div>
      <div className="nk-pane" hidden={experience !== 'makeup'}><MakeupLook /><BeautyFooter /></div>
    </>
  );
}

function BeautyFooter() {
  return (
    <footer>
      <NailTrustBar />
      <div className="nk-legal"><div className="nk-legal-inner">
        <span>Cijene i dostupnost provjeri kod trgovca prije kupnje.</span>
      </div></div>
    </footer>
  );
}

export default function App() {
  return (
    <ConsentProvider>
      <main><ExperienceSwitch /><ConsentBanner /></main>
    </ConsentProvider>
  );
}
