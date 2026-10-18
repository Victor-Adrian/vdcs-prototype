package nl.utwente.thesis.vdcs.source.postgres.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import nl.utwente.thesis.vdcs.orchestrator.executor.CompletedOperation;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import nl.utwente.thesis.vdcs.orchestrator.model.FinalGradeOps;
import nl.utwente.thesis.vdcs.secret.client.SecretStoreClient;
import nl.utwente.thesis.vdcs.secret.client.SourceSecret;
import nl.utwente.thesis.vdcs.source.postgres.StudentRegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Applies and compensates canonical updates in the student registry. */
@Service
public class PostgresWriteService {

    private static final Logger log = LoggerFactory.getLogger(PostgresWriteService.class);

    private static final String SOURCE_ID = "student-registry";

    /** Ontology entity to underlying SQL table. */
    private static final Map<String, String> TABLE_BY_ENTITY = Map.of(
            "Student",    "students",
            "Programme",  "programmes",
            "Enrollment", "enrollments"
    );

    /** Ontology property to SQL column, per entity. */
    private static final Map<String, Map<String, String>> COLUMN_MAP = Map.of(
            "Student", Map.of(
                    "studentId",   "id",
                    "fullName",    "full_name",
                    "dateOfBirth", "date_of_birth",
                    "programmeId", "programme_id"
            ),
            "Programme", Map.of(
                    "programmeId",         "id",
                    "programmeName",       "name",
                    "programmeDepartment", "department"
            ),
            "Enrollment", Map.of(
                    "enrollmentId", "id",
                    "studentId",    "student_id",
                    "courseId",     "course_id",
                    "academicYear", "academic_year"
            )
    );

    @Value("${vdcs.secret-store.url}")   private String secretStoreUrl;
    @Value("${vdcs.secret-store.token}") private String serviceToken;

    private String url;
    private String user;
    private String password;
    private StudentRegistryClient client;

