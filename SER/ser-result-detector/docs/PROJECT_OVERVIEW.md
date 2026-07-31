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

## 核心判定目标

检测器要回答的问题不是“输入记录的提交顺序是否串行”，而是：

> 是否存在一个覆盖全部已提交客户端事务的严格事务顺序 `AR`，使每个点读和谓词读都恰好等于在该顺序中执行到读事务时能够看到的数据库状态？

初始状态被建模为特殊 bottom transaction `T⊥`。`T⊥` 不进入真实事务的 MonoSAT AR graph，但在顺序比较中固定满足：

```text
T⊥ <AR T       对任意真实事务 T
T <AR T⊥       恒为 false
```

对真实事务，合法解释必须同时满足：

1. `AR` 是严格顺序：无自环、无环；公式实际比较到的事务对必须二选一。
2. `AR` 尊重 session order：同一 session 先出现的事务必须在后出现的事务之前。
3. `AR` 尊重点读来源：写出被读版本的事务必须在 reader 之前。
4. 同一 key 的不同写事务在 `AR` 中形成唯一先后顺序。
5. 点读读到的版本必须是 reader 之前该 key 的最新可观察版本。
6. 谓词读必须能从 reader 之前每个相关 key 的 latest-visible frontier 组成一个可见状态；执行结构化查询后，`inputs` 和投影后的 `values` 必须与历史完全一致。

因此最终 verdict 可以写成：

```text
ACCEPT
    ⇔ 存在满足上述全部条件的 AR

REJECT
    ⇔ 内部一致性已直接矛盾
       或已知依赖/被迫 WW 分支形成环
       或 SAT 中不存在能解释所有点读和谓词读的 AR
```

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

项目核心思想是：不要先枚举所有可能串行顺序，而是把公式实际需要判断的“事务 A 是否在事务 B 之前”编码成 SAT literal。MonoSAT 负责维护被选择的 arbitration graph 必须无环；如果公式可满足，就说明有一个可扩展成串行顺序的偏序。

完整控制流程与 `SERVerifier.audit()` 一致：

```text
load PRHIST
  |
  v
verifyInternalConsistency
  | false
  +---------------------------> REJECT
  |
  v
KnownGraph(SO, WR, writes, predicate observations)
  |
  v
generate WW choices and ordinary RW implications
  |
  v
prune branches that immediately close a cycle
  | both branches impossible
  +---------------------------> REJECT
  |
  v
build SERSolverAR
  - mandatory known order
  - remaining WW/RW choices
  - predicate frontier constraints
  - AR acyclicity
  |
  v
solve one SAT model
  | UNSAT
  +---------------------------> REJECT
  |
  v
evaluate full-query predicate snapshots
  | mismatch: add no-good clause and solve again
  |
  + no mismatch ----------------> ACCEPT
```

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

当前 `PredicateHistoryLoader` 把结构化 `query` 编译为 `QueryPlan`：

```text
from
    一个必填 relation，可带 alias。

joins
    零个或多个 INNER JOIN，每个 join 带 on 条件。

where
    可选条件数组，数组元素按 AND 连接。

select.columns
    一个或多个字段路径或带 AS 的表达式。

select.distinct
    可选布尔值，默认 false。
```

表达式支持字段路径、整数/字符串/布尔/null 字面量、`=`、`>`、`<`、`%`、`AND` 和括号。单表 KV 的 `value < 10` 等条件是该结构化查询的简单子集；多关系对象值可以使用 `alias.value.field` 访问。

`result.inputs` 是本次谓词读结果实际依赖的可见版本集合。检测器用这些 `(key,value)` 找到对应 source write；`result.values` 保存投影后的业务结果，并按多重集匹配。结构化结果只做相等性比较，`<`、`>` 只接受可排序的标量。

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

当这个事务对首次进入公式时，`ensureComparable` 会用互斥的两个 AR 方向保证二选一。选择某个方向后，还会激活对应的 RW implications；如果已知序已经确定方向，则直接使用常量，不再创建选择变量。

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

谓词 observation 还按 key 区分：

- `EXTERNAL`：需要在 AR 中选择事务外部的 latest-visible frontier。
- `INTERNAL`：当前谓词读之前，本事务已经写过该 key，或更早谓词读已经覆盖该 key；主求解不再为它新建外部 frontier，而是使用最后本地写或当前记录的内部输入。相同谓词继承和本地写矛盾由内部一致性预检负责。

