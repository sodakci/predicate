package verifier;

import history.Event;
import history.History;
import history.Transaction;
import history.query.MapVisibleState;
import history.query.QueryEvaluation;
import history.query.QueryValue;
import history.query.RecordedQueryResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 小历史使用的独立 SI 执行参考模型。
 *
 * <p>该模型只读取 History 及查询 AST：它枚举事务开始/提交事件，在开始时冻结
 * 已提交快照，按事件顺序叠加事务自己的写，并在提交时执行 first-committer-wins。
 * 它不调用生产 KnownGraph、可达性 oracle、latest-visible checker 或 SAT 编码器。</p>
 *
 * <p>参考模型沿用紧凑 PRHIST 的前置条件：每个 {@code (key,value)} 唯一标识一个
 * 写版本。紧凑 row-local 结果是否忠实保存原始 values 只能通过其公开完整性位读取；
 * 快照、输入集合和结果多重集仍由本类独立重放和比较。</p>
 */
final class SIExecutionOracle {
    private static final int NOT_STARTED = 0;
    private static final int ACTIVE = 1;
    private static final int COMMITTED = 2;

    private SIExecutionOracle() {
    }

    static <K, V> boolean accepts(History<K, V> history) {
        var transactions = new ArrayList<>(history.getClientTransactions());
        transactions.sort(Comparator.comparingLong(Transaction::getId));
        for (var transaction : transactions) {
            if (transaction.getStatus() != Transaction.TransactionStatus.COMMIT) {
                return false;
            }
        }

        var index = new HashMap<Transaction<K, V>, Integer>();
        for (int i = 0; i < transactions.size(); i++) {
            index.put(transactions.get(i), i);
        }
        var predecessor = new int[transactions.size()];
        java.util.Arrays.fill(predecessor, -1);
        for (var session : history.getClientSessions()) {
            Transaction<K, V> previous = null;
            for (var transaction : session.getTransactions()) {
                var currentIndex = index.get(transaction);
                if (currentIndex == null) {
                    continue;
                }
                if (previous != null) {
                    predecessor[currentIndex] = index.get(previous);
                }
                previous = transaction;
            }
        }

        var values = new LinkedHashMap<K, V>();
        var versions = new LinkedHashMap<K, Long>();
        var bottom = history.getTransaction(-1L);
        if (bottom != null) {
            for (var event : bottom.getEvents()) {
                if (event.getType() != Event.EventType.WRITE) {
                    continue;
                }
                putValue(values, event.getKey(), event.getValue());
                versions.merge(event.getKey(), 1L, Long::sum);
            }
        }

        return search(transactions, predecessor, new int[transactions.size()],
                new HashMap<>(), values, versions);
    }

    private static <K, V> boolean search(
            List<Transaction<K, V>> transactions,
            int[] predecessor,
            int[] states,
            Map<Integer, ActiveTransaction<K, V>> active,
            Map<K, V> committedValues,
            Map<K, Long> committedVersions) {
        boolean complete = true;
        for (var state : states) {
            if (state != COMMITTED) {
                complete = false;
                break;
            }
        }
        if (complete) {
            return true;
        }

        for (int i = 0; i < transactions.size(); i++) {
            if (states[i] == NOT_STARTED
                    && (predecessor[i] < 0 || states[predecessor[i]] == COMMITTED)) {
                var replay = replay(transactions.get(i), committedValues, committedVersions);
                if (replay == null) {
                    continue;
                }
                var nextStates = states.clone();
                nextStates[i] = ACTIVE;
                var nextActive = new HashMap<>(active);
                nextActive.put(i, replay);
                if (search(transactions, predecessor, nextStates, nextActive,
                        committedValues, committedVersions)) {
                    return true;
                }
            }

            if (states[i] != ACTIVE) {
                continue;
            }
            var transaction = active.get(i);
            if (!canCommit(transaction, committedVersions)) {
                continue;
            }
            var nextValues = new LinkedHashMap<>(committedValues);
            var nextVersions = new LinkedHashMap<>(committedVersions);
            for (var write : transaction.finalWrites.entrySet()) {
                putValue(nextValues, write.getKey(), write.getValue());
                nextVersions.merge(write.getKey(), 1L, Long::sum);
            }
            var nextStates = states.clone();
            nextStates[i] = COMMITTED;
            var nextActive = new HashMap<>(active);
            nextActive.remove(i);
            if (search(transactions, predecessor, nextStates, nextActive,
                    nextValues, nextVersions)) {
                return true;
            }
        }
        return false;
    }

