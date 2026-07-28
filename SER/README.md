# SER 使用手册

SER 是本仓库中的谓词感知可串行化检测器。它读取 PRHIST 历史，构造事务之间的已知依赖和待定写写顺序，并调用 MonoSAT 判断是否存在一个合法的串行解释。

详细的项目结构、核心算法流程和关键文件说明见：

```text
ser-result-detector/docs/PROJECT_OVERVIEW.md
```

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
    tools/validate_prhist_suite.py
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

## 输入格式

当前公开入口是 `PRHIST`。输入可以是：

- 一个 `history.prhist.jsonl` 文件。
- 一个包含 `history.prhist.jsonl` 和 `initial_state.json` 的 `hist-00000` 目录。

目录形态：

```text
hist-00000/
  initial_state.json
  history.prhist.jsonl
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

`history.prhist.jsonl` 每行一个已提交事务：

```json
{"session":0,"session_seq":1,"txn":1001,"status":"commit","ops":[{"type":"r","key":"kv:0","value":0},{"type":"w","key":"kv:0","value":10}]}
```

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
  audit -t PRHIST /absolute/path/to/hist-00000
```

`-t PRHIST` 可以省略，因为默认类型就是 PRHIST：

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit /absolute/path/to/hist-00000
```

输出末尾会包含稳定 verdict 标记：

```text
[[[[ ACCEPT ]]]]
[[[[ REJECT ]]]]
```

含义：

- `ACCEPT`：存在一个满足所有读写和谓词读约束的串行解释。
- `REJECT`：当前历史在检测器模型下不可串行化。

`REJECT` 时程序返回非零退出码；如果在 shell 脚本里批量跑，需要用输出标记判断结果。

## 常用 audit 参数

```text
--no-pruning
    关闭 pruning。用于对比 pruning 前后的求解行为。

--no-coalescing
    关闭相同事务对上的 WW choice 合并。用于调试约束规模。

--dot-output
    以 DOT 格式输出冲突图，便于可视化。

--compare-derived-predicate-edges
    额外打印按旧方式派生的 PR_WR / PR_RW 边数量。当前 SAT 求解不会依赖这些派生边。

--solver monosat
    指定 SAT 后端。当前只支持 monosat。

--solver-stats
    打印 SAT 后端标识和额外统计信息。
```

当前实现会自动使用以下等价编码，无需额外命令行开关：

- 对已知 SO/WR/依赖序计算传递闭包，并只向 MonoSAT 写入传递约简边；已由已知序确定的 AR 方向直接作为常量。
- AR 比较只为公式实际涉及的事务对创建；无环偏序最终可扩展为串行全序。
- 单表 `Scan/Filter`、`distinct=false` 且投影为逐行表达式的查询走 row-local 逐 key 编码。
- `JOIN`、`DISTINCT` 和其他非逐行查询继续走完整快照求值，并按 SAT 模型惰性加入不匹配快照的阻断子句。

示例：

```bash
java -Djava.library.path=build/monosat -Xmx12g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit --compare-derived-predicate-edges --solver-stats \
  ../../predicateHistories/kvpredicate/kvpredicate_serializable_20260706/hist-00000
```

## 查看统计和 dump

统计历史规模：

```bash
cd SER/ser-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  stat /absolute/path/to/hist-00000
```

打印 loader 解析后的事务和操作：

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  dump /absolute/path/to/hist-00000
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

- `hist-00000` 下是否同时有 `history.prhist.jsonl` 和 `initial_state.json`。
- `history.prhist.jsonl` 中是否只有 `status: "commit"` 的事务。
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
