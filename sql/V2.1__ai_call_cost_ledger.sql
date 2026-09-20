-- Manual migration only: back up old ai_call_log and stop in-flight work first.
-- Not executed automatically. Existing test costs are not backfilled.
DROP TABLE IF EXISTS ai_call_log;
CREATE TABLE IF NOT EXISTS ai_call_log (
    call_id CHAR(36) COLLATE utf8mb4_bin PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    execution_no INT NOT NULL,
    stage VARCHAR(32) NOT NULL,
    subtask_no INT NOT NULL,
    model VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NULL,
    remote_task_id VARCHAR(128) NULL,
    status VARCHAR(20) NOT NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    audio_seconds DECIMAL(18,3) NULL,
    usage_json JSON NULL,
    price_snapshot JSON NULL,
    estimated_cost_cny DECIMAL(20,10) NULL,
    cost_unknown_reason VARCHAR(32) NULL,
    error_code VARCHAR(128) NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    INDEX idx_ai_call_task (task_id,execution_no,stage,subtask_no),
    CONSTRAINT ck_ai_call_owner CHECK (execution_no >= 0 AND subtask_no >= 0),
    CONSTRAINT ck_ai_call_stage CHECK (stage IN ('ASR','TEXT_SCREEN','VIDEO_ANALYSIS','RAG_EMBEDDING','RAG_RERANK')),
    CONSTRAINT ck_ai_call_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN','NOT_SENT')),
    CONSTRAINT ck_ai_call_usage CHECK ((input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0) AND (audio_seconds IS NULL OR audio_seconds >= 0)),
    CONSTRAINT ck_ai_call_amount CHECK (estimated_cost_cny IS NULL OR estimated_cost_cny >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
