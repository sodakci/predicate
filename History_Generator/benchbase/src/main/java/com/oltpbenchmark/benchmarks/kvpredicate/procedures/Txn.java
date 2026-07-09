package com.oltpbenchmark.benchmarks.kvpredicate.procedures;

import com.oltpbenchmark.api.Procedure;
import com.oltpbenchmark.benchmarks.kvpredicate.KvPredicateLoader;
import com.oltpbenchmark.benchmarks.kvpredicate.KvPredicateOperation;
import com.oltpbenchmark.benchmarks.kvpredicate.KvPredicateRow;
import com.oltpbenchmark.benchmarks.kvpredicate.trace.KvPredicateTrace;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

public class Txn extends Procedure {
  private static final String POINT_READ_SQL = "SELECT k, value FROM kv WHERE k = ?";
  private static final String WRITE_SQL =
      "INSERT INTO kv AS kv (k, value) VALUES (?, ?) "
          + "ON CONFLICT (k) DO UPDATE SET value = EXCLUDED.value RETURNING k, value";
  private static final String TRUE_SQL = "SELECT k, value FROM kv ORDER BY k";
  private static final String EQUALS_SQL = "SELECT k, value FROM kv WHERE value = ? ORDER BY k";
  private static final String MOD_SQL = "SELECT k, value FROM kv WHERE MOD(value, ?) = ? ORDER BY k";
  private static final String GREATER_THAN_SQL = "SELECT k, value FROM kv WHERE value > ? ORDER BY k";
  private static final String LESS_THAN_SQL = "SELECT k, value FROM kv WHERE value < ? ORDER BY k";

  public void run(Connection conn, List<KvPredicateOperation> operations) throws SQLException {
    for (KvPredicateOperation operation : operations) {
      switch (operation.kind) {
        case READ:
          pointRead(conn, operation.key);
          break;
        case WRITE:
          write(conn, operation.key, operation.value);
          break;
        case PREDICATE:
          predicateRead(conn, operation);
          break;
        case SLEEP:
          sleep(operation.sleepMillis);
          break;
        case BARRIER:
          awaitBarrier(operation);
          break;
        default:
          throw new SQLException("unknown kvpredicate operation: " + operation.kind);
      }
    }
  }

  private void pointRead(Connection conn, long key) throws SQLException {
    String rowKey = KvPredicateLoader.rowKey(key);
    try (PreparedStatement stmt = conn.prepareStatement(POINT_READ_SQL)) {
      stmt.setString(1, rowKey);
      try (ResultSet result = stmt.executeQuery()) {
        if (result.next()) {
          KvPredicateTrace.pointRead(conn, key, POINT_READ_SQL, new Object[] {rowKey}, result);
          if (result.next()) {
            throw new SQLException("kv point read returned multiple rows for key " + rowKey);
          }
        } else {
          KvPredicateTrace.missingPointRead(conn, key, POINT_READ_SQL, new Object[] {rowKey});
        }
      }
    }
  }

  private void write(Connection conn, long key, long value) throws SQLException {
    try (PreparedStatement stmt = conn.prepareStatement(WRITE_SQL)) {
      stmt.setString(1, KvPredicateLoader.rowKey(key));
      stmt.setLong(2, value);
      try (ResultSet result = stmt.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("kv write returned no row for key " + key);
        }
      }
    }
  }

  private void predicateRead(Connection conn, KvPredicateOperation operation) throws SQLException {
    String sql;
    Object[] parameters;
    switch (operation.predicateKind) {
      case TRUE:
        sql = TRUE_SQL;
        parameters = new Object[0];
        break;
      case EQUALS:
        sql = EQUALS_SQL;
        parameters = new Object[] {operation.target};
        break;
      case MOD:
        sql = MOD_SQL;
        parameters = new Object[] {operation.modulus, operation.target};
        break;
      case GREATER_THAN:
        sql = GREATER_THAN_SQL;
        parameters = new Object[] {operation.target};
        break;
      case LESS_THAN:
        sql = LESS_THAN_SQL;
        parameters = new Object[] {operation.target};
        break;
      default:
        throw new SQLException("unknown kvpredicate predicate: " + operation.predicateKind);
    }

    List<KvPredicateRow> rows = new ArrayList<>();
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        stmt.setObject(index + 1, parameters[index]);
      }
      try (ResultSet result = stmt.executeQuery()) {
        while (result.next()) {
          String rowKey = result.getString("k");
          rows.add(new KvPredicateRow(rowKey, parseKey(rowKey), result.getLong("value")));
        }
      }
    }
    KvPredicateTrace.predicateRead(conn, operation, sql, parameters, rows);
  }

  private static long parseKey(String rowKey) throws SQLException {
    if (rowKey == null || rowKey.length() < 2 || rowKey.charAt(0) != 'k') {
      throw new SQLException("invalid kv row key: " + rowKey);
    }
    try {
      return Long.parseLong(rowKey.substring(1));
    } catch (NumberFormatException exc) {
      throw new SQLException("invalid kv row key: " + rowKey, exc);
    }
  }

  private static void sleep(long millis) throws SQLException {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException exc) {
      Thread.currentThread().interrupt();
      throw new SQLException("interrupted during kvpredicate sleep", exc);
    }
  }

  private static void awaitBarrier(KvPredicateOperation operation) throws SQLException {
    try {
      operation.barrier.await(operation.barrierTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException exc) {
      Thread.currentThread().interrupt();
      throw new SQLException("interrupted while waiting for kvpredicate barrier", exc);
    } catch (BrokenBarrierException | TimeoutException exc) {
      throw new SQLException("kvpredicate scripted barrier failed", exc);
    }
  }
}
