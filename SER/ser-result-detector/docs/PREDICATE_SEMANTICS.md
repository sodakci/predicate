# 单表与多表谓词读：统一判定口径

本次修改统一的是语义，不要求所有查询采用同一种约束生成算法。
没有引入新的命令行模式、求解器、序列化图或 precedence oracle。

## 1. 唯一判定契约

一次谓词读看到的状态由全局串行顺序中各物理 key 的最新外部版本，以及本事务在该查询事件之前的最后一次自写组成。所有表共用该串行顺序，事务不会按表拆分。

在这一状态执行查询后，PRHIST 的结果必须同时满足投影结果多重集与物理贡献来源相等。`result.inputs` 只记录贡献结果的来源，不代表完整可见快照。没有 `RecordedQueryResult` 的既有程序化历史继续使用原来的 input-only 比较约定。

公共实现位于 `history.query.PredicateReadSemantics`。内部一致性检查、求解前输入检查与独立串行执行 oracle 使用相同的结果比较和表归属规则；求解器不再执行 model refinement。

## 2. 物理表归属

`QueryScope.relationResolver()` 返回构造该 scope 时实际使用的 resolver，`RelationScope` 不再让覆盖检查和查询执行各用一套映射。

加载的历史继续保持原有 key，不进行 key 改写：限定 key（如 `A:x`）属于 `A`；既有未限定 key（如 `k0`）属于 `kv`。这个归属不随当前查询引用的表数变化。程序化历史的固定或自定义映射也沿用其 scope 中的 resolver。

`k0` 与 `kv:k0` 不会在这次修改中被合并为一个物理 key。本次不迁移输入格式，也不改变唯一 `(key,value)` 来源契约。

## 3. 单表优化的边界

`PredicateEvaluator.isRowLocal()` 默认返回 false。只有能证明各物理行独立贡献结果的实现才能返回 true；`QueryPlan` 保留已有 AST 判断。

此能力只允许按 key 的优化与相同查询的覆盖复用，不改变可见性或结果相等规则。JOIN 和单表自连接不因为表数少而被当作 row-local；当前检测器的唯一 `(key,value)` 模型不支持 DISTINCT。

`KnownGraph` 与 `Utils` 使用同一个能力声明决定是否复用此前相同查询的逐 key 覆盖。一般查询只把查询之前真正发生的自写当作内部确定版本，不会把前次未贡献结果的行从后续快照中删掉。

## 4. 求解器组织

```text
PredicateObservation
    -> 公共来源、scope、结果输入自洽检查
    -> 可证明 row-local：现有 EAGER / GMWR 加速
       受支持非 row-local 单调 QueryPlan：完整 JOIN binding 编码
       DISTINCT / 自定义全快照 evaluator：明确报不支持
    -> 同一 serializationGraph，单次 solve
```

对于 row-local 及当前支持的 monotone QueryPlan，已记录的完整贡献来源必须能重现记录结果。这一共同检查对 EAGER/GMWR 一致。声明 isRowLocal() 的程序化逐行谓词同样使用完整编码；自定义全快照或非单调 evaluator 不保留后备支持。

`encodeExplicitMultiRelationPredicate()` 枚举产生结果的物理版本 binding，约束 recorded sources 为 latest、排除额外 binding，并生成上下文守卫的 PR_WR/PR_RW。查询前自写仍作为本事务可见版本处理；所有约束在单次 SAT 求解前生成。

保留单表 GMWR obligation、预传播、紧凑结果表示与行贡献缓存。每个 item obligation 仍显式保留；删除循环后备路径及专用快照记录、模型读取和 no-good 追加方法，不保留求解后补约束。

## 5. 回归与正常构建

`PredicateSemanticsRegressionTest` 使用独立事务串行重放 oracle，比较 EAGER/GMWR 及剪枝模式的结果。强制走旧循环后备路径的 snapshot-only 包装与策略分支已删除。

测试覆盖重复 JOIN、自写、别名重命名、跨表同名局部 key、单表自连接、重复投影、完整贡献来源、legacy kv key 参与多表查询、自定义表归属和跨表共同串行顺序。另复用原差分套件的 160 个内存历史、60 个 PRHIST 历史和 25 个谓词/写模式组合；复用通过测试内部反射完成，不依赖生产 API。另有回归验证后端只调用一次、SAT 求解后不再执行谓词，以及 DISTINCT/自定义全快照形态明确报不支持。

在项目原有可用的 JDK/Gradle 环境中运行：

```bash
./gradlew test --tests verifier.PredicateSemanticsRegressionTest
./gradlew test
./gradlew jar
```

需要更新 JAR 后再运行 `audit --no-gmwr HISTORY`（EAGER）或默认的 `audit HISTORY`（GMWR + frontier + prepropagation）。输入目录和其他运行参数不变。
