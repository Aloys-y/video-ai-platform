# 面试要点速查

> 最后更新: 2026-04-19
> 覆盖已完成的所有功能模块

---

## Phase 0: 项目架构 ✅

| 要点 | 问题 | 答案要点 |
|------|------|---------|
| 多模块设计 | 为什么分多个模块？ | 职责分离、依赖管理、独立部署。common→infrastructure→api/worker |
| 依赖管理 | dependencyManagement作用？ | 统一版本、避免冲突、子模块按需引入 |
| 状态机 | 为什么用枚举实现状态机？ | 类型安全、封装行为、支持switch |
| ID生成 | 为什么不用UUID？ | 无序、索引不友好、太长。用 upload_{timestamp}_{random} |
| 数据库设计 | 5张表怎么设计的？ | user / upload_session / analysis_task / user_quota / ai_call_log |

---

## Phase 0.5: 认证与限流 ✅

### 双认证体系
| 要点 | 问题 | 答案要点 |
|------|------|---------|
| 双认证 | 为什么同时支持JWT和API Key？ | JWT用于前后端交互（有状态），API Key用于开放平台/第三方调用（无状态） |
| 认证优先级 | 拦截器怎么判断用哪种？ | 先检查 Authorization Bearer，再检查 X-API-Key |
| ThreadLocal | UserContext为什么要用ThreadLocal？ | 线程隔离、避免参数传递、必须在afterCompletion清理防内存泄漏 |
| BCrypt | 为什么用BCrypt不用MD5？ | 自带盐、可调成本因子、防彩虹表和暴力破解 |
| JWT | JWT的subject和claims放什么？ | subject=userId, claims=role+id，用HMAC-SHA签名 |

### 三级限流
| 要点 | 问题 | 答案要点 |
|------|------|---------|
| 全局限流 | 为什么用Guava RateLimiter？ | 单机足够、令牌桶算法、无网络开销 |
| 用户限流 | 为什么用Redis？ | 分布式场景共享、原子操作INCR+EXPIRE |
| 限流顺序 | 为什么先限流再认证？ | 减少无效认证开销、防DDoS要在认证前拦截 |
| 拦截器顺序 | RateLimit(order=1) vs Auth(order=2)？ | 先限流后认证，order越小越先执行 |

### 异常处理
| 要点 | 问题 | 答案要点 |
|------|------|---------|
| 全局异常 | @RestControllerAdvice怎么用？ | 统一捕获异常、BusinessException→业务错误码、MethodArgumentNotValidException→参数校验 |
| 错误码设计 | 为什么要分层错误码？ | 1xxxx系统/2xxxx上传/21xxx任务/22xxx配额/23xxx用户/3xxxx第三方/4xxxx限流 |
| traceId | 为什么要返回traceId？ | 便于日志追踪、问题定位、前后端联动排查 |
| API Key脱敏 | 返回API Key时为什么要脱敏？ | 安全性考虑，只显示前8位+***，防止日志/响应泄露 |

### Swagger集成
| 要点 | 问题 | 答案要点 |
|------|------|---------|
| SpringDoc | 为什么选SpringDoc不选Swagger2？ | Spring Boot 3不支持Swagger2、SpringDoc是官方推荐替代 |
| 双安全方案 | OpenAPI怎么配置双认证？ | @SecurityScheme定义Bearer JWT + API Key两种 |
| 路径放行 | 哪些路径不需要认证？ | /auth/**、/test/**、/swagger/**、/v3/api-docs/**、/druid/** |

---

## Phase 1: 分片上传 ✅

### 核心设计
| 要点 | 问题 | 答案要点 |
|------|------|---------|
| 分片大小 | 为什么选择5MB？ | 平衡网络效率和重传代价，小于MySQL默认max_allowed_packet |
| 服务端合并 | 为什么用MinIO ComposeObject？ | 避免下载到应用服务器再上传、减少带宽和内存开销、MinIO原生支持 |
| 断点续传 | 如何实现？ | uploadId + MySQL JSON数组记录已上传分片、前端查询后跳过已传分片 |
| 秒传 | 如何判断？ | 上传init时计算文件MD5、查数据库是否有相同hash的已完成上传 |
| 分布式锁 | 为什么选Redisson？ | 看门狗自动续期、比手写Lua更可靠、API简洁 |
| 并发安全 | 分片上传怎么保证线程安全？ | Redisson锁（uploadId+chunkIndex）+ MySQL JSON_ARRAY_APPEND原子操作 |

### 数据流
| 要点 | 问题 | 答案要点 |
|------|------|---------|
| 上传流程 | 完整的上传流程？ | init(校验+生成uploadId) → chunk(锁+存MinIO+记录) → complete(合并+清分片+创建任务+发Kafka) |
| 进度追踪 | 如何实时追踪进度？ | Redis缓存已传分片数/总分片数，MySQL持久化 |
| ID生成 | uploadId怎么生成的？ | upload_{timestamp}_{random}，存储路径按日期分区 |
| 过期处理 | 上传会话怎么过期？ | 24小时过期时间，待实现定时清理任务 |

---

## Phase 2: 消息队列（待实现）

| 要点 | 问题 | 答案要点 |
|------|------|---------|
| 为什么用Kafka | 不用RabbitMQ？ | 高吞吐、消息持久化、支持重放、适合日志/事件流 |
| 消息不丢失 | 如何保证？ | acks=all + 手动提交offset + 死信队列 |
| 消息重复 | 如何处理？ | 消费者幂等设计（taskId去重） |
| 分区策略 | 如何分区？ | 按taskId分区，保证同一任务的帧处理顺序 |
| Topic设计 | 4个Topic分别做什么？ | task.analyze(任务分发) / task.result(结果通知) / task.dead(死信) / task.event(状态变更) |

---

## Phase 3: 视频处理（待实现）

| 要点 | 问题 | 答案要点 |
|------|------|---------|
| 抽帧策略 | 为什么2秒一帧？ | 平衡分析精度和成本 |
| 成本预估 | 如何计算？ | 时长×帧率×每帧Token数 |
| 配额控制 | 如何防超扣？ | Redis原子操作 + 分布式锁 |
| 智能抽帧 | 如何省钱？ | 场景变化检测，减少50%帧数 |
