# SER 性能优化策略

本文档针对当前 SER detector 在大结果集谓词历史上的时间和内存瓶颈，给出可分阶段实施、可验证、保持判定语义不变的优化方案。

文档结论基于 2026-08-02 对以下两组 PostgreSQL KV 历史的实际检测结果：

- 20 个客户端 session。
- 每个 session 100 个已提交事务，共 2000 个客户端事务。
- 每个事务 15 个操作，共 30000 个客户端操作。
- 10000 个初始 key，uniform key distribution。
- 谓词读比例分别为 1% 和 5%。
- SERIALIZABLE 历史。
- SER 使用 8 GiB JVM heap，MonoSAT backend。

本文档只描述优化策略，不改变 SER 判定规则、PRHIST 格式或现有 verdict。

## 1. 结论摘要

当前核心瓶颈不在 pruning，也不主要在 MonoSAT 的 SAT 搜索，而在 MonoSAT solve 之前的 Java 侧处理：

1. Loader 把大规模谓词结果物化为多份对象结构。
2. 内部一致性检查、KnownGraph 和 SERSolverAR 对每次谓词读重复扫描完整 key universe。
3. 每个 predicate observation 长期保存一份接近 10000 项的 HashMap。
4. 谓词边使用 ArrayList.contains 做线性去重。
5. 相同查询对相同写版本被重复执行，反复创建 MapVisibleState、QueryEvaluation、Map 和 QueryValue。
6. 大量短命和长期存活对象触发频繁 G1 GC，并在 5% 历史上迫使系统使用 swap。

推荐优化顺序是：

1. 补充分阶段计时和真实 solver statistics。
2. 把谓词边去重改为常数时间索引。
3. 缓存 row-local predicate contribution，并复用 scope/key 列表。
4. 压缩 PredicateObservation 和 RecordedQueryResult 的内存表达。
5. 为 TRUE、EQ、MOD、GT、LT 建立专用索引和编码路径。
6. 最后再考虑并行预计算、JNI 批处理和硬件扩容。

直接增加 JVM heap 不是当前机器上的优先方案。5% 历史峰值 RSS 已达到约 8.48 GiB，运行期间系统一度使用约 2.5 GiB swap；继续增大 heap 会挤压 MonoSAT native memory 和操作系统页缓存。

## 2. 当前基线

### 2.1 历史规模

| 指标 | 1% 谓词读 | 5% 谓词读 | 比率 |
|---|---:|---:|---:|
| 客户端 session | 20 | 20 | 1.00 |
| 客户端事务 | 2000 | 2000 | 1.00 |
| 客户端操作 | 30000 | 30000 | 1.00 |
| 初始写事件 | 10000 | 10000 | 1.00 |
| SER Event 总数 | 40000 | 40000 | 1.00 |
| 谓词读 | 320 | 1555 | 4.86 |
| result.inputs 行数 | 1,487,070 | 6,905,812 | 4.64 |
| result.values 行数 | 1,487,070 | 6,905,812 | 4.64 |
| 平均每次谓词返回行数 | 4647 | 4441 | 0.96 |
| P50 返回行数 | 3100 | 2595 | 0.84 |
| P95 返回行数 | 10000 | 10000 | 1.00 |
| 最大返回行数 | 10000 | 10000 | 1.00 |
| history.prhist.jsonl | 84 MiB | 381 MiB | 4.54 |

SER Event 总数包含 30000 个客户端操作和 bottom transaction 中的 10000 个初始写事件。

### 2.2 5% 历史的查询构成

| 谓词类型 | 次数 | result.inputs 行数 | 平均返回 |
|---|---:|---:|---:|
| TRUE | 320 | 3,200,000 | 10000 |
| GT | 290 | 1,675,141 | 5776 |
| LT | 312 | 1,290,598 | 4137 |
| MOD | 296 | 739,852 | 2499 |
| EQ | 337 | 221 | 0.66 |

TRUE 只占 20.6% 的谓词读，却贡献 46.3% 的结果行。EQ 与 TRUE 都只算一次谓词操作，但成本相差四个数量级。因此后续 benchmark 不能只使用 predicate read ratio，至少还要记录：

- predicate count。
- result input row count。
- result value row count。
- covered key count。
- candidate writer count。
- 各查询类型和选择率。

### 2.3 检测耗时

| 阶段 | 1% | 5% | 比率 |
|---|---:|---:|---:|
| ENTIRE_EXPERIMENT | 96.591 s | 416.370 s | 4.31 |
| Loader 与框架开销，按差值估算 | 3.253 s | 25.437 s | 7.82 |
| ONESHOT_CONS | 9.279 s | 42.331 s | 4.56 |
| SER_VERIFY_INT | 6.335 s | 33.051 s | 5.22 |
| SER_GEN_PREC_GRAPH | 2.259 s | 8.736 s | 3.87 |
| SER_GEN_CONSTRAINTS | 0.181 s | 0.123 s | 0.68 |
| SER_PRUNE | 0.490 s | 0.408 s | 0.83 |
| ONESHOT_SOLVE | 84.059 s | 348.602 s | 4.15 |

两个历史均返回 ACCEPT。

ONESHOT_SOLVE 当前同时包括 SERSolverAR 构造、全部 Java 约束编码、MonoSAT solve 和可能的 predicate refinement。它不能被直接解释成 SAT 求解时间。

### 2.4 内存与 GC

| 指标 | 1% | 5% |
|---|---:|---:|
| Profiler 记录的最大 Java 已用堆 | 3.4 GB | 6.6 GB |
| 最大 RSS | 4.45 GiB | 8.48 GiB |
| JFR GarbageCollection 事件 | 65 | 324 |
| Young GC | 62 | 283 |
| Old GC | 3 | 41 |
| 最大观测 swap 使用 | 很低 | 约 2.5 GiB |

5% 运行没有 Full GC 或 JVM OOM，但存活对象持续超过 6 GiB，已经接近 8 GiB heap 上限。10% 历史在相同 heap 下 OOM 与这一增长趋势一致。

### 2.5 优化迭代记录表

后续每次性能改动追加一个独立编号，不覆盖旧数据。Loader 类优化统一记录同一历史、同一 JVM 参数下的加载耗时、`result.inputs` 吞吐和进程最大 RSS；耗时降幅按 `(优化前 - 优化后) / 优化前` 计算，吞吐提升按 `(优化后 - 优化前) / 优化前` 计算。

| 编号 | 日期 | 优化项 | 数据集 | Loader 耗时（前 → 后） | 耗时降幅 / 加速比 | inputs 吞吐（前 → 后） | 吞吐提升 | 最大 RSS（前 → 后） | RSS 降幅 | 正确性 |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---|
| A-001 | 2026-08-02 | RecordedQueryResult 分层；KV `k,value` row-local 结果不长期保存 values/multiset/canonicalInputs | 1% / 1,487,070 行 | 2.642 s → 2.342 s | 11.35% / 1.128x | 562,886 → 634,920 行/s | 12.80% | 1.393 GiB → 0.782 GiB | 43.89% | 40,000 Event；完整测试通过 |
| A-001 | 2026-08-02 | 同上 | 5% / 6,905,812 行 | 20.594 s → 7.633 s | 62.94% / 2.698x | 335,327 → 904,775 行/s | 169.82% | 4.767 GiB → 2.942 GiB | 38.29% | 40,000 Event；完整测试通过 |

本次前后测量均使用独立 JVM、`-Xms8g -Xmx8g`，只执行 `PredicateHistoryLoader.loadHistory()` 并保持返回的 History 存活到指标输出，因此不混入 KnownGraph、约束编码或 MonoSAT 时间。完整 `SER/ser-result-detector` 测试执行 152 项、0 失败、2 项按既有配置跳过；优化后的 1% 历史完整 SER audit 用时 70.126 秒，最终 verdict 为 `ACCEPT`，Profiler 最大 Java 已用堆为 2.3 GB。

#### PredicateObservation 分阶段记录

以下数据使用同一独立 JVM 和 `-Xms8g -Xmx8g`，计时边界仅为 `new KnownGraph(history)`，并保持 History 与 KnownGraph 存活到指标输出。耗时变化和 RSS 变化均按 `(优化前 - 优化后) / 优化前` 计算；正数表示改善，负数表示回退。方案 B 的三个候选都以方案 A 为共同基线，候选之间不是累加关系。

| 编号 | 优化项 | 数据集 | KnownGraph 耗时（前 → 后） | 耗时变化 / 加速比 | 最大 RSS KiB（前 → 后） | RSS 变化 | 结论 |
|---|---|---|---:|---:|---:|---:|---|
| PO-A | 连续 keyId；每个 observation 用 covered/internal BitSet，Map API 改为惰性视图 | 1% / 320 PR | 1.266797 s → 0.812822 s | +35.84% / 1.559x | 884268 → 876332 | +0.90% | 保留 |
| PO-A | 同上 | 5% / 1555 PR | 5.217065 s → 3.414043 s | +34.56% / 1.528x | 3329680 → 3692412 | -10.89% | 保留；单次 RSS 有回退 |
| PO-B-R | 默认类型 + RoaringBitmap 稀疏例外候选 | 1% / 320 PR | 0.812822 s → 0.962970 s | -18.47% / 0.844x | 876332 → 877772 | -0.16% | 未采用 |
| PO-B-R | 同上 | 5% / 1555 PR | 3.414043 s → 3.313492 s | +2.95% / 1.030x | 3692412 → 3328444 | +9.86% | 未采用 |
| PO-B-I | 默认类型 + 有序 `int[]` 稀疏例外候选 | 1% / 320 PR | 0.812822 s → 1.005157 s | -23.66% / 0.809x | 876332 → 887584 | -1.28% | 未采用 |
| PO-B-I | 同上 | 5% / 1555 PR | 3.414043 s → 3.119989 s | +8.61% / 1.094x | 3692412 → 3005228 | +18.61% | 未采用 |
| PO-B | 默认类型 + 稀疏 BitSet 例外 | 1% / 320 PR | 0.812822 s → 0.919714 s | -13.15% / 0.884x | 876332 → 894296 | -2.05% | 与方案 C 组合保留 |
| PO-B | 同上 | 5% / 1555 PR | 3.414043 s → 3.543779 s | -3.80% / 0.963x | 3692412 → 3637164 | +1.50% | 与方案 C 组合保留 |
| PO-C | 事务内 written/observed key 改为 BitSet；完整 scope 后使用 coverage epoch | 1% / 320 PR | 0.919714 s → 0.727917 s | +20.85% / 1.263x | 894296 → 821996 | +8.08% | 保留 |
| PO-C | 同上 | 5% / 1555 PR | 3.543779 s → 2.847361 s | +19.65% / 1.245x | 3637164 → 2985640 | +17.91% | 保留 |
| PO-FINAL | PO-A + PO-B + PO-C 相对原始 HashMap | 1% / 320 PR | 1.266797 s → 0.727917 s | +42.54% / 1.740x | 884268 → 821996 | +7.04% | 最终实现 |
| PO-FINAL | 同上 | 5% / 1555 PR | 5.217065 s → 2.847361 s | +45.42% / 1.832x | 3329680 → 2985640 | +10.33% | 最终实现 |

