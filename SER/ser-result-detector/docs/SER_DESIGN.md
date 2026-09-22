# SER 检测器当前结构

## 1. 设计边界

当前 checker 的主链是：

```text
PRHIST
  -> 内部一致性检查
  -> SO / point-WR / ordinary WW-RW candidates
  -> baseline WW reachability
  -> PredicatePruning（逐 key 分析、GMWR 传播、来源/区间剪枝、残余整理；冲突提前 REJECT）
  -> SERSolverAR（消费共享分析和剪枝结果，完整 predicate encoding）
  -> residual Boolean clauses + 唯一 serializationGraph
  -> MonoSAT acyclicity（单次求解）
  -> ACCEPT / REJECT
```

必须区分三层对象：

```text
history 中的读写事实
    != Java 层 typed dependency / obligation
    != SAT guard 或 MonoSAT serialization edge literal
```

`PR_WR`、`PR_RW`、`WR`、`WW`、`RW` 的类型和 key 保留在 Java metadata；它们投影到 MonoSAT 时，相同 `(from,to)` 可以共用一个物理 edge literal。复用物理 edge 不会把不同语义边改成同一种关系。

## 2. 当前默认与公开开关

`audit HISTORY` 与裸 `SolverSettings` 都默认开启：

- WW reachability；
- GMWR formulation；
- 普通 key 与 absent-key 的 frontier pruning；
- GMWR prepropagation；
- predicate witness coalescing；
- graph-edge interning。

只保留两个算法开关：

| CLI | 默认 | 含义 |
| --- | --- | --- |
| `--[no-]gmwr` | 开 | 同时控制 GMWR formulation 与 frontier pruning；关闭时走 EAGER。 |
| `--[no-]gmwr-prepropagation` | 开 | 控制 GMWR obligation 在 SAT 编码前的确定性传播；关闭 GMWR 时不生效。 |

检测器内部不设置求解超时；实验时间上限由外部 runner 的进程超时控制。stats 是运维参数，不属于算法消融。

生产 CLI 不再解析 WW pruning、predicate encoding、witness coalescing、edge interning、bundle 或 propagation mode 的旧参数。对应的细粒度 Java 字段仅保留给内部差分测试和嵌入调用。

## 3. 输入和一致性门禁

`Main.Audit` 使用 `PredicateHistoryLoader` 加载 PRHIST。`SERVerifier` 构造器保存加载后的 `History`，`audit()` 首先调用 `Utils.verifyInternalConsistency()`。

一致性门禁验证：

- transaction/session 顺序和 committed 状态；
- point read 的唯一来源、自写位置和外部最终写；
- predicate result inputs、投影结果和 query scope；
- row-local 与 general query 共用的结果比较规则。

失败直接产生 `REJECT`；格式或运行错误由 CLI 输出 `ERROR`。

## 4. KnownGraph 与普通 WW/RW

`KnownGraph` 收集 transaction node、SO、point-WR、写索引和 predicate observations。predicate dependency 主要在 `SERSolverAR.encodePredicateConstraints()` 中生成，而不是在 `KnownGraph` 构造时统一生成。

`SERVerifier.generateConstraintsCoalesce()` 为同 key 的 writer pair 生成二选一：

```text
branch 1: WW(A,B) + 与该选择绑定的 RW
branch 2: WW(B,A) + 与该选择绑定的 RW
```

未决 branch 用 SAT literal 表示。相应的 MonoSAT edge 在 solve 前创建，由 branch guard 决定是否激活；solver 不会在求解过程中回写 `KnownGraph`。

## 5. Baseline WW reachability

一次 audit 创建唯一 `PrecedenceOracle<Transaction>`。`Pruning.pruneConstraints()` 对每个完整 WW/RW branch 调用批量 `wouldCycle()`：

- 一侧成环：提交另一侧的确定 WW/RW，并删除该 constraint；
- 两侧成环：立即冲突；
- 两侧均可行：保留给 SAT。

