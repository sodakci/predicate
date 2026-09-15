# SER 使用手册

SER 是本仓库中的谓词感知可串行化检测器。它读取 PRHIST 历史，构造事务之间的已知依赖和待定写写顺序，并调用 MonoSAT 判断是否存在一个合法的串行解释。

当前将带类型的逻辑依赖与 MonoSAT 物理图分层：

```text
D = SO ∪ WR ∪ WW ∪ RW ∪ PR_WR ∪ PR_RW
```

`SEREdge` 保留 `WR/WW/RW/PR_WR/PR_RW` 的 type/key/guard 元数据，用于 explanation、debugging 和论文描述；这些逻辑依赖统一投影到唯一的 `serializationGraph`，MonoSAT 只断言该图 `acyclic()`。

```text
History
  -> Logical Dependency Layer
  -> Serialization Constraint Graph
  -> MonoSAT acyclic
```

详细的项目结构和求解公式见 [PROJECT_OVERVIEW.md](ser-result-detector/docs/PROJECT_OVERVIEW.md)，重点阅读“求解核心：分阶段 MonoSAT/typeedge 编码”及其内部状态、七阶段编码、依赖物化、EAGER/GMWR、no-good 与冲突缩减小节。

## 目录说明

```text
SER/
  README.md
  ser-result-detector/
    build.gradle
    gradlew
    jdk11-env.sh
    docs/PROJECT_OVERVIEW.md
    src/main/java/Main.java
    src/main/java/history/
    src/main/java/history/loaders/PredicateHistoryLoader.java
    src/main/java/graph/KnownGraph.java
    src/main/java/verifier/
    tools/audit-prhist.sh
    tools/run_catalog_experiment.py
    tools/run_ser_baseline_vs_gmwr.py
    tools/run_ser_ablation.py
    monosat/
```

日常使用基本都在 `SER/ser-result-detector` 下完成。

## 环境准备

推荐环境是 Linux、JDK 11、CMake、g++、make。当前 Gradle 配置使用 `sourceCompatibility = 11` 和 `targetCompatibility = 11`。

Ubuntu 示例：

```bash
sudo apt update
sudo apt install openjdk-11-jdk cmake g++ make
```

如果当前 shell 里默认 Java 不是 11，可以进入 detector 后启用项目自带的 Java 11 环境脚本：

```bash
cd SER/ser-result-detector
source ./jdk11-env.sh
java -version
```

`jdk11-env.sh` 只影响当前 shell，不会全局切换系统 Java。

从 GitHub 克隆后，仓库不包含 `SER/ser-result-detector/build/`。该目录是 Gradle 和 MonoSAT 的本地构建产物，不需要手动恢复；进入 detector 后执行构建命令即可重新生成。

## 构建

```bash
cd SER/ser-result-detector
./gradlew jar
```

构建会完成两件事：

- 编译 `src/main/java` 中的 Java 检测器。
- 通过 Gradle 任务 `configureMonoSAT` 和 `buildMonoSAT` 编译 `monosat/` 中的 MonoSAT Java/native 依赖。

主要产物：

```text
build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar
build/monosat/libmonosat.so
build/monosat/monosat.jar
```

如果只想运行测试：

```bash
cd SER/ser-result-detector
./gradlew test
```

当前 SER 全量回归为 263 项测试、0 failure、0 error、3 skipped。

## 输入格式

当前公开入口是 `PRHIST`。输入可以是：

- 一个 `history.prhist.jsonl.zst` 或旧版 `history.prhist.jsonl` 文件。
- 一个包含上述 history 文件和 `initial_state.json` 的 `hist-00000` 目录；两种 history 同时存在时优先读取压缩文件。

目录形态：

```text
hist-00000/
  initial_state.json
  history.prhist.jsonl.zst
  manifest.json
```

`manifest.json` 供生成器和实验脚本记录元数据，Java loader 不依赖它。

`initial_state.json` 是 JSON 数组，每个元素表示一个初始版本：

```json
[
  {"key": "kv:0", "value": 0},
  {"key": "kv:1", "value": 1}
]
```

history 解压后是 JSONL，每行一个已提交事务：

```json
{"session":0,"session_seq":1,"txn":1001,"status":"commit","ops":[{"type":"r","key":"kv:0","value":0},{"type":"w","key":"kv:0","value":10}]}
```

