# SER 检测器代码脉络索引

> 审计对象：`SER/ser-result-detector` 当前工作树（包含当前未提交实现）。本文件只建立真实入口、模块、核心类、关键函数和调用关系；完整语义与风险分析见第二步生成的 `SER_DESIGN.md`。

## 1. 可执行入口与最终结果

| 层级 | 文件 | 类 / 函数 | 代码作用 |
| --- | --- | --- | --- |
| JAR/CLI 入口 | `SER/ser-result-detector/src/main/java/Main.java` | `Main.main(String[])` | 创建 Picocli `CommandLine`，只注册 `audit` 子命令，进程退出码取该命令返回值。 |
| SER 命令入口 | 同上 | `Audit.call()` | 解析 SER 配置，构造 `PredicateHistoryLoader`、`SERVerifier.SolverSettings`，调用 `SERVerifier.audit()`，打印 timing/count、最大内存和 verdict marker。 |
| checker 总控 | `SER/ser-result-detector/src/main/java/verifier/SERVerifier.java` | `SERVerifier.audit()` | 串联一致性检查、`KnownGraph`、WW/RW constraint、基线 pruning、`SERSolverAR` 编码和求解，并映射到 verdict。 |
| precedence primitive | `SER/ser-result-detector/src/main/java/verifier/PrecedenceOracle.java` | `before/successor/predecessor/wouldCycle` | 唯一 Java precedence engine；统一维护增量传递闭包和批量 branch 成环预判。 |
| 求解入口 | `SER/ser-result-detector/src/main/java/verifier/SERSolverAR.java` | 构造函数；`solve()`；`solveOnce()` | 构造 logical dependency layer + 唯一 MonoSAT serialization graph；调用 MonoSAT；必要时做 predicate model refinement。 |
| latest-visible primitive | `SER/ser-result-detector/src/main/java/verifier/LatestVisibleChecker.java` | `check(reader,key,candidateWriters,serializationOrder)` | 统一返回每个候选的 visible literal 与 latest-writer validity；EAGER 直接使用完整候选，GMWR 先缩减候选再调用。 |
| verdict | `SERVerifier.AuditResult` | `ACCEPT/REJECT/TIMEOUT/INVALID_HISTORY` | `SAT -> ACCEPT(0)`；`UNSAT` 或 checker 提前冲突 -> `REJECT(-1)`；超时 -> `TIMEOUT(124)`。`INVALID_HISTORY(2)` 在当前 `audit()` 路径中没有显式转换点。 |

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
        -> new SERSolverAR(history, graph, residualConstraints, ...)
           -> propagateBeforeEncoding [GMWR only]
           -> buildKnownOrder
           -> encodeKnownEdges
           -> encodeRemainingWwChoices
           -> encodePredicateConstraints
           -> encodeDependencyEdges
           -> encodeSerializationAcyclicity
        -> SERSolverAR.solve
           -> solveOnce -> monosat.Solver.solve[Limited]
           -> refinePredicateConstraints [general queries, zero or more rounds]
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
| `src/main/java/history/query/StructuredQueryParser.java` | `parse()` | 把结构化 query JSON 解析为 relational/expression AST 和 `QueryPlan`。 |
| `src/main/java/history/query/QueryPlan.java` | `evaluate()` / `isRowLocal()` / `isMonotone()` | 执行 query；返回投影值和实际输入版本；决定 row-local eager/GMWR 与 general lazy 路径。 |
| `src/main/java/history/query/QueryEvaluation.java` | `QueryEvaluation` | 保存 `values`、`inputs`、值多重集、canonical inputs。 |
| `src/main/java/history/query/RecordedQueryResult.java` | `GeneralRecordedQueryResult` / `RowLocalRecordedQueryResult` | 保存或紧凑表示 history 中记录的 query 结果，并提供 canonical equality。 |
| `src/main/java/history/query/MapVisibleState.java` | `MapVisibleState` | checker 在具体候选 snapshot 上执行 query 时的 key/value 可见状态。 |

