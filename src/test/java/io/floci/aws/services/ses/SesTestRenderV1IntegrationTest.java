package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTestRenderV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";

    @Test
    @Order(1)
    void createTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "v1-render-welcome")
            .formParam("Template.SubjectPart", "Hello {{name}}")
            .formParam("Template.TextPart", "Hi {{name}}, team {{team}}!")
            .formParam("Template.HtmlPart", "<p>Hi <b>{{name}}</b></p>")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void testRenderTemplate_substitutesVariables() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "TestRenderTemplate")
            .formParam("TemplateName", "v1-render-welcome")
            .formParam("TemplateData", "{\"name\":\"Alice\",\"team\":\"floci\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<TestRenderTemplateResponse"))
            .body(containsString("<RenderedTemplate>"))
            .body(containsString("Subject: Hello Alice"))
            .body(containsString("Hi Alice, team floci!"))
            .body(containsString("multipart/alternative"));
    }

    @Test
    @Order(3)
    void testRenderTemplate_unknownTemplate_returnsError() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "TestRenderTemplate")
            .formParam("TemplateName", "ghost")
            .formParam("TemplateData", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("TemplateDoesNotExist"));
    }

    @Test
    @Order(4)
    void testRenderTemplate_invalidJson_returnsInvalidRenderingParameter() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "TestRenderTemplate")
            .formParam("TemplateName", "v1-render-welcome")
            .formParam("TemplateData", "{not json")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRenderingParameter"));
    }

    @Test
    @Order(5)
    void testRenderTemplate_missingVariable_returnsMissingRenderingAttribute() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "TestRenderTemplate")
            .formParam("TemplateName", "v1-render-welcome")
            .formParam("TemplateData", "{\"name\":\"Alice\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("MissingRenderingAttribute"));
    }

    @Test
    @Order(7)
    void testRenderTemplate_routedViaActionFallback_whenAuthHeaderAbsent() {
        // Exercises AwsQueryController.inferServiceFromAction → SES_ACTIONS dispatch
        // when no Authorization header is present (no service scope to resolve).
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "TestRenderTemplate")
            .formParam("TemplateName", "v1-render-welcome")
            .formParam("TemplateData", "{\"name\":\"Alice\",\"team\":\"floci\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<TestRenderTemplateResponse"))
            .body(containsString("Subject: Hello Alice"));
    }

    @Test
    @Order(8)
    void testRenderTemplate_utf8Body_uses8bitEncoding() {
        given()
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "v1-render-jp")
            .formParam("Template.SubjectPart", "件名 {{name}}")
            .formParam("Template.TextPart", "こんにちは {{name}} さん")
            .formParam("Template.HtmlPart", "<p>こんにちは {{name}} さん</p>")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .header("Authorization", AUTH)
            .formParam("Action", "TestRenderTemplate")
            .formParam("TemplateName", "v1-render-jp")
            .formParam("TemplateData", "{\"name\":\"太郎\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("Subject: 件名 太郎"))
            .body(containsString("Content-Transfer-Encoding: 8bit"))
            .body(containsString("こんにちは 太郎 さん"));
    }
}
