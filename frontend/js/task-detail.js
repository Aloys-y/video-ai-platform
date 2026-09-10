/**
 * TaskDetail Module — 任务详情 + AI 分析结果展示
 */

const TaskDetail = {
  taskId: null,
  task: null,
  pollTimer: null,
  segmentData: null,
  segmentError: null,
  loadVersion: 0,
  activeSegmentNo: null,
  playRequest: 0,

  /**
   * 初始化任务详情
   */
  init(taskId) {
    this.taskId = taskId;
    this.task = null;
    this.segmentData = null;
    this.segmentError = null;
    this.activeSegmentNo = null;
    this.playRequest++;
    this.loadVersion++;
    this.stopPolling();

    if (!taskId) {
      document.getElementById('task-detail-content').innerHTML = `
        <div class="empty-state">
          <div class="empty-state__title">任务不存在</div>
          <a href="#/dashboard" class="btn btn--ghost btn--small">返回列表</a>
        </div>
      `;
      return;
    }

    this.loadTask();
  },

  /**
   * 加载任务详情
   */
  async loadTask() {
    const id = this.taskId;
    const version = ++this.loadVersion;
    try {
      const task = await Api.get(`/task/${encodeURIComponent(id)}`);
      let segmentData = null, segmentError = null;
      if (task.analysisMode === 'AUDIO_PREFILTER') {
        try {
          segmentData = await Api.get(`/task/${encodeURIComponent(id)}/segments`);
          if (segmentData.executionNo !== (task.attemptNo || 0)) throw new Error('任务已重试，请刷新查看当前结果');
          task.status = segmentData.taskStatus;
          task.currentStep = segmentData.currentStep;
        } catch (err) { segmentData = null; segmentError = err.message; }
      }
      if (version !== this.loadVersion || id !== this.taskId) return;
      if (this.task && (this.task.attemptNo || 0) !== (task.attemptNo || 0)) {
        this.activeSegmentNo = null;
        this.playRequest++;
        document.getElementById('segment-player')?.pause();
      }
      this.task = task;
      this.segmentData = segmentData;
      this.segmentError = segmentError;
      this.render();

      // 非终态 → 自动轮询（用递归 setTimeout 替代 setInterval，避免并发）
      if (!this.isFinalState(this.task.status)) {
        this.scheduleNextPoll();
      }
    } catch (err) {
      if (version !== this.loadVersion) return;
      // H-06 fix: 加载失败时停止轮询
      this.stopPolling();
      document.getElementById('task-detail-content').innerHTML = `
        <div class="empty-state">
          <div class="empty-state__title">加载失败</div>
          <div class="empty-state__desc">${this.escapeHtml(err.message)}</div>
          <div style="display:flex;gap:12px;justify-content:center;margin-top:16px">
            <button class="btn btn--ghost btn--small" onclick="TaskDetail.retryLoad()">重试</button>
            <a href="#/dashboard" class="btn btn--ghost btn--small">返回列表</a>
          </div>
        </div>
      `;
    }
  },

  /**
   * 判断是否终态
   */
  isFinalState(status) {
    return ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(status);
  },

  /**
   * 手动重试加载（停止轮询后由用户触发）
   */
  retryLoad() {
    this.loadTask();
  },

  /**
   * H-07 fix: 递归 setTimeout 替代 setInterval，避免并发请求重叠
   */
  scheduleNextPoll() {
    this.stopPolling();
    this.pollTimer = setTimeout(() => {
      this.loadTask();
    }, 3000);
  },

  /**
   * 开始轮询（兼容旧调用）
   */
  startPolling() {
    this.scheduleNextPoll();
  },

  /**
   * 停止轮询
   */
  stopPolling() {
    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  },

  /**
   * 渲染详情页
   */
  render() {
    const task = this.task;
    if (!task) return;

    const container = document.getElementById('task-detail-content');
    if (!container) return;

    const statusClass = (task.status || 'pending').toLowerCase();
    const statusText = this.getStatusText(task.status);
    const isFinal = this.isFinalState(task.status);
    const displayName = task.taskName || this.extractFileName(task.videoUrl);
    const stage = TaskStage.describe(task);
    const canRetry = task.status === 'FAILED' || task.status === 'PARTIAL';
    const isStuck = !isFinal && this._isStuck(task);
    const canDelete = isFinal || isStuck;
    const deleteLabel = isFinal ? '删除' : '强制取消';
    const previousPlayer = document.getElementById('segment-player');

    let resultHtml = '';
    if (task.status === 'SUCCEEDED' && task.result) {
      const segmentNotice = task.analysisMode === 'AUDIO_PREFILTER' && this.segmentData?.total > 0
        && task.result.startsWith('已完成 ');
      resultHtml = segmentNotice ? '' : this.renderResult(task.result);
    } else if (!isFinal) {
      resultHtml = `
        <div class="card result-section" style="text-align:center;padding:48px 24px">
          <div class="badge badge--${statusClass}" style="margin-bottom:16px">${statusText}</div>
          <div role="status" aria-live="polite">
            <div class="hud-title" style="margin-bottom:12px">${this.escapeHtml(stage)}</div>
            <p style="color:var(--text-secondary);margin:0">${task.currentStep === 'ANALYZING_SEGMENTS' && this.segmentData?.total > 0
              ? `已完成 ${this.segmentData.succeeded} / ${this.segmentData.total} 个片段`
              : '阶段完成后自动更新，可稍后回来查看。'}</p>
          </div>
        </div>
      `;
    } else if (task.status === 'FAILED' || task.status === 'PARTIAL') {
      resultHtml = `
        <div class="card result-section" style="text-align:center;padding:48px 24px">
          <div style="font-size:48px;margin-bottom:16px;opacity:0.3">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--accent-red)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
          </div>
          <div class="badge badge--${statusClass}" style="margin-bottom:16px">${statusText}</div>
          <div class="result-section__title">${task.status === 'PARTIAL' ? '部分片段未完成，已保存结果见下方' : '错误信息'}</div>
          <p style="color:var(--accent-red);margin-top:8px">${this.escapeHtml(task.errorMessage || ({EXECUTION_INTERRUPTED: '执行中断或租约失效，已保存的片段结果仍可查看，请按需重试。', ANALYSIS_FAILED: '分析未全部完成，请查看各片段结果。'}[task.errorCode]) || '未知错误')}</p>
          ${canRetry ? `<button class="btn btn--primary btn--small mt-lg" onclick="TaskDetail.confirmRetry()">重新分析</button>` : ''}
        </div>
      `;
    } else {
      resultHtml = `
        <div class="card result-section" style="text-align:center;padding:48px 24px">
          <div style="font-size:48px;margin-bottom:16px;opacity:0.3">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg>
          </div>
          <div class="badge badge--${statusClass}" style="margin-bottom:12px">${statusText}</div>
          <p style="color:var(--text-secondary);margin-top:8px">${task.analysisMode === 'AUDIO_PREFILTER' ? '此任务已取消，已保存的片段结果仍可在下方查看' : '此任务已被取消，没有分析结果'}</p>
        </div>
      `;
    }

    if (task.analysisMode === 'AUDIO_PREFILTER') resultHtml += this.renderSegments();

    container.innerHTML = `
      <div class="task-detail">
        <div class="task-sidebar">
          <!-- 任务概览卡片 -->
          <div class="card task-sidebar__overview">
            <div class="task-sidebar__overview-name">${this.escapeHtml(displayName)}</div>
            <div class="task-sidebar__overview-status">
              <span class="badge badge--${statusClass}">${statusText}</span>
            </div>
            ${!isFinal ? `
            <div class="task-sidebar__overview-progress" style="color:var(--text-secondary);line-height:1.6">
              <div style="font-size:12px;margin-bottom:4px">当前阶段</div>
              <strong style="color:var(--text-primary)">${this.escapeHtml(stage)}</strong>
            </div>` : ''}
          </div>

          <!-- 详细信息 -->
          <div class="card task-sidebar__info">
            <div class="task-sidebar__row">
              <span class="task-sidebar__label">任务 ID</span>
              <span class="task-sidebar__value task-sidebar__value--mono">${task.taskId}</span>
            </div>
            <div class="task-sidebar__row">
              <span class="task-sidebar__label">创建时间</span>
              <span class="task-sidebar__value">${this.formatDate(task.createdAt)}</span>
            </div>
            ${task.startedAt ? `
            <div class="task-sidebar__row">
              <span class="task-sidebar__label">开始时间</span>
              <span class="task-sidebar__value">${this.formatDate(task.startedAt)}</span>
            </div>` : ''}
            ${task.completedAt ? `
            <div class="task-sidebar__row">
              <span class="task-sidebar__label">完成时间</span>
              <span class="task-sidebar__value">${this.formatDate(task.completedAt)}</span>
            </div>` : ''}
            ${task.attemptNo > 0 ? `
            <div class="task-sidebar__row">
              <span class="task-sidebar__label">重新分析次数</span>
              <span class="task-sidebar__value">${task.attemptNo}</span>
            </div>` : ''}
          </div>

          <!-- 操作按钮 -->
          <div class="task-sidebar__actions-wrap">
            <button class="btn btn--ghost btn--small" style="flex:1" onclick="TaskDetail.promptRename()">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px"><path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
              重命名
            </button>
            ${canRetry ? `
            <button class="btn btn--primary btn--small" style="flex:1" onclick="TaskDetail.confirmRetry()">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 11-2.12-9.36L23 10"/></svg>
              重新分析
            </button>` : ''}
            ${canDelete ? `
            <button class="btn btn--ghost btn--small" style="flex:1;color:var(--accent-red);border-color:var(--accent-red)" onclick="TaskDetail.confirmDelete()">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
              ${deleteLabel}
            </button>` : ''}
          </div>
        </div>

        <div class="task-result">
          ${resultHtml}
        </div>
      </div>
    `;
    const playerSlot = document.getElementById(`segment-player-slot-${this.activeSegmentNo}`);
    if (playerSlot && previousPlayer && previousPlayer.dataset.taskId === this.taskId
        && previousPlayer.dataset.executionNo === String(task.attemptNo || 0)) playerSlot.appendChild(previousPlayer);
  },

  renderSegments() {
    const step = TaskStage.describe(this.task);
    const data = this.segmentData;
    if (this.segmentError) return `<div class="card result-section"><p>${this.escapeHtml(this.segmentError)}</p>
      <button class="btn btn--ghost btn--small" onclick="TaskDetail.retryLoad()">重新加载片段</button></div>`;
    if (!data) return '';
    const isFinal = this.isFinalState(data.taskStatus);
    const label = status => ({SUCCEEDED: '已完成', FAILED: '分析失败',
      PREPARED: isFinal ? '未执行' : '等待分析', PROCESSING: isFinal ? '未完成' : '分析中'}[status] || status);
    const playIcon = '<svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M5 3.5v9l7-4.5z"/></svg>';
    const timeButton = (item, ms) => `<button class="segment-time" title="定位原视频 ${this.segmentTime(ms)}" aria-label="播放片段 ${Number(item.segmentNo) + 1}，原视频 ${this.segmentTime(ms)}" onclick="TaskDetail.playSegment(${Number(item.segmentNo)},${Number(ms)})">${playIcon}<span>${this.segmentTime(ms)}</span></button>`;
    return `<div class="segment-overview"><div><h3>片段分析</h3><p>${isFinal ? '仅展示选中片段的分析结果，不代表覆盖整段视频' : this.escapeHtml(step)}</p></div>
      <span class="segment-count"><strong>${data.succeeded}</strong> / ${data.total} 已完成</span></div>
      ${data.total === 0 ? '<div class="card result-section">暂无可展示的片段。</div>' : ''}` + data.segments.map(item => {
        const r = item.review, active = this.activeSegmentNo === item.segmentNo;
        const tone = item.status === 'SUCCEEDED' ? 'success' : item.status === 'FAILED' ? 'error' : 'pending';
        return `<article class="card segment-card${active ? ' is-playing' : ''}" id="segment-card-${Number(item.segmentNo)}">
          <header class="segment-card__header">
            <div class="segment-card__identity"><span class="segment-number">${String(Number(item.segmentNo) + 1).padStart(2, '0')}</span>
              <div><h3>片段 ${Number(item.segmentNo) + 1}</h3><div class="segment-range"><span>原视频</span><time>${this.segmentTime(item.startMs)}</time><span aria-hidden="true">—</span><time>${this.segmentTime(item.endMs)}</time></div></div></div>
            <div class="segment-card__actions"><span class="segment-status segment-status--${tone}">${this.escapeHtml(label(item.status))}</span>
              <button class="segment-play" aria-expanded="${active}" onclick="TaskDetail.${active ? 'closeSegment()' : `playSegment(${Number(item.segmentNo)},${Number(item.startMs)})`}">${active ? '收起视频' : playIcon + '播放片段'}</button></div>
          </header>
          <div class="segment-card__body">
            ${active ? `<div class="segment-media"><div class="segment-media__slot" id="segment-player-slot-${Number(item.segmentNo)}"></div><p>片段播放 · 时间标记对应原视频</p></div>` : ''}
            <div class="segment-analysis">
              ${item.reused ? '<span class="segment-reused">复用已保存结果</span>' : ''}
              ${item.errorMessage ? `<p class="segment-error">${this.escapeHtml(item.errorMessage)}</p>` : ''}
              ${r ? `<p class="segment-summary">${this.escapeHtml(r.summary)}</p>
                ${(r.events || []).length ? '<h4 class="segment-section-title">画面观察</h4>' : ''}
                <div class="segment-events">${(r.events || []).map(e => `<div class="segment-event">${timeButton(item, e.startMs)}<p>${this.escapeHtml(e.observation)}</p></div>`).join('')}</div>
                ${(r.advice || []).length ? '<h4 class="segment-section-title">问题与建议</h4>' : ''}
                ${(r.advice || []).map(a => `<div class="segment-advice"><div class="segment-advice__title"><strong>${this.escapeHtml(a.issue)}</strong>${timeButton(item, a.startMs)}</div>
                  <p>${this.escapeHtml(a.suggestion)}</p>
                  ${a.knowledgeBasis ? `<div class="segment-basis">参考依据 · ${this.escapeHtml(a.knowledgeBasis)}</div>` : ''}</div>`).join('')}
                ${(r.uncertainties || []).length ? `<div class="segment-uncertainties"><h4>尚不确定</h4><ul>${r.uncertainties.map(u => `<li>${this.escapeHtml(u)}</li>`).join('')}</ul></div>` : ''}` : !item.errorMessage ? `<p class="segment-empty">${isFinal ? '本次未完成该片段的分析。' : '分析结果将在完成后显示。'}</p>` : ''}
            </div>
          </div>
        </article>`;
      }).join('');
  },

  segmentTime(ms) {
    const seconds = Math.floor(Math.max(0, Number(ms) || 0) / 1000);
    return `${String(Math.floor(seconds / 3600)).padStart(2, '0')}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  },

  closeSegment() {
    this.playRequest++;
    document.getElementById('segment-player')?.pause();
    this.activeSegmentNo = null;
    this.render();
  },

  async playSegment(segmentNo, originalMs) {
    const id = this.taskId, no = this.segmentData?.executionNo;
    if (no == null) return;
    const request = ++this.playRequest;
    const existing = document.getElementById('segment-player');
    if (existing && this.activeSegmentNo === segmentNo && existing.readyState > 0 && !existing.error) {
      existing.currentTime = Math.max(0, (originalMs - Number(existing.dataset.originalStartMs)) / 1000);
      existing.play().catch(() => {});
      existing.scrollIntoView({behavior: 'smooth', block: 'nearest'});
      return;
    }
    try {
      const playback = await Api.get(`/task/${encodeURIComponent(id)}/segments/${segmentNo}/play`, {executionNo: no});
      if (request !== this.playRequest || id !== this.taskId || no !== this.segmentData?.executionNo) return;
      const url = new URL(playback.url);
      if (!['https:', 'http:'].includes(url.protocol)) throw new Error('播放地址无效');
      existing?.pause();
      this.activeSegmentNo = segmentNo;
      this.render();
      const slot = document.getElementById(`segment-player-slot-${segmentNo}`);
      if (!slot) return;
      const player = document.createElement('video');
      player.id = 'segment-player'; player.controls = true; player.preload = 'metadata'; player.playsInline = true;
      player.className = 'segment-video';
      player.setAttribute('aria-label', `片段 ${segmentNo + 1} 播放器`);
      player.dataset.taskId = id; player.dataset.executionNo = String(no);
      player.dataset.originalStartMs = String(playback.originalStartMs);
      player.src = url.href;
      player.addEventListener('loadedmetadata', () => {
        if (!player.isConnected) return;
        player.currentTime = Math.max(0, (originalMs - playback.originalStartMs) / 1000);
        player.play().catch(() => {});
      }, {once: true});
      player.addEventListener('error', () => App.toast('片段播放失败，请重新点击时间获取播放地址', 'error'));
      slot.replaceChildren(player); slot.scrollIntoView({behavior: 'smooth', block: 'nearest'});
    } catch (err) { if (request === this.playRequest) App.toast(err.message || '播放失败', 'error'); }
  },

  /**
   * 重命名任务
   */
  async promptRename() {
    const currentName = this.task.taskName || this.extractFileName(this.task.videoUrl);
    const newName = prompt('请输入新的任务名称：', currentName);
    if (!newName || newName.trim() === '' || newName === currentName) return;
    try {
      this.task = await Api.put(`/task/${this.taskId}/rename`, { taskName: newName.trim() });
      App.toast('重命名成功', 'success');
      this.render();
    } catch (err) {
      App.toast(err.message, 'error');
    }
  },

  /**
   * 用户手动重新分析失败任务
   */
  async confirmRetry() {
    if (!confirm('确定要重新分析此任务吗？')) return;
    try {
      this.task = await Api.post(`/task/${this.taskId}/retry`);
      App.toast('任务已重新提交', 'success');
      this.render();
      this.startPolling();
    } catch (err) {
      App.toast(err.message, 'error');
    }
  },

  /**
   * 删除任务
   */
  async confirmDelete() {
    const name = this.task.taskName || this.extractFileName(this.task.videoUrl);
    if (!confirm(`确定要删除任务「${name}」吗？此操作不可恢复。`)) return;
    try {
      await Api.del(`/task/${this.taskId}`);
      App.toast('任务已删除', 'success');
      this.stopPolling();
      Dashboard._preservePage = true;
      window.location.hash = '#/dashboard';
    } catch (err) {
      App.toast(err.message, 'error');
    }
  },

  /**
   * 渲染 AI 分析结果
   */
  renderResult(resultStr) {
    let result;
    try {
      // 清理可能的 markdown 代码块包裹
      let cleaned = resultStr.trim();
      if (cleaned.startsWith('```')) {
        cleaned = cleaned.replace(/^```(?:json)?\n?/, '').replace(/\n?```$/, '');
      }
      result = JSON.parse(cleaned);
    } catch {
      // JSON 解析失败，用 Markdown 渲染
      const mdHtml = this.renderMarkdown(resultStr);
      return `
        <div class="card result-section">
          <div class="result-section__title">分析结果</div>
          <div class="markdown-body">${mdHtml}</div>
        </div>
      `;
    }

    let html = '';

    // Summary
    if (result.summary) {
      html += `
        <div class="card result-section">
          <div class="result-section__title">内容摘要</div>
          <div class="result-summary">${this.escapeHtml(result.summary)}</div>
        </div>
      `;
    }

    // Tags + Sentiment
    const hasTags = result.tags && result.tags.length > 0;
    const hasSentiment = result.sentiment;
    if (hasTags || hasSentiment) {
      html += '<div class="card result-section"><div class="result-section__title">标签 & 情感</div>';
      if (hasTags) {
        html += '<div class="result-tags">';
        result.tags.forEach(tag => {
          html += `<span class="tag">${this.escapeHtml(tag)}</span>`;
        });
        html += '</div>';
      }
      if (hasSentiment) {
        const sentClass = result.sentiment.includes('正') || result.sentiment.toLowerCase().includes('positive') ? 'positive' :
                          result.sentiment.includes('负') || result.sentiment.toLowerCase().includes('negative') ? 'negative' : 'neutral';
        const sentIcon = sentClass === 'positive' ? '&#9650;' : sentClass === 'negative' ? '&#9660;' : '&#9679;';
        html += `<div style="margin-top:12px">
          <span class="sentiment sentiment--${sentClass}">${sentIcon} ${this.escapeHtml(result.sentiment)}</span>
        </div>`;
      }
      html += '</div>';
    }

    // Scenes
    if (result.scenes && result.scenes.length > 0) {
      html += `
        <div class="card result-section">
          <div class="result-section__title">场景分析 (${result.scenes.length})</div>
          <div class="scene-list">
            ${result.scenes.map(s => `
              <div class="scene-item">
                <div class="scene-item__time">${this.escapeHtml(s.timeRange || '-')}</div>
                <div class="scene-item__desc">${this.escapeHtml(s.description || '')}</div>
                <div class="scene-item__type">${this.escapeHtml(s.type || '')}</div>
              </div>
            `).join('')}
          </div>
        </div>
      `;
    }

    // Keyframes
    if (result.keyframes && result.keyframes.length > 0) {
      html += `
        <div class="card result-section">
          <div class="result-section__title">关键帧 (${result.keyframes.length})</div>
          <div class="keyframe-list">
            ${result.keyframes.map(k => `
              <div class="keyframe-item">
                <div class="keyframe-item__time">${this.escapeHtml(k.time || '-')}</div>
                <div class="keyframe-item__desc">${this.escapeHtml(k.description || '')}</div>
              </div>
            `).join('')}
          </div>
        </div>
      `;
    }

    // Text detected
    if (result.textDetected) {
      html += `
        <div class="card result-section">
          <div class="result-section__title">检测到的文字</div>
          <div class="result-summary">${this.escapeHtml(result.textDetected)}</div>
        </div>
      `;
    }

    // Raw JSON toggle
    html += `
      <div>
        <button class="json-toggle" onclick="TaskDetail.toggleJson()" aria-expanded="false" aria-controls="raw-json">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:4px"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
          查看原始 JSON
        </button>
        <div id="raw-json" class="json-viewer" role="region" aria-label="原始 JSON 数据">${this.escapeHtml(JSON.stringify(result, null, 2))}</div>
      </div>
    `;

    return html;
  },

  /**
   * 切换 JSON 显示
   */
  toggleJson() {
    const el = document.getElementById('raw-json');
    const btn = document.querySelector('.json-toggle');
    if (el) {
      el.classList.toggle('open');
      if (btn) {
        btn.setAttribute('aria-expanded', el.classList.contains('open'));
      }
    }
  },

  // === 工具方法 ===

  getStatusText(status) {
    const map = {
      'PENDING': '等待中', 'RUNNING': '分析中',
      'SUCCEEDED': '已完成', 'PARTIAL': '部分完成', 'FAILED': '失败', 'CANCELLED': '已取消',
    };
    return map[status] || status || '未知';
  },

  extractFileName(videoUrl) {
    if (!videoUrl) return '未知文件';
    const parts = videoUrl.split('/');
    return parts[parts.length - 1] || videoUrl;
  },

  /**
   * 格式化日期为 "yyyy-MM-dd HH:mm"
   * 兼容数组 [2026,5,8,12,34,56] 和字符串 "2026-05-08T12:34:56" 两种格式
   */
  formatDate(raw) {
    if (!raw) return '-';
    try {
      let d;
      if (Array.isArray(raw)) {
        // Jackson write-dates-as-timestamps=true 时返回数组，月份要 -1
        d = new Date(raw[0], raw[1] - 1, raw[2], raw[3] || 0, raw[4] || 0, raw[5] || 0);
      } else {
        d = new Date(String(raw).replace(/-/g, '/').replace('T', ' '));
      }
      if (isNaN(d.getTime())) return String(raw);
      const pad = n => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    } catch {
      return String(raw);
    }
  },

  /**
   * 判断任务是否卡死：非终态 + 超过 10 分钟
   */
  _isStuck(task) {
    const startTime = task.startedAt || task.createdAt;
    if (!startTime) return true;
    try {
      let d;
      if (Array.isArray(startTime)) {
        d = new Date(startTime[0], startTime[1] - 1, startTime[2], startTime[3] || 0, startTime[4] || 0, startTime[5] || 0);
      } else {
        d = new Date(String(startTime).replace(/-/g, '/').replace('T', ' '));
      }
      if (isNaN(d.getTime())) return true;
      return (Date.now() - d.getTime()) > 10 * 60 * 1000;
    } catch {
      return true;
    }
  },

  escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  },

  /**
   * 渲染 Markdown 文本为 HTML
   */
  renderMarkdown(text) {
    if (!text) return '';
    if (typeof marked !== 'undefined') {
      marked.setOptions({ breaks: true, gfm: true });
      return marked.parse(text);
    }
    // marked 未加载时降级为纯文本
    return `<pre style="white-space:pre-wrap">${this.escapeHtml(text)}</pre>`;
  },

  /**
   * 销毁（离开页面时调用）
   */
  destroy() {
    this.loadVersion++;
    this.playRequest++;
    document.getElementById('segment-player')?.pause();
    this.stopPolling();
  },
};

window.TaskDetail = TaskDetail;
