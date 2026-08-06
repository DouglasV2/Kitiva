// Sprint 10.72 — renders a legal document (privacy / terms / impressum) in a modal overlay. Content lives in
// legal.ts (HR + EN + DE; English fallback for other locales). The app has no router, so legal pages are modals
// opened from the footer.
import { useEffect } from 'react';
import { legalDoc, type LegalKey } from '../legal';

// Croatian, fixed. This app ships one market and every other string in it is hardcoded Croatian too, so
// routing this one through the locale context would drag the whole i18n + markets chain back in for two
// words. legal.ts still holds HR/EN/DE, so a second market only needs this constant made a prop.
const LANG = 'hr';

export function LegalModal({ docKey, onClose }: { docKey: LegalKey | null; onClose: () => void }) {

  useEffect(() => {
    if (!docKey) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [docKey, onClose]);

  if (!docKey) return null;
  const doc = legalDoc(LANG, docKey);

  return (
    <div className="legal-overlay" role="dialog" aria-modal="true" aria-label={doc.title} onClick={onClose}>
      <div className="legal-modal" onClick={(event) => event.stopPropagation()}>
        <div className="legal-modal-head">
          <h2>{doc.title}</h2>
          <button type="button" className="legal-close" aria-label="Zatvori" onClick={onClose}>×</button>
        </div>
        <p className="legal-updated">{doc.updated}</p>
        {doc.disclaimer && <p className="legal-disclaimer">{doc.disclaimer}</p>}
        <div className="legal-body">
          {doc.sections.map((section, i) => (
            <section key={i}>
              <h3>{section.heading}</h3>
              {section.body.map((paragraph, j) => <p key={j}>{paragraph}</p>)}
            </section>
          ))}
        </div>
      </div>
    </div>
  );
}
