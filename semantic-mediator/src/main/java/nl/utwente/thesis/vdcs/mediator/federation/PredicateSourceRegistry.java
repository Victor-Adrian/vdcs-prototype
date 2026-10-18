package nl.utwente.thesis.vdcs.mediator.federation;

import nl.utwente.thesis.vdcs.metadata.client.SourceCapabilityCatalogue;
import nl.utwente.thesis.vdcs.metadata.client.SourceDescriptor;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps ontology classes and predicates to the source that produces them.
 * Ambiguous or unknown patterns are left unrouted.
 */
public class PredicateSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(PredicateSourceRegistry.class);

    public static final String ONTOLOGY_NS =
            "https://thesis.utwente.nl/vdcs/ontology/university#";

    public static final String RDF_TYPE =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    /** Full IRI to source id. Built once at construction time. */
    private final Map<String, String> iriToSource;

    public PredicateSourceRegistry(SourceCapabilityCatalogue catalogue) {
        Map<String, String> map = new HashMap<>();
        for (SourceDescriptor src : catalogue.getSources()) {
            for (String localName : src.getProduces()) {
                String iri = ONTOLOGY_NS + localName;
                String prior = map.put(iri, src.getId());
                if (prior != null && !prior.equals(src.getId())) {
                    log.warn("IRI {} claimed by both {} and {} — last writer wins",
                            iri, prior, src.getId());
                }
            }
        }
        this.iriToSource = Map.copyOf(map);
        log.info("PredicateSourceRegistry initialised with {} IRI bindings across {} sources",
                iriToSource.size(), catalogue.getSources().size());
    }

    /**
     * Resolves a pattern by predicate, or by class IRI for rdf:type. Returns
     * empty for variables and unknown IRIs.
     */
    public Optional<String> sourceForPattern(StatementPattern p) {
        Var pred = p.getPredicateVar();
        if (pred == null || !pred.hasValue()) return Optional.empty();
        Value predValue = pred.getValue();
        if (!(predValue instanceof IRI)) return Optional.empty();

        String predIri = predValue.stringValue();
        if (RDF_TYPE.equals(predIri)) {
            Var obj = p.getObjectVar();
            if (obj == null || !obj.hasValue()) return Optional.empty();
            Value objValue = obj.getValue();
            if (!(objValue instanceof IRI)) return Optional.empty();
            return Optional.ofNullable(iriToSource.get(objValue.stringValue()));
        }
        return Optional.ofNullable(iriToSource.get(predIri));
    }

    /** Direct IRI lookup. Returns empty when the IRI is unknown. */
    public Optional<String> sourceForIri(String iri) {
        return Optional.ofNullable(iriToSource.get(iri));
    }

    /** All source IDs known to the registry. */
    public java.util.Set<String> knownSources() {
        return Map.copyOf(iriToSource).values().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
