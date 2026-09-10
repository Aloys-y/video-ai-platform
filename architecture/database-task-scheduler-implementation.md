# 数据库任务调度：实施规格与执行清单

2026-09-10。以轻量化方案为准，不兼容旧数据，不建设双后端。现有数据库不自动清空。

## 一、最终交付

API 用本地事务创建 PENDING 任务，数据库调度器按本地视频容量领取，Worker 执行业务并保存终态。删除分析 Kafka、Outbox、历史直传模式和消费协调器。进程中断任务明确失败，用户重试，不做整局自动接管。

## 二、接口与持久化契约

- 状态：PENDING、RUNNING、SUCCEEDED、PARTIAL、FAILED、CANCELLED。
- attempt_no：首次为0，用户主动重试加1；不是模型调用重试次数。
- owner_token：每次领取生成独立UUID，不复用实例ID。
- lease_until：数据库生成和比较；所有权过期不允许续租复活。
- 候选索引：(status, created_at, task_id)；过期索引：(status, lease_until, task_id)。
- 唯一片段键：(task_id, attempt_no, segment_no)。
- 当前阶段、错误原因、开始和完成时间保留；去除人为估算百分比。
- API创建、取消、重试均直接操作MySQL；不再先写Redis状态。任务详情与列表查MySQL。

## 三、领取与收尾的精确定义

1. 单实例3个视频许可，许可覆盖领取中、已提交、执行中和未收尾状态。
2. 有许可才查候选，候选查询不持锁；以PENDING和attempt_no为条件UPDATE。
3. 更新成功后登记执行，提交业务池。失败则按原owner退回PENDING；数据库不可用则等待租约过期。
4. 领取的提交结果未知时不调用模型。
5. 视频完成后先落终态，再退出并释放许可；不以Future逻辑完成代替线程实际退出。
6. 业务异常可确认时标FAILED/PARTIAL；数据库异常无法确认时保留RUNNING，失效或停止续租后由过期清理处理。
7. 续租每轮间隔20秒，续期90秒；过期清理使用数据库时间与状态条件，不自动重跑。
8. 所有结果写入在短事务内锁住父行，校验owner、attempt、RUNNING与有效租约；锁内禁止远端调用。
9. 过期清理与业务写入使用相同父行形成串行化边界，取消先更新父行，随后写入不能穿透。
10. 应用关闭先停止新领取，再请求中断；未实际退出的工作不释放为可用执行容量。

## 四、组件改造

| 组件 | 最终职责 |
|---|---|
| TaskDispatchRepository | 领取、续租、退回、终态、过期失败、受保护子写入 |
| TaskScheduler | 本地容量、候选扫描、派发和执行登记；维护调度与业务执行隔离 |
| VideoAnalysisService | 合并原TaskProcessor和AudioPrefilterPipeline，只保留粗筛片段链路 |
| 片段协调与执行组件 | 模型并发、有限重试、结果保存、取消收尾 |
| API TaskService / UploadService | 事务创建、用户操作、直接查询，不投递消息 |

TaskDispatchRepository先作为可测试普通类实现，完成新schema/API/业务统一后一次装配为运行时Bean，避免旧消费者与新调度器混跑。这是实施顺序，不是保留双后端能力。

## 五、故障语义

| 故障 | 结果 |
|---|---|
| 创建事务回滚 | 无任务可领取 |
| Worker尚未领取就重启 | PENDING保留，可正常领取 |
| 领取后崩溃 | 租约到期后失败，用户可重试 |
| 模型429/5xx | 片段内部有限重试，每次重新限速 |
| 模型结果未知 | 不调度级自动重发，保留原因 |
| 响应保存后解析失败 | 保留响应，不重复模型调用 |
| 部分片段成功 | PARTIAL，展示已有结果 |
| 旧线程迟到 | 数据库条件与父行锁拒绝写入 |
| 通知/缓存失败 | 不存在Kafka完成通知；查询反映数据库状态 |

没有可用语音或未筛出候选：展示分析范围说明，不能描述为“整局没有交战”；不是调度失败。

## 六、分阶段施工与完成标准

### P0：规格与仓储内核（当前开始）

- [x] 确认新状态、owner与attempt语义，明确过期失败不自动接管。
- [x] 实现独立数据库调度仓储与并发测试。
- [x] 快速测试验证同任务单领取、旧owner拦截、过期续租失败、终态不重领（H2多连接；真实MySQL留在P3验收）。

### P1：调度器

- [x] 视频许可、定时领取、续租、实际收尾、过期清理。
- [x] 模拟执行器验证3个容量、拒绝回退、关闭、异常与许可泄漏。

### P2：业务、API与新schema

