# SI 检测器详细设计与 SER 对齐分析

本文档面向第一次接触本项目的人，说明 SI detector 的判定目标、完整实现流程、SAT/MonoSAT 编码、谓词快照处理、主要模块和当前边界，并单独列出相较 SER detector 已对齐、语义上不应照搬以及当前仍未具备的流程。日常运行命令见 `SI/README.md`。

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

## 核心判定目标

SI detector 要回答的问题是：

> 是否存在一组同 key 写顺序、快照可见性和谓词 frontier 选择，使全部已提交事务的点读、写入和谓词读都与记录结果一致，并且对应的 induced SI graph 无环？

定义两类边：

```text
Dep = SO ∪ WR ∪ WW ∪ PR_WR
AntiDep = RW ∪ PR_RW
```

其中：

- `SO`：同一 session 的事务顺序。
- `WR`：普通点读的版本来源。
- `WW`：同 key 写事务之间选择出的版本顺序。
- `RW`：普通读没有看到后续版本而形成的反依赖。
- `PR_WR`：谓词读在某个 key 上选择的 snapshot frontier 来源。
- `PR_RW`：frontier 后某次写会改变完整谓词结果而形成的反依赖。

当前实现构造：

```text
InducedSI = Dep ∪ (Dep ; AntiDep)
```

组合 `Dep ; AntiDep` 的含义是：

```text
A --Dep--> B --AntiDep--> C
```

推出 induced edge：

```text
A ----------------------> C
```

最终 verdict 为：

```text
ACCEPT
    ⇔ 存在 WW/frontier SAT 赋值
       AND 所有点读 latest-visible 约束成立
       AND 所有谓词快照执行结果与记录一致
       AND InducedSI 无环

REJECT
    ⇔ 内部一致性预检已经发现确定矛盾
       OR 某个被迫 WW choice 两个方向都会形成 induced cycle
       OR MonoSAT 中不存在满足全部图约束和谓词约束的赋值
```

初始状态由特殊 bottom transaction `T⊥` 表示。与 SER 把 `T⊥` 从真实 AR graph 中排除不同，SI solver 为 `T⊥` 创建 dependency/induced graph 节点；它可以作为初始版本的来源，但任何有效依赖都不能指向 `T⊥`。

## 与 SER 的流程对齐状态

下表区分三类情况：已经对齐、SI 语义上不应照搬、当前实现仍未具备。不能把第二类误认为 SI 的实现缺陷。

| 流程 | 当前 SI 状态 | 与 SER 的关系 |
|---|---|---|
| PRHIST loader、bottom transaction、History/Event 模型 | 已具备 | 与 SER 主代码一致 |
| 点读/谓词读内部一致性预检 | 已具备 | 判断语义与 SER 一致 |
| SO、WR、写索引、predicate observation | 已具备 | `KnownGraph` 与 SER 一致 |
| WW 二选一、普通 RW implication、coalescing | 已具备 | 约束生成结构与 SER 一致 |
| 基于判定语义的 pruning | 已具备 | SI 检查 InducedSI；SER 检查直接 precedence/AR 环 |
| MonoSAT 条件 WW、谓词 frontier、no-good refinement | 已具备 | 两者目标图不同，但端到端流程均已存在 |
| JOIN、投影、重复行、遗漏行、`DISTINCT` 完整结果校验 | 已具备 | SI 走完整 snapshot 路径 |
| 严格 total AR 与任意依赖都直接进入 AR | 不适用 | 这是 SER 的定义；SI 必须保留 AntiDep，不能照搬 |
| `RW/PR_RW` 直接要求 `reader < writer` | 不适用 | SI 只通过 `Dep ; AntiDep` 影响 induced graph，否则会错误拒绝 write skew |
| SER 的 row-local 谓词逐 key 快路径 | 未具备 | 有 EXTERNAL key 时主要影响性能；全部 key 都是 INTERNAL 时，SI 会在 solver 中跳过该 observation，完整 `result.values` 校验覆盖弱于 SER |
| 增量 pruning reachability oracle | 未具备 | SI 当前每个候选方向复制 KnownGraph 并重建 MatrixGraph，语义已有但效率低于 SER |
| 精简 SAT 冲突核与完整条件边 cycle witness | 部分具备 | SI 能报告已知/被迫图环，但 UNSAT 时通常保留全部剩余 WW constraints，不能稳定给出最小解释 |
| 独立小历史 SI oracle differential test | 未具备 | 当前有场景测试和黑盒测试，但没有与独立穷举 SI oracle 的逐例对拍 |
| 独立 pruning reachability 单元测试 | 未具备 | 当前主要由 verifier/黑盒测试间接覆盖 |
| `validate_prhist_suite.py` oracle suite 校验工具 | 未具备 | SI `tools/` 当前只有批量 audit 和 catalog experiment；文档不能把 SER 工具算作 SI 已有功能 |

因此，从一次 `audit` 能否完成 SI verdict 的角度看，主流程已经闭合。当前相较 SER 的实际缺口集中在 all-INTERNAL row-local 结果校验、性能快路径、诊断精度、独立正确性 oracle 测试和 suite 工具，而不是缺少 SI 判定主链路。

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

完整控制流程与 `SIVerifier.audit()`、`SISolverInduced.solve()` 一致：

