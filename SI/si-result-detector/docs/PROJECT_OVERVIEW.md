# SI 检测器当前架构与实现说明

本文档以当前代码为准，说明 SI detector 的判定目标、A/B typed dependency 图语义、剪枝模式、分阶段 MonoSAT 编码、谓词求解和与 SER 的对齐边界。运行命令见 `SI/README.md`。

## 1. 项目定位

SI detector 读取 PRHIST 事务历史，判断是否存在一个能够解释全部点读、写入和谓词读结果的快照隔离执行。

输出为：

```text
[[[[ ACCEPT ]]]]
```

或：

```text
[[[[ REJECT ]]]]
```

- `ACCEPT`：至少存在一组合法的同 key 写顺序、快照 frontier 和 typed dependency 赋值。
- `REJECT`：内部一致性、剪枝或最终 MonoSAT 公式证明不存在这样的 SI 解释。

SI 不要求所有事务组成严格串行顺序。典型 write skew 可以被 SI 接受，因此不能把 SER 的 AR 全序或“所有 RW 都直接判环”逻辑搬入 SI。

## 2. 最终判定使用的图

当前实现采用 Adya typed dependency：

```text
A = {SO, WR, WW, PR_WR}
B = {RW, PR_RW}
InducedSI = A ∪ (A ∘ B)
```

组合 `A ∘ B` 表示：

```text
T1 --A--> T2 --B--> T3
```

产生 induced edge：

```text
T1 ----------> T3
```

最终条件是：

```text
存在合法 WW/frontier 赋值
AND 所有读结果与快照一致
AND InducedSI 无环
```

### 2.1 不是旧 AR 序列图

最终 verdict 不再建立旧式 `ar(T1,T2)` 序列变量，也不要求所有事务两两可比。求解器处理的是带类型和 key witness 的 dependency edge。

### 2.2 MonoSAT 中的实际表示

`SISolverInduced` 的实际数据结构是：

```text
dependencyEdgesA
    保存 A 类 typed edges 及其 guard。

dependencyEdgesB
    保存 B 类 typed edges 及其 guard。

depGraph
    MonoSAT 中的 A 图，也用于 predicate visibility reachability。

inducedGraph
    MonoSAT 中真正执行 acyclic() 的 verdict 图。
```

B 不需要单独执行无环检查，因此没有建立一个独立的 MonoSAT `Graph B`。每加入一条 A 或 B，统一入口 `addDependencyEdge` 都会与另一侧已有边组合，把对应 `A ∘ B` 边加入 `inducedGraph`。无论 A/B 的编码先后如何，组合都会补齐。

因此当前物理实现是“A typed graph + B typed edge 集合 + induced verdict graph”，语义仍严格对应两类 typed dependency，而不是 AR 图。

### 2.3 边类型路由

```text
A:
    SO      session order
    WR      point-read source dependency
    WW      same-key version order
    PR_WR   predicate frontier source dependency

B:
    RW      point-read anti-dependency
    PR_RW   predicate anti-dependency
```

`KnownGraph.putEdge`、剪枝和最终 solver 使用相同的 A/B 分桶约定。

### 2.4 Bottom transaction

初始版本由特殊事务 `T⊥` 表示：

```text
session = -1
txn     = -1
```

`KnownGraph` 保留 bottom 的节点和写版本，便于 source 解析与剪枝。最终 `SISolverInduced.createNodes()` 不为 bottom 创建 MonoSAT 节点：

- bottom 写对 predicate read 的可见性恒为 true。
- `beforeWrite(bottom, real)` 恒为 true。
- `beforeWrite(real, bottom)` 恒为 false。
- 从 bottom 出发的 dependency 不写入真实求解图。
- 指向 bottom 或真实事务自环的 guard 被断言为 false。

这与当前 SER 的 bottom 过滤结构一致。

## 3. 与 SER 的对齐边界

除用户明确暂缓的 GMWR 外，SI 已同步 SER 当前非图语义主结构；保留的差异来自隔离级别定义。

| 模块 | 当前状态 | SI/SER 关系 |
|---|---|---|
| PRHIST loader、History/Event、QueryPlan | 已对齐 | 输入与查询语义一致 |
| 内部一致性预检 | 已对齐 | source、事务内 latest write、相同谓词继承一致 |
| Adya typed edge 模型 | 已对齐 | 都保留边类型和 key witness |
| 分阶段 solver 编码 | 已对齐 | setup、known、WW、RW、predicate、acyclic |
| row-local EAGER | 已对齐 | 求解前逐 key 编码 |
| general query refinement | 已对齐 | 预编码 guarded `PR_*`，模型不匹配时加 no-good |
| predicate source constraint 统计 | 已对齐 | 每个 external `(read,key)` 计数 |
| `NONE/REACHABILITY/SNAPSHOT/PRUN` | 已对齐 | 模式入口一致，内部 oracle 使用各自图语义 |
| bottom 过滤 | 已对齐 | 不进入最终真实事务图 |
| UNSAT constraint 缩减 | 已对齐 | 递归求解禁用冲突提取，外层贪心缩减 WW choices |
| GMWR | 暂未同步 | SI 当前只有 EAGER，按要求不扩展 |
| 最终图公式 | 必须不同 | SER 检查 typed dependency/辅助顺序；SI 检查 `A ∪ (A ∘ B)` |
| visibility/order | 必须不同 | SER 可用辅助串行顺序；SI predicate visibility 使用 A reachability |
| RW/PR_RW | 必须不同 | SI 放入 B，只通过 `A ∘ B` 影响 verdict |

