package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
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

        org.junit.jupiter.api.Assertions.assertNotNull(rendered);
        org.junit.jupiter.api.Assertions.assertTrue(rendered.contains("Subject: Hello Alice"));
        org.junit.jupiter.api.Assertions.assertTrue(rendered.contains("Hi Alice, team floci!"));
        org.junit.jupiter.api.Assertions.assertTrue(rendered.contains("multipart/alternative"));
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
            .body("__type", equalTo("NotFoundException"));
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
            .body("__type", equalTo("BadRequestException"));
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
                Arguments.of("non-object body (array)", "[1,2,3]"),
                Arguments.of("TemplateData as object", "{\"TemplateData\": {\"name\": \"Alice\"}}")
        );
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

        // RFC 1123 with UTC ends with "GMT"; non-UTC zones would render numeric offsets
        org.junit.jupiter.api.Assertions.assertTrue(
                rendered.contains("Date: ") && rendered.split("\r\n", 2)[0].endsWith("GMT"),
                "expected Date header in UTC/GMT form, got: " + rendered.split("\r\n", 2)[0]);
    }

    @Test
    @Order(11)
    void testRenderEmailTemplate_utf8Body_uses8bitEncoding() {
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

        org.junit.jupiter.api.Assertions.assertTrue(rendered.contains("Subject: 件名 太郎"));
        org.junit.jupiter.api.Assertions.assertTrue(rendered.contains("Content-Transfer-Encoding: 8bit"));
        org.junit.jupiter.api.Assertions.assertTrue(rendered.contains("こんにちは 太郎 さん"));
    }
}