```text
load PRHIST
  |
  v
verifyInternalConsistency
  | false
  +--------------------------------------> REJECT
  |
  v
KnownGraph
  - mandatory SO / WR
  - readFrom
  - writesByKeyValue / allWrites / txnWrites
  - predicate observations
  |
  v
generateConstraintsSI
  - every same-key writer pair gets a WW choice
  - WR + candidate WW direction generates conditional RW
  - optional coalescing by transaction pair
  |
  v
InducedSI pruning
  | both WW directions impossible
  +--------------------------------------> REJECT
  |
  v
SISolverInduced encoding
  - known Dep / AntiDep
  - remaining conditional WW / RW
  - predicate PR_WR frontier guards
  - Dep ; AntiDep induced composition
  - inducedGraph.acyclic()
  |
  v
solve one MonoSAT model
  | UNSAT
  +--------------------------------------> REJECT
  |
  v
construct complete predicate snapshots
  |
  +-- query mismatch
  |      add no-good clause
  |      solve again
  |
  +-- query match
         encode result-changing PR_RW
         add new Dep ; PR_RW induced edges
         if new constraints were added, solve again
  |
  v
no more refinement --------------------------------> ACCEPT
```

需要特别注意：`--compare-derived-predicate-edges` 只创建独立诊断图，用于比较物化的 `PR_WR/PR_RW` 数量。生产 verdict 不读取这张诊断图，谓词依赖由 `SISolverInduced` 根据 SAT frontier 直接编码。

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

表达式支持字段路径、整数/字符串/布尔/null 字面量、`=`、`>`、`<`、`%`、`AND` 和括号。单表 KV 条件是该结构化查询的简单子集；多关系对象值可以使用 `alias.value.field` 访问。

`result.inputs` 是查询结果实际依赖的可见版本集合，检测器用这些 `(key,value)` 找到 source write；`result.values` 保存投影后的业务结果并按多重集匹配。结构化值支持相等性，`<`、`>` 只接受可排序的标量。

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

谓词 observation 还按 key 区分：

- `EXTERNAL`：需要在事务快照中选择外部 latest-visible frontier。
- `INTERNAL`：由当前谓词事件之前的同事务本地写或已有内部观察决定；固定内部值直接进入查询快照，不再创建外部 frontier。

求解器按 SAT 模型选择各 key 的 frontier，构造完整可见快照并执行 `QueryPlan`。如果 JOIN、投影、重复行、遗漏行或 `DISTINCT` 结果不匹配，便加入阻断当前 frontier 组合的子句后继续求解。结果匹配时，再为会改变完整查询结果的后续写编码 `PR_RW`；固定 recorded-source frontier 同样先比较 source 写与后续写的规范化谓词结果，仅在结果发生变化时施加对应可见性约束。

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

### 内部一致性预检

`SIVerifier.audit()` 在构造 `KnownGraph` 和启动 MonoSAT 前调用 `Utils.verifyInternalConsistency(history)`。该阶段只拒绝由输入记录和事务内 program order 已经能够直接证明的矛盾，不猜测跨事务快照顺序。

预检建立两个主要索引：

```text
writesByKeyValue
    (key,value) -> 候选写事件列表

txnWrites
    (transaction,key) -> 当前事务写该 key 的事件位置列表
```

当前 compact PRHIST 拒绝 `write_id`、`source_write_id`、`source_txn` 和 `source_op_index`。普通读和谓词结果输入都通过唯一 `(key,value)` 反查 source；没有候选或候选不唯一都会直接失败。

#### 点读规则

对每个 `r(key,value)`：

- `(key,value)` 必须对应唯一写事件。
- source 在当前事务内时，不能读取未来写，也不能跳过读前更晚的同 key 本地写。
- source 在其他事务时，必须是 source 事务对该 key 的最后一次写。
- reader 在点读之前已经本地写同 key 时，不能继续读取外部版本。

预检不会因为历史中还存在另一个外部 writer 就直接拒绝旧版本读取；这个 writer 是否位于 reader 的快照之前，由后续 WW/RW 和 InducedSI 求解决定。

#### 谓词结果基础规则

对每个 predicate read：

- predicate 和结果集合不能为 null。
- `result.inputs` 中同一个 key 不能重复。
- 每个输入 `(key,value)` 必须解析到唯一且已提交的 source write。
- key 必须落在 query scope 中。
- 外部 source 必须是 source 事务对该 key 的最后一次写。
- 当前事务在谓词事件之前已有同 key 本地写时，不能忽略本地写去读取外部版本。
- 内部 source 必须是谓词事件之前最后一次同 key 本地写，不能引用未来或较旧本地写。
- 若事件保存了结构化 `recordedPredicateResult`，其中 `inputs` 必须与解析后的输入完全一致。

完整 JOIN、投影、`DISTINCT`、遗漏行和 `result.values` 是否能由某个快照解释，不在预检阶段决定，留给 `SISolverInduced` 的完整 snapshot evaluator。

#### 相同谓词读与本地写

预检按 `predicate.identity()` 跟踪同一事务中最近一次相同谓词读。对 scope 覆盖且历史中存在版本的每个 key：

- 两次相同谓词读之间没有更新的本地写时，当前结果必须逐 key 继承前一次结果。
- 存在更新本地写时，以当前谓词事件之前最后一次本地写为准。
- 最后本地写满足谓词时，结果必须包含对应 key/value；不满足时结果不能包含该 key。
- 第一次谓词读之前已有本地写时，同样由最后本地写决定。
- 没有本地写、也没有更早相同谓词读依据的 key 属于外部 snapshot 问题，交给 frontier 求解。

因此，预检处理的是 read-your-writes、事务内 repeatable observation 和 source 合法性；跨事务可见性仍属于 SI solver 的职责。

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

