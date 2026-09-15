# SER 项目概览：逻辑依赖与串行化约束图

本文档说明当前代码如何保留 Adya typed logical dependency，并将其投影到唯一的 MonoSAT serialization constraint graph：

1. `SO/WR/WW/RW/PR_WR/PR_RW` 的 type/key/guard 元数据在哪一层保留。
2. 逻辑依赖如何转换成 serialization order 约束。
3. MonoSAT 为何只需要一张 endpoint-only `serializationGraph`。

日常构建和运行命令见 `SER/README.md`；WW/RW pruning 使用默认的 reachability 检查。

## 1. 先给出结论

当前实现有三层对象，必须分开理解：

| 层次 | 对象 | 是否保留 type/key | 合并规则 |
| --- | --- | --- | --- |
| 语义 witness 层 | `SEREdge(from,to,type,key)` | 保留 | 完全相同的 witness/guard 去重 |
| 谓词物理边层 | 合并后的 predicate `SEREdge` | 保留 type，并保留全部 keys | 相同 `(from,to,type)` 合并，guard 取 OR |
| MonoSAT native 图层 | `serializationGraph` 中的 theory edge | 不携带 type/key | 默认相同 `(from,to)` 共用一条 serialization edge |

所以，用户给出的两个例子对应两个不同层次：

```text
(a,b,PR_WR,k1) + (a,b,PR_WR,k2)
```

在谓词物理边层会合并为：

```text
(a,b,PR_WR,{k1,k2})
guard = guard(k1) OR guard(k2)
```

这是“相同类型、相同方向、不同 key”的平行 witness 合并。

而：

```text
(a,b,PR_WR,k1) + (a,b,WR,k1)
```

不会在语义层或谓词合并层被改写成同一种依赖。Java 层仍分别保留 `PR_WR` 和 `WR`；它们只在投影到 `serializationGraph` 时可共用同一条 `(a,b)` native edge，因为无环性只取决于端点和方向。生产 G2/E2 均默认启用该内部实现，旧 `--[no-]graph-edge-interning` 仅作为隐藏实验兼容参数。

因此准确答案不是二选一，而是：

- 谓词 witness coalescing 做的是第一个例子。
- MonoSAT graph-edge interning 在更低一层也会让第二个例子的两条语义边共用同一条 native edge。
- 第二种情况不是把 `PR_WR` 的语义“变成”`WR`，也不是删除谓词读约束；只是把相同方向的图论邻接关系复用。
- 上述两层物理合并不会删除一条能带来新端点方向或新可达关系的 Adya 边。被复用的是对判环没有额外作用的同向平行边。

## 2. 项目判定目标

SER detector 读取一段 PRHIST 事务历史，判断是否存在一个能够解释全部点读、写和谓词读的串行执行。输出为：

```text
SER audit result: ACCEPT
SER audit result: REJECT
SER audit result: TIMEOUT
```

普通 `audit` 按阶段流式输出精简的 History、WW、可选 GMWR、Predicate、SAT 和 Timing 摘要，隐藏逐轮 pruning、progress bar、bridge epoch 与原始 counter。EAGER 省略 GMWR section 和 WW 第三项。`--solver-stats` 在摘要后输出完整 profiler metrics/counters、求解配置及兼容旧 runner 的 `[[[[ ... ]]]]` 标记；`SER audit result: ...` 始终是最后一行。

当前实现使用定理 33 对应的 Adya 风格依赖关系：

```text
D = SO ∪ WR ∪ WW ∪ RW ∪ PR_WR ∪ PR_RW
```

对某个 SAT 模型，所有被 guard 激活的关系组成依赖图 `D`。合法结果要求：

```text
acyclic(D)
```

其中代码中的 `PR_WR`、`PR_RW` 分别对应论文记号 `Pred-WR`、`Pred-RW`。

### 2.1 六类边

设 `A/B/C` 为事务，`k` 为 key：

| 类型 | 方向 | 当前实现中的含义 |
| --- | --- | --- |
| `SO(A,B)` | `A -> B` | 同一 session 中 `A` 先于 `B` |
| `WR(A,B,k)` | `A -> B` | `B` 的点读读取了 `A` 写出的 `k` 版本 |
| `WW(A,C,k)` | `A -> C` | 对同一 key 的两个 writer，选择 `A` 的版本先于 `C` |
| `RW(B,C,k)` | `B -> C` | `B` 读自 `A`，且 `A ->WW C`，因此 `C` 必须位于 `B` 后面 |
| `PR_WR(A,B,k)` | `A -> B` | `A` 是谓词读 `B` 在 `k` 上的 latest-visible source |
| `PR_RW(B,C,k)` | `B -> C` | `C` 位于谓词 source 之后，且 `C` 会改变该谓词 observation |

逻辑层不是只有 `AR` 边，也不是只检查 `knownGraphA`。`knownGraphA` 与 `knownGraphB` 只是构建期分区：

```text
knownGraphA: SO, WR, WW, PR_WR
knownGraphB: RW, PR_RW

logicalDependencies = active(knownGraphA ∪ knownGraphB ∪ 动态依赖)
```

## 3. 当前代码中的唯一 MonoSAT 图

`SERSolverAR` 只建立一张物理图：

```text
serializationGraph    endpoint-only serialization constraints
```

