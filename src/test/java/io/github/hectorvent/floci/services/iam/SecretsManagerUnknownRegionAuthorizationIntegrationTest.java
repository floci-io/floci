package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;

/**
 * In a China deployment that also serves regions Floci does not know, {@code CreateSecret} is
 * authorized against the ARN in the partition the secret is created in: the deployment's for an
 * unknown region, rather than the commercial one a region lookup falls back to, and the region's
 * own for a known one.
 */
@QuarkusTest
@TestProfile(LogsNoncommercialUnknownRegionAuthorizationIntegrationTest.ChinaProfile.class)
class SecretsManagerUnknownRegionAuthorizationIntegrationTest {

    private static final String ACCOUNT = "111111111111";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();
    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"polygondwanaland-west-1,aws-cn", "cn-north-1,aws-cn", "us-gov-west-1,aws-us-gov", "us-east-1,aws"})
    void createSecretIsAuthorizedInThePartitionItIsCreatedIn(String region, String partition) {
        int port = endpoint.getPort();
        String name = "secret-partition-" + UUID.randomUUID().toString().substring(0, 8);
        String secretArnPrefix = "arn:" + partition + ":secretsmanager:" + region + ":" + ACCOUNT + ":secret:";
        String root = EnforcementFixtures.auth(ACCOUNT, "iam");
        String user = "secret-partition-" + UUID.randomUUID().toString().substring(0, 8);
        String caller = EnforcementFixtures.createUser(cleanup, port, root, user).accessKeyId();
        given().port(port).header("Authorization", root).formParam("Action", "PutUserPolicy")
                .formParam("UserName", user).formParam("PolicyName", "secrets")
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"secretsmanager:CreateSecret","Resource":"%s%s-??????"}]}"""
                        .formatted(secretArnPrefix, name))
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> call(ACCOUNT, region, "DeleteSecret",
                "{\"SecretId\": \"" + name + "\", \"ForceDeleteWithoutRecovery\": true}").then().statusCode(200));

        call(caller, region, "CreateSecret", "{\"Name\": \"" + name + "\", \"SecretString\": \"x\"}")
                .then().statusCode(200)
                .body("ARN", startsWith(secretArnPrefix + name + "-"));
    }

    private Response call(String caller, String region, String action, String body) {
        String auth = "AWS4-HMAC-SHA256 Credential=" + caller + "/20261015/" + region
                + "/secretsmanager/aws4_request, SignedHeaders=host, Signature=abc";
        return given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                .header("Authorization", auth).header("X-Amz-Target", "secretsmanager." + action)
                .body(body).when().post("/");
    }
}
