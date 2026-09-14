# SI 使用手册

SI 是本仓库中的谓词感知快照隔离检测器。它读取 PRHIST 历史，构造 Adya typed dependency 的 A/B 边、待定写写顺序和谓词读约束，并调用 MonoSAT 判断是否存在一个合法的 SI 解释。

当前最终判定不是旧式 AR 序列图：

```text
A = {SO, WR, WW, PR_WR}
B = {RW, PR_RW}
InducedSI = A ∪ (A ∘ B)
```

求解器要求 `InducedSI` 无环。实现中 `depGraph` 表示 A，B 以带 guard 的 typed edge 保存；每加入 A 或 B 都同步补齐 `A ∘ B` 到实际参与 verdict 的 `inducedGraph`。

详细的项目结构和关键文件说明见 [PROJECT_OVERVIEW.md](si-result-detector/docs/PROJECT_OVERVIEW.md)；其中“求解核心：分阶段 MonoSAT 编码”“两图如何实际参与冲突判断”“Predicate 求解核心”和“SAT 循环、判定与冲突提取”四节给出了 guard 公式、A/B 组合、frontier/refinement 与 UNSAT 缩减的完整说明。

## 目录说明

```text
SI/
  README.md
  si-result-detector/
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
    monosat/
```

日常使用基本都在 `SI/si-result-detector` 下完成。

## 环境准备

推荐环境是 Linux、JDK 11、CMake、g++、make。当前 Gradle 配置使用 `sourceCompatibility = 11` 和 `targetCompatibility = 11`。

Ubuntu 示例：

```bash
sudo apt update
sudo apt install openjdk-11-jdk cmake g++ make
```

如果当前 shell 里默认 Java 不是 11，可以进入 detector 后启用项目自带的 Java 11 环境脚本：

```bash
cd SI/si-result-detector
source ./jdk11-env.sh
java -version
```

`jdk11-env.sh` 只影响当前 shell，不会全局切换系统 Java。

从 GitHub 克隆后，仓库不包含 `SI/si-result-detector/build/`。该目录是 Gradle 和 MonoSAT 的本地构建产物，不需要手动恢复；进入 detector 后执行构建命令即可重新生成。

## 构建

```bash
cd SI/si-result-detector
./gradlew jar
```

构建会完成两件事：

- 编译 `src/main/java` 中的 Java 检测器。
- 通过 Gradle 任务 `configureMonoSAT` 和 `buildMonoSAT` 编译 `monosat/` 中的 MonoSAT Java/native 依赖。

主要产物：

```text
build/libs/si-result-detector-1.0.0-SNAPSHOT.jar
build/monosat/libmonosat.so
build/monosat/monosat.jar
```

如果只想运行测试：

```bash
cd SI/si-result-detector
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

`session_seq` 是必填的 long 整数，同一 session 内不得重复。loader 先读取并验证所有事务头，再按 `session -> session_seq` 建立会话顺序；JSONL 行序和 `txn` 编号不参与会话排序。

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

先构建 jar，然后运行唯一的检测入口：

```bash
cd SI/si-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  audit /absolute/path/to/hist-00000
```

输入固定为紧凑 PRHIST，求解后端固定为 MonoSAT。主入口不再提供
`constraint-stat`、`stat`、`dump`，也不再接受单选的 `--type` 或
`--solver`。

SI detector 正常运行时会打印：

```text
Mode: SI, solving Adya typed dependency graphs A/B and checking the induced SI graph
```

输出末尾会包含稳定 verdict 标记：

```text
[[[[ ACCEPT ]]]]
[[[[ REJECT ]]]]
[[[[ TIMEOUT ]]]]
```

- `ACCEPT`：存在一个满足点读、写入和谓词读可见性的 SI 解释。
- `REJECT`：当前历史在检测器模型下不存在合法 SI 解释。
- `TIMEOUT`：MonoSAT 在配置的求解期限内没有完成，退出码为 124。

## audit 参数

普通 `audit --help` 公开：

```text
--solver-timeout-seconds
    MonoSAT 求解与 refinement 的总超时秒数；0 禁用后端超时。

--solver-stats
    打印 MonoSAT/CNF、谓词编码、WW constraint 和 implication 统计。
```

当前 SI 谓词编码固定为 EAGER/general refinement。row-local EAGER 使用
`ENCODED/UNSUPPORTED/INVALID` 三态：只有 `UNSUPPORTED` 转 general，`INVALID`
直接加入矛盾约束。`--predicate-encoding`
将在 SI 的 GMWR 实现接入后再公开；当前不会把未完成的 GMWR 作为默认值或
可选值。

实验消融参数仍可解析，但在帮助中隐藏：

```text
--ww-pruning NONE|REACHABILITY
--[no-]predicate-witness-coalescing
--[no-]graph-edge-interning
```

`REACHABILITY` 是正式 WW 基线：每个候选分支仍由
`SIVerifier.InducedGraph.Oracle` 按 `A ∪ (A ∘ B)` 检查；`NONE`
仅用于跳过这一步的必要消融。WW constraint 始终按 writer transaction pair
合并，predicate witness coalescing 和 graph-edge interning 默认开启。

求解器构造分为六个可单独计时的阶段：

```text
SI_GRAPH_ENCODE_SETUP
SI_GRAPH_ENCODE_KNOWN_EDGES
SI_GRAPH_ENCODE_WW
SI_GRAPH_ENCODE_RW
SI_GRAPH_ENCODE_PREDICATE
SI_GRAPH_ENCODE_ACYCLIC
```

`--solver-stats` 会同时输出
`WW_INITIAL_CONSTRAINTS`、`WW_AFTER_BASELINE`、
`WW_INITIAL_IMPLICATIONS` 和 `WW_AFTER_BASELINE_IMPLICATIONS`；
不再需要独立 constraint-only 加载和遍历。REJECT 使用统一的文本 cycle
witness，不再提供 DOT/legacy 两套输出。

结构化谓词会按 SAT 模型构造 latest-visible 快照并执行完整查询。错误的 JOIN、
投影、重复行或遗漏行都会被拒绝；改变查询结果的后续写会生成相应的
`PR_RW` anti-dependency。谓词依赖只由实际 MonoSAT 编码路径产生，不再额外
构造或比较 debug-only 派生谓词图。

当前 SI detector 也可直接审计 `History_Generator` 生成的结构化 MultiKV
JOIN 历史：

```bash
java -Djava.library.path=build/monosat -Xmx12g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  audit ../../predicateHistories/multikv/<case>/hist-00000
```

TPC-C StockLevel 当前仍输出 SQL 文本而不是结构化 `query`，因此不属于已完整
支持的输入。

## 批量审计历史目录

`tools/audit-prhist.sh` 会递归查找输入目录下所有 `history.prhist.jsonl`，逐个调用 detector，并把完整日志写到输出目录。

```bash
cd SI/si-result-detector
tools/audit-prhist.sh ../../predicateHistories/kvpredicate
```

常用环境变量：

```text
SI_RESULT_DETECTOR_JAR
    指定 detector jar。默认 build/libs/si-result-detector-1.0.0-SNAPSHOT.jar。

MONOSAT_NATIVE_DIR
    指定 MonoSAT native library 目录。默认 build/monosat。

SI_RESULT_DETECTOR_HEAP
    JVM heap，例如 8g、12g、32g。默认 8g。

SI_RESULT_DETECTOR_JAVA_OPTS
    追加 JVM 参数。

SI_RESULT_DETECTOR_OUTPUT_DIR
    批量审计日志目录。默认 /tmp/si-result-detector-prhist-audit。
```
