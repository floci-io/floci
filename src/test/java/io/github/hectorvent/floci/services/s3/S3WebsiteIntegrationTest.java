package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
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

    private String websiteHost() {
        return BUCKET + ".s3-website-us-east-1.localhost:" + RestAssured.port;
    }

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
            .header("Host", websiteHost())
        .when()
            .get("/")
        .then()
            .statusCode(404)
            .contentType("text/html")
            .header("x-amz-error-code", "NoSuchKey")
            .header("x-amz-error-message", "The specified key does not exist.")
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
            .header("Host", websiteHost())
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .contentType("text/html")
            .body(equalTo("<html><body>Hello Website</body></html>"));
    }

    @Test
    @Order(12)
    void indexServedForRootEvenWithQueryString() {
        // A website endpoint has no S3 REST API, so a query string on the site root (e.g. an SPA
        // OAuth callback GET /?code=...&state=...) must still serve the index document rather than
        // fall through to a ListObjects response.
        given()
            .header("Host", websiteHost())
            .queryParam("code", "abc")
            .queryParam("state", "xyz")
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .contentType("text/html")
            .body(equalTo("<html><body>Hello Website</body></html>"));
    }

    @Test
    @Order(9)
    void missingKeyServesErrorDocument() {
        given()
            .header("Host", websiteHost())
        .when()
            .get("/missing-page")
        .then()
            .statusCode(404)
            .contentType("text/html")
            .header("x-amz-error-code", "NoSuchKey")
            .header("x-amz-error-message", "The specified key does not exist.")
            .body(equalTo("<html><body>Custom Error</body></html>"));
    }

    @Test
    @Order(10)
    void missingKeyReturnsXmlForRegularApiRequest() {
        given()
        .when()
            .get("/" + BUCKET + "/missing-page")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchKey"));
    }

    @Test
    @Order(11)
    void missingErrorDocumentServesHtmlFallback() {
        given().delete("/" + BUCKET + "/error.html");

        given()
            .header("Host", websiteHost())
        .when()
            .get("/missing-page")
        .then()
            .statusCode(404)
            .contentType("text/html")
            .header("x-amz-error-code", "NoSuchKey")
            .header("x-amz-error-message", "The specified key does not exist.")
            .body(containsString("404 Not Found"))
            .body(containsString("NoSuchKey"));

        given()
            .contentType("text/html")
            .body("<html><body>Custom Error</body></html>")
        .when()
            .put("/" + BUCKET + "/error.html")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(14)
    void uploadSubdirectoryIndex() {
        given()
            .contentType("text/html")
            .body("<html><body>Docs Home</body></html>")
        .when()
            .put("/" + BUCKET + "/docs/index.html")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(15)
    void directoryRequestServesIndexDocument() {
        // GET /docs/ on the website endpoint serves docs/index.html (AWS index-document resolution),
        // not the error document.
        given()
            .header("Host", websiteHost())
        .when()
            .get("/docs/")
        .then()
            .statusCode(200)
            .contentType("text/html")
            .body(equalTo("<html><body>Docs Home</body></html>"));
    }

    @Test
    @Order(16)
    void directoryWithoutTrailingSlashRedirects() {
        // GET /docs (a folder, not an object) 302-redirects to /docs/ so the page's relative asset
        // URLs resolve against the correct base — matching real S3 website behavior.
        given()
            .header("Host", websiteHost())
            .redirects().follow(false)
        .when()
            .get("/docs")
        .then()
            .statusCode(302)
            .header("Location", equalTo("/docs/"));
    }

    @Test
    @Order(17)
    void nestedDirectoryServesIndexDocument() {
        given()
            .contentType("text/html")
            .body("<html><body>Nested</body></html>")
        .when()
            .put("/" + BUCKET + "/a/b/index.html")
        .then()
            .statusCode(200);

        given()
            .header("Host", websiteHost())
        .when()
            .get("/a/b/")
        .then()
            .statusCode(200)
            .body(equalTo("<html><body>Nested</body></html>"));
    }

    @Test
    @Order(18)
    void directoryWithoutIndexServesErrorDocument() {
        // A trailing-slash request whose prefix has no index document falls back to the error document.
        given()
            .header("Host", websiteHost())
        .when()
            .get("/no-such-dir/")
        .then()
            .statusCode(404)
            .contentType("text/html")
            .header("x-amz-error-code", "NoSuchKey")
            .body(equalTo("<html><body>Custom Error</body></html>"));
    }

    @Test
    @Order(22)
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
    @Order(23)
    void cleanup() {
        given().delete("/" + BUCKET + "/index.html");
        given().delete("/" + BUCKET + "/error.html");
        given().delete("/" + BUCKET + "/docs/index.html");
        given().delete("/" + BUCKET + "/a/b/index.html");
        given().delete("/" + BUCKET);
    }
}
