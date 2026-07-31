package com.oltpbenchmark.benchmarks.multikv;

import org.json.JSONObject;

public final class MultiKvRow {
  public enum Table {
    USERS("users"),
    ITEMS("items"),
    ORDERS("orders");

    private final String sqlName;

    Table(String sqlName) {
      this.sqlName = sqlName;
    }

    public String sqlName() {
      return sqlName;
    }
  }

  public final Table table;
  public final String key;
  public final String valueJson;

  public MultiKvRow(Table table, String key, String valueJson) {
    this.table = table;
    this.key = key;
    this.valueJson = valueJson;
  }

  public static MultiKvRow user(String key, String region, boolean active) {
    String value =
        new JSONObject()
            .put("user_key", key)
            .put("region", region)
            .put("active", active)
            .toString();
    return new MultiKvRow(Table.USERS, key, value);
  }

  public static MultiKvRow item(String key, long stock, long price) {
    String value =
        new JSONObject().put("item_key", key).put("stock", stock).put("price", price).toString();
    return new MultiKvRow(Table.ITEMS, key, value);
  }

  public static MultiKvRow order(String key, String userKey, String itemKey, String status) {
    String value =
        new JSONObject()
            .put("order_key", key)
            .put("user_key", userKey)
            .put("item_key", itemKey)
            .put("status", status)
            .toString();
    return new MultiKvRow(Table.ORDERS, key, value);
  }

  public String canonicalKey() {
    return table.sqlName() + ":" + key;
  }
}
