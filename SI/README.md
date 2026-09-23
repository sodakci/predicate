# SI 使用手册

SI 是本仓库中的谓词感知快照隔离检测器。它读取 PRHIST 历史，构造 Adya typed dependency 的 A/B 边、待定写写顺序和谓词读约束，并调用 MonoSAT 判断是否存在一个合法的 SI 解释。

逻辑依赖按 SI 语义分为 A/B 两类：

```text
A = {SO, WR, WW, PR_WR}
B = {RW, PR_RW}
A_s = A ∪ VIS 的正分支辅助支持
B_s = B ∪ VIS 的负分支辅助支持
InducedSI = A_s ∪ (A_s ∘ B_s)
```

每个实际消费的外部 writer/reader 事务对共用一个 `VIS(writer,reader)`：正分支提供 `A_s(writer,reader)`，负分支提供 `B_s(reader,writer)`。它们与全部 typed A/B 支持一起参与 induced 组合；辅助支持不计作 `PR_WR/PR_RW`。物理图采用辅助节点 H 编码：A(U,V) 对应 U→V 与 U→V*，B(U,V) 对应 U*→V，避免逐对展开 A;B。辅助节点只表示组合通道；每个 guard 赋值下 acyclic(H) 等价于 acyclic(A_s∪(A_s;B_s))。H 的确定部分约简后，剩余物理边与其全部 support 的析取绑定，MonoSAT 对唯一 `inducedGraph` 断言无环。该约束使所有 key 和查询共用合法 SI 快照；已删除以 `depGraph.reaches()` 代替可见性的旧路径。`NOT_VIS(writer,reader)` 不等于反向 VIS，两个事务可以互不可见。

详细的项目结构、快照与 guard 公式、完整 induced 组合及冲突解释见 [PROJECT_OVERVIEW.md](si-result-detector/docs/PROJECT_OVERVIEW.md) 和 [SI_DESIGN.md](si-result-detector/docs/SI_DESIGN.md)。

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

测试集包含 row-local 谓词分类及同一事务内重复谓词读继承语义的回归检查。

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
    可选布尔值，默认 false；检测器不支持 true。
```

条件和投影表达式支持字段路径、整数/字符串/布尔/null 字面量、`=`、`>`、`<`、`%`、`AND` 和括号。单表 KV 的 `TRUE`、`value = n`、`value % m = r`、`value > n`、`value < n` 继续受支持；对象 `value` 可以通过 `relation.value.field` 访问。`result.values` 按多重集比较，`result.inputs` 必须列出结果实际依赖的可见 `(key,value)` 版本。对象和数组结果支持相等性比较，但 `<`、`>` 只适用于可排序的标量值。

查询支持 row-local `QueryPlan`、声明 `isRowLocal()` 的程序化逐行 evaluator，以及受支持的非 DISTINCT 单调 JOIN。DISTINCT、自定义全快照及其他不支持的非单调查询明确输出 `ERROR`。所有路径共用结果多重集和贡献来源比较规则；`result.inputs` 不是完整快照，不会据此裁掉可能产生额外结果的 key。外部读取使用同一事务快照，查询事件之前的最后一次自写覆盖对应 key。

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

默认输出按 `History`、`WW`、`GMWR`、`SAT`、`Timing` 分段，
最后打印 `Peak memory` 和 `SI audit result: ACCEPT|REJECT`。
EAGER 模式不输出 GMWR 段；提前拒绝时只输出已完成的阶段。

- `ACCEPT`：存在满足检测器约束的 SI 解释，退出码 0。
- `REJECT`：不存在合法解释，Java 退出码 -1（Linux 进程退出码为 255）。
- 加载或运行异常：输出 `[SI] Error: ...` 和 `SI audit result: ERROR`，退出码 1。

`--solver-stats` 额外输出 Predicate 细项、原始计时、计数、配置和 `[[[[ ACCEPT/REJECT ]]]]` marker，最终 verdict 仍为最后一行。检测器内部不设置求解超时；实验时限由外部 runner 的 `--timeout-seconds` 控制。`tools/run_catalog_experiment.py` 将进程超时记为 `PROCESS_TIMEOUT`，按最终 verdict 核对退出码；ERROR、截断日志或退出码不一致均不会作为 REJECT。

## audit 参数

`audit --help` 公开以下参数：

```text
--[no-]gmwr
    默认开启 GMWR；--no-gmwr 使用 EAGER。
