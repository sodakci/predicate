# PRUN 剪枝算法

对应实现：`src/main/java/verifier/Prun.java`。谓词层 GMWR 在 `SERSolverAR.java`，与本节 WW/RW 剪枝不是同一层。

## 当前实现状态

- 本文记录的是已经退出生产调用链的历史 shared-snapshot 原型；`Prun.java` 暂留供第二阶段物理清理前核对。
- 当前 `audit` 和 `constraint-stat` 只允许 `--ww-pruning=NONE|REACHABILITY`，默认 `REACHABILITY`；不会调用本文算法。
- 谓词相关模式通过 `--predicate-mode=EAGER|GMWR` 选择，默认是 `EAGER`。

## 1. 输入

`Prun` 读取 SER 已经构造的：

- `KnownGraph` 中确定的 SO、WR、WW 和 RW 顺序；
- `readFrom` 上的点读 WR（构造图时已跳过同事务读）；
- external predicate observation 中、source 与 reader 不是同一事务的 tuple writer；
- 按 key 分组的全部 writer transaction；
- `SERConstraint` 表示的 WW/RW 二选一约束。

对于 reader `R` 在 key `k` 上固定读到 source writer `S`，记为：

```text
fixedObservation(R, k, S)
```

同一 `(R, k)` 若出现两个不同 source，该 observation 丢弃。`PredicateReadType.INTERNAL` 的谓词 key 不进入 observation。

对同样写过 `k` 的竞争 writer `C`，latest-visible 语义给出：

```text
C < S  OR  R < C
```

竞争 writer 要么位于固定 source 之前，要么位于 reader 之后。

## 2. 数据结构

实现调用由 `SERVerifier`创建并注入的 `PrecedenceOracle<Transaction>`；BitSet闭包只封装在该primitive内，并与REACHABILITY、GMWR和solver known-order共享同一实例：

- `before(u,v)`：当前是否已有 `u <* v`。
- `successor(u)` / `predecessor(v)`：传递后继与传递前驱。
- `wouldCycle(u,v)`：加入 `u -> v` 是否闭环。
- `writersByKey[k]`：写过 key `k` 的事务。
- `observationsByReader[R]`：reader `R` 的全部固定 external observation。

每个有 observation 的 reader 只保存一个 shared lower bound：

```text
LB(R) = Pred(R) ∪ {S | fixedObservation(R, *, S)} ∪ Pred(S)
```

`Pred(T)` 来自 `PrecedenceOracle.predecessor(T)`，即已确定的 `A <* T`。实现不保存 `UB(R)`，也不生成 `LB(R) × UB(R)` 边。

`PrecedenceOracle.add(from,to)` 使用 `Pred(from) ∪ {from}` 和 `Succ(to) ∪ {to}` 的直积增量更新统一闭包。因此 P3/P4 新边和已物化约束的 WW/RW 边会立即对后续推理可见，无需每轮重算。

oracle只保存deterministic order facts。尚未选择的residual WW方向、predicate frontier selection和MonoSAT order decision literal不进入oracle。

`Pred(R)` 必须进入 `LB(R)`：若竞争 writer 已经排在 reader 之前，则 `R < C` 不可能，latest-visible 只剩 `C < S`。

## 3. 算法

核心事实：同一 reader 的全部固定读共享一个事务级 snapshot 前缀。snapshot 内不能再出现比记录 source 更晚的同 key 写。

### 3.1 主循环

`PRUN`（`includeReachabilityPruning = true`）与 `SNAPSHOT`（`false`）共用下面的循环，分叉只在约束物化：

