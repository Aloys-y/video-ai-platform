const RagCards = {
  state: {
    pageData: null,
    loading: false,
    editingCard: null,
    detailCard: null,
    deleteCardCode: null,
  },

  init() {
    this.bindEvents();
    this.renderRoleState();
    this.load(1);
  },

  destroy() {},

  bindEvents() {
    const s = document.getElementById('rc-search');
    const cf = document.getElementById('rc-filter-category');
    const ef = document.getElementById('rc-filter-enabled');
    if (s) s.oninput = () => this.load(1);
    if (cf) cf.onchange = () => this.load(1);
    if (ef) ef.onchange = () => this.load(1);
    document.getElementById('rc-refresh')?.addEventListener('click', () => this.load(this.state.pageData?.current || 1));
    document.getElementById('rc-edit-save')?.addEventListener('click', () => this.saveEdit());
    document.getElementById('rc-edit-cancel')?.addEventListener('click', () => this.closeEdit());
    document.getElementById('rc-detail-close')?.addEventListener('click', () => this.closeDetail());
    document.getElementById('rc-detail-delete')?.addEventListener('click', () => this.confirmDelete());
    document.getElementById('rc-delete-cancel')?.addEventListener('click', () => this.closeDeleteConfirm());
    document.getElementById('rc-delete-confirm-btn')?.addEventListener('click', () => this.doDelete());
  },

  isAdmin() { return Api.getUserInfo()?.role === 'ADMIN'; },

  renderRoleState() {
    const guard = document.getElementById('rc-guard');
    const panel = document.getElementById('rc-panel');
    const na = !this.isAdmin();
    if (guard) guard.classList.toggle('hidden', !na);
    if (panel) panel.classList.toggle('rag-import--disabled', na);
  },

  async load(page) {
    if (!this.isAdmin()) return;
    this.state.loading = true;
    this.renderPagination();

    const keyword = document.getElementById('rc-search')?.value?.trim() || '';
    const category = document.getElementById('rc-filter-category')?.value || '';
    const enabledStr = document.getElementById('rc-filter-enabled')?.value || '';
    const enabled = enabledStr === '' ? null : enabledStr === 'true';

    try {
      this.state.pageData = await Api.listKnowledgeCards({
        keyword, category, enabled, page, size: 10
      });
    } catch (e) {
      App.toast(e.message, 'error');
    } finally {
      this.state.loading = false;
      this.renderTable();
      this.renderPagination();
    }
  },

  renderTable() {
    const tbody = document.getElementById('rc-table-body');
    const empty = document.getElementById('rc-empty');
    if (!tbody) return;
    const cards = this.state.pageData?.records || [];
    if (empty) empty.classList.toggle('hidden', cards.length > 0 || this.state.loading);
    if (this.state.loading) {
      tbody.innerHTML = '<tr><td colspan="6" class="rc-loading">Loading...</td></tr>';
      return;
    }

    const COLORS = { LEGEND:'#a78bfa', WEAPON:'#f87171', MAP:'#34d399', TACTIC:'#fbbf24', MECHANIC:'#60a5fa', PATCH:'#fb923c' };
    const LABELS = { INDEXED:'Indexed', PENDING:'Pending', INDEXING:'Indexing', FAILED:'Failed', DRAFT:'Draft' };
    const CLS = { INDEXED:'completed', PENDING:'pending', INDEXING:'pending', FAILED:'failed', DRAFT:'draft' };

    tbody.innerHTML = cards.map(c => `
      <tr class="rc-row" data-cardcode="${this.esc(c.cardCode)}">
        <td class="rc-cell-code" title="${this.esc(c.cardCode)}">${this.esc(c.cardCode)}</td>
        <td class="rc-cell-title" title="${this.esc(c.title)}">${this.esc(c.title)}</td>
        <td><span class="rc-tag" style="background:${COLORS[c.category]||'#999'};color:#fff">${this.esc(c.category)}</span></td>
        <td><span class="badge badge--${CLS[c.indexStatus]||'info'}">${LABELS[c.indexStatus]||c.indexStatus}</span></td>
        <td>${c.enabled ? 'Yes' : '<span class="text-muted">No</span>'}</td>
        <td class="rc-cell-actions">
          <button class="btn btn--ghost btn--small rc-detail-btn" data-cardcode="${this.esc(c.cardCode)}">Detail</button>
          <button class="btn btn--ghost btn--small rc-edit-btn" data-cardcode="${this.esc(c.cardCode)}">Edit</button>
        </td>
      </tr>
    `).join('');

    tbody.querySelectorAll('.rc-row').forEach(row => {
      row.onclick = () => this.openDetail(row.dataset.cardcode);
      row.style.cursor = 'pointer';
    });
    tbody.querySelectorAll('.rc-detail-btn').forEach(b => {
      b.onclick = (e) => { e.stopPropagation(); this.openDetail(b.dataset.cardcode); };
    });
    tbody.querySelectorAll('.rc-edit-btn').forEach(b => {
      b.onclick = (e) => { e.stopPropagation(); this.openEdit(b.dataset.cardcode); };
    });
  },

  renderPagination() {
    const pg = document.getElementById('rc-pagination');
    if (!pg) return;
    const p = this.state.pageData;
    if (!p || p.pages <= 1) { pg.innerHTML = ''; return; }
    pg.innerHTML = `
      <span class="rc-page-info">${p.total} cards, page ${p.current}/${p.pages}</span>
      <button class="btn btn--ghost btn--small" ${p.current <= 1 ? 'disabled' : ''} data-rc-page="1">First</button>
      <button class="btn btn--ghost btn--small" ${p.current <= 1 ? 'disabled' : ''} data-rc-page="${p.current - 1}">Prev</button>
      <button class="btn btn--ghost btn--small" ${p.current >= p.pages ? 'disabled' : ''} data-rc-page="${p.current + 1}">Next</button>
      <button class="btn btn--ghost btn--small" ${p.current >= p.pages ? 'disabled' : ''} data-rc-page="${p.pages}">Last</button>
    `;
    pg.querySelectorAll('[data-rc-page]').forEach(b => {
      b.onclick = () => this.load(Number(b.dataset.rcPage));
    });
  },

  // ============ Detail Modal ============

  async openDetail(cardCode) {
    try {
      this.state.detailCard = await Api.getKnowledgeCard(cardCode);
      this.renderDetail();
      document.getElementById('rc-detail-modal')?.classList.remove('hidden');
    } catch (e) { App.toast(e.message, 'error'); }
  },

  closeDetail() {
    document.getElementById('rc-detail-modal')?.classList.add('hidden');
    this.state.detailCard = null;
    this.state.deleteCardCode = null;
    this.renderDetailDeleteBtn();
  },

  renderDetail() {
    const c = this.state.detailCard;
    if (!c) return;
    const LABELS = { INDEXED:'Indexed', PENDING:'Pending', INDEXING:'Indexing', FAILED:'Failed', DRAFT:'Draft' };

    document.getElementById('rc-detail-title').textContent = c.title || '-';
    document.getElementById('rc-detail-cardcode').textContent = c.cardCode || '-';
    document.getElementById('rc-detail-category').textContent = c.category || '-';
    document.getElementById('rc-detail-status').textContent = LABELS[c.indexStatus] || c.indexStatus;
    document.getElementById('rc-detail-aliases').textContent = (c.aliases || []).join(', ') || '-';
    document.getElementById('rc-detail-tags').textContent = (c.tags || []).join(', ') || '-';
    document.getElementById('rc-detail-version').textContent = c.versionTag || '-';
    document.getElementById('rc-detail-created').textContent = c.createdAt || '-';
    document.getElementById('rc-detail-updated').textContent = c.updatedAt || '-';

    // Markdown content
    const md = document.getElementById('rc-detail-content');
    if (md) {
      try {
        md.innerHTML = marked.parse(c.contentMarkdown || '*No content*');
      } catch {
        md.textContent = c.contentMarkdown || 'No content';
      }
    }

    // Show delete button if we're viewing the same card
    if (this.state.deleteCardCode !== c.cardCode) {
      this.state.deleteCardCode = null;
    }
    this.renderDetailDeleteBtn();
  },

  renderDetailDeleteBtn() {
    const btn = document.getElementById('rc-detail-delete');
    if (!btn) return;
    const c = this.state.detailCard;
    if (!c) return;
    btn.textContent = this.state.deleteCardCode === c.cardCode ? 'Delete Now!' : 'Delete';
    btn.style.background = this.state.deleteCardCode === c.cardCode ? 'var(--accent-red)' : '';
  },

  confirmDelete() {
    const c = this.state.detailCard;
    if (!c) return;
    if (this.state.deleteCardCode !== c.cardCode) {
      this.state.deleteCardCode = c.cardCode;
      this.renderDetailDeleteBtn();
      document.getElementById('rc-delete-confirm')?.classList.remove('hidden');
      return;
    }
    document.getElementById('rc-delete-confirm')?.classList.remove('hidden');
  },

  closeDeleteConfirm() {
    document.getElementById('rc-delete-confirm')?.classList.add('hidden');
    this.state.deleteCardCode = null;
    this.renderDetailDeleteBtn();
  },

  async doDelete() {
    const c = this.state.detailCard;
    if (!c) return;
    try {
      await Api.deleteKnowledgeCard(c.cardCode);
      this.closeDetail();
      this.load(this.state.pageData?.current || 1);
      App.toast(`${c.cardCode} deleted`, 'success');
    } catch (e) { App.toast(e.message, 'error'); }
  },

  // ============ Edit Panel (same) ============

  async openEdit(cardCode) {
    try {
      const card = await Api.getKnowledgeCard(cardCode);
      this.state.editingCard = card;
      document.getElementById('rc-detail-modal')?.classList.add('hidden');
      const panel = document.getElementById('rc-edit-panel');
      if (!panel) return;
      panel.querySelector('[data-rc-edit="cardCode"]').textContent = card.cardCode;
      panel.querySelector('[data-rc-edit="title"]').value = card.title || '';
      panel.querySelector('[data-rc-edit="category"]').value = card.category || 'MECHANIC';
      panel.querySelector('[data-rc-edit="subjectCode"]').value = card.subjectCode || '';
      panel.querySelector('[data-rc-edit="aliases"]').value = (card.aliases || []).join(', ');
      panel.querySelector('[data-rc-edit="tags"]').value = (card.tags || []).join(', ');
      panel.querySelector('[data-rc-edit="contentMarkdown"]').value = card.contentMarkdown || '';
      panel.querySelector('[data-rc-edit="enabled"]').checked = !!card.enabled;
      panel.querySelector('[data-rc-edit="timeless"]').checked = !!card.timeless;
      panel.classList.remove('hidden');
    } catch (e) { App.toast(e.message, 'error'); }
  },

  closeEdit() {
    document.getElementById('rc-edit-panel')?.classList.add('hidden');
    this.state.editingCard = null;
  },

  async saveEdit() {
    const panel = document.getElementById('rc-edit-panel');
    if (!panel || !this.state.editingCard) return;
    const cc = this.state.editingCard.cardCode;
    const data = {
      cardCode: cc,
      title: panel.querySelector('[data-rc-edit="title"]').value.trim(),
      category: panel.querySelector('[data-rc-edit="category"]').value,
      subjectCode: panel.querySelector('[data-rc-edit="subjectCode"]').value.trim() || null,
      aliases: panel.querySelector('[data-rc-edit="aliases"]').value.split(',').map(s => s.trim()).filter(Boolean),
      tags: panel.querySelector('[data-rc-edit="tags"]').value.split(',').map(s => s.trim()).filter(Boolean),
      contentMarkdown: panel.querySelector('[data-rc-edit="contentMarkdown"]').value.trim(),
      enabled: panel.querySelector('[data-rc-edit="enabled"]').checked,
      timeless: panel.querySelector('[data-rc-edit="timeless"]').checked,
    };
    try {
      await Api.updateKnowledgeCard(cc, data);
      this.closeEdit();
      this.load(this.state.pageData?.current || 1);
      App.toast(`${cc} updated`, 'success');
    } catch (e) { App.toast(e.message, 'error'); }
  },

  esc(v) {
    const d = document.createElement('div');
    d.textContent = v == null ? '' : String(v);
    return d.innerHTML;
  },
};
window.RagCards = RagCards;
