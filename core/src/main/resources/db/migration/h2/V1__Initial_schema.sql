-- V1__Initial_schema.sql for H2 Database
-- Initial database schema for ADK DatabaseSessionService
-- This represents the baseline schema for all database session operations

-- Create sessions table
CREATE TABLE IF NOT EXISTS sessions (
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    id VARCHAR(128) NOT NULL,
    state CLOB,
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
    actions CLOB,
    timestamp TIMESTAMP(6),
    content CLOB,
    grounding_metadata CLOB,
    custom_metadata CLOB,
    usage_metadata CLOB,
    citation_metadata CLOB,
    partial BOOLEAN,
    turn_complete BOOLEAN,
    error_code VARCHAR(256),
    error_message CLOB,
    interrupted BOOLEAN,
    branch VARCHAR(256),
    long_running_tool_ids_json CLOB,
    input_transcription CLOB,
    output_transcription CLOB,
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
    state CLOB,
    update_time TIMESTAMP(6),
    PRIMARY KEY (app_name)
);

-- Create user states table
CREATE TABLE IF NOT EXISTS user_states (
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    state CLOB,
    update_time TIMESTAMP(6),
    PRIMARY KEY (app_name, user_id)
);

-- Add indexes to improve query performance

-- Index for looking up sessions by app_name and user_id
CREATE INDEX IF NOT EXISTS idx_sessions_app_user ON sessions(app_name, user_id);

-- Index for looking up events by session
CREATE INDEX IF NOT EXISTS idx_events_session ON events(app_name, user_id, session_id);

-- Index for sorting events by timestamp
CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp);