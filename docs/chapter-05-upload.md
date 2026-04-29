# 第5章：分片上传（重点核心模块）

---

## 一、为什么需要分片上传？

```
普通上传（小文件）：
客户端 ──── 一次性发送 10MB ────→ 服务端
✅ 简单，直接

分片上传（大文件）：
客户端 ──── 分成 100 个 5MB 的块 ────→ 服务端
         第1块 ──→ ✅
         第2块 ──→ ✅
         第3块 ──→ 网络断了 ❌
         第4块 ──→ 重新上传第3块（不需要从头开始）
         ...
         全部传完 ──→ 服务端合并
```

**分片上传解决三个问题**：
1. **断点续传**：网络断了，只需重传失败的分片
2. **大文件支持**：5GB 文件不可能一次性上传，浏览器会崩溃
3. **并发加速**：多个分片可以并行上传

## 二、完整流程时序图

```
客户端                     UploadService              MinIO        MySQL        Kafka
  │                            │                       │            │            │
  │ ① POST /upload/init        │                       │            │            │
  │ {fileName, fileSize,       │                       │            │            │
  │  fileHash, chunkSize}      │                       │            │            │
  │───────────────────────────>│                       │            │            │
  │                            │ 校验类型+大小          │            │            │
  │                            │ 秒传检查(fileHash)     │            │            │
  │                            │ 计算分片数             │            │            │
  │                            │                       │            │            │
  │                            │ INSERT upload_session │            │            │
  │                            │──────────────────────────────────>│            │
  │                            │                       │            │            │
  │ <─ {uploadId, totalChunks, │                       │            │            │
  │     chunkSize} ───────────│                       │            │            │
  │                            │                       │            │            │
  │ ② POST /upload/chunk (×N)  │                       │            │            │
  │ Header: X-Upload-Id        │                       │            │            │
  │ Header: X-Chunk-Index      │                       │            │            │
  │ Body: 分片二进制数据         │                       │            │            │
  │───────────────────────────>│                       │            │            │
  │                            │ 查询会话               │            │            │
  │                            │──────────────────────────────────>│            │
  │                            │ 获取分布式锁(Redis)     │            │            │
  │                            │ 上传分片到 MinIO        │            │            │
  │                            │──────────────────────>│            │            │
  │                            │ 追加已传分片记录        │            │            │
  │                            │──────────────────────────────────>│            │
  │ <─ {uploadedChunks, progress} ──────────────────│  │            │            │
  │                            │                       │            │            │
  │ ③ POST /upload/complete    │                       │            │            │
  │ Header: X-Upload-Id        │                       │            │            │
  │───────────────────────────>│                       │            │            │
  │                            │ 检查所有分片已上传      │            │            │
  │                            │ MinIO 合并分片         │            │            │
  │                            │──────────────────────>│            │            │
  │                            │ 清理分片文件           │            │            │
  │                            │──────────────────────>│            │            │
  │                            │ 创建分析任务           │            │            │
  │                            │──────────────────────────────────>│            │
  │                            │ 发 Kafka 消息          │            │            │
  │                            │─────────────────────────────────────────────>│
  │ <─ {taskId} ──────────────│                       │            │            │
  │                            │                       │            │            │
  │ ④ 轮询 GET /upload/status  │                       │            │            │
  │───────────────────────────>│                       │            │            │
  │ <─ {progress, status} ────│                       │            │            │
```

## 三、第一步：初始化上传

### 3.1 Controller 层

```java
@PostMapping("/init")
public ApiResponse<UploadInitResponse> init(@Valid @RequestBody InitUploadRequest request) {
    User user = UserContext.getUser();  // 从ThreadLocal获取当前用户
    UploadInitResponse response = uploadService.init(user.getId(), request);
    return ApiResponse.success(response);
}
```

`@Valid` 触发参数校验，如果校验失败会抛出 `MethodArgumentNotValidException`，被全局异常处理器捕获。

### 3.2 参数校验（InitUploadRequest）

