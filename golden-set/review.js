// Golden-set review page logic (served by review.py; kept out of the HTML for the strict CSP).
const $ = (id) => document.getElementById(id);
const FIELDS = { category: ['FINANCIAL', 'NON_FINANCIAL'], operator: ['', '>=', '<=', '>', '<', '=='],
  frequency: ['', 'MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'YEARLY'] };
let docs = [], current = null, label = null, quoteOnPage = null, dirty = false;

try { $('reviewer').value = localStorage.getItem('reviewer') || ''; } catch { /* storage blocked */ }
$('reviewer').addEventListener('input', () => { try { localStorage.setItem('reviewer', $('reviewer').value); } catch {} });
window.addEventListener('beforeunload', (e) => { if (dirty) { e.preventDefault(); } });

async function api(path, options = {}) {
  const res = await fetch(path, options);
  const body = await res.json();
  if (!res.ok) { throw new Error(body.error || `HTTP ${res.status}`); }
  return body;
}

function message(text, kind = '') { $('msg').textContent = text; $('msg').className = `msg ${kind}`; }

async function loadDocs() {
  docs = await api('/api/documents');
  const verified = docs.filter((d) => d.status === 'VERIFIED').length;
  $('progress').textContent = `${verified} of ${docs.length} verified`;
  $('docs').replaceChildren(...docs.map((d) => {
    const b = document.createElement('button');
    b.className = d.documentId === current ? 'active' : '';
    b.innerHTML = `<b></b> <span class="status ${d.status}"></span><small></small>`;
    b.querySelector('b').textContent = d.documentId;
    b.querySelector('.status').textContent = d.status.replace('_UNVERIFIED', '').replace('_', ' ').toLowerCase();
    b.querySelector('small').textContent = `${d.covenants} covenants · ${d.pages} pages`;
    b.onclick = () => openDoc(d.documentId);
    return b;
  }));
}

async function openDoc(docId) {
  if (dirty && !confirm('Discard unsaved changes?')) { return; }
  const body = await api(`/api/labels/${docId}`);
  current = docId; label = body.label; quoteOnPage = body.quoteOnPage; dirty = false;
  label.covenants.forEach((c) => { c._checked = label.status === 'VERIFIED'; });
  $('notes').value = label.notes || '';
  showPage(label.covenants[0]?.page || 1);
  render(); loadDocs(); message('');
}

function showPage(page) { $('pdf').src = `/documents/${current}.pdf#page=${page}&view=FitH`; }

function field(c, name, attrs = {}) {
  const el = FIELDS[name] ? document.createElement('select') : document.createElement('input');
  if (FIELDS[name]) {
    FIELDS[name].forEach((v) => el.add(new Option(v || '—', v)));
    el.value = c[name] ?? '';
  } else {
    // Attributes, not properties: some (like `list`) are read-only as properties.
    Object.entries(attrs).forEach(([k, v]) => (k === 'className' ? (el.className = v) : el.setAttribute(k, v)));
    el.value = c[name] ?? '';
  }
  el.addEventListener('input', () => {
    const v = el.value.trim();
    c[name] = el.type === 'number' ? (v === '' ? null : Number(v)) : (v || null);
    c._checked = false; dirty = true; render(false);
  });
  const wrap = document.createElement('label');
  wrap.append(name, el);
  return wrap;
}

function render(full = true) {
  const box = $('covenants');
  if (!full) { box.querySelectorAll('.card').forEach((card, i) => card.classList.toggle('checked', !!label.covenants[i]._checked));
    box.querySelectorAll('input.correct').forEach((cb, i) => { cb.checked = !!label.covenants[i]._checked; }); return; }
  box.replaceChildren(...label.covenants.map((c, i) => {
    const card = document.createElement('div');
    card.className = 'card' + (c._checked ? ' checked' : '');
    const top = document.createElement('div'); top.className = 'row';
    top.append(field(c, 'metric', { list: 'metrics', placeholder: 'DSCR' }), field(c, 'category'));
    const terms = document.createElement('div'); terms.className = 'row';
    terms.append(field(c, 'operator'), field(c, 'threshold', { type: 'number', step: 'any', className: 'num' }),
      field(c, 'unit', { placeholder: 'x, %, INR cr', className: 'num' }), field(c, 'frequency'),
      field(c, 'page', { type: 'number', min: 1, max: label.pages, className: 'page' }));
    const go = document.createElement('button'); go.textContent = 'Show page'; go.onclick = () => showPage(c.page || 1);
    terms.append(go);
    const q = document.createElement('label'); q.style.display = 'block';
    const ta = document.createElement('textarea'); ta.rows = 3; ta.value = c.quote || '';
    ta.addEventListener('input', () => { c.quote = ta.value; c._checked = false; dirty = true; render(false); });
    const state = document.createElement('span');
    const known = quoteOnPage && quoteOnPage[i] !== undefined;
    state.className = 'quote-state ' + (known ? (quoteOnPage[i] ? 'yes' : 'no') : '');
    state.textContent = known ? (quoteOnPage[i] ? ' found on that page' : ' not found on that page: check page and wording') : '';
    q.append('quote (exact words from the agreement)', state, ta);
    const actions = document.createElement('div'); actions.className = 'row';
    const ok = document.createElement('label'); ok.className = 'inline';
    const cb = document.createElement('input'); cb.type = 'checkbox'; cb.className = 'correct'; cb.checked = !!c._checked;
    cb.onchange = () => { c._checked = cb.checked; dirty = true; render(false); };
    ok.append(cb, 'Correct');
    const rm = document.createElement('button'); rm.textContent = 'Remove (not a covenant)';
    rm.onclick = () => { label.covenants.splice(i, 1); quoteOnPage = null; dirty = true; render(); };
    actions.append(ok, rm);
    card.append(top, terms, q, actions);
    return card;
  }));
}

$('add').onclick = () => {
  label.covenants.push({ metric: '', category: 'FINANCIAL', operator: '>=', threshold: null, unit: null,
    frequency: null, page: 1, quote: '', _checked: false });
  quoteOnPage = null; dirty = true; render();
};
$('notes').addEventListener('input', () => { dirty = true; });

async function save(status) {
  if (status === 'VERIFIED' && label.covenants.some((c) => !c._checked)) {
    message('Tick "Correct" on every covenant first (or remove the ones that are not covenants).', 'error'); return;
  }
  const payload = { ...label, status, reviewedBy: $('reviewer').value.trim() || null, notes: $('notes').value,
    covenants: label.covenants.map(({ _checked, ...c }) => c) };
  try {
    const body = await api(`/api/labels/${current}`, { method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-Review': '1' }, body: JSON.stringify(payload) });
    const checked = label.covenants.map((c) => c._checked);
    label = body.label; quoteOnPage = body.quoteOnPage; dirty = false;
    label.covenants.forEach((c, i) => { c._checked = checked[i]; });
    render(); await loadDocs();
    message(status === 'VERIFIED' ? `${current} verified.` : 'Draft saved.', 'ok');
  } catch (e) { message(e.message, 'error'); }
}
$('save').onclick = () => save('IN_REVIEW');
$('verify').onclick = () => save('VERIFIED');

loadDocs().then(() => docs.length && openDoc(docs[0].documentId)).catch((e) => message(e.message, 'error'));
