-- 迁移脚本：新增 B2/云存储 Multipart Upload ID 字段
-- 用于存储 S3 Multipart Upload ID，支持 Backblaze B2 / MinIO 的 Multipart Upload 流程

ALTER TABLE upload_session
ADD COLUMN b2_upload_id VARCHAR(256) NULL COMMENT 'B2/云存储 Multipart Upload ID'
AFTER storage_path;

-- 将旧有 UPLOADING 状态的会话标记为 EXPIRED
-- （旧流程的 composeObject 无法兼容新的 multipart upload 流程）
UPDATE upload_session
SET status = 3
WHERE status = 0 AND b2_upload_id IS NULL;
