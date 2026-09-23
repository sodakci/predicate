# SI 检测器当前架构与实现说明

本文按批准的 SI 对齐契约和 2026-09-23 当前代码说明判定目标、typed dependency 与 VIS、分阶段分析/剪枝/编码、谓词求解和 SER 对齐边界。运行命令见 `SI/README.md`，验证状态以实施计划及变更记录为准。

## 1. 项目定位

SI detector 读取 PRHIST 事务历史，判断是否存在一个能够解释全部点读、写入和谓词读结果的快照隔离执行。

输出为：

```text
SI audit result: ACCEPT
```

或：

```text
SI audit result: REJECT
```

- `ACCEPT`：至少存在一组合法的同 key 写顺序、快照 frontier 和 typed dependency 赋值。
- `REJECT`：内部一致性、剪枝或最终 MonoSAT 公式证明不存在这样的 SI 解释。
- `ERROR`：解析、不支持的查询或运行异常，通过 CLI 异常流程输出；不属于隔离级别判定。

SI 不要求所有事务组成严格串行顺序。典型 write skew 可以被 SI 接受，因此不能把 SER 的 AR 全序或“所有 RW 都直接判环”逻辑搬入 SI。

## 2. 最终判定使用的图

保留原始 typed dependency，并用共享 VIS 的正负分支补足快照约束：

```text
A_t = {SO, WR, WW, PR_WR}
B_t = {RW, PR_RW}
A_s = A_t ∪ {W→R | VIS(W,R)}
B_s = B_t ∪ {R→W | NOT VIS(W,R)}
InducedSI = A_s ∪ (A_s ∘ B_s)
```

这里的 VIS 辅助关系不是新的 PR 类型；最终要求同 key WW、完整查询结果和 `InducedSI` 无环同时成立。

### 2.1 不引入事务串行顺序

每个实际消费的外部事务对 `(W,R)` 共用一个 VIS literal。`VIS(W,R)` 与 `VIS(R,W)` 是不同变量，不要求互补，允许两者均 false。无环 induced 图的拓扑序只用于构造提交先后与每个 reader 的开始 cut，不要求事务串行执行。

### 2.2 MonoSAT 中的实际表示

| 结构 | 职责 |
| --- | --- |
| `dependencyEdgesA/B` | 保留带 guard 的原始 typed edges。 |
| `visibilityChoices` | 外部事务对共享 VIS；正分支支持辅助 A，负分支支持辅助 B。 |
| `incomingDependencyA/outgoingDependencyB` | 按中间事务索引 typed 和辅助支持。 |
| `inducedGraph` | 唯一 native 图，承载 A_s direct 和完整 A_s∘B_s。 |

初始依赖、谓词约束和 VIS 都登记后，按中间事务组合 typed×typed、typed×aux、aux×typed、aux×aux，封闭物理边支持并断言无环。不再用独立 A 可达性 native 图解释快照。

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

| 模块 | 对齐内容 | SI 语义边界 |
| --- | --- | --- |
| PRHIST/QueryPlan | 输入、能力检查、完整 bag/provenance | 支持 row-local 与非 DISTINCT 单调 JOIN。 |
| 公共分析 | 共享写索引、scope、行贡献缓存 | 不创建 SAT 对象、不提交候选顺序事实。 |
| 谓词准备/剪枝 | prepared observation/key、PR_WR 固定点、GMWR residual items | 使用唯一 SI Oracle；准备结束后由 verifier 单向执行 WW-feedback，不在传播器内回调 WW。 |
| 编码/求解 | 阶段化、物理边复用、单次 solve、原生 assumption 冲突 | 使用共享 VIS 与完整 induced，而非 SER 事务顺序。 |
| B 关系 | 保留 RW/PR_RW 类型和 guard | 只经 A_s∘B_s 进入 verdict，不直接将 B 判环。 |

