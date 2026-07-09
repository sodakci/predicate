/*
 * Optional PostgreSQL trace bridge for the BenchBase TPC-C procedures.
 * Enable only for a trace run with: -Dser.tpcc.trace=true
 */
package com.oltpbenchmark.benchmarks.tpcc.trace;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.List;

public final class TpccTrace {
  private static final boolean ENABLED =
      Boolean.parseBoolean(System.getProperty("ser.tpcc.trace", "false"));

  private TpccTrace() {}

  public static boolean enabled() {
    return ENABLED;
  }

  public static Long begin(Connection conn, long sessionId, long sessionSeq, String txnType)
      throws SQLException {
    if (!ENABLED) {
      return null;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement("SELECT ser_tpcc_trace.begin_txn(?, ?, ?)")) {
      stmt.setLong(1, sessionId);
      stmt.setLong(2, sessionSeq);
      stmt.setString(3, txnType);
      try (ResultSet result = stmt.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("ser_tpcc_trace.begin_txn returned no transaction id");
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
            "UPDATE ser_tpcc_trace.trace_txn SET commit_observed_ts = clock_timestamp() WHERE xid = ?")) {
      stmt.setLong(1, xid);
      if (stmt.executeUpdate() != 1) {
        throw new SQLException("committed TPC-C trace transaction is missing: " + xid);
      }
    }
    // Worker has already committed the business transaction.  Persist this
    // post-commit marker before the next workload attempt begins.
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
            "INSERT INTO ser_tpcc_trace.trace_abort "
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
      Connection conn, String objectKey, String sql, Object[] parameters, ResultSet currentRow)
      throws SQLException {
    if (!ENABLED) {
      return;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "SELECT ser_tpcc_trace.capture_point_read(?, ?, ?::jsonb, ?::jsonb)")) {
      stmt.setString(1, objectKey);
      stmt.setString(2, sql);
      stmt.setString(3, jsonArray(parameters));
      stmt.setString(4, resultSetRowJson(currentRow));
      stmt.execute();
    }
  }

  public static void stockLevelRead(
      Connection conn,
      int warehouseId,
      int districtId,
      int nextOrderId,
      int sqlThreshold,
      List<Integer> stockItemIds,
      String joinEvidenceJson,
      int aggregateCount,
      String sql,
      Object[] parameters)
      throws SQLException {
    if (!ENABLED) {
      return;
    }
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "SELECT ser_tpcc_trace.capture_stock_level(?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)")) {
      stmt.setInt(1, warehouseId);
      stmt.setInt(2, districtId);
      stmt.setInt(3, nextOrderId);
      stmt.setInt(4, sqlThreshold);
      Array ids = conn.createArrayOf("integer", stockItemIds.toArray(new Integer[0]));
      try {
        stmt.setArray(5, ids);
        stmt.setInt(6, aggregateCount);
        stmt.setString(7, sql);
        stmt.setString(8, jsonArray(parameters));
        stmt.setString(
            9,
            "{\"stock_count\":"
                + aggregateCount
                + ",\"stock_item_ids\":"
                + jsonArray(stockItemIds.toArray())
                + ",\"join_rows\":"
                + joinEvidenceJson
                + "}");
        stmt.execute();
      } finally {
        ids.free();
      }
    }
  }

  public static String warehouseKey(int warehouseId) {
    return "warehouse:w=" + warehouseId;
  }

  public static String districtKey(int warehouseId, int districtId) {
    return "district:w=" + warehouseId + ":d=" + districtId;
  }

  public static String customerKey(int warehouseId, int districtId, int customerId) {
    return "customer:w=" + warehouseId + ":d=" + districtId + ":c=" + customerId;
  }

  public static String itemKey(int itemId) {
    return "item:i=" + itemId;
  }

  public static String stockKey(int warehouseId, int itemId) {
    return "stock:w=" + warehouseId + ":i=" + itemId;
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
    if (value.getClass().isArray()) {
      if (value instanceof Object[]) {
        return jsonArray((Object[]) value);
      }
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
