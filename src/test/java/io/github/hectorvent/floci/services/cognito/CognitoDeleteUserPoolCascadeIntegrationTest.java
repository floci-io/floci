package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeleteUserPool has to take the pool's records with it.
 *
 * <p>On AWS a pool id is never reused, so nothing can be inherited. Floci's
 * {@code floci:override-id} reserved tag makes ids caller-chosen and therefore reusable, which
 * turns any record left behind into one the next pool pinned to the same id picks up. Orphaned
 * users keep their password hashes and orphaned clients keep their secrets, so the recreated
 * pool has to come up empty.
 */
@QuarkusTest
class CognitoDeleteUserPoolCascadeIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String createPinnedPool(String poolId, String poolName) throws Exception {
        JsonNode pool = cognitoJson("CreateUserPool", """
            {
                "PoolName": "%s",
                "UserPoolTags": { "floci:override-id": "%s" }
            }
            """.formatted(poolName, poolId));
        return pool.path("UserPool").path("Id").asText();
    }

    @Test
    void aPoolRecreatedOnAPinnedIdInheritsNothingFromTheDeletedOne() throws Exception {
        String poolId = "us-east-1_cascadeprobe";

        assertEquals(poolId, createPinnedPool(poolId, "cascade-probe"));

        cognitoAction("AdminCreateUser", """
            {
                "UserPoolId": "%s",
                "Username": "orphanuser",
                "MessageAction": "SUPPRESS"
            }
            """.formatted(poolId)).then().statusCode(200);

        cognitoAction("CreateResourceServer", """
            {
                "UserPoolId": "%s",
                "Identifier": "https://api.example.com",
                "Name": "API",
                "Scopes": [ { "ScopeName": "read", "ScopeDescription": "r" } ]
            }
            """.formatted(poolId)).then().statusCode(200);

        String clientId = cognitoJson("CreateUserPoolClient", """
            {
                "UserPoolId": "%s",
                "ClientName": "orphanclient"
            }
            """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        assertTrue(clientId != null && !clientId.isEmpty(), "client id must be issued");

        cognitoAction("CreateGroup", """
            {
                "UserPoolId": "%s",
                "GroupName": "orphangroup"
            }
            """.formatted(poolId)).then().statusCode(200);

        cognitoAction("DeleteUserPool", """
            { "UserPoolId": "%s" }
            """.formatted(poolId)).then().statusCode(200);

        assertEquals(poolId, createPinnedPool(poolId, "cascade-probe-2"));

        assertEquals(0, cognitoJson("ListUsers", """
            { "UserPoolId": "%s" }
            """.formatted(poolId)).path("Users").size(), "users must not be inherited");

        assertEquals(0, cognitoJson("ListResourceServers", """
            { "UserPoolId": "%s", "MaxResults": 10 }
            """.formatted(poolId)).path("ResourceServers").size(),
                "resource servers must not be inherited");

        assertEquals(0, cognitoJson("ListUserPoolClients", """
            { "UserPoolId": "%s" }
            """.formatted(poolId)).path("UserPoolClients").size(),
                "clients must not be inherited");

        assertEquals(0, cognitoJson("ListGroups", """
            { "UserPoolId": "%s" }
            """.formatted(poolId)).path("Groups").size(), "groups must not be inherited");

        // The deleted pool's client id must not keep working against the new pool: it was issued
        // with a secret the caller of the recreated pool never saw.
        cognitoAction("DescribeUserPoolClient", """
            {
                "UserPoolId": "%s",
                "ClientId": "%s"
            }
            """.formatted(poolId, clientId))
            .then().statusCode(400)
            .body("__type", org.hamcrest.Matchers.equalTo("ResourceNotFoundException"));

        cognitoAction("DeleteUserPool", """
            { "UserPoolId": "%s" }
            """.formatted(poolId)).then().statusCode(200);
    }
}
