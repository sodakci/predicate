package com.oltpbenchmark.benchmarks.kvpredicate;

import com.oltpbenchmark.WorkloadConfiguration;
import com.oltpbenchmark.api.BenchmarkModule;
import com.oltpbenchmark.api.Loader;
import com.oltpbenchmark.api.Worker;
import com.oltpbenchmark.benchmarks.kvpredicate.procedures.Txn;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.configuration2.XMLConfiguration;

public final class KvPredicateBenchmark extends BenchmarkModule {
  private static final String ANOMALY_LOST_UPDATE = "lost-update";

  private final int keyCount;
  private final String keyDist;
  private final double keyDistBase;
  private final double keyDistScale;
  private final int minTxnLength;
  private final int maxTxnLength;
  private final int maxWritesPerKey;
  private final int predicateGroupCount;
  private final int transactionsPerSession;
  private final double predicateReadRatio;
  private final long mopDelayMillis;
  private final String anomalyMode;
  private final long anomalyDelayMillis;
  private final double[] zipfCdf;

  private final Object generatorLock = new Object();
  private final List<Long> activeKeys = new ArrayList<>();
  private final Map<Long, Integer> writesPerKey = new HashMap<>();
  private long nextKey;
  private long nextWrite;

  private final AtomicInteger scriptedAssignments = new AtomicInteger(0);
  private final CyclicBarrier writeSkewBarrier = new CyclicBarrier(2);
  private final CyclicBarrier lostUpdateBarrier = new CyclicBarrier(2);

  public KvPredicateBenchmark(WorkloadConfiguration workConf) {
    super(workConf);
    XMLConfiguration xml = workConf.getXmlConfig();
    this.keyCount = getInt(xml, "keyCount", 10);
    String configuredKeyDist = getString(xml, "keyDist", "exponential").toLowerCase();
    this.keyDist = "zipfian".equals(configuredKeyDist) ? "zipf" : configuredKeyDist;
    this.keyDistBase = getDouble(xml, "keyDistBase", 2.0);
    this.keyDistScale =
        "exponential".equals(this.keyDist)
            ? keyDistScale(this.keyDistBase, this.keyCount)
            : 0.0;
    this.minTxnLength = getInt(xml, "minTxnLength", 1);
    this.maxTxnLength = getInt(xml, "maxTxnLength", 4);
    this.maxWritesPerKey = getInt(xml, "maxWritesPerKey", 256);
    this.predicateGroupCount = getInt(xml, "predicateGroupCount", 4);
    this.transactionsPerSession = getInt(xml, "transactionsPerSession", 0);
    this.predicateReadRatio = getDouble(xml, "predicateReadRatio", 100.0 / 3.0);
    this.mopDelayMillis = getLong(xml, "mopDelayMs", 0L);
    this.anomalyMode = getString(xml, "kvPredicateAnomaly", "none").toLowerCase();
    this.anomalyDelayMillis = getLong(xml, "kvPredicateAnomalyDelayMs", 250L);

    if (this.keyCount <= 0) {
      throw new IllegalArgumentException("keyCount must be positive");
    }
    if (this.minTxnLength <= 0 || this.maxTxnLength < this.minTxnLength) {
      throw new IllegalArgumentException("invalid minTxnLength/maxTxnLength");
    }
    if (this.predicateGroupCount <= 0) {
      throw new IllegalArgumentException("predicateGroupCount must be positive");
    }
    if (!"uniform".equals(this.keyDist)
        && !"zipf".equals(this.keyDist)
        && !"hotspot".equals(this.keyDist)
        && !"exponential".equals(this.keyDist)) {
      throw new IllegalArgumentException(
          "keyDist must be uniform, zipf/zipfian, hotspot, or exponential");
    }
    if (this.keyDistBase <= 0.0
        || ("exponential".equals(this.keyDist) && this.keyDistBase <= 1.0)) {
      throw new IllegalArgumentException(
          "keyDistBase must be positive, and greater than 1 for exponential");
    }
    if (this.transactionsPerSession < 0) {
      throw new IllegalArgumentException("transactionsPerSession must be non-negative");
    }
    if (this.predicateReadRatio < 0.0 || this.predicateReadRatio > 100.0) {
      throw new IllegalArgumentException("predicateReadRatio must be between 0 and 100");
    }
    if (lostUpdateEnabled() && this.keyCount < 2) {
      throw new IllegalArgumentException("lost-update requires keyCount >= 2");
    }
    if (lostUpdateEnabled() && workConf.getTerminals() < 2) {
      throw new IllegalArgumentException("lost-update requires at least 2 terminals");
    }
    if (lostUpdateEnabled()
        && workConf.getIsolationMode() != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalArgumentException(
          "kvPredicateAnomaly=lost-update requires TRANSACTION_READ_COMMITTED");
    }
    int ordinaryKeyCount = lostUpdateEnabled() ? this.keyCount - 1 : this.keyCount;
    for (int key = 0; key < ordinaryKeyCount; key++) {
      this.activeKeys.add((long) key);
    }
    this.nextKey = this.keyCount;
    this.nextWrite = lostUpdateEnabled() ? this.keyCount + 2L : this.keyCount;
    this.zipfCdf = "zipf".equals(this.keyDist) ? buildZipfCdf(ordinaryKeyCount) : null;
  }