```java
@NotBlank(message = "文件名不能为空")
@Size(max = 255, message = "文件名最长255字符")
private String fileName;

@NotNull(message = "文件大小不能为空")
@Min(value = 1, message = "文件大小必须大于0")
@Max(value = 5L * 1024 * 1024 * 1024, message = "文件大小不能超过5GB")
private Long fileSize;

@Pattern(regexp = "^[a-fA-F0-9]{32}$", message = "文件哈希格式错误，应为32位MD5")
private String fileHash;

@Min(value = 1024 * 1024, message = "分片大小不能小于1MB")
@Max(value = 50 * 1024 * 1024, message = "分片大小不能超过50MB")
private Long chunkSize = 5 * 1024 * 1024L;  // 默认5MB
```

### 3.3 Service 层核心逻辑

```java
public UploadInitResponse init(Long userId, InitUploadRequest request) {
    // 1. 校验文件类型
    if (!isAllowedType(extension)) {
        throw new BusinessException(ErrorCode.UPLOAD_FILE_TYPE_NOT_SUPPORT);
    }

    // 2. 校验文件大小
    if (request.getFileSize() > maxFileSize) {
        throw new BusinessException(ErrorCode.UPLOAD_FILE_TOO_LARGE);
    }

    // 3. ⭐ 秒传检查
    if (request.getFileHash() != null) {
        UploadSession existing = uploadSessionMapper.selectByFileHash(request.getFileHash());
        if (existing != null && existing.getStatusEnum() == UploadStatus.MERGED) {
            // 文件已存在！不需要上传，直接创建任务
            AnalysisTask task = createAnalysisTask(userId, existing.getUploadId(), existing.getStoragePath());
            return UploadInitResponse.builder()
                    .uploadId(existing.getUploadId())
                    .instantUpload(true)   // ← 前端看到这个就知道了
                    .taskId(task.getTaskId())
                    .build();
        }
    }

    // 4. 创建上传会话
    int totalChunks = (int) Math.ceil((double) request.getFileSize() / chunkSize);
    // 例：4.9GB 文件，5MB 分片 = 1004 个分片

    UploadSession session = new UploadSession();
    session.setUploadId(uploadId);
    session.setTotalChunks(totalChunks);
    session.setUploadedChunks("[]");        // 空数组，还没传
    session.setStatus(UploadStatus.UPLOADING.getCode());
    session.setExpiredAt(LocalDateTime.now().plusHours(24));  // 24小时过期

    uploadSessionMapper.insert(session);

    return UploadInitResponse.builder()
            .uploadId(uploadId)
            .chunkSize(chunkSize)
            .totalChunks(totalChunks)
            .uploadedChunks(List.of())
            .instantUpload(false)
            .build();
}
```

### 3.4 秒传原理

```
第一次上传 video.mp4：
  客户端计算 MD5 → "abc123def456..."
  发送 init 请求 → 服务端查库 → 没找到 → 正常上传流程
  上传完成后，fileHash 存入数据库

第二次上传同样的 video.mp4：
  客户端计算 MD5 → "abc123def456..."  （相同文件，MD5 一样）
  发送 init 请求 → 服务端查库 → 找到了！状态是 MERGED
  → 直接复用已有文件，创建新任务
  → instantUpload=true
  → 省去了上传和合并的整个过程！
```

## 四、第二步：分片上传

### 4.1 Controller 层

```java
@PostMapping("/chunk")
public ApiResponse<UploadProgressResponse> uploadChunk(
        @RequestParam("file") MultipartFile file,           // 分片二进制
        @RequestHeader("X-Upload-Id") String uploadId,      // 哪个上传会话
        @RequestHeader("X-Chunk-Index") Integer chunkIndex) // 第几个分片
{
    return ApiResponse.success(uploadService.uploadChunk(uploadId, chunkIndex, file));
}
```

分片索引（chunkIndex）从 0 开始，到 totalChunks-1 结束。

### 4.2 Service 层核心逻辑

