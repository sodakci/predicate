# History Generator 操作手册

本目录用于运行改造后的 BenchBase workload，在真实 PostgreSQL 上采集事务历史，并生成：

```text
predicateHistories/<workload>/<case>/hist-00000
```

当前支持三个 workload：

- `kvpredicate`：单表 KV 谓词读 workload，生成结构化单表查询 PRHIST。
- `multikv`：`users/items/orders` 多表 workload，生成带 INNER JOIN、投影和对象行值的结构化查询 PRHIST，可由当前 SER/SI detector 验证。
- `tpcc`：真实 TPC-C 多表 workload，生成真实 PostgreSQL raw evidence 和 PRHIST case；StockLevel 当前仍输出 SQL 文本，需转换为结构化 `query` 后才能完整验证。

改造细节分别见：

```text
kv/README.md
tpcc/README.md
```

`multikv` 的运行方式集中记录在本文档的“运行 MultiKV”一节。

## 目录

```text
History_Generator/
  README.md
  benchbase/                    BenchBase 源码和改造后的 workload
  kv/                           KV predicate 运行、trace、转换、审计
  multikv/                      多表 KV/JOIN 运行、trace、转换、审计
  tpcc/                         TPC-C 运行、trace、转换、审计
  .tools/java23.env             本地 Java 23 环境
```

生成 case 的固定结构：

```text
hist-00000/
  initial_state.json
  history.prhist.jsonl
  manifest.json
  raw_kvpredicate_trace.jsonl   # KV case
  raw_multikv_trace.jsonl       # MultiKV case
  raw_tpcc_trace.jsonl          # TPC-C case
```

## 环境准备

所有命令默认从这里执行：

```bash
cd History_Generator
```

需要：

- PostgreSQL 和 `psql`
- Python 3
- Java 23，用于构建当前 BenchBase

从 GitHub 克隆后，仓库不包含以下本地环境和生成目录：

```text
.tools/jdk-23/
benchbase/.m2/
benchbase/target/
```

首次使用时先准备项目本地 Java 23：

```bash
cd History_Generator/.tools
python3 download_java23.py
tar -xzf OpenJDK23U-jdk_x64_linux_hotspot_23.0.2_7.tar.gz
mv jdk-23.0.2+7 jdk-23
```

启用项目自带 Java 23：

```bash
source .tools/java23.env
java -version
```

该命令只影响当前 shell。

PostgreSQL 密码建议放在 workload 自己的 `.runtime/pgpass`：

```text
kv/.runtime/pgpass
multikv/.runtime/pgpass
tpcc/.runtime/pgpass
```

权限必须是：

```bash
chmod 600 kv/.runtime/pgpass
chmod 600 multikv/.runtime/pgpass
chmod 600 tpcc/.runtime/pgpass
```

`.pgpass` 格式：

```text
host:port:database:user:password
```

## 构建 BenchBase

```bash
cd History_Generator
source .tools/java23.env
cd benchbase
./mvnw -q -DskipTests -Dfmt.skip=true -Ddescriptors=src/main/assembly/dir.xml -P postgres package
cd ..
```

默认 jar：

```text
benchbase/target/benchbase-postgres/benchbase-postgres/benchbase.jar
```

`benchbase/.m2/` 是 Maven 本地依赖缓存，`benchbase/target/` 是构建产物。它们不需要从 Git 恢复，执行上面的 `./mvnw ... package` 后会重新生成；首次构建需要联网下载依赖。

KV 和 MultiKV 一键脚本默认会自动构建；TPC-C 脚本需要显式设置 `BENCHBASE_JAR`。

## 运行 KV Predicate

推荐使用一键脚本：

```bash
cd History_Generator
PGPASSFILE=kv/.runtime/pgpass \
CASE_NAME=kvpredicate_serializable_20260706 \
ISOLATION=TRANSACTION_SERIALIZABLE \
KV_PREDICATE_ANOMALY=none \
KEY_COUNT=10 \
MIN_TXN_LENGTH=1 \
MAX_TXN_LENGTH=4 \
TERMINALS=4 \
TIME_SECONDS=5 \
RATE=200 \
KEY_DIST=exponential \
PREDICATE_GROUP_COUNT=4 \
./kv/run_kvpredicate_history_case.sh
```

输出：

```text
../predicateHistories/kvpredicate/kvpredicate_serializable_20260706/hist-00000
```