不能同步到 SI 的 SER 行为包括：

- 为全部事务对建立 total AR。
- 把 `RW/PR_RW` 直接当作 A 边。
- 因 B-only 双向边直接拒绝 write skew。

## 4. 端到端控制流

```text
PRHIST
  |
  v
PredicateHistoryLoader
  |
  v
Utils.verifyInternalConsistency
  | false
  +------------------------------------> REJECT
  |
  v
KnownGraph
  - A: fixed SO/WR
  - B: fixed RW/PR_RW（若已有）
  - readFrom / allWrites / txnWrites
  - predicate observations
  |
  v
generateConstraintsSI
  - same-key WW binary choices
  - branch-associated ordinary RW implications
  - optional transaction-pair coalescing
  |
  v
pruning mode
  - NONE / REACHABILITY / SNAPSHOT / PRUN
  | inconsistent
  +------------------------------------> REJECT
  |
  v
SISolverInduced constructor
  1. SETUP
  2. KNOWN_EDGES
  3. WW
  4. RW
  5. PREDICATE
  6. ACYCLIC
  |
  v
MonoSAT solve
  | model has wrong general-query snapshot
  +---- add no-good clause ----+
  |                            |
  +<---------------------------+
  |
  + SAT stable model -----------------> ACCEPT
  |
  + UNSAT -> conflict reduction ------> REJECT
```

## 5. 输入模型

当前公开输入类型为 `PRHIST`。路径可以指向 `history.prhist.jsonl`，也可以指向包含它的历史目录。

```text
hist-00000/
  initial_state.json
  history.prhist.jsonl
  manifest.json
```

Java loader 读取前两个文件；`manifest.json` 由生成器和实验工具使用。

### 5.1 初始状态

```json
[
  {"key": "kv:0", "value": 0},
  {"key": "kv:1", "value": 1}
]
```

这些写被放入 bottom transaction。

### 5.2 事务和操作

每行一个已提交事务：

```json
{"session":0,"session_seq":1,"txn":1001,"status":"commit","ops":[{"type":"r","key":"kv:0","value":0},{"type":"w","key":"kv:0","value":10}]}
```

支持：

- `r`：点读。
- `w`：写。
- `pr`：结构化谓词读。

谓词读示例：

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

`result.inputs` 用于定位查询实际依赖的可见版本；`result.values` 是投影后的规范结果，并按多重集语义比较。

### 5.3 查询能力

结构化 `QueryPlan` 支持：

- 单表 scan/filter。
- 一个或多个 INNER JOIN。
- 字段路径、别名和投影。
- `DISTINCT`。
- bag/multiset 结果。
- `=`、`>`、`<`、`%`、`AND` 和括号。

当前不接受任意 SQL 文本。紧凑格式依赖 `(key,value)` 能唯一定位写版本，并拒绝 `write_id`、`source_write_id`、`source_txn`、`source_op_index` 等 provenance 字段。

## 6. 内部一致性预检

`Utils.verifyInternalConsistency` 在构图和 MonoSAT 之前运行，只拒绝由输入记录和事务内顺序已经能证明的矛盾。

### 6.1 点读

对每个 `r(key,value)`：

- `(key,value)` 必须对应唯一 source write。
- 事务内 source 不能来自未来，也不能跳过读前更晚的同 key 本地写。
- 外部 source 必须是 source 事务对该 key 的最后一次写。
- reader 在读前已有同 key 本地写时不能继续读取外部版本。

### 6.2 谓词读

- `result.inputs` 中 key 不能重复。
- 每个输入必须解析到唯一且已提交的 source。
- 输入 key 必须位于 query scope。
- 外部 source 必须是 source 事务的最后一次同 key 写。
- 本地 source 必须是谓词事件前最后一次同 key 写。
- recorded result 的 `inputs` 必须与解析结果一致。

预检按 `predicate.identity()` 跟踪同一事务中前一次相同谓词读：

- 两次相同谓词之间没有本地更新时，结果逐 key 继承。
- 有本地更新时，以当前事件前最后一次本地写为准。
- 不同 predicate identity 的先前读取不会把 key 错误标成 INTERNAL。

JOIN、投影、重复行和 `DISTINCT` 的完整结果由 solver 的 QueryPlan 求值处理。