结构化查询的 AST、表达式解析、关系解析、值规范化和结果求值位于：

```text
src/main/java/history/query/
```

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

writesByKeyValue
    通过 (key,value) 查写来源。

allWrites
    所有写版本。

predicateObservations
    每个谓词读及其结果版本来源。
```

当前 SI 生产路径统一使用 `(key,value)` 唯一解析，并拒绝 `write_id/source_write_id` 等 provenance 字段；`Utils` 与 `KnownGraph` 均不再保留按 ID 解析的兼容分支。

#### 4.1 建立 SO

对每个 session，按事务在内部列表中的顺序只加入相邻 SO：

```text
T1 --SO--> T2 --SO--> T3
```

不需要显式建立 `T1 -> T3`；`depGraph.reaches` 和图算法会得到传递可达关系。SO 被放入 `knownGraphA`，后续作为 mandatory dependency。

#### 4.2 解析点读并建立 WR

对 `r(x,v)`，通过唯一 `(x,v)` 找到 source write：

```text
source 属于 Tw
reader 属于 Tr

Tw != Tr  => Tw --WR(x)--> Tr
Tw == Tr  => 不建立跨事务 WR
```

事务内读取是否合法已经由预检根据事件位置判断。跨事务 WR 同时放入 `readFrom` 和 `knownGraphA`：

- `knownGraphA` 表示 source 对 reader 的 mandatory dependency。
- `readFrom` 保留 key 和精确 reader/source 关系，用于生成普通 RW。

#### 4.3 写索引

每个写被记录为：

```text
WriteRef = (transaction, write event, event index)
```

主要索引含义：

```text
allWrites
    全部写版本，包含 bottom transaction 的初始版本。

writesByKeyValue
    用唯一 (key,value) 解析普通读和谓词输入的 source。

txnWrites[(T,x)]
    T 对 x 的事务内写位置，处理本地 latest write。

writesByKey[x]
    SISolverInduced 从 allWrites 构造的 per-key writer 列表。
```

同一事务多次写同 key 时，事务外 frontier 只保留该事务的最后一次写；较早写仅用于事务内 program-order 检查。

#### 4.4 收集 predicate observation

每个谓词事件被转换为：

```text
PredicateObservation
  reader transaction
  predicate event
  event index
  result tuple -> source WriteRef
  covered key -> INTERNAL / EXTERNAL
```

分类按事务内事件顺序进行：

```text
INTERNAL
    当前谓词事件之前，本事务已经写过该 key；
    或更早的任意谓词 observation 已经覆盖该 key。

EXTERNAL
    当前事件之前既没有本地写，也没有更早 observation 覆盖该 key。
```

`INTERNAL` key 不创建新的外部 snapshot frontier。存在本地写时使用事件前最后本地写；否则使用已经解析并由预检确认可继承的内部输入。只有 `EXTERNAL` key 进入 MonoSAT frontier 选择。

#### 4.5 A/B 分桶约定

`KnownGraph.putEdge` 统一维护边类型到图的映射：

```text
knownGraphA / Dep:
    SO, WR, WW, PR_WR

knownGraphB / AntiDep:
    RW, PR_RW
```

普通 `WR` 还额外进入 `readFrom`。主求解路径的 predicate edge 不会提前写入 KnownGraph，而是在 solver 中以带 guard 的 `PR_WR/PR_RW` 形式建立；诊断物化边不会反向参与 verdict。

### 5. 生成 SI 约束

文件：

```text
src/main/java/verifier/SIVerifier.java
src/main/java/verifier/SIConstraint.java
src/main/java/verifier/SIEdge.java
```

主要步骤：

1. `Utils.verifyInternalConsistency(history)` 检查内部一致性：相同谓词读在没有新本地写时逐 key 继承；存在本地写时由读前最后一次本地写决定；无本地依据的外部 key 留给 VIS/frontier 求解。
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

#### 5.1 WW 为什么必须二选一

若事务 `A` 和 `C` 都写 key `x`，SI 解释仍然必须给这两个版本确定同 key 写顺序：

```text
A --WW(x)--> C
或
C --WW(x)--> A
```

这不是把所有事务排成一个全局 total AR；它只确定每个 key 上冲突写版本的先后。`SISolverInduced.encodeWwChoices()` 为每个剩余 constraint 建立一个 Boolean literal：

```text
forward = true   => 激活 edges1
forward = false  => 激活 edges2
```

两个方向互为布尔取反，因此恰好选择一个方向。

#### 5.2 普通 RW implication

假设 `B` 读取 `A` 写出的 `x`，而 `C` 也写 `x`：

```text
A --WR(x)--> B
```

若 WW 选择 `A` 的版本在 `C` 前：

```text
A --WW(x)--> C
```

则 `B` 的快照没有看到 `C`，形成：

```text
B --RW(x)--> C
```

因此一个 constraint 方向通常携带：

```text
branch A-before-C:
    WW(A,C,x)
    RW(B,C,x)

branch C-before-A:
    WW(C,A,x)
```

在 SI 中，前者的 WW 是 Dep，RW 是 AntiDep。它们不会像 SER 那样都直接变成 `from <AR to`；最终由 `Dep ; AntiDep` 组合影响 induced graph。

#### 5.3 Coalescing

默认开启 coalescing。若同一事务对共同写多个 key，或多个点读产生重复 RW implication，检测器把它们合并为一个事务对级 `SIConstraint`：

```text
选择 A before C
    同时激活 A/C 在所有相关 key 上的 WW
    同时激活由该方向推出的全部 RW