不能把 SER 的串行 orderLiteral、A+B 判环或模型事后补救搬入 SI。B-only 双向反依赖的 write skew 是允许行为。

## 4. 端到端控制流

```text
PRHIST loader
  -> 查询能力检查（不支持 -> ERROR）
  -> 内部一致性（矛盾 -> REJECT）
  -> KnownGraph + WW/RW choices
  -> 唯一 SIReachabilityOracle
  -> WW reachability pruning
  -> PredicateAnalysis + PredicatePruning
     写/source/行贡献分析、prepared observations、PR_WR 固定点
     GMWR items 与可选预传播、residual 数据
     已证明冲突 -> REJECT（不创建 native solver）
  -> 单向 WW-feedback（GMWR、预传播及 WW 剪枝均开启时）
  -> SISolverInduced
     SETUP -> KNOWN_EDGES -> WW -> RW -> PREDICATE
     -> DEPENDENCIES -> ACYCLIC
  -> MonoSAT solve(assumptions) 一次
     SAT -> ACCEPT
     UNSAT -> 本次 conflict clause -> REJECT
```

各阶段共用同一确定 Oracle；候选 guard 不反写 Oracle。关闭 GMWR 选择 EAGER；关闭预传播仍构建 residual obligations。

## 5. 输入模型

当前公开输入类型为 `PRHIST`。路径可以指向 `history.prhist.jsonl`（或 `.zst`），也可以指向包含它的历史目录。

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
- 非 DISTINCT 的单调查询；`distinct=true` 不支持。
- bag/multiset 结果。
- `=`、`>`、`<`、`%`、`AND` 和括号。

声明 row-local 的自定义谓词也受支持；不支持的 whole-snapshot 谓词、DISTINCT 和不受支持的查询在能力检查时走 ERROR。当前不接受任意 SQL 文本。紧凑格式依赖 `(key,value)` 能唯一定位写版本，并拒绝 `write_id`、`source_write_id`、`source_txn`、`source_op_index` 等 provenance 字段。

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

仅对 row-local 谓词，预检按 `predicate.identity()` 跟踪同一事务中前一次相同谓词读并继承逐 key 结果；JOIN 不通过先前结果继承覆盖：

- 两次相同谓词之间没有本地更新时，结果逐 key 继承。
- 有本地更新时，以当前事件前最后一次本地写为准。
- 不同 predicate identity 的先前读取不会把 key 错误标成 INTERNAL。

受支持 JOIN、投影与重复行的完整结果在首次 solve 前编码；all-INTERNAL 也必须执行完整 QueryPlan 校验。

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
    或 row-local 的同一 predicate identity 的更早 observation 已覆盖该 key。

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

- `REACHABILITY` 对生成器的单方向 WW(u,v) 与共同指向 v 的 RW 分支执行完整冲突检查，补齐新 WW 与已有 B 的组合成环，不试加边或复制闭包。非标准分支保留原逐边充分检查；多个未定分支共同成立仍由轮末检查和最终求解验证。
- 最终 `SISolverInduced` 的 WW 阶段只读取 WW；随后 RW 阶段从 `readFrom + wwOrder` 重新生成相同的 guarded RW。

这样最终公式中的 RW 来源统一，不依赖 constraint 中重复保存的派生边。

### 8.2 Coalescing

主路径固定把同一 writer 事务对上的多个 key/读依赖合并到一个 `SIConstraint`，共享一个方向 literal；旧的非合并 WW 生成器和开关已删除。

coalescing 合并的是 choice 组织结构，不会把 A/B 类型抹掉。

## 9. 共享分析与 WW/谓词剪枝

每个 audit 只构建一个 `SIReachabilityOracle`，依次供 WW、PR_WR、GMWR 和 SAT 编码使用。它保存确定事实；Oracle 未证明 VIS=I*;A 不等于不可见，无法判定的 VIS 留给 SAT。WW 只按确定 induced 闭包排除已证明冲突的 branch，固定时提交完整 WW/RW side；每轮批量更新后检查冲突，停止后 residual choices 留给 solver。

