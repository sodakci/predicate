# SER 检测器代码脉络索引

> 审计对象：`SER/ser-result-detector` 当前工作树（包含当前未提交实现）。本文件只建立真实入口、模块、核心类、关键函数和调用关系；完整语义与风险分析见第二步生成的 `SER_DESIGN.md`。

## 1. 可执行入口与最终结果

| 层级 | 文件 | 类 / 函数 | 代码作用 |
| --- | --- | --- | --- |
| JAR/CLI 入口 | `SER/ser-result-detector/src/main/java/Main.java` | `Main.main(String[])` | 创建 Picocli `CommandLine`，只注册 `audit` 子命令，进程退出码取该命令返回值。 |
| SER 命令入口 | 同上 | `Audit.call()` | 解析 SER 配置，构造 `PredicateHistoryLoader`、`SERVerifier.SolverSettings`，调用 `SERVerifier.audit()`，打印 timing/count、最大内存和 verdict marker。 |
| checker 总控 | `SER/ser-result-detector/src/main/java/verifier/SERVerifier.java` | `SERVerifier.audit()` | 串联一致性检查、`KnownGraph`、WW/RW constraint、WW 剪枝、独立 `PredicatePruning`、`SERSolverAR` 编码和单次求解，并映射到 verdict。 |
| 谓词剪枝 | `SER/ser-result-detector/src/main/java/verifier/PredicatePruning.java` | `prune()` / `Result` | 在 SAT 编码前构造 row-local GMWR 义务并可选传播；交接残余义务、确定事实和阶段冲突。 |
| 共享谓词分析 | `SER/ser-result-detector/src/main/java/verifier/PredicateAnalysis.java` | 写索引、scope、行贡献及 absent-key 分析 | 剪枝与编码共享同一份无 MonoSAT 依赖的语义计算及缓存。 |
| precedence primitive | `SER/ser-result-detector/src/main/java/verifier/PrecedenceOracle.java` | `before/successor/wouldCycle/add` | 唯一 Java precedence engine；统一维护增量传递闭包、变化端点通知和批量 branch 成环预判。 |
| 求解入口 | `SER/ser-result-detector/src/main/java/verifier/SERSolverAR.java` | 构造函数；`solve()`；`solveOnce()` | 构造 logical dependency layer + 唯一 MonoSAT serialization graph；构造前验证查询范围；完整编码后仅调用一次 MonoSAT。 |
| latest-visible primitive | `SER/ser-result-detector/src/main/java/verifier/LatestVisibleChecker.java` | `check(reader,key,candidateWriters,serializationOrder)` | 为未确定的来源返回 visible literal 与 latest-writer validity；固定来源和 row-local 确定可见单候选绕过通用 checker。 |
| verdict | `SERVerifier.AuditResult` | `ACCEPT/REJECT/INVALID_HISTORY` | `SAT -> ACCEPT(0)`；`UNSAT` 或 checker 提前冲突 -> `REJECT(-1)`。`INVALID_HISTORY(2)` 在当前 `audit()` 路径中没有显式转换点。 |

主调用链：

```text
Main.main
  -> Audit.call
     -> new PredicateHistoryLoader(path)
     -> new SERVerifier(loader, settings, solverStats)
        -> loader.loadHistory()
     -> SERVerifier.audit
        -> verifier.Utils.verifyInternalConsistency
        -> new KnownGraph(history)
        -> SERVerifier.generateConstraintsSER
        -> Pruning.pruneConstraints / NONE
        -> new PredicateAnalysis(history, graph, precedence)
        -> PredicatePruning.prune [GMWR 义务与可选传播；EAGER 无 GMWR 状态]
           -> 冲突：输出阶段原因并 REJECT，不构造 MonoSAT
        -> new SERSolverAR(history, graph, residualConstraints, ..., precedence, predicateResult)
           -> buildKnownOrder
           -> encodeKnownEdges
           -> encodeRemainingWwChoices
           -> encodePredicateConstraints
           -> encodeDependencyEdges
           -> encodeSerializationAcyclicity
        -> SERSolverAR.solve
           -> solveOnce -> monosat.Solver.solve
        -> AuditResult
     -> print marker / return exit code
```

