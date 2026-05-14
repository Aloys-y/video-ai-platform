-- 迁移脚本：新增 OAuth 登录字段
ALTER TABLE user
ADD COLUMN oauth_provider VARCHAR(20) NULL COMMENT 'OAuth 提供商（github/gitee）' AFTER api_secret,
ADD COLUMN oauth_provider_id VARCHAR(64) NULL COMMENT 'OAuth 提供商用户ID' AFTER oauth_provider;

-- 为 OAuth 用户查询加速
CREATE INDEX idx_oauth ON user (oauth_provider, oauth_provider_id);
