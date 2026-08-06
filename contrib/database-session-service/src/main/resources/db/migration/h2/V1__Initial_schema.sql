-- V1__Initial_schema.sql for H2 Database
-- Initial database schema for ADK DatabaseSessionService (v1 format)
-- This schema matches Python ADK v1 with simplified event storage using CLOB

-- Create metadata table for schema versioning
CREATE TABLE IF NOT EXISTS adk_internal_metadata (
    "KEY" VARCHAR(128) PRIMARY KEY,
    "VALUE" VARCHAR(256)
);

-- Insert schema version (1 = v1 CLOB schema format, compatible with Python ADK)
MERGE INTO adk_internal_metadata ("KEY", "VALUE") VALUES ('schema_version', '1');

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

-- Create events table (v1 format with event_data CLOB column)
CREATE TABLE IF NOT EXISTS events (
    id VARCHAR(128) NOT NULL,
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    invocation_id VARCHAR(256),
    timestamp TIMESTAMP(6),
    event_data CLOB,
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