该阶段固定启用并发生在 `SERSolverAR` 构造前。当前没有 GMWR-to-WW feedback；GMWR 不会再次扫描或固定 residual WW choice。此前讨论的 WW reachability 外层提前终止问题本轮没有修改。

## 6. Predicate frontier

固定 `recordedSource=A` 时，直接断言 `A<R`，并对每个其他 external final write `B` 断言 `NOT(A<B AND B<R)`，所有子句保留 observation assumption。latest 校验覆盖全部竞争写，包括不改变谓词结果的写；PR_RW 仍只针对满足结果变化条件的后继写。固定来源只做 O(m) 比较，不为全部候选构造 latest；row-local 路径不创建 frontier/candidate 容器，general/JOIN 仅保留来源 A 的单候选 handle。

未固定来源时，`createExternalKeyFrontier()` 为 row-local external key 生成 latest-visible source candidates。独立阶段的 `PredicatePruning.analyzeRowKey()` 准备 absent-key 数据，`collectGmwrLogicalConstraints()` 生成 bad-writer obligations。编码器使用 `RowKey.sourceWrites`，不重复分析或裁剪候选域。row-local absent key 在原有候选缩减后只剩一个来源、且已确定 `source<reader` 时，直接检查空贡献并生成依赖；可见性未定则仍走通用 checker。该简化主要减少 Java 容器、stream 和候选对象分配；`frontiers`/`frontierCandidates` 统计保留逻辑域/候选计数，不等于实际分配的对象数。

`LatestVisibleChecker` 在当前求解器实例内共享相同的二元 AND 公式；每个候选的 latest 条件先收集合取项并折叠常量，再按 literal 编号排序、线性去重，共享多输入 AND，避免串联生成重复辅助变量。共享只影响布尔公式表示，不改变可见性、latest 条件、候选域及 GMWR item、逻辑依赖边的语义；GMWR 仍按 item 计数，计时仍只统计完整阶段。

两条路径遵循同一个开关边界：

```text
GMWR:
  PredicatePruning：来源可达性 / PR_RW 环 / pruneInterval
  -> RowKey.sourceWrites + residualItems
  -> SAT：确定单候选简化 / LatestVisibleChecker；残余 item 子句

EAGER (--no-gmwr):
  完整 external candidates
  -> 确定单候选简化 / LatestVisibleChecker；eager blocking clauses
```

因此 frontier 不再是独立开关。它和 GMWR 是同一搜索域缩减机制的两个入口，由 `--[no-]gmwr` 一起控制。

## 7. EAGER 与 GMWR

### 7.1 公共部分

两条路径共用：

- recorded source 检查；
- `LatestVisibleChecker`；
- source-aware `PR_WR/PR_RW`；
- 受支持 JOIN 的完整 binding 编码；
- 唯一 `serializationGraph`；
- witness coalescing 和 physical edge interning。

两条路径必须保持 ACCEPT/REJECT 等价，差别是 row-local predicate 的公式组织和候选域。

### 7.2 EAGER

EAGER 使用完整 external candidate 集，不运行 GMWR propagation。固定来源和确定可见的单候选使用上述简化路径，其余 row-local key 建立通用 latest-visible 与 bad-writer blocking clause。

### 7.3 GMWR

对 reader `R`、bad writer `B` 和 repair writer `G`，单个 item obligation 为：

```text
R < B  OR  OR_G(B < G AND G < R)
```

当前实现：

- 每个 item obligation 显式保留；
- 相同 `(R,B)` 可复用 outside-order literal 和 propagation state；
- 不进行 bundle compaction、duplicate item clause 合并或 repair-set antichain subsumption；
- 只对未由传播解决的 item 调用 `encodeResidualGmwr()`。

`resolveAndEncodeGmwrObligations()` 是残余义务的编码入口。

## 8. GMWR prepropagation

`GmwrPropagationState` 使用共享 `PrecedenceOracle` 和 worklist：

