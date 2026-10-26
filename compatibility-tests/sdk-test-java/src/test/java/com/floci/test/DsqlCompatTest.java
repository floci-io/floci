package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dsql.DsqlClient;
import software.amazon.awssdk.services.dsql.model.ClusterStatus;
import software.amazon.awssdk.services.dsql.model.CreateClusterResponse;
import software.amazon.awssdk.services.dsql.model.GetClusterPolicyResponse;
import software.amazon.awssdk.services.dsql.model.GetClusterResponse;
import software.amazon.awssdk.services.dsql.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dsql.model.ValidationException;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Aurora DSQL through the real AWS SDK: the control plane and its waiters, cluster policies, and a JDBC connection
 * to the cluster with a token from {@code DsqlUtilities}, the SDK's own signer. The connection goes
 * to Floci's address with the cluster's endpoint as the TLS server name, which is how Floci picks
 * the cluster, and the certificate is verified for that name against Floci's CA.
 *
 * Requires Docker (each cluster's database is a real PostgreSQL container).
 */
@DisplayName("Aurora DSQL")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DsqlCompatTest {

    private static final Region REGION = TestFixtures.region();
    /** A cluster is ACTIVE once its database answers: a container start on Floci, minutes on AWS. */
    private static final Duration ACTIVE_TIMEOUT =
            TestFixtures.isRealAws() ? Duration.ofMinutes(10) : Duration.ofMinutes(3);
    private static final Duration DELETE_TIMEOUT =
            TestFixtures.isRealAws() ? Duration.ofMinutes(10) : Duration.ofMinutes(1);
    private static final String SERVER_NAME_PROPERTY = "dsqlServerName";

    private static DsqlClient dsql;
    private static String identifier;
    private static String endpoint;

    @BeforeAll
    static void createCluster() {
        dsql = TestFixtures.dsqlClient();
        CreateClusterResponse created = dsql.createCluster(b -> b
                .deletionProtectionEnabled(false)
                .tags(Map.of("Name", TestFixtures.uniqueName("dsql-compat"))));
        identifier = created.identifier();
        assertThat(created.status()).isEqualTo(ClusterStatus.CREATING);

        GetClusterResponse active = dsql.waiter()
                .waitUntilClusterActive(b -> b.identifier(identifier), w -> w.waitTimeout(ACTIVE_TIMEOUT))
                .matched().response().orElseThrow();
        endpoint = active.endpoint();
    }

    @AfterAll
    static void deleteCluster() {
        if (identifier == null) {
            return;
        }
        dsql.deleteCluster(b -> b.identifier(identifier));
        dsql.waiter().waitUntilClusterNotExists(b -> b.identifier(identifier), w -> w.waitTimeout(DELETE_TIMEOUT));
        dsql.close();
    }

    @Test
    @Order(1)
    void aClusterIsActiveWithItsEndpointAndTags() {
        GetClusterResponse cluster = dsql.getCluster(b -> b.identifier(identifier));

        assertThat(identifier).matches("[a-z2-7]{26}");
        assertThat(cluster.status()).isEqualTo(ClusterStatus.ACTIVE);
        assertThat(cluster.deletionProtectionEnabled()).isFalse();
        assertThat(cluster.endpoint()).startsWith(identifier + ".dsql." + REGION.id() + ".");
        assertThat(cluster.arn()).endsWith(":cluster/" + identifier);
        assertThat(dsql.listClusters(b -> { }).clusters())
                .anySatisfy(summary -> assertThat(summary.identifier()).isEqualTo(identifier));
        assertThat(dsql.listTagsForResource(b -> b.resourceArn(cluster.arn())).tags()).containsKey("Name");
    }

    @Test
    @Order(2)
    void aProtectedClusterIsNotDeleted() {
        dsql.updateCluster(b -> b.identifier(identifier).deletionProtectionEnabled(true));

        assertThatThrownBy(() -> dsql.deleteCluster(b -> b.identifier(identifier)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Deletion protection is enabled");

        dsql.updateCluster(b -> b.identifier(identifier).deletionProtectionEnabled(false));
    }

    @Test
    @Order(3)
    void aPolicyIsKeptInDsqlsFormAndAPublicOneRefused() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":"
                + "{\"AWS\":\"*\"},\"Action\":[\"dsql:DbConnect\"],\"Resource\":\"*\",\"Condition\":"
                + "{\"StringNotEquals\":{\"aws:SourceVpc\":\"vpc-0123456789abcdef0\"}}}]}";

        String version = dsql.putClusterPolicy(b -> b.identifier(identifier).policy(policy)).policyVersion();
        GetClusterPolicyResponse kept = dsql.getClusterPolicy(b -> b.identifier(identifier));
        assertThat(version).matches("\\d{13}");
        assertThat(kept.policyVersion()).isEqualTo(version);
        assertThat(kept.policy()).contains("\"Action\" : \"dsql:DbConnect\"");
        assertThatThrownBy(() -> dsql.putClusterPolicy(b -> b.identifier(identifier).policy(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                        + "\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\"}]}")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("it grants public access");
        assertThat(dsql.deleteClusterPolicy(b -> b.identifier(identifier)).policyVersion()).isEqualTo(version);
        assertThatThrownBy(() -> dsql.getClusterPolicy(b -> b.identifier(identifier)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(4)
    void adminSignsInWithTheSdksTokenAndTheDatabaseSpeaksDsql() throws Exception {
        String token = dsql.utilities().generateDbConnectAdminAuthToken(b -> b.hostname(endpoint).region(REGION));

        try (Connection connection = connect("admin", token); Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery("SELECT current_user, current_setting('transaction_isolation')")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("admin");
                assertThat(row.getString(2)).isEqualTo("repeatable read");
            }
            statement.execute("CREATE TABLE compat_orders (id uuid PRIMARY KEY, n int)");
            String jobId;
            try (ResultSet job = statement.executeQuery("CREATE INDEX ASYNC compat_orders_n ON compat_orders (n)")) {
                assertThat(job.next()).isTrue();
                jobId = job.getString("job_id");
            }
            try (ResultSet waited = statement.executeQuery("CALL sys.wait_for_job('" + jobId + "')")) {
                assertThat(waited.next()).isTrue();
                assertThat(waited.getBoolean(1)).isTrue();
            }
            assertThatThrownBy(() -> statement.execute("TRUNCATE compat_orders"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("unsupported statement: Truncate");
        }
    }

    @Test
    @Order(5)
    void adminIsRefusedATokenForAnotherRole() {
        String token = dsql.utilities().generateDbConnectAuthToken(b -> b.hostname(endpoint).region(REGION));

        assertThatThrownBy(() -> connect("admin", token).close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("unable to accept connection, access denied")
                .hasMessageContaining("Wrong user to action mapping. user: admin, action: DbConnect");
    }

    @Test
    @Order(6)
    void adminSignsInWithTlsStartedAtOnce() throws Exception {
        String token = dsql.utilities().generateDbConnectAdminAuthToken(b -> b.hostname(endpoint).region(REGION));

        try (Connection connection = connect("admin", token, "direct");
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_user")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("admin");
        }
    }

    private static Connection connect(String user, String token) throws SQLException {
        return connect(user, token, "postgres");
    }

    /**
     * {@code sslNegotiation} is pgjdbc's: {@code postgres} sends an SSLRequest first, {@code direct}
     * starts TLS at once, offering the ALPN protocol postgresql.
     */
    private static Connection connect(String user, String token, String sslNegotiation) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", token);
        properties.setProperty("sslmode", "require");
        properties.setProperty("sslNegotiation", sslNegotiation);
        properties.setProperty("sslfactory", ServerNameSocketFactory.class.getName());
        properties.setProperty(SERVER_NAME_PROPERTY, endpoint);
        String host = TestFixtures.endpoint().getHost();
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + dsqlPort() + "/postgres", properties);
    }

    /** DSQL's port, 5432, unless the Floci under test serves it elsewhere (FLOCI_SERVICES_DSQL_PORT). */
    private static int dsqlPort() {
        String port = System.getenv("FLOCI_DSQL_PORT");
        return port == null || port.isBlank() ? 5432 : Integer.parseInt(port);
    }

    /**
     * Opens TLS to whatever pgjdbc dialled, naming the cluster's endpoint and verifying the
     * certificate for it against Floci's CA. pgjdbc builds it from the connection's properties.
     */
    public static final class ServerNameSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;
        private final String serverName;

        public ServerNameSocketFactory(Properties info) throws Exception {
            this.serverName = info.getProperty(SERVER_NAME_PROPERTY);
            this.delegate = TestFixtures.flociCaTrust().getSocketFactory();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            SSLSocket tls = (SSLSocket) delegate.createSocket(socket, serverName, port, autoClose);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            return tls;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return delegate.createSocket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return delegate.createSocket(host, port, localHost, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return delegate.createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return delegate.createSocket(address, port, localAddress, localPort);
        }
    }
}
