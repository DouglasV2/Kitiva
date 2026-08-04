// The instrument that steers nail catalog work: which real looks can the app complete HONESTLY, and for
// the ones it cannot, exactly which capability is missing.
//
// WHY THIS EXISTS
// "Add more products" is the wrong goal and an easy one to hit. 500 polishes that never combine into a
// burgundy cat-eye is worse than 45 that do, because it looks like progress. This measures the only thing
// that matters — target look in, honest Complete or a named gap out — and it does so by driving the REAL
// endpoints, not by re-implementing the assembler. If the matrix says Complete, the product says Complete.
//
// Run (backend must be up):  node scripts/nail-coverage-matrix.mjs
//                            node scripts/nail-coverage-matrix.mjs --json   # machine-readable
//
// Exit code is 0 always: this is a measurement, not a gate. The gate is NailCoverageTest on the backend.

const API = process.env.NAIL_API ?? 'http://localhost:8080';
const asJson = process.argv.includes('--json');

/**
 * The target looks. Chosen to be things a Croatian user actually types, weighted toward the combinations
 * the owner named first (burgundy, cat-eye, gold), and deliberately including a few we EXPECT to fail so
 * the matrix cannot quietly become a list of things we already support.
 *
 * `systems` says which systems the look is even askable in. Shape is a press-on-only requirement — with
 * polish you file your own nail — so "almond kratki" is not applicable to a polish kit and is marked so
 * rather than counted as a failure.
 */
const LOOKS = [
  // --- the owner's first priority -------------------------------------------------------------------
  { id: 'burgundy-cateye-gold', need: 'Burgundy cat-eye + zlatni detalj',
    prompt: 'Želim kratke almond burgundy cat-eye nokte s dva diskretna zlatna detalja.' },
  { id: 'burgundy-cateye', need: 'Burgundy cat-eye',
    prompt: 'Želim kratke burgundy nokte s cat-eye efektom.' },
  { id: 'burgundy-plain', need: 'Burgundy jednobojni',
    prompt: 'Želim kratke burgundy nokte, sjajne.' },
  { id: 'gold-accent', need: 'Zlatni detalj na jednobojnom',
    prompt: 'Želim kratke nude nokte sa zlatnim detaljem na prstenjaku.' },
  { id: 'cateye-any', need: 'Cat-eye bilo koje boje',
    prompt: 'Želim kratke nokte s cat-eye efektom.' },

  // --- everyday colour + finish ---------------------------------------------------------------------
  { id: 'nude-glossy', need: 'Sjajni nude', prompt: 'Želim kratke nude nokte, sjajne.' },
  { id: 'black-matte', need: 'Mat crni', prompt: 'Želim kratke crne nokte, mat.' },
  { id: 'red-glossy', need: 'Sjajni crveni', prompt: 'Želim kratke crvene nokte, sjajne.' },
  { id: 'pink-glossy', need: 'Sjajni roza', prompt: 'Želim kratke roza nokte, sjajne.' },
  { id: 'white-glossy', need: 'Sjajni bijeli', prompt: 'Želim kratke bijele nokte, sjajne.' },
  { id: 'brown-glossy', need: 'Smeđi / mocha', prompt: 'Želim kratke smeđe nokte, sjajne.' },
  { id: 'nude-matte', need: 'Mat nude', prompt: 'Želim kratke nude nokte, mat.' },
  { id: 'red-matte', need: 'Mat crveni', prompt: 'Želim kratke crvene nokte, mat.' },

  // --- effects --------------------------------------------------------------------------------------
  { id: 'french', need: 'French', prompt: 'Želim kratke nokte s francuskim vrhom.' },
  { id: 'chrome', need: 'Chrome', prompt: 'Želim kratke nokte s chrome efektom.' },
  { id: 'glitter', need: 'Glitter detalj', prompt: 'Želim kratke nokte s glitterom na prstenjaku.' },
  { id: 'french-nude', need: 'Nude french', prompt: 'Želim kratke nude nokte s francuskim vrhom.' },

  // --- shape + length (press-on only for shape) -----------------------------------------------------
  { id: 'almond-short', need: 'Almond kratki', prompt: 'Želim kratke almond nokte, sjajne.' },
  { id: 'almond-medium', need: 'Almond srednji', prompt: 'Želim srednje duge almond nokte, sjajne.' },
  { id: 'square-short', need: 'Četvrtasti kratki', prompt: 'Želim kratke četvrtaste nokte, sjajne.' },
  { id: 'coffin-long', need: 'Coffin dugi', prompt: 'Želim duge coffin nokte, sjajne.' },
  { id: 'oval-short', need: 'Ovalni kratki', prompt: 'Želim kratke ovalne nokte, sjajne.' },

  // --- combinations that stress compatibility -------------------------------------------------------
  { id: 'burgundy-almond-medium', need: 'Burgundy almond srednji',
    prompt: 'Želim srednje duge almond nokte u burgundy boji.' },
  { id: 'black-almond-glossy', need: 'Crni almond sjajni',
    prompt: 'Želim kratke almond nokte u crnoj boji, sjajne.' },
  { id: 'red-square', need: 'Crveni četvrtasti', prompt: 'Želim kratke četvrtaste crvene nokte.' },
  { id: 'nude-cateye', need: 'Nude cat-eye', prompt: 'Želim kratke nude nokte s cat-eye efektom.' },
  { id: 'gold-glitter', need: 'Zlatni glitter', prompt: 'Želim kratke nokte sa zlatnim glitterom.' },
  { id: 'white-french-square', need: 'Bijeli french četvrtasti',
    prompt: 'Želim kratke četvrtaste nokte s bijelim francuskim vrhom.' },
];

