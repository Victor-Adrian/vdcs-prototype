package nl.utwente.thesis.vdcs.secret.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Capacity-bounded audit log for credential requests. Secret values are never stored. */
@Service
public class CredentialAuditLog {

    private final Deque<Map<String, Object>> events = new ArrayDeque<>();
    private final int capacity;

    public CredentialAuditLog(
            @Value("${vdcs.secret.audit.capacity:5000}") int capacity) {
        this.capacity = Math.max(100, capacity);
    }

    public synchronized void record(String sourceId, String serviceTokenAlias,
                                    String outcome, String note) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("timestamp",   Instant.now().toString());
        ev.put("sourceId",    sourceId);
        ev.put("caller",      serviceTokenAlias);   // alias, never the raw token
        ev.put("outcome",     outcome);             // "GRANTED" | "DENIED"
        ev.put("note",        note);
        events.addLast(ev);
        while (events.size() > capacity) events.removeFirst();
    }

    public synchronized List<Map<String, Object>> recent(int limit) {
        return events.stream()
                .sorted(Comparator.comparing(e -> e.get("timestamp").toString(),
                        Comparator.reverseOrder()))
                .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                .toList();
    }

    public synchronized int size() { return events.size(); }
}
