package nl.utwente.thesis.vdcs.adapter.mongo.service;

import com.mongodb.client.MongoCollection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import nl.utwente.thesis.vdcs.secret.client.SecretStoreClient;
import nl.utwente.thesis.vdcs.secret.client.SourceSecret;
import nl.utwente.thesis.vdcs.source.mongo.StudentActivitiesClient;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

/** Maintains the H2 relational projection of MongoDB activity documents. */
@Service
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);

    private static final String CREATE_TABLES_DDL = """
            CREATE TABLE IF NOT EXISTS student_activities (
                student_id INT PRIMARY KEY
            );
            CREATE TABLE IF NOT EXISTS club_memberships (
                id          INT AUTO_INCREMENT PRIMARY KEY,
                student_id  INT NOT NULL,
                club_name   VARCHAR(200) NOT NULL,
                club_role   VARCHAR(100),
                joined_date VARCHAR(20)
            );
            CREATE TABLE IF NOT EXISTS academic_projects (
                id           INT AUTO_INCREMENT PRIMARY KEY,
                student_id   INT NOT NULL,
                title        VARCHAR(300) NOT NULL,
                project_year INT,
                status       VARCHAR(50)
            );
            """;

    private static final String SOURCE_ID = "student-activities";

    @Value("${vdcs.secret-store.url}")   private String secretStoreUrl;
    @Value("${vdcs.secret-store.token}") private String serviceToken;

    private final H2TcpServer h2;
    private StudentActivitiesClient mongoClient;

    public ProjectionService(H2TcpServer h2) {
        this.h2 = h2;
    }

    @PostConstruct
    public void init() throws Exception {
        // Fetch MongoDB credentials before opening the projection source.
        SecretStoreClient secrets = new SecretStoreClient(secretStoreUrl, serviceToken);
        SourceSecret s = secrets.fetch(SOURCE_ID);
        String mongoUri      = s.getMongoUri();
        String mongoDatabase = s.getDatabase();

        this.mongoClient = new StudentActivitiesClient(mongoUri, mongoDatabase);
        log.info("MongoDB driver pulled credentials from secret store; connected to {}",
                redact(mongoUri));

        // Create schema + populate so the mediator can connect
        // and introspect a populated database.
        refresh();
    }

    @PreDestroy
    public void shutdown() {
        if (mongoClient != null) mongoClient.close();
    }

    /** The Mongo client - exposed for the canonical write service. */
    public StudentActivitiesClient mongo() {
        return mongoClient;
    }

    /** Re-projects all documents from MongoDB into the H2 canonical view. */
    public void refresh() throws SQLException {
        log.info("Refreshing MongoDB → H2 canonical projection…");
        try (Connection conn = h2connection()) {
            conn.setAutoCommit(false);
            try {
                ensureSchema(conn);
                truncate(conn);
                int[] counts = loadFromMongo(conn);
                conn.commit();
                log.info("Canonical projection refreshed: {} activity records, "
                        + "{} club memberships, {} projects.",
                        counts[0], counts[1], counts[2]);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // internals

    private Connection h2connection() throws SQLException {
        return DriverManager.getConnection(h2.jdbcUrl(), h2.user(), h2.password());
    }

    private void ensureSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String ddl : CREATE_TABLES_DDL.split(";\\s*\\n")) {
                String trimmed = ddl.trim();
                if (!trimmed.isEmpty()) st.execute(trimmed);
            }
        }
    }

    private void truncate(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM academic_projects");
            st.execute("DELETE FROM club_memberships");
            st.execute("DELETE FROM student_activities");
            st.execute("ALTER TABLE club_memberships  ALTER COLUMN id RESTART WITH 1");
            st.execute("ALTER TABLE academic_projects ALTER COLUMN id RESTART WITH 1");
        }
    }

    private int[] loadFromMongo(Connection conn) throws SQLException {
        MongoCollection<Document> coll = mongoClient.getActivitiesCollection();

        try (PreparedStatement insertActivity = conn.prepareStatement(
                 "INSERT INTO student_activities(student_id) VALUES (?)");
             PreparedStatement insertClub = conn.prepareStatement(
                 "INSERT INTO club_memberships(student_id, club_name, club_role, joined_date) "
                 + "VALUES (?, ?, ?, ?)");
             PreparedStatement insertProject = conn.prepareStatement(
                 "INSERT INTO academic_projects(student_id, title, project_year, status) "
                 + "VALUES (?, ?, ?, ?)")) {

            int activityRows = 0, clubRows = 0, projectRows = 0;
            for (Document doc : coll.find()) {
                Integer sid = doc.getInteger("student_id");
                if (sid == null) continue;

                insertActivity.setInt(1, sid);
                insertActivity.executeUpdate();
                activityRows++;

                List<Document> clubs = doc.getList("clubs", Document.class);
                if (clubs != null) {
                    for (Document c : clubs) {
                        insertClub.setInt(1, sid);
                        insertClub.setString(2, c.getString("name"));
                        setNullableString(insertClub, 3, c.getString("role"));
                        setNullableString(insertClub, 4, c.getString("joined"));
                        insertClub.executeUpdate();
                        clubRows++;
                    }
                }

                List<Document> projects = doc.getList("projects", Document.class);
                if (projects != null) {
                    for (Document p : projects) {
                        insertProject.setInt(1, sid);
                        insertProject.setString(2, p.getString("title"));
                        setNullableInt(insertProject, 3, p.getInteger("year"));
                        setNullableString(insertProject, 4, p.getString("status"));
                        insertProject.executeUpdate();
                        projectRows++;
                    }
                }
            }
            return new int[]{activityRows, clubRows, projectRows};
        }
    }

    private static void setNullableString(PreparedStatement ps, int idx, String value)
            throws SQLException {
        if (value == null) ps.setNull(idx, Types.VARCHAR);
        else               ps.setString(idx, value);
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer value)
            throws SQLException {
        if (value == null) ps.setNull(idx, Types.INTEGER);
        else               ps.setInt(idx, value);
    }

    /** Hides credentials from log output. */
    private static String redact(String mongoUri) {
        return mongoUri.replaceAll("://[^:]+:[^@]+@", "://***:***@");
    }
}