常用参数：

```text
CASE_NAME
    case 名，默认 kvpredicate_serializable_<YYYYMMDD>。

BUILD
    是否自动构建 BenchBase，默认 true。

LOAD
    是否 create/load kv 表，默认 true。

DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD
    数据库连接参数。DB_PASSWORD 非空时脚本会生成 case 专用 pgpass。

PGPASSFILE
    推荐显式指定已有 pgpass。

KVPREDICATE_DSN
    psql 使用的 DSN；不设置时由 DB_* 组合生成。

ISOLATION
    TRANSACTION_SERIALIZABLE、TRANSACTION_REPEATABLE_READ、TRANSACTION_READ_COMMITTED 等。

KEY_COUNT
    初始 key 数量。

KEY_DIST
    uniform、zipf/zipfian、hotspot 或兼容旧配置的 exponential。

KEY_DIST_BASE
    Zipfian 指数；参数矩阵示例使用 0.99。

MIN_TXN_LENGTH / MAX_TXN_LENGTH
    每个事务的 logical operation 数量范围。

TXNS_PER_SESSION
    每个 session 必须提交的事务数；运行结束后脚本会严格校验。

PREDICATE_READ_RATIO
    0 到 100 的谓词读操作百分比；剩余操作中点读与写各占约一半。

MAX_WRITES_PER_KEY
    单个 key 轮换前的写次数；要求表行数固定时设置为 2147483647。

PREDICATE_GROUP_COUNT
    value % m = r 谓词中的 m。

TERMINALS
    BenchBase worker 数。

TIME_SECONDS
    BenchBase 执行时长，也是达到 TXNS_PER_SESSION 的超时窗口。

RATE
    目标速率，数字或 unlimited。

KV_PREDICATE_ANOMALY
    none、write-skew 或 lost-update。

KV_PREDICATE_ANOMALY_VARIANT
    write-skew 使用 injected 或 control；control 把右侧写从 k0 改到 k2，因而不形成对应的反向 PR_RW。

KV_PREDICATE_ANOMALY_SEED
    write-skew 核心事务内谓词/写位置的可复现布局 seed。

KV_PREDICATE_ANOMALY_ISOLATE_BACKGROUND
    write-skew 默认 true；普通点操作始终避开 k0..k4，true 额外把背景谓词固定为空谓词 value < 0。

KV_PREDICATE_ANOMALY_DELAY_MS
    write-skew/lost-update 模式中的并发交错等待时间。

RANDOM_SEED
    BenchBase 普通事务随机数 seed。

EXPECTED_VERDICT
    可选；写入 manifest 的预期检测结果，例如 lost-update 使用 REJECT。
```

本地格式审计：

```bash
python3 kv/audit_kvpredicate_prhist.py \
  ../predicateHistories/kvpredicate/<case>/hist-00000
```

SER detector 审计：

```bash
cd ../SER/ser-result-detector
./gradlew jar
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit ../../predicateHistories/kvpredicate/<case>/hist-00000
```

## KV 参数矩阵配置命令

请求参数与运行变量的对应关系：

| 实验参数 | 运行变量 | 可选值 |
| --- | --- | --- |
| sessions | `TERMINALS` | `5 10 20 40 80` |
| txns/session | `TXNS_PER_SESSION` | `50 100 200 500` |
| ops/txn | `MIN_TXN_LENGTH`、`MAX_TXN_LENGTH` | `5 10 20 40`，两者设置为相同值 |
| predicate read ratio | `PREDICATE_READ_RATIO` | `20 50 80 95` |
| rows/table | `KEY_COUNT` | `1000 10000 100000 1000000` |
| distribution | `KEY_DIST` | `uniform zipfian hotspot` |

运行单组配置，例如 5 sessions、每 session 50 个事务、每事务 5 个操作、20% 谓词读、1000 行、uniform：

```bash
cd /home/lc/Desktop/predicate/History_Generator
source .tools/java23.env

PGPASSFILE=kv/.runtime/pgpass \
BUILD=true \
LOAD=true \
CASE_NAME=kv_s5_t50_o5_pr20_rows1000_uniform_$(date +%Y%m%d_%H%M%S) \
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

完整笛卡尔积共 `5 × 4 × 4 × 4 × 4 × 3 = 3840` 个 case，可用以下循环生成：

```bash
cd /home/lc/Desktop/predicate/History_Generator
source .tools/java23.env
set -euo pipefail

sessions_values=(5 10 20 40 80)
txns_values=(50 100 200 500)
ops_values=(5 10 20 40)
predicate_read_ratio_values=(20 50 80 95)
row_values=(1000 10000 100000 1000000)
distribution_values=(uniform zipfian hotspot)
build=true

for sessions in "${sessions_values[@]}"; do
  for txns in "${txns_values[@]}"; do
    for ops in "${ops_values[@]}"; do
      for predicate_read_ratio in "${predicate_read_ratio_values[@]}"; do
        for rows in "${row_values[@]}"; do
          for distribution in "${distribution_values[@]}"; do
            case_name="kv_s${sessions}_t${txns}_o${ops}_pr${predicate_read_ratio}_rows${rows}_${distribution}_$(date +%Y%m%d_%H%M%S)"
            PGPASSFILE=kv/.runtime/pgpass \
            BUILD="$build" \
            LOAD=true \
            CASE_NAME="$case_name" \
            ISOLATION=TRANSACTION_SERIALIZABLE \
            KV_PREDICATE_ANOMALY=none \
            TERMINALS="$sessions" \
            TXNS_PER_SESSION="$txns" \
            MIN_TXN_LENGTH="$ops" \
            MAX_TXN_LENGTH="$ops" \
            PREDICATE_READ_RATIO="$predicate_read_ratio" \
            KEY_COUNT="$rows" \
            KEY_DIST="$distribution" \
            KEY_DIST_BASE=0.99 \
            MAX_WRITES_PER_KEY=2147483647 \
            TIME_SECONDS="${TIME_SECONDS_PER_CASE:-60}" \
            RATE=unlimited \
            ./kv/run_kvpredicate_history_case.sh
            build=false
          done
        done
      done
    done
  done
done
```

循环中的第一个 case 使用 `BUILD=true` 构建 BenchBase，后续 case 自动使用 `BUILD=false`。`TXNS_PER_SESSION` 按成功提交计数，abort/retry 不占额度；如果某个 session 未在 `TIME_SECONDS` 内达到目标，脚本会失败并打印各 session 的实际数量。

`PREDICATE_READ_RATIO` 是逐操作概率，例如 `20` 表示全部操作中约 20% 为谓词读；剩余 80% 中点读和写各约一半，因此三类操作约为 20% 谓词读、40% 点读、40% 写。`KEY_DIST` 只控制具有单一 key 的点读和写；谓词读本身没有单一目标 key。`hotspot` 使用 80/20 规则，即约 80% 的点读和写访问前 20% active keys。为了让物理表行数保持为 `KEY_COUNT`，矩阵命令固定使用 `MAX_WRITES_PER_KEY=2147483647`。

write-skew 的两个核心事务各生成 `MAX_TXN_LENGTH` 个可见操作；lost-update 的两个核心事务仍使用固定异常操作结构。100 万行上的全表/范围谓词可能返回大量结果，应根据机器性能增大 `TIME_SECONDS_PER_CASE`。

## 运行 KV Write-Skew 对照

REPEATABLE READ：

```bash
cd History_Generator
PGPASSFILE=kv/.runtime/pgpass \
CASE_NAME=kvpredicate_repeatable_read_write_skew_20260706 \
ISOLATION=TRANSACTION_REPEATABLE_READ \
KV_PREDICATE_ANOMALY=write-skew \
KV_PREDICATE_ANOMALY_VARIANT=injected \
KV_PREDICATE_ANOMALY_SEED=17 \
KV_PREDICATE_ANOMALY_ISOLATE_BACKGROUND=true \
KV_PREDICATE_ANOMALY_DELAY_MS=1000 \
KEY_COUNT=6 \
MIN_TXN_LENGTH=15 \
MAX_TXN_LENGTH=15 \
TERMINALS=2 \
TXNS_PER_SESSION=1 \
TIME_SECONDS=5 \
RATE=2 \
KEY_DIST=uniform \
PREDICATE_GROUP_COUNT=2 \
./kv/run_kvpredicate_history_case.sh
```

SERIALIZABLE：

```bash
PGPASSFILE=kv/.runtime/pgpass \
CASE_NAME=kvpredicate_serializable_write_skew_20260706 \
ISOLATION=TRANSACTION_SERIALIZABLE \
KV_PREDICATE_ANOMALY=write-skew \
KV_PREDICATE_ANOMALY_VARIANT=injected \
KV_PREDICATE_ANOMALY_SEED=17 \
KV_PREDICATE_ANOMALY_ISOLATE_BACKGROUND=true \
KV_PREDICATE_ANOMALY_DELAY_MS=1000 \
KEY_COUNT=6 \
MIN_TXN_LENGTH=15 \
MAX_TXN_LENGTH=15 \
TERMINALS=2 \
TXNS_PER_SESSION=1 \
TIME_SECONDS=5 \
RATE=2 \
KEY_DIST=uniform \
PREDICATE_GROUP_COUNT=2 \
./kv/run_kvpredicate_history_case.sh
```

write-skew 要求 `TERMINALS >= 2`、`KEY_COUNT >= 6` 和 `MAX_TXN_LENGTH >= 2`。默认保留 `k0..k4`：`k0/k1` 构造谓词环，`k2` 是 control 写入点，`k3/k4` 是左右核心事务的填充读键；普通点操作只访问 `k5` 及以后。`injected` 产生 `left -PR_RW(k0)-> right -PR_RW(k1)-> left`，`control` 只保留 `right -PR_RW(k1)-> left`。做最小因果验证时固定 `TERMINALS=2`、`TXNS_PER_SESSION=1`，并分别运行 injected/control；转换后的 manifest 会记录核心事务、实际操作位置、预期边和是否预期成环，但不会自动写入 detector verdict。

## 生成 100 事务 KV Lost-Update 历史

该模式必须使用 `TRANSACTION_READ_COMMITTED`，并要求 `TERMINALS >= 2`、`KEY_COUNT >= 2`。推荐固定使用两个 worker：

```bash
cd /home/lc/Desktop/predicate/History_Generator
source .tools/java23.env

