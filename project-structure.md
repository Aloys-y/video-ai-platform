# AI视频内容理解平台 - 项目结构

## 项目目录结构

```
video-ai-platform/
│
├── docs/                                    # 项目文档
│   ├── 01-系统架构设计.md
│   ├── 02-数据库设计.md
│   ├── 03-API接口设计.md
│   ├── 04-部署运维.md
│   └── 05-面试要点.md
│
├── video-api/                               # API服务模块（端口: 8080）
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/videoai/api/
│       │   │   ├── VideoApiApplication.java    # 启动类
│       │   │   │
│       │   │   ├── controller/                 # 控制器层
│       │   │   │   ├── UploadController.java   # 上传接口
│       │   │   │   ├── TaskController.java     # 任务管理接口
│       │   │   │   └── ResultController.java   # 结果查询接口
│       │   │   │
│       │   │   ├── service/                    # 业务层
│       │   │   │   ├── UploadService.java      # 上传服务
│       │   │   │   ├── TaskService.java        # 任务服务
│       │   │   │   └── ResultService.java      # 结果服务
│       │   │   │
│       │   │   ├── producer/                   # 消息生产者
│       │   │   │   └── TaskMessageProducer.java
│       │   │   │
│       │   │   ├── limiter/                    # 限流组件
│       │   │   │   ├── RateLimiterAspect.java
│       │   │   │   └── RateLimiter.java
│       │   │   │
│       │   │   ├── config/                     # 配置类
│       │   │   │   ├── WebMvcConfig.java
│       │   │   │   ├── RedisConfig.java
│       │   │   │   └── KafkaConfig.java
│       │   │   │
│       │   │   └── exception/                  # 异常处理
│       │   │       ├── GlobalExceptionHandler.java
│       │   │       └── BusinessException.java
│       │   │
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-dev.yml
│       │       └── application-prod.yml
│       │
│       └── test/java/com/videoai/api/
│           ├── service/
│           │   └── UploadServiceTest.java
│           └── controller/
│               └── UploadControllerTest.java
│
├── video-worker/                             # Worker服务模块（端口: 8081）
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/videoai/worker/
│       │   │   ├── VideoWorkerApplication.java # 启动类
│       │   │   │
│       │   │   ├── consumer/                   # 消息消费者
│       │   │   │   └── TaskConsumer.java
│       │   │   │
│       │   │   ├── processor/                  # 核心处理器
│       │   │   │   ├── VideoProcessor.java     # 视频处理入口
│       │   │   │   ├── FrameExtractor.java     # 视频抽帧
│       │   │   │   ├── AIAnalyzer.java         # AI分析
│       │   │   │   └── ResultAggregator.java   # 结果聚合
│       │   │   │
│       │   │   ├── retry/                      # 重试机制
│       │   │   │   └── RetryPolicy.java
│       │   │   │
│       │   │   ├── callback/                   # 回调通知
│       │   │   │   └── TaskCallback.java
│       │   │   │
│       │   │   └── config/
│       │   │       └── WorkerConfig.java
│       │   │
│       │   └── resources/
│       │       └── application.yml
│       │
│       └── test/java/com/videoai/worker/
│           └── processor/
│               └── FrameExtractorTest.java
│
├── video-common/                             # 公共模块
│   ├── pom.xml
│   └── src/
│       └── main/java/com/videoai/common/
│           ├── domain/                        # 领域模型
│           │   ├── UploadSession.java          # 上传会话
│           │   ├── AnalysisTask.java           # 分析任务
│           │   └── UserQuota.java              # 用户配额
│           │
│           ├── dto/                           # 数据传输对象
│           │   ├── request/
│           │   │   ├── InitUploadRequest.java
│           │   │   ├── UploadChunkRequest.java
│           │   │   └── CompleteUploadRequest.java
│           │   └── response/
│           │       ├── UploadSessionResponse.java
│           │       ├── TaskResponse.java
│           │       └── AnalysisResultResponse.java
│           │
│           ├── enums/                         # 枚举
│           │   ├── TaskStatus.java            # 任务状态
│           │   ├── UploadStatus.java          # 上传状态
│           │   └── ErrorCode.java             # 错误码
│           │
│           ├── message/                       # 消息定义
│           │   └── TaskMessage.java           # 任务消息
│           │
│           ├── exception/                     # 异常
│           │   └── BaseException.java
│           │
│           └── utils/                         # 工具类
│               ├── FileHashUtil.java          # 文件哈希
│               ├── IdGenerator.java           # ID生成器
│               └── JsonUtil.java              # JSON工具
│
├── video-infrastructure/                     # 基础设施模块
│   ├── pom.xml
│   └── src/
│       └── main/java/com/videoai/infra/
│           ├── mysql/                         # MySQL相关
│           │   ├── config/
│           │   │   └── DataSourceConfig.java
│           │   └── mapper/
│           │       ├── UploadSessionMapper.java
│           │       ├── AnalysisTaskMapper.java
│           │       └── UserQuotaMapper.java
│           │
│           ├── redis/                         # Redis相关
│           │   ├── config/
│           │   │   └── RedisConfig.java
│           │   ├── service/
│           │   │   ├── RedisService.java
│           │   │   └── DistributedLock.java
│           │   └── key/
│           │       └── RedisKey.java          # Key定义
│           │
│           ├── kafka/                         # Kafka相关
│           │   ├── config/
│           │   │   └── KafkaConfig.java
│           │   └── topic/
│           │       └── TopicConstant.java     # Topic定义
│           │
│           ├── oss/                           # 对象存储
│           │   ├── config/
│           │   │   └── OssConfig.java
│           │   └── service/
│           │       ├── OssService.java
│           │       └── MinioService.java
│           │
│           └── ai/                            # AI服务
│               ├── config/
│               │   └── AIConfig.java
│               └── client/
│                   └── ClaudeClient.java
│
├── video-gateway/                            # 网关模块（可选，端口: 9000）
│   ├── pom.xml
│   └── src/
│       └── main/
│           ├── java/com/videoai/gateway/
│           │   ├── GatewayApplication.java
│           │   ├── filter/
│           │   │   ├── AuthFilter.java
│           │   │   └── RateLimitFilter.java
│           │   └── config/
│           │       └── GatewayConfig.java
│           └── resources/
│               └── application.yml
│
├── frontend/                                 # 前端项目（Vue3）
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── main.js
│       ├── App.vue
│       ├── views/
│       │   ├── Upload.vue                    # 上传页面
│       │   ├── TaskList.vue                  # 任务列表
│       │   └── Result.vue                    # 结果展示
│       ├── components/
│       │   ├── ChunkUploader.vue             # 分片上传组件
│       │   ├── ProgressBar.vue               # 进度条组件
│       │   └── VideoPlayer.vue               # 视频播放器
│       ├── api/
│       │   ├── upload.js                     # 上传API
│       │   └── task.js                       # 任务API
│       ├── stores/
│       │   └── upload.js                     # Pinia状态管理
│       └── utils/
│           ├── chunkUpload.js                # 分片上传工具
│           └── websocket.js                  # WebSocket工具
│
├── sql/                                      # SQL脚本
│   ├── schema.sql                            # 表结构
│   └── init-data.sql                         # 初始化数据
│
├── docker/                                   # Docker配置
│   ├── docker-compose.yml                    # 开发环境编排
│   ├── mysql/
│   │   └── my.cnf
│   ├── redis/
│   │   └── redis.conf
│   └── kafka/
│       └── server.properties
│
├── pom.xml                                   # 父POM
├── README.md                                 # 项目说明
└── .gitignore
```