对于单表 `Scan/Filter`、`distinct=false` 且投影表达式也只依赖单行的 `QueryPlan`，结果是各行贡献的 bag union，求解器会使用 row-local 快路径逐 key 编码。`JOIN`、`DISTINCT` 和自定义非逐行 AST 继续使用完整快照求值。

### 内部一致性预检

`SERVerifier.audit()` 在构造 `KnownGraph` 和启动 MonoSAT 前调用 `Utils.verifyInternalConsistency(history)`。该预检只处理能由历史记录和事务内 program order 直接确定的矛盾；返回 `false` 时 audit 立即 `REJECT`，不会进入 pruning 或 SAT/AR 求解。

对当前紧凑 PRHIST，预检使用两类有效索引：

```text
writesByKeyValue
    (key,value) -> 候选写事件列表。

txnWrites
    (transaction,key) -> 该事务对 key 的写事件下标列表。
```

当前 `PredicateHistoryLoader` 拒绝 `write_id`、`source_write_id`、`source_txn` 和 `source_op_index`，`Utils.verifyInternalConsistency` 也不再保留按 id 查找的兼容分支。点读和谓词输入一律通过 `(key,value)` 反查来源写；找不到对应写或存在多个候选写都会失败。

#### 点读规则

对每个 `r(key,value)`：

- 读到的 `(key,value)` 必须在整段历史中恰好对应一个写事件；不存在或对应多个写事件都会失败。
- 来源在当前事务内时，不能来自读事件之后；读之前如果还有更晚的同 key 本地写，读取旧本地版本会失败。
- 来源在其他事务时，必须是来源事务对该 key 的最后一次写；当前事务在该点读之前不能已经写过同一 key，否则违反 read-your-writes。

外部事务之间谁先谁后并不由预检决定，仍由后续 AR 求解。

#### 谓词结果基础规则

对每个 `pr`：

- `predicate` 和结果集合不能为 null。
- `result.inputs` 中同一个 key 只能出现一次。
- 每个 `(key,value)` 必须能解析到唯一来源写，来源事务必须已提交，且 key 必须属于 query scope。
- 外部来源必须是来源事务对该 key 的最后一次写；如果当前事务在谓词读之前已经写过该 key，则不能忽略该本地写而使用外部来源。
- 内部来源必须是谓词读之前当前事务对该 key 的最后一次写，不能引用未来写或较旧本地写。
- 如果事件同时保存了结构化 `recordedPredicateResult`，其 `inputs` 必须与解析后的 `result.inputs` 完全一致。

这些检查确认输入版本的来源与事务内顺序合法；完整 JOIN、投影、`DISTINCT` 和 `result.values` 是否能由某个外部可见快照解释，仍由 `SERSolverAR` 校验。

#### 相同谓词读与本地写规则

预检按 `predicate.identity()` 在每个事务内分别跟踪最近一次相同谓词读。对 query scope 覆盖且历史中存在写版本的每个 key：

- 若当前谓词读之前没有比前一次相同谓词读更新的本地写，当前结果必须逐 key 继承前一次结果；key 的存在性和值都不能改变。
- 若当前谓词读之前存在更新的本地写，则以 program order 中最后一次本地写为准。检测器在单行本地状态上求值：该写满足谓词时，结果必须包含相同 key/value；不满足时，结果不得包含该 key。
- 第一次谓词读之前已有本地写时，同样由最后一次本地写决定结果，不需要先有前一次谓词读。
- 没有本地写且没有前一次相同谓词读作为依据的 key 属于外部可见性问题，预检不猜测其版本，交给 VIS/AR frontier 求解。

因此预检拒绝的是事务自身已经能证明的矛盾，例如读未来本地写、忽略最新本地写、重复谓词 key、相同谓词无中间写却改变结果；外部版本选择和完整关系查询结果不在此阶段提前固定。

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

当前 loader 不接受显式 source provenance；检测器只通过唯一 `(key,value)` 写版本解析读来源。因此生成器必须保证写版本唯一。

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

`KnownGraph` 把不需要 SAT 猜测的信息整理为已知依赖和索引。

#### 4.1 建立 SO

对每个 session，按事务出现顺序只加入相邻 SO 边：

