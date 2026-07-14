# SI 项目介绍

本文档面向第一次接触本项目的人，说明 SI detector 的整体框架、核心流程、主要模块和关键文件。日常运行命令见 `SI/README.md`。

## 项目定位

SI detector 是一个谓词感知的快照隔离结果检测器。它的输入是一段事务历史，输出是：

```text
[[[[ ACCEPT ]]]]
```

或：

```text
[[[[ REJECT ]]]]
```

含义如下：

- `ACCEPT`：存在某个合法的 SI 执行解释，可以解释所有点读、写入和谓词读。
- `REJECT`：不存在这样的 SI 执行解释，历史在当前模型下不满足快照隔离。

SER detector 检查是否存在严格事务串行顺序。SI detector 检查的是快照隔离解释，它允许一些可串行化不允许的并发形态，例如典型 write skew。当前实现的核心判定方式是：选择每个 key 上的 WW 顺序，生成普通读和谓词读带来的依赖，再检查 induced SI graph 是否无环。

## 总体框架

```text
PRHIST history
  -> PredicateHistoryLoader
  -> History / Session / Transaction / Event
  -> KnownGraph
       fixed SO / WR edges
       read-from index
       write index
       predicate observations
  -> SIVerifier
       internal consistency
       unresolved WW choices
       pruning
       optional coalescing
  -> SISolverInduced
       SAT literals over WW order
       dependency graph
       anti-dependency graph
       predicate frontier constraints
       induced SI acyclicity
  -> ACCEPT / REJECT
```

项目核心思想是：不要先枚举所有可能的快照隔离执行，而是把同 key 写写顺序和谓词可见性编码成 SAT literal。MonoSAT 负责维护依赖图和 induced graph 的可达性/无环性；如果公式可满足，就说明存在一个与历史观测一致的 SI 解释。

## 输入模型

当前活跃输入类型是 `PRHIST`。命令行入口 `audit` 默认使用它：

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  audit /path/to/hist-00000
```

`hist-00000` 至少包含：

```text
initial_state.json
history.prhist.jsonl
manifest.json
```

Java loader 实际读取前两个文件。`manifest.json` 是生成器和实验脚本使用的元数据。

### 初始版本

`initial_state.json` 是 JSON 数组：

```json
[
  {"key": "kv:0", "value": 0},
  {"key": "kv:1", "value": 1}
]
```

loader 会把它变成内部初始事务：

```text
session = -1
txn     = -1
```

这个 bottom transaction 写出每个 key 的初始版本。后续所有读都可以像读取普通事务写一样读取它。

### 事务

`history.prhist.jsonl` 每行是一个事务：

```json
{"session":0,"session_seq":1,"txn":1001,"status":"commit","ops":[{"type":"w","key":"kv:0","value":10}]}
```

当前 loader 要求：

- `status` 必须是 `commit`。
- `session` 和 `txn` 是整数。
- `ops` 是操作数组。
- 事务 id 全局唯一。

### 操作

点读：

```json
{"type":"r","key":"kv:0","value":10}
```

写：

```json
{"type":"w","key":"kv:0","value":11}
```

谓词读：

```json
{
  "type": "pr",
  "query": {
    "select": {"distinct": false, "columns": ["k", "value"]},
    "from": {"relation": "kv"},
    "where": ["value < 10"]
  },
  "result": {
    "values": [{"k": "0", "value": 0}],
    "inputs": [{"key": "kv:0", "value": 0}]
  }
}
```

当前 `PredicateHistoryLoader` 支持一条 `where` 条件，条件作用于编码后的 `value`：

```text
TRUE
value = n
value % m = r
value > n
value < n
```

`result.inputs` 是本次谓词读实际观察到的版本集合。检测器用这些 `(key,value)` 找到对应 source write，并在 SAT 中约束每个 key 的快照 frontier 为什么进入或没有进入谓词结果。

## 核心概念

### Session Order

同一个 client/session 中的事务按出现顺序形成 session order，简称 SO。SO 是强制依赖边：

```text
T1 --SO--> T2
```

### Write-Read

如果事务 `T2` 点读了 key `x` 的版本，而这个版本由 `T1` 写出，则形成 WR：

```text
T1 --WR(x)--> T2
```

WR 也是强制依赖边。

### Write-Write Choice

同一个 key 上的两个不同事务写入，如果历史本身没有决定谁先谁后，检测器会生成二选一的 WW choice：

```text
T1 --WW(x)--> T2
或
T2 --WW(x)--> T1
```

这些 choice 会被编码成 SAT 分支。选择某个方向后，还会激活对应的 RW implications。

### Read-Write Anti-Dependency

普通点读的 RW 可以从 WR 和 WW 关系推出：

```text
Tsource --WR(x)--> Treader
Tsource --WW(x)--> Twriter
--------------------------------
Treader --RW(x)--> Twriter
```

直觉是：如果 reader 读到的是 source 写出的版本，而另一个 writer 写在 source 后面，那么 reader 的快照必须早于这个后续 writer，否则它应该看到后续版本。

在 SI 判定中，RW 是 anti-dependency。它不会像 SER 那样直接要求所有依赖合成一个严格全序，而是参与 induced graph 的构造。

### Predicate Frontier

谓词读不能只看结果集合中的 key。对于谓词读事务 `S` 和每个 key `x`，检测器定义：

```text
frontier_x(S) = S 的快照中对 x 可见的最新写
```

谓词读约束检查这个 frontier 的行是否满足谓词，以及是否与 `result.inputs` 一致。

谓词相关依赖可理解为：

```text
PR_WR(T, S, x)
    T 是 frontier_x(S) 的写事务。

