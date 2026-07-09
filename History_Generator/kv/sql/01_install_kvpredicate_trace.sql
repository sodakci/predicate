-- PostgreSQL-side evidence store for the BenchBase kvpredicate workload.
--
-- Run this after BenchBase has created and loaded the normal kv table, and
-- before the measured execute phase. The workload intentionally keeps
-- kv.value globally unique; the trace therefore uses kv.value as both the
-- business value and PRHIST version id, matching the Jepsen kv-predicate
-- converter.

CREATE SCHEMA IF NOT EXISTS ser_kvpredicate_trace;

CREATE TABLE IF NOT EXISTS ser_kvpredicate_trace.trace_txn (
    xid                BIGINT PRIMARY KEY,
    session_id         BIGINT NOT NULL,
    session_seq        BIGINT NOT NULL,
    txn_type           TEXT NOT NULL,
    begin_ts           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_op_ts         TIMESTAMPTZ,
    commit_observed_ts TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS ser_kvpredicate_trace.trace_abort (
    xid             BIGINT PRIMARY KEY,
    session_id      BIGINT NOT NULL,
    session_seq     BIGINT NOT NULL,
    txn_type        TEXT NOT NULL,
    status          TEXT NOT NULL,
    error_text      TEXT,
    observed_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS ser_kvpredicate_trace.row_version (
    object_key      TEXT PRIMARY KEY,
    value           BIGINT NOT NULL UNIQUE,
    semantic        BIGINT NOT NULL,
    table_name      TEXT NOT NULL,
    pk              JSONB NOT NULL,
    row_data        JSONB NOT NULL,
    observed_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS ser_kvpredicate_trace.initial_version (
    object_key      TEXT PRIMARY KEY,
    value           BIGINT NOT NULL UNIQUE,
    semantic        BIGINT NOT NULL,
    table_name      TEXT NOT NULL,
    pk              JSONB NOT NULL,
    row_data        JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS ser_kvpredicate_trace.write_version (
    write_id        BIGINT PRIMARY KEY,
    xid             BIGINT NOT NULL,
    op_index        INTEGER NOT NULL,
    object_key      TEXT NOT NULL,
    value           BIGINT NOT NULL UNIQUE,
    semantic        BIGINT NOT NULL,
    table_name      TEXT NOT NULL,
    operation       TEXT NOT NULL CHECK (operation IN ('insert', 'update')),
    before_value    BIGINT,
    old_row         JSONB,
    new_row         JSONB,
    lsn             PG_LSN,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS ser_kvpredicate_trace.trace_op (
    xid             BIGINT NOT NULL REFERENCES ser_kvpredicate_trace.trace_txn(xid) ON DELETE CASCADE,
    op_index        INTEGER NOT NULL,
    op_type         TEXT NOT NULL CHECK (op_type IN ('r', 'pr', 'w')),
    object_key      TEXT,
    value           BIGINT,
    semantic        BIGINT,
    write_id        BIGINT,
    before_value    BIGINT,
    is_absent       BOOLEAN NOT NULL DEFAULT FALSE,
    predicate       JSONB,
    results         JSONB,
    read_versions   JSONB,
    sql_text        TEXT NOT NULL,
    parameters      JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw_result      JSONB,
    old_row         JSONB,
    new_row         JSONB,
    lsn             PG_LSN,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (xid, op_index)
);

ALTER TABLE ser_kvpredicate_trace.row_version
    DROP COLUMN IF EXISTS source_write_id,
    DROP COLUMN IF EXISTS source_txn,
    DROP COLUMN IF EXISTS source_op_index;

ALTER TABLE ser_kvpredicate_trace.initial_version
    DROP COLUMN IF EXISTS source_write_id,
    DROP COLUMN IF EXISTS source_txn,
    DROP COLUMN IF EXISTS source_op_index;

ALTER TABLE ser_kvpredicate_trace.trace_op
    DROP COLUMN IF EXISTS source_write_id,
    DROP COLUMN IF EXISTS source_txn,
    DROP COLUMN IF EXISTS source_op_index;

CREATE INDEX IF NOT EXISTS kvpredicate_trace_op_xid_index
    ON ser_kvpredicate_trace.trace_op (xid, op_index);
CREATE INDEX IF NOT EXISTS kvpredicate_row_version_semantic_index
    ON ser_kvpredicate_trace.row_version (semantic);

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.key_id(p_row_key TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF p_row_key !~ '^k[0-9]+$' THEN
        RAISE EXCEPTION 'invalid kv row key: %', p_row_key;
    END IF;
    RETURN substring(p_row_key FROM 2)::BIGINT;
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.object_key(p_row_key TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    RETURN format('kv:%s', ser_kvpredicate_trace.key_id(p_row_key));
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.object_key(p_key BIGINT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    RETURN format('kv:%s', p_key);
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.semantic_value(p_row JSONB)
RETURNS BIGINT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    RETURN (p_row->>'value')::BIGINT;
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.primary_key(p_row JSONB)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    RETURN jsonb_build_object('k', p_row->>'k');
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.next_op_index()
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_current INTEGER;
BEGIN
    v_current := COALESCE(NULLIF(current_setting('ser_kvpredicate.op_index', true), ''), '-1')::INTEGER;
    PERFORM set_config('ser_kvpredicate.op_index', (v_current + 1)::TEXT, true);
    RETURN v_current + 1;
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.current_xid()
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_xid TEXT;
BEGIN
    v_xid := current_setting('ser_kvpredicate.xid', true);
    IF v_xid IS NULL OR v_xid = '' THEN
        RAISE EXCEPTION 'kvpredicate trace operation without ser_kvpredicate.xid context';
    END IF;
    RETURN v_xid::BIGINT;
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.begin_txn(
    p_session_id BIGINT,
    p_session_seq BIGINT,
    p_txn_type TEXT
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_xid BIGINT := txid_current();
BEGIN
    PERFORM set_config('ser_kvpredicate.capture', 'on', true);
    PERFORM set_config('ser_kvpredicate.xid', v_xid::TEXT, true);
    PERFORM set_config('ser_kvpredicate.op_index', '-1', true);
    INSERT INTO ser_kvpredicate_trace.trace_txn (xid, session_id, session_seq, txn_type)
    VALUES (v_xid, p_session_id, p_session_seq, p_txn_type);
    RETURN v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.capture_write()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_row JSONB := to_jsonb(NEW);
    v_old JSONB := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END;
    v_xid BIGINT;
    v_op_index INTEGER;
    v_key TEXT;
    v_value BIGINT;
    v_before_value BIGINT;
    v_kind TEXT;
BEGIN
    IF current_setting('ser_kvpredicate.capture', true) IS DISTINCT FROM 'on' THEN
        RETURN NEW;
    END IF;
    v_xid := ser_kvpredicate_trace.current_xid();
    v_op_index := ser_kvpredicate_trace.next_op_index();
    v_key := ser_kvpredicate_trace.object_key(NEW.k);
    v_value := ser_kvpredicate_trace.semantic_value(v_row);
    v_kind := lower(TG_OP);

    SELECT value INTO v_before_value
    FROM ser_kvpredicate_trace.row_version
    WHERE object_key = v_key;
    IF TG_OP <> 'INSERT' AND NOT FOUND THEN
        RAISE EXCEPTION 'write has no prior traced row version: %', v_key;
    END IF;

    INSERT INTO ser_kvpredicate_trace.write_version
        (write_id, xid, op_index, object_key, value, semantic, table_name, operation,
         before_value, old_row, new_row, lsn)
    VALUES
        (v_value, v_xid, v_op_index, v_key, v_value, v_value, TG_TABLE_NAME, v_kind,
         v_before_value, v_old, v_row, pg_current_wal_lsn());

    INSERT INTO ser_kvpredicate_trace.row_version
        (object_key, value, semantic, table_name, pk, row_data, observed_at)
    VALUES
        (v_key, v_value, v_value, TG_TABLE_NAME,
         ser_kvpredicate_trace.primary_key(v_row), v_row, clock_timestamp())
    ON CONFLICT (object_key) DO UPDATE
    SET value = EXCLUDED.value,
        semantic = EXCLUDED.semantic,
        table_name = EXCLUDED.table_name,
        pk = EXCLUDED.pk,
        row_data = EXCLUDED.row_data,
        observed_at = EXCLUDED.observed_at;

    INSERT INTO ser_kvpredicate_trace.trace_op
        (xid, op_index, op_type, object_key, value, semantic, write_id, before_value,
         sql_text, old_row, new_row, lsn)
    VALUES
        (v_xid, v_op_index, 'w', v_key, v_value, v_value, v_value, v_before_value,
         format('trigger:%s %s', TG_OP, TG_TABLE_NAME), v_old, v_row, pg_current_wal_lsn());
    UPDATE ser_kvpredicate_trace.trace_txn SET last_op_ts = clock_timestamp() WHERE xid = v_xid;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.capture_point_read(
    p_key BIGINT,
    p_sql_text TEXT,
    p_parameters JSONB,
    p_returned JSONB
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_row ser_kvpredicate_trace.row_version%ROWTYPE;
    v_xid BIGINT := ser_kvpredicate_trace.current_xid();
    v_op_index INTEGER := ser_kvpredicate_trace.next_op_index();
BEGIN
    SELECT * INTO v_row
    FROM ser_kvpredicate_trace.row_version
    WHERE object_key = ser_kvpredicate_trace.object_key(p_key);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'read returned a row but trace has no version for key %', p_key;
    END IF;
    INSERT INTO ser_kvpredicate_trace.trace_op
        (xid, op_index, op_type, object_key, value, semantic,
         sql_text, parameters, raw_result)
    VALUES
        (v_xid, v_op_index, 'r', v_row.object_key, v_row.value, v_row.semantic,
         p_sql_text, COALESCE(p_parameters, '[]'::jsonb), p_returned);
    UPDATE ser_kvpredicate_trace.trace_txn SET last_op_ts = clock_timestamp() WHERE xid = v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.capture_missing_point_read(
    p_key BIGINT,
    p_sql_text TEXT,
    p_parameters JSONB
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_xid BIGINT := ser_kvpredicate_trace.current_xid();
    v_op_index INTEGER := ser_kvpredicate_trace.next_op_index();
    v_absent_value BIGINT := -1000000000000 - p_key;
BEGIN
    INSERT INTO ser_kvpredicate_trace.trace_op
        (xid, op_index, op_type, object_key, value, semantic,
         is_absent, sql_text, parameters, raw_result)
    VALUES
        (v_xid, v_op_index, 'r', ser_kvpredicate_trace.object_key(p_key), v_absent_value,
         -1, true, p_sql_text, COALESCE(p_parameters, '[]'::jsonb),
         '{"found": false}'::jsonb);
    UPDATE ser_kvpredicate_trace.trace_txn SET last_op_ts = clock_timestamp() WHERE xid = v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.capture_predicate_read(
    p_kind TEXT,
    p_modulus BIGINT,
    p_target BIGINT,
    p_sql_text TEXT,
    p_parameters JSONB,
    p_returned JSONB
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_xid BIGINT := ser_kvpredicate_trace.current_xid();
    v_op_index INTEGER := ser_kvpredicate_trace.next_op_index();
    v_predicate JSONB;
    v_results JSONB;
BEGIN
    IF p_kind = 'true' THEN
        v_predicate := jsonb_build_object('kind', 'true');
    ELSIF p_kind = 'eq' THEN
        v_predicate := jsonb_build_object('kind', 'eq', 'value', p_target);
    ELSIF p_kind = 'mod' THEN
        v_predicate := jsonb_build_object('kind', 'mod', 'modulus', p_modulus, 'target', p_target);
    ELSIF p_kind = 'gt' THEN
        v_predicate := jsonb_build_object('kind', 'gt', 'value', p_target);
    ELSIF p_kind = 'lt' THEN
        v_predicate := jsonb_build_object('kind', 'lt', 'value', p_target);
    ELSE
        RAISE EXCEPTION 'unknown kvpredicate predicate kind: %', p_kind;
    END IF;

    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'key', object_key,
        'key_id', ser_kvpredicate_trace.key_id(row_data->>'k'),
        'value', value,
        'semantic', semantic,
        'table', table_name,
        'pk', pk,
        'row', row_data
    ) ORDER BY ser_kvpredicate_trace.key_id(row_data->>'k')), '[]'::jsonb)
    INTO v_results
    FROM ser_kvpredicate_trace.row_version
    WHERE p_kind = 'true'
       OR (p_kind = 'eq' AND semantic = p_target)
       OR (p_kind = 'mod' AND MOD(semantic, p_modulus) = p_target)
       OR (p_kind = 'gt' AND semantic > p_target)
       OR (p_kind = 'lt' AND semantic < p_target);

    INSERT INTO ser_kvpredicate_trace.trace_op
        (xid, op_index, op_type, predicate, results, read_versions, sql_text,
         parameters, raw_result)
    VALUES
        (v_xid, v_op_index, 'pr', v_predicate, v_results, v_results,
         p_sql_text, COALESCE(p_parameters, '[]'::jsonb),
         jsonb_build_object('returned_rows', COALESCE(p_returned, '[]'::jsonb)));
    UPDATE ser_kvpredicate_trace.trace_txn SET last_op_ts = clock_timestamp() WHERE xid = v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_kvpredicate_trace.snapshot_initial_state()
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM ser_kvpredicate_trace.trace_op;
    DELETE FROM ser_kvpredicate_trace.trace_txn;
    DELETE FROM ser_kvpredicate_trace.trace_abort;
    DELETE FROM ser_kvpredicate_trace.write_version;
    DELETE FROM ser_kvpredicate_trace.initial_version;
    DELETE FROM ser_kvpredicate_trace.row_version;

    WITH kv_rows AS (
        SELECT to_jsonb(t) AS row_data FROM public.kv AS t
    )
    INSERT INTO ser_kvpredicate_trace.row_version
        (object_key, value, semantic, table_name, pk, row_data)
    SELECT ser_kvpredicate_trace.object_key(row_data->>'k'),
           ser_kvpredicate_trace.semantic_value(row_data),
           ser_kvpredicate_trace.semantic_value(row_data),
           'kv',
           ser_kvpredicate_trace.primary_key(row_data), row_data
    FROM kv_rows;

    INSERT INTO ser_kvpredicate_trace.write_version
        (write_id, xid, op_index, object_key, value, semantic, table_name, operation, new_row)
    SELECT value, -1, 0, object_key, value, semantic, table_name, 'insert', row_data
    FROM ser_kvpredicate_trace.row_version;

    INSERT INTO ser_kvpredicate_trace.initial_version
        (object_key, value, semantic, table_name, pk, row_data)
    SELECT object_key, value, semantic, table_name, pk, row_data
    FROM ser_kvpredicate_trace.row_version;
END;
$$;

DROP TRIGGER IF EXISTS ser_kvpredicate_trace_write ON public.kv;
CREATE TRIGGER ser_kvpredicate_trace_write
AFTER INSERT OR UPDATE ON public.kv
FOR EACH ROW EXECUTE FUNCTION ser_kvpredicate_trace.capture_write();

-- Deliberately separate from installation: run only after BenchBase load is
-- complete.
-- SELECT ser_kvpredicate_trace.snapshot_initial_state();