`PredicateAnalysis` 拥有共享写索引、scope、latest self、完整外部 final writes、记录完整性和行贡献缓存。`PredicatePruning` 准备 `PreparedObservation/PreparedKey`、确定 PR_WR 固定点和 `ResidualItem`，通过 `Result` 将同一 analysis/Oracle 与 residual 数据交给 solver。

GMWR 按 `(reader,badWriter)` 分组，但每个 key/observation item 按 AND 保留：

```text
NOT VIS(bad,R) OR OR_good(WW_k(bad,good) AND VIS(good,R))
```

无 repair 推导 NOT_VIS；bad 确定可见且唯一 repair 时推导 WW 与 VIS。NOT_VIS 不是反向 VIS，不将普通提交顺序当成快照可见性。GMWR 确定事实在准备结束后单向反馈到残余 WW，整侧提交 WW/RW 至固定点；不再次执行 GMWR。候选 PR_WR guard 不能成为证明自己的确定路径。

## 10. 求解核心：分阶段 MonoSAT 编码

### 10.1 求解器内部状态

| 状态 | 不变量 |
| --- | --- |
| `solver` | residual Boolean 公式与 induced 无环属于同次 solve。 |
| `inducedGraph` | 唯一 native 辅助图 H，无环性等价于 A_s∪A_s∘B_s 无环。 |
| `visibilityChoices` | 每个实际消费的外部事务对一个共享 VIS。 |
| `dependencyEdgesA/B` | 原始类型化依赖，不混入伪造 PR edges。 |
| `incomingDependencyA/outgoingDependencyB` | 包含 typed 和 VIS 支持，按中间事务建立辅助通道。 |
| `wwOrder` | 同 key writer pair 的同一方向 guard，供 RW/latest/PR_RW 复用。 |
| `inducedEdgeSupports` | 完整收集端点支持；约简无条件 H 图并简化已确定方向，剩余物理边与全部支持等价。 |
| shared analysis/prepared result | 不在 solver 重新构造索引、重新预传播或裁剪候选。 |

### 10.2 编码阶段

| 阶段 | 工作 |
| --- | --- |
| SETUP | 只为真实事务创建 induced 节点。 |
| KNOWN_EDGES | 登记固定 typed A/B。 |
| WW | 每个 residual choice 用 assumption a 和方向 f，生成 a∧f / a∧¬f。 |
| RW | 使用与 WW 相同的 guard，按 point WR 派生 ordinary RW。 |
| PREDICATE | 共享 VIS/latest、EAGER/GMWR、完整 JOIN bag/provenance 与 typed PR guards。 |
| DEPENDENCIES | 合并 predicate witness，建立 A 原事务边及 A/B 辅助通道，约简 H 后封闭物理边支持。 |
| ACYCLIC | 断言唯一 inducedGraph 无环。 |

bottom 恒可见、版本最早，不创建 native 节点；指向 bottom 或真实自环的有效逻辑 guard 被禁止。observation、WW 与传播事实 assumption 保留到各自结果和依赖公式中；全局 VIS 的两个结构分支独立于首次消费它的 observation。

## 11. 完整 induced 如何约束共同快照

### 11.1 统一支持与组合

```text
A_s(x,u), guardA: H(x,u) 与 H(x,u*) 均受 guardA 控制
B_s(u,y), guardB: H(u*,y) 受 guardB 控制
H(x,u*)→H(u*,y) 路径等价表达 D(x,y) 的 guardA AND guardB 支持
acyclic(H) 当且仅当 acyclic(A_s ∪ (A_s;B_s))
```

typed×typed、typed×aux、aux×typed、aux×aux 均要组合；自环 support 为 false。PR witness 按 `(from,to,type)` 合并，物理 induced edge 按端点复用，二者不能混同。

### 11.2 与 SI 执行的存在性对应