选择 C before A
    激活反方向的全部边
```

coalescing 只减少重复 SAT choice，不改变合法 SI 解释集合。`--no-coalescing` 使用逐 key/逐读 constraint 组织方式，主要用于诊断和对比。

#### 5.4 Constraint 与 Edge 的职责

```text
SIConstraint
    writeTransaction1 / writeTransaction2
    edges1 / edges2
    constraintId

SIEdge
    from / to / EdgeType / key
```

`SIConstraint` 表达选择，`SIEdge` 保留语义类型。进入 solver 后，WW/RW 根据类型分别注册到 dependency 或 anti-dependency 路径；不能只看 `from/to` 而丢失边类型。

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

对一个二选一约束 `C=(branch1,branch2)`：

```text
canAdd(branch1) = false
canAdd(branch2) = false
    => 两个 WW 方向都会造成 InducedSI 环，立即 REJECT

canAdd(branch1) = false
canAdd(branch2) = true
    => branch2 被迫成立，写回 KnownGraph，移除 C

canAdd(branch1) = true
canAdd(branch2) = false
    => branch1 被迫成立，写回 KnownGraph，移除 C

两边都可加入
    => 保留 C，交给 MonoSAT
```

`SIVerifier.InducedGraph.canAddAll()` 的当前实现：

1. 从当前 history 创建一个新的 `KnownGraph`。
2. 复制已有 A/B 边。
3. 加入一个候选 branch 的全部 WW/RW。
4. 构造 `Dep` 和 `AntiDep` 的 `MatrixGraph`。
5. 计算 `Dep ∪ (Dep ; AntiDep)`。
6. 通过拓扑排序判断是否有环。

被迫方向写回 KnownGraph 后会影响后续 constraint，所以 pruning 按轮执行。谓词 frontier 不在 pruning 阶段物化；当前试算只处理已知 SO/WR、已确定 WW/RW 和候选分支，最终 predicate consistency 由 MonoSAT 负责。

这一流程语义完整，但相较 SER 的增量 `ReachabilityOracle` 存在明确性能差距：SI 当前对每个候选方向复制图并重新计算矩阵，而不是在共享传递闭包上增量试边。该差距不改变 verdict，只影响大规模 WW choice 下的构造成本。

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
3. `encodePredicateConstraints`：为每个谓词读建立受查询 scope 限定的 per-key frontier 候选。
4. `encodeInducedComposition`：把 `dep ; anti-dep` 组合成 induced edge。
5. `solver.assertTrue(inducedGraph.acyclic())`：要求 induced graph 无环。
6. `solver.solve()`：读取当前 SAT 模型并构造 latest-visible 快照。
7. 查询结果不匹配时加入阻断子句并继续求解；匹配时补充该快照对应的谓词依赖。
8. 没有新的 refinement 后返回 ACCEPT；公式不可满足时返回 REJECT。

#### 7.1 图节点和 bottom transaction

`createNodes()` 为 history 中每个事务同时创建：

```text
depNodes[txn]
inducedNodes[txn]
```

这里包含 bottom transaction。初始写对任何 predicate frontier 都视为可见；但 `guardCanHold` 禁止自环和指向 bottom 的依赖。这个处理与 SER 把 bottom 比较常量化的做法不同，是 SI 图模型自身的实现方式。

#### 7.2 已知边编码

`encodeKnownEdges()` 读取 pruning 后的 KnownGraph：

```text
knownGraphA 中 SO / WR / WW
    -> addDepEdge(..., true)

knownGraphB 中 RW
    -> addAntiDepEdge(..., true)
```

生产路径中的 `PR_WR/PR_RW` 不是静态 known edge，由谓词 frontier 编码动态创建。因此这里显式过滤 predicate type，不会消费 `--compare-derived-predicate-edges` 的诊断结果。

`addDepEdge` 会：

1. 保存带 guard 的语义边。
2. 将同一个条件边绑定到 `depGraph`。
3. 同时将 dependency 本身绑定到 `inducedGraph`。

`addAntiDepEdge` 只保存 AntiDep，不直接加入 induced graph。AntiDep 必须通过 composition 才能产生 induced edge。

#### 7.3 条件 WW/RW 编码

每个剩余 `SIConstraint` 建立一个 `forward` literal：

```text
forward       -> edges1
not(forward)  -> edges2
```

对分支中的边：

```text
WW
    加入 Dep，并注册 wwOrder[(from,to,key)] = guard

RW
    加入 AntiDep
```

同一方向可能由 coalescing 合并多个 key，`wwOrder.merge` 会用逻辑 OR 合并重复方向 guard。

#### 7.4 图边与 guard 的双向绑定

MonoSAT graph edge 自身也是 literal。对条件边，`bindGraphEdge` 编码：

```text
guard -> graphEdge
graphEdge -> guard
```

因此图边是否存在与 guard 真值完全一致，不能由 MonoSAT 为满足 acyclicity 任意关闭一个已经选中的依赖。恒真 guard 直接断言 graph edge；恒假 guard 不创建有效边。

#### 7.5 同 key 写顺序

`beforeWrite(left,right)` 用于比较一个 key 上的两个候选版本：

```text
同一写引用
    false

同一事务的两个写
    按 event index 比较

left 是 bottom
    true

right 是 bottom
    false

不同真实事务
    查询该 key 的 wwOrder 或 pruning 已确定 WW