当前代码没有名为 `Vset` 的 class/field。与“观察到的版本集合”对应的实际结构是 `result.inputs -> Event.PredResult / RecordedQueryResult.inputs()`，进入 checker 后再转换为 `KnownGraph.PredicateObservation.tupleSources`、solver 的 `KeyFrontier` 和具体 snapshot map。

## 3. 解析后的一致性门禁

文件：`SER/ser-result-detector/src/main/java/verifier/Utils.java`

| 函数 | 作用 |
| --- | --- |
| `verifyInternalConsistency()` | 在构图前建立 `(key,value)->writes` 和 transaction-local write positions，逐个验证 point read 与 predicate read。失败由 `SERVerifier.audit()` 直接映射为 `REJECT`。 |
| `checkItemRead()` | source 必须唯一；self-read 必须读最新先前 self write；external read 必须来自 writer transaction 对该 key 的最终 write，且 reader 之前不能已有 self write。 |
| `checkPredicateRead()` | 校验结果 key 唯一、source 唯一且 committed/in-scope、internal latest-write、重复同谓词读继承规则以及 recorded inputs。 |
| `predicateSnapshotMatches()` | 对全部 key 都能由 transaction-local writes 决定的 general query 立即执行完整 snapshot 校验；否则外部部分延迟给 solver。 |

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
| WW | `SERVerifier.generateConstraintsCoalesce` 产生两方向候选；pruning/GMWR feedback 可把被迫分支提交到 A | residual 分支由 `SERSolverAR.encodeRemainingWwChoices()` 以 Boolean guard 编码。 |
| RW | 同一 WW decision 分支内由固定 WR + 另一 writer 产生；forced 分支提交到 B，residual 分支由同一 guard 激活 | 无独立 RW 重建路径。 |
| Pred-WR (`PR_WR`) | 主路径初始 `KnownGraph` 不生成 | `SERSolverAR` 的 recorded source / selected frontier 产生 fixed 或 guarded PR_WR；GMWR preprop 可产生 definite typed PR_WR。 |
| Pred-RW (`PR_RW`) | 主路径初始 `KnownGraph` 不生成 | `LatestVisibleChecker` 产出 source validity，`assertLatestVisible()`、`encodeSelectedPredicateDependencies()` 根据 selected source、later write 和 result delta 产生 guarded PR_RW。 |

## 5. 普通 WW/RW candidate 与保存结构

| 文件 | 类 / 函数 | 作用 |
| --- | --- | --- |
| `src/main/java/verifier/SEREdge.java` | `SEREdge` | checker/solver 层的带 `(from,to,type,keys)` 逻辑依赖描述；不是 Guava graph edge，也不是 MonoSAT literal。 |
| `src/main/java/verifier/SERConstraint.java` | `SERConstraint` | `edges1 OR edges2`，附两个 writer transaction 和 id。这里没有单独的 `Option`/`Group` class；两个 edge collections 就是两个 option，constraint 本身就是 group。 |
| `src/main/java/verifier/SERVerifier.java` | `generateConstraintsCoalesce()` | 对同 key writer pair 创建双向 WW；把对应 `WR(a,b,k)` 推出的 `RW(b,c,k)` 放入选择 `WW(a,c,k)` 的同一分支；同一 transaction pair 的多个 key 合并为一个 constraint。 |

## 6. Pruning / reachability

| 模式 | 文件 / 入口 | 真实作用 |
| --- | --- | --- |
| `NONE` | `SERVerifier.audit()` switch | 不做 checker pruning。 |
| `REACHABILITY`（默认） | `new Pruning(precedence)::pruneConstraints()` | 用 A+B 中非 predicate edge填充 audit唯一的 `PrecedenceOracle`；由 oracle判定整个branch加入后是否成环；一侧非法则提交另一侧WW/RW到 `KnownGraph`并删除constraint；两侧非法直接冲突。 |
| GMWR preprop | `GmwrPropagationState.propagate()` | solver 构造阶段的第二层 checker propagation；削减 GMWR repair/frontier domain，产生 definite order/typed facts或冲突。 |
| WW feedback | `SERSolverAR.propagateGmwrToWwFixpoint()` -> injected `GmwrWwBridge.scan[affected]()` | 仅 `serPropagationMode=WW_GMWR`：用共享 deterministic precedence判断 residual WW branches，提交 forced WW/RW，重跑 baseline reachability prune，再同步回 GMWR。 |

