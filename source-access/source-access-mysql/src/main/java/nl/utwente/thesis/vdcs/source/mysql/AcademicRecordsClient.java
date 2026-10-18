package nl.utwente.thesis.vdcs.source.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Opens per-request JDBC connections to the academic records source. */
public class AcademicRecordsClient implements AutoCloseable {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public AcademicRecordsClient(String jdbcUrl, String user, String password) {
        this.jdbcUrl  = jdbcUrl;
        this.user     = user;
        this.password = password;
    }

    /**
     * Opens and returns a new JDBC connection. Caller is responsible for
     * closing it (typically via try-with-resources).
     */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    /** Default JDBC URL for the Docker-compose Academic Records container. */
    public static String defaultUrl() {
        return "jdbc:mysql://localhost:5433/academics";
    }

    /** No-op - we hold no long-lived JDBC state. Kept for Spring lifecycle. */
    @Override
    public void close() { /* nothing to release */ }
}
