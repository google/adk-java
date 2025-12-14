-- V1__Initial_schema.sql for MySQL
-- Initial database schema for ADK DatabaseSessionService
-- This represents the baseline schema for all database session operations

-- Create sessions table
CREATE TABLE IF NOT EXISTS sessions (
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    id VARCHAR(128) NOT NULL,
    state LONGTEXT,
    create_time TIMESTAMP(6),
    update_time TIMESTAMP(6),
    PRIMARY KEY (app_name, user_id, id)
);

-- Create events table
CREATE TABLE IF NOT EXISTS events (
    id VARCHAR(128) NOT NULL,
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    invocation_id VARCHAR(256),
    author VARCHAR(256),
    actions LONGTEXT,
    timestamp TIMESTAMP(6),
    content LONGTEXT,
    grounding_metadata LONGTEXT,
    custom_metadata LONGTEXT,
    usage_metadata LONGTEXT,
    citation_metadata LONGTEXT,
    partial BOOLEAN,
    turn_complete BOOLEAN,
    error_code VARCHAR(256),
    error_message TEXT,
    interrupted BOOLEAN,
    branch VARCHAR(256),
    long_running_tool_ids_json TEXT,
    input_transcription LONGTEXT,
    output_transcription LONGTEXT,
    finish_reason VARCHAR(256),
    avg_logprobs DOUBLE,
    model_version VARCHAR(256),
    PRIMARY KEY (id, app_name, user_id, session_id),
    FOREIGN KEY (app_name, user_id, session_id) 
        REFERENCES sessions(app_name, user_id, id) 
        ON DELETE CASCADE
);

-- Create app states table
CREATE TABLE IF NOT EXISTS app_states (
    app_name VARCHAR(128) NOT NULL,
    state LONGTEXT,
    update_time TIMESTAMP(6),
    PRIMARY KEY (app_name)
);

-- Create user states table
CREATE TABLE IF NOT EXISTS user_states (
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    state LONGTEXT,
    update_time TIMESTAMP(6),
    PRIMARY KEY (app_name, user_id)
);

-- Add indexes to improve query performance
-- Note: CREATE INDEX IF NOT EXISTS requires MySQL 8.0.32+
-- Using simple CREATE INDEX for broader compatibility

-- Index for looking up sessions by app_name and user_id
CREATE INDEX idx_sessions_app_user ON sessions(app_name, user_id);

-- Index for looking up events by session
CREATE INDEX idx_events_session ON events(app_name, user_id, session_id);

-- Index for sorting events by timestamp
CREATE INDEX idx_events_timestamp ON events(timestamp);