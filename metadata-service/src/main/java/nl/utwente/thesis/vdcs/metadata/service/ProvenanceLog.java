package nl.utwente.thesis.vdcs.metadata.service;

import nl.utwente.thesis.vdcs.metadata.model.ProvenanceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Capacity-bounded in-memory provenance log that drops the oldest event when full. */
@Service
public class ProvenanceLog {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceLog.class);

    private final Deque<ProvenanceEvent> events = new ArrayDeque<>();
    private final int capacity;

    public ProvenanceLog(
            @Value("${vdcs.metadata.provenance.capacity:10000}") int capacity) {
        this.capacity = Math.max(100, capacity);
    }

    /** Records an event. Assigns id + timestamp if the caller did not. */
    public synchronized ProvenanceEvent record(ProvenanceEvent ev) {
        ev.ensureIdentified();
        events.addLast(ev);
        while (events.size() > capacity) events.removeFirst();
        if (log.isDebugEnabled()) {
            log.debug("Provenance recorded: op={} sources={} status={} latencyMs={}",
                    ev.getOperation(), ev.getSourcesTouched(), ev.getStatus(),
                    ev.getLatencyMs());
        }
        return ev;
    }

    /** All events newest-first. */
    public synchronized List<ProvenanceEvent> recent(int limit) {
        return events.stream()
                .sorted(Comparator.comparing(ProvenanceEvent::getTimestamp).reversed())
                .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                .toList();
    }

    /** Filter helper: events matching the given criteria. Any criterion may be null. */
    public synchronized List<ProvenanceEvent> filter(String operation,
                                                     String actor,
                                                     String sourceId,
                                                     Instant since,
                                                     int limit) {
        Stream<ProvenanceEvent> s = events.stream()
                .sorted(Comparator.comparing(ProvenanceEvent::getTimestamp).reversed());
        if (operation != null) s = s.filter(e -> operation.equalsIgnoreCase(e.getOperation()));
        if (actor     != null) s = s.filter(e -> actor.equals(e.getActor()));
        if (sourceId  != null) s = s.filter(e -> e.getSourcesTouched() != null
                                              && e.getSourcesTouched().contains(sourceId));
        if (since     != null) s = s.filter(e -> e.getTimestamp() != null
                                              && !e.getTimestamp().isBefore(since));
        return s.limit(limit <= 0 ? Long.MAX_VALUE : limit).toList();
    }

    public synchronized Optional<ProvenanceEvent> byId(String id) {
        return events.stream().filter(e -> id.equals(e.getId())).findFirst();
    }

    public synchronized int size() { return events.size(); }

    /** Lists the distinct actors that have appeared in the log. */
    public synchronized List<String> distinctActors() {
        return events.stream()
                .map(ProvenanceEvent::getActor)
                .filter(a -> a != null && !a.isBlank())
                .distinct()
                .toList();
    }

    /** Lists the distinct sources that have appeared in the log. */
    public synchronized List<String> distinctSources() {
        List<String> out = new ArrayList<>();
        for (ProvenanceEvent e : events) {
            if (e.getSourcesTouched() == null) continue;
            for (String s : e.getSourcesTouched()) {
                if (!out.contains(s)) out.add(s);
            }
        }
        return out;
    }
}
