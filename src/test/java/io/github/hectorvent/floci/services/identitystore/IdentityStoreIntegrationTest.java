package io.github.hectorvent.floci.services.identitystore;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class IdentityStoreIntegrationTest {
    private static final String TYPE = "application/x-amz-json-1.1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/identitystore/aws4_request";
    private static final String STORE = "d-1234567890";

    @BeforeAll
    static void configureRestAssured() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void groupUserAndMembershipLifecycle() {
        String group = json("AWSIdentityStore.CreateGroup", "{\"IdentityStoreId\":\"" + STORE + "\",\"DisplayName\":\"PlatformAdmins\"}")
                .statusCode(200).extract().path("GroupId");
        String user = json("AWSIdentityStore.CreateUser", "{\"IdentityStoreId\":\"" + STORE + "\",\"UserName\":\"admin@example.com\"}")
                .statusCode(200).extract().path("UserId");
        String query = "{\"IdentityStoreId\":\"" + STORE + "\",\"GroupIds\":[\"" + group + "\"],\"MemberId\":{\"UserId\":\"" + user + "\"}}";
        json("AWSIdentityStore.IsMemberInGroups", query).statusCode(200).body("Results[0].MembershipExists", equalTo(false));
        json("AWSIdentityStore.CreateGroupMembership", "{\"IdentityStoreId\":\"" + STORE + "\",\"GroupId\":\"" + group + "\",\"MemberId\":{\"UserId\":\"" + user + "\"}}")
                .statusCode(200).body("MembershipId", notNullValue());
        json("AWSIdentityStore.IsMemberInGroups", query).statusCode(200).body("Results[0].MembershipExists", equalTo(true));
    }

    @Test
    void duplicateUserReturnsConflict() {
        json("AWSIdentityStore.CreateUser", "{\"IdentityStoreId\":\"d-0987654321\",\"UserName\":\"duplicate@example.com\"}").statusCode(200);
        json("AWSIdentityStore.CreateUser", "{\"IdentityStoreId\":\"d-0987654321\",\"UserName\":\"duplicate@example.com\"}")
                .statusCode(400).body("__type", equalTo("ConflictException"));
    }

    private static io.restassured.response.ValidatableResponse json(String target, String body) {
        return given().contentType(TYPE).header("Authorization", AUTH).header("X-Amz-Target", target).body(body).post("/").then();
    }
}