- [x] 合并视频编排，消除主执行入口的TaskMessage、KafkaTemplate和直传兼容分支。
- [x] attempt/status字段全链路同步，包含子表对父任务的SQL校验、API与前端。
- [x] 统一结果保护与取消传播，保留模型有限重试。
- [x] 更新完整schema定义（尚未在真实MySQL建库；P3隔离库验收），不清空原库。

### P3：删除旧链路与完整验证

- [x] 删除Kafka/Outbox类、测试与依赖、专用Docker服务、配置。
- [x] 在 Kafka 客户端不在类路径的环境中，使用隔离 MySQL 库启动 API/Worker。
- [x] 新调度链路真实视频验收：3.MP4，实际媒体处理及真实云端 AI 调用，3 个片段成功；逐调用用量与标价已记录。
- [x] 真实 MySQL 多连接竞争、事务回滚、租约过期失败与迟到写入拦截。
- [x] 两个独立 JVM 的强杀与重启故障注入，真实 90 秒租约自然过期；无自动重放，手动重试成功。
- [x] README 与项目展示更新为数据库调度；学习页显著标记 Kafka 问答为历史方案。

## 七、验证要求

默认不访问真实模型。测试先覆盖领取并发与故障边界，再做真实MySQL集成测试与模拟长任务完整链路；最后按用户授权进行真实视频验证。记录命令、结果与未验证范围，不能以仓储测试通过宣称完整迁移完成。

## 八、明确不做

不做历史Outbox迁移、旧状态映射、灰度分流或旧二进制回滚兼容；不保留无用抽象；不自动清理用户已有数据；不在本次调度改造中顺带修改RAG实验与未提交的其他功能。

## 九、本轮施工记录

- 新增 `TaskDispatchRepository`：候选查询、CAS领取、续租、未启动退回、父行锁保护、终态写入、过期失败。
- 新增 `TaskDispatchRepositoryTest`，5个测试通过，包含8个线程/独立数据库连接竞争同一任务。
- 测试命令：`mvn -B test -pl video-worker -am -Dtest=TaskDispatchRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`。
- 本地日志：`logs/database-dispatch-p0-tests.log`。未访问真实MySQL或调用付费模型。
- 新仓储使用目标字段与状态，目前尚未装配到旧应用；P1/P2/P3尚未完成，Kafka和Outbox还未从运行链路移除。

### P1完成记录

- 新增 `DatabaseTaskScheduler`，具有独立扫描和续租定时器、视频容量许可、领取派发及真实退出收尾。
- 新增显式 Context；片段必须在提交前 retainChild，在实际退出或启动前移除后关闭登记。父任务提前返回时不提前释放容量，不继续续租或写成功。
- 仓储异常不会终止周期调度；领取结果未知不执行；确定的提交拒绝按owner退回；异常退出保留数据库过期失败路径。
- 执行器关闭时中断请求不等于实际完成；忽略中断的工作仍占容量。
- `DatabaseTaskSchedulerTest` 8个测试与 `TaskDispatchRepositoryTest` 6个测试全部通过，共14个；包含H2完整领取→运行→续租→终态→不再领取的组件联合测试。
- 命令：`mvn -B test -pl video-worker -am -Dtest=DatabaseTaskSchedulerTest,TaskDispatchRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`。
- 日志：`logs/database-dispatch-p1-tests.log`。
- P1组件尚未装配到旧服务，真实视频、API和真实MySQL验收属于后续P2/P3。保留旧运行链路仅为施工中状态，不是最终双后端架构。

### P2完成记录

- `DatabaseDispatchConfig` 装配数据库调度仓储、调度器与视频服务；源码不再注册Kafka分析消费者。
- `VideoAnalysisService` 合并原任务处理器与音频粗筛编排，直接读取数据库任务，持久化结果后返回明确Outcome，由调度器提交终态。
- 父任务状态统一为PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED，retry_count改为attempt_no；父表finished_at映射为API现有completedAt展示属性。子阶段和片段的PROCESSING状态不是父状态，保持独立。
- 创建任务、手动重试与压测创建入口移除Outbox调用；任务详情取消Redis读写缓存，直接查询数据库。手动重试清空旧owner和租约。
- TaskWriteFence接入新仓储的父行事务保护；片段Job提交前登记上下文，退出或启动前取消时释放登记，防止父线程提前返回导致执行槽过早释放。
- 初始schema更新字段、索引并移除Outbox表定义；没有执行真实数据库变更。
- 前端阶段、终态、重试代次和错误码提示同步；JavaScript语法检查通过。
- 为避免新旧运行入口共存，提前删除旧消费者/协调器/旧处理器及其专属测试；已有未提交内容的删除前快照位于本地忽略目录`logs/p2-removed-snapshot`。
- 全模块`mvn -B test`通过：共155项，142项执行通过，13项显式跳过（实时调用、长压测等）。日志`logs/database-dispatch-p2-tests.log`。新增统一视频服务与调度器衔接测试，并更新真实MyBatis/AOP写入保护测试。
- 下一阶段仍需清理剩余Outbox实体/服务、Kafka依赖与配置、历史无用字段和文案，并用真实MySQL与隔离启动完成验收。当前未重启运行服务，不能使用旧表结构直接启动新版本。