```

若两个不同事务写同 key，却既没有剩余 WW literal 也没有 KnownGraph 中已确定方向，公式直接 UNSAT。这保证 frontier 选择依赖的 per-key write order 是完备的。

#### 7.6 Predicate 可见性

外部写 `w` 对 predicate observation `R` 的可见条件为：

```text
visible(w,R) = depGraph.reaches(writer(w), R)
```

特殊情况：

```text
w 属于 bottom
    visible = true

w 属于 reader 自身
    eventIndex(w) < predicateEventIndex 时为 true，否则为 false
```

这里使用 dependency reachability，而不是 SER 的 `writer <AR reader`。因此 SI 可以让不同 key 的可见版本来自同一个一致快照，而不必把全部真实事务压进一个串行顺序。

#### 7.7 Induced composition

初始编码完成后，`encodeInducedComposition()` 枚举：

```text
dep.from -> dep.to
anti.from -> anti.to

dep.to == anti.from
```

并加入条件 induced edge：

```text
dep.from -> anti.to
guard = dep.guard AND anti.guard
```

Dependency 本身已经通过 `addDepEdge` 进入 induced graph，因此最终图正是：

```text
Dep ∪ (Dep ; AntiDep)
```

随后断言：

```text
inducedGraph.acyclic()
```

若组合边形成自环，solver 直接断言该组合 guard 必须为 false。

#### 7.8 求解与 refinement 循环

求解不是只调用一次 `solver.solve()`：

```text
while solver.solve():
    根据模型选择每个 predicate key 的 frontier
    构造完整 snapshot

    snapshot 与记录结果不一致:
        阻断当前 frontier 组合
        continue

    snapshot 与记录结果一致:
        编码该 snapshot 对应的 PR_RW
        补入新的 Dep ; PR_RW induced edge
        如果加入了新约束:
            continue

    所有 predicate 都不再产生新约束:
        ACCEPT

循环耗尽:
    REJECT
```

查询匹配本身还不是最终 ACCEPT；只有该模型对应的结果变化写已经补成 predicate anti-dependency，并在新 induced acyclicity 约束下重新求解后，模型才完整。

### 8. 谓词约束

每个谓词读先按 query scope 收集相关 key，再区分：

```text
EXTERNAL 且出现在 result.inputs 中
    记录的 source write 必须是该 key 的 snapshot frontier。

EXTERNAL 且没有出现在 result.inputs 中
    由 SAT 模型选择 latest-visible frontier 或无可见写。

INTERNAL
    使用谓词事件之前的最新本地写或已解析内部 source。
```

选出所有 frontier 后，solver 会：

- 将 frontier 与内部固定版本合成为完整 snapshot。
- 用 relation resolver 执行 QueryPlan，并将 `result.values` 按多重集比较。
- 拒绝错误 JOIN、投影、重复结果、遗漏结果和错误 `DISTINCT`。
- 对会改变完整查询结果的后续写生成受 snapshot guard 控制的 `PR_RW`。
- 在新增谓词 anti-dependency 时同步补入相关 `dependency ; anti-dependency` induced edge。

#### 8.1 Frontier 候选

对每个 EXTERNAL key，每个 writer transaction 只保留它对该 key 的最后一次写。这样避免把事务中间版本错误地作为外部快照版本。

若 reader 在谓词事件之前已经写该 key，则：

```text
frontier = reader 在事件之前的最后一次本地写
```

该 frontier 固定为 INTERNAL，不再创建外部 `PR_WR`。

否则，对每个外部候选写保存：

```text
FrontierCandidate {
    write
    visible = depGraph.reaches(writer, reader)
}
```

历史 `result.inputs` 已经包含该 key 时，解析出的 write 是 `fixedWrite`。求解器要求它成为 latest-visible frontier，并建立固定 `PR_WR(source,reader,key)`。

历史未返回该 key 时，候选 source 由 SAT 模型选择。每个非 bottom writer 会获得一个 predicate source guard，并与“该候选被选为 latest frontier”的 selection guard 双向绑定。

#### 8.2 Latest-visible selection guard

候选 `ws` 被选择需要：

```text
selected(ws,R,x)
    = visible(ws,R)
      AND 对每个其他候选 wu:
          NOT(visible(wu,R) AND beforeWrite(ws,wu))
```

也就是 `ws` 可见，并且不存在同 key 上比 `ws` 更晚且同样可见的版本。

若所有候选都不可见，则该 key 的选择是 `ABSENT`：

```text
selected(ABSENT,R,x)
    = AND_w NOT visible(w,R)
```

bottom 初始写通常会使有初始状态的 key 不会成为 `ABSENT`；但公式保留通用的无可见候选情况。

#### 8.3 固定 recorded source

对于记录中明确出现的 source `ws`：

1. `ws` 必须存在于该 key 的 frontier candidates。
2. `ws` 的 writer 必须通过 `PR_WR` dependency 对 reader 可见。
3. 若另一个写 `wu` 位于 `ws` 之后，并且单行规范化结果已经能确定 `wu` 会改变观察，则 `wu` 不能同时位于当前 reader 的 snapshot 中。
4. 完整 snapshot 匹配后，仍会用完整查询重新判断每个 later write；真正改变 JOIN/投影/`DISTINCT` 结果的写生成 `PR_RW`。

第 3 步是提前收紧显然不合法的 source；第 4 步保证复杂查询按完整结果语义处理。

#### 8.4 完整 snapshot 求值

每个 SAT 模型产生：

```text
snapshot = fixedInternalState ∪ selectedExternalFrontiers
```

`RelationResolver` 根据 canonical key 中的 `relation:key` 前缀确定关系。随后在 `MapVisibleState` 上执行完整 `QueryPlan`。

比较方式：

```text
存在 recordedPredicateResult
    evaluation.canonicalEquals(recorded)
    同时比较 inputs 和 values 的规范化多重集

