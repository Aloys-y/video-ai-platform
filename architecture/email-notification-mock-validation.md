# 邮件通知 Mock 链路

日期：2026-09-13。代码已实现，默认关闭；本次未对正在运行的服务、数据库做部署切换。

## 当前链路

```text
视频终态条件更新成功 → 数据库提交后 → 有界异步发布器 → Kafka
  → API 消费者 → mock_mail_notification 入库 → 提交消费位点
  → 每 5 秒扫描到期记录 → Mock Provider → MOCK_SENT
```

成功、部分成功、失败、租约过期失败均覆盖；取消不通知。事件 ID 根据任务 ID 和执行代次生成，重复事件只建立一条记录，新代次独立记录。

无真实邮箱、无外部邮件调用、无模型调用。主题标明“模拟邮件”，MOCK_SENT 不表示真实投递。Mock 开启后所有新结束任务产生模拟记录；真实发信前仍必须增加邮箱验证、用户提醒选项和邮箱版本校验，不能直接替换 Provider 后向全部任务发信。

## 取舍

- 没有 Outbox。提交后宕机、发布队列满或 Kafka 不可用允许丢提示，不回滚分析结果。
- 发布线程 1、队列 64；send 同步阻塞上限 1 秒、生产者投递期限 15 秒；失败回调仅记录安全日志。
- 消费者只入库，数据库异常不吞掉、不提交位点。无效事件记录分区/位点后跳过。
- Mock 发送器单线程，每轮最多领取 2 条，条件领取兼容多个 API 实例；Mock 无慢网络调用，不另建业务线程池。
- 临时失败最多 3 次，后两次间隔 1 分钟、5 分钟；永久失败停止；不确定结果与领取过期标 UNKNOWN，不盲目补发。
- 查询接口校验任务归属，每个任务返回最近 50 条。此次不增加前端通知中心。
- Mock 表独立命名，避免后续真实服务误发测试记录。

## 本地开启

1. 按项目要求备份、确认无在途任务，再执行 `sql/V2.2__mock_mail_notification.sql`。新库 schema.sql 已包含此表。
2. 准备 Kafka Broker，创建 `video.analysis.finished.v1` Topic，开发验证单分区即可。
3. API 和 Worker 设置下面配置，再构建重启；目前配置模板中默认关闭。

```yaml
notification:
  mock:
    enabled: true
  kafka:
    bootstrap-servers: localhost:9092
    topic: video.analysis.finished.v1
    group: video-mail-mock-v1 # API 使用
```

4. 用合成任务验证，不必重新上传视频或调用模型。
5. 登录后访问 `GET /api/task/{taskId}/mock-notifications` 查看主题、正文、代次、状态和次数。沿用原有认证，无权用户不可查询。

关闭开关后不创建本功能的生产者、消费者、发送器与接口；保留已有记录。

## 自动测试与结果

```powershell
mvn -pl video-api,video-worker -am test '-Dtest=MockMailChainTest,TaskDispatchRepositoryTest,DatabaseTaskSchedulerTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

使用 H2 MySQL 模式和真正的嵌入式 Kafka KRaft Broker，无须业务 MySQL、外部 Broker 或邮件账号。日志：`logs/mock-mail-tests-final.log`。

本次结果：24 项通过，0 失败、0 跳过（MockMailChainTest 7 项、TaskDispatchRepositoryTest 9 项、DatabaseTaskSchedulerTest 8 项）。

覆盖 Kafka 到数据库再到 Mock Provider、重复事件、新执行代次、有限重试、永久/不确定失败、过期领取、归属校验、关闭开关、事务回滚、条件更新失败、租约过期，以及通知异常不影响终态和原调度器回归。

该验收不等同于现有运行服务已开启功能，也不替代真实 MySQL 迁移验收或邮件送达测试。完整正式功能的后续范围见 [执行方案](email-notification-execution-plan.md)。
