CREATE TABLE verification_codes (
  id BINARY(16) NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  channel VARCHAR(16) NOT NULL,
  subject VARCHAR(128) NOT NULL,
  destination VARCHAR(128) NOT NULL,
  code_hash CHAR(64) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  consumed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_verification_lookup (purpose, channel, subject, created_at),
  KEY idx_verification_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- V1 时代已有的联系方式视为历史已登记联系方式，保证老账号可立即使用验证码找回密码。
UPDATE users SET email_verified = TRUE WHERE email IS NOT NULL AND email <> '';
UPDATE users SET phone_verified = TRUE WHERE phone IS NOT NULL AND phone <> '';
