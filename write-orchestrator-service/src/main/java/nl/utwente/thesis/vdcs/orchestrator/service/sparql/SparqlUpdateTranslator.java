package nl.utwente.thesis.vdcs.orchestrator.service.sparql;

import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate.OperationType;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.algebra.InsertData;
import org.eclipse.rdf4j.query.algebra.DeleteData;
import org.eclipse.rdf4j.query.algebra.Modify;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.UpdateExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.StatementPatternCollector;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts supported SPARQL Update forms into source-independent canonical
 * updates. WHERE clauses are evaluated through the mediator before routing.
 */
@Component
public class SparqlUpdateTranslator {

    private static final Logger log = LoggerFactory.getLogger(SparqlUpdateTranslator.class);

    private final MediatorSelectClient mediator;

    public SparqlUpdateTranslator(MediatorSelectClient mediator) {
        this.mediator = mediator;
    }

    private static final String RDF_TYPE  = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String ONT_NS    = "https://thesis.utwente.nl/vdcs/ontology/university#";
    private static final String RES_NS    = "https://thesis.utwente.nl/vdcs/resource/";

    /** Maps a URI-template segment to a canonical entity type. */
    private static final Map<String, String> SEGMENT_TO_ENTITY = Map.ofEntries(
            Map.entry("student",          "Student"),
            Map.entry("programme",        "Programme"),
            Map.entry("enrollment",       "Enrollment"),
            Map.entry("course",           "Course"),
            Map.entry("assessment",       "Assessment"),
            Map.entry("grade",            "Grade"),
            Map.entry("final-grade",      "FinalGrade"),
            Map.entry("activity",         "StudentActivity"),
            Map.entry("club-membership",  "ClubMembership"),
            Map.entry("academic-project", "AcademicProject"),
            Map.entry("publication",      "Publication")
    );

    /** Maps a URI-template segment to the name of the canonical id field. */
    private static final Map<String, String> SEGMENT_TO_ID_FIELD = Map.ofEntries(
            Map.entry("student",          "studentId"),
            Map.entry("programme",        "programmeId"),
            Map.entry("enrollment",       "enrollmentId"),
            Map.entry("course",           "courseId"),
            Map.entry("assessment",       "assessmentId"),
            Map.entry("grade",            "gradeId"),
            // FinalGrade uses the composite natural key "{studentId}-{courseId}"
            // (the IRI's id segment). The SACs split it back into the two ids.
            Map.entry("final-grade",      "finalGradeKey"),
            Map.entry("activity",         "activityStudentId"),
            Map.entry("club-membership",  "id"),
            Map.entry("academic-project", "id"),
            Map.entry("publication",      "publicationId")
    );

    /**
     * Maps ontology links to canonical foreign-key fields. Parent-to-child
     * links are ignored because the child carries its own foreign key as a
     * data property.
     */
    private static final Map<String, String> OBJECT_PROPERTY_TO_FK_FIELD = Map.of(
            "enrolledInProgramme", "programmeId",
            "enrolledInCourse",    "courseId",
            "partOfCourse",        "courseId",
            "assessedBy",          "assessmentId"
    );

    /** Convenience overload - anonymous actor (used by tests / non-IAM callers). */
    public List<CanonicalUpdate> translate(String sparqlUpdate) {
        return translate(sparqlUpdate, null);
    }

    /**
     * Parses a SPARQL Update string and returns the list of canonical
     * updates it implies. Order matches the order of the update operations
     * in the source string. actor is forwarded on the WHERE-clause
     * evaluation so the read is attributed correctly in provenance.
     */
    public List<CanonicalUpdate> translate(String sparqlUpdate, String actor) {
        ParsedUpdate parsed;
        try {
            parsed = new SPARQLParser().parseUpdate(sparqlUpdate, null);
        } catch (MalformedQueryException e) {
            throw new IllegalArgumentException("Malformed SPARQL Update: " + e.getMessage(), e);
        }

        List<UpdateExpr> exprs = parsed.getUpdateExprs();
        boolean hasModify = exprs.stream().anyMatch(e -> e instanceof Modify);
        if (hasModify && exprs.size() != 1) {
            throw new IllegalArgumentException(
                    "A DELETE/INSERT … WHERE request must contain exactly one update operation "
                  + "(the WHERE clause is extracted from the request text). Split multiple "
                  + "operations into separate requests.");
        }

        List<CanonicalUpdate> out = new ArrayList<>();
        for (UpdateExpr expr : exprs) {
            if (expr instanceof InsertData ins) {
                out.addAll(translateInsertData(ins));
            } else if (expr instanceof DeleteData del) {
                out.addAll(translateDeleteData(del));
            } else if (expr instanceof Modify mod) {
                out.addAll(translateModify(mod, sparqlUpdate, actor));
            } else {
                throw new IllegalArgumentException(
                        "Unsupported SPARQL Update operation: " + expr.getClass().getSimpleName());
            }
        }
        return out;
    }

