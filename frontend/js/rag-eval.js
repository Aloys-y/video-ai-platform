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
    const pipeline = r.hybridRetrievalEnabled
      ? [`${r.rawCandidateCount ?? '-'} + ${r.lexicalCandidateCount ?? '-'}`, r.fusedCandidateCount,
          r.scorePassedCount, r.diversifiedCount, r.selectedCount, r.hitCount]
      : [r.rawCandidateCount, r.scorePassedCount, r.diversifiedCount, r.selectedCount, r.hitCount];
    document.getElementById('rev-pipeline').textContent = pipeline
      .map(v => v == null ? '-' : v).join(' → ');
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
            ${this.esc((h.contentText || '').substring(0, 300))}${(h.contentText || '').length > 300 ? '...' : ''}
          </td>
        </tr>
      `).join('');

    this.renderTrace(r);

    // Full prompt preview
    document.getElementById('rev-prompt').textContent = r.promptPreview || '';
  },

  renderTrace(r) {
    const raw = r.hybridRetrievalEnabled ? (r.fusedCandidates || []) : (r.rawCandidates || []);
    const scorePassed = new Set((r.scorePassedCandidates || []).map(h => h.vectorId));
    const diversified = new Set((r.diversifiedCandidates || []).map(h => h.vectorId));
    const selected = new Set((r.selectedCandidates || []).map(h => h.vectorId));
    const included = new Set((r.hits || []).map(h => h.vectorId));
    const tbody = document.getElementById('rev-trace-body');
    if (!tbody) return;

    tbody.innerHTML = raw.length === 0
      ? '<tr><td colspan="6" style="text-align:center;color:var(--text-secondary);padding:20px">No raw candidates</td></tr>'
      : raw.map((h, index) => {
        let outcome = '进入上下文';
        let color = 'var(--accent-green)';
        if (!scorePassed.has(h.vectorId)) {
          outcome = '低于阈值';
          color = 'var(--text-secondary)';
        } else if (!diversified.has(h.vectorId)) {
          outcome = '单卡限额';
          color = 'var(--accent-orange)';
        } else if (!selected.has(h.vectorId)) {
          outcome = 'Final TopK';
          color = 'var(--accent-gold)';
        } else if (!included.has(h.vectorId)) {
          outcome = '上下文超长';
          color = 'var(--accent-orange)';
        }
        return `
          <tr>
            <td>${index + 1}</td>
            <td class="rev-score">${r.hybridRetrievalEnabled
              ? `RRF ${Number(h.fusionScore || 0).toFixed(4)} / D ${Number(h.denseScore || 0).toFixed(4)}`
              : Number(h.score || 0).toFixed(4)}</td>
            <td><code>${this.esc(h.cardCode)}</code></td>
            <td>${this.esc(h.title)}</td>
            <td style="font-size:0.82rem;color:var(--text-secondary)">${this.esc(h.headingPath || '-')}</td>
            <td style="color:${color}">${outcome}</td>
          </tr>`;
      }).join('');
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
