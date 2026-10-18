package nl.utwente.thesis.vdcs.iam.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory audit log for authentication attempts and reported access decisions. */
@Service
public class AuthAuditLog {

    private final Deque<Map<String, Object>> events = new ArrayDeque<>();
    private final int capacity;

    public AuthAuditLog(@Value("${vdcs.iam.audit.capacity:10000}") int capacity) {
        this.capacity = Math.max(100, capacity);
    }

    public synchronized void recordAuthentication(String identity, String outcome, String note) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("timestamp", Instant.now().toString());
        ev.put("type",      "AUTHENTICATE");
        ev.put("identity",  identity);
        ev.put("outcome",   outcome);   // "SUCCESS" | "FAILURE"
        ev.put("note",      note);
        append(ev);
    }

    /** Stores an access-decision event reported by the PEP. */
    public synchronized void recordReported(Map<String, Object> ev) {
        Map<String, Object> copy = new LinkedHashMap<>(ev);
        copy.putIfAbsent("timestamp", Instant.now().toString());
        copy.putIfAbsent("type", "ACCESS");
        append(copy);
    }

    public synchronized List<Map<String, Object>> recent(int limit) {
        return events.stream()
                .sorted(Comparator.comparing(e -> e.get("timestamp").toString(),
                        Comparator.reverseOrder()))
                .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                .toList();
    }

    public synchronized int size() { return events.size(); }

    private void append(Map<String, Object> ev) {
        events.addLast(ev);
        while (events.size() > capacity) events.removeFirst();
    }
}
