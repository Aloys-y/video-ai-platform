-- P3：文本模型调用留痕；默认排序规则应匹配 analysis_execution.task_id。
CREATE TABLE IF NOT EXISTS analysis_text_call (
    task_id VARCHAR(64) NOT NULL,
    execution_no INT NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    batch_no INT NOT NULL,
    request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    response_object_key VARCHAR(512) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (task_id,execution_no,purpose,batch_no),
    CONSTRAINT fk_text_call_execution FOREIGN KEY (task_id,execution_no) REFERENCES analysis_execution(task_id,execution_no),
    CONSTRAINT ck_text_call_key CHECK (batch_no >= 0 AND purpose IN ('SCREEN','SUMMARY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文本模型调用留痕，不用于调度';
