# KV Predicate 历史生成管线改造说明

本文说明 `kv/` 这条管线如何从原始 BenchBase 改造成当前的 PostgreSQL 历史生成器。运行命令见上级目录 `README.md`。

当前 KV 历史不是由 Jepsen/Clojure 生成，而是：

```text
BenchBase kvpredicate workload
  -> PostgreSQL kv 表真实执行
  -> ser_kvpredicate_trace sidecar 记录事务证据
  -> raw_kvpredicate_trace.jsonl
  -> kvpredicate_trace_to_prhist.py
  -> history.prhist.jsonl / initial_state.json / manifest.json
```

## 改造目标

KV workload 需要生成当前 SER detector 可以直接读取的紧凑 PRHIST：

- 写入版本用全局唯一整数 `value` 表示。
- 点读记录读到的 `(key,value)`。
- 谓词读记录 SQL 形状的 predicate 和本次读到的版本集合。
- 只把 committed transaction 写入 `history.prhist.jsonl`。
- abort/retry attempt 保留在 raw trace 中。

业务表保持极简：

```sql
kv(k PRIMARY KEY, value)
```

`value` 同时承担业务值和 PRHIST version id。因为它全局唯一，detector 可以通过唯一 `(key,value)` 找到读来源。

## BenchBase 侧改造

### 1. 新增 workload 插件

新增 Java 包：

```text
benchbase/src/main/java/com/oltpbenchmark/benchmarks/kvpredicate/
```

并在 BenchBase 插件配置中注册：

```text
benchbase/config/plugin.xml
```

注册项：

```xml
<plugin name="kvpredicate">com.oltpbenchmark.benchmarks.kvpredicate.KvPredicateBenchmark</plugin>
```

因此 BenchBase 能按普通插件方式运行：

```bash
java -jar benchbase.jar -b kvpredicate -c kvpredicate_trace.xml --execute=true
```

### 2. 新增 DDL 和配置

DDL：

```text
benchbase/src/main/resources/benchmarks/kvpredicate/ddl-postgres.sql
benchbase/src/main/resources/benchmarks/kvpredicate/ddl-generic.sql
```

配置：

```text
benchbase/config/postgres/sample_kvpredicate_config.xml
kv/config/kvpredicate_trace.xml
```

关键配置项：

```xml
<isolation>TRANSACTION_SERIALIZABLE</isolation>
<keyCount>10</keyCount>
<keyDist>exponential</keyDist>
<minTxnLength>1</minTxnLength>
<maxTxnLength>4</maxTxnLength>
<predicateGroupCount>4</predicateGroupCount>
<kvPredicateAnomaly>none</kvPredicateAnomaly>
```

### 3. KvPredicateBenchmark.java

职责：

- 读取 XML 参数。
- 创建 `KvPredicateLoader` 和 `KvPredicateWorker`。
- 为每个事务生成 logical operations。
- 支持 `uniform`、`exponential`、`zipf` key 分布。
- 支持 `minTxnLength` / `maxTxnLength` 事务长度范围。
- 维护全局递增写入值，保证每次写产生唯一版本。
- 在 `kvPredicateAnomaly=write-skew` 时生成两个脚本化并发事务。

### 4. KvPredicateLoader.java

职责：

- 创建并初始化 `kv` 表。
- 初始 key 形如 `k0`、`k1`、`k2`。
- 初始 value 与 key id 对齐，例如：

```text
k0 -> 0
k1 -> 1
k2 -> 2
```

这些初始行之后会被 trace schema 快照到 `initial_state.json`。

### 5. KvPredicateWorker.java

这是 BenchBase 事务生命周期与 trace 系统的连接点。

事务开始：

```text
executeWork()
  -> KvPredicateTrace.begin(...)
  -> getBenchmark().nextTransaction(...)
  -> Txn.run(...)
```

事务提交：

```text
afterTransactionCommit()
  -> KvPredicateTrace.markCommitted(...)
```

事务 abort/retry：

```text
afterTransactionAbort()
  -> KvPredicateTrace.recordAbort(...)
```

这样 raw trace 中能同时看到 committed 事务和 abort/retry attempt。

### 6. procedures/Txn.java

`Txn.java` 执行事务内部的 logical operations：

```text
READ
  SELECT k, value FROM kv WHERE k = ?

WRITE
  INSERT INTO kv AS kv (k, value)
  VALUES (?, ?)
  ON CONFLICT (k) DO UPDATE SET value = EXCLUDED.value
  RETURNING k, value

PREDICATE
  SELECT ... WHERE TRUE
  SELECT ... WHERE value = ?
  SELECT ... WHERE MOD(value, ?) = ?
  SELECT ... WHERE value > ?
  SELECT ... WHERE value < ?

SLEEP / BARRIER
  只用于制造可控并发交错，不是数据库操作。
```

支持的谓词正好对应当前 SER loader 能解析的条件：

```text
TRUE
value = n
value % m = r
value > n
value < n
```

### 7. KvPredicateOperation.java / KvPredicateRow.java

`KvPredicateOperation` 描述事务内操作，包括 `READ`、`WRITE`、`PREDICATE`、`SLEEP`、`BARRIER`。

`KvPredicateRow` 保存谓词读结果行，主要包含 key 和 value。

### 8. trace/KvPredicateTrace.java

Java 侧 trace API。它不直接写文件，而是通过 JDBC 调用 PostgreSQL 函数：

```text
ser_kvpredicate_trace.begin_txn
ser_kvpredicate_trace.capture_point_read
ser_kvpredicate_trace.capture_missing_point_read
ser_kvpredicate_trace.capture_predicate_read
ser_kvpredicate_trace.mark_committed
ser_kvpredicate_trace.record_abort
```

