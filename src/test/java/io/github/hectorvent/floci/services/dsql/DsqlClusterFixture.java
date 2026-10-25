package io.github.hectorvent.floci.services.dsql;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.dsql.dataplane.DsqlEndpoint;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.restassured.specification.RequestSpecification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A DSQL cluster a test class creates through Floci's control plane, with a real database behind
 * it, and the ways a client reaches that database: JDBC over TLS with an IAM token, or the wire
 * protocol written by hand.
 */
public final class DsqlClusterFixture {

    public static final String ACCOUNT = "111122223333";
    public static final String REGION = "eu-west-1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20261016/" + REGION + "/dsql/aws4_request, SignedHeaders=host, Signature=abc";

    private final int httpPort;
    private final DsqlEndpoint endpoint;
    private String identifier;
    private String host;

    public DsqlClusterFixture(int httpPort, DsqlEndpoint endpoint) {
        this.httpPort = httpPort;
        this.endpoint = endpoint;
    }

    /** Creates the cluster and waits until it is ACTIVE; skips the test class when Docker is not there. */
    public void create(DockerClient dockerClient) {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
        identifier = dsql().body(Map.of("deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).extract().path("identifier");
        host = identifier + ".dsql." + REGION + ".localhost.floci.io";
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(500)).until(() ->
                "ACTIVE".equals(dsql().get("/cluster/" + identifier).then().extract().path("status")));
    }

    public void delete() {
        if (identifier != null) {
            dsql().delete("/cluster/" + identifier);
            await().atMost(Duration.ofMinutes(1)).until(() ->
                    dsql().get("/cluster/" + identifier).then().extract().statusCode() == 404);
        }
    }

    public String host() {
        return host;
    }

    public String identifier() {
        return identifier;
    }

    public String arn() {
        return "arn:aws:dsql:" + REGION + ":" + ACCOUNT + ":cluster/" + identifier;
    }

    /** Gives the cluster {@code policy} through the control plane, as the account's root. */
    public void putPolicy(String policy) {
        dsql().body(Map.of("policy", policy)).post("/cluster/" + identifier + "/policy").then().statusCode(200);
    }

    /** Takes the cluster's policy away, if it has one. */
    public void removePolicy() {
        dsql().delete("/cluster/" + identifier + "/policy").then().statusCode(anyOf(is(200), is(404)));
    }

    public String url() {
        return "jdbc:postgresql://127.0.0.1:" + endpoint.port() + "/postgres";
    }

    /**
     * What JDBC needs to sign in as {@code user}: TLS naming the cluster as its server, as DSQL
     * requires, without checking Floci's certificate.
     */
    public Properties properties(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "require");
        properties.setProperty("sslfactory", DsqlServerNameSocketFactory.class.getName());
        properties.setProperty(DsqlServerNameSocketFactory.SERVER_NAME, host);
        return properties;
    }

    public Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(url(), properties(user, password));
    }

    public DsqlWireClient wireClient(String user, String password) throws Exception {
        return new DsqlWireClient(endpoint.port(), host, user, password);
    }

    public String adminToken() throws Exception {
        return token("DbConnectAdmin", "test", "test", Instant.now(), 900, null);
    }

    public String token(String action, String accessKeyId, String secret, Instant at, int expires,
                        String sessionToken) throws Exception {
        return SigV4TokenTestHelper.createDsqlToken(host, REGION, action, accessKeyId, secret, at, expires,
                sessionToken);
    }

    private RequestSpecification dsql() {
        return given().port(httpPort).header("Authorization", AUTH).contentType("application/json");
    }
}