每个保留阶段及方案 B 候选均通过完整 Gradle 回归；最终执行 152 项、0 失败、2 项按既有配置跳过。覆盖语义专项测试确认 scope 外为 `null`、先前本地写或先前谓词覆盖为 `INTERNAL`、其他 covered key 为 `EXTERNAL`，并确认部分 scope 不推进 epoch。最终 1% 大历史完整 SER audit 用时 69.95 秒，verdict 保持 `ACCEPT`。

#### Scope key 列表缓存记录

目标子阶段使用三个独立 JVM 测量，仅计时全部 observation 的 scope 筛选、`String.valueOf` 排序和 List 收集；优化后计时包括一次全局排序、稳定 scope cache key 查找和首次筛选。表中为三次中位数，前后 checksum 必须一致。

| 编号 | 优化项 | 数据集 | Scope 列表阶段（前 → 后） | 耗时变化 / 加速比 | checksum | 结论 |
|---|---|---|---:|---:|---:|---|
| SC-001 | 全局 key 只排序一次；按稳定 QueryScope 值 key 缓存不可变筛选结果 | 1% / 320 PR | 0.799033 s → 0.023343 s | +97.08% / 34.230x | 3,200,000 → 3,200,000 | 保留 |
| SC-001 | 同上 | 5% / 1555 PR | 3.179699 s → 0.026357 s | +99.17% / 120.640x | 15,550,000 → 15,550,000 | 保留 |

独立 `SERSolverAR` 构造单次测量受大堆、native 分配和换页影响，1% 为 56.322618 秒 → 61.605304 秒（-9.38%），5% 为 222.099909 秒 → 251.627602 秒（-13.29%），因此不能把该单次整体构造结果记为提升。最终 1% 完整 audit 的 `ONESHOT_SOLVE` 从 62.564 秒降至 59.435 秒（+5.00%，1.053x），总用时从 69.95 秒降至 66.98 秒（+4.25%，1.044x），verdict 保持 `ACCEPT`。完整 Gradle 回归执行 153 项、0 失败、2 项按既有配置跳过。

#### Row contribution 缓存记录

本阶段以前一阶段最终代码为基线。独立构造测量使用相同历史、独立 JVM、`-Xms8g -Xmx8g`，计时边界仅为 `new SERSolverAR(history, graph, constraints)`；进程最大 RSS 包含保持存活的 History、KnownGraph、constraints 和 solver。提升按 `(前 - 后) / 前` 计算。

| 编号 | 优化项 | 数据集 | SERSolverAR 构造（前 → 后） | 耗时提升 / 加速比 | 最大 RSS KiB（前 → 后） | RSS 降幅 | 正确性 |
|---|---|---|---:|---:|---:|---:|---|
| RC-001 | solver-local BitSet contribution；AST 直接编译 TRUE/EQ/MOD/GT/LT matcher；通用 canonical contribution 有界 LRU | 1% / 320 PR | 61.605304 s → 28.258634 s | 54.13% / 2.180x | 5,980,152 → 2,656,768 | 55.57% | 构造完成，无 OOM |
| RC-001 | 同上 | 5% / 1555 PR | 251.627602 s → 164.726158 s | 34.54% / 1.528x | 8,518,724 → 5,560,696 | 34.72% | 构造完成，无 OOM、进程级 swap 为 0 |

最终 1% 完整 audit 的 `ONESHOT_SOLVE` 从 59.435 秒降至 37.255 秒（37.32%，1.595x），总用时从 66.98 秒降至 44.056 秒（34.23%，1.520x）；verdict 保持 `ACCEPT`，Profiler 最大 Java 已用堆为 2.0 GB，进程最大 RSS 为 2,535,184 KiB。完整 Gradle 回归执行 156 项、0 失败、2 项按既有配置跳过。

#### KeyWriteIndex 记录

`KWI-001` 在每个 `SERSolverAR` 实例内为每个 key 建立不可跨历史共享的 `KeyWriteIndex`。索引从原 `writesByKey` 已按 `(txnId,eventIndex)` 排序的列表构造：writer 保持 `LinkedHashMap` 第一次出现顺序，每个 writer 的 external candidate 仍取最后一个 `WriteRef`；事务内读取使用同一 writer 的有序 write 列表二分查找严格小于 predicate event index 的最后一个写。scope key 顺序、遍历 key 数、candidate 顺序、`SEREdge` 调用、guard、clause 和 dependency 去重流程不变。

该修改改变 L3 数据表示和 L4 执行/分配语义，不改变 L0 查询与 verdict、L1 边推导或 L2 guard/clause 编码语义。详细统计中的 latest-writer input writes 继续记录与旧实现可比的逻辑输入版本数，表示被索引复用、无需再次归并的版本，而不是优化后的实际重复扫描次数。

首先评估的每 key 常驻 writer `HashMap` 候选没有保留：1% 三次中位数的 row-local key scan 从 35.323 秒回退到 37.240 秒（-5.43%），predicate 编码从 38.283 秒回退到 40.070 秒（-4.67%）。最终实现只长期保留紧凑 writer/group List，构造期 `LinkedHashMap` 不进入长期索引。

1% 使用相同历史、独立 JVM、`-Xms8g -Xmx8g` 和 `--solver-stats` 各运行三次，表中取中位数：

| KWI-001 指标 | 修改前 | 修改后 | 变化 / 加速比 |
|---|---:|---:|---:|
| ROW_LOCAL_KEY_SCAN | 35.323 s | 34.426 s | +2.54% / 1.026x |
| SER_AR_ENCODE_PREDICATE | 38.283 s | 37.525 s | +1.98% / 1.020x |
| SER_AR_ENCODE | 38.609 s | 37.881 s | +1.89% / 1.019x |
| ONESHOT_SOLVE | 39.795 s | 39.094 s | +1.76% / 1.018x |
| ENTIRE_EXPERIMENT | 48.003 s | 48.714 s | -1.48% / 0.985x；loader/GC 波动，不申报整体提升 |
| 最大 RSS | 2,639,872 KiB | 2,212,652 KiB | +16.18% |

5% 使用相同条件各运行一次，用于规模验证而不作为稳定中位数：

| KWI-001 指标 | 修改前 | 修改后 | 变化 / 加速比 |
|---|---:|---:|---:|
| ROW_LOCAL_KEY_SCAN | 159.240 s | 133.396 s | +16.23% / 1.194x |
| SER_AR_ENCODE_PREDICATE | 170.173 s | 145.050 s | +14.76% / 1.173x |
| SER_AR_ENCODE | 170.803 s | 145.771 s | +14.66% / 1.172x |
| ONESHOT_SOLVE | 186.914 s | 161.903 s | +13.38% / 1.154x |
| ENTIRE_EXPERIMENT | 222.473 s | 193.585 s | +12.99% / 1.149x |
| 最大 RSS | 5,431,820 KiB | 6,134,292 KiB | -12.93%；单次回退，不申报内存收益 |

前后 1%/5% 的 observations、scoped/internal/external key、latest-writer lookup/input/result、frontier/candidate、beforeWrite、known edge、dependency attempt/duplicate/skipped/queued、blocking clause/literal 计数逐项一致；两组 verdict 均为 `ACCEPT`。专项测试覆盖同一 writer 多版本只选择最后一写、旧版本不能成为 frontier，以及 predicate event 前事务内最后写；完整 Gradle 回归为 160 项、0 失败、2 项按既有配置跳过。

#### Coverage epoch row-local 稀疏快路径（未保留）

`CE-RL-001` 评估了 `coverageEpoch > 0` 时只检查事务内稀疏写 key、跳过其余无动作 INTERNAL key 的实现。候选没有修改 PR_WR/PR_RW、guard、blocking clause 或 AR 关系定义；1%/5% 前后 19 项 predicate 结构计数逐项一致，verdict 均为 `ACCEPT`。

1% 使用独立 JVM、`-Xms8g -Xmx8g` 和 `--solver-stats` 各运行三次并取中位数：

| CE-RL-001 指标 | 当前状态 | 候选 | 变化 |
|---|---:|---:|---:|
| ROW_LOCAL_KEY_SCAN | 30.304 s | 29.192 s | +3.67% |
| SER_AR_ENCODE_PREDICATE | 34.647 s | 31.486 s | +9.12% |
| SER_AR_ENCODE | 34.980 s | 31.797 s | +9.10% |
| ONESHOT_SOLVE | 35.950 s | 32.765 s | +8.86% |
| ENTIRE_EXPERIMENT | 42.424 s | 39.451 s | +7.01% |
| 最大 RSS | 2,376,144 KiB | 3,054,172 KiB | -28.53% |

同机同时间段 5% 单次规模对照否定了 1% 表观收益：

| CE-RL-001 指标 | 当前状态 | 候选 | 变化 |
|---|---:|---:|---:|
| ROW_LOCAL_KEY_SCAN | 107.803 s | 115.359 s | -7.01% |
| SER_AR_ENCODE_PREDICATE | 117.262 s | 126.459 s | -7.84% |
| SER_AR_ENCODE | 117.714 s | 127.050 s | -7.93% |
| ONESHOT_SOLVE | 130.585 s | 139.627 s | -6.92% |
| ENTIRE_EXPERIMENT | 154.894 s | 167.232 s | -7.97% |
| 最大 RSS | 5,863,528 KiB | 8,160,176 KiB | -39.17% |

由于 5% 同时发生速度和内存回退，候选未保留；`SERSolverAR.java` 已恢复到修改前 SHA-256 `a63e9d536abef23c99c2cdee0d0f3fb5f371e4ae272ffbaf5a0c397c4e5425ae`。恢复后完整 Gradle 回归执行 160 项、0 失败、2 项按既有配置跳过。

