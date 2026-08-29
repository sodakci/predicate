# PRUN 剪枝算法

对应实现：`src/main/java/verifier/Prun.java`。谓词层 GMWR 在 `SERSolverAR.java`，与本节 WW/RW 剪枝不是同一层。

## 当前实现状态

- SER 审计剪枝模式为 `NONE`、`REACHABILITY`、`SNAPSHOT`、`PRUN`，通过 `--pruning-mode` 选择，默认是 `REACHABILITY`。
- 四种模式都直接用于实际 audit；`constraint-stat` 可以只统计剪枝前后约束而不构造 `SERSolverAR`、不运行 MonoSAT。
- `REACHABILITY` 走 `Pruning.pruneConstraints`（PolySI：某分支加边是否成环）。`SNAPSHOT` 只跑 shared-snapshot 固定点，并只物化本轮 P3/P4 碰到的 writer 对。`PRUN` 在同一套 snapshot 规则上，每轮先按 writer 对的当前可达性消解约束。
- 谓词求解模式为 `EAGER`、`GMWR`，通过 `--predicate-solving-mode` 选择，默认是 `EAGER`。剪枝在谓词 SAT 编码之前运行，与两种谓词模式正交。
- 当前 SER 全量回归为 167 项测试、0 failure、0 error、2 skipped。

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

实现用事务下标上的 `BitSet[]`，由 `IncrementalOrder` 同时维护直接边和闭包：

- `direct[u]`：当前确定的直接顺序。
- `reach[u]`：`u <* v` 的传递闭包。
- `predecessors[v]`：`reach` 的反方向，即能到达 `v` 的事务。
- `writersByKey[k]`：写过 key `k` 的事务。
- `observationsByReader[R]`：reader `R` 的全部固定 external observation。

每个有 observation 的 reader 只保存一个 shared lower bound：

```text
LB(R) = Pred(R) ∪ {S | fixedObservation(R, *, S)} ∪ Pred(S)
```

`Pred(T)` 是当前 `predecessors[T]`，即已确定的 `A <* T`。实现不保存 `UB(R)`，也不生成 `LB(R) × UB(R)` 边。

`Pred(R)` 必须进入 `LB(R)`：若竞争 writer 已经排在 reader 之前，则 `R < C` 不可能，latest-visible 只剩 `C < S`。

## 3. 算法

核心事实：同一 reader 的全部固定读共享一个事务级 snapshot 前缀。snapshot 内不能再出现比记录 source 更晚的同 key 写。

### 3.1 主循环

`PRUN`（`includeReachabilityPruning = true`）与 `SNAPSHOT`（`false`）共用下面的循环，分叉只在约束物化：

```text
loop:
  若 reach 存在自环 → 不一致，结束
  若当前是 PRUN，且至少一条 WW 约束已被 reach 唯一决定方向
      → 物化该分支，本轮不做 P3/P4，继续 loop
  按当前 predecessors 重算每个 reader 的 LB(R)
  对每个 fixedObservation(R, k, S) 与每个写过 k 的 C（C ≠ S, C ≠ R）：
      若 C <* S 或 R <* C：二选一已成立，跳过
      否则若 C ∈ LB(R)：强制 C < S          （P3）
      否则若 S <* C：强制 R < C            （P4）
  若没有新顺序：
      SNAPSHOT：物化本轮 snapshotWriterOrders 中的 WW 约束
      结束
  把新顺序写入 IncrementalOrder（更新 direct / reach / predecessors）
  SNAPSHOT：物化本轮 snapshotWriterOrders 中的 WW 约束
```

`PRUN` 不在 P3/P4 之后立刻按 snapshot writer 对物化约束。P3/P4 的新边先进入 `reach`，下一轮开头的 writer 对可达性消解再物化对应 `SERConstraint`。

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

物化时把选中分支的 WW/RW 写入 `KnownGraph`（`PR_RW` 不写回图），并把端点顺序加入 `IncrementalOrder`，然后删除该约束。

两种模式的判定条件不同：

| 模式 | 何时物化一条约束 |
| --- | --- |
| `PRUN` | 当前 `reach` 中恰好一个方向成立：`W1 <* W2` 或 `W2 <* W1` |
| `SNAPSHOT` | 本轮 P3/P4 把该 writer 对写入了 `snapshotWriterOrders` |
| 两边都未知，或两个方向同时出现 | 本轮不删约束；若 `reach` 已有自环，整次剪枝判定不一致 |

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

下表来自当前保留的 30 份 KV 历史结果文件；该文件生成于四策略脚本加入 `PRUN` 统计之前，因此只列出 NONE、POLYSI（`REACHABILITY`）和 SNAPSHOT。重新运行当前 `tools/run_pruning_constraint_comparison.py` 会同时输出 `POLYSI_SNAPSHOT`（`PRUN`）。


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



## 7. GMWR 谓词求解优化

`EAGER` 是默认谓词模式，在首次 MonoSAT 求解前显式建立全部 row-local reader-key 约束。`GMWR` 与 EAGER 并列，负责压缩这些提前建立的谓词义务；它与前述 WW/RW 选择剪枝不是同一层算法。

GMWR 不增加新的可串行化语义，也不能得出 MonoSAT 无法得出的结论。差别不在「GMWR 会推、MonoSAT 不会推」，而在：同一类单位后果，GMWR 在建 SAT 对象之前就算完，并据此停掉后续物化。它是针对谓词 item 结构的预处理器：

```text
mandatory AR 可达性
+ item 公式常量折叠
+ 公式单位传播（repair 全假 ⇒ R < B）
+ 新强制顺序回写闭包
+ 固定点迭代
```

如果已经实现了“用可达性化简 `R < B OR (B < A AND A < R)`、提取唯一剩余分支、把新顺序加回闭包并迭代到固定点”，就已经实现了 GMWR 的主要逻辑功能。Bundle、去重和 repair-set 包含消减只减少重复处理和约束物化，不是额外判定能力。MonoSAT 如何传播同一公式、以及为何大量约束不必进求解器，见 8.4、8.5。

对于谓词读事务 `R`，如果事务 `B` 写入了可能改变 `R` 查询结果的数据，则每条 item 约束形如：

```text
R < B
或存在某个 repair A ∈ Repairs：B < A < R
```

其中 `Repairs` 是集合，不是单个 writer：

- 已返回 item：repair 是记录 source；
- 未返回 item：repair 是所有对该行没有贡献的 writer。

相同 `(R, B)` 的 item 组成一个 bundle，共享 outside 分支 `R < B`。repair 集合更小的 item 会吞并更大的集合（更弱的子句不必保留）。

固定点只根据 mandatory AR 闭包做必然推导：

- 已有 `R < B`：整个 bundle 满足，删除；
- 某条 item 已有 `B < A < R`：该 item 满足；
- 没有任何 repair 能插在 `B` 与 `R` 之间：强制 `R < B`，整个 bundle 随之满足；
- 已有 `B < R` 且只剩唯一可行 repair：强制 `B < A < R`；
- 已有 `B < R` 且没有可行 repair：矛盾。

闭包只回答某个 AR 原子是否已经必真或必假。`R < B` 并不是闭包自己长出来的边，而是 item 公式在 repair 全假时的单位后果。不能提前消解的 bundle 才编码成残余 SAT clause：`R < B` 或各个可行的 `B < A < R`。

### 7.1 GMWR 补足 PolySI 的什么局限

这里的“局限”不是指 PolySI 的可达性推理不正确，也不是指 GMWR 获得了更强的判定能力。原始 PolySI 本身不支持 predicate reads；它的 generalized polygraph 约束主要表示同 key writer 的两个 WW/RW edge-set 分支。当前 SER 中的 PolySI 风格 `REACHABILITY` 剪枝也只消解已经生成的 `SERConstraint`：

```text
branch 1: W1 < W2，以及该方向激活的 RW edges
OR
branch 2: W2 < W1，以及该方向激活的 RW edges
```

它会在某一分支加入 mandatory graph 后成环时强制另一分支，并把两边都仍可行的残余选择交给 MonoSAT。这一点与 GMWR 的“先传播、再求解残余”架构相同。

PolySI 风格剪枝无法直接压缩当前谓词路径，原因在于它的输入对象和公式形状不同：

1. **谓词覆盖局限**：原始 PolySI 没有 predicate observation、bad writer 和 repair 集合的语义，不会生成 `R < B OR repairs`。
2. **约束形状局限**：`SERConstraint` 是两个 edge set 的二选一；谓词 item 可以有多个 repair，形如 `R < B OR X1 OR X2 ...`，而且多个 item 会共享同一个 outside literal `R < B`。现有二分支剪枝不知道这个 bundle 结构。
3. **物化边界局限**：PolySI 风格 WW/RW 剪枝在谓词 SAT 编码之前运行，它看不到稍后才在 `SERSolverAR` 中构造的 reader-key-bad-writer 义务。即使某些谓词子句可由同一套 mandatory 可达性立即化简，单独的 PolySI 剪枝阶段也无从访问它们。
4. **共享分支物化成本**：如果按 EAGER 把每个 key 的 item 独立展开，PolySI 已经完成的 WW/RW 剪枝不会阻止这些谓词公式创建 AR literal、repair AND、OR clause 和 JNI 调用。

