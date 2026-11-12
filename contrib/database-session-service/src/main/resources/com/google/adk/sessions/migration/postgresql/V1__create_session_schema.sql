-- Copyright 2026 Google LLC
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
-- Unless required by applicable law or agreed to in writing, software distributed under
-- the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
-- ANY KIND, either express or implied. See the License for the specific language
-- governing permissions and limitations under the License.

-- C collation preserves exact Java key identity.
-- JSON preserves the submitted document instead of normalizing numbers or rejecting escaped NUL.

CREATE TABLE adk_internal_metadata (
  key VARCHAR(128) NOT NULL PRIMARY KEY,
  value VARCHAR(256) NOT NULL
);
INSERT INTO adk_internal_metadata (key, value) VALUES
  ('schema_version', '1'), ('adk_java_storage_format', 'jdbc-v1');

CREATE TABLE app_states (
  app_name VARCHAR(128) COLLATE "C" NOT NULL PRIMARY KEY,
  state JSON NOT NULL CHECK (json_typeof(state) = 'object'),
  update_time TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL
);
CREATE TABLE user_states (
  app_name VARCHAR(128) COLLATE "C" NOT NULL,
  user_id VARCHAR(128) COLLATE "C" NOT NULL,
  state JSON NOT NULL CHECK (json_typeof(state) = 'object'),
  update_time TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
  PRIMARY KEY (app_name, user_id)
);
CREATE TABLE sessions (
  app_name VARCHAR(128) COLLATE "C" NOT NULL,
  user_id VARCHAR(128) COLLATE "C" NOT NULL,
  id VARCHAR(128) COLLATE "C" NOT NULL,
  state JSON NOT NULL CHECK (json_typeof(state) = 'object'),
  create_time TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
  update_time TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
  PRIMARY KEY (app_name, user_id, id)
);
CREATE TABLE events (
  app_name VARCHAR(128) COLLATE "C" NOT NULL,
  user_id VARCHAR(128) COLLATE "C" NOT NULL,
  session_id VARCHAR(128) COLLATE "C" NOT NULL,
  event_sequence BIGINT NOT NULL CHECK (event_sequence > 0),
  id VARCHAR(128) COLLATE "C" NOT NULL,
  invocation_id VARCHAR(256) NOT NULL,
  timestamp TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
  event_data JSON CHECK (event_data IS NULL OR (json_typeof(event_data) = 'object')),
  PRIMARY KEY (id, app_name, user_id, session_id),
  UNIQUE (app_name, user_id, session_id, event_sequence),
  FOREIGN KEY (app_name, user_id, session_id) REFERENCES sessions (app_name, user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_events_app_user_session_ts_id
  ON events (app_name, user_id, session_id, timestamp DESC, id DESC);