```java
public UploadProgressResponse uploadChunk(String uploadId, Integer chunkIndex, MultipartFile file) {
    // 1. 查询会话（校验存在 + 未过期）
    UploadSession session = getValidSession(uploadId);

    // 2. 校验分片索引范围
    if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
        throw new BusinessException(ErrorCode.UPLOAD_CHUNK_INDEX_ERROR);
    }

    // 3. 幂等检查：已上传的分片不需要重复上传
    if (session.isChunkUploaded(chunkIndex)) {
        return buildProgressResponse(session);  // 直接返回当前进度
    }

    // 4. ⭐ 分布式锁：防止并发上传同一分片
    String lockKey = RedisKey.uploadLock(uploadId, chunkIndex);
    RLock lock = redissonClient.getLock(lockKey);

    try {
        boolean locked = lock.tryLock(5, 30, TimeUnit.SECONDS);
        if (!locked) {
            throw new BusinessException(ErrorCode.UPLOAD_CHUNK_UPLOADING);
        }

        // 5. ⭐ 双重检查（DCL）：获取锁后再确认
        session = uploadSessionMapper.selectByUploadId(uploadId);
        if (session.isChunkUploaded(chunkIndex)) {
            return buildProgressResponse(session);
        }

        // 6. 上传到 MinIO
        String chunkPath = IdGenerator.generateChunkPath(uploadId, chunkIndex);
        // 路径示例：chunks/upload_20240414_abc123/0
        storageService.putObject(chunkPath, file.getInputStream(), ...);

        // 7. MySQL 原子追加已传分片索引
        uploadSessionMapper.appendUploadedChunk(uploadId, chunkIndex);

        // 8. 更新 Redis 进度缓存
        refreshProgressCache(uploadId);

    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    // 返回最新进度
    session = uploadSessionMapper.selectByUploadId(uploadId);
    return buildProgressResponse(session);
}
```

### 4.3 ⭐ 分布式锁详解（面试高频）

**为什么需要锁？**

```
无锁场景：用户网络卡顿，重复点击上传按钮

请求A：检查分片0未上传 → 开始上传
请求B：检查分片0未上传 → 也开始上传（A还没写完数据库）
                          → 两个请求同时上传同一个分片！
                          → 浪费带宽，可能数据冲突
```

**Redisson 分布式锁的工作原理**：

```
请求A：lock.tryLock(5, 30, SECONDS)
  → Redis 中设置 key = "videoai:upload:lock:upload_xxx:0"
  → value = "客户端A的ID"
  → 过期时间 = 30秒
  → 返回 true（获取锁成功）

请求B：lock.tryLock(5, 30, SECONDS)
  → 发现 key 已存在（被A持有）
  → 等待最多5秒
  → 如果A在5秒内释放 → B获取锁
  → 如果5秒超时 → 返回 false

请求A：上传完成 → lock.unlock()
  → 删除 Redis key
  → B 可以获取锁了
```

**参数含义**：
- `tryLock(5, 30, SECONDS)`：
  - `5` = 等待获取锁的最长时间（5秒后放弃）
  - `30` = 锁的持有时间（30秒后自动释放，防止死锁）
  - Redisson 还有 **Watchdog 机制**：如果业务没执行完，每 10 秒自动续期到 30 秒

### 4.4 ⭐ 双重检查（DCL）

```java
// 第一次检查（获取锁之前）
if (session.isChunkUploaded(chunkIndex)) {
    return buildProgressResponse(session);  // 已上传，直接返回
}

lock.tryLock(...);  // 获取锁

// 第二次检查（获取锁之后）
session = uploadSessionMapper.selectByUploadId(uploadId);  // 重新查数据库
if (session.isChunkUploaded(chunkIndex)) {
    return buildProgressResponse(session);  // 被其他线程上传了
}

// 确认没人上传过，才真正上传
storageService.putObject(...);
```

**为什么两次检查？**

```
时间线：
  请求A                          请求B
    │                              │
    ├─ 检查：未上传 ✅              │
    │                              ├─ 检查：未上传 ✅（A还没写数据库）
    ├─ 获取锁 ✅                    │
    │                              ├─ 等待锁...
    ├─ 上传分片到 MinIO             │
    ├─ 写入数据库 [0]              │
    ├─ 释放锁                      │
    │                              ├─ 获取锁 ✅
    │                              ├─ 第二次检查：已上传！→ 直接返回
    │                              │  （不会重复上传）
```