### 6.1 REACHABILITY

保留 `Pruning` 的完整 branch-cycle判断：对两个候选 branch分别调用共享 oracle的批量 `wouldCycle()`；一侧非法时物化另一侧，两侧非法时报告冲突。此次统一只改变 `before relation` 的所有权，不改变该规则。

### 6.2 唯一 deterministic before relation

`SERVerifier`创建一次 `PrecedenceOracle<Transaction>`，并经constructor injection传给 `Pruning`、`SERSolverAR`、`GmwrPropagationState`和 `GmwrWwBridge`。所有生产组件的 `before(a,b)`读取同一实例，solver known-order不再另建 closure。

oracle只保存history/known graph、pruning结论与GMWR forced facts等 deterministic order。residual WW guard、frontier selection和其他MonoSAT decision literal只存在于Boolean/theory encoding，绝不反写oracle。独立的conflict-extraction solve拥有自己的solve context，不与主solve共享可变oracle。

## 7. SERSolverAR 编码索引

文件：`SER/ser-result-detector/src/main/java/verifier/SERSolverAR.java`

构造期真实顺序：

1. 创建 `monosat.Solver`、唯一 `serializationGraph` 及 real transaction nodes（bottom 不进 MonoSAT 图）。
2. 建 write/key 索引。
3. `propagateBeforeEncoding()`：GMWR obligation/frontier 构造及可选预传播、WW feedback。
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
| `dependencyEdgesA/B` / `GuardedDependencyEdge` | 等待最终物化的 typed logical edge + guard。 |
| `predicateDependencyCandidates` | 合并前逐 key PR_WR/PR_RW witness。 |
| `logicalDependenciesByEndpoint` | `(from,to)` 对应的所有 `GuardedDependencyEdge(SEREdge(type,keys),guard)`；不依赖 MonoSAT 边保留语义，用于 explanation/debugging/paper description。 |
| `KeyFrontier` / `FrontierCandidate` | 某 predicate reader/key 的 latest-visible source 候选及 `visible=writer<reader` literal。 |
| `PredicateCheck` | general query 的 lazy snapshot refinement 记录。 |

关键函数：

| 函数 | 作用 |
| --- | --- |
| `encodeRemainingWwChoices()` | 普通 WW/RW 的唯一 SAT 来源：每个 residual `SERConstraint` 创建一个带`A<n>`的`WW_CHOICE` assumption和一个 fresh `Lit forward`；两侧branch guard分别为`A AND forward`与`A AND not(forward)`，branch 中 WW/RW 由同一 guard 激活。 |
| `registerWwOrder()` / `wwOrderLiteral()` | 建 key-local WW guard；bottom 顺序返回常量；找不到明确 WW guard 时回退到 auxiliary `orderLiteral()`。 |
| `addDependencyEdge()` | ordinary edge 入 A/B queue；predicate edge 先做 source legality/dedup，再进 candidate list。 |
| `prunePredicateDependencies()` | 按 `(from,to,type)` 合并 predicate witnesses，key 集合合并，guard 取 OR；这就是 predicate witness coalescing。 |
| `encodeDependencyEdge()` | 先记录 logical metadata，再 assert `guard -> serialization(from,to)`。fixed edge 的 guard 是 `True`。 |
| `ensureComparable()` / `directSerializationEdge()` | 对实际被请求比较的 pair assert 两方向 serialization edge XOR；不是预先创建全体 pair。 |
| `encodeSerializationAcyclicity()` | 断言唯一 `serializationGraph` 的 directed acyclicity literal 为 true。 |
| `solve()` / `solveOnce()` | 将全部assumption literals传给`Solver.solveLimited(assumptions)`或`solve(assumptions)`；SAT model 后执行 general predicate refinement，直到无新 clause、UNSAT 或 TIMEOUT。 |
| `extractConflicts()` / `getConflictReasons()` | 直接读取MonoSAT conflict clause，对literal取反后映射到`A<n>`及`WW_CHOICE/PREDICATE_OBLIGATION/GMWR_RULE`原因；旧`getConflicts()`只保留WW/known-edge legacy输出兼容，不再重建solver缩核。 |