#### CALFE：候选 AR 重放与按需公式物化

`CALFE-001` 将实现分成两个阶段。第一阶段冻结当前 eager row-local 编码作为对照组，源码为 `备份/tag_SERSolverAR.java.bak_date20260803162132`，SHA-256 为 `a63e9d536abef23c99c2cdee0d0f3fb5f371e4ae272ffbaf5a0c397c4e5425ae`。第二阶段只对 `QueryPlan.isRowLocal()` 的 observation 使用 CALFE；通用 JOIN、DISTINCT 和非 row-local 查询仍走原 `PredicateCheck` 路径。

1% 的每次迭代均使用相同历史、独立 JVM、`-Xms8g -Xmx8g` 和 `--solver-stats`。表中“完整 audit 变化”相对上一行计算，正数为提速；未保留的候选也不删除记录。

| 阶段 | 修改 | Predicate 初始编码 | AR 变量预声明 | MonoSAT | CALFE 重放 | ONESHOT | 完整 audit | 最大 RSS KiB | 完整 audit 变化 | 结论 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| C0 | 当前 eager 对照组，中位数 | 34.647 s | 0 | 0.970 s | 0 | 35.950 s | 42.424 s | 2,376,144 | — | 冻结基线 |
| C1 | 首次 solve 前同时预声明 AR 图变量和全部潜在 pair 的 XOR 可比性 | 3.030 s | 1.195 s | 51.261 s | 6.800 s | 62.619 s | 69.142 s | 3,873,532 | -62.98% | 未保留；欠约束总序搜索转移到 MonoSAT |
| C2 | 只预声明图变量；pair 的 XOR 在公式物化时加入 | 3.555 s | 5.985 s | 6.111 s | 9.491 s | 26.329 s | 32.751 s | 5,616,020 | +52.63% | 继续优化 |
| C3 | 已由 known-order 传递闭包决定的 pair 不创建图变量 | 3.737 s | 0.968 s | 0.190 s | 8.892 s | 14.076 s | 20.725 s | 5,608,968 | +36.72% | 保留 |
| C4 | observation 以连续 keyId 数组保存 recorded source，重放不重建 HashMap | 4.089 s | 1.020 s | 0.199 s | 8.146 s | 13.772 s | 20.269 s | 5,638,052 | +2.20% | 保留 |
| C5 | solver-local 缓存 Event 的 row-local QueryPlan 判定，避免每次 write 检查重复构造 Stream | 3.867 s | 0.978 s | 0.171 s | 5.454 s | 10.793 s | 17.214 s | 4,268,096 | +15.07% | 最终实现 |
| C6 | 跨 QueryPlan 对象共享 capability matcher BitSet | 3.953 s | 0.910 s | 0.219 s | 7.975 s | 13.392 s | 19.772 s | 5,608,280 | -14.86% | 未保留；恢复 plan-local cache |

最终 C5 相对 C0 的 1% 完整 audit 提升 59.42%，ONESHOT 提升 69.98%，predicate 初始编码提升 88.84%；但最大 RSS 增加 79.62%，不申报内存收益。最终 1% 执行 3 轮重放、访问 960 万个 reader-key、物化 25,889 个 reader-key 公式，并在首次 solve 前为 15,702 个未知事务 pair 创建图变量。

5% 使用同一时间段冻结的当前版本和最终 C5 各运行一次：

| 指标 | 当前 eager | CALFE C5 | 变化 / 加速比 |
|---|---:|---:|---:|
| SER_AR_ENCODE_PREDICATE | 117.262 s | 10.218 s | +91.29% / 11.476x |
| SER_AR_ENCODE | 117.714 s | 13.503 s | +88.53% / 8.718x |
| SER_MONOSAT_SOLVE | 12.870 s | 1.483 s | +88.48% / 8.678x |
| SER_AR_PREDICATE_REFINEMENT | < 1 ms | 19.676 s | 工作由 eager 编码转移到 3 轮按需重放 |
| ONESHOT_SOLVE | 130.585 s | 34.662 s | +73.46% / 3.767x |
| ENTIRE_EXPERIMENT | 154.894 s | 58.680 s | +62.12% / 2.640x |
| 最大 RSS | 5,863,528 KiB | 6,604,692 KiB | -12.64%；无 swap、无 OOM |

5% 的 1,555 个 observation 仍各覆盖 10,000 个 key。CALFE 共执行 3 轮、访问 4,665 万个 reader-key，但只物化 70,441 个 reader-key 公式，占 1,555 万个潜在公式的 0.453%；预声明的未知 AR pair 为 46,834 个。verdict 前后均为 `ACCEPT`。

##### 5% 多历史验证

为检查 CALFE 是否只适配原 5% 历史，另外生成 3 份独立 PostgreSQL 历史，并连同原历史组成 4 历史样本。所有历史固定使用 20 个 session、每 session 100 个已提交事务、每事务 15 个操作、10,000 个 key、uniform 分布、`predicateReadRatio=5`、`TRANSACTION_SERIALIZABLE`、60 秒测量窗口和 unlimited rate；仅随机操作序列、并发调度及重试过程自然变化。生成器审计确认每份历史均为 2,000 个事务、30,000 个操作、10,000 个初始 key，且每个 session 恰好 100 个事务。

| 历史 | 数据集 | 谓词读 | 实际占比 | 点读 | 写 | 服务端重试 | history SHA-256 前缀 |
|---|---|---:|---:|---:|---:|---:|---|
| H0 | `kv_s20_t100_o15_pr5_rows10000_uniform_20260802_160519` | 1,555 | 5.183% | 14,225 | 14,220 | 13,733 | `ff9d55d3d76c` |
| H1 | `kv_s20_t100_o15_pr5_rows10000_uniform_multi01_20260803_172520` | 1,514 | 5.047% | 14,296 | 14,190 | 13,706 | `ea238973a7ee` |
| H2 | `kv_s20_t100_o15_pr5_rows10000_uniform_multi02_20260803_173050` | 1,512 | 5.040% | 14,078 | 14,410 | 13,543 | `f5cabc3b4c82` |
| H3 | `kv_s20_t100_o15_pr5_rows10000_uniform_multi03_20260803_173700` | 1,487 | 4.957% | 14,234 | 14,279 | 12,901 | `3015e7b66bef` |

eager 对照组使用冻结源码 `a63e9d536abef23c99c2cdee0d0f3fb5f371e4ae272ffbaf5a0c397c4e5425ae` 在隔离目录构建；CALFE 使用最终源码 `c99025c6d8b8995c2b143cd46e0621c9e9251b3f98efd6db20085543ef5320a1`。两组均使用独立 JVM、`-Xms8g -Xmx8g` 和 `--solver-stats`，同一历史不并行求解；H0/H2 先 eager，H1/H3 先 CALFE，以交替次序减小执行先后偏差。主指标为检测器内部 `ENTIRE_EXPERIMENT`。

| 历史 | eager 完整 audit | CALFE 完整 audit | 节省时间 | 端到端加速 | eager predicate 编码 | CALFE predicate 编码 | predicate 加速 | CALFE 物化 reader-key | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| H0 | 200.363 s | 58.852 s | 70.63% | 3.405x | 156.407 s | 11.246 s | 13.908x | 70,441 / 15,550,000（0.453%） | ACCEPT / ACCEPT |
| H1 | 187.029 s | 61.511 s | 67.11% | 3.041x | 143.907 s | 10.623 s | 13.547x | 90,128 / 15,140,000（0.595%） | ACCEPT / ACCEPT |
| H2 | 165.072 s | 60.885 s | 63.12% | 2.711x | 116.013 s | 11.290 s | 10.276x | 84,421 / 15,120,000（0.558%） | ACCEPT / ACCEPT |
| H3 | 159.550 s | 62.518 s | 60.82% | 2.552x | 124.892 s | 11.079 s | 11.273x | 52,243 / 14,870,000（0.351%） | ACCEPT / ACCEPT |

四历史算术均值和汇总比例如下。加速比使用“eager 均值 / CALFE 均值”，不是选择最快一次；端到端逐历史加速的中位数为 2.876x、几何均值为 2.909x，范围为 2.552x--3.405x。

| 指标 | eager 四历史均值 | CALFE 四历史均值 | 节省时间 / 加速比 |
|---|---:|---:|---:|
| SER_AR_ENCODE_PREDICATE | 135.305 s | 11.059 s | 91.83% / 12.234x |
| SER_AR_ENCODE | 135.828 s | 14.285 s | 89.48% / 9.508x |
| SER_MONOSAT_SOLVE | 15.724 s | 2.510 s | 84.04% / 6.264x |
| SER_AR_PREDICATE_REFINEMENT | < 1 ms | 19.672 s | 工作转移到每份历史 3 轮按需重放 |
| ONESHOT_SOLVE | 151.553 s | 36.644 s | 75.82% / 4.136x |
| ENTIRE_EXPERIMENT | 178.004 s | 60.941 s | 65.76% / 2.921x |

峰值 RSS 逐历史为 eager/CALFE：H0 8,450,280/7,102,284 KiB，H1 6,115,636/7,011,480 KiB，H2 7,233,928/6,777,416 KiB，H3 7,542,992/6,822,332 KiB。CALFE 在 H1 增加 14.65%，其余三份下降 6.31%--15.95%；四历史均值下降 5.55%，但该方向不一致，因此仍不申报稳定内存收益。8 次运行均无 swap、无 OOM。

结论边界：4 份不同历史上的最差端到端结果仍提升 60.82%，且 eager/CALFE verdict 逐份一致，能够排除“只在原历史有效”的当前证据风险。每个历史的每个实现在本批次只运行一次，因此该实验验证的是跨历史稳健性，不替代同一历史多次重复对运行时方差的统计估计。原始生成与求解日志保存为 `备份/tag_calfe_multihistory_*.log.bak_date20260803180251`。

##### 边定义与严格 total AR 的等价边界

设不含 row-local predicate 的基础公式为 \(B\)，每个 observation-key 对应当前 eager 实现的完整局部公式为 \(F_{o,k}\)。当前版本一次构造：

\[
\Phi_{eager}=B\land\bigwedge_{(o,k)}F_{o,k}
\]

CALFE 第 \(r\) 轮只包含已物化集合 \(M_r\)：

\[
\Phi_r=B\land\bigwedge_{(o,k)\in M_r}F_{o,k}
\]