GMWR 补足的就是这个“谓词专用预处理层”：

```text
PolySI-style REACHABILITY
    处理已物化的 WW/RW 二分支 SERConstraint

GMWR
    处理谓词 R < B OR repairs
    按 (reader,bad-writer) 共享 R < B
    删除重复或被包含的 repair set
    用 mandatory closure 化简并传播强制顺序
    只把残余 clause 交给 MonoSAT
```

单 repair 的 `R < B OR B < A < R` 理论上可以改写为类似 PolySI 的两个 edge-set 分支；如果再给 PolySI 预处理器增加多 repair、bundle 共享、去重、包含消减和谓词物化前固定点，它也可以实现与 GMWR 相同的功能。因此，GMWR 解决的是 PolySI 原有适用范围不覆盖谓词、现有 WW/RW 剪枝无法利用谓词公式共享结构所导致的工程扩展性问题，而不是 PolySI 的正确性缺陷。

这两层可以正交组合。PolySI 风格剪枝后仍未决的 WW/RW 分支会进入 MonoSAT；GMWR 固定点后仍未决的 `R < B OR repairs` 也会进入同一求解器。GMWR 不保证消除所有谓词约束，当前某组实验中 `residual clauses = 0` 只是该历史的实测结果。

当前实现覆盖两类输入：

- row-local 查询：按上式建立 bundle；internal key 不进 bundle，只做事务内一致性检查。
- 非 DISTINCT 的单调 `Scan/Filter/INNER JOIN`（`QueryPlan.isMonotone()`）：已记录的 external source 复用 bundle；未记录 key 仍走完整快照 refinement。候选快照多出结果时，只用该结果的实际贡献输入、减去已记录输入，形成多 key witness。

`DISTINCT`、非单调查询和不能用上述规则表达的剩余部分继续走完整快照 refinement。

## 8. GMWR 例子

先看一个抽象的三 item 例子。假设谓词读 `R` 没有看到事务 `B` 对三个 key 写入的会改变查询结果的版本，并产生：

```text
k1: R < B  OR  B < A1 < R
k2: R < B  OR  B < A2 < R
k3: R < B  OR  B < A3 < R
```

这里 `B < Ai < R` 是一个 repair term，实际上是 `(B < Ai) AND (Ai < R)`。三个 item 是合取关系：

```text
(R < B OR B < A1 < R)
AND
(R < B OR B < A2 < R)
AND
(R < B OR B < A3 < R)
```

它们共享 outside literal `R < B`，因此按 `(reader,bad-writer)=(R,B)` 组成：

```text
Bundle(R, B)
 ├─ k1: Repairs={A1}
 ├─ k2: Repairs={A2}
 └─ k3: Repairs={A3}
```

这不等价于把三个 repair 改成一个更弱的析取。如果 `R < B` 最终为假，每个尚未消解的 item 都必须分别找到成立的 repair，不是 `A1/A2/A3` 中任意一个成立就足够。

### 8.1 使用当前真实历史的 bundle

以下数据直接来自：

```text
predicateHistories/kvpredicate/test/
20_100_15_10000_0.2_uniform/hist-00000/history.prhist.jsonl
```

读事务为：

```text
R = txn 6878909
session = 0
session_seq = 53
```

`R` 的 `op_index=8` 执行谓词：

```sql
SELECT k, value FROM kv WHERE value > 10242
```

该次读没有返回下列五个 key：

```text
kv:6007  kv:4894  kv:115  kv:9325  kv:6514
```

在同一个事务中，`op_index=3` 的 `value > 2019` 读记录了这五个 key 当时的可见值：

| key | `R` 记录的可见值 | 是否满足 `value > 10242` |
| --- | ---: | --- |
| `kv:6007` | 6007 | 否 |
| `kv:4894` | 4894 | 否 |
| `kv:115` | 10237 | 否 |
| `kv:9325` | 9325 | 否 |
| `kv:6514` | 6514 | 否 |

事务 `B=6926663` 在一个事务中写入了这五个 key：

```text
B = txn 6926663
session = 7
session_seq = 1630
```

| key | `B` 写入的值 | 是否满足 `value > 10242` |
| --- | ---: | --- |
| `kv:6007` | 21392 | 是 |
| `kv:4894` | 21393 | 是 |
| `kv:115` | 21394 | 是 |
| `kv:9325` | 21395 | 是 |
| `kv:6514` | 21396 | 是 |

如果 `B` 成为 `R` 之前这些 key 的 latest-visible writer，五个 key 都应出现在 `R` 的结果中，与历史记录矛盾。因此同一个 `(R,B)` 产生五个 item 义务：

