package com.oltpbenchmark.benchmarks.kvpredicate;

import java.util.concurrent.CyclicBarrier;

public final class KvPredicateOperation {
  public enum Kind {
    READ,
    WRITE,
    PREDICATE,
    SLEEP,
    BARRIER
  }

  public enum PredicateKind {
    TRUE,
    EQUALS,
    MOD,
    GREATER_THAN,
    LESS_THAN
  }

  public final Kind kind;
  public final long key;
  public final long value;
  public final PredicateKind predicateKind;
  public final long modulus;
  public final long target;
  public final long sleepMillis;
  public final CyclicBarrier barrier;
  public final long barrierTimeoutMillis;

  private KvPredicateOperation(
      Kind kind,
      long key,
      long value,
      PredicateKind predicateKind,
      long modulus,
      long target,
      long sleepMillis,
      CyclicBarrier barrier,
      long barrierTimeoutMillis) {
    this.kind = kind;
    this.key = key;
    this.value = value;
    this.predicateKind = predicateKind;
    this.modulus = modulus;
    this.target = target;
    this.sleepMillis = sleepMillis;
    this.barrier = barrier;
    this.barrierTimeoutMillis = barrierTimeoutMillis;
  }

  public static KvPredicateOperation read(long key) {
    return new KvPredicateOperation(Kind.READ, key, 0, null, 0, 0, 0, null, 0);
  }

  public static KvPredicateOperation write(long key, long value) {
    return new KvPredicateOperation(Kind.WRITE, key, value, null, 0, 0, 0, null, 0);
  }

  public static KvPredicateOperation predicateTrue() {
    return new KvPredicateOperation(Kind.PREDICATE, 0, 0, PredicateKind.TRUE, 0, 0, 0, null, 0);
  }

  public static KvPredicateOperation predicateEquals(long value) {
    return new KvPredicateOperation(Kind.PREDICATE, 0, 0, PredicateKind.EQUALS, 0, value, 0, null, 0);
  }

  public static KvPredicateOperation predicateMod(long modulus, long target) {
    return new KvPredicateOperation(Kind.PREDICATE, 0, 0, PredicateKind.MOD, modulus, target, 0, null, 0);
  }

  public static KvPredicateOperation predicateGreaterThan(long value) {
    return new KvPredicateOperation(Kind.PREDICATE, 0, 0, PredicateKind.GREATER_THAN, 0, value, 0, null, 0);
  }

  public static KvPredicateOperation predicateLessThan(long value) {
    return new KvPredicateOperation(Kind.PREDICATE, 0, 0, PredicateKind.LESS_THAN, 0, value, 0, null, 0);
  }

  public static KvPredicateOperation sleep(long sleepMillis) {
    return new KvPredicateOperation(Kind.SLEEP, 0, 0, null, 0, 0, sleepMillis, null, 0);
  }

  public static KvPredicateOperation barrier(CyclicBarrier barrier, long timeoutMillis) {
    return new KvPredicateOperation(Kind.BARRIER, 0, 0, null, 0, 0, 0, barrier, timeoutMillis);
  }
}
