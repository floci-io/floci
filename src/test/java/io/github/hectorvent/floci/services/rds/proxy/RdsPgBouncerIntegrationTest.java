package io.github.hectorvent.floci.services.rds.proxy;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@Tag("docker")
class RdsPgBouncerIntegrationTest {

    @Inject DockerClient docker;
    @Inject ContainerBuilder builder;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject ContainerLogStreamer logs;

    @BeforeEach
    void requireDocker() throws ClassNotFoundException {
        try {
            docker.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
        Class.forName("org.postgresql.Driver");
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "require"})
    void freshPgBouncerServerLoginsKeepWorking(String serverTlsMode) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String dbId = "pgbouncer-" + suffix;
        int proxyPort = rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", dbId)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "master")
                .formParam("MasterUserPassword", "Master123!")
                .formParam("DBName", "appdb")
                .formParam("AllocatedStorage", "20")
                .formParam("DBInstanceClass", "db.t3.micro")
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
        ContainerInfo pooler = null;
        Closeable poolerLogs = null;
        try {
            try (Connection connection = DriverManager.getConnection(jdbcUrl("127.0.0.1", proxyPort),
                    credentials("master", "Master123!"));
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE ROLE app_user LOGIN PASSWORD 'Application123!'");
            }
            ContainerSpec spec = builder.newContainer(TestImages.PGBOUNCER)
                    .withName("floci-test-pgbouncer-" + suffix)
                    .withHostDockerInternalOnLinux()
                    // PgBouncer's DNS-only resolver cannot always resolve Docker's host alias.
                    .withEntrypoint(List.of("/bin/sh", "-ec"))
                    .withCmd("""
                            export DB_HOST="$(getent hosts "$DB_HOST" | awk 'NR == 1 {print $1}')"
                            test -n "$DB_HOST"
                            exec /entrypoint.sh /usr/bin/pgbouncer /etc/pgbouncer/pgbouncer.ini
                            """)
                    .withLoopbackPortBinding(5432, 0)
                    .withEnv("DB_HOST", "host.docker.internal")
                    .withEnv("DB_PORT", String.valueOf(proxyPort))
                    .withEnv("DB_NAME", "appdb")
                    .withEnv("DB_USER", "app_user")
                    .withEnv("DB_PASSWORD", "Application123!")
                    .withEnv("AUTH_TYPE", "scram-sha-256")
                    .withEnv("POOL_MODE", "session")
                    .withEnv("SERVER_LIFETIME", "0")
                    .withEnv("SERVER_LOGIN_RETRY", "1")
                    .withEnv("SERVER_CONNECT_TIMEOUT", "5")
                    .withEnv("SERVER_TLS_SSLMODE", serverTlsMode)
                    .withLogRotation()
                    .build();
            pooler = lifecycle.createAndStart(spec);
            poolerLogs = logs.attachConsoleOnly(pooler.containerId(), "pgbouncer:" + suffix);
            EndpointInfo endpoint = pooler.getEndpoint(5432);
            await().atMost(Duration.ofSeconds(10)).until(() -> listening(endpoint));

            // server_lifetime=0 closes a pooled server after each client; distinct backend PIDs
            // prove that every iteration authenticates again rather than reusing a warm session.
            Set<Integer> backends = new HashSet<>();
            for (int i = 0; i < 30; i++) {
                try (Connection connection = DriverManager.getConnection(jdbcUrl(endpoint.host(), endpoint.port()),
                        credentials("app_user", "Application123!"));
                     Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT current_user, pg_backend_pid()")) {
                    assertTrue(result.next());
                    assertEquals("app_user", result.getString(1));
                    assertTrue(backends.add(result.getInt(2)), "each login must reach a new backend session");
                }
            }
            assertEquals(30, backends.size());
        } finally {
            if (pooler != null) {
                lifecycle.stopAndRemove(pooler.containerId(), poolerLogs);
            }
            rds("DeleteDBInstance").formParam("DBInstanceIdentifier", dbId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/").then().statusCode(200);
        }
    }

    private static Properties credentials(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        return properties;
    }

    private static String jdbcUrl(String host, int port) {
        return "jdbc:postgresql://" + host + ":" + port + "/appdb";
    }

    private static boolean listening(EndpointInfo endpoint) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
