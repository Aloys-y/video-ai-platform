# 架构与验证文档索引

当前链路：MySQL任务表 → 后台调度器领取与续租 → ASR → 文本粗筛 → FFmpeg裁剪 → RAG → 片段线程池 → 保存结果与费用。运行时不使用Kafka/Outbox。

## 当前设计

- [每日免费额度与积分执行方案](daily-quota-credit-plan.md)：待实施；每日两次免费、按次积分消费、失败补偿及支付分阶段接入。

- [Kafka → Mock 邮件通知](email-notification-mock-validation.md)：可选功能，默认关闭；不改变数据库任务调度，不使用 Outbox，不发送真实邮件。

- [数据库调度实施方案](database-task-scheduler-implementation.md)：容量许可、执行代次、租约和条件写入。
- [统一费用账本方案](ai-cost-ledger-plan.md)：逐调用记账、价格快照、本次与累计费用。
- [新模型切换及真实验证](new-model-switch-validation.md)：Qwen-Audio 3.0、Qwen3.8-Flash和Qwen3.7-Plus；当前模型与最近一次真实任务证据。
- [费用账本部署验证](ai-cost-deployment-validation.md)：迁移和费用接口验收，当时使用的是旧模型组合。

## 实施过程与测试证据

- [分析结束邮件通知执行方案](email-notification-execution-plan.md)：待实施；Kafka 仅用于非核心邮件提醒，不使用 Outbox，不替换数据库任务调度。

- [音频粗筛实施阶段](audio-prefilter-execution-plan.md)，各P2—P6文档为当时的阶段记录，状态以当前代码和上面的最近验证为准。
- 费用账本：[P0](ai-cost-p0-validation.md)、[P1文本](ai-cost-p1-text-validation.md)、[P1 ASR](ai-cost-p1-asr-validation.md)、[P1视频](ai-cost-p1-video-validation.md)、[P1 RAG](ai-cost-p1-rag-validation.md)、[P2接口与页面](ai-cost-p2-validation.md)。
- [早期新模型成本对照](model-selection-comparison-2026-09-10.md)：历史隔离实验，不能替代当前任务的调用账本。
- [线程池压测入口](load-test/README.md)：保留历史实验复现资料，Kafka/Outbox相关命令不适用于当前服务。

## 历史方案

- [Kafka实验归档](archive/kafka/README.md)：长任务消费、重平衡复现与分区参数。
- `database-task-scheduler-*-plan.md`、`segment-*-report.md`等保留设计演进和实验依据，不代表每一版参数仍在使用。
- 逐次验收结果保存在相应日期目录；本机凭据、数据库备份、原始模型回执和运行日志保留在被Git忽略的 `logs/`，不公开原始敏感数据。