```text
Bundle(R=6878909, B=6926663)
 ├─ kv:6007
 ├─ kv:4894
 ├─ kv:115
 ├─ kv:9325
 └─ kv:6514
```

实现会在流式构建期立即消解已经能够确定的 item，所以这五个逻辑义务不一定同时作为五个物化对象驻留在 `gmwrBundles` 中。

### 8.2 repair 从哪里来

对未返回的 key，`B` 是单行执行会产生非空贡献的 bad writer；repair 候选是同一 key 上单行执行不产生贡献的 writer。repair 不是从事务号或物理时间猜出来的，而是对候选版本实际执行谓词后分类得到的。

以 `kv:115` 为例：

```text
A = txn 6877388
A writes kv:115 = 10237
A: session=7, session_seq=19

B = txn 6926663
B writes kv:115 = 21394
B: session=7, session_seq=1630
```

`10237 > 10242` 为假，所以 `A` 是 repair 候选；`21394 > 10242` 为真，所以 `B` 是 bad writer。对应 item 是：

```text
R < B  OR  B < A < R
```

代入真实事务号：

```text
6878909 < 6926663
OR
6926663 < 6877388 < 6878909
```

初始版本 `T⊥` 的值 `115` 也不产生谓词贡献，但 `T⊥` 固定在所有真实事务之前，不可能满足 `B < T⊥ < R`。

### 8.3 mandatory order 如何消解这个真实 bundle

`A=6877388` 与 `B=6926663` 属于同一 session，且 session sequence 分别是 19 和 1630，因此 mandatory session order 已经确定：

```text
A < B
```

但 `kv:115` 的 repair term 要求 `B < A < R`，其中 `B < A` 与已知 `A < B` 冲突。所以 `A` 不是 feasible repair；`T⊥` 也不可行，该 item 退化为：

```text
R < B OR false
= R < B
```

其他四个 key 上，历史中的其他非初始写值也都大于 10242，它们同样会产生谓词贡献，不是 repair。唯一不贡献的初始版本又不能放在 `B` 之后，因此这些 item 也没有 feasible repair。

固定点处理分两种情况：

1. mandatory closure 已有 `R < B`：整个 bundle 直接满足并删除。
2. `R < B` 尚未确定：任意一个“无 feasible repair”的 item 都会将它自身化简为 `R < B`，因而强制加入 `6878909 < 6926663`。该共享分支随后一次满足所有兄弟 item。

概念上的五个约束因此都退化为同一个方向：

```text
kv:6007: R < B
kv:4894: R < B
kv:115:  R < B
kv:9325: R < B
kv:6514: R < B
```

最终只需保留强制顺序：

```text
6878909 < 6926663
```

整个 bundle 标记为 resolved，不生成残余谓词 clause。

### 8.4 这些子句在 MonoSAT 里怎么传播

上述真实 bundle 能在预处理中完全消解，进不了 MonoSAT。对一个无法完全消解的一般 bundle，GMWR 只保留没有被已知顺序证真或证伪的部分。例如：

```text
k1: R < B OR B < A1 < R       // 已知 B < A1 < R，整条删除
k2: R < B OR B < A2 < R       // A2 仍可行，保留
k3: R < B OR B < A3 < R       // 已知 A3 < B，repair 删除
```

如果 `R < B` 仍未确定，`k3` 没有其他 repair，它会强制 `R < B`，进而消解整个 bundle。只有 outside 分支和至少一个 repair 都仍可行时，才编码残余：

```text
R < B OR (B < A2 AND A2 < R)
```

`gmwrOrderLiteral` 把闭包已定方向折成 `true/false`，只有未决方向才创建 MonoSAT literal。EAGER 对每个 item 都建等价公式，不经这一步过滤。

进求解器之后，**没有特殊的 bundle 传播器**。共享的 `R < B` 只是同一个 Boolean literal 出现在多条子句里。真正互相推的是三件事：子句单位传播（BCP）、方向 XOR、图无环理论。

记 `O = ar(R,B)`，即直接边 `R → B`。`B < Ai < R` 不是一条边，而是合取，先建成辅助变量：

```text
X1  ↔  ar(B,A1) ∧ ar(A1,R)
X2  ↔  ar(B,A2) ∧ ar(A2,R)

(O ∨ X1) ∧ (O ∨ X2)
```

每个被公式碰到的事务对还有 `ar(U,V) XOR ar(V,U)`，再加上 `arGraph.acyclic()`。`ar(U,V)` 是**直接边变量**，不是传递闭包变量。传递性只作用在已经建了 XOR 的那一对上：路上已有 `U ↝ V` 时，反向边会成环，无环理论把 `ar(V,U)` 推成 false，XOR 再把 `ar(U,V)` 推成 true。

