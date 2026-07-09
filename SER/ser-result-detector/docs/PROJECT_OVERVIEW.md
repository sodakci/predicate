# SER 项目介绍

本文档面向第一次接触本项目的人，说明 SER detector 的整体框架、核心流程、主要模块和关键文件。日常运行命令见 `SER/README.md`。

## 项目定位

SER detector 是一个谓词感知的可串行化结果检测器。它的输入是一段事务历史，输出是：

```text
[[[[ ACCEPT ]]]]
```

或：

```text
[[[[ REJECT ]]]]
```

含义如下：

- `ACCEPT`：存在某个事务串行顺序，可以解释所有点读、写入和谓词读。
- `REJECT`：不存在这样的串行顺序，历史在当前模型下不可串行化。

与只检查点读写的检测器不同，本项目把谓词读也放进求解模型。谓词读不仅要求“结果里有哪些行”正确，还要求“结果外的行为什么没有出现”也能被某个串行顺序解释。

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
  -> SERVerifier
       internal consistency
       unresolved WW choices
       pruning
       optional coalescing
  -> SERSolverAR
       SAT literals over arbitration order
       WW / RW / predicate visibility constraints
       MonoSAT acyclicity
  -> ACCEPT / REJECT
```

项目核心思想是：不要先枚举所有可能串行顺序，而是把“事务 A 是否在事务 B 之前”编码成 SAT literal。MonoSAT 负责维护被选择的 arbitration graph 必须无环；如果公式可满足，就说明有一个可扩展成串行顺序的偏序。

## 输入模型

当前活跃输入类型是 `PRHIST`。命令行入口 `audit` 默认使用它：

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
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

`result.inputs` 是本次谓词读实际观察到的版本集合。检测器用这些 `(key,value)` 找到对应 source write，并在 SAT 中约束其他 key 的 frontier 为什么没有进入结果。

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

这些 choice 会被编码成 SAT XOR。选择某个方向后，还会激活对应的 RW implications。

### Read-Write

普通点读的 RW 可以从 WR 和 WW 关系推出：

```text
Tsource --WR(x)--> Treader
Tsource --WW(x)--> Twriter
--------------------------------
Treader --RW(x)--> Twriter
```

直觉是：如果 reader 读到的是 source 写出的版本，而另一个 writer 写在 source 后面，那么 reader 必须排在这个后续 writer 前面，否则它应该看到后续版本。

### Predicate Frontier

谓词读不能只看结果集合中的 key。对于谓词读事务 `S` 和每个 key `x`，检测器定义：

```text
frontier_x(S) = S 之前在 arbitration order 中对 x 的最新可见写
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

当前实现不会把所有 PR_WR / PR_RW 预先物化成固定边再求解，而是在 SAT 中直接编码谓词可见性。`--compare-derived-predicate-edges` 只用于诊断对比。

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

`audit` 会设置 pruning、coalescing、DOT 输出、诊断参数，然后创建 `SERVerifier`。

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
    放 SO、WR、WW、PR_WR 等正向依赖。

knownGraphB
    放 RW、PR_RW 等反向依赖。

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

### 5. 生成 SER 约束

文件：

```text
src/main/java/verifier/SERVerifier.java
```

主要步骤：

1. `Utils.verifyInternalConsistency(history)` 检查内部一致性。
2. 创建 `KnownGraph`。
3. `generateConstraintsSER` 生成未定 WW choice 和对应 implications。
4. `Pruning.pruneConstraints` 尝试把会立即形成环的分支剪掉。
5. 创建 `SERSolverAR`。
6. 求解 SAT。
7. 若 UNSAT，输出冲突诊断和可选 cycle witness。

这里的 `SERConstraint` 表示一次写写顺序二选一：

```text
writeTransaction1 before writeTransaction2
或
writeTransaction2 before writeTransaction1
```

每个方向都带有若干被激活的依赖边。

### 6. Pruning

文件：

```text
src/main/java/verifier/Pruning.java
```

pruning 的目标是提前处理明显被迫的 WW choice。如果某个 choice 的反方向会让当前已知图立刻成环，则可以直接选择另一个方向。这样能减少进入 SAT 的待定分支数量。

pruning 不改变可满足性：它只提交那些反方向已经不可能成立的 choice。

### 7. SAT/AR 编码

文件：

```text
src/main/java/verifier/SERSolverAR.java
```

`SERSolverAR` 把可串行化问题编码成 SAT：

- `ar(T1,T2)` literal 表示 `T1` 在 arbitration order 中早于 `T2`。
- 已知 SO/WR 边必须满足对应 AR。
- 每个未定 WW choice 用 XOR 表示二选一。
- 普通 RW 由 WR 和 WW 顺序推出。
- 谓词读通过 frontier 和 result matching 约束。
- MonoSAT graph acyclicity 保证被选择的 AR 边无环。

实现细节：