    @PostConstruct
    public void init() throws Exception {
        // Credentials are not in this service's config - they live in the
        // Secret & Credential Store. We authenticate with a service
        // token and pull them at startup.
        SecretStoreClient secrets = new SecretStoreClient(secretStoreUrl, serviceToken);
        SourceSecret s = secrets.fetch(SOURCE_ID);
        this.url      = s.getJdbcUrl();
        this.user     = s.getUsername();
        this.password = s.getPassword();

        this.client = new StudentRegistryClient(url, user, password);
        log.info("PostgreSQL SAC pulled credentials from secret store; connected to {}", url);
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) client.close();
    }

    /** Returns the JDBC URL + credentials the mediator should use for reads. */
    public Map<String, Object> connectionDetails() {
        return Map.of(
                "sourceId", SOURCE_ID,
                "engine",   "postgresql",
                "jdbcUrl",  url,
                "username", user,
                "password", password,
                "driverClass", "org.postgresql.Driver");
    }

    // apply / compensate (lifted from PostgresWriteExecutor)

    public CompletedOperation apply(CanonicalUpdate update) throws Exception {
        String entity = update.getEntityType();
        if (FinalGradeOps.ENTITY.equals(entity)) {
            FinalGradeOps.validate(update);
            try (Connection conn = client.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    CompletedOperation completed = applyFinalGrade(conn, update);
                    conn.commit();
                    return completed;
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            }
        }
        String table  = requireTable(entity);
        Map<String, String> cols = COLUMN_MAP.get(entity);

        try (Connection conn = client.getConnection()) {
            return switch (update.getOperation()) {
                case INSERT -> applyInsert(conn, table, cols, update);
                case UPDATE -> applyUpdate(conn, table, cols, update);
                case DELETE -> applyDelete(conn, table, cols, update);
            };
        }
    }

    // Final grades use (student_id, course_id) on an existing enrolment.
    // INSERT and UPDATE set the value; DELETE is unsupported.

    private CompletedOperation applyFinalGrade(Connection conn, CanonicalUpdate update)
            throws Exception {
        if (update.getOperation() == CanonicalUpdate.OperationType.DELETE) {
            throw new IllegalArgumentException(
                    "DELETE is not supported for FinalGrade; set a new value instead");
        }
        int[] key = FinalGradeOps.key(update);
        Object value = FinalGradeOps.value(update);

        // Pre-image: the current final_grade (may be NULL) for compensation.
        Map<String, Object> snapshot = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, final_grade FROM enrollments WHERE student_id = ? AND course_id = ? FOR UPDATE")) {
            ps.setInt(1, key[0]);
            ps.setInt(2, key[1]);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "No enrollment for student " + key[0] + " / course " + key[1]
                          + " — cannot record a final grade without a registration");
                }
                snapshot.put("enrollment_id", rs.getInt("id"));
                snapshot.put("student_id", key[0]);
                snapshot.put("course_id",  key[1]);
                // CompletedOperation copies the snapshot with Map.copyOf,
                // which rejects null values - so "was NULL" is encoded as
                // an absent key, and compensation restores NULL for it.
                Object prior = rs.getObject("final_grade");
                if (prior != null) snapshot.put("final_grade", prior);
                if (rs.next()) {
                    throw new IllegalStateException("Ambiguous FinalGrade: more than one enrollment for student "
                            + key[0] + " / course " + key[1] + ". No grade was changed.");
                }
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE enrollments SET final_grade = ? WHERE id = ?")) {
            ps.setObject(1, value);
            ps.setObject(2, snapshot.get("enrollment_id"));
            if (ps.executeUpdate() != 1) throw new IllegalStateException("FinalGrade enrollment no longer exists.");
        }
        log.info("Set final grade [{}] student={} course={} -> {}", SOURCE_ID, key[0], key[1], value);
        return new CompletedOperation(SOURCE_ID, update, snapshot);
    }

    private void compensateFinalGrade(Connection conn, Map<String, Object> snap) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE enrollments SET final_grade = ? WHERE id = ?")) {
            ps.setObject(1, FinalGradeOps.toDecimal(snap.get("final_grade")));
            Object enrollmentId = snap.get("enrollment_id");
            if (enrollmentId == null) throw new IllegalArgumentException("FinalGrade compensation requires enrollment_id.");
            ps.setObject(2, enrollmentId);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("FinalGrade enrollment no longer exists during compensation.");
        }
    }

    public void compensate(CompletedOperation handle) throws Exception {
        CanonicalUpdate orig = handle.getOriginalUpdate();
        String entity = orig.getEntityType();
        if (FinalGradeOps.ENTITY.equals(entity)) {
            try (Connection conn = client.getConnection()) {
                compensateFinalGrade(conn, handle.getSnapshot());
            }
            log.info("Compensated [{}] final grade for student={} course={}",
                    SOURCE_ID, handle.getSnapshot().get("student_id"),
                    handle.getSnapshot().get("course_id"));
            return;
        }
        String table  = requireTable(entity);
        Map<String, String> cols = COLUMN_MAP.get(entity);

        try (Connection conn = client.getConnection()) {
            switch (orig.getOperation()) {
                case INSERT -> {
                    Object id = handle.getSnapshot().get("id");
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM " + table + " WHERE id = ?")) {
                        ps.setObject(1, id);
                        ps.executeUpdate();
                    }
                }
                case UPDATE -> {
                    // The pre-image snapshot is keyed by DB column name. Restore
                    // only the columns the original update changed (from its
                    // payload) -- this avoids rewriting untouched columns such
                    // as date_of_birth, whose value would otherwise have to
                    // survive the JSON round-trip through the orchestrator.
                    Map<String, Object> snap = handle.getSnapshot();
                    Object id = snap.get("id");
                    Map<String, Object> restore = new LinkedHashMap<>();
                    for (String field : orig.getPayload().keySet()) {
                        String col = cols.get(field);
                        if (col != null && !"id".equals(col) && snap.containsKey(col)) {
                            restore.put(col, snap.get(col));
                        }
                    }
                    if (restore.isEmpty()) return;
                    String setClause = String.join(", ",
                            restore.keySet().stream().map(c -> c + " = ?").toList());
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE " + table + " SET " + setClause + " WHERE id = ?")) {
                        int i = 1;
                        for (Object v : restore.values()) ps.setObject(i++, v);
                        ps.setObject(i, id);
                        ps.executeUpdate();
                    }
                }
                case DELETE -> {
                    // Re-insert the full pre-image row (keyed by DB column).
                    Map<String, Object> snap = handle.getSnapshot();
                    if (snap.isEmpty()) return;
                    String columns = String.join(", ", snap.keySet());
                    String placeholders = String.join(", ",
                            snap.keySet().stream().map(c -> "?").toList());
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO " + table + "(" + columns + ") VALUES (" + placeholders + ")")) {
                        int i = 1;
                        for (Object v : snap.values()) ps.setObject(i++, v);
                        ps.executeUpdate();
                    }
                }
            }
        }
        log.info("Compensated [{}] {} on {}", SOURCE_ID, orig.getOperation(), entity);
    }

    // per-operation handlers

    private CompletedOperation applyInsert(Connection conn, String table,
                                           Map<String, String> cols,
                                           CanonicalUpdate update) throws Exception {
        Map<String, Object> sqlValues = toSqlValues(cols, update.getPayload());
        if (sqlValues.isEmpty()) {
            throw new IllegalArgumentException("INSERT payload contained no recognised fields");
        }
        String columns = String.join(", ", sqlValues.keySet());
        String placeholders = String.join(", ",
                sqlValues.keySet().stream().map(c -> "?").toList());

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + table + "(" + columns + ") VALUES (" + placeholders + ")",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            for (Object v : sqlValues.values()) ps.setObject(i++, v);
            ps.executeUpdate();

            Object id = sqlValues.get("id");
            if (id == null) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) id = keys.getObject(1);
                }
            }
            log.info("Inserted into {}.{} id={}", SOURCE_ID, table, id);
            return new CompletedOperation(SOURCE_ID, update, Map.of("id", id));
        }
    }

    private CompletedOperation applyUpdate(Connection conn, String table,
                                           Map<String, String> cols,
                                           CanonicalUpdate update) throws Exception {
        Object id = requireIdFromFilter(update, cols);
        Map<String, Object> preImage = fetchRow(conn, table, id);

        Map<String, Object> sqlValues = toSqlValues(cols, update.getPayload());
        sqlValues.remove("id");
        if (sqlValues.isEmpty()) {
            throw new IllegalArgumentException("UPDATE payload had no recognised fields");
        }
        String setClause = String.join(", ",
                sqlValues.keySet().stream().map(c -> c + " = ?").toList());
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + table + " SET " + setClause + " WHERE id = ?")) {
            int i = 1;
            for (Object v : sqlValues.values()) ps.setObject(i++, v);
            ps.setObject(i, id);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                throw new IllegalStateException(
                        "UPDATE on " + table + " id=" + id + " affected 0 rows");
            }
        }
        log.info("Updated {}.{} id={}", SOURCE_ID, table, id);
        return new CompletedOperation(SOURCE_ID, update, preImage);
    }

    private CompletedOperation applyDelete(Connection conn, String table,
                                           Map<String, String> cols,
                                           CanonicalUpdate update) throws Exception {
        Object id = requireIdFromFilter(update, cols);
        Map<String, Object> preImage = fetchRow(conn, table, id);

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + table + " WHERE id = ?")) {
            ps.setObject(1, id);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                throw new IllegalStateException(
                        "DELETE on " + table + " id=" + id + " affected 0 rows");
            }
        }
        log.info("Deleted from {}.{} id={}", SOURCE_ID, table, id);
        return new CompletedOperation(SOURCE_ID, update, preImage);
    }

    // helpers

    private static String requireTable(String entity) {
        String table = TABLE_BY_ENTITY.get(entity);
        if (table == null) {
            throw new IllegalArgumentException(
                    "PostgreSQL SAC cannot write entity '" + entity + "'");
        }
        return table;
    }

    private static Object requireIdFromFilter(CanonicalUpdate update,
                                              Map<String, String> cols) {
        for (Map.Entry<String, Object> e : update.getFilter().entrySet()) {
            if ("id".equals(cols.get(e.getKey()))) return e.getValue();
        }
        throw new IllegalArgumentException(
                "UPDATE/DELETE on " + update.getEntityType() + " requires the id field in 'filter'");
    }

    private static Map<String, Object> toSqlValues(Map<String, String> cols,
                                                   Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            String col = cols.get(e.getKey());
            if (col != null) out.put(col, coerce(col, e.getValue()));
        }
        return out;
    }

    /**
     * Coerces an incoming canonical value into a Java type the PostgreSQL
     * driver will accept. Both source paths - SPARQL Update (xsd:date
     * literals) and JSON canonical (raw strings) - deliver dates as
     * String; PostgreSQL refuses varchar-to-date implicit casts.
     */
    private static Object coerce(String column, Object value) {
        if (value instanceof String s && "date_of_birth".equals(column)) {
            return java.time.LocalDate.parse(s);
        }
        return value;
    }

    private static Map<String, Object> fetchRow(Connection conn, String table,
                                                Object id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM " + table + " WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Row not found: " + table + ".id=" + id);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                int colCount = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    // Skip NULL columns: CompletedOperation snapshots go
                    // through Map.copyOf, which rejects null values. An
                    // absent key re-inserts as NULL on DELETE compensation.
                    Object v = rs.getObject(i);
                    if (v != null) {
                        row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), v);
                    }
                }
                return row;
            }
        }
    }
}
