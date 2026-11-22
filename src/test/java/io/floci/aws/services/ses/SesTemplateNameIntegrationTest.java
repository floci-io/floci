package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.floci.aws.services.ses.SesV2Requests.AUTH;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * The template name rules, as probed on us-west-2: blank, then longer than 64 characters, then a
 * character outside ASCII letters, digits, '_' and '-'. Create, update, get, delete and test-render
 * all answer them, on both APIs, before the name is looked up; the other lookups do not apply them.
 */
@QuarkusTest
class SesTemplateNameIntegrationTest {

    private static final String V1_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String BLANK = "The template name must be specified.";
    private static final String TOO_LONG = "Template name cannot exceed 64 characters.";

    private static String notAllowed(String name) {
        return "Template name <" + name + ">: only alphanumeric ASCII characters, '_', and '-' are allowed.";
    }

    static Stream<Arguments> refusedNames() {
        return Stream.of(
                Arguments.of(" ", BLANK),
                Arguments.of("a".repeat(65), TOO_LONG),
                Arguments.of("name-probe ", notAllowed("name-probe ")),
                Arguments.of(" name-probe", notAllowed(" name-probe")),
                Arguments.of("name probe", notAllowed("name probe")),
                Arguments.of("name.probe", notAllowed("name.probe")),
                Arguments.of("name:probe", notAllowed("name:probe")),
                Arguments.of("name-probé", notAllowed("name-probé")));
    }

    @ParameterizedTest
    @MethodSource("refusedNames")
    void v1_everyOperationRefusesTheName(String name, String message) {
        for (String action : new String[] {"CreateTemplate", "UpdateTemplate"}) {
            given()
                .contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .header("Authorization", V1_AUTH)
                .formParam("Action", action)
                .formParam("Template.TemplateName", name)
                .formParam("Template.SubjectPart", "S")
                .formParam("Template.TextPart", "t")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
                .body("ErrorResponse.Error.Message", equalTo(message));
        }
        for (String action : new String[] {"GetTemplate", "DeleteTemplate", "TestRenderTemplate"}) {
            given()
                .contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .header("Authorization", V1_AUTH)
                .formParam("Action", action)
                .formParam("TemplateName", name)
                .formParam("TemplateData", "{}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
                .body("ErrorResponse.Error.Message", equalTo(message));
        }
    }

    @ParameterizedTest
    @MethodSource("refusedNames")
    void v2_everyOperationRefusesTheName(String name, String message) {
        given()
            .contentType("application/json; charset=UTF-8")
            .header("Authorization", AUTH)
            .body("{\"TemplateName\": \"" + name + "\", \"TemplateContent\": {\"Subject\": \"S\", \"Text\": \"t\"}}")
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("{\"TemplateContent\": {\"Subject\": \"S\", \"Text\": \"t\"}}")
        .when()
            .put("/v2/email/templates/{name}", name)
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/templates/{name}", name)
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v2/email/templates/{name}", name)
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("{\"TemplateData\": \"{}\"}")
        .when()
            .post("/v2/email/templates/{name}/render", name)
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    // probed: the name is checked before the subject
    @Test
    void theNameIsCheckedBeforeTheContent() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "name probe")
            .formParam("Template.TextPart", "t")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo(notAllowed("name probe")));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("{\"TemplateName\": \"name probe\", \"TemplateContent\": {\"Text\": \"t\"}}")
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("message", equalTo(notAllowed("name probe")));
    }

    // probed: v1 writes a control character of the name as 0x0009, v2 sends it as it came
    @Test
    void v1SpellsOutAControlCharacterOfTheName() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "GetTemplate")
            .formParam("TemplateName", "name\tprobe\u0001")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo(notAllowed("name0x0009probe0x0001")));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("{\"TemplateName\": \"name\\tprobe\", \"TemplateContent\": {\"Subject\": \"S\", \"Text\": \"t\"}}")
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("message", equalTo(notAllowed("name\tprobe")));
    }

    @Test
    void a64CharacterNameOfEveryAllowedKindIsAccepted() {
        String name = "Name_probe-0123456789" + "a".repeat(43);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", name)
            .formParam("Template.SubjectPart", "S")
            .formParam("Template.TextPart", "t")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/templates/{name}", name)
        .then()
            .statusCode(200)
            .body("TemplateName", equalTo(name));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", V1_AUTH)
            .formParam("Action", "GetTemplate")
            .formParam("TemplateName", name)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<TemplateName>" + name + "</TemplateName>"));

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v2/email/templates/{name}", name)
        .then()
            .statusCode(200);
    }
}