## 7. KnownGraph

`KnownGraph` 负责：

1. 为 history 事务建立 A/B/readFrom 节点。
2. 为相邻 session 事务加入 SO。
3. 解析点读 source 并加入 WR。
4. 建立全部写版本索引。
5. 收集 predicate observations。

主要索引：

```text
knownGraphA
    SO、WR、已固定 WW、PR_WR。

knownGraphB
    RW、PR_RW。

readFrom
    点读的精确 WR 来源与 key。

allWrites
    全部 WriteRef，包括 bottom 初始写。

writesByKeyValue
    (key,value) -> source candidates。

txnWrites
    (txn,key) -> 事务内写事件位置。

predicateObservations
    谓词事件、tuple sources、covered key 分类。
```

### 7.1 Predicate key 分类

对 query scope 中的 key：

```text
INTERNAL
    当前谓词事件之前本事务已经写过该 key；
    或同一 predicate identity 的更早 observation 已覆盖该 key。

EXTERNAL
    没有上述事务内依据，需要 solver 选择 snapshot frontier。
```

每个 observation 用 BitSet 和默认值/例外集合压缩保存分类。

## 8. WW/RW 约束生成

`SIConstraint` 表示一对 writer 事务的方向选择：

```text
edges1: writer1 before writer2
edges2: writer2 before writer1
```

`SIEdge` 保存：

```text
from / to / EdgeType / keys
```

`keys` 能保留同一 typed edge 的多个 witness key，`getKey()` 返回首个 key，以适配当前按 key 的 WW 索引接口。

### 8.1 约束生成阶段的 RW

若：

```text
Tsource --WR(x)--> Treader
Tsource --WW(x)--> Twriter
```

则对应分支带有：

```text
Treader --RW(x)--> Twriter
```

生成的 `SIConstraint` 因此可以同时包含 WW 和 RW。两处消费者行为不同：

- `REACHABILITY/PRUN` 试加一个分支时使用该分支的全部 WW/RW typed edges。
- 最终 `SISolverInduced` 的 WW 阶段只读取 WW；随后 RW 阶段从 `readFrom + wwOrder` 重新生成相同的 guarded RW。

这样最终公式中的 RW 来源统一，不依赖 constraint 中重复保存的派生边。

### 8.2 Coalescing

默认把同一 writer 事务对上的多个 key/读依赖合并到一个 `SIConstraint`，共享一个方向 literal。`--no-coalescing` 使用未合并结构，主要用于约束规模对比。

coalescing 合并的是 choice 组织结构，不会把 A/B 类型抹掉。

## 9. 四种剪枝模式

CLI 通过：

```text
--pruning-mode NONE|REACHABILITY|SNAPSHOT|PRUN
```

选择模式，默认 `REACHABILITY`。`--no-pruning` 强制覆盖为 `NONE`。

### 9.1 NONE

不提前固定任何 WW choice，直接进入最终 solver。

### 9.2 REACHABILITY

实现位于 `Pruning.java`。对每个 constraint 的两侧分别调用 `InducedGraph.canAddAll`：

```text
两侧都成环     -> pruning REJECT
仅一侧成环     -> 固定另一侧并写回 KnownGraph
两侧都不成环   -> 保留给 MonoSAT
```

`canAddAll` 复制当前 KnownGraph，加入该侧全部 typed edges，再计算：

```text
A ∪ (A ∘ B)
```

它按轮执行，直到本轮固定数量低于阈值或 constraints 为空。

### 9.3 SNAPSHOT

实现位于 `Prun.java`。它根据点读和 recorded predicate source 建立固定 observation，维护 A 的增量传递闭包，传播同一 reader 的共享快照下界，并固定能够推出的 writer 顺序。

关键约束：

- snapshot visibility 只使用 A reachability。
- 推出的 WW/PR_WR 等 A 边才进入 A closure。
- 推出的 RW 是 B 边，只写入 B，绝不折叠进 A closure。
- SNAPSHOT 不执行逐 constraint 的 induced branch 双侧试加。

### 9.4 PRUN

PRUN 在 SNAPSHOT 传播基础上，额外执行 SI induced-graph branch pruning。每轮先检查当前 `A ∪ (A ∘ B)`，再尝试固定只有一侧可行的 constraint，之后继续共享快照传播，直到固定点。

### 9.5 剪枝与最终 verdict

未在剪枝阶段判定 inconsistent 的历史最终都进入同一个 `SISolverInduced`。剪枝只提前物化必然方向、减少剩余 choices；B 始终保持 B 语义，最终公式始终检查同一个 InducedSI。

`constraint-stat` 只运行内部一致性、约束生成和所选剪枝，不启动最终 MonoSAT，用于比较：

```text
constraints_before / constraints_after
implications_before / implications_after
pruning_inconsistent
```

## 10. 求解核心：分阶段 MonoSAT 编码

