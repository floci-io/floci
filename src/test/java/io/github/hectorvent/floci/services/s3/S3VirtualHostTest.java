package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class S3VirtualHostTest {

    @Test
    void testVirtualHostPostDelete() {
        given()
            .header("Host", "mybucket.s3.amazonaws.com")
        .when()
            .put("/")
        .then()
            .statusCode(200);

        String xml = "<Delete><Object><Key>test</Key></Object></Delete>";
        given()
            .header("Host", "mybucket.s3.amazonaws.com")
            // Empty value adds equal sign in RestAssured: ?delete=
            .queryParam("delete", "")
            .contentType("application/xml")
            .body(xml)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("DeleteResult"));
            
        // Test with raw query string ?delete
        given()
            .header("Host", "mybucket.s3.amazonaws.com")
            .contentType("application/xml")
            .body(xml)
        .when()
            .post("/?delete")
        .then()
            .statusCode(200)
            .body(containsString("DeleteResult"));
            
        // What about path-style ?delete
        given()
            .contentType("application/xml")
            .body(xml)
        .when()
            .post("/mybucket?delete")
        .then()
            .statusCode(200)
            .body(containsString("DeleteResult"));
            
        // Path style with delete=
        given()
            .contentType("application/xml")
            .body(xml)
        .when()
            .post("/mybucket?delete=")
        .then()
            .statusCode(200)
            .body(containsString("DeleteResult"));
    }
}
