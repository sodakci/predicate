# SI 检测器代码脉络索引

本文索引 `SI/si-result-detector` 当前工作树中的实际调用链和数据结构。它与 `SER_CODE_MAP.md` 使用相同审计口径，但保留 SI 的核心判定：

```text
A = SO ∪ WR ∪ WW ∪ PR_WR
B = RW ∪ PR_RW
A_s = A ∪ VIS 正分支辅助关系
B_s = B ∪ VIS 负分支辅助关系
InducedSI = A_s ∪ (A_s ∘ B_s)
verdict = InducedSI 是否存在无环的 WW / predicate-source assignment
```

对齐契约：2026-09-22；当前实现核对：2026-09-23。本文描述批准的实现边界；验证结果以实施计划与变更记录为准。

## 1. 可执行入口与最终结果

文件：`src/main/java/Main.java`

| 入口 | 调用链 | 输出 |
| --- | --- | --- |
| `Main.main()` | Picocli -> `Audit.call()` | 进程退出码与分段日志。 |
| `audit` | loader -> `SIVerifier.auditResult()` | `ACCEPT=0`、`REJECT=-1`（shell 为 255）；异常 `ERROR=1`。 |

输入固定为 PRHIST，backend 固定为 MonoSAT。主入口不再保留独立的
`constraint-stat`、`stat`、`dump` 加载/遍历路径。

`SIVerifier.audit()` 是兼容旧调用方的 boolean 包装：只有 `ACCEPT` 返回 true；CLI 使用仅含 `ACCEPT/REJECT` 的 `auditResult()`；不支持的查询走异常流程。

## 2. 输入、解析与初始版本

| 文件 | 结构 / 函数 | 作用 |
| --- | --- | --- |
| `history/loaders/PredicateHistoryLoader.java` | `loadHistory()` | 读取 `initial_state.json` 与 `history.prhist.jsonl`（也支持 `.zst`）。 |
| 同上 | `loadInitialState()` | 创建 `(session=-1, txn=-1)` bottom transaction；拒绝重复初始 key。 |
| 同上 | `parseTransaction/parseOperation()` | 只接受 committed transaction 和 `w/r/pr` operation。 |
| 同上 | `parseQueryPredicateResult()` | 将 `query + result` 转成 `QueryPlan`、`PredResult` 和 canonical `RecordedQueryResult`。 |
| `history/History.java` | `ensureInitialVersions()` | 为 history 中出现、但 bottom 未覆盖的 key 合成 `value=null` 的 ABSENT write。 |
| 同上 | `missingInitialKeys()` | 只检查 ordinary read/write 与 predicate result 中实际出现的有限 key universe。 |

loader 仍拒绝旧 source metadata 字段。point/predicate source 解析依赖唯一 `(key,value)`。程序化构造且没有 bottom transaction 的 history 不会凭空创建 bottom；PRHIST loader 总会创建它。

## 3. 内部一致性门禁

文件：`src/main/java/verifier/Utils.java`

| 函数 | 作用 |
| --- | --- |
| `verifyInternalConsistency()` | 构造 `(key,value)->writes` 与 transaction-local write-position 索引，验证全部 point/predicate reads。 |
| `checkItemRead()` | 检查唯一 source、self latest-write、external writer final-write，以及读前是否已有 self write。 |
| `checkPredicateRead()` | 检查 result key/source/scope、INTERNAL/EXTERNAL source、重复同谓词 read 的继承规则。 |
| `history.query.PredicateReadSemantics.predicateSnapshotMatches()` | general query 的 covered keys 全由当前 transaction 本地写决定时，立即执行受支持查询的完整 snapshot 校验。 |
| `predicateMatchesRow()` | row-local 查询的单行贡献检查。 |

先由 `PredicateAnalysis.validateSupportedPredicates(history)` 检查查询能力：支持 row-local 和非 DISTINCT 的单调 QueryPlan；不支持的查询抛异常并输出 ERROR。内部一致性门禁失败才得到 REJECT，不进入剪枝或 SAT。

## 4. KnownGraph 与 typed relation

文件：`src/main/java/graph/KnownGraph.java`

构造器首先调用 `history.ensureInitialVersions()`，随后创建：