最终求解器是 `SISolverInduced`。它不构造事务的全序 AR，也不要求任意两个事务可比较；它只为待定 WW 方向、谓词 source 选择和图可达性建立 Boolean/graph theory 约束，并直接判定：

```text
A = SO ∪ WR ∪ WW ∪ PR_WR
B = RW ∪ PR_RW
InducedSI = A ∪ (A ∘ B)

SAT  <=> 在当前已编码范围内，存在一组 WW/source 选择，使 InducedSI 无环，且所有进入 EAGER/PredicateCheck 的谓词结果一致
```

### 10.1 求解器内部状态

| 状态 | 含义 | 核心不变量 |
| --- | --- | --- |
| `solver` | MonoSAT 主求解器 | Boolean 选择、图边、可达性和无环性处于同一公式中 |
| `depGraph` | A 图 | 只包含 `SO/WR/WW/PR_WR`；谓词 snapshot 可见性只查询该图 |
| `inducedGraph` | verdict 图 | 包含所有激活的 A 边和所有激活的 `A ∘ B` 合成边 |
| `dependencyEdgesA` | 已登记的 guarded A typed edges | 新 B 边到达时可与所有既有 A 边组合 |
| `dependencyEdgesB` | 已登记的 guarded B typed edges | B 本身不直接进入 verdict 图 |
| `wwOrder` | `(writer1,writer2,key) -> Lit` | 普通 RW、谓词 frontier 和 `PR_RW` 共用同一 WW 方向变量 |
| `guardedEdgesByGuard` | guarded typed-edge 去重表 | 相同 guard 下相同语义边只编码一次 |
| `inducedEdgesByGuard` | guarded induced-edge 去重表 | 相同 guard 下相同合成端点只编码一次 |
| `writesByKey` | key 到全部版本的索引 | bottom 优先，其后按事务 id 和事件位置稳定排序 |
| `predicateChecks` | general 查询的延迟快照检查 | 只负责模型验证和 no-good refinement，不临时补图边 |

bottom transaction 表示初始版本，不创建 MonoSAT 图节点。来自 bottom 的依赖由“初始版本天然先于真实事务”的规则处理，而不是把 bottom 当作普通事务加入环检测。

### 10.2 六个编码阶段

构造函数按固定顺序完成：

```text
SI_GRAPH_ENCODE_SETUP
    -> SI_GRAPH_ENCODE_KNOWN_EDGES
    -> SI_GRAPH_ENCODE_WW
    -> SI_GRAPH_ENCODE_RW
    -> SI_GRAPH_ENCODE_PREDICATE
    -> SI_GRAPH_ENCODE_ACYCLIC
```

`SIVerifier` 另记录外层 `SI_GRAPH_ENCODE` 和 `SI_GRAPH_SOLVE`。固定分阶段的意义不仅是统计耗时：后续阶段会复用前面已经建立的 A 图、WW guard 和 typed edges。

阶段与代码入口一一对应：

| 阶段 | `SISolverInduced` 入口 | 产物 |
| --- | --- | --- |
| SETUP | `createNodes` | 两张图的真实事务节点 |
| KNOWN_EDGES | `encodeKnownEdges` | 固定 A/B typed edges |
| WW | `encodeWwChoices`、`addConstraintSide` | 分支 literal、guarded WW、`wwOrder` |
| RW | `encodeRwFromWrAndWw` | 由 WR+WW 派生的 guarded B/RW |
| PREDICATE | `encodePredicateConstraints` | frontier、guarded `PR_*`、general checks |
| ACYCLIC | `inducedGraph.acyclic()` | 最终 InducedSI 无环断言 |

### 10.3 SETUP：节点和版本索引

构造开始先按 key 建立 `writesByKey`，为每个 `WriteRef` 分配稳定 id；SETUP 阶段再为每个真实事务分别创建一个 `depGraph` 节点和一个 `inducedGraph` 节点。两个图节点一一对应，但承载的边语义不同。

### 10.4 KNOWN_EDGES：固定 typed dependency

KnownGraph A/B 中的全部受支持边都经过 `addDependencyEdge(edge, Lit.True)`：

```text
SO / WR / WW / PR_WR -> A
RW / PR_RW           -> B
```

因此 known `PR_WR/PR_RW` 不是只供日志或冲突标签使用：它们和普通依赖经过完全相同的图编码，能够改变最终 ACCEPT/REJECT。

### 10.5 WW：一个二选一 guard 控制一个 constraint

每个未被剪枝固定的 `SIConstraint` 创建一个 fresh literal `forward`：

```text
forward       => 选择 constraint.edges1 中的 WW
NOT forward   => 选择 constraint.edges2 中的 WW
```

每条 WW 同时登记为：

```text
wwOrder[(from, to, key)] = guard
```

