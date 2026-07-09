package com.oltpbenchmark.benchmarks.kvpredicate;

public final class KvPredicateRow {
  public final String rowKey;
  public final long key;
  public final long value;

  public KvPredicateRow(String rowKey, long key, long value) {
    this.rowKey = rowKey;
    this.key = key;
    this.value = value;
  }
}
