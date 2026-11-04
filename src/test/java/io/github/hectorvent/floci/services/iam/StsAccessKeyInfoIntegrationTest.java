package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * {@code GetAccessKeyInfo} over the wire.
 *
 * <p>The operation answers one member, the account an access key id belongs to, and models no
 * errors at all. Its one explicit behaviour is what it does not do: "this operation does not
 * indicate the state of the access key. The key might be active, inactive, or deleted", so an
 * inactive key still answers its account. A key Floci does not hold is the answer the reference
 * leaves open, and a capture from a real account answers it {@code ValidationError} "Access key ID
 * is not valid." over both id formats, which {@code docs/services/sts.md} carries.
 */
@QuarkusTest
class StsAccessKeyInfoIntegrationTest {

    private static final String ACCOUNT = "000000000941";
    private static final String REGION = "us-east-1";
    private static final String RESULT = "GetAccessKeyInfoResponse.GetAccessKeyInfoResult";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aLongTermKeyAnswersTheAccountThatHoldsIt() {
        String userName = user("holder");
        String accessKeyId = accessKeyFor(userName);

        sts("GetAccessKeyInfo", Map.of("AccessKeyId", accessKeyId)).statusCode(200)
                .body(RESULT + ".Account", equalTo(ACCOUNT));
    }

    @Test
    void anInactiveKeyStillAnswersItsAccount() {
        // "This operation does not indicate the state of the access key. The key might be active,
        // inactive, or deleted", so the status is not a reason to refuse.
        String userName = user("inactive");
        String accessKeyId = accessKeyFor(userName);

        iam("UpdateAccessKey", Map.of("UserName", userName, "AccessKeyId", accessKeyId,
                "Status", "Inactive")).statusCode(200);

        sts("GetAccessKeyInfo", Map.of("AccessKeyId", accessKeyId)).statusCode(200)
                .body(RESULT + ".Account", equalTo(ACCOUNT));
    }

    @Test
    void aSessionKeyAnswersTheAccountItsSessionBelongsTo() {
        // "Access key IDs beginning with ASIA are temporary credentials that are created using
        // STS operations", and they resolve through the session rather than the key store.
        String sessionKeyId = sts("GetSessionToken", Map.of()).statusCode(200)
                .body("GetSessionTokenResponse.GetSessionTokenResult.Credentials.AccessKeyId",
                        startsWith("ASIA"))
                .extract().path("GetSessionTokenResponse.GetSessionTokenResult.Credentials.AccessKeyId");

        sts("GetAccessKeyInfo", Map.of("AccessKeyId", sessionKeyId)).statusCode(200)
                .body(RESULT + ".Account", equalTo(ACCOUNT));
    }

    @Test
    void anIdOutsideTheShapeIsReadBeforeAnyLookup() {
        sts("GetAccessKeyInfo", Map.of("AccessKeyId", "AKIASHORT")).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("1 validation error detected: Value 'AKIASHORT' at 'accessKeyId' "
                                + "failed to satisfy constraint: Member must have length greater "
                                + "than or equal to 16"));

        sts("GetAccessKeyInfo", Map.of("AccessKeyId", "AKIA-NOT-A-VALID-KEY-ID")).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        containsString("Member must satisfy regular expression pattern"));

        // A member sent empty is a value that fails the minimum, where an absent one is the
        // refusal that names it null, which is the pair IAM already answers for RoleName here.
        sts("GetAccessKeyInfo", Map.of("AccessKeyId", "")).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("1 validation error detected: Value '' at 'accessKeyId' failed to "
                                + "satisfy constraint: Member must have length greater than or "
                                + "equal to 16"));

        sts("GetAccessKeyInfo", Map.of()).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        containsString("Value null at 'accessKeyId'"));
    }

    /**
     * Captured from a real account against a long-term and a temporary id alike: an id the account
     * never issued is a {@code ValidationError} whose sentence names neither the id nor a
     * violation count, so AWS refuses it rather than reading the account out of it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"AKIAABSENTKEY0000000", "ASIAABSENTKEY0000000"})
    void aWellFormedKeyNobodyHoldsIsRefused(String accessKeyId) {
        sts("GetAccessKeyInfo", Map.of("AccessKeyId", accessKeyId)).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Type", equalTo("Sender"))
                .body("ErrorResponse.Error.Message", equalTo("Access key ID is not valid."));
    }

    private String user(String label) {
        String userName = "access-key-info-" + label;
        iam("CreateUser", Map.of("UserName", userName)).statusCode(200);
        cleanup.register(() -> iam("DeleteUser", Map.of("UserName", userName)).statusCode(200));
        return userName;
    }

    private String accessKeyFor(String userName) {
        String accessKeyId = iam("CreateAccessKey", Map.of("UserName", userName)).statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        cleanup.register(() -> iam("DeleteAccessKey",
                Map.of("UserName", userName, "AccessKeyId", accessKeyId)).statusCode(200));
        return accessKeyId;
    }

    private static ValidatableResponse iam(String action, Map<String, String> params) {
        return call(action, "iam", "2010-05-08", params);
    }

    private static ValidatableResponse sts(String action, Map<String, String> params) {
        return call(action, "sts", "2011-06-15", params);
    }

    private static ValidatableResponse call(String action, String service, String version,
                                            Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261019/" + REGION + "/" + service + "/aws4_request, "
                        + "SignedHeaders=host, Signature=abc")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", version);
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }
}