const SYSTEMS = ['regular-polish', 'press-on'];

const post = async (path, body) => {
  const res = await fetch(API + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${path} -> HTTP ${res.status}`);
  return res.json();
};

/** COMPLETE and COMPLETE_WITH_ASSUMPTIONS both count: a stated assumption is honest, a silent gap is not. */
const isComplete = (status) => status === 'COMPLETE' || status === 'COMPLETE_WITH_ASSUMPTIONS';

async function measure(look, system) {
  const parsed = await post('/api/nail/parse', { prompt: look.prompt, budgetCents: 0 });
  const shapeStated = parsed.brief.assumptions.every((a) => a.field !== 'shape');
  const res = await post('/api/nail/generate', {
    brief: parsed.brief, executionMode: 'AT_HOME', system,
    pinnedBySlot: {}, preferCheapest: false, singleRetailer: null,
  });
  const kit = res.kit;
  return {
    status: kit.status,
    complete: isComplete(kit.status),
    blocked: kit.status === 'SAFETY_BLOCKED',
    missing: kit.missingRequiredSlots,
    totalCents: kit.totalCents,
    itemCount: kit.items.length,
    shapeStated,
  };
}

const cell = (r) => (r.blocked ? '⛔ blocked' : r.complete ? '✅' : '❌');

const results = [];
for (const look of LOOKS) {
  const row = { id: look.id, need: look.need, prompt: look.prompt, systems: {} };
  for (const system of SYSTEMS) row.systems[system] = await measure(look, system);
  results.push(row);
}

if (asJson) {
  console.log(JSON.stringify({ api: API, measuredAt: new Date().toISOString(), results }, null, 2));
} else {
  const pad = (s, n) => String(s).padEnd(n);
  console.log(`\nNAIL COVERAGE MATRIX   (${API})\n`);
  console.log(`${pad('Potreba', 32)} ${pad('Klasični lak', 14)} ${pad('Press-on', 12)}`);
  console.log('-'.repeat(62));
  for (const r of results) {
    console.log(`${pad(r.need, 32)} ${pad(cell(r.systems['regular-polish']), 14)} ${pad(cell(r.systems['press-on']), 12)}`);
  }

  const cells = results.flatMap((r) => SYSTEMS.map((s) => r.systems[s]));
  const done = cells.filter((c) => c.complete).length;
  console.log('-'.repeat(62));
  console.log(`\n${done}/${cells.length} cells honestly Complete  (${LOOKS.length} looks x ${SYSTEMS.length} systems)\n`);

  // What is actually blocking the rest, ranked. This is the shopping list for catalog work.
  const gaps = new Map();
  for (const r of results) {
    for (const s of SYSTEMS) {
      for (const m of r.systems[s].missing) {
        const key = m.replace(/\s*\(.*\)$/, '');
        if (!gaps.has(key)) gaps.set(key, { count: 0, detail: new Set() });
        gaps.get(key).count++;
        const why = m.match(/\((.*)\)$/);
        if (why) gaps.get(key).detail.add(why[1]);
      }
    }
  }
  console.log('BLOCKING CAPABILITIES, most cells first:\n');
  for (const [key, v] of [...gaps.entries()].sort((a, b) => b[1].count - a[1].count)) {
    console.log(`  ${String(v.count).padStart(3)}x  ${key}`);
    for (const d of v.detail) console.log(`        - ${d}`);
  }
  console.log('');
}