只有旧式 predResults
    比较 evaluation.inputs() 与期望 key/value map
```

因此同一套路径覆盖：

- 单表 scan/filter。
- 多个 INNER JOIN。
- 字段投影和别名。
- 重复行与 bag semantics。
- `DISTINCT`。
- 应返回但遗漏、或不应返回却多出的行。

当前 SI 没有复制 SER 的 row-local 逐 key 快路径。存在 EXTERNAL key 时，即使查询满足 row-local 条件，也仍建立 scope 内 EXTERNAL key frontier 并走完整 snapshot refinement，主要代价是 frontier 数量和 no-good 搜索空间增加。

如果某次 observation 的全部 scope key 都是 INTERNAL，`frontierEntries` 为空，当前 SI 会在创建 `PredicateCheck` 前直接跳过该 observation。此时 `Utils` 仍检查 source、继承和本地逐行成员关系，但 solver 不会再次执行完整 `QueryPlan`，特别是不会完整比较 recorded `result.values`。SER 的 row-local 快路径在 empty-frontier 判断之前执行，因此 row-local all-INTERNAL 情况的结果校验比当前 SI 更完整。对于非 row-local 的 all-INTERNAL 查询，两者当前都没有独立的完整 snapshot solver 路径。

#### 8.5 No-good clause

若当前模型选择：

```text
x -> wx
y -> wy
z -> ABSENT
```

但完整查询结果不匹配，solver 加入：

```text
NOT select(x,wx)
OR NOT select(y,wy)
OR NOT select(z,ABSENT)
```

下一次模型至少改变一个未固定 frontier。固定 recorded source 不加入 clause，因为它不能被后续模型改变；如果所有相关 frontier 都固定且结果仍不匹配，blocking clause 为空，公式直接 UNSAT。

frontier 组合有限，每个 mismatch 至少排除当前组合，所以 refinement 最终会找到合法快照或穷尽候选。

#### 8.6 结果变化与 PR_RW

当完整 snapshot 已匹配记录结果后，对 frontier `ws` 之后的同 key 写 `wu` 做一次替换求值：

```text
before = evaluate(snapshot)
after  = evaluate(snapshot[x := value(wu)])
```

若 `before` 与 `after` 的 canonical result 不同，则建立：

```text
reader --PR_RW(x)--> writer(wu)
```

guard 为：

```text
snapshotGuard AND beforeWrite(ws,wu)
```

其中 `snapshotGuard` 是本次所有 per-key frontier selection guard 的合取。这样 `PR_RW` 只约束产生该完整查询结果的具体快照，不会污染其他 frontier 组合。

新 `PR_RW` 加入 AntiDep 后，solver 立即为所有 `dep.to == reader` 的 dependency 补充：

```text
dep.from -> writer(wu)
guard = dep.guard AND prRw.guard
```

也就是新增的 `Dep ; PR_RW` induced edge。随后重新执行 acyclicity 求解。

#### 8.7 最终 ACCEPT/REJECT

`SISolverInduced.solve()` 返回 `true` 必须同时满足：

```text
每个 WW choice 有合法方向
AND 普通点读的 WR/WW/RW 约束成立
AND 每个 predicate frontier 与 snapshot visibility 一致
AND 每个完整 QueryPlan 结果匹配记录
AND 所有结果变化写已经形成 PR_RW
AND Dep ∪ (Dep ; AntiDep) 无环
```

返回 `false` 表示 mandatory dependency、WW/RW 分支、predicate frontier、no-good clauses 和 induced acyclicity 的合取不可满足。

### 9. MonoSAT 集成

相关位置：

```text
build.gradle
monosat/
src/main/java/verifier/SISolverInduced.java
```

Gradle 在 Java 编译前构建本地 MonoSAT。运行 detector 时必须提供 native library：

```text
-Djava.library.path=build/monosat
```

否则 JVM 无法加载 `libmonosat.so`。

当前 solver 使用 MonoSAT 的两类能力：

- Boolean literal/logic：WW 分支、frontier guard、selection guard、snapshot guard 和 no-good clause。
- Graph theory：dependency reachability 与 induced graph acyclicity。

大型动态阻断条件通过 `assertOr` 提交；条件 graph edge 使用 guard 与 edge literal 双向蕴含，确保 SAT 选择和图状态一致。

## 三个完整判断示例

### 示例一：普通串行历史被接受

```text
T1: W(x,1)
T2: R(x,1), W(x,2)
```

已知和选择出的关系：

```text
T1 --WR(x)--> T2
T1 --WW(x)--> T2
```

二者都是 Dep。没有反向 dependency 或能闭环的 `Dep ; AntiDep`，InducedSI 无环，点读 source 也是 snapshot latest-visible，最终 `ACCEPT`。

### 示例二：write skew 为什么 SI 接受而 SER 拒绝

初始状态 `x=0,y=0`：

```text
T1: R(x,0), R(y,0), W(x,1)
T2: R(x,0), R(y,0), W(y,1)
```

两个事务都读取 bottom 版本，并分别形成指向另一个 writer 的 RW：

```text
T1 --RW(y)--> T2
T2 --RW(x)--> T1
```

在 SER 中，这两条边都必须成为直接 AR 方向，因此得到 `T1 <AR T2 <AR T1`，历史被拒绝。

在 SI 中，它们是 AntiDep，不直接加入 induced graph。bottom 对两个 reader 的 WR dependency 与这些 AntiDep 组合后仍只产生从 bottom 指向真实事务的 induced edge，不形成 `T1/T2` 环，所以该 write skew 可以 `ACCEPT`。

### 示例三：谓词 phantom 如何形成拒绝

假设谓词为 `value > 5`：

```text
T1: W(x,10)
T2: PR(value > 5) -> {(x,10)}
T3: W(x,3)
```

记录 source 产生：

```text
T1 --PR_WR(x)--> T2          Dep
```

若 WW 选择 `T1 --WW(x)--> T3`，并且把 `x=10` 替换成 `x=3` 会改变完整查询结果，则产生：

```text
T2 --PR_RW(x)--> T3          AntiDep
```

组合得到：

```text
T1 --Induced--> T3
```

若其他 mandatory dependency 又要求 `T3 -> T1`，InducedSI 成环，当前 frontier/WW 模型不可用；若所有模型都如此则最终 `REJECT`。

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
src/main/java/graph/MatrixGraph.java
```

