package io.github.hectorvent.floci.services.cognito;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;

/**
 * Deletion protection is the pool's guard against DeleteUserPool. The developer guide ("User
 * pool deletion protection") states the contract: with it ACTIVE the delete is refused with
 * InvalidParameterException until an UpdateUserPool switches it to INACTIVE.
 */
@QuarkusTest
class CognitoDeletionProtectionIntegrationTest {

    private static final String PROTECTED_MESSAGE =
            "The user pool cannot be deleted because deletion protection is activated. "
                    + "Deletion protection must be inactivated first.";

    @Test
    void deleteUserPool_deletionProtectionActive_isRefusedUntilDeactivated() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName": "cognito-deletion-protection-it", "DeletionProtection": "ACTIVE"}
                """).path("UserPool").path("Id").asText();

        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
                .then()
                .statusCode(200)
                .body("UserPool.DeletionProtection", equalTo("ACTIVE"));

        cognitoAction("DeleteUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(PROTECTED_MESSAGE));

        // The refusal left the pool untouched.
        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
                .then()
                .statusCode(200)
                .body("UserPool.Id", equalTo(poolId));

        cognitoAction("UpdateUserPool", "{\"UserPoolId\": \"" + poolId + "\", \"DeletionProtection\": \"INACTIVE\"}")
                .then()
                .statusCode(200);

        cognitoAction("DeleteUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
                .then()
                .statusCode(200);

        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
                .then()
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteUserPool_deletionProtectionInactiveOrUnset_deletes() throws Exception {
        String unset = cognitoJson("CreateUserPool", """
                {"PoolName": "cognito-deletion-protection-unset-it"}
                """).path("UserPool").path("Id").asText();
        cognitoAction("DeleteUserPool", "{\"UserPoolId\": \"" + unset + "\"}")
                .then()
                .statusCode(200);

        String inactive = cognitoJson("CreateUserPool", """
                {"PoolName": "cognito-deletion-protection-inactive-it", "DeletionProtection": "INACTIVE"}
                """).path("UserPool").path("Id").asText();
        cognitoAction("DeleteUserPool", "{\"UserPoolId\": \"" + inactive + "\"}")
                .then()
                .statusCode(200);
    }
}