```text
T1 ->SO T2 ->SO T3
```

不必显式加入 `T1 -> T3`，后续 `KnownOrder` 会计算传递闭包。

#### 4.2 解析点读并建立 WR

当前 PRHIST 没有 source id。对于 `r(x,v)`，通过唯一 `(x,v)` 找到写事件 `w`：

```text
w 属于事务 Tw，读属于事务 Tr

Tw != Tr  =>  Tw ->WR(x) Tr
Tw == Tr  =>  不建立跨事务 WR；事务内合法性已由预检确认
```

`readFrom` 单独保存 WR，普通 RW 推导需要知道“reader 具体读自哪个 writer”；同一条 WR 同时进入 `knownGraphA`，成为强制 AR 方向。

#### 4.3 建立写索引

每个写被保存为：

```text
WriteRef = (transaction, write event, event index)
```

`allWrites` 保存所有写，`writesByKeyValue` 用于来源解析，`txnWrites[(T,x)]` 保存事务 `T` 对 key `x` 的 program-order 写位置。`SERSolverAR` 还会从 `allWrites` 构造 `writesByKey[x]`，用于枚举同 key writer 和谓词 frontier。

#### 4.4 收集并分类谓词 observation

每个谓词事件被转换为：

```text
PredicateObservation
  reader transaction
  predicate event
  event index in reader
  result tuple -> source WriteRef
  every covered key -> INTERNAL or EXTERNAL
```

分类在同一事务内按事件顺序进行。对 query scope 覆盖的 key：

```text
INTERNAL
    当前谓词读之前，本事务已经写过该 key；
    或本事务更早的任意谓词读已经覆盖过该 key。

EXTERNAL
    当前读之前既没有本地写，也没有更早谓词 observation 覆盖该 key。
```

`INTERNAL` 表示主求解不再为该 key 选择新的外部 frontier：有本地写时使用事件前最后本地写，否则使用当前谓词记录中已经解析的内部输入；相同谓词继承和本地写矛盾由 `verifyInternalConsistency` 检查。`EXTERNAL` 才需要在 AR 中选择外部可见 frontier。

内部维护的主要索引：

```text
readFrom
    记录 WR 来源。

knownGraphA
    放 SO、WR、WW、PR_WR 等正向依赖。

knownGraphB
    放 RW、PR_RW 等反向依赖。

writesByKeyValue
    通过唯一 (key,value) 查写来源，是当前 PRHIST 的有效来源索引。

allWrites
    所有写版本。

predicateObservations
    每个谓词读及其结果版本来源。
```

当前紧凑 PRHIST 只走 `(key,value)` 唯一解析路径；`write_id/source_write_id/source_txn/source_op_index` 会被 loader 拒绝。

### 5. 生成 SER 约束

文件：

```text
src/main/java/verifier/SERVerifier.java
```

主要步骤：

1. `Utils.verifyInternalConsistency(history)` 检查点读、写和上述事务内谓词继承/本地写一致性。
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

#### 5.1 WW 为什么必须二选一

若事务 `A` 和 `C` 都写 key `x`，串行解释中只能有：

```text
A <AR C    对应 WW(A,C,x)
```

或：

```text
C <AR A    对应 WW(C,A,x)
```

`SERConstraint` 的 `edges1/edges2` 分别保存选择两个方向时必须同时激活的边。

#### 5.2 普通 RW 如何保证读到最新版本

假设 `B` 读取 `A` 写出的 `x`，即：

```text
A ->WR(x) B
```

另一个事务 `C` 也写 `x`。如果选择 `A <AR C`，为了让 `B` 仍读到 `A` 的版本，`B` 必须位于 `C` 之前：

```text
A <AR C  =>  B <AR C
```

代码把它记录为同一 WW 分支中的两条边：

```text
WW(A,C,x)
RW(B,C,x)
```

反方向 `C <AR A` 不需要 `B <AR C`，因为 `C` 的版本位于 `A` 之前，`B` 读取 `A` 仍然是合法的 latest-visible 结果。

因此对每组 `A ->WR(x) B` 和第三方 writer `C`，核心公式是：

```text
ar(A,C) -> ar(B,C)
```

这正是普通读“不能跨过一个更新版本仍读取旧值”的判断逻辑。

#### 5.3 Coalescing