最终公式只断言：

```text
serializationGraph.acyclic()
```

每条激活的逻辑依赖都满足：

```text
guard(edge) -> serialization(edge.from, edge.to)
```

`WR/WW/RW/PR_WR/PR_RW` 的 type/key/guard 留在 logical dependency layer，用于 explanation、debugging 和 paper description；MonoSAT 只维护 `serializationGraph`。

```text
History
  -> Logical Dependency Layer
  -> Serialization Constraint Graph
  -> MonoSAT acyclic
```

初始状态事务 `T⊥` 不建立 MonoSAT 节点。顺序比较直接返回常量：

```text
T⊥ < T    = true
T < T⊥    = false
```

因此 bottom 写仍参与版本来源和 frontier 语义，但不会向真实事务图增加一个普通节点。

## 4. “OR 序列”到底指什么

当前代码中没有名为 `OR` 的第三种 Adya 依赖关系。这个说法可能指两种对象，影响也不同。

### 4.1 如果 OR 指 order/AR 顺序

本文统一称为 serialization order。它会影响哪些条件依赖被激活，并承载所有激活依赖的端点方向。

它的影响包括：

1. 决定同 key writer 的 `WW` 方向。
2. WW decision 分支同时激活该方向的 `WW` 和由固定 `WR` 产生的普通 `RW`。
3. 决定谓词读之前哪些 writer 可见，以及哪个 writer 是 AR-max frontier。
4. 决定对应 `PR_WR/PR_RW` guard 是否成立。
5. 要求每条已经激活的 typed dependency 与同一个 order 方向一致。

它不做的事情是：

- 不因为两个原本无依赖的事务在某个拓扑序中有先后，就凭空生成 Adya 边。
- 不预先构造完整的 `n(n-1)` 全序边集。

`orderLiteral(A,B)` 只在公式确实需要比较 `A/B` 时调用。对尚未由已知闭包确定方向的事务对，`ensureComparable` 加入：

```text
order(A,B) XOR order(B,A)
```

再由 `serializationGraph.acyclic()` 保证这些局部选择能够共同扩展成严格全序。已知依赖先计算闭包；已知顺序只物化传递约简边，闭包中已经确定的比较直接返回 `true/false`。

结论是：逻辑层保留 typed dependency 语义，物理层只检查其 serialization endpoint 约束。

### 4.2 如果 OR 指逻辑 OR 子句

逻辑 OR 主要出现在两处：

- 多个谓词 witness 合并时，物理边 guard 为所有 witness guard 的 OR。
- GMWR/EAGER 的结果合法性约束中，用 OR 表达“bad writer 在 reader 后，或者存在一个更晚的 good writer 覆盖它”等替代方案。

OR 子句会影响哪个候选模型、哪个 guarded dependency 能被激活，但不会修改依赖类型。例如 `PR_WR` 不会因为与 `WR` 共用端点就被重新标成 `WR`。

## 5. 求解核心：分阶段 MonoSAT/typeedge 编码与 Adya 图构建

当前主流程是：

```text
PRHIST
  -> PredicateHistoryLoader
  -> History
  -> verifyInternalConsistency
  -> KnownGraph
  -> generateConstraintsSER
  -> WW reachability（默认；实验可设 NONE）
  -> SERSolverAR
       SETUP
       KNOWN_EDGES
       WW
       RW
       PREDICATE
       DEPENDENCIES
       TOTAL_ORDER
  -> solve / predicate refinement
  -> ACCEPT、REJECT 或 TIMEOUT
```

### 5.1 `KnownGraph`：收集确定事实

`KnownGraph` 在加载后完成以下工作：

- 为历史中的事务建立节点。
- 对每个 session 只加入相邻事务的 `SO`；传递关系由图可达性表达。
- 根据点读的 `(key,value)` 找到唯一 source write，建立 `WR`。
- 建立 `readFrom`、全部 write、按 `(key,value)` 的 source 索引。
- 保存谓词 observation，并按 key 区分 `EXTERNAL` 与 `INTERNAL`。

`readFrom` 单独保留点读来源，是因为后续普通 `RW` 推导必须知道 reader 具体读自哪个 writer。

### 5.2 `SERConstraint`：生成 WW 二选一和普通 RW implication

对于写过同一 key 的事务 `A/C`，必须选择一个 WW 方向：

```text
branch 1: A ->WW C
branch 2: C ->WW A
```

如果 `B` 从 `A` 读取这个 key，则选择 `A ->WW C` 时还会激活：

```text
B ->RW C
```

默认 constraint coalescing 会让同一 writer 事务对跨 key 共用一个方向选择，因为全局串行顺序中不可能在 `k1` 上要求 `A<C`、同时在 `k2` 上要求 `C<A`。这里合并的是“选择变量”，分支内部的各个 `WW/RW/type/key` 边仍保留。

pruning 只提前物化已经被已知可达性唯一决定的分支；未决分支继续进入 SAT。

### 5.3 `SERSolverAR`：编码已知边、WW 和 RW

构造器按以下顺序编码：

