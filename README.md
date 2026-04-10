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
| AI服务 | Claude API | - |
| 接口文档 | SpringDoc OpenAPI (Swagger UI) | 2.3.0 |
| 前端 | Vue 3 + Vite（规划中） | - |

## 项目结构

```
DoVideoAI/
├── docs/                    # 架构设计文档
├── video-api/              # API服务（用户交互入口）
├── video-worker/           # Worker服务（后台任务处理）
├── video-common/           # 公共模块（领域模型、工具类）
├── video-infrastructure/   # 基础设施（MySQL、Redis、Kafka配置）
├── sql/                    # 数据库脚本
└── docker/                 # Docker配置
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
mvn spring-boot:run
```

启动Worker服务：
```bash
cd video-worker
mvn spring-boot:run
```

### 5. 访问服务

- Swagger UI: http://localhost:8080/swagger-ui.html
- API服务: http://localhost:8080/api
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

### 接口文档

启动后访问 Swagger UI 查看完整的接口文档，支持在线调试：
- http://localhost:8080/swagger-ui.html

## 架构亮点

| 亮点 | 说明 | 状态 |
|------|------|------|
| **双认证体系** | JWT Bearer Token + API Key，灵活适配不同场景 | ✅ |
| **多级限流** | Guava 全局限流 + Redis 用户级限流 | ✅ |
| **统一响应** | ApiResponse 统一包装，ErrorCode 结构化错误码 | ✅ |
| **分片上传+断点续传** | 支持GB级大文件，网络中断可恢复 | 🔲 |
| **Kafka消息队列** | 削峰填谷，支持万级QPS | 🔲 |
| **成本控制** | 预估扣费、配额管理、智能抽帧 | 🔲 |
| **状态机** | 任务状态流转可控，避免状态混乱 | 🔲 |

## 开发进度

- [x] 项目架构设计 & 多模块搭建
- [x] 数据库设计（5张表）
- [x] 基础设施层（MyBatis-Plus、Redis、Kafka 常量）
- [x] 用户注册 & 登录（JWT + BCrypt）
- [x] 双认证体系（JWT Bearer + API Key）
- [x] 多级限流（Guava 全局 + Redis 用户级）
- [x] SpringDoc OpenAPI (Swagger UI) 接口文档
- [x] CORS 跨域配置
- [ ] 上传功能实现
  - [ ] 分片上传
  - [ ] 断点续传
  - [ ] 秒传
- [ ] 任务处理实现
  - [ ] Kafka消息生产
  - [ ] Worker消费逻辑
  - [ ] 视频抽帧
- [ ] AI分析实现
  - [ ] Claude API集成
  - [ ] 成本控制
  - [ ] 结果聚合
- [ ] 前端界面

## 面试要点

详见 [docs/01-系统架构设计.md](docs/01-系统架构设计.md)

## License

MIT
