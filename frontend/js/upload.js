/**
 * Upload Module — 分片上传
 *
 * 核心流程：
 * 1. 用户选择/拖拽文件
 * 2. 计算 MD5（秒传检查）
 * 3. POST /upload/init → 获取 uploadId
 * 4. 循环分片 → POST /upload/chunk（每片 5MB）
 * 5. POST /upload/complete → 合并 → 返回 taskId
 */

const Upload = {
  // 默认分片大小 5MB
  CHUNK_SIZE: 5 * 1024 * 1024,
  // 允许的视频格式
  ALLOWED_TYPES: ['mp4', 'avi', 'mov', 'mkv', 'wmv', 'flv'],
  // 最大文件大小 5GB
  MAX_SIZE: 5 * 1024 * 1024 * 1024,

  state: {
    file: null,
    uploadId: null,
    totalChunks: 0,
    uploadedChunks: [],
    isUploading: false,
    isInstant: false,
    taskId: null,
  },

  /**
   * 初始化上传页面
   */
  init() {
    this.bindDropZone();
    this.bindFileInput();
    this.state = { file: null, uploadId: null, totalChunks: 0, uploadedChunks: [], isUploading: false, isInstant: false, taskId: null };
    this.showZone();
  },

  /**
   * 绑定拖拽区域
   */
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

  /**
   * 绑定文件选择
   */
  bindFileInput() {
    const input = document.getElementById('file-input');
    if (!input) return;
    input.addEventListener('change', (e) => {
      const file = e.target.files[0];
      if (file) this.handleFile(file);
      input.value = ''; // 允许重复选择同一文件
    });
  },

  /**
   * 处理选中的文件
   */
  async handleFile(file) {
    // 校验格式
    const ext = file.name.split('.').pop().toLowerCase();
    if (!this.ALLOWED_TYPES.includes(ext)) {
      this.toast(`不支持的格式，仅限：${this.ALLOWED_TYPES.join(', ')}`, 'error');
      return;
    }

    // 校验大小
    if (file.size > this.MAX_SIZE) {
      this.toast('文件大小超过 5GB 限制', 'error');
      return;
    }

    this.state.file = file;

    // 计算文件 MD5（使用简单的 SparkMD5 或仅用文件属性作为标识）
    // 简化版：不计算 MD5，跳过秒传（后续可加入）
    await this.startUpload();
  },

  /**
   * 开始上传流程
   */
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

      // 秒传命中
      if (initResult.instantUpload) {
        this.state.isInstant = true;
        this.state.taskId = initResult.taskId;
        this.showInstantUpload();
        return;
      }

      // 2. 分片上传
      this.state.uploadedChunks = initResult.uploadedChunks || [];
      await this.uploadChunks();

      // 3. 合并完成
      const taskId = await Api.request('POST', '/upload/complete', {
        headers: { 'X-Upload-Id': this.state.uploadId },
      });

      // 注意：complete API 返回 taskId 字符串
      this.state.taskId = typeof taskId === 'string' ? taskId : taskId.taskId;
      this.toast('上传完成，正在分析...', 'success');

      // 跳转任务详情
      setTimeout(() => {
        window.location.hash = `#/task/${this.state.taskId}`;
      }, 1500);

    } catch (err) {
      this.toast(`上传失败：${err.message}`, 'error');
      this.state.isUploading = false;
      this.showZone();
    }
  },

  /**
   * 分片上传循环
   */
  async uploadChunks() {
    const file = this.state.file;
    const totalChunks = this.state.totalChunks;
    const uploaded = new Set(this.state.uploadedChunks);

    for (let i = 0; i < totalChunks; i++) {
      if (uploaded.has(i)) continue; // 跳过已上传的分片

      const start = i * this.CHUNK_SIZE;
      const end = Math.min(start + this.CHUNK_SIZE, file.size);
      const chunk = file.slice(start, end);

      try {
        await Api.uploadChunk(this.state.uploadId, i, chunk);
        this.state.uploadedChunks.push(i);
        this.updateProgress(i + 1, totalChunks);
      } catch (err) {
        // 重试一次
        try {
          await Api.uploadChunk(this.state.uploadId, i, chunk);
          this.state.uploadedChunks.push(i);
          this.updateProgress(i + 1, totalChunks);
        } catch (retryErr) {
          throw new Error(`分片 ${i + 1}/${totalChunks} 上传失败：${retryErr.message}`);
        }
      }
    }
  },

  /**
   * 更新进度
   */
  updateProgress(current, total) {
    const percent = Math.round((current / total) * 100);
    const bar = document.getElementById('upload-progress-bar');
    const label = document.getElementById('upload-progress-label');
    if (bar) bar.style.width = `${percent}%`;
    if (label) label.textContent = `${current} / ${total} 分片 (${percent}%)`;
  },

  /**
   * 显示上传区域，隐藏进度
   */
  showZone() {
    const zone = document.getElementById('upload-zone');
    const progress = document.getElementById('upload-progress-section');
    if (zone) zone.classList.remove('hidden');
    if (progress) progress.classList.add('hidden');
  },

  /**
   * 显示进度，隐藏上传区域
   */
  showProgress() {
    const zone = document.getElementById('upload-zone');
    const progress = document.getElementById('upload-progress-section');
    const fileInfo = document.getElementById('upload-file-info');
    const statusText = document.getElementById('upload-status');

    if (zone) zone.classList.add('hidden');
    if (progress) progress.classList.remove('hidden');
    if (fileInfo) {
      document.getElementById('upload-filename').textContent = this.state.file.name;
      document.getElementById('upload-filesize').textContent = this.formatSize(this.state.file.size);
    }
    if (statusText) statusText.textContent = '上传中...';
  },

  /**
   * 显示秒传成功
   */
  showInstantUpload() {
    const progress = document.getElementById('upload-progress-section');
    const instant = document.getElementById('upload-instant');
    if (progress) progress.classList.add('hidden');
    if (instant) instant.classList.remove('hidden');

    this.toast('秒传成功，文件已存在，正在分析...', 'success');

    setTimeout(() => {
      window.location.hash = `#/task/${this.state.taskId}`;
    }, 1500);
  },

  /**
   * 格式化文件大小
   */
  formatSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
  },

  /**
   * Toast 通知（复用全局 toast）
   */
  toast(message, type = 'info') {
    if (window.App && window.App.toast) {
      window.App.toast(message, type);
    }
  },

  /**
   * 销毁（离开页面时调用）
   */
  destroy() {
    if (this.state.isUploading) {
      this.state.isUploading = false;
      this.state.file = null;
      this.state.uploadId = null;
    }
  },
};

window.Upload = Upload;
