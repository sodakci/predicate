# History Generator 操作手册

本目录用于运行改造后的 BenchBase workload，在真实 PostgreSQL 上采集事务历史，并生成：

```text
/home/lc/Desktop/predicate/PolySIHistories/<workload>/<case>/hist-00000
```

当前支持两个 workload：

- `kvpredicate`：KV 谓词读 workload，可直接生成当前 SER detector 支持的紧凑 PRHIST。
- `tpcc`：真实 TPC-C 多表 workload，生成真实 PostgreSQL raw evidence 和 PRHIST case；StockLevel 使用关系谓词证据，当前 SER loader 仍需扩展后才能完整验证。

改造细节分别见：

```text
kv/README.md
tpcc/README.md
```

## 目录

```text
History_Generator/
  README.md
  benchbase/                    BenchBase 源码和改造后的 workload
  kv/                           KV predicate 运行、trace、转换、审计
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
  raw_tpcc_trace.jsonl          # TPC-C case
```

## 环境准备

所有命令默认从这里执行：

```bash
cd /home/lc/Desktop/predicate/History_Generator
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
cd /home/lc/Desktop/predicate/History_Generator/.tools
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
tpcc/.runtime/pgpass
```

权限必须是：

```bash
chmod 600 kv/.runtime/pgpass
chmod 600 tpcc/.runtime/pgpass
```

`.pgpass` 格式：

```text
host:port:database:user:password
```

## 构建 BenchBase

```bash
cd /home/lc/Desktop/predicate/History_Generator
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

KV 一键脚本默认会自动构建；TPC-C 脚本需要显式设置 `BENCHBASE_JAR`。

## 运行 KV Predicate

推荐使用一键脚本：

```bash
cd /home/lc/Desktop/predicate/History_Generator
PGPASSFILE=/home/lc/Desktop/predicate/History_Generator/kv/.runtime/pgpass \
CASE_NAME=kvpredicate_serializable_20260706 \
ISOLATION=TRANSACTION_SERIALIZABLE \
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
/home/lc/Desktop/predicate/PolySIHistories/kvpredicate/kvpredicate_serializable_20260706/hist-00000
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
    uniform、exponential 或 zipf。

MIN_TXN_LENGTH / MAX_TXN_LENGTH
    每个事务的 logical operation 数量范围。

PREDICATE_GROUP_COUNT
    value % m = r 谓词中的 m。

TERMINALS
    BenchBase worker 数。

TIME_SECONDS
    执行时长。

RATE
    目标速率，数字或 unlimited。

KV_PREDICATE_ANOMALY
    none 或 write-skew。

KV_PREDICATE_ANOMALY_DELAY_MS
    write-skew 模式中 barrier 后的等待时间。
```

本地格式审计：

```bash
python3 kv/audit_kvpredicate_prhist.py \
  /home/lc/Desktop/predicate/PolySIHistories/kvpredicate/<case>/hist-00000
```

SER detector 审计：

```bash
cd /home/lc/Desktop/predicate/SER/ser-result-detector
./gradlew jar
java -Djava.library.path=build/monosat -Xmx8g \
  -jar build/libs/ser-result-detector-1.0.0-SNAPSHOT.jar \
  audit /home/lc/Desktop/predicate/PolySIHistories/kvpredicate/<case>/hist-00000
```

## 运行 KV Write-Skew 对照

REPEATABLE READ：

```bash
cd /home/lc/Desktop/predicate/History_Generator
PGPASSFILE=/home/lc/Desktop/predicate/History_Generator/kv/.runtime/pgpass \
CASE_NAME=kvpredicate_repeatable_read_write_skew_20260706 \
ISOLATION=TRANSACTION_REPEATABLE_READ \
KV_PREDICATE_ANOMALY=write-skew \
KV_PREDICATE_ANOMALY_DELAY_MS=1000 \
KEY_COUNT=2 \
MIN_TXN_LENGTH=1 \
MAX_TXN_LENGTH=4 \
TERMINALS=2 \
TIME_SECONDS=5 \
RATE=2 \
KEY_DIST=uniform \
PREDICATE_GROUP_COUNT=2 \
./kv/run_kvpredicate_history_case.sh
```

SERIALIZABLE：

```bash
PGPASSFILE=/home/lc/Desktop/predicate/History_Generator/kv/.runtime/pgpass \
CASE_NAME=kvpredicate_serializable_write_skew_20260706 \
ISOLATION=TRANSACTION_SERIALIZABLE \
KV_PREDICATE_ANOMALY=write-skew \
KV_PREDICATE_ANOMALY_DELAY_MS=1000 \
KEY_COUNT=2 \
MIN_TXN_LENGTH=1 \
MAX_TXN_LENGTH=4 \
TERMINALS=2 \
TIME_SECONDS=5 \
RATE=2 \
KEY_DIST=uniform \
PREDICATE_GROUP_COUNT=2 \
./kv/run_kvpredicate_history_case.sh
```

建议 `TERMINALS=2`。更多 worker 可能在脚本化事务等待时抢先更新 `k0/k1`，导致异常核心事务进入 retry/abort。

## 运行 TPC-C

先构建 BenchBase，然后准备 TPC-C 配置和连接：

```bash
cd /home/lc/Desktop/predicate/History_Generator
source .tools/java23.env

export BENCHBASE_JAR=/home/lc/Desktop/predicate/History_Generator/benchbase/target/benchbase-postgres/benchbase-postgres/benchbase.jar
export TPCC_DSN='postgresql://tpcc_user@127.0.0.1:5432/tpcc_trace'
export PGPASSFILE=/home/lc/Desktop/predicate/History_Generator/tpcc/.runtime/pgpass
export BENCHBASE_CONFIG=/home/lc/Desktop/predicate/History_Generator/tpcc/.runtime/tpcc_trace.xml

CASE_NAME=tpcc_serializable_20260706 \
./tpcc/run_tpcc_trace.sh --load
```

`--load` 会让 BenchBase 重建并加载 TPC-C 业务表，只能在实验数据库使用。省略 `--load` 时，脚本假定业务表已经创建和加载。

输出：

```text
/home/lc/Desktop/predicate/PolySIHistories/tpcc/tpcc_serializable_20260706/hist-00000
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
  /home/lc/Desktop/predicate/PolySIHistories/tpcc/<case>/hist-00000
```

注意：TPC-C case 当前主要用于保存真实 PostgreSQL evidence 和关系谓词 PRHIST。当前 SER loader 只支持 KV value 谓词，不能把它对 TPC-C StockLevel 的结果当作完整验证结论。

## 可选 Oracle

两个 converter 都不会猜测 `ACCEPT/REJECT`。如果你已经外部证明了 expected verdict，可以传：

```bash
export EXPECTED_VERDICT=ACCEPT
export SERIAL_ORDER='73001 73002 73003'
```

当 `EXPECTED_VERDICT=ACCEPT` 时，必须提供覆盖所有事务的 `SERIAL_ORDER`。

## 常见问题

### BenchBase jar 不存在

运行：

```bash
cd /home/lc/Desktop/predicate/History_Generator
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
