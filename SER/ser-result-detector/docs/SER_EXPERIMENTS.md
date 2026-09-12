# SER 结构优化与剪枝实验

## 冻结边界

以下 correctness-critical 模块冻结；除修复已确认 bug 外，不再重构：

- `KnownGraph`
- WW/RW constraint semantics
- predicate latest-visible semantics
- GMWR obligation semantics
- `PrecedenceOracle`
- serialization graph encoding

实验所需的生产代码改动仅为只读计数：`PrecedenceOracle` 记录关系加入、闭包更新和成环检查次数；`SERSolverAR` 发布 MonoSAT 图规模、propagation/conflict 数。计数器不创建约束、不选择分支，也不改变求解控制流。

## Runner

统一入口是：

```bash
python3 tools/run_ser_ablation.py HISTORY_ROOT --suite pruning
```

三个 suite：

| suite | 配置 | 用途 |
| --- | --- | --- |
| `pruning` | `NONE → REACHABILITY → GMWR → GMWR_WWFeedback` | WW reachability 与 GMWR 剪枝消融 |
| `eager-gmwr` | `REACHABILITY → GMWR` | 固定其余公共开关，比较相同 predicate semantics 的编码成本 |
| `structure` | `REACHABILITY` | 对同一配置比较外部提供的实现 variant |

结构对比不把旧实现放回主源码。旧 dual-graph 版本单独编译到外部目录后，通过 classpath 前缀运行；第一个 variant 是基线：

```bash
python3 tools/run_ser_ablation.py HISTORY_ROOT \
  --suite structure \
  --variant dual=/tmp/ser-dual-baseline/classes \
  --variant single
```

默认每个 history 重复 3 次、单次硬超时 180 秒。12 GB 机器默认使用 `-Xmx5g`，每 200 ms 检查系统 `MemAvailable`；低于 2048 MiB 时只终止当前 JVM并记录 `MEMORY_GUARD`，已完成行仍保存在 `raw.csv`。`--xmx`、`--min-available-memory-mb`、`--post-verdict-grace-seconds`、`--repeats`、`--timeout-seconds`、`--limit` 和重复的 `--config` 可控制资源与实验规模。runner 始终串行执行 JVM，不并发运行多个 checker。不同配置/variant 的运行顺序会轮换，减少固定顺序造成的缓存偏差。

## 指标定义

| 输出列 | 定义 |
| --- | --- |
| `initial_candidates` | 初始 WW candidate 与 GMWR obligation 数之和 |
| `deleted_candidates` | 初始 candidate 减去预处理后 residual candidate |
| `forced_orders` | reachability、GMWR 和 WW feedback 推导出的 forced order 总数 |
| `residual_constraints` | residual WW constraint 与 residual GMWR constraint 总数 |
| `preprocessing_ms` | known precedence graph、reachability pruning、GMWR build/reduction 和 WW feedback 时间之和 |
| `SER_AR_ENCODE` | 整个 MonoSAT encoding 时间 |
| `SER_MONOSAT_SOLVE` | MonoSAT 求解时间 |
| `ENTIRE_EXPERIMENT` | checker 端到端时间 |
| `peak_rss_mb` | GNU `time -v` 记录的进程峰值 RSS；不是 JVM heap 上限 |
| `SER_PROP_MONOSAT_GRAPH_EDGES_COUNT` | 实际创建的 MonoSAT graph edge 数；dual variant 为两张图之和 |
| `SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT` | 进入求解器的变量数 |
| `SER_PRECEDENCE_CLOSURE_BUILDS_COUNT` | audit 内构造 precedence closure/oracle 的实例次数 |
| `SER_PRECEDENCE_CLOSURE_UPDATES_COUNT` | 接受的非冗余 precedence relation 更新次数 |
| `SER_PROP_MONOSAT_PROPAGATIONS_COUNT` | MonoSAT 报告的 propagation 数 |
| `timeout_rate` | 同一 history/variant/config 下 timeout 次数除以总尝试次数；不以 solver time 代替 |

`preprocessing_ms` 的子阶段位于 `SER_AR_ENCODE` 内部时，两列可能重叠，不能相加。它们分别用于回答“剪枝成本”和“总编码成本”。结构对比同时保留 peak RSS、encoding、MonoSAT solving 和 end-to-end time；oracle 对比额外保留 closure builds/updates。

输入维度随每个 history 自动解析并写入 CSV：operation/transaction/key/point-read count、predicate operation count/ratio、predicate transaction count/ratio、empty-result ratio、predicate selectivity、returned-result cardinality 的 mean/median/max，以及全 key 和已写 key 口径下的 distinct writers/key。这里 selectivity 定义为 `返回行数 / (predicate count × manifest.initial_keys)`；absent-result 定义为 `result.values` 为空。

## 输出与 correctness 门禁

每次实验生成：

- `raw.csv`：每次独立运行的完整指标和日志路径。
- `summary.csv`：按 history/variant/config 取 complete runs 的中位数。
- `causal_chain.csv`：并列给出 initial/deleted/residual candidates、preprocessing、encoding、solver、total、RSS、变量和图边，用于绘制“候选约束 → 剩余约束 → solver time”的因果图。
- `step_deltas.csv`：相邻 pruning 级别的预处理、solver、total、candidate 和图规模增量。
- `structure_deltas.csv`：第一个 variant 相对后续 variant 的绝对变化和降幅。
- `equivalence.csv`：所有完成运行的 verdict 一致性。
- `machine_and_config.json` 和 `logs/`：机器、参数及原始日志。

只有完成的 `ACCEPT/REJECT` 参与语义比较；timeout/error 标为不完整，不伪装成 verdict。任何完成运行之间的 verdict difference 都使 runner 返回退出码 2。

## Smoke 验证

`results/ser-ablation-smoke-20260912` 在一个 100-transaction real PostgreSQL multikv history 上完成六级消融：6/6 为 `ACCEPT`，semantic mismatch 为 0。该单 history、单 repeat 结果只验证实验链路，不作为论文性能结论。

`results/ser-eager-gmwr-smoke-20260912` 使用同一 history 单独运行 EAGER/GMWR suite：2/2 为 `ACCEPT`，semantic mismatch 为 0。

`results/ser-structure-smoke-20260912` 使用仅有 graph-architecture 差异的相邻历史快照，对同一 history 比较 dual 与 single serialization graph：两者均为 `ACCEPT`；本次观测 MonoSAT graph edge 从 2088 降到 214，SAT variable 从 2189 降到 278。

`results/ser-oracle-smoke-20260912` 使用 shared-oracle 注入前后的相邻历史快照，在 `GMWR_WWFeedback` 下比较 local 与 shared：两者均为 `ACCEPT`；closure build `3→1`、closure update `1952→217`、branch-pruned candidate `3958→3968`、preprocessing `162→145 ms`。

以上均为单 history、单 repeat；timing 和 RSS 存在噪声，正式报告必须使用多 history、多 repeat 的中位数。
