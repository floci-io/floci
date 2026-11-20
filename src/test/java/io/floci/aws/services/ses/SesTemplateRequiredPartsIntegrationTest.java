package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * What a template must carry to be created or updated, as probed on us-west-2: a subject, then at
 * least one part with a character that is not whitespace, both asked for before the name is looked up.
 */
@QuarkusTest
class SesTemplateRequiredPartsIntegrationTest {

    private static final String V1_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String V2_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String NO_SUBJECT = "The subject must be specified.";
    private static final String NO_PART = "Template must specify at least one of the parts.";

    static Stream<Arguments> refusedV2Contents() {
        return Stream.of(
                Arguments.of("{}", NO_SUBJECT),
                Arguments.of("{\"Text\": \"t\", \"Html\": \"<p>h</p>\"}", NO_SUBJECT),
                Arguments.of("{\"Subject\": \" \", \"Text\": \"t\"}", NO_SUBJECT),
                Arguments.of("{\"Subject\": \"S\"}", NO_PART),
                Arguments.of("{\"Subject\": \"S\", \"Text\": \" \", \"Html\": \"\"}", NO_PART));
    }

    @ParameterizedTest
    @MethodSource("refusedV2Contents")
    void v2Create_refusesTheContent(String content, String message) {
        given()
            .contentType("application/json")
            .header("Authorization", V2_AUTH)
            .body("{\"TemplateName\": \"required-parts-refused\", \"TemplateContent\": " + content + "}")
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    @ParameterizedTest
    @MethodSource("refusedV2Contents")
    void v2Update_refusesTheContentOfAnUnknownTemplate(String content, String message) {
        given()
            .contentType("application/json")
            .header("Authorization", V2_AUTH)
            .body("{\"TemplateContent\": " + content + "}")
        .when()
            .put("/v2/email/templates/required-parts-ghost")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    @Test
    void v2Create_readsAnEmptyPartBackAsNoPart() {
        given()
            .contentType("application/json")
            .header("Authorization", V2_AUTH)
            .body("""
                {"TemplateName": "required-parts-html-only",
                 "TemplateContent": {"Subject": "S", "Text": "", "Html": "<p>h</p>"}}
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", V2_AUTH)
        .when()
            .get("/v2/email/templates/required-parts-html-only")
        .then()
            .statusCode(200)
            .body("TemplateContent.Html", equalTo("<p>h</p>"))
            .body("TemplateContent.Text", nullValue());

        given()
            .header("Authorization", V2_AUTH)
        .when()
            .delete("/v2/email/templates/required-parts-html-only")
        .then()
            .statusCode(200);
    }

    @Test
    void v1Create_requiresASubject() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "required-parts-v1")
            .formParam("Template.TextPart", "t")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body(containsString("<Message>" + NO_SUBJECT + "</Message>"));
    }

    @Test
    void v1Create_requiresAPart() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "required-parts-v1")
            .formParam("Template.SubjectPart", "S")
            .formParam("Template.TextPart", " ")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body(containsString("<Message>" + NO_PART + "</Message>"));
    }

    @Test
    void v1Update_requiresAPartBeforeTheTemplateIsLookedUp() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "UpdateTemplate")
            .formParam("Template.TemplateName", "required-parts-ghost")
            .formParam("Template.SubjectPart", "S")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body(containsString("<Message>" + NO_PART + "</Message>"));
    }
}
