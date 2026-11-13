package io.floci.aws.testing;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.jboss.logging.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.RdsClientBuilder;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.net.URI;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class RdsRequests {
    private static final Logger LOG = Logger.getLogger(RdsRequests.class);

    private RdsRequests() {}

    public static RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20261011/us-east-1/rds/aws4_request")
                .contentType(URLENC).formParam("Action", action).formParam("Version", "2014-10-31");
    }

    public static RdsClient rdsClient(String region, String account) {
        return rdsClientBuilder(region, account).build();
    }

    /** Keeps synchronous Docker startup within one bounded request, without replaying a create. */
    public static RdsClient rdsDockerClient(String region, String account) {
        Duration requestBudget = Duration.ofSeconds(90);
        return rdsClientBuilder(region, account)
                .httpClientBuilder(UrlConnectionHttpClient.builder().socketTimeout(requestBudget))
                .overrideConfiguration(c -> c.apiCallTimeout(requestBudget)
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
                .build();
    }

    /** A client failure does not establish that server-side creation finished. */
    public static void cleanupDockerInstance(RdsClient client, String id,
                                             RuntimeException createFailure, Duration budget) {
        try {
            if (createFailure instanceof RdsException) {
                try {
                    client.deleteDBInstance(b -> b.dbInstanceIdentifier(id).skipFinalSnapshot(true));
                } catch (DbInstanceNotFoundException expected) {
                    // The server rejected creation and completed its rollback.
                    LOG.debugv("Rejected RDS test instance {0} is absent", id);
                }
            } else {
                long deadline = System.nanoTime() + budget.toNanos();
                await().pollDelay(Duration.ZERO).atMost(budget)
                        .ignoreExceptionsMatching(DbInstanceNotFoundException.class::isInstance)
                        .untilAsserted(() -> client.deleteDBInstance(b -> b.dbInstanceIdentifier(id)
                                .skipFinalSnapshot(true).overrideConfiguration(c -> c.apiCallTimeout(
                                        Duration.ofNanos(Math.max(1, deadline - System.nanoTime()))))));
            }
        } catch (RuntimeException cleanupFailure) {
            if (createFailure == null) {
                throw cleanupFailure;
            }
            createFailure.addSuppressed(cleanupFailure);
        }
    }

    private static RdsClientBuilder rdsClientBuilder(String region, String account) {
        return RdsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test-secret-key")));
    }

    /** Skips the test when no Docker daemon answers, which a real PostgreSQL instance needs. */
    public static void assumeDocker() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker is not available");
    }

    /** Creates a PostgreSQL instance with database {@code appdb} and returns its endpoint port. */
    public static int createPostgresInstance(String id, String masterUsername, String masterPassword,
                                             boolean iamDatabaseAuthentication) {
        return rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", masterUsername)
                .formParam("MasterUserPassword", masterPassword)
                .formParam("DBName", "appdb")
                .formParam("AllocatedStorage", "20")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("EnableIAMDatabaseAuthentication", iamDatabaseAuthentication)
        .when().post("/").then().statusCode(200)
                .extract().xmlPath()
                .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
    }

    public static void deleteInstance(String id) {
        rds("DeleteDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/");
    }
}