默认开启 coalescing。同一事务对可能共同写多个 key，也可能由多个读产生重复 RW implication；检测器把相同事务对的方向选择合并为一个 `SERConstraint`：

```text
选择 A <AR C
    同时激活该事务对在所有相关 key 上的 WW/RW 边

选择 C <AR A
    同时激活反方向对应的全部边
```

关闭 coalescing 只改变约束组织方式，不改变合法串行解释的集合。

### 6. Pruning

文件：

```text
src/main/java/verifier/Pruning.java
```

pruning 的目标是提前处理明显被迫的 WW choice。如果某个 choice 的反方向会让当前已知图立刻成环，则可以直接选择另一个方向。这样能减少进入 SAT 的待定分支数量。

pruning 不改变可满足性：它只提交那些反方向已经不可能成立的 choice。

对一个二选一约束 `C = (branch1, branch2)`：

```text
canAdd(branch1) = false
canAdd(branch2) = false
    => 两边都成环，立即 REJECT

canAdd(branch1) = false
canAdd(branch2) = true
    => branch2 被迫成立，写入 KnownGraph，移除 C

canAdd(branch1) = true
canAdd(branch2) = false
    => branch1 被迫成立，写入 KnownGraph，移除 C

两边都可加入
    => 保留 C，交给 SAT
```

`ReachabilityOracle` 以当前 `knownGraphA + knownGraphB` 的传递闭包为基础判断新增边是否会闭环。被迫分支写回 KnownGraph 后会影响后续约束，所以 pruning 按轮执行；一轮解决的约束太少或已无剩余约束时停止。

当前环检查不会为每个候选分支复制完整事务可达矩阵：

- SER 常见的同目标 WW/RW 分支直接在基础传递闭包上逐边检查。
- 混合目标分支只构造候选边端点的局部闭包图，同时保留经非端点事务形成环的判断。
- 每轮是否继续按当前剩余约束数判断。

### 7. SAT/AR 编码

文件：

```text
src/main/java/verifier/SERSolverAR.java
```

`SERSolverAR` 把可串行化问题编码成 SAT：

- `ar(T1,T2)` literal 表示 `T1` 在 arbitration order 中早于 `T2`。
- 已知 SO/WR/依赖序先计算传递闭包；已确定的 AR 方向直接常量化，MonoSAT 只接收传递约简边。
- 每个公式可见的未定事务对通过 `ensureComparable` 保证两个 AR 方向恰选其一。
- 普通 RW 由 WR 和 WW 顺序推出。
- row-local 谓词逐 key 编码 recorded source 和未返回行约束；仅当后续写改变该行对谓词结果的成员关系或改变已匹配行的值时，才建立对应 `PR_RW`。
- JOIN、DISTINCT 等通用谓词根据具体 SAT 模型构造完整可见快照；结果不匹配时加入该快照的 no-good 子句并继续求解。
- MonoSAT graph acyclicity 保证被选择的 AR 边无环。

实现细节：

- AR graph 节点对应真实事务，不包含 bottom init transaction。
- AR literal 是按需创建的，不为所有事务对一次性生成。
- 已知序能推出的方向返回 `true/false` 常量，不创建 MonoSAT edge。
- 对公式中需要比较的事务对，`ensureComparable` 会保证方向可比较。
- 同一个 key 的 writer pair 比较只初始化一次，可被多个谓词读复用。
- 固定 recorded frontier 同样只排除会改变谓词结果的后续写；谓词前后均不匹配时不会产生多余的 AR/`PR_RW` 限制。
- 条件依赖按 guard/edge 去重，恒假 guard、恒真 target 和重复 implication 不进入最终公式。
- 大型动态谓词阻断子句通过 `assertOr` 提交，避免固定 JNI clause 缓冲边界。
- 无环偏序可以扩展成严格全序，因此只要 SAT 可满足，就存在合法串行解释。

#### 7.1 已知顺序与 AR literal

`SERSolverAR` 先把 pruning 后的非谓词已知边合并为 mandatory precedence。这里两类图在 SER 中最终都要求相同的 AR 方向：

```text
A-side: SO, WR, pruning 已确定的 WW
B-side: pruning 已确定的 RW

任意边 T1 -> T2
    都要求 ar(T1,T2) = true
```

