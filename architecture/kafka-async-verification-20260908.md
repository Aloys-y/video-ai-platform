# 异步消费者检查与故障测试

日期：2026-09-08。

## 实际环境

- MySQL连接、查询正常；V1.8的3个租约字段存在。检查时1257个任务均为COMPLETED，没有处理中任务。
- Kafka配置端口19092与Compose中的另一监听端口19093均连接超时；使用与Java服务相同的执行权限复核，结果一致。
- 服务器Kafka UI可访问，`GET /api/clusters`返回`videoai-kafka: offline`、`brokerCount: 0`；Broker查询超时。
- 因此不只是本地消费者无法建立连接，服务器上的Kafka UI也发现不了Broker。尚无服务器容器日志，不能进一步断言是容器退出、监听配置还是网络故障。

未切换到其他Kafka地址，也未重建集群或删除数据。实际任务消费验收仍需恢复Broker后完成。

## 测试发现并修复的问题

原逻辑只将`DataAccessException`识别为持久化问题。Spring的`TransactionSystemException`属于另一类事务异常，可能出现在提交阶段；它会被当作普通片段失败，破坏已保存响应的恢复路径。

已在片段处理、父线程批次异常归类和任务处理器中统一识别`TransactionException`。发生这类错误时保留消息、不ACK、不把它直接改成模型失败。恢复时以数据库实际记录为准：

1. 提交已成功但客户端收到异常：读取已成功结果。
2. 提交未成功、原始响应引用已经保存：重新解析/保存已有响应。
3. 两种场景都不重新调用模型。

边界仍然存在：如果原始响应引用本身未能可靠保存，不能承诺自动找回响应；原有“远端结果未知则禁止自动重发”规则继续生效。

## 验证结果

| 验证 | 结果 |
|---|---|
| 修改前全模块回归 | 165项，154通过、11项外部依赖/长时测试默认跳过 |
| 新增提交异常故障测试，修改前 | 复现失败：事务异常未被标记为持久化待核对 |
| 修复后针对性回归 | 39项全部通过，含新增3个场景 |
| 两种片段提交结果不确定场景 | 能恢复，模型调用次数均为1 |
| 父任务终态提交异常 | 不ACK，不调用业务失败落库 |
| Kafka实际offset、持续poll、主动再均衡 | 嵌入式Kafka测试通过 |
| 执行租约、旧令牌拒写、部分完成收尾 | 测试通过 |

本轮没有发起付费模型调用，没有向实际任务Topic投递探针消息。修复后Worker包已构建并启动，实际Kafka仍在重连；API和前端未重启。

日志：`logs/async-verification-20260908.log`、`logs/async-commit-fault-before.log`、`logs/async-commit-fault-after.log`、`logs/worker-async-commit-fix.log`。

## 服务器端下一步检查

在部署Compose的服务器执行以下只读命令：

```bash
docker compose ps -a kafka zookeeper
docker compose logs --tail=150 kafka zookeeper
docker inspect videoai-kafka --format '{{json .State}}'
docker exec videoai-kafka kafka-broker-api-versions --bootstrap-server localhost:9092
```

先依据退出码、OOM标记、磁盘/日志报错及Zookeeper连接情况定位，再决定是否重启。

Broker恢复后，确认Worker取得分区，再跑无付费模型的隔离探针与真实消费提交验证。本轮嵌入式测试不能替代这一步。
