/**
 * Dashboard Module — 任务列表
 */

const Dashboard = {
  state: {
    page: 1,
    size: 20,
    total: 0,
    tasks: [],
    loading: false,
  },

  /**
   * 初始化仪表盘
   */
  init() {
    this.state = { page: 1, size: 20, total: 0, tasks: [], loading: false };
    this.loadTasks();
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
   */
  renderTaskCard(task) {
    const statusClass = task.status ? task.status.toLowerCase() : 'pending';
    const statusText = this.getStatusText(task.status);
    const progress = task.progress || 0;
    const fileName = this.extractFileName(task.videoUrl);
    const time = task.createdAt ? this.formatTime(task.createdAt) : '';

    return `
      <div class="card task-card" onclick="window.location.hash='#/task/${task.taskId}'" role="button" tabindex="0" aria-label="查看任务 ${fileName}">
        <div class="task-card__info">
          <div class="task-card__name">${this.escapeHtml(fileName)}</div>
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

window.Dashboard = Dashboard;
