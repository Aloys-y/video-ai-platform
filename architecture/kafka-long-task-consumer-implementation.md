# 长耗时视频消费者：实施记录

日期：2026-09-08。方案：[长任务消费方案](kafka-long-task-consumer-plan.md)。

后续检查：[环境诊断与事务提交异常修复](kafka-async-verification-20260908.md)。

## 当前执行链路

```text
Kafka子消费者收到一条消息
→ AsyncVideoCoordinator登记、提交视频执行池，监听方法返回
→ Spring asyncAcks暂停该子消费者全部分区的新记录交付，继续poll
→ 视频父线程原子领取数据库租约
→ TaskProcessor：下载、转写、文字粗筛、裁剪最多5段
→ 共享片段池处理，父线程用CountDownLatch等待
→ 保存片段结果和任务终态
→ 确认终态后ACK，Spring提交offset并恢复交付
```

视频父线程与Kafka消费线程已经分离。工作线程只调用Spring的`Acknowledgment`，不直接操作`KafkaConsumer`。

## 参数与语义

| 项目 | 首版配置 |
|---|---|
| 子消费者 | 3个，每个最多1条未确认消息 |
| 视频执行池 | `ThreadPoolExecutor(3,3)`，队列3，`AbortPolicy`；拒绝后保留消息，每秒重交，不ACK |
| 片段执行池 | 保留固定4线程、队列16，最多5段/视频 |
| ACK | `MANUAL`、`asyncAcks=true`、关闭自动提交 |
| Kafka poll期限 | async profile下5分钟；pollTimeout为1秒 |
| 租约 | 90秒，独立线程每20秒续租；SQL超时5秒 |
| 数据库连接 | 连接池等待5秒、连接建立5秒、socket读取10秒；避免SQL还未执行就无限等待 |
| 视频耗时 | 30分钟仅日志提示，继续等待；单次下载、ASR和模型调用仍有超时 |
| 容器恢复 | 每分钟检查异常停止，撤销本地执行权后重启；正常停止/暂停不自动重启 |

正常分配下最多3个在途视频；再均衡期间旧线程可能仍在退出，仍受本机线程池上限约束，不无限创建替代线程。

## 失败和恢复

- **普通片段失败**：其他片段继续完成。至少一个成功时任务为`PARTIALLY_COMPLETED`，否则`FAILED`；成功结果保留，未完成片段明确收尾。父终态与片段收尾在一个事务内提交。
- **结果已绑定、数据库短暂失败**：不ACK；保留`PROCESSING`及响应引用，恢复时只解析/保存已存在响应，禁止因持久化失败重发模型请求。
- **远端请求结果未知**：不自动重发付费请求；明确失败并等待核对/用户重试。直传模式中断恢复也采取这一保守策略。
- **终态保存后、提交offset前退出**：重投读到终态即ACK，不重做分析。
- **撤销分区、租约失效**：关闭令牌并请求中断。旧线程晚返回时不能写库、不能调用旧ACK；已发出的远端调用不保证能取消。
- **用户取消**：停止后续执行，数据库写入保护拒绝取消后的结果覆盖，成功片段仍可查询。

租约字段位于`analysis_task`，在下载前就能领取。`TaskWriteFence`只保护真实Mapper方法，排除MyBatis工厂生命周期；短事务中锁定任务行，校验令牌、数据库租约、重试代次、取消状态及写入目标，再执行实际写入。执行令牌显式传到片段线程，并在退出时清理。

同一视频的音频准备、转写及文字粗筛复用一个工作区，避免多视频并发时互相等待第二个工作区。工作区不足时等待容量，等待响应取消/令牌失效。

## 主要代码

| 文件 | 职责 |
|---|---|
| `TaskConsumer`、`KafkaListenerConfig` | 监听入口、异步ACK及再均衡回调 |
| `AsyncVideoCoordinator` | 视频池、在途记录、续租、完成确认和拒绝重交 |
| `TaskLeaseService`、`TaskWriteFence` | 原子执行权和数据库写入保护 |
| `AsyncConsumerSupervisor` | 异常停止的容器恢复 |
| `SegmentAnalysisExecutor` | 片段池、Latch、执行权传递、实际退出统计 |
| `TaskFailureService` | 部分完成与失败的事务收尾 |

## 已完成验证

- 全模块自动化测试164项：153通过，11项外部依赖/长时压测默认跳过。真实FFmpeg补跑4项全部通过。
- 最后补跑实际async配置绑定、容器工厂配置及MyBatis工厂切面测试11项全部通过，含新增async profile配置测试；实际Spring Kafka依赖为3.1.2。
- 嵌入式Kafka：处理超过测试poll期限仍持续poll；终态前offset不前进；同消费者其他分区不提前交付；主动再均衡重投，旧线程迟到不会跳过下一条消息。
- 真实MyBatis工厂+AOP+H2：竞争领取只有一个成功；旧令牌、错误任务、旧代次及取消均不能写入；子表同样受保护；事务回滚和部分失败收尾正确。
- 容量与故障：视频池满后重交、不提前ACK；数据库不可用时不启动处理；已绑定响应恢复时不重复调用模型；ACK与下一条消息交接不受旧租约释放延迟影响。
- 前端JavaScript语法检查通过，部分完成已加入任务列表、详情、轮询停止及重试入口。

这组测试不等同于多实例生产稳定性验证。尚需在稳定基础环境下验证实例强杀、实际数据库长时间断连、实际Kafka提交故障及云模型不响应中断。未进行新的付费模型调用。

## 迁移与运行

`sql/V1.8__task_execution_ownership.sql`已应用到当前dev数据库，并核验3个新增列；既有任务内容未迁移重写。全新数据库的`sql/schema.sql`也已补充相同字段。

默认配置仍关闭异步模式；完成迁移且停止旧Worker后，使用`dev,async` profile启用。不要新旧Worker混跑。

截至12:43，本地API、前端可访问，新Worker已使用`dev,async`启动，MyBatis工厂循环创建告警已消除。MySQL曾短暂读取超时，目前握手与查询恢复；配置中的远端Kafka端口仍连接超时，Worker尚未取得分区，客户端持续重连。因此**真实环境任务消费验收尚未完成**，不把“应用启动成功”当成“Kafka链路跑通”。未投递探针或新增AI任务。

日志：`logs/async-full-tests-v3.log`、`logs/async-final-config-tests-v2.log`、`logs/async-ffmpeg-tests.log`、`logs/v18-migration-result.json`、`logs/worker-async-v3.log`。旧API/Worker包已保存在`logs/async-rollback/`，回滚前仍需停止新Worker并确认无在途执行。

日志每分钟记录在途数、运行/排队数、拒绝重交数、撤销次数和续租失败数；慢任务与持久化异常有日志提示。尚未接入外部告警平台或完整指标面板。

参考：[Spring Kafka 3.1 asyncAcks语义](https://docs.spring.io/spring-kafka/docs/3.1.4/api/org/springframework/kafka/listener/ContainerProperties.html#setAsyncAcks(boolean))。
