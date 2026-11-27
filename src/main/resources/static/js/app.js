(function () {
  'use strict';

  const $ = id => document.getElementById(id);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c =>
    ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  };
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const SESSION_EXPIRED = 'Your session may have expired. Reload the page and sign in again.';

  const SUGGESTIONS = [
    'When may personal information be transferred outside South Africa?',
    'What security measures must a responsible party take?',
    'When must a data breach be reported?',
    'What are the rules for direct marketing?'
  ];

  let acts = [];
  let current = null;
  let pendingSection = null;
  const cache = {};
  const sectionText = new Map(); // section number -> full text, rendered only when a section is opened

  /* ---------- API ---------- */

  async function api(path, options) {
    const res = await fetch(path, options);
    // A signed-out request is redirected to the login page, which is HTML, not JSON.
    if (res.redirected && new URL(res.url).pathname === '/login') {
      const err = new Error(SESSION_EXPIRED);
      err.status = 401;
      throw err;
    }
    let body = null;
    try { body = await res.json(); } catch (e) { /* not JSON */ }
    if (!res.ok) {
      const err = new Error((body && body.detail) ||
        (res.status === 401 || res.status === 403 ? SESSION_EXPIRED : 'Request failed (' + res.status + ')'));
      err.status = res.status;
      throw err;
    }
    if (body === null) throw new Error(SESSION_EXPIRED);
    return body;
  }

  /* ---------- library ---------- */

  async function loadActs() {
    try {
      acts = await api('/api/acts');
      renderActs();
    } catch (e) {
      $('act-list').innerHTML = '<p class="empty">Could not load the Acts: ' + esc(e.message) + '</p>';
    }
  }

  function renderActs() {
    const box = $('act-list');
    if (!acts.length) {
      box.innerHTML = '<p class="empty">No Acts are loaded yet. An administrator can add one with the ingest endpoint.</p>';
      return;
    }
    // Links, not buttons: headings are allowed inside <a>, it works without JavaScript listeners,
    // and each Act can be opened in a new tab or bookmarked.
    box.innerHTML = acts.map(a => `
      <a class="act" href="#${encodeURIComponent(a.act)}">
        <span class="act-spine" aria-hidden="true">${esc(a.act)}</span>
        <span class="act-body">
          <h3>${esc(a.name)}</h3>
          <p>${esc(a.summary)}</p>
          <span class="act-facts">${esc(a.sections)} sections &middot; ${esc(a.versionLabel)}</span>
        </span>
      </a>`).join('');
  }

  /* ---------- routing ---------- */

  function route() {
    const key = decodeURIComponent(location.hash.slice(1));
    if (key) showAct(key); else showHome();
  }

  function showHome() {
    current = null;
    $('act-view').hidden = true;
    $('home').hidden = false;
    document.title = 'Comply ZA';
    window.scrollTo(0, 0);
  }

  async function showAct(key) {
    $('home').hidden = true;
    $('act-view').hidden = false;
    if (current && current.act === key.toUpperCase()) {
      if (pendingSection) { const sec = pendingSection; pendingSection = null; focusSection(sec); }
      return;
    }
    $('act-title').textContent = 'Loading…';
    $('act-summary').textContent = '';
    $('act-meta').textContent = '';
    $('sections').innerHTML = '';
    try {
      if (!cache[key]) cache[key] = await api('/api/acts/' + encodeURIComponent(key));
      current = cache[key];
      renderAct(current);
      if (pendingSection) {
        const sec = pendingSection;
        pendingSection = null;
        focusSection(sec);
      } else {
        window.scrollTo(0, 0);
      }
    } catch (e) {
      current = null;
      $('act-title').textContent = 'This Act could not be opened';
      $('act-summary').textContent = e.status === 404
        ? 'It is not loaded. Go back to the list of Acts.'
        : e.message;
      $('act-abbr').textContent = '';
      $('act-meta').textContent = '';
    }
  }

  /* ---------- reading view ---------- */

  function fillText(d) {
    const box = d.querySelector('.section-text');
    if (box && !box.dataset.filled) {
      box.textContent = sectionText.get(d.dataset.section) || '';
      box.dataset.filled = '1';
    }
  }

  function renderAct(data) {
    document.title = data.name + ' | Comply ZA';
    $('act-abbr').textContent = data.act;
    $('act-title').textContent = data.name;
    $('act-summary').textContent = data.summary;
    $('act-meta').textContent = data.sectionCount + ' sections · ' + data.versionLabel;
    $('section-search').value = '';

    const list = $('sections');
    list.classList.remove('filtering');
    sectionText.clear();
    const frag = document.createDocumentFragment(); // one DOM insert instead of hundreds
    let lastChapter = null;

    data.sections.forEach(s => {
      const chapter = s.chapter || '';
      if (chapter !== lastChapter) {
        lastChapter = chapter;
        if (chapter) {
          const title = s.chapterTitle ? ' – ' + s.chapterTitle : '';
          frag.appendChild(el('h3', 'chapter', 'Chapter ' + chapter + title));
        }
      }
      sectionText.set(s.section, s.text);
      const d = document.createElement('details');
      d.className = 'section';
      d.id = 'sec-' + s.section;
      d.dataset.section = s.section;
      d.dataset.search = (s.section + ' ' + s.heading + ' ' + s.text).toLowerCase();

      const sum = document.createElement('summary');
      sum.appendChild(el('span', 'sec-num', s.section));
      sum.appendChild(el('span', 'sec-title', s.heading || ('Section ' + s.section)));
      d.appendChild(sum);
      d.appendChild(el('div', 'section-text')); // text added on first open
      frag.appendChild(d);
    });
    list.replaceChildren(frag);
    applyFilter();
  }

  function applyFilter() {
    const q = $('section-search').value.trim().toLowerCase();
    const list = $('sections');
    const items = list.querySelectorAll('details.section');
    let shown = 0;
    list.classList.toggle('filtering', q !== '');
    items.forEach(d => {
      const match = q === '' || d.dataset.search.includes(q);
      d.hidden = !match;
      if (match) shown++;
    });
    if (q !== '') {
      items.forEach(d => { d.open = !d.hidden && shown <= 12; });
    }
    $('section-count').textContent = q === '' ? items.length + ' sections' : shown + ' of ' + items.length;
    $('no-match').hidden = shown !== 0;
  }

  let filterTimer = 0;
  function scheduleFilter() {
    clearTimeout(filterTimer);
    filterTimer = setTimeout(applyFilter, 120); // wait for a pause in typing
  }

  function focusSection(sec) {
    const d = $('sec-' + sec);
    if (!d) return;
    if ($('section-search').value !== '') {
      $('section-search').value = '';
      applyFilter();
    }
    d.open = true;
    fillText(d);
    d.scrollIntoView({behavior: reduceMotion ? 'auto' : 'smooth', block: 'start'});
    d.classList.remove('flash');
    void d.offsetWidth;
    d.classList.add('flash');
  }

  function openSection(act, sec) {
    if (current && current.act === act) {
      focusSection(sec);
    } else {
      pendingSection = sec;
      location.hash = encodeURIComponent(act);
    }
  }

  /* ---------- assistant ---------- */

  let busy = false;
  let greeted = false;

  function setChat(open) {
    $('chat').hidden = !open;
    $('shell').classList.toggle('chat-open', open);
    $('chat-toggle').setAttribute('aria-expanded', String(open));
    if (open) {
      if (!greeted) {
        greeted = true;
        addBot(textNode('Ask a question about any Act on this site. Each answer is checked against the Act’s own words, and every quote links to its section.'));
      }
      $('chat-input').focus();
    }
  }

  function textNode(text) {
    const wrap = el('div');
    wrap.textContent = text;
    return wrap;
  }

  function scrollLog() {
    const log = $('chat-log');
    log.scrollTop = log.scrollHeight;
  }

  function addUser(text) {
    $('chat-log').appendChild(el('div', 'msg user', text));
    scrollLog();
  }

  function addBot(node, cls) {
    const m = el('div', cls || 'msg bot');
    m.appendChild(node);
    $('chat-log').appendChild(m);
    scrollLog();
    return m;
  }

  function addTyping() {
    const t = el('span', 'typing');
    t.innerHTML = '<i></i><i></i><i></i>';
    t.setAttribute('role', 'status');
    t.setAttribute('aria-label', 'The assistant is working on an answer');
    return addBot(t);
  }

  function renderAnswer(r) {
    const wrap = el('div');
    const level = String(r.confidence || '').toLowerCase();
    wrap.appendChild(el('span', 'badge ' + level, level.replaceAll('_', ' ')));
    wrap.appendChild(el('p', 'answer-text', r.answer));

    const bySection = new Map();
    (r.citations || []).forEach(c => {
      const key = c.act + '|' + c.section;
      if (!bySection.has(key)) bySection.set(key, {act: c.act, section: c.section, quotes: []});
      bySection.get(key).quotes.push(c.quote);
    });

    if (bySection.size) {
      const chips = el('div', 'cites');
      bySection.forEach(v => {
        const b = el('button', 'cite-chip', v.act + ' section ' + v.section);
        b.type = 'button';
        b.addEventListener('click', () => openSection(v.act, v.section));
        chips.appendChild(b);
      });
      wrap.appendChild(chips);

      const q = document.createElement('details');
      q.className = 'quotes';
      q.appendChild(el('summary', null, 'Show the quoted text'));
      bySection.forEach(v => v.quotes.forEach(text => q.appendChild(el('blockquote', null, text))));
      wrap.appendChild(q);
    }
    if (r.rejectedCitations) {
      wrap.appendChild(el('p', 'note', r.rejectedCitations + ' citation(s) were removed because the quote did not match the source text.'));
    }
    if (Object.keys(r.redactions || {}).length) {
      wrap.appendChild(el('p', 'note', 'Personal information was removed from your question before it was processed.'));
    }
    return wrap;
  }

  async function ask(question) {
    const text = question.trim();
    if (!text || busy) return;
    busy = true;
    $('chat-send').disabled = true;
    $('chips').hidden = true;
    addUser(text);
    $('chat-input').value = '';
    const typing = addTyping();
    try {
      const r = await api('/api/ask', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({question: text})
      });
      typing.remove();
      addBot(renderAnswer(r));
    } catch (e) {
      typing.remove();
      addBot(textNode(e.message), 'msg error');
    } finally {
      busy = false;
      $('chat-send').disabled = false;
      $('chat-input').focus();
    }
  }

  function renderChips() {
    const box = $('chips');
    SUGGESTIONS.forEach(s => {
      const b = el('button', 'chip', s);
      b.type = 'button';
      b.addEventListener('click', () => ask(s));
      box.appendChild(b);
    });
  }

  /* ---------- wiring ---------- */

  $('chat-toggle').addEventListener('click', () => setChat($('chat').hidden));
  $('chat-close').addEventListener('click', () => { setChat(false); $('chat-toggle').focus(); });
  $('chat-form').addEventListener('submit', e => { e.preventDefault(); ask($('chat-input').value); });
  $('chat-input').addEventListener('keydown', e => {
    // isComposing: don't send while an input method (e.g. accented characters) is mid-word
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); ask($('chat-input').value); }
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !$('chat').hidden) { setChat(false); $('chat-toggle').focus(); }
  });

  // 'toggle' doesn't bubble, so listen in the capture phase: one listener for every section.
  $('sections').addEventListener('toggle', e => {
    if (e.target.matches('details.section') && e.target.open) fillText(e.target);
  }, true);

  $('section-search').addEventListener('input', scheduleFilter);
  $('expand-all').addEventListener('click', () => {
    $('sections').querySelectorAll('details.section:not([hidden])').forEach(d => { d.open = true; fillText(d); });
  });
  $('collapse-all').addEventListener('click', () => {
    $('sections').querySelectorAll('details.section').forEach(d => { d.open = false; });
  });

  function goHome(e) { e.preventDefault(); if (location.hash) location.hash = ''; else showHome(); }
  $('brand-link').addEventListener('click', goHome);
  $('back-link').addEventListener('click', goHome);

  window.addEventListener('hashchange', route);
  renderChips();
  loadActs();
  route();
})();
