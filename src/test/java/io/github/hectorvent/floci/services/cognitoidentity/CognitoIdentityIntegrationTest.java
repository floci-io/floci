package io.github.hectorvent.floci.services.cognitoidentity;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoIdentityIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET = "AWSCognitoIdentityService";
    private static final String USER_POOL_PROVIDER = "cognito-idp.us-east-1.amazonaws.com/us-east-1_abc123";
    private static final String EU_WEST_1_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20260101/eu-west-1/cognito-identity/aws4_request";

    private static String identityPoolId;
    private static String identityPoolArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.specification.RequestSpecification action(String name) {
        return given()
                .header("X-Amz-Target", TARGET + "." + name)
                .contentType(CONTENT_TYPE);
    }

    @Test
    @Order(1)
    void createIdentityPool() {
        identityPoolId = action("CreateIdentityPool")
            .body("""
                {
                  "IdentityPoolName": "floci integration pool",
                  "AllowUnauthenticatedIdentities": true,
                  "AllowClassicFlow": true,
                  "DeveloperProviderName": "login.floci.test",
                  "SupportedLoginProviders": {"graph.facebook.com": "1234567890"},
                  "OpenIdConnectProviderARNs": ["arn:aws:iam::000000000000:oidc-provider/example.com"],
                  "SamlProviderARNs": ["arn:aws:iam::000000000000:saml-provider/example"],
                  "CognitoIdentityProviders": [
                    {"ProviderName": "%s", "ClientId": "client-abc", "ServerSideTokenCheck": true}
                  ],
                  "IdentityPoolTags": {"team": "identity"}
                }
                """.formatted(USER_POOL_PROVIDER))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPoolId", matchesPattern("us-east-1:[0-9a-f-]{36}"))
            .body("IdentityPoolName", equalTo("floci integration pool"))
            .body("AllowUnauthenticatedIdentities", equalTo(true))
            .body("AllowClassicFlow", equalTo(true))
            .body("DeveloperProviderName", equalTo("login.floci.test"))
            .body("SupportedLoginProviders.'graph.facebook.com'", equalTo("1234567890"))
            .body("OpenIdConnectProviderARNs[0]", equalTo("arn:aws:iam::000000000000:oidc-provider/example.com"))
            .body("SamlProviderARNs[0]", equalTo("arn:aws:iam::000000000000:saml-provider/example"))
            .body("CognitoIdentityProviders[0].ProviderName", equalTo(USER_POOL_PROVIDER))
            .body("CognitoIdentityProviders[0].ClientId", equalTo("client-abc"))
            .body("CognitoIdentityProviders[0].ServerSideTokenCheck", equalTo(true))
            .body("IdentityPoolTags.team", equalTo("identity"))
            .extract().path("IdentityPoolId");

        identityPoolArn = "arn:aws:cognito-identity:us-east-1:000000000000:identitypool/" + identityPoolId;
    }

    @Test
    @Order(2)
    void describeIdentityPool() {
        action("DescribeIdentityPool")
            .body("{\"IdentityPoolId\": \"" + identityPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPoolId", equalTo(identityPoolId))
            .body("IdentityPoolName", equalTo("floci integration pool"))
            .body("AllowUnauthenticatedIdentities", equalTo(true))
            .body("CognitoIdentityProviders[0].ClientId", equalTo("client-abc"))
            .body("IdentityPoolTags.team", equalTo("identity"));
    }

    @Test
    @Order(3)
    void describeMissingIdentityPoolReturnsResourceNotFound() {
        action("DescribeIdentityPool")
            .body("{\"IdentityPoolId\": \"us-east-1:00000000-0000-0000-0000-000000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(4)
    void createIdentityPoolRejectsInvalidName() {
        action("CreateIdentityPool")
            .body("{\"IdentityPoolName\": \"bad/name\", \"AllowUnauthenticatedIdentities\": false}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(5)
    void listIdentityPools() {
        action("ListIdentityPools")
            .body("{\"MaxResults\": 60}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPools.IdentityPoolId", hasItem(identityPoolId))
            .body("IdentityPools.IdentityPoolName", hasItem("floci integration pool"));
    }

    @Test
    @Order(6)
    void listIdentityPoolsRejectsOutOfRangeMaxResults() {
        action("ListIdentityPools")
            .body("{\"MaxResults\": 500}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(7)
    void getIdentityPoolRolesBeforeSetReturnsEmptyMaps() {
        action("GetIdentityPoolRoles")
            .body("{\"IdentityPoolId\": \"" + identityPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPoolId", equalTo(identityPoolId))
            .body("Roles", not(nullValue()))
            .body("Roles.keySet()", emptyIterable())
            .body("RoleMappings", not(nullValue()));
    }

    @Test
    @Order(8)
    void setAndGetIdentityPoolRoles() {
        action("SetIdentityPoolRoles")
            .body("""
                {
                  "IdentityPoolId": "%s",
                  "Roles": {
                    "authenticated": "arn:aws:iam::000000000000:role/authenticated",
                    "unauthenticated": "arn:aws:iam::000000000000:role/unauthenticated"
                  },
                  "RoleMappings": {
                    "%s:client-abc": {
                      "Type": "Rules",
                      "AmbiguousRoleResolution": "Deny",
                      "RulesConfiguration": {
                        "Rules": [
                          {"Claim": "isAdmin", "MatchType": "Equals", "Value": "true",
                           "RoleARN": "arn:aws:iam::000000000000:role/admin"}
                        ]
                      }
                    }
                  }
                }
                """.formatted(identityPoolId, USER_POOL_PROVIDER))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        Map<String, ?> roleMappings = action("GetIdentityPoolRoles")
            .body("{\"IdentityPoolId\": \"" + identityPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPoolId", equalTo(identityPoolId))
            .body("Roles.authenticated", equalTo("arn:aws:iam::000000000000:role/authenticated"))
            .body("Roles.unauthenticated", equalTo("arn:aws:iam::000000000000:role/unauthenticated"))
            .extract().path("RoleMappings");

        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) roleMappings.get(USER_POOL_PROVIDER + ":client-abc");
        assertNotNull(mapping, "RoleMappings must round-trip the provider key sent on SetIdentityPoolRoles");
        assertEquals("Rules", mapping.get("Type"));
        assertEquals("Deny", mapping.get("AmbiguousRoleResolution"));
    }

    @Test
    @Order(9)
    void setIdentityPoolRolesRejectsUnknownRoleKey() {
        action("SetIdentityPoolRoles")
            .body("""
                {"IdentityPoolId": "%s", "Roles": {"admin": "arn:aws:iam::000000000000:role/admin"}}
                """.formatted(identityPoolId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(10)
    void tagRoundTrip() {
        action("TagResource")
            .body("""
                {"ResourceArn": "%s", "Tags": {"env": "test"}}
                """.formatted(identityPoolArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        action("ListTagsForResource")
            .body("{\"ResourceArn\": \"" + identityPoolArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.team", equalTo("identity"))
            .body("Tags.env", equalTo("test"));

        action("DescribeIdentityPool")
            .body("{\"IdentityPoolId\": \"" + identityPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPoolTags.env", equalTo("test"));

        action("UntagResource")
            .body("""
                {"ResourceArn": "%s", "TagKeys": ["env"]}
                """.formatted(identityPoolArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        action("ListTagsForResource")
            .body("{\"ResourceArn\": \"" + identityPoolArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasKey("team"))
            .body("Tags", not(hasKey("env")));
    }

    @Test
    @Order(11)
    void listTagsForUnknownPoolReturnsResourceNotFound() {
        action("ListTagsForResource")
            .body("""
                {"ResourceArn": "arn:aws:cognito-identity:us-east-1:000000000000:identitypool/us-east-1:00000000-0000-0000-0000-000000000000"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(12)
    void principalTagAttributeMapRoundTrip() {
        action("SetPrincipalTagAttributeMap")
            .body("""
                {"IdentityPoolId": "%s", "IdentityProviderName": "%s",
                 "UseDefaults": false, "PrincipalTags": {"department": "custom:department"}}
                """.formatted(identityPoolId, USER_POOL_PROVIDER))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPoolId", equalTo(identityPoolId))
            .body("IdentityProviderName", equalTo(USER_POOL_PROVIDER))
            .body("UseDefaults", equalTo(false))
            .body("PrincipalTags.department", equalTo("custom:department"));

        action("GetPrincipalTagAttributeMap")
            .body("""
                {"IdentityPoolId": "%s", "IdentityProviderName": "%s"}
                """.formatted(identityPoolId, USER_POOL_PROVIDER))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UseDefaults", equalTo(false))
            .body("PrincipalTags.department", equalTo("custom:department"));
    }

    @Test
    @Order(13)
    void updateIdentityPoolReplacesTheWholePool() {
        action("UpdateIdentityPool")
            .body("""
                {
                  "IdentityPoolId": "%s",
                  "IdentityPoolName": "floci integration pool renamed",
                  "AllowUnauthenticatedIdentities": false
                }
                """.formatted(identityPoolId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPoolId", equalTo(identityPoolId))
            .body("IdentityPoolName", equalTo("floci integration pool renamed"))
            .body("AllowUnauthenticatedIdentities", equalTo(false))
            .body("AllowClassicFlow", equalTo(false))
            .body("CognitoIdentityProviders", emptyIterable())
            .body("SupportedLoginProviders.keySet()", emptyIterable())
            .body("IdentityPoolTags.keySet()", emptyIterable());

        action("GetIdentityPoolRoles")
            .body("{\"IdentityPoolId\": \"" + identityPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Roles.authenticated", equalTo("arn:aws:iam::000000000000:role/authenticated"));
    }

    @Test
    @Order(14)
    void unimplementedOperationReturnsUnknownOperation() {
        action("GetCredentialsForIdentity")
            .body("{\"IdentityId\": \"us-east-1:00000000-0000-0000-0000-000000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    @Order(15)
    void deleteIdentityPool() {
        action("DeleteIdentityPool")
            .body("{\"IdentityPoolId\": \"" + identityPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        action("DescribeIdentityPool")
            .body("{\"IdentityPoolId\": \"" + identityPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(16)
    void createIdentityPoolRequiresAllowUnauthenticatedIdentities() {
        action("CreateIdentityPool")
            .body("{\"IdentityPoolName\": \"missing member\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value null at 'allowUnauthenticatedIdentities'"
                    + " failed to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(17)
    void tagResourceWithCrossRegionArnWritesBackToThePoolRegion() {
        String euPoolId = action("CreateIdentityPool")
            .header("Authorization", EU_WEST_1_AUTHORIZATION)
            .body("{\"IdentityPoolName\": \"eu pool\", \"AllowUnauthenticatedIdentities\": false}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPoolId", matchesPattern("eu-west-1:[0-9a-f-]{36}"))
            .extract().path("IdentityPoolId");
        String euPoolArn = "arn:aws:cognito-identity:eu-west-1:000000000000:identitypool/" + euPoolId;

        action("TagResource")
            .body("""
                {"ResourceArn": "%s", "Tags": {"env": "eu"}}
                """.formatted(euPoolArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        action("DescribeIdentityPool")
            .header("Authorization", EU_WEST_1_AUTHORIZATION)
            .body("{\"IdentityPoolId\": \"" + euPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPoolTags.env", equalTo("eu"));

        action("DescribeIdentityPool")
            .body("{\"IdentityPoolId\": \"" + euPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        action("ListIdentityPools")
            .body("{\"MaxResults\": 60}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityPools.IdentityPoolId", not(hasItem(euPoolId)));

        action("DeleteIdentityPool")
            .header("Authorization", EU_WEST_1_AUTHORIZATION)
            .body("{\"IdentityPoolId\": \"" + euPoolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(18)
    void createIdentityPoolRejectsNonBooleanAllowUnauthenticatedIdentities() {
        action("CreateIdentityPool")
            .body("{\"IdentityPoolName\": \"wrong type\", \"AllowUnauthenticatedIdentities\": \"true\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("AllowUnauthenticatedIdentities must be a boolean."));
    }
}
