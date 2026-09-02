ALTER TABLE password_reset_tokens
  ADD COLUMN updated_at DATETIME(6) NOT NULL AFTER created_at;