PGPASSFILE=kv/.runtime/pgpass \
BUILD=true \
LOAD=true \
CASE_NAME=kvpredicate_read_committed_lost_update_100_$(date +%Y%m%d_%H%M%S) \
ISOLATION=TRANSACTION_READ_COMMITTED \
KV_PREDICATE_ANOMALY=lost-update \
KV_PREDICATE_ANOMALY_DELAY_MS=250 \
KEY_COUNT=10 \
MIN_TXN_LENGTH=1 \
MAX_TXN_LENGTH=4 \
TERMINALS=2 \
TIME_SECONDS=1 \
RATE=98 \
KEY_DIST=exponential \
PREDICATE_GROUP_COUNT=4 \
EXPECTED_VERDICT=REJECT \
./kv/run_kvpredicate_history_case.sh
```

首次运行使用 `BUILD=true`；已有最新 BenchBase jar 时可以改为 `BUILD=false`。默认 `KEY_COUNT=10` 时，注入事务复用 `kv:9=9`，两个并发事务都读取 `9`，数据库实际覆盖链为 `9 → 10 → 11`。输出目录为：

```text
../predicateHistories/kvpredicate/<CASE_NAME>/hist-00000/
```

目录中包含 `history.prhist.jsonl`、`initial_state.json`、`manifest.json` 和 `raw_kvpredicate_trace.jsonl`。

## 运行 MultiKV

MultiKV 在真实 PostgreSQL 的 `users`、`items` 和 `orders` 表上混合执行点读、写和结构化 INNER JOIN 谓词读。推荐使用一键脚本：

```bash
cd /home/lc/Desktop/predicate/History_Generator
source .tools/java23.env

PGPASSFILE=multikv/.runtime/pgpass \
BUILD=true \
LOAD=true \
CASE_NAME=multikv_repeatable_read_write_skew_$(date +%Y%m%d_%H%M%S) \
ISOLATION=TRANSACTION_REPEATABLE_READ \
MULTIKV_ANOMALY=write-skew \
MULTIKV_ANOMALY_DELAY_MS=250 \
MULTIKV_TRANSACTION_COUNT=100 \
TERMINALS=2 \
TIME_SECONDS=5 \
RATE=100 \
./multikv/run_multikv_history_case.sh
```

输出：

```text
../predicateHistories/multikv/<CASE_NAME>/hist-00000/
  initial_state.json
  history.prhist.jsonl
  manifest.json
  raw_multikv_trace.jsonl
```

常用参数：

```text
MULTIKV_ANOMALY
    none、write-skew 或 lost-update。

