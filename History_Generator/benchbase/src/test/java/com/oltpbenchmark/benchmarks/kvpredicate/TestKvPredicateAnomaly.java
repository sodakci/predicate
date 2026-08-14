package com.oltpbenchmark.benchmarks.kvpredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.oltpbenchmark.WorkloadConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.commons.configuration2.XMLConfiguration;
import org.junit.Test;

public class TestKvPredicateAnomaly {
  @Test
  public void testInjectedAndControlUseSameSeededLayout() {
    KvPredicateBenchmark injected = benchmark("injected", true, 5.0, 10, 15);
    KvPredicateBenchmark control = benchmark("control", true, 5.0, 10, 15);

    List<KvPredicateOperation> injectedLeft = injected.nextTransaction(0, new Random(1L));
    List<KvPredicateOperation> injectedRight = injected.nextTransaction(1, new Random(2L));
    List<KvPredicateOperation> controlLeft = control.nextTransaction(0, new Random(1L));
    List<KvPredicateOperation> controlRight = control.nextTransaction(1, new Random(2L));

    assertCore(injectedLeft, 0L, 1L, 3L);
    assertCore(injectedRight, 1L, 0L, 4L);
    assertCore(controlLeft, 0L, 1L, 3L);
    assertCore(controlRight, 1L, 2L, 4L);
    assertEquals(visibleKinds(injectedLeft), visibleKinds(controlLeft));
    assertEquals(visibleKinds(injectedRight), visibleKinds(controlRight));
    assertEquals(
        "Txn|kvpredicate-anomaly:write-skew:injected:left:17:true",
        injected.traceTransactionType("Txn", injectedLeft));
    assertEquals(
        "Txn|kvpredicate-anomaly:write-skew:control:right:17:true",
        control.traceTransactionType("Txn", controlRight));
  }

  @Test
  public void testBackgroundOperationsCannotTouchReservedKeys() {
    KvPredicateBenchmark predicateBenchmark = benchmark("injected", true, 100.0, 8, 4);
    predicateBenchmark.nextTransaction(0, new Random(1L));
    predicateBenchmark.nextTransaction(1, new Random(2L));
    for (KvPredicateOperation operation : predicateBenchmark.nextTransaction(0, new Random(3L))) {
      assertEquals(KvPredicateOperation.Kind.PREDICATE, operation.kind);
      assertEquals(KvPredicateOperation.PredicateKind.LESS_THAN, operation.predicateKind);
      assertEquals(0L, operation.target);
    }

    KvPredicateBenchmark pointBenchmark = benchmark("injected", true, 0.0, 8, 8);
    pointBenchmark.nextTransaction(0, new Random(1L));
    pointBenchmark.nextTransaction(1, new Random(2L));
    for (KvPredicateOperation operation : pointBenchmark.nextTransaction(0, new Random(4L))) {
      assertTrue(
          operation.kind == KvPredicateOperation.Kind.READ
              || operation.kind == KvPredicateOperation.Kind.WRITE);
      assertTrue(operation.key >= 5L);
    }
  }

  @Test
  public void testWriteSkewConfigurationValidation() {
    assertThrows(IllegalArgumentException.class, () -> benchmark("injected", true, 5.0, 5, 15));
    assertThrows(IllegalArgumentException.class, () -> benchmark("injected", true, 5.0, 6, 1));
    assertThrows(IllegalArgumentException.class, () -> benchmark("unknown", true, 5.0, 6, 15));
  }

  private static void assertCore(
      List<KvPredicateOperation> operations, long predicateTarget, long writeKey, long paddingKey) {
    List<KvPredicateOperation> visible = visibleOperations(operations);
    assertEquals(15, visible.size());
    int predicateIndex = -1;
    int writeIndex = -1;
    for (int index = 0; index < visible.size(); index++) {
      KvPredicateOperation operation = visible.get(index);
      if (operation.kind == KvPredicateOperation.Kind.PREDICATE) {
        assertEquals(-1, predicateIndex);
        predicateIndex = index;
        assertEquals(KvPredicateOperation.PredicateKind.EQUALS, operation.predicateKind);
        assertEquals(predicateTarget, operation.target);
      } else if (operation.kind == KvPredicateOperation.Kind.WRITE) {
        assertEquals(-1, writeIndex);
        writeIndex = index;
        assertEquals(writeKey, operation.key);
      } else {
        assertEquals(KvPredicateOperation.Kind.READ, operation.kind);
        assertEquals(paddingKey, operation.key);
      }
    }
    assertTrue(predicateIndex >= 0);
    assertTrue(writeIndex > predicateIndex);
  }

  private static List<KvPredicateOperation.Kind> visibleKinds(
      List<KvPredicateOperation> operations) {
    List<KvPredicateOperation.Kind> kinds = new ArrayList<>();
    for (KvPredicateOperation operation : visibleOperations(operations)) {
      kinds.add(operation.kind);
    }
    return kinds;
  }

  private static List<KvPredicateOperation> visibleOperations(
      List<KvPredicateOperation> operations) {
    List<KvPredicateOperation> visible = new ArrayList<>();
    for (KvPredicateOperation operation : operations) {
      if (operation.kind != KvPredicateOperation.Kind.BARRIER
          && operation.kind != KvPredicateOperation.Kind.SLEEP) {
        visible.add(operation);
      }
    }
    return visible;
  }

  private static KvPredicateBenchmark benchmark(
      String variant,
      boolean isolateBackground,
      double predicateRatio,
      int keyCount,
      int txnLength) {
    XMLConfiguration xml = new XMLConfiguration();
    xml.setProperty("keyCount", keyCount);
    xml.setProperty("keyDist", "uniform");
    xml.setProperty("keyDistBase", 2.0);
    xml.setProperty("minTxnLength", txnLength);
    xml.setProperty("maxTxnLength", txnLength);
    xml.setProperty("maxWritesPerKey", 256);
    xml.setProperty("predicateGroupCount", 4);
    xml.setProperty("predicateReadRatio", predicateRatio);
    xml.setProperty("kvPredicateAnomaly", "write-skew");
    xml.setProperty("kvPredicateAnomalyVariant", variant);
    xml.setProperty("kvPredicateAnomalySeed", 17L);
    xml.setProperty("kvPredicateAnomalyIsolateBackground", isolateBackground);
    xml.setProperty("kvPredicateAnomalyDelayMs", 0L);

    WorkloadConfiguration configuration = new WorkloadConfiguration();
    configuration.setBenchmarkName("kvpredicate");
    configuration.setTerminals(2);
    configuration.setIsolationMode("TRANSACTION_REPEATABLE_READ");
    configuration.setXmlConfig(xml);
    return new KvPredicateBenchmark(configuration);
  }
}