`buildKnownOrder()` 明确跳过 `PR_WR/PR_RW`，因为主路径的谓词边要等 frontier SAT 条件建立后由 `addDependencyEdge` 单独编码；`--compare-derived-predicate-edges` 生成的诊断边也不会反向影响 verdict。

对上述非谓词已知边，`buildKnownOrder()`：

1. 检查是否已有环或指向 bottom 的非法边；有则直接令公式 UNSAT。
2. 计算传递闭包 `reachable`。
3. 计算传递约简，只把保持同一可达关系所需的最少已知边提交给 MonoSAT。

之后调用 `ar(A,B)` 时：

```text
knownOrder 已知 A 可达 B  => true 常量
knownOrder 已知 B 可达 A  => false 常量
A 或 B 是 bottom          => 固定常量
否则                       => 按需创建 MonoSAT edge literal
```

第一次需要比较未定事务对 `{A,B}` 时，`ensureComparable` 加入：

```text
xor(ar(A,B), ar(B,A))
```

所以公式涉及的事务对必定二选一。没有被任何约束查询的事务对不创建变量；最终无环偏序可以任意拓扑扩展成全序。

#### 7.2 WW 分支与普通 RW implication

pruning 后剩余的每个 `SERConstraint` 被编码为两个相反 AR literal。方向 literal 作为 guard，控制该分支携带的 WW/RW：

```text
ar(A,C) -> ar(edge.from, edge.to)
```

求解器还直接遍历 `readFrom` 再编码一次普通 RW 语义：

```text
A ->WR(x) B
C writes x

ar(A,C) -> ar(B,C)
```

这一直接编码保证即使约束经过 coalescing/pruning，普通读 latest-visible 语义仍完整存在。相同 guard/edge 会去重；guard 恒假时不创建目标 AR literal，guard 恒真时直接断言目标。

#### 7.3 谓词 key 的 frontier 候选

对谓词读事务 `R` 和 scope 内 key `x`，若 `R` 在谓词事件之前已经本地写 `x`：

```text
frontier_x(R) = R 在该事件之前对 x 的最后一次本地写
```

它是固定 INTERNAL frontier，不需要外部 AR 选择。

否则，每个外部 writer 只保留该事务对 `x` 的最后一次写作为候选，因为同一事务的中间写不可能成为事务外可见版本。候选 `w` 的可见 guard 是：

```text
visible(w,R) = ar(writer(w), R)
```

若某个候选是当前 SAT 模型下所有可见候选中写顺序最晚的一个，它就是该 key 的 selected frontier。若所有候选 writer 都在 `R` 之后，则该 key 在这个模型中没有外部可见写。

历史结果中已出现 key `x` 时，记录的 `(x,value)` 会解析为固定 source：

```text
source writer <AR R                         对应 PR_WR
source 后任何会改变结果的同 key writer U
    必须满足 R <AR U                        对应 PR_RW
```

这保证 source 是“对查询结果而言最新”的可见版本。若一个后续写在谓词前后都不匹配，它不会改变可观察结果，因此无需强制排在 reader 之后。代码还防御性处理“两个写的 key/value 完全相同”的等价情况，但当前紧凑 PRHIST 要求 `(key,value)` 全局唯一，不会通过 loader 产生这种输入。

#### 7.4 Row-local 谓词快路径

以下 QueryPlan 被视为 row-local：

```text
单表 Scan/Filter
distinct = false
投影只依赖当前单行
```

这类查询的完整结果是每个 key 单行贡献的 bag union，可以逐 key 编码：

1. `result.inputs` 的 key 集必须等于已解析 tuple source 的 key 集。
2. 在只包含记录输入的状态上执行查询，投影结果必须与记录结果一致。
3. 返回的 EXTERNAL key：固定 recorded source，并建立必要 `PR_WR/PR_RW`。
4. 未返回的 EXTERNAL key：找出单行执行会产生非空贡献的候选写，禁止这些写成为 reader 的 latest-visible frontier。
5. INTERNAL key：必须使用谓词事件之前的最后本地写或已经继承的内部 source；若该行会产生结果却被遗漏，公式直接 UNSAT。
6. 结果中的 key 若超出 scope 或历史中不存在写版本，公式直接 UNSAT。

未返回 key 的阻断逻辑不是简单要求“这个 writer 在 reader 后面”。如果一个会返回结果的旧写位于 reader 前，但其后还有一个不产生结果的更新写也位于 reader 前，那么后者可以成为 frontier，空结果仍合法。

