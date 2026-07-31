-- psql -X -qAt -d <database> -f kv/sql/02_export_kvpredicate_trace.sql \
--   > kv/output/<case>/hist-00000/raw_kvpredicate_trace.jsonl

COPY (
SELECT jsonb_build_object(
           'record_type', 'initial',
           'key', object_key,
           'value', value,
           'semantic', semantic,
           'table', table_name,
           'pk', pk,
           'row', row_data
       )::TEXT
FROM ser_kvpredicate_trace.initial_version
ORDER BY object_key
) TO STDOUT;

COPY (
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
           'ops', COALESCE(o.ops, '[]'::jsonb)
       )::TEXT
FROM ser_kvpredicate_trace.trace_txn AS t
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
               'op_index', op.op_index,
               'type', op.op_type,
               'key', op.object_key,
               'value', op.value,
               'semantic', op.semantic,
               'write_id', op.write_id,
               'before_value', op.before_value,
               'absent', op.is_absent,
               'predicate', op.predicate,
               'results', op.results,
               'read_versions', op.read_versions,
               'sql', op.sql_text,
               'parameters', op.parameters,
               'raw_result', op.raw_result,
               'old_row', op.old_row,
               'new_row', op.new_row,
               'lsn', op.lsn,
               'recorded_at', op.recorded_at
           )) ORDER BY op.op_index) AS ops
    FROM ser_kvpredicate_trace.trace_op AS op
    WHERE op.xid = t.xid
) AS o ON TRUE
ORDER BY t.session_id, t.session_seq, t.xid
) TO STDOUT;

COPY (
SELECT jsonb_build_object(
           'record_type', 'abort',
           'txn', xid,
           'session', session_id,
           'session_seq', session_seq,
           'txn_type', txn_type,
           'status', status,
           'error', error_text,
           'observed_at', observed_at
       )::TEXT
FROM ser_kvpredicate_trace.trace_abort
ORDER BY session_id, session_seq, xid
) TO STDOUT;