`KnownGraph` 构造 SO/WR 已知边、读来源索引、写版本索引和谓词观察集合。`MatrixGraph` 用于 pruning 中的 `Dep ∪ (Dep ; AntiDep)` 组合与环检查。

### SIVerifier

```text
src/main/java/verifier/SIVerifier.java
src/main/java/verifier/SIConstraint.java
src/main/java/verifier/SIEdge.java
```

`SIVerifier` 是验证流程总控，负责 internal check、生成约束、调用 pruning、调用 solver 和输出诊断。`SIConstraint/SIEdge` 保存 WW choice 及其条件 dependency/anti-dependency。

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

### Utils

```text
src/main/java/verifier/Utils.java
```

主路径使用 `verifyInternalConsistency` 做 source 唯一性、事务内 latest write 和谓词继承检查。SI 文件中还保留 `getOrderInSession/reduceEdges` 图辅助方法，但当前 `audit` 主路径没有调用它们，不能把它们算作当前判定步骤。

### Tools

```text
tools/audit-prhist.sh
tools/run_catalog_experiment.py
```

用途：

- `audit-prhist.sh`：递归批量审计 `history.prhist.jsonl`。
- `run_catalog_experiment.py`：按 catalog 跑可复现实验，保存日志、CSV、summary 和机器信息。

SER 中存在的 `validate_prhist_suite.py` 当前没有 SI 版本，因此 SI 尚无同名 oracle suite 校验入口。

### Tests

```text
src/test/java/TestPredicateHistoryLoader.java
src/test/java/TestVerifier.java
src/test/java/BlackBoxSIAuditTest.java
src/test/java/verifier/SIDetectabilityTest.java
src/test/java/verifier/SIVerifierPredicateTest.java
src/test/java/verifier/SIVerifierPredicateIntegrationTest.java
src/test/java/verifier/RelationalPredicateSatTest.java
```

当前测试覆盖 loader、QueryPlan、基础 verifier、write skew、同 key 写冲突、单表谓词、关系 JOIN/投影/`DISTINCT`、遗漏行、结果多重集和 CLI 行为。

与 SER 测试目录相比，SI 当前没有 `SISolverInduced` 的独立 SAT encoding test、独立穷举 SI oracle differential test，也没有 `PruningReachabilityTest` 对应项。现有测试能覆盖已知场景，但不能替代独立 oracle 对随机小历史的系统对拍。

## 当前相较 SER 尚未具备的工程流程

本节只记录当前代码中确实不存在或不完整的流程，不把 SI/SER 模型差异列为缺口。

### 1. Row-local 谓词快路径

SER 对单表、非 `DISTINCT`、逐行独立的 `QueryPlan` 提供 `encodeRowLocalPredicate`，可以逐 key 直接编码返回/未返回条件。SI 的 `SISolverInduced.encodePredicateConstraints` 当前没有对应分支，所有查询都进入：

```text
per-key frontier
  -> complete snapshot
  -> QueryPlan evaluation
  -> no-good refinement
```

该差距有两种影响：

- observation 至少有一个 EXTERNAL key：SI 仍会执行完整 QueryPlan，语义流程存在，差距主要是 scope key 较多时的 frontier 组合和重复求解成本。
- observation 的全部 key 都是 INTERNAL：SI 在 `frontierEntries.isEmpty()` 时直接退出，不创建 `PredicateCheck`。`Utils` 会检查输入版本、相同谓词继承和本地逐行成员关系，但不会完整比较结构化 `result.values`；SER 的 row-local 快路径能够在这一位置之前完成 recorded result 校验。

因此当前 row-local 缺口同时包含性能差距和 all-INTERNAL observation 的结果校验覆盖差距。

### 2. 增量 pruning

SER pruning 维护当前已知 precedence 的传递闭包，并对常见同目标分支做增量检查。SI pruning 当前调用 `InducedGraph.canAddAll`，每次：

```text
copy KnownGraph
  -> add candidate branch
  -> convert to MatrixGraph
  -> compose Dep ; AntiDep
  -> topological cycle check
```

两者都有安全剪枝语义，但 SI 缺少复用基础图和传递信息的增量实现流程。

### 3. 精简冲突核和条件边 witness

SER solver 在 UNSAT 后可以反复重建较小公式，缩减 active WW constraints，并从已知 precedence 提取解释边。SI solver 当前在 UNSAT 后：