    private static <K, V> ActiveTransaction<K, V> replay(
            Transaction<K, V> transaction,
            Map<K, V> snapshot,
            Map<K, Long> snapshotVersions) {
        var visible = new LinkedHashMap<>(snapshot);
        var writes = new LinkedHashMap<K, V>();
        for (var event : transaction.getEvents()) {
            switch (event.getType()) {
            case READ:
                if (!Objects.equals(event.getValue(), visible.get(event.getKey()))) {
                    return null;
                }
                break;
            case WRITE:
                putValue(visible, event.getKey(), event.getValue());
                writes.put(event.getKey(), event.getValue());
                break;
            case PREDICATE_READ:
                if (!matchesPredicate(event, visible)) {
                    return null;
                }
                break;
            default:
                throw new AssertionError(event.getType());
            }
        }
        return new ActiveTransaction<>(new LinkedHashMap<>(snapshotVersions), writes);
    }

    private static <K, V> boolean matchesPredicate(
            Event<K, V> event, Map<K, V> visible) {
        QueryEvaluation<K, V> actual = event.getPredicate().evaluate(
                new MapVisibleState<>(visible,
                        event.getPredicate().scope().relationResolver()));

        var expectedInputs = new LinkedHashMap<K, V>();
        for (var input : event.getPredResults()) {
            if (expectedInputs.containsKey(input.getKey())) {
                return false;
            }
            expectedInputs.put(input.getKey(), input.getValue());
        }
        if (!sameMap(actual.inputs(), expectedInputs)) {
            return false;
        }

        RecordedQueryResult<K, V> recorded = event.getRecordedPredicateResult();
        if (recorded == null) {
            return true;
        }
        if (!sameMap(recorded.inputs(), expectedInputs)
                || !sameMap(actual.inputs(), recorded.inputs())
                || !multiset(actual.values()).equals(multiset(recorded.values()))) {
            return false;
        }
        // 紧凑结果在 loader 中另存一个“原始 values 是否可由 inputs 推导”的位；
        // 公共接口不暴露该位，只在这里读取其输入完整性，不复用任何生产约束逻辑。
        return !recorded.isCompact() || recorded.canonicalEquals(actual);
    }

    private static <K, V> boolean canCommit(
            ActiveTransaction<K, V> transaction,
            Map<K, Long> committedVersions) {
        for (var key : transaction.finalWrites.keySet()) {
            long atStart = transaction.snapshotVersions.getOrDefault(key, 0L);
            long now = committedVersions.getOrDefault(key, 0L);
            if (atStart != now) {
                return false;
            }
        }
        return true;
    }

    private static <K, V> boolean sameMap(Map<K, V> left, Map<K, V> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (var entry : left.entrySet()) {
            if (!right.containsKey(entry.getKey())
                    || !Objects.equals(entry.getValue(), right.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<QueryValue, Integer> multiset(List<QueryValue> values) {
        var result = new LinkedHashMap<QueryValue, Integer>();
        for (var value : values) {
            result.merge(value, 1, Integer::sum);
        }
        return result;
    }

    private static <K, V> void putValue(Map<K, V> values, K key, V value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    private static final class ActiveTransaction<K, V> {
        private final Map<K, Long> snapshotVersions;
        private final Map<K, V> finalWrites;

        private ActiveTransaction(
                Map<K, Long> snapshotVersions, Map<K, V> finalWrites) {
            this.snapshotVersions = snapshotVersions;
            this.finalWrites = finalWrites;
        }
    }
}
