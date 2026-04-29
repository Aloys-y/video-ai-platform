# AI视频内容理解平台

> 一个生产级的高并发视频分析系统，专为复盘总结设计。

## 项目简介

本项目是一个**AI视频内容理解平台**，支持大文件上传、异步处理、AI智能分析。设计目标：

- **生产级架构**：消息队列解耦、分库分表设计、限流熔断
- **高并发支持**：支持万级QPS上传和并发处理
- **可靠性保证**：断点续传、失败重试、消息不丢失
- **成本可控**：智能抽帧、配额管理、费用预估

## 技术栈

| 类别 | 技术选型 | 版本 |
|------|---------|------|
| 核心框架 | Spring Boot | 3.2.4 |
| 数据库 | MySQL + MyBatis-Plus | 8.0 / 3.5.5 |
| 缓存 | Redis + Redisson | 7.0 / 3.27.0 |
| 消息队列 | Kafka | 3.6.x |
| 对象存储 | MinIO | 8.5.9 |
| AI服务 | 智谱 GLM-4.6V (ZhipuAiClient SDK) | 0.3.3 |
| 接口文档 | SpringDoc OpenAPI (Swagger UI) | 2.3.0 |
| 前端 | 纯 HTML/CSS/JS SPA | - |

## 项目结构

```
DoVideoAI/
├── docs/                    # 架构设计文档 + 面试考点
├── frontend/               # 前端SPA（HTML/CSS/JS）
├── video-api/              # API服务（用户交互入口，port 8080）
├── video-worker/           # Worker服务（异步任务处理，port 8081）
├── video-common/           # 公共模块（领域模型、DTO、枚举、消息类型）
├── video-infrastructure/   # 基础设施（MySQL、Redis、Kafka、MinIO配置）
├── sql/                    # 数据库脚本
└── docker/                 # Docker Compose 配置
```

## 快速开始

### 1. 启动基础服务

```bash
cd docker
docker-compose up -d
```

### 2. 初始化数据库

数据库会在MySQL启动时自动初始化，也可以手动执行：

```bash
mysql -u root -p < sql/schema.sql
```

### 3. 编译项目

```bash
mvn clean install -DskipTests
```

### 4. 启动服务

启动API服务：
```bash
cd video-api
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

启动Worker服务：
```bash
cd video-worker
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

启动前端（纯静态文件，任选一种方式）：
```bash
# 方式1：直接浏览器打开 frontend/index.html（需要 API 服务在 8080 跑着）

# 方式2：用 Node.js 启动 HTTP 服务（需要安装 Node.js）
cd frontend
npx serve -l 3000 -s .
```

### 5. 访问服务

- 前端页面: http://localhost:3000（方式2）或直接打开 frontend/index.html（方式1）
- Swagger UI: http://localhost:8080/swagger-ui.html
- MinIO控制台: http://localhost:9001 (minioadmin/minioadmin)
- Kafka UI: http://localhost:8090
- Druid监控: http://localhost:8080/api/druid (admin/admin)

## 已实现功能

### 认证体系（双模式）

支持两种认证方式，可按场景选用：

**用户名密码认证**
```bash
# 注册
POST /api/auth/register
{
    "username": "testuser",
    "email": "test@example.com",
    "password": "123456"
}

# 登录
POST /api/auth/login
{
    "username": "testuser",
    "password": "123456"
}
# 返回 JWT Token

# 生成 API Key
GET /api/auth/api-key
Authorization: Bearer <token>
```

**API Key 认证**
```bash
# 使用 API Key 直接调用接口
GET /api/xxx
X-API-Key: <your-api-key>
```

### 限流保护

- **全局限流**：Guava 令牌桶，保护系统整体负载
- **用户级限流**：Redis 原子计数器，按用户维度限流
- **端点级限流**：按接口维度精细控制

### 视频上传

支持大文件分片上传（20MB分片，3并发），完整流程：

**1. 初始化上传**
```bash
POST /api/upload/init
Authorization: Bearer <token>
{
    "fileName": "video.mp4",
    "fileSize": 1073741824,
    "chunkSize": 20971520
}
# 返回 uploadId, totalChunks, chunkSize
```

**2. 上传分片**（可并发）
```bash
POST /api/upload/chunk
X-Upload-Id: <uploadId>
X-Chunk-Index: 0
Content-Type: multipart/form-data
file: <binary>
```

**3. 完成上传（合并分片）**
```bash
POST /api/upload/complete
X-Upload-Id: <uploadId>
# 合并分片，返回 uploadId
```

**4. 提交分析任务（用户确认后）**
```bash
POST /api/upload/submit?prompt=分析视频内容
X-Upload-Id: <uploadId>
# 创建分析任务，返回 taskId
```