```text
loop:
  若新增顺序 wouldCycle → 不一致，结束
  若当前是 PRUN，且至少一条 WW 约束已被 before 唯一决定方向
      → 物化该分支，本轮不做 P3/P4，继续 loop
  按当前 predecessor() 重算每个 reader 的 LB(R)
  对每个 fixedObservation(R, k, S) 与每个写过 k 的 C（C ≠ S, C ≠ R）：
      若 C <* S 或 R <* C：二选一已成立，跳过
      否则若 C ∈ LB(R)：强制 C < S          （P3）
      否则若 S <* C：强制 R < C            （P4）
  若没有新顺序：
      SNAPSHOT：物化本轮 snapshotWriterOrders 中的 WW 约束
      结束
  把新顺序写入 PrecedenceOracle
  SNAPSHOT：物化本轮 snapshotWriterOrders 中的 WW 约束
```

`PRUN` 不在 P3/P4 之后立刻按 snapshot writer 对物化约束。P3/P4 的新边先进入 oracle，下一轮开头的 writer 对可达性消解再物化对应 `SERConstraint`。

### 3.2 P3：snapshot 内的竞争 writer 必须在 source 之前

若 `C ∈ LB(R)` 且 `C` 写过 `k`，则 `C` 与 `S` 都在 `R` 的 snapshot 前缀里。若 `S < C`，`C` 会成为更晚且可见的 `k` writer，`R` 不可能仍从 `S` 读取。因此只能有 `C < S`。

`C` 进入 `LB(R)` 的来源可以是：

- `C <* R`（含 SO 等已有顺序）；
- `R` 在另一个 key `k'` 上读到 `S'`，且 `C = S'` 或 `C <* S'`；
- `C` 已经是当前 source 的前驱（这种情况会被「`C <* S` 则跳过」挡住，不会再加边）。

当强制边 `C < S` 不能只由当前 key 的 `Pred(S) ∪ {S}` 得到，且存在另一个 key 的 observation 作为可见性见证时，计入 `crossKeyForcedOrders`。

### 3.3 P4：source 之后的 writer 必须在 reader 之后

若已有 `S <* C`，则 `C < S` 不可能，latest-visible 只剩 `R < C`。

SNAPSHOT 物化约束时记录的是 writer 对 `(S, C)`，不是 `(R, C)`。选中 `S < C` 的那一分支时，分支里的 `R ->RW C` 会一并写入 `KnownGraph`。

### 3.4 固定点

P3/P4 的新边加入闭包后，其他 reader 的 `LB` 可能变大，从而推出更多顺序。`propagationRounds` 只统计真正产生了 P3/P4 新边的轮次；纯约束消解轮次只增加内部 pass 计数。第二轮及以后新出现的直接顺序计入 `crossSnapshotDerivedOrders`。

## 4. WW/RW 约束物化

对两个同 key writer `W1`、`W2`，`SERConstraint` 有两个分支：

```text
branch 1: W1 < W2
branch 2: W2 < W1
```

分支中除 WW 边外，还可以包含由固定读取产生的 RW 边。例如 `R` 从 `W1` 读取，选择 `W1 < W2` 时同时需要：

```text
W1 ->WW W2
R  ->RW W2
```

物化时把选中分支的 WW/RW 写入 `KnownGraph`（`PR_RW` 不写回图），并把端点顺序加入 `PrecedenceOracle`，然后删除该约束。

两种模式的判定条件不同：

| 模式 | 何时物化一条约束 |
| --- | --- |
| `PRUN` | `before(W1,W2)` 与 `before(W2,W1)` 中恰好一个成立 |
| `SNAPSHOT` | 本轮 P3/P4 把该 writer 对写入了 `snapshotWriterOrders` |
| 两边都未知 | 本轮不删约束；新增关系若 `wouldCycle`，整次剪枝判定不一致 |

`PRUN` 这里看的是 writer 对是否已经有确定顺序，不是 `Pruning.java` 的「某一分支加边是否成环」。竞争 writer 已经位于 reader 之前、因而 RW 方向会成环的情况，由 `Pred(R) ⊆ LB(R)` 加 P3 推出 `C < S`，再在下一轮被 writer 对可达性消解。