| 阶段 | 方法 | 作用 |
| --- | --- | --- |
| SETUP | 构造函数前半段 | 建唯一 `serializationGraph`、写索引、传播状态和已知闭包 |
| KNOWN_EDGES | `encodeKnownEdges` | 保留已知 typed metadata，并将已知顺序的传递约简加入 serialization graph |
| WW/RW | `encodeRemainingWwChoices` | 每个残余 `SERConstraint` 建一个正反 WW decision literal，选中分支同时激活其 `WW` 和 `RW` |
| PREDICATE | `encodePredicateConstraints` | 建 frontier、结果约束以及 guarded `PR_WR/PR_RW` |
| DEPENDENCIES | `encodeDependencyEdges` | 保留 typed edge metadata，将其 guard 绑定到 serialization edge |
| ACYCLIC | `encodeSerializationAcyclicity` | 对唯一 serialization graph 断言无环 |

`encodeKnownTypedEdges` 会把 `SO/WR/WW/RW/PR_WR/PR_RW` 保留为 logical dependency metadata，它们的端点方向与已知顺序的传递约简共同进入 `serializationGraph`。

Java checker侧的确定性precedence查询统一由 `PrecedenceOracle`提供：`before(a,b)`、`successor(a)`、`predecessor(b)`、`wouldCycle(a,b)`。`SERVerifier`为每次audit创建唯一实例，并通过constructor injection传给WW reachability、solver、GMWR propagation与GMWR-WW bridge；任一阶段加入deterministic fact后，所有阶段看到同一 `before relation`。MonoSAT的residual WW、frontier和order decision variables不进入oracle。

### 5.4 谓词边如何产生

对谓词读事务 `R` 和 external key `k`，统一 primitive `LatestVisibleChecker` 接收 `(reader, key, candidate writers, serialization order)`，返回每个候选 writer 的 visible literal 和 latest-writer validity。EAGER 直接传完整候选集；GMWR 先按已有 source/reachability/PR_RW-cycle/known interval 规则减少候选，再调用同一个 checker。

候选 source `S` 的选择条件可以概括为：

```text
selected(S,R,k)
  = S 在 R 前可见
    AND 不存在另一个在 S 后、同时仍位于 R 前的同 key writer
```

当同一个 key 有多个候选 writer `S1...Sn` 时，checker 不会预先任选一个 source，也不会再建立一组与 serialization order 无关的 source-choice 变量。它会为每个候选分别构造：

```text
visible_i  = beforeReader(k,Si,R)
selected_i = visible_i
             AND 对所有 j != i：NOT(beforeWrite(Si,Sj) AND visible_j)
```

因此，source 由 MonoSAT 求出的全局 serialization order 决定：若 `S1 < S2 < R`，则 `S2` 遮蔽 `S1`，只有 `selected(S2,R,k)` 成立；若 `S2 < S1 < R`，结果反过来；排在 `R` 后的 writer 不可见，也不会遮蔽 source。在 serialization order 为全序且至少存在一个可见 writer 时，这些公式自然保证恰好一个 AR-max writer 被选中，而不是靠额外的任意选择。每个 writer transaction 对该 key 只有最后一次写可以进入 external source 候选集，同事务内更早的写不会成为 external source。

若谓词结果已经记录了 `(k,value)`，紧凑历史先按唯一 `(k,value)` 解析出 `recordedSource`，随后直接断言该候选的 `selected` 条件；其他 writer 的相对顺序只能服从这一条件，否则模型不可满足。同一 `(k,value)` 对应多个 write 会被内部一致性检查判为 source 歧义。若没有 recorded source（例如未返回的 key），则由 serialization order 产生的 AR-max writer 与结果合法性 clause/GMWR obligation 共同决定哪些选择可行；不符合记录谓词结果的 frontier 会被约束或 refinement 排除。

当 `S` 被选为 source 时：

```text
selected(S,R,k) -> PR_WR(S,R,k)
```

对同 key 的另一个 writer `U`，若 `S ->WW U` 且 `U` 会改变记录的谓词 observation，则：

```text
selected(S,R,k) AND beforeWrite(S,U)
    -> PR_RW(R,U,k)
```

这里的“改变 observation”由 `writeChangesPredicateResult` 判断：包括匹配/不匹配发生变化，以及两边都匹配但输入或投影贡献不同。不会改变谓词结果的写不产生额外 `PR_RW`。

row-local 查询可以逐 key 预编码。JOIN、DISTINCT 或其他 general query 会在 SAT 给出候选 frontier 后执行完整 `QueryPlan`；若模型结果与记录不一致，则加入 no-good 后继续求解。refinement 改变的是 frontier 组合的合法性，不会在模型验证阶段临时发明新的边类型。

## 6. 核心算法一：谓词语义驱动的 WW 剪枝

### 6.1 它比普通 WW 剪枝多知道什么

设当前已经确定：

$$
T_b \prec T_r.
$$

令 bad writer $T_b$ 写入会让查询返回 `x` 的值 80，good writer $T_g$ 写入不会让查询返回 `x` 的值 20，而读取事务 $T_r$ 实际没有返回 `x`。此时 $T_b$ 与 $T_g$ 的 WW 顺序还没有决定。只看现有顺序图，以下两种顺序都可能无环：

$$
T_g \prec T_b \prec T_r,
$$

以及：

$$
T_b \prec T_g \prec T_r.
$$

然而，它们对查询的含义不同。在第一种顺序中，读取时 `x` 的最新值是 80，应该返回 `x`，与观察不符；在第二种顺序中，最新值是 20，没有返回 `x` 才合理。因此，谓词语义可以进一步推出：

