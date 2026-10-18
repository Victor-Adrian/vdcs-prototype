package nl.utwente.thesis.vdcs.access.auth;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.StatementPatternCollector;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Enforces entity permissions on SPARQL reads. Denied entities reject the
 * query; self-scoped entities add an ownership constraint to the outer query.
 * The predicate map below should eventually be derived from ontology metadata.
 */
@Component
public class QueryAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(QueryAuthorizer.class);

    private static final String ONT_NS = "https://thesis.utwente.nl/vdcs/ontology/university#";
    private static final String RES_NS = "https://thesis.utwente.nl/vdcs/resource/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    /** Local name (class or predicate) to its owning ontology entity. */
    private static final Map<String, String> ENTITY_OF = Map.ofEntries(
            // Student
            e("Student"), e("studentId", "Student"), e("fullName", "Student"),
            e("dateOfBirth", "Student"), e("enrolledInProgramme", "Student"),
            // Programme
            e("Programme"), e("programmeId", "Programme"), e("programmeName", "Programme"),
            e("programmeDepartment", "Programme"),
            // Enrollment
            e("Enrollment"), e("enrollmentId", "Enrollment"), e("academicYear", "Enrollment"),
            e("hasEnrollment", "Enrollment"), e("enrolledInCourse", "Enrollment"),
            // Course
            e("Course"), e("courseId", "Course"), e("courseTitle", "Course"),
            e("credits", "Course"), e("courseDepartment", "Course"),
            // Assessment
            e("Assessment"), e("assessmentId", "Assessment"), e("assessmentType", "Assessment"),
            e("weight", "Assessment"), e("hasAssessment", "Assessment"),
            // Grade
            e("Grade"), e("gradeId", "Grade"), e("score", "Grade"),
            e("hasGrade", "Grade"), e("assessedBy", "Grade"),
            // FinalGrade (replicated in student-registry + academic-records)
            e("FinalGrade"), e("finalStudentId", "FinalGrade"),
            e("finalCourseId", "FinalGrade"), e("finalScore", "FinalGrade"),
            e("hasFinalGrade", "FinalGrade"), e("finalGradeFor", "FinalGrade"),
            // Publication (RDF source, read-only)
            e("Publication"), e("publicationId", "Publication"),
            e("publicationTitle", "Publication"), e("publicationYear", "Publication"),
            e("publicationVenue", "Publication"), e("hasPublication", "Publication"),
            // StudentActivity
            e("StudentActivity"), e("activityStudentId", "StudentActivity"),
            e("hasActivity", "StudentActivity"),
            // ClubMembership
            e("ClubMembership"), e("clubName", "ClubMembership"), e("clubRole", "ClubMembership"),
            e("joinedDate", "ClubMembership"), e("hasClubMembership", "ClubMembership"),
            // AcademicProject
            e("AcademicProject"), e("projectTitle", "AcademicProject"),
            e("projectYear", "AcademicProject"), e("projectStatus", "AcademicProject"),
            e("hasProject", "AcademicProject")
    );

    private static Map.Entry<String, String> e(String selfNamed) { return Map.entry(selfNamed, selfNamed); }
    private static Map.Entry<String, String> e(String local, String entity) { return Map.entry(local, entity); }

    /** Outcome of an authorization check. */
    public record Decision(boolean allowed, String reason,
                           String rewrittenQuery, Set<String> entities) {
        static Decision deny(String reason, Set<String> entities) {
            return new Decision(false, reason, null, entities);
        }
        static Decision allow(String query, Set<String> entities) {
            return new Decision(true, null, query, entities);
        }
    }

    /**
     * Authorize (and possibly rewrite) a SELECT query for a principal.
     */
    public Decision authorize(Principal principal, String sparql) {
        ParsedQuery parsed;
        try {
            parsed = new SPARQLParser().parseQuery(sparql, null);
        } catch (Exception ex) {
            // Let the mediator produce the parse error; nothing to enforce.
            return Decision.allow(sparql, Set.of());
        }

        List<StatementPattern> patterns =
                StatementPatternCollector.process(parsed.getTupleExpr());

        Set<String> referenced = new TreeSet<>();
        Set<String> selfEntities = new LinkedHashSet<>();
        boolean sawUnroutable = false;

        for (StatementPattern p : patterns) {
            String entity = entityOf(p);
            if (entity == null) { sawUnroutable = true; continue; }
            referenced.add(entity);
            String perm = principal.permissionFor(entity);
            if ("DENY".equalsIgnoreCase(perm)) {
                return Decision.deny("access to :" + entity + " is denied for "
                        + principal.getIdentity(), referenced);
            }
            if ("SELF".equalsIgnoreCase(perm)) {
                selfEntities.add(entity);
            }
        }

        // Unroutable patterns (variable predicate, unknown predicate) fall
        // under the principal's default permission.
        if (sawUnroutable && "DENY".equalsIgnoreCase(principal.getDefaultPermission())) {
            return Decision.deny("query references an unrecognised or unrestricted "
                    + "predicate; denied by default policy for " + principal.getIdentity(),
                    referenced);
        }

        if (selfEntities.isEmpty()) {
            return Decision.allow(sparql, referenced);   // no rewrite needed
        }

        // Build and inject ownership constraints for each SELF entity.
        String injection = buildInjection(principal, selfEntities, patterns);
        if (injection.isEmpty()) {
            return Decision.allow(sparql, referenced);
        }
        String rewritten = injectIntoWhere(sparql, injection);
        log.debug("Row-level rewrite for {}: {}", principal.getIdentity(), rewritten);
        return Decision.allow(rewritten, referenced);
    }

    // entity resolution

    private static String entityOf(StatementPattern p) {
        Var pred = p.getPredicateVar();
        if (pred == null || !pred.hasValue() || !(pred.getValue() instanceof IRI predIri)) {
            return null;   // variable predicate
        }
        if (RDF_TYPE.equals(predIri.stringValue())) {
            Var obj = p.getObjectVar();
            if (obj != null && obj.hasValue() && obj.getValue() instanceof IRI cls) {
                return ENTITY_OF.get(localName(cls.stringValue()));
            }
            return null;
        }
        return ENTITY_OF.get(localName(predIri.stringValue()));
    }

    // row-level injection

    private String buildInjection(Principal principal, Set<String> selfEntities,
                                  List<StatementPattern> patterns) {
        StringBuilder sb = new StringBuilder();
        for (String entity : selfEntities) {
            Principal.SelfScope scope = principal.selfScopeFor(entity);
            if (scope == null) continue;
            Object ownerId = principal.getAttributes().get(scope.getOwnerIdAttribute());
            if (ownerId == null) continue;
            String ownerIri  = RES_NS + scope.getOwnerType() + "/" + ownerId;
            String predIri   = ONT_NS + scope.getOwnerPredicate();

            // Collect the owned-entity terms (the grade variables/IRIs).
            Set<String> ownedTerms = new LinkedHashSet<>();
            for (StatementPattern p : patterns) {
                if (!entity.equals(entityOf(p))) continue;
                String predLocal = predicateLocal(p);
                Var ownedVar = scope.getOwnerPredicate().equals(predLocal)
                        ? p.getObjectVar()    // owner -hasGrade-> grade : grade is object
                        : p.getSubjectVar();  // grade -score-> ...      : grade is subject
                String term = renderTerm(ownedVar);
                if (term != null) ownedTerms.add(term);
            }
            for (String t : ownedTerms) {
                sb.append('<').append(ownerIri).append("> <")
                  .append(predIri).append("> ").append(t).append(" . ");
            }
        }
        return sb.toString();
    }

    private static String predicateLocal(StatementPattern p) {
        Var pred = p.getPredicateVar();
        if (pred != null && pred.hasValue() && pred.getValue() instanceof IRI iri) {
            return localName(iri.stringValue());
        }
        return "";
    }

    /** Renders a Var as a SPARQL term: ?name for variables, <iri> for IRIs. */
    private static String renderTerm(Var v) {
        if (v == null) return null;
        if (v.hasValue()) {
            Value val = v.getValue();
            if (val instanceof IRI iri) return "<" + iri.stringValue() + ">";
            return null;   // literal grade subject makes no sense; skip
        }
        return "?" + v.getName();
    }

    /**
     * Injects the given triples at the start of the outermost group graph
     * pattern (right after the first '{'). The original query text is
     * otherwise untouched, preserving FILTER / ORDER BY / projection.
     */
    private static String injectIntoWhere(String query, String injection) {
        int brace = query.indexOf('{');
        if (brace < 0) return query;   // not a graph-pattern query; nothing to do
        return query.substring(0, brace + 1) + " " + injection + query.substring(brace + 1);
    }

    private static String localName(String iri) {
        int hash = iri.indexOf('#');
        if (hash >= 0) return iri.substring(hash + 1);
        int slash = iri.lastIndexOf('/');
        return slash >= 0 ? iri.substring(slash + 1) : iri;
    }
}