从满足公式的 D 拓扑排序得到提交序 c，令 `cut_R=max({c_U|A_s(U,R)}∪{0})`。A_s direct 保证 cut_R<c_R；每个 `B_s(R,W)` 经完整组合保证 cut_R<c_W。因此 VIS true 对应 writer 在 cut 前提交，false 对应 cut 后提交。把 R 的开始事件放在 cut 后，即可补全未显式创建 VIS 的 pair，得到同一提交序上的各自快照。

反向由合法 committed SI 执行赋实际 VIS/WW/source，每条 A_s 都是 commit→start，B_s 都是 start→commit，D 边沿提交顺序，故无环。SO、WW 保证对应事务不能违背 session 与同 key 写入排他要求；辅助提交序不等于 SER 串行事务执行。

该证明依赖正确的 typed guards、完整 scope、latest 域和完整查询 bag/provenance，也依赖剪枝保留合法 latest 候选；详细公式和 witness 前提见 `SI_DESIGN.md` 第 6 节。

### 11.3 必须保留的区分

- `VIS(W,R)` 和 `VIS(R,W)` 可同时 false，允许 write skew。
- `W A→X B→C` 只推出提交 W<C，不能推出 VIS(W,C)。
- 再有 `C A→R` 时，NOT_VIS(W,R) 的负分支必须进入组合并产生冲突，不能只检查原始 typed 图。
- Oracle 缺路径表示未知；SAT 中 source guard 不通过把自己写回 Oracle 来获得支持。

## 12. Predicate 求解核心

### 12.1 候选版本与共同可见性

每个 external key 只考虑各外部 writer 的 final write；同一个 writer 对多个 key 的贡献共用 `VIS(writer,reader)`。bottom 可见且最早。读前最新 self write 覆盖对应 key，读后 self write 不参与当前查询。同一 reader 的多个谓词事件共用外部快照，再分别叠加事件前自写。

### 12.2 latest-visible frontier

```text
Latest_k(s,R) = VIS(s,R)
    AND 对全部其他外部 final writer u:
        NOT(VIS(u,R) AND WW_k(s,u))
```

竞争域不能以裁剪后的 source 候选替代。PRHIST 对缺初始值的有限 key 提供 ABSENT bottom；它没有行贡献，但仍是最早的可见版本。有 query 前 self write 时取最后一次该 key 本地写，不再由外部 frontier 决定。

### 12.3 source 与 typed PR guards

选中的外部 source 产生 PR_WR；source 之后的 result-changing writer 产生 `Select(source)∧WW(source,later)` 守卫的 PR_RW。结果与依赖同时受 observation assumption 控制，使用传播裁剪的结果也保留事实 assumptions。

报告中的 absent Pred-WR witness 不等于“定义上必须是 latest”。实现选择实际 latest 作为存在性见证；合法执行必须在候选中保留该表示。任意更早的 good writer 不保证可替代，因为可能产生额外 PR_RW 冲突。

### 12.4 recorded source 的固定编码

recorded input/source 是待验证事实。它必须出现在合法 source 域，与 key/value、scope、final-write 或 query 前 latest self 要求相符；不能由求解器任意换成另一个来源。`result.inputs` 是结果贡献来源，并不是整个可见快照。

### 12.5 row-local EAGER / GMWR

两种模式消费同一 prepared observation/key 数据和行贡献分析。INTERNAL key 验证自写贡献；EXTERNAL recorded source 及没有隐式 bottom 备选的唯一实来源直接编码可见性和逐竞争写排除子句，跳过通用 latest 合取；JOIN 固定来源使用相同检查并保留句柄。其他来源的 latest 合取在当前 checker 内规范化、去重和缓存；PR 依赖仍保留全部竞争写与 Delta 条件；absent key 的每个 bad writer 要么不可见，要么由更晚且可见的 good writer 修复。GMWR 只改变 item 的准备、预传播和残余编码组织，不降低完整结果要求。

