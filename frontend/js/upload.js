/**
 * Upload Module - chunk upload with resume support
 */

const Upload = {
  DEFAULT_CHUNK_SIZE: 5 * 1024 * 1024,
  CONCURRENCY: 3,
  CHUNK_REQUEST_TIMEOUT: 60000,
  CHUNK_MAX_RETRIES: 3,
  RETRY_BASE_DELAY: 1000,
  RETRY_MAX_DELAY: 8000,
  ALLOWED_TYPES: ['mp4'],
  MAX_SIZE: 5 * 1024 * 1024 * 1024,
  SAMPLE_SIZE: 2 * 1024 * 1024,
  eventsBound: false,

  state: {},

  init() {
    if (!this.eventsBound) {
      this.bindDropZone();
      this.bindFileInput();
      this.bindSubmitButton();
      this.eventsBound = true;
    }

    this.resetState();
    this.showZone();
  },

  resetState() {
    this.state = {
      file: null,
      uploadId: null,
      chunkSize: 0,
      totalChunks: 0,
      uploadedChunks: [],
      isUploading: false,
      activeChunkControllers: new Set(),
    };
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
    if (this.state.isUploading) {
      this.toast('当前已有上传任务进行中，请等待完成后再选择新文件', 'info');
      return;
    }

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
      this.updateStatus('计算文件指纹...');
      const fileHash = await this.computeFileHash(file);

      const requestedChunkSize = this.DEFAULT_CHUNK_SIZE;
      const fallbackTotalChunks = Math.ceil(file.size / requestedChunkSize);
      const initResult = await Api.post('/upload/init', {
        fileName: file.name,
        fileSize: file.size,
        chunkSize: requestedChunkSize,
        contentType: file.type || 'video/mp4',
        fileHash,
      });

      this.state.uploadId = initResult.uploadId;
      this.state.chunkSize = initResult.chunkSize || requestedChunkSize;
      this.state.totalChunks = initResult.totalChunks || fallbackTotalChunks;

      if (initResult.instantUpload) {
        this.state.isUploading = false;
        this.toast('秒传成功，文件已存在', 'success');
        this.showConfirm();
        return;
      }

      this.state.uploadedChunks = Array.isArray(initResult.uploadedChunks)
        ? [...initResult.uploadedChunks]
        : [];

      if (this.state.uploadedChunks.length > 0) {
        this.toast(
          `断点续传，已有 ${this.state.uploadedChunks.length}/${this.state.totalChunks} 个分片`,
          'info'
        );
      }

      this.updateStatus('上传中...');
      this.updateProgress(this.state.uploadedChunks.length, this.state.totalChunks);
      await this.uploadChunks();

      this.updateStatus('合并分片...');
      await Api.request('POST', '/upload/complete', {
        headers: { 'X-Upload-Id': this.state.uploadId },
      });

      this.state.isUploading = false;
      this.toast('上传完成', 'success');
      this.showConfirm();
    } catch (err) {
      this.state.isUploading = false;
      this.abortActiveChunkRequests();
      this.toast(`上传失败：${err.message}`, 'error');
      this.showZone();
    }
  },

  async computeFileHash(file) {
    const sampleSize = Math.min(this.SAMPLE_SIZE, file.size);
    const chunks = [];

    chunks.push(file.slice(0, sampleSize));

    if (file.size > sampleSize * 2) {
      const mid = Math.floor((file.size - sampleSize) / 2);
      chunks.push(file.slice(mid, mid + sampleSize));
    }

    if (file.size > sampleSize) {
      chunks.push(file.slice(file.size - sampleSize));
    }

    const buffers = await Promise.all(chunks.map(chunk => chunk.arrayBuffer()));
    const combined = new Uint8Array(
      buffers.reduce((acc, buffer) => acc + buffer.byteLength, 0) + 8
    );

    let offset = 0;
    for (const buffer of buffers) {
      combined.set(new Uint8Array(buffer), offset);
      offset += buffer.byteLength;
    }

    const sizeView = new DataView(combined.buffer, combined.byteOffset + combined.byteLength - 8);
    sizeView.setFloat64(0, file.size, true);

    const hashBuffer = await crypto.subtle.digest('SHA-256', combined);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(byte => byte.toString(16).padStart(2, '0')).join('');
  },

  async submitTask() {
    const btn = document.getElementById('upload-submit-btn');
    if (btn) {
      btn.disabled = true;
      btn.querySelector('.btn__text').textContent = '提交中...';
    }

    try {
      const prompt = document.getElementById('upload-confirm-prompt')?.value?.trim() || '';
      const result = await Api.request(
        'POST',
        `/upload/submit?prompt=${encodeURIComponent(prompt)}`,
        {
          headers: { 'X-Upload-Id': this.state.uploadId },
        }
      );

      const taskId = typeof result === 'string' ? result : result.taskId || result;
      this.toast('任务已提交，正在分析...', 'success');

      setTimeout(() => {
        window.location.hash = `#/task/${taskId}`;
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
    const chunkSize = this.state.chunkSize || this.DEFAULT_CHUNK_SIZE;
    const totalChunks = this.state.totalChunks;
    const uploaded = new Set(this.state.uploadedChunks);
    const pending = [];

    for (let i = 0; i < totalChunks; i++) {
      if (!uploaded.has(i)) {
        pending.push(i);
      }
    }

    if (pending.length === 0) {
      return;
    }

    const progress = { completed: uploaded.size };
    let nextPendingIndex = 0;
    const workerCount = Math.min(this.CONCURRENCY, pending.length);

    const workers = Array.from({ length: workerCount }, async () => {
      while (nextPendingIndex < pending.length) {
        if (!this.state.isUploading) {
          return;
        }

        const chunkIndex = pending[nextPendingIndex];
        nextPendingIndex += 1;
        await this.uploadSingleChunk(file, chunkSize, chunkIndex, totalChunks, progress);
      }
    });

    await Promise.all(workers);
  },

  async uploadSingleChunk(file, chunkSize, chunkIndex, totalChunks, progress) {
    const start = chunkIndex * chunkSize;
    const end = Math.min(start + chunkSize, file.size);
    const chunk = file.slice(start, end);

    for (let attempt = 1; attempt <= this.CHUNK_MAX_RETRIES; attempt++) {
      if (!this.state.isUploading) {
        throw new Error('上传已取消');
      }

      const controller = new AbortController();
      this.state.activeChunkControllers.add(controller);

      try {
        this.updateStatus(
          `上传分片 ${chunkIndex + 1}/${totalChunks}，尝试 ${attempt}/${this.CHUNK_MAX_RETRIES}`
        );
        await Api.uploadChunk(this.state.uploadId, chunkIndex, chunk, {
          timeout: this.CHUNK_REQUEST_TIMEOUT,
          signal: controller.signal,
        });

        if (!this.state.uploadedChunks.includes(chunkIndex)) {
          this.state.uploadedChunks.push(chunkIndex);
        }

        progress.completed += 1;
        this.updateProgress(progress.completed, totalChunks);
        return;
      } catch (err) {
        if (!this.shouldRetryChunkUpload(err, attempt)) {
          throw new Error(`分片 ${chunkIndex + 1}/${totalChunks} 上传失败：${err.message}`);
        }

        const delay = this.getRetryDelay(attempt);
        this.updateStatus(
          `分片 ${chunkIndex + 1}/${totalChunks} 上传失败，${Math.round(delay / 1000)} 秒后重试`
        );
        await this.sleep(delay);
      } finally {
        this.state.activeChunkControllers.delete(controller);
      }
    }

    throw new Error(`分片 ${chunkIndex + 1}/${totalChunks} 上传失败：超过最大重试次数`);
  },

  shouldRetryChunkUpload(err, attempt) {
    if (!this.state.isUploading || attempt >= this.CHUNK_MAX_RETRIES) {
      return false;
    }

    const message = err?.message || '';
    if (message.includes('取消')) {
      return false;
    }

    return (
      message.includes('超时') ||
      message.includes('网络') ||
      message.includes('频繁') ||
      message.includes('稍后') ||
      message.includes('服务') ||
      message.includes('上传中')
    );
  },

  getRetryDelay(attempt) {
    const baseDelay = Math.min(
      this.RETRY_BASE_DELAY * (2 ** (attempt - 1)),
      this.RETRY_MAX_DELAY
    );
    const jitter = 0.8 + Math.random() * 0.4;
    return Math.round(baseDelay * jitter);
  },

  sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  },

  updateProgress(current, total) {
    const percent = Math.round((current / total) * 100);
    const bar = document.getElementById('upload-progress-bar');
    const label = document.getElementById('upload-progress-label');

    if (bar) {
      bar.style.width = `${percent}%`;
    }
    if (label) {
      label.textContent = `${current} / ${total} 分片 (${percent}%)`;
    }
  },

  updateStatus(message) {
    const statusText = document.getElementById('upload-status');
    if (statusText) {
      statusText.textContent = message;
    }
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

    document.getElementById('upload-filename').textContent = this.state.file.name;
    document.getElementById('upload-filesize').textContent = this.formatSize(this.state.file.size);
    this.updateStatus('准备中...');
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
    if (promptInput) {
      promptInput.value = '';
    }

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
    return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
  },

  toast(message, type = 'info') {
    if (window.App && window.App.toast) {
      window.App.toast(message, type);
    }
  },

  abortActiveChunkRequests() {
    for (const controller of this.state.activeChunkControllers) {
      controller.abort();
    }
    this.state.activeChunkControllers.clear();
  },

  destroy() {
    if (this.state.isUploading) {
      this.state.isUploading = false;
      this.abortActiveChunkRequests();
    }
    this.resetState();
  },
};

window.Upload = Upload;