| 字段 | 内容 |
| --- | --- |
| `readFrom` | point-read 的 WR；供 ordinary RW 生成使用。 |
| `knownGraphA` | SO、WR，以及剪枝已固定的 WW/PR_WR。 |
| `knownGraphB` | 剪枝已固定的 RW/PR_RW。 |
| `allWrites` / `writesByKeyValue` / `txnWrites` | source、版本、transaction-local write 索引。 |
| `predicateObservations` | reader/event、tuple source、scope 内 key 的 INTERNAL/EXTERNAL 分类。 |

`PredicateObservation` 使用 key-id、`BitSet`、默认分类加例外集合保存 predicate key 类型，避免为每次 read 保存完整 `Map<Key,PredicateReadType>`。

关系的主要产生位置：

| Relation | 初始 / candidate 产生位置 | 最终去向 |
| --- | --- | --- |
| SO | `KnownGraph` 中同 session 相邻 transaction | A |
| WR | `KnownGraph.resolveReadSource()` | `readFrom` 与 A |
| WW | `SIVerifier.generateConstraintsSI()` 两方向 candidate | A |
| RW | WW branch 内生成；solver 再由 `readFrom + wwOrder` 统一生成 | B |
| PR_WR | `PredicatePruning` 固定来源；`SISolverInduced` 编码 remaining source / frontier selection | A |
| PR_RW | predicate source 后的 result-changing writer | B |

## 5. Ordinary WW/RW constraint

| 文件 | 类 / 函数 | 作用 |
| --- | --- | --- |
| `verifier/SIEdge.java` | `SIEdge` | `(from,to,type,keys)` typed logical edge；可保留多个 witness key。 |
| `verifier/SIConstraint.java` | `SIConstraint` | `edges1 OR edges2` 的 writer-order 二选一。 |
| `verifier/SIVerifier.java` | `generateConstraintsCoalesce()` | 将同一 writer transaction pair 的多 key 方向合并；主路径固定使用。 |

constraint 生成复杂度主要为每个 key 的 writer pair 与 `WR × competing writer`：

```text
O(Σk wk² + Σk WRk·wk)
```

最终 solver 的 WW 阶段只消费 branch 中的 WW；ordinary RW 在独立阶段重新由 WR 与同一 WW guard 派生，防止两条路径产生不一致 guard。

## 6. 共享分析与分阶段剪枝

| 文件 | 职责 |
| --- | --- |
| `verifier/SIReachabilityOracle.java` | 每次 audit 唯一的确定事实入口；保留 SI typed A/B，增量维护 induced 正反闭包与 VIS=I*;A，按变化端点通知传播；用于 WW 分支可行性、确定 VIS/NOT_VIS 和候选裁剪。 |
| `verifier/SIReachabilityPruner.java` | 每轮用同一确定快照检查 WW 两侧；生成器的单方向 WW(u,v) 与共同指向 v 的 RW 分支，额外检查新 WW 与已有 B 的组合成环，通过 B(v) 与 induced 反向闭包求交，无需复制闭包。非标准分支保留原充分检查。唯一可行侧整侧提交，包含 WW 与对应 RW。两侧都冲突或批量提交成环则 REJECT。 |
| `verifier/PredicateAnalysis.java` | 共享写索引、scope、读前自写、每个 key 的只读末次写列表与完整外部 final-write 域、记录完整性与行贡献；不创建 SAT 对象，不提交顺序事实。 |
| `verifier/PredicatePruning.java` | WW 完成后准备 observation/row-key 数据、PR_WR 候选和 GMWR items；可选预传播后交付 residual 数据；固定来源跳过无消费者分类，两次交接共享不可变 metadata，动态来源域独立冻结。 |
| `verifier/SiGmwrPropagationState.java` | 按 `(reader,badWriter)` 分组，但各 key/observation item 仍按 AND 保留；无 repair 推导 NOT_VIS，唯一 repair 推导 WW 与 VIS；按受影响端点唤醒去重队列，不扫描全部关系 watcher。 |

Oracle 的“尚无确定路径”是未知，不是 NOT_VIS。未证明的分支、VIS 和 source 选择留给 SAT。唯一 PR_WR 可进入确定事实固定点；候选 guard 不反写 Oracle。默认 GMWR 预传播完成后，SIVerifier 单向反馈确定事实到残余 WW，整侧提交 WW/RW 至固定点；不再重跑 GMWR，不把普通提交先后当成快照可见性。

