# AI视频内容理解平台 - 开发计划 v2

> 最后更新: 2026-04-19
> 当前阶段: Phase 2 - 任务处理（Worker消费）

---

## 开发原则

1. **每个功能开发完必须测试** - 确保功能正常
2. **测试通过后立即提交Git** - 小步快跑
3. **每个Phase有明确的验收标准** - 面试可讲解
4. **必须用 feature 分支开发，PR 合入 main** - 禁止直接推 main

---

## Phase 0: 环境准备 ✅ DONE

- [x] 项目结构搭建
- [x] Maven多模块配置（video-common / video-infrastructure / video-api / video-worker）
- [x] 基础领域模型（User / UploadSession / AnalysisTask / UserQuota）
- [x] 架构设计文档
- [x] Git初始化
- [x] Docker Compose（MySQL 8.0 / Redis 7.0 / Kafka 7.5 / MinIO / Kafka UI）

---

## Phase 0.5: 用户鉴权与限流 ✅ DONE

### 0.5.1 用户模块 ✅
| 决策 | 选择 | 原因 |
|------|------|------|
| 认证方式 | JWT + API Key 双认证 | JWT用于前后端，API Key用于开放平台 |
| 密码存储 | BCrypt | 业界标准，防彩虹表 |
| 用户表 | 最小字段 | 只存必要信息，演示用 |

**已完成任务**:
- [x] User实体类 + UserMapper
- [x] UserService（注册/登录/API Key生成）
- [x] AuthController（register / login / api-key）
- [x] JwtUtil（HMAC-SHA，jjwt 0.12.5）

### 0.5.2 鉴权拦截器 ✅
- [x] AuthInterceptor（JWT Bearer + API Key 双模式，优先 JWT）
- [x] UserContext（ThreadLocal 上下文，afterCompletion 清理）
- [x] WebMvcConfig（拦截器注册 + CORS + 公共路径放行）

### 0.5.3 多级限流 ✅
- [x] 全局限流（Guava RateLimiter，1000 QPS）
- [x] 用户限流（Redis 原子计数器，100 QPS）
- [x] 接口限流（上传接口 10 QPS）

### 0.5.4 安全与文档 ✅
- [x] 全局异常处理（GlobalExceptionHandler + ErrorCode 25+错误码）
- [x] SpringDoc OpenAPI（Swagger UI，双安全方案）
- [x] Swagger 路径放行配置

**验收标准**: ✅ 全部通过
- [x] 无认证访问返回401
- [x] JWT和API Key均可认证
- [x] 超过限流阈值返回429
- [x] 用户信息可通过UserContext获取

---

## Phase 1: 分片上传功能 ✅ DONE

### 1.1 基础上传接口 ✅
| 任务 | 状态 | 说明 |
|------|------|------|
| MinIO存储服务 (StorageService) | ✅ 完成 | 对象存储封装（put/compose/remove） |
| UploadService | ✅ 完成 | 上传业务逻辑（init/chunk/complete/status） |
| UploadController | ✅ 完成 | REST接口 |
| UploadSessionMapper | ✅ 完成 | 自定义SQL（JSON_ARRAY_APPEND原子追加） |

**接口设计**:
```
POST /api/upload/init      - 初始化上传，返回uploadId
POST /api/upload/chunk     - 上传分片（X-Upload-Id + X-Chunk-Index）
POST /api/upload/complete  - 完成上传，MinIO服务端合并
GET  /api/upload/status/{uploadId} - 查询上传进度
```

### 1.2 断点续传 ✅
- [x] 已上传分片记录（MySQL JSON数组存储）
- [x] Redis分布式锁（Redisson，按uploadId+chunkIndex加锁）
- [x] Redis进度缓存

### 1.3 秒传 ✅
- [x] 文件MD5秒传检测（相同hash直接复用）
- [x] UploadSessionMapper.fileHashLookup

