package com.oltpbenchmark.benchmarks.kvpredicate;

import com.oltpbenchmark.api.Procedure.UserAbortException;
import com.oltpbenchmark.api.TransactionType;
import com.oltpbenchmark.api.Worker;
import com.oltpbenchmark.benchmarks.kvpredicate.procedures.Txn;
import com.oltpbenchmark.benchmarks.kvpredicate.trace.KvPredicateTrace;
import com.oltpbenchmark.types.State;
import com.oltpbenchmark.types.TransactionStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KvPredicateWorker extends Worker<KvPredicateBenchmark> {
  private static final Logger LOG = LoggerFactory.getLogger(KvPredicateWorker.class);

  private final Txn procTxn;
  private long traceSessionSequence;
  private int committedTransactions;
  private Long activeTraceXid;
  private long activeTraceSessionSequence;
  private String activeTraceTransactionType;
  private List<KvPredicateOperation> activeOperations = List.of();
  private List<KvPredicateOperation> pendingRetryOperations = List.of();

  public KvPredicateWorker(KvPredicateBenchmark benchmarkModule, int id) {
    super(benchmarkModule, id);
    this.procTxn = this.getProcedure(Txn.class);
  }

  @Override
  protected TransactionStatus executeWork(Connection conn, TransactionType nextTrans)
      throws UserAbortException, SQLException {
    List<KvPredicateOperation> operations;
    if (pendingRetryOperations.isEmpty()) {
      operations = getBenchmark().nextTransaction(getId(), rng());
    } else {
      operations = pendingRetryOperations;
      pendingRetryOperations = List.of();
    }
    activeOperations = operations;
    activeTraceSessionSequence = ++traceSessionSequence;
    activeTraceTransactionType =
        getBenchmark().traceTransactionType(nextTrans.getName(), operations);
    try {
      activeTraceXid =
          KvPredicateTrace.begin(
              conn, getId(), activeTraceSessionSequence, activeTraceTransactionType);
    } catch (SQLException ex) {
      pendingRetryOperations = activeOperations;
      activeOperations = List.of();
      throw ex;
    }
    this.procTxn.run(conn, operations);
    return TransactionStatus.SUCCESS;
  }

  @Override
  protected void afterTransactionCommit(Connection conn, TransactionType transactionType) {
    if (activeTraceXid == null) {
      return;
    }
    try {
      KvPredicateTrace.markCommitted(conn, activeTraceXid);
    } catch (SQLException ex) {
      LOG.error("Failed to mark committed kvpredicate trace xid={}", activeTraceXid, ex);
    } finally {
      activeTraceXid = null;
      activeOperations = List.of();
      committedTransactions++;
    }
  }

  @Override
  protected boolean shouldExecuteWork(TransactionType transactionType) {
    int transactionLimit = getBenchmark().getTransactionsPerSession();
    if (transactionLimit <= 0 || committedTransactions < transactionLimit) {
      return true;
    }
    waitForBenchmarkEnd();
    return false;
  }

  private void waitForBenchmarkEnd() {
    while (true) {
      State state = configuration.getWorkloadState().getGlobalState();
      if (state == State.DONE || state == State.EXIT || state == State.ERROR) {
        return;
      }
      try {
        Thread.sleep(100L);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  @Override
  protected void completePendingWork() {
    int transactionLimit = getBenchmark().getTransactionsPerSession();
    if (transactionLimit <= 0 || committedTransactions >= transactionLimit) {
      return;
    }
    TransactionType transactionType = transactionTypes.getType(Txn.class);
    LOG.info(
        "Completing kvpredicate session quota after timed phase: session={}, committed={}, target={}",
        getId(),
        committedTransactions,
        transactionLimit);
    while (committedTransactions < transactionLimit) {
      doWork(configuration.getDatabaseType(), transactionType);
    }
  }

  @Override
  protected boolean shouldExecuteWorkAfterDone(TransactionType transactionType) {
    int transactionLimit = getBenchmark().getTransactionsPerSession();
    return transactionLimit > 0 && committedTransactions < transactionLimit;
  }

  @Override
  protected void afterTransactionAbort(
      Connection conn, TransactionType transactionType, TransactionStatus status) {
    if (activeTraceXid == null) {
      return;
    }
    try {
      KvPredicateTrace.recordAbort(
          conn,
          activeTraceXid,
          getId(),
          activeTraceSessionSequence,
          activeTraceTransactionType,
          status.name(),
          "BenchBase transaction attempt rolled back or failed");
    } catch (SQLException ex) {
      LOG.warn("Failed to persist aborted kvpredicate trace xid={}", activeTraceXid, ex);
    } finally {
      pendingRetryOperations = activeOperations;
      activeOperations = List.of();
      activeTraceXid = null;
    }
  }
}
