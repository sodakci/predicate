package com.oltpbenchmark.benchmarks.multikv;

import java.util.concurrent.CyclicBarrier;

public final class MultiKvOperation {
  public enum Kind {
    READ,
    WRITE,
    JOIN_PREDICATE,
    SLEEP,
    BARRIER
  }

  public final Kind kind;
  public final MultiKvRow row;
  public final String itemKeyFilter;
  public final long sleepMillis;
  public final CyclicBarrier barrier;
  public final long barrierTimeoutMillis;

  private MultiKvOperation(
      Kind kind,
      MultiKvRow row,
      String itemKeyFilter,
      long sleepMillis,
      CyclicBarrier barrier,
      long barrierTimeoutMillis) {
    this.kind = kind;
    this.row = row;
    this.itemKeyFilter = itemKeyFilter;
    this.sleepMillis = sleepMillis;
    this.barrier = barrier;
    this.barrierTimeoutMillis = barrierTimeoutMillis;
  }

  public static MultiKvOperation read(MultiKvRow row) {
    return new MultiKvOperation(Kind.READ, row, null, 0, null, 0);
  }

  public static MultiKvOperation write(MultiKvRow row) {
    return new MultiKvOperation(Kind.WRITE, row, null, 0, null, 0);
  }

  public static MultiKvOperation joinOpenAvailable(String itemKeyFilter) {
    return new MultiKvOperation(Kind.JOIN_PREDICATE, null, itemKeyFilter, 0, null, 0);
  }

  public static MultiKvOperation sleep(long sleepMillis) {
    return new MultiKvOperation(Kind.SLEEP, null, null, sleepMillis, null, 0);
  }

  public static MultiKvOperation barrier(CyclicBarrier barrier, long timeoutMillis) {
    return new MultiKvOperation(Kind.BARRIER, null, null, 0, barrier, timeoutMillis);
  }
}
