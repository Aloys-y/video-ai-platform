const RagEval = {
  state: { loading: false, result: null, query: '' },

  init() {
    this.bindEvents();
    document.getElementById('rev-query')?.focus();
  },
  destroy() {},

  bindEvents() {
    document.getElementById('rev-submit')?.addEventListener('click', () => this.search());
    document.getElementById('rev-query')?.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && e.ctrlKey) this.search();
    });
  },

  async search() {
    const q = document.getElementById('rev-query')?.value?.trim();
    if (!q || this.state.loading) return;
    this.state.loading = true;
    this.state.query = q;
    this.updateSubmit();

    try {
      this.state.result = await Api.request('POST', '/admin/rag/retrieve-test', {
        body: { query: q },
        timeout: 30000,
      });
      this.render();
    } catch (e) {
      App.toast(e.message, 'error');
    } finally {
      this.state.loading = false;
      this.updateSubmit();
    }
  },

  updateSubmit() {
    const btn = document.getElementById('rev-submit');
    if (!btn) return;
    btn.disabled = this.state.loading;
    btn.textContent = this.state.loading ? 'Searching...' : 'Search (Ctrl+Enter)';
  },

  render() {
    const r = this.state.result;
    if (!r) return;

    document.getElementById('rev-section').classList.remove('hidden');
    document.getElementById('rev-query-display').textContent = r.queryText || '-';
    document.getElementById('rev-expanded').textContent = r.expandedQuery || '-';
    document.getElementById('rev-version').textContent = r.versionTag || '-';
    document.getElementById('rev-topk').textContent = r.topK || '-';
    document.getElementById('rev-minscore').textContent = r.minScore != null ? r.minScore.toFixed(2) : '-';
    document.getElementById('rev-hitcount').textContent = r.hitCount || 0;
    document.getElementById('rev-latency').textContent = (r.latencyMs || 0) + ' ms';

    // Context preview
    document.getElementById('rev-context').textContent = r.contextPreview || '(none)';

    // Hits table
    const hits = r.hits || [];
    const tbody = document.getElementById('rev-hits-body');
    tbody.innerHTML = hits.length === 0
      ? '<tr><td colspan="5" style="text-align:center;color:var(--text-secondary);padding:20px">No hits — try a different query</td></tr>'
      : hits.map(h => `
        <tr>
          <td class="rev-score" style="color:${h.score >= 0.85 ? 'var(--accent-green)' : h.score >= 0.75 ? 'var(--accent-gold)' : 'var(--accent-orange)'}">${(h.score * 100).toFixed(1)}%</td>
          <td><code>${this.esc(h.cardCode)}</code></td>
          <td>${this.esc(h.title)}</td>
          <td><span class="rc-tag" style="background:${this.catColor(h.category)};color:#fff">${this.esc(h.category)}</span></td>
          <td style="font-size:0.82rem;color:var(--text-secondary)">${this.esc(h.headingPath || '-')}</td>
        </tr>
        <tr>
          <td colspan="5" style="padding:4px 12px 12px;font-size:0.82rem;line-height:1.5;color:var(--text-primary);border-bottom:1px solid var(--border-default)">
            ${(h.contentText || '').substring(0, 300)}${(h.contentText || '').length > 300 ? '...' : ''}
          </td>
        </tr>
      `).join('');

    // Full prompt preview
    document.getElementById('rev-prompt').textContent = r.promptPreview || '';
  },

  catColor(c) {
    const m = { LEGEND:'#a78bfa', WEAPON:'#f87171', MAP:'#34d399', TACTIC:'#fbbf24', MECHANIC:'#60a5fa', PATCH:'#fb923c' };
    return m[c] || '#999';
  },

  esc(v) {
    const d = document.createElement('div');
    d.textContent = v == null ? '' : String(v);
    return d.innerHTML;
  },
};
window.RagEval = RagEval;
