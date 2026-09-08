package io.github.hectorvent.floci.services.identitystore;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class IdentityStoreIntegrationTest {
    private static final String TYPE = "application/x-amz-json-1.1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/identitystore/aws4_request";
    private static final String STORE = "d-1234567890";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void completeLifecycleUsesAwsJson11Shapes() {
        String group = json("AWSIdentityStore.CreateGroup",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"DisplayName\":\"PlatformAdmins\",\"Description\":\"Initial\"}")
                .statusCode(200).body("IdentityStoreId", equalTo(STORE)).extract().path("GroupId");
        String user = json("AWSIdentityStore.CreateUser",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"UserName\":\"admin@example.com\",\"DisplayName\":\"Admin\"}")
                .statusCode(200).body("IdentityStoreId", equalTo(STORE)).extract().path("UserId");

        json("AWSIdentityStore.DescribeGroup",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"GroupId\":\"" + group + "\"}")
                .statusCode(200)
                .body("DisplayName", equalTo("PlatformAdmins"))
                .body("CreatedAt", notNullValue());
        json("AWSIdentityStore.DescribeUser",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"UserId\":\"" + user + "\"}")
                .statusCode(200)
                .body("UserName", equalTo("admin@example.com"))
                .body("CreatedAt", notNullValue());

        json("AWSIdentityStore.GetGroupId",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"AlternateIdentifier\":{\"UniqueAttribute\":{\"AttributePath\":\"displayName\",\"AttributeValue\":\"PlatformAdmins\"}}}")
                .statusCode(200).body("GroupId", equalTo(group));
        json("AWSIdentityStore.GetUserId",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"AlternateIdentifier\":{\"UniqueAttribute\":{\"AttributePath\":\"userName\",\"AttributeValue\":\"admin@example.com\"}}}")
                .statusCode(200).body("UserId", equalTo(user));

        json("AWSIdentityStore.UpdateGroup",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"GroupId\":\"" + group + "\",\"Operations\":[{\"AttributePath\":\"description\",\"AttributeValue\":\"Updated\"}]}")
                .statusCode(200);
        json("AWSIdentityStore.UpdateUser",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"UserId\":\"" + user + "\",\"Operations\":[{\"AttributePath\":\"displayName\",\"AttributeValue\":\"Updated Admin\"}]}")
                .statusCode(200);

        String membershipBody = "{\"IdentityStoreId\":\"" + STORE + "\",\"GroupId\":\"" + group
                + "\",\"MemberId\":{\"UserId\":\"" + user + "\"}}";
        String memberQuery = "{\"IdentityStoreId\":\"" + STORE + "\",\"GroupIds\":[\"" + group
                + "\"],\"MemberId\":{\"UserId\":\"" + user + "\"}}";
        json("AWSIdentityStore.IsMemberInGroups", memberQuery)
                .statusCode(200).body("Results[0].MembershipExists", equalTo(false));
        String membership = json("AWSIdentityStore.CreateGroupMembership", membershipBody)
                .statusCode(200).extract().path("MembershipId");
        json("AWSIdentityStore.DescribeGroupMembership",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"MembershipId\":\"" + membership + "\"}")
                .statusCode(200).body("GroupId", equalTo(group)).body("CreatedAt", notNullValue());
        json("AWSIdentityStore.GetGroupMembershipId", membershipBody)
                .statusCode(200).body("MembershipId", equalTo(membership));
        json("AWSIdentityStore.ListGroupMemberships",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"GroupId\":\"" + group + "\"}")
                .statusCode(200).body("GroupMemberships", hasSize(1));
        json("AWSIdentityStore.ListGroupMembershipsForMember",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"MemberId\":{\"UserId\":\"" + user + "\"}}")
                .statusCode(200).body("GroupMemberships", hasSize(1));
        json("AWSIdentityStore.IsMemberInGroups", memberQuery)
                .statusCode(200).body("Results[0].MembershipExists", equalTo(true));

        json("AWSIdentityStore.DeleteGroupMembership",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"MembershipId\":\"" + membership + "\"}")
                .statusCode(200);
        json("AWSIdentityStore.DeleteUser",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"UserId\":\"" + user + "\"}")
                .statusCode(200);
        json("AWSIdentityStore.DeleteGroup",
                "{\"IdentityStoreId\":\"" + STORE + "\",\"GroupId\":\"" + group + "\"}")
                .statusCode(200);

        json("AWSIdentityStore.ListUsers", "{\"IdentityStoreId\":\"" + STORE + "\"}")
                .statusCode(200).body("Users", hasSize(0));
        json("AWSIdentityStore.ListGroups", "{\"IdentityStoreId\":\"" + STORE + "\"}")
                .statusCode(200).body("Groups", hasSize(0));
    }

    @Test
    void extensionsAreReturnedOnlyWhenRequested() {
        String store = "d-0987654321";
        String user = json("AWSIdentityStore.CreateUser",
                "{\"IdentityStoreId\":\"" + store + "\",\"UserName\":\"extensions@example.com\","
                        + "\"Extensions\":{\"aws:identitystore:enterprise\":{\"department\":\"Platform\"}}}")
                .statusCode(200).extract().path("UserId");

        String describe = "{\"IdentityStoreId\":\"" + store + "\",\"UserId\":\"" + user + "\"}";
        json("AWSIdentityStore.DescribeUser", describe)
                .statusCode(200).body("Extensions", nullValue());
        json("AWSIdentityStore.DescribeUser",
                "{\"IdentityStoreId\":\"" + store + "\",\"UserId\":\"" + user
                        + "\",\"Extensions\":[\"aws:identitystore:enterprise\"]}")
                .statusCode(200)
                .body("Extensions.'aws:identitystore:enterprise'.department", equalTo("Platform"));
    }

    @Test
    void duplicateUserReturnsConflict() {
        String body = "{\"IdentityStoreId\":\"d-1111111111\",\"UserName\":\"duplicate@example.com\"}";
        json("AWSIdentityStore.CreateUser", body).statusCode(200);
        json("AWSIdentityStore.CreateUser", body)
                .statusCode(400).body("__type", equalTo("ConflictException"));
    }

    @Test
    void updatesRejectImmutableAndResourceSpecificAttributePaths() {
        String store = "d-2222222222";
        String user = json("AWSIdentityStore.CreateUser",
                "{\"IdentityStoreId\":\"" + store + "\",\"UserName\":\"immutable@example.com\"}")
                .statusCode(200).extract().path("UserId");
        String group = json("AWSIdentityStore.CreateGroup",
                "{\"IdentityStoreId\":\"" + store + "\",\"DisplayName\":\"ImmutableGroup\"}")
                .statusCode(200).extract().path("GroupId");

        json("AWSIdentityStore.UpdateUser",
                "{\"IdentityStoreId\":\"" + store + "\",\"UserId\":\"" + user
                        + "\",\"Operations\":[{\"AttributePath\":\"UserId\",\"AttributeValue\":\"forbidden\"}]}")
                .statusCode(400).body("__type", equalTo("ValidationException"));
        json("AWSIdentityStore.UpdateGroup",
                "{\"IdentityStoreId\":\"" + store + "\",\"GroupId\":\"" + group
                        + "\",\"Operations\":[{\"AttributePath\":\"aws:identitystore:enterprise.department\","
                        + "\"AttributeValue\":\"forbidden\"}]}")
                .statusCode(400).body("__type", equalTo("ValidationException"));

        json("AWSIdentityStore.DescribeUser",
                "{\"IdentityStoreId\":\"" + store + "\",\"UserId\":\"" + user + "\"}")
                .statusCode(200)
                .body("UserId", equalTo(user))
                .body("UserName", equalTo("immutable@example.com"));
    }

    private static io.restassured.response.ValidatableResponse json(String target, String body) {
        return given()
                .contentType(TYPE)
                .header("Authorization", AUTH)
                .header("X-Amz-Target", target)
                .body(body)
                .post("/")
                .then();
    }
}
