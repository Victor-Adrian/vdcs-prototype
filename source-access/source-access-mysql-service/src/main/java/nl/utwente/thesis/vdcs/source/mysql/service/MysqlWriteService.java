package nl.utwente.thesis.vdcs.source.mysql.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import nl.utwente.thesis.vdcs.orchestrator.executor.CompletedOperation;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import nl.utwente.thesis.vdcs.orchestrator.model.FinalGradeOps;
import nl.utwente.thesis.vdcs.secret.client.SecretStoreClient;
import nl.utwente.thesis.vdcs.secret.client.SourceSecret;
import nl.utwente.thesis.vdcs.source.mysql.AcademicRecordsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Applies and compensates canonical updates in the academic records source. */
@Service
public class MysqlWriteService {

    private static final Logger log = LoggerFactory.getLogger(MysqlWriteService.class);

    private static final String SOURCE_ID = "academic-records";

    private static final Map<String, String> TABLE_BY_ENTITY = Map.of(
            "Course",     "courses",
            "Assessment", "assessments",
            "Grade",      "grades"
    );

    private static final Map<String, Map<String, String>> COLUMN_MAP = Map.of(
            "Course", Map.of(
                    "courseId",         "id",
                    "courseTitle",      "title",
                    "credits",          "credits",
                    "courseDepartment", "department"
            ),
            "Assessment", Map.of(
                    "assessmentId",   "id",
                    "courseId",       "course_id",
                    "assessmentType", "type",
                    "weight",         "weight"
            ),
            "Grade", Map.of(
                    "gradeId",      "id",
                    "studentId",    "student_id",
                    "assessmentId", "assessment_id",
                    "score",        "score"
            )
    );

    @Value("${vdcs.secret-store.url}")   private String secretStoreUrl;
    @Value("${vdcs.secret-store.token}") private String serviceToken;

    private String url;
    private String user;
    private String password;
    private AcademicRecordsClient client;

    @PostConstruct
    public void init() throws Exception {
        SecretStoreClient secrets = new SecretStoreClient(secretStoreUrl, serviceToken);
        SourceSecret s = secrets.fetch(SOURCE_ID);
        this.url      = s.getJdbcUrl();
        this.user     = s.getUsername();
        this.password = s.getPassword();

        this.client = new AcademicRecordsClient(url, user, password);
        log.info("MySQL SAC pulled credentials from secret store; connected to {}", url);
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) client.close();
    }

    public Map<String, Object> connectionDetails() {
        return Map.of(
                "sourceId", SOURCE_ID,
                "engine",   "mysql",
                "jdbcUrl",  url,
                "username", user,
                "password", password,
                "driverClass", "com.mysql.cj.jdbc.Driver");
    }

    public CompletedOperation apply(CanonicalUpdate update) throws Exception {
        String entity = update.getEntityType();
        if (FinalGradeOps.ENTITY.equals(entity)) {
            FinalGradeOps.validate(update);
            try (Connection conn = client.getConnection()) {
                return applyFinalGrade(conn, update);
            }
        }
        String table  = requireTable(entity);
        Map<String, String> cols = COLUMN_MAP.get(entity);

        CompletedOperation handle;
        try (Connection conn = client.getConnection()) {
            handle = switch (update.getOperation()) {
                case INSERT -> applyInsert(conn, table, cols, update);
                case UPDATE -> applyUpdate(conn, table, cols, update);
                case DELETE -> applyDelete(conn, table, cols, update);
            };
        }
        return handle;
    }

    // Final grades use (student_id, course_id) as their key. INSERT creates
    // or replaces a value; UPDATE requires an existing row. The snapshot
    // records whether compensation should restore or remove the row.

    private CompletedOperation applyFinalGrade(Connection conn, CanonicalUpdate update)
            throws Exception {
        if (update.getOperation() == CanonicalUpdate.OperationType.DELETE) {
            throw new IllegalArgumentException(
                    "DELETE is not supported for FinalGrade; set a new value instead");
        }
        int[] key = FinalGradeOps.key(update);
        Object value = FinalGradeOps.value(update);

        // Pre-image for compensation.
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("student_id", key[0]);
        snapshot.put("course_id",  key[1]);
        boolean existed;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT final_grade FROM final_grades WHERE student_id = ? AND course_id = ?")) {
            ps.setInt(1, key[0]);
            ps.setInt(2, key[1]);
            try (ResultSet rs = ps.executeQuery()) {
                existed = rs.next();
                snapshot.put("existed", existed);
                // CompletedOperation copies the snapshot with Map.copyOf,
                // which rejects null values - only record the pre-image
                // value when a row existed.
                if (existed) {
                    Object prior = rs.getObject("final_grade");
                    if (prior != null) snapshot.put("final_grade", prior);
                }
            }
        }

        if (existed) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE final_grades SET final_grade = ? WHERE student_id = ? AND course_id = ?")) {
                ps.setObject(1, value);
                ps.setInt(2, key[0]);
                ps.setInt(3, key[1]);
                ps.executeUpdate();
            }
        } else if (update.getOperation() == CanonicalUpdate.OperationType.INSERT) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO final_grades (student_id, course_id, final_grade) VALUES (?, ?, ?)")) {
                ps.setInt(1, key[0]);
                ps.setInt(2, key[1]);
                ps.setObject(3, value);
                ps.executeUpdate();
            }
        } else {
            throw new IllegalStateException(
                    "No final-grade record for student " + key[0] + " / course " + key[1]
                  + " in " + SOURCE_ID + " — UPDATE requires an existing record");
        }
        log.info("Set final grade [{}] student={} course={} -> {}", SOURCE_ID, key[0], key[1], value);
        return new CompletedOperation(SOURCE_ID, update, snapshot);
    }

    private void compensateFinalGrade(Connection conn, Map<String, Object> snap) throws Exception {
        boolean existed = Boolean.TRUE.equals(snap.get("existed"));
        if (existed) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE final_grades SET final_grade = ? WHERE student_id = ? AND course_id = ?")) {
                ps.setObject(1, FinalGradeOps.toDecimal(snap.get("final_grade")));
                ps.setObject(2, snap.get("student_id"));
                ps.setObject(3, snap.get("course_id"));
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM final_grades WHERE student_id = ? AND course_id = ?")) {
                ps.setObject(1, snap.get("student_id"));
                ps.setObject(2, snap.get("course_id"));
                ps.executeUpdate();
            }
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
                    // Restore only the columns the original update changed
                    // (keyed by DB column in the pre-image snapshot); see the
                    // PostgreSQL SAC for the rationale.
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

    private static String requireTable(String entity) {
        String table = TABLE_BY_ENTITY.get(entity);
        if (table == null) {
            throw new IllegalArgumentException(
                    "MySQL SAC cannot write entity '" + entity + "'");
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
            if (col != null) out.put(col, e.getValue());
        }
        return out;
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
