/**
 * API Client — HTTP 封装
 *
 * 封装 fetch，自动注入 JWT、处理错误、解析响应
 */

// 本地开发直连后端 8080，Nginx 部署时改为 '/api'
const API_BASE = window.location.port === '3000'
  ? 'http://localhost:8080/api'
  : '/api';

const Api = {
  /**
   * 获取存储的 JWT token
   */
  getToken() {
    return localStorage.getItem('jwt_token');
  },

  /**
   * 存储 JWT token
   */
  setToken(token) {
    localStorage.setItem('jwt_token', token);
  },

  /**
   * 清除 token（登出）
   */
  clearToken() {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('user_info');
  },

  /**
   * 存储用户信息
   */
  setUserInfo(info) {
    localStorage.setItem('user_info', JSON.stringify(info));
  },

  /**
   * 获取用户信息
   */
  getUserInfo() {
    try {
      return JSON.parse(localStorage.getItem('user_info'));
    } catch {
      return null;
    }
  },

  /**
   * 检查是否已登录
   */
  isLoggedIn() {
    return !!this.getToken();
  },

  /**
   * 通用请求方法
   */
  async request(method, path, options = {}) {
    const url = `${API_BASE}${path}`;
    const headers = {
      ...options.headers,
    };

    // 注入 JWT
    const token = this.getToken();
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    const config = {
      method,
      headers,
    };

    // JSON body
    if (options.body && !(options.body instanceof FormData)) {
      headers['Content-Type'] = 'application/json';
      config.body = JSON.stringify(options.body);
    } else if (options.body instanceof FormData) {
      config.body = options.body;
    }

    try {
      const response = await fetch(url, config);
      const data = await response.json();

      if (!response.ok || !data.success) {
        // 401 且非认证接口 → 跳转登录
        const isAuthEndpoint = path.startsWith('/auth/');
        if (response.status === 401 && !isAuthEndpoint) {
          this.clearToken();
          window.location.hash = '#/login';
          throw new Error('登录已过期，请重新登录');
        }
        throw new Error(data.message || `请求失败 (${data.code || response.status})`);
      }

      return data.data;
    } catch (err) {
      if (err.name === 'TypeError' && err.message.includes('fetch')) {
        throw new Error('网络连接失败，请检查网络');
      }
      throw err;
    }
  },

  // === 便捷方法 ===

  get(path, params) {
    let url = path;
    if (params) {
      const qs = new URLSearchParams(params).toString();
      url += `?${qs}`;
    }
    return this.request('GET', url);
  },

  post(path, body) {
    return this.request('POST', path, { body });
  },

  put(path, body) {
    return this.request('PUT', path, { body });
  },

  del(path) {
    return this.request('DELETE', path);
  },

  // === 文件上传（分片）专用 ===

  /**
   * 上传单个分片
   */
  async uploadChunk(uploadId, chunkIndex, chunkBlob) {
    const formData = new FormData();
    formData.append('file', chunkBlob);

    const url = `${API_BASE}/upload/chunk`;
    const headers = {};

    const token = this.getToken();
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    headers['X-Upload-Id'] = uploadId;
    headers['X-Chunk-Index'] = String(chunkIndex);

    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: formData,
    });

    const data = await response.json();
    if (!response.ok || !data.success) {
      if (response.status === 401) {
        this.clearToken();
        window.location.hash = '#/login';
        throw new Error(data.message || '登录已过期');
      }
      throw new Error(data.message || '分片上传失败');
    }
    return data.data;
  },
};

// 全局暴露
window.Api = Api;
