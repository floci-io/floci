package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The two role template operations over the wire. Both answer {@code NoSuchEntity} for every ARN,
 * and that is the faithful answer rather than a stub: role templates are AWS's own, the user guide
 * having role manager use "AWS-managed role templates", and the model publishes no operation that
 * creates, lists, updates or deletes one, so no account holds a template to read or to acquire a
 * role from.
 *
 * <p>What these hold is that the 404 is reached the way AWS reaches it. The declared constraints
 * are read first, so a malformed request is the 400 the validation ahead of the operation answers,
 * and the refusal names the template rather than something else. Dispatching them at all is what
 * the tests are really for: before this, both answered {@code UnsupportedOperation} 400, which no
 * SDK models for either operation, where the 404 is modelled by both.
 *
 * <p>If AWS ever publishes the template bodies the guide's ARN directory names, these answers are
 * what would change: the ARNs in that directory would have a version to return, and only those.
 */
@QuarkusTest
class RoleTemplateIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261024/us-east-1/iam/aws4_request";

    /** One of the template ARNs the user guide's directory publishes, as a client would send it. */
    private static final String TEMPLATE_ARN =
            "arn:aws:iam::aws:role-template/ec2.amazonaws.com/AmazonEC2RoleforSSM:1";

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    @Test
    void readingATemplateVersionIsReportedMissingRatherThanUnsupported() {
        iam("GetRoleTemplateVersion")
            .formParam("TemplateArn", TEMPLATE_ARN)
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"))
            .body("ErrorResponse.Error.Message",
                    equalTo("The role template with ARN " + TEMPLATE_ARN + " cannot be found."))
            .body(not(containsString("UnsupportedOperation")));
    }

    @Test
    void acquiringARoleFromATemplateIsReportedMissingRatherThanUnsupported() {
        // No state assertion to make here: the role's name comes from the template, so a request
        // that is refused for naming no template names nothing to look up afterwards either.
        iam("AcquireRole")
            .formParam("TemplateArn", TEMPLATE_ARN)
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"))
            .body("ErrorResponse.Error.Message",
                    equalTo("The role template with ARN " + TEMPLATE_ARN + " cannot be found."))
            .body(not(containsString("UnsupportedOperation")));
    }

    /**
     * {@code TemplateArn} is {@code arnType}, min 20, on both operations, and a value under it is
     * refused on its shape before anything is looked for. The member is required, so an absent one
     * is the null violation rather than the 404.
     */
    @Test
    void aTemplateArnOutsideItsDeclaredLengthIsAValidationError() {
        for (String action : new String[] {"GetRoleTemplateVersion", "AcquireRole"}) {
            iam(action).formParam("TemplateArn", "arn:aws:iam::aws")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: Value "
                        + "at 'templateArn' failed to satisfy constraint: Member must have length "
                        + "greater than or equal to 20"))
                .body(not(containsString("NoSuchEntity")));

            iam(action)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: Value "
                        + "null at 'templateArn' failed to satisfy constraint: Member must not be "
                        + "null"))
                .body(not(containsString("NoSuchEntity")));
        }
    }

    /**
     * Each operation names its minor version differently, {@code MinorVersion} on the read and
     * {@code TemplateMinorVersion} on the acquire, and both are declared integers with no bounds,
     * so a value that is not a number is {@code InvalidInput} as it is for {@code MaxItems}.
     */
    @Test
    void aMinorVersionThatIsNotANumberIsRefusedBeforeTheTemplateIsLookedFor() {
        iam("GetRoleTemplateVersion")
            .formParam("TemplateArn", TEMPLATE_ARN)
            .formParam("MinorVersion", "latest")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
            .body("ErrorResponse.Error.Message",
                    equalTo("The value latest at 'minorVersion' is not a number."));

        iam("AcquireRole")
            .formParam("TemplateArn", TEMPLATE_ARN)
            .formParam("TemplateMinorVersion", "latest")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
            .body("ErrorResponse.Error.Message",
                    equalTo("The value latest at 'templateMinorVersion' is not a number."));

        // A number is carried through to the 404, so the check is on the value and not on the
        // member being present.
        iam("GetRoleTemplateVersion")
            .formParam("TemplateArn", TEMPLATE_ARN)
            .formParam("MinorVersion", "3")
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /**
     * {@code ReplacementValues} is declared with at most 30 entries. The Query form spells a map
     * as {@code ReplacementValues.entry.N.key}, and 31 of them is a length violation ahead of the
     * lookup rather than a 404 that ignored them.
     */
    @Test
    void moreReplacementValuesThanTheDeclaredMaximumIsAValidationError() {
        RequestSpecification request = iam("AcquireRole").formParam("TemplateArn", TEMPLATE_ARN);
        for (int entry = 1; entry <= 31; entry++) {
            request.formParam("ReplacementValues.entry." + entry + ".key", "Parameter" + entry)
                    .formParam("ReplacementValues.entry." + entry + ".value.Values.member.1", "v");
        }

        request.when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: Value at "
                    + "'replacementValues' failed to satisfy constraint: Member must have length "
                    + "less than or equal to 30"));
    }

    @Test
    void thirtyReplacementValuesAreCarriedThroughToTheMissingTemplate() {
        RequestSpecification request = iam("AcquireRole").formParam("TemplateArn", TEMPLATE_ARN);
        for (int entry = 1; entry <= 30; entry++) {
            request.formParam("ReplacementValues.entry." + entry + ".key", "Parameter" + entry)
                    .formParam("ReplacementValues.entry." + entry + ".value.Values.member.1", "v");
        }

        request.when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }
}
