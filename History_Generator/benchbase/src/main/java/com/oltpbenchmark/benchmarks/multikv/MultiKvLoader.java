package com.oltpbenchmark.benchmarks.multikv;

import com.oltpbenchmark.api.Loader;
import com.oltpbenchmark.api.LoaderThread;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class MultiKvLoader extends Loader<MultiKvBenchmark> {
  public MultiKvLoader(MultiKvBenchmark benchmark) {
    super(benchmark);
  }

  @Override
  public List<LoaderThread> createLoaderThreads() {
    List<LoaderThread> threads = new ArrayList<>();
    threads.add(
        new LoaderThread(this.benchmark) {
          @Override
          public void load(Connection conn) throws SQLException {
            loadRows(conn);
          }
        });
    return threads;
  }

  private void loadRows(Connection conn) throws SQLException {
    List<MultiKvRow> rows = new ArrayList<>();
    rows.add(MultiKvRow.user("u0", "north", true));
    rows.add(MultiKvRow.user("u1", "south", true));
    rows.add(MultiKvRow.item("i0", 3, 10));
    rows.add(MultiKvRow.item("i1", 0, 20));
    rows.add(MultiKvRow.order("o0", "u0", "i0", "open"));
    rows.add(MultiKvRow.order("o1", "u1", "i1", "open"));

    if (benchmark.writeSkewEnabled()) {
      rows.add(MultiKvRow.item("ws0", 1, 30));
      rows.add(MultiKvRow.item("ws1", 1, 40));
      rows.add(MultiKvRow.order("ws0", "u0", "ws0", "open"));
      rows.add(MultiKvRow.order("ws1", "u1", "ws1", "open"));
    } else if (benchmark.lostUpdateEnabled()) {
      rows.add(MultiKvRow.item("lu0", 10, 50));
    }

    for (MultiKvRow.Table table : MultiKvRow.Table.values()) {
      loadTable(conn, table, rows);
    }
  }

  private void loadTable(Connection conn, MultiKvRow.Table table, List<MultiKvRow> rows)
      throws SQLException {
    String sql = "INSERT INTO " + table.sqlName() + " (k, value) VALUES (?, ?::jsonb)";
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      int batch = 0;
      for (MultiKvRow row : rows) {
        if (row.table != table) {
          continue;
        }
        stmt.setString(1, row.key);
        stmt.setString(2, row.valueJson);
        stmt.addBatch();
        if (++batch >= workConf.getBatchSize()) {
          stmt.executeBatch();
          batch = 0;
        }
      }
      if (batch > 0) {
        stmt.executeBatch();
      }
    }
  }
}
