# SER 两开关消融实验

## 实验目标

生产路径只保留两个算法开关：

- `--[no-]gmwr`：同时控制 GMWR formulation 与两条 frontier 剪枝路径。
- `--[no-]gmwr-prepropagation`：控制 GMWR obligation 在 SAT 编码前的确定性传播；关闭 GMWR 时该开关不生效。

WW reachability、predicate witness coalescing 和 graph-edge interning 在三组实验中始终开启，不再作为 CLI 消融变量。bundle compaction 已删除，不再进入实验矩阵。

## Runner

查看默认样本和运行数：

```bash
python3 tools/run_ser_acceleration_ablation.py --plan-only
```

执行实验：

```bash
python3 tools/run_ser_acceleration_ablation.py \
  --out-dir results/ser-acceleration-ablation
```

默认抽样规则：

- `predicateHistories/kvpredicate/test-ser`：每个参数目录取 `hist-00000`；
- `predicateHistories/kvpredicate/test-ser%`：只取目录名义谓词比例严格小于 `0.10` 的参数目录，每个取 `hist-00000`；
- 每条历史重复两次，配置顺序按 repeat 反转，并按 history 轮换。

## 三个有效配置

| 配置 | CLI | 含义 |
| --- | --- | --- |
| `NO_GMWR` | `--no-gmwr --no-gmwr-prepropagation` | EAGER；GMWR 与 frontier 均关闭 |
| `NO_PREPROP` | `--gmwr --no-gmwr-prepropagation` | 开启 GMWR 与 frontier，但不做预传播 |
| `FULL` | `--gmwr --gmwr-prepropagation` | 生产默认完整加速 |

由此形成两个单变量步骤和一个端到端对比：

```text
NO_GMWR --(GMWR + frontier)--> NO_PREPROP --(prepropagation)--> FULL
    \----------------------------------------------------------/
                              full_stack
```

`NO_GMWR + prepropagation` 没有独立配置，因为 EAGER 路径不会构造 GMWR propagation state，该组合与 `NO_GMWR` 等价。

## 输出

- `raw.csv`：每次 JVM 运行的状态、verdict、时间、峰值 RSS 和全部 profiler 指标。
- `paired.csv`：同一 history/repeat 的 `gmwr_frontier`、`prepropagation`、`full_stack` 配对结果。
- `summary.csv`：按全部样本、`test-ser`、`test-ser%` 汇总的 paired median/geomean、hard-tail、OOM/timeout rescue。
- `by_history.csv`：逐 history/config 的重复运行中位数。
- `raw.json`：可恢复的逐次 checkpoint；`--resume` 只补跑未完成配置。
- `logs/`：stdout、stderr 和 GNU `time -v` 日志。
- `runtime/`、`environment.json`、`plan.json`、`worktree.diff`：冻结 classpath/native library 与复现信息。

timeout、OOM、系统内存保护停止和 verdict mismatch 都作为删失结果，不参与运行时间比。只有双方均完成且 verdict 一致的 paired run 才计算 speedup。

## GMWR 与 NO_GMWR 耗时图

实验完成后，使用 `raw.csv` 生成四张受控变量 SVG 图：

```bash
python3 tools/plot_ser_gmwr_vs_no_gmwr.py \
  results/ser-acceleration-ablation/raw.csv \
  --out-dir results/ser-acceleration-ablation/svg
```

脚本只读取 `test-ser`，比较 `FULL`（GMWR）与 `NO_GMWR`，并以 `20_100_15_5000_0.20_uniform` 为共同基线，每次只改变一个参数。输出为：

- `transactions.svg`：每个 session 的事务数；
- `operations_per_transaction.svg`：每个事务的操作数；
- `keys.svg`：key 数量；
- `predicate_ratio.svg`：谓词读比例；
- `gmwr_vs_no_gmwr.csv`：各点运行数、状态分布、双方完成运行的端到端耗时中位数及加速比。

SVG 的纵轴为完成运行中 `ENTIRE_EXPERIMENT` 的中位数（秒）。timeout、OOM 等状态保留在汇总 CSV 中，但不作为耗时点绘制；某一点必须同时存在 `FULL` 和 `NO_GMWR` 的完成运行才会进入曲线。

## 关键指标

至少关注：

- 端到端与阶段时间：`ENTIRE_EXPERIMENT`、`GMWR_BUILD_MS`、`GMWR_REDUCTION_MS`、`SER_AR_ENCODE`、`SER_AR_ENCODE_PREDICATE`、`SER_MONOSAT_SOLVE`；
- 搜索空间：`SER_PROP_RESIDUAL_SAT_VARIABLES_COUNT`、`SER_PROP_RESIDUAL_SAT_CONSTRAINTS_COUNT`、`SER_PROP_MONOSAT_GRAPH_EDGES_COUNT`；
- solver 行为：`SER_PROP_MONOSAT_PROPAGATIONS_COUNT`、`SER_PROP_MONOSAT_CONFLICTS_COUNT`；
- GMWR：`GMWR_INITIAL_CONSTRAINTS`、`GMWR_RESIDUAL_CONSTRAINTS`、`GMWR_REMOVED_CANDIDATES`、`GMWR_FORCED_FACTS`、residual clauses/literals；
- frontier/latest：`SER_PRED_FRONTIER_CANDIDATES_COUNT`、`SER_PRED_LATEST_WRITER_INPUT_WRITES_COUNT`、`SER_PRED_LATEST_WRITER_RESULTS_COUNT`、`SER_GMWR_INTERVAL_CANDIDATES_PRUNED_COUNT`；
- 资源：GNU `time -v` 的 peak RSS、timeout/OOM/rescue。

归因时先看 `NO_GMWR -> NO_PREPROP` 是否减少 latest/frontier 候选和残余 SAT 规模，再看 `NO_PREPROP -> FULL` 是否进一步减少 residual obligations、propagation/conflicts 和 solve time。不要用单独的 obligation 数下降代替搜索空间或运行时间结论。
