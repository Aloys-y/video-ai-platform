# DoVideoAI 已完成模块学习规划

> 基于 5 个已完成迭代，按学习顺序排列，每个模块建议 1-2 天

---

## 第1天：项目架构 & 多模块设计

**学习目标**：理解整体分层，搞清楚请求从入口到数据库的完整链路

**要看的文件**：
- `pom.xml`（根 + 各子模块） — 依赖关系、模块划分
- `video-api/pom.xml` / `video-infrastructure/pom.xml` — 依赖怎么传递
- `sql/schema.sql` — 5张表的字段设计，为什么这么分

**核心问题**：
1. 为什么要分 video-api / video-worker / video-common / video-infrastructure 四个模块？各模块的职责边界是什么？
2. `video-common` 和 `video-infrastructure` 都放公共代码，区别是什么？
3. 如果未来要加一个 admin 管理后台模块，怎么复用现有代码？

**面试高频**：
- 分层架构的好处（职责单一、独立部署、依赖方向）
- Maven 依赖传递和 `<dependencyManagement>` 的区别

---

## 第2天：认证体系（JWT + API Key）

**学习目标**：掌握 JWT 原理和双认证拦截器的设计

**要看的文件**（按阅读顺序）：
1. `video-api/.../util/JwtUtil.java` — JWT 生成/解析/验证
2. `video-api/.../service/AuthService.java` — 注册登录业务逻辑
3. `video-api/.../interceptor/AuthInterceptor.java` — 拦截器双模式认证
4. `video-api/.../config/WebMvcConfig.java` — 拦截器注册和路径排除
5. `video-common/.../context/UserContext.java` — ThreadLocal 存储用户

**核心问题**：
1. JWT 的结构是什么？Header/Payload/Signature 各存什么？为什么不需要服务端存储 Session？
2. 拦截器里 JWT 和 API Key 的判断优先级是什么？为什么要这样设计？
3. `UserContext` 用 ThreadLocal 存用户信息，如果用异步线程（如 @Async）会出什么问题？
4. BCrypt 和 MD5 的本质区别是什么？为什么 BCrypt 更安全？
5. API Key 生成后存在数据库里的是什么形式？为什么注册返回完整 key，登录只返回脱敏的？

**自己动手**：
- 用 Swagger 注册一个用户，观察返回的 API Key 格式
- 用 JWT Token 和 API Key 分别调用一个接口，对比请求头
- 故意传过期的 JWT Token，观察返回的错误码

**面试高频**：
- JWT vs Session 各自优缺点
- 无状态认证的优缺点
- Token 泄露了怎么办？（黑名单、短期过期）

---

## 第3天：限流 & 安全

**学习目标**：理解两级限流的设计理由和安全防护要点

**要看的文件**：
1. `video-api/.../interceptor/RateLimitInterceptor.java` — 限流拦截器
2. `video-api/.../config/SecurityConfig.java` 或相关配置 — CORS、安全头

**核心问题**：
1. 为什么不全用 Redis 限流？Guava RateLimiter 作为第一道防线的好处是什么？
2. 令牌桶和漏桶算法的区别？本项目用的哪个？
3. Redis 原子计数器限流的 Lua 脚本（如果有）为什么需要原子性？
4. CORS 为什么要限制域名而不是用 `*`？

**面试高频**：
- 如何设计一个分布式限流方案
- 突发流量怎么应对（限流 + 熔断 + 降级）
- XSS / SQL 注入 / CSRF 防护手段

---

## 第4天：全局异常处理 & 统一响应

**学习目标**：理解 Spring 的异常处理机制和错误码设计

**要看的文件**：
1. `video-common/.../exception/BusinessException.java` — 业务异常类
2. `video-api/.../exception/GlobalExceptionHandler.java` — 全局异常处理器
3. `video-common/.../dto/response/ApiResponse.java` — 统一响应包装
4. `video-common/.../enums/ErrorCode.java` — 错误码枚举

**核心问题**：
1. `@RestControllerAdvice` + `@ExceptionHandler` 的原理是什么？为什么能捕获所有 Controller 的异常？
2. BusinessException 和 RuntimeException 的区别？为什么不直接 throw RuntimeException？
3. ErrorCode 的错误码分段设计（1xxxx/2xxxx/3xxxx/4xxxx）好处是什么？
4. `ApiResponse.error()` 里 traceId 的作用？怎么实现的？

**自己动手**：
- 调用一个不存在的接口，观察返回的 JSON 结构
- 故意触发一个参数校验错误（比如传空用户名注册），对比和业务错误的响应区别

