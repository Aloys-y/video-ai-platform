const RagImport = {
  state: {
    files: [],
    previews: [],
    uploading: false,
    lastResult: null,
    step: 'select', // 'select' | 'preview' | 'result'
  },

  init() {
    this.state.uploading = false;
    this.bindEvents();
    this.renderRoleState();
    this.renderStep();
  },

  destroy() {
    this.state.uploading = false;
  },

  bindEvents() {
    const zone = document.getElementById('rag-upload-zone');
    const input = document.getElementById('rag-file-input');
    const submit = document.getElementById('rag-import-submit');
    const clear = document.getElementById('rag-files-clear');
    const confirm = document.getElementById('rag-confirm-btn');
    const back = document.getElementById('rag-back-btn');

    if (zone) {
      zone.onclick = () => { if (!this.isAdmin()) return; input?.click(); };
      zone.onkeydown = (event) => {
        if (!this.isAdmin()) return;
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); input?.click(); }
      };
      zone.ondragover = (event) => { if (!this.isAdmin()) return; event.preventDefault(); zone.classList.add('dragover'); };
      zone.ondragleave = () => zone.classList.remove('dragover');
      zone.ondrop = (event) => {
        if (!this.isAdmin()) return;
        event.preventDefault();
        zone.classList.remove('dragover');
        this.addFiles(Array.from(event.dataTransfer?.files || []));
      };
    }

    if (input) {
      input.onchange = (event) => {
        this.addFiles(Array.from(event.target.files || []));
        input.value = '';
      };
    }

    if (submit) submit.onclick = () => this.preview();
    if (clear) clear.onclick = () => { this.state.files = []; this.renderFiles(); this.updateSubmitState(); };
    if (confirm) confirm.onclick = () => this.confirm();
    if (back) back.onclick = () => { this.state.step = 'select'; this.renderStep(); };
  },

  isAdmin() {
    return Api.getUserInfo()?.role === 'ADMIN';
  },

  addFiles(newFiles) {
    const markdownFiles = newFiles.filter(f => this.isMarkdownFile(f));
    const existing = new Set(this.state.files.map(f => `${f.name}-${f.size}-${f.lastModified}`));
    markdownFiles.forEach(f => {
      const key = `${f.name}-${f.size}-${f.lastModified}`;
      if (!existing.has(key)) { existing.add(key); this.state.files.push(f); }
    });
    const ignoredCount = newFiles.length - markdownFiles.length;
    if (ignoredCount > 0) App.toast(`已忽略 ${ignoredCount} 个非 Markdown 文件`, 'info');
    this.renderFiles();
    this.updateSubmitState();
  },

  isMarkdownFile(file) {
    return /\.(md|markdown)$/i.test(file?.name || '');
  },

  // ==================== Step Rendering ====================

  renderStep() {
    const selectPanel = document.getElementById('rag-step-select');
    const previewPanel = document.getElementById('rag-step-preview');
    const resultSection = document.getElementById('rag-results-section');

    if (selectPanel) selectPanel.classList.toggle('hidden', this.state.step !== 'select');
    if (previewPanel) previewPanel.classList.toggle('hidden', this.state.step !== 'preview');
    if (resultSection) resultSection.classList.toggle('hidden', !this.state.lastResult);

    if (this.state.step === 'select') {
      this.renderFiles();
      this.updateSubmitState();
    }
    if (this.state.step === 'preview') {
      this.renderPreviews();
    }
    if (this.state.lastResult) {
      this.renderResults();
    }
  },

  renderRoleState() {
    const guard = document.getElementById('rag-import-guard');
    const panel = document.getElementById('rag-import-panel');
    const nonAdmin = !this.isAdmin();
    if (guard) guard.classList.toggle('hidden', !nonAdmin);
    if (panel) panel.classList.toggle('rag-import--disabled', nonAdmin);
  },

  // ==================== Step 1: Select Files ====================

  renderFiles() {
    const empty = document.getElementById('rag-files-empty');
    const list = document.getElementById('rag-files-list');
    const count = document.getElementById('rag-file-count');
    const clear = document.getElementById('rag-files-clear');
    if (!list || !empty || !count || !clear) return;

    count.textContent = `${this.state.files.length} 个文件`;
    clear.classList.toggle('hidden', this.state.files.length === 0);
    empty.classList.toggle('hidden', this.state.files.length > 0);

    list.innerHTML = this.state.files.map((file, i) => `
      <div class="rag-file-item">
        <div class="rag-file-item__meta">
          <div class="rag-file-item__name">${this.escapeHtml(file.name)}</div>
          <div class="rag-file-item__size">${this.formatSize(file.size)}</div>
        </div>
        <button type="button" class="btn btn--ghost btn--small rag-file-item__remove" data-index="${i}">移除</button>
      </div>
    `).join('');

    list.querySelectorAll('[data-index]').forEach(btn => {
      btn.onclick = () => {
        this.state.files.splice(Number(btn.dataset.index), 1);
        this.renderFiles();
        this.updateSubmitState();
      };
    });
  },

  updateSubmitState() {
    const btn = document.getElementById('rag-import-submit');
    if (!btn) return;
    btn.disabled = this.state.uploading || !this.isAdmin() || this.state.files.length === 0;
  },

  // ==================== Step 2: Preview & Edit ====================

  async preview() {
    if (this.state.uploading || !this.isAdmin() || this.state.files.length === 0) return;

    const btn = document.getElementById('rag-import-submit');
    const category = document.getElementById('rag-default-category')?.value || 'MECHANIC';
    const enabled = !!document.getElementById('rag-default-enabled')?.checked;
    const timeless = !!document.getElementById('rag-default-timeless')?.checked;
    const codePrefix = document.getElementById('rag-code-prefix')?.value?.trim() || '';

    this.state.uploading = true;
    this.updateSubmitState();
    if (btn) { btn.dataset.origText = btn.textContent; btn.textContent = '解析中...'; }

    try {
      this.state.previews = await Api.previewKnowledgeMarkdown(this.state.files, {
        defaultCategory: category,
        defaultEnabled: enabled,
        defaultTimeless: timeless,
        codePrefix,
      });
      this.state.step = 'preview';
      this.renderStep();
      App.toast(`已解析 ${this.state.previews.length} 张卡片`, 'success');
    } catch (error) {
      App.toast(error.message, 'error');
    } finally {
      this.state.uploading = false;
      if (btn) btn.textContent = btn.dataset.origText || '预览卡片';
      this.updateSubmitState();
    }
  },

  renderPreviews() {
    const list = document.getElementById('rag-preview-list');
    const count = document.getElementById('rag-preview-count');
    if (!list || !count) return;

    count.textContent = `共 ${this.state.previews.length} 张卡片，请确认并编辑后导入`;

    list.innerHTML = this.state.previews.map((card, i) => `
      <div class="card rag-preview-card">
        <div class="rag-preview-card__header">
          <span class="rag-preview-card__file">${this.escapeHtml(card.fileName)}</span>
          <button type="button" class="btn btn--ghost btn--small rag-preview-card__toggle" data-toggle="${i}">
            ${card.contentMarkdown ? '展开内容' : ''}
          </button>
        </div>
        <div class="rag-preview-card__fields">
          <div class="form-row">
            <div class="form-group form-group--flex">
              <label class="form-label">cardCode</label>
              <input class="form-input" data-field="${i}-cardCode" value="${this.escapeHtml(card.cardCode)}" maxlength="64">
            </div>
            <div class="form-group form-group--flex">
              <label class="form-label">分类</label>
              <select class="form-input" data-field="${i}-category">
                ${['LEGEND','WEAPON','MAP','TACTIC','MECHANIC','PATCH'].map(c =>
                  `<option value="${c}" ${card.category === c ? 'selected' : ''}>${c}</option>`
                ).join('')}
              </select>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">标题</label>
            <input class="form-input" data-field="${i}-title" value="${this.escapeHtml(card.title)}" maxlength="255">
          </div>
          <div class="form-group">
            <label class="form-label">别名（逗号分隔）</label>
            <input class="form-input" data-field="${i}-aliases" value="${this.escapeHtml((card.aliases || []).join(', '))}" placeholder="别名1, 别名2">
          </div>
          <div class="form-group">
            <label class="form-label">标签（逗号分隔）</label>
            <input class="form-input" data-field="${i}-tags" value="${this.escapeHtml((card.tags || []).join(', '))}" placeholder="tag1, tag2">
          </div>
          <div class="rag-preview-card__content hidden" data-content="${i}">
            <textarea class="form-input rag-preview-card__textarea" data-field="${i}-contentMarkdown" rows="12">${this.escapeHtml(card.contentMarkdown || '')}</textarea>
          </div>
          <div class="rag-preview-card__switches">
            <label class="rag-import-check">
              <input type="checkbox" data-field="${i}-enabled" ${card.enabled ? 'checked' : ''}>
              <span>导入后启用并索引</span>
            </label>
            <label class="rag-import-check">
              <input type="checkbox" data-field="${i}-timeless" ${card.timeless ? 'checked' : ''}>
              <span>timeless</span>
            </label>
          </div>
        </div>
      </div>
    `).join('');

    // Bind toggle buttons
    list.querySelectorAll('[data-toggle]').forEach(btn => {
      btn.onclick = () => {
        const i = btn.dataset.toggle;
        const content = list.querySelector(`[data-content="${i}"]`);
        const isHidden = content?.classList.toggle('hidden');
        btn.textContent = isHidden ? '展开内容' : '收起内容';
      };
    });
  },

  collectEditedPreviews() {
    const list = document.getElementById('rag-preview-list');
    if (!list) return [];

    return this.state.previews.map((card, i) => {
      const getVal = (field) => list.querySelector(`[data-field="${i}-${field}"]`)?.value || '';
      const getChecked = (field) => !!list.querySelector(`[data-field="${i}-${field}"]`)?.checked;

      return {
        cardCode: getVal('cardCode').trim() || card.cardCode,
        title: getVal('title').trim() || card.title,
        category: getVal('category').trim() || card.category,
        subjectCode: card.subjectCode,
        aliases: getVal('aliases').split(',').map(s => s.trim()).filter(Boolean),
        tags: getVal('tags').split(',').map(s => s.trim()).filter(Boolean),
        contentMarkdown: getVal('contentMarkdown').trim() || card.contentMarkdown,
        enabled: getChecked('enabled'),
        timeless: getChecked('timeless'),
      };
    });
  },

  // ==================== Step 3: Confirm & Result ====================

  async confirm() {
    if (this.state.uploading) return;

    const requests = this.collectEditedPreviews();
    if (requests.length === 0) { App.toast('没有可导入的卡片', 'error'); return; }

    const btn = document.getElementById('rag-confirm-btn');
    this.state.uploading = true;
    if (btn) { btn.dataset.origText = btn.textContent; btn.textContent = '导入中...'; btn.disabled = true; }

    try {
      this.state.lastResult = await Api.batchCreateCards(requests);
      this.state.files = [];
      this.state.previews = [];
      this.renderStep();
      App.toast(`导入完成，成功 ${this.state.lastResult.successCount || 0} 个`, 'success');
    } catch (error) {
      App.toast(error.message, 'error');
    } finally {
      this.state.uploading = false;
      if (btn) { btn.textContent = btn.dataset.origText || '确认导入'; btn.disabled = false; }
    }
  },

  // ==================== Result Rendering ====================

  renderResults() {
    const section = document.getElementById('rag-results-section');
    const summary = document.getElementById('rag-results-summary');
    const list = document.getElementById('rag-results-list');
    const result = this.state.lastResult;
    if (!section || !summary || !list) return;

    section.classList.remove('hidden');
    summary.innerHTML = `
      <div class="rag-results-summary__item">总文件 <strong>${result.totalFiles || 0}</strong></div>
      <div class="rag-results-summary__item">成功 <strong>${result.successCount || 0}</strong></div>
      <div class="rag-results-summary__item">失败 <strong>${result.failedCount || 0}</strong></div>
    `;

    list.innerHTML = (result.items || []).map(item => `
      <div class="card rag-result-card">
        <div class="rag-result-card__top">
          <div>
            <div class="rag-result-card__code">${this.escapeHtml(item.cardCode || '-')}</div>
            <div class="rag-result-card__title">${this.escapeHtml(item.title || '-')}</div>
          </div>
          <span class="badge badge--${item.status === 'SUCCESS' ? 'completed' : 'failed'}">${item.status === 'SUCCESS' ? '成功' : '失败'}</span>
        </div>
        <div class="rag-result-card__meta">
          <span>分类：${this.escapeHtml(item.category || '-')}</span>
          <span>索引状态：${this.escapeHtml(item.indexStatus || '-')}</span>
        </div>
        <div class="rag-result-card__message">${this.escapeHtml(item.message || '')}</div>
      </div>
    `).join('');
  },

  // ==================== Utilities ====================

  formatSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  },

  escapeHtml(value) {
    const div = document.createElement('div');
    div.textContent = value == null ? '' : String(value);
    return div.innerHTML;
  },
};

window.RagImport = RagImport;