## 2. 输入、解析与内部 history 表示

| 文件 | 类 / 函数 | 索引 |
| --- | --- | --- |
| `src/main/java/history/loaders/PredicateHistoryLoader.java` | `loadHistory()` | 读取同目录 `initial_state.json` 和 `history.prhist.jsonl`；`audit` 直接构造该 loader。 |
| 同上 | `loadInitialState()` | 创建 `session=-1, txn=-1` 的 bottom/init transaction；每个初始 tuple 变成 WRITE。 |
| 同上 | `parseTransactionHeader()` / `parseTransaction()` | 显式读取整数`session_seq`，拒绝同session重复值；按`(session, session_seq)`排序后，只接受`status=commit`并把transaction加入对应session。 |
| 同上 | `parseOperation()` | `w -> addWriteEvent`，`r -> addReadEvent`，`pr -> StructuredQueryParser + RecordedQueryResult`。 |
| 同上 | `parseQueryPredicateResult()` | `result.inputs` 变成 `Event.PredResult` 和 recorded input map；`result.values` 进入 compact row-local 或 general recorded result。 |
| 同上 | `parseValue()` / `rejectSourceMetadata()` | 值归一为 `PredicateValue(QueryValue)`；当前 loader 拒绝 `write_id/source_write_id/source_txn/source_op_index`。实际 source 之后按唯一 `(key,value)` 解析。 |
| `src/main/java/history/History.java` | `History` | 持有 session/transaction map、写标识/唯一 `(key,value)` 集合；提供 add/get 和全部事件视图。 |
| 同上 | `ensureInitialVersions()` | 对点读/写和谓词结果中出现但初始状态缺失的 key，在 bottom transaction 合成 `value=null` 的 ABSENT write。此调用发生在 `KnownGraph` 构造时。 |
| `src/main/java/history/Session.java` | `Session` | `id + 有序 transaction list`；该 list 的相邻元素产生 SO。 |
| `src/main/java/history/Transaction.java` | `Transaction` | `(session,id) + 有序 Event list + ONGOING/COMMIT`。 |
| `src/main/java/history/Event.java` | `Event` / `PredResult` | `READ/WRITE/PREDICATE_READ`；点操作持有 key/value，谓词读持有 evaluator、结果来源项和 recorded result。 |
| `src/main/java/history/query/StructuredQueryParser.java` | `parse()` | 构造时显式传入 `ValueAdapter` 和 `RelationResolver`；把结构化 query JSON 解析为 relational/expression AST 和 `QueryPlan`。 |
| `src/main/java/history/query/QueryPlan.java` | `evaluate()` / `isRowLocal()` / `isMonotone()` | 执行 query；返回投影值和实际输入版本；决定 row-local EAGER/GMWR 与显式 JOIN 编码；检测器不支持 DISTINCT。 |
| `src/main/java/history/query/QueryEvaluation.java` | `QueryEvaluation` | 保存投影值、实际输入、值多重集和 canonical inputs；通过 `values()` / `inputs()` 访问结果。 |
| `src/main/java/history/query/RecordedQueryResult.java` | `GeneralRecordedQueryResult` / `RowLocalRecordedQueryResult` | 保存或紧凑表示 history 中记录的 query 结果，通过 `values()` / `inputs()` 访问，并提供 canonical equality。 |
| `src/main/java/history/query/MapVisibleState.java` | `MapVisibleState` | checker 在具体候选 snapshot 上执行 query 时的 key/value 可见状态。 |

当前代码没有名为 `Vset` 的 class/field。与“观察到的版本集合”对应的实际结构是 `result.inputs -> Event.PredResult / RecordedQueryResult.inputs()`，进入 checker 后再转换为 `KnownGraph.PredicateObservation.tupleSources`、solver 的 `KeyFrontier` 和具体 snapshot map。

