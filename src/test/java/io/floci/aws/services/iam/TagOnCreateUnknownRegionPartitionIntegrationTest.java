package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.LogsAuthorizationFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * In a deployment whose partition is not {@code aws}, a request in a Region Floci does not know is in the deployment's
 * partition, and so is the resource it creates, which the tags the request carries are checked on: a grant scoped to
 * that partition reaches the tagging of the user pool a {@code CreateUserPool} makes, an action acting on no resource
 * type whose tags go on the resource it makes.
 */
@QuarkusTest
@TestProfile(LogsNoncommercialUnknownRegionAuthorizationIntegrationTest.ChinaProfile.class)
class TagOnCreateUnknownRegionPartitionIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "polygondwanaland-west-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theTagsACreateCarriesAreCheckedOnWhatItMakesInTheDeploymentsPartition() {
        String name = "pool-" + UUID.randomUUID().toString().substring(0, 8);
        String caller = LogsAuthorizationFixtures.user(cleanup, endpoint.getPort(), ACCOUNT, "tag-partition-",
                "pools", "{\"Effect\":\"Allow\",\"Action\":\"cognito-idp:CreateUserPool\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Allow\",\"Action\":\"cognito-idp:TagResource\",\"Resource\":\"arn:aws-cn:"
                        + "cognito-idp:" + REGION + ":" + ACCOUNT + ":userpool/*\"}");

        Response created = cognitoCall(caller, "CreateUserPool",
                "{\"PoolName\": \"" + name + "\", \"UserPoolTags\": {\"team\": \"blue\"}}");
        created.then().statusCode(200);
        String poolId = created.path("UserPool.Id");
        cleanup.register(() -> cognitoCall(ACCOUNT, "DeleteUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
                .then().statusCode(200));
    }

    private Response cognitoCall(String caller, String action, String body) {
        return given().port(endpoint.getPort())
                .header("Authorization", EnforcementFixtures.auth(caller, REGION, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }
}
