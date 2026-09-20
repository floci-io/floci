package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the IAM login profile actions via the Query Protocol, covering the full
 * HTTP stack through {@link AwsQueryController} → {@link IamQueryHandler}.
 *
 * <p>Ordered: a login profile is a single per-user value, so these cases share state
 * deliberately: missing before create, create, duplicate create, get-after-create, update,
 * update against an unknown user, delete-user conflict, delete, missing after delete,
 * duplicate delete, delete the user.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamLoginProfileIntegrationTest {

    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String USER_NAME = "login-profile-user";

    @Test
    @Order(1)
    void createTheUser() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserName", USER_NAME)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void getBeforeAnyCreateIsNoSuchEntity() {
        given()
            .formParam("Action", "GetLoginProfile")
            .formParam("UserName", USER_NAME)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(3)
    void createSetsTheProfile() {
        given()
            .formParam("Action", "CreateLoginProfile")
            .formParam("UserName", USER_NAME)
            .formParam("Password", "Sup3r$ecret!")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateLoginProfileResponse.CreateLoginProfileResult.LoginProfile.UserName",
                    equalTo(USER_NAME))
            .body("CreateLoginProfileResponse.CreateLoginProfileResult.LoginProfile.PasswordResetRequired",
                    equalTo("false"))
            .body("CreateLoginProfileResponse.CreateLoginProfileResult.LoginProfile.CreateDate",
                    notNullValue());
    }

    @Test
    @Order(4)
    void createAgainIsEntityAlreadyExists() {
        given()
            .formParam("Action", "CreateLoginProfile")
            .formParam("UserName", USER_NAME)
            .formParam("Password", "AnotherP4ss!")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    @Test
    @Order(5)
    void getReturnsTheProfileJustCreated() {
        given()
            .formParam("Action", "GetLoginProfile")
            .formParam("UserName", USER_NAME)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetLoginProfileResponse.GetLoginProfileResult.LoginProfile.UserName", equalTo(USER_NAME))
            .body("GetLoginProfileResponse.GetLoginProfileResult.LoginProfile.PasswordResetRequired",
                    equalTo("false"));
    }

    @Test
    @Order(6)
    void updateWithoutUserNameIsValidationError() {
        given()
            .formParam("Action", "UpdateLoginProfile")
            .formParam("Password", "NewP4ssword!")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(7)
    void updateAgainstAnUnknownUserIsNoSuchEntity() {
        given()
            .formParam("Action", "UpdateLoginProfile")
            .formParam("UserName", "no-such-user")
            .formParam("PasswordResetRequired", "true")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(8)
    void updateFlagOnlyLeavesThePasswordInPlace() {
        given()
            .formParam("Action", "UpdateLoginProfile")
            .formParam("UserName", USER_NAME)
            .formParam("PasswordResetRequired", "true")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetLoginProfile")
            .formParam("UserName", USER_NAME)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetLoginProfileResponse.GetLoginProfileResult.LoginProfile.PasswordResetRequired",
                    equalTo("true"));
    }

    @Test
    @Order(9)
    void deleteUserWhileProfileExistsIsDeleteConflict() {
        given()
            .formParam("Action", "DeleteUser")
            .formParam("UserName", USER_NAME)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"));
    }

    @Test
    @Order(10)
    void deleteRemovesTheProfile() {
        given()
            .formParam("Action", "DeleteLoginProfile")
            .formParam("UserName", USER_NAME)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void getAfterDeleteIsNoSuchEntity() {
        given()
            .formParam("Action", "GetLoginProfile")
            .formParam("UserName", USER_NAME)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(12)
    void deleteAgainIsNoSuchEntity() {
        given()
            .formParam("Action", "DeleteLoginProfile")
            .formParam("UserName", USER_NAME)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(13)
    void deleteTheUser() {
        given()
            .formParam("Action", "DeleteUser")
            .formParam("UserName", USER_NAME)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
