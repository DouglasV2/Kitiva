/**
 * A scratch gallery for eyeballing HandPreview across shapes, lengths, finishes and effects at once.
 * Mounted only from the dev harness route; not part of the app.
 */
import { HandPreview, type NailDesign } from './HandPreview';

const CASES: Array<[string, NailDesign, boolean?]> = [
  ['default: kratki almond burgundy + cat-eye', {}],
  ['isto, zrcaljeno (lijeva ruka)', {}, true],
  ['almond srednji, mat', { length: 'medium', finish: 'matte', effects: [] }],
  ['coffin dugi, crvena, sjajna', { shape: 'coffin', length: 'long', color: '#C0182F', effects: [] }],
  ['stiletto extra dugi, crna, chrome', { shape: 'stiletto', length: 'extra-long', color: '#1B1418', effects: ['chrome'] }],
  ['square kratki, nude, french', { shape: 'square', length: 'short', color: '#E4C4B0', effects: ['french'] }],
  ['oval srednji, roza, shimmer', { shape: 'oval', length: 'medium', color: '#D98BA6', finish: 'shimmer', effects: [] }],
  ['squoval, bijela, glitter', { shape: 'squoval', length: 'medium', color: '#F2EDE9', effects: ['glitter'] }],
  ['round kratki, smeđa', { shape: 'round', color: '#8A5A46', effects: [] }],
];

export function HandPreviewGallery() {
  return (
    <div style={{ background: '#fdfafb', padding: 24, fontFamily: 'system-ui, sans-serif' }}>
      <h1 style={{ fontFamily: 'Georgia, serif', fontWeight: 400 }}>HandPreview</h1>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 20 }}>
        {CASES.map(([label, design, mirrored], i) => (
          <figure key={label} style={{ margin: 0, width: 210 }}>
            <HandPreview
              design={{ effects: ['cat-eye'], ...design }}
              mirrored={mirrored}
              idPrefix={`c${i}-`}
              className="gallery-hand"
            />
            <figcaption style={{ fontSize: 12, color: '#6b5560', marginTop: 6 }}>{label}</figcaption>
          </figure>
        ))}

        <figure style={{ margin: 0, width: 210 }}>
          <HandPreview
            idPrefix="pn-"
            design={{ color: '#E4C4B0', effects: [] }}
            perNail={{
              ring: { color: '#5C0A22', effects: ['cat-eye'], accent: true, accentColor: '#B08D3F' },
              middle: { length: 'long', shape: 'stiletto', color: '#1B1418' },
            }}
          />
          <figcaption style={{ fontSize: 12, color: '#6b5560', marginTop: 6 }}>
            per-nail override: prstenjak i srednjak
          </figcaption>
        </figure>
      </div>

      <style>{'.gallery-hand{width:100%;height:auto;display:block}'}</style>
    </div>
  );
}