GMWR 残余的 `assertOr([O, and(B<A1, A1<R)])` 就是这个形状。EAGER 的 blocking clause 写的是 `¬(B<R) ∨ ∨(A<R ∧ B<A)`，由 XOR 得到 `¬(B<R) = O`，同一组式子。

对上面两条 item：

- **某条 repair 被证伪 → 推出 `R < B`，另一条自动满足。** 例如已有 `A1 < B`，则 `X1` 为假，`O ∨ X1` 变成单位子句 `O`。`O` 一真，`O ∨ X2` 直接满足，不必再给 A2 赋值。这就是共享 outside 在 MonoSAT 里的传播：普通 watched-literal BCP。`X1` 为假也可以来自 XOR（`ar(A1,B)` 已真）或无环（路上已有 `A1 ↝ B`）。
- **`R < B` 为假 → 两条 repair 都被强制。** `O = false` 时 XOR 给出 `B < R`。两条子句都变成单位：`B → A1 → R` 且 `B → A2 → R`，与 `B → R` 共存，无环。含义是 B 对 R 可见，每个 key 都必须被夹在中间的非贡献写盖住。
- **`R < B` 为真 → repair 被无环理论禁掉。** `O` 是边 `R → B`。若再让 `X1` 为真，则 `R → B → A1 → R` 成环。子句只要至少一个，图论保证不能两个都真，合起来这条 item 是互斥二选一。`O = true` 时 k2 已被满足，不必为 A2 建新子句。
- **两条 repair 都可行，`O` 也未定。** BCP 推不动。求解器要决策：试 `O`，或试某个 `Xi`。这才是残余 SAT 还在的原因。

共享 literal 只会在 `R < B` 被赋值或某条 repair 被证伪时，把另一条 item 一起带走。它**不会**因此推出 `A1 < A2` 之类新的跨 key 顺序。

### 8.5 GMWR 与 MonoSAT 后续推断的差别

GMWR 只用当前必真的 AR 闭包（SO/WR/剪枝后的 WW/RW，加上它自己强制进去的边）去读 item 公式。规则是保守的：只有「所有满足该 item 的全序都必须含这条边」时才 `gmwrForceOrder`。它不做猜测，不学冲突子句，也不看尚未赋值的 WW 选择。

MonoSAT 在公式建完之后能力更强：

| | GMWR | MonoSAT |
|---|---|---|
| 输入 | 必真闭包 + item 公式 | 全部已物化的 clause + 图边 |
| 猜测 | 无 | CDCL 决策：试 `R<B` 或试某条 repair |
| 传播 | BitSet 可达 + 公式化简 | BCP + XOR + `acyclic()` |
| 交互 | 只在谓词 item / bundle 之间 | 谓词、WW/RW、SO/WR 全混在一张图里 |
| 学习 | 无 | 冲突分析、lemma |
| 结果 | 强制边，或留下残余 `OR` | 一个完整可满足偏序，或 UNSAT |

后续 MonoSAT 并不是在重复 GMWR。GMWR 消不掉的残余、未决 WW/RW、无环扩展，仍归它。很多历史上 `residual clauses = 0`，于是它几乎只剩「把强制边和已知序收成一个 DAG」。

EAGER 对每个 item 大致会做：两个方向的图边和 XOR、repair 的 AND 门、OR clause、JNI，同 key 全体 writer 还往往两两 `ensureComparable`。那份 `0.2_uniform` 历史上有 1336 万个 item。绝大部分在必真序下已经恒真或已经单位推出 `R<B`，但 EAGER 仍先建完再让 MonoSAT 做 BCP。贵的是建公式，不是推理更强。

GMWR 对同一个 item 先问闭包三件事（BitSet，不建 literal）：

1. 已有 `R < B`？整条（整个 bundle）扔掉。
2. 已有某个 `B < A < R`？这条 item 扔掉。
3. 所有 repair 都插不进？强制 `R < B`，兄弟 item 一起丢。

只有 1、2、3 都不成立，才 `assertOr`。那次实验里 229 万个 bundle 全部在这一步消掉，进 MonoSAT 的谓词 clause 是 0，只留下 3136 条 `assertTrue(ar(...))`。这些就是 MonoSAT 本会用单位传播得到的后果；提前拿到它们，后面的 item 会看到更大的闭包，继续被 1/3 消掉。这是固定点，不是新语义。

用 8.4 的两条 item 看何时不必进求解器：

