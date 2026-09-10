-- 创建数据库
CREATE DATABASE IF NOT EXISTS video_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE video_ai;

-- ==================== 用户模块 ====================

-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id         VARCHAR(32) NOT NULL COMMENT '业务用户ID',
    username        VARCHAR(64) NOT NULL COMMENT '用户名',
    email           VARCHAR(128) COMMENT '邮箱',
    password        VARCHAR(128) COMMENT '密码(BCrypt哈希)',
    api_key         VARCHAR(64) NOT NULL COMMENT 'API Key',
    api_secret      VARCHAR(128) COMMENT 'API Secret(加密存储)',
    oauth_provider  VARCHAR(20) NULL COMMENT 'OAuth提供商(github/gitee)',
    oauth_provider_id VARCHAR(64) NULL COMMENT 'OAuth提供商用户ID',
    role            VARCHAR(20) DEFAULT 'USER' COMMENT '角色: USER/ADMIN/VIP',
    status          TINYINT DEFAULT 1 COMMENT '1:正常 0:禁用',
    rate_limit      INT DEFAULT 100 COMMENT '用户限流QPS',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_user_id (user_id),
    UNIQUE KEY uk_api_key (api_key),
    UNIQUE KEY uk_email (email),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 注意：测试用户由 DataInitializer（@Profile("dev")）在应用启动时自动创建
-- 不在 SQL 中硬编码任何凭证，避免泄露到版本控制

-- ==================== 上传模块 ====================