### 任务管理

```bash
# 查询任务列表
GET /api/task/list?page=1&size=10
Authorization: Bearer <token>

# 查询任务详情
GET /api/task/{taskId}
Authorization: Bearer <token>

# 重命名任务
PUT /api/task/{taskId}/rename
{ "taskName": "新名称" }

# 重试失败任务
POST /api/task/{taskId}/retry

# 删除任务
DELETE /api/task/{taskId}
```

### AI视频分析

- **模型**：智谱 GLM-4.6V，原生支持 video_url 视频理解
- **格式**：仅支持 MP4
- **Prompt**：用户可自定义分析提示词
- **重试**：429限流自动重试（最多3次，间隔10s/30s/60s）
- **流程**：上传 → 用户确认 → 创建任务 → Kafka → Worker → 生成预签名URL → 调用AI → 存储结果

### 前端界面

- 纯 HTML/CSS/JS SPA，无框架依赖
- 页面：登录注册、任务列表、视频上传（拖拽+进度条+确认面板）、任务详情
- AI结果 Markdown 渲染（marked.js）
- 认证：JWT 自动管理，Token 过期自动跳转登录

## 架构亮点

| 亮点 | 说明 | 状态 |
|------|------|------|
| **双认证体系** | JWT Bearer Token + API Key，灵活适配不同场景 | ✅ |
| **多级限流** | Guava 全局 + Redis 用户级 + 端点级 | ✅ |
| **统一响应** | ApiResponse 统一包装，ErrorCode 结构化错误码 | ✅ |
| **分片上传+秒传** | Redisson分布式锁双重检查，20MB分片3并发 | ✅ |
| **两阶段任务创建** | 上传与任务解耦，用户确认后才创建任务 | ✅ |
| **Kafka消息队列** | 削峰填谷，死信队列，offset手动提交 | ✅ |
| **状态机驱动** | TaskStatus状态机控制任务流转，天然幂等 | ✅ |
| **AI集成** | 智谱GLM-4.6V，429自动重试，用户自定义prompt | ✅ |
| **前端SPA** | 纯HTML/CSS/JS，拖拽上传，实时进度 | ✅ |
| **成本控制** | 预估扣费、配额管理 | 🔲 |

## 核心流程

```
用户上传视频 → 分片上传到MinIO → 合并完成
     → 用户输入prompt并确认 → 创建分析任务
     → Kafka消息 → Worker消费
     → 生成MinIO预签名URL → 调用智谱GLM-4.6V API
     → 解析结果 → 存储到MySQL → 前端轮询展示
```

## 开发进度

- [x] 项目架构设计 & 多模块搭建
- [x] 数据库设计（5张表）
- [x] 基础设施层（MyBatis-Plus、Redis、Kafka、MinIO）
- [x] 用户注册 & 登录（JWT + BCrypt）
- [x] 双认证体系（JWT Bearer + API Key）
- [x] 多级限流（Guava 全局 + Redis 用户级 + 端点级）
- [x] SpringDoc OpenAPI (Swagger UI) 接口文档
- [x] CORS 跨域配置
- [x] 上传功能
  - [x] 分片上传（20MB分片，3并发，Redisson分布式锁）
  - [x] 断点续传（已传分片索引追踪）
  - [x] 秒传（文件Hash去重）
  - [x] MinIO Compose API 服务端合并
  - [x] 两阶段提交（上传完成 → 用户确认 → 创建任务）
- [x] 任务处理
  - [x] Kafka消息生产与消费
  - [x] Worker消费逻辑（状态机驱动）
  - [x] 死信队列处理
  - [x] 分布式锁防并发
- [x] AI分析
  - [x] 智谱GLM-4.6V集成（video_url原生支持）
  - [x] 429限流自动重试
  - [x] 用户自定义prompt
- [x] 前端界面
  - [x] 登录注册
  - [x] 任务列表 + 分页
  - [x] 视频上传（拖拽+进度条+确认面板）
  - [x] 任务详情 + 结果展示
  - [x] 任务管理（重命名、重试、删除）
- [ ] 功能增强
  - [ ] 大视频支持（GLM-4V-Plus 或 FFmpeg压缩）
  - [ ] 配额管理与费用预估
  - [ ] WebSocket实时推送

## 面试要点

详见 [docs/](docs/) 目录下的系列文档：
- [系统架构设计](docs/01-系统架构设计.md)
- [认证体系](docs/chapter-02-auth.md)
- [限流设计](docs/chapter-03-ratelimit.md)
- [异常处理](docs/chapter-04-exception.md)
- [上传功能](docs/chapter-05-upload.md)
- [AI SDK 429重试](docs/chapter-06-ai-sdk-429.md)

## License

MIT
