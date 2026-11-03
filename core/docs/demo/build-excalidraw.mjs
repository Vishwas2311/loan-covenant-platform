// =====================================================================================
// build-excalidraw.mjs — generates covenant-journey.excalidraw (management-demo canvas).
// Run:  "C:\Program Files\nodejs\node.exe" docs/demo/build-excalidraw.mjs
//
// STYLE: a left-to-right USER-JOURNEY RIBBON. Eight numbered stage cards flow across the
// page, joined by arrows. Each card shows the USER ACTION (the hero) and ONE small data
// chip = "table -> tag" (no column dumps, no swimlane grid). Stages 5 & 6 are greyed as
// "automatic (system)". A fork note + governance callout + tag legend sit underneath.
// =====================================================================================
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT = join(__dirname, 'covenant-journey.excalidraw');

let idc = 0, seed = 20260716;
const nextId = (p = 'el') => `${p}_${++idc}`;
const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff);
const NOW = 1752624000000;
const elements = [];

function baseEl(type, x, y, w, h, extra = {}) {
  return {
    id: extra.id || nextId(type), type, x, y, width: w, height: h, angle: 0,
    strokeColor: extra.strokeColor || '#1e1e1e',
    backgroundColor: extra.backgroundColor || 'transparent',
    fillStyle: extra.fillStyle || 'solid',
    strokeWidth: extra.strokeWidth || 1.5,
    strokeStyle: extra.strokeStyle || 'solid',
    roughness: extra.roughness ?? 1, opacity: 100, groupIds: [], frameId: null,
    roundness: extra.roundness ?? null, seed: rnd(), version: 1, versionNonce: rnd(),
    isDeleted: false, boundElements: [], updated: NOW, link: null, locked: false,
    ...(extra.rest || {}),
  };
}
function rect(x, y, w, h, o = {}) {
  elements.push(baseEl('rectangle', x, y, w, h, {
    backgroundColor: o.bg || '#ffffff', strokeColor: o.stroke || '#343a40',
    strokeWidth: o.strokeWidth || 1.5, strokeStyle: o.strokeStyle || 'solid',
    roughness: o.roughness ?? 1, roundness: o.round === false ? null : { type: 3 },
  }));
}
function ellipse(x, y, w, h, o = {}) {
  elements.push(baseEl('ellipse', x, y, w, h, {
    backgroundColor: o.bg || '#ffffff', strokeColor: o.stroke || '#343a40',
    strokeWidth: o.strokeWidth || 1.5, roughness: o.roughness ?? 1, roundness: null,
  }));
}
function text(x, y, str, o = {}) {
  const fontSize = o.fontSize || 14, lineHeight = 1.25;
  const lines = String(str).split('\n');
  const w = o.width || 300, h = Math.ceil(lines.length * fontSize * lineHeight) + 2;
  elements.push(baseEl('text', x, y, w, h, {
    strokeColor: o.color || '#1e1e1e', roughness: 1,
    rest: {
      fontSize, fontFamily: o.fontFamily || 2, text: str, originalText: str,
      textAlign: o.align || 'left', verticalAlign: o.valign || 'top',
      containerId: null, lineHeight, baseline: Math.ceil(fontSize * lineHeight) - 3,
    },
  }));
}
function arrow(x1, y1, pts, o = {}) {
  const e = baseEl('arrow', x1, y1, Math.abs(pts[pts.length - 1][0]) || 1, Math.abs(pts[pts.length - 1][1]) || 1, {
    strokeColor: o.color || '#e8590c', strokeWidth: o.strokeWidth || 3, roughness: 1,
    roundness: { type: 2 },
    rest: {
      points: pts, lastCommittedPoint: null, startBinding: null, endBinding: null,
      startArrowhead: null, endArrowhead: o.head || 'arrow', elbowed: false,
    },
  });
  elements.push(e);
}

// ---- palette ------------------------------------------------------------------------
const TAG = {
  blue: { bg: '#a5d8ff', stroke: '#1971c2' }, green: { bg: '#b2f2bb', stroke: '#2f9e44' },
  red: { bg: '#ffc9c9', stroke: '#e03131' }, amber: { bg: '#ffec99', stroke: '#f08c00' },
  grey: { bg: '#dee2e6', stroke: '#868e96' }, purple: { bg: '#eebefa', stroke: '#9c36b5' },
  teal: { bg: '#96f2d7', stroke: '#0ca678' },
};

// ---- geometry -----------------------------------------------------------------------
const CARD_W = 330, CARD_H = 244, GAP = 44, STRIDE = CARD_W + GAP;
const X0 = 70, CARDS_Y = 168;
const cardX = (i) => X0 + i * STRIDE;
const RIGHT = cardX(7) + CARD_W;
const MIDY = CARDS_Y + CARD_H / 2;