## 3. 解析后的一致性门禁

文件：`SER/ser-result-detector/src/main/java/verifier/Utils.java`

| 函数 | 作用 |
| --- | --- |
| `verifyInternalConsistency()` | 在构图前建立 `(key,value)->writes` 和 transaction-local write positions，逐个验证 point read 与 predicate read。失败由 `SERVerifier.audit()` 直接映射为 `REJECT`。 |
| `checkItemRead()` | source 必须唯一；self-read 必须读最新先前 self write；external read 必须来自 writer transaction 对该 key 的最终 write，且 reader 之前不能已有 self write。 |
| `checkPredicateRead()` | 校验结果 key 唯一、source 唯一且 committed/in-scope、internal latest-write、重复同谓词读继承规则以及 recorded inputs。 |
| `predicateSnapshotMatches()` | 编码前校验 recorded contributing inputs 是否重现完整结果及 provenance；不再用于 SAT model refinement。 |

## 4. KnownGraph 与关系产生位置

文件：`SER/ser-result-detector/src/main/java/graph/KnownGraph.java`

核心容器：

| 字段 | 内容 |
| --- | --- |
| `readFrom` | 只保存 point-read 的 WR；供 ordinary RW/WW constraint 生成使用。 |
| `knownGraphA` | SO、WR、已固定 WW、已固定/求解器阶段加入的 PR_WR。 |
| `knownGraphB` | 已固定 RW、已固定/求解器阶段加入的 PR_RW。 |
| `allWrites` / `writesByKeyValue` / `txnWrites` | 写版本、source resolution 与 transaction 内写位置索引。 |
| `predicateObservations` | 每个 predicate read 的 reader、event index、tuple source、covered-key bitset、internal/external 压缩分类和 coverage epoch。 |

产生位置：

| Relation | 直接产生位置 | 后续产生位置 |
| --- | --- | --- |
| SO | `KnownGraph` constructor：每个 session 的相邻 transaction | 不产生候选 SO。 |
| WR | `KnownGraph` constructor：point read 唯一 source writer -> reader，同时写入 `readFrom` 和 A | 不由 SAT 选择。 |
| WW | `SERVerifier.generateConstraintsCoalesce` 产生两方向候选；WW pruning 可把被迫分支提交到 A | residual 分支由 `SERSolverAR.encodeRemainingWwChoices()` 以 Boolean guard 编码。 |
| RW | 同一 WW decision 分支内由固定 WR + 另一 writer 产生；forced 分支提交到 B，residual 分支由同一 guard 激活 | 无独立 RW 重建路径。 |
| Pred-WR (`PR_WR`) | 主路径初始 `KnownGraph` 不生成 | `SERSolverAR` 的 recorded source / selected frontier 产生 fixed 或 guarded PR_WR；GMWR preprop 可产生 definite typed PR_WR。 |
| Pred-RW (`PR_RW`) | 主路径初始 `KnownGraph` 不生成 | `LatestVisibleChecker` 产出 source validity，`encodeRecordedSourceDependencies()`、`encodeSelectedSourceDependencies()` 根据 selected source、later write 和 result delta 产生 guarded PR_RW。 |

## 5. 普通 WW/RW candidate 与保存结构

| 文件 | 类 / 函数 | 作用 |
| --- | --- | --- |
| `src/main/java/verifier/SEREdge.java` | `SEREdge` | checker/solver 层的带 `(from,to,type,keys)` 逻辑依赖描述；不是 Guava graph edge，也不是 MonoSAT literal。 |
| `src/main/java/verifier/SERConstraint.java` | `SERConstraint` | `edges1 OR edges2`，附两个 writer transaction 和 id。这里没有单独的 `Option`/`Group` class；两个 edge collections 就是两个 option，constraint 本身就是 group。 |
| `src/main/java/verifier/SERVerifier.java` | `generateConstraintsCoalesce()` | 对同 key writer pair 创建双向 WW；把对应 `WR(a,b,k)` 推出的 `RW(b,c,k)` 放入选择 `WW(a,c,k)` 的同一分支；同一 transaction pair 的多个 key 合并为一个 constraint。 |

