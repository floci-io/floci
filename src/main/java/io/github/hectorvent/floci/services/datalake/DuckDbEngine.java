package io.github.hectorvent.floci.services.datalake;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@RegisterForReflection(targets = { org.duckdb.DuckDBDriver.class })
public class DuckDbEngine {

    private static final Logger LOG = Logger.getLogger(DuckDbEngine.class);
    private final EmulatorConfig config;

    @Inject
    public DuckDbEngine(EmulatorConfig config) {
        this.config = config;
    }

    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        initS3(conn);
        return conn;
    }

    private void initS3(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSTALL httpfs;");
            stmt.execute("LOAD httpfs;");
            
            // Resolve the hostname:
            // 1. FLOCI_HOSTNAME environment variable
            // 2. config.hostname() (from floci.hostname in YAML)
            // 3. 127.0.0.1 (fallback)
            String hostname = System.getenv("FLOCI_HOSTNAME");
            if (hostname == null || hostname.isBlank()) {
                hostname = config.hostname().orElse("127.0.0.1");
            }

            // Resolve the port:
            // In QuarkusTest, we use the random test port. 
            // In production, we use the standard port (usually 4566).
            String port = ConfigProvider.getConfig().getOptionalValue("quarkus.http.test-port", String.class)
                    .orElse(ConfigProvider.getConfig().getOptionalValue("quarkus.http.port", String.class)
                            .orElse("4566"));

            String endpoint = hostname + ":" + port;
            LOG.debugv("Configuring DuckDB S3 endpoint: {0}", endpoint);

            stmt.execute("SET s3_endpoint='" + endpoint + "';");
            stmt.execute("SET s3_use_ssl=false;");
            stmt.execute("SET s3_url_style='path';");
            stmt.execute("SET s3_access_key_id='test';");
            stmt.execute("SET s3_secret_access_key='test';");
            stmt.execute("SET s3_region='us-east-1';");
        }
    }

    public void executeUpdate(String sql) throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    public List<List<String>> executeQuery(String sql) throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            List<List<String>> results = new ArrayList<>();

            // Header
            List<String> header = new ArrayList<>();
            for (int i = 1; i <= cols; i++) {
                header.add(meta.getColumnName(i));
            }
            results.add(header);

            // Rows
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= cols; i++) {
                    row.add(rs.getString(i));
                }
                results.add(row);
            }
            return results;
        }
    }
}