#### 7.5 JOIN、DISTINCT 与完整快照路径

JOIN、`DISTINCT`、多行相关投影等查询不能逐 key 独立判断，走完整快照 refinement：

1. 对每个 EXTERNAL scoped key 建立 `KeyFrontier`。
2. 将 INTERNAL key 的最后本地写/已继承 source 放入 `fixedSnapshot`。
3. SAT 先给出一个候选 AR 模型。
4. 从模型中为每个 frontier 选择 latest-visible candidate，组成：

```text
snapshot = fixedSnapshot ∪ selectedExternalFrontiers
```

5. 用 `RelationResolver` 把 `table:key` 映射到 relation，在 `MapVisibleState` 上执行完整 `QueryPlan`。
6. 将求值结果与历史记录比较：

```text
有 recordedPredicateResult
    使用 QueryEvaluation.canonicalEquals
    同时比较 inputs 和 values 的规范化多重集

只有旧式 predResults
    比较 inputs map
```

如果结果不一致，不会立刻整体 REJECT，而是只禁止当前这组 frontier 选择。

#### 7.6 No-good refinement

假设当前模型为某次谓词读选择：

```text
x -> wx
y -> wy
z -> ABSENT
```

但执行查询得到的结果与历史不一致。`appendNegatedSelection` 构造一个 no-good clause，表达“下一次求解至少改变一个 key 的 frontier”：

```text
not(select(x,wx))
or not(select(y,wy))
or not(select(z,ABSENT))
```

其中：

- `select(x,wx)` 表示 `wx` 对 reader 可见，且不存在一个在 `wx` 之后、reader 之前的更晚可见候选。
- `select(z,ABSENT)` 表示所有 `z` 候选 writer 都位于 reader 之后。
- 已由记录 source 固定的 frontier 不加入 clause，因为它不能在后续模型中改变。

如果不匹配查询的全部 frontier 都已固定，blocking clause 为空，说明没有其他可见快照可尝试，公式直接变为 UNSAT。

求解循环是：

```text
while SAT:
    从模型构造所有完整查询快照
    if 每个谓词结果都匹配:
        ACCEPT
    对每个不匹配快照加入 no-good clause

没有新 SAT 模型:
    REJECT
```

该过程等价于预先枚举所有 frontier 笛卡尔积并为错误组合加约束，但只探索 SAT 实际产生的组合。

#### 7.7 `PR_RW` 的“结果变化”条件

对同 key 的 source 写 `ws` 和 source 后的写 `wu`，只有以下情况认为 `wu` 改变谓词结果：

```text
ws 匹配，wu 不匹配
ws 不匹配，wu 匹配
两者都匹配，但输出行的 key/value 不同
```

以下情况不产生额外 `PR_RW`：

```text
两者都不匹配
```

内部求解代码还把“两者都匹配且 key/value 完全相同”视为结果不变；这是通用事件模型中的防御分支。当前紧凑 PRHIST 依赖 `(key,value)` 全局唯一，因此两个不同写不能通过该格式表达为相同 key/value。

这是结果可检测性规则：SER detector 约束历史中可观察到的查询结果，不用当前输入中不存在的版本 provenance 改变判断。

#### 7.8 最终 ACCEPT/REJECT

`SERSolverAR.solve()` 返回 `true` 的条件是：

```text
MonoSAT 找到无环 AR 模型
AND
该模型下所有 row-local 谓词约束成立
AND
所有完整快照查询结果匹配
AND
没有新的 no-good refinement 需要加入
```

返回 `false` 表示 mandatory edge、WW/RW implication、谓词可见性和所有已发现 no-good clause 的合取不可满足。外层随后尝试提取已知边环或缩减 WW constraint core，用于解释 REJECT；诊断是否完整不影响 verdict。

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

## 两个完整判断示例

### 示例一：点读旧版本为什么会形成矛盾

同一 session 依次提交：

```text
T1: W(x,1)
T2: W(x,2)
T3: R(x,1)
```

已知关系：

```text
SO: T1 <AR T2 <AR T3
WR: T1 <AR T3
```

`T1` 与 `T2` 都写 `x`，SO 已确定 `WW(T1,T2,x)`。由于 `T3` 读取 `T1` 的版本，普通 RW 规则推出：