## 6. Pruning / reachability

| 模式 | 文件 / 入口 | 真实作用 |
| --- | --- | --- |
| `NONE`（仅内部测试） | `SERVerifier.audit()` switch | 不做 checker pruning；生产 CLI 不再暴露。 |
| `REACHABILITY`（默认） | `new Pruning(precedence)::pruneConstraints()` | 用 A+B 中非 predicate edge填充 audit唯一的 `PrecedenceOracle`；由 oracle判定整个branch加入后是否成环；一侧非法则提交另一侧WW/RW到 `KnownGraph`并删除constraint；两侧非法直接冲突。 |
| 谓词剪枝 | `PredicatePruning.prune()` -> `GmwrPropagationState.propagate()` | WW 剪枝后的独立阶段；准备 row-local 数据、构造 items，可选传播，再完成来源/区间剪枝和残余整理，产生候选域、确定事实或冲突。关闭预传播仍保留所有未消解 items；EAGER 不创建 GMWR 状态。 |

### 6.1 REACHABILITY

保留 `Pruning` 的完整 branch-cycle判断：对两个候选 branch分别调用共享 oracle的批量 `wouldCycle()`；一侧非法时物化另一侧，两侧非法时报告冲突。此次统一只改变 `before relation` 的所有权，不改变该规则。

### 6.2 唯一 deterministic before relation

`SERVerifier`创建一次 `PrecedenceOracle<Transaction>`，先交给 `Pruning` 完成 baseline WW/RW 剪枝，再传给 `PredicatePruning` 及其 `GmwrPropagationState`，最后连同剪枝结果注入 `SERSolverAR`。所有生产组件的 `before(a,b)`读取同一实例，solver known-order不再另建 closure。当前没有 GMWR-to-WW feedback。

oracle只保存history/known graph、pruning结论与GMWR forced facts等 deterministic order。residual WW guard、frontier selection和其他MonoSAT decision literal只存在于Boolean/theory encoding，绝不反写oracle。UNSAT 的 conflict clause 直接来自这一次求解，不为冲突提取重新求解。

## 7. SERSolverAR 编码索引

文件：`SER/ser-result-detector/src/main/java/verifier/SERSolverAR.java`

构造期真实顺序：

1. 校验已准备的 `PredicatePruning.Result` 与注入的 oracle 是同一实例，且结果没有冲突。
2. 创建 `monosat.Solver`、唯一 `serializationGraph` 及 real transaction nodes（bottom 不进 MonoSAT 图）。
3. 复用结果中的 `PredicateAnalysis` 写索引、observation、来源候选域及残余 item 快照，不在构造器执行传播或候选域剪枝。
4. `buildKnownOrder()`：把 A+B 的已知 transaction precedence 送入 `PrecedenceOracle`，并生成 transitive reduction。
5. `encodeKnownEdges()`。
6. `encodeRemainingWwChoices()`：WW decision 同时排队分支内的 WW/RW。
7. `encodePredicateConstraints()`。
8. `encodeDependencyEdges()`。
9. `encodeSerializationAcyclicity()`：assert `serializationGraph.acyclic()`。

主要结构：

