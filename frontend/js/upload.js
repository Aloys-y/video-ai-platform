/**
 * Upload Module — 分片上传
 *
 * 核心流程：
 * 1. 用户选择/拖拽文件
 * 2. POST /upload/init → 获取 uploadId
 * 3. 循环分片 → POST /upload/chunk（每片 20MB，3并发）
 * 4. POST /upload/complete → 合并分片
 * 5. 显示确认面板，用户输入 prompt → 点击确认
 * 6. POST /upload/submit → 创建分析任务 → 跳转详情
 */

const Upload = {
  CHUNK_SIZE: 20 * 1024 * 1024,
  CONCURRENCY: 3,
  ALLOWED_TYPES: ['mp4'],
  MAX_SIZE: 5 * 1024 * 1024 * 1024,

  state: {
    file: null,
    uploadId: null,
    totalChunks: 0,
    uploadedChunks: [],
    isUploading: false,
  },

  init() {
    this.bindDropZone();
    this.bindFileInput();
    this.bindSubmitButton();
    this.state = { file: null, uploadId: null, totalChunks: 0, uploadedChunks: [], isUploading: false };
    this.showZone();
  },

  bindDropZone() {
    const zone = document.getElementById('upload-zone');
    if (!zone) return;

    zone.addEventListener('click', () => {
      document.getElementById('file-input').click();
    });

    zone.addEventListener('dragover', (e) => {
      e.preventDefault();
      zone.classList.add('dragover');
    });

    zone.addEventListener('dragleave', () => {
      zone.classList.remove('dragover');
    });

    zone.addEventListener('drop', (e) => {
      e.preventDefault();
      zone.classList.remove('dragover');
      const file = e.dataTransfer.files[0];
      if (file) this.handleFile(file);
    });
  },

  bindFileInput() {
    const input = document.getElementById('file-input');
    if (!input) return;
    input.addEventListener('change', (e) => {
      const file = e.target.files[0];
      if (file) this.handleFile(file);
      input.value = '';
    });
  },

  bindSubmitButton() {
    const btn = document.getElementById('upload-submit-btn');
    if (!btn) return;
    btn.addEventListener('click', () => this.submitTask());
  },

  async handleFile(file) {
    const ext = file.name.split('.').pop().toLowerCase();
    if (!this.ALLOWED_TYPES.includes(ext)) {
      this.toast(`不支持的格式，仅限：${this.ALLOWED_TYPES.join(', ')}`, 'error');
      return;
    }

    if (file.size > this.MAX_SIZE) {
      this.toast('文件大小超过 5GB 限制', 'error');
      return;
    }

    this.state.file = file;
    await this.startUpload();
  },

  async startUpload() {
    const file = this.state.file;
    if (!file) return;

    this.state.isUploading = true;
    this.showProgress();

    try {
      // 1. 初始化上传
      const totalChunks = Math.ceil(file.size / this.CHUNK_SIZE);
      const initResult = await Api.post('/upload/init', {
        fileName: file.name,
        fileSize: file.size,
        chunkSize: this.CHUNK_SIZE,
        contentType: file.type || 'video/mp4',
      });

      this.state.uploadId = initResult.uploadId;
      this.state.totalChunks = initResult.totalChunks || totalChunks;

      // 秒传命中 → 直接显示确认面板
      if (initResult.instantUpload) {
        this.toast('秒传成功，文件已存在', 'success');
        this.showConfirm();
        return;
      }

      // 2. 分片上传
      this.state.uploadedChunks = initResult.uploadedChunks || [];
      await this.uploadChunks();

      // 3. 合并分片
      await Api.request('POST', '/upload/complete', {
        headers: { 'X-Upload-Id': this.state.uploadId },
      });

      this.toast('上传完成', 'success');
      // 4. 显示确认面板，等用户输入 prompt 并确认
      this.showConfirm();

    } catch (err) {
      this.toast(`上传失败：${err.message}`, 'error');
      this.state.isUploading = false;
      this.showZone();
    }
  },

  /**
   * 提交分析任务（用户点击确认后）
   */
  async submitTask() {
    const btn = document.getElementById('upload-submit-btn');
    if (btn) {
      btn.disabled = true;
      btn.querySelector('.btn__text').textContent = '提交中...';
    }

    try {
      const prompt = document.getElementById('upload-confirm-prompt')?.value?.trim() || '';
      const result = await Api.request('POST', `/upload/submit?prompt=${encodeURIComponent(prompt)}`, {
        headers: { 'X-Upload-Id': this.state.uploadId },
      });

      const tid = typeof result === 'string' ? result : result.taskId || result;
      this.toast('任务已提交，正在分析...', 'success');

      setTimeout(() => {
        window.location.hash = `#/task/${tid}`;
      }, 1000);

    } catch (err) {
      this.toast(`提交失败：${err.message}`, 'error');
      if (btn) {
        btn.disabled = false;
        btn.querySelector('.btn__text').textContent = '开始分析';
      }
    }
  },

  async uploadChunks() {
    const file = this.state.file;
    const totalChunks = this.state.totalChunks;
    const uploaded = new Set(this.state.uploadedChunks);

    const pending = [];
    for (let i = 0; i < totalChunks; i++) {
      if (!uploaded.has(i)) pending.push(i);
    }

    let completed = uploaded.size;

    for (let batch = 0; batch < pending.length; batch += this.CONCURRENCY) {
      const tasks = pending.slice(batch, batch + this.CONCURRENCY).map(async (i) => {
        const start = i * this.CHUNK_SIZE;
        const end = Math.min(start + this.CHUNK_SIZE, file.size);
        const chunk = file.slice(start, end);

        try {
          await Api.uploadChunk(this.state.uploadId, i, chunk);
          this.state.uploadedChunks.push(i);
          completed++;
          this.updateProgress(completed, totalChunks);
        } catch (err) {
          try {
            await Api.uploadChunk(this.state.uploadId, i, chunk);
            this.state.uploadedChunks.push(i);
            completed++;
            this.updateProgress(completed, totalChunks);
          } catch (retryErr) {
            throw new Error(`分片 ${i + 1}/${totalChunks} 上传失败：${retryErr.message}`);
          }
        }
      });

      await Promise.all(tasks);
    }
  },

  updateProgress(current, total) {
    const percent = Math.round((current / total) * 100);
    const bar = document.getElementById('upload-progress-bar');
    const label = document.getElementById('upload-progress-label');
    if (bar) bar.style.width = `${percent}%`;
    if (label) label.textContent = `${current} / ${total} 分片 (${percent}%)`;
  },

  showZone() {
    const zone = document.getElementById('upload-zone');
    const progress = document.getElementById('upload-progress-section');
    const confirm = document.getElementById('upload-confirm-section');
    if (zone) zone.classList.remove('hidden');
    if (progress) progress.classList.add('hidden');
    if (confirm) confirm.classList.add('hidden');
  },

  showProgress() {
    const zone = document.getElementById('upload-zone');
    const progress = document.getElementById('upload-progress-section');
    const confirm = document.getElementById('upload-confirm-section');
    if (zone) zone.classList.add('hidden');
    if (progress) progress.classList.remove('hidden');
    if (confirm) confirm.classList.add('hidden');
    const statusText = document.getElementById('upload-status');
    document.getElementById('upload-filename').textContent = this.state.file.name;
    document.getElementById('upload-filesize').textContent = this.formatSize(this.state.file.size);
    if (statusText) statusText.textContent = '上传中...';
  },

  showConfirm() {
    const zone = document.getElementById('upload-zone');
    const progress = document.getElementById('upload-progress-section');
    const confirm = document.getElementById('upload-confirm-section');
    if (zone) zone.classList.add('hidden');
    if (progress) progress.classList.add('hidden');
    if (confirm) confirm.classList.remove('hidden');

    document.getElementById('confirm-filename').textContent = this.state.file.name;
    document.getElementById('confirm-filesize').textContent = this.formatSize(this.state.file.size);
    const promptInput = document.getElementById('upload-confirm-prompt');
    if (promptInput) promptInput.value = '';
  },

  formatSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
  },

  toast(message, type = 'info') {
    if (window.App && window.App.toast) {
      window.App.toast(message, type);
    }
  },

  destroy() {
    if (this.state.isUploading) {
      this.state.isUploading = false;
      this.state.file = null;
      this.state.uploadId = null;
    }
  },
};

window.Upload = Upload;
