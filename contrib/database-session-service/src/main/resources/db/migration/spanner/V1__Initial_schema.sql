-- V1__Initial_schema.sql for Cloud Spanner
-- Initial database schema for ADK DatabaseSessionService (v1 format)
-- This schema matches Python ADK v1 with simplified event storage using JSON

-- Create metadata table for schema versioning
CREATE TABLE adk_internal_metadata (
    key STRING(128) NOT NULL,
    value STRING(256)
) PRIMARY KEY (key);

-- Insert schema version (1 = v1 JSON schema format, compatible with Python ADK)
INSERT INTO adk_internal_metadata (key, value) VALUES ('schema_version', '1');

-- Create sessions table
CREATE TABLE sessions (
    app_name STRING(128) NOT NULL,
    user_id STRING(128) NOT NULL,
    id STRING(128) NOT NULL,
    state JSON,
    create_time TIMESTAMP,
    update_time TIMESTAMP
) PRIMARY KEY (app_name, user_id, id);

-- Create events table (v1 format with event_data JSON column)
-- Note: Spanner does not support traditional FOREIGN KEY constraints with ON DELETE CASCADE
-- We avoid INTERLEAVE IN PARENT to keep the schema simpler and compatible with the DAO layer
-- Applications must handle cascade deletes manually if needed
CREATE TABLE events (
    id STRING(128) NOT NULL,
    app_name STRING(128) NOT NULL,
    user_id STRING(128) NOT NULL,
    session_id STRING(128) NOT NULL,
    invocation_id STRING(256),
    timestamp TIMESTAMP,
    event_data JSON
) PRIMARY KEY (id, app_name, user_id, session_id);

-- Create app states table
CREATE TABLE app_states (
    app_name STRING(128) NOT NULL,
    state JSON,
    update_time TIMESTAMP
) PRIMARY KEY (app_name);

-- Create user states table
CREATE TABLE user_states (
    app_name STRING(128) NOT NULL,
    user_id STRING(128) NOT NULL,
    state JSON,
    update_time TIMESTAMP
) PRIMARY KEY (app_name, user_id);

-- Add indexes to improve query performance

-- Index for looking up sessions by app_name and user_id
CREATE INDEX idx_sessions_app_user ON sessions(app_name, user_id);

-- Index for sorting events by timestamp
CREATE INDEX idx_events_timestamp ON events(timestamp);