同一方向被多次登记时，guard 取 OR。反向查询直接返回正向 guard 的否定；known WW 返回常量 true/false；若两个真实 writer 对同一 key 的先后既不在 `wwOrder` 也不在 known WW 中，公式立即置为 false，而不是猜一个未受 constraint 约束的新顺序。

`SIConstraint` 中可能携带生成阶段推导出的 RW 边，但本阶段只消费 WW。这样 RW 只有下一阶段的统一推导来源，避免一部分 RW 随 constraint side 重复进入求解语义。

### 10.6 RW：由同一 WW guard 派生 B 边

对每条真实读来源：

```text
source --WR(key)--> reader
source --WW(key)--> laterWriter
```

求解器创建：

```text
reader --RW(key)--> laterWriter
guardRW = wwOrderLiteral(source, laterWriter, key)
```

这条 RW 经统一入口登记到 B。因而 WW 分支变化时，与之对应的 RW 会在同一个模型中同步启用或关闭，不存在“WW 选了左侧、RW 仍沿用右侧”的脱节。

### 10.7 PREDICATE：先编码依赖，再验证结果

每个 predicate observation 依次执行：

1. 将 recorded tuple sources 建成 `key -> source write`；同一 key 出现多个 source 立即令公式 UNSAT。
2. 按 `QueryScope` 从 `writesByKey` 取出相关版本，结果按 scope cache 复用。
3. row-local `QueryPlan` 尝试完整 EAGER 编码。
4. 其他查询为每个 EXTERNAL key 建立 frontier，预编码所有可能激活的 `PR_WR/PR_RW`，并保存 general `PredicateCheck`。
5. 每次 `createKeyFrontier` 将 `Predicate source constraints` 增加一；该指标统计 external `(predicate read,key)`，不是 predicate read 事件数。

### 10.8 ACYCLIC：直接约束 verdict 图

所有初始 typed edges 和当时可生成的组合边进入图后，最终阶段只断言：

```java
solver.assertTrue(inducedGraph.acyclic());
```

后续 general refinement 只追加 Boolean no-good clause。求解不会切换到 AR 图，也不会在模型得到后重建另一套 verdict 图。

## 11. 两图如何实际参与冲突判断

### 11.1 唯一入口 `addDependencyEdge`

known、WW、RW、`PR_WR` 和 `PR_RW` 都必须经过 `addDependencyEdge(edge, guard)`。它先执行三类边界处理：

- `guard=false`：该候选不可能激活，跳过。
- source 是 bottom：不创建真实图边；初始版本不参加环。
- 真实自环或 target 是 bottom：断言 `NOT guard`，禁止导致非法边的选择。

然后按 edge type 分流：

```text
add A:                         add B:
  保存 guarded A                保存 guarded B
  A -> depGraph                 B 不直接进 inducedGraph
  A -> inducedGraph             对每个 A(x,u) 且 B(u,y)
  对每个 B(u,y)                   添加 induced(x,y)
    若 A(x,u)，添加 induced(x,y)
```

A 到达时扫描已有 B，B 到达时扫描已有 A，所以 `A ∘ B` 的生成与插入先后无关。组合要求中间事务严格相接：

```text
A(x, u, guardA) AND B(u, y, guardB)
    => induced(x, y, guardA AND guardB)
```

这正是“实际使用边判断冲突”的位置：最终 `acyclic()` 检查的 `inducedGraph` 中，A 是直接图边，B 通过每一条可成立的 `A ∘ B` 路径成为合成图边。孤立 B 不进入 verdict，B 也不会被错误折叠进 A closure。

### 11.2 guard 与图边是等价关系

对条件边，`bindGraphEdge` 同时加入：

```text
guard -> graphEdge
graphEdge -> guard
```

即 `graphEdge <=> guard`。固定边使用 `guard=true` 并直接断言图边。双向绑定很关键：如果只有 `guard -> graphEdge`，虽然 guard 为真时边必须存在，但 theory solver 仍可能让无 guard 支撑的图边取值混乱；当前实现明确保证图中条件边和产生它的选择完全一致。

对合成边：

```text
inducedEdge <=> (guardA AND guardB)
```

如果合成端点相同形成 `x -> x`，不向图中添加自环，而是直接断言 `NOT (guardA AND guardB)`。这会排除同时激活该 A/B 组合的模型，语义上等价于无环约束禁止该自环。

### 11.3 三个必须始终成立的不变量

求解正确性依赖以下不变量：

1. 每条已激活 A typed edge 都同时存在于 `depGraph` 和 `inducedGraph`。
2. 对每个已激活的 `A(x,u)` 与 `B(u,y)`，`inducedGraph` 都存在 `x -> y`；任何 B 都不会单独作为 `u -> y` 加入该图。
3. predicate visibility 只读取 `depGraph.reaches(writer,reader)`，最终冲突只读取 `inducedGraph.acyclic()`；两种图用途不混用。

因此当前求解器的核心对象确实是 typed-edge A/B 和由它们构造的两张 MonoSAT 图，不是旧式 AR 序列图。

