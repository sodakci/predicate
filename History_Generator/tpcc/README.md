# TPC-C 历史生成管线改造说明

本文说明 `tpcc/` 这条管线如何在保留 BenchBase TPC-C 多表模型的前提下，改造成当前的 PostgreSQL 历史生成器。运行命令见上级目录 `README.md`。

当前 TPC-C 历史来自：

```text
BenchBase TPC-C
  -> PostgreSQL 原始 TPC-C 多表真实执行
  -> ser_tpcc_trace sidecar 记录事务证据
  -> raw_tpcc_trace.jsonl
  -> tpcc_trace_to_prhist.py
  -> history.prhist.jsonl / initial_state.json / manifest.json
```

当前接入的 TPC-C 事务：

- NewOrder
- Payment
- StockLevel

## 改造目标

TPC-C 改造的目标不是把 TPC-C 改成 KV workload，而是：

- 保留 BenchBase 原始 TPC-C 多表 schema 和事务逻辑。
- 在真实 PostgreSQL 事务中采集读写证据。
- 给每个物理行版本分配统一 object key 和全局唯一 version id。
- 把 committed transaction 转成统一 `hist-00000` PRHIST case。
- 把 abort/retry、SQL、参数、row JSON、LSN 等证据保留在 raw trace。

数据库中仍是标准 TPC-C 表：

```text
warehouse
district
customer
history
item
stock
oorder
new_order
order_line
```

## BenchBase 侧改造

### 1. 改造 TPCCWorker.java

文件：

```text
benchbase/src/main/java/com/oltpbenchmark/benchmarks/tpcc/TPCCWorker.java
```

改造内容：

- 在事务开始时调用 TPC-C trace begin。
- 在事务提交后标记 committed。
- 在事务 abort/retry 后记录 abort 证据。
- 维护 BenchBase worker/session、session 内顺序号、事务类型和 PostgreSQL xid 的对应关系。

这一步让 raw trace 能恢复：

```text
session
session_seq
txn/xid
txn_type
begin/commit observation
abort/retry attempt
```

### 2. 新增 TpccTrace.java

文件：

```text
benchbase/src/main/java/com/oltpbenchmark/benchmarks/tpcc/trace/TpccTrace.java
```

它是 Java 侧 trace API，通过 JDBC 调用 PostgreSQL 中的 `ser_tpcc_trace.*` 函数。

trace 开关：

```text
-Dser.tpcc.trace=true
```

create/load 阶段不打开该开关，正式 execute 阶段才打开，因此加载初始数据不会混入事务历史。

### 3. 改造 NewOrder.java

文件：

```text
benchbase/src/main/java/com/oltpbenchmark/benchmarks/tpcc/procedures/NewOrder.java
```

NewOrder 真实执行多表读写，包括：

- 读取 warehouse、district、customer。
- 更新 district 的 `d_next_o_id`。
- 读取 item、stock。
- 更新 stock。
- 插入 oorder。
- 插入 new_order。
- 插入多条 order_line。

改造方式：

- 每个 SELECT 返回当前行后，Java 调用 `TpccTrace` 显式记录点读。
- INSERT/UPDATE 不由 Java 手工记录，而由 PostgreSQL trigger 捕获。
- 所有记录仍处在同一个 PostgreSQL transaction 内；事务回滚时业务 trace op 也会回滚。

转换后的 PRHIST 操作流类似：

```text
r(warehouse:w=1, version=...)
r(district:w=1:d=3, version=...)
w(district:w=1:d=3, version=...)
r(customer:w=1:d=3:c=42, version=...)
r(item:i=22567, version=...)
r(stock:w=1:i=22567, version=...)
w(stock:w=1:i=22567, version=...)
w(oorder:w=1:d=3:o=3002, version=...)
w(new_order:w=1:d=3:o=3002, version=...)
w(order_line:w=1:d=3:o=3002:n=1, version=...)
```

### 4. 改造 Payment.java

文件：

```text
benchbase/src/main/java/com/oltpbenchmark/benchmarks/tpcc/procedures/Payment.java
```

Payment 涉及：

- warehouse 读写。
- district 读写。
- customer 读写。
- history 插入。

改造方式与 NewOrder 一致：

- SELECT 后由 JDBC trace hook 记录读版本。
- UPDATE/INSERT 由 trigger 记录写版本。
- `history` 表原始 TPC-C DDL 没有主键，trace 安装脚本增加采集专用 surrogate key，用于生成稳定 object key。

### 5. 改造 StockLevel.java

文件：

```text
benchbase/src/main/java/com/oltpbenchmark/benchmarks/tpcc/procedures/StockLevel.java
```

StockLevel 原始核心是聚合：

```sql
SELECT COUNT(DISTINCT s.s_i_id)
FROM order_line AS ol
JOIN stock AS s
  ON s.s_w_id = :warehouse_id
 AND s.s_i_id = ol.ol_i_id
WHERE ol.ol_w_id = :warehouse_id
  AND ol.ol_d_id = :district_id
  AND ol.ol_o_id >= :next_order_id - 20
  AND ol.ol_o_id <  :next_order_id
  AND s.s_quantity < :threshold;
```

只保存 COUNT 不足以构造谓词读历史，因为 verifier 需要知道哪些 item/row 进入了结果集合。改造后 trace mode 会额外执行等价 membership 查询，保存：

- warehouse id
- district id
- order id window
- threshold
- aggregate count
- `DISTINCT s_i_id` membership
- membership 对应的 read versions

因此当前 raw trace 保留了 StockLevel 的真实多表谓词证据。

## PostgreSQL Trace 侧改造

### 1. 安装脚本

文件：