trace 开关：

```java
Boolean.parseBoolean(System.getProperty("ser.kvpredicate.trace", "false"))
```

脚本只在 execute 阶段加：

```text
-Dser.kvpredicate.trace=true
```

因此 create/load 阶段不会混入历史。

## PostgreSQL Trace 侧改造

### 1. 安装脚本

文件：

```text
kv/sql/01_install_kvpredicate_trace.sql
```

创建 schema：

```text
ser_kvpredicate_trace
```

核心表：

```text
trace_txn
    记录 BenchBase 事务 begin、session、session_seq、xid、事务类型和提交观测时间。

trace_op
    记录事务内 r/w/pr 操作，包含 op_index、SQL、参数、raw result 和版本信息。

write_version
    记录每次写产生的新版本。

row_version
    维护每个 key 当前最新版本。

initial_version
    保存 workload execute 前的初始状态快照。

trace_abort
    保存 abort/retry attempt 证据。
```

### 2. 事务边界

事务开始时 Java 调：

```sql
SELECT ser_kvpredicate_trace.begin_txn(?, ?, ?)
```

`begin_txn` 会：

1. 用 `txid_current()` 取得 PostgreSQL xid。
2. 插入 `trace_txn`。
3. 设置 transaction-local GUC：

```text
ser_kvpredicate.capture = on
ser_kvpredicate.xid = 当前 xid
ser_kvpredicate.op_index = -1
```

后续 trigger 和 trace 函数都通过这些 GUC 判断当前事务是否需要捕获，以及下一个 `op_index` 是多少。

### 3. 操作顺序

所有读写捕获都会调用：

```sql
ser_kvpredicate_trace.next_op_index()
```

它从事务局部 GUC 中读取上一个 index 并加一，因此 `trace_op.op_index` 保留了事务内部 logical operation 顺序。

### 4. 写操作捕获

写通过 PostgreSQL trigger 捕获：

```sql
CREATE TRIGGER ser_kvpredicate_trace_write
AFTER INSERT OR UPDATE ON public.kv
FOR EACH ROW EXECUTE FUNCTION ser_kvpredicate_trace.capture_write();
```

`capture_write()` 做：

1. 检查 trace 是否开启。
2. 读取当前 xid。
3. 分配 op index。
4. 保存 old row / new row。
5. 分配并记录新版本。
6. 更新 `row_version`。
7. 写入 `trace_op(type='w')`。

因此只要真实数据库发生 insert/update，就由数据库侧记录写证据。

### 5. 点读捕获

`SELECT` 没有 trigger，所以 Java 在点读 SQL 返回后显式调用：

```text
KvPredicateTrace.pointRead(...)
KvPredicateTrace.missingPointRead(...)
```

PostgreSQL 函数会把 SQL、参数、raw result 和当时可见版本写入 `trace_op(type='r')`。

### 6. 谓词读捕获

谓词读由 Java 执行真实 SELECT，然后把返回行集合交给：

```text
KvPredicateTrace.predicateRead(...)
```

trace 中保存：

- 谓词类型和参数。
- SQL 与绑定参数。
- raw result rows。
- 本次读到的版本集合。

这些信息之后被 converter 转成 PRHIST 的 `pr.query` 和 `pr.result.inputs`。

## 导出 Raw Evidence

导出脚本：

```text
kv/sql/02_export_kvpredicate_trace.sql
```

输出：

```text
raw_kvpredicate_trace.jsonl
```

包含：

- `record_type = initial`
- `record_type = txn`
- `record_type = abort`

raw trace 是最接近 PostgreSQL 真实执行的证据文件，保留 SQL、参数、结果、版本和 abort/retry attempt。

## 转换成 PRHIST

转换器：

```text
kv/kvpredicate_trace_to_prhist.py
```

转换规则：

- `initial` 记录写入 `initial_state.json`。
- committed `txn` 记录写入 `history.prhist.jsonl`。
- `abort` 记录不进入 history，只统计到 manifest 并保留在 raw trace。
- 按 `op_index` 排列事务内操作。
- `w` 转成 `{"type":"w","key":"...","value":...}`。
- `r` 转成 `{"type":"r","key":"...","value":...}`。
- `pr` 转成当前 SER loader 支持的 `query/result` 格式。

谓词转换示例：

```json
{
  "type": "pr",
  "query": {
    "select": {"distinct": false, "columns": ["k", "value"]},
    "from": {"relation": "kv"},
    "where": ["value % 2 = 0"]
  },
  "result": {
    "values": [{"k": "0", "value": 0}],
    "inputs": [{"key": "kv:0", "value": 0}]
  }
}
```

converter 会校验：

- 写版本是否唯一。
- 点读引用的版本是否存在。
- 谓词读 `inputs` 引用的版本是否存在。
- 同一 session 的 `session_seq` 是否严格递增。

## 得到当前历史的完整链路

一键脚本：

```text
kv/run_kvpredicate_history_case.sh
```

内部调用：

```text
生成 runtime XML
  -> 如需则构建 benchbase.jar
  -> kv/run_kvpredicate_trace.sh --load
     -> BenchBase create/load
     -> 01_install_kvpredicate_trace.sql
     -> snapshot_initial_state()
     -> java -Dser.kvpredicate.trace=true -jar benchbase.jar ...
     -> 02_export_kvpredicate_trace.sql
     -> kvpredicate_trace_to_prhist.py
     -> audit_kvpredicate_prhist.py
```

最终得到：

```text
PolySIHistories/kvpredicate/<case>/hist-00000/
  initial_state.json
  history.prhist.jsonl
  manifest.json
  raw_kvpredicate_trace.jsonl
```

这就是当前 KV 历史的来源。