| 字段 / 内部类 | 作用 |
| --- | --- |
| `serializationGraph` | 唯一 MonoSAT 物理图；承载 logical dependency、WW/source/frontier 比较和已知顺序的 endpoint 约束。 |
| `serializationEdgeCache` / `comparablePairs` | `(from,to)` serialization theory-edge literal 与已建立 XOR 的无序 pair。 |
| `wwOrder` | `(writerFrom,writerTo,key) -> Boolean guard`；多个产生路径以 OR 合并。 |
| `dependencyEdgesA/B` / `GuardedDependencyEdge` | 等待最终物化的 typed logical edge + 条件项列表；多个 support 分别以单向子句激活同一边。 |
| `predicateDependencyAccumulators` | 按 `(from,to,type)` 合并 PR_WR/PR_RW 的 key 与条件项集合；不创建合取 guard 或 support OR 辅助变量。 |
| `logicalDependenciesByEndpoint` | `(from,to)` 对应的 `SEREdge(type,keys)`；编码后不再保留仅供物化使用的 guard/origin 包装，用于 explanation/debugging/paper description。 |
| `KeyFrontier` / `FrontierCandidate` | 某 predicate reader/key 的 latest-visible source 候选及 `visible=writer<reader` literal。 |

关键函数：

| 函数 | 作用 |
| --- | --- |
| `encodeRemainingWwChoices()` | 普通 WW/RW 的唯一 SAT 来源：每个 residual `SERConstraint` 创建一个带`A<n>`的`WW_CHOICE` assumption和一个 fresh `Lit forward`；两侧branch guard分别为`A AND forward`与`A AND not(forward)`，branch 中 WW/RW 由同一 guard 激活。 |
| `registerWwOrder()` / `wwOrderLiteral()` | 建 key-local WW guard；bottom 顺序返回常量；找不到明确 WW guard 时回退到 auxiliary `orderLiteral()`。 |
| `addDependencyEdge()` | ordinary edge 入 A/B queue；predicate edge 保留 assumption/source/latest-visible/WW/context 条件项，常量化简与去重后合入 `(from,to,type)` accumulator。 |
| `flushPredicateDependencies()` | 将已在线合并的 predicate accumulator 排入 A/B；合并 key 并保留各 witness 的条件项，不物化 OR。 |
| `encodeDependencyEdge()` | 先记录 logical metadata，再逐 support 提交 `not(term1) OR ... OR serialization(from,to)`；空条件列表表示确定边，不创建 implication 辅助变量。 |
| `ensureComparable()` / `directSerializationEdge()` | 对实际被请求比较的 pair assert 两方向 serialization edge XOR；不是预先创建全体 pair。 |
| `encodeSerializationAcyclicity()` | 断言唯一 `serializationGraph` 的 directed acyclicity literal 为 true。 |
| `solve()` / `solveOnce()` | 将全部 assumption literals 传给 `Solver.solve(assumptions)`；每次 `solve()` 只调用一次后端，直接返回 SAT/UNSAT，无追加子句、内部超时或重解。 |
| `extractConflicts()` / `getConflictReasons()` | 直接读取MonoSAT conflict clause，对literal取反后映射到`A<n>`及`WW_CHOICE/PREDICATE_OBLIGATION/GMWR_RULE`原因；旧`getConflicts()`只保留WW/known-edge legacy输出兼容，不再重建solver缩核。 |

## 8. Predicate / SER 扩展编码索引