记录不匹配直接形成矛盾，不跳转到更宽松路径。compact matcher 与有界行贡献缓存由共享 analysis 提供。

### 12.6 非 DISTINCT 单调 JOIN

首次 solve 前完成 scope 内 frontier、所有可能绑定、recorded bag 重数及 contributing inputs/provenance、额外结果绑定排除、typed PR_WR/PR_RW。完整 scope 包括没有出现在 recorded inputs 中的 key，避免遗漏额外 JOIN 行或跨 key 快照不一致。

### 12.7 all-INTERNAL 与不支持查询

all-INTERNAL 也执行完整查询校验，不因没有 EXTERNAL frontier 而跳过。DISTINCT 和不支持的 whole-snapshot 自定义谓词在能力检查阶段报 ERROR。不存在模型快照检查后追加 no-good 的循环。

## 13. 单次 SAT、判定与冲突提取

### 13.1 求解流程

```text
SatSolveBackend.solve(Solver, assumptions) -> boolean
true -> SolveStatus.SAT -> AuditResult.ACCEPT
false -> SolveStatus.UNSAT -> AuditResult.REJECT
```

仅调用一次 native solve。检测器没有内部 deadline 或 TIMEOUT 状态；运行异常走 ERROR，外部 runner 超时单独记 PROCESS_TIMEOUT。

### 13.2 assumption 冲突提取

UNSAT 后直接读取本次 `getConflictClause()`，映射 `WW_CHOICE/PREDICATE_OBLIGATION/GMWR_RULE` 与 assumption 原因，不另建 solver、不重解、不承诺最小 core。无 assumption 原因时保留确定图/剪枝冲突摘要。

## 14. CLI

`Main` 只提供：

```text
audit HISTORY
```

公开参数：

```text
--[no-]gmwr                      # default on; off selects EAGER
--[no-]gmwr-prepropagation       # default on; only effective with GMWR
--solver-stats
```

输入固定为 PRHIST，backend 固定为 MonoSAT。WW reachability、witness coalescing、graph-edge interning 在 CLI 固定开启；旧 `--predicate-encoding`、`--ww-pruning`、`--solver-timeout-seconds` 和合并/边复用开关不再解析。

默认日志分 History/WW/GMWR/SAT/Timing 段，GMWR 模式的 PR_WR 计数随阶段摘要输出，末尾为 `Peak memory` 与 `SI audit result: ...`。`--solver-stats` 追加 Predicate 细项、原始统计和旧 marker。异常显式输出 `[SI] Error`、`SI audit result: ERROR`，返回 1。

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  audit --solver-stats /path/to/hist-00000
```
## 15. 关键文件

```text
src/main/java/Main.java
    单一 audit CLI 与本次运行的 SolverSettings。

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

src/main/java/verifier/SIReachabilityOracle.java
    shared A/B reachability 和 induced-SI branch feasibility。

src/main/java/verifier/SIReachabilityPruner.java
    基于 shared oracle 的 WW branch reduction。

src/main/java/verifier/SiGmwrPropagationState.java
    row-local GMWR item 预传播，结束后单向反馈确定事实到残余 WW 剪枝至固定点。

src/main/java/verifier/PredicateAnalysis.java
    共享 scope、写/source 与行贡献。

src/main/java/verifier/PredicatePruning.java
    prepared observation/key、PR_WR 固定点、residual items。

src/main/java/verifier/SIConstraint.java
src/main/java/verifier/SIEdge.java
    WW choice 与 typed edge 数据结构。