`session_seq`是必填整数。Java loader先检查同一`session`内的`session_seq`唯一性，再按`session_seq`建立session order；因此JSONL事务行可以任意排列。缺失、非整数、超出`long`范围或同session重复的`session_seq`都会使history无效。

支持的操作类型：

- `r`：点读，包含 `key` 和读到的 `value`。
- `w`：写，包含 `key` 和新 `value`。
- `pr`：谓词读，使用 `query` 和 `result` 描述谓词以及读到的版本集合。

谓词读示例：

```json
{
  "type": "pr",
  "query": {
    "select": {"distinct": false, "columns": ["k", "value"]},
    "from": {"relation": "kv"},
    "where": ["value % 2 = 0"]
  },
  "result": {
    "values": [{"k": "0", "value": 0}],
    "inputs": [{"key": "kv:0", "value": 0}]
  }
}
```

当前 loader 接受结构化 `query`：

```text
from
    必填，指定一个 relation，可选 alias。

joins
    可选，支持一个或多个 INNER JOIN；每个 join 使用 on 条件。

where
    可选，数组中的条件按 AND 连接。

select.columns
    必填，支持字段路径和 AS 别名。

select.distinct
    可选布尔值，默认 false。
```

条件和投影表达式支持字段路径、整数/字符串/布尔/null 字面量、`=`、`>`、`<`、`%`、`AND` 和括号。单表 KV 的 `TRUE`、`value = n`、`value % m = r`、`value > n`、`value < n` 继续受支持；对象 `value` 可以通过 `relation.value.field` 访问。`result.values` 按多重集比较，`result.inputs` 必须列出结果实际依赖的可见 `(key,value)` 版本。对象和数组结果支持相等性比较，但 `<`、`>` 只适用于可排序的标量值。

注意：`PredicateHistoryLoader` 只接受紧凑 `query/result` 形态，并拒绝 `write_id`、`source_write_id`、`source_txn`、`source_op_index` 等 source provenance 字段。带 `predicate/results` 字段或直接保存 SQL 文本的历史不属于当前输入格式。

## 运行单个历史

先构建 jar，然后运行：

```bash
cd SER/ser-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit /absolute/path/to/hist-00000
```

普通 `audit` 按 History、WW、可选 GMWR、Predicate、SAT、Timing 的完成顺序流式打印精简摘要；数量带千位分隔符，时间统一为秒并保留三位小数。Predicate 摘要分别展示编码阶段生成的 `PR_WR/PR_RW` 尝试及剩余 logical candidate、编码前已确定的 fixed PR candidate，并展示两者合计后按 `(from,to,type)` 合并得到的 physical edge 数。History 中的 Events 仅统计客户端事务事件，不包含内部初始版本写。稳定 verdict 始终是最后一行：

```text
SER audit result: ACCEPT
SER audit result: REJECT
```

含义：

- `ACCEPT`：存在一个满足所有读写和谓词读约束的串行解释。
- `REJECT`：当前历史在检测器模型下不可串行化。

`REJECT` 时程序返回非零退出码；如果在 shell 脚本里批量跑，需要用输出标记判断结果。

### 内部一致性预检

`audit` 会在构造 KnownGraph 和启动 MonoSAT 前调用 `Utils.verifyInternalConsistency`。预检会验证点读和谓词结果的来源写、事务内 read-your-writes、谓词结果 key 的唯一性/查询范围，以及相同谓词读对未被本地写覆盖的结果继承。预检失败时会先在标准错误输出具体原因，然后直接返回 `REJECT`，不会进入 SAT/AR 求解。

完整规则和判断顺序见 `ser-result-detector/docs/PROJECT_OVERVIEW.md` 的“内部一致性预检”一节。

## 常用 audit 参数

```text
--predicate-encoding EAGER|GMWR
    谓词编码，默认 GMWR。EAGER 对应论文 E2 baseline。

--solver-timeout-seconds N
    SAT 求解超时秒数，默认 600；0 表示禁用。计时从 `solve()` 调用开始，不包含编码。超时摘要输出 `SER audit result: TIMEOUT`，退出码 124，并分别打印 encode/solve 时间。全检查器超时由 runner 进程超时负责，从 `audit()` 开始计算墙钟。

--solver-stats
    在精简摘要后打印完整 profiler metrics/counters、SAT 后端和求解配置；verdict 仍为最后一行。
```

### 默认 G2 与 E2 baseline

不带算法选项的 `audit HISTORY` 直接运行完整 G2：