初始 WW 停止条件为约束清空或本轮确定数量不超过剩余数量的 1%；GMWR 后单向 feedback 停止于约束清空或无新确定项。row-local preparation 与预传播不负责重新组织 WW choices；关闭预传播仍保留全部 residual obligations。

## 7. SISolverInduced 编码顺序

文件：`src/main/java/verifier/SISolverInduced.java`。求解器消费同次 audit 的 Oracle、analysis、prepared/residual 数据，编码顺序为：

1. SETUP：`createNodes()` 仅为真实事务创建 `inducedGraph` 节点。
2. KNOWN_EDGES：`encodeKnownEdges()` 登记固定 typed A/B。
3. WW：`encodeWwChoices()` 以 assumption `a` 和方向 `g` 生成 `a∧g` / `a∧¬g`。
4. RW：`encodeRwFromWrAndWw()` 使用同一 WW guard 派生 ordinary RW。
5. PREDICATE：传播事实受 `GMWR_RULE` assumption 守卫；EAGER/GMWR 和 JOIN 完整结果约束受 observation assumption 守卫。
6. DEPENDENCIES：`encodePredicateDependencies()` 合并 typed PR witness；`encodeFactorizedInducedGraph()` 构造辅助组合通道 H；`materializeReducedInducedGraph()` 约简 H 的无条件图、简化条件支持并创建剩余物理边。
7. ACYCLIC：断言 `inducedGraph.acyclic()`。

| 状态 / 接口 | 作用 |
| --- | --- |
| `visibilityLiteral(writer,reader)` / `visibilityChoices` | 每个实际消费的外部事务对共享一个 VIS literal；true 支撑辅助 A(writer,reader)，false 支撑辅助 B(reader,writer)。 |
| `inducedGraph` / `inducedNodes` | 唯一 native 图及真实事务节点；通过辅助节点 H 等价表达 `A_s∪A_s∘B_s`。 |
| `dependencyEdgesA/B` | typed 逻辑依赖及其 guard，保留原始 PR/WW/RW 类型。 |
| `incomingDependencyA/outgoingDependencyB` | 同时索引 typed 与 VIS 辅助关系，按中间事务建立辅助通道，不展开入边×出边。 |
| `wwOrder` | `(writerFrom,writerTo,key)` 对应 WW guard。 |
| `predicateDependencyCandidates` | 按 `(from,to,type)` 合并前的 PR witness。 |
| `inducedEdgeSupports` | 按 DirectedEdgeKey 收集全部逻辑支持，辅助通道构造后决定需物化的边。 |

没有独立可见性 native 图，也没有模型快照检查列表或重求解队列。

## 8. A/B 到 InducedSI 的实际编码

`materializeDependencyEdge()` 将 typed A/B 交给 `addOrderingSupport()`；VIS 两个分支也使用同一顺序支持入口，但不伪装成 typed PR 边。

```text
收集全部 typed/VIS 的 A_s、B_s 支持
对同时有 A 入边和 B 出边的事务 u 创建辅助节点 u*
每个 A_s(x,u), guardA:
    登记 H(x,u)，若 u* 存在再登记 H(x,u*)，均受 guardA 控制
每个 B_s(u,y), guardB:
    若 u* 存在，登记 H(u*,y)，受 guardB 控制
约简 H 的确定部分、简化条件支持，断言 H.acyclic()
```

组合必须覆盖 typed×typed、typed×aux、aux×typed、aux×aux。原 A 自环使对应 support 为 false，A;B 自环成为 H 中的两段环并由无环约束排除；bottom 不创建 native 节点。不能只保留 typed 组合，也不能用 Oracle 未证明 VIS 来推断辅助分支不成立。

### 8.1 Predicate witness coalescing

生产 CLI 固定开启 predicate witness coalescing：相同 `(from,to,type)` 的多个 PR witness 合为一个 `SIEdge(keys)`，guard 为各 witness guard 的 OR。内部测试设置关闭时每个 witness 单独物化。

### 8.2 Graph-edge interning

生产 CLI 固定开启。辅助 H 构造后，先对含 `Lit.True` 支持的确定 H 图做传递约简；同向已达的条件边省略，反向已达的条件边强制 guard 为假，其余端点创建 native edge，并断言：

```text
H edge <-> OR(all supports at these directed endpoints)
```