### 4.5 幂等性设计

```java
// 三层幂等保障：

// 第1层：快速检查（无锁，性能好）
if (session.isChunkUploaded(chunkIndex)) return progress;

// 第2层：分布式锁（防并发）
lock.tryLock(...)

// 第3层：双重检查（锁内再确认）
session = requery();
if (session.isChunkUploaded(chunkIndex)) return progress;
```

**效果**：同一个分片无论被请求多少次，只会真正上传一次。

## 五、第三步：合并完成

### 5.1 核心逻辑

```java
@Transactional
public String completeUpload(String uploadId) {
    UploadSession session = getValidSession(uploadId);

    // 1. 检查所有分片是否已上传
    if (!session.isAllChunksUploaded()) {
        throw new BusinessException(ErrorCode.UPLOAD_CHUNK_SIZE_ERROR,
            String.format("还有 %d 个分片未上传", ...));
    }

    // 2. 更新状态为 COMPLETED
    uploadSessionMapper.updateStatus(uploadId, UploadStatus.COMPLETED.getCode());

    // 3. ⭐ MinIO 合并分片（服务端合并，不经过应用层）
    String videoPath = IdGenerator.generateVideoPath(uploadId, extension);
    // 路径：videos/2024/04/14/upload_xxx.mp4

    List<String> chunkPaths = new ArrayList<>();
    for (int i = 0; i < session.getTotalChunks(); i++) {
        chunkPaths.add(IdGenerator.generateChunkPath(uploadId, i));
    }
    storageService.composeObject(videoPath, chunkPaths);

    // 4. 清理分片文件
    storageService.removeObjects(chunkPaths);

    // 5. 更新存储路径
    uploadSessionMapper.setStoragePath(uploadId, videoPath);

    // 6. ⭐ 创建分析任务 + 发 Kafka 消息
    AnalysisTask task = createAnalysisTask(session.getUserId(), uploadId, videoPath);

    return task.getTaskId();
}
```

### 5.2 MinIO 合并的优势

```
方案A：应用层合并（❌ 不推荐）
  从 MinIO 下载每个分片到应用服务器内存
  拼接成一个完整文件
  再上传回 MinIO
  → 5GB 文件要经过应用服务器，内存爆炸！

方案B：MinIO 服务端合并（✅ 本项目采用）
  调用 MinIO的 ComposeObject API
  MinIO 在内部直接拼接，不经过应用层
  → 应用服务器零内存消耗！
```

### 5.3 Kafka 异步通知

```java
private AnalysisTask createAnalysisTask(Long userId, String uploadId, String storagePath) {
    // 1. 创建任务记录
    AnalysisTask task = new AnalysisTask();
    task.setTaskId(IdGenerator.generateTaskId());
    task.setStatusEnum(TaskStatus.PENDING);
    analysisTaskMapper.insert(task);

    // 2. 异步发送 Kafka 消息
    try {
        kafkaTemplate.send(TopicConstant.TASK_TOPIC, task.getTaskId(), buildTaskMessage(task));
    } catch (Exception e) {
        // Kafka 发送失败不影响主流程
        // Worker 可以通过定时任务扫描数据库兜底
        log.warn("Kafka send failed, taskId={}. Task will be picked up later.", task.getTaskId());
    }

    return task;
}
```

**为什么 Kafka 发送失败不影响主流程？**

```
正常路径：Kafka 消息 → Worker 消费 → 处理任务
兜底路径：Worker 定时扫描数据库 → 发现 PENDING 状态的任务 → 处理

两条路径保证了任务不会被遗漏。
```

## 六、上传会话状态流转

```
UPLOADING ──────→ COMPLETED ──────→ MERGED
   │                 │                  │
   │            合并失败               完成（最终状态）
   │                 │
   │                 ▼
   │           MERGE_FAILED
   │
   │     超过24小时
   │         │
   └─────────└──→ EXPIRED
```

