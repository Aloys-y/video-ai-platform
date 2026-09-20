-- 仅新增 Mock 通知表；执行前按项目要求备份并确认无在途任务。
CREATE TABLE IF NOT EXISTS mock_mail_notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(128) NOT NULL UNIQUE,
  task_id VARCHAR(64) NOT NULL,
  execution_no INT NOT NULL,
  subject VARCHAR(255) NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  claim_token VARCHAR(64),
  claim_until TIMESTAMP(3) NULL,
  error_code VARCHAR(64),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_mock_mail_due (status,next_attempt_at),
  INDEX idx_mock_mail_task (task_id,created_at)
);
