DROP TABLE IF EXISTS kv;
CREATE TABLE kv (
    k     text PRIMARY KEY,
    value bigint NOT NULL UNIQUE
);
CREATE INDEX idx_kv_value ON kv (value);