- **Session 已有 `A1 < B`。** k1 退化成 `O`。GMWR 直接写入 `R < B`，k2 因 `O` 为真删除。MonoSAT 侧：零个子句、零个 AND、A2 的边也不建。EAGER 仍会为 k1、k2 各建一套，再靠 BCP 发现 `X1=false ⇒ O`。
- **闭包里已有 `R < B`。** 两条都不用建。
- **`A1`、`A2` 都能插在 `B` 与 `R` 之间，且 `R ? B` 未定。** 这才留给 MonoSAT。GMWR 停手；MonoSAT 开始决策、成环剪枝、和 WW 选择互相推。

三件结构差异放大了「提前单位传播」的收益：

1. **Bundle 共享 `O`。** EAGER 按 key 展开；GMWR 一个 `(R,B)` 只留一份 `O`，任一 item 强制 `O`，其余连对象都不建。
2. **流式消解。** 先处理到的 item 一旦强制新边，立刻扩大闭包，后面的 item 只查 BitSet。EAGER 必须先全部 `addEdge`/`assertOr`，求解器才能开始推。
3. **不为只是查询的序建变量。** 判断 `A1` 能否插在中间，GMWR 看三条反向可达是否存在。EAGER 往往还要为该 key 全体 writer 两两建 XOR 边。

因此 GMWR 没有替代 MonoSAT 的后续推断，只把「闭包已经决定的谓词义务」从 SAT 里拿掉。后续仍负责未决 WW/RW、残余谓词 `OR`、以及整张 AR 图无环。大量约束进不去，是因为它们对必真偏序已经是恒真或单位后果，不值得先变成图边和 clause。

### 8.6 当前约 3× 加速实际省在哪里

对上述 `20_100_15_10000_0.2_uniform/hist-00000`，已保存的五次对比结果中位数为：

| 阶段 | EAGER | GMWR | 直接变化 |
| --- | ---: | ---: | ---: |
| 完整实验 | 324.405 s | 108.837 s | 约 2.98× |
| 谓词 AR 编码 | 236.009 s | 51.553 s | 约 4.58× |
| MonoSAT solve | 28.534 s | 0.019 s | 约 1502× |
| GMWR build | — | 50.290 s | 包含在 GMWR 谓词编码内 |

完整运行减少约 215.6 秒。其中谓词编码减少约 184.5 秒，MonoSAT solve 减少约 28.5 秒。约 3× 不是同一条传递推导在 Java 里比 MonoSAT 快 3 倍，而是 8.5 所说：大量公式没有走完整物化路径。

该历史的 GMWR 规模指标为：

```text
item obligations       = 13,358,765
bundles                = 2,294,390
resolved bundles       = 2,294,390
residual bundles       = 0
residual clauses       = 0
forced orders          = 3,136
```

GMWR 仍需约 50.3 秒遍历、分类和聚合约 1336 万个 item；这 50.3 秒已包含在上面的 51.6 秒谓词编码里。`residual clauses = 0` 时，MonoSAT 几乎只消化 3136 条强制边，所以 solve 从 28.5s 降到 0.019s。如果 MonoSAT 原生接受同样的 compact bundle 并在 native 内做相同预处理，也可能获得类似收益。

### 8.7 Bundle 多 key 与 multi-key witness 不是一件事

Bundle 中的多 key 只是共享 outside literal。对：

```text
k1: O OR X1
k2: O OR X2
k3: O OR X3
```

其中 `O = R < B`，整体语义是：

```text
(O OR X1) AND (O OR X2) AND (O OR X3)
= O OR (X1 AND X2 AND X3)
```

因此 `O` 成立时可批量删除所有 item；`O` 不成立时，每个 key 仍须分别满足自己的 repair。Bundle 不会产生 `A1 < A2` 之类新的跨 key 顺序，也不能把三个 repair 改成 `O OR X1 OR X2 OR X3`。它是公共子式提取和批量消除，不是新的多 key 一致性规则。

真正的 multi-key witness 出现在 JOIN 等单调多表查询中。例如一条历史外的额外 JOIN 结果同时依赖：

```text
Visible(R, orders:o1@Wo)
AND
Visible(R, items:i1@Wi)
```

单独禁止 `Wo` 或 `Wi` 可见都会过度约束，因为单个输入版本未必产生该 JOIN 结果。正确的 no-good 只禁止当前联合组合：

```text
NOT (
    Visible(R, orders:o1@Wo)
    AND
    Visible(R, items:i1@Wi)
)
```

即：

```text
NOT Visible(R, orders:o1@Wo)
OR
NOT Visible(R, items:i1@Wi)
```

因此：