| 路径 | 函数 | 作用 |
| --- | --- | --- |
| 共享 | `encodePredicateConstraints()` | 每个有效observation先注册`PREDICATE_OBLIGATION` assumption；消费已准备的 result-source map 和 query-scope write index；row-local 共用 `encodeRowLocalPredicate()`，非 row-local 的受支持单调 QueryPlan 显式编码 JOIN bindings；全部约束在求解前生成并受 observation assumption 守卫。 |
| GMWR | `encodeKnownEdges()` / `encodeResidualGmwr()` | 对结果中的确定事实和每条残余规则注册 `GMWR_RULE` assumption，守卫对应 order/clause；阶段冲突在创建求解器前处理。 |
| 共享 | `LatestVisibleChecker.check()` | 输入 reader、key、candidate writers 和 serialization order，统一计算 `writer<reader AND` 不存在更晚可见 writer 的 validity。 |
| 共享 | `createExternalKeyFrontier()` / `createExplicitQueryFrontier()` | 前者处理 row-local 未定来源；后者处理 JOIN frontiers。固定来源由 `assertFixedSourceLatest()` 对全部竞争写直接提交 O(m) latest 子句，JOIN 仅保留单候选 handle。 |
| 共享 | `encodeSelectedPredicateDependencies()` | selected source guard 激活 PR_WR；`selected AND source<later AND delta` 激活 PR_RW。 |
| EAGER | `encodeRowLocalPredicate()` | recorded source 调用 `encodeFixedPredicateSource()`，不构造 frontier；absent key 由 `encodeAbsentRowLocalKey()` 在唯一来源已确定可见时直接编码，否则调用 checker 并建立 bad-writer blocking disjunction。 |
| GMWR 构建 | `PredicatePruning.pruneGmwrItemCandidates()` / `collectGmwrLogicalConstraints()` | 先单独完成整批区间剪枝，再由保留的 bad writer 为 row-local absent key 产生 `(reader,badWriter)` GMWR item，repair 是产生空贡献的 good writers；item 显式保留。 |
| GMWR 剪枝 | `PredicatePruning.finalizeSourceDomains()` / `forceUniquePrWrSource()` / `prepareResidualItems()` | 完成 PR_WR 可达性、PR_RW 环及区间剪枝；唯一合法真实来源直接登记去重 typed PR_WR 并强制顺序，迭代收缩其他来源域；唯一 bottom 只解决来源选择；按来源约束和候选分别计数，移除已满足 item 与不可能 repair，交接只读候选域和残余列表。 |
| GMWR | `GmwrPropagationState` | `PrecedenceOracle` + obligation worklist；去除不可能 repair，识别 satisfied/conflict，强制 outside/single repair。 |
| GMWR | `encodeRowLocalPredicate()` | 固定来源直接编码；absent key 消费 `RowKey.sourceWrites`，确定可见单候选绕过容器与 checker，其他候选仍 source-aware；absent validity 由准备好的 GMWR residual items 表示。 |
| GMWR | `resolveAndEncodeGmwrObligations()` / `encodeResidualGmwr()` | 消费 `Result.residualItems()`，每个残余 item 进入 SAT：`reader<bad OR OR(bad<repair AND repair<reader)`。这些是 order literals/Boolean clauses，不直接产生 typed graph edge。 |
| JOIN | `encodeExplicitMultiRelationPredicate()` | 求解前枚举 contributing bindings、固定记录来源、排除额外 binding，并生成 context 守卫的 PR_WR/PR_RW。 |

## 9. MonoSAT 接口与图理论回传

| 层级 | 文件 / 函数 | 作用 |
| --- | --- | --- |
| Java API | `monosat/src/monosat/api/java/monosat/Graph.java::addEdge()` | JNI `newEdge`，返回“该物理 edge 是否包含在图中”的 literal。 |
| Java API | 同上 `Graph.acyclic()` | JNI `acyclic_directed`，返回 directed acyclicity literal。 |
| C API | `monosat/src/monosat/api/Monosat.cpp::newEdge()` | 创建 SAT var 并注册到 `GraphTheorySolver::newEdge()`。 |
| Graph theory | `monosat/src/monosat/graph/GraphTheory.h::newEdge()` | 把 edge var 同时加入 under/over approximation graph；under graph 初始禁用。 |
| Graph theory | 同上 `GraphTheorySolver::acyclic()` | 创建/复用 `CycleDetector` 并注册 acyclicity literal。 |
| Cycle propagation | `monosat/src/monosat/graph/CycleDetector.cpp::propagate()` | asserted acyclic 时，如果当前 true edge 的 under-approximation 有 directed cycle，构造 conflict。 |
| Cycle reason | 同上 `buildDirectedCycleReason()` | conflict reason 是该具体 cycle 上所有已启用 graph-edge literals 的否定，回传 CDCL。 |

