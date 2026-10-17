package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A real PostgreSQL instance's log, read through {@code DescribeDBLogFiles} and
 * {@code DownloadDBLogFilePortion}: the container's startup line lands in an hourly
 * {@code error/postgresql.log.*} file. Skipped without Docker.
 */
@QuarkusTest
@Tag("docker")
class RdsLogFilesDockerIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261015/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void checkDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
    }

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    void aPostgresInstancesLogIsListedAndDownloaded() {
        String id = "log-files-pg-" + Long.toString(System.nanoTime(), 36);
        try {
            query("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", id)
                    .formParam("Engine", "postgres")
                    .formParam("DBInstanceClass", "db.t3.micro")
                    .formParam("MasterUsername", "admin")
                    .formParam("MasterUserPassword", "password123")
                    .formParam("AllocatedStorage", "20")
            .when().post("/").then().statusCode(200);

            // CreateDBInstance returns once the database accepts connections, so its startup log is
            // already in the container's output: the first call must see it.
            List<String> names = query("DescribeDBLogFiles").formParam("DBInstanceIdentifier", id)
                    .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getList("**.findAll { it.name() == 'LogFileName' }", String.class);
            assertFalse(names.isEmpty(), "the instance should have a log file");
            assertTrue(names.stream().allMatch(n -> n.matches("error/postgresql\\.log\\.\\d{4}-\\d{2}-\\d{2}-\\d{2}")),
                    names.toString());

            StringBuilder all = new StringBuilder();
            for (String name : names) {
                all.append(query("DownloadDBLogFilePortion").formParam("DBInstanceIdentifier", id)
                        .formParam("LogFileName", name).formParam("Marker", "0")
                        .when().post("/").then().statusCode(200).extract().xmlPath()
                        .getString("DownloadDBLogFilePortionResponse.DownloadDBLogFilePortionResult.LogFileData"));
            }
            String data = all.toString();
            assertTrue(data.contains("ready to accept connections"), data);
        } finally {
            query("DeleteDBInstance").formParam("DBInstanceIdentifier", id).formParam("SkipFinalSnapshot", "true")
            .when().post("/");
        }
    }
}