- Bundle 多 key：多个独立 item 共享 `R < B`，属于公共分支聚合。
- Multi-key witness：一个额外查询结果同时依赖多个输入 key，阻断的是它们的联合可见组合。
- 当前 KV 单表 scan/filter 历史中的五 key bundle 属于前者，不是 JOIN 型 multi-key witness。

### 8.8 EAGER/GMWR 汇总对比

| Predicate ratio | 有效配对 | EAGER 总时间中位数 | GMWR 总时间中位数 | 时间降幅中位数 | 加速中位数 | MonoSAT 降幅中位数 | 内存降幅中位数 | Bundle reduction |
| --------------- | ---- | ------------ | ----------- | ------- | ----- | ------------- | ------- | ---------------- |
| 0               | 5/5  | 0.829 s      | 0.837 s     | -0.74%  | 0.99× | 1.08%         | 0.00%   | —                |
| 0.005           | 5/5  | 10.982 s     | 5.550 s     | 49.46%  | 1.98× | 98.74%        | 38.33%  | 85.29%           |
| 0.010           | 5/5  | 17.403 s     | 6.936 s     | 58.59%  | 2.41× | 99.58%        | 38.21%  | 85.08%           |
| 0.05            | 5/5  | 91.061 s     | 25.784 s    | 71.93%  | 3.56× | 99.94%        | 12.90%  | 84.72%           |
| 0.1             | 5/5  | 148.692 s    | 51.160 s    | 66.52%  | 2.99× | 99.95%        | 26.42%  | 84.01%           |
| 0.2             | 4/5  | 305.122 s    | 107.601 s   | 64.62%  | 2.83× | 99.93%        | 23.75%  | 82.80%           |


## 9. 图边与 AR

SAT 层上，已经确定的依赖最终都是同一件事：`from <AR to`。但不能因此不建 SO/WR，也不能用一张“只有 AR 边”的图替代现在的 KnownGraph。AR 是要找的串行顺序；SO、WR、WW、RW 是如何推出这个顺序的事实。求解器里它们会塌缩成 AR literal，但生成约束、剪枝、RW 推导和谓词编码都还依赖这些事实本身。

### 9.1 哪些边在求解器里已经是 AR

`buildKnownOrder()` 已经把 pruning 后的非谓词已知边压成 mandatory AR：

```text
SO / WR / 已定 WW   →  A-side
已定 RW             →  B-side

任意 T1 -> T2  都要求  ar(T1, T2) = true
```

从 MonoSAT 的角度看，这些边类型可以丢掉：它只关心无环偏序。谓词边更极端——主路径本来就不预先把 `PR_WR` / `PR_RW` 物化进 KnownGraph，而是直接在 SAT 里写 `guard → ar(from, to)`。

因此，对**已经确定、无条件成立**的那一层，建图确实就是在建 AR。

### 9.2 为什么不能只建 AR、不建前面的边

未定选择不是无条件 AR。WW 是二选一：

```text
ar(A, C)  XOR  ar(C, A)
```

RW 是 implication，不是固定边：

```text
A --WR(x)--> B
C 写了 x
----------------
ar(A, C) → ar(B, C)
```

如果只建无条件 AR，未决的 WW/RW 会消失。只保留 SO+WR 再求一个无环全序，只要 SO+WR 无环就总能拓扑排序，那只是尊重 session 和 read-from，不是可串行化。

KnownGraph 里还有很多根本不是边的索引：`readFrom`、`writesByKey`、`predicateObservations`。它们用来枚举同 key writer、从 WR+WW 推出 RW、给 PRUN/SNAPSHOT 提供固定 observation，以及给 EAGER/GMWR 提供 frontier / repair。这些不能被一张 AR 图替代。就算 mandatory 序改成无标签 DAG，这些结构还得留着。

剪枝看的也是“谁读了谁、谁写了同一 key”，不是一堆匿名 AR。P3/P4 需要 `R` 从 `S` 读到 `k` 以及同 key 的竞争 writer `C`；REACHABILITY 需要 WW 两个分支及其附带的 RW。环证言同样依赖边类型：`SO → WR → RW` 不能全写成 `AR`。

### 9.3 对应关系

| 现在建的东西 | 进 AR 的方式 | 能否省略 |
|---|---|---|
| SO、跨事务 WR | 无条件 `ar(from,to)` | 不能省略推导，只能省略标签 |
| 已定 WW/RW | 同样无条件 `ar` | 同上 |
| 未定 WW | `xor` 两个 AR 方向 | 不能改成固定边 |
| 未定 RW | `ar(A,C) → ar(B,C)` | 不能改成固定边 |
| PR_WR / PR_RW | 带 guard 的 AR | 主路径已经不预建图边 |
| readFrom / 写索引 / 谓词 observation | 不进 AR 图 | 不能删 |

