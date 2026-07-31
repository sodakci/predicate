-- psql -X -qAt -d <database> -f multikv/sql/02_export_multikv_trace.sql \
--   > <case>/hist-00000/raw_multikv_trace.jsonl

WITH initial_rows AS (
    SELECT 0 AS record_order,
           object_key AS sort_key,
           jsonb_build_object(
               'record_type', 'initial',
               'key', object_key,
               'value', value,
               'table', table_name,
               'local_key', local_key,
               'row', row_data
           ) AS line
    FROM ser_multikv_trace.initial_version
), transaction_rows AS (
    SELECT 1 AS record_order,
           lpad(t.session_id::TEXT, 20, '0') || ':' ||
           lpad(t.session_seq::TEXT, 20, '0') || ':' ||
           lpad(t.xid::TEXT, 20, '0') AS sort_key,
           jsonb_build_object(
               'record_type', 'txn',
               'txn', t.xid,
               'session', t.session_id,
               'session_seq', t.session_seq,
               'txn_type', t.txn_type,
               'status', 'commit',
               'isolation', t.isolation_level,
               'begin_ts', t.begin_ts,
               'last_op_ts', t.last_op_ts,
               'commit_observed_ts', t.commit_observed_ts,
               'ops', COALESCE(jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
                   'op_index', o.op_index,
                   'type', o.op_type,
                   'key', o.object_key,
                   'value', o.value,
                   'before_value', o.before_value,
                   'predicate', o.predicate,
                   'results', o.results,
                   'read_versions', o.read_versions,
                   'sql', o.sql_text,
                   'parameters', o.parameters,
                   'raw_result', o.raw_result,
                   'old_row', o.old_row,
                   'new_row', o.new_row,
                   'lsn', o.lsn,
                   'recorded_at', o.recorded_at
               )) ORDER BY o.op_index)
                   FILTER (WHERE o.op_index IS NOT NULL), '[]'::jsonb)
           ) AS line
    FROM ser_multikv_trace.trace_txn AS t
    LEFT JOIN ser_multikv_trace.trace_op AS o ON o.xid = t.xid
    GROUP BY t.xid, t.session_id, t.session_seq, t.txn_type,
             t.isolation_level, t.begin_ts, t.last_op_ts,
             t.commit_observed_ts
), abort_rows AS (
    SELECT 2 AS record_order,
           lpad(session_id::TEXT, 20, '0') || ':' ||
           lpad(session_seq::TEXT, 20, '0') || ':' ||
           lpad(xid::TEXT, 20, '0') AS sort_key,
           jsonb_build_object(
               'record_type', 'abort',
               'txn', xid,
               'session', session_id,
               'session_seq', session_seq,
               'txn_type', txn_type,
               'status', status,
               'error', error_text,
               'observed_at', observed_at
           ) AS line
    FROM ser_multikv_trace.trace_abort
)
SELECT line::TEXT
FROM (
    SELECT record_order, sort_key, line FROM initial_rows
    UNION ALL
    SELECT record_order, sort_key, line FROM transaction_rows
    UNION ALL
    SELECT record_order, sort_key, line FROM abort_rows
) AS exported
ORDER BY record_order, sort_key;
