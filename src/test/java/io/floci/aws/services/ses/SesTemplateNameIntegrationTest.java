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

/**
 * The template name rules, as probed on us-west-2: blank, then longer than 64 characters, then a
 * character outside ASCII letters, digits, '_' and '-'. Create, update, get, delete and test-render
 * all answer them, on both APIs, before the name is looked up; the other lookups do not apply them.
 */
@QuarkusTest
class SesTemplateNameIntegrationTest {

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
                .header("Authorization", SesV1Requests.AUTH)
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
                .header("Authorization", SesV1Requests.AUTH)
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
            .header("Authorization", SesV2Requests.AUTH)
            .body("{\"TemplateName\": \"" + name + "\", \"TemplateContent\": {\"Subject\": \"S\", \"Text\": \"t\"}}")
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));

        SesV2Requests.v2()
            .body("{\"TemplateContent\": {\"Subject\": \"S\", \"Text\": \"t\"}}")
        .when()
            .put("/v2/email/templates/{name}", name)
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));

        given()
            .header("Authorization", SesV2Requests.AUTH)
        .when()
            .get("/v2/email/templates/{name}", name)
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));

        given()
            .header("Authorization", SesV2Requests.AUTH)
        .when()
            .delete("/v2/email/templates/{name}", name)
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));

        SesV2Requests.v2()
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
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "name probe")
            .formParam("Template.TextPart", "t")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo(notAllowed("name probe")));

        SesV2Requests.v2()
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
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "GetTemplate")
            .formParam("TemplateName", "name\tprobe\u0001")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", equalTo(notAllowed("name0x0009probe0x0001")));

        SesV2Requests.v2()
            .body("{\"TemplateName\": \"name\\tprobe\", \"TemplateContent\": {\"Subject\": \"S\", \"Text\": \"t\"}}")
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("message", equalTo(notAllowed("name\tprobe")));
    }

    // not probed: U+FFFE and U+FFFF cannot appear in an XML 1.0 document, so v1 spells them out too;
    // without the UTF-8 charset RestAssured sends them as '?'
    @Test
    void v1SpellsOutACharacterXmlForbids() {
        SesV1Requests.query("GetTemplate")
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .formParam("TemplateName", "name\uFFFEprobe\uFFFF")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo(notAllowed("name0xfffeprobe0xffff")));
    }

    @Test
    void a64CharacterNameOfEveryAllowedKindIsAccepted() {
        String name = "Name_probe-0123456789" + "a".repeat(43);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", name)
            .formParam("Template.SubjectPart", "S")
            .formParam("Template.TextPart", "t")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", SesV2Requests.AUTH)
        .when()
            .get("/v2/email/templates/{name}", name)
        .then()
            .statusCode(200)
            .body("TemplateName", equalTo(name));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "GetTemplate")
            .formParam("TemplateName", name)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<TemplateName>" + name + "</TemplateName>"));

        given()
            .header("Authorization", SesV2Requests.AUTH)
        .when()
            .delete("/v2/email/templates/{name}", name)
        .then()
            .statusCode(200);
    }
}