由 snapshot 规则产生的顺序可以不在初始 `TC(E)` 里；它们进入闭包后继续消解约束或扩大其他 reader 的 `LB`。

## 5. 跨 key 例子

```text
T1: WRITE x=1
T2: WRITE x=2, WRITE y=2
R:  READ  x=1, READ  y=2
```

固定 observation：`R` 从 `T1` 读 `x`，从 `T2` 读 `y`。

```text
LB(R) ⊇ {T1, T2}
T2 写过 x，且 T2 ∈ LB(R)，T2 ≠ T1
⇒ P3 强制 T2 < T1
```

同 key writer 的 WW 选择因此被唯一决定，对应 `SERConstraint` 被物化。`SNAPSHOT` 与 `PRUN` 都会推出这条边；这就是 `crossKeyForcedOrders`。

若同一 session 里已经有 `S < C`，而 `R` 仍从 `S` 读取，则 P3 不会触发（`C` 不在 `LB(R)` 里），P4 强制 `R < C`。`PRUN` 随后按 `S <* C` 物化含 `R ->RW C` 的那一分支；`SNAPSHOT` 靠 `snapshotWriterOrders` 里的 `(S, C)` 做同样的物化。

仅有 session 顺序 `C < S`、且没有跨 key 见证时，SNAPSHOT 不会消解该 WW 约束；PRUN 会因为 `C <* S` 已在 `reach` 中而消解。这是两种模式的差异，不是 snapshot 规则的一部分。

## 6. 对比（初始约束相同，看剪枝后剩余）

下表是原型阶段保留的历史结果，不代表当前 runner 配置。当前 `tools/run_pruning_constraint_comparison.py` 只输出 `NONE` 与 `REACHABILITY`。


| 策略       | 历史数 | 原始 Constraints | 剩余 Constraints | Constraints 剪枝率 | 原始 Implications | 剩余 Implications | Implications 剪枝率 |
| -------- | --- | -------------- | -------------- | --------------- | --------------- | --------------- | ---------------- |
| NONE     | 30  | 357,323        | 357,323        | 0%              | 2,037,445       | 2,037,445       | 0%               |
| POLYSI   | 30  | 357,323        | 6,704          | **98.12%**      | 2,037,445       | 13,857          | **99.32%**       |
| POLYSI +SNAPSHOT | 30  | 357,323        | 3,590          | **99.00%**      | 2,037,445       | 7,464           | **99.63%**       |



| Predicate ratio | POLYSI Constraints 剪枝率 | SNAPSHOT+POLYSI  Constraints 剪枝率 | POLYSI Implications 剪枝率 | POLYSI +SNAPSHOT Implications 剪枝率 |
| --------------- | ---------------------- | ------------------------ | ----------------------- | ------------------------- |
| 0               | 94.60%                 | 94.60%                   | 98.03%                  | 98.03%                    |
| 0.005           | 99.02%                 | **99.94%**               | 99.64%                  | **99.98%**                |
| 0.01            | 99.08%                 | **99.98%**               | 99.67%                  | **99.99%**                |
| 0.05            | 98.99%                 | **100.00%**              | 99.63%                  | **100.00%**               |
| 0.1             | 98.89%                 | **99.998%**              | 99.60%                  | **99.999%**               |
| 0.2             | 98.48%                 | **100.00%**              | 99.45%                  | **100.00%**               |



## 7. GMWR 当前实现

`GMWR` 通过 `--predicate-mode=GMWR` 启用。当前实现位于
`src/main/java/verifier/SERSolverAR.java`，只包含以下四条谓词剪枝规则。

### 7.1 规则一：PR_WR source 可达性剪枝

对候选谓词读依赖 `PR_WR(A,R,k)`，如果 mandatory dependency closure
已经存在 `R ->* A`，激活 `A -> R` 会形成环，因此删除该 source
alternative。

