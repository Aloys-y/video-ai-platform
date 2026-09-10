# 数据库调度开发环境切换记录

切换时间：2026-09-10 11:49（北京时间）。新版本已在开发环境运行。

## 服务

| 服务 | PID | 入口 |
| --- | --- | --- |
| API | 1880 | http://localhost:8080/api |
| 数据库调度 Worker | 19132 | 后台进程，无需 HTTP 入口 |
| 前端 | 33244 | http://localhost:3000/ |

旧 API 7316、Worker 29664、前端 31212 已核对身份后停止。新 API/Worker 仅启用 dev profile，不再启用旧 async profile；Worker 显式配置本机 FFmpeg 与 ffprobe 路径。

## 数据库

在 video_ai 开发库重建 analysis_task、analysis_execution、analysis_segment、analysis_asr_part、analysis_text_call、task_rag_context、ai_call_log，删除 task_outbox。保留 user、user_quota、upload_session 和 knowledge_*。

旧测试数据先备份至 `logs/database-switch-backup-20260910-114752`，每个表包含原始 DDL 与行数据：父任务 1,257 行、Outbox 1,259 行，其余分析数据也已备份。切换前所有旧父任务均为 COMPLETED，没有正在执行的任务。未删除 OSS 对象。

界面任务列表已重置为空，原有账号、上传记录和 223 条知识片段保留。P3 真实视频验收的隔离数据库、结果报告和 OSS 产物仍保留，未混入新开发任务列表。

## 验证

- Maven package 成功；API 与 Worker 均记录 Started，三个新进程仍存活。
- 两个 fat JAR 检查不含 Kafka 依赖、TaskConsumer 或 Outbox 类。
- 使用已有用户 API Key 在进程内进行鉴权测试，任务列表返回成功；未输出凭据。
- 前端 HTTP 200，前端到 API 的跨域预检通过。
- 插入一条已过期的 RUNNING 部署探针，由实际新版 Worker 扫描并标记 FAILED / EXECUTION_INTERRUPTED；API 查询、取消成功。
- 探针已 CANCELLED，界面不显示；当前没有 PENDING/RUNNING 任务。
- 本轮验证未调用 ASR、Embedding、文本或视频模型，新增 AI 费用为 0；完整真实视频业务链路已在 P3 验收报告中验证，本次未重复上传调用。

构建日志：`logs/database-deploy-build.log`。
运行日志：`logs/database-deploy-20260910-114912`。
启动脚本：`scripts/start-database-dev.ps1`（仅用于旧实例已停止后启动，检测到已有实例或端口占用会拒绝重复启动）。

[真实链路与故障验收报告](database-task-scheduler-p3-validation.md)