```bash
cd SER/ser-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit /absolute/path/to/hist-00000
```

其内部固定组合为 WW reachability、compact GMWR encoding、完整 GMWR-WW semantic propagation/fixpoint、predicate witness coalescing 和 graph-edge interning，随后只把残余约束交给 MonoSAT 并执行 verification。

论文 E2 baseline 只需显式选择 EAGER encoding：

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit --predicate-encoding=eager \
  /absolute/path/to/hist-00000
```

E2 仍使用 WW reachability、EAGER predicate encoding 与 graph-edge interning，但不构造或传播 GMWR。E1/G1 所需的 `--ser-propagation-mode`、`--[no-]gmwr-prepropagation`、`--[no-]predicate-witness-coalescing`、`--[no-]graph-edge-interning` 和 `--ww-pruning` 仅作为隐藏实验参数保留；旧 `--predicate-mode` 已删除。

精简摘要以 `SER audit result: ACCEPT`、`SER audit result: REJECT`、`SER audit result: TIMEOUT` 或 `SER audit result: ERROR` 收尾，并展示 History、WW、可选 GMWR、Predicate、SAT、关键计时和峰值内存；超时退出码为 124。EAGER 不打印 GMWR section，也不在 WW 转换中打印第三项。启用 `--solver-stats` 后，摘要之后仍会输出完整 profiler 标签、配置、runner 使用的 `Max memory` 行和兼容旧 runner 的 `[[[[ ... ]]]]` 标记，最终 verdict 仍是最后一行。

当前实现会自动使用以下等价编码，无需额外命令行开关：

- 对已知 SO/WR/依赖序计算传递闭包，并只向 MonoSAT 写入传递约简边；已由已知序确定的 AR 方向直接作为常量。
- AR 比较只为公式实际涉及的事务对创建；无环偏序最终可扩展为串行全序。
- 单表 `Scan/Filter`、`distinct=false` 且投影为逐行表达式的查询走 row-local 逐 key 编码。
- `GMWR` 对 row-local 查询使用 `(reader,bad-writer)` bundle；同一 bundle 内只保留最小 repair set 反链，较弱的超集子句不再物化。
- GMWR 思想同样用于非 DISTINCT 的单调多表 `INNER JOIN`，但不会把跨表结果错误拆成逐 key bundle：它保留完整 QueryPlan 求值，复用 source 剪枝和 predicate witness coalescing / graph-edge interning，并用额外结果实际依赖的多表输入形成 multi-key witness no-good。
- `DISTINCT`、非单调查询及 GMWR 未覆盖的部分继续走完整快照求值，并按 SAT 模型惰性加入不匹配快照的阻断子句。

这里的紧凑编码有明确范围：row-local bundle 只压缩无 recorded source 的 EXTERNAL bad-writer obligations；predicate physical-edge 只合并相同 `(from,to,type)` 的多 key witnesses；多表 witness 只用于非 DISTINCT 单调 QueryPlan 的记录外结果。它们都不删除 recorded source、不省略完整多表求值，也不改变最终 typed dependency 并集判环。完整处理矩阵见 [PROJECT_OVERVIEW.md 的紧凑编码章节](ser-result-detector/docs/PROJECT_OVERVIEW.md#711-紧凑编码的设计范围与处理范围)。

示例：

```bash
java -Djava.library.path=build/monosat -Xmx12g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit --solver-stats \
  ../../predicateHistories/kvpredicate/kvpredicate_serializable_20260706/hist-00000
```

## 批量审计历史目录

`tools/audit-prhist.sh` 会递归查找输入目录下所有 `history.prhist.jsonl`，逐个调用 detector，并把完整日志写到输出目录。

```bash
cd SER/ser-result-detector
tools/audit-prhist.sh ../../predicateHistories/kvpredicate
```

常用环境变量：

```text
SER_RESULT_DETECTOR_JAR
    指定 detector jar。默认 build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar。

MONOSAT_NATIVE_DIR
    指定 MonoSAT native library 目录。默认 build/monosat。

SER_RESULT_DETECTOR_HEAP
    JVM heap，例如 8g、12g、32g。默认 8g。

SER_RESULT_DETECTOR_JAVA_OPTS
    追加 JVM 参数。

SER_RESULT_DETECTOR_OUTPUT_DIR
    批量审计日志目录。默认 /tmp/ser-result-detector-prhist-audit。
