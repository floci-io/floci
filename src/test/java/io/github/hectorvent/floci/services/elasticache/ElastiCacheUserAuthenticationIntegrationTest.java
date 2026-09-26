package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Control-plane coverage for how CreateUser and ModifyUser resolve a user's authentication:
 * the top-level Passwords and NoPasswordRequired members as well as AuthenticationMode.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheUserAuthenticationIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String PASSWORD_USER = "auth-it-password-user";
    private static final String OPEN_USER = "auth-it-open-user";
    private static final String CREATE = "CreateUserResponse.CreateUserResult.";
    private static final String MODIFY = "ModifyUserResponse.ModifyUserResult.";
    private static final String DESCRIBE = "DescribeUsersResponse.DescribeUsersResult.Users.member.";

    private static RequestSpecification request(String action) {
        return given()
            .formParam("Action", action)
            .header("Authorization", AUTH_HEADER);
    }

    private static RequestSpecification createUser(String userId) {
        return request("CreateUser")
            .formParam("UserId", userId)
            .formParam("UserName", userId)
            .formParam("Engine", "valkey")
            .formParam("AccessString", "on ~* +@all");
    }

    @Test
    @Order(1)
    void createUserWithTopLevelPasswordsUsesPasswordAuthentication() {
        createUser(PASSWORD_USER)
            .formParam("Passwords.member.1", "first-password-0123456789")
            .formParam("Passwords.member.2", "second-password-0123456789")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(CREATE + "Authentication.Type", equalTo("password"))
            .body(CREATE + "Authentication.PasswordCount", equalTo("2"));
    }

    @Test
    @Order(2)
    void createUserWithNoPasswordRequiredUsesNoPasswordAuthentication() {
        createUser(OPEN_USER)
            .formParam("NoPasswordRequired", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(CREATE + "Authentication.Type", equalTo("no-password-required"))
            .body(CREATE + "Authentication.PasswordCount", equalTo("0"));
    }

    @Test
    @Order(3)
    void createUserRejectsPasswordsTogetherWithNoPasswordRequired() {
        createUser("auth-it-conflicting-user")
            .formParam("Passwords.member.1", "first-password-0123456789")
            .formParam("NoPasswordRequired", "true")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterCombination"));
    }

    @Test
    @Order(4)
    void createUserRejectsPasswordModeWithoutPasswords() {
        createUser("auth-it-passwordless-user")
            .formParam("AuthenticationMode.Type", "password")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(5)
    void modifyUserWithPasswordsSwitchesANoPasswordUserToPasswordAuthentication() {
        request("ModifyUser")
            .formParam("UserId", OPEN_USER)
            .formParam("Passwords.member.1", "new-password-0123456789")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MODIFY + "Authentication.Type", equalTo("password"))
            .body(MODIFY + "Authentication.PasswordCount", equalTo("1"));
    }

    @Test
    @Order(6)
    void modifyUserWithAuthenticationModeSwitchesAPasswordUserToNoPassword() {
        request("ModifyUser")
            .formParam("UserId", PASSWORD_USER)
            .formParam("AuthenticationMode.Type", "no-password-required")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MODIFY + "Authentication.Type", equalTo("no-password-required"))
            .body(MODIFY + "Authentication.PasswordCount", equalTo("0"));
    }

    @Test
    @Order(7)
    void modifyUserReplacesTheAccessString() {
        request("ModifyUser")
            .formParam("UserId", PASSWORD_USER)
            .formParam("AccessString", "on ~app:* -@all +@read")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MODIFY + "AccessString", equalTo("on ~app:* -@all +@read"));
    }

    @Test
    @Order(8)
    void modifyUserAppendsToTheAccessString() {
        request("ModifyUser")
            .formParam("UserId", PASSWORD_USER)
            .formParam("AppendAccessString", "+@write")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MODIFY + "AccessString", equalTo("on ~app:* -@all +@read +@write"));
    }

    @Test
    @Order(9)
    void modifyUserRejectsAccessStringTogetherWithAppendAccessString() {
        request("ModifyUser")
            .formParam("UserId", PASSWORD_USER)
            .formParam("AccessString", "on ~* +@all")
            .formParam("AppendAccessString", "+@write")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterCombination"));
    }

    @Test
    @Order(10)
    void describeUsersReportsTheModifiedAuthentication() {
        request("DescribeUsers")
            .formParam("UserId", OPEN_USER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(DESCRIBE + "Authentication.Type", equalTo("password"))
            .body(DESCRIBE + "Authentication.PasswordCount", equalTo("1"));
    }

    @Test
    @Order(11)
    void deleteUsers() {
        for (String userId : new String[] {PASSWORD_USER, OPEN_USER}) {
            request("DeleteUser")
                .formParam("UserId", userId)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }
}
