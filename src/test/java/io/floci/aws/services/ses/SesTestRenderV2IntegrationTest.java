package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTestRenderV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void createTemplate() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "v2-render-welcome",
                  "TemplateContent": {
                    "Subject": "Hello {{name}}",
                    "Text": "Hi {{name}}, team {{team}}!",
                    "Html": "<p>Hi <b>{{name}}</b></p>"
                  }
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void testRenderEmailTemplate_substitutesVariables() {
        String rendered = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TemplateData": "{\\"name\\":\\"Alice\\",\\"team\\":\\"floci\\"}"}
                """)
        .when()
            .post("/v2/email/templates/v2-render-welcome/render")
        .then()
            .statusCode(200)
            .extract().path("RenderedTemplate");

        Assertions.assertNotNull(rendered);
        Assertions.assertTrue(rendered.contains("Subject: Hello Alice"));
        Assertions.assertTrue(rendered.contains("Hi Alice, team floci!"));
        Assertions.assertTrue(rendered.contains("multipart/alternative"));
    }

    @Test
    @Order(3)
    void testRenderEmailTemplate_unknownTemplate_returns404() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TemplateData": "{}"}
                """)
        .when()
            .post("/v2/email/templates/ghost/render")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("Unable to find <ghost> for <000000000000>"));
    }

    @Test
    @Order(6)
    void testRenderEmailTemplate_unknownTemplateWithMalformedData_reportsTheData() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TemplateData": "[1]"}
                """)
        .when()
            .post("/v2/email/templates/ghost/render")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Template rendering data is invalid"));
    }

    @Test
    @Order(4)
    void testRenderEmailTemplate_invalidJson_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TemplateData": "{not json"}
                """)
        .when()
            .post("/v2/email/templates/v2-render-welcome/render")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Template rendering data is invalid"));
    }

    @Test
    @Order(5)
    void testRenderEmailTemplate_missingVariable_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TemplateData": "{\\"name\\":\\"Alice\\"}"}
                """)
        .when()
            .post("/v2/email/templates/v2-render-welcome/render")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRenderBodies")
    @Order(7)
    void testRenderEmailTemplate_malformedBody_returns400(String label, String body) {
        RequestSpecification spec = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER);
        if (body != null) {
            spec = spec.body(body);
        }
        spec
        .when()
            .post("/v2/email/templates/v2-render-welcome/render")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    static Stream<Arguments> malformedRenderBodies() {
        return Stream.of(
                Arguments.of("null body", null),
                Arguments.of("TemplateData as object", "{\"TemplateData\": {\"name\": \"Alice\"}}")
        );
    }

    @Test
    @Order(8)
    void testRenderEmailTemplate_arrayBody_isASerializationException() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("[1,2,3]")
        .when()
            .post("/v2/email/templates/v2-render-welcome/render")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("Start of list found where not expected"));
    }

    @Test
    @Order(10)
    void testRenderEmailTemplate_dateHeaderIsUtc() {
        String rendered = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TemplateData": "{\\"name\\":\\"Alice\\",\\"team\\":\\"floci\\"}"}
                """)
        .when()
            .post("/v2/email/templates/v2-render-welcome/render")
        .then()
            .statusCode(200)
            .extract().path("RenderedTemplate");

        String dateLine = rendered.split("\r\n", 2)[0];
        Assertions.assertTrue(
                dateLine.matches("Date: \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "expected an ISO-8601 UTC Date header with milliseconds, got: " + dateLine);
    }

    @Test
    @Order(11)
    void testRenderEmailTemplate_utf8Body_isBase64WithAnEncodedSubject() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "v2-render-jp",
                  "TemplateContent": {
                    "Subject": "件名 {{name}}",
                    "Text": "こんにちは {{name}} さん"
                  }
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(200);

        String rendered = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TemplateData": "{\\"name\\":\\"太郎\\"}"}
                """)
        .when()
            .post("/v2/email/templates/v2-render-jp/render")
        .then()
            .statusCode(200)
            .extract().path("RenderedTemplate");

        Assertions.assertTrue(rendered.contains("Subject: =?UTF-8?B?5Lu25ZCNIOWkqumDjg==?=\r\n"));
        // A template with only a text part is a single-part message, not multipart.
        Assertions.assertTrue(rendered.endsWith("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n44GT44KT44Gr44Gh44GvIOWkqumDjiDjgZXjgpM="));
    }
}
