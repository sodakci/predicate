package com.oltpbenchmark.benchmarks.multikv;

import com.oltpbenchmark.WorkloadConfiguration;
import com.oltpbenchmark.api.BenchmarkModule;
import com.oltpbenchmark.api.Loader;
import com.oltpbenchmark.api.Worker;
import com.oltpbenchmark.benchmarks.multikv.procedures.Txn;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.configuration2.XMLConfiguration;

public final class MultiKvBenchmark extends BenchmarkModule {
  private static final String ANOMALY_NONE = "none";
  private static final String ANOMALY_WRITE_SKEW = "write-skew";
  private static final String ANOMALY_LOST_UPDATE = "lost-update";
  private static final List<MultiKvRow> RANDOM_ROWS =
      List.of(
          MultiKvRow.user("u0", "north", true),
          MultiKvRow.user("u1", "south", true),
          MultiKvRow.item("i0", 3, 10),
          MultiKvRow.item("i1", 0, 20),
          MultiKvRow.order("o0", "u0", "i0", "open"),
          MultiKvRow.order("o1", "u1", "i1", "open"));

  private final String anomalyMode;
  private final long anomalyDelayMillis;
  private final int transactionCount;
  private final String keyDist;
  private final double keyDistBase;
  private final double keyDistScale;
  private final int minTxnLength;
  private final int maxTxnLength;
  private final Object generatorLock = new Object();
  private long nextWriteRevision = 1;
  private final AtomicInteger transactionAssignments = new AtomicInteger(0);
  private final CyclicBarrier anomalyBarrier = new CyclicBarrier(2);
  private final CountDownLatch anomalyCoreCommits = new CountDownLatch(2);

  public MultiKvBenchmark(WorkloadConfiguration workConf) {
    super(workConf);
    XMLConfiguration xml = workConf.getXmlConfig();
    this.anomalyMode = getString(xml, "multiKvAnomaly", ANOMALY_NONE).toLowerCase();
    this.anomalyDelayMillis = getLong(xml, "multiKvAnomalyDelayMs", 250L);
    this.transactionCount = getInt(xml, "multiKvTransactionCount", 0);
    this.keyDist = getString(xml, "keyDist", "exponential").toLowerCase();
    this.keyDistBase = getDouble(xml, "keyDistBase", 2.0);
    this.minTxnLength = getInt(xml, "minTxnLength", 1);
    this.maxTxnLength = getInt(xml, "maxTxnLength", 4);

    if (!List.of(ANOMALY_NONE, ANOMALY_WRITE_SKEW, ANOMALY_LOST_UPDATE)
        .contains(anomalyMode)) {
      throw new IllegalArgumentException("unsupported multiKvAnomaly: " + anomalyMode);
    }
    if (anomalyDelayMillis < 0) {
      throw new IllegalArgumentException("multiKvAnomalyDelayMs must be non-negative");
    }
    if (transactionCount < 0) {
      throw new IllegalArgumentException("multiKvTransactionCount must be non-negative");
    }
    if (!List.of("uniform", "zipf", "exponential").contains(keyDist)) {
      throw new IllegalArgumentException("unsupported keyDist: " + keyDist);
    }
    if (("zipf".equals(keyDist) && keyDistBase <= 0)
        || ("exponential".equals(keyDist) && keyDistBase <= 1)) {
      throw new IllegalArgumentException("invalid keyDistBase for " + keyDist);
    }
    if (minTxnLength <= 0 || maxTxnLength < minTxnLength) {
      throw new IllegalArgumentException("invalid minTxnLength/maxTxnLength");
    }
    if (scriptedAnomalyEnabled() && workConf.getTerminals() < 2) {
      throw new IllegalArgumentException(
          "multiKvAnomaly=" + anomalyMode + " requires at least 2 terminals");
    }
    if (scriptedAnomalyEnabled() && transactionCount > 0 && transactionCount < 2) {
      throw new IllegalArgumentException(
          "multiKvAnomaly="
              + anomalyMode
              + " requires multiKvTransactionCount=0 or at least 2");
    }
    if (lostUpdateEnabled()
        && workConf.getIsolationMode() != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalArgumentException(
          "multiKvAnomaly=lost-update requires TRANSACTION_READ_COMMITTED");
    }
    this.keyDistScale =
        "exponential".equals(keyDist)
            ? keyDistScale(this.keyDistBase, RANDOM_ROWS.size())
            : 0.0;
  }