## 10. 配置入口

| CLI | 默认 | 消费位置 |
| --- | --- | --- |
| `--[no-]gmwr` | 开 | 同时控制 GMWR formulation 与普通/absent-key frontier 剪枝；关闭时使用 EAGER。 |
| `--[no-]gmwr-prepropagation` | 开 | 控制 GMWR SAT 编码前传播；仅在 GMWR 开启时有效。 |
| `--solver-stats` | false | detailed predicate counts和配置输出。 |

生产 CLI 不再解析 `--predicate-encoding`、`--ww-pruning`、`--solver-timeout-seconds`、witness coalescing 或 graph-edge interning 开关。WW reachability、predicate witness coalescing 和 graph-edge interning 固定开启；检测器内部不设置求解超时，实验时限由外部 runner 控制。内部 `SolverSettings` 只为嵌入和差分测试保留细粒度字段。

## 11. Statistics / timing / debug 索引

| 文件 / 位置 | 内容 |
| --- | --- |
| `util/Profiler.java` | per-thread tag 的毫秒累计、count 累计；后台每 100 ms 采样 JVM used heap 的最大值。 |
| `SERVerifier.audit()` | `SER_VERIFY_INT`、`SER_GEN_PREC_GRAPH`、`SER_GEN_CONSTRAINTS`、`WW_REACHABILITY_PRUNE_MS`、`SER_AR_ENCODE`、`SER_AR_SOLVE`、`ONESHOT_*`。 |
| `SERSolverAR` constructor | 分阶段 `SER_AR_ENCODE_SETUP/KNOWN_EDGES/WW/PREDICATE/DEPENDENCIES/ACYCLIC`。 |
| `SERSolverAR.solve()` | `SER_MONOSAT_SOLVE`、`SER_AR_CONFLICT_EXTRACTION`；旧 refinement 计时已删除。 |
| `Pruning` / `PredicatePruning` / `GmwrPropagationState` | `SER_PRUNE*`；`GMWR_BUILD_MS` 仅计准备/构建，`GMWR_PRUNING_MS` 计初始区间过滤与最终候选/残余 item 整理，`GMWR_REDUCTION_MS` 仅计预传播。关闭预传播仍计普通剪枝，EAGER 三项均为零。剪枝阶段一次发布义务、残余、forced facts、来源与区间剪枝计数，以及 `SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT/RESIDUAL_CONSTRAINTS_COUNT/FORCED_CONSTRAINTS_COUNT`（处理前按未固定 row-local external absent observation/key 快照，初始−剩余＝强制解决），并发布 `SER_PRED_PR_WR_INITIAL_CANDIDATES_COUNT/RESIDUAL_CANDIDATES_COUNT/PRUNED_CANDIDATES_COUNT/FIXED_CANDIDATES_COUNT`（初始＝剩余待选＋排除＋固定）。唯一合法真实来源直接强制 PR_WR 及顺序，迭代收缩其他来源域；唯一显式/隐式 bottom 同样解决但无真实事务边；空域为冲突。约束计数独立于 typed 边去重，不是 GMWR item 或 SAT 子句数；编码阶段不再增加候选剪枝计数。 |
| `publishResidualSatStats()` | residual WW choice 数、`solver.nVars()`、`solver.nClauses()`。 |
| `PredicateEncodingMetrics.publish()` / `publishGmwrMetrics()` | 保留 source/scope/frontier/witness/physical edge/blocking clause/GMWR obligation 数量统计，以及整批残余义务编码的 `SER_GMWR_RESOLUTION` 计时。计时仅覆盖完整阶段，不再逐 key、逐边或逐条谓词读取调用时钟；对应累计耗时字段及输出已移除，GMWR 准备/构建以 `GMWR_BUILD_MS` 为准，普通剪枝另计 `GMWR_PRUNING_MS`，按前后两个完整批次累计。 |
| `Main.Audit.call()` | 遍历输出全部 duration/count、solver 配置、最大内存和最终 marker。 |
| `SERVerifier.emitRejectDiagnostics()` | 输出 typed-dependency UNSAT 原因、`!A1 | !A2 -> reason` 映射或当前 conflict core 摘要。 |
| `SERSolverARDifferentialTest.predicateWriterModeMatrixMatchesExhaustiveOracle()` | predicate × writer pattern × EAGER/GMWR 配置，逐项要求生产 SER verdict 等于穷举 AR oracle。 |
| `SERAcceptanceSuiteTest` | 按T1-T8编号组织的验收入口；常规运行覆盖基础WR/SO、WW/RW、predicate、模式与剪枝等价、共享oracle、单serialization graph结构和论文指标契约。 |