    // INSERT DATA

    private List<CanonicalUpdate> translateInsertData(InsertData ins) {
        List<Statement> stmts = parseDataBlock(ins.getDataBlock());
        return groupBySubject(stmts).entrySet().stream()
                .map(e -> buildInsert(e.getKey(), e.getValue()))
                .toList();
    }

    private CanonicalUpdate buildInsert(String subjectIri, List<Statement> stmts) {
        EntityRef ref = parseSubjectIri(subjectIri);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ref.idField, ref.id);

        for (Statement st : stmts) {
            String predIri = st.getPredicate().stringValue();
            if (RDF_TYPE.equals(predIri)) continue;       // already encoded by IRI template
            String localName  = localName(predIri);
            // Foreign-key links: rename the ontology property to the FK data field
            String payloadKey = OBJECT_PROPERTY_TO_FK_FIELD.getOrDefault(localName, localName);
            payload.put(payloadKey, valueToObject(st.getObject()));
        }

        return new CanonicalUpdate(OperationType.INSERT, ref.entityType, payload, Map.of());
    }

    // DELETE DATA / DELETE WHERE

    private List<CanonicalUpdate> translateDeleteData(DeleteData del) {
        List<Statement> stmts = parseDataBlock(del.getDataBlock());
        return groupBySubject(stmts).keySet().stream()
                .map(this::buildDelete)
                .toList();
    }

    private CanonicalUpdate buildDelete(String subjectIri) {
        EntityRef ref = parseSubjectIri(subjectIri);
        Map<String, Object> filter = Map.of(ref.idField, ref.id);
        return new CanonicalUpdate(OperationType.DELETE, ref.entityType, Map.of(), filter);
    }

    // DELETE { ... } INSERT { ... } WHERE { ... }

    /**
     * Evaluates the WHERE clause, substitutes each binding into the templates,
     * and groups results by subject. Matched delete/insert pairs become
     * updates; unpaired templates remain inserts or deletes.
     */
    private List<CanonicalUpdate> translateModify(Modify mod, String originalSparql, String actor) {
        String whereBlock = extractWhereBlock(originalSparql);
        String prefixes   = extractPrefixLines(originalSparql);
        String select     = prefixes + "\nSELECT * WHERE " + whereBlock;

        List<Map<String, Value>> bindings = mediator.select(select, actor);
        log.info("DELETE/INSERT WHERE: WHERE matched {} binding(s)", bindings.size());

        List<StatementPattern> deleteTemplate = patternsOf(mod.getDeleteExpr());
        List<StatementPattern> insertTemplate = patternsOf(mod.getInsertExpr());
        boolean hasDelete = !deleteTemplate.isEmpty();

        // Dedup identical (op, entity, id) updates that several bindings may
        // produce (e.g. when ?old is selected but irrelevant to the key).
        Map<String, CanonicalUpdate> bySignature = new LinkedHashMap<>();

        for (Map<String, Value> binding : bindings) {
            Map<String, List<Value[]>> deleteGroups = substituteGroup(deleteTemplate, binding);
            Map<String, List<Value[]>> insertGroups = substituteGroup(insertTemplate, binding);

            java.util.Set<String> subjects = new java.util.LinkedHashSet<>();
            subjects.addAll(insertGroups.keySet());
            subjects.addAll(deleteGroups.keySet());

            for (String subjectIri : subjects) {
                EntityRef ref = parseSubjectIri(subjectIri);
                Map<String, Object> insertPayload = toPayload(insertGroups.get(subjectIri));

                CanonicalUpdate u;
                if (!insertPayload.isEmpty()) {
                    if (hasDelete) {
                        // property-update pattern: UPDATE the inserted fields
                        u = new CanonicalUpdate(OperationType.UPDATE, ref.entityType,
                                insertPayload, Map.of(ref.idField, ref.id));
                    } else {
                        // pure INSERT ... WHERE: INSERT (carry the id in the payload)
                        Map<String, Object> payload = new LinkedHashMap<>(insertPayload);
                        payload.putIfAbsent(ref.idField, ref.id);
                        u = new CanonicalUpdate(OperationType.INSERT, ref.entityType, payload, Map.of());
                    }
                } else if (deleteGroups.containsKey(subjectIri)) {
                    // pure DELETE ... WHERE: row-level delete keyed by id
                    u = new CanonicalUpdate(OperationType.DELETE, ref.entityType,
                            Map.of(), Map.of(ref.idField, ref.id));
                } else {
                    continue;
                }
                bySignature.put(u.getOperation() + "|" + ref.entityType + "|" + ref.id, u);
            }
        }
        return new ArrayList<>(bySignature.values());
    }

    /** Collects the StatementPatterns of a DELETE/INSERT template (null becomes empty). */
    private static List<StatementPattern> patternsOf(org.eclipse.rdf4j.query.algebra.TupleExpr expr) {
        if (expr == null) return List.of();
        return StatementPatternCollector.process(expr);
    }

    /**
     * Substitutes a binding into a template's patterns and groups the
     * resulting concrete triples by subject IRI. Each value is a
     * [predicateIRI, object] pair. Patterns whose subject or
     * predicate cannot be resolved (unbound variable) are skipped.
     */
    private static Map<String, List<Value[]>> substituteGroup(List<StatementPattern> template,
                                                              Map<String, Value> binding) {
        Map<String, List<Value[]>> groups = new LinkedHashMap<>();
        for (StatementPattern p : template) {
            Value subj = resolve(p.getSubjectVar(),  binding);
            Value pred = resolve(p.getPredicateVar(), binding);
            Value obj  = resolve(p.getObjectVar(),    binding);
            if (!(subj instanceof IRI) || !(pred instanceof IRI) || obj == null) continue;
            groups.computeIfAbsent(subj.stringValue(), k -> new ArrayList<>())
                  .add(new Value[]{ pred, obj });
        }
        return groups;
    }

    /** A Var resolves to its constant value, or to the binding value, or null. */
    private static Value resolve(Var v, Map<String, Value> binding) {
        if (v == null) return null;
        if (v.hasValue()) return v.getValue();
        return binding.get(v.getName());
    }

    /** Turns a subject's [pred, obj] pairs into a canonical payload (skips rdf:type). */
    private Map<String, Object> toPayload(List<Value[]> predObjs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (predObjs == null) return payload;
        for (Value[] po : predObjs) {
            String predIri = po[0].stringValue();
            if (RDF_TYPE.equals(predIri)) continue;
            String local = localName(predIri);
            String key   = OBJECT_PROPERTY_TO_FK_FIELD.getOrDefault(local, local);
            payload.put(key, valueToObject(po[1]));
        }
        return payload;
    }

    // WHERE / PREFIX textual extraction

    /**
     * Extracts the outermost { ... } block that follows the
     * top-level WHERE keyword, preserving FILTER/OPTIONAL/UNION
     * verbatim (so the mediator evaluates them). Brace-balanced; the only
     * depth-0 WHERE in a modify is the clause we want.
     */
    private static String extractWhereBlock(String sparql) {
        int i = indexOfKeywordAtDepthZero(sparql, "WHERE");
        if (i < 0) {
            throw new IllegalArgumentException("Could not locate the WHERE clause in the update");
        }
        int brace = sparql.indexOf('{', i);
        if (brace < 0) throw new IllegalArgumentException("WHERE clause has no '{'");
        int depth = 0;
        for (int j = brace; j < sparql.length(); j++) {
            char c = sparql.charAt(j);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return sparql.substring(brace, j + 1);
            }
        }
        throw new IllegalArgumentException("Unbalanced braces in WHERE clause");
    }

    /** Finds a keyword (case-insensitive, word-bounded) outside any braces. */
    private static int indexOfKeywordAtDepthZero(String s, String keyword) {
        int depth = 0;
        String upper = s.toUpperCase();
        String kw = keyword.toUpperCase();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (depth == 0 && upper.startsWith(kw, i)) {
                boolean leftOk  = (i == 0) || !Character.isLetterOrDigit(s.charAt(i - 1));
                int end = i + kw.length();
                boolean rightOk = (end >= s.length()) || !Character.isLetterOrDigit(s.charAt(end));
                if (leftOk && rightOk) return i;
            }
        }
        return -1;
    }

    /** Collects the request's PREFIX declarations to re-use in the SELECT. */
    private static String extractPrefixLines(String sparql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sparql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, "PREFIX", 0, "PREFIX".length())) {
                sb.append(trimmed).append('\n');
            }
        }
        return sb.toString();
    }

    // Helpers

    private record EntityRef(String entityType, String idField, Object id) {}

    private EntityRef parseSubjectIri(String iri) {
        if (!iri.startsWith(RES_NS)) {
            throw new IllegalArgumentException(
                    "Subject IRI does not match the resource prefix " + RES_NS + ": " + iri);
        }
        String rest = iri.substring(RES_NS.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Subject IRI missing /{id}: " + iri);
        }
        String segment = rest.substring(0, slash);
        String idStr   = rest.substring(slash + 1);

        String entityType = SEGMENT_TO_ENTITY.get(segment);
        String idField    = SEGMENT_TO_ID_FIELD.get(segment);
        if (entityType == null || idField == null) {
            throw new IllegalArgumentException("Unknown URI template segment '" + segment + "'");
        }

        Object id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            id = idStr;
        }
        return new EntityRef(entityType, idField, id);
    }

    private static String localName(String iri) {
        int hash = iri.indexOf('#');
        if (hash >= 0) return iri.substring(hash + 1);
        int slash = iri.lastIndexOf('/');
        return slash >= 0 ? iri.substring(slash + 1) : iri;
    }

    private static Object valueToObject(Value v) {
        if (v instanceof IRI iri) {
            // Object IRI: flatten to the id of the referenced entity
            String s = iri.stringValue();
            if (s.startsWith(RES_NS)) {
                String rest = s.substring(RES_NS.length());
                int slash = rest.indexOf('/');
                String idStr = slash < 0 ? rest : rest.substring(slash + 1);
                try { return Integer.parseInt(idStr); } catch (NumberFormatException e) { return idStr; }
            }
            return s;
        }
        if (v instanceof Literal lit) {
            // Preserve typed value if possible; fall back to string label
            String dt = lit.getDatatype() == null ? "" : lit.getDatatype().stringValue();
            if (dt.endsWith("#integer") || dt.endsWith("#int") || dt.endsWith("#long")) {
                return Long.parseLong(lit.getLabel());
            }
            if (dt.endsWith("#decimal") || dt.endsWith("#double") || dt.endsWith("#float")) {
                return Double.parseDouble(lit.getLabel());
            }
            return lit.getLabel();
        }
        return v.stringValue();
    }

    private static Map<String, List<Statement>> groupBySubject(List<Statement> stmts) {
        Map<String, List<Statement>> by = new LinkedHashMap<>();
        for (Statement st : stmts) {
            by.computeIfAbsent(st.getSubject().stringValue(), k -> new ArrayList<>()).add(st);
        }
        return by;
    }

    /**
     * Parses an RDF4J data-block string (Turtle-ish) into Statements via the
     * RIO Turtle parser. We wrap it in a graph context-free document.
     */
    private static List<Statement> parseDataBlock(String dataBlock) {
        if (dataBlock == null) return List.of();
        StatementCollector collector = new StatementCollector();
        try {
            org.eclipse.rdf4j.rio.RDFParser parser = Rio.createParser(RDFFormat.TURTLE);
            parser.setRDFHandler(collector);
            parser.parse(new StringReader(dataBlock), "");
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not parse SPARQL data block as Turtle: " + e.getMessage(), e);
        }
        return collector.getStatements().stream().toList();
    }
}