```

示例：

```bash
SER_RESULT_DETECTOR_HEAP=12g \
SER_RESULT_DETECTOR_OUTPUT_DIR=/tmp/ser-kv-audit \
tools/audit-prhist.sh ../../predicateHistories/kvpredicate
```

脚本最后会输出汇总：

```text
Summary: ACCEPT=... REJECT=... RUNTIME_ERROR=...
```

如果出现 `RUNTIME_ERROR`，优先看脚本打印的 per-history log 路径。

## 运行 E2/G2 对比与消融

主 runner 默认只运行 E2/G2，并统一负责执行 detector、解析日志和保存结果：

```bash
cd SER/ser-result-detector
./gradlew installDist
python3 tools/run_ser_baseline_vs_gmwr.py \
  ../../predicateHistories/kvpredicate/test \
  --repeats 5
```

E1/G1、关闭 WW reachability 等实验变体由消融入口运行：

```bash
python3 tools/run_ser_ablation.py \
  ../../predicateHistories/kvpredicate/test \
  --suite pruning \
  --repeats 5
```

两个入口都保存 stdout/stderr 和 raw CSV，并汇总 verdict、耗时、MonoSAT、内存及 GMWR 指标。catalog runner 只保留 catalog 选样和期望 verdict 校验，复用主 runner 的进程执行与日志解析函数。

### 运行 WW/GMWR 剪枝对比

先构建可运行分发，再运行专用脚本。不传历史路径时，默认处理 `predicateHistories/kvpredicate/test-ser%` 下的全部谓词比例：

```bash
cd SER/ser-result-detector
./gradlew installDist
python3 tools/run_ww_gmwr_pruning_comparison.py
```

如果只运行实际谓词比例低于指定阈值的历史，使用 `--predicate-ratio-below`：

```bash
python3 tools/run_ww_gmwr_pruning_comparison.py \
  --predicate-ratio-below 0.10
```

阈值使用 0–1 的小数表示；`0.10` 表示只运行 `manifest.json` 中实际谓词操作比例严格低于 10% 的历史，等于 10% 的历史不会运行。

如果只运行一个谓词比例，通过 `PREDICATE_RATIO` 选择对应的已有历史目录：

```bash
cd SER/ser-result-detector
PREDICATE_RATIO=0.10
python3 tools/run_ww_gmwr_pruning_comparison.py \
  "../../predicateHistories/kvpredicate/test-ser%/20_100_10_5000_${PREDICATE_RATIO}_uniform"
```

`PREDICATE_RATIO` 使用 0–1 的小数表示，例如 `0.10` 表示 10%；指定的目录必须已存在。脚本读取每份 history 的 `manifest.json` 计算实际谓词操作比例，并生成 `raw.csv`、`summary.csv`、`pruning_time.svg` 和 `pruned_constraints.svg`。`pruned_constraints.svg` 分别比较 WW 相对初始 WW 约束、GMWR 相对 WW 剩余约束的削减百分比；`pruning_time.svg` 分别比较 WW reachability 与 GMWR feedback 阶段的中位耗时（秒）。

## 运行 catalog 实验

当历史集合提供 `catalog.json` 且其中有 `expected_verdict` 时，可以用 catalog runner 做可复现实验：

```bash
cd SER/ser-result-detector
./gradlew jar
tools/run_catalog_experiment.py \
  /absolute/path/to/catalog.json \
  --output-dir /tmp/ser-catalog-results \
  --run-id kvpredicate-main
```

快速 smoke run：

```bash
tools/run_catalog_experiment.py \
  /absolute/path/to/catalog.json \
  --limit 1 \
  --timeout-seconds 120 \
  --output-dir /tmp/ser-catalog-smoke \
  --run-id smoke
```

输出目录通常包含：

```text
logs/
results.jsonl
results.csv
paper_table.csv
summary.json
config.json
machine.json
```

这些文件会记录命令行、JVM 参数、机器信息、原始日志、期望 verdict 和实际 verdict，适合长期实验复现。

## 与 History_Generator 配合

`History_Generator` 默认把新 case 写到仓库根目录：

```text
predicateHistories/<workload>/<case>/hist-00000
```

### 生成 KV 参数化历史

KV 历史生成参数如下：

| 实验参数 | 运行变量 | 可选值 |
| --- | --- | --- |
| sessions | `TERMINALS` | `5 10 20 40 80` |
| txns/session | `TXNS_PER_SESSION` | `50 100 200 500` |
| ops/txn | `MIN_TXN_LENGTH`、`MAX_TXN_LENGTH` | `5 10 20 40`，两者设置为相同值 |
| predicate read ratio | `PREDICATE_READ_RATIO` | `20 50 80 95` |
| rows/table | `KEY_COUNT` | `1000 10000 100000 1000000` |
| distribution | `KEY_DIST` | `uniform zipfian hotspot` |

例如生成 5 sessions、每 session 50 个事务、每事务 5 个操作、20% 谓词读、1000 行、uniform 的真实 PostgreSQL 历史：

```bash
cd /home/lc/Desktop/predicate/History_Generator
source .tools/java23.env

