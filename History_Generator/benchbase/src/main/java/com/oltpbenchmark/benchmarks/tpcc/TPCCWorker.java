/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.oltpbenchmark.benchmarks.tpcc;

import com.oltpbenchmark.api.Procedure.UserAbortException;
import com.oltpbenchmark.api.TransactionType;
import com.oltpbenchmark.api.Worker;
import com.oltpbenchmark.benchmarks.tpcc.procedures.TPCCProcedure;
import com.oltpbenchmark.benchmarks.tpcc.trace.TpccTrace;
import com.oltpbenchmark.types.TransactionStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TPCCWorker extends Worker<TPCCBenchmark> {

  private static final Logger LOG = LoggerFactory.getLogger(TPCCWorker.class);

  private final int terminalWarehouseID;

  /** Forms a range [lower, upper] (inclusive). */
  private final int terminalDistrictLowerID;

  private final int terminalDistrictUpperID;
  private final Random gen = new Random();

  private final int numWarehouses;
  private long traceSessionSequence;
  private Long activeTraceXid;
  private long activeTraceSessionSequence;
  private String activeTraceTransactionType;

  public TPCCWorker(
      TPCCBenchmark benchmarkModule,
      int id,
      int terminalWarehouseID,
      int terminalDistrictLowerID,
      int terminalDistrictUpperID,
      int numWarehouses) {
    super(benchmarkModule, id);

    this.terminalWarehouseID = terminalWarehouseID;
    this.terminalDistrictLowerID = terminalDistrictLowerID;
    this.terminalDistrictUpperID = terminalDistrictUpperID;

    this.numWarehouses = numWarehouses;
  }

  /** Executes a single TPCC transaction of type transactionType. */
  @Override
  protected TransactionStatus executeWork(Connection conn, TransactionType nextTransaction)
      throws UserAbortException, SQLException {
    try {
      TPCCProcedure proc = (TPCCProcedure) this.getProcedure(nextTransaction.getProcedureClass());
      // This call is part of the same JDBC transaction as the procedure.  If
      // BenchBase later rolls it back, PostgreSQL rolls back every trace row as
      // well; persisted trace rows are therefore committed executions only.
      activeTraceSessionSequence = ++traceSessionSequence;
      activeTraceTransactionType = nextTransaction.getName();
      activeTraceXid =
          TpccTrace.begin(conn, getId(), activeTraceSessionSequence, activeTraceTransactionType);
      proc.run(
          conn,
          gen,
          terminalWarehouseID,
          numWarehouses,
          terminalDistrictLowerID,
          terminalDistrictUpperID,
          this);
    } catch (ClassCastException ex) {
      // fail gracefully
      LOG.error("We have been invoked with an INVALID transactionType?!", ex);
      throw new RuntimeException("Bad transaction type = " + nextTransaction);
    }
    return (TransactionStatus.SUCCESS);
  }

  @Override
  protected void afterTransactionCommit(Connection conn, TransactionType transactionType) {
    if (activeTraceXid == null) {
      return;
    }
    try {
      TpccTrace.markCommitted(conn, activeTraceXid);
    } catch (SQLException ex) {
      // Do not retroactively turn a committed TPC-C transaction into a failed
      // workload request.  The atomic in-transaction trace is still present;
      // this optional marker merely records the client-observed commit time.
      LOG.error("Failed to mark committed TPC-C trace xid={}", activeTraceXid, ex);
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
      TpccTrace.recordAbort(
          conn,
          activeTraceXid,
          getId(),
          activeTraceSessionSequence,
          activeTraceTransactionType,
          status.name(),
          "BenchBase transaction attempt rolled back or failed");
    } catch (SQLException ex) {
      LOG.warn("Failed to persist aborted TPC-C trace xid={}", activeTraceXid, ex);
    } finally {
      activeTraceXid = null;
    }
  }

  @Override
  protected long getPreExecutionWaitInMillis(TransactionType type) {
    // TPC-C 5.2.5.2: For keying times for each type of transaction.
    return type.getPreExecutionWait();
  }

  @Override
  protected long getPostExecutionWaitInMillis(TransactionType type) {
    // TPC-C 5.2.5.4: For think times for each type of transaction.
    long mean = type.getPostExecutionWait();

    float c = this.getBenchmark().rng().nextFloat();
    long thinkTime = (long) (-1 * Math.log(c) * mean);
    if (thinkTime > 10 * mean) {
      thinkTime = 10 * mean;
    }

    return thinkTime;
  }
}