## 12. Predicate 求解核心

### 12.1 候选版本与可见性 literal

对一个 external key，候选集合只保留每个外部 writer transaction 对该 key 的最后一次写；同一事务更早的写不会成为跨事务 snapshot source。可见性定义为：

```text
visible(write, reader)
    write 属于 bottom                         = true
    write 与 reader 同事务且位于读事件之前      = true
    write 与 reader 同事务但位于读事件之后      = false
    write 属于其他真实事务                     = depGraph.reaches(writer, reader)
```

这里的 `reaches` 是 MonoSAT 图 theory literal，不是在编码时把当前 A 图做一次静态闭包。后续 WW 或 `PR_WR` guard 改变 A 图时，可见性会随同一个 SAT 模型变化。

版本先后 `beforeWrite(left,right)` 使用以下统一规则：

```text
同一个 WriteRef                     = false
同事务写                            = event index 比较
bottom -> real                      = true
real -> bottom                      = false
不同真实事务、同一 key              = wwOrderLiteral(leftTxn,rightTxn,key)
```

### 12.2 latest-visible frontier 的公式

令候选写 `s` 的可见性为 `V(s)`，`W(s,u)` 表示同一 key 上 `s` 先于 `u`。候选 `s` 被选择为 latest-visible source 的 guard 是：

```text
Select(s) = V(s) AND ∧[u != s] NOT(V(u) AND W(s,u))
```

即 `s` 自身可见，而且不存在一个也可见、并在 WW 次序上晚于 `s` 的候选。没有任何候选可见时，frontier 选择 ABSENT：

```text
Select(ABSENT) = ∧[u] NOT V(u)
```

若 reader 在谓词事件前已经写过该 key，最后一次本地写是固定 source，frontier 不再创建外部选择；recorded source 与该本地版本不同会直接导致 UNSAT。

### 12.3 source 选择如何变成 PR typed edges

对每个非 bottom 候选 source `s`，求解器预编码：

```text
s --PR_WR(key)--> reader
guard = Select(s)
```

再对每个位于 `s` 之后且会改变谓词结果的写 `u` 预编码：

```text
reader --PR_RW(key)--> u
guard = Select(s) AND W(s,u)
```

`PR_WR` 进入 A，既影响后续 snapshot visibility，也直接进入 verdict；`PR_RW` 进入 B，只通过 `A ∘ B` 参与 verdict。两类边仍调用同一个 `addDependencyEdge`，不存在仅记录在 observation 上但未进入 MonoSAT 的旁路。

`writeChangesPredicateResult` 先比较 source/later 是否匹配谓词；匹配状态相同时，row-local 情况优先比较 canonical row contribution，无法使用该结果时再退回 key/value 差异。它决定是否需要 `PR_RW`，但 general 查询最终仍由完整 `QueryPlan` refinement 校验整个 snapshot。

### 12.4 recorded source 的固定编码

历史显式记录 source 时，source 必须出现在该 key 的 frontier candidates 中，否则公式置为 false。随后：

- 普通真实 source 固定加入 `PR_WR(source,reader,key)`；bottom 或 reader 自身 source 则直接断言其可见性。
- 对 source 之后、会改变谓词结果的每个 writer 加入 `PR_RW(reader,writer,key)`，guard 为对应的 `W(source,writer)`。

也就是说 recorded source 不是求解器可以替换的建议值，而是待验证的固定读来源。

### 12.5 row-local EAGER

`QueryPlan.isRowLocal()` 为 true 时，求解器尝试在首次 `solve()` 前完成全部约束：

1. 用 recorded inputs 执行查询，做 canonical `inputs/values` 校验，并确认每个 recorded source 的实际值等于 recorded input。
2. INTERNAL key 使用谓词读之前的最后本地写；有 recorded source 时必须是同一 `WriteRef`，没有 recorded source 时该本地版本必须对结果无贡献。
3. EXTERNAL 且有 recorded source 时，按上一节固定 `PR_WR/PR_RW`。
4. EXTERNAL 且没有 recorded source 时，仍为所有 frontier candidate 预编码 guarded `PR_WR/PR_RW`。
5. 对每个会错误进入结果的 bad write `b`，加入阻止它成为 latest-visible 的子句：

```text
NOT V(b)
OR ∨[good g] (V(g) AND W(b,g))
```

该子句要求 bad write 要么不可见，要么被一个更晚且可见的 good write 覆盖。row-local 全 INTERNAL observation 在 `rowLocalSnapshotValid` 成功时也会在本路径完成结果校验，不依赖 external frontier 是否存在。

为避免重复执行行级表达式，compact matcher 使用按 write id 索引的缓存；一般 row contribution 使用 solver-local、有容量上限的 LRU cache。若 EAGER 无法证明 recorded snapshot 合法，则转入 general 路径，而不是直接接受。

### 12.6 general QueryPlan 与 no-good refinement

