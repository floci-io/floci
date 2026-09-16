package io.github.hectorvent.floci.services.identitystore;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ScimIntegrationTest {
    private static final String TENANT = "9067f2a3c1-00000000-0000-0000-0000-000000000000";
    private static final String STORE = "d-9067f2a3c1";
    private static final String BEARER = "Bearer floci-scim-token";
    private static final String AWS_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/identitystore/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void rejectsUnknownBearerToken() {
        given()
                .header("Authorization", "Bearer wrong-token")
            .when()
                .get("/" + TENANT + "/scim/v2/ServiceProviderConfig")
            .then()
                .statusCode(401)
                .body("status", equalTo("401"));
    }

    @Test
    void createGroupUsesAwsScimShapeAndPersistsToIdentityStore() {
        String groupId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"externalId\":\"701984\",\"displayName\":\"SCIM Platform Admins\"}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(201)
                .body("schemas[0]", equalTo("urn:ietf:params:scim:schemas:core:2.0:Group"))
                .body("id", notNullValue())
                .body("externalId", equalTo("701984"))
                .body("displayName", equalTo("SCIM Platform Admins"))
                .body("meta.resourceType", equalTo("Group"))
                .body("meta.created", notNullValue())
                .body("meta.lastModified", notNullValue())
                .extract().path("id");

        given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", AWS_AUTH)
                .header("X-Amz-Target", "AWSIdentityStore.ListGroups")
                .body("{\"IdentityStoreId\":\"" + STORE + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Groups.GroupId", hasItem(groupId))
                .body("Groups.DisplayName", hasItem("SCIM Platform Admins"));
    }

    @Test
    void createUserUsesAwsScimShapeAndPersistsToIdentityStore() {
        String userId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"externalId\":\"2819\",\"userName\":\"bjensen@example.com\","
                        + "\"displayName\":\"Babs Jensen\",\"active\":true,"
                        + "\"name\":{\"formatted\":\"Ms. Barbara J Jensen III\","
                        + "\"familyName\":\"Jensen\",\"givenName\":\"Barbara\",\"middleName\":\"Jane\"},"
                        + "\"emails\":[{\"value\":\"bjensen@example.com\",\"type\":\"work\",\"primary\":true}]}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .body("schemas[0]", equalTo("urn:ietf:params:scim:schemas:core:2.0:User"))
                .body("id", notNullValue())
                .body("externalId", equalTo("2819"))
                .body("userName", equalTo("bjensen@example.com"))
                .body("displayName", equalTo("Babs Jensen"))
                .body("name.familyName", equalTo("Jensen"))
                .body("name.givenName", equalTo("Barbara"))
                .body("emails[0].value", equalTo("bjensen@example.com"))
                .body("emails[0].primary", equalTo(true))
                .body("active", equalTo(true))
                .body("meta.resourceType", equalTo("User"))
                .extract().path("id");

        given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", AWS_AUTH)
                .header("X-Amz-Target", "AWSIdentityStore.DescribeUser")
                .body("{\"IdentityStoreId\":\"" + STORE + "\",\"UserId\":\"" + userId + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("UserName", equalTo("bjensen@example.com"))
                .body("DisplayName", equalTo("Babs Jensen"))
                .body("Name.FamilyName", equalTo("Jensen"))
                .body("Emails[0].Value", equalTo("bjensen@example.com"));
    }

    @Test
    void createUserValidatesAwsUnsupportedAttributesAndRequiredFields() {
        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"missing@example.com\",\"displayName\":\"Missing Name\"}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"groups@example.com\",\"displayName\":\"Groups User\","
                        + "\"name\":{\"givenName\":\"Groups\",\"familyName\":\"User\"},\"groups\":[]}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"multi@example.com\",\"displayName\":\"Multi Email\","
                        + "\"name\":{\"givenName\":\"Multi\",\"familyName\":\"Email\"},"
                        + "\"emails\":[{\"value\":\"one@example.com\",\"primary\":true},"
                        + "{\"value\":\"two@example.com\",\"primary\":true}]}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));
    }

    @Test
    void getUserUsesAwsScimRepresentation() {
        String userId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"externalId\":\"get-user-ext\",\"userName\":\"get@example.com\","
                        + "\"displayName\":\"Get User\",\"name\":{\"givenName\":\"Get\",\"familyName\":\"User\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(200)
                .body("schemas[0]", equalTo("urn:ietf:params:scim:schemas:core:2.0:User"))
                .body("id", equalTo(userId))
                .body("externalId", equalTo("get-user-ext"))
                .body("userName", equalTo("get@example.com"))
                .body("displayName", equalTo("Get User"))
                .body("name.givenName", equalTo("Get"))
                .body("name.familyName", equalTo("User"))
                .body("meta.resourceType", equalTo("User"));

        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Users/9067f2a3c1-00000000-0000-0000-0000-000000000098")
            .then()
                .statusCode(404)
                .body("status", equalTo("404"));
    }

    @Test
    void getGroupUsesAwsScimRepresentation() {
        String groupId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"externalId\":\"get-group-ext\",\"displayName\":\"SCIM Get Group\"}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Groups/" + groupId)
            .then()
                .statusCode(200)
                .body("schemas[0]", equalTo("urn:ietf:params:scim:schemas:core:2.0:Group"))
                .body("id", equalTo(groupId))
                .body("externalId", equalTo("get-group-ext"))
                .body("displayName", equalTo("SCIM Get Group"))
                .body("meta.resourceType", equalTo("Group"))
                .body("meta.created", notNullValue())
                .body("meta.lastModified", notNullValue());

        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Groups/9067f2a3c1-00000000-0000-0000-0000-000000000099")
            .then()
                .statusCode(404)
                .body("status", equalTo("404"));
    }

    @Test
    void deleteUserUsesAwsScimStatusAndRemovesIdentityStoreResource() {
        String userId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"delete@example.com\",\"displayName\":\"Delete User\","
                        + "\"name\":{\"givenName\":\"Delete\",\"familyName\":\"User\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .header("Authorization", BEARER)
            .when()
                .delete("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(204);

        given()
                .header("Authorization", BEARER)
            .when()
                .delete("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(404)
                .body("status", equalTo("404"));
    }

    @Test
    void deleteGroupUsesAwsScimStatusAndRemovesIdentityStoreResource() {
        String groupId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"displayName\":\"SCIM Delete Me\"}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .header("Authorization", BEARER)
            .when()
                .delete("/" + TENANT + "/scim/v2/Groups/" + groupId)
            .then()
                .statusCode(204);

        given()
                .header("Authorization", BEARER)
            .when()
                .delete("/" + TENANT + "/scim/v2/Groups/" + groupId)
            .then()
                .statusCode(404)
                .body("status", equalTo("404"));
    }

    @Test
    void createGroupRequiresBearerAndKnownTenant() {
        given()
                .contentType("application/json")
                .body("{\"displayName\":\"Unauthorized Group\"}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(401)
                .body("schemas[0]", equalTo("urn:ietf:params:scim:api:messages:2.0:Error"))
                .body("status", equalTo("401"));

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"displayName\":\"Wrong Tenant Group\"}")
            .when()
                .post("/aaaaaaaaaa-00000000-0000-0000-0000-000000000000/scim/v2/Groups")
            .then()
                .statusCode(401)
                .body("status", equalTo("401"));
    }

    @Test
    void createGroupValidatesRequiredDisplayNameAndMemberLimit() {
        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));

        StringBuilder members = new StringBuilder("[\n");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                members.append(',');
            }
            members.append("{\"value\":\"11111111-2222-3333-4444-")
                    .append(String.format("%012d", i))
                    .append("\"}");
        }
        members.append(']');

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"displayName\":\"Too Many Members\",\"members\":" + members + "}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));
    }

    @Test
    void putUserOverwritesExistingUserAndPreservesResourceIdentity() {
        String userId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"externalId\":\"put-user-old\",\"userName\":\"put-user-old@example.com\","
                        + "\"displayName\":\"Put User Old\",\"nickName\":\"OldNick\","
                        + "\"name\":{\"givenName\":\"Put\",\"familyName\":\"Old\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");

        String createdAt = given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(200)
                .extract().path("meta.created");

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"id\":\"" + userId + "\",\"externalId\":\"put-user-new\","
                        + "\"userName\":\"put-user-new@example.com\",\"displayName\":\"Put User New\","
                        + "\"name\":{\"formatted\":\"Put User New\",\"givenName\":\"Put\",\"familyName\":\"New\"},"
                        + "\"emails\":[{\"value\":\"put-user-new@example.com\",\"type\":\"work\",\"primary\":true}],"
                        + "\"active\":false,\"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User\":{"
                        + "\"department\":\"Platform\",\"manager\":{\"value\":\"9067f2a3c1-00000000-0000-0000-0000-000000000077\","
                        + "\"$ref\":\"../Users/9067f2a3c1-00000000-0000-0000-0000-000000000077\"}}}")
            .when()
                .put("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(201)
                .body("id", equalTo(userId))
                .body("externalId", equalTo("put-user-new"))
                .body("userName", equalTo("put-user-new@example.com"))
                .body("displayName", equalTo("Put User New"))
                .body("nickName", nullValue())
                .body("active", equalTo(false))
                .body("emails[0].value", equalTo("put-user-new@example.com"))
                .body("meta.created", equalTo(createdAt))
                .body("'urn:ietf:params:scim:schemas:extension:enterprise:2.0:User'.department", equalTo("Platform"))
                .body("'urn:ietf:params:scim:schemas:extension:enterprise:2.0:User'.manager.value",
                        equalTo("9067f2a3c1-00000000-0000-0000-0000-000000000077"));

        given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", AWS_AUTH)
                .header("X-Amz-Target", "AWSIdentityStore.DescribeUser")
                .body("{\"IdentityStoreId\":\"" + STORE + "\",\"UserId\":\"" + userId + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("UserName", equalTo("put-user-new@example.com"))
                .body("DisplayName", equalTo("Put User New"))
                .body("NickName", nullValue())
                .body("UserStatus", equalTo("DISABLED"));
    }

    @Test
    void putUserRejectsInvalidReplacementAndConflictingUserName() {
        String userId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"put-target@example.com\",\"displayName\":\"Put Target\","
                        + "\"name\":{\"givenName\":\"Put\",\"familyName\":\"Target\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"put-conflict@example.com\",\"displayName\":\"Put Conflict\","
                        + "\"name\":{\"givenName\":\"Put\",\"familyName\":\"Conflict\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"put-target@example.com\",\"displayName\":\"Missing Name\"}")
            .when()
                .put("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"put-conflict@example.com\",\"displayName\":\"Put Target Conflict\","
                        + "\"name\":{\"givenName\":\"Put\",\"familyName\":\"Target\"}}")
            .when()
                .put("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(409)
                .body("status", equalTo("409"));

        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(200)
                .body("userName", equalTo("put-target@example.com"))
                .body("displayName", equalTo("Put Target"));
    }

    @Test
    void patchUserUpdatesSupportedAttributesAndReturnsUser() {
        String userId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"externalId\":\"patch-user-old\",\"userName\":\"patch-user@example.com\","
                        + "\"displayName\":\"Patch User Old\","
                        + "\"name\":{\"givenName\":\"Patch\",\"familyName\":\"User\"},"
                        + "\"emails\":[{\"value\":\"old@example.com\",\"primary\":true}]}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":["
                        + "{\"op\":\"replace\",\"path\":\"active\",\"value\":\"false\"},"
                        + "{\"op\":\"replace\",\"value\":{\"displayName\":\"Patch User New\","
                        + "\"externalId\":\"patch-user-new\"}},"
                        + "{\"op\":\"replace\",\"path\":\"emails\",\"value\":["
                        + "{\"value\":\"new@example.com\",\"type\":\"work\",\"primary\":true}]}]}")
            .when()
                .patch("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(200)
                .body("id", equalTo(userId))
                .body("active", equalTo(false))
                .body("displayName", equalTo("Patch User New"))
                .body("externalId", equalTo("patch-user-new"))
                .body("emails[0].value", equalTo("new@example.com"));
    }

    @Test
    void patchUserRejectsUnsupportedAndRepeatedProtectedChanges() {
        String userId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"patch-user-validation@example.com\",\"displayName\":\"Patch Validation\","
                        + "\"name\":{\"givenName\":\"Patch\",\"familyName\":\"Validation\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":["
                        + "{\"op\":\"replace\",\"path\":\"active\",\"value\":true},"
                        + "{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}")
            .when()
                .patch("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":["
                        + "{\"op\":\"remove\",\"path\":\"active\"}]}")
            .when()
                .patch("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":["
                        + "{\"op\":\"replace\",\"path\":\"password\",\"value\":\"nope\"}]}")
            .when()
                .patch("/" + TENANT + "/scim/v2/Users/" + userId)
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));
    }

    @Test
    void patchGroupUpdatesAttributesAndMemberships() {
        String firstUserId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"patch-group-one@example.com\",\"displayName\":\"Patch One\","
                        + "\"name\":{\"givenName\":\"Patch\",\"familyName\":\"One\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");
        String secondUserId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"patch-group-two@example.com\",\"displayName\":\"Patch Two\","
                        + "\"name\":{\"givenName\":\"Patch\",\"familyName\":\"Two\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");
        String groupId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"externalId\":\"patch-group-old\",\"displayName\":\"Patch Group Old\","
                        + "\"members\":[{\"value\":\"" + firstUserId + "\"}]}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":["
                        + "{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Patch Group New\"},"
                        + "{\"op\":\"replace\",\"path\":\"externalId\",\"value\":\"patch-group-new\"},"
                        + "{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"" + secondUserId + "\"}]},"
                        + "{\"op\":\"remove\",\"path\":\"members\",\"value\":[{\"value\":\"" + firstUserId + "\"}]}]}")
            .when()
                .patch("/" + TENANT + "/scim/v2/Groups/" + groupId)
            .then()
                .statusCode(204);

        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Groups/" + groupId)
            .then()
                .statusCode(200)
                .body("displayName", equalTo("Patch Group New"))
                .body("externalId", equalTo("patch-group-new"));

        given()
                .header("Authorization", BEARER)
                .queryParam("filter", "members.value eq \"" + secondUserId + "\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(200)
                .body("Resources.id", hasItem(groupId));

        given()
                .header("Authorization", BEARER)
                .queryParam("filter", "members.value eq \"" + firstUserId + "\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(200)
                .body("totalResults", equalTo(0));
    }

    @Test
    void patchGroupRejectsUnsupportedOrBulkMembershipChanges() {
        String groupId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"displayName\":\"Patch Group Validation\"}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                        + "\"Operations\":[{\"op\":\"remove\",\"path\":\"members\",\"value\":[]}]}")
            .when()
                .patch("/" + TENANT + "/scim/v2/Groups/" + groupId)
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));

        given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                        + "\"Operations\":[{\"op\":\"replace\",\"path\":\"description\",\"value\":\"nope\"}]}")
            .when()
                .patch("/" + TENANT + "/scim/v2/Groups/" + groupId)
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));
    }

    @Test
    void serviceProviderConfigMatchesAwsScimCapabilities() {
        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/ServiceProviderConfig")
            .then()
                .statusCode(200)
                .body("schemas[0]", equalTo("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"))
                .body("authenticationSchemes[0].type", equalTo("oauthbearertoken"))
                .body("authenticationSchemes[0].primary", equalTo(true))
                .body("patch.supported", equalTo(true))
                .body("bulk.supported", equalTo(false))
                .body("bulk.maxOperations", equalTo(1))
                .body("bulk.maxPayloadSize", equalTo(1048576))
                .body("filter.supported", equalTo(true))
                .body("filter.maxResults", equalTo(50))
                .body("changePassword.supported", equalTo(false))
                .body("sort.supported", equalTo(false))
                .body("etag.supported", equalTo(false));
    }

    @Test
    void listResourceTypesReturnsAwsScimCatalog() {
        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/ResourceTypes")
            .then()
                .statusCode(200)
                .body("schemas[0]", equalTo("urn:ietf:params:scim:api:messages:2.0:ListResponse"))
                .body("totalResults", equalTo(2))
                .body("itemsPerPage", equalTo(2))
                .body("startIndex", equalTo(1))
                .body("Resources.id", hasItem("User"))
                .body("Resources.id", hasItem("Group"))
                .body("Resources.find { it.id == 'User' }.endpoint", equalTo("/Users"))
                .body("Resources.find { it.id == 'User' }.schema", equalTo("urn:ietf:params:scim:schemas:core:2.0:User"))
                .body("Resources.find { it.id == 'User' }.schemaExtensions[0].schema",
                        equalTo("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"))
                .body("Resources.find { it.id == 'User' }.schemaExtensions[0].required", equalTo(true))
                .body("Resources.find { it.id == 'User' }.meta.resourceType", equalTo("ResourceType"))
                .body("Resources.find { it.id == 'User' }.meta.location", notNullValue());
    }

    @Test
    void listSchemasReturnsAwsScimCatalog() {
        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Schemas")
            .then()
                .statusCode(200)
                .body("schemas[0]", equalTo("urn:ietf:params:scim:api:messages:2.0:ListResponse"))
                .body("totalResults", equalTo(3))
                .body("itemsPerPage", equalTo(3))
                .body("startIndex", equalTo(1))
                .body("Resources.id", hasItem("urn:ietf:params:scim:schemas:core:2.0:User"))
                .body("Resources.id", hasItem("urn:ietf:params:scim:schemas:core:2.0:Group"))
                .body("Resources.id", hasItem("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"));
    }

    @Test
    void getSchemaReturnsAwsScimSchema() {
        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Schemas/urn:ietf:params:scim:schemas:core:2.0:User")
            .then()
                .statusCode(200)
                .body("id", equalTo("urn:ietf:params:scim:schemas:core:2.0:User"))
                .body("name", equalTo("User"))
                .body("description", equalTo("User Schema"))
                .body("attributes.name", hasItem("userName"))
                .body("attributes.find { it.name == 'userName' }.required", equalTo(true));

        given()
                .header("Authorization", BEARER)
            .when()
                .get("/" + TENANT + "/scim/v2/Schemas/urn:example:missing")
            .then()
                .statusCode(404)
                .body("status", equalTo("404"));
    }

    @Test
    void listUsersSupportsAwsFiltersAndCursorPagination() {
        String managerId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"manager-list@example.com\",\"displayName\":\"List Manager\","
                        + "\"name\":{\"givenName\":\"List\",\"familyName\":\"Manager\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");

        String userId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"externalId\":\"list-users-ext\",\"userName\":\"list-users@example.com\","
                        + "\"displayName\":\"SCIM List Users Target\","
                        + "\"name\":{\"givenName\":\"List\",\"familyName\":\"Users\"},"
                        + "\"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User\":{"
                        + "\"manager\":{\"value\":\"" + managerId + "\"}}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");

        String groupId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"displayName\":\"SCIM List Users Group\",\"members\":[{\"value\":\""
                        + userId + "\",\"type\":\"User\"}]}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .header("Authorization", BEARER)
                .queryParam("filter", "userName eq \"list-users@example.com\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(200)
                .body("totalResults", equalTo(1))
                .body("Resources[0].id", equalTo(userId));

        given()
                .header("Authorization", BEARER)
                .queryParam("filter", "groups.value eq \"" + groupId + "\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(200)
                .body("Resources.id", hasItem(userId));

        given()
                .header("Authorization", BEARER)
                .queryParam("filter", "id eq \"" + userId + "\" and manager eq \"" + managerId + "\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(200)
                .body("totalResults", equalTo(1))
                .body("Resources[0].id", equalTo(userId));

        String nextCursor = given()
                .header("Authorization", BEARER)
                .queryParam("cursor", "")
                .queryParam("count", 1)
            .when()
                .get("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(200)
                .body("itemsPerPage", equalTo(1))
                .body("totalResults", org.hamcrest.Matchers.nullValue())
                .body("startIndex", org.hamcrest.Matchers.nullValue())
                .body("nextCursor", notNullValue())
                .extract().path("nextCursor");

        given()
                .header("Authorization", BEARER)
                .queryParam("cursor", nextCursor)
                .queryParam("filter", "userName eq \"changed@example.com\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));
    }

    @Test
    void listGroupsSupportsAwsFiltersAndCursorPagination() {
        String userId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"userName\":\"list-groups@example.com\",\"displayName\":\"List Groups User\","
                        + "\"name\":{\"givenName\":\"List\",\"familyName\":\"Groups\"}}")
            .when()
                .post("/" + TENANT + "/scim/v2/Users")
            .then()
                .statusCode(201)
                .extract().path("id");

        String groupId = given()
                .contentType("application/json")
                .header("Authorization", BEARER)
                .body("{\"externalId\":\"list-groups-ext\",\"displayName\":\"SCIM List Groups Target\","
                        + "\"members\":[{\"value\":\"" + userId + "\",\"type\":\"User\"}]}")
            .when()
                .post("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .header("Authorization", BEARER)
                .queryParam("filter", "displayName eq \"SCIM List Groups Target\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(200)
                .body("schemas[0]", equalTo("urn:ietf:params:scim:api:messages:2.0:ListResponse"))
                .body("totalResults", equalTo(1))
                .body("itemsPerPage", equalTo(1))
                .body("startIndex", equalTo(1))
                .body("Resources[0].id", equalTo(groupId));

        given()
                .header("Authorization", BEARER)
                .queryParam("filter", "members.value eq \"" + userId + "\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(200)
                .body("Resources.id", hasItem(groupId));

        given()
                .header("Authorization", BEARER)
                .queryParam("filter", "id eq \"" + groupId + "\" and member eq \"" + userId + "\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(200)
                .body("totalResults", equalTo(1))
                .body("Resources[0].id", equalTo(groupId));

        String nextCursor = given()
                .header("Authorization", BEARER)
                .queryParam("cursor", "")
                .queryParam("count", 1)
            .when()
                .get("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(200)
                .body("itemsPerPage", equalTo(1))
                .body("totalResults", org.hamcrest.Matchers.nullValue())
                .body("startIndex", org.hamcrest.Matchers.nullValue())
                .body("nextCursor", notNullValue())
                .extract().path("nextCursor");

        given()
                .header("Authorization", BEARER)
                .queryParam("cursor", nextCursor)
                .queryParam("count", 1)
            .when()
                .get("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(200)
                .body("itemsPerPage", equalTo(1));

        given()
                .header("Authorization", BEARER)
                .queryParam("cursor", nextCursor)
                .queryParam("filter", "displayName eq \"changed\"")
            .when()
                .get("/" + TENANT + "/scim/v2/Groups")
            .then()
                .statusCode(400)
                .body("status", equalTo("400"));
    }
}