- AR graph 节点对应真实事务，不包含 bottom init transaction。
- AR literal 是按需创建的，不为所有事务对一次性生成。
- 对公式中需要比较的事务对，`ensureComparable` 会保证方向可比较。
- 无环偏序可以扩展成严格全序，因此只要 SAT 可满足，就存在合法串行解释。

### 8. MonoSAT 集成

相关位置：

```text
build.gradle
monosat/
src/main/java/verifier/SERSolverAR.java
```

Gradle 在编译 Java 前会先编译 MonoSAT：

```text
configureMonoSAT -> buildMonoSAT -> compileJava / jar
```

运行时必须提供 native library：

```text
-Djava.library.path=build/monosat
```

否则 JVM 找不到 `libmonosat.so`。

## 主要模块和关键文件

### CLI

```text
src/main/java/Main.java
```

命令行入口。定义 `audit`、`stat`、`dump`，负责把输入路径和参数传给 loader/verifier。

### History 模型

```text
src/main/java/history/History.java
src/main/java/history/Session.java
src/main/java/history/Transaction.java
src/main/java/history/Event.java
src/main/java/history/InvalidHistoryError.java
```

保存 detector 内部统一历史表示。所有后续图构建和 SAT 编码都只看这些对象。

### PRHIST Loader

```text
src/main/java/history/loaders/PredicateHistoryLoader.java
```

当前最重要的输入适配层。它定义了本 detector 现在实际接受的 PRHIST 子集。

### Graph

```text
src/main/java/graph/Edge.java
src/main/java/graph/EdgeType.java
src/main/java/graph/KnownGraph.java
src/main/java/graph/MatrixGraph.java
```

`KnownGraph` 是主路径。`MatrixGraph` 主要用于图算法或测试辅助。

### Verifier

```text
src/main/java/verifier/SERVerifier.java
src/main/java/verifier/SERSolverAR.java
src/main/java/verifier/SERConstraint.java
src/main/java/verifier/SEREdge.java
src/main/java/verifier/Pruning.java
src/main/java/verifier/Utils.java
```

这里是核心验证逻辑：

- `SERVerifier` 组织整个验证流程。
- `SERConstraint` / `SEREdge` 表达 WW choice 和条件边。
- `Pruning` 做分支剪枝。
- `SERSolverAR` 生成 SAT/MonoSAT 约束并求解。

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
src/test/java/BlackBoxSERAuditTest.java
src/test/java/verifier/
```

覆盖 loader、基础 verifier、SAT encoding、谓词集成、小历史 differential 检查和 CLI 行为。

## 正确性直觉

### Soundness

如果 solver 返回 SAT，则：

1. 所有 SO/WR 已知依赖都被放进 AR。
2. 每个相关 WW choice 都选择了一个方向。
3. 普通点读的 RW 约束保证读到的是最新可见版本。
4. 谓词读的 frontier 约束保证结果集合与每个 key 的最新可见写一致。
5. MonoSAT 保证选中的 AR graph 无环。

任何有限无环偏序都能扩展成严格全序，所以存在一个串行顺序解释该历史。

### Completeness

如果真实存在某个合法串行解释，则可以按这个串行顺序给所有 `ar(T1,T2)` literal 赋值。这个赋值会满足：

- SO/WR 已知边。
- 每个 key 上的 WW 顺序。
- 普通读的 latest-visible 约束。
- 谓词读的 frontier 和结果集合约束。
- AR 无环约束。

因此 solver 不应返回 UNSAT。

### Pruning 和 Coalescing

pruning 只提交反方向已经立即成环的 choice，因此保持可满足性。

coalescing 把同一事务对上的重复 choices 合并，因为严格事务级串行顺序里，同一事务对的先后关系必须一致。

## 当前边界

当前 detector 的稳定路径是：

```text
compact PRHIST + KV value predicates + MonoSAT AR solver
```

已明确的边界：

- Java loader 当前只接受 `query/result` 形态的 predicate read。
- `where` 当前只支持一条作用于 `value` 的简单条件。
- 当前紧凑格式依赖 `(key,value)` 写版本唯一性。
- TPC-C 多表 SQL-shaped predicate 需要 loader 和谓词求值扩展后才能被完整验证。
- abort/retry attempt 不进入 `history.prhist.jsonl`；它们应保留在 raw trace 或 manifest 中。

## 新人阅读路径

建议按这个顺序理解代码：

1. `SER/README.md`：先跑通构建和单个 history audit。
2. `src/main/java/Main.java`：看命令行如何进入 verifier。
3. `src/main/java/history/loaders/PredicateHistoryLoader.java`：理解输入 JSON 如何变成内部历史。
4. `src/main/java/history/History.java` 和 `Event.java`：理解内部数据模型。
5. `src/main/java/graph/KnownGraph.java`：看 SO/WR 和 predicate observation 如何建立。
6. `src/main/java/verifier/SERVerifier.java`：看验证流程的总控。
7. `src/main/java/verifier/SERSolverAR.java`：看 SAT/AR 编码。
8. `src/test/java/verifier/`：用小测试理解边界情况和预期行为。