  int getKeyCount() {
    return keyCount;
  }

  int getTransactionsPerSession() {
    return transactionsPerSession;
  }

  boolean lostUpdateEnabled() {
    return ANOMALY_LOST_UPDATE.equals(anomalyMode);
  }

  long lostUpdateKey() {
    return keyCount - 1L;
  }

  List<KvPredicateOperation> nextTransaction(int workerId, Random random) {
    if ("write-skew".equals(anomalyMode) && workConf.getTerminals() >= 2) {
      int assignment = scriptedAssignments.getAndIncrement();
      if (assignment < 2) {
        return writeSkewTransaction(assignment);
      }
    }
    if (lostUpdateEnabled()) {
      int assignment = scriptedAssignments.getAndIncrement();
      if (assignment < 2) {
        return lostUpdateTransaction(assignment);
      }
    }
    return randomTransaction(random);
  }

  private List<KvPredicateOperation> writeSkewTransaction(int assignment) {
    long writeValue;
    synchronized (generatorLock) {
      long writeKey = assignment == 0 ? 1 : 0;
      writeValue = nextWrite++;
      rotateKey(writeKey);
    }
    List<KvPredicateOperation> ops = new ArrayList<>();
    ops.add(KvPredicateOperation.predicateEquals(assignment == 0 ? 0 : 1));
    ops.add(KvPredicateOperation.barrier(writeSkewBarrier, Math.max(5000L, anomalyDelayMillis * 8L)));
    if (anomalyDelayMillis > 0) {
      ops.add(KvPredicateOperation.sleep(anomalyDelayMillis));
    }
    ops.add(KvPredicateOperation.write(assignment == 0 ? 1 : 0, writeValue));
    return ops;
  }

  private List<KvPredicateOperation> lostUpdateTransaction(int assignment) {
    List<KvPredicateOperation> ops = new ArrayList<>();
    ops.add(KvPredicateOperation.read(lostUpdateKey()));
    ops.add(
        KvPredicateOperation.barrier(lostUpdateBarrier, Math.max(5000L, anomalyDelayMillis * 8L)));
    if (assignment == 1 && anomalyDelayMillis > 0) {
      ops.add(KvPredicateOperation.sleep(anomalyDelayMillis));
    }
    ops.add(
        KvPredicateOperation.write(
            lostUpdateKey(), assignment == 0 ? keyCount : keyCount + 1L));
    return ops;
  }

  private List<KvPredicateOperation> randomTransaction(Random random) {
    synchronized (generatorLock) {
      int length = minTxnLength + random.nextInt(maxTxnLength - minTxnLength + 1);
      List<KvPredicateOperation> ops = new ArrayList<>(length);
      for (int index = 0; index < length; index++) {
        if (random.nextDouble() * 100.0 < predicateReadRatio) {
          ops.add(randomPredicate(random));
        } else if (random.nextBoolean()) {
          ops.add(KvPredicateOperation.read(chooseKey(random)));
        } else {
          long key = chooseKey(random);
          long value = nextWrite++;
          rotateKey(key);
          ops.add(KvPredicateOperation.write(key, value));
        }
      }
      return ops;
    }
  }