## 七、核心问题解答

### Q1：为什么不把分片直接拼成完整文件再上传？

1. **内存问题**：5GB 文件不可能加载到内存
2. **断点续传**：分片可以独立上传、独立重试
3. **并发加速**：多个分片可以并行上传
4. **网络效率**：一个分片失败只需重传几 MB，不需要重传整个文件

### Q2：分布式锁为什么用 Redis 而不是 `synchronized`？

```java
// synchronized 只能锁单 JVM
synchronized (uploadId.intern()) {
    // 如果部署了 2 个 API 实例，
    // 实例A 的锁对实例B 无效！
    // 两个实例可能同时上传同一个分片
}

// Redis 分布式锁：跨 JVM、跨实例
RLock lock = redissonClient.getLock(lockKey);
// 锁存在 Redis 中，所有实例共享
```

### Q3：分片大小为什么是 5MB？

权衡的结果：
- **太小**（如 1MB）：5GB 文件要 5000 个分片，请求次数太多，数据库压力大
- **太大**（如 100MB）：网络不稳定时分片容易失败，重传成本高
- **5MB**：平衡点，5GB 文件约 1000 个分片，重传一个分片也就 5MB

### Q4：uploaded_chunks 为什么用 JSON 数组？

```sql
-- 当前方案：JSON 数组
uploaded_chunks = "[0, 1, 2, 5, 6]"

-- 备选方案：位图（Bitmap）
uploaded_chunks = "0b00100111"  -- 第0,1,2,5,6位为1

-- 备选方案：关联表
CREATE TABLE upload_chunk (
    upload_id VARCHAR(64),
    chunk_index INT,
    PRIMARY KEY (upload_id, chunk_index)
)
```

JSON 数组的好处：简单、直观、不需要额外表。分片数最多几千个，JSON 完全够用。

### Q5：如果上传过程中服务重启了怎么办？

**断点续传机制**：

1. 客户端选择文件后，先用 `crypto.subtle.digest('SHA-256')` 对文件头/中/尾各采样 2MB 计算文件指纹（fileHash）
2. 调 `POST /upload/init` 时带上 fileHash
3. 服务端通过 fileHash 查找 UPLOADING 状态的旧会话 → 返回旧 uploadId + `uploadedChunks` 列表
4. 客户端跳过已传分片，只上传剩余分片

```
第一次上传 video.mp4（500MB）：
  init(fileHash="a1b2c3...") → 创建新会话，25个分片
  上传分片 0-14 → 网络断了 ❌

第二次上传同一文件：
  init(fileHash="a1b2c3...") → 命中 UPLOADING 会话！
  返回 uploadedChunks=[0,1,2,...,14]
  → 只上传分片 15-24，省掉 60% 上传量
```

5. 上传会话有 24 小时过期时间，过期自动清理

### Q6：上传完成后为什么不直接创建任务？

**两阶段任务创建**（上传与任务解耦）：

```
旧流程（耦合）：
  上传 → 合并 → 立即创建任务 → 用户没有机会输入 prompt

新流程（解耦）：
  上传 → 合并 → 显示确认面板（用户输入 prompt）→ 点击确认 → 创建任务
```

好处：
1. 用户可以在看到上传成功后再决定分析策略（自定义 prompt）
2. 上传和任务是独立的关注点，符合单一职责原则
3. 前端体验更好：用户不会因为还没想好 prompt 就被迫提交

## 八、面试模拟

### "大文件上传怎么做的？"
> "采用三步走方案：初始化、分片上传、合并完成。客户端先计算文件指纹（采样SHA-256），调 init 接口传入文件信息和 fileHash，服务端检查是否有同 hash 的 UPLOADING 会话实现断点续传，或 MERGED 会话实现秒传。然后客户端并行上传各个分片（20MB/片，3并发），每个分片通过 Redisson 分布式锁 + 双重检查保证幂等性，分片上传到 MinIO 对象存储。全部上传完成后，客户端调 complete 接口合并分片，然后显示确认面板让用户输入分析 prompt，确认后才调 submit 接口创建任务，通过 Kafka 通知 Worker 开始 AI 分析。"