- 删除已知不可能满足 `B < G < R` 的 repair；
- 标记已由 `R < B` 或已知 repair chain 满足的 obligation；
- 在 outside 分支不可能且只剩唯一 repair 时传播确定顺序；
- 没有任何合法 disjunct 时报告冲突。

`seedKnownDependencies()` 初始化时保留顺序环检查与共享闭包更新，关闭 dirty 通知；`propagate()` 开始时统一将 obligation 入队。正常传播中，`PrecedenceOracle.add()` 在 BitSet 闭包更新时通过差集收集新增可达关系的端点，前向/反向闭包全部更新后，每个变化端点仅通知一次；`GmwrPropagationState` 合并端点关联的 obligation 后去重入队。该路径保留对传递可达关系变化的完整通知，删除前驱×后继的逐对扫描、Relation 临时列表和逐关系重复通知。

`PredicatePruning.java` 是独立于 `SERSolverAR` 的阶段文件，由 `SERVerifier.audit()` 在 WW 剪枝后调用。结果中的冲突原因不依赖 MonoSAT，确定性矛盾在构造求解器前拒绝；无冲突时将共享 oracle、`PredicateAnalysis`、已准备的 observation/逐 key 候选域、确定事实及残余 item 快照交给编码器，不暴露活动传播状态。阶段内部依次执行 `prepareObservations()`、`pruneGmwrItemCandidates()` 初始区间剪枝、GMWR items 构造及可选传播、`finalizeSourceDomains()`、`prepareResidualItems()`。EAGER/GMWR 共用编码器的逐 key 遍历；编码期仍保留 literal 化简和已知不可激活 witness 过滤，不再修改来源候选域。

逐 key 写索引的 latest-writer 列表只冻结一次，`RowKey` 复用不可变候选列表；内部读取、固定来源及空分类结果使用共享空集合。空贡献集合由阶段内独占构建并冻结，最终候选域整理复用该集合和既有来源 map，不再次复制。GMWR 在初始区间剪枝后用 `badWrites` 暂存实际需要构造 item 的 bad writer；最终候选域确定后重新分类供编码器使用。两轮均从完整 `externalWrites` 出发，repair 集合仍取完整外部版本中的空贡献写，不能把第一轮区间候选当成 repair 域。EAGER 在初次分类时生成 `badWrites`。对外交接仍只读，item 顺序和剪枝规则不变。

计时分为三个不重叠的口径：`GMWR_BUILD_MS` 仅包含初始化、observation 准备与 item 构造；`GMWR_PRUNING_MS` 包含初始区间过滤，以及传播后的来源可达性、PR_RW 环、区间过滤和残余 item 整理；`GMWR_REDUCTION_MS` 仅计 `propagate()` 工作队列预传播。普通剪枝按前后两个完整批次累计，包含候选结果整理和只读交接开销，不在逐 key/逐边循环内读取时钟。旧版 `GMWR_BUILD_MS` 包含普通剪枝，不能直接与新版同名指标比较；新版 build 与 pruning 之和才对应旧版混合口径。

PR_WR 按两套单位统计。`SER_PRED_PR_WR_INITIAL_CONSTRAINTS_COUNT`、`SER_PRED_PR_WR_RESIDUAL_CONSTRAINTS_COUNT`、`SER_PRED_PR_WR_FORCED_CONSTRAINTS_COUNT` 对应初始未解决、剩余未解决和强制解决的来源选择数；终端显示 `PR_WR constraints` 和 `Forced PR_WR constraints`。范围为 row-local、非 internal、无 recorded source 且无已有确定 PR_WR 的 observation/key，每个计一条。`RowKey.unresolvedPrWr` 保存处理前的纳入状态；初始数在 `prepareObservations()` 期间记录，不再由最终新增去重边数反推。不同 external observation 即使对应同一 typed 边，约束也分别计数；已有 internal 重复读取不纳入。