```

## 16. 测试覆盖

回归范围包括 loader/QueryPlan、内部一致性、write skew、同 key 写冲突、point RW、typed PR_WR/PR_RW、共同 VIS 快照、row-local 与 JOIN 的 bag/provenance、all-INTERNAL、能力错误、预传播/编码模式 parity、单次 backend 调用和原生冲突复用，以及 runner 最终 verdict/exit/外部超时契约。

纯 typed A/B 小图差分仍可验证基本 induced 规则，但不能代替含 VIS 辅助分支、完整 latest/frontier 和跨 key JOIN 的语义回归。此处列出覆盖职责，截至 2026-09-23 最近一次正式回归共 177 项，175 通过、2 项因既有外部 catalog 缺失跳过，0 失败。WW 检查的三个历史两轮交叉顺序测试均 ACCEPT，平均总耗时下降约 9.3%–14.5%；不是全 workload 性能保证。证据、样本和资源限制见根目录 CHANGE_LOG.md。

## 17. 当前边界

- 仅支持批准的 row-local、非 DISTINCT 单调 QueryPlan；不接受任意 SQL 文本。
- 紧凑 PRHIST 要求 `(key,value)` 唯一 source；snapshot 域是历史已知的有限 key。
- 生成器形状的 WW 分支已补齐相对当前确定图的冲突检查；非标准形状仍保留充分检查。逐个可行的分支组合未必可行，最终完整 induced 公式负责剩余选择。
- 单次 solve 前的 JOIN 绑定枚举仍可能有较大开销，不通过模型补救降低语义要求。
- 唯一确定 Oracle 与 VIS SAT 选择各有职责；不将缺路径误读为不可见。
- runner 只使用外部进程期限；最后 verdict 与 exit 必须一致，ERROR/截断不能算 REJECT。
- assumption conflict 不保证最小 core；文档不替代最终编译、回归和实验验收。

截至 2026-09-23 的源码核查还发现以下性能候选，尚未实施，也未测得加速收益：

| 候选 | 当前证据 | 验证边界 |
| --- | --- | --- |
| residual item 按 observation 分组 | SI 每次 row-local 编码都扫描全部 residual items；SER 独立遍历一次 residual 列表。 | 可减少重复遍历，必须保留每个 item 的 observation/rule assumption。 |
| prepared 数据索引 | SI 的 Result.observation 和 PreparedObservation.key 均线性查找，JOIN 每个 key 重复调用；SER 的 observation 使用 Map。 | 可传递已准备对象或索引，需同时评估内存成本。 |
| 编码临时集合及时释放 | SI 的 typed guard 集合、A/B support 索引与 H support 集合保留至求解；SER 在消费后清理相应队列。 | 只释放无后续消费者的数据，保留冲突原因；是否降低 RSS/耗时需实测。 |
| latest 之外的 guard 合取共享 | SI 的通用 and 帮助方法仍直接创建公式；SER 的依赖路径可保存条件项并直接发子句。 | 不能照搬 SER 单向蕴含而破坏 SI 物理边与支持条件的等价关系，须先测重复度及公式规模。 |

已完成的固定来源路径、latest 合取缓存、直接子句、见证 OR 链消除、增量 Oracle/watcher、共享最终写、辅助图 H、物理边复用和 WW-feedback 不再列为缺项。已回退的确定 PR_RW 传播与来源联合假设剪枝也不属于遗漏，不能自动恢复。

## 18. 新人阅读顺序

1. `SI/README.md`：构建和命令行。
2. `src/main/java/Main.java`：模式入口。
3. `PredicateHistoryLoader.java`：输入到 History。
4. `KnownGraph.java`：A/B、readFrom、predicate observations。
5. `SIVerifier.java`：约束生成、剪枝分派和诊断。
6. `SIReachabilityOracle.java`、`SIReachabilityPruner.java`：shared A/B oracle 与 WW reduction。
7. `PredicateAnalysis.java`、`PredicatePruning.java`、`SiGmwrPropagationState.java`：共享分析、准备与 GMWR item 预传播。
8. `SISolverInduced.java`：分阶段编码、predicate frontier、induced graph。
9. `SIGmwrParityTest`、`SISolverInducedParityTest`、`SISolverInducedDifferentialTest`：用回归测试核对实际语义。
