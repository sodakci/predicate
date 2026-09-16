# 单表与多表谓词读：统一判定口径

本次修改统一的是语义，不要求所有查询采用同一种约束生成算法。
没有引入新的命令行模式、求解器、序列化图或 precedence oracle。

## 1. 唯一判定契约

一次谓词读看到的状态由全局串行顺序中各物理 key 的最新外部版本，以及本事务在该查询事件之前的最后一次自写组成。所有表共用该串行顺序，事务不会按表拆分。

在这一状态执行查询后，PRHIST 的结果必须同时满足投影结果多重集与物理贡献来源相等。`result.inputs` 只记录贡献结果的来源，不代表完整可见快照。没有 `RecordedQueryResult` 的既有程序化历史继续使用原来的 input-only 比较约定。

公共实现位于 `history.query.PredicateReadSemantics`。内部一致性检查、求解前输入检查、完整快照检查与模型 refinement 使用相同的结果比较和表归属规则。

## 2. 物理表归属

`QueryScope.relationResolver()` 返回构造该 scope 时实际使用的 resolver，`RelationScope` 不再让覆盖检查和查询执行各用一套映射。

加载的历史继续保持原有 key，不进行 key 改写：限定 key（如 `A:x`）属于 `A`；既有未限定 key（如 `k0`）属于 `kv`。这个归属不随当前查询引用的表数变化。程序化历史的固定或自定义映射也沿用其 scope 中的 resolver。

`k0` 与 `kv:k0` 不会在这次修改中被合并为一个物理 key。本次不迁移输入格式，也不改变唯一 `(key,value)` 来源契约。

## 3. 单表优化的边界

`PredicateEvaluator.isRowLocal()` 默认返回 false。只有能证明各物理行独立贡献结果的实现才能返回 true；`QueryPlan` 保留已有 AST 判断。

此能力只允许按 key 的优化与相同查询的覆盖复用，不改变可见性或结果相等规则。JOIN、单表自连接、DISTINCT 不因为表数少而被当作 row-local。

`KnownGraph` 与 `Utils` 使用同一个能力声明决定是否复用此前相同查询的逐 key 覆盖。一般查询只把查询之前真正发生的自写当作内部确定版本，不会把前次未贡献结果的行从后续快照中删掉。

## 4. 求解器组织

```text
PredicateObservation
    -> 公共来源、scope、结果输入自洽检查
    -> 可证明 row-local：现有 EAGER / GMWR 加速
       其余查询：精确 latest-visible frontier + 查询执行
    -> 同一 serializationGraph 与 solve/refine 循环
```

对于 row-local 及当前支持的 monotone QueryPlan，已记录的完整贡献来源必须能重现记录结果。这一共同检查对 EAGER/GMWR 一致。任意自定义、非单调 evaluator 不强行套用这一来源缩减，而保留完整快照检查。

`encodeSnapshotPredicate()` 直接按“是否有查询前自写”确定哪些 key 需要外部 frontier；不会把单表的重复覆盖快捷分类用作完整快照。`refinePredicateConstraints()` 直接执行公共语义检查，删除了原来的 general 专属转发层。

保留单表 GMWR bundle、WW 传播、紧凑结果表示、行贡献缓存与现有 multi-key refinement。没有将 JOIN 全部提前展开，没有引入通用 witness 传播器，也没有为单表新增持久化完整快照。

## 5. 回归与正常构建

新增 `PredicateSemanticsRegressionTest`，使用事务串行重放 oracle，并将相同查询包装成关闭单行加速的 evaluator 作差分对照。包装器仅存在于测试，不是生产开关。

测试覆盖重复 JOIN、自写、别名重命名、跨表同名局部 key、单表自连接、重复投影、DISTINCT、完整贡献来源、legacy kv key 参与多表查询、自定义表归属和跨表共同串行顺序。另复用原差分套件的 160 个内存历史、60 个 PRHIST 历史和 25 个谓词/写模式组合；复用通过测试内部反射完成，不依赖生产 API。

在项目原有可用的 JDK/Gradle 环境中运行：

```bash
./gradlew test --tests verifier.PredicateSemanticsRegressionTest
./gradlew test
./gradlew jar
```

需要更新 JAR 后再运行原来的 `audit --predicate-encoding=EAGER` 或 `audit --predicate-encoding=GMWR`。输入目录和其他运行参数不变。
