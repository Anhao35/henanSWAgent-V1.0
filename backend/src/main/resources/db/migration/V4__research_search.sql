CREATE TABLE research_searches (
  id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  query_text VARCHAR(1000) NOT NULL,
  filters_json VARCHAR(2000) NULL,
  sources VARCHAR(255) NULL,
  result_count INT NOT NULL DEFAULT 0,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_research_search_user_time (user_id, created_at),
  CONSTRAINT fk_research_search_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE research_saved_items (
  id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  source VARCHAR(32) NOT NULL,
  source_id VARCHAR(512) NOT NULL,
  item_type VARCHAR(32) NOT NULL,
  title VARCHAR(1000) NOT NULL,
  authors TEXT NULL,
  publication_year INT NULL,
  venue VARCHAR(500) NULL,
  abstract_text LONGTEXT NULL,
  doi VARCHAR(255) NULL,
  source_url VARCHAR(1500) NULL,
  open_access_url VARCHAR(1500) NULL,
  citation_count INT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_saved_user_source_item (user_id, source, source_id(191)),
  KEY idx_saved_user_time (user_id, created_at),
  CONSTRAINT fk_research_saved_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
