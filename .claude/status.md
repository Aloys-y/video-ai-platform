# 当前工作状态

> 最后更新: 2026-04-19
> 当前分支: fix/kafka-listener-port

## 当前阶段
**Phase 2**: 任务处理（Worker消费） - 准备开始

## 已完成的分支/PR
| 分支 | 状态 | 内容 |
|------|------|------|
| main | ✅ | 基础架构 + 鉴权 + 上传 + Swagger + 异常处理 |
| feature/upload | ✅ 已合并PR#1 | 视频分片上传（MinIO + Redisson） |
| fix/swagger-and-readme | ✅ 已合并PR#2 | Swagger路径放行 + README更新 |
| fix/kafka-listener-port | 🚧 当前 | 修复Kafka监听端口配置 |

## 已完成功能模块

### Phase 0: 环境准备 ✅
- [x] 多模块Maven项目（common / infrastructure / api / worker）
- [x] Docker Compose（MySQL / Redis / Kafka / MinIO / Kafka UI）
- [x] 领域模型 + 枚举 + DTO
- [x] SQL Schema（5张表）

### Phase 0.5: 鉴权与限流 ✅
- [x] 双认证体系（JWT Bearer + API Key）
- [x] BCrypt密码 + JWT生成/解析
- [x] 三级限流（全局Guava / 用户Redis / 接口级）
- [x] 全局异常处理（25+错误码）
- [x] SpringDoc OpenAPI（Swagger UI）

### Phase 1: 分片上传 ✅
- [x] 初始化上传（文件校验 + uploadId生成）
- [x] 分片上传（Redisson分布式锁 + MinIO存储）
- [x] 完成上传（MinIO ComposeObject服务端合并）
- [x] 断点续传（MySQL JSON记录已上传分片）
- [x] 秒传（文件MD5去重）
- [x] 进度查询（Redis缓存）
- [x] 任务创建 + Kafka消息发送

## 待办事项（Phase 2）

1. [ ] 实现TaskConsumer（Kafka消费 videoai.task.analyze）
2. [ ] 任务状态流转（PENDING → PROCESSING → COMPLETED/FAILED）
3. [ ] FFmpeg视频抽帧
4. [ ] Claude API集成（AI分析）
5. [ ] 死信队列处理
6. [ ] 定时任务（过期清理/配额重置）

## 技术栈概览

| 层面 | 技术 |
|------|------|
| 框架 | Spring Boot 3.2.4 + Java 17 |
| 数据库 | MySQL 8.0 + MyBatis-Plus + Druid |
| 缓存 | Redis 7.0 + Redisson |
| 消息队列 | Kafka 7.5（acks=all, 手动提交offset） |
| 对象存储 | MinIO |
| 认证 | JWT（jjwt 0.12.5）+ API Key + BCrypt |
| 文档 | SpringDoc OpenAPI (Swagger UI) |
| ID生成 | upload_{timestamp}_{random} |

## 关键配置参数

| 参数 | 值 |
|------|------|
| API端口 | 8080, Context Path: /api |
| Worker端口 | 8081 |
| 分片大小 | 5MB |
| 最大文件 | 5GB |
| 上传过期 | 24小时 |
| 全局限流 | 1000 QPS |
| 用户限流 | 100 QPS |
| Worker并发 | 5 |
| AI QPS限制 | 2 |
| 抽帧间隔 | 2秒 |
| 最大帧分辨率 | 1280x720 |

## 关键决策记录

### 2026-04-19
- **Kafka端口**: 修复监听端口配置，解决健康检查失败问题

### 2026-04-18
- **Swagger放行**: 公共路径不需认证即可访问Swagger UI

### 2026-04-10
- **异常处理**: 补全GlobalExceptionHandler，API Key脱敏，traceId空值修复

### 2026-04-08
- **认证方式**: JWT + API Key双认证（灵活支持多种场景）
- **限流策略**: 三级限流（全局→用户→接口）
- **上传方案**: MinIO ComposeObject服务端合并（避免下载再上传）
- **分布式锁**: Redisson（比Redis Lua更易维护）
