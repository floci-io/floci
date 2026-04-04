package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class FilterTest {
    @Test
    void testFilterWithQuery() {
        given()
            .header("Host", "my-bucket.localhost:4566")
            .when()
            .put("/")
            .then()
            .statusCode(200);

        String xml = "<Delete><Object><Key>test</Key></Object></Delete>";
        given()
            .header("Host", "my-bucket.localhost:4566")
            .queryParam("delete", "")
            .contentType("application/xml")
            .body(xml)
            .log().all()
            .when()
            .post("/")
            .then()
            .log().all()
            .statusCode(200);
    }
}
