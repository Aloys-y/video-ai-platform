/**
 * Auth Module — 登录/注册
 */

const Auth = {
  /**
   * 初始化认证页面
   */
  init() {
    this.bindTabs();
    this.bindForms();
    this.bindPasswordToggles();
  },

  /**
   * Tab 切换
   */
  bindTabs() {
    const tabs = document.querySelectorAll('.auth-tab');
    tabs.forEach(tab => {
      tab.addEventListener('click', () => {
        const target = tab.dataset.tab;
        tabs.forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        document.querySelectorAll('.auth-form').forEach(f => f.classList.remove('active'));
        document.getElementById(`form-${target}`).classList.add('active');
        this.clearErrors();
      });
    });
  },

  /**
   * 绑定表单提交
   */
  bindForms() {
    const loginForm = document.getElementById('form-login');
    const registerForm = document.getElementById('form-register');

    if (loginForm) {
      loginForm.addEventListener('submit', (e) => {
        e.preventDefault();
        this.handleLogin();
      });
    }

    if (registerForm) {
      registerForm.addEventListener('submit', (e) => {
        e.preventDefault();
        this.handleRegister();
      });
    }

    // Inline validation on blur
    document.querySelectorAll('#form-login .form-input, #form-register .form-input').forEach(input => {
      input.addEventListener('blur', () => {
        this.validateField(input);
      });
      input.addEventListener('input', () => {
        const errorEl = input.parentElement.querySelector('.form-error');
        if (errorEl && errorEl.classList.contains('visible')) {
          this.validateField(input);
        }
      });
    });
  },

  /**
   * 验证单个字段
   */
  validateField(input) {
    const name = input.name;
    const value = input.value.trim();
    let error = '';

    switch (name) {
      case 'email':
        if (!value) error = '请输入邮箱';
        else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) error = '邮箱格式不正确';
        break;
      case 'password':
        if (!value) error = '请输入密码';
        else if (value.length < 6) error = '密码至少 6 位';
        break;
      case 'username':
        if (!value) error = '请输入用户名';
        else if (value.length < 2) error = '用户名至少 2 个字符';
        else if (!/^[a-zA-Z0-9_\u4e00-\u9fa5]+$/.test(value)) error = '仅支持字母、数字、下划线、中文';
        break;
    }

    const errorEl = input.parentElement.querySelector('.form-error');
    if (errorEl) {
      if (error) {
        errorEl.textContent = error;
        errorEl.classList.add('visible');
        input.classList.add('error');
      } else {
        errorEl.classList.remove('visible');
        input.classList.remove('error');
      }
    }
    return !error;
  },

  /**
   * 清除所有错误
   */
  clearErrors() {
    document.querySelectorAll('.form-error').forEach(el => el.classList.remove('visible'));
    document.querySelectorAll('.form-input').forEach(el => el.classList.remove('error'));
  },

  /**
   * 处理登录
   */
  async handleLogin() {
    const email = document.querySelector('#form-login input[name="email"]').value.trim();
    const password = document.querySelector('#form-login input[name="password"]').value;

    // 验证
    const emailInput = document.querySelector('#form-login input[name="email"]');
    const passInput = document.querySelector('#form-login input[name="password"]');
    const valid = this.validateField(emailInput) && this.validateField(passInput);
    if (!valid) return;

    const btn = document.querySelector('#form-login .btn--primary');
    this.setButtonLoading(btn, true);

    try {
      const result = await Api.post('/auth/login', { email, password });
      Api.setToken(result.token);
      Api.setUserInfo({
        userId: result.userId,
        username: result.username,
        role: result.role,
        apiKey: result.apiKey,
      });
      App.toast('登录成功', 'success');
      window.location.hash = '#/dashboard';
    } catch (err) {
      this.showFormError('login', err.message);
    } finally {
      this.setButtonLoading(btn, false);
    }
  },

  /**
   * 处理注册
   */
  async handleRegister() {
    const username = document.querySelector('#form-register input[name="username"]').value.trim();
    const email = document.querySelector('#form-register input[name="email"]').value.trim();
    const password = document.querySelector('#form-register input[name="password"]').value;

    // 验证
    const inputs = document.querySelectorAll('#form-register .form-input');
    let valid = true;
    inputs.forEach(input => {
      if (!this.validateField(input)) valid = false;
    });
    if (!valid) return;

    const btn = document.querySelector('#form-register .btn--primary');
    this.setButtonLoading(btn, true);

    try {
      const result = await Api.post('/auth/register', { username, email, password });
      Api.setToken(result.token);
      Api.setUserInfo({
        userId: result.userId,
        username: result.username,
        role: result.role,
        apiKey: result.apiKey,
      });
      App.toast('注册成功', 'success');
      window.location.hash = '#/dashboard';
    } catch (err) {
      this.showFormError('register', err.message);
    } finally {
      this.setButtonLoading(btn, false);
    }
  },

  /**
   * 显示表单级别错误
   */
  showFormError(form, message) {
    const errorEl = document.querySelector(`#form-${form} .form-error--general`);
    if (errorEl) {
      errorEl.textContent = message;
      errorEl.classList.add('visible');
      setTimeout(() => errorEl.classList.remove('visible'), 5000);
    }
  },

  /**
   * 密码显示/隐藏切换
   */
  bindPasswordToggles() {
    document.querySelectorAll('.form-input__toggle').forEach(btn => {
      btn.addEventListener('click', () => {
        const input = document.getElementById(btn.dataset.target);
        if (!input) return;
        const isPassword = input.type === 'password';
        input.type = isPassword ? 'text' : 'password';
        const eyeOpen = btn.querySelector('.eye-open');
        const eyeClosed = btn.querySelector('.eye-closed');
        if (eyeOpen && eyeClosed) {
          eyeOpen.style.display = isPassword ? 'none' : '';
          eyeClosed.style.display = isPassword ? '' : 'none';
        }
      });
    });
  },

  /**
   * 按钮加载状态
   */
  setButtonLoading(btn, loading) {
    if (!btn) return;
    if (loading) {
      btn.classList.add('btn--loading');
      btn.disabled = true;
    } else {
      btn.classList.remove('btn--loading');
      btn.disabled = false;
    }
  },
};

window.Auth = Auth;
