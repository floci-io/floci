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
 * {@code UpdateRoleDescription} over the wire.
 *
 * <p>The reference opens with "Use UpdateRole instead" and says the operation "performs the same
 * function as the Description parameter in the UpdateRole operation", so it is that path and
 * nothing else about the role moves. Its two members are both required, where UpdateRole's are
 * optional, and it answers the modified role, which UpdateRole does not answer at all. The errors
 * it models are {@code NoSuchEntity}, {@code UnmodifiableEntity} and {@code ServiceFailure}, and
 * the last of those is not a description's to answer: AWS documents the description of any role as
 * editable, a service-linked one included, which {@link IamServiceLinkedRoleIntegrationTest}
 * covers alongside the protection that does hold there.
 */
@QuarkusTest
class IamRoleDescriptionIntegrationTest {

    private static final String ACCOUNT = "000000000931";
    private static final String REGION = "us-east-1";
    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},"
                    + "\"Action\":\"sts:AssumeRole\"}]}";
    private static final String RESULT = "UpdateRoleDescriptionResponse.UpdateRoleDescriptionResult";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void theNewDescriptionIsAppliedAndTheRoleComesBack() {
        String roleName = role("applied", "the description it was created with");

        update(Map.of("RoleName", roleName, "Description", "the description it answers with"))
                .statusCode(200)
                .body(RESULT + ".Role.RoleName", equalTo(roleName))
                .body(RESULT + ".Role.Description", equalTo("the description it answers with"))
                .body(RESULT + ".Role.Arn", containsString(":role/" + roleName));

        admin("GetRole", Map.of("RoleName", roleName)).statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.Description",
                        equalTo("the description it answers with"));
    }

    @Test
    void anEmptyDescriptionIsAValueAndClearsIt() {
        // roleDescriptionType declares a maximum and no minimum, so an empty string satisfies the
        // shape: the description it replaces is the one the role was created with.
        String roleName = role("cleared", "about to be cleared");

        update(Map.of("RoleName", roleName, "Description", "")).statusCode(200)
                .body(RESULT + ".Role.Description", equalTo(""));

        admin("GetRole", Map.of("RoleName", roleName)).statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.Description", equalTo(""));
    }

    @Test
    void onlyTheDescriptionMoves() {
        String roleName = role("only", "before");
        admin("UpdateRole", Map.of("RoleName", roleName, "MaxSessionDuration", "7200"))
                .statusCode(200);

        update(Map.of("RoleName", roleName, "Description", "after")).statusCode(200)
                .body(RESULT + ".Role.MaxSessionDuration", equalTo("7200"));

        admin("GetRole", Map.of("RoleName", roleName)).statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.MaxSessionDuration", equalTo("7200"))
                .body("GetRoleResponse.GetRoleResult.Role.AssumeRolePolicyDocument",
                        containsString("lambda.amazonaws.com"));
    }

    @Test
    void theMaximumIsTakenAndOneCharacterMoreIsNot() {
        String roleName = role("length", "before");
        String atMaximum = "d".repeat(1000);

        update(Map.of("RoleName", roleName, "Description", atMaximum)).statusCode(200)
                .body(RESULT + ".Role.Description", equalTo(atMaximum));

        update(Map.of("RoleName", roleName, "Description", "d".repeat(1001))).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("1 validation error detected: Value at 'description' failed to "
                                + "satisfy constraint: Member must have length less than or equal "
                                + "to 1000"));

        admin("GetRole", Map.of("RoleName", roleName)).statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.Description", equalTo(atMaximum));
    }

    @Test
    void aCharacterOutsideTheSetIsRefusedSoTheAnswerStaysParsable() {
        // The set admits tab, newline and carriage return and no other control character, which is
        // what keeps the Role this action answers readable: XmlBuilder escapes the five entities
        // and passes everything else through, so a stored control character would make this
        // response, and GetRole's, unparsable.
        String roleName = role("control", "before");

        update(Map.of("RoleName", roleName, "Description", "a" + (char) 1 + "b")).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        containsString("Member must satisfy regular expression pattern"));

        update(Map.of("RoleName", roleName, "Description", "two\nlines\tand a tab")).statusCode(200);

        admin("GetRole", Map.of("RoleName", roleName)).statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.Description",
                        containsString("lines"));
    }

    @Test
    void theOtherOperationsThatTakeADescriptionAnswerTheSameWay() {
        // One validator, four call sites: the shape is shared, so a description CreateRole or
        // UpdateRole would have stored is refused there too, and the create stores nothing.
        admin("CreateRole", Map.of("RoleName", "role-description-sibling",
                "AssumeRolePolicyDocument", TRUST_POLICY, "Description", "d".repeat(1001)))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("at 'description'"));
        admin("GetRole", Map.of("RoleName", "role-description-sibling")).statusCode(404);

        String roleName = role("sibling", "before");
        admin("UpdateRole", Map.of("RoleName", roleName, "Description", "a" + (char) 1 + "b"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
        admin("GetRole", Map.of("RoleName", roleName)).statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.Description", equalTo("before"));
    }

    @Test
    void anAbsentDescriptionIsAValidationError() {
        String roleName = role("absent-description", "kept");

        update(Map.of("RoleName", roleName)).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("1 validation error detected: Value at 'description' failed "
                                + "to satisfy constraint: Member must not be null"));

        admin("GetRole", Map.of("RoleName", roleName)).statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.Description", equalTo("kept"));
    }

    @Test
    void anAbsentRoleNameIsAValidationError() {
        update(Map.of("Description", "nobody to apply it to")).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("at 'roleName'"));
    }

    @Test
    void theRoleNameIsReadBeforeTheRoleIsLookedUp() {
        // A colon is outside roleNameType, and the shape is what AWS answers for: the lookup would
        // otherwise decide, which is NoSuchEntity 404 for a role that cannot exist under that name.
        update(Map.of("RoleName", "role:with:colons", "Description", "whatever")).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("at 'roleName'"));
    }

    @Test
    void anUnknownRoleIsNoSuchEntity() {
        update(Map.of("RoleName", "role-description-absent", "Description", "nothing to apply it to"))
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    private String role(String label, String description) {
        String roleName = "role-description-" + label;
        admin("CreateRole", Map.of("RoleName", roleName, "AssumeRolePolicyDocument", TRUST_POLICY,
                "Description", description)).statusCode(200);
        cleanup.register(() -> admin("DeleteRole", Map.of("RoleName", roleName)).statusCode(200));
        return roleName;
    }

    private static ValidatableResponse update(Map<String, String> params) {
        return iam("UpdateRoleDescription", params);
    }

    private static ValidatableResponse admin(String action, Map<String, String> params) {
        return iam(action, params);
    }

    private static ValidatableResponse iam(String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261018/" + REGION + "/iam/aws4_request, SignedHeaders=host, "
                        + "Signature=abc")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }
}