$$
\boxed{T_b \prec T_g}
\qquad\text{以及}\qquad
\boxed{T_g \prec T_r}.
$$

原来未决的 WW 方向由此确定。这不是把可达性算法写得更快，而是给可达性剪枝增加了原本没有利用的谓词观察语义。

### 6.2 核心过程：删候选、推事实、反馈 WW

算法状态可以分成三部分：

$$
P=\text{已确定的顺序事实},
\qquad
U=\text{未决的 WW 选择},
\qquad
\mathcal O=\text{尚未解决的谓词义务}.
$$

对 reader $r$、bad writer $b$ 和 good writer 候选集合 $G$，单个谓词义务可写为：

$$
(r\prec b)\;\lor\;\bigvee_{g\in G}\bigl[(b\prec g)\land(g\prec r)\bigr]. \tag{1}
$$

首先用 $P$ 化简式（1）。好写者 $g$ 必须能够放在区间：

$$
b\prec g\prec r
$$

之中。若已经知道 $g\prec b$ 或 $r\prec g$，它就不可能完成这次覆盖，可以从候选集合中删除。

随后根据剩余候选传播：

| 当前已知情况 | 可以推出什么 |
| --- | --- |
| 已知 $r\prec b$ | 该 bad writer 对应的义务已满足 |
| 已知某个 $b\prec g\prec r$ | 该义务已被确定的覆盖关系满足 |
| 已无可行 good writer | 必须有 $r\prec b$ |
| 已知 $b\prec r$，且只剩一个 good writer $g$ | 必须有 $b\prec g\prec r$ |
| 已知 $b\prec r$，但没有可行 good writer | 当前约束发生矛盾 |

这里必须遵守一个原则：只能删除已证明不可能的候选，不能把“当前没有可达路径”当成“不可能”。

推导出的新顺序进入 $P$，然后重新检查 WW 选择。某个 WW 分支及其伴随依赖可能因此与新事实形成环，于是该分支被排除；固定的 WW 方向又可能让更多覆盖候选变得不可能。由此形成固定点传播：

$$
\boxed{
\text{WW 顺序事实}
\;\longrightarrow\;
\text{谓词义务化简}
\;\longrightarrow\;
\text{新顺序事实}
\;\longrightarrow\;
\text{更多 WW 剪枝}
}
$$

这一过程持续到没有新事实、候选删除或 WW 定向为止。

### 6.3 输出是更小的搜索问题，而不是完整答案

到达固定点不代表已经决定所有事务顺序。例如仍可能存在：

$$
(b\prec g_1\prec r)\;\lor\;(b\prec g_2\prec r).
$$

两个候选都可行时，传播过程不能擅自选择其中一个；这个选择仍交给求解器。因此，该算法的职责是提前确定必然成立的事实、排除必然错误的分支，并保留真正无法确定的部分。

一条已有实验记录将这种增量效果区分为：

| 阶段 | WW choices |
| --- | ---: |
| 初始 | 112,032 |
| WW reachability 之后 | 1,742 |
| GMWR 之后 | 1,565 |

该记录中的 `GMWR_TO_WW_FORCED=177` 对应最后一段额外定向，而不是把基础 WW 剪枝的全部收益也计入 GMWR。第一个算法贡献可以概括为：利用谓词观察强化 WW 推理，在进入完整求解之前缩小未决搜索空间。

## 7. 核心算法二：因子化谓词编码与延迟物化

### 7.1 为什么直接展开会产生冗余

回到式（1）：

$$
(r\prec b)\;\lor\;\bigvee_g\bigl[(b\prec g)\land(g\prec r)\bigr].
$$

一个读取事务可能执行多个谓词，一个写事务也可能修改多个 key。于是多个语义义务会重复涉及同样的：

$$
r\prec b,
\qquad
b\prec g,
\qquad
g\prec r.
$$

如果逐个义务独立处理，就可能反复构造顺序表达式、覆盖条件和条件依赖；有些义务最后还会被已知事实完全解决。问题不只是最终出现重复边，而是在知道这些结构是否必要之前，就已经付出了展开和构造成本。

### 7.2 第一层：把共享结构组织成 bundle

假设多个义务具有相同的读取事务 $r$ 和 bad writer $b$。记：

$$
a=(r\prec b),
$$

并令 $R_i$ 表示第 $i$ 个义务自己的覆盖条件。它们原本是：

$$
(a\lor R_1)\land(a\lor R_2)\land\cdots\land(a\lor R_m).
$$

利用布尔等价关系：

$$
\boxed{
\bigwedge_{i=1}^{m}(a\lor R_i)
\equiv
a\lor\bigwedge_{i=1}^{m}R_i
} \tag{2}
$$

可以把它们组织成一个共享结构：要么整个写事务 $b$ 在读取之后；否则，它带来的每一项问题都必须分别得到修复。这就是 bundle 的核心含义：共享公共条件，同时保留各个 item 的独立语义。

特别需要注意：

$$
a\lor(R_1\land R_2)
$$

不能改成：

$$
a\lor(R_1\lor R_2).
$$

后者只要求修复其中一个问题，会漏掉另一个 key 上的错误。同样，不同 item 可以由不同 good writer 修复，不能强行要求它们共享同一个 repair witness。

