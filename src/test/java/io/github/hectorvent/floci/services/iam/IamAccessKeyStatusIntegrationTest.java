package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code UpdateAccessKey} over the wire, for the three things the reference publishes about it that
 * the handler did not follow.
 *
 * <p>The status is a {@code statusType}, whose Valid Values line reads "Active | Inactive |
 * Expired", so {@code Expired} is a value the operation takes rather than one to refuse. A value
 * outside that set breaks a constraint the model declares, which the Common Errors page answers as
 * {@code ValidationError}, and it is read before the key is looked up, so it is answered even when
 * the key named does not exist. And "if the UserName is not specified, the user name is determined
 * implicitly based on the AWS access key ID used to sign the request", the sentence this operation
 * shares with DeleteAccessKey and ListAccessKeys.
 *
 * <p>The four operations share {@code IamService.requireStatusFromEnum}; the latest AWS captures
 * distinguish an absent status from a present value outside the enum.
 */
@QuarkusTest
class IamAccessKeyStatusIntegrationTest {

    private static final String ACCOUNT = "000000000912";
    private static final String REGION = "us-east-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void expiredIsAStatusTheOperationTakes() {
        String userName = user("expired");
        String accessKeyId = accessKeyFor(userName);

        update(ACCOUNT, Map.of("UserName", userName, "AccessKeyId", accessKeyId,
                "Status", "Expired")).statusCode(200);

        admin("ListAccessKeys", Map.of("UserName", userName)).statusCode(200)
                .body("ListAccessKeysResponse.ListAccessKeysResult.AccessKeyMetadata.member.Status",
                        equalTo("Expired"));
    }

    @Test
    void aStatusOutsideTheEnumIsAValidationError() {
        String userName = user("bad-status");
        String accessKeyId = accessKeyFor(userName);

        update(ACCOUNT, Map.of("UserName", userName, "AccessKeyId", accessKeyId,
                "Status", "Disabled")).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("1 validation error detected: Value at 'status' failed to satisfy "
                                + "constraint: Member must satisfy enum value set: "
                                + "[Expired, Active, Inactive]"));

        assertStatusStillActive(userName);
    }

    @Test
    void anAbsentStatusNamesTheMissingMember() {
        String userName = user("absent-status");
        String accessKeyId = accessKeyFor(userName);

        update(ACCOUNT, Map.of("UserName", userName, "AccessKeyId", accessKeyId)).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("1 validation error detected: Value at 'status' failed to satisfy "
                                + "constraint: Member must not be null"));

        assertStatusStillActive(userName);
    }

    /** A refused status leaves the key as it was: the value is read before anything is written. */
    private void assertStatusStillActive(String userName) {
        admin("ListAccessKeys", Map.of("UserName", userName)).statusCode(200)
                .body("ListAccessKeysResponse.ListAccessKeysResult.AccessKeyMetadata.member.Status",
                        equalTo("Active"));
    }

    @Test
    void anAbsentAccessKeyIdNamesItself() {
        update(ACCOUNT, Map.of("UserName", user("absent-id"), "Status", "Inactive")).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("Value null at 'accessKeyId'"));
    }

    @Test
    void theStatusIsReadBeforeTheKeyIsLookedUp() {
        // Both are wrong, and the shape is what AWS answers for: the key would otherwise decide,
        // which is NoSuchEntity 404 for a key that is not there.
        update(ACCOUNT, Map.of("UserName", user("order"), "AccessKeyId", "AKIANOSUCHKEY00000",
                "Status", "Disabled")).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        update(ACCOUNT, Map.of("UserName", user("order-valid"), "AccessKeyId", "AKIANOSUCHKEY00000",
                "Status", "Inactive")).statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void theSignersOwnKeyNeedsNoUserName() {
        String userName = user("implicit");
        String accessKeyId = accessKeyFor(userName);

        update(accessKeyId, Map.of("AccessKeyId", accessKeyId, "Status", "Inactive"))
                .statusCode(200);

        admin("ListAccessKeys", Map.of("UserName", userName)).statusCode(200)
                .body("ListAccessKeysResponse.ListAccessKeysResult.AccessKeyMetadata.member.Status",
                        equalTo("Inactive"));
    }

    @Test
    void anotherUsersKeyIsStillNoSuchEntity() {
        String owner = user("owner");
        String ownerKey = accessKeyFor(owner);
        String stranger = user("stranger");

        update(ACCOUNT, Map.of("UserName", stranger, "AccessKeyId", ownerKey,
                "Status", "Inactive")).statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    private String user(String label) {
        String userName = "access-key-status-" + label;
        admin("CreateUser", Map.of("UserName", userName)).statusCode(200);
        cleanup.register(() -> admin("DeleteUser", Map.of("UserName", userName)).statusCode(200));
        return userName;
    }

    private String accessKeyFor(String userName) {
        String accessKeyId = admin("CreateAccessKey", Map.of("UserName", userName)).statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey"
                        + ".AccessKeyId");
        cleanup.register(() -> admin("DeleteAccessKey",
                Map.of("UserName", userName, "AccessKeyId", accessKeyId)).statusCode(200));
        return accessKeyId;
    }

    private static ValidatableResponse update(String accessKeyId, Map<String, String> params) {
        return iam(accessKeyId, "UpdateAccessKey", params);
    }

    private static ValidatableResponse admin(String action, Map<String, String> params) {
        return iam(ACCOUNT, action, params);
    }

    private static ValidatableResponse iam(String accessKeyId, String action,
                                           Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20261018/" + REGION + "/iam/aws4_request, SignedHeaders=host, "
                        + "Signature=abc")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }
}