## 模块依赖关系

```
                    ┌─────────────────┐
                    │  video-gateway  │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
       ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
       │  video-api   │ │ video-worker │ │   frontend   │
       └──────┬───────┘ └──────┬───────┘ └──────────────┘
              │                │
              └───────┬────────┘
                      ▼
              ┌──────────────┐
              │video-common  │
              └──────┬───────┘
                     │
                     ▼
              ┌──────────────────┐
              │video-infrastructure│
              └──────────────────┘
```

## 技术栈版本

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17 | LTS版本 |
| Spring Boot | 3.2.x | 最新稳定版 |
| MySQL | 8.0 | |
| Redis | 7.0 | |
| Kafka | 3.6 | |
| Vue | 3.4 | |
| MinIO | latest | 对象存储 |

## 开发顺序

1. **Phase 1: 基础搭建**
   - 创建Maven父工程和子模块
   - 配置数据库表结构
   - 实现基础设施层（MySQL、Redis、Kafka配置）

2. **Phase 2: 上传功能**
   - 实现分片上传接口
   - 实现断点续传
   - 实现秒传（MD5去重）

3. **Phase 3: 任务处理**
   - 实现Kafka消息生产
   - 实现Worker消费逻辑
   - 实现视频抽帧

4. **Phase 4: AI分析**
   - 集成AI服务
   - 实现成本控制
   - 实现结果聚合

5. **Phase 5: 优化完善**
   - 添加限流
   - 添加监控
   - 前端界面

---

*下一步：创建Maven父工程和基础模块结构*