逐候选路径通过 `SER_PRED_PR_WR_INITIAL_CANDIDATES_COUNT`、`SER_PRED_PR_WR_RESIDUAL_CANDIDATES_COUNT`、`SER_PRED_PR_WR_PRUNED_CANDIDATES_COUNT`、`SER_PRED_PR_WR_FIXED_CANDIDATES_COUNT` 发布，分别对应处理前候选总数、最终仍待选择的候选数、被排除候选数、唯一选定候选数。初始候选取完整外部末次写域中不贡献结果的写；没有初始版本的 key 还计一个隐式 bottom（尚无可见版本）。最终只有在不存在初始版本且没有任何外部写已确定在 reader 前时，才保留隐式 bottom。显式 bottom 按其初始值的谓词贡献判断是否合法。候选由 3 个缩至唯一 1 个时，记录排除 2、固定 1、剩余待选 0；候选被剪空属于冲突，该来源约束仍计未解决，不能算强制解决。

`finalizeSourceDomains()` 以不贡献结果的合法来源数判断唯一性，不要求包含 bad writer 的完整 frontier 唯一，也不要求事先已有 `source<reader`。唯一真实来源直接向 KnownGraph 登记 `PR_WR(source,reader,key)` 并更新共享 oracle 的顺序；新增顺序后继续整理所有来源域，直到不再新增强制顺序，再发布最终计数和准备残余 item。SAT 所需的 bad-writer frontier 仍保留。唯一显式或隐式 bottom 同样解决来源约束，但不生成真实事务间的 PR_WR 边。真实 typed 边按 `(source,reader,type,key)` 去重，并由 `SERSolverAR.encodeKnownTypedEdges()` 纳入编码前 fixed PR 统计。

发布时检查 `initialConstraints−residualConstraints=forcedConstraints` 以及 `initialCandidates=residualCandidates+prunedCandidates+fixedCandidates`；每个被解决的来源约束恰好固定一个候选，固定候选数等于强制解决约束数，比例为 forced/initial（分母零时为 0%）。固定 recorded source、internal 和 JOIN 不属于这两套统计。关闭预传播仍执行普通剪枝和唯一来源强制；EAGER 各项为零。旧 `SER_PRED_PR_WR_FORCED_EDGES_COUNT` 已移除；其按新增去重真实边的口径不可与新解决约束数直接比较。

既有 `GMWR_INITIAL_CONSTRAINTS` 仍表示初始区间过滤后实际构建的 item 数，`GMWR_RESIDUAL_CONSTRAINTS` 仍表示残余 item 数，供既有实验字段使用。旧的 `SER_PRED_CONSTRAINTS_BEFORE_COUNT/AFTER_COUNT` 结果一致性约束计数及对应摘要已移除，不能与新的 PR_WR 来源约束数混用。

关闭 `--gmwr-prepropagation` 后，GMWR obligation、frontier 与普通候选剪枝仍执行，预传播耗时为零。EAGER 不创建 GMWR propagation state，因此 `--no-gmwr` 会把 prepropagation 的实际值固定为 false。

## 9. SAT / MonoSAT 编码

`SERSolverAR` 的构造顺序为：

1. 校验 `PredicatePruning.Result` 没有冲突并共享同一 oracle；
2. 创建 `Solver`、唯一 `serializationGraph` 和 transaction nodes；
3. 复用 `PredicateAnalysis` 写索引及剪枝结果，计算已知顺序约简；
4. 编码已知 typed edges；
5. 编码 residual WW choices；
6. 编码 predicate constraints；
7. 将 guarded logical dependencies 映射到 serialization edge；
8. assert `serializationGraph.acyclic()`。

`encodeDependencyEdge()` 对每个 support 直接提交单向条件子句：

```text
assumption AND selectedSource AND WW AND changeContext -> serialization edge
等价子句：NOT assumption OR NOT selectedSource OR NOT WW OR NOT changeContext OR edge
```

row-local PR_RW 仍要求来源成立、对应 WW 成立且写入改变谓词结果：匹配变不匹配、不匹配变匹配，或都匹配但贡献值改变；结果变化在 Java 中判断，非 row-local 路径保留原有 context 条件。来源/latest 本身的语义约束不变。

