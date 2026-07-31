package com.oltpbenchmark.benchmarks.multikv.procedures;

import com.oltpbenchmark.api.Procedure;
import com.oltpbenchmark.benchmarks.multikv.MultiKvOperation;
import com.oltpbenchmark.benchmarks.multikv.MultiKvRow;
import com.oltpbenchmark.benchmarks.multikv.trace.MultiKvTrace;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Txn extends Procedure {
  private static final String JOIN_SQL =
      "SELECT o.k AS order_k, o.value AS order_value, "
          + "i.k AS item_k, i.value AS item_value, "
          + "o.value->>'order_key' AS order_key, "
          + "i.value->>'item_key' AS item_key, "
          + "o.value->>'user_key' AS user_key, "
          + "(i.value->>'stock')::bigint AS stock "
          + "FROM orders AS o "
          + "INNER JOIN items AS i "
          + "ON o.value->>'item_key' = i.value->>'item_key' "
          + "WHERE (i.value->>'stock')::bigint > 0 "
          + "ORDER BY o.k, i.k";
  private static final String FILTERED_JOIN_SQL =
      "SELECT o.k AS order_k, o.value AS order_value, "
          + "i.k AS item_k, i.value AS item_value, "
          + "o.value->>'order_key' AS order_key, "
          + "i.value->>'item_key' AS item_key, "
          + "o.value->>'user_key' AS user_key, "
          + "(i.value->>'stock')::bigint AS stock "
          + "FROM orders AS o "
          + "INNER JOIN items AS i "
          + "ON o.value->>'item_key' = i.value->>'item_key' "
          + "WHERE (i.value->>'stock')::bigint > 0 "
          + "AND i.value->>'item_key' = ? "
          + "ORDER BY o.k, i.k";

  public void run(Connection conn, List<MultiKvOperation> operations) throws SQLException {
    for (MultiKvOperation operation : operations) {
      switch (operation.kind) {
        case READ:
          pointRead(conn, operation.row);
          break;
        case WRITE:
          write(conn, operation.row);
          break;
        case JOIN_PREDICATE:
          joinOpenAvailable(conn, operation.itemKeyFilter);
          break;
        case SLEEP:
          sleep(operation.sleepMillis);
          break;
        case BARRIER:
          awaitBarrier(operation);
          break;
        default:
          throw new SQLException("unknown multikv operation: " + operation.kind);
      }
    }
  }

  private void pointRead(Connection conn, MultiKvRow row) throws SQLException {
    String sql = "SELECT value FROM " + row.table.sqlName() + " WHERE k = ?";
    if (MultiKvTrace.pointRead(
        conn, row.table.sqlName(), row.key, sql, new Object[] {row.key})) {
      return;
    }
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, row.key);
      try (ResultSet result = stmt.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("multikv point read returned no row for " + row.canonicalKey());
        }
        if (result.next()) {
          throw new SQLException(
              "multikv point read returned multiple rows for " + row.canonicalKey());
        }
      }
    }
  }

  private void write(Connection conn, MultiKvRow row) throws SQLException {
    String sql;
    switch (row.table) {
      case USERS:
        sql =
            "INSERT INTO users AS target (k, value) VALUES (?, ?::jsonb) "
                + "ON CONFLICT (k) DO UPDATE SET value = EXCLUDED.value RETURNING k, value";
        break;
      case ITEMS:
        sql =
            "INSERT INTO items AS target (k, value) VALUES (?, ?::jsonb) "
                + "ON CONFLICT (k) DO UPDATE SET value = EXCLUDED.value RETURNING k, value";
        break;
      case ORDERS:
        sql =
            "INSERT INTO orders AS target (k, value) VALUES (?, ?::jsonb) "
                + "ON CONFLICT (k) DO UPDATE SET value = EXCLUDED.value RETURNING k, value";
        break;
      default:
        throw new SQLException("unknown multikv table: " + row.table);
    }

    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, row.key);
      stmt.setString(2, row.valueJson);
      try (ResultSet result = stmt.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("multikv write returned no row for " + row.canonicalKey());
        }
      }
    }
  }

  private void joinOpenAvailable(Connection conn, String itemKeyFilter) throws SQLException {
    String sql = itemKeyFilter == null ? JOIN_SQL : FILTERED_JOIN_SQL;
    Object[] parameters = itemKeyFilter == null ? new Object[0] : new Object[] {itemKeyFilter};
    if (MultiKvTrace.joinRead(conn, itemKeyFilter, sql, parameters)) {
      return;
    }

    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      if (itemKeyFilter != null) {
        stmt.setString(1, itemKeyFilter);
      }
      try (ResultSet result = stmt.executeQuery()) {
        while (result.next()) {
          // Consume the complete predicate result when tracing is disabled.
        }
      }
    }
  }

  private static void sleep(long millis) throws SQLException {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException exc) {
      Thread.currentThread().interrupt();
      throw new SQLException("interrupted during multikv sleep", exc);
    }
  }

  private static void awaitBarrier(MultiKvOperation operation) throws SQLException {
    try {
      operation.barrier.await(operation.barrierTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException exc) {
      Thread.currentThread().interrupt();
      throw new SQLException("interrupted while waiting for multikv barrier", exc);
    } catch (BrokenBarrierException | TimeoutException exc) {
      throw new SQLException("multikv scripted barrier failed", exc);
    }
  }
}