---

## 第5-6天：分片上传（重点）

**学习目标**：完整掌握大文件上传的设计，这是本项目最核心的模块

**要看的文件**（按阅读顺序）：
1. `video-common/.../domain/UploadSession.java` — 领域模型，理解字段含义
2. `video-common/.../enums/UploadStatus.java` — 状态枚举（UPLOADING/COMPLETED/MERGED/FAILED）
3. `video-infrastructure/.../mapper/UploadSessionMapper.java` — 自定义SQL，重点看 `appendUploadedChunk`
4. `video-infrastructure/.../minio/config/MinioConfig.java` — MinIO 配置
5. `video-infrastructure/.../minio/service/StorageService.java` — 对象存储操作
6. `video-api/.../service/UploadService.java` — **核心！** 逐行看
7. `video-api/.../controller/UploadController.java` — 接口层

**核心问题**：
1. **分片上传的完整流程**：init 返回什么？chunk 怎么传？complete 做了什么？
2. **秒传原理**：fileHash 匹配到已有 MERGED 状态的记录就直接返回，为什么状态必须是 MERGED？
3. **断点续传**：客户端断了怎么恢复？init 接口返回的 `uploadedChunks` 是从哪来的？
4. **Redisson 锁**：
   - 为什么用 `tryLock(5, 30, SECONDS)` 而不是 `lock()`？
   - watchdog 是什么？什么场景下需要续期？
   - 双重检查为什么放在锁里面而不是外面？
   - `isHeldByCurrentThread()` 不判断直接 unlock 会怎样？
5. **MinIO Compose**：为什么用服务端合并而不是下载到本地再上传？
6. **appendUploadedChunk** 的 SQL `JSON_ARRAY_APPEND` 是原子操作吗？和 `SELECT + UPDATE` 的区别？

**画个流程图**（用纸笔画也行）：
```
客户端                    服务端                         存储/数据库
  |                        |                              |
  |-- POST /init --------->|-- 创建 UploadSession ------->| MySQL INSERT
  |<-- uploadId -----------|                              |
  |                        |                              |
  |-- POST /chunk (0) ---->|-- Redisson tryLock --------->| Redis
  |                        |-- 上传分片 ----------------->| MinIO putObject
  |                        |-- 追加已传索引 ------------->| MySQL JSON_ARRAY_APPEND
  |                        |-- unlock -------------------->| Redis
  |<-- progress 33% ------|                              |
  |                        |                              |
  |-- POST /chunk (1) ---->|  (同上)                      |
  |-- POST /chunk (2) ---->|  (同上)                      |
  |                        |                              |
  |-- POST /complete ----->|-- 更新状态 COMPLETED ------->| MySQL
  |                        |-- MinIO 合并分片 ----------->| MinIO composeObject
  |                        |-- 清理分片文件 ------------->| MinIO removeObject
  |                        |-- 更新路径 MERGED ----------->| MySQL
  |                        |-- 创建分析任务 ------------->| MySQL + Kafka
  |<-- taskId ------------|                              |
```

**面试高频**：
- 大文件上传方案设计（分片 + 断点续传 + 秒传）
- 分布式锁的实现方式对比（Redis setnx / Redisson / Zookeeper）
- 对象存储选型（MinIO vs OSS vs S3）
- 如何保证上传的幂等性

---

## 第7天：复习 & 模拟面试

**复习方式**：
1. 关掉所有代码，在纸上画出系统的请求链路：`请求 → 限流 → 认证 → Controller → Service → Mapper/Redis/MinIO → 响应`
2. 对着每个模块自问自答上面的核心问题
3. 重点复习：JWT认证流程、分片上传流程、Redisson锁原理

**模拟面试题**：
- "介绍一下你的项目架构"
- "JWT 认证是怎么实现的？Token 过期了怎么办？"
- "大文件上传怎么做的？分片大小怎么选的？"
- "分布式锁用的什么方案？为什么选 Redisson？"
- "如果上传过程中 MinIO 挂了怎么办？"
- "你们项目的限流怎么做的？为什么用两级？"

---

## 学习建议

- **不要只看代码**：每个模块配合 Swagger 实际调用一遍，看请求和响应
- **改一行试一下**：比如把锁去掉并发上传同一分片，看看会不会出问题
- **记笔记用自己的话**：不要抄代码注释，用自己的理解复述
- **重点投入第5-6天**：分片上传是本项目最有深度的模块，也是面试最容易深问的