每次 SAT 后，把模型中的已选 AR 图边拓扑排序成一个确定的严格总序扩展，并逐 key 直接检查原 \(F_{o,k}\)。发现违例时，不加入针对当前模型的无条件边，而是调用原 eager 实现使用的函数，加入完整 \(F_{o,k}\)：

- recorded source \(s\) 仍生成 `PR_WR(s,T,k)`，并对每个会改变 observation 的 later writer \(u\) 生成带原 guard 的 `PR_RW(T,u,k)`：\(AR(s,u)\Rightarrow AR(T,u)\)。
- absent result 仍使用原 `createKeyFrontier`、`selectionGuard`、selected `PR_WR/PR_RW`，并对每个 bad writer 加入原 blocking clause：\(\lnot Visible(b,T)\lor\bigvee_g(Visible(g,T)\land AR(b,g))\)。
- internal key 仍比较 predicate event 前的 latest-self 和 recorded source；不生成外部 predicate 边。
- writer 是否改变结果仍调用原 `writeChangesPredicateResult`，保留 membership、canonical contribution、bag、input provenance 和异常处理。

因此 `SEREdge` 的方向、类型、key、guard、target AR literal、blocking clause 内容和 `writeChangesPredicateResult` 判定没有改变。改变的是 L1 的生成时机和 L2 的每轮公式大小：最终成功轮不必物化所有未被候选 witness 违反的边，所以 Java graph 中可见的物理 predicate 边集合可能是 eager 集合的子集；这属于 CALFE 的操作语义改变，不能表述成“实现完全没变”。保持不变的是 L0 判定问题和每条被物化边的定义。

正确性依据如下：

- 若某轮 UNSAT，因为 \(\Phi_r\) 是 \(\Phi_{eager}\) 的子公式，完整 eager 公式也必然 UNSAT。
- 若重放没有违例，得到的具体总序 witness 满足所有 \(F_{o,k}\)，因此满足 \(\Phi_{eager}\)。
- 每次新增的都是 \(\Phi_{eager}\) 中原有的完整合取项；若 eager 存在模型，任何 refinement 都不会排除该模型。
- reader-key 数有限，且每个 key 最多物化一次，因此 refinement 有限终止。

MonoSAT 禁止首次 `solve()` 后新增图边变量。实现只在首次 solve 前为以后可能引用、且未被 known-order 常量折叠的事务 pair 创建正反两个 `directArEdge` 变量，不提前加入 XOR。某个局部公式真正物化时，仍由原 `ar()`/`ensureComparable()` 加入 \(AR(a,b)\oplus AR(b,a)\)。未被公式引用的 pair 保持偏序表示；`arGraph.acyclic()` 保证无环，而有限无环偏序必有严格总序扩展，`candidateArPositions()` 构造的正是该 witness。预声明本身不生成 PR_WR、PR_RW、guard 或 blocking clause。

专项测试覆盖：首次候选违反 recorded source 后物化原 PR_WR 并再次求解；known-visible matching writer 与 absent result 冲突时拒绝。完整 Gradle 回归执行 162 项、0 失败、2 项按既有配置跳过。最终源码 SHA-256 为 `c99025c6d8b8995c2b143cd46e0621c9e9251b3f98efd6db20085543ef5320a1`；1%/5% 原始日志保存于 `备份/tag_calfe_final_{1pct,5pct}.log.bak_date20260803165425`。

## 3. 当前执行路径与成本模型

当前主要流程如下：

~~~text
PRHIST JSONL
  -> PredicateHistoryLoader
  -> verifyInternalConsistency
  -> KnownGraph
  -> generateConstraintsSER
  -> pruning
  -> new SERSolverAR
       buildKnownOrder
       encodeKnownEdges
       encodeRemainingWwChoices
       encodeRwFromWrAndWw
       encodePredicateConstraints (register row-local checks)
       predeclareRowLocalArPairs (graph variables only)
       encodeDependencyEdges
       encodeStrictTotalOrder
  -> MonoSAT solve
  -> build one total AR extension
  -> replay every row-local reader-key
  -> materialize violated original local formulas
  -> repeat solve/replay until ACCEPT or UNSAT
~~~

可用下列符号描述成本：

- T：客户端事务数量。
- O：客户端操作数量。
- K：历史 key universe 大小。
- P：谓词读数量。
- R：全部谓词结果输入行数。
- W：写版本数量。
- Wk：单个 key 的外部 writer 数量。
- C：最终 SAT constraint 和 dependency edge 数量。

当前主要成本近似为：

~~~text
Loader memory         = O(R) 但每行有多份对象副本
Internal consistency = O(P × W + R)
KnownGraph            = O(P × K + R)
Predicate encoding    = O(P × K log K + R × Wk + absent-key candidates)
Edge deduplication    = 最坏接近 O(E²) 于同一事务对的 edge collection
MonoSAT               = O(C) 编码加实际 SAT/graph search
~~~

在当前数据上，P、R 和 P × K 同时约放大 4.6 至 4.9 倍，总时间也放大约 4.3 倍，说明谓词规模是主要成本驱动因素。

## 4. 根因一：Loader 和结果对象重复物化

### 4.1 当前行为

PredicateHistoryLoader 对每个事务执行 ObjectMapper.readTree，然后处理完整 JsonNode 树。对每个谓词结果，它会同时构造：

- ArrayList of PredResult。
- LinkedHashMap recordedInputs。
- ArrayList of QueryValue。
- RecordedQueryResult 内部复制后的 inputs。
- RecordedQueryResult 内部复制后的 values。
- valueMultiset。
- canonicalInputs。
- Event 构造时再次复制 predResults list。

相关代码：

- ../src/main/java/history/loaders/PredicateHistoryLoader.java
- ../src/main/java/history/query/RecordedQueryResult.java
- ../src/main/java/history/Event.java

5% 历史有 690.6 万个 inputs 和同量 values。即使每个逻辑行只增加几十字节对象开销，也会迅速达到数 GiB。

### 4.2 优化目标

在不改变 PRHIST 外部格式的前提下，减少内部重复表示：

1. Loader 校验完成后只保留求解真正需要的数据。
2. row-local 查询不重复保存可由 inputs 推导的 values。
3. 避免为同一个 input 同时保留 PredicateValue、QueryValue、PredResult 和多个 Map entry。
4. 避免每行事务先构造完整 JsonNode 再转内部对象。

### 4.3 建议方案

#### 方案 A：RecordedQueryResult 分层实现

保留通用查询的完整结果表示，同时增加 row-local compact result：

~~~text
RecordedQueryResult
  -> GeneralRecordedQueryResult
       inputs
       values
       valueMultiset
       canonicalInputs

  -> RowLocalRecordedQueryResult
       compact key ids
       compact version/value ids
       projection descriptor
       lazy canonical comparison
~~~

对于当前 KV 查询：

- select 为 k、value。
- distinct 为 false。
- 每个输出行由单个 input 行决定。

因此 values 可以从 inputs 和 QueryPlan 派生，不需要长期保存完整第二份结果。

#### 方案 B：Jackson streaming parser

使用 JsonParser 逐字段读取，不再为整个事务行构造 JsonNode tree。需要保留：

- transaction/session metadata。
- operation type。
- key/value。
- query plan。
- compact predicate input/result。

必须在 streaming 阶段完成字段完整性和非法 source metadata 校验。

#### 方案 C：key、transaction 和 version 整数化

在 loader 内建立：

~~~text
String key       -> int keyId
Transaction      -> int txnId
WriteRef         -> int writeId
PredicateValue   -> compact long 或 canonical value id
~~~

当前 KV value 是整数且全局唯一，最适合使用 primitive long。通用对象值继续走现有 QueryValue 路径。

### 4.4 风险

- result.values 对 JOIN、DISTINCT、对象投影仍不可省略。
- bag/multiset 语义不能被 Set 代替。
- compact KV 路径和通用查询路径必须由 QueryPlan capability 明确分流，不能按输入字段猜测。
- streaming loader 仍必须拒绝当前格式不接受的 source provenance 字段。

### 4.5 验收标准

- 所有现有 loader 和 structured query 测试 verdict 不变。
- 5% 历史 Loader 及框架阶段由约 25.4 秒降至 10 秒以内。
- 5% 历史进入 solver 前的存活堆降低至少 40%。
- 通用 JOIN/DISTINCT 历史继续使用完整结果表示。

## 5. 根因二：每个谓词都扫描并保存完整 key universe

### 5.1 内部一致性检查

verifier.Utils.checkPredicateRead 当前对每次谓词读遍历 writesByKeyValue.keySet，构造 coveredKeys，然后逐 key 查询本事务写位置。

问题包括：

- writesByKeyValue 的 key 是 key/value version pair，不是去重后的业务 key。
- 同一业务 key 有多个版本时会被重复访问。
- 每次检查都重新创建 HashSet、Pair 和查询临时对象。

建议：

1. 在 History 或校验上下文中预先构造按 scope 分组的唯一 keyId 列表。
2. 对当前单表 KV，所有 QueryPlan 共享同一有序 keyId 数组。
3. txnWrites 使用 txnId/keyId 二维稀疏索引或 packed long key，避免循环创建 Pair。
4. 相同 predicate identity 的重复读继承检查使用增量 changed-key 集合，不重新扫描所有 key。

目标复杂度：

~~~text
当前：O(P × W + R)
目标：O(P × Kscope + R)，并消除 version 重复与大部分临时对象
进一步目标：O(R + local-changed-keys + indexed predicate candidates)
~~~

### 5.2 PredicateObservation 的全量 Map

KnownGraph 优化前为每个 predicate observation 创建：

~~~text
Map<KeyType, PredicateReadType> predicateReadTypes
~~~

在 K=10000、P=1555 时，理论上要长期保存约 1555 万个 Map entry。实际上绝大多数 entry 只是重复表达默认 EXTERNAL 或整段 scope 已经 INTERNAL。

现已按以下优先级完成替换：

#### 方案 A：BitSet

~~~text
PredicateObservation
  scope
  BitSet internalKeys
~~~

keyId 连续时，1555 × 10000 bit 约 1.9 MiB，远低于 1555 万个 HashMap entry。

实现使用共享连续 keyId、covered BitSet 和惰性 `Map<KeyType, PredicateReadType>` 兼容视图，不再长期保存逐 key HashMap entry。

#### 方案 B：默认类型加稀疏例外

~~~text
defaultType = EXTERNAL 或 INTERNAL
exceptionKeyIds = IntSet
~~~

适用于大多数 key 类型相同、只有少量本地写例外的 observation。

