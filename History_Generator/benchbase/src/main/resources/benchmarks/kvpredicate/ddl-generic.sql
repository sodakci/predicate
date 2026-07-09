DROP TABLE IF EXISTS kv;
CREATE TABLE kv (
    k     varchar(64) PRIMARY KEY,
    value bigint NOT NULL UNIQUE
);
CREATE INDEX idx_kv_value ON kv (value);
