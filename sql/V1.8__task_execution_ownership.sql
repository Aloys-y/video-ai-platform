-- 部署前先停止旧同步Worker；新增字段不改变现有任务结果。
ALTER TABLE analysis_task
 ADD COLUMN execution_owner VARCHAR(64) NULL COMMENT '当前执行所有者令牌',
 ADD COLUMN execution_lease_until DATETIME(3) NULL COMMENT '数据库时间租约',
 ADD COLUMN execution_heartbeat_at DATETIME(3) NULL COMMENT '最后续租时间';
