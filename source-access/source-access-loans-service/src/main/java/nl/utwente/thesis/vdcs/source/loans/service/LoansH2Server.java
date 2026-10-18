package nl.utwente.thesis.vdcs.source.loans.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Hosts seeded library loan data in H2 for mediator access. */
@Component
public class LoansH2Server {

    private static final Logger log = LoggerFactory.getLogger(LoansH2Server.class);

    @Value("${vdcs.loans.h2.port:9093}")   private int tcpPort;
    @Value("${vdcs.loans.h2.dbName:loans}") private String dbName;

    private Server tcpServer;
    private Connection keepAlive;

    @PostConstruct
    public void start() throws SQLException {
        // Keep the in-memory database open before exposing it over TCP.
        keepAlive = DriverManager.getConnection(localUrl(), "sa", "");
        try (Statement st = keepAlive.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS loans (
                    id          INT PRIMARY KEY,
                    student_id  INT NOT NULL,
                    title       VARCHAR(200) NOT NULL,
                    borrowed_on VARCHAR(20),
                    returned_on VARCHAR(20)
                )""");
            st.execute("DELETE FROM loans");
            st.execute("""
                INSERT INTO loans (id, student_id, title, borrowed_on, returned_on) VALUES
                  (1, 1, 'Database Systems',              '2026-02-01', '2026-02-20'),
                  (2, 1, 'Designing Data-Intensive Apps', '2026-03-05', NULL),
                  (3, 2, 'Clean Architecture',            '2026-02-10', '2026-03-01'),
                  (4, 3, 'The Pragmatic Programmer',      '2026-01-15', '2026-02-05'),
                  (5, 3, 'Refactoring',                   '2026-03-10', NULL)""");
        }
        log.info("Loans H2 seeded with 5 rows");

        tcpServer = Server.createTcpServer("-tcpPort", String.valueOf(tcpPort),
                "-tcpAllowOthers", "-ifNotExists").start();
        log.info("Loans H2 TCP server on port {} ({})", tcpPort, jdbcUrl());
    }

    public String localUrl() { return "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1"; }
    public String jdbcUrl()  { return "jdbc:h2:tcp://localhost:" + tcpPort + "/mem:" + dbName; }

    @PreDestroy
    public void stop() {
        try { if (keepAlive != null && !keepAlive.isClosed()) keepAlive.close(); } catch (SQLException ignored) {}
        if (tcpServer != null && tcpServer.isRunning(true)) tcpServer.stop();
    }
}
