package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class RedshiftDataRegistrationIntegrationTest {

    private static final String TARGET = "X-Amz-Target";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void redshiftDataTargetReachesTheHandler() {
        // The stub service throws ValidationException("... not implemented yet");
        // the point is that it is NOT an UnknownOperationException from the catalog.
        given()
            .contentType("application/x-amz-json-1.1")
            .header(TARGET, "RedshiftData.ExecuteStatement")
            .body("{\"Sql\":\"select 1\",\"ClusterIdentifier\":\"none\",\"DbUser\":\"admin\",\"Database\":\"dev\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ValidationException"))
            .body("__type", not(containsString("UnknownOperation")));
    }

    @Test
    void deprecatedExecuteSqlIsRejected() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header(TARGET, "RedshiftData.ExecuteSql")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ValidationException"));
    }
}