// ---- title --------------------------------------------------------------------------
text(X0, 40, 'Covenant Monitoring — the user journey, step by step', { fontSize: 30, fontFamily: 1, color: '#1864ab' });
text(X0, 92, 'Follow one covenant left to right. Under each step, the one thing that changes in the data model:  table  ->  tag.   Steps 5 & 6 are automatic (the system, not a user).', { fontSize: 15, fontFamily: 2, color: '#495057', width: 1700 });

// ---- stage data ---------------------------------------------------------------------
const CARDS = [
  { t: 'Author covenant', action: 'A banker authors the covenant\nONCE in the library: name,\ncategory, formula, frequency\nand eligibility rules.',
    table: '->  cov_covenant_master', tags: [['is_custom = false', 'grey']] },
  { t: 'Recommend', action: 'Open a deal (CP). Rules match\neligibility; ML also suggests\nthresholds, each with a\nconfidence score & reason.',
    table: '->  cov_covenant_recommendation', tags: [['SUGGESTED', 'blue'], ['ML', 'purple']] },
  { t: 'Review & tag', action: 'Banker accepts or rejects each\nsuggestion, tweaks a threshold,\nor adds a custom covenant\n(tagged MANUAL_ADD).',
    table: '->  recommendation (+ custom master)', tags: [['ACCEPTED', 'green'], ['REJECTED', 'red']] },
  { t: 'Apply to deal', action: 'Apply the chosen covenants to\nthe deal; set frequency, start\ndate & type. "source" records\nwhere the threshold came from.',
    table: '->  cov_applied_covenant', tags: [['source: MASTER', 'grey'], ['USER / ML', 'purple']] },
  { t: 'Schedule', action: 'The system schedules the\nmonitoring AUTOMATICALLY via\nQuartz the moment a covenant\nis applied — no manual setup.',
    table: '->  cov_monitoring_schedule (+QRTZ)', tags: [['SCHEDULED', 'grey']], auto: true },
  { t: 'Evaluate', action: 'At the set frequency the formula\nis evaluated against the deal\'s\ndata:  TRUE = compliant,\nFALSE = breached.',
    table: '->  cov_assessment   (immutable)', tags: [['COMPLIANT', 'green'], ['BREACHED', 'red']], auto: true },
  { t: 'Monitor', action: 'The RM reviews the deal grid:\nactual value, headroom, status\nand timing. This screen is\nread-only — nothing is written.',
    table: '(no writes — status is derived)', tags: [['read-only', 'grey']] },
  { t: 'Resolve breach', action: 'On a breach the RM grants a\nWaiver or Extension with a\nreason. An AI suggestion can\nbe pulled in and applied.',
    table: '->  cov_covenant_disposition', tags: [['WAIVER / EXTENSION', 'amber'], ['AI', 'teal']] },
];

function badges(x, y, list, maxRight) {
  let bx = x;
  for (const [label, key] of list) {
    const c = TAG[key];
    const bw = Math.min(label.length * 7.7 + 18, 300);
    if (bx + bw > maxRight) { bx = x; y += 34; }
    rect(bx, y, bw, 26, { bg: c.bg, stroke: c.stroke, strokeWidth: 1.5, round: true });
    text(bx, y + 5, label, { fontSize: 12, fontFamily: 2, color: '#212529', width: bw, align: 'center' });
    bx += bw + 8;
  }
}

