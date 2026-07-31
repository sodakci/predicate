-- PostgreSQL-side evidence store for the BenchBase multikv workload.
--
-- Install this after BenchBase has created and loaded public.users,
-- public.items, and public.orders. Call snapshot_initial_state() immediately
-- before the measured execute phase.

CREATE SCHEMA IF NOT EXISTS ser_multikv_trace;

CREATE TABLE IF NOT EXISTS ser_multikv_trace.trace_txn (
    xid                BIGINT PRIMARY KEY,
    session_id         BIGINT NOT NULL,
    session_seq        BIGINT NOT NULL,
    txn_type           TEXT NOT NULL,
    isolation_level    TEXT NOT NULL,
    begin_ts           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_op_ts         TIMESTAMPTZ,
    commit_observed_ts TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS ser_multikv_trace.trace_abort (
    xid             BIGINT PRIMARY KEY,
    session_id      BIGINT NOT NULL,
    session_seq     BIGINT NOT NULL,
    txn_type        TEXT NOT NULL,
    status          TEXT NOT NULL,
    error_text      TEXT,
    observed_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS ser_multikv_trace.row_version (
    object_key      TEXT PRIMARY KEY,
    value           JSONB NOT NULL,
    table_name      TEXT NOT NULL CHECK (table_name IN ('users', 'items', 'orders')),
    local_key       TEXT NOT NULL,
    row_data        JSONB NOT NULL,
    observed_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (table_name, local_key)
);

CREATE TABLE IF NOT EXISTS ser_multikv_trace.initial_version (
    object_key      TEXT PRIMARY KEY,
    value           JSONB NOT NULL,
    table_name      TEXT NOT NULL CHECK (table_name IN ('users', 'items', 'orders')),
    local_key       TEXT NOT NULL,
    row_data        JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS ser_multikv_trace.version_registry (
    object_key      TEXT NOT NULL,
    value           JSONB NOT NULL,
    source_kind     TEXT NOT NULL CHECK (source_kind IN ('initial', 'write')),
    source_xid      BIGINT,
    source_op_index INTEGER,
    PRIMARY KEY (object_key, value),
    CHECK (
        (source_kind = 'initial' AND source_xid IS NULL AND source_op_index IS NULL)
        OR
        (source_kind = 'write' AND source_xid IS NOT NULL AND source_op_index IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS ser_multikv_trace.write_version (
    xid             BIGINT NOT NULL,
    op_index        INTEGER NOT NULL,
    object_key      TEXT NOT NULL,
    value           JSONB NOT NULL,
    before_value    JSONB,
    table_name      TEXT NOT NULL CHECK (table_name IN ('users', 'items', 'orders')),
    local_key       TEXT NOT NULL,
    operation       TEXT NOT NULL CHECK (operation IN ('insert', 'update')),
    old_row         JSONB,
    new_row         JSONB NOT NULL,
    lsn             PG_LSN,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (xid, op_index)
);

CREATE TABLE IF NOT EXISTS ser_multikv_trace.trace_op (
    xid             BIGINT NOT NULL
                    REFERENCES ser_multikv_trace.trace_txn(xid) ON DELETE CASCADE,
    op_index        INTEGER NOT NULL,
    op_type         TEXT NOT NULL CHECK (op_type IN ('r', 'pr', 'w')),
    object_key      TEXT,
    value           JSONB,
    before_value    JSONB,
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

CREATE INDEX IF NOT EXISTS multikv_trace_op_xid_index
    ON ser_multikv_trace.trace_op (xid, op_index);

CREATE OR REPLACE FUNCTION ser_multikv_trace.object_key(
    p_table_name TEXT,
    p_local_key TEXT
)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_table_name TEXT := lower(p_table_name);
BEGIN
    IF v_table_name NOT IN ('users', 'items', 'orders') THEN
        RAISE EXCEPTION 'unknown multikv table: %', p_table_name;
    END IF;
    IF p_local_key IS NULL OR p_local_key = '' OR position(':' IN p_local_key) > 0 THEN
        RAISE EXCEPTION 'invalid multikv local key: %', p_local_key;
    END IF;
    RETURN format('%s:%s', v_table_name, p_local_key);
END;
$$;

CREATE OR REPLACE FUNCTION ser_multikv_trace.validate_value(
    p_table_name TEXT,
    p_local_key TEXT,
    p_value JSONB
)
RETURNS VOID
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_table_name TEXT := lower(p_table_name);
    v_field_count INTEGER;
BEGIN
    PERFORM ser_multikv_trace.object_key(v_table_name, p_local_key);
    IF jsonb_typeof(p_value) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION '%:% value must be a JSON object', v_table_name, p_local_key;
    END IF;
    SELECT count(*) INTO v_field_count FROM jsonb_object_keys(p_value);

    IF v_table_name = 'users' THEN
        IF v_field_count <> 3
           OR jsonb_typeof(p_value->'user_key') IS DISTINCT FROM 'string'
           OR p_value->>'user_key' IS DISTINCT FROM p_local_key
           OR jsonb_typeof(p_value->'region') IS DISTINCT FROM 'string'
           OR jsonb_typeof(p_value->'active') IS DISTINCT FROM 'boolean' THEN
            RAISE EXCEPTION 'invalid users:% value: %', p_local_key, p_value;
        END IF;
    ELSIF v_table_name = 'items' THEN
        IF v_field_count <> 3
           OR jsonb_typeof(p_value->'item_key') IS DISTINCT FROM 'string'
           OR p_value->>'item_key' IS DISTINCT FROM p_local_key
           OR jsonb_typeof(p_value->'stock') IS DISTINCT FROM 'number'
           OR jsonb_typeof(p_value->'price') IS DISTINCT FROM 'number'
           OR (p_value->>'stock') !~ '^-?[0-9]+$'
           OR (p_value->>'price') !~ '^-?[0-9]+$' THEN
            RAISE EXCEPTION 'invalid items:% value: %', p_local_key, p_value;
        END IF;
    ELSIF v_table_name = 'orders' THEN
        IF v_field_count <> 4
           OR jsonb_typeof(p_value->'order_key') IS DISTINCT FROM 'string'
           OR p_value->>'order_key' IS DISTINCT FROM p_local_key
           OR jsonb_typeof(p_value->'user_key') IS DISTINCT FROM 'string'
           OR jsonb_typeof(p_value->'item_key') IS DISTINCT FROM 'string'
           OR jsonb_typeof(p_value->'status') IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'invalid orders:% value: %', p_local_key, p_value;
        END IF;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION ser_multikv_trace.next_op_index()
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_current INTEGER;
BEGIN
    v_current := COALESCE(
        NULLIF(current_setting('ser_multikv.op_index', true), ''),
        '-1'
    )::INTEGER;
    PERFORM set_config('ser_multikv.op_index', (v_current + 1)::TEXT, true);
    RETURN v_current + 1;
END;
$$;

CREATE OR REPLACE FUNCTION ser_multikv_trace.current_xid()
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_xid TEXT;
BEGIN
    v_xid := current_setting('ser_multikv.xid', true);
    IF v_xid IS NULL OR v_xid = '' THEN
        RAISE EXCEPTION 'multikv trace operation without ser_multikv.xid context';
    END IF;
    RETURN v_xid::BIGINT;
END;
$$;

CREATE OR REPLACE FUNCTION ser_multikv_trace.begin_txn(
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
    PERFORM set_config('ser_multikv.capture', 'on', true);
    PERFORM set_config('ser_multikv.xid', v_xid::TEXT, true);
    PERFORM set_config('ser_multikv.op_index', '-1', true);
    INSERT INTO ser_multikv_trace.trace_txn
        (xid, session_id, session_seq, txn_type, isolation_level)
    VALUES
        (v_xid, p_session_id, p_session_seq, p_txn_type,
         current_setting('transaction_isolation'));
    RETURN v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_multikv_trace.capture_write()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_new_row JSONB := to_jsonb(NEW);
    v_old_row JSONB := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END;
    v_xid BIGINT;
    v_op_index INTEGER;
    v_object_key TEXT;
    v_value JSONB := v_new_row->'value';
    v_before_value JSONB;
BEGIN
    IF current_setting('ser_multikv.capture', true) IS DISTINCT FROM 'on' THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.k IS DISTINCT FROM OLD.k THEN
        RAISE EXCEPTION 'multikv primary key updates are not traceable';
    END IF;

    PERFORM ser_multikv_trace.validate_value(TG_TABLE_NAME, NEW.k, v_value);
    v_xid := ser_multikv_trace.current_xid();
    v_op_index := ser_multikv_trace.next_op_index();
    v_object_key := ser_multikv_trace.object_key(TG_TABLE_NAME, NEW.k);

    SELECT value INTO v_before_value
    FROM ser_multikv_trace.row_version
    WHERE object_key = v_object_key;

    IF TG_OP = 'UPDATE' THEN
        IF NOT FOUND OR v_before_value IS DISTINCT FROM v_old_row->'value' THEN
            RAISE EXCEPTION 'stale traced row version before update: %', v_object_key;
        END IF;
    ELSIF FOUND THEN
        RAISE EXCEPTION 'insert already has a traced row version: %', v_object_key;
    END IF;

    INSERT INTO ser_multikv_trace.version_registry
        (object_key, value, source_kind, source_xid, source_op_index)
    VALUES
        (v_object_key, v_value, 'write', v_xid, v_op_index);

    INSERT INTO ser_multikv_trace.write_version
        (xid, op_index, object_key, value, before_value, table_name, local_key,
         operation, old_row, new_row, lsn)
    VALUES
        (v_xid, v_op_index, v_object_key, v_value, v_before_value,
         TG_TABLE_NAME, NEW.k, lower(TG_OP), v_old_row, v_new_row,
         pg_current_wal_lsn());

    INSERT INTO ser_multikv_trace.row_version
        (object_key, value, table_name, local_key, row_data, observed_at)
    VALUES
        (v_object_key, v_value, TG_TABLE_NAME, NEW.k, v_new_row, clock_timestamp())
    ON CONFLICT (object_key) DO UPDATE
    SET value = EXCLUDED.value,
        table_name = EXCLUDED.table_name,
        local_key = EXCLUDED.local_key,
        row_data = EXCLUDED.row_data,
        observed_at = EXCLUDED.observed_at;

    INSERT INTO ser_multikv_trace.trace_op
        (xid, op_index, op_type, object_key, value, before_value,
         sql_text, old_row, new_row, lsn)
    VALUES
        (v_xid, v_op_index, 'w', v_object_key, v_value, v_before_value,
         format('trigger:%s %s', TG_OP, TG_TABLE_NAME),
         v_old_row, v_new_row, pg_current_wal_lsn());

    UPDATE ser_multikv_trace.trace_txn
    SET last_op_ts = clock_timestamp()
    WHERE xid = v_xid;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION ser_multikv_trace.capture_point_read(
    p_table_name TEXT,
    p_local_key TEXT,
    p_sql_text TEXT,
    p_parameters JSONB,
    p_returned JSONB
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_xid BIGINT := ser_multikv_trace.current_xid();
    v_op_index INTEGER := ser_multikv_trace.next_op_index();
    v_object_key TEXT := ser_multikv_trace.object_key(p_table_name, p_local_key);
    v_returned JSONB;
BEGIN
    IF p_returned IS NOT NULL THEN
        RAISE EXCEPTION 'multikv point reads must be captured server-side';
    END IF;

    CASE lower(p_table_name)
        WHEN 'users' THEN
            SELECT value INTO v_returned
            FROM public.users
            WHERE k = p_local_key;
        WHEN 'items' THEN
            SELECT value INTO v_returned
            FROM public.items
            WHERE k = p_local_key;
        WHEN 'orders' THEN
            SELECT value INTO v_returned
            FROM public.orders
            WHERE k = p_local_key;
        ELSE
            RAISE EXCEPTION 'unknown multikv table: %', p_table_name;
    END CASE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'multikv point read returned no row for %', v_object_key;
    END IF;

    PERFORM ser_multikv_trace.validate_value(p_table_name, p_local_key, v_returned);
    PERFORM 1
    FROM ser_multikv_trace.version_registry
    WHERE object_key = v_object_key
      AND value = v_returned;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'point read returned an unregistered version: %', v_object_key;
    END IF;

    INSERT INTO ser_multikv_trace.trace_op
        (xid, op_index, op_type, object_key, value,
         sql_text, parameters, raw_result)
    VALUES
        (v_xid, v_op_index, 'r', v_object_key, v_returned,
         p_sql_text, COALESCE(p_parameters, '[]'::jsonb),
         jsonb_build_object('key', v_object_key, 'value', v_returned));

    UPDATE ser_multikv_trace.trace_txn
    SET last_op_ts = clock_timestamp()
    WHERE xid = v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_multikv_trace.capture_join_read(
    p_item_key_filter TEXT,
    p_sql_text TEXT,
    p_parameters JSONB,
    p_trace_rows JSONB
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_xid BIGINT := ser_multikv_trace.current_xid();
    v_op_index INTEGER := ser_multikv_trace.next_op_index();
    v_trace_rows JSONB;
    v_results JSONB;
    v_inputs JSONB;
    v_predicate JSONB := jsonb_build_object('kind', 'join_open_available');
BEGIN
    IF p_trace_rows IS NOT NULL THEN
        RAISE EXCEPTION 'multikv JOIN reads must be captured server-side';
    END IF;
    IF p_item_key_filter = '' THEN
        RAISE EXCEPTION 'multikv JOIN item filter cannot be empty';
    END IF;

    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'value', jsonb_build_object(
                'order_key', o.value->'order_key',
                'item_key', i.value->'item_key',
                'user_key', o.value->'user_key',
                'stock', i.value->'stock'
            ),
            'order_input', jsonb_build_object(
                'key', ser_multikv_trace.object_key('orders', o.k),
                'value', o.value
            ),
            'item_input', jsonb_build_object(
                'key', ser_multikv_trace.object_key('items', i.k),
                'value', i.value
            )
        )
        ORDER BY o.k, i.k
    ), '[]'::jsonb)
    INTO v_trace_rows
    FROM public.orders AS o
    JOIN public.items AS i
      ON o.value->>'item_key' = i.value->>'item_key'
    WHERE (i.value->>'stock')::BIGINT > 0
      AND (p_item_key_filter IS NULL OR i.value->>'item_key' = p_item_key_filter);

    IF EXISTS (
        SELECT 1
        FROM jsonb_array_elements(v_trace_rows) AS returned(trace_row)
        CROSS JOIN LATERAL (
            VALUES
                (trace_row->'order_input'->>'key', trace_row->'order_input'->'value'),
                (trace_row->'item_input'->>'key', trace_row->'item_input'->'value')
        ) AS input(object_key, value)
        WHERE NOT EXISTS (
            SELECT 1
            FROM ser_multikv_trace.version_registry AS known
            WHERE known.object_key = input.object_key
              AND known.value = input.value
        )
    ) THEN
        RAISE EXCEPTION 'multikv JOIN returned an unregistered row version';
    END IF;

    SELECT COALESCE(jsonb_agg(trace_row->'value' ORDER BY ordinal), '[]'::jsonb)
    INTO v_results
    FROM jsonb_array_elements(v_trace_rows) WITH ORDINALITY
         AS returned(trace_row, ordinal);

    WITH input_candidates AS (
        SELECT ordinal * 2 - 1 AS position, trace_row->'order_input' AS input
        FROM jsonb_array_elements(v_trace_rows) WITH ORDINALITY
             AS returned(trace_row, ordinal)
        UNION ALL
        SELECT ordinal * 2 AS position, trace_row->'item_input' AS input
        FROM jsonb_array_elements(v_trace_rows) WITH ORDINALITY
             AS returned(trace_row, ordinal)
    ), first_inputs AS (
        SELECT DISTINCT ON (input->>'key') position, input
        FROM input_candidates
        ORDER BY input->>'key', position
    )
    SELECT COALESCE(jsonb_agg(input ORDER BY position), '[]'::jsonb)
    INTO v_inputs
    FROM first_inputs;

    IF p_item_key_filter IS NOT NULL THEN
        v_predicate := v_predicate
            || jsonb_build_object('item_key', p_item_key_filter);
    END IF;

    INSERT INTO ser_multikv_trace.trace_op
        (xid, op_index, op_type, predicate, results, read_versions,
         sql_text, parameters, raw_result)
    VALUES
        (v_xid, v_op_index, 'pr', v_predicate, v_results, v_inputs,
         p_sql_text, COALESCE(p_parameters, '[]'::jsonb), v_trace_rows);

    UPDATE ser_multikv_trace.trace_txn
    SET last_op_ts = clock_timestamp()
    WHERE xid = v_xid;
END;
$$;

CREATE OR REPLACE FUNCTION ser_multikv_trace.snapshot_initial_state()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_row ser_multikv_trace.row_version%ROWTYPE;
BEGIN
    LOCK TABLE public.users, public.items, public.orders IN SHARE MODE;

    DELETE FROM ser_multikv_trace.trace_op;
    DELETE FROM ser_multikv_trace.trace_txn;
    DELETE FROM ser_multikv_trace.trace_abort;
    DELETE FROM ser_multikv_trace.write_version;
    DELETE FROM ser_multikv_trace.version_registry;
    DELETE FROM ser_multikv_trace.initial_version;
    DELETE FROM ser_multikv_trace.row_version;

    INSERT INTO ser_multikv_trace.row_version
        (object_key, value, table_name, local_key, row_data)
    SELECT ser_multikv_trace.object_key('users', u.k),
           u.value, 'users', u.k,
           jsonb_build_object('k', u.k, 'value', u.value)
    FROM public.users AS u
    UNION ALL
    SELECT ser_multikv_trace.object_key('items', i.k),
           i.value, 'items', i.k,
           jsonb_build_object('k', i.k, 'value', i.value)
    FROM public.items AS i
    UNION ALL
    SELECT ser_multikv_trace.object_key('orders', o.k),
           o.value, 'orders', o.k,
           jsonb_build_object('k', o.k, 'value', o.value)
    FROM public.orders AS o;

    FOR v_row IN SELECT * FROM ser_multikv_trace.row_version LOOP
        PERFORM ser_multikv_trace.validate_value(
            v_row.table_name, v_row.local_key, v_row.value
        );
    END LOOP;

    INSERT INTO ser_multikv_trace.initial_version
        (object_key, value, table_name, local_key, row_data)
    SELECT object_key, value, table_name, local_key, row_data
    FROM ser_multikv_trace.row_version;

    INSERT INTO ser_multikv_trace.version_registry
        (object_key, value, source_kind, source_xid, source_op_index)
    SELECT object_key, value, 'initial', NULL, NULL
    FROM ser_multikv_trace.row_version;
END;
$$;

DROP TRIGGER IF EXISTS ser_multikv_trace_write ON public.users;
CREATE TRIGGER ser_multikv_trace_write
AFTER INSERT OR UPDATE ON public.users
FOR EACH ROW EXECUTE FUNCTION ser_multikv_trace.capture_write();

DROP TRIGGER IF EXISTS ser_multikv_trace_write ON public.items;
CREATE TRIGGER ser_multikv_trace_write
AFTER INSERT OR UPDATE ON public.items
FOR EACH ROW EXECUTE FUNCTION ser_multikv_trace.capture_write();

DROP TRIGGER IF EXISTS ser_multikv_trace_write ON public.orders;
CREATE TRIGGER ser_multikv_trace_write
AFTER INSERT OR UPDATE ON public.orders
FOR EACH ROW EXECUTE FUNCTION ser_multikv_trace.capture_write();

-- Deliberately separate from installation:
-- SELECT ser_multikv_trace.snapshot_initial_state();