### P3第一批验收记录（2026-09-10）

- 删除剩余 Outbox 模型、仓储、服务和工具，以及 Kafka Maven 依赖、配置模板、专用 Docker 服务定义。移除旧的 30 分钟任务恢复定时器，避免其干扰租约机制。未停止现有 Docker 容器或删除卷。
- 真实 MySQL 建全量 schema 时发现父子表排序规则不一致导致外键创建失败，已统一 utf8mb4_unicode_ci，并重新建库验证通过。
- 新增显式开启的 DatabaseTaskMysqlAcceptanceTest：8 个独立连接竞争同一任务仅一个成功；事务异常回滚；过期不续租、标记 FAILED；旧执行不能完成；真实调度器搭配模拟 Work 完成任务后不重领。
- 真实 Worker Spring 上下文在隔离库启动成功；API 使用随机 HTTP 端口启动成功，实际 TaskService 查询、手动重试（attempt + 1）、取消均通过。HTTP 冒烟只验证请求不出现服务端错误，不等同于已登录上传验收。
- 两项验收均断言 KafkaConsumer 不在类路径；随机测试库在 finally 中按严格名称校验删除，没有修改开发业务库。需要配置账户具有建库和删库权限，读取现有 dev 配置，不输出凭据。
- 全模块回归：157 项，142 项执行通过，15 项按条件跳过（其中新增两个真实 MySQL 验收已单独启用并通过），0 失败、0 错误。
- 日志：logs/database-dispatch-p3-final-tests.log、logs/database-dispatch-p3-mysql.log、logs/database-dispatch-p3-api.log。
- README、配置示例和项目展示已同步；学习页保留历史 Kafka 问答并增加醒目标记。本次未改 RAG 评估内容。
- 注意 loadtest profile 只模拟视频模型，不能保证 ASR 和文本筛选不计费；完整模拟必须隔离这两个外部边界。配置注释已纠正。

复现命令（PowerShell）：

```powershell
mvn -B test
mvn -B test -pl video-worker -am '-Dtest=DatabaseTaskMysqlAcceptanceTest' '-Ddispatch.mysql.acceptance=true' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -B test -pl video-api -am '-Dtest=DatabaseApiMysqlAcceptanceTest' '-Ddispatch.mysql.acceptance=true' '-Dsurefire.failIfNoSpecifiedTests=false'
```

**第一批结束时 P3 尚未完成。** 当时未调用付费模型，也未做进程强杀。以下第二批记录补齐这两项；没有将现有运行服务切到新 schema。

### P3第二批：真实视频与进程故障（2026-09-10）

- 按用户授权使用 `D:\data\video\3.MP4` 调用真实 ASR、文本、Embedding、Rerank 和视频模型。生产 Worker Spring 上下文完成下载、FFmpeg、粗筛、裁剪、并发分析和落库；父任务与 3 个片段全部 SUCCEEDED。
- 记录 14 次外部 AI 接口调用（包括 ASR 提交和轮询），130,954 Token 与 393 秒 ASR 回执用量；按官网华北2标价合计 0.12667875 元，未查询账户实扣。
- 两个独立 JVM 使用生产调度器进行阻塞探针测试，实际续租后强杀；恢复进程等待 90 秒租约自然过期，任务只标记失败、不自动重放；旧 owner 写入被拒绝，手动重试的新代次完成。
- 修复验收脚本 LocalDateTime 导出与 Windows 子 JVM 类路径过长问题。未因导出失败重跑已完成的视频；只读取已有结果继续验收。
- 真实视频与故障验收使用独立库，原数据库和运行服务未清空、未切换。故障探针不调用付费服务；本轮未重复走浏览器分片上传 HTTP 流程。
- 完整结果、范围说明和逐调用账本见 [P3 验收报告](database-task-scheduler-p3-validation.md)。验收库、OSS产物与模型结果保留可查。

最终回归：全模块 Maven 共 159 项，142 项执行通过，17 项条件跳过，0 失败/错误；计费脚本 4 项单元测试通过。默认回归不会重复触发真实模型。日志：`logs/database-dispatch-p3-complete-tests.log`。

### 开发环境部署切换（2026-09-10）

旧服务已停止，开发库分析表备份后重建，新 API、Worker、前端已启动并通过鉴权接口、页面、跨域与运行中租约过期处理验证。账号、上传记录与 RAG 知识数据保留；旧任务列表清空。详见 [部署切换记录](database-task-scheduler-deployment.md)。
