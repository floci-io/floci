package io.github.hectorvent.floci.services.apigateway;

import java.util.Optional;

import jakarta.inject.Inject;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ListRestApis renders a snapshot of the APIs and then resolves each one's rootResourceId,
 * which reads that API's resources. An API deleted in between is already gone by the time the
 * second read happens, and the not-found it raises must cost the caller that one member rather
 * than turning the whole listing into a 404.
 */
@QuarkusTest
class ApiGatewayListRestApisConcurrentDeleteTest {

    private static final String REGION = "us-east-1";

    @Inject
    ApiGatewayService service;

    @Test
    void theRootLookupOfAVanishedApiIsEmptyRatherThanNotFound() {
        // Same read the listing performs for each API in its snapshot. The id belongs to no
        // API, which is exactly the state a concurrent DeleteRestApi leaves behind.
        assertEquals(Optional.empty(), service.findRootResourceId(REGION, "nosuchapi"));
    }

    @Test
    void thatToleranceIsNarrowerThanTheReadItGuards() {
        // Only the vanished API is tolerated: reading the resources of a missing API on its
        // own still raises, so nothing else is being swallowed on the way past.
        AwsException e = assertThrows(AwsException.class, () -> service.getResources(REGION, "nosuchapi"));
        assertEquals("NotFoundException", e.getErrorCode());
    }

    @Test
    void anApiThatIsStillThereStillReportsItsRoot() {
        String apiId = given()
            .contentType("application/json")
            .body("{\"name\":\"listing-survivor\"}")
        .when()
            .post("/restapis")
        .then()
            .statusCode(201)
            .extract().path("id");

        assertTrue(service.findRootResourceId(REGION, apiId).isPresent());

        given()
        .when()
            .get("/restapis")
        .then()
            .statusCode(200)
            .body("item.findAll { it.id == '" + apiId + "' }[0].rootResourceId", notNullValue())
            .body("item.findAll { it.id == '" + apiId + "' }[0].name", equalTo("listing-survivor"));
    }
}
