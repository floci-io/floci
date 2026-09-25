package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * Control-plane coverage for CreateUserGroup, DescribeUserGroups, ModifyUserGroup and
 * DeleteUserGroup; these never touch a container.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheUserGroupIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String DEFAULT_USER = "ug-it-default-user";
    private static final String APP_USER = "ug-it-app-user";
    private static final String USER_GROUP = "ug-it-group";
    private static final String CREATE = "CreateUserGroupResponse.CreateUserGroupResult.";
    private static final String MODIFY = "ModifyUserGroupResponse.ModifyUserGroupResult.";
    private static final String DESCRIBE = "DescribeUserGroupsResponse.DescribeUserGroupsResult.UserGroups.member.";

    private static RequestSpecification request(String action) {
        return given()
            .formParam("Action", action)
            .header("Authorization", AUTH_HEADER);
    }

    private static void createUser(String userId, String userName) {
        request("CreateUser")
            .formParam("UserId", userId)
            .formParam("UserName", userName)
            .formParam("Engine", "redis")
            .formParam("AccessString", "on ~* +@all")
            .formParam("AuthenticationMode.Type", "password")
            .formParam("AuthenticationMode.Passwords.member.1", "password-0123456789")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(1)
    void createUserGroupWithoutADefaultUserIsRejected() {
        createUser(DEFAULT_USER, "default");
        createUser(APP_USER, "ug-it-app");

        request("CreateUserGroup")
            .formParam("UserGroupId", "ug-it-no-default")
            .formParam("Engine", "redis")
            .formParam("UserIds.member.1", APP_USER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("DefaultUserRequired"));
    }

    @Test
    @Order(2)
    void createUserGroupStoresTheIdInLowercase() {
        request("CreateUserGroup")
            .formParam("UserGroupId", "UG-IT-Group")
            .formParam("Engine", "redis")
            .formParam("UserIds.member.1", DEFAULT_USER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(CREATE + "UserGroupId", equalTo(USER_GROUP))
            .body(CREATE + "Status", equalTo("active"))
            .body(CREATE + "Engine", equalTo("redis"))
            .body(CREATE + "UserIds.member", equalTo(DEFAULT_USER))
            .body(CREATE + "MinimumEngineVersion", equalTo("6.0"))
            .body(CREATE + "ARN", containsString(":usergroup:" + USER_GROUP));
    }

    @Test
    @Order(3)
    void userGroupArnCarriesTheRequestRegion() {
        given()
            .formParam("Action", "CreateUserGroup")
            .formParam("UserGroupId", "ug-it-regional")
            .formParam("Engine", "redis")
            .formParam("UserIds.member.1", DEFAULT_USER)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260412/ap-south-1/elasticache/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(CREATE + "ARN", containsString(":ap-south-1:"));

        request("DeleteUserGroup")
            .formParam("UserGroupId", "ug-it-regional")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void createUserGroupWithATakenIdIsRejected() {
        request("CreateUserGroup")
            .formParam("UserGroupId", USER_GROUP)
            .formParam("Engine", "redis")
            .formParam("UserIds.member.1", DEFAULT_USER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UserGroupAlreadyExists"));
    }

    @Test
    @Order(4)
    void modifyUserGroupAddsAUser() {
        request("ModifyUserGroup")
            .formParam("UserGroupId", USER_GROUP)
            .formParam("UserIdsToAdd.member.1", APP_USER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MODIFY + "UserIds.member", hasItems(DEFAULT_USER, APP_USER));
    }

    @Test
    @Order(5)
    void modifyUserGroupRejectsAnUnknownUser() {
        request("ModifyUserGroup")
            .formParam("UserGroupId", USER_GROUP)
            .formParam("UserIdsToAdd.member.1", "ug-it-missing-user")
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(6)
    void describeUsersReportsTheUserGroupsAUserBelongsTo() {
        request("DescribeUsers")
            .formParam("UserId", APP_USER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeUsersResponse.DescribeUsersResult.Users.member.UserGroupIds.member", equalTo(USER_GROUP));
    }

    @Test
    @Order(7)
    void describeUserGroupsFiltersById() {
        request("DescribeUserGroups")
            .formParam("UserGroupId", USER_GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(DESCRIBE + "UserGroupId", equalTo(USER_GROUP))
            .body(DESCRIBE + "UserIds.member", hasItems(DEFAULT_USER, APP_USER));
    }

    @Test
    @Order(8)
    void deletingAUserRemovesItFromItsUserGroups() {
        request("DeleteUser")
            .formParam("UserId", APP_USER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        request("DescribeUserGroups")
            .formParam("UserGroupId", USER_GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(DESCRIBE + "UserIds.member", equalTo(DEFAULT_USER));
    }

    @Test
    @Order(9)
    void deleteUserGroupThenDescribeReportsNotFound() {
        request("DeleteUserGroup")
            .formParam("UserGroupId", USER_GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteUserGroupResponse.DeleteUserGroupResult.UserGroupId", equalTo(USER_GROUP));

        request("DescribeUserGroups")
            .formParam("UserGroupId", USER_GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("UserGroupNotFound"));

        request("DeleteUser")
            .formParam("UserId", DEFAULT_USER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