RoaringBitmap、排序 `int[]` 和 BitSet 三种例外表示均完成同基线实测；最终保留默认类型加稀疏 BitSet 例外，具体数据见 2.5 节。

#### 方案 C：coverage epoch

同一事务内，一次覆盖完整 KV scope 的谓词读之后，后续谓词读可用 observation epoch 表示此前已覆盖，无需把全部 key 放入 predicateObservedKeys。

实现只在 covered key 数等于完整有限 key universe 时推进 epoch；部分 scope 继续使用 observed-key BitSet 逐 key 累积。epoch 生效后的 observation 直接表示为默认 `INTERNAL` 且无例外。

### 5.3 正确性约束

优化后必须保持当前定义：

- 当前事务此前任意谓词读覆盖过 key，则后续覆盖该 key 时为 INTERNAL。
- 当前事务此前写过 key，则谓词读该 key 时为 INTERNAL。
- 其他 covered key 为 EXTERNAL。
- scope 外 key 不参与该 observation。

不能把 predicate identity 错误地重新加入 KnownGraph external/internal 分类。

### 5.4 验收标准

- 现有 mixed INTERNAL/EXTERNAL 和 repeated predicate 测试全部通过。
- 5% 历史 PredicateObservation 的长期存储从 O(P × K 个对象) 降到 O(P × K bits) 或更低。
- KnownGraph 阶段由约 8.7 秒降至 3 秒以内。
- 5% 历史峰值 RSS 降至 6 GiB 以下，且不使用 swap。

## 6. 根因三：scope key 列表被重复筛选和排序

SERSolverAR.encodePredicateConstraints 优化前对每个 observation 执行：

1. 遍历 writesByKey.entrySet。
2. 调用 predicate.scope().covers。
3. 把 key 转为 String。
4. 使用 Comparator 排序。
5. 收集成新的 List。

基准历史中的所有 KV 查询都覆盖同一个 kv relation；优化前，1555 次谓词读会重复筛选和排序同一组 10000 个 key。

### 6.1 实施方案

SERSolverAR 初始化时先对完整 key universe 使用原 Comparator 排序一次：

~~~text
sortedWritesByKeyEntries
scopedWritesCache[stableScopeCacheKey]
~~~

`QueryScope.cacheKey()` 返回可选的稳定、不可变值 key。标准 relation scope 的 key 由以下内容组成：

~~~text
RelationScopeCacheKey
  immutable relations
  stable RelationResolver cache key
~~~

`canonicalStringKeys()` 和 `fixed(relation)` resolver 提供值语义 key，不依赖对象引用。自定义 QueryScope 或 resolver 无法安全声明等价性时返回 empty；该 scope 仍从全局已排序列表筛选，但不跨 observation 缓存结果。

对于单表 KV：

~~~text
scope kv -> 唯一共享的 sorted entry list
~~~

对于多表 relation 集合，不同 immutable relations 产生不同 cache key；筛选是在全局稳定顺序上进行，因此结果顺序与原先“先筛选再使用同一 Comparator 排序”一致。

### 6.2 验收标准

- 已完成：完整 key universe 只排序一次，相同稳定 scope 只筛选一次。
- 已完成：`String.valueOf` 和 Comparator 调用不再按 predicate count 增长。
- 已验证：1%/5% scope 列表 checksum 与旧算法完全一致；完整测试和 1% `ACCEPT` audit 通过。
- 本轮未重新采集 JFR；速率使用独立 JVM 目标子阶段中位数和完整 audit 指标记录。

## 7. 根因四：谓词边使用线性去重

SERSolverAR.addKnownPredicateEdge 当前从 KnownGraph 取出同一事务对的 Collection<Edge>，随后调用 existing.contains。

若 Collection 实际为 ArrayList，单次去重是 O(n)。同一事务对累计大量 key 级 PR_WR 或 PR_RW 边后，整体可能接近 O(n²)。

1% JFR execution sample 中：

- graph.Edge.equals 是第一大叶节点热点，1330/6888 个样本。
- ArrayList.contains 和 ArrayList.indexOf 出现在 2127 个 inclusive sample 中。
- 实时线程栈多次停在 addKnownPredicateEdge。

### 7.1 推荐实现

保留现有 graph 结构用于诊断，同时增加常数时间 membership index：

~~~text
Set<PredicateEdgeKey>

PredicateEdgeKey
  fromTxnId
  toTxnId
  edgeType
  keyId
~~~

流程：

1. 先对 Set 执行 add。
2. 返回 true 时才写入 KnownGraph 的诊断 collection。
3. 返回 false 时直接跳过。

或者把 KnownGraph 每个 endpoint pair 的 Collection 从 ArrayList 改成 LinkedHashSet，但需要审计所有调用点是否依赖：

- 插入顺序。
- 可重复 edge。
- 可变 collection。
- removeIf 和 diagnostic 输出。

独立 membership index 风险更低，适合作为第一批代码改动。

### 7.2 进一步压缩

SAT 逻辑真正需要的是事务间 AR 方向。多个不同 key 的：

~~~text
writerTxn -> readerTxn
~~~

可能对应同一个 AR literal。可以把：

- 逻辑 constraint 去重。
- 诊断 provenance 按 key 单独保留。

这样既不丢失错误解释，也不重复向 MonoSAT 提交相同事务方向。

### 7.3 验收标准

- addKnownPredicateEdge membership 变为均摊 O(1)。
- Edge.equals 不再是 JFR 前十热点。
- predicate edge 数和所有 verdict 与旧实现完全一致。
- 1% 历史 ONESHOT_SOLVE 至少下降 15%。

## 8. 根因五：相同 predicate/write 被重复求值

writeChangesPredicateResult 会分别对 source 和 later 调用 writeMatchesPredicate。writeMatchesPredicate 每次构造单行 MapVisibleState 并执行完整 QueryPlan。

同一个 predicate event 和同一个 WriteRef 可能在以下位置重复求值：

- absent-key badWrites 过滤。
- recorded source 与 later write 对比。
- frontier source 与所有 later writer 对比。
- predicate refinement。
- diagnostic predicate edge 派生。

### 8.1 第一层缓存：row contribution（已实施）

每个 `SERSolverAR` 实例现在维护：

~~~text
IdentityHashMap<WriteRef, int> writeRefIds
IdentityHashMap<QueryPlan, CompactRowMatchCache>
LinkedHashMap<(planId, writeRefId), CachedRowContribution> general LRU

CompactRowMatchCache
  computed BitSet
  matched BitSet
  invalid BitSet

RowContribution
  value multiset
  canonical inputs
~~~

`writeRefId` 是 solver 内 `graph.getAllWrites()` 的位置编号，只用作 BitSet 下标；它不替代业务 key/value。QueryPlan 使用对象身份作为缓存 key，避免 AST 文本相同但 scope 或 value adapter 不同的计划错误共享结果。

所有缓存都是 solver 实例字段，不是全局静态缓存，因此不会产生：

- 跨历史 write id 冲突。
- QueryPlan 生命周期泄漏。
- 并发 audit 相互污染。

### 8.2 第二层缓存：query kind 专用 contribution（已实施）

`QueryPlan` 在构造时从结构化 AST 编译可选的 `compiledRowMatcher`。只有 `isRowLocal()` 明确成立，并且 `compactResultProjection()` 明确声明单表、`distinct=false`、精确投影 `k,value` 时，SERSolverAR 才进入紧凑 matcher 路径。

当前 KV 查询编译为：

~~~text
TRUE        -> always match
EQ n        -> value == n
MOD m r     -> value % m == r
GT n        -> value > n
LT n        -> value < n
~~~

该路径直接对 `(key, value)` 求 membership，不创建 `MapVisibleState`、`QueryEvaluation`、结果 Map 或结果 List。分流完全依据 QueryPlan capability 和 AST 节点类型，不读取或匹配原始 SQL 字符串。无法编译的自定义节点安全回退到现有完整求值器。

### 8.3 通用 contribution 与语义保持

紧凑 `select k,value` 路径的 BitSet 只缓存 membership 和异常状态，因为匹配行的输出可由 input 与 projection descriptor 唯一派生。通用 row-local projection 不只缓存 boolean，而是缓存由完整单行 `QueryEvaluation` 规范化得到的 `RowContribution`，其中同时保留：

- projected value multiset，保留 `distinct=false` 的 bag 计数。
- canonical inputs，保留物理 source key 和规范化对象值。
- 无效求值状态，保持原有 `QueryException` 分支行为。

`writeChangesPredicateResult` 在通用 row-local 路径比较完整 canonical contribution；非 row-local、自定义 evaluator 和无法安全编译的计划继续使用原完整 evaluator，不改变 JOIN、DISTINCT 或对象投影语义。

### 8.4 内存上界

通用 contribution LRU 上限为 32768 项，达到上限后按访问顺序淘汰，不会增长为 `predicate count × write count`。当前 KV 历史全部进入 BitSet 路径，通用 LRU 不承载这些查询。

按保守的三个 BitSet 都扩展到全部 writeRef 估算，1% 的原始 bit payload 约 2.84 MiB，5% 约 13.47 MiB；实际 `invalid` BitSet 在无异常时不扩展。这远低于为每个 predicate/write 组合保存 HashMap、MapVisibleState 和 QueryEvaluation 对象的成本。5% 实测最大 RSS 下降 34.72%，没有 OOM。

### 8.5 验收标准

- 已完成：相同 QueryPlan 对象和 WriteRef 的 matcher/contribution 在 solver 实例内只计算一次。
- 已完成：当前 KV matcher 路径不再创建 MapVisibleState 和 QueryEvaluation。
- 已完成：writeChangesPredicateResult 复用 source/later contribution。
- 已验证：TRUE、EQ、MOD、GT、LT 逐值差分与完整 QueryPlan evaluator 一致；scope 排除与 QueryException 行为一致。
- 已验证：通用 computed projection 使用 canonical RowContribution，并覆盖相同投影但物理 input 变化的情况。
- 已验证：完整测试和 1% `ACCEPT` audit 通过。

## 9. 根因六：未使用谓词类型索引

即使完成缓存，逐谓词扫描 10000 个 key 仍是主要成本。当前生成器只产生五类简单 KV 谓词，可使用更强的索引。

### 9.1 EQ

建立：

~~~text
value -> WriteRef
~~~

由于当前紧凑历史要求 key/value 版本唯一，value equality 通常只命中一个写版本。不存在等值版本的 key 不需要执行完整 QueryPlan。

### 9.2 MOD

