-- P2：每个音轨分段记录远端任务ID和产物，不承担任务调度。
-- 执行前将本表默认 COLLATE 与 analysis_execution.task_id 保持一致。
CREATE TABLE IF NOT EXISTS analysis_asr_part (
    task_id VARCHAR(64) NOT NULL,
    execution_no INT NOT NULL,
    part_no INT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    audio_object_key VARCHAR(512) NOT NULL,
    asr_task_id VARCHAR(128) NULL COMMENT 'NULL未提交，SUBMITTING提交状态不确定，其余为远端ID',
    transcript_object_key VARCHAR(512) NULL,
    usage_json LONGTEXT NULL,
    reused_execution_no INT NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (task_id, execution_no, part_no),
    CONSTRAINT fk_asr_part_execution FOREIGN KEY (task_id, execution_no)
        REFERENCES analysis_execution(task_id, execution_no),
    CONSTRAINT ck_asr_part_range CHECK (part_no >= 0 AND start_ms >= 0 AND end_ms > start_ms),
    CONSTRAINT ck_asr_part_reuse CHECK (reused_execution_no IS NULL OR (reused_execution_no >= 0 AND reused_execution_no < execution_no)),
    CONSTRAINT ck_asr_part_result CHECK (transcript_object_key IS NULL OR
        (asr_task_id IS NOT NULL AND asr_task_id <> 'SUBMITTING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='音轨分段转写记录';
