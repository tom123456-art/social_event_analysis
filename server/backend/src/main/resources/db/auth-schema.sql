CREATE TABLE IF NOT EXISTS app_user_credential (
  user_id BIGINT PRIMARY KEY,
  password_hash VARCHAR(100) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_credential_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_user_session (
  token_hash CHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_session_user (user_id),
  KEY idx_session_expiry (expires_at),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
