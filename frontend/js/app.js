/**
 * App — 路由 + 导航 + 初始化
 *
 * Hash-based SPA 路由器
 */

const App = {
  currentPage: null,

  /**
   * 初始化应用
   */
  init() {
    this.bindRouter();
    this.renderNavbar();
    this.navigate(window.location.hash || '#/login');
  },

  /**
   * 绑定路由监听
   */
  bindRouter() {
    window.addEventListener('hashchange', () => {
      this.navigate(window.location.hash);
    });
  },

  /**
   * 路由分发
   */
  navigate(hash) {
    const path = (hash || '#/login').replace('#', '');

    // 认证守卫
    const publicRoutes = ['/login', '/register'];
    if (!publicRoutes.includes(path) && !Api.isLoggedIn()) {
      window.location.hash = '#/login';
      return;
    }

    // 已登录用户访问登录页 → 跳转 dashboard
    if (publicRoutes.includes(path) && Api.isLoggedIn()) {
      window.location.hash = '#/dashboard';
      return;
    }

    // 销毁旧页面
    if (this.currentPage === 'task-detail') {
      TaskDetail.destroy();
    }
    if (this.currentPage === 'upload') {
      Upload.destroy();
    }

    // 隐藏所有页面
    document.querySelectorAll('.page-section').forEach(s => s.classList.remove('active'));

    // 路由匹配
    let pageId = null;
    if (path === '/login' || path === '/register') {
      pageId = 'page-auth';
      this.initAuthPage(path);
    } else if (path === '/dashboard') {
      pageId = 'page-dashboard';
      Dashboard.init();
    } else if (path === '/upload') {
      pageId = 'page-upload';
      Upload.init();
    } else if (path.startsWith('/task/')) {
      const taskId = path.replace('/task/', '');
      pageId = 'page-task-detail';
      TaskDetail.init(taskId);
    } else {
      // 未知路由 → dashboard 或 login
      window.location.hash = Api.isLoggedIn() ? '#/dashboard' : '#/login';
      return;
    }

    this.currentPage = pageId === 'page-auth' ? 'auth' :
                       pageId === 'page-dashboard' ? 'dashboard' :
                       pageId === 'page-upload' ? 'upload' :
                       pageId === 'page-task-detail' ? 'task-detail' : null;

    // 显示目标页面
    const page = document.getElementById(pageId);
    if (page) page.classList.add('active');

    // 更新导航栏
    this.renderNavbar();
    this.updateActiveNav(path);

    // 聚焦主内容区域（可访问性）
    const main = document.querySelector('.main-content');
    if (main) main.focus();
  },

  /**
   * 初始化认证页面
   */
  initAuthPage(path) {
    // 切换到对应的 tab
    const tabName = path === '/register' ? 'register' : 'login';
    document.querySelectorAll('.auth-tab').forEach(t => {
      t.classList.toggle('active', t.dataset.tab === tabName);
    });
    document.querySelectorAll('.auth-form').forEach(f => {
      f.classList.toggle('active', f.id === `form-${tabName}`);
    });
    Auth.init();
  },

  /**
   * 渲染导航栏
   */
  renderNavbar() {
    const nav = document.getElementById('navbar');
    if (!nav) return;

    const loggedIn = Api.isLoggedIn();
    const user = Api.getUserInfo();

    if (!loggedIn) {
      nav.innerHTML = `
        <div class="navbar__logo">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="var(--accent-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="23 7 16 12 23 17 23 7"/>
            <rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>
          </svg>
          Do<span>Video</span>AI
        </div>
      `;
      return;
    }

    nav.innerHTML = `
      <div class="navbar__logo">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="var(--accent-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polygon points="23 7 16 12 23 17 23 7"/>
          <rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>
        </svg>
        Do<span>Video</span>AI
      </div>
      <div class="navbar__links">
        <a href="#/dashboard" class="navbar__link" data-nav="/dashboard">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px;vertical-align:middle"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>
          任务
        </a>
        <a href="#/upload" class="navbar__link" data-nav="/upload">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px;vertical-align:middle"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
          上传
        </a>
      </div>
      <div class="navbar__user">
        ${user ? this.escapeHtml(user.username) : ''}
        ${user && user.apiKey ? `<br><span style="font-size:0.7rem;color:var(--text-tertiary)">${this.escapeHtml(user.apiKey)}</span>` : ''}
      </div>
      <button class="navbar__logout" onclick="App.logout()" aria-label="退出登录">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
      </button>
    `;
  },

  /**
   * 更新导航栏激活状态
   */
  updateActiveNav(path) {
    document.querySelectorAll('.navbar__link').forEach(link => {
      const nav = link.dataset.nav;
      link.classList.toggle('active', nav === path);
    });
  },

  /**
   * 登出
   */
  logout() {
    Api.clearToken();
    this.toast('已退出登录', 'info');
    window.location.hash = '#/login';
  },

  /**
   * Toast 通知
   */
  toast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    // M-12: 最多同时显示 3 个 toast
    while (container.children.length >= 3) {
      container.firstChild.remove();
    }

    const toast = document.createElement('div');
    toast.className = `toast toast--${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transform = 'translateX(40px)';
      toast.style.transition = 'all 250ms ease-out';
      setTimeout(() => toast.remove(), 300);
    }, 4000);
  },

  escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  },
};

// 启动
document.addEventListener('DOMContentLoaded', () => App.init());

window.App = App;