依赖激活不再创建 AND guard、各 support 的 OR 链或 implication 辅助变量；同端点仍合并 typed key metadata、复用物理边，每个不同 support 分别提交子句。assumption 保留在子句中用于冲突解释。物理 edge 为真不会反推出某个具体来源、WW 或匹配条件。

## 10. 单次求解与查询范围

当前检测器采用 `(key, value)` 唯一模型，不支持 DISTINCT，也不保留自定义全快照谓词的循环后备路径。`PredicateAnalysis` 在独立剪枝开始前检查谓词形态（此时尚未分配 native solver），不支持的形态抛出 `QueryException`，不会忽略查询后返回 ACCEPT。

声明 `isRowLocal()` 的谓词统一进入 EAGER/GMWR 完整编码，包括程序构造的逐行谓词。非 row-local 的单调 `QueryPlan`（当前受支持 JOIN）在求解前枚举产生结果的 binding，固定 recorded sources、排除额外 binding 并生成 PR_WR/PR_RW；所有约束保留 observation assumption。

`solve()` 仅调用一次 MonoSAT：SAT 返回 ACCEPT，UNSAT 提取 conflict reasons 并返回 REJECT。检测器不设置 backend timeout，也不维护 deadline；需要限制实验时长时，由 runner 在进程外终止运行。旧快照组合记录、model refinement、no-good 追加和 monotone witness 后备分支已删除。

## 11. 性能归因

最终消融只保留三个非冗余配置：

```text
NO_GMWR --(GMWR + frontier)--> NO_PREPROP --(prepropagation)--> FULL
```

第一步回答 GMWR/frontier 是否通过减少 latest candidates、SAT variables/constraints、graph edges 和 solver search 实现加速；第二步回答 obligation propagation 是否继续减少 residual clauses、propagations、conflicts 与 solve time。

关键指标包括：

- `SER_PRED_FRONTIER_CANDIDATES_COUNT`；
- `SER_PRED_LATEST_WRITER_INPUT_WRITES_COUNT` / `RESULTS_COUNT`；
- `GMWR_INITIAL_CONSTRAINTS` / `RESIDUAL_CONSTRAINTS`；
- `GMWR_REMOVED_CANDIDATES` / `FORCED_FACTS`；
- `SER_GMWR_RESIDUAL_CLAUSES_COUNT` / `RESIDUAL_LITERALS_COUNT`；
- `SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT` / `CONSTRAINTS_COUNT`；
- MonoSAT propagations、conflicts、solve time、端到端时间和 peak RSS。

旧 bundle obligation count 不是保留机制的依据，该实现与对应 CLI、统计和 runner 已删除。

## 12. 关键文件

| 文件 | 作用 |
| --- | --- |
| `src/main/java/Main.java` | 两开关 CLI、默认配置和 stats 输出。 |
| `src/main/java/verifier/SERVerifier.java` | audit 生命周期、默认 `SolverSettings`、ordinary constraint 与 baseline pruning。 |
| `src/main/java/verifier/Pruning.java` | WW/RW branch-cycle reachability。 |
| `src/main/java/verifier/PrecedenceOracle.java` | 唯一 deterministic precedence relation。 |
| `src/main/java/verifier/PredicatePruning.java` | 独立逐 key 分析、GMWR 预传播、来源/区间剪枝及候选域/残余/冲突交接。 |
| `src/main/java/verifier/PredicateAnalysis.java` | 写索引、scope、行贡献缓存和共享谓词分析。 |
| `src/main/java/verifier/SERSolverAR.java` | 消费剪枝结果，进行 GMWR/EAGER、frontier、SAT/MonoSAT encoding 及单次 solve。 |
| `src/main/java/verifier/GmwrPropagationState.java` | GMWR obligation worklist 与预传播。 |
| `src/main/java/verifier/LatestVisibleChecker.java` | latest-visible candidate 公式。 |
| `tools/run_ser_acceleration_ablation.py` | 三配置 paired ablation。 |