```text
tpcc/sql/01_install_tpcc_trace.sql
```

创建 schema：

```text
ser_tpcc_trace
```

主要能力：

- 创建事务、操作、版本、初始状态、abort 表。
- 为 TPC-C 物理表安装 INSERT/UPDATE/DELETE trigger。
- 为 Java SELECT trace 提供 receiver 函数。
- 创建 object key 映射函数。
- 支持 `snapshot_initial_state()`。

### 2. Object Key 映射

每个 TPC-C 物理行会被映射成统一字符串 key：

```text
warehouse(w_id)
  -> warehouse:w=<w_id>

district(d_w_id,d_id)
  -> district:w=<d_w_id>:d=<d_id>

customer(c_w_id,c_d_id,c_id)
  -> customer:w=<w_id>:d=<d_id>:c=<c_id>

item(i_id)
  -> item:i=<i_id>

stock(s_w_id,s_i_id)
  -> stock:w=<w_id>:i=<i_id>

oorder(o_w_id,o_d_id,o_id)
  -> oorder:w=<w_id>:d=<d_id>:o=<o_id>

new_order(no_w_id,no_d_id,no_o_id)
  -> new_order:w=<w_id>:d=<d_id>:o=<o_id>

order_line(ol_w_id,ol_d_id,ol_o_id,ol_number)
  -> order_line:w=<w_id>:d=<d_id>:o=<o_id>:n=<number>

history
  -> history:id=<ser_tpcc_history_id>
```

`key` 表示哪一行，`value` 表示这一行的哪个版本。

### 3. Version Id

trace schema 使用全局 sequence 分配版本：

```text
ser_tpcc_trace.version_seq
```

因此：

```text
key   = TPC-C 物理行身份
value = 全局唯一版本身份
row   = 该版本完整业务列 JSON
```

`value` 不是库存数量、订单号或 customer id；这些业务字段保留在 raw `row` / `old_row` / `new_row` 中。

### 4. 写捕获

INSERT/UPDATE/DELETE 由 PostgreSQL trigger 记录：

- old row
- new row
- table name
- primary key
- object key
- before value
- new value
- WAL LSN
- xid
- op_index

当前 PRHIST converter 主要转换 NewOrder、Payment、StockLevel 涉及的 INSERT/UPDATE。Delivery 包含 DELETE，虽然 raw trace 可捕获 DELETE，但 PRHIST 还没有明确 tombstone 表达，因此当前不把 Delivery 纳入转换范围。

### 5. 读捕获

SELECT 没有 trigger，所以 NewOrder、Payment、StockLevel 在 Java 侧对关键 SELECT 添加 `TpccTrace` hook。

读捕获保存：

- SQL 名称或语义类型。
- 绑定参数。
- raw JDBC rows。
- 当时可见版本。
- 所属 transaction xid 和 op_index。

### 6. 初始状态

load 完成后执行：

```sql
SELECT ser_tpcc_trace.snapshot_initial_state();
```

它把当前所有相关 TPC-C 表行保存到 `initial_version`。converter 后续把这些记录写入 `initial_state.json`。

## 导出 Raw Evidence

导出脚本：

```text
tpcc/sql/02_export_tpcc_trace.sql
```

输出：

```text
raw_tpcc_trace.jsonl
```

包含：

- 初始版本。
- committed transaction。
- abort/retry attempt。
- 每个操作的 SQL/参数/raw result。
- old/new row JSON。
- LSN、xid、op_index 等数据库证据。

## 转换成 PRHIST

转换器：

```text
tpcc/tpcc_trace_to_prhist.py
```

转换规则：

- `record_type = initial` 写入 `initial_state.json`。
- `record_type = txn` 且 committed 的事务写入 `history.prhist.jsonl`。
- `record_type = abort` 不进入 history，只保留在 raw trace 和 manifest 统计中。
- 按 `op_index` 保持事务内部操作顺序。
- 点读转成 `r`。
- 写转成 `w`。
- StockLevel 转成 SQL-shaped relational predicate。

写操作示例：

```json
{"type":"w","key":"stock:w=1:i=22567","value":599134,"row":{"s_quantity":8}}
```

点读示例：

```json
{"type":"r","key":"district:w=1:d=3","value":2001}
```

StockLevel 谓词读会表达为：

```text
order_line JOIN stock
WHERE warehouse/district/order window/stock threshold 条件成立
```

并在 `result.inputs` 中记录该谓词读实际观察到的版本集合。

## 得到当前历史的完整链路

入口脚本：

```text
tpcc/run_tpcc_trace.sh
```

内部流程：

```text
可选 BenchBase create/load
  -> 01_install_tpcc_trace.sql
  -> snapshot_initial_state()
  -> java -Dser.tpcc.trace=true -jar benchbase.jar -b tpcc ...
  -> 02_export_tpcc_trace.sql
  -> tpcc_trace_to_prhist.py
  -> audit_tpcc_prhist.py
```

最终得到：

```text
PolySIHistories/tpcc/<case>/hist-00000/
  initial_state.json
  history.prhist.jsonl
  manifest.json
  raw_tpcc_trace.jsonl
```

## 当前边界

当前 TPC-C 历史保留了多表关系谓词证据，但当前 `SER/ser-result-detector` 的 `PredicateHistoryLoader` 只支持 KV value 谓词：

```text
TRUE
value = n
value % m = r
value > n
value < n
```

因此 TPC-C case 现在主要用于：

- 保存真实 PostgreSQL TPC-C 执行证据。
- 检查 trace/converter 是否能正确生成 PRHIST case。
- 为后续支持关系谓词的 detector 提供输入。

不能把当前 SER 对 TPC-C StockLevel 的结果当作完整可串行化判定。
