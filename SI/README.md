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
cd SI/si-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  audit -t PRHIST /absolute/path/to/hist-00000
```

`-t PRHIST` 可以省略，因为默认类型就是 PRHIST：

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  audit /absolute/path/to/hist-00000
```

SI detector 正常运行时会打印：

```text
Mode: SI, solving Adya typed dependency graphs A/B and checking the induced SI graph
```

如果输出里出现 `Mode: SER`，说明当前命令使用的是 SER jar，而不是 SI jar。

输出末尾会包含稳定 verdict 标记：

```text
[[[[ ACCEPT ]]]]
[[[[ REJECT ]]]]
```

含义：

- `ACCEPT`：存在一个满足点读、写入和谓词读可见性的 SI 解释。
- `REJECT`：当前历史在检测器模型下不存在合法 SI 解释。

`REJECT` 时程序返回非零退出码；如果在 shell 脚本里批量跑，需要用输出标记判断结果。

## 常用 audit 参数

```text
--pruning-mode NONE|REACHABILITY|SNAPSHOT|PRUN
    选择 WW/RW 剪枝模式，默认 REACHABILITY。

--no-pruning
    强制使用 NONE；优先级高于 --pruning-mode。

--no-coalescing
    关闭相同事务对上的 WW choice 合并。用于调试约束规模。

--dot-output
    以 DOT 格式输出冲突图，便于可视化。

--compare-derived-predicate-edges
    额外打印按旧方式派生的 PR_WR / PR_RW 边数量。当前 SAT 求解不会依赖这些派生边。

--solver monosat
    指定 SAT solver 后端。当前只支持 monosat。

--solver-stats
    打印 SAT 后端标识，并启用详细谓词编码计数。

--solver-timeout-seconds
    当前仅完成 CLI 参数解析，尚未传入 MonoSAT 后端，不能作为已生效的超时机制。
```

四种剪枝模式的含义：

- `NONE`：不预先固定 WW 方向，全部交给 MonoSAT。
- `REACHABILITY`：逐个试加 constraint 两侧的 typed edges，以 `A ∪ (A ∘ B)` 是否成环固定单侧可行的方向。
- `SNAPSHOT`：只执行共享快照传播；快照闭包只使用 A，可推出的 RW 仍写入 B。
- `PRUN`：在 SNAPSHOT 传播基础上，再执行 induced-graph 分支剪枝。

未在剪枝阶段拒绝的历史，最终都由同一个 `SISolverInduced` 完成 typed-edge 编码和 induced-graph 判定；剪枝只减少待求解的 WW choices，不改变图语义。

求解器构造分为六个可单独计时的阶段：

```text
SI_GRAPH_ENCODE_SETUP
SI_GRAPH_ENCODE_KNOWN_EDGES
SI_GRAPH_ENCODE_WW
SI_GRAPH_ENCODE_RW
SI_GRAPH_ENCODE_PREDICATE
SI_GRAPH_ENCODE_ACYCLIC
```

其中 WW 阶段只编码分支的 WW 方向，普通 RW 在下一阶段由 `readFrom + wwOrder` 统一生成。运行时输出的 `Predicate source constraints` 表示 external `(predicate read,key)` frontier 约束数，不是谓词读事件数。

结构化谓词会按 SAT 模型构造 latest-visible 快照并执行完整查询。错误的 JOIN、投影、重复行或遗漏行都会被拒绝；改变查询结果的后续写会生成相应的 `PR_RW` anti-dependency。

谓词编码分为两条路径：row-local `QueryPlan` 在求解前逐 key EAGER 编码；JOIN、`DISTINCT` 等 general query 预先编码带 frontier guard 的 `PR_WR/PR_RW`，求解后只对不匹配的完整快照加入 no-good clause。谓词边不是诊断标签：它们通过统一 typed-edge 入口进入 A/B，并实际参与冲突判断。

示例：

```bash
java -Djava.library.path=build/monosat -Xmx12g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  audit --compare-derived-predicate-edges --solver-stats \
  ../../predicateHistories/kvpredicate/kvpredicate_repeatable_read_write_skew1_20260706/hist-00000
```

当前 SI detector 也可直接审计 `History_Generator` 生成的结构化 MultiKV JOIN 历史：

```bash
java -Djava.library.path=build/monosat -Xmx12g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  audit ../../predicateHistories/multikv/<case>/hist-00000
```

TPC-C StockLevel 当前仍输出 SQL 文本而不是结构化 `query`，因此不属于已完整支持的输入。

当前 SI 未引入 SER 的 GMWR 路径；谓词求解保持 EAGER/general refinement。general 路径在全部 scope key 都为 INTERNAL 时不会创建 `PredicateCheck`：非 row-local 查询不会再次执行完整 JOIN/`DISTINCT` snapshot 求值；row-local 查询若 EAGER 校验失败后回退到该路径，也存在同一跳过边界。

## 查看统计和 dump

只统计剪枝前后约束规模、不启动最终 MonoSAT 求解：

```bash
cd SI/si-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  constraint-stat --pruning-mode PRUN /absolute/path/to/hist-00000
```

输出字段包括 `constraints_before/after`、`implications_before/after`、内部一致性和剪枝冲突状态。

统计历史规模：

```bash
cd SI/si-result-detector
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  stat /absolute/path/to/hist-00000
```

打印 loader 解析后的事务和操作：

```bash
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/si-result-detector-1.0.0-SNAPSHOT.jar \
  dump /absolute/path/to/hist-00000
```

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
