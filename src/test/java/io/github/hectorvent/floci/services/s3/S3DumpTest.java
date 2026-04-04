package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;

@QuarkusTest
class S3DumpTest {
    @Test
    void dump() {
        given()
            .header("Host", "mybucket.s3.amazonaws.com")
        .when()
            .post("/?delete")
        .then()
            .log().all();
    }
}
