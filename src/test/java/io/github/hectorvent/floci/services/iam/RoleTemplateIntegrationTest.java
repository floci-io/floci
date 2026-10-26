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

    /**
     * Each entry holds a {@code ReplacementValueEntry}, which declares {@code Values} required.
     * A Query list arrives as {@code ...value.Values.member.M}, so an entry sending none of those
     * is the required member reading as null, reported against the key the entry was sent under.
     */
    @Test
    void anEntryCarryingNoValuesIsTheRequiredMemberRatherThanThe404() {
        iam("AcquireRole")
            .formParam("TemplateArn", TEMPLATE_ARN)
            .formParam("ReplacementValues.entry.1.key", "Department")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: Value null "
                    + "at 'replacementValues.Department.values' failed to satisfy constraint: "
                    + "Member must not be null"))
            .body(not(containsString("NoSuchEntity")));
    }

    /**
     * {@code replacementValueListType} is 1 to 20, so a 21st value in one entry is a length
     * violation ahead of the lookup, while 20 of them reach the 404 the ARN earns.
     */
    @Test
    void moreValuesInOneEntryThanTheDeclaredMaximumIsAValidationError() {
        acquireWithValues(21).when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: Value at "
                    + "'replacementValues.Department.values' failed to satisfy constraint: Member "
                    + "must have length less than or equal to 20"));

        acquireWithValues(20).when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    private static RequestSpecification acquireWithValues(int values) {
        RequestSpecification request = iam("AcquireRole")
                .formParam("TemplateArn", TEMPLATE_ARN)
                .formParam("ReplacementValues.entry.1.key", "Department");
        for (int value = 1; value <= values; value++) {
            request.formParam("ReplacementValues.entry.1.value.Values.member." + value, "v" + value);
        }
        return request;
    }

    /**
     * The list's members publish no constraint of their own, so a value sent empty is still a
     * value: the entry carries one, the required member is satisfied and the request reaches the
     * 404. That is the same reading {@code GetMFADevice} gives a blank {@code UserName}, where what
     * the request carries is the member being present rather than what it holds.
     */
    @Test
    void anEntryWhoseOnlyValueIsEmptyStillCarriesAValue() {
        iam("AcquireRole")
            .formParam("TemplateArn", TEMPLATE_ARN)
            .formParam("ReplacementValues.entry.1.key", "Department")
            .formParam("ReplacementValues.entry.1.value.Values.member.1", "")
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"))
            .body(not(containsString("ValidationError")));
    }

    /**
     * A Query index is read as the request spells it, the parameters that carry an entry being
     * named after that spelling rather than after the number the spelling reads as. An entry
     * numbered {@code 01} holds its key and its values under {@code entry.01}, so that is where
     * they are read from and the entry is whole: reading it as entry 1 would look for parameters
     * nothing sent and answer the required member against a key it could not find either.
     *
     * <p>The 404 alone would not hold that, an entry nothing read at all reaching it too, so the
     * same entry left empty is sent as well: the violation naming {@code Department} is what says
     * the key was read under the spelling it arrived in rather than under {@code 1}.
     */
    @Test
    void anEntryNumberedWithALeadingZeroIsReadUnderTheSpellingItWasSent() {
        iam("AcquireRole")
            .formParam("TemplateArn", TEMPLATE_ARN)
            .formParam("ReplacementValues.entry.01.key", "Department")
            .formParam("ReplacementValues.entry.01.value.Values.member.1", "Engineering")
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"))
            .body(not(containsString("ValidationError")));

        iam("AcquireRole")
            .formParam("TemplateArn", TEMPLATE_ARN)
            .formParam("ReplacementValues.entry.01.key", "Department")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: Value null "
                    + "at 'replacementValues.Department.values' failed to satisfy constraint: "
                    + "Member must not be null"));
    }

    /**
     * The validation layer answers with every member it found broken, so a request that sends 31
     * entries and leaves two of them empty is told all three things at once: the map's own length
     * first, the shape being walked from the outside in, then the entries in the order their
     * numbers read rather than the order the parameters happen to be held in.
     */
    @Test
    void theMapsLengthAndEveryEmptyEntryAreReportedTogether() {
        RequestSpecification request = iam("AcquireRole").formParam("TemplateArn", TEMPLATE_ARN);
        for (int entry = 1; entry <= 31; entry++) {
            request.formParam("ReplacementValues.entry." + entry + ".key", "Parameter" + entry);
            if (entry != 2 && entry != 10) {
                request.formParam("ReplacementValues.entry." + entry + ".value.Values.member.1", "v");
            }
        }

        request.when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo("3 validation errors detected: Value at "
                    + "'replacementValues' failed to satisfy constraint: Member must have length "
                    + "less than or equal to 30; Value null at 'replacementValues.Parameter2."
                    + "values' failed to satisfy constraint: Member must not be null; Value null "
                    + "at 'replacementValues.Parameter10.values' failed to satisfy constraint: "
                    + "Member must not be null"));
    }
}
