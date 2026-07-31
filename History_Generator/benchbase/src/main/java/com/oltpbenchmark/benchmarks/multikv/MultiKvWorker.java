package com.oltpbenchmark.benchmarks.multikv;

import com.oltpbenchmark.api.Procedure.UserAbortException;
import com.oltpbenchmark.api.TransactionType;
import com.oltpbenchmark.api.Worker;
import com.oltpbenchmark.benchmarks.multikv.procedures.Txn;
import com.oltpbenchmark.benchmarks.multikv.trace.MultiKvTrace;
import com.oltpbenchmark.types.TransactionStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MultiKvWorker extends Worker<MultiKvBenchmark> {
  private static final Logger LOG = LoggerFactory.getLogger(MultiKvWorker.class);

  private final Txn procTxn;
  private long traceSessionSequence;
  private Long activeTraceXid;
  private long activeTraceSessionSequence;
  private String activeTraceTransactionType;
  private List<MultiKvOperation> activeOperations = List.of();
  private List<MultiKvOperation> pendingRetryOperations = List.of();

  public MultiKvWorker(MultiKvBenchmark benchmarkModule, int id) {
    super(benchmarkModule, id);
    this.procTxn = this.getProcedure(Txn.class);
  }

  @Override
  protected TransactionStatus executeWork(Connection conn, TransactionType nextTrans)
      throws UserAbortException, SQLException {
    List<MultiKvOperation> operations;
    if (pendingRetryOperations.isEmpty()) {
      operations = getBenchmark().nextTransaction(rng());
    } else {
      operations = pendingRetryOperations;
      pendingRetryOperations = List.of();
    }
    if (operations.isEmpty()) {
      return TransactionStatus.SUCCESS;
    }

    activeOperations = operations;
    getBenchmark().awaitAnomalyCore(operations);
    activeTraceSessionSequence = ++traceSessionSequence;
    activeTraceTransactionType = getBenchmark().traceTransactionType(operations);
    activeTraceXid =
        MultiKvTrace.begin(conn, getId(), activeTraceSessionSequence, activeTraceTransactionType);

    this.procTxn.run(conn, operations);
    return TransactionStatus.SUCCESS;
  }

  @Override
  protected void afterTransactionCommit(Connection conn, TransactionType transactionType) {
    try {
      if (activeTraceXid != null) {
        MultiKvTrace.markCommitted(conn, activeTraceXid);
      }
    } catch (SQLException ex) {
      LOG.error("Failed to mark committed multikv trace xid={}", activeTraceXid, ex);
    } finally {
      finishCommittedTransaction();
    }
  }

  @Override
  protected void afterTransactionAbort(
      Connection conn, TransactionType transactionType, TransactionStatus status) {
    try {
      if (activeTraceXid != null) {
        MultiKvTrace.recordAbort(
            conn,
            activeTraceXid,
            getId(),
            activeTraceSessionSequence,
            activeTraceTransactionType,
            status.name(),
            "BenchBase transaction attempt rolled back or failed");
      }
    } catch (SQLException ex) {
      LOG.warn("Failed to persist aborted multikv trace xid={}", activeTraceXid, ex);
    } finally {
      retainAbortedTransactionForRetry();
    }
  }

  private void finishCommittedTransaction() {
    getBenchmark().transactionCommitted(activeOperations);
    clearActiveTransaction();
  }

  private void retainAbortedTransactionForRetry() {
    pendingRetryOperations = activeOperations;
    clearActiveTransaction();
  }

  private void clearActiveTransaction() {
    activeOperations = List.of();
    activeTraceXid = null;
    activeTraceTransactionType = null;
  }
}
