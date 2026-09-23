package history.query;

import history.Event;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Common predicate-read contract shared by the SI row-local accelerator and
 * whole-snapshot / multi-relation encoders. Result inputs are contributing
 * physical sources, not a complete visible snapshot.
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

    public static <K, V> QueryEvaluation<K, V> evaluatePredicateSnapshot(
            Event<K, V> event, Map<K, V> snapshot) {
        return evaluatePredicateSnapshot(event, snapshot, relationResolverFor(event));
    }

    public static <K, V> QueryEvaluation<K, V> evaluatePredicateSnapshot(
            Event<K, V> event, Map<K, V> snapshot,
            RelationResolver<K> relationResolver) {
        try {
            return event.getPredicate().evaluate(
                    new MapVisibleState<>(snapshot, relationResolver));
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

    public static <K, V> boolean predicateSnapshotMatches(
            Event<K, V> event, Map<K, V> snapshot,
            RelationResolver<K> relationResolver) {
        return predicateEvaluationMatches(
                event, evaluatePredicateSnapshot(event, snapshot, relationResolver));
    }
}