```text
conflictEdges = 从当前 KnownGraph 提取可见 induced cycle
conflictConstraints = 全部剩余 constraints
```

而 SAT 选择的条件 WW、RW、PR_WR、PR_RW 并不会完整写回 KnownGraph。因此：

- pruning 阶段已形成的固定环通常可以解释。
- 仅由 MonoSAT 条件边或 predicate refinement 导致的 UNSAT，可能只能报告笼统原因。
- 当前 cycle witness 不等价于最小 UNSAT core。

该缺口影响可解释性，不改变 solver 的布尔 verdict。

### 4. 独立 SI 正确性 oracle

SER 有小事务数 exhaustive AR oracle，可以把直接 solver、verifier 和穷举结果对拍。SI 当前缺少独立实现的 exhaustive snapshot/WW/InducedSI oracle，因此以下正确性保障流程尚未具备：

```text
随机生成小历史
  -> 独立枚举 WW 和合法 snapshot frontier
  -> 独立计算 Dep / AntiDep / InducedSI
  -> 与 SISolverInduced verdict 对拍
```

现有 `SIDetectabilityTest` 和 predicate integration tests 是手工 oracle 场景，不能发现所有组合编码差异。

### 5. Suite oracle 工具

SI `tools/` 当前没有 `validate_prhist_suite.py`。因此带 manifest/oracle 的批量数据集只能通过现有 audit/catalog 工具运行，没有与 SER 相同的独立 suite 结构和 expected verdict 校验入口。

### 不属于缺口的 SER 流程

以下流程不能迁移到 SI：

- 为所有公式可见事务对建立 `ar(A,B) XOR ar(B,A)`。
- 把 `RW/PR_RW` 直接编码成全局 `from <AR to`。
- 要求一个能够解释全部事务的严格串行顺序。
- 因双向 RW 直接成环而拒绝 write skew。

若加入这些流程，SI detector 会退化为 SER detector，改变目标语义，而不是补全 SI。

## 正确性直觉

### Soundness

如果 solver 返回 SAT，则：

1. 内部一致性已经确认 source 唯一性、事务内 program order 和本地写语义。
2. 所有 SO/WR 已知依赖都被放进 dependency graph。
3. 每个同 key writer pair 都选择了一个 WW 方向，per-key 写顺序可用于 latest frontier 比较。
4. 每个普通读对应的条件 RW 都作为 AntiDep 编码。
5. 每个谓词 EXTERNAL key 都选择了 dependency-visible 的 latest frontier，INTERNAL key 使用事件前固定本地状态。
6. 对至少包含一个 EXTERNAL key 的 observation，完整 QueryPlan 求值保证 `inputs`、投影结果、重复行和 `DISTINCT` 与记录一致；all-INTERNAL observation 只具有前述预检保证。
7. 当前匹配 snapshot 下所有会改变完整查询结果的 later write 都已经生成 `PR_RW`。
8. induced graph 包含全部有效 Dep 以及 `Dep ; AntiDep` 条件组合边。
9. MonoSAT 保证 induced graph 无环，且 refinement 已经达到没有新约束的固定点。

因此存在一个满足当前已编码 SI 模型的执行解释。该结论按“当前模型”理解，不能覆盖前述 all-INTERNAL 完整结果校验缺口。

### Completeness

如果真实存在某个合法 SI 解释，则可以按这个解释给所有同 key WW 顺序和谓词 frontier literal 赋值。这个赋值会满足：

- SO/WR 已知边。
- 每个 key 上的 WW 顺序。
- 普通读的 latest-visible 约束。
- 进入 solver 的谓词读 frontier 和结果集合约束。
- 结果变化写对应的 predicate anti-dependency。
- induced graph 无环约束。

因此 solver 不应返回 UNSAT。

### Pruning 和 Coalescing

pruning 只提交反方向已经立即造成 induced cycle 的 choice，因此保持可满足性。

coalescing 把同一事务对上的重复 choices 合并，因为事务级先后关系在同一个解释中必须一致。

predicate frontier 组合数量有限。每次结果 mismatch 都阻断当前组合；每次结果 match 但发现新 `PR_RW` 都永久补入对应 guard 下的 induced edge。因此 refinement 不会无限重复同一个未处理状态，最终返回稳定模型或 UNSAT。

## 当前边界

当前 detector 的稳定路径是：

```text
compact PRHIST + structured QueryPlan predicates + MonoSAT induced SI solver
```

已明确的边界：

- Java loader 当前只接受 `query/result` 形态的 predicate read。
- 支持单表查询和一个或多个 INNER JOIN、表达式、投影、`DISTINCT` 与结果多重集；不接受任意 SQL 文本。
- 当前紧凑格式依赖 `(key,value)` 写版本唯一性。
- `write_id/source_write_id/source_txn/source_op_index` provenance 字段会被 loader 拒绝。
- value 不接受 null、数组和非整数数字；结果投影可以是结构化 JSON，但结构化值只支持相等性。
- TPC-C 多表 SQL-shaped predicate 必须先转换为当前结构化 query，才能被完整验证。
- abort/retry attempt 不进入 `history.prhist.jsonl`；它们应保留在 raw trace 或 manifest 中。
- `--compare-derived-predicate-edges` 是诊断路径，主求解不依赖其物化边。
- 全部 scope key 都分类为 INTERNAL 时，当前 solver 不创建完整 predicate snapshot check；复杂查询以及 SI 缺少 row-local 快路径时的 `result.values` 校验受此边界限制。

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