PR_RW(S, U, x)
    U 在 frontier_x(S) 后面又写了 x，
    且 U 的写会改变该 key 在谓词结果中的成员关系或结果值。
```

当前主求解路径不会把所有 PR_WR / PR_RW 预先物化成固定边再求解，而是在 `SISolverInduced` 中直接编码 predicate frontier。`--compare-derived-predicate-edges` 只用于诊断对比。

### Dependency Graph

SI solver 内部维护一个 dependency graph。它包含：

```text
SO
WR
WW
PR_WR
```

这些边表示一个事务必须依赖另一个事务的结果或写入顺序。

### Anti-Dependency Graph

anti-dependency graph 包含：

```text
RW
PR_RW
```

这些边表示某个读或谓词读的快照必须早于后续写。

### Induced SI Graph

`SISolverInduced` 将 dependency edge 放入 induced graph，并对每个形如：

```text
A --dep--> B --anti-dep--> C
```

加入 induced edge：

```text
A --> C
```

最后要求 induced graph 无环。这个条件是当前 SI 判定的核心 SAT/MonoSAT 约束。

## 核心流程

### 1. 命令行解析

文件：

```text
src/main/java/Main.java
```

`picocli` 定义了三个子命令：

- `audit`：验证历史并输出 ACCEPT/REJECT。
- `stat`：打印历史规模。
- `dump`：打印解析后的事务和事件。

`audit` 会设置 pruning、coalescing、DOT 输出、诊断参数，然后创建 `SIVerifier`。

### 2. 加载 PRHIST

文件：

```text
src/main/java/history/loaders/PredicateHistoryLoader.java
```

职责：

- 识别输入是目录还是 `history.prhist.jsonl` 文件。
- 加载相邻的 `initial_state.json`。
- 创建内部 bottom transaction。
- 把 JSONL 事务转换为 `History`、`Session`、`Transaction`、`Event`。
- 校验 commit status、操作字段、谓词格式和 value 类型。

紧凑历史如果没有显式 source id，检测器通过唯一 `(key,value)` 写版本解析读来源。因此生成器必须保证写版本唯一。

### 3. 内部历史结构

关键文件：

```text
src/main/java/history/History.java
src/main/java/history/Session.java
src/main/java/history/Transaction.java
src/main/java/history/Event.java
```

内部对象关系：

```text
History
  -> Session
     -> Transaction
        -> Event
```

`Event` 有三类：

- `READ`
- `WRITE`
- `PREDICATE_READ`

`PREDICATE_READ` 不绑定单个 key，而是保存一个谓词求值函数和本次观察到的结果版本集合。

### 4. 构造 KnownGraph

文件：

```text
src/main/java/graph/KnownGraph.java
```

`KnownGraph` 做四件事：

1. 为每个事务创建图节点。
2. 从 session 内顺序生成 SO 边。
3. 根据点读解析 source write，生成 WR 边。
4. 收集所有写版本和所有 predicate observation。

内部维护的主要索引：

```text
readFrom
    记录 WR 来源。