对常用模数建立：

~~~text
modulus -> remainder -> write/version ids
~~~

若模数种类很多，则按 QueryPlan 首次出现时惰性构建。

### 9.3 GT 和 LT

建立按 canonical numeric value 排序的 NavigableMap 或 primitive sorted array：

~~~text
value -> write/version ids
~~~

通过 range query 直接取得可能匹配的版本。

### 9.4 TRUE

TRUE 不需要执行 predicate evaluator。所有行都匹配，但仍要处理：

- latest-visible source。
- 同 key 后续写导致投影值变化。
- reader 前后 AR 关系。

TRUE 的优化重点不是跳过语义，而是跳过 10000 次相同的 matches 计算，并复用 key/frontier 模板。

### 9.5 从 key 扫描转为 change 扫描

对 row-local predicate，约束只在某个写使该 key 的贡献发生变化时有意义：

~~~text
source contribution != later contribution
~~~

可以先按每个 key 的写版本序列计算 contribution change points，再只为 change point 建立 PR_RW。

目标复杂度从：

~~~text
O(P × K × Wk)
~~~

接近变为：

~~~text
O(unique query plans × W + relevant observations × change points)
~~~

### 9.6 风险

- 两个值都匹配但 select 投影不同，仍属于结果变化。
- 对象值和多列投影不能只比较 predicate membership。
- JOIN、DISTINCT、aggregate 必须继续走 general lazy snapshot 路径。
- 范围索引必须使用 QueryValue 的正式比较语义，不能依赖字符串排序。

## 10. SAT 与 dependency encoding 优化

完成前述 Java 热点优化后，再处理公式规模。

### 10.1 逻辑边和诊断 provenance 分离

当前 SEREdge 同时承担：

- SAT implication 的逻辑目标。
- edge type/key 的诊断来源。

多个 key 最终可能要求相同 ar(from,to)。建议：

~~~text
LogicalDependency
  guard
  arFrom
  arTo

DiagnosticProvenance
  edgeType
  keyId
  observationId
~~~

逻辑层按 guard/AR target 去重，诊断层保留全部必要来源。

### 10.2 批量 JNI 提交

MonoSAT Java binding 的逐 literal/逐 clause 调用存在 JNI 边界成本。可在 Java 侧形成紧凑 clause buffer，再批量提交：

- known AR edges。
- guarded implications。
- predicate blocking clauses。

必须遵守 MonoSAT JNI 对 clause 大小和 native buffer 的限制。

### 10.3 strict total order 按需性复核

当前实现已经按公式涉及的事务对创建 AR literal，不应退回全事务对 O(T²) 创建。后续优化需要增加统计，确认：

- 唯一 AR pair 数。
- 常量化 pair 数。
- ensureComparable 调用数。
- 实际创建的 graph edge literal 数。

### 10.4 refinement 统计

通用查询 lazy refinement 应记录：

- MonoSAT solve 调用次数。
- predicate snapshot 检查次数。
- 新增 no-good clause 数。
- 每轮新增 clause 大小。

如果当前 KV row-local 快路径没有 refinement，统计应明确显示为零。

## 11. 观测能力必须先补齐

`ONESHOT_SOLVE` 原先把构造、native solve 和 refinement 合并，容易把 Java/JNI 编码误判为 SAT 搜索慢。现已保留该总指标用于历史对比，并增加以下嵌套阶段：

~~~text
ONESHOT_SOLVE
  SER_AR_ENCODE
    SER_AR_ENCODE_SETUP
    SER_AR_ENCODE_KNOWN_EDGES
    SER_AR_ENCODE_WW
    SER_AR_ENCODE_RW
    SER_AR_ENCODE_PREDICATE
    SER_AR_ENCODE_DEPENDENCIES
    SER_AR_ENCODE_TOTAL_ORDER
  SER_AR_SOLVE
    SER_MONOSAT_SOLVE
    SER_AR_PREDICATE_REFINEMENT
    SER_AR_CONFLICT_EXTRACTION（仅 REJECT 路径）
~~~

计时器包裹原有函数调用，不改变编码顺序、约束、solve 循环或 refinement 条件。MonoSAT 多轮 solve 和多轮 refinement 使用 Profiler 的累计时间；当前 Profiler 为毫秒粒度，因此 `0 ms` 表示本次累计不足 1 ms。

### 11.1 1%/5% 实际拆分

测量使用与 RC-001 相同的历史和 `-Xms8g -Xmx8g`，两个历史 verdict 均保持 `ACCEPT`。

| 阶段 | 1% / 320 PR | 占 ONESHOT_SOLVE | 5% / 1555 PR | 占 ONESHOT_SOLVE |
|---|---:|---:|---:|---:|
| ONESHOT_SOLVE | 36.898 s | 100.00% | 142.655 s | 100.00% |
| SER_AR_ENCODE | 35.941 s | 97.41% | 129.363 s | 90.68% |
| SER_AR_ENCODE_PREDICATE | 35.649 s | 96.61% | 128.942 s | 90.39% |
| SER_AR_SOLVE | 0.957 s | 2.59% | 13.292 s | 9.32% |
| SER_MONOSAT_SOLVE | 0.957 s | 2.59% | 13.292 s | 9.32% |
| SER_AR_PREDICATE_REFINEMENT | < 1 ms | < 0.01% | < 1 ms | < 0.01% |

predicate 编码分别占 AR 编码时间的 99.19% 和 99.67%，说明当前核心瓶颈已经定位为 `encodePredicateConstraints`，而不是 MonoSAT SAT 搜索或 refinement。

| AR 编码子阶段 | 1% | 5% |
|---|---:|---:|
| SETUP | 140 ms | 115 ms |
| KNOWN_EDGES | 18 ms | 25 ms |
| WW | 6 ms | 8 ms |
| RW | 26 ms | 34 ms |
| PREDICATE | 35,649 ms | 128,942 ms |
| DEPENDENCIES | 76 ms | 208 ms |
| TOTAL_ORDER | < 1 ms | < 1 ms |

观测改动前后的同一 1% 历史总量为：`ONESHOT_SOLVE` 37.255 秒 → 36.898 秒（表观下降 0.96%），完整 audit 44.056 秒 → 43.607 秒（表观下降 1.02%）。该差异属于单次运行波动，不作为性能优化收益申报。5% 完整 audit 为 168.945 秒，最大 RSS 6,521,396 KiB，进程级 swap 为 0。

### 11.2 Predicate 内部子阶段与计数

现已在 observation 粒度累计以下时间，避免在千万级 key 热循环内反复进入同步 Profiler：

~~~text
SER_PRED_SOURCE_INDEX
SER_PRED_SCOPE_LOOKUP
SER_PRED_SNAPSHOT_VALIDATE
SER_PRED_ROW_LOCAL_KEY_SCAN
SER_PRED_GENERAL_KEY_SCAN
~~~

详细计数由 `--solver-stats` 显式启用；普通 audit 只保留 observation 粒度计时，不执行 key/frontier/dependency 热循环计数。计数器属于当前 SERSolverAR 实例，构造结束时一次性汇总到 Profiler。

| Predicate 子阶段 | 1% / 320 PR | 占 predicate 编码 | 5% / 1555 PR | 占 predicate 编码 |
|---|---:|---:|---:|---:|
| SER_AR_ENCODE_PREDICATE | 28.523 s | 100.00% | 133.449 s | 100.00% |
| ROW_LOCAL_KEY_SCAN | 26.386 s | 92.51% | 124.357 s | 93.19% |
| SNAPSHOT_VALIDATE | 2.023 s | 7.09% | 8.652 s | 6.48% |
| SOURCE_INDEX | 0.089 s | 0.31% | 0.396 s | 0.30% |
| SCOPE_LOOKUP | 0.019 s | 0.07% | 0.034 s | 0.03% |
| GENERAL_KEY_SCAN | < 1 ms | < 0.01% | < 1 ms | < 0.01% |

单次运行绝对耗时受 GC、native 分配和系统负载影响，因此本表用于阶段占比，不把与上一轮的绝对差异申报为性能收益。

| 计数 | 1% | 5% |
|---|---:|---:|
| observations / row-local encoded / fallback | 320 / 320 / 0 | 1,555 / 1,555 / 0 |
| result sources | 1,487,070 | 6,905,812 |
| scoped key visits | 3,200,000 | 15,550,000 |
| INTERNAL / EXTERNAL key | 221,095 / 2,978,905 | 4,803,187 / 10,746,813 |
| latest-writer lookups | 3,200,000 | 15,550,000 |
| latest-writer input writes | 7,937,600 | 37,662,100 |
| frontiers / candidates | 1,571,721 / 2,712,381 | 5,981,922 / 10,332,100 |
| beforeWrite calls | 7,165,608 | 25,815,365 |
| dependency attempts | 7,501,728 | 26,664,266 |
| dependency skipped / queued | 7,408,428 / 93,264 | 26,359,708 / 304,503 |
| blocking clauses / literals | 248,509 / 588,361 | 996,825 / 2,380,131 |

所有当前 KV observation 都走 row-local 路径，没有 general fallback。每个 scoped key 都执行一次 latest-writer lookup；5% 中 1555 万次 key visit 扫描 3766 万个 write version。dependency add 尝试中约 98.86% 最终被常量 guard、已满足 target 或重复项跳过。所以下一步应首先减少 row-local 全 key 扫描、latest-writer 临时结构和可提前判定无效的 dependency 尝试；source index 和 scope lookup 已不是优先瓶颈。

### 11.3 尚未补齐的统计

当前 Main 中 `solver-timeout-seconds` 参数仍没有传递到 solver。`solver-stats` 已输出 predicate 编码计数和 backend 名称，但以下 CNF/native 统计仍未补齐：

~~~text
transactions
unique AR pairs
MonoSAT variables
clauses
graph nodes
graph edges
solve calls
refinement clauses
~~~

solver timeout 应分别定义：

- encoding timeout。
- 单次 backend solve timeout。
- entire audit deadline。

如果 MonoSAT Java API不支持可靠的 native timeout，应由 audit runner 保留进程级 timeout 作为最终保护。

## 12. 不建议优先做的事情

### 12.1 不要先优化 pruning

当前两组历史的 pruning 都低于 0.5 秒，并解决约 98.6% 至 98.8% 的 WW choices。它已经有效且不是瓶颈。

### 12.2 不要先关闭 pruning

关闭 pruning 会把一万多个本可提前确定的 WW choices 推给 SAT，预计只会放大公式。

