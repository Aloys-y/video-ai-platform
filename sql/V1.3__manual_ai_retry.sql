-- AI 失败改为由用户手动重新分析。
-- 部署新版本前执行一次，将旧自动重试状态收敛为 FAILED。
UPDATE analysis_task
SET status = 'FAILED',
    error_message = COALESCE(error_message, '自动重试已停用，请手动重新分析'),
    next_retry_at = NULL,
    completed_at = COALESCE(completed_at, NOW()),
    updated_at = NOW()
WHERE status IN ('RETRYING', 'DEAD');

-- retry_count 不再表示自动重试次数，而是单调递增的执行代次。
UPDATE analysis_task
SET retry_count = 0
WHERE retry_count IS NULL;

ALTER TABLE analysis_task
    MODIFY COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '执行代次/用户手动重新分析次数';
