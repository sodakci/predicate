# SER 检测器当前结构

## 1. 设计边界

当前 checker 的主链是：

```text
PRHIST
  -> 内部一致性检查
  -> SO / point-WR / ordinary WW-RW candidates
  -> baseline WW reachability
  -> predicate encoding（默认 GMWR + frontier）
  -> 可选 GMWR prepropagation
  -> residual Boolean clauses + 唯一 serializationGraph
  -> MonoSAT acyclicity + query model refinement
  -> ACCEPT / REJECT / TIMEOUT
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

solver timeout 和 stats 是运维参数，不属于算法消融。

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

`createKeyFrontier()` 为普通 external key 生成 latest-visible source candidates。`analyzeAbsentKey()` 为 absent-result key 生成 bad-writer obligations。

两条路径遵循同一个开关边界：

```text
GMWR:
  possibleExternalFrontierWrites(...)
  -> LatestVisibleChecker / GMWR obligation

EAGER (--no-gmwr):
  完整 external candidates
  -> LatestVisibleChecker / eager blocking clauses
```

因此 frontier 不再是独立开关。它和 GMWR 是同一搜索域缩减机制的两个入口，由 `--[no-]gmwr` 一起控制。

## 7. EAGER 与 GMWR

### 7.1 公共部分

两条路径共用：

- recorded source 检查；
- `LatestVisibleChecker`；
- source-aware `PR_WR/PR_RW`；
- general query 的完整 `QueryPlan` model refinement；
- 唯一 `serializationGraph`；
- witness coalescing 和 physical edge interning。

两条路径必须保持 ACCEPT/REJECT 等价，差别是 row-local predicate 的公式组织和候选域。

### 7.2 EAGER

EAGER 对每个 row-local key 直接建立 latest-visible 与 bad-writer blocking clause。使用完整 external candidate 集，不运行 GMWR propagation。

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

关闭 `--gmwr-prepropagation` 后，GMWR obligation 与 frontier 仍构造，但不会在 SAT 编码前做上述消解。EAGER 不创建 GMWR propagation state，因此 `--no-gmwr` 会把 prepropagation 的实际值固定为 false。

## 9. SAT / MonoSAT 编码

`SERSolverAR` 的构造顺序为：

1. 创建 `Solver`、唯一 `serializationGraph` 和 transaction nodes；
2. 建立 write/key 索引；
3. 构造并可选传播 GMWR obligations；
4. 编码已知 typed edges；
5. 编码 residual WW choices；
6. 编码 predicate constraints；
7. 将 guarded logical dependencies 映射到 serialization edge；
8. assert `serializationGraph.acyclic()`。

`encodeDependencyEdge()` 建立的是：

```text
semantic guard -> serialization edge literal
```

不是双向等价。物理 edge 为真不会反推出某个具体 typed witness guard 为真。

## 10. General query refinement

JOIN、DISTINCT 或其他不能证明 row-local 的 query 不做逐 key GMWR 公式替代。第一次 SAT model 选出各 key frontier 后，checker 执行真实 `QueryPlan`：

- 与 recorded result 一致：接受该 model；
- 不一致：为当前 frontier 组合加入 no-good clause 并重新 solve；
- UNSAT：`REJECT`；
- backend timeout：`TIMEOUT`。

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
| `src/main/java/verifier/SERSolverAR.java` | GMWR/EAGER、frontier、SAT/MonoSAT encoding、solve/refinement。 |
| `src/main/java/verifier/GmwrPropagationState.java` | GMWR obligation worklist 与预传播。 |
| `src/main/java/verifier/LatestVisibleChecker.java` | latest-visible candidate 公式。 |
| `tools/run_ser_acceleration_ablation.py` | 三配置 paired ablation。 |