### "如何保证分片上传的幂等性？"
> "三层保障：第一层，上传前快速检查该分片是否已在数据库中标记为已上传；第二层，使用 Redisson 分布式锁防止并发上传同一分片；第三层，获取锁后双重检查，重新查库确认。三个层次从性能到安全性逐层递进，确保同一分片无论被请求多少次，只会上传一次。"

### "分布式锁怎么实现的？为什么不用 synchronized？"
> "使用 Redisson 的分布式锁，底层基于 Redis 的 SET NX EX 命令实现。不使用 synchronized 是因为它是 JVM 级别的锁，只能锁单实例。如果部署多个 API 实例，synchronized 就失效了。Redisson 的锁存储在 Redis 中，所有实例共享，真正实现了跨进程互斥。而且还支持 Watchdog 自动续期，防止业务没执行完锁就过期。"

### "断点续传怎么实现的？"
> "核心是文件指纹 + 会话复用。前端用 Web Crypto API 对文件头中尾各采样 2MB 计算 SHA-256 作为文件指纹，调 init 时带上。服务端按 fileHash 查找同文件的 UPLOADING 会话，如果有就返回旧 uploadId 和已传分片列表，前端跳过已传分片只上传剩余部分。采样 hash 而非全量 hash 是为了性能——500MB 文件全量计算要好几秒，采样只需要毫秒级。"

### "为什么上传完不直接创建任务，要拆成两步？"
> "这是两阶段提交的设计思路，把上传和任务解耦。用户上传完视频后可能还想定制分析策略，比如指定分析视频中的动作细节或提取文字内容。拆成两步后，用户在确认面板输入 prompt 再提交，体验更好。技术上也让上传模块和任务模块职责更清晰。"

## 九、STAR 面试题：断点续传 + 秒传设计

### Situation（场景）
> 视频分析平台需要支持大文件上传（最大 5GB），用户在上传过程中经常遇到网络中断、浏览器崩溃、服务重启等情况，导致上传进度丢失，用户体验极差。尤其是 500MB 以上的视频，用户需要重新从头上传，耗时又耗流量。

### Task（任务）
> 需要设计一个断点续传机制，满足：
> 1. 网络中断后重新上传同一文件，能从断点继续，不需要重头开始
> 2. 已完整上传过的文件，再次上传时直接"秒传"，不传输任何数据
> 3. 不能引入额外的客户端依赖（纯 HTML/JS SPA）

### Action（行动）

**1. 文件指纹设计（采样 SHA-256）**
> - 使用浏览器原生 `crypto.subtle.digest` 计算文件指纹，无需第三方库
> - 采用采样策略：取文件头部、中部、尾部各 2MB 拼接后计算 SHA-256，再追加文件 size 防碰撞
> - 500MB 文件全量 hash 需要 3-5 秒，采样 hash 只需 ~50ms

**2. 后端会话匹配（三种结果）**
> - `init` 接口接收 fileHash，按 hash 查找已有会话
> - MERGED 状态 → 秒传（直接复用文件）
> - UPLOADING 状态 → 断点续传（返回已传分片列表）
> - 无匹配 → 新建会话

**3. 前端分片跳过**
> - init 返回 `uploadedChunks: [0,1,2,5,6]`
> - 前端构建 pending 列表，跳过已传分片，只上传缺失的
> - 并发上传（3路），已有分片的进度直接显示

**4. 分片级幂等保障**
> - 快速检查 → Redisson 分布式锁 → 双重检查（DCL）
> - 确保同一分片无论被请求多少次，只上传一次

### Result（结果）
> - 断点续传：上传 500MB 视频中断后重传，只需上传剩余分片，实测节省 60-80% 上传时间
> - 秒传：同一文件二次上传，0 数据传输，毫秒级完成
> - 零依赖：纯 Web Crypto API 实现，无第三方库
> - 采样 hash 性能：500MB 文件指纹计算 ~50ms（全量 MD5 需要 3-5 秒）