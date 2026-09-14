# SI 检测器代码脉络索引

本文索引 `SI/si-result-detector` 当前工作树中的实际调用链和数据结构。它与 `SER_CODE_MAP.md` 使用相同审计口径，但保留 SI 的核心判定：

```text
A = SO ∪ WR ∪ WW ∪ PR_WR
B = RW ∪ PR_RW
InducedSI = A ∪ (A ∘ B)
verdict = InducedSI 是否存在无环的 WW / predicate-source assignment
```

审计日期：2026-09-13。

## 1. 可执行入口与最终结果

文件：`src/main/java/Main.java`

| 入口 | 调用链 | 输出 |
| --- | --- | --- |
| `Main.main()` | Picocli -> `Audit.call()` | 进程退出码与 marker。 |
| `audit` | loader -> `SIVerifier.auditResult()` | `ACCEPT=0`、`REJECT=-1`、`TIMEOUT=124`。 |

输入固定为 PRHIST，backend 固定为 MonoSAT。主入口不再保留独立的
`constraint-stat`、`stat`、`dump` 加载/遍历路径。

`SIVerifier.audit()` 是兼容旧调用方的 boolean 包装：只有 `ACCEPT` 返回 true；CLI 和需要区分超时的调用方使用 `auditResult()`。

## 2. 输入、解析与初始版本

| 文件 | 结构 / 函数 | 作用 |
| --- | --- | --- |
| `history/loaders/PredicateHistoryLoader.java` | `loadHistory()` | 读取 `initial_state.json` 与 `history.prhist.jsonl`。 |
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
| `predicateSnapshotMatches()` | general query 的 covered keys 全由当前 transaction 本地写决定时，立即执行完整 JOIN/DISTINCT snapshot 校验。 |
| `predicateMatchesRow()` | row-local 查询的单行贡献检查。 |

门禁失败直接得到 `REJECT`，不会进入图构造、剪枝或 SAT。

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
| PR_WR | `SISolverInduced` 的 recorded source / frontier selection | A |
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

## 6. Pruning 代码索引

### 6.1 NONE / REACHABILITY

文件：`src/main/java/verifier/Pruning.java`

`REACHABILITY` 的当前主路径：

```text
KnownGraph A/B
  -> SIVerifier.InducedGraph.Oracle
  -> BitSet directA/directB
  -> 对 branch 克隆两组 rows 并加入 WW/RW
  -> 计算 A ∪ (A ∘ B)
  -> Kahn 拓扑检查
  -> 一侧非法则提交另一侧并增量更新 oracle
```

旧实现为每个 branch 重建 `History -> KnownGraph -> Guava graph -> MatrixGraph`；当前 oracle 只复制紧凑 `BitSet[]`。它不是 SER 的 A+B reachability oracle：SI 必须显式保持 A/B 分区并检查 `A∘B`。

`NONE` 只跳过上述调用；`Pruning` 本身不再使用进程级静态 enable
开关。旧 `SNAPSHOT/PRUN` 分派与 `Prun.java` 已删除。

## 7. SISolverInduced 编码顺序

文件：`src/main/java/verifier/SISolverInduced.java`

构造期顺序：

1. `createNodes()`：为真实 transaction 创建 `depGraph` 与 `inducedGraph` 节点；bottom 不进入。
2. `encodeKnownEdges()`：登记 fixed A/B typed dependency。
3. `encodeWwChoices()`：每个 residual `SIConstraint` 创建一个方向 literal，并登记 per-key `wwOrder`。
4. `encodeRwFromWrAndWw()`：由 fixed WR 与 WW guard 生成 ordinary RW/B。
5. `encodePredicateConstraints()`：row-local EAGER 或 general frontier/refinement 准备。
6. `encodePredicateDependencies()`：可选 predicate witness coalescing，再物化 A/B 与组合边。
7. `sealInternedGraphEdges()`：对物理 edge 建立完整 support 等价。
8. `inducedGraph.acyclic()`：断言唯一 verdict graph 无环。

主要状态：

| 字段 | 作用 |
| --- | --- |
| `depGraph` | A 的 MonoSAT 图；其 `reaches()` literal决定 external predicate source 可见性。 |
| `inducedGraph` | A direct edge 与 A∘B composed edge；只对它断言 `acyclic()`。 |
| `dependencyEdgesA/B` | 带 guard 的 typed logical dependency，供组合生成。 |
| `wwOrder` | `(writerFrom,writerTo,key)->guard`。 |
| `predicateDependencyCandidates` | 尚未按 `(from,to,type)` 合并的 PR_WR/PR_RW witness。 |
| `physicalDepEdges` / `physicalInducedEdges` | 每张 MonoSAT 图各自按 endpoint pair 复用的 native edge。 |
| `physical*EdgeGuards` | 每条复用 edge 的全部 semantic support guards。 |
| `predicateChecks` | general query 的模型级 snapshot 校验与 no-good refinement。 |

## 8. A/B 到 InducedSI 的实际编码

统一入口是 `addDependencyEdge()` / `materializeDependencyEdge()`：

```text
A edge x -> u, guardA:
    depGraph(x,u)
    inducedGraph(x,u)
    与已有 B(u,y) 组合 inducedGraph(x,y)

B edge u -> y, guardB:
    不直接加入 verdict graph
    与每个已有 A(x,u) 组合 inducedGraph(x,y)

composition guard = guardA AND guardB
```

真实自环或指向 bottom 的 edge 通过 `NOT guard` 禁止；从 bottom 出发的 dependency 不创建真实 graph node/edge。

### 8.1 Predicate witness coalescing

