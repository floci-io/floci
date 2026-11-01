package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** IAM's four statusType updates share the validation behavior captured for issue #6042. */
@QuarkusTest
class IamCredentialStatusValidationIntegrationTest {

    private static final String MESSAGE = "ErrorResponse.Error.Message";
    private static final String PREFIX = "1 validation error detected: Value at 'status' failed "
            + "to satisfy constraint: ";

    @ParameterizedTest(name = "{0} without Status")
    @CsvSource({
            "UpdateAccessKey, AccessKeyId, AKIAIOSFODNN7EXAMPLE",
            "UpdateSigningCertificate, CertificateId, ABCDEFGHIJKLMNOPQRSTUVWX",
            "UpdateSSHPublicKey, SSHPublicKeyId, APKAEXAMPLEEXAMPLE12",
            "UpdateServiceSpecificCredential, ServiceSpecificCredentialId, ACCAEXAMPLEEXAMPLE12"
    })
    void omittedStatusIsReportedAsNull(String action, String idMember, String id) {
        update(action, idMember, id, null).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body(MESSAGE, equalTo(PREFIX + "Member must not be null"));
    }

    @ParameterizedTest(name = "{0} with Status=Enabled")
    @CsvSource({
            "UpdateAccessKey, AccessKeyId, AKIAIOSFODNN7EXAMPLE",
            "UpdateSigningCertificate, CertificateId, ABCDEFGHIJKLMNOPQRSTUVWX",
            "UpdateSSHPublicKey, SSHPublicKeyId, APKAEXAMPLEEXAMPLE12",
            "UpdateServiceSpecificCredential, ServiceSpecificCredentialId, ACCAEXAMPLEEXAMPLE12"
    })
    void invalidStatusListsAllowedValues(String action, String idMember, String id) {
        update(action, idMember, id, "Enabled").statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body(MESSAGE, equalTo(PREFIX
                        + "Member must satisfy enum value set: [Expired, Active, Inactive]"));
    }

    private static ValidatableResponse update(String action, String idMember, String id,
                                              String status) {
        RequestSpecification request = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000912/20261101/"
                        + "us-east-1/iam/aws4_request")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08")
                .formParam("UserName", "status-validation-missing-user")
                .formParam(idMember, id);
        if (status != null) {
            request.formParam("Status", status);
        }
        return request.when().post("/").then();
    }
}
