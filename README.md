# AI视频内容理解平台

> 一个生产级的高并发视频分析系统，专为中大厂后端面试设计。

## 项目简介

本项目是一个**AI视频内容理解平台**，支持大文件上传、异步处理、AI智能分析。设计目标：

- **生产级架构**：消息队列解耦、分库分表设计、限流熔断
- **高并发支持**：支持万级QPS上传和并发处理
- **可靠性保证**：断点续传、失败重试、消息不丢失
- **成本可控**：智能抽帧、配额管理、费用预估

## 技术栈

| 类别 | 技术选型 | 版本 |
|------|---------|------|
| 核心框架 | Spring Boot | 3.2.x |
| 数据库 | MySQL + MyBatis-Plus | 8.0 / 3.5.x |
| 缓存 | Redis + Redisson | 7.0 / 3.27.x |
| 消息队列 | Kafka | 3.6.x |
| 对象存储 | MinIO | latest |
| AI服务 | Claude API | - |
| 前端 | Vue 3 + Vite | 3.4.x |

## 项目结构

```
video-ai-platform/
├── docs/                    # 架构设计文档
├── video-api/              # API服务（用户交互入口）
├── video-worker/           # Worker服务（后台任务处理）
├── video-common/           # 公共模块（领域模型、工具类）
├── video-infrastructure/   # 基础设施（MySQL、Redis、Kafka配置）
├── frontend/               # 前端项目（Vue3）
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

- API服务: http://localhost:8080/api
- MinIO控制台: http://localhost:9001 (minioadmin/minioadmin)
- Kafka UI: http://localhost:8090
- Druid监控: http://localhost:8080/api/druid (admin/admin)

## 核心功能

### 1. 分片上传

```bash
# 初始化上传
POST /api/upload/init
{
    "fileName": "test.mp4",
    "fileSize": 104857600,
    "fileHash": "md5hash...",
    "chunkSize": 5242880
}

# 上传分片
POST /api/upload/chunk
Content-Type: multipart/form-data
uploadId: xxx
chunkIndex: 0
file: (binary)

# 完成上传
POST /api/upload/complete
{
    "uploadId": "xxx"
}
```

### 2. 任务查询

```bash
# 查询任务状态
GET /api/task/{taskId}

# 查询任务列表
GET /api/task/list?userId=1&pageNum=1&pageSize=10
```

### 3. 结果获取

```bash
# 获取分析结果
GET /api/result/{taskId}
```

## 架构亮点

| 亮点 | 说明 |
|------|------|
| **分片上传+断点续传** | 支持GB级大文件，网络中断可恢复 |
| **Kafka消息队列** | 削峰填谷，支持万级QPS |
| **多级限流** | 网关、应用、消费三层限流保护 |
| **成本控制** | 预估扣费、配额管理、智能抽帧 |
| **状态机** | 任务状态流转可控，避免状态混乱 |

## 开发进度

- [x] 项目架构设计
- [x] 基础模块搭建
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