## 8. Predicate / SER 扩展编码索引

| 路径 | 函数 | 作用 |
| --- | --- | --- |
| 共享 | `encodePredicateConstraints()` | 每个有效observation先注册`PREDICATE_OBLIGATION` assumption；按 observation 建 result-source map 和 query-scope write index；row-local 分派 EAGER/GMWR，general 路径建 frontiers 和 lazy check，所有直接断言、PR guards和后续no-good均受同一assumption守卫。 |
| GMWR | `encodeKnownEdges()` / `encodeResidualGmwr()` | propagation forced fact、propagation conflict及每条materialized residual rule注册`GMWR_RULE` assumption，并以该literal守卫对应order或clause。 |
| 共享 | `LatestVisibleChecker.check()` | 输入 reader、key、candidate writers 和 serialization order，统一计算 `writer<reader AND` 不存在更晚可见 writer 的 validity。 |
| 共享 | `createKeyFrontier()` | EAGER 将完整候选交给 checker；GMWR 先做现有 source/reachability/interval 缩减再调用；随后建立 PR_WR/PR_RW 候选。 |
| 共享 | `encodeSelectedPredicateDependencies()` | selected source guard 激活 PR_WR；`selected AND source<later AND delta` 激活 PR_RW。 |
| EAGER | `encodeRowLocalPredicateEager()` | recorded source 与 absent frontier 均直接复用 `LatestVisibleChecker`；absent key 对每个 bad visible writer 立即建立完整 blocking disjunction。 |
| GMWR | `collectGmwrLogicalConstraints()` | row-local absent key 产生 `(reader,badWriter)` GMWR item，repair 是产生空贡献的 good writers；相同 pair bundle 化。 |
| GMWR | `GmwrPropagationState` | `PrecedenceOracle` + obligation/frontier worklist；去除不可能 repair，识别 satisfied/conflict，强制 outside/single repair/unique PR_WR。 |
| GMWR | `encodeRowLocalPredicateGmwr()` | typed predicate frontier仍 source-aware；absent result 的 Boolean validity clauses由 GMWR bundle代替。 |
| GMWR | `resolveAndEncodeGmwrBundles()` / `encodeResidualGmwr()` | 未由 checker 决定的每个 item进入 SAT：`reader<bad OR OR(bad<repair AND repair<reader)`。这些是 order literals/Boolean clauses，不直接产生 typed graph edge。 |
| General | `refineGeneralPredicateConstraints()` | 从 SAT model 选各 key frontier，执行真实 `QueryPlan.evaluate()`；不匹配则加入所选组合的 no-good clause并重新 solve。GMWR monotone case可只取新增 input keys 作 witness。 |

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
| `--predicate-encoding` | `GMWR` | 公开算法选择；默认映射完整 G2，`eager` 映射 E2。 |
| `--solver-timeout-seconds` | 600 | `SERSolverAR.solveOnce()`。 |
| `--solver-stats` | false | detailed predicate counts和配置输出。 |

普通 `audit --help` 只公开上述三项。E1/G1 与剪枝实验仍可使用隐藏的 `--ww-pruning`、`--ser-propagation-mode`、`--[no-]gmwr-prepropagation`、`--[no-]predicate-witness-coalescing` 和 `--[no-]graph-edge-interning`。固定 backend/loader、旧谓词别名、非合并 WW、增量校验和派生图诊断入口均已删除。

## 11. Statistics / timing / debug 索引

