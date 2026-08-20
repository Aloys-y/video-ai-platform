const App = {
  currentPage: null,
  _navigating: false,

  init() {
    this.bindRouter();
    this.renderNavbar();
    this.navigate(window.location.hash || '#/login');
  },

  bindRouter() {
    window.addEventListener('hashchange', () => {
      if (!this._navigating) {
        this.navigate(window.location.hash);
      }
    });
  },

  navigate(hash) {
    if (this._navigating) return;
    this._navigating = true;
    try {
      this._doNavigate(hash);
    } finally {
      setTimeout(() => {
        this._navigating = false;
      }, 0);
    }
  },

  _doNavigate(hash) {
    const path = (hash || '#/login').replace('#', '');
    const publicRoutes = ['/login', '/register'];

    if (!publicRoutes.includes(path) && !Api.isLoggedIn()) {
      window.location.hash = '#/login';
      return;
    }

    if (publicRoutes.includes(path) && Api.isLoggedIn()) {
      window.location.hash = '#/dashboard';
      return;
    }

    if (this.currentPage === 'task-detail') {
      TaskDetail.destroy();
    }
    if (this.currentPage === 'upload') {
      Upload.destroy();
    }
    if (this.currentPage === 'rag-import') {
      RagImport.destroy();
    }
    if (this.currentPage === 'rag-cards') {
      RagCards.destroy();
    }
    if (this.currentPage === 'rag-eval') {
      RagEval.destroy();
    }

    document.querySelectorAll('.page-section').forEach(section => section.classList.remove('active'));

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
    } else if (path === '/rag-import') {
      pageId = 'page-rag-import';
      RagImport.init();
    } else if (path === '/rag-cards') {
      pageId = 'page-rag-cards';
      RagCards.init();
    } else if (path === '/rag-eval') {
      pageId = 'page-rag-eval';
      RagEval.init();
    } else if (path.startsWith('/task/')) {
      const taskId = path.replace('/task/', '');
      pageId = 'page-task-detail';
      TaskDetail.init(taskId);
    } else {
      window.location.hash = Api.isLoggedIn() ? '#/dashboard' : '#/login';
      return;
    }

    this.currentPage =
      pageId === 'page-auth' ? 'auth' :
      pageId === 'page-dashboard' ? 'dashboard' :
      pageId === 'page-upload' ? 'upload' :
      pageId === 'page-rag-import' ? 'rag-import' :
      pageId === 'page-rag-cards' ? 'rag-cards' :
      pageId === 'page-rag-eval' ? 'rag-eval' :
      pageId === 'page-task-detail' ? 'task-detail' : null;

    const page = document.getElementById(pageId);
    if (page) page.classList.add('active');

    this.renderNavbar();
    this.updateActiveNav(path);

    const main = document.querySelector('.main-content');
    if (main) main.focus();

    this.closeMobileMenu();
  },

  initAuthPage(path) {
    const tabName = path === '/register' ? 'register' : 'login';
    document.querySelectorAll('.auth-tab').forEach(tab => {
      tab.classList.toggle('active', tab.dataset.tab === tabName);
    });
    document.querySelectorAll('.auth-form').forEach(form => {
      form.classList.toggle('active', form.id === `form-${tabName}`);
    });
    Auth.init();
  },

  renderNavbar() {
    const nav = document.getElementById('navbar');
    if (!nav) return;

    const loggedIn = Api.isLoggedIn();
    const user = Api.getUserInfo();
    const isAdmin = !!user && user.role === 'ADMIN';

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
      <button class="navbar__hamburger" aria-label="菜单" onclick="App.toggleMobileMenu()">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="3" y1="6" x2="21" y2="6"/>
          <line x1="3" y1="12" x2="21" y2="12"/>
          <line x1="3" y1="18" x2="21" y2="18"/>
        </svg>
      </button>
      <div class="navbar__links">
        <a href="#/dashboard" class="navbar__link" data-nav="/dashboard">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px;vertical-align:middle">
            <rect x="3" y="3" width="7" height="7"/>
            <rect x="14" y="3" width="7" height="7"/>
            <rect x="3" y="14" width="7" height="7"/>
            <rect x="14" y="14" width="7" height="7"/>
          </svg>
          任务
        </a>
        <a href="#/upload" class="navbar__link" data-nav="/upload">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px;vertical-align:middle">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="17 8 12 3 7 8"/>
            <line x1="12" y1="3" x2="12" y2="15"/>
          </svg>
          上传视频
        </a>
        ${isAdmin ? `
          <a href="#/rag-eval" class="navbar__link" data-nav="/rag-eval">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px;vertical-align:middle">
              <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            RAG 评估
          </a>
          <a href="#/rag-cards" class="navbar__link" data-nav="/rag-cards">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px;vertical-align:middle">
              <rect x="3" y="3" width="18" height="18" rx="2"/>
              <line x1="8" y1="8" x2="16" y2="8"/>
              <line x1="8" y1="12" x2="16" y2="12"/>
              <line x1="8" y1="16" x2="13" y2="16"/>
            </svg>
            RAG 卡片
          </a>
          <a href="#/rag-import" class="navbar__link" data-nav="/rag-import">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px;vertical-align:middle">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
              <polyline points="14 2 14 8 20 8"/>
              <line x1="8" y1="13" x2="16" y2="13"/>
              <line x1="8" y1="17" x2="13" y2="17"/>
            </svg>
            RAG 导入
          </a>
        ` : ''}
      </div>
      <div class="navbar__user">
        ${user ? this.escapeHtml(user.username) : ''}
        ${user && user.apiKey ? `<br><span style="font-size:0.7rem;color:var(--text-tertiary)">${this.escapeHtml(user.apiKey)}</span>` : ''}
      </div>
      <button class="navbar__logout" onclick="App.logout()" aria-label="退出登录">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
          <polyline points="16 17 21 12 16 7"/>
          <line x1="21" y1="12" x2="9" y2="12"/>
        </svg>
      </button>
    `;
  },

  updateActiveNav(path) {
    document.querySelectorAll('.navbar__link').forEach(link => {
      link.classList.toggle('active', link.dataset.nav === path);
    });
  },

  logout() {
    Api.clearToken();
    this.toast('已退出登录', 'info');
    window.location.hash = '#/login';
  },

  toggleMobileMenu() {
    const nav = document.getElementById('navbar');
    if (nav) nav.classList.toggle('menu-open');
  },

  closeMobileMenu() {
    const nav = document.getElementById('navbar');
    if (nav) nav.classList.remove('menu-open');
  },

  toast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

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
    div.textContent = str == null ? '' : String(str);
    return div.innerHTML;
  },
};

document.addEventListener('DOMContentLoaded', () => App.init());
window.App = App;