可以合并的：mandatory 那一层不必长期保留 SO/WR/WW/RW 四套图，solver 已经把它们当成同一类 AR 闭包。不能省的：SO/WR 的推导、WR 来源、同 key writer 集合，以及 WW/RW 的选择结构。

谓词路径已经是“不建图边、直接写 AR 公式”。点读路径还不能这么做，因为 WW/RW 在求解前大部分仍是未决选择，不是已知序。

### 9.4 为什么 GMWR 可以不显式建立谓词边

GMWR 不建谓词边，不是因为它比点读更强，而是因为谓词义务本来就不是一条已知方向的边。它是一组关于 AR 的析取式；`PR_WR` / `PR_RW` 只是把这个式子展开后的中间写法。

点读 WR 是历史里已经钉死的事实：`r(x,v)` 唯一对应写出 `(x,v)` 的事务，所以可以先画 `Tw →WR Tr`，再断言 `ar(Tw, Tr)`。谓词读钉不死一条这样的边。

- 结果里有的 key：记录 source `A` 必须可见，相当于 `A <AR R`。GMWR 仍然做这件事，只是写成 `gmwrForceOrder(A, R)`，不建 `PR_WR`。
- 真正的约束是：另一个会改结果的 writer `B` 不能成为 `R` 之前更晚的可见版本。这不是“必须 `R → B`”，而是：

```text
R < B
或
存在 repair A：B < A < R
```

未返回的 key 更明显：没有唯一 source，repair 是一整个集合，不可能先画一条 `PR_WR` 再画一堆 `PR_RW`。

EAGER 的 `PR_RW` 也不是已知边，而是带 guard 的 implication：

```text
A < B  →  R < B
```

加上已记录的 `A < R`，以及 `{A,B}` 必须可比，它就是：

```text
(R < B)  ∨  (B < A < R)
```

所以 GMWR 直接写这个式子，EAGER 先把它拆成 `PR_WR` / `PR_RW` 再编译回 AR。语义相同，谓词边不是额外信息。

GMWR 对每个 `(R, B)` 保留 item 公式，只用已经确定的 AR 闭包做化简：

| 闭包里已有的 | 动作 |
|---|---|
| `R < B` | 整个 bundle 已满足，丢掉 |
| 某个 `B < A < R` | 该 item 已满足 |
| 没有任何 repair 插得进 `B` 和 `R` 之间 | 强制 `R < B`（这就是本该物化的那条 `PR_RW`） |
| 已有 `B < R` 且只剩一个可行 repair | 强制 `B < A < R` |
| 两边都还可行 | 才把残余 `OR` 交给 MonoSAT |

强制出来的顺序走 `gmwrForceOrder`，进 AR 闭包和 SAT，不经过 `SEREdge(PR_WR/PR_RW)`。残余子句也是直接 `assertOr([ar(R,B), ar(B,A)∧ar(A,R), ...])`。

显式边只适合已经知道方向的依赖：`guard = true`，`from < to` 必须成立。SO、WR、剪枝后被迫的 WW/RW 都是这种。谓词 item 在消解之前方向未知，正确载体是析取，不是图边。硬要先建边，会出现：

1. 未返回 key 没有唯一 `PR_WR`，边集合画不全。
2. 每个 key 单独展开 `PR_RW`，看不到多个 item 共享同一个 `R < B`，EAGER 贵就贵在这里。
3. 大量 `guard → ar(...)` 其实是恒真或已被闭包证伪，建了也立刻被丢掉。

GMWR 能省掉谓词边，是因为 item 语义已经能写成 AR 上的命题，闭包又能判断每个原子是真、是假、还是未定。真/假就折叠，未定才建 literal。它省略的是编码中间对象，不是谓词语义。

主路径里 EAGER 也从来没把 `PR_WR` / `PR_RW` 写进 KnownGraph 当已知边；那些边只存在于编码期，随后就编译成 AR。GMWR 只是连这个中间层也不建。

## 10. 运行与复现

实际审计：

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit --pruning-mode=PRUN --predicate-solving-mode=GMWR \
  /absolute/path/to/hist-00000
```

只比较四种剪枝策略的约束规模：

```bash
./gradlew installDist
python3 tools/run_pruning_constraint_comparison.py /path/to/history/root
```

比较 EAGER 与 GMWR 的 verdict 和性能：

```bash
python3 tools/run_gmwr_comparison.py /path/to/history/root \
  --pruning-mode PRUN --repeats 5
```
