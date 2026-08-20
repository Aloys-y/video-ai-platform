/**
 * API Client - HTTP wrapper
 */

const isLocalHost = ['localhost', '127.0.0.1'].includes(window.location.hostname);
const API_BASE = isLocalHost && window.location.port !== '8080'
  ? 'http://localhost:8080/api'
  : '/api';

const Api = {
  getToken() {
    return localStorage.getItem('jwt_token');
  },

  setToken(token) {
    localStorage.setItem('jwt_token', token);
  },

  clearToken() {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('user_info');
  },

  setUserInfo(info) {
    localStorage.setItem('user_info', JSON.stringify(info));
  },

  getUserInfo() {
    try {
      return JSON.parse(localStorage.getItem('user_info'));
    } catch {
      return null;
    }
  },

  isLoggedIn() {
    return !!this.getToken();
  },

  async request(method, path, options = {}) {
    const url = `${API_BASE}${path}`;
    const headers = {
      ...options.headers,
    };

    const token = this.getToken();
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), options.timeout || 30000);
    let externalAbortHandler = null;

    if (options.signal) {
      if (options.signal.aborted) {
        controller.abort();
      } else {
        externalAbortHandler = () => controller.abort();
        options.signal.addEventListener('abort', externalAbortHandler, { once: true });
      }
    }

    const config = {
      method,
      headers,
      signal: controller.signal,
    };

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
        const isAuthEndpoint = path.startsWith('/auth/');
        if (response.status === 401 && !isAuthEndpoint) {
          this.clearToken();
          window.location.hash = '#/login';
          throw new Error('登录已过期，请重新登录');
        }
        throw new Error(this.getErrorMessage(data.code, data.message));
      }

      return data.data;
    } catch (err) {
      if (err.name === 'AbortError') {
        throw new Error(options.signal?.aborted ? '请求已取消' : '请求超时，请稍后重试');
      }
      if (err.name === 'TypeError' && err.message.includes('fetch')) {
        throw new Error('网络连接失败，请检查网络');
      }
      throw err;
    } finally {
      clearTimeout(timeoutId);
      if (options.signal && externalAbortHandler) {
        options.signal.removeEventListener('abort', externalAbortHandler);
      }
    }
  },

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

  async uploadChunk(uploadId, chunkIndex, chunkBlob, options = {}) {
    const formData = new FormData();
    formData.append('file', chunkBlob);

    return this.request('POST', '/upload/chunk', {
      body: formData,
      timeout: options.timeout || 60000,
      signal: options.signal,
      headers: {
        'X-Upload-Id': uploadId,
        'X-Chunk-Index': String(chunkIndex),
      },
    });
  },

  async importKnowledgeMarkdown(files, options = {}) {
    const formData = new FormData();
    files.forEach(file => formData.append('files', file));

    if (options.defaultCategory) {
      formData.append('defaultCategory', options.defaultCategory);
    }
    if (typeof options.defaultEnabled === 'boolean') {
      formData.append('defaultEnabled', String(options.defaultEnabled));
    }
    if (typeof options.defaultTimeless === 'boolean') {
      formData.append('defaultTimeless', String(options.defaultTimeless));
    }
    if (options.codePrefix) {
      formData.append('codePrefix', options.codePrefix);
    }

    return this.request('POST', '/admin/knowledge/cards/import-markdown', {
      body: formData,
      timeout: options.timeout || 120000,
      signal: options.signal,
    });
  },

  async previewKnowledgeMarkdown(files, options = {}) {
    const formData = new FormData();
    files.forEach(file => formData.append('files', file));

    if (options.defaultCategory) {
      formData.append('defaultCategory', options.defaultCategory);
    }
    if (options.codePrefix) {
      formData.append('codePrefix', options.codePrefix);
    }
    if (typeof options.defaultEnabled === 'boolean') {
      formData.append('defaultEnabled', String(options.defaultEnabled));
    }
    if (typeof options.defaultTimeless === 'boolean') {
      formData.append('defaultTimeless', String(options.defaultTimeless));
    }

    return this.request('POST', '/admin/knowledge/cards/preview', {
      body: formData,
      timeout: options.timeout || 60000,
      signal: options.signal,
    });
  },

  async batchCreateCards(requests) {
    return this.request('POST', '/admin/knowledge/cards/batch-create', {
      body: requests,
      timeout: 120000,
    });
  },

  async listKnowledgeCards(params = {}) {
    const query = new URLSearchParams();
    if (params.keyword) query.set('keyword', params.keyword);
    if (params.category) query.set('category', params.category);
    if (params.enabled != null) query.set('enabled', params.enabled);
    if (params.page) query.set('page', params.page);
    if (params.size) query.set('size', params.size);
    const qs = query.toString();
    return this.request('GET', '/admin/knowledge/cards' + (qs ? '?' + qs : ''));
  },

  async getKnowledgeCard(cardCode) {
    return this.request('GET', '/admin/knowledge/cards/' + encodeURIComponent(cardCode));
  },

  async updateKnowledgeCard(cardCode, data) {
    return this.request('PUT', '/admin/knowledge/cards/' + encodeURIComponent(cardCode), { body: data });
  },

  async deleteKnowledgeCard(cardCode) {
    return this.request('DELETE', '/admin/knowledge/cards/' + encodeURIComponent(cardCode));
  },
};

window.Api = Api;

Api.ERROR_MESSAGES = {
  10000: '系统繁忙，请稍后重试',
  10001: '提交的信息有误，请检查后重试',
  10002: '缺少必要信息',
  20001: '上传会话已失效，请重新上传',
  20002: '上传已超时，请重新上传',
  20003: '上传分片顺序错误',
  20004: '分片大小不正确',
  20005: '不支持该文件格式',
  20006: '文件大小超出限制',
  20007: '分片正在上传中，请稍后',
  20008: '分片上传失败，请重试',
  20009: '文件合并失败，请重新上传',
  21001: '任务不存在',
  21002: '任务已存在',
  21003: '当前任务状态不允许此操作',
  21004: '重试次数已用完',
  21005: '任务正在处理中',
  22001: '配额不足，请联系管理员',
  22002: '今日配额已用完，明天再试',
  22003: '本月配额已用完',
  23001: '用户不存在',
  23002: '账号已被禁用',
  23003: '用户名已被占用',
  23004: '邮箱已被注册',
  23005: '邮箱或密码错误',
  30001: 'AI 分析服务异常，请稍后重试',
  30002: 'AI 分析超时，请重试',
  30003: 'AI 服务配额不足',
  30004: '存储服务异常',
  30005: '消息队列异常',
  40001: '请求过于频繁，请稍后重试',
  40002: '服务暂时不可用，请稍后',
  40003: '登录已过期，请重新登录',
  40004: '认证凭证无效',
};

Api.getErrorMessage = function(code, fallback) {
  return Api.ERROR_MESSAGES[code] || fallback || '操作失败';
};
