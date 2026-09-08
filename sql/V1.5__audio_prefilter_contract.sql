-- MySQL 8.0.16+；手动执行一次，先于发布新版本应用。见 V1.5__migration.md。
ALTER TABLE analysis_task
    ADD COLUMN analysis_mode VARCHAR(32) NOT NULL DEFAULT 'DIRECT_VIDEO' COMMENT '分析模式',
    ADD COLUMN current_step VARCHAR(32) NULL COMMENT '当前执行步骤，不用于领取任务';

-- 每代一行；不承担任务领取，配置只插入一次，产物引用只从 NULL 写入一次。
CREATE TABLE IF NOT EXISTS analysis_execution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id VARCHAR(64) NOT NULL,
    execution_no INT NOT NULL,
    analysis_mode VARCHAR(32) NOT NULL,
    config_snapshot LONGTEXT COLLATE utf8mb4_bin NOT NULL COMMENT '无凭据的完整配置JSON快照',
    config_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化配置SHA256',
    input_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原视频内容SHA256',
    asr_task_id VARCHAR(128) NULL,
    transcript_object_key VARCHAR(512) NULL,
    candidates_object_key VARCHAR(512) NULL,
    segments_object_key VARCHAR(512) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_execution (task_id, execution_no),
    CONSTRAINT fk_execution_task FOREIGN KEY (task_id) REFERENCES analysis_task(task_id),
    CONSTRAINT ck_execution_no CHECK (execution_no >= 0),
    CONSTRAINT ck_execution_mode CHECK (analysis_mode IN ('DIRECT_VIDEO', 'AUDIO_PREFILTER')),
    CONSTRAINT ck_execution_hash CHECK (CHAR_LENGTH(config_hash) = 64 AND CHAR_LENGTH(input_hash) = 64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按代次保存的分析配置及产物引用';

CREATE TABLE IF NOT EXISTS analysis_segment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id VARCHAR(64) NOT NULL,
    execution_no INT NOT NULL,
    segment_no INT NOT NULL COMMENT '从0开始，按时间排序',
    start_ms BIGINT NOT NULL COMMENT '原视频毫秒坐标，含起点',
    end_ms BIGINT NOT NULL COMMENT '原视频毫秒坐标，不含终点',
    object_key VARCHAR(512) NOT NULL COMMENT '裁剪片段对象键，不保存签名URL',
    status VARCHAR(20) NOT NULL DEFAULT 'PREPARED',
    result LONGTEXT NULL,
    error_message TEXT NULL,
    usage_json LONGTEXT NULL COMMENT '本次调用厂商实际用量JSON，未知或复用为NULL',
    reused_execution_no INT NULL,
    reused_segment_no INT NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_segment (task_id, execution_no, segment_no),
    CONSTRAINT fk_segment_execution FOREIGN KEY (task_id, execution_no)
        REFERENCES analysis_execution(task_id, execution_no),
    CONSTRAINT fk_segment_source FOREIGN KEY (task_id, reused_execution_no, reused_segment_no)
        REFERENCES analysis_segment(task_id, execution_no, segment_no),
    CONSTRAINT ck_segment_range CHECK (segment_no >= 0 AND start_ms >= 0 AND end_ms > start_ms),
    CONSTRAINT ck_segment_status CHECK (status IN ('PREPARED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_segment_result CHECK (
        (status = 'SUCCEEDED' AND result IS NOT NULL AND error_message IS NULL)
        OR (status = 'FAILED' AND error_message IS NOT NULL AND result IS NULL)
        OR (status IN ('PREPARED', 'PROCESSING') AND result IS NULL AND error_message IS NULL)),
    CONSTRAINT ck_segment_source CHECK (
        (reused_execution_no IS NULL AND reused_segment_no IS NULL)
        OR (reused_execution_no IS NOT NULL AND reused_segment_no IS NOT NULL
            AND reused_execution_no >= 0 AND reused_execution_no < execution_no
            AND reused_segment_no >= 0 AND status = 'SUCCEEDED' AND usage_json IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分析片段与执行结果';