  boolean writeSkewEnabled() {
    return ANOMALY_WRITE_SKEW.equals(anomalyMode);
  }

  boolean lostUpdateEnabled() {
    return ANOMALY_LOST_UPDATE.equals(anomalyMode);
  }

  private boolean scriptedAnomalyEnabled() {
    return writeSkewEnabled() || lostUpdateEnabled();
  }

  String traceTransactionType(List<MultiKvOperation> operations) {
    if (!isAnomalyCore(operations)) {
      return "MultiKvTxn";
    }
    return writeSkewEnabled() ? "WriteSkew" : "LostUpdate";
  }

  List<MultiKvOperation> nextTransaction(Random random) {
    int assignment = transactionAssignments.getAndIncrement();
    if (transactionCount > 0 && assignment >= transactionCount) {
      return List.of();
    }
    if (scriptedAnomalyEnabled() && assignment < 2) {
      return writeSkewEnabled()
          ? writeSkewTransaction(assignment)
          : lostUpdateTransaction(assignment);
    }
    return randomTransaction(random);
  }

  void awaitAnomalyCore(List<MultiKvOperation> operations) throws SQLException {
    if (!scriptedAnomalyEnabled() || isAnomalyCore(operations)) {
      return;
    }
    long timeoutMillis = Math.max(5000L, anomalyDelayMillis * 8L);
    try {
      if (!anomalyCoreCommits.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
        throw new SQLException(
            "timed out waiting for both multikv " + anomalyMode + " core commits");
      }
    } catch (InterruptedException exc) {
      Thread.currentThread().interrupt();
      throw new SQLException(
          "interrupted while waiting for multikv " + anomalyMode + " core commits", exc);
    }
  }

  void transactionCommitted(List<MultiKvOperation> operations) {
    if (isAnomalyCore(operations)) {
      anomalyCoreCommits.countDown();
    }
  }

  private boolean isAnomalyCore(List<MultiKvOperation> operations) {
    return scriptedAnomalyEnabled()
        && operations.stream().anyMatch(operation -> operation.kind == MultiKvOperation.Kind.BARRIER);
  }

  private List<MultiKvOperation> randomTransaction(Random random) {
    synchronized (generatorLock) {
      int length = minTxnLength + random.nextInt(maxTxnLength - minTxnLength + 1);
      List<MultiKvOperation> operations = new ArrayList<>(length);
      for (int index = 0; index < length; index++) {
        int choice = random.nextInt(3);
        if (choice == 0) {
          operations.add(MultiKvOperation.read(chooseRow(random)));
        } else if (choice == 1) {
          operations.add(MultiKvOperation.write(nextWrite(chooseRow(random))));
        } else {
          operations.add(MultiKvOperation.joinOpenAvailable(null));
        }
      }
      return operations;
    }
  }

  private MultiKvRow chooseRow(Random random) {
    int size = RANDOM_ROWS.size();
    int index;
    switch (keyDist) {
      case "uniform":
        index = random.nextInt(size);
        break;
      case "zipf":
        index = zipfIndex(random, size);
        break;
      case "exponential":
        index =
            (int)
                Math.floor(
                    Math.log(random.nextDouble() * keyDistScale + keyDistBase)
                            / Math.log(keyDistBase)
                        - 1.0);
        if (index < 0) {
          index = 0;
        } else if (index >= size) {
          index = size - 1;
        }
        break;
      default:
        throw new IllegalStateException("unsupported keyDist: " + keyDist);
    }
    return RANDOM_ROWS.get(index);
  }

  private int zipfIndex(Random random, int size) {
    double normalizer = 0.0;
    for (int index = 1; index <= size; index++) {
      normalizer += 1.0 / Math.pow(index, keyDistBase);
    }
    double sample = random.nextDouble() * normalizer;
    double cumulative = 0.0;
    for (int index = 1; index <= size; index++) {
      cumulative += 1.0 / Math.pow(index, keyDistBase);
      if (sample <= cumulative) {
        return index - 1;
      }
    }
    return size - 1;
  }

