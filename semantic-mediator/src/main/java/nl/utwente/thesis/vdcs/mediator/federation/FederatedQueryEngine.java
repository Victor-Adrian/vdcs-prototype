package nl.utwente.thesis.vdcs.mediator.federation;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.StatementPatternCollector;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.eclipse.rdf4j.query.resultio.QueryResultIO;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Federates SPARQL by grouping triple patterns per source, materializing
 * matching triples in memory, and running the original query over the merged
 * graph. Variable predicates and variable rdf:type objects are not routable.
 */
public class FederatedQueryEngine {

    private static final Logger log = LoggerFactory.getLogger(FederatedQueryEngine.class);

    private final Map<String, Repository>      reposBySourceId;
    private final PredicateSourceRegistry      registry;

    /**
     * CONSTRUCT mappings that translate graph sources to the global ontology.
     * Relational repositories already apply their mappings through Ontop.
     */
    private final Map<String, String> graphMappingsBySourceId;

    public FederatedQueryEngine(Map<String, Repository> reposBySourceId,
                                PredicateSourceRegistry registry,
                                Map<String, String> graphMappingsBySourceId) {
        this.reposBySourceId         = Map.copyOf(reposBySourceId);
        this.registry                = registry;
        this.graphMappingsBySourceId = Map.copyOf(graphMappingsBySourceId);
    }

    /**
     * True when the source's mapping is applied at query time (a graph
     * source). The original query cannot be executed against such a
     * source directly - it must go through the materialize-then-rerun
     * path even when it is the only source touched.
     */
    public boolean isQueryTimeMapped(String sourceId) {
        return graphMappingsBySourceId.containsKey(sourceId);
    }

    // Entry point

    /**
     * Executes a federated SPARQL query.
     *
     * @param sparql original query, reused against the merged results
     * @return result serialised as application/sparql-results+json
     */
    public String execute(String sparql) {
        ParsedQuery parsed = new SPARQLParser().parseQuery(sparql, null);
        List<StatementPattern> patterns =
                StatementPatternCollector.process(parsed.getTupleExpr());
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException(
                    "Federated query has no triple patterns — nothing to materialize");
        }

        Map<String, List<StatementPattern>> bySource = groupBySource(patterns);

        log.info("Federated query plan: {} sources touched ({})",
                bySource.size(), bySource.keySet());