### 7.3 第二层：删除重复和被包含的义务

因子化之后，还可以在语义表示上继续化简。例如：

$$
C_1=a\lor x\lor y,
\qquad
C_2=a\lor x\lor y\lor z.
$$

因为：

$$
C_1\Rightarrow C_2,
$$

两者同时要求成立时，只保留 $C_1$ 即可。这里的 $x,y,z$ 可以代表完整的覆盖条件，例如：

$$
x=(b\prec g_1)\land(g_1\prec r).
$$

包含判断比较的必须是实际逻辑条件，不能仅凭两个候选列表的事务编号相似，就认定它们等价。已有运行记录中的 `BUNDLES`、`DUPLICATE_ITEM_CLAUSES`、`SUBSUMED_ITEM_CLAUSES` 和 `RESIDUAL_CLAUSES` 分别记录这些不同层次的数量，不能统称为“图边去重”。

### 7.4 第三层：只物化化简后仍然需要的部分

延迟物化的重点是改变构造顺序：

```text
直接展开：
枚举候选
  -> 构造完整公式和条件依赖
  -> 后续处理再发现其中一部分不需要

GMWR：
建立语义义务和 bundle
  -> 用已知顺序删除不可能的候选
  -> 消解已满足义务、合并重复项、执行包含消除
  -> 只为剩余部分构造 SAT 公式和必要的条件依赖
```

例如，一个义务最初包含：

$$
a\lor x_1\lor x_2\lor\cdots\lor x_{100}.
$$

假设化简证明其中 98 个覆盖候选不可能，最终只需要编码：

$$
a\lor x_7\lor x_{42}.
$$

若进一步证明 $a$ 已成立，这个剩余选择公式也不再需要；但使 $a$ 成立的必然顺序仍需保留，不能把它的语义一并删除。

因此，延迟物化不是“少检查一些谓词”，也不必然意味着“等求解出错后再补约束”。它首先意味着：先在紧凑语义层完成能够完成的推理，再支付底层编码成本。

### 7.5 为什么它不等于 witness coalescing 或 edge interning

三者发生的位置和消除的冗余不同：

| 技术 | 主要处理的对象 |
| --- | --- |
| GMWR 因子化与延迟物化 | 语义义务及其公式结构，避免不必要的展开 |
| Witness coalescing | 多个 witness 对同一逻辑依赖的重复支持 |
| Edge interning | 同一物理图中重复创建的边对象 |

GMWR 可以让某些候选、公式和依赖从一开始就不被构造；后两者主要避免已经进入相应构造阶段的重复表示。所以第二个算法贡献应概括为：通过共享公共条件、消除冗余义务，并延迟实例化残余依赖，构造等价但更紧凑的谓词约束表示。

## 8. 边的收集、合并和最终物化

### 8.1 语义对象：`SEREdge`

编码期的边对象包含：

```text
from
to
type
keys
```

普通边初始通常只有一个 key；`SEREdge` 对 0/1 key 使用空值或直接字段，出现第二个不同 key 时才升级为集合。谓词 coalescing 后，同一个 `SEREdge` 可以保存多个 witness key。

非谓词依赖按同一个 guard 下的完整 `SEREdge` 去重。谓词依赖先用 `(from,to,type,key,guard identity)` 去掉完全重复的 witness，再在完整 guard 已生成的当前位置直接合入 transaction-level accumulator，不保存逐 witness candidate 对象。

### 8.2 谓词 witness coalescing：按 `(from,to,type)`

在线 accumulator 的分组键明确包含 `type`：

```text
PredicateTransactionEdgeKey = (from, to, type)
```

因此：

```text
(a,b,PR_WR,k1,g1)
(a,b,PR_WR,k2,g2)
```

会变成：

```text
edge  = (a,b,PR_WR,{k1,k2})
guard = g1 OR g2
```

但：

```text
(a,b,PR_WR,k1)
(a,b,PR_RW,k1)
```

不会在这一层合并，因为 type 不同。`PR_WR` 与 `WR` 也不会在这一层合并，因为该过程只处理 predicate candidates，而且分组键仍包含 type。

这个内部设置是 `predicateWitnessCoalescing`，生产 G2/E2 均开启；隐藏参数 `--predicate-witness-coalescing` 仅供实验覆盖。WW constraint 始终由 `generateConstraintsCoalesce()` 按事务对合并。

### 8.3 Serialization graph-edge interning：按 `(from,to)`

`encodeDependencyEdge` 最终把 typed edge 投影到 MonoSAT：

```text
SEREdge(from,to,type,keys) -> serializationGraph.addEdge(fromNode,toNode)
```

默认开启 `graphEdgeInterning` 时，cache key 只有：

```text
(from,to)
```

因此所有相同端点、相同方向的语义依赖会共用一条 MonoSAT theory edge，不论它们的 type 或 key 是否相同。每个原始 guard 仍分别蕴含这条共享 edge：

```text
g1 -> E(a,b)
g2 -> E(a,b)
...
```

这对可满足性等价于：

```text
(g1 OR g2 OR ...) -> E(a,b)
```

代码没有额外断言 `E(a,b) -> (g1 OR g2 OR ...)`。这不会产生错误的 ACCEPT：没有 witness 激活时，SAT 可以令 `E(a,b)=false`；无缘由地令它为 true 只会让无环约束更严格，不会帮助模型满足公式。

