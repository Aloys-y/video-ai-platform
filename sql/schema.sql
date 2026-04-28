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
    role            VARCHAR(20) DEFAULT 'USER' COMMENT '角色: USER/ADMIN/VIP',
    status          TINYINT DEFAULT 1 COMMENT '1:正常 0:禁用',
    rate_limit      INT DEFAULT 100 COMMENT '用户限流QPS',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_user_id (user_id),
    UNIQUE KEY uk_api_key (api_key),
    UNIQUE KEY uk_email (email),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

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
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    expired_at      DATETIME COMMENT '过期时间',

    UNIQUE KEY uk_upload_id (upload_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status_created (status, created_at),
    INDEX idx_file_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传会话表';

-- 分析任务表
CREATE TABLE IF NOT EXISTS analysis_task (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_id         VARCHAR(64) NOT NULL COMMENT '任务ID',
    task_name       VARCHAR(255) DEFAULT NULL COMMENT '任务名称(用户自定义)',
    upload_id       VARCHAR(64) NOT NULL COMMENT '关联的上传ID',
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    video_url       VARCHAR(512) NOT NULL COMMENT '视频URL',
    video_duration  INT COMMENT '视频时长(秒)',

    -- 状态管理
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
    progress        INT DEFAULT 0 COMMENT '进度百分比(0-100)',

    -- 重试机制
    retry_count     INT DEFAULT 0 COMMENT '重试次数',
    max_retry       INT DEFAULT 3 COMMENT '最大重试次数',
    error_message   TEXT COMMENT '错误信息',

    -- AI分析结果
    frame_count     INT COMMENT '抽取帧数',
    ai_model        VARCHAR(50) COMMENT '使用的AI模型',
    tokens_used     BIGINT COMMENT '消耗的Token数',
    result          JSON COMMENT '分析结果',
    summary         TEXT COMMENT '视频摘要',

    -- 时间记录
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    started_at      DATETIME COMMENT '开始处理时间',
    completed_at    DATETIME COMMENT '完成时间',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_task_id (task_id),
    INDEX idx_user_id (user_id),
    INDEX idx_upload_id (upload_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析任务表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户配额表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI调用日志表';