MULTIKV_TRANSACTION_COUNT
    要求生成的已提交事务总数；大于 0 时脚本会严格校验 trace 和 manifest。

ISOLATION
    数据库隔离级别；lost-update 模式必须是 TRANSACTION_READ_COMMITTED。

MIN_TXN_LENGTH / MAX_TXN_LENGTH
    普通事务的 logical operation 数量范围。

KEY_DIST / KEY_DIST_BASE
    普通点操作使用的 key 分布及分布参数。

TERMINALS / TIME_SECONDS / RATE
    BenchBase worker 数、运行时限和目标速率。
```

`write-skew` 和 `lost-update` 至少需要两个事务；`lost-update` 使用专用键 `items:lu0`，两个核心事务读取同一初始版本后写入不同覆盖版本。异常模式的 manifest 会记录 `expected_verdict=REJECT`。

本地格式审计：

```bash
python3 multikv/audit_multikv_join_history.py \
  ../predicateHistories/multikv/<case>/hist-00000
```

生成的结构化 JOIN 历史可以直接交给 SER 或 SI detector 审计。

## 运行 TPC-C

先构建 BenchBase，然后准备 TPC-C 配置和连接：

```bash
cd History_Generator
source .tools/java23.env

export BENCHBASE_JAR=benchbase/target/benchbase-postgres/benchbase-postgres/benchbase.jar
export TPCC_DSN='postgresql://tpcc_user@127.0.0.1:5432/tpcc_trace'
export PGPASSFILE=tpcc/.runtime/pgpass
export BENCHBASE_CONFIG=tpcc/.runtime/tpcc_trace.xml

CASE_NAME=tpcc_serializable_20260706 \
./tpcc/run_tpcc_trace.sh --load
```

`--load` 会让 BenchBase 重建并加载 TPC-C 业务表，只能在实验数据库使用。省略 `--load` 时，脚本假定业务表已经创建和加载。

输出：

```text
../predicateHistories/tpcc/tpcc_serializable_20260706/hist-00000
```

TPC-C 脚本必需环境变量：

```text
BENCHBASE_JAR
    BenchBase PostgreSQL distribution jar。

TPCC_DSN
    psql 使用的 PostgreSQL DSN。

BENCHBASE_CONFIG
    本地 TPC-C XML 配置，包含 JDBC URL、用户名和密码。

CASE_NAME
    case 名，默认 tpcc_neworder_payment_stocklevel_sf1_s2_serializable。
```

本地格式审计：

```bash
python3 tpcc/audit_tpcc_prhist.py \
  ../predicateHistories/tpcc/<case>/hist-00000
```

注意：TPC-C case 当前主要用于保存真实 PostgreSQL evidence 和关系谓词 PRHIST。SER/SI loader 支持结构化多表 `query`，但 StockLevel 当前输出的是 SQL 文本，因此不能把 detector 对该历史的结果当作完整验证结论。

## 可选 Oracle

各 converter 都不会为普通 case 猜测 `ACCEPT/REJECT`。如果你已经外部证明了 expected verdict，可以传：

```bash
export EXPECTED_VERDICT=ACCEPT
export SERIAL_ORDER='73001 73002 73003'
```

当 `EXPECTED_VERDICT=ACCEPT` 时，必须提供覆盖所有事务的 `SERIAL_ORDER`。

## 常见问题

### BenchBase jar 不存在

运行：

```bash
cd History_Generator
source .tools/java23.env
cd benchbase
./mvnw -q -DskipTests -Dfmt.skip=true -Ddescriptors=src/main/assembly/dir.xml -P postgres package
```

### psql 连接失败

检查：

- `PGPASSFILE` 是否设置。
- pgpass 权限是否是 `600`。
- DSN 中 host、port、database、user 是否正确。
- 数据库用户是否有 create/load、trigger、function 权限。

### 没生成 history.prhist.jsonl

按顺序检查：

1. BenchBase execute 是否成功。
2. `sql/01_install_*_trace.sql` 是否成功执行。
3. `snapshot_initial_state()` 是否成功执行。
4. raw trace 是否为空。
5. converter 是否报 `ConversionError`。

### Workload audit 通过但 SER REJECT

这是正常情况。workload audit 只检查格式和引用一致性；SER REJECT 表示 committed history 在当前检测模型下不可串行化。
