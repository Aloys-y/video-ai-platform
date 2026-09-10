# 旧同步消费者轮询超时复现

2026-09-08，真实 Kafka 独立 Topic / 消费组，未改变运行服务、未调用付费模型或业务数据库。

## 方法与结果

对照历史提交 `1a9b48c^` 的 `TaskConsumer.consume`，在测试中保留相同监听体：同步调用 `TaskProcessor.process`，返回 true 后 ACK，异常捕获并记录。业务方法用等待模拟；不是部署旧版全套应用。

同组两个消费者、一个分区，MANUAL_IMMEDIATE，自动提交关闭，max.poll.records=1。为快速复现，将 max.poll.interval.ms 从旧配置的40分钟缩短为5秒；heartbeat.interval.ms=1秒、session.timeout.ms=10秒。没有设置静态成员ID。

| 场景 | 首次业务耗时 | 消息交付次数 | 额外分区分配 | 距最近poll时间采样峰值 | 结果 |
|---|---:|---:|---:|---:|---|
| 正常同步处理 | 1秒 | 1 | 0 | 1秒 | 正常提交 |
| 同步处理超限 | 12秒 | 2 | 1 | 11秒 | 轮询超时、迟到ACK失败、同一记录重投 |

最终对照用例通过，总执行约24秒。两组结束后清理临时 Topic 和消费组。

## 定位证据

超限组事件，以第一次业务开始为 t=0：

1. 消费线程 `legacy-0-C-1` 收到 partition=0、offset=0，并在同一线程处理业务。
2. Kafka 心跳线程记录 `consumer poll timeout has expired`，随后发出 LeaveGroup；原因明确指向两次 poll 间隔超过 max.poll.interval.ms。
3. t≈12.001秒，业务方法返回；原消费者尝试 ACK，捕获 `CommitFailedException`。
4. t≈12.296秒，记录新的分区分配。
5. t≈12.406秒，再次交付 partition=0、offset=0。实际是原消费者重新入组后收到同一记录，不是另一个消费者并行接管。
6. 第二次模拟处理后最终提交 offset=1。

本轮没有观察到并行重复执行。首次试验已经出现超时、提交失败及重投，但测试错误地要求“必然并行重入”，因此失败；修正这一不成立的假设后重新运行并保存完整结果。

## 可以怎样表达

“在旧同步消费方案的长任务对照压测中，我将轮询期限缩到5秒，并模拟12秒的业务处理。发现同一分区、同一offset的消息被交付两次。结合日志定位到先发生poll超时和退组，业务结束后的ACK又报CommitFailedException。沿代码检查发现，监听方法同步调用视频处理，没返回前容器无法再次poll。即使max.poll.records已经是1，单个任务仍可能超时。因此把耗时处理交给有界业务线程池，并用asyncAcks控制未确认期间的新消息交付。”

这证明旧同步消费结构在超限时会引发消费组变动、提交失败及消息重投，不代表真实模型一定重复计费。生产任务状态检查可能复用已完成结果。本轮也不是40分钟真实视频的线上事故复现。

## 复现入口

测试文件：`video-worker/src/test/java/com/videoai/worker/consumer/KafkaLegacyPollReproductionTest.java`，默认不运行。

```powershell
mvn -B test -pl video-worker -am '-Dtest=KafkaLegacyPollReproductionTest' '-Dkafka.legacy.reproduce=true' '-Dkafka.load.bootstrap=<Kafka地址:端口>' '-Dkafka.legacy.output=D:/data/proj/javaProj/vibeCoding/DoVideoAI/logs/kafka-legacy-poll.json' '-Dsurefire.failIfNoSpecifiedTests=false'
```

原始事件及日志：`logs/kafka-legacy-poll.json`、`logs/kafka-legacy-poll.log`；第一次复现日志保留在 `logs/kafka-legacy-poll-first-reproduction.log`。
