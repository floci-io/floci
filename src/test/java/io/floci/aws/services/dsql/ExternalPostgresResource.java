package io.floci.aws.services.dsql;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * A throwaway PostgreSQL 16 that wants SCRAM-SHA-256 passwords, as a shared server does, started
 * before Floci boots and set as the external DSQL backend. Without Docker the overrides point at a
 * port where nothing listens, and {@link DsqlClusterFixture#create} skips the test class. Any other
 * failure to start the server points them there too and sets {@link #START_FAILURE} to its cause,
 * for the test class to fail with: the application still boots, since classes that need no
 * PostgreSQL share it.
 */
public final class ExternalPostgresResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = Logger.getLogger(ExternalPostgresResource.class);

    public static final String PASSWORD = "floci-test";

    /** Why the server did not start, when that fails the test class rather than skips it. */
    public static final String START_FAILURE = "floci-test.dsql-external.start-failure";

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
        } catch (SQLException | RuntimeException e) {
            LOG.errorv(e, "The external PostgreSQL for the DSQL tests did not start");
            overrides.put(START_FAILURE, String.valueOf(e));
            port = 1;
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