```text
T1 <AR T2  =>  T3 <AR T2
```

但 SO 已要求 `T2 <AR T3`，于是：

```text
T2 <AR T3 <AR T2
```

mandatory AR 成环，历史 `REJECT`。若没有 `T2 <AR T3` 的已知约束，求解器可以选择 `T1 <AR T3 <AR T2`，此时该点读是可解释的。

### 示例二：谓词结果如何限制后续写

假设谓词是 `value > 5`：

```text
T1: W(x,10)
T3: W(x,3)
T2: PR(value > 5) -> {(x,10)}
```

记录结果把 `T1` 的写固定为 `x` 的 observable frontier：

```text
T1 <AR T2                    PR_WR
```

`T3` 把 `x` 从匹配值 `10` 改成不匹配值 `3`，会改变谓词结果，所以：

```text
T1 <AR T3  =>  T2 <AR T3    PR_RW
```

若其他已知关系要求 `T1 <AR T3 <AR T2`，则 `T2` 应看到 `x=3` 并返回空集，与记录结果冲突；`PR_RW` 要求的 `T2 <AR T3` 和已知 `T3 <AR T2` 成环，最终 `REJECT`。

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

覆盖 loader、基础 verifier、事务内谓词继承与本地写一致性、SAT encoding、仅结果变化写触发的 `PR_RW`、row-local 与 JOIN/DISTINCT fallback、剪枝可达性、小历史 differential 检查和 CLI 行为。

## 正确性直觉

### Soundness

如果 solver 返回 SAT，则：

1. 内部一致性已经确认所有读取来源和事务内 program order 没有直接矛盾。
2. 所有 SO/WR 和 pruning 后被迫的 WW/RW 已知依赖都被放进 AR。
3. 每个公式涉及的未定 writer pair 都选择了唯一方向。
4. 对每个普通读，`WR + WW => RW` 保证 reader 位于所有 source 后续 writer 之前，因此读到的 source 是合法 latest-visible 版本。
5. row-local 谓词已逐 key 排除错误 frontier；完整查询路径已经在最终 SAT 模型上实际执行 QueryPlan 并匹配 `inputs/values`。
6. MonoSAT 保证选中的 AR graph 无环。
7. 没有进入公式的事务对可以按任意拓扑扩展补全，不破坏已有依赖。

任何有限无环偏序都能扩展成严格全序，所以存在一个串行顺序解释该历史。

### Completeness

如果真实存在某个合法串行解释，则可以按这个串行顺序给所有 `ar(T1,T2)` literal 赋值。这个赋值会满足：

- SO/WR 已知边。
- 每个 key 上的 WW 顺序。
- 普通读的 latest-visible 约束。
- 谓词读的 frontier 和结果集合约束。
- AR 无环约束。

对于完整查询路径，真实解释对应的 frontier 组合执行 QueryPlan 必然与历史一致，因此不会被 no-good clause 排除。每条 no-good 只排除一个已经实际求值为错误的组合，不会移除合法解释。因此 solver 不应返回 UNSAT。

### Pruning 和 Coalescing

pruning 只提交反方向已经立即成环的 choice，因此保持可满足性。

coalescing 把同一事务对上的重复 choices 合并，因为严格事务级串行顺序里，同一事务对的先后关系必须一致。

refinement 的 frontier 组合数量有限；每次 mismatch 至少排除当前模型的一组选择，所以循环最终会找到合法组合或耗尽全部可满足组合并返回 UNSAT。

## 当前边界

当前 detector 的稳定路径是：

```text
compact PRHIST + structured QueryPlan predicates + MonoSAT AR solver
```

已明确的边界：

- Java loader 当前只接受 `query/result` 形态的 predicate read。
- query 支持单表扫描、多个 INNER JOIN、AND 条件、字段投影和 DISTINCT；不支持其他 JOIN 类型或任意 SQL 语法。
- value 不接受 null、数组和非整数数字；结果投影可以是结构化 JSON，但结构化值只支持相等性。
- 当前紧凑格式依赖 `(key,value)` 写版本唯一性。
- `write_id/source_write_id/source_txn/source_op_index` provenance 字段会被 loader 拒绝。
- TPC-C 多表 SQL-shaped predicate 必须先转换为当前结构化 query，才能被完整验证。
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