每条 Java 语义边及其 guard 仍记录在 `logicalDependenciesByEndpoint[(from,to)]` 中，其 `SEREdge` 可通过 `getLogicalDependencies()` 用于 explanation 和 debugging。MonoSAT native edge 自身不保存 type/key，论文描述中的依赖类型以该 logical layer 为准。

<a id="711-紧凑编码的设计范围与处理范围"></a>

### 8.4 为什么这种“省边”不改变判环

对一个 SAT 模型 `M`，定义激活的语义依赖：

```text
D_M = {(u,v,type,key) | 对应 guard 在 M 中为 true}
```

MonoSAT 实际检查的是端点投影：

```text
π(D_M) = {(u,v) | 存在某个 type/key，使 (u,v,type,key) 属于 D_M}
```

有：

```text
D_M 有有向环  <=>  π(D_M) 有有向环
```

原因是平行边的数量和标签不会改变可达性；环只需要每一步存在对应方向的至少一条边。

所以：

- 同向、同端点、不同 key 的边对判环是平行边。
- 同向、同端点、不同 type 的边对 native 判环同样是平行边。
- 反方向不能合并。例如 `(a,b,PR_WR,...)` 与 `(b,a,PR_RW,...)` 会保留为两条相反方向的 native edge，并可能形成二环。
- 不同端点不能合并，即使 type/key 相同也必须保留各自方向。

从 Adya 的语义记录看，typed witness 没有被改写；从 MonoSAT 的图论判环看，只需要每个激活的 `(from,to)` 一条边。这就是当前压缩成立的边界。

## 9. 哪些边会被真正跳过

除了平行边复用，当前代码还会跳过以下不需要物化的候选：

| 情况 | 原因 |
| --- | --- |
| guard 恒假 | 该依赖不可能在任何当前模型中激活 |
| self edge | 事务内依赖由 program order/内部一致性处理，不建立事务级自环 |
| bottom 为端点 | bottom 顺序由常量处理，不进入真实事务 MonoSAT 图 |
| 完全重复的 witness | type、key、端点和 guard 都相同，没有新增约束 |
| `PR_WR` source 已知在 reader 后 | 激活会立即与已知 order 冲突 |
| `PR_WR` source 已被确定可见的后继 writer 遮蔽 | 它不可能成为 AR-max source |
| GMWR 证明某个 source 会强制 `PR_RW` 环 | 该 source alternative 不属于任何合法模型 |

这些情况中，后几项属于“删除已证明不可激活或不可满足的候选分支”，不是把一条仍可能在合法模型中独立生效的必要方向当作平行边删除。

已知顺序的传递约简进入 `serializationGraph`。所有已知 typed dependencies 仍经过 `encodeKnownTypedEdges` 保留元数据并施加同方向的 serialization 约束；传递约简不会删除 logical dependency metadata。

## 10. 当前开关、默认值和各自解决的问题

本节的“默认值”指 `audit` 命令的实际默认路径。CLI 会调用 `SolverSettings.forModes(...)`，而不是直接使用一个未初始化的裸 `SolverSettings` 对象。若测试或外部调用方自行 `new SolverSettings()` 且不再赋值，Java 的 boolean 字段初值是 `false`；这不是 `audit` CLI 的默认配置。

### 10.1 不传任何可选参数时的实际配置

```text
history type                    PRHIST
pruning mode                   REACHABILITY
predicate solving mode         GMWR
SER propagation mode           WW_GMWR
GMWR prepropagation            true
WW constraint coalescing       true
predicate witness coalescing   true
graph-edge interning           true
solver                         monosat
solver timeout                 600 seconds
detailed solver stats          false
```

按当前实验命名，这个无参数组合属于完整 `G2`：`GMWR + WW_GMWR + prepropagation + witness coalescing + graph-edge interning`。

如果显式使用：

```text
--predicate-encoding=eager
```

则切换为论文 E2 baseline：

```text
predicate solving mode         EAGER
SER propagation mode           WW_ONLY
GMWR prepropagation            false
```

WW reachability、predicate witness coalescing 和 graph-edge interning 仍开启。E1/G1 所需的物理展开或 one-way propagation 只通过隐藏 experimental flags 保留给 benchmark。

### 10.2 两个物理压缩实验开关

下表 CLI 均为隐藏 experimental 参数；生产 G2/E2 不要求用户指定。

| CLI | 内部设置 | 默认 | 解决的问题 | 关闭后的直接变化 |
| --- | --- | --- | --- | --- |
| `--[no-]predicate-witness-coalescing` | `predicateWitnessCoalescing` | 开启 | 多个 key 产生相同 `(from,to,type)` 的 `PR_WR/PR_RW` witness | 不再把 keys 和 guards 合并，每个 predicate witness 单独进入依赖队列 |
| `--[no-]graph-edge-interning` | `graphEdgeInterning` | 开启 | 不同 type/key 最终可能产生相同 `(from,to)` 的 MonoSAT serialization edge | 每个送入 `encodeDependencyEdge` 的 Java typed edge各建一条 serialization edge |

三者处理的不是同一个对象：