每个 support 都保留对应 WW、observation 和传播事实 assumption。全局共享 VIS 的正负结构关系独立于首次访问它的 observation assumption。多个 PR witness 合并后保留各自 guard，不再构造 OR 链；物理边通过直接子句满足每个 guard 蕴含 edge，且 edge 蕴含 guards 的析取。谓词断言与 GMWR obligation 同样使用直接蕴含/析取子句，保留 observation 与 rule assumption。物理边不能无支持地自激活；候选 PR_WR 也不能作为证明自身 guard 的 Oracle 确定路径。

关闭 interning 的测试路径逐逻辑边保持 `edge <-> guard`。

## 9. Predicate 编码

### 9.1 Row-local EAGER / GMWR

`encodeRowLocalPredicate()` 统一消费 EAGER/GMWR 的 prepared 数据，并复用 `LatestVisibleChecker`：

- INTERNAL key 使用事件前最后一次 self write。
- EXTERNAL key 的 latest-visible 在该 key 全部外部 final writes 中判定，不能以裁剪过的 source 域替代竞争域。未定来源使用当前 checker 内的二元/多元合取缓存；按字面量规范化、去重并一次构造多元合取，等价条件换序仍复用公式，缓存不跨 native solver。
- recorded source 和没有隐式 bottom 备选的唯一来源走固定来源路径，直接约束来源可见以及每个竞争写不能更晚且可见；不构造通用 latest 合取。固定 PR_WR，并为 result-changing later writer 产生 guarded PR_RW。JOIN 的 recorded source 同样保留全部竞争写检查及单候选句柄。
- absent-key GMWR item 为 `¬VIS(bad,R) ∨ ⋁good(WW(bad,good) ∧ VIS(good,R))`。
- bottom 可见且版本最早；ABSENT 无行贡献，不送入 value adapter。

报告的 absent Pred-WR witness 定义不要求它等于实际 latest；实现选择实际 latest 作为存在性见证。候选剪枝必须保留合法执行的该见证，不能声称任意 good writer 都可替代 latest。

### 9.2 非 DISTINCT 单调 JOIN

`encodeExplicitMultiRelationPredicate()` / `createExplicitQueryFrontier()` 在求解前编码完整结果：

```text
Latest_k(w,R) = VIS(w,R)
               AND 对每个其他外部 final writer u:
                   NOT(WW_k(w,u) AND VIS(u,R))
```

frontier 共用事务对 VIS 与逐 key WW，query 前 self writes 覆盖相应 key。完整绑定同时核对 bag 重数和 contributing inputs/provenance；`result.inputs` 不是完整快照。`encodeExplicitPrWr()` / `encodeExplicitPrRw()` 保留 typed 依赖，`encodeAdditionalBindingExclusion()` 排除额外结果绑定。all-INTERNAL 也执行完整查询检查。

## 10. 单次 solve 与冲突提取

| 接口 | 契约 |
| --- | --- |
| `SatSolveBackend.solve(Solver, Collection<Lit>)` | 返回 boolean，单次调用 native `solve(assumptions)`。 |
| `solveStatus()` | 仅 SAT/UNSAT，无模型结果修补循环。 |
| `solve()` | SAT 返回 true。 |
| `extractConflicts()` | 直接读取这次求解的 `getConflictClause()`；按 assumption 映射原因，不另建求解器或重解。 |
| `getConflictReasons()` | `WW_CHOICE/PREDICATE_OBLIGATION/GMWR_RULE` 与 `A<n>` 的对应说明。 |

无检测器内部 deadline 或 TIMEOUT verdict。runner 的外部进程超时独立记为 PROCESS_TIMEOUT；canonical 最终 verdict 必须与 exit code 相符，ERROR 或截断不能记为 REJECT。

## 11. MonoSAT 路径

| 层 | 文件 / API | 作用 |
| --- | --- | --- |
| Java | `monosat.Graph.addEdge()` | 创建由 SAT literal 控制的 theory edge。 |
| Java | `Graph.acyclic()` | directed acyclicity literal；SI 只 assert `inducedGraph.acyclic()`。 |
| JNI/C++ | `Monosat.cpp`、`GraphTheory.h` | 将 edge/acyclicity 注册进 graph theory solver。 |
| cycle theory | `CycleDetector.cpp` | true-edge under graph 成环时向 CDCL 返回 conflict reason。 |