| 文件 / 位置 | 内容 |
| --- | --- |
| `util/Profiler.java` | per-thread tag 的毫秒累计、count 累计；后台每 100 ms 采样 JVM used heap 的最大值。 |
| `SERVerifier.audit()` | `SER_VERIFY_INT`、`SER_GEN_PREC_GRAPH`、`SER_GEN_CONSTRAINTS`、`WW_REACHABILITY_PRUNE_MS`、`SER_AR_ENCODE`、`SER_AR_SOLVE`、`ONESHOT_*`。 |
| `SERSolverAR` constructor | 分阶段 `SER_AR_ENCODE_SETUP/KNOWN_EDGES/WW/RW/PREDICATE/DEPENDENCIES/TOTAL_ORDER`。 |
| `SERSolverAR.solve()` | `SER_MONOSAT_SOLVE`、`SER_AR_PREDICATE_REFINEMENT`、`SER_AR_CONFLICT_EXTRACTION`。 |
| `Pruning` / `GmwrPropagationState` | `SER_PRUNE*`、`GMWR_BUILD_MS`、`GMWR_REDUCTION_MS`、`GMWR_WW_BRIDGE_MS`。 |
| `publishResidualSatStats()` | residual WW choice 数、`solver.nVars()`、`solver.nClauses()`。 |
| `PredicateEncodingMetrics.publish()` / `publishGmwrMetrics()` | source/scope/frontier/witness/physical edge/blocking clause/GMWR bundle 与各子阶段耗时。 |
| `Main.Audit.call()` | 遍历输出全部 duration/count、solver 配置、最大内存和最终 marker。 |
| `SERVerifier.emitRejectDiagnostics()` | 输出 typed-dependency UNSAT 原因、`!A1 | !A2 -> reason` 映射或当前 conflict core 摘要。 |
| `SERSolverARDifferentialTest.predicateWriterModeMatrixMatchesExhaustiveOracle()` | predicate × writer pattern × EAGER/GMWR/WWBridge 配置，逐项要求生产 SER verdict 等于穷举 AR oracle。 |
| `SERAcceptanceSuiteTest` | 按T1-T8编号组织的验收入口；常规运行覆盖基础WR/SO、WW/RW、predicate、模式与剪枝等价、共享oracle、单serialization graph结构和论文指标契约。 |

### 11.1 T1-T8 验收测试映射

| 类别 | 自动化覆盖 |
| --- | --- |
| T1 | `BasicAdyaCorrectness`：单WR、多reader、反向SO形成的WR/SO环。 |
| T2 | `WwRwEncoding`：WW合法分支、SO强制的WW/RW冲突、三 writer 的三个 client WW choice。 |
| T3 | `PredicateCorrectness`：returned/stale/overwrite、absent matching writer、bad/repair writer、INTERNAL和mixed key。T3.4按“写入值满足谓词但结果为空”的本意使用`x=20, x>10`；若使用`x<10`，`20`本身不属于结果，不能推出writer必须位于reader之后。 |
| T4 | 常规测试比较手工predicate corpus；`SER_ACCEPTANCE_EXTENDED=true`时额外执行100,000个生成history的EAGER/GMWR verdict差分。 |
| T5 | 对`Pruning`、`GmwrPropagationState`、`GmwrWwBridge`、`SERSolverAR`做同实例断言，并验证fact可见性和反向关系立即冲突。 |
| T6 | 反射守卫确保`SERSolverAR`只保留`serializationGraph`，并比较endpoint interning开关的verdict；小history语义基准继续由`SERSolverARDifferentialTest`的独立穷举AR oracle提供。 |
| T7 | `NONE/REACHABILITY`、GMWR和WWBridge对同一corpus必须与baseline verdict一致；另直接验证reachability删除成环branch。 |
| T8 | 验证EAGER/GMWR发布runner消费的candidate、residual SAT vars/clauses和GMWR obligation指标；实际规模、内存和运行时间由`tools/run_ser_baseline_vs_gmwr.py`在独立进程中采集。 |

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
7. EAGER/GMWR row-local 的公式可逐项对应；general GMWR 的 monotone witness 缩小 clause 是否在全部 query shape 上等价，需要结合结构判定和差分测试边界说明。当前P0矩阵已把JOIN纳入完整visible-snapshot oracle，但有限小history差分不能替代全局证明。