### 12.3 不要只增加 heap

当前物理内存不足以安全承载更大的 Java heap、MonoSAT native memory、文件页缓存和系统服务。扩大 heap 可能减少部分 GC，却会增加 swap 或触发系统 OOM killer。

### 12.4 不要先并行化全部编码

当前主线程热点包含大量共享 graph、solver 和去重状态。直接 parallelStream 可能引入：

- MonoSAT JNI 线程安全问题。
- graph mutation 竞争。
- cache 锁竞争。
- 更高的临时对象峰值。
- 非确定性诊断顺序。

应先降低算法复杂度和对象量，再只并行化纯函数式 contribution 预计算。

### 12.5 不要丢弃未返回 key

谓词结果为空不代表没有约束。未返回 key 的 latest-visible writer 必须不产生匹配结果，否则历史无法被该 AR 解释。

## 13. 分阶段实施计划

### 阶段 P0：建立可测量基线

修改范围：

- src/main/java/Main.java
- src/main/java/verifier/SERVerifier.java
- src/main/java/verifier/SERSolverAR.java
- src/main/java/util/Profiler.java

任务：

1. 拆分 encoding 和 native solve 时间。
2. 输出 predicate/key/evaluation/edge/AR/clause 统计。
3. 使 solver-timeout-seconds 真正生效，或明确拒绝不支持的 backend timeout。
4. 固化 0%、1%、5% 和 10% benchmark 命令。

验收：

- 计时总和与 ENTIRE_EXPERIMENT 基本一致。
- 每次 benchmark 输出稳定的机器可解析字段。
- 不改变任何 verdict。

### 阶段 P1：低风险 CPU 热点

修改范围：

- src/main/java/verifier/SERSolverAR.java
- src/main/java/graph/KnownGraph.java
- 对应 solver 单元测试和 differential tests

任务：

1. 增加 known predicate edge membership Set。
2. 缓存 predicate/write RowContribution。
3. 复用 RelationResolver。
4. 预构造和复用 sorted scoped entries。

验收：

- 1% ONESHOT_SOLVE 下降至少 30%。
- 5% ONESHOT_SOLVE 下降至少 30%。
- Edge.equals、ArrayList.contains 不再是主要热点。
- predicate evaluator cache hit rate可观测。

### 阶段 P2：长期存活内存压缩

修改范围：

- src/main/java/graph/KnownGraph.java
- src/main/java/history/loaders/PredicateHistoryLoader.java
- src/main/java/history/query/RecordedQueryResult.java
- src/main/java/history/Event.java
- 对应 loader/query/integration tests

任务：

1. predicateReadTypes 改为 BitSet 或默认类型加例外。
2. key/txn/write 建立整数 ID。
3. row-local result 使用 compact representation。
4. 评估 streaming parser。

验收：

- 5% 峰值 RSS 低于 5 GiB。
- 5% 全程不使用 swap。
- 10% 在 Xmx8g 下能够完成加载和 KnownGraph 构造。
- 通用多表/对象查询不退化。

### 阶段 P3：谓词专用索引

修改范围：

- src/main/java/history/query/QueryPlan.java
- src/main/java/history/query/QueryAst.java
- src/main/java/verifier/SERSolverAR.java
- 新增或扩展 query capability tests

任务：

1. QueryPlan 暴露正式的 row contribution evaluator。
2. TRUE、EQ、MOD、GT、LT 走 typed fast path。
3. 建立 value/range/mod 索引。
4. 按 contribution change point 生成 PR_RW。

验收：

- 5% 总时间进入 120 秒以内作为阶段目标。
- 1% 总时间进入 30 秒以内作为阶段目标。
- general query 继续走旧语义路径。
- exhaustive/differential oracle 无 verdict 差异。

### 阶段 P4：公式和 JNI 压缩

任务：

1. 逻辑 AR dependency 与诊断 provenance 分离。
2. 按 guard/target 去重 implication。
3. 批量提交 clauses 和 graph edges。
4. 对 refinement 加入统计和上限保护。

验收：

- MonoSAT variables、clauses 和 JNI calls 有明确下降。
- ACCEPT/REJECT witness 保持可解释。
- 不牺牲 strict total AR 和 predicate completeness。

## 14. 正确性验证策略

性能优化不能只比较运行时间，必须证明 verdict 等价。

### 14.1 单元测试

至少覆盖：

- point read source 唯一性。
- initial bottom write。
- transaction-local read-your-writes。
- repeated identical predicate inheritance。
- different predicate identity 的 INTERNAL 分类。
- empty predicate result。
- TRUE 全表结果。
- EQ 命中和未命中。
- MOD、GT、LT。
- source/later 都匹配但投影值变化。
- source 匹配、later 不匹配。
- source 不匹配、later 匹配。
- 同事务对多个 key 的 predicate edge 去重。
- 多写版本同 key frontier。
- JOIN、DISTINCT 和对象值走 general path。

### 14.2 Differential test

对小历史同时运行：

1. 当前实现。
2. 优化实现。
3. exhaustive serial-order oracle。

比较：

- verdict。
- 可接受历史的一个合法 AR。
- 拒绝历史的冲突性质。
- predicate result evaluation。

所有随机 seed 必须固定并记录。

### 14.3 大历史回归

固定使用：

- 0% 历史：确认无谓词路径不退化。
- 1% 历史：快速性能门禁。
- 5% 历史：内存和规模门禁。
- 10% 历史：OOM 改善门禁。

每次记录：

~~~text
git commit
jar sha256
JDK version
heap
CPU count
physical memory
history sha256
verdict
phase timings
peak heap
peak RSS
GC count/time
swap delta
solver variables/clauses
~~~

### 14.4 等价性红线

出现以下任一情况不得合并：

- 旧实现 ACCEPT、新实现 REJECT，或反向差异，且无法由旧 bug 证明解释。
- predicate result bag semantics 变化。
- INTERNAL/EXTERNAL 分类变化。
- bottom transaction 顺序变化。
- compact history 的 source 唯一解析变化。
- 通用 JOIN/DISTINCT 被错误送入 KV fast path。
- 为提速跳过未返回 key 的约束。

## 15. 推荐的第一批实际改动

第一批建议严格控制在低风险范围：

1. 增加细粒度 Profiler tag。
2. 增加 predicate edge membership Set。
3. 增加 solver 实例级 RowContribution cache。
4. 缓存 sorted scoped entry list。
5. 添加对应计数器和回归测试。

第一批不修改：

- PRHIST 格式。
- Loader 数据结构。
- PredicateObservation 表示。
- QueryPlan 通用语义。
- MonoSAT backend。

这样可以先用较小改动验证 CPU 热点判断，并为后续内存结构改造建立可靠基线。

## 16. 最终目标

建议把最终性能目标定义为：

| 指标 | 当前 5% | 第一阶段目标 | 完整优化目标 |
|---|---:|---:|---:|
| 总时间 | 416 s | 250 s以内 | 120 s以内 |
| ONESHOT_SOLVE | 349 s | 220 s以内 | 90 s以内 |
| 峰值 RSS | 8.48 GiB | 7 GiB以内 | 5 GiB以内 |
| swap | 约 2.5 GiB | 低于 0.5 GiB | 0 |
| 10% Xmx8g | OOM | 至少完成加载/编码 | 完整检测 |
| verdict | ACCEPT | 必须一致 | 必须一致 |

这些目标需要用关闭 JFR 后的正式 benchmark 复核；JFR 只用于热点定位，不应作为最终发布性能数字。

最关键的判断标准不是某个单点优化是否更快，而是：

> 在保持严格 total AR、latest-visible、predicate completeness、bag result 和通用查询语义不变的前提下，使成本从“每个谓词物化和扫描整个世界”转变为“只处理该谓词真正相关的版本变化和唯一逻辑依赖”。

## 17. 全部现有 KV 历史的 CALFE 审计（2026-08-04）

本节清点 `predicateHistories/kvpredicate` 中全部 22 份当前历史，其中 21 份已有 CALFE 运行，20% control 历史尚未运行检测器。实现版本为 `SERSolverAR.java` SHA-256 `c99025c6d8b8995c2b143cd46e0621c9e9251b3f98efd6db20085543ef5320a1`。本轮新增运行使用单独 JVM、Java 11、`-Xmx5g`、`--solver-stats` 和 900 秒 timeout；不与编译或其他 solver 并行。普通 5% 四历史及早期 RR 三历史复用保留的同 SHA 原始 CALFE 日志；其中普通 5% 四历史的旧运行使用 8 GiB JVM，故 RSS 不与本轮 5 GiB 结果直接比较。

| 组别 | 历史数 | verdict | wall time（秒） | 备注 |
|---|---:|---|---:|---|
| 普通 KV，0% predicate | 1 | ACCEPT | 2.14 | 无谓词基线 |
| 普通 KV，1% predicate | 1 | ACCEPT | 20.16 | 3 轮 replay，9.60M key visits |
| 普通 KV，5% predicate | 4 | ACCEPT | 58.852、61.511、60.885、62.518 | 已有多历史 CALFE 原始日志 |
| 普通 KV，10% predicate | 1 | ACCEPT | 134.06 | 3 轮 replay，89.19M key visits |
| 旧 RR write-skew | 3 | REJECT | 75.994、71.727、71.687 | pruning 阶段即出现无关 WW/RW 冲突 |
| 完整 15-op injected v2，5% | 3 | REJECT | 434.03、509.06、190.88 | 2 轮 refinement；目标 `k0/k1` 谓词环 |
| 完整 15-op control，2% | 1 | ACCEPT | 224.62 | 1 轮 replay |
| 完整 15-op injected，2% | 1 | REJECT | 320.37 | 2 轮 replay、2 个 materialized key |
| 完整 15-op control，1% | 1 | ACCEPT | 552.82 | 1 轮 replay |
| 完整 15-op injected，1% | 1 | REJECT | 322.19 | 2 轮 replay、2 个 materialized key |
| 完整 15-op injected，10% | 1 | REJECT | 233.54 | 2 轮 replay、2 个 materialized key |
| 完整 15-op injected，20% | 1 | TIMEOUT | >900 | 900 秒 timeout，未产生 verdict |
| 完整 15-op control，20% | 1 | 未运行 | — | 尚无 detector 日志 |
| 最小 causal control / injected | 2 | ACCEPT / REJECT | 0.223 / 0.218（内部 audit） | 仅验证两条 PR_RW 边的语义 |

