CREATE TABLE IF NOT EXISTS ai_conversation (
  id CHAR(36) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL DEFAULT '新对话',
  messages JSON NOT NULL,
  model_id VARCHAR(64) NOT NULL DEFAULT 'qwen',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ai_user_updated (user_id, updated_at),
  CONSTRAINT fk_ai_conversation_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
