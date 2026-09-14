CREATE TABLE IF NOT EXISTS adk_sessions (
    app_name VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    state JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (app_name, user_id, session_id),
    INDEX idx_adk_sessions_updated (app_name, user_id, updated_at)
);

CREATE TABLE IF NOT EXISTS adk_events (
    event_sequence BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_name VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    event_data JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_adk_events_session
        FOREIGN KEY (app_name, user_id, session_id)
        REFERENCES adk_sessions (app_name, user_id, session_id)
        ON DELETE CASCADE,
    INDEX idx_adk_events_session (app_name, user_id, session_id)
);

CREATE TABLE IF NOT EXISTS adk_app_state (
    app_name VARCHAR(255) NOT NULL,
    state_key VARCHAR(255) NOT NULL,
    state_value JSON NOT NULL,
    PRIMARY KEY (app_name, state_key)
);

CREATE TABLE IF NOT EXISTS adk_user_state (
    app_name VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    state_key VARCHAR(255) NOT NULL,
    state_value JSON NOT NULL,
    PRIMARY KEY (app_name, user_id, state_key)
);
