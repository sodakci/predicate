package com.oltpbenchmark.benchmarks.kvpredicate;

import com.oltpbenchmark.api.Loader;
import com.oltpbenchmark.api.LoaderThread;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class KvPredicateLoader extends Loader<KvPredicateBenchmark> {
  public KvPredicateLoader(KvPredicateBenchmark benchmark) {
    super(benchmark);
  }

  @Override
  public List<LoaderThread> createLoaderThreads() {
    List<LoaderThread> threads = new ArrayList<>();
    threads.add(
        new LoaderThread(this.benchmark) {
          @Override
          public void load(Connection conn) throws SQLException {
            loadInitialRows(conn);
          }
        });
    return threads;
  }

  private void loadInitialRows(Connection conn) throws SQLException {
    try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO kv (k, value) VALUES (?, ?)")) {
      int batch = 0;
      for (int key = 0; key < this.benchmark.getKeyCount(); key++) {
        stmt.setString(1, rowKey(key));
        stmt.setLong(2, key);
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

  public static String rowKey(long key) {
    return "k" + key;
  }
}
