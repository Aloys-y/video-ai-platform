# Kafka历史实验

此目录保存迁移到数据库调度之前的设计与验证。当前应用不依赖Kafka/Outbox，请从[当前架构索引](../../README.md)开始。

- [长任务消费设计](kafka-long-task-consumer-plan.md)
- [实现记录](kafka-long-task-consumer-implementation.md)
- [旧版poll超时复现](kafka-legacy-poll-reproduction.md)
- [分区和并发配置](kafka-partition-consumer-sizing.md)
- [压测报告](kafka-async-load-report.md)
- [异步验证](kafka-async-verification-20260908.md)
- [回调补偿设计](kafka-callback-compensation-design.md)

历史驱动脚本位于 `scripts/archive/outbox_load_test.py`，不是当前链路的验收入口。