        Repository inMem = new SailRepository(new MemoryStore());
        inMem.init();
        try {
            // materialize each source group into the in-memory store.
            try (RepositoryConnection memConn = inMem.getConnection()) {
                memConn.begin();
                for (Map.Entry<String, List<StatementPattern>> e : bySource.entrySet()) {
                    materialize(memConn, e.getKey(), e.getValue());
                }
                memConn.commit();
            }

            // re-execute the original query against the merged graph.
            try (RepositoryConnection memConn = inMem.getConnection();
                 TupleQueryResult result = memConn.prepareTupleQuery(sparql).evaluate()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                QueryResultIO.writeTuple(result, TupleQueryResultFormat.JSON, baos);
                return baos.toString(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException("Federated SPARQL execution failed: " + e.getMessage(), e);
        } finally {
            inMem.shutDown();
        }
    }

    // Decomposition

    /**
     * Groups triple patterns by source. A pattern that cannot be routed
     * uniquely (variable predicate, unknown IRI, variable rdf:type object)
     * causes the whole query to be rejected.
     */
    private Map<String, List<StatementPattern>> groupBySource(List<StatementPattern> patterns) {
        Map<String, List<StatementPattern>> bySource = new LinkedHashMap<>();
        List<StatementPattern> unresolved = new ArrayList<>();
        for (StatementPattern p : patterns) {
            Optional<String> srcOpt = registry.sourceForPattern(p);
            if (srcOpt.isEmpty()) {
                unresolved.add(p);
            } else {
                bySource.computeIfAbsent(srcOpt.get(), k -> new ArrayList<>()).add(p);
            }
        }
        if (!unresolved.isEmpty()) {
            String dump = unresolved.stream()
                    .map(FederatedQueryEngine::renderTriple)
                    .collect(Collectors.joining("\n  "));
            throw new IllegalArgumentException(
                    "Federated query has triple pattern(s) that cannot be routed to a source:\n  "
                  + dump + "\n"
                  + "Variable predicates, variable rdf:type objects, and predicates outside "
                  + "the catalogue's `produces` lists are not supported.");
        }
        return bySource;
    }

    // Materialization

    /**
     * Builds and executes a CONSTRUCT query against one source's Ontop
     * repository, inserting every returned statement into the in-memory
     * store.
     */
    private void materialize(RepositoryConnection memConn,
                             String sourceId,
                             List<StatementPattern> patterns) {
        Repository repo = reposBySourceId.get(sourceId);
        if (repo == null) {
            throw new IllegalStateException(
                    "FederatedQueryEngine has no repository registered for source '"
                  + sourceId + "'");
        }
        // Graph source: run its CONSTRUCT mapping directly against the
        // source's SPARQL endpoint - the result arrives already lifted
        // into the global vocabulary. (The whole projection is fetched;
        // the superset principle above covers this, and the re-run
        // enforces the query's actual constraints.)
        String construct = graphMappingsBySourceId.containsKey(sourceId)
                ? graphMappingsBySourceId.get(sourceId)
                : buildConstruct(patterns);
        log.debug("Materializing source {}:\n{}", sourceId, construct);

        int count = 0;
        try (RepositoryConnection srcConn = repo.getConnection();
             GraphQueryResult triples = srcConn.prepareGraphQuery(construct).evaluate()) {
            while (triples.hasNext()) {
                Statement st = triples.next();
                memConn.add(st);
                count++;
            }
        }
        log.info("Materialized {} triples from source {}", count, sourceId);
    }

    /**
     * Builds a CONSTRUCT query for the supplied patterns. Literal objects are
     * replaced with variables because Ontop's CONSTRUCT path may not match
     * them against source columns. The original query applies those filters
     * after the source triples are merged. IRI constants remain unchanged for
     * routing.
     */
    private static String buildConstruct(List<StatementPattern> patterns) {
        // Assign a stable relaxation variable to each literal-object pattern,
        // reused in both the CONSTRUCT template and the WHERE block.
        String[] rendered = new String[patterns.size()];
        for (int i = 0; i < patterns.size(); i++) {
            rendered[i] = renderTripleForConstruct(patterns.get(i), i);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CONSTRUCT {\n");
        for (String t : rendered) sb.append("  ").append(t).append("\n");
        sb.append("} WHERE {\n");
        for (String t : rendered) sb.append("  ").append(t).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /** Renders a pattern, relaxing a literal object constant to ?__litN. */
    private static String renderTripleForConstruct(StatementPattern p, int idx) {
        String obj;
        Var objVar = p.getObjectVar();
        if (objVar != null && objVar.hasValue() && objVar.getValue() instanceof Literal) {
            obj = "?__lit" + idx;   // relax: fetch broadly, constrain at re-run
        } else {
            obj = renderVar(objVar);
        }
        return renderVar(p.getSubjectVar()) + " "
             + renderVar(p.getPredicateVar()) + " "
             + obj + " .";
    }

    // SPARQL rendering helpers

    static String renderTriple(StatementPattern p) {
        return renderVar(p.getSubjectVar())   + " "
             + renderVar(p.getPredicateVar()) + " "
             + renderVar(p.getObjectVar())    + " .";
    }

    private static String renderVar(Var v) {
        if (v == null) {
            throw new IllegalStateException("Null Var in StatementPattern");
        }
        if (v.hasValue()) return renderValue(v.getValue());
        // Anonymous vars from the parser get synthetic names like "-anon-1";
        // those names are stable per-query so reusing them in CONSTRUCT and
        // WHERE blocks keeps the join consistent.
        return "?" + v.getName();
    }

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private static String renderValue(Value val) {
        if (val instanceof IRI iri) {
            return "<" + iri.stringValue() + ">";
        }
        if (val instanceof Literal lit) {
            // Numeric and boolean literals must be rendered in their native
            // SPARQL form (bare 1, 95.50, true) rather than the typed-string
            // form ("1"^^xsd:integer). Ontop matches the native form against
            // the underlying database column; the typed-string form silently
            // fails to match in a CONSTRUCT WHERE, which made any cross-source
            // query with a literal integer/decimal constraint return nothing.
            if (lit.getDatatype() != null) {
                String bare = renderNativeNumericOrBoolean(lit);
                if (bare != null) return bare;
            }
            String label = "\"" + escapeLiteral(lit.getLabel()) + "\"";
            if (lit.getLanguage().isPresent()) {
                return label + "@" + lit.getLanguage().get();
            }
            String dt = lit.getDatatype() == null ? "" : lit.getDatatype().stringValue();
            // Plain xsd:string needs no datatype suffix; anything else keeps it.
            if (dt.isEmpty() || (XSD + "string").equals(dt)) {
                return label;
            }
            return label + "^^<" + dt + ">";
        }
        if (val instanceof BNode bn) {
            return "_:" + bn.getID();
        }
        throw new IllegalArgumentException("Unsupported Value kind: " + val.getClass());
    }

    /**
     * Renders an integer/decimal/boolean literal as a bare SPARQL token,
     * or returns null if the datatype is not one of those exact forms.
     * Only the exact lexical forms SPARQL recognises natively are emitted
     * bare; floats/doubles (which need an exponent to be unambiguous) and
     * other datatypes fall through to the typed form.
     */
    private static String renderNativeNumericOrBoolean(Literal lit) {
        String dt = lit.getDatatype().stringValue();
        String label = lit.getLabel().trim();
        if ((XSD + "integer").equals(dt) || (XSD + "int").equals(dt)
                || (XSD + "long").equals(dt) || (XSD + "short").equals(dt)
                || (XSD + "byte").equals(dt) || (XSD + "nonNegativeInteger").equals(dt)
                || (XSD + "positiveInteger").equals(dt) || (XSD + "unsignedInt").equals(dt)
                || (XSD + "unsignedLong").equals(dt)) {
            return label.matches("[+-]?\\d+") ? label : null;
        }
        if ((XSD + "decimal").equals(dt)) {
            return label.matches("[+-]?\\d*\\.\\d+|[+-]?\\d+") ? label : null;
        }
        if ((XSD + "boolean").equals(dt)) {
            return ("true".equals(label) || "false".equals(label)) ? label : null;
        }
        return null;
    }

    private static String escapeLiteral(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // Diagnostics

    /**
     * Returns the set of source IDs this query would touch. Public so
     * OntopMediatorService can use
     * it for single- vs cross-source routing.
     */
    public Set<String> sourcesFor(String sparql) {
        ParsedQuery parsed = new SPARQLParser().parseQuery(sparql, null);
        List<StatementPattern> patterns =
                StatementPatternCollector.process(parsed.getTupleExpr());
        return patterns.stream()
                .map(registry::sourceForPattern)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toUnmodifiableSet());
    }
}