22 份历史中，20 份得到终态 verdict：10 ACCEPT、10 REJECT；1 份 20% injected 历史在 900 秒后 timeout，1 份 20% control 历史尚未运行。新补测日志保存在对应 `hist-00000/calfe_xmx5g.log`，既有多历史和 RR 的来源分别为 `备份/tag_calfe_multihistory_h*_calfe.log.bak_date20260803180251` 与 `备份/tag_calfe_anomaly_h*_calfe.log.bak_date20260803204049`。

### 耗时分析

普通随机 ACCEPT 历史随谓词比例增加呈可解释的增长：0% 为 2.14 秒、1% 为 20.16 秒、5% 为约 61 秒、10% 为 134.06 秒。10% 的初始 predicate 编码为 27.656 秒，replay 扫描 89.19M reader-key；这里初始编码与 scope 扫描均可见。

完整 write-skew 注入历史不服从单调的“predicate 比例越高越慢”关系。1% 的精确 A/B 对照中，两份历史具有完全相同的 2,000 事务、30,000 操作、362 predicate reads、14,108 point reads 和 15,530 writes；唯一业务差异是右侧核心写 `k2`（control）或 `k0`（injected）。但 control ACCEPT 为 552.82 秒，injected REJECT 为 322.19 秒。前者 MonoSAT 为 543.730 秒，后者为 290.839 秒；因此主导因素是严格 total AR 的可满足性搜索路径，而不是初始谓词编码（分别仅 0.119 与 0.296 秒）或 replay。

5% injected v2 的三次 REJECT 为 190.88--509.06 秒，均只在两轮 replay 中物化 2 个 key。这说明 CALFE 已有效避免 eager 的全量 predicate 公式物化，但尚未消除候选 AR 的 MonoSAT 搜索及空结果大 scope 的重复验证。旧 RR 组约 72--76 秒的快速 REJECT 不可当作同类优化收益：其冲突核来自随机 `kv:3201` WW/RW 约束，未进入目标谓词环的 refinement 路径。

#### 完整 write-skew injected 比率明细

以下历史均为 2,000 个事务、30,000 个操作和 10,000 个初始 key。名义比例来自生成参数，实际占比按 `predicate_reads / operations` 计算；5% 包含三份独立历史。

| 名义谓词比率 | 样本 | 谓词读 | 实际占比 | 点读 | 写 | known edges | 未决 WW | conditional AR implications |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1% | v4_01 | 362 | 1.21% | 14,108 | 15,530 | 34,509 | 10,071 | 127,486 |
| 2% | v3_01 | 502 | 1.67% | 15,114 | 14,384 | 33,631 | 9,385 | 118,624 |
| 5% | v2_01 | 1,520 | 5.07% | 14,828 | 13,652 | 29,744 | 10,013 | 128,035 |
| 5% | v2_02 | 1,618 | 5.39% | 14,622 | 13,760 | 30,787 | 7,836 | 103,216 |
| 5% | v2_03 | 1,422 | 4.74% | 14,854 | 13,724 | 26,606 | 9,666 | 123,396 |
| 10% | v5_01 | 2,480 | 8.27% | 13,038 | 14,482 | 37,754 | 6,945 | 83,007 |
| 20% | v6_01 | 6,352 | 21.17% | 11,400 | 12,248 | 25,178 | — | — |

20% 在 profiler 汇总前 timeout，因此该行只记录 manifest 操作数和 pruning 后 `graphA + graphB = 20,434 + 4,744 = 25,178` 条 known edges，不推断未输出的 WW 与 conditional AR 计数。

| 名义谓词比率 | 样本 | wall time（秒） | MonoSAT（秒） | 初始 predicate 编码（秒） | replay（秒） | replay key visits | materialized keys | 最大 RSS（KiB） | 结果 |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---|
| 1% | v4_01 | 322.19 | 290.839 | 0.296 | 5.514 | 7.24M | 2 | 1,192,832 | REJECT |
| 2% | v3_01 | 320.37 | 289.090 | 0.193 | 6.379 | 10.04M | 2 | 1,427,540 | REJECT |
| 5% | v2_01 | 434.03 | 372.747 | 0.534 | 16.767 | 30.40M | 2 | 2,086,000 | REJECT |
| 5% | v2_02 | 509.06 | 411.077 | 1.070 | 29.000 | 32.36M | 2 | 2,878,544 | REJECT |
| 5% | v2_03 | 190.88 | 130.779 | 0.413 | 15.710 | 28.44M | 2 | 2,884,548 | REJECT |
| 10% | v5_01 | 233.54 | 154.910 | 0.738 | 18.803 | 49.60M | 2 | 2,144,336 | REJECT |
| 20% | v6_01 | 901.41 | — | — | — | — | — | 3,417,196 | TIMEOUT |

20% 的 901.41 秒是 timeout 命令返回的 wall time，不是完整检测耗时。该进程在 strict total AR 求解期间被 timeout 终止，没有输出 MonoSAT、predicate 编码、replay 或 materialized key 的阶段汇总。

| 名义谓词比率 | 样本数 | wall time 范围（秒） | wall time 中位数（秒） | MonoSAT 范围（秒） | replay key visits | materialized keys |
|---:|---:|---:|---:|---:|---:|---:|
| 1% | 1 | 322.19 | 322.19 | 290.839 | 7.24M | 2 |
| 2% | 1 | 320.37 | 320.37 | 289.090 | 10.04M | 2 |
| 5% | 3 | 190.88--509.06 | 434.03 | 130.779--411.077 | 28.44M--32.36M | 2 |
| 10% | 1 | 233.54 | 233.54 | 154.910 | 49.60M | 2 |
| 20% | 1 | >900（timeout） | — | — | — | — |

谓词比例上升时，谓词 observation 和 replay 工作量确实增加，但固定的 30,000 个操作意味着谓词读会替换部分点读或写，WW、WR/RW 和 conditional AR 规模会同时变化。CALFE 又只对候选 AR 中实际不匹配的 reader-key 物化公式；所有已完成 injected 历史最终均只物化目标异常的 2 个 key。当前总耗时主要受 MonoSAT 搜索路径影响：同为 5% 的三份历史 wall time 已相差 2.67 倍，且 20% 历史最终 timeout，因此这些独立随机历史不支持“谓词比例越高，总耗时越少”的单调结论。

## 18. 当前阶段总结

### 18.1 PolySI 紧凑型编码已生效，但不代表 WW 会消失

当前 `SERVerifier` 确实使用 PolySI 紧凑型约束生成：同一无序事务对在多个 key 上产生的重复 WW/RW 选择会被合并为一个约束。以 5% injected v2_01 为例：

| 阶段 | WW 约束数 | 说明 |
|---|---:|---|
| 按 key 展开（根据历史推算） | 约 149,237 | 每个 key 分别产生 writer pair |
| PolySI 紧凑合并后 | 26,963 | 约减少 81.9%，缩小 5.53 倍 |
| Java pruning 后未决 | 10,013 | 剩余方向仍需由求解器决定 |

因此紧凑编码没有失效。26,963 个紧凑约束中，24,963 个对应真正不同的 client writer 事务对，另有 2,000 个 bottom-to-writer 事务对。不同事务对的 WW 方向可以不同，不能在保持语义正确的前提下继续合并。紧凑编码解决的是“重复表示”，不是“确定 WW 方向”。

### 18.2 5% 普通 KV 与 injected 差距的主因是历史结构

普通 5% 历史的 14,220 次写分布在 7,578 个 key 上，单 key 最多 7 个 writer，紧凑 WW 为 12,109。injected v2_01 的 13,652 次写却只分布在 670 个 key 上，其中 648 个 key 各有 20 个 writer，紧凑 WW 增至 26,963。

根因不是 `101/102/103` 中某个 seed 特别差，而是设置全局 `RANDOM_SEED` 后，各 worker 使用了相同 seed 初始化自己的 RNG，导致 20 个 session 的普通事务高度同步地选择相同 key。更换 seed 值会改变具体历史和剪枝幅度，但不会消除这种结构性相关。同比率 control/injected 对照的初始 WW 与 conditional AR 相同，说明异常核心中 `k2`/`k0` 的单个写目标差异不是 WW 暴增的主因。Repeatable Read 下事务中止更少、已知先序边更少，是剪枝变弱的另一个次要因素。

### 18.3 eager 与 CALFE 使用相同剪枝结果

WW 生成和 Java pruning 均发生在 `SERSolverAR` 构造之前，因此对同一份历史，eager 和 CALFE 的剪枝效果一致。5% injected v2_01 的两类日志均为 4 轮剪枝、解决 16,950 个约束，剩余 10,013 个 WW。eager 的现有日志在剪枝后终止，没有终态 verdict 和完整耗时，因此不能据此宣称 CALFE 在 injected 历史上慢于 eager。

当前紧凑/CALFE 方案已避免 eager 对全部谓词公式的预先物化，并在普通 5% ACCEPT 历史上将平均总耗时由 178.004 秒降至 60.941 秒（加速 2.921 倍），predicate 编码平均加速 12.234 倍。但对高冲突、大量未决 WW 的 REJECT/TIMEOUT 历史，成本会转移到 strict total AR 的 MonoSAT 搜索。所以更准确的结论是：紧凑数据表示已解决全量物化带来的主要内存问题，并显著改善了普通历史耗时；但当前求解路径对高 WW 历史的耗时稳定性仍不足，不应笼统地归结为“紧凑编码表现不好”。

### 18.4 20% timeout 不表示谓词比例与耗时单调相关

固定 30,000 个操作时，谓词读增加会同时减少点读或写，因此谓词约束、WW 和已知边会一起变化。CALFE 又仅物化候选 AR 下真正不匹配的 reader-key，总耗时主要由 SAT 实例结构和搜索路径决定，本来就不具备单调性。同为 5% 的三份 injected 历史已在 190.88--509.06 秒之间波动；20% injected 和后续补测的 20% control 都在 900 秒时未完成。这说明 timeout 不能单独归因于谓词读更多，也不能单独归因于异常注入。

### 18.5 后续对比的必要前提

后续必须先统一 Java 版本、JVM 堆上限、cgroup 内存/swap、timeout、数据规模、操作数、隔离级别和运行时系统负载。历史生成应保留可复现的全局 seed，但为每个 worker 派生不同的确定性 seed，避免 session 之间共用同一随机序列。只有在同一份历史或严格配对的 none/control/injected 历史上，才能有效对比 eager/CALFE、异常注入和谓词比率的影响。