```text
PR_WR/PR_RW typed witnesses
    --[no-]predicate-witness-coalescing 控制

MonoSAT serializationGraph edge
    --[no-]graph-edge-interning 控制
```

两个开关都以保持 ACCEPT/REJECT 等价为目标，改变的是公式规模和物理表示，不改变六类 Adya relation 的定义。

#### `PR_WR(a,b)` 与 `WR(a,b)` 共用 native edge 是否由开关控制

是，直接由 `graphEdgeInterning` 控制。

`SolverSettings.forModes(...)` 无条件设置：

```text
predicateWitnessCoalescing = true
graphEdgeInterning         = true
```

随后 `Main.Audit.call()` 只在用户显式传入正向或负向参数时覆盖对应值。`encodeDependencyEdge` 的分支为：

```text
graphEdgeInterning = false
    每条 logical dependency 调用 serializationGraph.addEdge(from,to)

graphEdgeInterning = true
    复用 serializationEdgeCache[(from,to)]
```

因此：

```text
# 默认：PR_WR(a,b) 与 WR(a,b) 共用 E(a,b)
audit history

# 关闭：二者各自创建一条 MonoSAT native edge
audit --no-graph-edge-interning history

# 显式开启，与默认相同
audit --graph-edge-interning history
```

关闭后，Java 语义层没有变化，只是 MonoSAT 中重新出现同向平行边；预期 verdict 不变，native edge 数量和求解成本可能增大。

### 10.3 隐藏实验 WW/RW pruning 开关

| CLI | 默认 | 解决的问题 | 对 Adya 图的影响 |
| --- | --- | --- | --- |
| `--ww-pruning=NONE` | 否 | 保留全部未决 WW 分支，作为无剪枝实验基线 | 不预先物化分支，全部交给 SAT |
| `--ww-pruning=REACHABILITY` | 是 | 删除会立即与已知依赖闭包成环的 WW/RW 分支 | 只提前固定被可达性唯一决定的 typed edges |

这些开关发生在 `SERSolverAR` 构造之前，主要减少残余 `SERConstraint`。它们不会把某种 typed edge 改成另一种类型。

### 10.4 谓词编码模式

| CLI | 默认 | 解决的问题 | 保留的语义 |
| --- | --- | --- | --- |
| `--predicate-encoding=eager` | 否 | row-local 谓词逐 key 直接生成结果合法性 clause，对应 E2 | 仍构造 source-aware `PR_WR/PR_RW`，general query 仍做模型 refinement |
| `--predicate-encoding=gmwr` | 是 | 合并 `(reader,badWriter)` obligations、剪除不可能 source/frontier，并减少残余 clause，对应完整 G2 | 不改变 recorded source、`PR_WR/PR_RW` 定义或完整查询结果校验 |

`--predicate-encoding` 是公开的完整 predicate solving mode，不是 WW pruning 开关。旧 `--predicate-mode` 已删除。

`predicateWitnessCoalescing` 与该模式独立，CLI 默认在 EAGER 和 GMWR 下都开启；不要把“选择 GMWR”误解为“才会开启 `(from,to,type)` witness 合并”。

### 10.5 GMWR prepropagation 与 WW feedback

内部 `gmwrPrepropagation` 控制是否在 SAT 编码前运行 GMWR 化简；生产 G2 固定开启，`--[no-]gmwr-prepropagation` 仅为隐藏实验覆盖：

| 当前 predicate mode | 默认 | 行为 |
| --- | --- | --- |
| EAGER | 关 | `propagateBeforeEncoding` 直接返回；即使手动打开该开关，当前 EAGER 路径也不执行 GMWR propagation |
| GMWR | 开 | 先传播确定事实、消解已经满足或冲突的 GMWR obligations，再编码残余部分 |

关闭 prepropagation 不会关闭 GMWR obligation 构造，也不会退回 EAGER；它只是不在 SAT 前化简这些 obligations。

内部 `serPropagationMode` 控制 GMWR 信息是否反向固定残余 WW choice；生产 G2 固定为 `WW_GMWR`，`--ser-propagation-mode` 仅为隐藏实验覆盖：

| 值 | 默认条件 | 当前作用 |
| --- | --- | --- |
| `ww-only` | EAGER 默认 | 不运行 GMWR-to-WW bridge |
| `ww-gmwr-oneway` | 非默认实验值 | 允许既有 WW/known facts 参与 GMWR 化简，但不把 GMWR 结果反馈成 WW 分支 |
| `ww-gmwr` | GMWR 默认 | 运行 `propagateGmwrToWwFixpoint`，用 GMWR definite facts 检查并固定只能取一侧的 WW constraint，再把新 WW 同步回传播状态 |

当前 `SERSolverAR` 中只有 `WW_GMWR` 会进入 `GmwrWwBridge`；`WW_ONLY` 和 `WW_GMWR_ONEWAY` 都不会反向物化 WW。并且只有 `predicate mode=GMWR` 时才会构造 GMWR propagation state。

### 10.6 公开运行控制与隐藏实验开关

