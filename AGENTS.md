# AGENTS.md

## 项目与运行方式

Java 17 / Spring Boot 3.2.4，多模块Maven项目。模块关系：video-common → video-infrastructure → video-rag；video-api和video-worker使用这些模块。

- video-api：REST API，8080端口、`/api`上下文；上传、用户、任务、片段和费用查询。
- video-worker：无独立HTTP入口的后台进程；数据库任务调度、FFmpeg、ASR、文本筛选和片段模型调用。
- video-rag：知识切分、索引、检索和提示词编排。
- video-infrastructure：MySQL/MyBatis、Redis/Redisson、S3、Milvus、AiCallRecorder和费用计算。
- frontend：纯HTML/CSS/JS SPA，hash路由。

当前不用Kafka或Outbox。API事务创建PENDING任务，Worker先获取容量再条件领取，独立续租；owner、执行代次与租约约束结果写入。过期执行标记失败，不自动重放付费请求。片段使用共享有界线程池，模型重试只覆盖明确可重试的调用异常。

当前模型：`qwen-audio-3.0-asr-flash-filetrans` → `qwen3.8-flash` → `qwen3.7-plus`。视频使用百炼兼容接口，只输入裁剪后的片段，不回退整视频分析。RAG使用text-embedding-v3和qwen3-rerank。每次实际调用在ai_call_log保存归属、用量和价格快照，未知费用不等于0。

## 构建与测试

```bash
mvn clean install -DskipTests
mvn test
mvn test -Dcost.mysql.acceptance=true
node --test scripts/test_task_cost.cjs
```

JUnit 5 + Mockito，部分测试使用Mock HTTP。cost.mysql.acceptance启用隔离MySQL测试，需要建库权限。真实视频/模型验收需要明确授权，不属于默认测试。

API：在video-api执行 `mvn spring-boot:run -Dspring-boot.run.profiles=dev`。
Worker：在video-worker执行相同命令。FFmpeg/FFprobe必须可用。
前端：仓库根目录执行 `python -m http.server 3000 --directory frontend`。
Windows一并启动可用scripts/start-database-dev.ps1，先检查本机可执行文件路径。

中间件：docker目录执行 `docker compose up -d`，默认MySQL 13306、Redis 16379；MinIO和Milvus通过对应profile选择启动。

## 配置和数据

配置模板为各模块的application-dev.yml.example，复制为application-dev.yml后填写凭据。实际dev配置、docker/.env、logs和本机验收回执不提交Git。新空库使用sql/schema.sql；已有库迁移前先备份、确认无在途任务，逐份核对适用SQL。费用迁移V2.1会重建旧账本，不可重复覆盖已有新账本。

API统一返回ApiResponse，认证支持JWT Bearer和X-API-Key；UserContext在请求结束清理。费用查询先验证任务归属，只从账本聚合，不通过子任务JOIN造成金额倍增。

文档从README.md、architecture/README.md和scripts/README.md进入。architecture/archive/kafka为历史实验，不能用来判断当前代码。保留当前部署JAR、运行日志、数据库备份和真实调用证据；清理临时worktree前先确认无未提交修改。

## Git Workflow

Feature branch development with PR merges to main. Never push directly to main.

## Development Language

All user communication in Chinese. Code comments and commit messages in Chinese or English.
