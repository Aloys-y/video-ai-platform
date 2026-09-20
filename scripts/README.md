# 脚本入口

从仓库根目录执行，避免相对路径指向错误目录。

| 用途 | 入口 |
| --- | --- |
| Windows启动本地服务 | `start-database-dev.ps1`（先检查脚本中的本机Java/Python/FFmpeg路径） |
| 费用页面逻辑测试 | `node --test scripts/test_task_cost.cjs` |
| 验收回执费用核算 | `dispatch_acceptance_cost.py`、`test_dispatch_acceptance_cost.py`（包含历史回执计价规则，不是线上计费入口） |
| ASR粗筛原型 | `asr_prefilter_p0.py`、`test_asr_prefilter_p0.py` |
| 上传压测 | `bench-upload.mjs` |
| RAG导入、评估和语料维护 | `import_rag_data.py`、`rag_*`、`*_legend_*`、`curate_pc_legend_data.py` |
| 手动SQL迁移工具 | `JdbcSqlRunner.java`（凭据通过环境变量传入） |
| 历史Kafka/Outbox压测 | `archive/outbox_load_test.py`，不适用于当前数据库调度版本 |

有写入或真实模型调用的脚本，运行前先查看参数与目标环境；默认测试不要求付费调用。
