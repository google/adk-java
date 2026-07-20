CREATE TABLE memories (
    id UUID PRIMARY KEY,
    app_id text,
    user_id text,
    key text,
    value text,
    embedding vector<float, 4>
);

CREATE CUSTOM INDEX ON memories (embedding) USING 'org.apache.cassandra.index.sai.StorageAttachedIndex';
CREATE CUSTOM INDEX ON memories (app_id) USING 'org.apache.cassandra.index.sai.StorageAttachedIndex';
CREATE CUSTOM INDEX ON memories (user_id) USING 'org.apache.cassandra.index.sai.StorageAttachedIndex';

INSERT INTO memories (id, app_id, user_id, key, value, embedding) VALUES (uuid(), 'test_app', 'test_user', 'test_key_1', 'test_value_1', [0.1, 0.2, 0.3, 0.4]);
INSERT INTO memories (id, app_id, user_id, key, value, embedding) VALUES (uuid(), 'test_app', 'test_user', 'test_key_2', 'test_value_2', [0.2, 0.3, 0.4, 0.5]);
INSERT INTO memories (id, app_id, user_id, key, value, embedding) VALUES (uuid(), 'test_app', 'another_user', 'test_key_3', 'test_value_3', [0.3, 0.4, 0.5, 0.6]);
INSERT INTO memories (id, app_id, user_id, key, value, embedding) VALUES (uuid(), 'another_app', 'test_user', 'test_key_4', 'test_value_4', [0.4, 0.5, 0.6, 0.7]);