  private MultiKvRow nextWrite(MultiKvRow selected) {
    long revision = nextWriteRevision++;
    switch (selected.table) {
      case USERS:
        return MultiKvRow.user(
            selected.key,
            ("u0".equals(selected.key) ? "north-r" : "south-r") + revision,
            true);
      case ITEMS:
        return MultiKvRow.item(
            selected.key, "i0".equals(selected.key) ? 3 : 0, 1000L + revision);
      case ORDERS:
        return MultiKvRow.order(
            selected.key,
            "o0".equals(selected.key) ? "u0" : "u1",
            "o0".equals(selected.key) ? "i0" : "i1",
            "open-r" + revision);
      default:
        throw new IllegalStateException("unknown multikv table: " + selected.table);
    }
  }

  private List<MultiKvOperation> writeSkewTransaction(int assignment) {
    String readItemKey = assignment == 0 ? "ws0" : "ws1";
    String writeItemKey = assignment == 0 ? "ws1" : "ws0";
    long writeItemPrice = assignment == 0 ? 40 : 30;
    long barrierTimeoutMillis = Math.max(5000L, anomalyDelayMillis * 8L);

    List<MultiKvOperation> operations = new ArrayList<>();
    operations.add(MultiKvOperation.joinOpenAvailable(readItemKey));
    operations.add(MultiKvOperation.barrier(anomalyBarrier, barrierTimeoutMillis));
    if (anomalyDelayMillis > 0) {
      operations.add(MultiKvOperation.sleep(anomalyDelayMillis));
    }
    operations.add(MultiKvOperation.write(MultiKvRow.item(writeItemKey, 0, writeItemPrice)));
    return operations;
  }

  private List<MultiKvOperation> lostUpdateTransaction(int assignment) {
    long barrierTimeoutMillis = Math.max(5000L, anomalyDelayMillis * 8L);
    List<MultiKvOperation> operations = new ArrayList<>();
    operations.add(MultiKvOperation.read(MultiKvRow.item("lu0", 10, 50)));
    operations.add(MultiKvOperation.barrier(anomalyBarrier, barrierTimeoutMillis));
    if (assignment == 1 && anomalyDelayMillis > 0) {
      operations.add(MultiKvOperation.sleep(anomalyDelayMillis));
    }
    operations.add(
        MultiKvOperation.write(MultiKvRow.item("lu0", assignment == 0 ? 9 : 8, 50)));
    return operations;
  }

  private static long getLong(XMLConfiguration xml, String key, long defaultValue) {
    return xml != null && xml.containsKey(key) ? xml.getLong(key) : defaultValue;
  }

  private static int getInt(XMLConfiguration xml, String key, int defaultValue) {
    return xml != null && xml.containsKey(key) ? xml.getInt(key) : defaultValue;
  }

  private static double getDouble(XMLConfiguration xml, String key, double defaultValue) {
    return xml != null && xml.containsKey(key) ? xml.getDouble(key) : defaultValue;
  }

  private static String getString(XMLConfiguration xml, String key, String defaultValue) {
    return xml != null && xml.containsKey(key) ? xml.getString(key) : defaultValue;
  }

  private static double keyDistScale(double base, int keyCount) {
    return ((Math.pow(base, keyCount) - 1.0) * base) / (base - 1.0);
  }

  @Override
  protected List<Worker<? extends BenchmarkModule>> makeWorkersImpl() {
    List<Worker<? extends BenchmarkModule>> workers = new ArrayList<>();
    for (int index = 0; index < workConf.getTerminals(); index++) {
      workers.add(new MultiKvWorker(this, index));
    }
    return workers;
  }

  @Override
  protected Loader<MultiKvBenchmark> makeLoaderImpl() {
    return new MultiKvLoader(this);
  }

  @Override
  protected Package getProcedurePackageImpl() {
    return Txn.class.getPackage();
  }
}