PGPASSFILE=kv/.runtime/pgpass \
BUILD=true \
LOAD=true \
CASE_NAME=kv_ser_s5_t50_o5_pr20_rows1000_uniform_$(date +%Y%m%d_%H%M%S) \
ISOLATION=TRANSACTION_SERIALIZABLE \
KV_PREDICATE_ANOMALY=none \
TERMINALS=5 \
TXNS_PER_SESSION=50 \
MIN_TXN_LENGTH=5 \
MAX_TXN_LENGTH=5 \
PREDICATE_READ_RATIO=20 \
KEY_COUNT=1000 \
KEY_DIST=uniform \
KEY_DIST_BASE=0.99 \
MAX_WRITES_PER_KEY=2147483647 \
TIME_SECONDS=60 \
RATE=unlimited \
./kv/run_kvpredicate_history_case.sh
```

`PREDICATE_READ_RATIO` 表示谓词读占全部操作的概率；剩余操作中点读和写各约一半。例如设置为 `20` 时，三类操作约为 20% 谓词读、40% 点读、40% 写。旧的 `READ_RATIO` 已被替换，不再用于该生成命令。

完整 3840 组参数矩阵循环见 [History_Generator 使用手册](../History_Generator/README.md#kv-参数矩阵配置命令)。

KV predicate case 可以直接交给当前 SER detector：

```bash
cd SER/ser-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit ../../predicateHistories/kvpredicate/<case>/hist-00000
```

MultiKV case 使用当前 SER loader 支持的结构化 INNER JOIN、投影、对象行值和结果多重集格式，也可以直接审计：

```bash
cd SER/ser-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit ../../predicateHistories/multikv/<case>/hist-00000
```

TPC-C generator 当前能采集、导出和审计 raw evidence，但 StockLevel 会输出多表 SQL-shaped predicate。当前 SER loader 支持的是结构化 `query` 对象而不是 SQL 文本，因此不应把该 StockLevel 历史当作已被 detector 完整支持的输入。

## 常见问题

### 找不到 MonoSAT native library

现象通常是 JVM 报 `UnsatisfiedLinkError`。

处理：

```bash
cd SER/ser-result-detector
./gradlew jar
ls build/monosat/libmonosat.so
```

运行时确保带上：

```text
-Djava.library.path=build/monosat
```

### 构建时 Java 版本不对

先确认：

```bash
java -version
```

如需固定 Java 11：

```bash
cd SER/ser-result-detector
source ./jdk11-env.sh
./gradlew jar
```

### 大历史内存不足

当前 detector 已对大型谓词历史启用传递约简、按需 AR、依赖去重和 row-local 逐 key 编码。若仍然内存不足，再增大 heap：

```bash
java -Djava.library.path=build/monosat -Xmx32g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit /absolute/path/to/hist-00000
```

批量脚本中使用：

```bash
SER_RESULT_DETECTOR_HEAP=32g tools/audit-prhist.sh /absolute/path/to/root
```

### loader 报 InvalidHistoryError

优先检查：

- `hist-00000` 下是否同时有 `history.prhist.jsonl.zst`（或旧版 `.jsonl`）和 `initial_state.json`。
- history 解压后的 JSONL 中是否只有 `status: "commit"` 的事务。
- `r` 和 `pr.result.inputs` 引用的 `(key,value)` 是否能在初始版本或写操作中找到。
- 写入的 `(key,value)` 是否唯一。
- 谓词读是否使用当前 loader 支持的 `query/result` 格式。

### audit-prhist.sh 找不到历史

脚本会识别三种输入：

- `hist-00000` 目录。
- `history.prhist.jsonl` 文件。
- 包含多个 `history.prhist.jsonl` 的上级目录。

如果输入路径不是这三类，脚本会报告：

```text
No predicate histories found under: ...
```
