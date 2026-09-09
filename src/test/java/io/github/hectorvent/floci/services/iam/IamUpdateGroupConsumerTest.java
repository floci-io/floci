package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Wire-level tests for {@code UpdateGroup}.
 *
 * <p>New class, not appended to {@code IamIntegrationTest} (which shares mutable
 * state across {@code @Order}-sequenced tests), so falsifiability isolates per
 * operation (CS-001).
 */
@QuarkusTest
class IamUpdateGroupConsumerTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static Response call(String action, String... formParams) {
        var request = given().formParam("Action", action).header("Authorization", AUTH_HEADER);
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/");
    }

    private static void createGroup(String name) {
        call("CreateGroup", "GroupName", name).then().statusCode(200);
    }

    @Test
    void updateGroup_renamesGroup() {
        createGroup("ab-update-group-before");

        call("UpdateGroup", "GroupName", "ab-update-group-before", "NewGroupName", "ab-update-group-after")
        .then()
            .statusCode(200);

        call("GetGroup", "GroupName", "ab-update-group-after")
        .then()
            .statusCode(200)
            .body("GetGroupResponse.GetGroupResult.Group.GroupName", equalTo("ab-update-group-after"));

        call("GetGroup", "GroupName", "ab-update-group-before")
        .then()
            .statusCode(404);
    }

    @Test
    void updateGroup_changesPath() {
        createGroup("ab-update-group-path");

        call("UpdateGroup", "GroupName", "ab-update-group-path", "NewPath", "/custom/")
        .then()
            .statusCode(200);

        call("GetGroup", "GroupName", "ab-update-group-path")
        .then()
            .statusCode(200)
            .body("GetGroupResponse.GetGroupResult.Group.Path", equalTo("/custom/"))
            .body("GetGroupResponse.GetGroupResult.Group.Arn", containsString(":group/custom/"));
    }

    @Test
    void updateGroup_newNameAlreadyExists_returnsEntityAlreadyExists() {
        createGroup("ab-update-group-existing-1");
        createGroup("ab-update-group-existing-2");

        call("UpdateGroup", "GroupName", "ab-update-group-existing-1",
                "NewGroupName", "ab-update-group-existing-2")
        .then()
            .statusCode(409)
            .body(containsString("EntityAlreadyExists"));
    }

    @Test
    void updateGroup_unknownGroup_returnsNoSuchEntity() {
        call("UpdateGroup", "GroupName", "ab-update-group-doesnotexist", "NewPath", "/x/")
        .then()
            .statusCode(404);
    }

    @Test
    void updateGroup_malformedGroupName_returnsValidationError() {
        call("UpdateGroup", "GroupName", "not a valid name!", "NewPath", "/x/")
        .then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void updateGroup_malformedNewPath_returnsValidationError() {
        createGroup("ab-update-group-bad-path");

        call("UpdateGroup", "GroupName", "ab-update-group-bad-path", "NewPath", "no-leading-slash")
        .then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }
}
