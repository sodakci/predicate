package history;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class History<KeyType, ValueType> {
	private final Map<Long, Session<KeyType, ValueType>> sessions = new HashMap<>();
	private final Map<Long, Transaction<KeyType, ValueType>> transactions = new HashMap<>();
	private final Set<Long> writeIds = new HashSet<>();
	private final Set<Pair<KeyType, ValueType>> seenWrites = new HashSet<>();

	public History(Set<Long> sessions,
			Map<Long, List<Long>> transactions,
			Map<Long, List<Triple<Event.EventType, KeyType, ValueType>>> events) {
		var sessionMap = sessions.stream()
			.map(id -> Pair.of(id, addSession(id)))
			.collect(Collectors.toMap(Pair::getKey, Pair::getValue));

		var txnMap = transactions.entrySet().stream()
			.flatMap(e -> e.getValue().stream().map(id -> {
				var s = sessionMap.get(e.getKey());
				return Pair.of(id, addTransaction(s, id));
			})).collect(Collectors.toMap(Pair::getKey, Pair::getValue));

		events.forEach((id, list) -> list.forEach(e -> addEvent(
			txnMap.get(id), e.getLeft(), e.getMiddle(), e.getRight()
		)));
	}

	public Collection<Session<KeyType, ValueType>> getSessions() {
		return sessions.values();
	}

	public Collection<Session<KeyType, ValueType>> getClientSessions() {
		return sessions.values().stream()
			.filter(session -> !isInternalInitSession(session))
			.collect(Collectors.toList());
	}

	public Collection<Transaction<KeyType, ValueType>> getTransactions() {
		return transactions.values();
	}

	public Collection<Transaction<KeyType, ValueType>> getClientTransactions() {
		return transactions.values().stream()
			.filter(transaction -> !isInternalInitTransaction(transaction))
			.collect(Collectors.toList());
	}

	public Collection<Event<KeyType, ValueType>> getEvents() {
		return transactions.values().stream().flatMap(txn -> txn.events.stream()).collect(Collectors.toList());
	}

	private static boolean isInternalInitSession(Session<?, ?> session) {
		return session.getId() == -1L;
	}

	private static boolean isInternalInitTransaction(Transaction<?, ?> transaction) {
		return transaction.getId() == -1L && isInternalInitSession(transaction.getSession());
	}

	public Session<KeyType, ValueType> getSession(long id) {
		return sessions.get(id);
	}

	public Transaction<KeyType, ValueType> getTransaction(long id) {
		return transactions.get(id);
	}

	public Session<KeyType, ValueType> addSession(long id) {
		if (sessions.containsKey(id)) {
			throw new InvalidHistoryError();
		}

		var session = new Session<KeyType, ValueType>(id);
		sessions.put(id, session);
		return session;
	}

	public Transaction<KeyType, ValueType> addTransaction(Session<KeyType, ValueType> session, long id) {
		if (!sessions.containsKey(session.id) || transactions.containsKey(id)) {
			throw new InvalidHistoryError();
		}

		var txn = new Transaction<KeyType, ValueType>(id, session);
		transactions.put(id, txn);
		session.getTransactions().add(txn);
		return txn;
	}

	public Event<KeyType, ValueType> addEvent(Transaction<KeyType, ValueType> transaction, Event.EventType type, KeyType key,
			ValueType value) {
		return addEvent(transaction, type, key, value, null, null, null, null);
	}

	public Event<KeyType, ValueType> addWriteEvent(Transaction<KeyType, ValueType> transaction,
			KeyType key, ValueType value, Long writeId) {
		return addEvent(transaction, Event.EventType.WRITE, key, value, writeId, null, null, null);
	}

	public Event<KeyType, ValueType> addReadEvent(Transaction<KeyType, ValueType> transaction,
			KeyType key, ValueType value, Long sourceWriteId, Long sourceTxnId, Integer sourceOpIndex) {
		return addEvent(transaction, Event.EventType.READ, key, value, null, sourceWriteId, sourceTxnId, sourceOpIndex);
	}

	private Event<KeyType, ValueType> addEvent(Transaction<KeyType, ValueType> transaction, Event.EventType type,
			KeyType key, ValueType value, Long writeId, Long sourceWriteId, Long sourceTxnId, Integer sourceOpIndex) {
		if (!transactions.containsKey(transaction.id)) {
			throw new InvalidHistoryError();
		}
		if (type == Event.EventType.WRITE) {
			if (writeId != null) {
				if (!writeIds.add(writeId)) {
					throw new InvalidHistoryError();
				}
			} else {
				var p = Pair.of(key, value);
				if (seenWrites.contains(p)) {
					throw new InvalidHistoryError();
				}
				seenWrites.add(p);
			}
		}

		var ev = new Event<KeyType, ValueType>(transaction, type, key, value, writeId, sourceWriteId,
				sourceTxnId, sourceOpIndex);
		transaction.getEvents().add(ev);
		return ev;
	}

    public Event<KeyType, ValueType> addPredicateReadEvent(Transaction<KeyType, ValueType> transaction,
            Event.PredEval<KeyType, ValueType> predicate,
            Collection<Event.PredResult<KeyType, ValueType>> predResults) {
        if (!transactions.containsKey(transaction.id)) {
            throw new InvalidHistoryError();
        }

        var ev = new Event<KeyType, ValueType>(transaction, Event.EventType.PREDICATE_READ, null, null, predicate,
                predResults);
        transaction.getEvents().add(ev);
        return ev;
    }
}
