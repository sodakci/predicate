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

package com.oltpbenchmark.benchmarks.tpcc.procedures;

import com.oltpbenchmark.api.SQLStmt;
import com.oltpbenchmark.benchmarks.tpcc.TPCCConstants;
import com.oltpbenchmark.benchmarks.tpcc.TPCCUtil;
import com.oltpbenchmark.benchmarks.tpcc.TPCCWorker;
import com.oltpbenchmark.benchmarks.tpcc.trace.TpccTrace;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StockLevel extends TPCCProcedure {

  private static final Logger LOG = LoggerFactory.getLogger(StockLevel.class);

  public SQLStmt stockGetDistOrderIdSQL =
      new SQLStmt(
          """
        SELECT D_NEXT_O_ID
          FROM  %s
         WHERE D_W_ID = ?
           AND D_ID = ?
    """
              .formatted(TPCCConstants.TABLENAME_DISTRICT));

  public SQLStmt stockGetCountStockSQL =
      new SQLStmt(
          """
        SELECT COUNT(DISTINCT (S_I_ID)) AS STOCK_COUNT
         FROM  %s, %s
         WHERE OL_W_ID = ?
         AND OL_D_ID = ?
         AND OL_O_ID < ?
         AND OL_O_ID >= ?
         AND S_W_ID = ?
         AND S_I_ID = OL_I_ID
         AND S_QUANTITY < ?
    """
              .formatted(TPCCConstants.TABLENAME_ORDERLINE, TPCCConstants.TABLENAME_STOCK));

  // The benchmark's COUNT query is retained unchanged.  Trace mode performs
  // this companion query in the same transaction to record the actual JOIN
  // membership, which a COUNT result alone cannot identify.
  public SQLStmt stockGetMembershipSQL =
      new SQLStmt(
          """
        SELECT DISTINCT S_I_ID
         FROM  %s, %s
         WHERE OL_W_ID = ?
         AND OL_D_ID = ?
         AND OL_O_ID < ?
         AND OL_O_ID >= ?
         AND S_W_ID = ?
         AND S_I_ID = OL_I_ID
         AND S_QUANTITY < ?
         ORDER BY S_I_ID
    """
              .formatted(TPCCConstants.TABLENAME_ORDERLINE, TPCCConstants.TABLENAME_STOCK));

  // Trace-only: retain every JOIN input, including stock rows above the
  // threshold, so later conversion can model membership changes correctly.
  public SQLStmt stockGetJoinEvidenceSQL =
      new SQLStmt(
          """
        SELECT OL_W_ID, OL_D_ID, OL_O_ID, OL_NUMBER, OL_I_ID, OL_SUPPLY_W_ID,
               S_W_ID, S_I_ID, S_QUANTITY
          FROM %s, %s
         WHERE OL_W_ID = ?
           AND OL_D_ID = ?
           AND OL_O_ID < ?
           AND OL_O_ID >= ?
           AND S_W_ID = ?
           AND S_I_ID = OL_I_ID
         ORDER BY OL_O_ID, OL_NUMBER
    """
              .formatted(TPCCConstants.TABLENAME_ORDERLINE, TPCCConstants.TABLENAME_STOCK));

  public void run(
      Connection conn,
      Random gen,
      int w_id,
      int numWarehouses,
      int terminalDistrictLowerID,
      int terminalDistrictUpperID,
      TPCCWorker w)
      throws SQLException {

    int threshold = TPCCUtil.randomNumber(10, 20, gen);
    int d_id = TPCCUtil.randomNumber(terminalDistrictLowerID, terminalDistrictUpperID, gen);

    int o_id = getOrderId(conn, w_id, d_id);

    int stock_count = getStockCount(conn, w_id, threshold, d_id, o_id);

    if (LOG.isTraceEnabled()) {
      String terminalMessage =
          "\n+-------------------------- STOCK-LEVEL --------------------------+"
              + "\n Warehouse: "
              + w_id
              + "\n District:  "
              + d_id
              + "\n\n Stock Level Threshold: "
              + threshold
              + "\n Low Stock Count:       "
              + stock_count
              + "\n+-----------------------------------------------------------------+\n\n";
      LOG.trace(terminalMessage);
    }
  }

  private int getOrderId(Connection conn, int w_id, int d_id) throws SQLException {
    try (PreparedStatement stockGetDistOrderId =
        this.getPreparedStatement(conn, stockGetDistOrderIdSQL)) {
      stockGetDistOrderId.setInt(1, w_id);
      stockGetDistOrderId.setInt(2, d_id);

      try (ResultSet rs = stockGetDistOrderId.executeQuery()) {

        if (!rs.next()) {
          throw new RuntimeException("D_W_ID=" + w_id + " D_ID=" + d_id + " not found!");
        }
        int nextOrderId = rs.getInt("D_NEXT_O_ID");
        TpccTrace.pointRead(
            conn,
            TpccTrace.districtKey(w_id, d_id),
            stockGetDistOrderIdSQL.getSQL(),
            new Object[] {w_id, d_id},
            rs);
        return nextOrderId;
      }
    }
  }

  private int getStockCount(Connection conn, int w_id, int threshold, int d_id, int o_id)
      throws SQLException {
    try (PreparedStatement stockGetCountStock =
        this.getPreparedStatement(conn, stockGetCountStockSQL)) {
      stockGetCountStock.setInt(1, w_id);
      stockGetCountStock.setInt(2, d_id);
      stockGetCountStock.setInt(3, o_id);
      stockGetCountStock.setInt(4, o_id - 20);
      stockGetCountStock.setInt(5, w_id);
      stockGetCountStock.setInt(6, threshold);

      int stockCount;
      try (ResultSet rs = stockGetCountStock.executeQuery()) {
        if (!rs.next()) {
          String msg =
              String.format(
                  "Failed to get StockLevel result for COUNT query [W_ID=%d, D_ID=%d, O_ID=%d]",
                  w_id, d_id, o_id);

          throw new RuntimeException(msg);
        }

        stockCount = rs.getInt("STOCK_COUNT");
      }

      List<Integer> stockItemIds = new ArrayList<>();
      try (PreparedStatement stockGetMembership =
          this.getPreparedStatement(conn, stockGetMembershipSQL)) {
        stockGetMembership.setInt(1, w_id);
        stockGetMembership.setInt(2, d_id);
        stockGetMembership.setInt(3, o_id);
        stockGetMembership.setInt(4, o_id - 20);
        stockGetMembership.setInt(5, w_id);
        stockGetMembership.setInt(6, threshold);
        try (ResultSet rs = stockGetMembership.executeQuery()) {
          while (rs.next()) {
            stockItemIds.add(rs.getInt("S_I_ID"));
          }
        }
      }

      StringBuilder joinEvidence = new StringBuilder("[");
      try (PreparedStatement evidence = this.getPreparedStatement(conn, stockGetJoinEvidenceSQL)) {
        evidence.setInt(1, w_id);
        evidence.setInt(2, d_id);
        evidence.setInt(3, o_id);
        evidence.setInt(4, o_id - 20);
        evidence.setInt(5, w_id);
        try (ResultSet rs = evidence.executeQuery()) {
          while (rs.next()) {
            if (joinEvidence.length() > 1) joinEvidence.append(',');
            joinEvidence.append(
                String.format(
                    "{\"ol_w_id\":%d,\"ol_d_id\":%d,\"ol_o_id\":%d,\"ol_number\":%d,\"ol_i_id\":%d,\"ol_supply_w_id\":%d,\"s_w_id\":%d,\"s_i_id\":%d,\"s_quantity\":%d}",
                    rs.getInt("OL_W_ID"),
                    rs.getInt("OL_D_ID"),
                    rs.getInt("OL_O_ID"),
                    rs.getInt("OL_NUMBER"),
                    rs.getInt("OL_I_ID"),
                    rs.getInt("OL_SUPPLY_W_ID"),
                    rs.getInt("S_W_ID"),
                    rs.getInt("S_I_ID"),
                    rs.getInt("S_QUANTITY")));
          }
        }
      }
      joinEvidence.append(']');

      Object[] parameters = new Object[] {w_id, d_id, o_id, o_id - 20, w_id, threshold};
      TpccTrace.stockLevelRead(
          conn,
          w_id,
          d_id,
          o_id,
          threshold,
          stockItemIds,
          joinEvidence.toString(),
          stockCount,
          stockGetCountStockSQL.getSQL()
              + "\n-- trace membership\n"
              + stockGetMembershipSQL.getSQL(),
          parameters);
      return stockCount;
    }
  }
}