knownGraphA
    放 SO、WR、WW、PR_WR 等 dependency 边。

knownGraphB
    放 RW、PR_RW 等 anti-dependency 边。

writesById
    通过 write_id 查写来源。

writesByKeyValue
    通过 (key,value) 查写来源。

allWrites
    所有写版本。

predicateObservations
    每个谓词读及其结果版本来源。
```

当前紧凑 PRHIST 主要走 `(key,value)` 唯一解析路径；`write_id/source_write_id` 相关字段保留给更显式的格式扩展。

### 5. 生成 SI 约束

文件：

```text
src/main/java/verifier/SIVerifier.java
src/main/java/verifier/SIConstraint.java
src/main/java/verifier/SIEdge.java
```

主要步骤：

1. `Utils.verifyInternalConsistency(history)` 检查内部一致性。
2. 创建 `KnownGraph`。
3. `generateConstraintsSI` 生成未定 WW choice 和对应 RW implications。
4. `Pruning.pruneConstraints` 尝试把会立即造成 induced cycle 的分支剪掉。
5. 创建 `SISolverInduced`。
6. 求解 SAT。
7. 若 UNSAT，输出冲突诊断和可选 cycle witness。

这里的 `SIConstraint` 表示一次写写顺序二选一：

```text
writeTransaction1 before writeTransaction2
或
writeTransaction2 before writeTransaction1
```

每个方向都带有若干被激活的 `WW` 和 `RW` 边。

### 6. Pruning

文件：

```text
src/main/java/verifier/Pruning.java
```

pruning 的目标是提前处理明显被迫的 WW choice。如果某个 choice 的反方向会让当前 induced SI graph 立刻成环，则可以直接选择另一个方向。这样能减少进入 SAT 的待定分支数量。

默认启用 pruning。命令行可以用：

```text
--no-pruning
```

关闭它做对比。

### 7. SAT 求解

文件：

```text
src/main/java/verifier/SISolverInduced.java
```

求解器内部维护两个 MonoSAT graph：

```text
depGraph
    用于表达 SO/WR/WW/PR_WR 的可达性。

inducedGraph
    用于表达 SI 判定需要检查无环的 induced graph。
```

核心编码步骤：

1. `encodeKnownEdges`：把已知 SO/WR 和 pruning 后固定的 WW/RW 边放入图中。
2. `encodeWwChoices`：为剩余每个 WW choice 创建 SAT literal，并注册该 key 上的写写顺序。
3. `encodePredicateConstraints`：为每个谓词读编码 per-key frontier 约束。
4. `encodeInducedComposition`：把 `dep ; anti-dep` 组合成 induced edge。
5. `solver.assertTrue(inducedGraph.acyclic())`：要求 induced graph 无环。
6. `solver.solve()`：返回 ACCEPT/REJECT。

### 8. 谓词约束

谓词约束分两类处理：

```text
key 出现在 result.inputs 中
    result 中的 source write 必须是该 key 的 snapshot frontier。

key 没有出现在 result.inputs 中
    要么没有可见写，要么最新可见写不满足谓词。
