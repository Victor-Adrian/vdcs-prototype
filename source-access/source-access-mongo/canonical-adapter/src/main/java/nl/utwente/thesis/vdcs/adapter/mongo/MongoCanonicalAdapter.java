package nl.utwente.thesis.vdcs.adapter.mongo;

import com.mongodb.client.MongoCollection;
import nl.utwente.thesis.vdcs.source.mongo.StudentActivitiesClient;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

/** Projects MongoDB activity documents into the H2 schema consumed by Ontop. */
public class MongoCanonicalAdapter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoCanonicalAdapter.class);

    /**
     * Named H2 database kept alive between connections. Default identifier
     * folding matches Ontop's treatment of unquoted OBDA table names.
     */
    private static final String H2_URL =
            "jdbc:h2:mem:activities;DB_CLOSE_DELAY=-1";

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

    private final StudentActivitiesClient mongoClient;

    public MongoCanonicalAdapter(StudentActivitiesClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    /** JDBC URL for the H2 projection. Used by Ontop to configure its connection. */
    public static String h2JdbcUrl() {
        return H2_URL;
    }

    /** Default H2 user. Must match what Ontop is configured with downstream. */
    public static final String H2_USER     = "sa";
    /** Default H2 password (empty). */
    public static final String H2_PASSWORD = "";

    /**
     * Opens the H2 projection with the credentials Ontop uses. Callers close
     * the connection; DB_CLOSE_DELAY keeps the database alive.
     */
    public Connection getRelationalConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
    }

    /**
     * Reloads the H2 projection from MongoDB. Creates tables if missing,
     * truncates all rows, then inserts one row per document plus rows for
     * every embedded club membership and project.
     */
    public void refresh() throws SQLException {
        log.info("Refreshing MongoDB → H2 canonical projection…");

        try (Connection conn = getRelationalConnection()) {
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

    // Private helpers

    private void ensureSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            // H2 accepts multiple statements separated by ';' in a single execute()
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
            // Restart auto-increment so IDs are stable across refreshes
            st.execute("ALTER TABLE club_memberships  ALTER COLUMN id RESTART WITH 1");
            st.execute("ALTER TABLE academic_projects ALTER COLUMN id RESTART WITH 1");
        }
    }

    /** Returns {activityRows, clubRows, projectRows} counts. */
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

    @Override
    public void close() {
        mongoClient.close();
    }
}
