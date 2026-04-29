/**
 * Upload Module — 分片上传 + 断点续传
 *
 * 核心流程：
 * 1. 用户选择/拖拽文件
 * 2. 计算文件指纹（采样SHA-256）
 * 3. POST /upload/init（带fileHash）→ 获取 uploadId + 已传分片
 * 4. 跳过已传分片，上传剩余分片
 * 5. POST /upload/complete → 合并分片
 * 6. 显示确认面板，用户输入 prompt → 点击确认
 * 7. POST /upload/submit → 创建分析任务 → 跳转详情
 */

const Upload = {
  CHUNK_SIZE: 20 * 1024 * 1024,
  CONCURRENCY: 3,
  ALLOWED_TYPES: ['mp4'],
  MAX_SIZE: 5 * 1024 * 1024 * 1024,
  // 采样hash配置：取头/中/尾各 SAMPLE_SIZE 字节
  SAMPLE_SIZE: 2 * 1024 * 1024,

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
      // 1. 计算文件指纹（采样hash）
      const statusText = document.getElementById('upload-status');
      if (statusText) statusText.textContent = '计算文件指纹...';

      const fileHash = await this.computeFileHash(file);

      // 2. 初始化上传（带fileHash，支持断点续传/秒传）
      const totalChunks = Math.ceil(file.size / this.CHUNK_SIZE);
      const initResult = await Api.post('/upload/init', {
        fileName: file.name,
        fileSize: file.size,
        chunkSize: this.CHUNK_SIZE,
        contentType: file.type || 'video/mp4',
        fileHash: fileHash,
      });

      this.state.uploadId = initResult.uploadId;
      this.state.totalChunks = initResult.totalChunks || totalChunks;

      // 秒传命中 → 直接显示确认面板
      if (initResult.instantUpload) {
        this.toast('秒传成功，文件已存在', 'success');
        this.showConfirm();
        return;
      }

      // 断点续传：已有部分分片
      this.state.uploadedChunks = initResult.uploadedChunks || [];
      if (this.state.uploadedChunks.length > 0) {
        this.toast(`断点续传，已有 ${this.state.uploadedChunks.length}/${this.state.totalChunks} 分片`, 'info');
      }

      // 3. 分片上传（跳过已传分片）
      if (statusText) statusText.textContent = '上传中...';
      this.updateProgress(this.state.uploadedChunks.length, this.state.totalChunks);
      await this.uploadChunks();

      // 4. 合并分片
      await Api.request('POST', '/upload/complete', {
        headers: { 'X-Upload-Id': this.state.uploadId },
      });

      this.toast('上传完成', 'success');
      // 5. 显示确认面板
      this.showConfirm();

    } catch (err) {
      this.toast(`上传失败：${err.message}`, 'error');
      this.state.isUploading = false;
      this.showZone();
    }
  },

  /**
   * 计算文件指纹（采样SHA-256）
   * 取文件头部、中部、尾部各 SAMPLE_SIZE 字节拼接后计算
   */
  async computeFileHash(file) {
    const sampleSize = Math.min(this.SAMPLE_SIZE, file.size);
    const chunks = [];

    // 头部
    chunks.push(file.slice(0, sampleSize));

    // 中部（仅文件大于2倍采样大小时才取）
    if (file.size > sampleSize * 2) {
      const mid = Math.floor((file.size - sampleSize) / 2);
      chunks.push(file.slice(mid, mid + sampleSize));
    }

    // 尾部
    if (file.size > sampleSize) {
      chunks.push(file.slice(file.size - sampleSize));
    }

    // 拼接采样数据 + 文件大小作为唯一标识
    const buffers = await Promise.all(chunks.map(c => c.arrayBuffer()));
    const combined = new Uint8Array(buffers.reduce((acc, b) => acc + b.byteLength, 0) + 8);
    let offset = 0;
    for (const buf of buffers) {
      combined.set(new Uint8Array(buf), offset);
      offset += buf.byteLength;
    }
    // 追加文件大小（防碰撞）
    const sizeView = new DataView(combined.buffer, combined.byteOffset + combined.byteLength - 8);
    sizeView.setFloat64(0, file.size, true);

    const hashBuffer = await crypto.subtle.digest('SHA-256', combined);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
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

    // 全部分片已上传
    if (pending.length === 0) return;

    let completed = uploaded.size;

    for (let batch = 0; batch < pending.length; batch += this.CONCURRENCY) {
      if (!this.state.isUploading) return; // 用户取消
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
    if (statusText) statusText.textContent = '准备中...';
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

    // 重置按钮状态
    const btn = document.getElementById('upload-submit-btn');
    if (btn) {
      btn.disabled = false;
      btn.querySelector('.btn__text').textContent = '开始分析';
    }
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
