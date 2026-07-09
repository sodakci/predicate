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

当前 loader 支持的 KV 谓词为：

```text
TRUE
value = n
value % m = r
value > n
value < n
```

注意：当前 `PredicateHistoryLoader` 只接受紧凑 `query/result` 形态。带 `predicate/results` 字段、source provenance 字段或 TPC-C 多表 SQL predicate 的历史，需要先确认 detector 已扩展对应 parser 与谓词求值逻辑。

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

示例：

```bash
java -Djava.library.path=build/monosat -Xmx12g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit --compare-derived-predicate-edges --solver-stats \
  ../../PolySIHistories/kvpredicate/kvpredicate_serializable_20260706/hist-00000
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
tools/audit-prhist.sh ../../PolySIHistories/kvpredicate
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
tools/audit-prhist.sh ../../PolySIHistories/kvpredicate
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
PolySIHistories/<workload>/<case>/hist-00000
```

KV predicate case 可以直接交给当前 SER detector：

```bash
cd SER/ser-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit ../../PolySIHistories/kvpredicate/<case>/hist-00000
```

TPC-C generator 当前能采集、导出和审计 raw evidence，但 StockLevel 会输出多表 SQL-shaped predicate；在当前 SER loader 只支持 KV `value` 谓词的情况下，不应把 TPC-C StockLevel 历史当作已被当前 detector 完整支持的输入。

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

增大 heap：

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