```

对于 result 中的 key，solver 会保证：

- source write 的行确实满足 predicate。
- source write 对 reader 可见。
- source write 后面的同 key 写如果可见，会导致矛盾。
- 可能改变谓词结果的后续写会生成受 guard 控制的 PR_RW anti-dependency。

对于 result 外的 key，solver 会在“没有可见写”和“某个不满足 predicate 的最新可见写”之间选择一个可满足 frontier，并排除满足 predicate 的 frontier。

## 关键文件

### Main

```text
src/main/java/Main.java
```

命令行入口。负责解析 `audit`、`stat`、`dump` 子命令。

### PredicateHistoryLoader

```text
src/main/java/history/loaders/PredicateHistoryLoader.java
```

PRHIST loader。负责读取 `initial_state.json` 和 `history.prhist.jsonl`，并把 JSON 操作转换为内部事件。

### History Model

```text
src/main/java/history/History.java
src/main/java/history/Session.java
src/main/java/history/Transaction.java
src/main/java/history/Event.java
```

内部历史对象模型。

### KnownGraph

```text
src/main/java/graph/KnownGraph.java
```

构造 SO/WR 已知边、读来源索引、写版本索引和谓词观察集合。

### SIVerifier

```text
src/main/java/verifier/SIVerifier.java
```

验证流程总控。负责 internal check、生成约束、调用 pruning、调用 solver、输出诊断。

### SISolverInduced

```text
src/main/java/verifier/SISolverInduced.java
```

MonoSAT 求解器封装。负责 WW literal、predicate frontier、dep graph、anti-dep composition 和 induced graph acyclicity。

### Pruning

```text
src/main/java/verifier/Pruning.java
```

约束剪枝。提前提交被 induced graph 判定强制的 WW 方向。

### Tools

```text
tools/audit-prhist.sh
tools/run_catalog_experiment.py
tools/validate_prhist_suite.py
```

用途：

- `audit-prhist.sh`：递归批量审计 `history.prhist.jsonl`。
- `run_catalog_experiment.py`：按 catalog 跑可复现实验，保存日志、CSV、summary 和机器信息。
- `validate_prhist_suite.py`：校验带 oracle 的 PRHIST suite。

### Tests

```text
src/test/java/TestPredicateHistoryLoader.java
src/test/java/TestVerifier.java
src/test/java/BlackBoxSIAuditTest.java
src/test/java/verifier/
```

覆盖 loader、基础 verifier、SI 判定、谓词集成、小历史 differential 检查和 CLI 行为。

## 正确性直觉

### Soundness

如果 solver 返回 SAT，则：

1. 所有 SO/WR 已知依赖都被放进 dependency graph。
2. 每个相关 WW choice 都选择了一个方向。
3. 普通点读的 RW 约束保证读到的是该快照中对应 key 的最新可见版本。
4. 谓词读的 frontier 约束保证结果集合与每个 key 的快照最新可见写一致。
5. induced graph 包含 dependency edges 以及 `dependency ; anti-dependency` 组合边。
6. MonoSAT 保证 induced graph 无环。

因此存在一个满足当前 SI 模型的执行解释。

### Completeness

如果真实存在某个合法 SI 解释，则可以按这个解释给所有同 key WW 顺序和谓词 frontier literal 赋值。这个赋值会满足：

- SO/WR 已知边。
- 每个 key 上的 WW 顺序。
- 普通读的 latest-visible 约束。
- 谓词读的 frontier 和结果集合约束。
- induced graph 无环约束。

因此 solver 不应返回 UNSAT。

### Pruning 和 Coalescing

pruning 只提交反方向已经立即造成 induced cycle 的 choice，因此保持可满足性。

coalescing 把同一事务对上的重复 choices 合并，因为事务级先后关系在同一个解释中必须一致。

## 当前边界

当前 detector 的稳定路径是：

```text
compact PRHIST + KV value predicates + MonoSAT induced SI solver
```

已明确的边界：

- Java loader 当前只接受 `query/result` 形态的 predicate read。
- `where` 当前只支持一条作用于 `value` 的简单条件。
- 当前紧凑格式依赖 `(key,value)` 写版本唯一性。
- TPC-C 多表 SQL-shaped predicate 需要 loader 和谓词求值扩展后才能被完整验证。
- abort/retry attempt 不进入 `history.prhist.jsonl`；它们应保留在 raw trace 或 manifest 中。
- `--compare-derived-predicate-edges` 是诊断路径，主求解不依赖其物化边。

## 新人阅读路径

建议按这个顺序理解代码：

1. `SI/README.md`：先跑通构建和单个 history audit。
2. `src/main/java/Main.java`：看命令行如何进入 verifier。
3. `src/main/java/history/loaders/PredicateHistoryLoader.java`：理解输入 JSON 如何变成内部历史。
4. `src/main/java/history/History.java` 和 `Event.java`：理解内部数据模型。
5. `src/main/java/graph/KnownGraph.java`：看 SO/WR 和 predicate observation 如何建立。
6. `src/main/java/verifier/SIVerifier.java`：看验证流程的总控。
7. `src/main/java/verifier/SISolverInduced.java`：看 SAT/induced SI 编码。
8. `src/test/java/verifier/`：用小测试理解边界情况和预期行为。