--[no-]gmwr-prepropagation
    默认开启，只在 GMWR 模式下执行 vis/repair 预传播。
--solver-stats
    输出详细统计和旧 verdict marker。
```

WW reachability、predicate witness coalescing、graph-edge interning 在生产 CLI 固定开启。
不再接受 `--solver-timeout-seconds`、`--predicate-encoding`、`--ww-pruning`、witness coalescing 或 interning 开关。

当前 WW 检查针对生成器的 WW(u,v) 加共同指向 v 的 RW 分支，补齐新 WW 与已有 B 组合后成环的情况；通过 BitSet 与既有反向闭包求交，不复制闭包。非标准分支保留原充分检查。

每次 audit 创建唯一 `SIReachabilityOracle`，WW 剪枝、谓词预处理和求解器共享这个确定事实实例。未知 VIS 由 SAT 的共享 literal 决定，候选 guard 不反写 Oracle；没有已知路径不等于不可见。

WW 剪枝后准备 GMWR 义务，并按开关执行预传播。相同 reader/bad writer 的各 key/observation item 仍按 AND 保留，不合并 repair 集合。不存在 repair 时约束 `NOT_VIS(bad,reader)`；bad 已确定可见且 repair 唯一时产生同 key WW/VIS 确定事实，由 assumption 守卫的 SAT 约束落实，不创建人工 PR 边。GMWR、预传播与 WW 剪枝均开启时，准备完成后单向将确定事实反馈给残余 WW，迭代至没有新确定项，不再重跑 GMWR。关闭预传播仍编码未解决 item；进入求解阶段后仅调用一次 MonoSAT。

求解器构造按以下阶段计时：

```text
SI_GRAPH_ENCODE_SETUP
SI_GRAPH_ENCODE_KNOWN_EDGES
SI_GRAPH_ENCODE_WW
SI_GRAPH_ENCODE_RW
SI_GRAPH_ENCODE_PREDICATE
SI_GRAPH_ENCODE_DEPENDENCIES
SI_GRAPH_ENCODE_ACYCLIC
```

`--solver-stats` 输出 `WW_INITIAL_CHOICES`、`WW_AFTER_REACHABILITY`、
`WW_AFTER_GMWR_FEEDBACK`、`WW_GMWR_FEEDBACK_FORCED`、`WW_BRANCH_EXTRA_CONFLICTS`、
`GMWR_INITIAL_CONSTRAINTS`、`GMWR_RESIDUAL_CONSTRAINTS` 和 `GMWR_FORCED_FACTS`。
`Generated/Fixed PR_WR/PR_RW` 是两类合计；PR_WR 来源候选统计与最终 H 物理边数量是不同口径。`WW_BRANCH_EXTRA_CONFLICTS` 是增强检查命中次数，跨轮可能重复，不等于新增固定 WW 数。
UNSAT 直接读取本次 MonoSAT assumption conflict clause，映射到 `WW_CHOICE`、
`PREDICATE_OBLIGATION`、`GMWR_RULE`；不再重建 solver 缩核或输出旧 stdout cycle witness。

固定返回来源及无隐式 bottom 备选的唯一来源直接编码可见性和全部竞争写排除；未定来源复用 latest 合取缓存。PR 见证合并保留去重 guard 集合，物理边使用直接子句精确绑定全部支持，不构造见证 OR 链。

row-local 路径按 key 编码 latest-visible 版本，GMWR 只改变公式组织和剪枝。受支持 JOIN 在求解前枚举完整 contributing bindings，固定记录来源并排除额外结果，结果变化的 `PR_RW` 保留其他关系的上下文条件。两条路径都使用共同 VIS、同 key WW 和查询前自写，不在求解后读取模型快照、追加 no-good 或重复求解。

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