-- 上传会话表（支持断点续传）
CREATE TABLE IF NOT EXISTS upload_session (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    upload_id       VARCHAR(64) NOT NULL COMMENT '上传会话ID',
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    file_name       VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_hash       VARCHAR(64) COMMENT '文件MD5哈希',
    total_size      BIGINT NOT NULL COMMENT '文件总大小(字节)',
    chunk_size      INT NOT NULL COMMENT '分片大小(字节)',
    total_chunks    INT NOT NULL COMMENT '总分片数',
    uploaded_chunks JSON DEFAULT ('[]') COMMENT '已上传分片索引',
    status          TINYINT DEFAULT 0 COMMENT '0:上传中 1:已完成 2:已合并 3:已过期 4:合并失败',
    storage_path    VARCHAR(512) COMMENT '合并后的存储路径',
    b2_upload_id    VARCHAR(256) NULL COMMENT 'B2/云存储 Multipart Upload ID',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    expired_at      DATETIME COMMENT '过期时间',

    UNIQUE KEY uk_upload_id (upload_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status_created (status, created_at),
    INDEX idx_file_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上传会话表';

-- 分析任务表
CREATE TABLE IF NOT EXISTS analysis_task (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_id         VARCHAR(64) NOT NULL COMMENT '任务ID',
    task_name       VARCHAR(255) DEFAULT NULL COMMENT '任务名称(用户自定义)',
    upload_id       VARCHAR(64) NOT NULL COMMENT '关联的上传ID',
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    video_url       VARCHAR(512) NOT NULL COMMENT '视频URL',
    video_duration  INT COMMENT '视频时长(秒)',
    prompt          TEXT COMMENT '用户自定义分析提示词',

    -- 状态管理
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED',
    progress        INT DEFAULT 0 COMMENT '进度百分比(0-100)',

    -- 执行代次（首次为0，用户每次手动重新分析后递增）
    attempt_no     INT NOT NULL DEFAULT 0 COMMENT '执行代次/用户手动重新分析次数',
    analysis_mode   VARCHAR(32) NOT NULL DEFAULT 'AUDIO_PREFILTER' COMMENT '分析模式',
    current_step    VARCHAR(32) NULL COMMENT '当前执行步骤，不用于领取任务',
    owner_token VARCHAR(64) NULL COMMENT '当前执行所有者令牌',
    lease_until DATETIME(3) NULL COMMENT '数据库时间租约',
    error_code VARCHAR(64) NULL COMMENT '固定错误分类',
    error_message   TEXT COMMENT '错误信息',

    -- AI分析结果
    frame_count     INT COMMENT '抽取帧数',
    ai_model        VARCHAR(50) COMMENT '使用的AI模型',
    tokens_used     BIGINT COMMENT '消耗的Token数',
    result          LONGTEXT COMMENT '分析结果',
    summary         TEXT COMMENT '视频摘要',

    -- 时间记录
    created_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    started_at      DATETIME(3) COMMENT '开始处理时间',
    finished_at    DATETIME(3) COMMENT '完成时间',
    updated_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    UNIQUE KEY uk_task_id (task_id),
    INDEX idx_user_id (user_id),
    INDEX idx_upload_id (upload_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    INDEX idx_status_started (status, started_at),
    INDEX idx_dispatch_pending(status, created_at, task_id),
    INDEX idx_dispatch_expired(status, lease_until, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分析任务表';

-- 用户配额表（成本控制）
CREATE TABLE IF NOT EXISTS user_quota (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    quota_monthly   BIGINT NOT NULL DEFAULT 10000 COMMENT '月度Token配额',
    used_monthly    BIGINT DEFAULT 0 COMMENT '已使用月度配额',
    quota_daily     BIGINT NOT NULL DEFAULT 500 COMMENT '每日Token配额',
    used_daily      BIGINT DEFAULT 0 COMMENT '已使用每日配额',
    reset_daily_at  DATETIME COMMENT '每日配额重置时间',
    reset_monthly_at DATETIME COMMENT '月度配额重置时间',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户配额表';

-- AI调用日志表（成本审计）
CREATE TABLE IF NOT EXISTS ai_call_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_id         VARCHAR(64) NOT NULL COMMENT '任务ID',
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    model           VARCHAR(50) NOT NULL COMMENT 'AI模型',
    input_tokens    INT COMMENT '输入Token数',
    output_tokens   INT COMMENT '输出Token数',
    total_tokens    BIGINT COMMENT '总Token数',
    cost_amount     DECIMAL(10,6) COMMENT '费用(美元)',
    latency_ms      INT COMMENT '响应延迟(毫秒)',
    status          TINYINT COMMENT '1:成功 2:失败',
    error_message   TEXT COMMENT '错误信息',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    INDEX idx_task_id (task_id),
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI调用日志表';

-- ==================== RAG 知识库 ====================

CREATE TABLE IF NOT EXISTS knowledge_base (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    base_code           VARCHAR(64) NOT NULL COMMENT '知识库编码',
    name                VARCHAR(128) NOT NULL COMMENT '知识库名称',
    domain              VARCHAR(64) NOT NULL COMMENT '领域',
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    current_version_tag VARCHAR(64) NOT NULL COMMENT '当前全局版本',
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_base_code (base_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表';

CREATE TABLE IF NOT EXISTS knowledge_card (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    base_code        VARCHAR(64) NOT NULL COMMENT '知识库编码',
    card_code        VARCHAR(64) NOT NULL COMMENT '卡片编码',
    title            VARCHAR(255) NOT NULL COMMENT '标题',
    category         VARCHAR(32) NOT NULL COMMENT '类别',
    subject_code     VARCHAR(64) DEFAULT NULL COMMENT '主题编码',
    aliases          JSON DEFAULT ('[]') COMMENT '别名列表',
    tags             JSON DEFAULT ('[]') COMMENT '标签列表',
    content_markdown LONGTEXT NOT NULL COMMENT 'Markdown正文',
    enabled          TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    timeless         TINYINT NOT NULL DEFAULT 0 COMMENT '是否常驻知识',
    version_tag      VARCHAR(64) NOT NULL COMMENT '版本标签',
    index_status     VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '索引状态',
    last_job_id      VARCHAR(64) DEFAULT NULL COMMENT '最近索引任务ID',
    created_by       VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    updated_by       VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    indexed_at       DATETIME DEFAULT NULL COMMENT '最近索引时间',
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_base_card_code (base_code, card_code),
    INDEX idx_card_category (base_code, category),
    INDEX idx_card_enabled_version (base_code, enabled, version_tag),
    INDEX idx_card_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识卡片表';

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    base_code      VARCHAR(64) NOT NULL COMMENT '知识库编码',
    card_code      VARCHAR(64) NOT NULL COMMENT '卡片编码',
    version_tag    VARCHAR(64) NOT NULL COMMENT '版本标签',
    category       VARCHAR(32) NOT NULL COMMENT '类别',
    subject_code   VARCHAR(64) DEFAULT NULL COMMENT '主题编码',
    chunk_no       INT NOT NULL COMMENT '块序号',
    heading_path   VARCHAR(512) DEFAULT NULL COMMENT '标题路径',
    title          VARCHAR(255) NOT NULL COMMENT '卡片标题',
    content_text   LONGTEXT NOT NULL COMMENT '分块文本',
    content_length INT NOT NULL DEFAULT 0 COMMENT '文本长度',
    metadata_json  LONGTEXT DEFAULT NULL COMMENT '分块元数据',
    vector_id      VARCHAR(128) NOT NULL COMMENT 'Milvus向量ID',
    index_status   VARCHAR(16) NOT NULL DEFAULT 'INDEXED' COMMENT '索引状态',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_vector_id (vector_id),
    UNIQUE KEY uk_card_chunk (base_code, card_code, chunk_no),
    INDEX idx_chunk_card (base_code, card_code),
    INDEX idx_chunk_version (base_code, version_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识分块表';

CREATE TABLE IF NOT EXISTS knowledge_index_job (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    job_id         VARCHAR(64) NOT NULL COMMENT '任务ID',
    base_code      VARCHAR(64) NOT NULL COMMENT '知识库编码',
    job_type       VARCHAR(32) NOT NULL COMMENT '任务类型',
    card_code      VARCHAR(64) DEFAULT NULL COMMENT '卡片编码',
    status         VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT '任务状态',
    payload_json   LONGTEXT DEFAULT NULL COMMENT '任务载荷',
    total_chunks   INT NOT NULL DEFAULT 0 COMMENT '总块数',
    success_chunks INT NOT NULL DEFAULT 0 COMMENT '成功块数',
    failed_chunks  INT NOT NULL DEFAULT 0 COMMENT '失败块数',
    error_message  TEXT DEFAULT NULL COMMENT '错误信息',
    created_by     VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    queued_at      DATETIME DEFAULT NULL COMMENT '入队时间',
    started_at     DATETIME DEFAULT NULL COMMENT '开始处理时间',
    completed_at   DATETIME DEFAULT NULL COMMENT '完成时间',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_knowledge_job_id (job_id),
    INDEX idx_knowledge_job_status (status, created_at),
    INDEX idx_knowledge_job_card (base_code, card_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识索引任务表';

CREATE TABLE IF NOT EXISTS task_rag_context (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_id        VARCHAR(64) NOT NULL COMMENT '任务ID',
    base_code      VARCHAR(64) NOT NULL COMMENT '知识库编码',
    version_tag    VARCHAR(64) DEFAULT NULL COMMENT '版本标签',
    query_text     TEXT DEFAULT NULL COMMENT '检索查询',
    retrieval_mode VARCHAR(32) NOT NULL DEFAULT 'milvus-ann' COMMENT '检索模式',
    top_k          INT NOT NULL DEFAULT 0 COMMENT '初筛数量',
    hit_count      INT NOT NULL DEFAULT 0 COMMENT '命中数量',
    context_chars  INT NOT NULL DEFAULT 0 COMMENT '注入上下文字符数',
    status         VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'RAG状态',
    latency_ms     INT NOT NULL DEFAULT 0 COMMENT '耗时',
    snapshot_json  LONGTEXT DEFAULT NULL COMMENT '完整快照',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_task_rag_task (task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务RAG上下文表';

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
    CONSTRAINT ck_execution_mode CHECK (analysis_mode IN ('AUDIO_PREFILTER')),
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
