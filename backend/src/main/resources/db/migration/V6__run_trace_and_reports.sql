CREATE TABLE run_contexts (
  run_id BINARY(16) PRIMARY KEY,
  assistant_message_id BINARY(16) NOT NULL,
  task_mode VARCHAR(32) NOT NULL DEFAULT 'AUTO',
  evidence_json LONGTEXT NULL,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT fk_run_context_run FOREIGN KEY (run_id) REFERENCES agent_runs(id)
);
CREATE TABLE dify_mode_conversations (
  conversation_id BINARY(16) NOT NULL,
  mode_key VARCHAR(100) NOT NULL,
  dify_conversation_id VARCHAR(100) NOT NULL,
  PRIMARY KEY(conversation_id,mode_key),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id)
);
CREATE TABLE run_events (
  sequence BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BINARY(16) NOT NULL,
  event_json TEXT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_run_events (run_id, sequence),
  CONSTRAINT fk_run_event_run FOREIGN KEY (run_id) REFERENCES agent_runs(id)
);
CREATE TABLE report_templates (
  id VARCHAR(36) PRIMARY KEY,
  template_key VARCHAR(48) NOT NULL,
  version INT NOT NULL,
  name VARCHAR(120) NOT NULL,
  sections_json TEXT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_report_template_version(template_key, version)
);
CREATE TABLE saved_reports (
  id VARCHAR(36) PRIMARY KEY,
  root_id VARCHAR(36) NOT NULL,
  version INT NOT NULL,
  user_id BINARY(16) NOT NULL,
  run_id BINARY(16) NOT NULL,
  template_id VARCHAR(36) NOT NULL,
  title VARCHAR(160) NOT NULL,
  content LONGTEXT NOT NULL,
  snapshot_json LONGTEXT NOT NULL,
  snapshot_sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_report_version(root_id, version),
  INDEX idx_reports_user(user_id, created_at),
  CONSTRAINT fk_report_run FOREIGN KEY(run_id) REFERENCES agent_runs(id),
  CONSTRAINT fk_report_template FOREIGN KEY(template_id) REFERENCES report_templates(id)
);
INSERT INTO report_templates(id,template_key,version,name,sections_json) VALUES
('builtin-detailed-v1','detailed',1,'详细技术报告','["scope","answer","evidence","limitations"]'),
('builtin-brief-v1','brief',1,'快速研判简报','["scope","evidence","limitations"]'),
('builtin-vulnerability-v1','vulnerability',1,'漏洞处置报告','["scope","evidence","remediation","limitations"]');