### 1.4 任务创建 ✅
- [x] 上传完成后自动创建AnalysisTask
- [x] Kafka消息发送到 videoai.task.analyze
- [x] ID生成器（upload_{timestamp}_{random}，日期分区存储路径）

**验收标准**: ✅ 全部通过
- [x] 可成功上传50MB以上文件
- [x] 分片上传中断后可续传
- [x] 所有分片可正确合并（MinIO ComposeObject）
- [x] 上传完成后自动创建分析任务并发Kafka消息

---

## Phase 2: 任务处理 🚧 NEXT

### 2.1 Worker消费
**目标**: 实现Kafka消费者处理分析任务
| 任务 | 状态 | 说明 |
|------|------|------|
| TaskConsumer | ⬜ 待开始 | Kafka消费（videoai.task.analyze） |
| 任务状态更新 | ⬜ 待开始 | 状态机流转（PENDING→PROCESSING→COMPLETED/FAILED） |
| 重试机制 | ⬜ 待开始 | 死信队列（videoai.task.dead） |
| 并发控制 | ⬜ 待开始 | Worker并发数配置（当前: 5） |

### 2.2 视频抽帧
| 任务 | 状态 | 说明 |
|------|------|------|
| FrameExtractor | ⬜ 待开始 | FFmpeg抽帧（2秒间隔，最大1280x720） |
| 帧图片存储 | ⬜ 待开始 | 存MinIO |

### 2.3 AI分析
| 任务 | 状态 | 说明 |
|------|------|------|
| ClaudeClient | ⬜ 待开始 | API封装 |
| AIAnalyzer | ⬜ 待开始 | 分析逻辑 |
| 成本预估 | ⬜ 待开始 | Token计算 + AI QPS限制（当前: 2） |

---

## Phase 3: 结果与查询

### 3.1 结果存储
| 任务 | 状态 | 说明 |
|------|------|------|
| ResultService | ⬜ 待开始 | 结果处理 + Kafka消息（videoai.task.result） |
| 结果聚合 | ⬜ 待开始 | 多帧分析结果合并 |

### 3.2 查询接口
| 任务 | 状态 | 说明 |
|------|------|------|
| TaskController | ⬜ 待开始 | 任务查询/列表 |
| ResultController | ⬜ 待开始 | 结果查询 |
| 缓存优化 | ⬜ 待开始 | Redis缓存 |

### 3.3 配额控制
| 任务 | 状态 | 说明 |
|------|------|------|
| UserQuotaService | ⬜ 待开始 | 配额管理 |
| AI调用日志 | ⬜ 待开始 | ai_call_log表使用 |
| 定时任务 | ⬜ 待开始 | 上传过期清理/配额重置 |

---

## Phase 4: 高级特性

### 4.1 监控
- [ ] 集成Actuator
- [ ] 自定义Metrics

### 4.2 前端（可选）
- [ ] Vue3上传组件

---

## 当前进度

```
Phase 0:   ████████████████████ 100% ✅ 环境准备
Phase 0.5: ████████████████████ 100% ✅ 鉴权与限流
Phase 1:   ████████████████████ 100% ✅ 分片上传
Phase 2:   ░░░░░░░░░░░░░░░░░░░░   0% 🚧 任务处理（下一步）
Phase 3:   ░░░░░░░░░░░░░░░░░░░░   0%
Phase 4:   ░░░░░░░░░░░░░░░░░░░░   0%
```

**整体进度**: ~45%

---

## 开发日志

### 2026-04-19
- 修复Kafka监听端口配置（fix/kafka-listener-port分支）

### 2026-04-18
- Swagger路径放行 + README上传功能文档更新

### 2026-04-10
- 修复全局异常处理缺失、API Key脱敏、traceId空值问题

### 2026-04-08
- 完成项目初始化
- 完成用户鉴权模块（JWT + API Key双认证 + 三级限流）
- 完成视频分片上传（MinIO + Redisson分布式锁 + 秒传 + Kafka消息）
- 完成全局异常处理 + Swagger集成
