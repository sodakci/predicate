-- psql -X -qAt -d <database> -f tpcc/sql/02_export_tpcc_trace.sql \
--   > tpcc/output/raw_trace.jsonl
--
-- Only persisted trace rows appear here, therefore every exported transaction
-- committed in PostgreSQL.  Rolled-back BenchBase attempts leave no trace rows.

WITH initial_rows AS (
    SELECT jsonb_build_object(
        'record_type', 'initial',
        'key', object_key,
        'value', value,
        'semantic', semantic,
        'source_write_id', source_write_id,
        'source_txn', source_txn,
        'source_op_index', source_op_index,
        'table', table_name,
        'pk', pk,
        'row', row_data
    ) AS line
    FROM ser_tpcc_trace.initial_version
), transaction_rows AS (
    SELECT jsonb_build_object(
        'record_type', 'txn',
        'txn', t.xid,
        'session', t.session_id,
        'session_seq', t.session_seq,
        'txn_type', t.txn_type,
        'status', 'commit',
        'begin_ts', t.begin_ts,
        'last_op_ts', t.last_op_ts,
        'commit_observed_ts', t.commit_observed_ts,
        'ops', COALESCE(jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
            'op_index', o.op_index,
            'type', o.op_type,
            'key', o.object_key,
            'value', o.value,
            'semantic', o.semantic,
            'write_id', o.write_id,
            'before_value', o.before_value,
            'source_write_id', o.source_write_id,
            'source_txn', o.source_txn,
            'source_op_index', o.source_op_index,
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
        )) ORDER BY o.op_index), '[]'::jsonb)
    ) AS line
    FROM ser_tpcc_trace.trace_txn AS t
    LEFT JOIN ser_tpcc_trace.trace_op AS o ON o.xid = t.xid
    GROUP BY t.xid, t.session_id, t.session_seq, t.txn_type, t.begin_ts, t.last_op_ts, t.commit_observed_ts
), abort_rows AS (
    SELECT jsonb_build_object(
        'record_type', 'abort',
        'txn', xid,
        'session', session_id,
        'session_seq', session_seq,
        'txn_type', txn_type,
        'status', status,
        'error', error_text,
        'observed_at', observed_at
    ) AS line
    FROM ser_tpcc_trace.trace_abort
)
SELECT line::TEXT FROM initial_rows
UNION ALL
SELECT line::TEXT FROM transaction_rows
UNION ALL
SELECT line::TEXT FROM abort_rows
ORDER BY 1;