  private KvPredicateOperation randomPredicate(Random random) {
    if (lostUpdateEnabled()) {
      long target = nonNegativeLong(random, keyCount - 1L);
      return random.nextBoolean()
          ? KvPredicateOperation.predicateEquals(target)
          : KvPredicateOperation.predicateLessThan(target);
    }
    int choice = random.nextInt(5);
    if (choice == 0) {
      return KvPredicateOperation.predicateTrue();
    }
    if (choice == 1) {
      return KvPredicateOperation.predicateMod(predicateGroupCount, random.nextInt(predicateGroupCount));
    }
    long target = nextWrite <= 0 ? 0 : nonNegativeLong(random, nextWrite);
    if (choice == 2) {
      return KvPredicateOperation.predicateEquals(target);
    }
    if (choice == 3) {
      return KvPredicateOperation.predicateGreaterThan(target);
    }
    return KvPredicateOperation.predicateLessThan(target);
  }

  private long chooseKey(Random random) {
    int index;
    int size = activeKeys.size();
    switch (keyDist) {
      case "uniform":
        index = random.nextInt(size);
        break;
      case "zipf":
        index = zipfIndex(random, size);
        break;
      case "hotspot":
        index = hotspotIndex(random, size);
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
        throw new IllegalStateException("unsupported key distribution: " + keyDist);
    }
    return activeKeys.get(index);
  }

  private int zipfIndex(Random random, int size) {
    int index = Arrays.binarySearch(zipfCdf, random.nextDouble());
    if (index < 0) {
      index = -index - 1;
    }
    return Math.min(index, size - 1);
  }

  private int hotspotIndex(Random random, int size) {
    int hotSize = Math.max(1, (int) Math.ceil(size * 0.2));
    if (hotSize >= size || random.nextDouble() < 0.8) {
      return random.nextInt(hotSize);
    }
    return hotSize + random.nextInt(size - hotSize);
  }

  private double[] buildZipfCdf(int size) {
    double[] cdf = new double[size];
    double normalizer = 0.0;
    for (int i = 1; i <= size; i++) {
      normalizer += 1.0 / Math.pow(i, keyDistBase);
    }
    double cumulative = 0.0;
    for (int i = 1; i <= size; i++) {
      cumulative += 1.0 / Math.pow(i, keyDistBase);
      cdf[i - 1] = cumulative / normalizer;
    }
    cdf[size - 1] = 1.0;
    return cdf;
  }

  private void rotateKey(long key) {
    int count = writesPerKey.getOrDefault(key, 0) + 1;
    if (count < maxWritesPerKey) {
      writesPerKey.put(key, count);
      return;
    }
    int index = activeKeys.indexOf(key);
    if (index >= 0) {
      activeKeys.set(index, nextKey);
      writesPerKey.remove(key);
      writesPerKey.put(nextKey, 0);
      nextKey++;
    }
  }

  private static long nonNegativeLong(Random random, long bound) {
    long value = random.nextLong();
    if (value == Long.MIN_VALUE) {
      value = 0;
    }
    value = Math.abs(value);
    return value % bound;
  }

  private static double keyDistScale(double keyDistBase, int keyCount) {
    return ((Math.pow(keyDistBase, keyCount) - 1.0) * keyDistBase) / (keyDistBase - 1.0);
  }

  private static int getInt(XMLConfiguration xml, String key, int defaultValue) {
    return xml != null && xml.containsKey(key) ? xml.getInt(key) : defaultValue;
  }

  private static long getLong(XMLConfiguration xml, String key, long defaultValue) {
    return xml != null && xml.containsKey(key) ? xml.getLong(key) : defaultValue;
  }

  private static double getDouble(XMLConfiguration xml, String key, double defaultValue) {
    return xml != null && xml.containsKey(key) ? xml.getDouble(key) : defaultValue;
  }

  private static String getString(XMLConfiguration xml, String key, String defaultValue) {
    return xml != null && xml.containsKey(key) ? xml.getString(key) : defaultValue;
  }

  @Override
  protected List<Worker<? extends BenchmarkModule>> makeWorkersImpl() {
    List<Worker<? extends BenchmarkModule>> workers = new ArrayList<>();
    for (int i = 0; i < workConf.getTerminals(); ++i) {
      workers.add(new KvPredicateWorker(this, i));
    }
    return workers;
  }

  @Override
  protected Loader<KvPredicateBenchmark> makeLoaderImpl() {
    return new KvPredicateLoader(this);
  }

  @Override
  protected Package getProcedurePackageImpl() {
    return Txn.class.getPackage();
  }
}