如果一个 key 剪枝后只剩一个合法 source，求解器固定该 source，并将对应
`PR_WR` 作为已知谓词依赖加入后续编码。

对应指标：

- `SER_PRED_PR_WR_SOURCE_ALTERNATIVES_COUNT`
- `SER_PRED_PR_WR_REACHABILITY_PRUNED_COUNT`
- `SER_PRED_PR_WR_REACHABILITY_FORCED_COUNT`

### 7.2 规则二：固定 WW 导致的 PR_RW 环剪枝

设 `A` 是候选 source，`B` 是同 key 的后续 writer。仅当下面三个条件
同时成立时删除 `PR_WR(A,R,k)` source alternative：

1. `WW(A,B,k)` 已经确定；
2. `B` 相对 `A` 会改变谓词结果；
3. mandatory dependency closure 已经存在 `B ->* R`。

选择 `A` 后会产生 `PR_RW(R,B,k)`，与已有 `B ->* R` 构成环。该规则
只读取已经确定的 WW，不推断或固定未决 WW choice。

对应指标：`SER_PRED_PR_WR_PR_RW_CYCLE_PRUNED_COUNT`。

### 7.3 规则三：无 recorded source 的 frontier 区间剪枝

对没有 recorded source 的 external key，只保留仍可能成为 reader 的
AR-max frontier 的 writer：

- 已知位于 reader 之后的 writer 被删除；
- 已知位于 reader 之前、但又被另一个已知可见的同 key writer 支配的 writer
  被删除；
- 已有真实 writer 确定在 reader 之前时，bottom 初始版本被删除；
- 与 reader 或其他 writer 的顺序仍未确定时保留候选。

该规则依据 fixed WW 和已确定 typed dependency/order 工作，不猜测未决 WW
方向。被删除的 writer 不再产生 frontier candidate 或相应的谓词义务。

对应指标：`SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT`。

### 7.4 规则四：物理谓词依赖边合并

谓词编码先保留逐 key 的 typed witness。多个 witness 如果具有相同的
`(from,to,type)`，其中 `type` 为 `PR_WR` 或 `PR_RW`，则在
唯一的 MonoSAT `serializationGraph` 中只物化一条端点物理边；该边的 guard 是所有 witness
guard 的逻辑 OR，witness key 集合仍保留用于语义和诊断。

对应指标：

- `SER_PRED_DEPENDENCY_CANDIDATES_COUNT`
- `SER_PRED_DEPENDENCY_PHYSICAL_EDGES_COUNT`
- `SER_PRED_DEPENDENCY_COALESCED_COUNT`
- `SER_PRED_DEPENDENCY_PHYSICAL_PR_WR_EDGES_COUNT`
- `SER_PRED_DEPENDENCY_PHYSICAL_PR_RW_EDGES_COUNT`

### 7.5 与 PolySI REACHABILITY 的关系

两者处理不同对象，可以正交组合：

- `REACHABILITY` 剪枝处理已经生成的 WW/RW 二分支 `SERConstraint`；
- `GMWR` 的四条规则处理谓词 source、frontier 和物理谓词依赖边。

因此评估 GMWR 时应固定相同的 `--ww-pruning` 比较 `EAGER` 与
`GMWR`。不能把 `GMWR + NONE` 和 `EAGER + REACHABILITY` 的时间差
全部归因于 GMWR。

## 8. 运行与复现

实际审计：

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit --predicate-mode=GMWR \
  --ser-propagation-mode=ww-gmwr \
  --gmwr-prepropagation \
  /absolute/path/to/hist-00000
```

比较 WW reachability 开启与关闭时的约束规模：

```bash
./gradlew installDist
python3 tools/run_pruning_constraint_comparison.py /path/to/history/root
```

比较 EAGER 与 GMWR 的 verdict 和性能：

```bash
python3 tools/run_gmwr_comparison.py /path/to/history/root \
  --ww-pruning REACHABILITY --repeats 5
```
