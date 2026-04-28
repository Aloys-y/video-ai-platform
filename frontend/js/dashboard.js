/**
 * Dashboard Module — 任务列表
 */

const Dashboard = {
  _menuListenerBound: false,

  state: {
    page: 1,
    size: 20,
    total: 0,
    tasks: [],
    loading: false,
  },

  /** 保留上次页码，从详情页返回时不重置 */
  _preservePage: false,

  /**
   * 初始化仪表盘
   */
  init() {
    if (!this._preservePage) {
      this.state = { page: 1, size: 20, total: 0, tasks: [], loading: false };
    }
    this._preservePage = false;
    this.loadTasks();

    // H-02 fix: 全局 click 监听只绑定一次
    if (!this._menuListenerBound) {
      document.addEventListener('click', () => {
        document.querySelectorAll('.task-card__menu').forEach(m => m.style.display = 'none');
      });
      this._menuListenerBound = true;
    }
  },

  /**
   * 加载任务列表
   */
  async loadTasks() {
    this.state.loading = true;
    this.renderLoading();

    try {
      const result = await Api.get('/task/list', {
        page: this.state.page,
        size: this.state.size,
      });

      // MyBatis-Plus Page 结构
      this.state.tasks = result.records || [];
      this.state.total = result.total || 0;
      this.render();
    } catch (err) {
      document.getElementById('task-list').innerHTML = `
        <div class="empty-state">
          <div class="empty-state__title">加载失败</div>
          <div class="empty-state__desc">${err.message}</div>
          <button class="btn btn--ghost btn--small" onclick="Dashboard.loadTasks()">重试</button>
        </div>
      `;
    } finally {
      this.state.loading = false;
    }
  },

  /**
   * 渲染任务列表
   */
  render() {
    const container = document.getElementById('task-list');
    if (!container) return;

    if (this.state.tasks.length === 0) {
      container.innerHTML = `
        <div class="empty-state">
          <svg class="empty-state__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M17 8l-5-5-5 5M12 3v12" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <div class="empty-state__title">暂无分析任务</div>
          <div class="empty-state__desc">上传一个视频开始 AI 分析</div>
          <a href="#/upload" class="btn btn--primary">上传视频</a>
        </div>
      `;
      return;
    }

    container.innerHTML = '<div class="task-grid">' +
      this.state.tasks.map(task => this.renderTaskCard(task)).join('') +
      '</div>';

    this.renderPagination();
  },

  /**
   * 渲染单个任务卡片
   * H-01 fix: 使用 data-* 属性传递参数，避免 onclick 拼接用户输入
   */
  renderTaskCard(task) {
    const statusClass = task.status ? task.status.toLowerCase() : 'pending';
    const statusText = this.getStatusText(task.status);
    const progress = task.progress || 0;
    const displayName = task.taskName || this.extractFileName(task.videoUrl);
    const time = task.createdAt ? this.formatTime(task.createdAt) : '';
    const canRetry = task.status === 'FAILED' || task.status === 'DEAD';
    const canDelete = this.isFinalState(task.status);

    return `
      <div class="card task-card" data-task-id="${task.taskId}" onclick="window.location.hash='#/task/${task.taskId}'" role="button" tabindex="0" aria-label="查看任务 ${this.escapeHtml(displayName)}">
        <div class="task-card__info">
          <div class="task-card__header">
            <div class="task-card__name">${this.escapeHtml(displayName)}</div>
            <button class="task-card__menu-btn" data-action="toggle-menu" data-task-id="${task.taskId}" aria-label="操作菜单">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
            </button>
          </div>
          <div class="task-card__menu" id="menu-${task.taskId}" style="display:none" onclick="event.stopPropagation()">
            <button class="menu-item" data-action="rename" data-task-id="${task.taskId}" data-display-name="${this.escapeHtml(displayName)}">重命名</button>
            ${canRetry ? `<button class="menu-item" data-action="retry" data-task-id="${task.taskId}">重新分析</button>` : ''}
            ${canDelete ? `<button class="menu-item menu-item--danger" data-action="delete" data-task-id="${task.taskId}" data-display-name="${this.escapeHtml(displayName)}">删除</button>` : ''}
          </div>
          <div class="task-card__meta">
            <span class="badge badge--${statusClass}">${statusText}</span>
            ${task.retryCount > 0 ? `<span class="text-muted" style="margin-left:8px">重试 ${task.retryCount}/${task.maxRetry}</span>` : ''}
          </div>
        </div>
        <div class="task-card__progress">
          <div class="progress">
            <div class="progress__bar" style="width:${progress}%"></div>
          </div>
          <div class="progress__label">
            <span>进度</span>
            <span>${progress}%</span>
          </div>
        </div>
        <div class="task-card__time">${time}</div>
      </div>
    `;
  },

  /**
   * H-03 fix: 带 loading 状态的异步操作
   */
  async _withLoading(btn, asyncFn) {
    if (btn.disabled) return;
    btn.disabled = true;
    btn.dataset.originalText = btn.textContent;
    btn.textContent = '处理中...';
    try {
      await asyncFn();
    } finally {
      btn.disabled = false;
      btn.textContent = btn.dataset.originalText;
    }
  },

  /**
   * 重命名任务
   */
  async promptRename(taskId, currentName) {
    const newName = prompt('请输入新的任务名称：', currentName);
    if (!newName || newName.trim() === '' || newName === currentName) return;
    try {
      await Api.put(`/task/${taskId}/rename`, { taskName: newName.trim() });
      App.toast('重命名成功', 'success');
      this.loadTasks();
    } catch (err) {
      App.toast(err.message, 'error');
    }
  },

  /**
   * 重试任务
   */
  async confirmRetry(taskId) {
    if (!confirm('确定要重新分析此任务吗？')) return;
    try {
      await Api.post(`/task/${taskId}/retry`);
      App.toast('任务已重新提交', 'success');
      this.loadTasks();
    } catch (err) {
      App.toast(err.message, 'error');
    }
  },

  /**
   * 删除任务
   */
  async confirmDelete(taskId, name) {
    if (!confirm(`确定要删除任务「${name}」吗？此操作不可恢复。`)) return;
    try {
      await Api.del(`/task/${taskId}`);
      App.toast('任务已删除', 'success');
      this.loadTasks();
    } catch (err) {
      App.toast(err.message, 'error');
    }
  },

  /**
   * 渲染骨架屏
   */
  renderLoading() {
    const container = document.getElementById('task-list');
    if (!container) return;

    let html = '<div class="task-grid">';
    for (let i = 0; i < 5; i++) {
      html += `
        <div class="card task-card" style="pointer-events:none">
          <div class="task-card__info">
            <div class="skeleton" style="height:18px;width:200px;margin-bottom:8px"></div>
            <div class="skeleton" style="height:14px;width:120px"></div>
          </div>
          <div class="task-card__progress">
            <div class="skeleton" style="height:6px;width:100%"></div>
          </div>
          <div class="task-card__time">
            <div class="skeleton" style="height:14px;width:80px"></div>
          </div>
        </div>
      `;
    }
    html += '</div>';
    container.innerHTML = html;
  },

  /**
   * 渲染分页
   */
  renderPagination() {
    const container = document.getElementById('pagination');
    if (!container) return;

    const totalPages = Math.ceil(this.state.total / this.state.size);
    if (totalPages <= 1) {
      container.innerHTML = '';
      return;
    }

    container.innerHTML = `
      <div class="pagination">
        <button class="pagination__btn" ${this.state.page <= 1 ? 'disabled' : ''} onclick="Dashboard.goPage(${this.state.page - 1})" aria-label="上一页">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 18l-6-6 6-6"/></svg>
        </button>
        <span class="pagination__info">${this.state.page} / ${totalPages}</span>
        <button class="pagination__btn" ${this.state.page >= totalPages ? 'disabled' : ''} onclick="Dashboard.goPage(${this.state.page + 1})" aria-label="下一页">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18l6-6-6-6"/></svg>
        </button>
      </div>
    `;
  },

  /**
   * 跳转页码
   */
  goPage(page) {
    this.state.page = page;
    this.loadTasks();
  },

  // === 工具方法 ===

  isFinalState(status) {
    return ['COMPLETED', 'FAILED', 'DEAD', 'CANCELLED'].includes(status);
  },

  getStatusText(status) {
    const map = {
      'PENDING': '等待中',
      'QUEUED': '排队中',
      'PROCESSING': '分析中',
      'COMPLETED': '已完成',
      'FAILED': '失败',
      'RETRYING': '重试中',
      'DEAD': '已终止',
      'CANCELLED': '已取消',
    };
    return map[status] || status || '未知';
  },

  extractFileName(videoUrl) {
    if (!videoUrl) return '未知文件';
    const parts = videoUrl.split('/');
    return parts[parts.length - 1] || videoUrl;
  },

  formatTime(timeStr) {
    if (!timeStr) return '';
    try {
      const d = new Date(timeStr.replace(/-/g, '/'));
      const now = new Date();
      const diff = now - d;
      if (diff < 60000) return '刚刚';
      if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`;
      if (diff < 86400000) return `${Math.floor(diff / 3600000)} 小时前`;
      if (diff < 604800000) return `${Math.floor(diff / 86400000)} 天前`;
      return `${d.getMonth() + 1}/${d.getDate()}`;
    } catch {
      return timeStr;
    }
  },

  escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  },
};

// M-03: Task card 键盘导航 (Enter/Space)
document.addEventListener('keydown', (e) => {
  if ((e.key === 'Enter' || e.key === ' ') && e.target.classList.contains('task-card')) {
    e.preventDefault();
    const taskId = e.target.dataset.taskId;
    if (taskId) window.location.hash = `#/task/${taskId}`;
  }
});

// H-01 fix: 事件委托，通过 data-action 分发，避免 onclick 拼接用户输入
document.addEventListener('click', (e) => {
  const target = e.target.closest('[data-action]');
  if (!target) return;

  const action = target.dataset.action;
  const taskId = target.dataset.taskId;
  const displayName = target.dataset.displayName || '';

  if (action === 'toggle-menu') {
    e.stopPropagation();
    Dashboard.toggleMenu(taskId);
  } else if (action === 'rename') {
    e.stopPropagation();
    Dashboard._withLoading(target, () => Dashboard.promptRename(taskId, displayName));
  } else if (action === 'retry') {
    e.stopPropagation();
    Dashboard._withLoading(target, () => Dashboard.confirmRetry(taskId));
  } else if (action === 'delete') {
    e.stopPropagation();
    Dashboard._withLoading(target, () => Dashboard.confirmDelete(taskId, displayName));
  }
});

window.Dashboard = Dashboard;
