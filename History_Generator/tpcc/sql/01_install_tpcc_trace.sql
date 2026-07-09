-- PostgreSQL-side evidence store for the BenchBase TPC-C trace mode.
--
-- Run this after BenchBase has created and loaded the normal TPC-C tables,
-- and before the measured execute phase.  It neither replaces nor reshapes
-- the TPC-C business tables.  The one added history column is a surrogate
-- key because BenchBase's PostgreSQL history table has no primary key.

CREATE SCHEMA IF NOT EXISTS ser_tpcc_trace;

CREATE SEQUENCE IF NOT EXISTS ser_tpcc_trace.version_seq AS BIGINT;

CREATE TABLE IF NOT EXISTS ser_tpcc_trace.trace_txn (
    xid             BIGINT PRIMARY KEY,
    session_id      BIGINT NOT NULL,
    session_seq     BIGINT NOT NULL,
    txn_type        TEXT NOT NULL,
    begin_ts        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_op_ts      TIMESTAMPTZ
);

ALTER TABLE ser_tpcc_trace.trace_txn
    ADD COLUMN IF NOT EXISTS commit_observed_ts TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS ser_tpcc_trace.trace_abort (
    xid             BIGINT PRIMARY KEY,
    session_id      BIGINT NOT NULL,
    session_seq     BIGINT NOT NULL,
    txn_type        TEXT NOT NULL,
    status          TEXT NOT NULL,
    error_text      TEXT,
    observed_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS ser_tpcc_trace.row_version (
    object_key      TEXT PRIMARY KEY,
    value           BIGINT NOT NULL UNIQUE,
    semantic        INTEGER NOT NULL,
    source_write_id BIGINT NOT NULL UNIQUE,
    source_txn      BIGINT NOT NULL,
    source_op_index INTEGER NOT NULL,
    table_name      TEXT NOT NULL,
    pk              JSONB NOT NULL,
    row_data        JSONB NOT NULL,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    observed_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Keep initial versions independently from row_version: the latter always
-- represents the latest visible version, while PRHIST needs every initial
-- source even after the row has been updated by a measured transaction.
CREATE TABLE IF NOT EXISTS ser_tpcc_trace.initial_version (
    object_key      TEXT PRIMARY KEY,
    value           BIGINT NOT NULL UNIQUE,
    semantic        INTEGER NOT NULL,
    source_write_id BIGINT NOT NULL UNIQUE,
    source_txn      BIGINT NOT NULL DEFAULT -1,
    source_op_index INTEGER NOT NULL DEFAULT 0,
    table_name      TEXT NOT NULL,
    pk              JSONB NOT NULL,
    row_data        JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS ser_tpcc_trace.write_version (
    write_id        BIGINT PRIMARY KEY,
    xid             BIGINT NOT NULL,
    op_index        INTEGER NOT NULL,
    object_key      TEXT NOT NULL,
    value           BIGINT NOT NULL UNIQUE,
    semantic        INTEGER NOT NULL,
    table_name      TEXT NOT NULL,
    operation       TEXT NOT NULL CHECK (operation IN ('insert', 'update', 'delete')),
    before_value    BIGINT,
    old_row         JSONB,
    new_row         JSONB,
    lsn             PG_LSN,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS ser_tpcc_trace.trace_op (
    xid             BIGINT NOT NULL REFERENCES ser_tpcc_trace.trace_txn(xid) ON DELETE CASCADE,
    op_index        INTEGER NOT NULL,
    op_type         TEXT NOT NULL CHECK (op_type IN ('r', 'pr', 'w')),
    object_key      TEXT,
    value           BIGINT,
    semantic        INTEGER,
    write_id        BIGINT,
    before_value    BIGINT,
    source_write_id BIGINT,
    source_txn      BIGINT,
    source_op_index INTEGER,
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

CREATE INDEX IF NOT EXISTS trace_op_xid_index ON ser_tpcc_trace.trace_op (xid, op_index);
CREATE INDEX IF NOT EXISTS row_version_table_index ON ser_tpcc_trace.row_version (table_name, object_key);

ALTER TABLE ser_tpcc_trace.write_version ADD COLUMN IF NOT EXISTS before_value BIGINT;
ALTER TABLE ser_tpcc_trace.trace_op ADD COLUMN IF NOT EXISTS before_value BIGINT;
ALTER TABLE ser_tpcc_trace.trace_op ADD COLUMN IF NOT EXISTS read_versions JSONB;

ALTER TABLE public.history
    ADD COLUMN IF NOT EXISTS ser_tpcc_history_id BIGSERIAL;
CREATE UNIQUE INDEX IF NOT EXISTS history_ser_tpcc_history_id_key
    ON public.history (ser_tpcc_history_id);

CREATE OR REPLACE FUNCTION ser_tpcc_trace.object_key(p_table TEXT, p_row JSONB)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    CASE p_table
        WHEN 'warehouse' THEN RETURN format('warehouse:w=%s', p_row->>'w_id');
        WHEN 'district' THEN RETURN format('district:w=%s:d=%s', p_row->>'d_w_id', p_row->>'d_id');
        WHEN 'customer' THEN RETURN format('customer:w=%s:d=%s:c=%s', p_row->>'c_w_id', p_row->>'c_d_id', p_row->>'c_id');
        WHEN 'item' THEN RETURN format('item:i=%s', p_row->>'i_id');
        WHEN 'stock' THEN RETURN format('stock:w=%s:i=%s', p_row->>'s_w_id', p_row->>'s_i_id');
        WHEN 'oorder' THEN RETURN format('oorder:w=%s:d=%s:o=%s', p_row->>'o_w_id', p_row->>'o_d_id', p_row->>'o_id');
        WHEN 'new_order' THEN RETURN format('new_order:w=%s:d=%s:o=%s', p_row->>'no_w_id', p_row->>'no_d_id', p_row->>'no_o_id');
        WHEN 'order_line' THEN RETURN format('order_line:w=%s:d=%s:o=%s:n=%s', p_row->>'ol_w_id', p_row->>'ol_d_id', p_row->>'ol_o_id', p_row->>'ol_number');
        WHEN 'history' THEN RETURN format('history:id=%s', p_row->>'ser_tpcc_history_id');
        ELSE RAISE EXCEPTION 'unsupported TPC-C table for trace: %', p_table;
    END CASE;
END;
$$;

CREATE OR REPLACE FUNCTION ser_tpcc_trace.semantic_value(p_table TEXT, p_row JSONB)
RETURNS INTEGER
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    -- Only stock uses semantic as a predicate-domain value.  It is the stable
    -- S_I_ID so an order_filter allowed_semantics list can exactly express a
    -- StockLevel JOIN membership without adding a new PRHIST predicate kind.
    CASE p_table
        WHEN 'stock' THEN RETURN (p_row->>'s_i_id')::INTEGER;
        WHEN 'warehouse' THEN RETURN (p_row->>'w_id')::INTEGER;
        WHEN 'district' THEN RETURN (p_row->>'d_next_o_id')::INTEGER;
        WHEN 'customer' THEN RETURN (p_row->>'c_id')::INTEGER;
        WHEN 'item' THEN RETURN (p_row->>'i_id')::INTEGER;
        WHEN 'oorder' THEN RETURN (p_row->>'o_id')::INTEGER;
        WHEN 'new_order' THEN RETURN (p_row->>'no_o_id')::INTEGER;
        WHEN 'order_line' THEN RETURN (p_row->>'ol_i_id')::INTEGER;
        WHEN 'history' THEN RETURN (p_row->>'h_c_id')::INTEGER;
        ELSE RAISE EXCEPTION 'unsupported TPC-C table for semantic: %', p_table;
    END CASE;
END;
$$;

CREATE OR REPLACE FUNCTION ser_tpcc_trace.primary_key(p_table TEXT, p_row JSONB)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    CASE p_table
        WHEN 'warehouse' THEN RETURN jsonb_build_object('w_id', p_row->>'w_id');
        WHEN 'district' THEN RETURN jsonb_build_object('d_w_id', p_row->>'d_w_id', 'd_id', p_row->>'d_id');
        WHEN 'customer' THEN RETURN jsonb_build_object('c_w_id', p_row->>'c_w_id', 'c_d_id', p_row->>'c_d_id', 'c_id', p_row->>'c_id');
        WHEN 'item' THEN RETURN jsonb_build_object('i_id', p_row->>'i_id');
        WHEN 'stock' THEN RETURN jsonb_build_object('s_w_id', p_row->>'s_w_id', 's_i_id', p_row->>'s_i_id');
        WHEN 'oorder' THEN RETURN jsonb_build_object('o_w_id', p_row->>'o_w_id', 'o_d_id', p_row->>'o_d_id', 'o_id', p_row->>'o_id');
        WHEN 'new_order' THEN RETURN jsonb_build_object('no_w_id', p_row->>'no_w_id', 'no_d_id', p_row->>'no_d_id', 'no_o_id', p_row->>'no_o_id');
        WHEN 'order_line' THEN RETURN jsonb_build_object('ol_w_id', p_row->>'ol_w_id', 'ol_d_id', p_row->>'ol_d_id', 'ol_o_id', p_row->>'ol_o_id', 'ol_number', p_row->>'ol_number');
        WHEN 'history' THEN RETURN jsonb_build_object('ser_tpcc_history_id', p_row->>'ser_tpcc_history_id');
        ELSE RAISE EXCEPTION 'unsupported TPC-C table for primary key: %', p_table;
    END CASE;
END;
$$;

CREATE OR REPLACE FUNCTION ser_tpcc_trace.next_op_index()
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_current INTEGER;
BEGIN
    v_current := COALESCE(NULLIF(current_setting('ser_tpcc.op_index', true), ''), '-1')::INTEGER;
    PERFORM set_config('ser_tpcc.op_index', (v_current + 1)::TEXT, true);
    RETURN v_current + 1;
END;
$$;

CREATE OR REPLACE FUNCTION ser_tpcc_trace.current_xid()
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_xid TEXT;
BEGIN
    v_xid := current_setting('ser_tpcc.xid', true);
    IF v_xid IS NULL OR v_xid = '' THEN
        RAISE EXCEPTION 'TPC-C trace operation without ser_tpcc.xid context';
    END IF;
    RETURN v_xid::BIGINT;
END;
$$;

CREATE OR REPLACE FUNCTION ser_tpcc_trace.begin_txn(
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
    PERFORM set_config('ser_tpcc.capture', 'on', true);
    PERFORM set_config('ser_tpcc.xid', v_xid::TEXT, true);
    PERFORM set_config('ser_tpcc.op_index', '-1', true);
    INSERT INTO ser_tpcc_trace.trace_txn (xid, session_id, session_seq, txn_type)
    VALUES (v_xid, p_session_id, p_session_seq, p_txn_type);
    RETURN v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_tpcc_trace.capture_write()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_row JSONB;
    v_old JSONB;
    v_table TEXT := TG_TABLE_NAME;
    v_xid BIGINT;
    v_op_index INTEGER;
    v_key TEXT;
    v_value BIGINT;
    v_semantic INTEGER;
    v_kind TEXT;
    v_before_value BIGINT;
BEGIN
    IF current_setting('ser_tpcc.capture', true) IS DISTINCT FROM 'on' THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    v_xid := ser_tpcc_trace.current_xid();
    v_op_index := ser_tpcc_trace.next_op_index();
    v_old := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END;
    v_row := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    v_key := ser_tpcc_trace.object_key(v_table, v_row);
    IF TG_OP <> 'INSERT' THEN
        SELECT value INTO v_before_value
        FROM ser_tpcc_trace.row_version
        WHERE object_key = v_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'write has no prior traced row version: %', v_key;
        END IF;
    END IF;
    v_semantic := ser_tpcc_trace.semantic_value(v_table, v_row);
    v_value := nextval('ser_tpcc_trace.version_seq');
    v_kind := lower(TG_OP);

    INSERT INTO ser_tpcc_trace.write_version
        (write_id, xid, op_index, object_key, value, semantic, table_name, operation, before_value, old_row, new_row, lsn)
    VALUES
        (v_value, v_xid, v_op_index, v_key, v_value, v_semantic, v_table, v_kind, v_before_value, v_old,
         CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE v_row END, pg_current_wal_lsn());

    IF TG_OP = 'DELETE' THEN
        DELETE FROM ser_tpcc_trace.row_version WHERE object_key = v_key;
    ELSE
        INSERT INTO ser_tpcc_trace.row_version
            (object_key, value, semantic, source_write_id, source_txn, source_op_index, table_name, pk, row_data, observed_at)
        VALUES
            (v_key, v_value, v_semantic, v_value, v_xid, v_op_index, v_table,
             ser_tpcc_trace.primary_key(v_table, v_row), v_row, clock_timestamp())
        ON CONFLICT (object_key) DO UPDATE
        SET value = EXCLUDED.value,
            semantic = EXCLUDED.semantic,
            source_write_id = EXCLUDED.source_write_id,
            source_txn = EXCLUDED.source_txn,
            source_op_index = EXCLUDED.source_op_index,
            table_name = EXCLUDED.table_name,
            pk = EXCLUDED.pk,
            row_data = EXCLUDED.row_data,
            is_deleted = FALSE,
            observed_at = EXCLUDED.observed_at;
    END IF;

    INSERT INTO ser_tpcc_trace.trace_op
        (xid, op_index, op_type, object_key, value, semantic, write_id, before_value, sql_text, old_row, new_row, lsn)
    VALUES
        (v_xid, v_op_index, 'w', v_key, v_value, v_semantic, v_value, v_before_value,
         format('trigger:%s %s', TG_OP, v_table), v_old,
         CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE v_row END, pg_current_wal_lsn());
    UPDATE ser_tpcc_trace.trace_txn SET last_op_ts = clock_timestamp() WHERE xid = v_xid;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE OR REPLACE FUNCTION ser_tpcc_trace.capture_point_read(
    p_object_key TEXT,
    p_sql_text TEXT,
    p_parameters JSONB,
    p_returned JSONB
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_row ser_tpcc_trace.row_version%ROWTYPE;
    v_xid BIGINT := ser_tpcc_trace.current_xid();
    v_op_index INTEGER := ser_tpcc_trace.next_op_index();
BEGIN
    SELECT * INTO v_row FROM ser_tpcc_trace.row_version WHERE object_key = p_object_key;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'read has no current traced row version: %', p_object_key;
    END IF;
    INSERT INTO ser_tpcc_trace.trace_op
        (xid, op_index, op_type, object_key, value, semantic, source_write_id, source_txn, source_op_index,
         sql_text, parameters, raw_result)
    VALUES
        (v_xid, v_op_index, 'r', v_row.object_key, v_row.value, v_row.semantic,
         v_row.source_write_id, v_row.source_txn, v_row.source_op_index,
         p_sql_text, COALESCE(p_parameters, '[]'::jsonb), p_returned);
    UPDATE ser_tpcc_trace.trace_txn SET last_op_ts = clock_timestamp() WHERE xid = v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_tpcc_trace.capture_stock_level(
    p_warehouse_id INTEGER,
    p_district_id INTEGER,
    p_next_order_id INTEGER,
    p_sql_threshold INTEGER,
    p_stock_item_ids INTEGER[],
    p_stock_count INTEGER,
    p_sql_text TEXT,
    p_parameters JSONB,
    p_raw_result JSONB
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_xid BIGINT := ser_tpcc_trace.current_xid();
    v_op_index INTEGER := ser_tpcc_trace.next_op_index();
    v_prefix TEXT := format('stock:w=%s:i=', p_warehouse_id);
    v_predicate JSONB;
    v_results JSONB;
    v_read_versions JSONB;
BEGIN
    -- Preserve the real SQL parameters.  The detector must implement this
    -- relational predicate; it must not treat the observed item IDs as a
    -- fixed single-table whitelist.
    v_predicate := jsonb_build_object(
        'kind', 'tpcc_stock_level',
        'warehouse_id', p_warehouse_id,
        'district_id', p_district_id,
        'order_id_from', p_next_order_id - 20,
        'order_id_to_exclusive', p_next_order_id,
        'stock_quantity_lt', p_sql_threshold
    );
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'key', object_key,
        'value', value,
        'semantic', semantic,
        'source_write_id', source_write_id,
        'source_txn', source_txn,
        'source_op_index', source_op_index
    ) ORDER BY semantic), '[]'::jsonb)
    INTO v_results
    FROM ser_tpcc_trace.row_version
    WHERE object_key LIKE v_prefix || '%'
      AND semantic = ANY(COALESCE(p_stock_item_ids, ARRAY[]::INTEGER[]));

    -- The result rows identify returned items.  The separate version read-set
    -- identifies every stock/order_line version that participated in the
    -- relational predicate at this snapshot, so the detector can construct
    -- PR-WR edges without interpreting presentation-oriented result data.
    WITH join_rows AS (
        SELECT value AS evidence
        FROM jsonb_array_elements(
            CASE WHEN jsonb_typeof(p_raw_result->'join_rows') = 'array'
                 THEN p_raw_result->'join_rows' ELSE '[]'::jsonb END
        )
    ), referenced_keys AS (
        SELECT format('stock:w=%s:i=%s', p_warehouse_id, evidence->>'s_i_id') AS object_key
        FROM join_rows
        UNION
        SELECT format('order_line:w=%s:d=%s:o=%s:n=%s',
                      evidence->>'ol_w_id', evidence->>'ol_d_id',
                      evidence->>'ol_o_id', evidence->>'ol_number')
        FROM join_rows
    )
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'key', rv.object_key,
        'value', rv.value,
        'semantic', rv.semantic,
        'source_write_id', rv.source_write_id,
        'source_txn', rv.source_txn,
        'source_op_index', rv.source_op_index,
        'table', rv.table_name,
        'pk', rv.pk
    ) ORDER BY rv.table_name, rv.object_key), '[]'::jsonb)
    INTO v_read_versions
    FROM referenced_keys AS rk
    JOIN ser_tpcc_trace.row_version AS rv ON rv.object_key = rk.object_key;

    INSERT INTO ser_tpcc_trace.trace_op
        (xid, op_index, op_type, predicate, results, read_versions, sql_text, parameters, raw_result)
    VALUES
        (v_xid, v_op_index, 'pr', v_predicate, v_results, v_read_versions, p_sql_text,
         COALESCE(p_parameters, '[]'::jsonb),
         jsonb_build_object('aggregate_stock_count', p_stock_count, 'join_evidence', p_raw_result));
    UPDATE ser_tpcc_trace.trace_txn SET last_op_ts = clock_timestamp() WHERE xid = v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_tpcc_trace.snapshot_initial_state()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_table TEXT;
BEGIN
    -- A snapshot is only valid before the trace workload starts.
    DELETE FROM ser_tpcc_trace.trace_op;
    DELETE FROM ser_tpcc_trace.trace_txn;
    DELETE FROM ser_tpcc_trace.trace_abort;
    DELETE FROM ser_tpcc_trace.write_version;
    DELETE FROM ser_tpcc_trace.initial_version;
    DELETE FROM ser_tpcc_trace.row_version;
    ALTER SEQUENCE ser_tpcc_trace.version_seq RESTART WITH 1;

    FOREACH v_table IN ARRAY ARRAY['warehouse', 'district', 'customer', 'item', 'stock', 'oorder', 'new_order', 'order_line', 'history']
    LOOP
        EXECUTE format($fmt$
            WITH source_rows AS (
                SELECT to_jsonb(t) AS row_data FROM public.%I AS t
            ), versions AS (
                SELECT row_data, nextval('ser_tpcc_trace.version_seq') AS version_id FROM source_rows
            )
            INSERT INTO ser_tpcc_trace.row_version
                (object_key, value, semantic, source_write_id, source_txn, source_op_index, table_name, pk, row_data)
            SELECT ser_tpcc_trace.object_key(%L, row_data), version_id,
                   ser_tpcc_trace.semantic_value(%L, row_data), version_id, -1, 0, %L,
                   ser_tpcc_trace.primary_key(%L, row_data), row_data
            FROM versions
        $fmt$, v_table, v_table, v_table, v_table, v_table);
    END LOOP;

    INSERT INTO ser_tpcc_trace.write_version
        (write_id, xid, op_index, object_key, value, semantic, table_name, operation, new_row)
    SELECT source_write_id, -1, 0, object_key, value, semantic, table_name, 'insert', row_data
    FROM ser_tpcc_trace.row_version;

    INSERT INTO ser_tpcc_trace.initial_version
        (object_key, value, semantic, source_write_id, source_txn, source_op_index, table_name, pk, row_data)
    SELECT object_key, value, semantic, source_write_id, source_txn, source_op_index, table_name, pk, row_data
    FROM ser_tpcc_trace.row_version;
END;
$$;

DO $$
DECLARE
    v_table TEXT;
BEGIN
    FOREACH v_table IN ARRAY ARRAY['warehouse', 'district', 'customer', 'item', 'stock', 'oorder', 'new_order', 'order_line', 'history']
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS ser_tpcc_trace_write ON public.%I', v_table);
        EXECUTE format(
            'CREATE TRIGGER ser_tpcc_trace_write AFTER INSERT OR UPDATE OR DELETE ON public.%I '
            || 'FOR EACH ROW EXECUTE FUNCTION ser_tpcc_trace.capture_write()',
            v_table
        );
    END LOOP;
END;
$$;

-- Deliberately separate from installation: run only after BenchBase load is
-- complete.  It is idempotent for a fresh trace run and creates every initial
-- version required by the later PRHIST converter.
-- SELECT ser_tpcc_trace.snapshot_initial_state();