// ---- cards --------------------------------------------------------------------------
CARDS.forEach((c, i) => {
  const x = cardX(i), auto = !!c.auto;
  const border = auto ? '#868e96' : '#1971c2';
  const headBg = auto ? '#adb5bd' : '#4dabf7';
  // card body
  rect(x, CARDS_Y, CARD_W, CARD_H, { bg: '#ffffff', stroke: border, strokeWidth: 2.5 });
  // header strip
  rect(x, CARDS_Y, CARD_W, 46, { bg: headBg, stroke: border, strokeWidth: 1, round: false });
  text(x + 58, CARDS_Y + 12, c.t + (auto ? '   [auto]' : ''), { fontSize: 17, fontFamily: 1, color: '#ffffff', width: CARD_W - 66 });
  // number circle (overlaps header, left)
  ellipse(x + 8, CARDS_Y + 5, 36, 36, { bg: auto ? '#495057' : '#1864ab', stroke: '#ffffff', strokeWidth: 2 });
  text(x + 8, CARDS_Y + 12, String(i + 1), { fontSize: 18, fontFamily: 1, color: '#ffffff', width: 36, align: 'center' });
  // action text (the hero)
  text(x + 16, CARDS_Y + 58, c.action, { fontSize: 13.5, fontFamily: 2, color: '#212529', width: CARD_W - 30 });
  // "writes:" label + table pill
  text(x + 16, CARDS_Y + 150, 'DATA MODEL:', { fontSize: 10.5, fontFamily: 2, color: '#868e96', width: 120 });
  rect(x + 14, CARDS_Y + 166, CARD_W - 28, 28, { bg: '#f1f3f5', stroke: '#adb5bd', strokeWidth: 1.5, round: true });
  text(x + 22, CARDS_Y + 173, c.table, { fontSize: 12.5, fontFamily: 3, color: '#343a40', width: CARD_W - 44 });
  // tag badges
  badges(x + 14, CARDS_Y + 204, c.tags, x + CARD_W - 12);
  // connecting arrow to next card
  if (i < 7) {
    arrow(x + CARD_W + 4, MIDY, [[0, 0], [GAP - 8, 0]], { color: '#e8590c', strokeWidth: 3 });
  }
});
// label on the 6 -> 7 arrow (the breach path)
text(cardX(5) + CARD_W - 2, MIDY - 30, 'if BREACHED', { fontSize: 12, fontFamily: 2, color: '#e03131', width: 90 });

// ---- fork note (after step 6) -------------------------------------------------------
const FORK_Y = CARDS_Y + CARD_H + 26;
rect(cardX(4), FORK_Y, CARD_W * 2 + GAP, 74, { bg: '#f8f9fa', stroke: '#adb5bd', strokeWidth: 2, strokeStyle: 'dashed' });
text(cardX(4) + 16, FORK_Y + 10, 'After step 6, the deal forks:', { fontSize: 13, fontFamily: 1, color: '#212529', width: 400 });
text(cardX(4) + 16, FORK_Y + 32, 'COMPLIANT / NA  ->  "Complied" (locked, read-only, no action)', { fontSize: 12.5, fontFamily: 2, color: '#2f9e44', width: 560 });
text(cardX(4) + 16, FORK_Y + 50, 'BREACHED  ->  continue to steps 7 & 8 (monitor, then resolve)', { fontSize: 12.5, fontFamily: 2, color: '#e03131', width: 560 });

// ---- governance callout -------------------------------------------------------------
const CALL_Y = FORK_Y + 100;
rect(X0, CALL_Y, RIGHT - X0, 92, { bg: '#fff9db', stroke: '#f08c00', strokeWidth: 2.5 });
text(X0 + 16, CALL_Y + 12, 'WHY LEADERSHIP CAN TRUST IT', { fontSize: 16, fontFamily: 1, color: '#e8590c', width: 500 });
text(X0 + 16, CALL_Y + 40,
  '1) Every threshold is TAGGED with its origin — library (MASTER), a person (USER) or the model (ML).      ' +
  '2) Assessments are IMMUTABLE: a re-run adds a new row, it never overwrites (cov_threshold_audit & cov_covenant_status_audit are append-only).      ' +
  '3) A breach is never erased — a Waiver/Extension is layered on top, so who-waived-what-when is always on record.',
  { fontSize: 13, fontFamily: 2, color: '#5c3a00', width: RIGHT - X0 - 32 });

// ---- legend -------------------------------------------------------------------------
const LEG_Y = CALL_Y + 116;
text(X0, LEG_Y, 'Tags:', { fontSize: 14, fontFamily: 1, color: '#212529', width: 70 });
let lx = X0 + 62;
for (const [label, key] of [['SUGGESTED', 'blue'], ['COMPLIANT / COMPLIED', 'green'], ['BREACHED', 'red'],
  ['WAIVER / EXTENSION', 'amber'], ['MASTER (library)', 'grey'], ['ML', 'purple'], ['AI', 'teal']]) {
  const c = TAG[key], bw = label.length * 7.7 + 18;
  rect(lx, LEG_Y - 4, bw, 26, { bg: c.bg, stroke: c.stroke, strokeWidth: 1.5, round: true });
  text(lx, LEG_Y + 1, label, { fontSize: 12, fontFamily: 2, color: '#212529', width: bw, align: 'center' });
  lx += bw + 12;
}

// ---- write --------------------------------------------------------------------------
writeFileSync(OUT, JSON.stringify({
  type: 'excalidraw', version: 2, source: 'https://excalidraw.com',
  elements, appState: { gridSize: null, viewBackgroundColor: '#ffffff' }, files: {},
}, null, 2), 'utf8');
console.log(`wrote ${OUT}`);
console.log(`elements: ${elements.length}`);
console.log('by type:', JSON.stringify(elements.reduce((m, e) => ((m[e.type] = (m[e.type] || 0) + 1), m), {})));
