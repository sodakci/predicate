package com.oltpbenchmark.benchmarks.kvpredicate;

import com.oltpbenchmark.api.Procedure.UserAbortException;
import com.oltpbenchmark.api.TransactionType;
import com.oltpbenchmark.api.Worker;
import com.oltpbenchmark.benchmarks.kvpredicate.procedures.Txn;
import com.oltpbenchmark.benchmarks.kvpredicate.trace.KvPredicateTrace;
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
  private Long activeTraceXid;
  private long activeTraceSessionSequence;
  private String activeTraceTransactionType;

  public KvPredicateWorker(KvPredicateBenchmark benchmarkModule, int id) {
    super(benchmarkModule, id);
    this.procTxn = this.getProcedure(Txn.class);
  }

  @Override
  protected TransactionStatus executeWork(Connection conn, TransactionType nextTrans)
      throws UserAbortException, SQLException {
    activeTraceSessionSequence = ++traceSessionSequence;
    activeTraceTransactionType = nextTrans.getName();
    activeTraceXid =
        KvPredicateTrace.begin(conn, getId(), activeTraceSessionSequence, activeTraceTransactionType);

    List<KvPredicateOperation> operations = getBenchmark().nextTransaction(getId(), rng());
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
    }
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
      activeTraceXid = null;
    }
  }
}