JOIN、`DISTINCT` 或其他非 row-local 查询按“预编码全部依赖 + 模型级结果校验”求解：

1. 为 scope 中每个 EXTERNAL key 建立 frontier。
2. 在第一次 `solve()` 前，为每个可能 source 预编码 selection-guarded `PR_WR/PR_RW`。
3. 对非 frontier 的 INTERNAL key，取最后本地写或 recorded source，组成 `fixedSnapshot`。
4. MonoSAT 给出模型后，从每个 frontier 选出模型中的 latest-visible candidate；无可见候选则该 key 为 ABSENT。
5. 合并 `fixedSnapshot` 与这些 candidate values，执行完整 `QueryPlan`，并与 recorded result 做 canonical 比较。

若模型选择为 `(s1, s2, ..., sn)` 且结果不匹配，求解器加入否定该组合的 no-good：

```text
NOT Select_1(s1) OR NOT Select_2(s2) OR ... OR NOT Select_n(sn)
```

实现没有额外创建 `Select` 变量，而是直接展开其否定：

- 已选 `s`：加入 `NOT V(s)`，以及每个 `V(u) AND W(s,u)`；只要 source 不再可见或出现更晚可见版本，原选择就被改变。
- ABSENT：加入所有可取候选的 `V(u)`；只要任一候选变为可见，原 ABSENT 选择就被改变。
- fixed frontier：不进入 no-good，因为它没有可替换选择。

如果展开后 clause 为空，说明错误 snapshot 完全由固定状态决定，公式直接置为 false。否则重新调用 MonoSAT，直到找到查询结果一致的模型或穷尽全部可能组合。

refinement 阶段不新增 `PR_WR/PR_RW`。这些边已经按 `Select(s)` guard 在编码阶段进入 A/B，因此每次新模型都自动激活与当前 source 选择一致的 typed edges，并立即受同一个 `inducedGraph.acyclic()` 约束。

### 12.7 当前 general all-INTERNAL 边界

general 路径在 scope 中没有 EXTERNAL key 时会直接跳过，不创建 `PredicateCheck`。因此当前存在两类边界：

- 非 row-local all-INTERNAL 查询不会再次执行完整 JOIN/`DISTINCT` snapshot 求值。
- row-local all-INTERNAL 通常在 EAGER 的 `rowLocalSnapshotValid` 中完成校验；但如果该校验失败而回退 general，general 仍会因没有 EXTERNAL frontier 而跳过，不能把这条 fallback 路径描述为已经完整拒绝。

这些是按当前代码记录的实现边界，不属于 `A/B` 两图公式本身。

## 13. SAT 循环、判定与冲突提取

### 13.1 完整求解流程

```text
build formula:
    create real-transaction nodes
    encode fixed known A/B edges
    create one Boolean branch per unresolved WW constraint
    derive guarded RW from WR + WW
    encode predicate frontiers and all guarded PR edges
    assert acyclic(InducedSI graph)

solve/refine:
    while MonoSAT has a model:
        evaluate every general predicate snapshot in that model
        if any mismatch:
            add no-good clauses for the mismatching selections
            continue
        return ACCEPT
    return REJECT
```

所以 ACCEPT 的含义不是“图暂时无环”这么单一，而是存在同一组 guard 赋值同时满足：

- 每个 unresolved WW constraint 恰好选择一侧。
- 普通 RW 与选择的 WW 一致。
- predicate source/frontier、`PR_WR/PR_RW` 与 A 图可见性一致。
- 所有实际进入 EAGER 或 general `PredicateCheck` 的 recorded query result 与模型 snapshot 一致。
- `A ∪ (A ∘ B)` 无环。

详细谓词指标只在 `--solver-stats` 时输出；六个编码阶段和外层求解耗时始终进入 profiler。

### 13.2 REJECT 后的 constraint 缩减

外层 solver 默认在 UNSAT 后执行解释缩减：

```text
if solve(constraints = empty) is UNSAT:
    conflictConstraints = empty
    conflictEdges = known InducedSI cycle witness
else:
    core = all remaining WW constraints
    for each constraint c in traversal order:
        if solve(core - c) is still UNSAT:
            core = core - c
    conflictConstraints = core
    conflictEdges = encoded known typed edges among core transactions
```

每次试删都会构造一个新的 `SISolverInduced`，重新执行相同的 A/B、predicate 和 acyclic 公式；递归实例设置 `collectConflicts=false`，不会嵌套缩减。

最终 constraint 集合是按当前遍历顺序得到的 deletion-minimal core：再删除其中任意一个已保留 constraint，当前检查会变为 SAT；它不保证是基数最小或全局唯一的 UNSAT core。

如果不需要任何待定 WW 就已经 UNSAT，KnownGraph 的 cycle witness 会把 `A ∘ B` 合成边展开为实际的 A typed edge 和 B typed edge，而不是只报告无类型的合成端点。对于依赖条件 predicate guard 才形成的矛盾，当前冲突输出仍可能无法还原完整动态模型 witness；这不影响 verdict，只限制解释的完整度。