默认 `--predicate-witness-coalescing`：相同 `(from,to,type)` 的多个 PR witness 合为一个 `SIEdge(keys)`，guard 为各 witness guard 的 OR。关闭后每个 witness 单独物化。

### 8.2 Graph-edge interning

默认 `--graph-edge-interning`：在 `depGraph` 和 `inducedGraph` 内分别按 `(from,to)` 复用 native MonoSAT edge。

SI 不能照搬 SER 的单向绑定，因为 `depGraph.reaches()`直接参与 predicate visibility。当前 SI 对复用 edge 建立：

```text
each semantic guard -> physical edge
physical edge -> OR(all semantic guards)
```

因此：

```text
physical edge <-> OR(all semantic supports)
```

关闭 interning 时，每条 logical/combined edge 独立创建 native edge，并保持 `edge <-> guard`。

## 9. Predicate 编码

### 9.1 Row-local EAGER

`encodeRowLocalPredicateEager()`：

- 校验 recorded inputs/values；
- INTERNAL key 使用谓词事件前最后一个 self write；
- recorded EXTERNAL source 固定 PR_WR，并为 later result-changing writer 产生 guarded PR_RW；
- absent key 对每个 bad writer 编码 `NOT visible(bad) OR later-visible-good`；
- ABSENT bottom write (`value=null`) 直接视为无 predicate contribution，不送入 value adapter。

### 9.2 General query

`createKeyFrontier()` 为每个 external key 生成 latest-visible candidates。可见性使用：

```text
visible(writer, reader) = depGraph.reaches(writer, reader)
```

选择 guard：

```text
Select(s) = Visible(s)
            AND NOT EXISTS u: Visible(u) AND WW(s,u,key)
```

每个候选 source 的 PR_WR/PR_RW 在首次 solve 前已 guarded 编码。`refinePredicateConstraints()` 从 SAT model 组成实际 snapshot，执行完整 `QueryPlan`；不匹配则加入所选 frontier 组合的 no-good 并重求解。

## 10. Solve、timeout 与冲突提取

| 函数 | 作用 |
| --- | --- |
| `solveStatus()` | 返回 `SAT/UNSAT/TIMEOUT`；general predicate refinement 共用一个总 deadline。 |
| `solveOnce()` | 有 deadline 时调用 `Solver.setTimeLimit()` + `solveLimited()`；秒数向上取整，避免 1 秒配置立即变为 0。 |
| `solve()` | 旧 boolean API；只有 SAT 返回 true。 |
| `extractConflicts()` | UNSAT 时贪心缩减 residual WW constraints；TIMEOUT 不进入该路径。 |
| `conflictExtractionSettings()` | 复用当前压缩配置，诊断重解不继承主求解 deadline。 |

timeout 从 `solveStatus()` 开始计时，不覆盖 parse、consistency、pruning 与 encoding。CLI 输出 `[[[[ TIMEOUT ]]]]` 并返回 124。

## 11. MonoSAT 路径

| 层 | 文件 / API | 作用 |
| --- | --- | --- |
| Java | `monosat.Graph.addEdge()` | 创建由 SAT literal 控制的 theory edge。 |
| Java | `Graph.reaches()` | A 图可达性 literal，供 predicate visibility。 |
| Java | `Graph.acyclic()` | directed acyclicity literal；SI 只 assert `inducedGraph.acyclic()`。 |
| JNI/C++ | `Monosat.cpp`、`GraphTheory.h` | 将 edge/reachability/acyclicity 注册进 graph theory solver。 |
| cycle theory | `CycleDetector.cpp` | true-edge under graph 成环时向 CDCL 返回 conflict reason。 |

## 12. CLI 配置

| CLI | 默认 | 消费位置 |
| --- | --- | --- |
| `--solver-timeout-seconds` | 600 | solve/refinement 总 backend deadline；0 禁用。 |
| `--solver-stats` | false | 输出详细 predicate/physical edge/CNF counts。 |

隐藏实验参数为 `--ww-pruning NONE|REACHABILITY`、
`--[no-]predicate-witness-coalescing` 和 `--[no-]graph-edge-interning`。
当前谓词编码固定为 EAGER/general refinement；SI GMWR 接入前不公开
`--predicate-encoding`。

## 13. Statistics / timing

主要阶段：

```text
SI_VERIFY_INT
SI_GEN_PREC_GRAPH
SI_GEN_CONSTRAINTS
WW_BASELINE_PRUNE_MS
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
  SI_GRAPH_PREDICATE_REFINEMENT
  SI_GRAPH_CONFLICT_EXTRACTION
```

counts 包含 `WW_INITIAL_CONSTRAINTS/WW_AFTER_BASELINE`、
`WW_INITIAL_IMPLICATIONS/WW_AFTER_BASELINE_IMPLICATIONS`、SAT
variables/clauses、predicate candidates/physical/coalesced edges、dep/induced
physical edge 数、frontier、cache、blocking clause 等。

## 14. 与 SER 的明确边界

本次从 SER 同步的是通用输入修复、超时三态、predicate witness coalescing、graph-edge interning、统计和 BitSet pruning 思路；没有同步 GMWR/AR 总序传播。

原因不是版本遗漏，而是语义边界：

- SER 的 auxiliary total order 与 `A+B` 无环不能替代 SI 的 `A ∪ (A∘B)`；
- SI 的 B edge 不能直接折叠到 A/reachability；
- SER GMWR obligation 和 WW feedback 建立在 serial-order 关系上，直接移植会拒绝 SI 允许的 write skew；
- SI graph interning 需要 support 反向约束，因为 A reachability 本身是 predicate visibility 的语义输入。
