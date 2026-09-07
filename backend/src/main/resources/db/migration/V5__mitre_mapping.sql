CREATE TABLE mitre_mapping_runs (
  id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  mapping_type VARCHAR(16) NOT NULL,
  input_text LONGTEXT NOT NULL,
  input_hash CHAR(64) NOT NULL,
  result_json LONGTEXT NULL,
  model VARCHAR(100) NULL,
  status VARCHAR(32) NOT NULL,
  error_message VARCHAR(500) NULL,
  prompt_tokens INT NULL,
  completion_tokens INT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_mitre_mapping_user_time (user_id, created_at),
  KEY idx_mitre_mapping_cache (user_id, mapping_type, input_hash, status, created_at),
  CONSTRAINT fk_mitre_mapping_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