## 14. CLI

`Main` 提供四个子命令：

```text
audit
constraint-stat
stat
dump
```

### 14.1 audit

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  audit --pruning-mode PRUN /path/to/hist-00000
```

主要参数：

```text
--pruning-mode NONE|REACHABILITY|SNAPSHOT|PRUN
--no-pruning
--no-coalescing
--dot-output
--compare-derived-predicate-edges
--solver monosat
--solver-stats
```

`--compare-derived-predicate-edges` 创建独立诊断图，只比较旧式物化 `PR_WR/PR_RW` 数量；生产 verdict 不读取该图。

`--solver-timeout-seconds` 当前被 CLI 解析，但尚未传入 MonoSAT 后端，不能把它视为已生效的超时机制。

### 14.2 constraint-stat

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  constraint-stat --pruning-mode SNAPSHOT /path/to/hist-00000
```

该命令要求显式提供 `--pruning-mode`。

## 15. 关键文件

```text
src/main/java/Main.java
    CLI、pruning mode 和 constraint-stat。

src/main/java/history/loaders/PredicateHistoryLoader.java
    PRHIST loader。

src/main/java/history/query/
    Query AST、scope、row-local 判定、求值和 canonical result。

src/main/java/graph/KnownGraph.java
    A/B 分桶、SO/WR、写索引和 predicate observations。

src/main/java/graph/MatrixGraph.java
    KnownGraph 剪枝/诊断中的图组合与环检查。

src/main/java/verifier/SIVerifier.java
    验证总控、constraint 生成、模式分派和诊断。

src/main/java/verifier/SISolverInduced.java
    分阶段 typed-edge MonoSAT 编码和最终 verdict。

src/main/java/verifier/Pruning.java
    REACHABILITY 模式。

src/main/java/verifier/Prun.java
    SNAPSHOT/PRUN 固定点传播。

src/main/java/verifier/SIConstraint.java
src/main/java/verifier/SIEdge.java
    WW choice 与 typed edge 数据结构。
```

## 16. 测试覆盖

当前测试包括：

- loader、QueryPlan 和 MatrixGraph。
- write skew、同 key 冲突、point-read RW。
- known `PR_WR/PR_RW` 实际进入 verdict 图。
- row-local 与 relational JOIN/投影/重复行/`DISTINCT`。
- 六阶段编码 profiler。
- SNAPSHOT/PRUN 的 A-only closure 与 B 路由。
- predicate source constraint 计数和冲突缩减。
- CLI 四种 pruning modes 与 `constraint-stat`。
- 160 组固定随机种子的三事务 A/B 图差分测试。

差分测试对每个 case 穷举 WW 两个方向，独立计算 `A ∪ (A ∘ B)` 的传递闭包和环，再分别对比 NONE、REACHABILITY、SNAPSHOT、PRUN 与最终 solver verdict。

当前全量结果为：

```text
144 tests
0 failures
0 errors
2 skipped
```

## 17. 当前边界

- 按要求尚未为 SI 引入 GMWR；当前 predicate solving 为 EAGER/general refinement。
- general 路径在全部 scope key 都为 INTERNAL 时不创建 `PredicateCheck`：非 row-local 查询没有第二次完整 QueryPlan snapshot check，row-local EAGER 校验失败后的 fallback 也会跳过该检查。
- REACHABILITY 对每个分支复制 KnownGraph 并重算 MatrixGraph；SNAPSHOT/PRUN 已维护增量 A closure，但 induced branch oracle 仍使用 SI 图试加。
- `--solver-timeout-seconds` 尚未连接到后端。
- 只支持结构化 QueryPlan，不接受任意 SQL 文本。
- 紧凑 PRHIST 要求 `(key,value)` source 唯一。
- `tools/` 当前只有 `audit-prhist.sh` 和 `run_catalog_experiment.py`，没有 SI 版 `validate_prhist_suite.py`。
- 冲突 constraint 集合经过贪心缩减，但不承诺全局最小 core，也不承诺完整还原某个 UNSAT 动态 guard 模型。

## 18. 新人阅读顺序

1. `SI/README.md`：构建和命令行。
2. `src/main/java/Main.java`：模式入口。
3. `PredicateHistoryLoader.java`：输入到 History。
4. `KnownGraph.java`：A/B、readFrom、predicate observations。
5. `SIVerifier.java`：约束生成、剪枝分派和诊断。
6. `Pruning.java` 与 `Prun.java`：四种模式的差异。
7. `SISolverInduced.java`：分阶段编码、predicate frontier、induced graph。
8. `SISolverInducedStageTest`、`PrunTest`、`SISolverInducedParityTest`、`SISolverInducedDifferentialTest`：用回归测试核对实际语义。
