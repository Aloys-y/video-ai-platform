# P2 费用接口与任务详情展示验证

日期：2026-09-10。状态：代码与开发验收完成，业务库迁移和部署待执行。本阶段未调用付费模型。

后续更新：同日已完成迁移、部署及真实视频验收，见[部署验证](ai-cost-deployment-validation.md)。下文保留P2开发阶段的原始边界。

## 实现

- 新增 `GET /api/task/{taskId}/costs`，使用现有登录用户和任务归属校验；越权不读取账本。
- `TaskCostService` 在只读一致性快照中组织当前执行、历次累计、五个阶段及当前视频片段的费用。SQL只对账本聚合，片段表仅提供元数据，避免JOIN造成金额倍增。
- 失败但已知费用的请求纳入汇总；每次实际重试单独计入。复用结果没有本次调用行，本次新增费用为0，历史费用仍归原执行。
- 金额与用量用字符串返回，后端BigDecimal计算，前端不重新计价。金额最多保留10位小数，不把极小的非零费用显示成0。
- 新执行快照增加 `costLedgerVersion=1`。无标记的旧任务不根据空账本推断免费；本次覆盖完整也不代表旧执行费用完整。
- 详情页新增费用卡片和折叠阶段/片段表，运行中沿用任务轮询；终态提供手动刷新。请求失败仅影响费用区域，旧请求和不同执行代次的响应不能覆盖当前费用。
- 修复窄屏Grid子项默认最小宽度导致的整页溢出，表格在卡片内横向滚动；更新CSS和脚本缓存版本。

## 接口口径

返回 `executionNo`、`taskStatus`、`current`、`lifetime`、`stages`、`segments`。

每组汇总包含：`knownCostCny`、`callCount`、`incompleteCount`、`runningCount`、`inputTokens`、`outputTokens`、`audioSeconds`、`coverageKnown`、`complete`。

`incompleteCount`包含费用为空的记录，`runningCount`是尚未收尾数量，两者可能重叠，不相加。`complete`要求覆盖已知、没有未知费用或运行中调用，且任务已终态。费用仍是按标价估算，并非供应商账单实扣。

未覆盖且没有调用时，已知金额数值虽为0，界面必须显示“尚无完整费用记录”和“—”。已知金额与最终总金额不能混淆。

为保持结构清爽，本阶段不增加逐次调用明细分页、最近100条列表或费用缓存；完整逐次记录保留在 `ai_call_log`。普通用户接口不返回原始usage、错误响应、价格配置或存储凭据。

## 测试与结果

| 验证 | 结果 |
| --- | --- |
| `mvn test -Dcost.mysql.acceptance=true -q` | 216项，198通过，18按条件跳过，失败/错误0；排除历史遗留Surefire报告 |
| `TaskCostServiceTest` | 5项通过：当前/累计、失败重试、复用、旧记录、越权及HTTP金额字符串 |
| `TaskCostMysqlTest` | 1项通过：临时独立MySQL库，真实Mapper构造映射、SUM、JSON覆盖标记、元数据不重复聚合 |
| 新增快照标记断言后的定向复验 | `AudioPrefilterPreparationServiceTest` 5项通过 |
| `node --test scripts/test_task_cost.cjs` | 7项通过：未知不等于0、金额精度、待核对、错误转义、费用错误隔离、执行代次和异步响应防串页 |
| `node --check` 两个费用相关JS、`git diff --check` | 通过 |
| Chrome桌面与390px窄屏 | 已观察正常费用、折叠明细、旧任务无记录、费用加载失败；窄屏整页无横向溢出 |

测试日志：`logs/ai-cost-p2-regression.log`、`logs/ai-cost-p2-marker-test.log`、`logs/ai-cost-p2-frontend-test.log`。

界面验收使用本地模拟数据，加载实际页面脚本与CSS；不是已部署后端的真实完整链路。截图位于 `output/playwright/fee-desktop.png`、`fee-mobile.png`、`fee-legacy.png`、`fee-error.png`。浏览器仅出现本地favicon缺失404，没有页面脚本异常。

## 后续部署边界

本阶段没有迁移业务库、重启API/Worker或发起真实视频任务。下一阶段需确认无在途任务，备份后执行 `sql/V2.1__ai_call_cost_ledger.sql`，部署API、Worker和前端，再对完整链路验收。18项条件跳过不能视为已通过的付费模型或部署验收。
