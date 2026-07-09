package com.oltpbenchmark.benchmarks.kvpredicate.trace;

import com.oltpbenchmark.benchmarks.kvpredicate.KvPredicateOperation;
import com.oltpbenchmark.benchmarks.kvpredicate.KvPredicateRow;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.List;

public final class KvPredicateTrace {
  private static final boolean ENABLED =
      Boolean.parseBoolean(System.getProperty("ser.kvpredicate.trace", "false"));

  private KvPredicateTrace() {}

  public static boolean enabled() {
    return ENABLED;
  }

  public static Long begin(Connection conn, long sessionId, long sessionSeq, String txnType)
      throws SQLException {
    if (!ENABLED) {
      return null;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement("SELECT ser_kvpredicate_trace.begin_txn(?, ?, ?)")) {
      stmt.setLong(1, sessionId);
      stmt.setLong(2, sessionSeq);
      stmt.setString(3, txnType);
      try (ResultSet result = stmt.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("ser_kvpredicate_trace.begin_txn returned no transaction id");
        }
        return result.getLong(1);
      }
    }
  }

  public static void markCommitted(Connection conn, long xid) throws SQLException {
    if (!ENABLED) {
      return;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "UPDATE ser_kvpredicate_trace.trace_txn "
                + "SET commit_observed_ts = clock_timestamp() WHERE xid = ?")) {
      stmt.setLong(1, xid);
      if (stmt.executeUpdate() != 1) {
        throw new SQLException("committed kvpredicate trace transaction is missing: " + xid);
      }
    }
    conn.commit();
  }

  public static void recordAbort(
      Connection conn,
      long xid,
      long sessionId,
      long sessionSeq,
      String txnType,
      String status,
      String errorText)
      throws SQLException {
    if (!ENABLED || conn == null || conn.isClosed()) {
      return;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "INSERT INTO ser_kvpredicate_trace.trace_abort "
                + "(xid, session_id, session_seq, txn_type, status, error_text) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (xid) DO NOTHING")) {
      stmt.setLong(1, xid);
      stmt.setLong(2, sessionId);
      stmt.setLong(3, sessionSeq);
      stmt.setString(4, txnType);
      stmt.setString(5, status);
      stmt.setString(6, errorText);
      stmt.executeUpdate();
    }
    conn.commit();
  }

  public static void pointRead(
      Connection conn, long key, String sql, Object[] parameters, ResultSet currentRow)
      throws SQLException {
    if (!ENABLED) {
      return;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "SELECT ser_kvpredicate_trace.capture_point_read(?, ?, ?::jsonb, ?::jsonb)")) {
      stmt.setLong(1, key);
      stmt.setString(2, sql);
      stmt.setString(3, jsonArray(parameters));
      stmt.setString(4, resultSetRowJson(currentRow));
      stmt.execute();
    }
  }

  public static void missingPointRead(Connection conn, long key, String sql, Object[] parameters)
      throws SQLException {
    if (!ENABLED) {
      return;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "SELECT ser_kvpredicate_trace.capture_missing_point_read(?, ?, ?::jsonb)")) {
      stmt.setLong(1, key);
      stmt.setString(2, sql);
      stmt.setString(3, jsonArray(parameters));
      stmt.execute();
    }
  }

  public static void predicateRead(
      Connection conn,
      KvPredicateOperation operation,
      String sql,
      Object[] parameters,
      List<KvPredicateRow> rows)
      throws SQLException {
    if (!ENABLED) {
      return;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "SELECT ser_kvpredicate_trace.capture_predicate_read"
                + "(?, ?, ?, ?, ?::jsonb, ?::jsonb)")) {
      stmt.setString(1, predicateKind(operation));
      if (operation.predicateKind == KvPredicateOperation.PredicateKind.MOD) {
        stmt.setLong(2, operation.modulus);
      } else {
        stmt.setObject(2, null);
      }
      if (operation.predicateKind == KvPredicateOperation.PredicateKind.TRUE) {
        stmt.setObject(3, null);
      } else {
        stmt.setLong(3, operation.target);
      }
      stmt.setString(4, sql);
      stmt.setString(5, jsonArray(parameters));
      stmt.setString(6, rowsJson(rows));
      stmt.execute();
    }
  }

  private static String predicateKind(KvPredicateOperation operation) {
    switch (operation.predicateKind) {
      case TRUE:
        return "true";
      case EQUALS:
        return "eq";
      case MOD:
        return "mod";
      case GREATER_THAN:
        return "gt";
      case LESS_THAN:
        return "lt";
      default:
        throw new IllegalArgumentException("unknown predicate kind " + operation.predicateKind);
    }
  }

  private static String rowsJson(List<KvPredicateRow> rows) {
    StringBuilder json = new StringBuilder("[");
    for (int index = 0; index < rows.size(); index++) {
      if (index > 0) {
        json.append(',');
      }
      KvPredicateRow row = rows.get(index);
      json.append("{\"k\":")
          .append(row.key)
          .append(",\"row_key\":")
          .append(jsonString(row.rowKey))
          .append(",\"value\":")
          .append(row.value)
          .append('}');
    }
    return json.append(']').toString();
  }

  private static String resultSetRowJson(ResultSet row) throws SQLException {
    ResultSetMetaData metadata = row.getMetaData();
    StringBuilder json = new StringBuilder("{");
    for (int index = 1; index <= metadata.getColumnCount(); index++) {
      if (index > 1) {
        json.append(',');
      }
      json.append(jsonString(metadata.getColumnLabel(index))).append(':');
      json.append(jsonValue(row.getObject(index)));
    }
    return json.append('}').toString();
  }

  private static String jsonArray(Object[] values) {
    StringBuilder json = new StringBuilder("[");
    for (int index = 0; index < values.length; index++) {
      if (index > 0) {
        json.append(',');
      }
      json.append(jsonValue(values[index]));
    }
    return json.append(']').toString();
  }

  private static String jsonValue(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Number || value instanceof Boolean || value instanceof BigDecimal) {
      return value.toString();
    }
    if (value instanceof Timestamp || value instanceof TemporalAccessor) {
      return jsonString(value.toString());
    }
    if (value instanceof Collection<?>) {
      return jsonArray(((Collection<?>) value).toArray());
    }
    if (value.getClass().isArray() && value instanceof Object[]) {
      return jsonArray((Object[]) value);
    }
    return jsonString(value.toString());
  }

  private static String jsonString(String text) {
    StringBuilder json = new StringBuilder("\"");
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '\\':
          json.append("\\\\");
          break;
        case '"':
          json.append("\\\"");
          break;
        case '\n':
          json.append("\\n");
          break;
        case '\r':
          json.append("\\r");
          break;
        case '\t':
          json.append("\\t");
          break;
        default:
          if (character < 0x20) {
            json.append(String.format("\\u%04x", (int) character));
          } else {
            json.append(character);
          }
          break;
      }
    }
    return json.append('"').toString();
  }
}