| CLI | 默认 | 作用 | 是否进入正常 Adya verdict 语义 |
| --- | --- | --- | --- |
| `--predicate-encoding=gmwr` | GMWR | 选择默认 G2；`eager` 切换为 E2 | 是，选择等价实现路径 |
| `--solver-timeout-seconds=600` | 600 秒 | 从 `solve()` 开始限制 MonoSAT 时间；`0` 表示不设 backend timeout | 不改变公式，但可能返回 `TIMEOUT` 而不是等到 SAT/UNSAT |
| `--solver-stats` | 关 | 输出 SAT、GMWR、coalescing 和 native edge 统计 | 否，只增加统计 |

普通 `audit --help` 只展示以上三项。E1/G1 和剪枝消融使用的 `--ser-propagation-mode`、GMWR prepropagation、witness coalescing、edge interning 与 WW pruning 仍可解析，但均隐藏并标记 experimental。固定 backend/loader、旧谓词参数和诊断参数已经删除。

### 10.7 如何通过隐藏实验参数验证某个压缩开关

若只想确认 `PR_WR/WR` 的 native edge 共享是否影响结果，应保持其他参数不变，只切换 graph-edge interning：

```bash
audit --graph-edge-interning /path/to/history
audit --no-graph-edge-interning /path/to/history
```

若要关闭两层谓词物理压缩：

```bash
audit \
  --no-predicate-witness-coalescing \
  --no-graph-edge-interning \
  /path/to/history
```

前一组只隔离 `(from,to)` native edge 复用；后一组会同时扩大 predicate witness 队列和 MonoSAT native 图，不能把性能差异只归因于 graph-edge interning。

## 11. 完整例子

假设事务 `A` 同时写 `k1/k2`，事务 `B` 的谓词读在两个 key 上都选择 `A` 为 source，并且 `B` 还有一个点读也读自 `A`：

```text
语义 witnesses:
  (A,B,PR_WR,k1,g1)
  (A,B,PR_WR,k2,g2)
  (A,B,WR,k1,true)
```

谓词 witness coalescing 后：

```text
  (A,B,PR_WR,{k1,k2}, g1 OR g2)
  (A,B,WR,k1,true)
```

graph-edge interning 后，MonoSAT `serializationGraph` 只有一个方向 edge：

```text
E(A,B)
```

约束为：

```text
(g1 OR g2) -> E(A,B)
true       -> E(A,B)
```

由于 `WR` 已经是强制边，`E(A,B)` 必须为 true。两个 `PR_WR` witness 仍用于谓词 source 语义和 provenance，但再增加两条同方向 native edge 不会改变任何环。

如果同时存在：

```text
(B,A,PR_RW,k3,g3)
```

则必须另建 `E(B,A)`，并编码：

```text
g3 -> E(B,A)
```

当 `g3=true` 时，`E(A,B)` 与 `E(B,A)` 构成二环，`serializationGraph.acyclic()` 会使该模型 UNSAT。这说明实现省掉的是平行表示，不是相反方向或新的可达关系。

## 12. ACCEPT / REJECT 的准确含义

`ACCEPT` 表示存在一个 SAT 模型，使得：

1. 内部一致性检查通过。
2. 每个同 key writer 对有一致的 WW 方向。
3. 点读的 WR/RW latest-visible 约束成立。
4. 谓词 source、PR_WR、PR_RW 和记录结果约束成立。
5. 所有激活 typed dependency 与 frontier/order 选择投影到同一张无环 `serializationGraph`。
6. general query 的完整模型校验不再发现不匹配快照。

任何有限无环偏序都可以拓扑扩展成严格全序，因此这样的模型对应一个合法串行解释。

`REJECT` 表示内部一致性直接矛盾、pruning 已证明冲突，或者不存在同时满足上述条件的模型。`TIMEOUT` 与 `REJECT` 分开返回，不会把求解超时误报为不可串行化。

## 13. 关键实现位置

| 文件 | 与 Adya 图相关的职责 |
| --- | --- |
| `src/main/java/graph/EdgeType.java` | 定义六类 typed edge |
| `src/main/java/graph/KnownGraph.java` | 收集 SO、WR、write/source 索引和 predicate observations |
| `src/main/java/verifier/SEREdge.java` | 编码期 typed edge，保存 from/to/type/keys |
| `src/main/java/verifier/SERConstraint.java` | 表示 writer 事务对的两个 WW/RW 分支 |
| `src/main/java/verifier/PrecedenceOracle.java` | 每次audit唯一的deterministic precedence state，提供before/successor/predecessor/wouldCycle并由各模块共享 |
| `src/main/java/verifier/LatestVisibleChecker.java` | 统一计算 candidate writer 的 latest-visible validity |
| `src/main/java/verifier/SERVerifier.java` | 内部一致性、约束生成、pruning、求解入口 |
| `src/main/java/verifier/SERSolverAR.java` | logical dependency metadata、唯一 serialization graph、edge guard、frontier、coalescing、interning 和判环 |

阅读 `SERSolverAR.java` 时，最直接的调用链是：

```text
addDependencyEdge
  -> complete guard / exact dedup
  -> predicateDependencyAccumulators（可选，立即按 from/to/type 合并）
  -> flushPredicateDependencies
  -> queueGuardedDependency
  -> encodeDependencyEdges
  -> encodeDependencyEdge
       -> orderLiteral
       -> serializationGraph edge（可选，按 from/to intern）
```

这条调用链也给出了本文核心问题的最终答案：type/key 在前面的语义层保留，最后进入 MonoSAT 无环图时才投影为事务端点方向。
