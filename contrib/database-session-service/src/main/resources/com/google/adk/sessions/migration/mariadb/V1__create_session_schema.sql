-- Copyright 2026 Google LLC
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
-- Unless required by applicable law or agreed to in writing, software distributed under
-- the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
-- ANY KIND, either express or implied. See the License for the specific language
-- governing permissions and limitations under the License.

-- UTF-8 bytes avoid case/accent folding and PAD SPACE key comparison.
-- MariaDB JSON is validated LONGTEXT, not MySQL binary JSON.

CREATE TABLE adk_internal_metadata (
  `key` VARCHAR(128) NOT NULL PRIMARY KEY,
  value VARCHAR(256) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_nopad_bin;
INSERT INTO adk_internal_metadata (`key`, value) VALUES
  ('schema_version', '1'), ('adk_java_storage_format', 'jdbc-v1');

CREATE TABLE app_states (
  app_name VARCHAR(128) NOT NULL PRIMARY KEY,
  state LONGTEXT NOT NULL CHECK (JSON_VALID(state) AND JSON_TYPE(state) = 'OBJECT'),
  update_time DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_nopad_bin;
CREATE TABLE user_states (
  app_name VARCHAR(128) NOT NULL,
  user_id VARCHAR(128) NOT NULL,
  state LONGTEXT NOT NULL CHECK (JSON_VALID(state) AND JSON_TYPE(state) = 'OBJECT'),
  update_time DATETIME(6) NOT NULL,
  PRIMARY KEY (app_name, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_nopad_bin;
CREATE TABLE sessions (
  app_name VARCHAR(128) NOT NULL,
  user_id VARCHAR(128) NOT NULL,
  id VARCHAR(128) NOT NULL,
  state LONGTEXT NOT NULL CHECK (JSON_VALID(state) AND JSON_TYPE(state) = 'OBJECT'),
  create_time DATETIME(6) NOT NULL,
  update_time DATETIME(6) NOT NULL,
  PRIMARY KEY (app_name, user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_nopad_bin;
CREATE TABLE events (
  app_name VARCHAR(128) NOT NULL,
  user_id VARCHAR(128) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  event_sequence BIGINT NOT NULL CHECK (event_sequence > 0),
  id VARCHAR(128) NOT NULL,
  invocation_id VARCHAR(256) NOT NULL,
  timestamp DATETIME(6) NOT NULL,
  event_data LONGTEXT CHECK (event_data IS NULL OR (JSON_VALID(event_data) AND JSON_TYPE(event_data) = 'OBJECT')),
  PRIMARY KEY (id, app_name, user_id, session_id),
  UNIQUE (app_name, user_id, session_id, event_sequence),
  FOREIGN KEY (app_name, user_id, session_id) REFERENCES sessions (app_name, user_id, id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_nopad_bin;
CREATE INDEX idx_events_app_user_session_ts_id
  ON events (app_name, user_id, session_id, timestamp DESC, id DESC);
