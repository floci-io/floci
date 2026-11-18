package io.floci.aws.services.dsql;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * A throwaway PostgreSQL 16 that wants SCRAM-SHA-256 passwords, as a shared server does, started
 * before Floci boots and set as the external DSQL backend. Without Docker the overrides point at a
 * port where nothing listens, and {@link DsqlClusterFixture#create} skips the test class; any other
 * failure to start the server fails the boot, with its cause.
 */
public final class ExternalPostgresResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = Logger.getLogger(ExternalPostgresResource.class);

    public static final String PASSWORD = "floci-test";

    private ExternalPostgresServer server;

    @Override
    public Map<String, String> start() {
        Map<String, String> overrides = new HashMap<>();
        // A real data plane for each cluster, its endpoint on an ephemeral port.
        overrides.put("floci.services.dsql.mock", "false");
        overrides.put("floci.services.dsql.port", "0");
        overrides.put("floci.services.dsql.backend", "external");
        overrides.put("floci.services.dsql.external.host", "127.0.0.1");
        overrides.put("floci.services.dsql.external.password", PASSWORD);
        int port;
        try {
            server = ExternalPostgresServer.start("floci-dsql-external", PASSWORD, "POSTGRES_PASSWORD=" + PASSWORD,
                    "POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256");
            port = server.port();
        } catch (ExternalPostgresServer.DockerUnavailableException e) {
            LOG.warnv(e, "No external PostgreSQL for the DSQL tests; they skip");
            // Nothing listens on port 1; the tests skip before they create a cluster.
            port = 1;
        } catch (SQLException e) {
            throw new IllegalStateException("The external PostgreSQL for the DSQL tests did not start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while the external PostgreSQL started", e);
        }
        overrides.put("floci.services.dsql.external.port", String.valueOf(port));
        return overrides;
    }

    @Override
    public void stop() {
        if (server != null) {
            server.close();
        }
    }
}
