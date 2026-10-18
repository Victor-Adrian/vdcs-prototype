package nl.utwente.thesis.vdcs.adapter.mongo.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * Runs the H2 TCP server and keeps one local connection open so the
 * in-memory projection survives between mediator connections.
 */
@Component
public class H2TcpServer {

    private static final Logger log = LoggerFactory.getLogger(H2TcpServer.class);

    @Value("${vdcs.csa.h2.port:9092}")    private int    tcpPort;
    @Value("${vdcs.csa.h2.dbName:activities}") private String dbName;
    @Value("${vdcs.csa.h2.user:sa}")      private String user;
    @Value("${vdcs.csa.h2.password:}")    private String password;

    private Server tcpServer;
    private java.sql.Connection keepAlive;

    @PostConstruct
    public void start() throws SQLException {
        // Open H2 locally first and keep the in-memory database alive.
        keepAlive = java.sql.DriverManager.getConnection(localJdbcUrl(), user, password);
        log.info("H2 in-memory database '{}' created locally", dbName);

        // Allow the mediator to connect to the same database over TCP.
        tcpServer = Server.createTcpServer(
                        "-tcpPort", String.valueOf(tcpPort),
                        "-tcpAllowOthers",
                        "-ifNotExists")
                .start();
        log.info("H2 TCP server listening on port {} (external URL: {})",
                tcpPort, jdbcUrl());
    }

    /** Local JDBC URL - used by code in THIS JVM for direct in-process access. */
    public String localJdbcUrl() {
        return "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1";
    }

    /** TCP-routed JDBC URL - the URL the mediator (another JVM) uses to connect. */
    public String jdbcUrl() {
        return "jdbc:h2:tcp://localhost:" + tcpPort + "/mem:" + dbName;
    }

    public String user()     { return user; }
    public String password() { return password; }

    @PreDestroy
    public void stop() {
        try {
            if (keepAlive != null && !keepAlive.isClosed()) keepAlive.close();
        } catch (SQLException ignored) { /* best-effort */ }
        if (tcpServer != null && tcpServer.isRunning(true)) {
            tcpServer.stop();
            log.info("H2 TCP server stopped.");
        }
    }
}
