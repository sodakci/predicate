package com.oltpbenchmark.benchmarks.multikv.trace;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class MultiKvTrace {
  private static final boolean ENABLED =
      Boolean.parseBoolean(System.getProperty("ser.multikv.trace", "false"));

  private MultiKvTrace() {}

  public static Long begin(Connection conn, long sessionId, long sessionSeq, String txnType)
      throws SQLException {
    if (!ENABLED) {
      return null;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement("SELECT ser_multikv_trace.begin_txn(?, ?, ?)")) {
      stmt.setLong(1, sessionId);
      stmt.setLong(2, sessionSeq);
      stmt.setString(3, txnType);
      try (ResultSet result = stmt.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("ser_multikv_trace.begin_txn returned no transaction id");
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
            "UPDATE ser_multikv_trace.trace_txn "
                + "SET commit_observed_ts = clock_timestamp() WHERE xid = ?")) {
      stmt.setLong(1, xid);
      if (stmt.executeUpdate() != 1) {
        throw new SQLException("committed multikv trace transaction is missing: " + xid);
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
            "INSERT INTO ser_multikv_trace.trace_abort "
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

  public static boolean joinRead(
      Connection conn, String itemKeyFilter, String sql, Object[] parameters)
      throws SQLException {
    if (!ENABLED) {
      return false;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "SELECT ser_multikv_trace.capture_join_read(?, ?, ?::jsonb, NULL::jsonb)")) {
      stmt.setString(1, itemKeyFilter);
      stmt.setString(2, sql);
      stmt.setString(3, jsonArray(parameters));
      stmt.execute();
    }
    return true;
  }

  public static boolean pointRead(
      Connection conn,
      String tableName,
      String localKey,
      String sql,
      Object[] parameters)
      throws SQLException {
    if (!ENABLED) {
      return false;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "SELECT ser_multikv_trace.capture_point_read("
                + "?, ?, ?, ?::jsonb, NULL::jsonb)")) {
      stmt.setString(1, tableName);
      stmt.setString(2, localKey);
      stmt.setString(3, sql);
      stmt.setString(4, jsonArray(parameters));
      stmt.execute();
    }
    return true;
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