### 11.1 T1-T8 验收测试映射

| 类别 | 自动化覆盖 |
| --- | --- |
| T1 | `BasicAdyaCorrectness`：单WR、多reader、反向SO形成的WR/SO环。 |
| T2 | `WwRwEncoding`：WW合法分支、SO强制的WW/RW冲突、三 writer 的三个 client WW choice。 |
| T3 | `PredicateCorrectness`：returned/stale/overwrite、absent matching writer、bad/repair writer、INTERNAL和mixed key。T3.4按“写入值满足谓词但结果为空”的本意使用`x=20, x>10`；若使用`x<10`，`20`本身不属于结果，不能推出writer必须位于reader之后。 |
| T4 | 常规测试比较手工predicate corpus；`SER_ACCEPTANCE_EXTENDED=true`时额外执行100,000个生成history的EAGER/GMWR verdict差分。 |
| T5 | 对`Pruning`、`GmwrPropagationState`、`SERSolverAR`做共享 oracle 断言，并验证 fact 可见性和反向关系立即冲突。 |
| T6 | 反射守卫确保`SERSolverAR`只保留`serializationGraph`，并比较endpoint interning开关的verdict；小history语义基准继续由`SERSolverARDifferentialTest`的独立穷举AR oracle提供。 |
| T7 | 内部 `NONE/REACHABILITY` 与 EAGER/GMWR 对同一 corpus 必须保持 verdict 一致；另直接验证 reachability 删除成环 branch。 |
| T8 | 验证 EAGER/GMWR 发布 candidate、residual SAT vars/clauses 和 GMWR obligation 指标；实际规模、内存和运行时间由 `tools/run_ser_acceleration_ablation.py` 在独立进程中采集。 |

常规验收测试：

```bash
./gradlew test --tests verifier.SERAcceptanceSuiteTest
```

100,000 个 EAGER/GMWR 差分属于长跑测试，默认跳过；显式运行：

```bash
SER_ACCEPTANCE_EXTENDED=true ./gradlew test --tests verifier.SERAcceptanceSuiteTest
```

## 12. 需要在第二步深审的连接点

1. `SEREdge`、Guava `Edge`、MonoSAT `Graph.addEdge()` 返回的 literal 是三层不同对象，需逐关系说明映射。
2. WW choice literal 是 residual `SERConstraint` 的 Boolean guard；选择分支后 Java `KnownGraph` 不再追加 edge，实际激活发生在 MonoSAT model 中。
3. RW 在求解器构造时确实有 `SEREdge`/theory-edge candidate，但只有 WW guard 为 true 才被 implication 激活。
4. `guard -> serializationEdge` 是单向绑定；physical edge literal 不反向等价于某个具体 relation guard。
5. GMWR residual clause约束 serialization literals；typed PR_WR/PR_RW 元数据由独立 frontier 路径生成。
6. 生产调用链只消费 reachability 与 GMWR 产生的确定事实。
7. EAGER/GMWR row-local 公式与显式 JOIN 编码均由独立串行执行 oracle 做差分；旧 snapshot-only 包装、循环后备与 monotone witness 子集已删除。当前测试范围不包含 DISTINCT，有限小 history 差分不能替代全局证明。
