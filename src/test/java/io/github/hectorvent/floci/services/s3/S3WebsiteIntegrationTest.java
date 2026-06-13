package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3WebsiteIntegrationTest {

    private static final String BUCKET = "website-test-bucket";

    @Test
    @Order(1)
    void setupBucket() {
        given()
            .put("/" + BUCKET)
            .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void getWebsiteConfigurationMissingReturns404() {
        given()
            .queryParam("website", "")
        .when()
            .get("/" + BUCKET)
        .then()
            .statusCode(404)
            .body(containsString("NoSuchWebsiteConfiguration"));
    }

    @Test
    @Order(3)
    void putWebsiteConfiguration() {
        String xml = """
                <WebsiteConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <IndexDocument>
                        <Suffix>index.html</Suffix>
                    </IndexDocument>
                    <ErrorDocument>
                        <Key>error.html</Key>
                    </ErrorDocument>
                </WebsiteConfiguration>
                """;
        given()
            .queryParam("website", "")
            .contentType("application/xml")
            .body(xml)
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void getWebsiteConfiguration() {
        given()
            .queryParam("website", "")
        .when()
            .get("/" + BUCKET)
        .then()
            .statusCode(200)
            .body(containsString("<IndexDocument><Suffix>index.html</Suffix></IndexDocument>"))
            .body(containsString("<ErrorDocument><Key>error.html</Key></ErrorDocument>"));
    }

    @Test
    @Order(5)
    void uploadErrorFile() {
        given()
            .contentType("text/html")
            .body("<html><body>Custom Error</body></html>")
        .when()
            .put("/" + BUCKET + "/error.html")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void missingIndexServesErrorDocument() {
        given()
        .when()
            .get("/" + BUCKET)
        .then()
            .statusCode(404)
            .contentType("text/html")
            .body(equalTo("<html><body>Custom Error</body></html>"));
    }

    @Test
    @Order(7)
    void uploadIndexFile() {
        given()
            .contentType("text/html")
            .body("<html><body>Hello Website</body></html>")
        .when()
            .put("/" + BUCKET + "/index.html")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(8)
    void indexRedirectionWorks() {
        given()
        .when()
            .get("/" + BUCKET)
        .then()
            .statusCode(200)
            .contentType("text/html")
            .body(equalTo("<html><body>Hello Website</body></html>"));
    }

    @Test
    @Order(9)
    void missingKeyServesErrorDocument() {
        given()
        .when()
            .get("/" + BUCKET + "/missing-page")
        .then()
            .statusCode(404)
            .contentType("text/html")
            .body(equalTo("<html><body>Custom Error</body></html>"));
    }

    @Test
    @Order(10)
    void deleteWebsiteConfiguration() {
        given()
            .queryParam("website", "")
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);

        given()
            .queryParam("website", "")
        .when()
            .get("/" + BUCKET)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(11)
    void cleanup() {
        given().delete("/" + BUCKET + "/index.html");
        given().delete("/" + BUCKET + "/error.html");
        given().delete("/" + BUCKET);
    }
}
