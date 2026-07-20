CREATE TABLE spans (
    -- Primary identifiers (OpenTelemetry standard)
    trace_id VARCHAR(50) NOT NULL,
    span_id VARCHAR(50) NOT NULL,
    parent_span_id VARCHAR(50),
    user_id VARCHAR(100),
    -- Span metadata
    name VARCHAR(100) NOT NULL,

    -- Timing (nanoseconds for precision)
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    duration DECIMAL(10,2),

    gen_ai_request_model VARCHAR(100),
    event_id VARCHAR(100),

    -- Status
    status_code VARCHAR(20), -- OK, ERROR, UNSET
    status_message TEXT,

    attributes JSONB,

    PRIMARY KEY (trace_id, span_id)
);
CREATE INDEX idx_spans_trace_id ON spans (trace_id);
CREATE INDEX idx_spans_parent_span_id ON spans (parent_span_id);
CREATE INDEX idx_spans_start_time ON spans (start_time);
