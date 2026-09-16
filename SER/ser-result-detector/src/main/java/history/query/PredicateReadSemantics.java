package history.query;

import history.Event;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Common predicate-read contract for accelerated and whole-snapshot checking.
 * The caller supplies the latest-visible external versions overlaid with writes
 * before this query event. Result inputs are contributing sources, not a full
 * snapshot. Recorded results compare both projected bags and physical sources.
 */
public final class PredicateReadSemantics {
    private PredicateReadSemantics() {
    }

    public static <K, V> RelationResolver<K> relationResolverFor(Event<K, V> event) {
        return event.getPredicate().scope().relationResolver();
    }

    public static <K, V> Map<K, V> expectedPredicateInputs(Event<K, V> event) {
        var recorded = event.getRecordedPredicateResult();
        if (recorded != null) {
            return recorded.inputs();
        }
        var inputs = new LinkedHashMap<K, V>();
        for (var result : event.getPredResults()) {
            if (inputs.containsKey(result.getKey())) {
                throw new QueryException("duplicate predicate input key " + result.getKey());
            }
            inputs.put(result.getKey(), result.getValue());
        }
        return inputs;
    }

    /** Returns null for an invalid query evaluation, never an empty success. */
    public static <K, V> QueryEvaluation<K, V> evaluatePredicateSnapshot(
            Event<K, V> event, Map<K, V> snapshot) {
        try {
            return event.getPredicate().evaluate(
                    new MapVisibleState<>(snapshot, relationResolverFor(event)));
        } catch (QueryException exception) {
            return null;
        }
    }

    public static <K, V> boolean predicateEvaluationMatches(
            Event<K, V> event, QueryEvaluation<K, V> evaluation) {
        if (evaluation == null) {
            return false;
        }
        var recorded = event.getRecordedPredicateResult();
        if (recorded != null) {
            return evaluation.canonicalEquals(recorded);
        }
        // Preserve the existing input-only contract of programmatic histories.
        try {
            return evaluation.inputs().equals(expectedPredicateInputs(event));
        } catch (QueryException exception) {
            return false;
        }
    }

    public static <K, V> boolean predicateSnapshotMatches(
            Event<K, V> event, Map<K, V> snapshot) {
        return predicateEvaluationMatches(event, evaluatePredicateSnapshot(event, snapshot));
    }
}