## 12. CLI 配置

| CLI | 默认 | 消费位置 |
| --- | --- | --- |
| `--solver-stats` | false | 输出详细 predicate/physical edge/CNF counts。 |

公开参数为 `--[no-]gmwr`、`--[no-]gmwr-prepropagation`，默认均开启；关闭 GMWR 使用 EAGER，预传播开关随之失效。生产 CLI 固定开启 WW reachability、witness coalescing、graph-edge interning，不接受旧 `--predicate-encoding/--ww-pruning/--solver-timeout-seconds` 和后两项开关。

默认日志使用 History/WW/GMWR/SAT/Timing 摘要及 `SI audit result: ...`；Predicate 细项、原始统计与旧 marker 只在 `--solver-stats` 输出。异常显式输出 `[SI] Error`、`SI audit result: ERROR`，退出码 1。

## 13. Statistics / timing

主要阶段：

```text
SI_VERIFY_INT
SI_GEN_PREC_GRAPH
SI_GEN_CONSTRAINTS
WW_REACHABILITY_PRUNE_MS
SI_PRED_PREPARE_MS
GMWR_BUILD_MS
GMWR_PRUNING_MS / SI_PRED_PRUNING_MS
GMWR_REDUCTION_MS
SI_GRAPH_ENCODE
  SI_GRAPH_ENCODE_SETUP
  SI_GRAPH_ENCODE_KNOWN_EDGES
  SI_GRAPH_ENCODE_WW
  SI_GRAPH_ENCODE_RW
  SI_GRAPH_ENCODE_PREDICATE
  SI_GRAPH_ENCODE_DEPENDENCIES
  SI_GRAPH_ENCODE_ACYCLIC
SI_GRAPH_SOLVE
  SI_MONOSAT_SOLVE
  SI_GRAPH_CONFLICT_EXTRACTION
```

counts 包含 `WW_INITIAL_CHOICES/WW_AFTER_REACHABILITY/WW_AFTER_GMWR_FEEDBACK`、`WW_GMWR_FEEDBACK_FORCED/WW_GMWR_FEEDBACK_ROUNDS/WW_BRANCH_EXTRA_CONFLICTS`、`GMWR_INITIAL_CONSTRAINTS/GMWR_RESIDUAL_CONSTRAINTS/GMWR_FORCED_FACTS`、`SI_ORACLE_BUILDS/SI_ORACLE_UPDATES`、SAT
variables/clauses、predicate candidates/physical/coalesced edges、VIS 变量数、induced
physical edge 数、frontier 与 cache。`Generated/Fixed PR_WR/PR_RW` 为两类合计；PR_WR 来源候选计数不是 PR_RW 边计数，也不是 H 物理边总数。`WW_BRANCH_EXTRA_CONFLICTS` 统计额外发现冲突的调用次数，不能当作新增固定 WW 数。PR_WR 发布初始/残余/强制约束及初始/残余/裁剪/固定候选七项计数；build/pruning/reduction 分别计时。native 创建数、H 图节点/边数（节点包含辅助节点，辅助数单独发布为 SI_PROP_MONOSAT_AUXILIARY_NODES_COUNT）及 MonoSAT propagation/conflict 计数用于核对阶段和求解规模；验收数值以实际运行记录为准。

## 14. 与 SER 的明确边界

复用 SER 的输入/分析/准备/剪枝/残余编码分层和单次求解结构；不复制其事务串行顺序。

- SI 的原始 typed B 不直接当作 A；最终仅检查 `A_s∪A_s∘B_s`。
- `VIS(W,R)` 与 `VIS(R,W)` 不建立互补 XOR；两者均 false 的 write skew 可合法。
- VIS true/false 分别进入辅助 A/B，而不是伪造 PR_WR/PR_RW；NOT_VIS 不是反向 VIS。
- induced 拓扑顺序是用于构造共同快照的辅助提交顺序，每个 reader 可有不同开始 cut；不要求事务串行执行。
- 唯一确定 Oracle、候选 guard、最终 SAT 选择各自承担不同职责，未知事实由共享 VIS 与完整 induced 约束求解。

尚未验证的性能候选及已回退实验边界见 [当前边界](PROJECT_OVERVIEW.md#17-当前边界)，不将源码层面的机会等同于已证明的加速。
