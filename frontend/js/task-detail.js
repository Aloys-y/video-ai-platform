/**
 * TaskDetail Module — 任务详情 + AI 分析结果展示
 */

const TaskDetail = {
  taskId: null,
  task: null,
  pollTimer: null,

  /**
   * 初始化任务详情
   */
  init(taskId) {
    this.taskId = taskId;
    this.task = null;
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
    try {
      this.task = await Api.get(`/task/${this.taskId}`);
      this.render();

      // 非终态 → 自动轮询
      if (!this.isFinalState(this.task.status)) {
        this.startPolling();
      }
    } catch (err) {
      document.getElementById('task-detail-content').innerHTML = `
        <div class="empty-state">
          <div class="empty-state__title">加载失败</div>
          <div class="empty-state__desc">${err.message}</div>
          <div style="display:flex;gap:12px;justify-content:center;margin-top:16px">
            <button class="btn btn--ghost btn--small" onclick="TaskDetail.loadTask()">重试</button>
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
    return ['COMPLETED', 'FAILED', 'DEAD', 'CANCELLED'].includes(status);
  },

  /**
   * 开始轮询
   */
  startPolling() {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      this.loadTask();
    }, 3000);
  },

  /**
   * 停止轮询
   */
  stopPolling() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
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
    const canRetry = task.status === 'FAILED' || task.status === 'DEAD';
    const canDelete = isFinal;

    let resultHtml = '';
    if (task.status === 'COMPLETED' && task.result) {
      resultHtml = this.renderResult(task.result);
    } else if (!isFinal) {
      resultHtml = `
        <div class="card result-section" style="text-align:center;padding:48px 24px">
          <div class="badge badge--${statusClass}" style="margin-bottom:16px">${statusText}</div>
          <div class="hud-title" style="margin-bottom:8px">分析进行中</div>
          <div class="progress progress--large" style="max-width:400px;margin:16px auto">
            <div class="progress__bar" style="width:${task.progress || 0}%"></div>
          </div>
          <div class="progress__label" style="justify-content:center">
            <span>进度</span><span>${task.progress || 0}%</span>
          </div>
        </div>
      `;
    } else if (task.status === 'FAILED' || task.status === 'DEAD') {
      resultHtml = `
        <div class="card result-section">
          <div class="badge badge--${statusClass}" style="margin-bottom:16px">${statusText}</div>
          <div class="result-section__title">错误信息</div>
          <p style="color:var(--accent-red)">${this.escapeHtml(task.errorMessage || '未知错误')}</p>
          ${canRetry ? `<button class="btn btn--primary btn--small mt-lg" onclick="TaskDetail.confirmRetry()">重新分析</button>` : ''}
        </div>
      `;
    }

    container.innerHTML = `
      <div class="task-detail">
        <div class="task-sidebar">
          <div class="card task-sidebar__section">
            <div class="task-sidebar__label">任务 ID</div>
            <div class="task-sidebar__value" style="font-size:0.8rem;word-break:break-all">${task.taskId}</div>
          </div>
          <div class="card task-sidebar__section">
            <div class="task-sidebar__label">文件</div>
            <div class="task-sidebar__value">${this.escapeHtml(displayName)}</div>
          </div>
          <div class="card task-sidebar__section">
            <div class="task-sidebar__label">状态</div>
            <div><span class="badge badge--${statusClass}">${statusText}</span></div>
          </div>
          <div class="card task-sidebar__section">
            <div class="task-sidebar__label">创建时间</div>
            <div class="task-sidebar__value">${task.createdAt || '-'}</div>
          </div>
          ${task.startedAt ? `
          <div class="card task-sidebar__section">
            <div class="task-sidebar__label">开始时间</div>
            <div class="task-sidebar__value">${task.startedAt}</div>
          </div>` : ''}
          ${task.completedAt ? `
          <div class="card task-sidebar__section">
            <div class="task-sidebar__label">完成时间</div>
            <div class="task-sidebar__value">${task.completedAt}</div>
          </div>` : ''}
          ${task.retryCount > 0 ? `
          <div class="card task-sidebar__section">
            <div class="task-sidebar__label">重试次数</div>
            <div class="task-sidebar__value">${task.retryCount} / ${task.maxRetry}</div>
          </div>` : ''}
          <div class="card task-sidebar__section">
            <div class="task-sidebar__label">上传 ID</div>
            <div class="task-sidebar__value" style="font-size:0.8rem">${task.uploadId || '-'}</div>
          </div>

          <!-- 操作按钮 -->
          <div class="card task-sidebar__section task-sidebar__actions">
            <button class="btn btn--ghost btn--small"
                    onclick="TaskDetail.promptRename()">
              重命名
            </button>
            ${canRetry ? `
            <button class="btn btn--primary btn--small"
                    onclick="TaskDetail.confirmRetry()">
              重新分析
            </button>` : ''}
            ${canDelete ? `
            <button class="btn btn--ghost btn--small" style="color:var(--accent-red);border-color:var(--accent-red)"
                    onclick="TaskDetail.confirmDelete()">
              删除任务
            </button>` : ''}
          </div>
        </div>

        <div class="task-result">
          ${resultHtml}
        </div>
      </div>
    `;
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
   * 重试任务
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
      // JSON 解析失败，直接显示原文
      return `
        <div class="card result-section">
          <div class="result-section__title">分析结果</div>
          <div class="result-summary" style="white-space:pre-wrap">${this.escapeHtml(resultStr)}</div>
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
      'PENDING': '等待中', 'QUEUED': '排队中', 'PROCESSING': '分析中',
      'COMPLETED': '已完成', 'FAILED': '失败', 'RETRYING': '重试中',
      'DEAD': '已终止', 'CANCELLED': '已取消',
    };
    return map[status] || status || '未知';
  },

  extractFileName(videoUrl) {
    if (!videoUrl) return '未知文件';
    const parts = videoUrl.split('/');
    return parts[parts.length - 1] || videoUrl;
  },

  escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  },

  /**
   * 销毁（离开页面时调用）
   */
  destroy() {
    this.stopPolling();
  },
};

window.TaskDetail = TaskDetail;
