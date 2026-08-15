package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Control-plane coverage for the user Engine attribute; unlike the Docker-backed
 * lifecycle tests these never touch a container.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheUserEngineIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String VALKEY_USER = "engine-it-valkey-user";
    private static final String DEFAULT_USER = "engine-it-default-user";

    @Test
    @Order(1)
    void createUserWithValkeyEngineEchoesValkey() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserId", VALKEY_USER)
            .formParam("UserName", "engine-it-valkey")
            .formParam("Engine", "valkey")
            .formParam("AccessString", "on ~* +@all")
            .formParam("AuthenticationMode.Type", "no-password-required")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateUserResponse.CreateUserResult.Engine", equalTo("valkey"))
            // AWS documents no valkey-specific MinimumEngineVersion, so it stays at the published 6.0.
            .body("CreateUserResponse.CreateUserResult.MinimumEngineVersion", equalTo("6.0"));
    }

    @Test
    @Order(2)
    void createUserWithoutEngineDefaultsToRedis() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserId", DEFAULT_USER)
            .formParam("UserName", "engine-it-default")
            .formParam("AccessString", "on ~* +@all")
            .formParam("AuthenticationMode.Type", "no-password-required")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateUserResponse.CreateUserResult.Engine", equalTo("redis"))
            .body("CreateUserResponse.CreateUserResult.MinimumEngineVersion", equalTo("6.0"));
    }

    @Test
    @Order(3)
    void describeUsersReportsTheStoredEngine() {
        given()
            .formParam("Action", "DescribeUsers")
            .formParam("UserId", VALKEY_USER)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeUsersResponse.DescribeUsersResult.Users.member.Engine", equalTo("valkey"));
    }

    /** Floci's own tolerance, mirroring the handler's memcached check — AWS documents no case handling for Engine. */
    @Test
    @Order(4)
    void engineValueIsCaseInsensitiveAndStoredLowercase() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserId", "engine-it-case-user")
            .formParam("UserName", "engine-it-case")
            .formParam("Engine", "Valkey")
            .formParam("AccessString", "on ~* +@all")
            .formParam("AuthenticationMode.Type", "no-password-required")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateUserResponse.CreateUserResult.Engine", equalTo("valkey"));
    }

    @Test
    @Order(5)
    void createUserRejectsAnUnknownEngine() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserId", "engine-it-bad-user")
            .formParam("UserName", "engine-it-bad")
            .formParam("Engine", "memcached")
            .formParam("AccessString", "on ~* +@all")
            .formParam("AuthenticationMode.Type", "no-password-required")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
    }

    @Test
    @Order(6)
    void modifyUserChangesTheEngine() {
        given()
            .formParam("Action", "ModifyUser")
            .formParam("UserId", DEFAULT_USER)
            .formParam("Engine", "valkey")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyUserResponse.ModifyUserResult.Engine", equalTo("valkey"));
    }

    @Test
    @Order(7)
    void modifyUserWithoutEngineLeavesItUnchanged() {
        given()
            .formParam("Action", "ModifyUser")
            .formParam("UserId", VALKEY_USER)
            .formParam("AuthenticationMode.Passwords.member.1", "some-password-1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyUserResponse.ModifyUserResult.Engine", equalTo("valkey"));
    }

    /** A sent-but-empty Engine is treated like an absent one on both actions. */
    @Test
    @Order(8)
    void blankEngineBehavesLikeAnAbsentOne() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserId", "engine-it-blank-user")
            .formParam("UserName", "engine-it-blank")
            .formParam("Engine", "")
            .formParam("AccessString", "on ~* +@all")
            .formParam("AuthenticationMode.Type", "no-password-required")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateUserResponse.CreateUserResult.Engine", equalTo("redis"));
        given()
            .formParam("Action", "ModifyUser")
            .formParam("UserId", VALKEY_USER)
            .formParam("Engine", "")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyUserResponse.ModifyUserResult.Engine", equalTo("valkey"));
    }

    @Test
    @Order(9)
    void createUserWithExplicitRedisEngineEchoesRedis() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserId", "engine-it-redis-user")
            .formParam("UserName", "engine-it-redis")
            .formParam("Engine", "redis")
            .formParam("AccessString", "on ~* +@all")
            .formParam("AuthenticationMode.Type", "no-password-required")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateUserResponse.CreateUserResult.Engine", equalTo("redis"));
    }

    /** DescribeUsers documents an Engine filter, which means something now that users differ by engine. */
    @Test
    @Order(10)
    void describeUsersFiltersByEngine() {
        given()
            .formParam("Action", "DescribeUsers")
            .formParam("Engine", "redis")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("engine-it-redis-user"))
            .body(not(containsString("engine-it-valkey-user")));
        given()
            .formParam("Action", "DescribeUsers")
            .formParam("Engine", "REDIS")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("engine-it-redis-user"))
            .body(not(containsString("engine-it-valkey-user")));
        // UserId wins when both filters are sent; the engine filter is ignored.
        given()
            .formParam("Action", "DescribeUsers")
            .formParam("UserId", VALKEY_USER)
            .formParam("Engine", "redis")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(VALKEY_USER));
    }

    /**
     * A ModifyUser rejected for a bad Engine must leave the user untouched — the storage
     * backends hand back the live stored object, so a password change applied before
     * validation would survive the 400.
     */
    @Test
    @Order(11)
    void rejectedModifyUserLeavesThePasswordsUnchanged() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserId", "engine-it-atomic-user")
            .formParam("UserName", "engine-it-atomic")
            .formParam("AccessString", "on ~* +@all")
            .formParam("AuthenticationMode.Type", "password")
            .formParam("AuthenticationMode.Passwords.member.1", "atomic-password-1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateUserResponse.CreateUserResult.Authentication.PasswordCount", equalTo("1"));
        given()
            .formParam("Action", "ModifyUser")
            .formParam("UserId", "engine-it-atomic-user")
            .formParam("AuthenticationMode.Passwords.member.1", "atomic-password-1")
            .formParam("AuthenticationMode.Passwords.member.2", "atomic-password-2")
            .formParam("Engine", "memcached")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
        given()
            .formParam("Action", "DescribeUsers")
            .formParam("UserId", "engine-it-atomic-user")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeUsersResponse.DescribeUsersResult.Users.member.Authentication.PasswordCount", equalTo("1"));
        // The same call with a valid engine applies both changes.
        given()
            .formParam("Action", "ModifyUser")
            .formParam("UserId", "engine-it-atomic-user")
            .formParam("AuthenticationMode.Passwords.member.1", "atomic-password-1")
            .formParam("AuthenticationMode.Passwords.member.2", "atomic-password-2")
            .formParam("Engine", "valkey")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyUserResponse.ModifyUserResult.Engine", equalTo("valkey"))
            .body("ModifyUserResponse.ModifyUserResult.Authentication.PasswordCount", equalTo("2"));
    }

    @Test
    @Order(12)
    void deleteUserEchoesTheStoredEngine() {
        given()
            .formParam("Action", "DeleteUser")
            .formParam("UserId", VALKEY_USER)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteUserResponse.DeleteUserResult.Engine", equalTo("valkey"));
    }

    /**
     * The user store is shared across test classes, so this class removes what it created —
     * as an ordered test rather than an {@code @AfterAll}, which would run after Quarkus has
     * already reset the RestAssured port, and asserting each delete so a silent no-op turns red.
     */
    @Test
    @Order(13)
    void deleteRemainingUsersSoTheSharedStoreStaysClean() {
        for (String userId : new String[] {DEFAULT_USER, "engine-it-case-user",
                "engine-it-blank-user", "engine-it-redis-user", "engine-it-atomic-user"}) {
            given()
                .formParam("Action", "DeleteUser")
                .formParam("UserId", userId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }
}
